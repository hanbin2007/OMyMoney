package app.omymoney.data.backup

import android.content.Context
import android.graphics.BitmapFactory
import androidx.room.withTransaction
import app.omymoney.data.*
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject

data class ArchiveLimits(val maxJsonBytes: Long = 16L * 1024 * 1024, val maxTotalBytes: Long = 512L * 1024 * 1024, val maxEntries: Int = 10_000)
@Serializable data class BackupSummary(val books: Int, val accounts: Int, val bills: Int, val attachments: Int,
    val scheduledPlans: Int = 0, val automaticPlans: Int = 0, val pendingExecutions: Int = 0,
    val savingsPlans: Int = 0, val savingsDeposits: Int = 0, val importReceipts: Int = 0, val batchActions: Int = 0)
enum class ConflictKind { BOOK, ACCOUNT, CATEGORY, BILL, POSTING, SUBMISSION, DELETION, DRAFT, QUEUE, BALANCE_OVERFLOW, LINKED_RULE, EXCHANGE_RATE, BUDGET, SCHEDULE, SCHEDULE_EXECUTION, SAVINGS_PLAN, SAVINGS_DEPOSIT, IMPORT_RECEIPT, BATCH_ACTION }
data class RestoreConflict(val kind: ConflictKind, val recordId: String, val localLabel: String, val backupLabel: String)
data class RestorePreview(val previewId: String, val summary: BackupSummary, val conflicts: List<RestoreConflict>, val createdAt: String)
enum class RestoreMode { MERGE, REPLACE }
data class RestoreResult(val summary: BackupSummary, val recoveryAvailable: Boolean)
class BackupException(val code: String, cause: Throwable? = null) : Exception(code, cause)

@Serializable private data class ArchiveFile(val path: String, val size: Long, val sha256: String)
@Serializable private data class ArchiveManifest(val schemaVersion: Int, val createdAt: String, val files: List<ArchiveFile>)
@Serializable private data class PreviewMetadata(val createdAt: String, val inspectedAt: String, val dataHash: String, val baseHash: String)

