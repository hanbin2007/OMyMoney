package app.omymoney

import android.os.SystemClock
import app.omymoney.core.recognition.*
import app.omymoney.platform.accessibility.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import app.omymoney.core.diagnostics.DiagnosticSink
import kotlinx.serialization.json.*

class AccessibilityCaptureTest {
    @Test fun sensitiveWindowClassificationSurvivesWidgetEventsUntilARealWindowTransition() {
        val policy = DiagnosticWindowPolicy()
        val window = android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val content = android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        assertTrue(policy.observe(PACKAGE, 1, window, "payment.LoginActivity", false))
        assertTrue(policy.observe(PACKAGE, 1, content, "android.widget.TextView", false))
        assertTrue(policy.observe(PACKAGE, 1, window, "android.widget.FrameLayout", false))
        assertFalse(policy.observe(PACKAGE, 1, window, "payment.BillDetailActivity", false))
        assertTrue(policy.observe(PACKAGE, 1, content, "android.view.View", true))
        assertTrue(policy.restricted(PACKAGE, 1))
        policy.clear()
        assertFalse(policy.restricted(PACKAGE, 1))
    }

    @Test fun restrictedWindowPreservesRecognitionSnapshotButNeverSerializesItsNodes() {
        val traces = mutableListOf<JsonObject>()
        val reader = NodeSnapshotReader(DiagnosticSink { _, fields -> traces += fields })
        val snapshot = reader.read(Node(value = "LOGIN_ORDINARY_TEXT_SENTINEL"), PACKAGE, metadataOnly = true)
        assertEquals("LOGIN_ORDINARY_TEXT_SENTINEL", snapshot!!.nodes.single().text)
        assertTrue(traces.toString().contains("metadataOnly"))
        assertFalse(traces.toString().contains("LOGIN_ORDINARY_TEXT_SENTINEL"))
    }

    @Test fun privacyChangedDuringTraversalIsRecheckedBeforeSerializingNodes() {
        val traces = mutableListOf<JsonObject>()
        var restricted = false
        val node = Node(value = "MID_READ_SECRET").also { it.onRead = { restricted = true } }
        val reader = NodeSnapshotReader(DiagnosticSink { _, fields -> traces += fields }, metadataOnlyNow = { restricted })
        assertEquals("MID_READ_SECRET", reader.read(node, PACKAGE)!!.nodes.single().text)
        assertFalse(traces.toString().contains("MID_READ_SECRET"))
        assertTrue(traces.toString().contains("metadataOnly"))
    }

    @Test fun restrictedEventRetainsRecognitionButDoesNotSerializeResultContent(): Unit = runBlocking {
        val rig = Rig()
        try {
            rig.capture.connect(); rig.capture.submit(rig.event().copy(diagnosticMetadataOnly = true))
            await { rig.emitted.size == 1 }
            assertFalse(rig.fields.toString().contains("午餐"))
            assertTrue(rig.kinds.contains("capture_result"))
        } finally { rig.close() }
    }

