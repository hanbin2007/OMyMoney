package app.omymoney.data

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.omymoney.core.model.*
import app.omymoney.data.backup.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var source: LedgerDatabase
    private lateinit var target: LedgerDatabase
    private lateinit var archive: BackupArchive
    private lateinit var receiver: BackupArchive
    private lateinit var draft: TransactionDraft
    private lateinit var image: AttachmentRef
    @Before fun setup() = runBlocking {
        source = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        target = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        archive = BackupArchive(source, context)
        receiver = BackupArchive(target, context)
        val bytes = ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff267053.toInt()) }
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }.toByteArray()
        image = AttachmentStore(context).importImage(bytes.inputStream())
        draft = CatalogRepository(source).initialDraft().copy(amount = Money(1234, CurrencySpec.CNY),
            attachments = listOf(image), note = "backup", tags = listOf("work"))
        assertTrue(RoomLedgerRepository(source).save(draft, null) is SaveResult.Saved)
        DraftRepository(source).write(EditorDraft("manual/${draft.bookId}", draft.copy(id = null), amountText = "1..2"))
    }
    @After fun cleanup() { source.close(); target.close() }

    @Test fun roundTripPreservesAllRowsAndAttachmentDigest() = runBlocking {
        val bytes = export(archive)
        val preview = receiver.inspect(bytes.inputStream())
        assertEquals(1, preview.summary.bills)
        assertEquals(1, preview.summary.attachments)
        assertTrue(preview.conflicts.isEmpty())
        assertTrue(AttachmentStore(context).verifiedFile(image).delete())
        receiver.restore(preview.previewId, RestoreMode.REPLACE)
        assertEquals(entries(bytes)["data.json"]!!.decodeToString(), entries(export(receiver))["data.json"]!!.decodeToString())
        assertEquals(image.sha256, hash(AttachmentStore(context).verifiedFile(image).readBytes()))
        assertEquals("1..2", DraftRepository(target).read("manual/${draft.bookId}")!!.amountText)
        assertEquals(-1234L, target.ledgerDao().account(draft.fromAccountId!!)!!.balanceMinor)
    }

    @Test fun cancelAndUnconfirmedPreviewLeaveExistingRowsUntouched() = runBlocking {
        CatalogRepository(target).ensureLocalDefaults()
        val before = export(receiver)
        val preview = receiver.inspect(export(archive).inputStream())
        assertDataEqual(before, export(receiver))
        receiver.cancel(preview.previewId)
        rejected { receiver.restore(preview.previewId, RestoreMode.REPLACE) }
        assertDataEqual(before, export(receiver))
    }

    @Test fun archiveVersionOneMigratesToAnEmptyRecognitionQueue() = runBlocking {
        val original = entries(export(archive))
        val data = Json.parseToJsonElement(original.getValue("data.json").decodeToString()).jsonObject
        val withoutQueue = withData(original, data - "queue")
        val manifest = Json.parseToJsonElement(withoutQueue.getValue("manifest.json").decodeToString()).jsonObject
        val legacy = withoutQueue + ("manifest.json" to JsonObject(manifest + ("schemaVersion" to JsonPrimitive(1))).toString().toByteArray())
        receiver.restore(receiver.inspect(zip(legacy).inputStream()).previewId, RestoreMode.REPLACE)
        assertTrue(RecognitionQueue(target).entries().isEmpty())
        assertEquals(1, target.ledgerDao().billCount())
        assertEquals("1..2", DraftRepository(target).read("manual/${draft.bookId}")!!.amountText)
    }

    @Test fun archiveVersionTwoMigratesMissingCreditTermsButRejectsNewSettingsWithOldVersion() = runBlocking {
        val original = entries(export(archive))
        val data = Json.parseToJsonElement(original.getValue("data.json").decodeToString()).jsonObject
        val accounts = data.getValue("accounts").jsonArray.map { JsonObject(it.jsonObject - "creditTermsJson") }
        val withoutTerms = withData(original, data + ("accounts" to JsonArray(accounts)))
        val manifest = Json.parseToJsonElement(withoutTerms.getValue("manifest.json").decodeToString()).jsonObject
        val legacy = withoutTerms + ("manifest.json" to JsonObject(manifest + ("schemaVersion" to JsonPrimitive(2))).toString().toByteArray())
        receiver.restore(receiver.inspect(zip(legacy).inputStream()).previewId, RestoreMode.REPLACE)
        assertEquals(null, CatalogRepository(target).observeAccounts().first().single().creditTerms)
        val terms = LedgerJson.format.encodeToString(CreditTerms.serializer(), CreditTerms(31, CreditDueMode.DAY_OF_MONTH, 5))
        val withTerms = accounts.map { JsonObject(it + mapOf("kind" to JsonPrimitive("CREDIT"), "creditTermsJson" to JsonPrimitive(terms))) }
        rejected { receiver.inspect(zip(withData(legacy, data + ("accounts" to JsonArray(withTerms)))).inputStream()) }
        val invalidTerms = LedgerJson.format.encodeToString(CreditTerms.serializer(), CreditTerms(32, CreditDueMode.DAY_OF_MONTH, 0))
        val invalid = accounts.map { JsonObject(it + mapOf("kind" to JsonPrimitive("CREDIT"), "creditTermsJson" to JsonPrimitive(invalidTerms))) }
        rejected { receiver.inspect(zip(withData(original, data + ("accounts" to JsonArray(invalid)))).inputStream()) }
    }

    @Test fun rejectsMalformedSchemaDuplicatesReferencesBalancesAndUnsafePaths() = runBlocking {
        CatalogRepository(target).ensureLocalDefaults()
        val before = export(receiver)
        val valid = entries(export(archive))
        val data = Json.parseToJsonElement(valid.getValue("data.json").decodeToString()).jsonObject
        val manifest = Json.parseToJsonElement(valid.getValue("manifest.json").decodeToString()).jsonObject
        val invalid = listOf(
            valid + ("data.json" to "{".toByteArray()),
            valid + ("manifest.json" to JsonObject(manifest + ("schemaVersion" to JsonPrimitive(999))).toString().toByteArray()),
            withData(valid, data + ("books" to JsonArray(data.getValue("books").jsonArray.toList().let { it + it.first() }))),
            withData(valid, data + ("accounts" to JsonArray(emptyList()))),
            withData(valid, data + ("postings" to JsonArray(emptyList()))),
            valid + ("../escape" to byteArrayOf(1)),
            valid + ("/absolute" to byteArrayOf(1)),
            valid + ("attachments/../escape.png" to byteArrayOf(1)),
            valid + ("unlisted.txt" to byteArrayOf(1)),
            valid + (image.relativePath to byteArrayOf(1, 2, 3)),
        )
        invalid.forEachIndexed { index, entries ->
            try { receiver.inspect(zip(entries).inputStream()); fail("Accepted malformed archive $index") }
            catch (_: BackupException) { }
            assertDataEqual(before, export(receiver))
        }
    }

    @Test fun decompressionAndEntryLimitsAreEnforced() = runBlocking {
        val tiny = BackupArchive(target, context, ArchiveLimits(maxJsonBytes = 32, maxTotalBytes = 64, maxEntries = 2))
        val valid = export(archive)
        rejected { tiny.inspect(valid.inputStream()) }
        val repeated = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use {
            it.putNextEntry(ZipEntry("data.json")); it.write(ByteArray(1024 * 1024)); it.closeEntry()
        } }.toByteArray()
        rejected { tiny.inspect(repeated.inputStream()) }
        assertEquals(0, target.ledgerDao().billCount())
    }

    @Test fun mergeIsIdempotentButNeverOverwritesConflictingBills() = runBlocking {
        receiver.restore(receiver.inspect(export(archive).inputStream()).previewId, RestoreMode.MERGE)
        receiver.restore(receiver.inspect(export(archive).inputStream()).previewId, RestoreMode.MERGE)
        assertEquals(1, target.ledgerDao().billCount())
        val bill = RoomLedgerRepository(source).observeBills(BillQuery()).first().single()
        assertTrue(RoomLedgerRepository(source).save(bill.toDraft().copy(note = "changed"), bill.revision) is SaveResult.Saved)
        val preview = receiver.inspect(export(archive).inputStream())
        assertTrue(preview.conflicts.isNotEmpty())
        val before = export(receiver)
        rejected { receiver.restore(preview.previewId, RestoreMode.MERGE) }
        assertDataEqual(before, export(receiver))
    }

    @Test fun replacementKeepsRecoveryAndRejectsStalePreview() = runBlocking {
        CatalogRepository(target).ensureLocalDefaults()
        val original = export(receiver)
        val first = receiver.inspect(export(archive).inputStream())
        target.ledgerDao().upsertPreference(PreferenceEntity("test", "changed"))
        rejected { receiver.restore(first.previewId, RestoreMode.REPLACE) }
        val current = export(receiver)
        val next = receiver.inspect(export(archive).inputStream())
        assertTrue(receiver.restore(next.previewId, RestoreMode.REPLACE).recoveryAvailable)
        val recovery = ByteArrayOutputStream().also { receiver.exportRecovery(it) }.toByteArray()
        assertDataEqual(current, recovery)
        assertNotEquals(entries(original)["data.json"]!!.decodeToString(), entries(current)["data.json"]!!.decodeToString())
    }

    @Test fun insertionFailureRollsBackReplacement() = runBlocking {
        CatalogRepository(target).ensureLocalDefaults()
        val before = export(receiver)
        val preview = receiver.inspect(export(archive).inputStream())
        target.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_bill BEFORE INSERT ON bills BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        rejected { receiver.restore(preview.previewId, RestoreMode.REPLACE) }
        assertDataEqual(before, export(receiver))
    }

    @Test fun deletedBillsUndoAndSharedAccountMergeRemainConsistent() = runBlocking {
        receiver.restore(receiver.inspect(export(archive).inputStream()).previewId, RestoreMode.MERGE)
        val extra = draft.copy(submissionId = UUID.randomUUID().toString(), amount = Money(250, CurrencySpec.CNY), attachments = emptyList())
        val saved = RoomLedgerRepository(source).save(extra, null) as SaveResult.Saved
        receiver.restore(receiver.inspect(export(archive).inputStream()).previewId, RestoreMode.MERGE)
        assertEquals(-1484L, target.ledgerDao().account(draft.fromAccountId!!)!!.balanceMinor)
        val undo = RoomLedgerRepository(source).delete(saved.billId)
        receiver.restore(receiver.inspect(export(archive).inputStream()).previewId, RestoreMode.REPLACE)
        assertEquals(-1234L, target.ledgerDao().account(draft.fromAccountId!!)!!.balanceMinor)
        assertTrue(RoomLedgerRepository(target).undo(undo))
        assertEquals(-1484L, target.ledgerDao().account(draft.fromAccountId!!)!!.balanceMinor)
    }

    @Test fun previewSurvivesNewInstanceButExpiresAndDetectsTampering() = runBlocking {
        val preview = receiver.inspect(export(archive).inputStream())
        assertEquals(preview, BackupArchive(target, context).preview(preview.previewId))
        val late = BackupArchive(target, context, clock = Clock.offset(Clock.systemUTC(), Duration.ofDays(2)))
        rejected { late.restore(preview.previewId, RestoreMode.REPLACE) }
        val staging = File(context.noBackupFilesDir, "backup-previews/${preview.previewId}")
        File(staging, image.relativePath).writeBytes(byteArrayOf(7))
        rejected { receiver.restore(preview.previewId, RestoreMode.REPLACE) }
        assertEquals(0, target.ledgerDao().billCount())
    }

    @Test fun invalidCurrentBookAndAllArchivedCatalogAreRejectedBeforeConfirmation() = runBlocking {
        val entries = entries(export(archive))
        val data = Json.parseToJsonElement(entries.getValue("data.json").decodeToString()).jsonObject
        val archived = data.getValue("books").jsonArray.map { JsonObject(it.jsonObject + ("archived" to JsonPrimitive(true))) }
        val invalidPreferences = data.getValue("preferences").jsonArray.map {
            if (it.jsonObject.getValue("key").jsonPrimitive.content == "current_book")
                JsonObject(it.jsonObject + ("value" to JsonPrimitive("missing-book"))) else it
        }
        rejected { receiver.inspect(zip(withData(entries, data + ("books" to JsonArray(archived)))).inputStream()) }
        rejected { receiver.inspect(zip(withData(entries, data + ("preferences" to JsonArray(invalidPreferences)))).inputStream()) }
    }

    @Test fun nonCanonicalEntityIdsAreRejectedBeforeTheyCanBreakNavigation() = runBlocking {
        val entries = entries(export(archive))
        for (id in listOf("bad/id", "new", "book?kind=INCOME", "book#fragment")) {
            val changed = entries.getValue("data.json").decodeToString().replace(draft.bookId, id)
            val data = Json.parseToJsonElement(changed).jsonObject
            rejected { receiver.inspect(zip(withData(entries, data)).inputStream()) }
            assertEquals(0, target.ledgerDao().billCount())
        }
    }

    @Test fun validatedBackupRepairsCorruptImagesAndRepairSurvivesDatabaseRollback() = runBlocking {
        val bytes = export(archive)
        receiver.restore(receiver.inspect(bytes.inputStream()).previewId, RestoreMode.REPLACE)
        for (mode in RestoreMode.entries) {
            val file = AttachmentStore(context).verifiedFile(image)
            file.appendBytes(byteArrayOf(1, 2, 3))
            receiver.restore(receiver.inspect(bytes.inputStream()).previewId, mode)
            assertEquals(image.sha256, hash(AttachmentStore(context).verifiedFile(image).readBytes()))
            assertDataEqual(bytes, export(receiver))
        }
        AttachmentStore(context).verifiedFile(image).appendBytes(byteArrayOf(9))
        val preview = receiver.inspect(bytes.inputStream())
        target.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_repair_restore BEFORE INSERT ON bills BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        rejected { receiver.restore(preview.previewId, RestoreMode.REPLACE) }
        assertDataEqual(bytes, export(receiver))
        assertEquals(image.sha256, hash(AttachmentStore(context).verifiedFile(image).readBytes()))
    }

    @Test fun validReplacementCanBePreviewedWhenMergedBalanceWouldOverflow() = runBlocking {
        val left = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val right = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val incoming = BackupArchive(left, context)
            val local = BackupArchive(right, context)
            val base = CatalogRepository(left).initialDraft()
            local.restore(local.inspect(export(incoming).inputStream()).previewId, RestoreMode.REPLACE)
            val income = base.copy(kind = BillKind.INCOME, fromAccountId = null, toAccountId = base.fromAccountId, categoryId = null)
            assertTrue(RoomLedgerRepository(left).save(income.copy(amount = Money(1, CurrencySpec.CNY)), null) is SaveResult.Saved)
            assertTrue(RoomLedgerRepository(right).save(income.copy(submissionId = UUID.randomUUID().toString(), amount = Money(Long.MAX_VALUE, CurrencySpec.CNY)), null) is SaveResult.Saved)
            val preview = local.inspect(export(incoming).inputStream())
            assertTrue("Unmergeable balances must be shown, not block replacement preview", preview.conflicts.isNotEmpty())
            rejected { local.restore(preview.previewId, RestoreMode.MERGE) }
            local.restore(preview.previewId, RestoreMode.REPLACE)
            assertEquals(1L, right.ledgerDao().account(base.fromAccountId!!)!!.balanceMinor)
        } finally { left.close(); right.close() }
    }

    private suspend fun export(archive: BackupArchive): ByteArray = ByteArrayOutputStream().also { archive.exportTo(it) }.toByteArray()
    private fun entries(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(bytes.inputStream()).use { zip -> while (true) { val next = zip.nextEntry ?: break; put(next.name, zip.readBytes()) } }
    }
    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()
    private fun withData(entries: Map<String, ByteArray>, data: Map<String, JsonElement>): Map<String, ByteArray> {
        val bytes = JsonObject(data).toString().toByteArray()
        val manifest = Json.parseToJsonElement(entries.getValue("manifest.json").decodeToString()).jsonObject
        val updatedFiles = manifest.getValue("files").jsonArray.map { element ->
            val file = element.jsonObject
            if (file["path"]?.jsonPrimitive?.content == "data.json") JsonObject(file + mapOf("sha256" to JsonPrimitive(hash(bytes)), "size" to JsonPrimitive(bytes.size))) else element
        }
        return entries + mapOf("data.json" to bytes, "manifest.json" to JsonObject(manifest + ("files" to JsonArray(updatedFiles))).toString().toByteArray())
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun assertDataEqual(first: ByteArray, second: ByteArray) = assertEquals(entries(first).getValue("data.json").decodeToString(), entries(second).getValue("data.json").decodeToString())
    private suspend fun rejected(block: suspend () -> Unit) { try { block(); fail("Expected rejection") } catch (_: BackupException) { } }
}