class BackupArchive(private val database: LedgerDatabase, context: Context,
    private val limits: ArchiveLimits = ArchiveLimits(), private val clock: Clock = Clock.systemUTC()) {
    private val app = context.applicationContext
    private val directory = File(app.noBackupFilesDir, "backup-previews")
    private val recovery = File(app.noBackupFilesDir, "before-replace.zip")
    private val attachments = AttachmentStore(app)
    private val dao = database.ledgerDao()
    private val json = LedgerJson.format

    suspend fun exportTo(output: OutputStream): BackupSummary = guarded {
        val snapshot = database.withTransaction { snapshot() }
        val temporary = File(app.cacheDir, "export-${UUID.randomUUID()}.zip")
        try {
            temporary.outputStream().use { writeArchive(snapshot, it) }
            temporary.inputStream().use { input -> copyLimited(input, output, limits.maxTotalBytes + limits.maxJsonBytes) }
            output.flush()
            snapshot.summary()
        } finally { temporary.delete() }
    }

    suspend fun inspect(input: InputStream): RestorePreview = guarded {
        directory.mkdirs()
        directory.listFiles()?.filter { it.isDirectory && clock.millis() - it.lastModified() > PREVIEW_LIFETIME.toMillis() }
            ?.forEach { it.deleteRecursively() }
        val id = UUID.randomUUID().toString()
        val staging = File(directory, id)
        RestoreValidator.check(staging.mkdir(), "storage")
        try {
            unpack(input, staging)
            val (data, manifest) = readValidated(staging)
            val base = database.withTransaction { snapshot() }
            val meta = PreviewMetadata(manifest.createdAt, clock.instant().toString(), snapshotHash(data), snapshotHash(base))
            File(staging, "preview-meta.json").writeText(json.encodeToString(meta))
            RestorePreview(id, data.summary(), RestoreValidator.merge(base, data).conflicts, manifest.createdAt)
        } catch (error: Throwable) { staging.deleteRecursively(); throw error }
    }

    suspend fun preview(previewId: String): RestorePreview = guarded {
        val staging = previewDirectory(previewId)
        val meta = metadata(staging)
        val data = readValidated(staging).first
        RestoreValidator.check(snapshotHash(data) == meta.dataHash, "preview_changed")
        val current = database.withTransaction { snapshot() }
        RestorePreview(previewId, data.summary(), RestoreValidator.merge(current, data).conflicts, meta.createdAt)
    }

    suspend fun restore(previewId: String, mode: RestoreMode): RestoreResult = guarded {
        val staging = previewDirectory(previewId)
        val meta = metadata(staging)
        val incoming = readValidated(staging).first
        RestoreValidator.check(snapshotHash(incoming) == meta.dataHash, "preview_changed")
        database.withTransaction {
            val current = snapshot()
            // Refuse a replacement confirmed against data that has since changed.
            RestoreValidator.check(snapshotHash(current) == meta.baseHash, "stale_preview")
            val result = if (mode == RestoreMode.REPLACE) incoming else {
                val (merged, conflicts) = RestoreValidator.merge(current, incoming)
                RestoreValidator.check(conflicts.isEmpty(), "merge_conflict")
                requireNotNull(merged)
            }
            RestoreValidator.validate(result)
            installAttachments(staging, current, incoming)
            if (mode == RestoreMode.REPLACE) {
                val next = File(recovery.parentFile, "before-replace.part")
                try {
                    next.outputStream().use { output -> writeArchive(current, output); output.fd.sync() }
                    RestoreValidator.check(next.renameTo(recovery), "storage")
                } finally { next.delete() }
            }
            replaceRows(result)
            RestoreResult(result.summary(), recovery.isFile)
        }.also { staging.deleteRecursively() }
    }

    private suspend fun installAttachments(staging: File, current: LedgerSnapshot, incoming: LedgerSnapshot) {
        val currentRefs = current.refs().associateBy { it.id }
        incoming.refs().forEach { ref ->
            val existing = currentRefs[ref.id]
            RestoreValidator.check(existing == null || existing == ref, "attachment_conflict")
            val target = File(app.filesDir, ref.relativePath)
            if (target.exists()) {
                try { attachments.verifiedFile(ref); return@forEach }
                catch (error: AttachmentException) {
                    // Only repair known corruption. Unknown historical files remain immutable.
                    RestoreValidator.check(existing == ref && error.code == "corrupt", "attachment_conflict")
                }
                val quarantine = File(app.noBackupFilesDir, "attachment-quarantine/${ref.id}-${UUID.randomUUID()}.bin")
                RestoreValidator.check(quarantine.parentFile!!.isDirectory || quarantine.parentFile!!.mkdirs(), "storage")
                target.inputStream().use { source -> quarantine.outputStream().use { output ->
                    copyLimited(source, output, limits.maxTotalBytes); output.fd.sync()
                } }
            }
            RestoreValidator.check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs(), "storage")
            val part = File(target.parentFile, "${ref.id}.restore-part")
            try {
                File(staging, ref.relativePath).inputStream().use { source -> part.outputStream().use { output ->
                    copyLimited(source, output, AttachmentStore.MAX_BYTES); output.fd.sync()
                } }
                // Atomic replacement restores the bytes already expected by old rows, even on rollback.
                RestoreValidator.check(part.renameTo(target), "storage")
            } finally { part.delete() }
        }
    }

    suspend fun cancel(previewId: String) = guarded { previewDirectory(previewId, mustExist = false).deleteRecursively(); Unit }
    suspend fun hasRecovery(): Boolean = withContext(Dispatchers.IO) { recovery.isFile }
    suspend fun exportRecovery(output: OutputStream) = guarded {
        RestoreValidator.check(recovery.isFile, "no_recovery")
        recovery.inputStream().use { copyLimited(it, output, limits.maxTotalBytes + limits.maxJsonBytes) }
        output.flush()
    }

    private suspend fun snapshot() = LedgerSnapshot(dao.books(), dao.accounts(), dao.allCategories(), dao.allBills(),
        dao.allPostings(), dao.allSubmissions(), dao.allDeletions(), dao.allPreferences().filterNot { it.key.startsWith(RecognitionQueue.CAPTURE_WINDOW_PREFIX) }, dao.allDrafts(), dao.allQueueEntries(), dao.allExchangeRates(), dao.allBudgets(),
        dao.allSchedules(), dao.allScheduleExecutions(), dao.allSavingsPlans(), dao.allSavingsDeposits(), dao.allImportReceipts(), dao.allBatchActions()).normalized()

    private suspend fun replaceRows(data: LedgerSnapshot) {
        dao.clearImportReceipts(); dao.clearBatchActions()
        dao.clearSavingsDeposits(); dao.clearSavingsPlans()
        dao.clearScheduleExecutions(); dao.clearSchedules(); dao.clearBudgets(); dao.clearExchangeRates(); dao.clearQueue(); dao.clearDrafts(); dao.clearPreferences(); dao.clearBills(); dao.clearChildCategories()
        dao.clearCategories(); dao.clearAccounts(); dao.clearBooks()
        data.books.forEach { dao.upsertBook(it) }; data.accounts.forEach { dao.upsertAccount(it) }
        data.categories.sortedBy { it.parentId != null }.forEach { dao.upsertCategory(it) }
        data.bills.forEach { dao.upsertBill(it) }; dao.insertPostings(data.postings)
        data.submissions.forEach { dao.insertSubmission(it) }; data.deletions.forEach { dao.upsertDeletion(it) }
        data.preferences.forEach { dao.upsertPreference(it) }; data.drafts.forEach { dao.upsertDraft(it) }
        data.queue.forEach { dao.upsertQueueEntry(it) }
        data.exchangeRates.forEach { dao.upsertExchangeRate(it) }
        data.budgets.forEach { dao.upsertBudget(it) }
        data.schedules.forEach { dao.upsertSchedule(it) }
        data.scheduleExecutions.forEach { dao.upsertScheduleExecution(it) }
        data.savingsPlans.forEach { dao.upsertSavingsPlan(it) }
        data.savingsDeposits.forEach { dao.upsertSavingsDeposit(it) }
        data.importReceipts.forEach { dao.insertImportReceipt(it) }
        data.batchActions.forEach { dao.upsertBatchAction(it) }
    }

    private suspend fun writeArchive(data: LedgerSnapshot, output: OutputStream) {
        RestoreValidator.validate(data)
        val bytes = json.encodeToString(data).toByteArray()
        RestoreValidator.check(bytes.size <= limits.maxJsonBytes, "size_limit")
        val files = data.refs().map { it to attachments.verifiedFile(it) }
        val entries = listOf(ArchiveFile("data.json", bytes.size.toLong(), hash(bytes))) + files.map { (ref, file) -> ArchiveFile(ref.relativePath, file.length(), ref.sha256) }
        RestoreValidator.check(entries.size + 1 <= limits.maxEntries && entries.sumOf { it.size } <= limits.maxTotalBytes, "size_limit")
        val manifest = ArchiveManifest(SCHEMA_VERSION, clock.instant().toString(), entries)
        val manifestBytes = json.encodeToString(manifest).toByteArray()
        RestoreValidator.check(manifestBytes.size <= limits.maxJsonBytes && entries.sumOf { it.size } + manifestBytes.size <= limits.maxTotalBytes, "size_limit")
        // Finish the ZIP without closing a caller-owned stream (including recovery fsync).
        ZipOutputStream(object : FilterOutputStream(output) { override fun close() { flush() } }).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifestBytes); zip.closeEntry()
            zip.putNextEntry(ZipEntry("data.json")); zip.write(bytes); zip.closeEntry()
            files.forEach { (ref, file) ->
                zip.putNextEntry(ZipEntry(ref.relativePath))
                file.inputStream().use { copyLimited(it, zip, AttachmentStore.MAX_BYTES) }; zip.closeEntry()
            }
            zip.finish(); zip.flush()
        }
    }

    private suspend fun unpack(input: InputStream, staging: File) {
        var total = 0L
        val paths = mutableSetOf<String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                val path = entry.name
                RestoreValidator.check(path == "manifest.json" || path == "data.json" || RestoreValidator.attachmentPath.matches(path), "unsafe_path")
                RestoreValidator.check(!entry.isDirectory && paths.add(path), "duplicate_path")
                RestoreValidator.check(paths.size <= limits.maxEntries, "size_limit")
                val target = File(staging, path)
                RestoreValidator.check(target.canonicalPath.startsWith(staging.canonicalPath + File.separator), "unsafe_path")
                target.parentFile!!.mkdirs()
                val max = if (path.endsWith(".json")) limits.maxJsonBytes else AttachmentStore.MAX_BYTES
                target.outputStream().use { output -> total += copyLimited(zip, output, minOf(max, limits.maxTotalBytes - total)) }
                zip.closeEntry()
            }
        }
    }

    private suspend fun readValidated(staging: File): Pair<LedgerSnapshot, ArchiveManifest> {
        RestoreValidator.check(File(staging, "manifest.json").length() <= limits.maxJsonBytes && File(staging, "data.json").length() <= limits.maxJsonBytes, "size_limit")
        val manifest = json.decodeFromString<ArchiveManifest>(File(staging, "manifest.json").readText())
        RestoreValidator.check(manifest.schemaVersion in 1..SCHEMA_VERSION, "unsupported_schema")
        Instant.parse(manifest.createdAt)
        val paths = manifest.files.map { it.path }
        RestoreValidator.check(paths.size == paths.toSet().size && paths.size + 1 <= limits.maxEntries, "duplicate_path")
        RestoreValidator.check("data.json" in paths && paths.all { it == "data.json" || RestoreValidator.attachmentPath.matches(it) }, "unsafe_path")
        val actual = staging.walkTopDown().filter { it.isFile }.map { it.relativeTo(staging).invariantSeparatorsPath }.toSet() - "preview-meta.json"
        RestoreValidator.check(actual == paths.toSet() + "manifest.json", "manifest_mismatch")
        var total = File(staging, "manifest.json").length()
        manifest.files.forEach { entry ->
            val file = File(staging, entry.path)
            val max = if (entry.path == "data.json") limits.maxJsonBytes else AttachmentStore.MAX_BYTES
            total += file.length()
            RestoreValidator.check(file.length() <= max && total <= limits.maxTotalBytes, "size_limit")
            RestoreValidator.check(entry.size >= 0 && file.length() == entry.size && hash(file) == entry.sha256, "checksum")
        }
        val dataText = File(staging, "data.json").readText()
        val fields = json.parseToJsonElement(dataText).jsonObject
        RestoreValidator.check(if (manifest.schemaVersion == 1) "queue" !in fields else "queue" in fields, "schema_mismatch")
        val data = json.decodeFromString<LedgerSnapshot>(dataText).normalized()
        RestoreValidator.check(manifest.schemaVersion >= 3 || data.accounts.all { it.creditTermsJson == null }, "schema_mismatch")
        RestoreValidator.check(if (manifest.schemaVersion >= 4) "exchangeRates" in fields else
            data.exchangeRates.isEmpty() && data.bills.all { LedgerJson.decodeBill(it).let { bill -> bill.exchange == null && bill.fee == null } }, "schema_mismatch")
        RestoreValidator.check(if (manifest.schemaVersion >= 5) "budgets" in fields else data.budgets.isEmpty(), "schema_mismatch")
        RestoreValidator.check(if (manifest.schemaVersion >= 6) "schedules" in fields && "scheduleExecutions" in fields
            else data.schedules.isEmpty() && data.scheduleExecutions.isEmpty(), "schema_mismatch")
        RestoreValidator.check(if (manifest.schemaVersion >= 7) "savingsPlans" in fields && "savingsDeposits" in fields
            else data.savingsPlans.isEmpty() && data.savingsDeposits.isEmpty(), "schema_mismatch")
        RestoreValidator.check(if (manifest.schemaVersion >= 8) "importReceipts" in fields && "batchActions" in fields
            else data.importReceipts.isEmpty() && data.batchActions.isEmpty(), "schema_mismatch")
        RestoreValidator.validate(data)
        val refs = data.refs()
        RestoreValidator.check(paths.toSet() == refs.map { it.relativePath }.toSet() + "data.json", "manifest_mismatch")
        val byPath = manifest.files.associateBy { it.path }
        refs.forEach { ref ->
            RestoreValidator.check(byPath[ref.relativePath]?.sha256 == ref.sha256, "checksum")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(File(staging, ref.relativePath).path, bounds)
            RestoreValidator.check(bounds.outMimeType == ref.mimeType && bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outWidth.toLong() * bounds.outHeight <= AttachmentStore.MAX_PIXELS, "invalid_image")
        }
        return data to manifest
    }

    private fun previewDirectory(id: String, mustExist: Boolean = true): File {
        RestoreValidator.check(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false), "invalid_preview")
        return File(directory, id).also { RestoreValidator.check(!mustExist || it.isDirectory, "invalid_preview") }
    }
    private fun metadata(staging: File): PreviewMetadata = json.decodeFromString<PreviewMetadata>(File(staging, "preview-meta.json").readText()).also {
        RestoreValidator.check(Duration.between(Instant.parse(it.inspectedAt), clock.instant()) in Duration.ZERO..PREVIEW_LIFETIME, "expired_preview")
    }
    private fun snapshotHash(data: LedgerSnapshot) = hash(json.encodeToString(data.normalized()).toByteArray())
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private suspend fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(32 * 1024); while (true) {
            currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
        } }
        return digest.digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private suspend fun copyLimited(input: InputStream, output: OutputStream, max: Long): Long {
        val buffer = ByteArray(32 * 1024); var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer); if (count < 0) break
            total += count; RestoreValidator.check(total <= max, "size_limit"); output.write(buffer, 0, count)
        }
        return total
    }
    private suspend fun <T> guarded(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            try { block() } catch (error: CancellationException) { throw error
            } catch (error: BackupException) { throw error
            } catch (error: Exception) { throw BackupException("invalid_archive_or_storage", error) }
        }
    }
    companion object {
        const val SCHEMA_VERSION = 9
        private val PREVIEW_LIFETIME = Duration.ofHours(24)
        private val operationMutex = Mutex()
    }
}
