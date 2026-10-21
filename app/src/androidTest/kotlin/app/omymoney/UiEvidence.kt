package app.omymoney

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

internal fun captureUiEvidence(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    instrumentation.uiAutomation.waitForIdle(200, 5000)
    val bitmap = instrumentation.uiAutomation.takeScreenshot()
    assertNotNull("Native screenshot unavailable", bitmap)
    val directory = File(instrumentation.targetContext.filesDir, "qa").apply { mkdirs() }
    File(directory, name).outputStream().use { assertTrue(bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it)) }
}

internal fun <T> withFailureEvidence(name: String, block: () -> T): T = try { block() } catch (failure: Throwable) {
    runCatching {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val text = StringBuilder()
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
            if (depth > 30 || text.length > 100_000) return
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            text.append("  ".repeat(depth)).append("${node.className} text=${node.text} desc=${node.contentDescription} focused=${node.isFocused} clickable=${node.isClickable} enabled=${node.isEnabled} visible=${node.isVisibleToUser} bounds=$bounds\n")
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth + 1) }
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let { visit(it, 0) }
        File(instrumentation.targetContext.filesDir, "qa/$name.txt").writeText(text.toString())
    }
    runCatching { captureUiEvidence("$name.png") }
    throw failure
}
