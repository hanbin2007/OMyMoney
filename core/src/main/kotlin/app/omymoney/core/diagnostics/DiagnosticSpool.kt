package app.omymoney.core.diagnostics

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.*

data class DiagnosticBatch(val id: String, val file: File, val sha256: String, val records: Long)
data class SpoolStats(val pendingBatches: Int, val pendingBytes: Long, val droppedRecords: Long,
    val sentBatches: Long, val uncleanSessions: Long)

/** Single owner, with synchronized access for the upload worker and export UI. */
class DiagnosticSpool(
    private val directory: File,
    private val maxBytes: Long = 16L * 1024 * 1024,
    private val maxBatchBytes: Int = 256 * 1024,
    private val maxRecordBytes: Int = 96 * 1024,
) : Closeable {
    private val active = File(directory, "active.ndjson")
    private val state = File(directory, "state.json")
    private val marker = File(directory, "session.active")
    private val journal = File(directory, "sealing.json")
    private var dropped = 0L
    private var sent = 0L
    private var unclean = 0L
    private var activeRecords = 0L
    private var closed = false

    init {
        require(maxRecordBytes > 0 && maxRecordBytes <= maxBatchBytes && maxBatchBytes < maxBytes)
        check(directory.isDirectory || directory.mkdirs())
        if (state.exists()) {
            try {
                require(state.length() < 4096)
                val saved = Json.parseToJsonElement(state.readText()).jsonObject
                dropped = saved.getValue("dropped").jsonPrimitive.long.coerceAtLeast(0)
                sent = saved.getValue("sent").jsonPrimitive.long.coerceAtLeast(0)
                unclean = saved.getValue("unclean").jsonPrimitive.long.coerceAtLeast(0)
            } catch (_: Exception) { dropped = 1 }
        }
        if (marker.exists()) unclean++
        if (journal.exists()) {
            val name = journal.takeIf { it.length() < 256 }?.readText().orEmpty()
            if (BATCH_NAME.matches(name) && File(directory, name).exists()) active.delete()
            journal.delete()
        }
        directory.listFiles().orEmpty().filter { it.name.endsWith(".part") }.forEach { it.delete() }
        repairActive()
        atomicWrite(marker, byteArrayOf(1))
        persistState()
        enforceQuota()
    }

    @Synchronized fun append(record: JsonObject): Boolean {
        check(!closed)
        val bytes = (DiagnosticRedactor.clean(record).toString() + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > maxRecordBytes) { noteDropped(1); return false }
        if (active.length() + bytes.size > maxBatchBytes || activeRecords >= 128) seal()
        FileOutputStream(active, true).use { it.write(bytes); it.fd.sync() }
        activeRecords++
        enforceQuota()
        return true
    }

    @Synchronized fun seal(): DiagnosticBatch? {
        check(!closed)
        if (activeRecords == 0L) return null
        val name = "${System.currentTimeMillis()}-${UUID.randomUUID()}-$activeRecords.gz"
        val target = File(directory, name)
        atomicWrite(journal, name.toByteArray())
        val temporary = File(directory, "$name.part")
        FileOutputStream(temporary).use { output ->
            val compressed = GZIPOutputStream(output)
            active.inputStream().use { it.copyTo(compressed) }
            compressed.finish(); output.fd.sync()
        }
        move(temporary, target)
        check(active.delete() || !active.exists())
        activeRecords = 0
        check(journal.delete())
        enforceQuota()
        return target.takeIf(File::exists)?.let(::batch)
    }

    @Synchronized fun pending(): List<DiagnosticBatch> = batchFiles().map(::batch)

    @Synchronized fun acknowledge(id: String, sha256: String): Boolean {
        val match = batchFiles().firstOrNull { batchId(it) == id } ?: return false
        if (batch(match).sha256 != sha256) return false
        if (!match.delete()) return false
        sent++
        persistState()
        return true
    }

    @Synchronized fun noteDropped(count: Long) {
        require(count >= 0)
        dropped += count
        persistState()
    }

    @Synchronized fun stats(): SpoolStats {
        val files = batchFiles()
        return SpoolStats(files.size, files.sumOf(File::length) + active.length(), dropped, sent, unclean)
    }

    @Synchronized override fun close() {
        if (closed) return
        seal()
        marker.delete()
        closed = true
    }

    private fun repairActive() {
        if (!active.exists()) return
        if (active.length() > maxBatchBytes) { active.delete(); dropped++; return }
        val bytes = active.readBytes()
        val lastNewline = bytes.indexOfLast { it == '\n'.code.toByte() }
        val complete = if (lastNewline < 0) "" else bytes.copyOfRange(0, lastNewline + 1).toString(Charsets.UTF_8)
        if (lastNewline + 1 < bytes.size) dropped++
        val valid = complete.lineSequence().filter(String::isNotEmpty).mapNotNull { line ->
            try {
                require(line.toByteArray().size < maxRecordBytes)
                Json.parseToJsonElement(line).jsonObject
                activeRecords++
                line
            } catch (_: Exception) { dropped++; null }
        }.toList()
        atomicWrite(active, valid.joinToString("\n", postfix = if (valid.isEmpty()) "" else "\n").toByteArray())
    }

    private fun enforceQuota() {
        val files = batchFiles().toMutableList()
        var size = files.sumOf(File::length) + active.length()
        while ((size > maxBytes || files.size > 512) && files.isNotEmpty()) {
            val file = files.removeAt(0)
            val length = file.length()
            if (file.delete()) { size -= length; dropped += recordCount(file); persistState() }
            else break
        }
    }
    private fun persistState() = atomicWrite(state, buildJsonObject {
        put("dropped", dropped); put("sent", sent); put("unclean", unclean)
    }.toString().toByteArray())
    private fun batchFiles() = directory.listFiles().orEmpty().filter { BATCH_NAME.matches(it.name) }.sortedBy { it.name }
    private fun batchId(file: File) = file.name.substringAfter('-').substringBeforeLast('-')
    private fun recordCount(file: File) = file.name.substringAfterLast('-').removeSuffix(".gz").toLong()
    private fun batch(file: File) = DiagnosticBatch(batchId(file), file, sha256(file), recordCount(file))

    companion object {
        private val BATCH_NAME = Regex("[0-9]{13}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[0-9]{1,3}\\.gz")
        fun sha256(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val bytes = ByteArray(8192)
                while (true) { val n = input.read(bytes); if (n < 0) break; hash.update(bytes, 0, n) }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        private fun atomicWrite(file: File, bytes: ByteArray) {
            val temporary = File(file.parentFile, "${file.name}.part")
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            move(temporary, file)
        }
        private fun move(from: File, to: File) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
