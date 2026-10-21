package app.omymoney

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.android.R
import app.omymoney.core.model.*
import app.omymoney.core.recognition.*
import app.omymoney.data.*
import app.omymoney.platform.accessibility.AccessibilityStatus
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AccessibilityOverlayDeviceTest {
    @Test fun pendingDraftsStayQuietAcrossUnlockAndPolicyChanges() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("overlayDeviceTest") == "true")
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as OMyMoneyApplication
        val store = app.container.store
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        val resolver = app.contentResolver
        val previousServices = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val previousEnabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        val service = "app.omymoney.android/app.omymoney.platform.accessibility.LedgerAccessibilityService"
        val services = (previousServices.orEmpty().split(':').filter(String::isNotBlank) + service).distinct().joinToString(":")
        val original = runBlocking {
            store.automation.setEnabled(false)
            store.catalog.initialDraft().copy(amount = Money(1, CurrencySpec.CNY), fromAccountId = null,
                occurredAt = null, timeSource = TimeSource.UNKNOWN, note = "Quiet pending fixture", source = BillSource.ACCESSIBILITY)
        }
        val balances = runBlocking { store.catalog.observeAccounts().first().associate { it.id to it.balance } }
        try {
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services)
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            app.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("service connection") { AccessibilityStatus.runtime.value.connected }
            val pending = runBlocking {
                listOf(original, original.copy(submissionId = UUID.randomUUID().toString(), source = BillSource.NOTIFICATION,
                    sourcePackage = "com.eg.android.AlipayGphone", kind = null)).map { draft ->
                    store.recognitionQueue.enqueue(RecognitionResult.NeedsReview(draft,
                        listOf(FieldError("occurredAt", "unknown_time", "Time needs review")), emptyList(), "fixture-quiet-pending"))
                }
            }
            runBlocking { store.automation.setPaused(false); store.automation.setEnabled(true) }
            assertQuiet("pending drafts must not appear automatically")
            runBlocking { store.automation.setPaused(true) }
            assertQuiet("paused")
            runBlocking { store.automation.setPaused(false) }
            assertQuiet("resumed")
            shell(automation, "input keyevent 26")
            await("locked") { !AccessibilityStatus.runtime.value.unlocked }
            assertQuiet("locked")
            shell(automation, "input keyevent 224")
            shell(automation, "wm dismiss-keyguard")
            await("unlocked") { AccessibilityStatus.runtime.value.unlocked }
            assertQuiet("old drafts must not reappear on unlock")
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, previousServices)
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, previousEnabled)
            await("service disconnected") { !AccessibilityStatus.runtime.value.connected }
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services)
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            await("service reconnected") { AccessibilityStatus.runtime.value.connected }
            assertQuiet("old drafts must not reappear on service reconnect")
            pending.forEach { entry ->
                val current = runBlocking { store.recognitionQueue.read(entry.id)!! }
                assertEquals(QueueState.PENDING, current.state)
                assertNull(current.billId)
            }
            assertEquals(balances, runBlocking { store.catalog.observeAccounts().first().associate { it.id to it.balance } })
            app.startActivity(Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_QUEUE)
                .putExtra(MainActivity.EXTRA_REVIEW_ID, pending.last().id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            await("manual review remains available") {
                node(automation) { it.isEditable && it.text?.toString() == "0.01" } != null
            }
            assertNull(overlayView())
            shell(automation, "input keyevent 3")
            assertQuiet("leaving app must not reopen pending drafts")
        } finally {
            runBlocking { store.automation.setEnabled(false) }
            shell(automation, "input keyevent 224")
            shell(automation, "wm dismiss-keyguard")
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, previousServices)
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, previousEnabled)
            automation.dropShellPermissionIdentity()
        }
    }

    private fun assertQuiet(stage: String) {
        val end = android.os.SystemClock.uptimeMillis() + 2_000
        while (android.os.SystemClock.uptimeMillis() < end) {
            assertNull(stage, overlayView())
            Thread.sleep(50)
        }
    }
    private fun node(automation: UiAutomation, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { result -> return result } }
            return null
        }
        return automation.windows.sortedByDescending { it.layer }.firstNotNullOfOrNull { it.root?.let(::find) }
    }
    private fun overlayView(): View? {
        var view: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { it.findViewById(R.id.recognition_overlay) }
        }
        return view
    }
    private fun shell(automation: UiAutomation, command: String) {
        automation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }
    private fun await(stage: String, condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 10_000
        while (android.os.SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(50) }
        fail("Timed out: $stage")
    }
}
