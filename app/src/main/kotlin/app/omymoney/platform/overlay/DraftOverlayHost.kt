package app.omymoney.platform.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.omymoney.AppContainer
import app.omymoney.MainActivity
import app.omymoney.android.R
import app.omymoney.core.model.toDraft
import app.omymoney.data.*
import app.omymoney.feature.automation.DraftReviewContent
import app.omymoney.feature.bills.BillEditorViewModel
import app.omymoney.feature.catalog.CatalogViewModel
import app.omymoney.platform.accessibility.AccessibilityStatus
import app.omymoney.ui.theme.LedgerTheme
import app.omymoney.ui.theme.ThemeMode
import kotlinx.coroutines.flow.update

enum class OverlayDismissReason { USER, LOCKED, APP_VISIBLE, PAUSED, RESOLVED, DISCONNECTED, WINDOW_REJECTED, TIMEOUT, REPLACED }

class DraftOverlayHost(private val service: AccessibilityService, private val container: AppContainer,
    private val manager: WindowManager = service.getSystemService(WindowManager::class.java)) {
    private val session = OverlaySession()
    private var view: FrameLayout? = null
    private var composition: ComposeView? = null
    private var owner: Owner? = null
    private var expanded by mutableStateOf(false)
    private var pendingCount by mutableIntStateOf(0)
    private var entries = emptyList<QueueEntry>()
    private val dismissed = mutableSetOf<String>()
    private var openingApp = false
    private var receiptMode = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var timeout: Runnable? = null

    fun update(entries: List<QueueEntry>, settings: AutomationSettings, unlocked: Boolean, appVisible: Boolean) {
        this.entries = entries
        dismissed.retainAll(entries.map { it.id }.toSet())
        pendingCount = entries.count { it.state in RecognitionQueue.ACTIVE_STATES }
        when {
            !unlocked || !container.privacyLock.allowOverlay -> { dismiss(OverlayDismissReason.LOCKED); return }
            appVisible -> { dismiss(OverlayDismissReason.APP_VISIBLE); return }
            !settings.enabled || settings.paused -> { dismiss(OverlayDismissReason.PAUSED); return }
        }
        val latestReceipt = selectReceipt(entries, session.entryId.takeIf { receiptMode }, dismissed, java.time.Instant.now())
        if (session.entryId != null && latestReceipt != null && !expanded && latestReceipt.id != session.entryId) {
            dismiss(OverlayDismissReason.REPLACED)
        }
        if (session.entryId != null && entries.none { it.id == session.entryId && (it.state in RecognitionQueue.ACTIVE_STATES || it.id == latestReceipt?.id) }) {
            dismiss(OverlayDismissReason.RESOLVED)
        }
        if (session.entryId != null && !receiptMode && !expanded && latestReceipt?.id == session.entryId) dismiss(OverlayDismissReason.REPLACED)
        if (session.entryId != null) return
        // Pending history is reviewed in-app; only a newly committed bill may interrupt.
        if (latestReceipt != null) show(latestReceipt.id)
    }

    fun show(entryId: String): Boolean {
        val entry = entries.firstOrNull { it.id == entryId } ?: return false
        if (!session.offer(entryId, AccessibilityStatus.unlocked(service) && container.privacyLock.allowOverlay, container.appVisible.value)) return false
        val lifecycle = Owner { dismiss(OverlayDismissReason.USER) }
        owner = lifecycle
        receiptMode = entry.state == QueueState.SAVED && entry.automaticRevision != null
        val context = ContextThemeWrapper(service, R.style.Theme_OMyMoney)
        val root = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    lifecycle.onBackPressedDispatcher.onBackPressed()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        root.id = R.id.recognition_overlay
        val compose = ComposeView(context)
        composition = compose
        root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        view = root
        root.setViewTreeLifecycleOwner(lifecycle)
        root.setViewTreeSavedStateRegistryOwner(lifecycle)
        root.setViewTreeViewModelStoreOwner(lifecycle)
        root.setViewTreeOnBackPressedDispatcherOwner(lifecycle)
        val receiptDuration = if (!receiptMode) 0L else if (android.os.Build.VERSION.SDK_INT >= 29) service.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            .getRecommendedTimeoutMillis(8_000, android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT or
                android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS or android.view.accessibility.AccessibilityManager.FLAG_CONTENT_ICONS).toLong()
            else 8_000L
        compose.setContent {
            val appearance by container.preferences.state.collectAsStateWithLifecycle()
            LedgerTheme(appearance.theme, dynamicColor = appearance.dynamicColor) {
                if (receiptMode) {
                    var bill by remember(entry.billId) { mutableStateOf<app.omymoney.core.model.Bill?>(null) }
                    LaunchedEffect(entry.billId) {
                        try {
                            container.store.ledger.observeBill(requireNotNull(entry.billId)).collect { current ->
                                if (current == null) dismiss(OverlayDismissReason.RESOLVED) else bill = current
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { dismiss(OverlayDismissReason.WINDOW_REJECTED) }
                    }
                    bill?.let { PaymentReceipt(it.toDraft(), onEdit = { openSavedBill(entry) },
                        onDismiss = { dismiss(OverlayDismissReason.USER) }, displayMillis = receiptDuration) }
                } else if (!expanded) PendingIsland(pendingCount, onReopen = ::expand, onLater = { dismiss(OverlayDismissReason.USER) })
                else {
                    val catalog: CatalogViewModel = viewModel(viewModelStoreOwner = lifecycle,
                        factory = remember { viewModelFactory { initializer { CatalogViewModel(container.store.catalog) } } })
                    val catalogState by catalog.state.collectAsStateWithLifecycle()
                    val editor: BillEditorViewModel = viewModel(viewModelStoreOwner = lifecycle,
                        factory = remember(entry.id) { viewModelFactory { initializer {
                            BillEditorViewModel(container.store.drafts, container.store.catalog, container.store.attachments,
                                container.applicationScope, entry.draft.bookId, recognitionQueue = container.store.recognitionQueue, recognitionId = entry.id)
                        } } })
                    DraftReviewContent(editor, catalog, catalogState, container.store,
                        onBack = {
                            val open = openingApp
                            dismiss(OverlayDismissReason.USER)
                            if (open) service.startActivity(Intent(service, MainActivity::class.java)
                                .setAction(MainActivity.ACTION_QUEUE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        }, onSaved = { dismiss(OverlayDismissReason.RESOLVED) }, overlayMode = true,
                        onOpenApp = { openingApp = true; editor.keepAndClose() })
                }
            }
        }
        return try {
            lifecycle.start()
            manager.addView(root, parameters(focusable = false))
            if (receiptMode) timeout = Runnable { dismiss(OverlayDismissReason.TIMEOUT) }.also { handler.postDelayed(it, receiptDuration) }
            true
        } catch (_: RuntimeException) {
            AccessibilityStatus.mutable.update { it.copy(error = "overlay_unavailable") }
            dismiss(OverlayDismissReason.WINDOW_REJECTED)
            false
        }
    }

    private fun openSavedBill(entry: QueueEntry) {
        if (!AccessibilityStatus.refresh(service) || !container.privacyLock.allowOverlay) { dismiss(OverlayDismissReason.LOCKED); return }
        try {
            service.startActivity(Intent(service, MainActivity::class.java).setAction(MainActivity.ACTION_QUEUE)
                .putExtra(MainActivity.EXTRA_REVIEW_ID, entry.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            dismiss(OverlayDismissReason.USER)
        } catch (_: RuntimeException) {
            AccessibilityStatus.mutable.update { it.copy(error = "overlay_unavailable") }
            dismiss(OverlayDismissReason.WINDOW_REJECTED)
        }
    }

    private fun expand() {
        if (!AccessibilityStatus.unlocked(service) || !container.privacyLock.allowOverlay) { dismiss(OverlayDismissReason.LOCKED); return }
        val root = view ?: return
        try {
            manager.updateViewLayout(root, parameters(focusable = true))
            session.expand(); expanded = true
        } catch (_: RuntimeException) { dismiss(OverlayDismissReason.WINDOW_REJECTED) }
    }

    fun dismiss(reason: OverlayDismissReason) {
        timeout?.let(handler::removeCallbacks); timeout = null
        if (receiptMode || reason in setOf(OverlayDismissReason.USER, OverlayDismissReason.RESOLVED, OverlayDismissReason.WINDOW_REJECTED)) {
            session.entryId?.let { dismissed += it }
        }
        val root = view
        view = null
        root?.let {
            service.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0)
            composition?.disposeComposition()
            if (it.isAttachedToWindow) runCatching { manager.removeViewImmediate(it) }
        }
        composition = null
        owner?.close(); owner = null
        session.dismiss(); expanded = false; openingApp = false; receiptMode = false
    }

    private fun parameters(focusable: Boolean) = WindowManager.LayoutParams(
        if (receiptMode) minOf((400 * service.resources.displayMetrics.density).toInt(), service.resources.displayMetrics.widthPixels - (24 * service.resources.displayMetrics.density).toInt())
            // The collapsed reminder is a capsule: wrapping it keeps the screen around it touchable.
            else if (focusable) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
        if (focusable) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; if (!focusable) y = (8 * service.resources.displayMetrics.density).toInt()
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }

    private class Owner(onBack: () -> Unit) : SavedStateRegistryOwner, ViewModelStoreOwner, OnBackPressedDispatcherOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry get() = savedState.savedStateRegistry
        override val viewModelStore = ViewModelStore()
        override val onBackPressedDispatcher = OnBackPressedDispatcher(onBack)
        init { savedState.performAttach(); savedState.performRestore(null) }
        fun start() { registry.currentState = Lifecycle.State.RESUMED }
        fun close() { registry.currentState = Lifecycle.State.DESTROYED; viewModelStore.clear() }
    }
}
