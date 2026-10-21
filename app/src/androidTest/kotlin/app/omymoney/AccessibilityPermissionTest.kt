package app.omymoney

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityManager
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.platform.accessibility.AccessibilityStatus
import app.omymoney.platform.accessibility.NodeSnapshotReader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in device test: changes only the dedicated emulator's OMyMoney accessibility grant. */
class AccessibilityPermissionTest {
    @Test fun systemGrantRevocationAndReconnectAreReflected() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("permissionDeviceTest") == "true")
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val context = instrumentation.targetContext
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        automation.waitForIdle(300, 5000)
        val root = requireNotNull(automation.rootInActiveWindow)
        try {
            val snapshot = NodeSnapshotReader().read(root, "com.android.settings")
            assertNotNull(snapshot)
            assertTrue(snapshot!!.nodes.isNotEmpty())
        } finally { @Suppress("DEPRECATION") root.recycle() }
        save(automation, "b10-system-settings.png")
        clickText(automation, "自动记账")
        save(automation, "b10-system-service.png")
        clickText(automation, "Use 自动记账")
        clickText(automation, "Allow")
        await { AccessibilityStatus.granted(context) && AccessibilityStatus.runtime.value.connected }
        save(automation, "b10-system-granted.png")
        val repository = (context.applicationContext as OMyMoneyApplication).container.store.automation
        val before = runBlocking { repository.read().settings }
        val packageName = "com.tencent.mm"
        fun activePackages() = context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .firstOrNull { it.resolveInfo.serviceInfo.packageName == context.packageName }?.packageNames.orEmpty().toSet()
        runBlocking { repository.setPackage(packageName, true); repository.setPaused(false); repository.setEnabled(true) }
        await { packageName in activePackages() }
        runBlocking { repository.setPaused(true) }
        await { packageName !in activePackages() }
        runBlocking { repository.setPaused(false) }
        await { packageName in activePackages() }
        runBlocking {
            repository.setEnabled(before.enabled); repository.setPaused(before.paused)
            repository.setPackage(packageName, packageName in before.packages)
        }
        clickText(automation, "Use 自动记账")
        clickText(automation, "Stop")
        await { !AccessibilityStatus.granted(context) && !AccessibilityStatus.runtime.value.connected }
        clickText(automation, "Use 自动记账")
        clickText(automation, "Allow")
        await { AccessibilityStatus.granted(context) && AccessibilityStatus.runtime.value.connected }
        clickText(automation, "Use 自动记账")
        clickText(automation, "Stop")
        await { !AccessibilityStatus.granted(context) && !AccessibilityStatus.runtime.value.connected }
    }
    private fun clickText(automation: UiAutomation, text: String) {
        var clicked = false
        await {
            val root = automation.rootInActiveWindow ?: return@await false
            try {
                val matches = root.findAccessibilityNodeInfosByText(text)
                try {
                    val node = matches.firstOrNull { it.text?.toString() == text }
                    var target = node
                    while (target != null && !target.isClickable) target = target.parent
                    clicked = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                } finally { @Suppress("DEPRECATION") matches.forEach { it.recycle() } }
            } finally { @Suppress("DEPRECATION") root.recycle() }
            clicked
        }
        automation.waitForIdle(300, 5000)
    }
    private fun await(condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 8000
        while (android.os.SystemClock.uptimeMillis() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail("Expected device state did not arrive")
    }
    private fun save(automation: UiAutomation, name: String) {
        automation.waitForIdle(500, 5000)
        Thread.sleep(400)
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "qa").apply { mkdirs() }
        File(folder, name).outputStream().use { assertTrue(automation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