    @Test fun ordinaryCaptureResultsAreMetadataOnlyWithoutCopyingPageEvidence(): Unit = runBlocking {
        val rig = Rig()
        try {
            rig.readDelay = 100
            rig.capture.connect(); rig.capture.submit(rig.event())
            await { rig.reads.get() == 1 }
            await { rig.emitted.size == 1 }
            assertFalse(rig.fields.toString().contains("午餐"))
        } finally { rig.close() }
    }
    @Test fun additionalDiagnosticEventTypesNeverTriggerFinancialCapture() {
        assertTrue(isRecognitionEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertTrue(isRecognitionEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        for (type in listOf(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED,
            android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
            android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED)) assertFalse(isRecognitionEvent(type))
    }
    @Test fun prunedNodesHaveReasonsButNeverSecretValues() {
        val traces = mutableListOf<JsonObject>()
        val sensitive = Node(password = true).also { it.failIfRead = true }
        val reader = NodeSnapshotReader(DiagnosticSink { _, fields -> traces += fields })
        reader.read(Node(children = listOf(sensitive, Node(editable = true), Node(value = "12.30"))), PACKAGE)
        val serialized = traces.toString()
        assertTrue(serialized.contains("password"))
        assertTrue(serialized.contains("editable"))
        assertFalse(serialized.contains("12.30"))
        assertTrue(serialized.contains("metadataOnly"))
        traces.clear()
        reader.read(Node(value = "12.30"), PACKAGE)
        assertTrue(traces.toString().contains("12.30"))
    }

    @Test fun cancellationAndPermissionRefusalAreObservableWithoutChangingCapture(): Unit = runBlocking {
        val rig = Rig()
        try {
            rig.capture.connect()
            rig.allowed = false
            rig.capture.submit(rig.event()); await { rig.kinds.contains("capture_rejected") }
            assertEquals(0, rig.reads.get())
            rig.allowed = true
            rig.readDelay = 500
            rig.capture.submit(rig.event()); await { rig.reads.get() == 1 }
            rig.capture.interrupt(); await { rig.kinds.contains("capture_cancelled") }
            assertEquals(0, rig.emitted.size)
        } finally { rig.close() }
    }
    @Test fun passwordEditableSensitiveAndForeignSubtreesAreNeverRead() {
        val hidden = listOf(Node(password = true), Node(editable = true), Node(sensitive = true), Node(packageName = "other"), Node(visible = false))
        hidden.forEach { it.failIfRead = true }
        val normal = Node(value = "付款金额", children = listOf(Node(value = "12.30")))
        val snapshot = NodeSnapshotReader().read(Node(children = hidden + normal), PACKAGE)
        assertNotNull(snapshot)
        assertEquals(listOf(null, "付款金额", "12.30"), snapshot!!.nodes.map { it.text })
        assertEquals(listOf(null, 0, 1), snapshot.nodes.map { it.parentIndex })
        assertTrue((hidden + normal).all { it.releases == 1 })
        assertNull(NodeSnapshotReader().read(Node(packageName = "other"), PACKAGE))
    }

    @Test fun boundedReadsFailClosedInsteadOfRecognizingPartialPages() {
        val snapshot = NodeSnapshotReader().read(Node(children = (0..1100).map { Node(value = "payment") }), PACKAGE)
        assertNotNull(snapshot)
        assertTrue(snapshot!!.truncated)
        assertTrue(snapshot.nodes.size <= RuleEngine.MAX_NODES)
        assertEquals(RecognitionResult.Unsupported("snapshot_limit"), RuleEngine(RuleRegistry.enabledRules(setOf(PACKAGE))).recognize(snapshot))
    }

    @Test fun unauthorizedPausedUnknownAndUnboundCaptureNothing(): Unit = runBlocking {
        val rig = Rig()
        try {
            rig.capture.submit(rig.event()); delay(100)
            rig.capture.connect()
            rig.allowed = false
            rig.capture.submit(rig.event()); delay(100)
            rig.allowed = true
            rig.capture.submit(rig.event().copy(packageName = "unsupported")); delay(100)
            assertEquals(0, rig.reads.get())
            assertEquals(0, rig.emitted.size)
            rig.capture.submit(rig.event()); await { rig.emitted.size == 1 }
            rig.allowed = false
            rig.capture.submit(rig.event()); delay(100)
            assertEquals(1, rig.emitted.size)
        } finally { rig.close() }
    }

    @Test fun repeatedEventsCoalesceAndReconnectNeedsFreshEvent(): Unit = runBlocking {
        val rig = Rig()
        try {
            rig.capture.connect()
            repeat(20) { rig.capture.submit(rig.event()) }
            await { rig.emitted.size == 1 }
            rig.capture.submit(rig.event()); delay(100)
            assertEquals(1, rig.emitted.size)
            val stale = rig.event()
            rig.capture.disconnect(); delay(5); rig.capture.connect()
            rig.capture.submit(stale); delay(100)
            assertEquals(1, rig.emitted.size)
            rig.capture.submit(rig.event()); await { rig.emitted.size == 2 }
        } finally { rig.close() }
    }

    @Test fun revokedDuringReadAndWindowChangeCannotEmitStaleData(): Unit = runBlocking {
        val rig = Rig()
        try {
            rig.readDelay = 100
            rig.capture.connect(); rig.capture.submit(rig.event())
            await { rig.reads.get() == 1 }
            rig.allowed = false; delay(160)
            assertEquals(0, rig.emitted.size)
            rig.allowed = true; rig.capture.submit(rig.event())
            await { rig.reads.get() == 2 }
            rig.capture.interrupt(); delay(160)
            assertEquals(0, rig.emitted.size)
        } finally { rig.close() }
    }

    @Test fun unpaidFixtureOnlyProducesReviewWithoutSaving(): Unit = runBlocking {
        val rig = Rig()
        try {
            rig.unpaid = true
            rig.capture.connect(); rig.capture.submit(rig.event())
            await { rig.emitted.size == 1 }
            val result = rig.emitted.single()
            assertTrue(result is RecognitionResult.NeedsReview)
            assertNull((result as RecognitionResult.NeedsReview).partialDraft.fromAccountId)
            assertTrue(result.issues.isNotEmpty())
        } finally { rig.close() }
    }

    @Test fun newWindowInvalidatesEvenAnUninterruptibleBinderRead(): Unit = runBlocking {
        val rig = Rig()
        try {
            rig.readDelay = 200
            rig.blockingRead = true
            rig.capture.connect(); rig.capture.submit(rig.event())
            await { rig.reads.get() == 1 }
            rig.capture.submit(rig.event().copy(windowId = 2))
            await { rig.reads.get() == 2 }
            await { rig.emitted.isNotEmpty() }
            assertEquals("The old window must not enqueue after a newer event", 1, rig.emitted.size)
            assertEquals(listOf(2), rig.emittedWindows.toList())
        } finally { rig.close() }
    }

    private class Rig {
        private val worker = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + worker)
        @Volatile var allowed = true
        @Volatile var unpaid = false
        @Volatile var readDelay = 0L
        @Volatile var blockingRead = false
        val reads = AtomicInteger()
        val emitted = java.util.Collections.synchronizedList(mutableListOf<RecognitionResult>())
        val emittedWindows = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val kinds = java.util.Collections.synchronizedList(mutableListOf<String>())
        val fields = java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
        val capture = CaptureCoordinator(scope, permitted = { allowed }, context = { RecognitionContext("test-book") },
            snapshot = { event ->
                reads.incrementAndGet()
                if (blockingRead) Thread.sleep(readDelay) else delay(readDelay)
                UiSnapshot(event.packageName, event.windowId, Instant.now(),
                    (if (unpaid) listOf("使用“先用后付”下单成功", "先用后付下单，实付12.30", "等待收货", "确认收货后，自动付款¥12.30", "知道了", "查看订单")
                    else listOf("账单详情", "当前状态", "支付成功", "商品详情", "午餐", "实付款", "12.30", "支付时间", "2026-10-11 12:00:00", "支付方式", "多多钱包", "交易单号", "123456789", "商户单号", "998877665")).map { UiNode(text = it) })
            }, enqueue = { result, event -> emittedWindows += event.windowId; emitted += result }, normalDelayMillis = 20,
            diagnostics = DiagnosticSink { kind, data -> kinds += kind; fields += data })
        fun event() = CaptureEvent(PACKAGE, 1, null, SystemClock.uptimeMillis())
        fun close() { capture.disconnect(); scope.cancel(); worker.close() }
    }
    private class Node(
        override val packageName: String? = PACKAGE,
        override val password: Boolean = false,
        override val editable: Boolean = false,
        override val sensitive: Boolean = false,
        override val visible: Boolean = true,
        private val value: String? = null,
        val children: List<Node> = emptyList(),
    ) : SnapshotNode {
        var releases = 0
        var failIfRead = false
        var onRead: () -> Unit = {}
        override val windowId = 1
        override val text: String? get() { check(!failIfRead); onRead(); return value }
        override val description: String? get() { check(!failIfRead); return null }
        override val viewId: String? get() { check(!failIfRead); return null }
        override val className: String? get() { check(!failIfRead); return null }
        override val childCount: Int get() { check(!failIfRead); return children.size }
        override fun child(index: Int) = children[index]
        override fun release() { releases++ }
    }
    private suspend fun await(condition: () -> Boolean) { withTimeout(2500) { while (!condition()) delay(10) } }
    companion object { private const val PACKAGE = "com.xunmeng.pinduoduo" }
}
