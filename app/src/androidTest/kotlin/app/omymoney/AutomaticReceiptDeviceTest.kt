package app.omymoney

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.android.R
import app.omymoney.core.model.*
import app.omymoney.core.recognition.*
import app.omymoney.core.recognition.rules.AlipayRules
import app.omymoney.platform.accessibility.AccessibilityStatus
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AutomaticReceiptDeviceTest {
    @Test fun committedReceiptIsCompactNonFocusableEditableAndHiddenWhenLocked() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("automaticReceiptDevice") == "true")
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as OMyMoneyApplication
        val container = app.container
        val store = container.store
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        val resolver = app.contentResolver
        val previousServices = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val previousEnabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        val service = "app.omymoney.android/app.omymoney.platform.accessibility.LedgerAccessibilityService"
        try {
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                (previousServices.orEmpty().split(':').filter(String::isNotBlank) + service).distinct().joinToString(":"))
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            app.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("connected") { AccessibilityStatus.runtime.value.connected }
            val book = UUID.randomUUID().toString()
            val payee = "zjy ${book.take(4)}"
            runBlocking {
                store.catalog.saveBook(Book(book, "Receipt ${book.take(6)}"))
                store.automation.setPackage(AlipayRules.PACKAGE, true)
                store.automation.setPaused(false)
                store.automation.setEnabled(true)
            }
            fun save(window: Int) = runBlocking {
                val result = RuleEngine(AlipayRules.create(RecognitionContext(book))).recognize(UiSnapshot(AlipayRules.PACKAGE, window,
                    Instant.now(), listOf("支付成功0.01", payee, "0.01", "完成").map { UiNode(it) }))
                store.recognitionQueue.recordCapture(result, "receipt/$book/$window")
            }
            val first = save(1)
            await("saved receipt") { overlay() != null }
            await("this receipt text") { node(automation) { it.text?.toString()?.contains(payee) == true } != null }
            await("receipt measured") { overlay()?.let { it.width > 0 && it.height > 0 } == true }
            val root = requireNotNull(overlay())
            val params = root.layoutParams as WindowManager.LayoutParams
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertTrue(root.width < app.resources.displayMetrics.widthPixels)
            await("receipt text") { node(automation) { it.text?.toString()?.let { text -> "支出" in text && "已记账" in text } == true } != null }
            assertNotNull(node(automation) { it.text?.toString()?.contains(payee) == true })
            capture(root, "automatic-v5-capsule.png")
            runBlocking { store.ledger.delete(requireNotNull(first.billId)) }
            await("deleted receipt disappears", 1500) { overlay() == null }
            save(2)
            await("receipt before lock") { overlay() != null }
            shell(automation, "input keyevent 26")
            await("screen-off removes receipt") { overlay() == null && !AccessibilityStatus.runtime.value.unlocked }
            shell(automation, "input keyevent 224")
            shell(automation, "wm dismiss-keyguard")
            await("unlock state refreshed") { AccessibilityStatus.runtime.value.unlocked }
            save(3)
            await("second receipt") { overlay() != null }
            await("edit control") { node(automation) { it.contentDescription?.toString() == "编辑账单" } != null }
            val edit = node(automation) { it.contentDescription?.toString() == "编辑账单" } ?: error("Missing edit control")
            var clickable: AccessibilityNodeInfo? = edit
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
            assertTrue(clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            await("saved bill editor") { node(automation) { it.isEditable && it.text?.toString() == payee } != null }
            assertNull(overlay())
        } finally {
            runBlocking { store.automation.setEnabled(false) }
            shell(automation, "input keyevent 224")
            shell(automation, "wm dismiss-keyguard")
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, previousServices)
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, previousEnabled)
            automation.dropShellPermissionIdentity()
        }
    }
    private fun overlay(): View? {
        var result: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { it.findViewById(R.id.recognition_overlay) }
        }
        return result
    }
    private fun node(automation: UiAutomation, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { value -> return value } }
            return null
        }
        return automation.windows.sortedByDescending { it.layer }.firstNotNullOfOrNull { it.root?.let(::find) }
    }
    private fun capture(view: View, name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val folder = File(instrumentation.targetContext.filesDir, "qa").apply { mkdirs() }
            File(folder, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }
    private fun shell(automation: UiAutomation, command: String) {
        automation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }
    private fun await(stage: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + timeoutMillis
        while (android.os.SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(50) }
        fail("Timed out: $stage")
    }
}
