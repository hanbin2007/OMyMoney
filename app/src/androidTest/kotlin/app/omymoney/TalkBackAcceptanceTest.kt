package app.omymoney

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs only with the real TalkBack service enabled on a dedicated test emulator. */
class TalkBackAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val journal = mutableListOf<String>()

    @Test fun realTalkBackFocusTraversalAndDoubleTapReachTheFourPrimaryDestinations() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("talkbackQa") == "true")
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val service = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN)
            .singleOrNull { it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback" }
        assertNotNull("The actual installed TalkBack service must be running", service)
        await { manager.isTouchExplorationEnabled }
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await { automation.rootInActiveWindow?.packageName == context.packageName }
        try {
            for (label in listOf("账单", "资产", "统计", "设置")) {
                val target = find(automation, label)
                assertTrue("Named navigation target must be clickable: $label", target.isClickable)
                assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                await { focused(automation)?.contains(label) == true }
                journal += "focus=$label; node=${focused(automation)}"
                doubleTap(automation)
                await { findOrNull(automation, label)?.isSelected == true }
                journal += "activated=$label; selected=true"
            }
            assertNotNull(findOrNull(automation, "自动记账"))
            val before = focused(automation)
            swipeRight(automation)
            await { focused(automation) != null && focused(automation) != before }
            journal += "TalkBack swipe-right: $before -> ${focused(automation)}"
            save(automation, "c10-talkback-settings.png")
            val add = findOrNull(automation, "记一笔")
            if (add != null) {
                assertTrue(add.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                doubleTap(automation)
            } else {
                val bills = find(automation, "账单")
                assertTrue(bills.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                doubleTap(automation)
                val create = find(automation, "记一笔")
                assertTrue(create.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                doubleTap(automation)
            }
            await { findOrNull(automation, "保存") != null }
            assertNotNull(findOrNull(automation, "金额"))
            journal += "editor opened by TalkBack double-tap; amount and save are named"
            save(automation, "c10-talkback-editor.png")
        } finally {
            File(context.filesDir, "qa").apply { mkdirs() }.resolve("c10-talkback.txt").writeText(journal.joinToString("\n"))
        }
    }

    private fun focused(automation: UiAutomation): String? {
        val root = automation.rootInActiveWindow ?: return null
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) ?: return null
        return "${node.text?.toString().orEmpty()}|${node.contentDescription?.toString().orEmpty()}|${node.className}|selected=${node.isSelected}"
    }
    private fun find(automation: UiAutomation, label: String): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = null
        await { findOrNull(automation, label).also { node = it } != null }
        return requireNotNull(node)
    }
    private fun findOrNull(automation: UiAutomation, label: String): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        return root.findAccessibilityNodeInfosByText(label).firstOrNull {
            it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label)
        }
    }
    private fun doubleTap(automation: UiAutomation) {
        val bounds = Rect().also { automation.rootInActiveWindow.getBoundsInScreen(it) }
        val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
        repeat(2) {
            val start = SystemClock.uptimeMillis()
            event(automation, start, start, MotionEvent.ACTION_DOWN, x, y)
            event(automation, start, start + 45, MotionEvent.ACTION_UP, x, y)
            SystemClock.sleep(90)
        }
        SystemClock.sleep(350)
    }
    private fun swipeRight(automation: UiAutomation) {
        val bounds = Rect().also { automation.rootInActiveWindow.getBoundsInScreen(it) }
        val start = SystemClock.uptimeMillis()
        val x = bounds.left + bounds.width() * 0.3f
        val end = bounds.left + bounds.width() * 0.7f
        val y = bounds.exactCenterY()
        event(automation, start, start, MotionEvent.ACTION_DOWN, x, y)
        for (step in 1..8) {
            SystemClock.sleep(15)
            event(automation, start, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x + (end - x) * step / 8, y)
        }
        event(automation, start, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, end, y)
    }
    private fun event(automation: UiAutomation, down: Long, time: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, time, action, x, y, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail("TalkBack native state did not arrive; journal=$journal")
    }
    private fun save(automation: UiAutomation, name: String) {
        automation.waitForIdle(300, 5000)
        val folder = File(context.filesDir, "qa").apply { mkdirs() }
        File(folder, name).outputStream().use { assertTrue(automation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
