package app.omymoney.platform.notification

import app.omymoney.core.diagnostics.DiagnosticSink
import app.omymoney.core.recognition.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NotificationCaptureCoordinatorTest {
    private fun sample(index: Int = 0) = TransactionNotification("com.tencent.mm", "notification-$index",
        Instant.parse("2026-10-18T10:00:00Z").plusSeconds(index.toLong()), mapOf("text" to "支付成功 CNY 12.30"))

    @Test fun burstsAreNotConflatedAndReplayDoesNotEnqueueTwice() = runTest {
        val saved = mutableListOf<RecognitionResult>()
        val coordinator = NotificationCaptureCoordinator(backgroundScope, { true }, { RecognitionContext(UUID.randomUUID().toString()) },
            { saved += it }, DiagnosticSink.NONE)
        repeat(10) { assertTrue(coordinator.submit(sample(it))) }
        coordinator.submit(sample())
        runCurrent()
        assertEquals(10, saved.size)
    }

    @Test fun policyIsReadAtProcessingAndDiagnosticsCanCollectWithoutBookkeeping() = runTest {
        var permitted = true
        var diagnosticEnabled = true
        val records = mutableListOf<Pair<String, JsonObject>>()
        val sink = object : DiagnosticSink {
            override val enabled get() = diagnosticEnabled
            override fun record(kind: String, fields: JsonObject) { records += kind to fields }
        }
        var saves = 0
        val coordinator = NotificationCaptureCoordinator(backgroundScope, { permitted }, { RecognitionContext() }, { saves++ }, sink)
        coordinator.submit(sample())
        permitted = false
        runCurrent()
        assertEquals(0, saves)
        assertTrue(records.any { it.first == "notification_sample" })
        records.clear()
        coordinator.submit(sample(1))
        diagnosticEnabled = false
        runCurrent()
        assertTrue(records.isEmpty())
    }

    @Test fun failedPersistenceCanRetryAndOneFailureDoesNotKillTheStream() = runTest {
        var fail = true
        var saves = 0
        val coordinator = NotificationCaptureCoordinator(backgroundScope, { true }, { RecognitionContext() }, {
            if (fail) error("storage") else saves++
        }, DiagnosticSink.NONE)
        coordinator.submit(sample()); runCurrent()
        fail = false
        coordinator.submit(sample()); coordinator.submit(sample(1)); runCurrent()
        assertEquals(2, saves)
    }

    @Test fun summariesDistinguishReadEventsCandidatesFilteringAndQueueDropsWithoutContent() = runTest {
        val records = mutableListOf<Pair<String, JsonObject>>()
        val coordinator = NotificationCaptureCoordinator(backgroundScope, { false }, { RecognitionContext() }, {},
            DiagnosticSink { kind, fields -> records += kind to fields })
        assertTrue(coordinator.submit(sample()))
        assertTrue(coordinator.submit(sample(1).copy(fields = mapOf("text" to "付款验证码 123456"))))
        runCurrent()
        val summary = records.lastOrNull { it.first == "notification_capture_counts" }?.second
        assertNotNull("Notification event counts must not be confused with diagnostic upload batches", summary)
        assertEquals(2L, summary!!.getValue("received").jsonPrimitive.long)
        assertEquals(1L, summary.getValue("candidates").jsonPrimitive.long)
        assertEquals(1L, summary.getValue("filtered").jsonPrimitive.long)
        assertEquals(0L, summary.getValue("dropped").jsonPrimitive.long)
        assertFalse(records.toString().contains("123456"))
        assertFalse(summary.toString().contains("private-key"))
    }

    @Test fun boundedQueueReportsDroppedEventsWithoutDroppingTheSixtyFourAcceptedSamples() = runTest {
        val records = mutableListOf<Pair<String, JsonObject>>()
        val coordinator = NotificationCaptureCoordinator(backgroundScope, { false }, { RecognitionContext() }, {},
            DiagnosticSink { kind, fields -> records += kind to fields })
        repeat(64) { assertTrue(coordinator.submit(sample(it))) }
        assertFalse(coordinator.submit(sample(64)))
        runCurrent()
        val summary = records.lastOrNull { it.first == "notification_capture_counts" }?.second
        assertNotNull(summary)
        assertEquals(65L, summary!!.getValue("received").jsonPrimitive.long)
        assertEquals(64L, summary.getValue("candidates").jsonPrimitive.long)
        assertEquals(1L, summary.getValue("dropped").jsonPrimitive.long)
    }

    @Test fun aFailedStatisticsObserverCannotInterruptTheFinancialReviewQueue() = runTest {
        var saves = 0
        val coordinator = NotificationCaptureCoordinator(backgroundScope, { true }, { RecognitionContext(UUID.randomUUID().toString()) },
            { saves++ }, DiagnosticSink.NONE, onCounts = { error("statistics unavailable") })
        coordinator.submit(sample())
        runCurrent()
        assertEquals(1, saves)
    }

    @Test fun ordinaryMessagesAndUnknownTemplatesNeverEnterTheReviewQueue() = runTest {
        val saved = mutableListOf<RecognitionResult>()
        val records = mutableListOf<Pair<String, JsonObject>>()
        val coordinator = NotificationCaptureCoordinator(backgroundScope, { true }, { RecognitionContext(UUID.randomUUID().toString()) },
            { saved += it }, DiagnosticSink { kind, fields -> records += kind to fields })
        coordinator.submit(sample().copy(fields = mapOf("title" to "测试联系人", "text" to "今天不消费")))
        coordinator.submit(sample(1).copy(packageName = "com.eg.android.AlipayGphone", fields = mapOf("title" to "支付宝卡包", "text" to "3元红包今晚失效")))
        coordinator.submit(sample(2).copy(fields = mapOf("title" to "交易提醒", "text" to "金额稍后显示")))
        runCurrent()
        assertTrue(saved.isEmpty())
        assertEquals(1, records.count { it.first == "notification_sample" })
    }
}
