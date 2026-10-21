package app.omymoney

import android.content.Intent
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.core.model.*
import app.omymoney.platform.security.LaunchRequest
import app.omymoney.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Dedicated owned-emulator run: the runner provisions and removes its test-only PIN. */
class PrivacyNativeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as OMyMoneyApplication).container
    private fun waitFor(tag: String) = withFailureEvidence("c10-privacy-wait-$tag") {
        try {
            compose.waitUntil(15_000) {
                runCatching { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
            }
        } catch (failure: Throwable) {
            throw AssertionError("Missing $tag; privacy=${container.privacyLock.state.value}", failure)
        }
    }
    private fun scroll(tag: String) { compose.onNodeWithTag("preferences_screen").performScrollToNode(hasTestTag(tag)) }
    private fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList() else
        listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    private fun credential(pin: String = "2468") {
        val end = SystemClock.uptimeMillis() + 10_000
        var edit: AccessibilityNodeInfo? = null
        while (SystemClock.uptimeMillis() < end && edit == null) {
            val tree = nodes(instrumentation.uiAutomation.rootInActiveWindow)
            edit = tree.firstOrNull {
                it.packageName?.toString() in setOf("com.android.settings", "com.android.systemui") &&
                    it.className?.toString()?.endsWith("EditText") == true
            }
            if (edit == null) {
                tree.firstOrNull { it.text?.toString()?.contains("Use PIN", true) == true }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                SystemClock.sleep(100)
            }
        }
        assertNotNull("System device-credential field must be visible", edit)
        edit!!.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        instrumentation.uiAutomation.waitForIdle(350, 3_000)
        shell("input text $pin")
        shell("input keyevent 66")
    }
    private fun capture(name: String) { compose.waitForIdle(); captureUiEvidence(name) }
    private fun unlockDevice() {
        shell("input keyevent 224")
        instrumentation.uiAutomation.waitForIdle(350, 5_000)
        shell("input keyevent 82")
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        if (!keyguard.isDeviceLocked) { shell("wm dismiss-keyguard"); return }
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (nodes(instrumentation.uiAutomation.rootInActiveWindow).none { it.viewIdResourceName?.endsWith("/pinEntry") == true } &&
            SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        captureUiEvidence("c8-keyguard.png")
        for (key in listOf("key2", "key4", "key6", "key8", "key_enter")) {
            val button = nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull { it.viewIdResourceName?.endsWith("/$key") == true }
            assertNotNull("Native PIN keypad control $key must be available", button)
            assertTrue(button!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        val unlocked = SystemClock.uptimeMillis() + 10_000
        while (keyguard.isDeviceLocked && SystemClock.uptimeMillis() < unlocked) SystemClock.sleep(100)
        assertFalse("System device credential must unlock the owned emulator", keyguard.isDeviceLocked)
    }

    @Test fun systemCredentialsCancellationBackgroundRotationShortcutsAndSecureScreenshot() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("privacyNative") == "true")
        assertTrue(context.getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure)
        unlockDevice()
        val book = Book(UUID.randomUUID().toString(), "Native private book")
        runBlocking { container.store.catalog.ensureLocalDefaults(); container.store.catalog.saveBook(book); container.store.catalog.selectBook(book.id) }
        val initial = Intent(context, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(initial).use { scenario ->
            waitFor("nav_settings"); compose.onNodeWithTag("nav_settings").performClick()
            compose.onNodeWithTag("settings_preferences").performClick()
            compose.onNodeWithTag("theme_DARK").performClick()
            scenario.recreate(); waitFor("preferences_screen")
            assertEquals(ThemeMode.DARK, container.preferences.state.value.theme)
            capture("c8-settings.png")
            scroll("privacy_enable"); compose.onNodeWithTag("privacy_enable").performClick()
            credential()
            withFailureEvidence("c10-system-enable-failure") {
                try {
                    compose.waitUntil(15_000) { container.privacyLock.state.value.enabled && !container.privacyLock.state.value.authenticating }
                } catch (failure: Throwable) {
                    throw AssertionError("Native enable result: ${container.privacyLock.state.value}", failure)
                }
            }
            assertFalse(container.privacyLock.state.value.locked)
            scenario.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
            scenario.recreate(); waitFor("preferences_screen")
            assertFalse("Rotation must not relock", container.privacyLock.state.value.locked)
            shell("input keyevent 3")
            compose.waitUntil(10_000) { container.privacyLock.state.value.locked }
            lateinit var launchHost: MainActivity
            scenario.onActivity { launchHost = it; it.startActivity(LaunchRequest.intent(it, book.id, true)) }
            waitFor("privacy_locked")
            compose.onNodeWithTag("bill_amount").assertDoesNotExist()
            // ActivityScenario filters lifecycle events by the original intent; restore its
            // test identity while the app retains the separate, saved pending request.
            instrumentation.runOnMainSync { launchHost.intent = initial; launchHost.recreate() }
            waitFor("privacy_locked")
            scenario.onActivity { it.startActivity(Intent(it, app.omymoney.feature.automation.ScreenshotImportActivity::class.java)) }
            waitFor("privacy_locked")
            compose.onNodeWithTag("ocr_choose").assertDoesNotExist()
            shell("input keyevent 4")
            waitFor("privacy_locked")
            compose.onNodeWithTag("privacy_unlock").performClick()
            credential("1357")
            assertTrue("A wrong system PIN must never unlock private content", container.privacyLock.state.value.locked)
            SystemClock.sleep(500)
            shell("input keyevent 4")
            // The first Back may only dismiss the credential keyboard.
            val cancellationDeadline = SystemClock.uptimeMillis() + 1_000
            while (container.privacyLock.state.value.authenticating && SystemClock.uptimeMillis() < cancellationDeadline) SystemClock.sleep(50)
            if (container.privacyLock.state.value.authenticating) shell("input keyevent 4")
            compose.waitUntil(10_000) { !container.privacyLock.state.value.authenticating }
            assertTrue(container.privacyLock.state.value.locked)
            compose.onNodeWithTag("bill_amount").assertDoesNotExist()
            compose.onNodeWithTag("privacy_unlock").performClick(); credential()
            waitFor("bill_amount")
            compose.onNodeWithTag("bill_amount").performTextReplacement("91.23")
            val screen = instrumentation.uiAutomation.takeScreenshot()
            if (screen != null) {
                val field = compose.onNodeWithTag("bill_amount").fetchSemanticsNode().boundsInWindow.center
                val center = screen.getPixel(field.x.toInt().coerceIn(0, screen.width - 1), field.y.toInt().coerceIn(0, screen.height - 1))
                assertEquals("FLAG_SECURE must blank the private window in native capture", android.graphics.Color.BLACK, center)
            }
            shell("input keyevent 3")
            compose.waitUntil(10_000) { container.privacyLock.state.value.locked }
            // Return to the existing host even when the legacy credential task was
            // above it; SINGLE_TOP alone can create a second MainActivity on API 26.
            scenario.onActivity { it.startActivity(Intent(it, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            waitFor("privacy_locked"); compose.onNodeWithTag("privacy_unlock").performClick(); credential()
            waitFor("bill_amount"); compose.onNodeWithTag("bill_amount").assertTextContains("91.23")
            assertEquals(book.id, runBlocking { container.store.catalog.observeCurrentBookId().first() })
            shell("input keyevent 223")
            compose.waitUntil(10_000) { container.privacyLock.state.value.locked }
            unlockDevice()
            waitFor("privacy_locked")
            compose.onNodeWithTag("bill_amount").assertDoesNotExist()
            compose.onNodeWithTag("privacy_unlock").performClick(); credential()
            waitFor("bill_amount"); compose.onNodeWithTag("bill_amount").assertTextContains("91.23")
            // Leave the app unlocked and disable protection through the same system verification path.
            scenario.onActivity { container.privacyLock.setEnabled(it, false) }; credential()
            compose.waitUntil(10_000) { !container.privacyLock.state.value.enabled }
        }
    }
}
