package app.omymoney

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.model.*
import app.omymoney.core.recognition.*
import app.omymoney.data.*
import app.omymoney.feature.bills.*
import app.omymoney.platform.overlay.OverlaySession
import app.omymoney.platform.overlay.DraftOverlayHost
import app.omymoney.platform.accessibility.AccessibilityStatus
import app.omymoney.platform.notification.RecognitionNotification
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class OverlayReviewTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Test fun comparisonRetainsSecondPrecision() = runBlocking {
        withLedger { catalog, _, _, _, _ ->
            val draft = catalog.initialDraft().copy(occurredAt = Instant.parse("2026-10-11T12:00:40Z"), zoneId = java.time.ZoneId.of("UTC"))
            assertTrue(app.omymoney.feature.automation.summary(context, draft).contains("12:00:40"))
        }
    }
    @Test fun newCandidatesDoNotReplaceAnExpandedSessionAndLockedWindowsStayHidden() {
        val session = OverlaySession()
        assertFalse(session.offer("one", unlocked = false, appVisible = false))
        assertNull(session.entryId)
        assertFalse(session.offer("one", unlocked = true, appVisible = true))
        assertTrue(session.offer("one", unlocked = true, appVisible = false))
        assertFalse(session.expanded)
        session.expand()
        assertFalse(session.offer("two", unlocked = true, appVisible = false))
        assertEquals("one", session.entryId)
        assertTrue(session.expanded)
        session.dismiss()
        assertNull(session.entryId)
        assertFalse(session.expanded)
    }

    @Test fun reviewUsesRecognitionDraftAndOnlyExplicitSaveChangesBalances(): Unit = runBlocking {
        withLedger { catalog, queue, drafts, database, scope ->
            val original = catalog.initialDraft().copy(amount = Money(1230, CurrencySpec.CNY), note = "识别原始备注", recognizedAt = Instant.now())
            val entry = queue.enqueue(RecognitionResult.Matched(original, emptyList(), "fixture"))
            val before = catalog.observeAccounts().first().single().balance.minor
            val editor = BillEditorViewModel(drafts, catalog, AttachmentStore(context), scope, original.bookId,
                recognitionQueue = queue, recognitionId = entry.id)
            await { editor.state.value.record != null }
            assertEquals("12.30", editor.state.value.record!!.amountText)
            assertEquals(QueueState.EDITING, queue.read(entry.id)!!.state)
            withContext(Dispatchers.Main) { editor.onAction(EditorAction.Amount("13.50")); editor.onAction(EditorAction.Note("人工修改")) }
            await { !editor.state.value.persistencePending }
            assertEquals(0, database.ledgerDao().billCount())
            assertEquals(before, catalog.observeAccounts().first().single().balance.minor)
            withContext(Dispatchers.Main) { editor.submit(); editor.submit() }
            await { editor.state.value.saved != null }
            assertEquals(1, database.ledgerDao().billCount())
            assertEquals(before - 1350, catalog.observeAccounts().first().single().balance.minor)
            assertEquals(QueueState.SAVED, queue.read(entry.id)!!.state)
            assertNull(drafts.read("recognition/${entry.id}"))
        }
    }

    @Test fun closeRetainsPendingAndTypedInputCanReopenInApp(): Unit = runBlocking {
        withLedger { catalog, queue, drafts, database, scope ->
            val original = catalog.initialDraft().copy(amount = Money(1230, CurrencySpec.CNY))
            val entry = queue.enqueue(RecognitionResult.Matched(original, emptyList(), "fixture"))
            val editor = BillEditorViewModel(drafts, catalog, AttachmentStore(context), scope, original.bookId,
                recognitionQueue = queue, recognitionId = entry.id)
            await { editor.state.value.record != null }
            withContext(Dispatchers.Main) { editor.onAction(EditorAction.Amount("12.")); editor.keepAndClose() }
            await { editor.state.value.closed }
            assertEquals(QueueState.PENDING, queue.read(entry.id)!!.state)
            assertEquals(0, database.ledgerDao().billCount())
            val reopened = BillEditorViewModel(drafts, catalog, AttachmentStore(context), scope, original.bookId,
                recognitionQueue = queue, recognitionId = entry.id)
            await { reopened.state.value.record != null }
            assertEquals("12.", reopened.state.value.record!!.amountText)
            withContext(Dispatchers.Main) { reopened.discard() }
            await { reopened.state.value.closed }
            assertEquals(QueueState.IGNORED, queue.read(entry.id)!!.state)
            assertEquals(0, database.ledgerDao().billCount())
        }
    }

    @Test fun rejectedWindowAndDeniedNotificationsKeepTheQueueRecoverable(): Unit = runBlocking {
        val container = (context.applicationContext as OMyMoneyApplication).container
        val catalog = container.store.catalog
        catalog.ensureLocalDefaults()
        val draft = catalog.initialDraft().copy(amount = Money(1230, CurrencySpec.CNY), note = "窗口拒绝样本")
        val entry = container.store.recognitionQueue.enqueue(RecognitionResult.Matched(draft, emptyList(), "fixture"))
        val actualManager = context.getSystemService(android.view.WindowManager::class.java)
        val deniedManager = java.lang.reflect.Proxy.newProxyInstance(android.view.WindowManager::class.java.classLoader,
            arrayOf(android.view.WindowManager::class.java)) { _, method, args ->
            if (method.name == "addView") throw android.view.WindowManager.BadTokenException("Test rejected window")
            method.invoke(actualManager, *(args ?: emptyArray()))
        } as android.view.WindowManager
        val beforeRuntime = AccessibilityStatus.runtime.value
        try {
            withContext(Dispatchers.Main) {
                val service = TestService(context)
                val host = DraftOverlayHost(service, container, deniedManager)
                host.update(listOf(entry), AutomationSettings(enabled = true), unlocked = true, appVisible = false)
                assertEquals("overlay_unavailable", AccessibilityStatus.runtime.value.error)
                host.dismiss(app.omymoney.platform.overlay.OverlayDismissReason.USER)
            }
            assertEquals(QueueState.PENDING, container.store.recognitionQueue.read(entry.id)!!.state)
            assertNotNull(container.store.recognitionQueue.entries().firstOrNull { it.id == entry.id })
            val notifications = RecognitionNotification(context)
            if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                assertFalse(notifications.allowed())
                notifications.refresh(1, AutomationSettings(enabled = true), beforeRuntime)
                assertTrue(context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.none { it.id == RecognitionNotification.ID })
            }
        } finally {
            AccessibilityStatus.mutable.value = beforeRuntime
            container.store.recognitionQueue.resolve(entry.id, QueueOutcome.Ignore)
        }
    }

    private class TestService(context: Context) : android.accessibilityservice.AccessibilityService() {
        init { attachBaseContext(context) }
        override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }

    private suspend fun withLedger(block: suspend (CatalogRepository, RecognitionQueue, DraftRepository, LedgerDatabase, CoroutineScope) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val catalog = CatalogRepository(db)
            catalog.ensureLocalDefaults()
            block(catalog, RecognitionQueue(db), DraftRepository(db), db, scope)
        } finally { scope.coroutineContext[Job]?.cancelAndJoin(); db.close() }
    }
    private suspend fun await(condition: () -> Boolean) { withTimeout(5000) { while (!condition()) delay(10) } }
}
