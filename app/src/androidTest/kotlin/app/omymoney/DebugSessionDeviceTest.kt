package app.omymoney

import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.platform.accessibility.AccessibilityStatus
import app.omymoney.platform.diagnostics.DebugSessionService
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import java.time.Instant
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Dedicated emulator only: grants and revokes this app's own permissions. */
class DebugSessionDeviceTest {
    @Test fun continuousRecordingHasHeartbeatNotificationStopAndPermissionRevocation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("debugDeviceTest") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val context = instrumentation.targetContext.applicationContext as OMyMoneyApplication
        val container = context.container
        val notifications = context.getSystemService(NotificationManager::class.java)
        fun shell(command: String) = automation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().readText()
        }
        shell("cmd statusbar collapse")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        // Settings caches its downloaded-services list across UTP uninstall/reinstall.
        shell("am force-stop com.android.settings")
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        click(automation, "自动记账", skipIf = { hasText(automation, "Use 自动记账") })
        val configured = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').contains("${context.packageName}/app.omymoney.platform.accessibility.LedgerAccessibilityService")
        if (configured) {
            click(automation, "Use 自动记账")
            click(automation, "Turn off")
            await { !AccessibilityStatus.granted(context) }
        }
        click(automation, "Use 自动记账")
        click(automation, "Allow")
        await { AccessibilityStatus.runtime.value.connected }
        context.startActivity(Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_DIAGNOSTICS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        await { container.appVisible.value }
        runBlocking { container.diagnostics.setEnabled(true) }
        instrumentation.runOnMainSync {
            container.debugSession.start()
            container.applicationScope.launch(start = CoroutineStart.UNDISPATCHED) { container.debugSession.stop() }
        }
        await { !container.debugSession.state.value.stopping && !container.debugSession.state.value.active && !container.diagnostics.enabled }
        assertTrue(notifications.activeNotifications.none { it.id == DebugSessionService.ID })
        click(automation, "记录诊断日志")
        click(automation, "同意并开始记录")
        val recordingStarted = Instant.now()
        click(automation, "开始持续调试")
        await { container.debugSession.state.value.active && notifications.activeNotifications.any { it.id == 1102 } }
        await { hasText(automation, "停止调试与记录") }
        save(automation, "debug-active.png")
        val notification = notifications.activeNotifications.single { it.id == 1102 }.notification
        assertTrue(notification.flags and android.app.Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertEquals(1, notification.actions.size)
        shell("input keyevent KEYCODE_HOME")
        shell("cmd statusbar expand-notifications")
        await { hasText(automation, "持续调试正在记录") }
        save(automation, "debug-notification.png")
        expandNotification(automation)
        await { hasText(automation, "停止调试与记录") }
        save(automation, "debug-notification-expanded.png")
        shell("cmd statusbar collapse")
        // Real minute cadence, not an injected timer, proves background runtime evidence.
        Thread.sleep(62_000)
        assertTrue(container.debugSession.state.value.active)
        val output = ByteArrayOutputStream()
        runBlocking { container.diagnostics.export(output) }
        val records = buildString {
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name.endsWith(".gz")) append(GZIPInputStream(zip.readBytes().inputStream()).bufferedReader().readText())
                }
            }
        }
        val heartbeat = records.lineSequence().filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }.filter {
            it["kind"]?.jsonPrimitive?.content == "runtime_snapshot" &&
                it["fields"]?.jsonObject?.get("reason")?.jsonPrimitive?.content == "debug_heartbeat" &&
                Instant.parse(it.getValue("at").jsonPrimitive.content) >= recordingStarted
        }.toList()
        assertTrue(heartbeat.size >= 2)
        assertTrue(heartbeat.any { it.getValue("fields").jsonObject["appVisible"]?.jsonPrimitive?.boolean == false })
        notification.contentIntent.send()
        await { hasText(automation, "停止调试与记录") }
        shell("input keyevent KEYCODE_HOME")
        notification.actions.single().actionIntent.send()
        await { !container.debugSession.state.value.active && !container.diagnostics.enabled && notifications.activeNotifications.none { it.id == 1102 } }
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        await { container.appVisible.value }
        await { !container.debugSession.state.value.stopping }
        assertFalse(container.debugSession.state.value.active)
        runBlocking { container.diagnostics.setEnabled(true) }
        instrumentation.runOnMainSync { container.debugSession.start() }
        await { container.debugSession.state.value.active }
        shell("am force-stop com.android.settings")
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        click(automation, "自动记账", skipIf = { hasText(automation, "Use 自动记账") })
        click(automation, "Use 自动记账")
        click(automation, "Turn off")
        await { !AccessibilityStatus.granted(context) && !container.debugSession.state.value.active && !container.diagnostics.enabled }
        assertTrue(notifications.activeNotifications.none { it.id == DebugSessionService.ID })
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        await { container.appVisible.value }
    }

    private fun hasText(automation: UiAutomation, text: String): Boolean {
        val root = automation.rootInActiveWindow ?: return false
        return try {
            val node = findText(root, text)
            try { node != null } finally { @Suppress("DEPRECATION") node?.recycle() }
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun expandNotification(automation: UiAutomation) {
        // Android may first expand an automatic group, then its individual notification.
        repeat(3) {
            if (hasText(automation, "停止调试与记录")) return
            val root = requireNotNull(automation.rootInActiveWindow)
            val controls = StringBuilder()
            fun inspect(node: AccessibilityNodeInfo) {
                if (node.text?.toString() == "持续调试正在记录" || node.contentDescription?.toString() in setOf("Expand", "Collapse")) {
                    val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                    controls.append("${node.text}|${node.contentDescription}|${node.viewIdResourceName}|${node.isVisibleToUser}|$bounds\n")
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                    try { inspect(child) } finally { child.recycle() }
                }
            }
            inspect(root)
            InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("notificationControls", controls.toString()) })
            var parent = findText(root, "持续调试正在记录")
            var clicked = false
            try {
                val titleBounds = android.graphics.Rect().also { parent?.getBoundsInScreen(it) }
                val aligned = findText(root, "Expand", titleBounds.centerY())
                if (aligned != null) {
                    try { clicked = tap(automation, aligned) }
                    finally { aligned.recycle() }
                }
                while (!clicked && parent != null) {
                    val expand = findText(parent, "Expand")
                    if (expand != null) {
                        try { clicked = expand.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                        finally { expand.recycle() }
                        break
                    }
                    val next = parent.parent
                    parent.recycle(); parent = next
                }
            } finally { parent?.recycle(); root.recycle() }
            assertTrue("Notification expansion control missing", clicked)
            automation.waitForIdle(300, 5000)
        }
        save(automation, "debug-notification-expansion.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "screencap -p /sdcard/omymoney-qa-debug-expansion.png")).use { it.readBytes() }
        assertTrue("Expanded notification must expose Stop", hasText(automation, "停止调试与记录"))
    }

    private fun tap(automation: UiAutomation, node: AccessibilityNodeInfo): Boolean {
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        val at = SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(at, at, android.view.MotionEvent.ACTION_DOWN,
            bounds.exactCenterX(), bounds.exactCenterY(), 0)
        val up = android.view.MotionEvent.obtain(at, at + 50, android.view.MotionEvent.ACTION_UP,
            bounds.exactCenterX(), bounds.exactCenterY(), 0)
        down.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        up.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        return try { automation.injectInputEvent(down, true) && automation.injectInputEvent(up, true) }
        finally { down.recycle(); up.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun findText(root: AccessibilityNodeInfo, text: String, alignedY: Int? = null): AccessibilityNodeInfo? {
        val bounds = android.graphics.Rect().also(root::getBoundsInScreen)
        if ((root.text?.toString() == text || root.contentDescription?.toString() == text) &&
            (alignedY == null || alignedY in bounds.top until bounds.bottom)) return AccessibilityNodeInfo.obtain(root)
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            try { findText(child, text, alignedY)?.let { return it } } finally { child.recycle() }
        }
        return null
    }

    private fun click(automation: UiAutomation, text: String, skipIf: () -> Boolean = { false }) {
        await {
            if (skipIf()) return@await true
            val root = automation.rootInActiveWindow ?: return@await false
            try {
                val node = findText(root, text)
                try {
                    var target = node
                    while (target != null && !target.isClickable) target = target.parent
                    target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                } finally { @Suppress("DEPRECATION") node?.recycle() }
            } finally { @Suppress("DEPRECATION") root.recycle() }
        }
        automation.waitForIdle(300, 5000)
    }

    private fun await(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(50) }
        fail("Expected debug-session device state did not arrive")
    }

    private fun save(automation: UiAutomation, name: String) {
        automation.waitForIdle(500, 5000)
        Thread.sleep(400)
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "qa/debug-session").apply { mkdirs() }
        val config = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        File(folder, "${name.removeSuffix(".png")}_${config.screenWidthDp}_${config.fontScale}.png").outputStream().use {
            assertTrue(automation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
