package app.omymoney.core.diagnostics

import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun secretsAreRemovedBeforeSerializingWhilePaymentEvidenceRemains() {
        val safe = DiagnosticRedactor.clean(buildJsonObject {
            put("amount", "12.30")
            put("merchant", "Cafe")
            put("orderId", "1234567890123456")
            put("authorization", "Bearer SECRET")
            put("text", "验证码: 123456")
            put("nested", buildJsonObject { put("password", "123456"); put("error", "token=abc123") })
        })
        val encoded = safe.toString()
        assertFalse(encoded.contains("SECRET"))
        assertEquals("[REDACTED]", safe.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("[REDACTED]", safe.jsonObject["nested"]!!.jsonObject["password"]!!.jsonPrimitive.content)
        assertFalse(encoded.contains("abc123"))
        assertTrue(encoded.contains("12.30"))
        assertTrue(encoded.contains("1234567890123456"))
    }

    @Test fun gzipBatchIsImmutableAndOnlyMatchingReceiptDeletesIt() {
        val spool = DiagnosticSpool(temp.newFolder())
        spool.append(buildJsonObject { put("kind", "capture"); put("amount", "12.30") })
        val batch = spool.seal()!!
        val original = batch.file.readBytes()
        spool.append(buildJsonObject { put("kind", "next") })
        assertArrayEquals(original, batch.file.readBytes())
        assertTrue(GZIPInputStream(batch.file.inputStream()).bufferedReader().readText().contains("12.30"))
        assertFalse(spool.acknowledge(batch.id, "0".repeat(64)))
        assertTrue(batch.file.exists())
        assertTrue(spool.acknowledge(batch.id, batch.sha256))
        assertFalse(batch.file.exists())
        assertEquals(1L, spool.stats().sentBatches)
    }

    @Test fun restartRepairsPartialTailAndReportsUncleanSession() {
        val directory = temp.newFolder()
        val spool = DiagnosticSpool(directory)
        spool.append(buildJsonObject { put("kind", "before") })
        File(directory, "active.ndjson").appendText("{\"kind\":\"incomplete")
        val recovered = DiagnosticSpool(directory)
        assertEquals(1L, recovered.stats().uncleanSessions)
        val batch = recovered.seal()!!
        val lines = GZIPInputStream(batch.file.inputStream()).bufferedReader().readLines()
        assertEquals(1, lines.size)
        assertEquals("before", Json.parseToJsonElement(lines.single()).jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals(1L, recovered.stats().droppedRecords)
    }

    @Test fun quotaEvictsWholeOldBatchesAndPersistsLossCounters() {
        val directory = temp.newFolder()
        val spool = DiagnosticSpool(directory, maxBytes = 1200, maxBatchBytes = 500, maxRecordBytes = 400)
        repeat(20) { n ->
            spool.append(buildJsonObject { put("kind", "event"); put("value", "$n-" + java.util.UUID.randomUUID().toString().repeat(7)) })
            spool.seal()
        }
        assertTrue(spool.stats().pendingBytes <= 1200)
        assertTrue(spool.stats().droppedRecords > 0)
        val lost = spool.stats().droppedRecords
        spool.close()
        val recovered = DiagnosticSpool(directory, maxBytes = 1200, maxBatchBytes = 500, maxRecordBytes = 400)
        assertEquals(lost, recovered.stats().droppedRecords)
        assertEquals(0L, recovered.stats().uncleanSessions)
    }

    @Test fun oversizedRecordIsCountedNotSilentlyTruncatedOrWritten() {
        val spool = DiagnosticSpool(temp.newFolder(), maxBytes = 1200, maxBatchBytes = 500, maxRecordBytes = 100)
        assertFalse(spool.append(buildJsonObject { put("kind", "event"); put("text", "x".repeat(200)) }))
        assertNull(spool.seal())
        assertEquals(1L, spool.stats().droppedRecords)
    }

    @Test fun throwingDiagnosticSinkCannotBreakTheCaller() {
        val sink = DiagnosticSink { _, _ -> error("disk failed") }
        sink.recordSafely("capture", buildJsonObject { put("state", "connected") })
        sink.recordSafely("capture") { error("serialization failed") }
        var invoked = false
        DiagnosticSink.NONE.recordSafely("capture") { invoked = true; buildJsonObject {} }
        assertFalse(invoked)
    }

    @Test fun secretLabelSuppressesAdjacentValuesAcrossTheWholeDiagnosticSnapshot() {
        val records = mutableListOf<JsonObject>()
        val sink = DiagnosticSink { _, fields -> records += fields }
        sink.recordSnapshot(app.omymoney.core.recognition.UiSnapshot("example.payment", 1, java.time.Instant.EPOCH,
            listOf(app.omymoney.core.recognition.UiNode("验证码"), app.omymoney.core.recognition.UiNode("654321"))), "diagnostic")
        assertFalse(records.toString().contains("654321"))
        assertEquals(true, records.single()["metadataOnly"]!!.jsonPrimitive.boolean)
    }

    @Test fun largeSnapshotsAreSplitIntoBoundedIndependentlyIdentifiableChunks() {
        val records = mutableListOf<Pair<String, JsonObject>>()
        val sink = DiagnosticSink { kind, fields -> records += kind to fields }
        sink.recordSnapshot(app.omymoney.core.recognition.UiSnapshot("example.payment", 1, java.time.Instant.EPOCH,
            List(100) { app.omymoney.core.recognition.UiNode("金额".repeat(1000)) }), "diagnostic")
        val chunks = records.filter { it.first == "snapshot_nodes" }
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.second.toString().toByteArray().size < 98304 })
        assertEquals(100, chunks.sumOf { it.second.getValue("nodes").jsonArray.size })
        assertEquals(1, records.map { it.second.getValue("snapshotId").jsonPrimitive.content }.distinct().size)
    }
}
