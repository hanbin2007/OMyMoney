package app.omymoney

import android.graphics.*
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.omymoney.feature.automation.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class ScreenshotUiTest {
    @get:Rule val compose = createAndroidComposeRule<ScreenshotImportActivity>()
    @Test fun chosenScreenshotShowsRealQueueOutcomeAndAReviewAction() {
        compose.onNodeWithTag("ocr_choose").assertIsDisplayed()
        compose.onNodeWithTag("ocr_start").assertIsNotEnabled()
        val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f }
            listOf("微信支付", "支付成功", "实付金额 12.30", "支付时间", "2026-10-11 19:59:30")
                .forEachIndexed { i, text -> drawText(text, 40f, 120f + 140f * i, paint) }
        }
        val uri = Uri.parse("content://app.omymoney.android.test.ocr/receipt.png")
        compose.activity.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        compose.runOnIdle { compose.activity.importer.accept(listOf(uri)) }
        compose.onNodeWithTag("ocr_start").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("已加入识别记录").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("核对草稿").assertIsDisplayed()
        captureUiEvidence("b12-screenshot-queued.png")
        compose.onNodeWithContentDescription("核对草稿").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("bill_amount").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bill_amount").assertTextContains("12.30")
        captureUiEvidence("b12-screenshot-review.png")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().forEach { it.finish() }
        }
    }

    @Test fun batchCannotBeAbandonedByReviewingAnAlreadyQueuedImage() {
        val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f }
            listOf("微信支付", "支付成功", "实付金额 12.30", "支付时间", "2026-10-11 19:59:30")
                .forEachIndexed { i, text -> drawText(text, 40f, 120f + 140f * i, paint) }
        }
        val resolver = compose.activity.contentResolver
        val uri = Uri.parse("content://app.omymoney.android.test.ocr/receipt.png")
        val blocked = Uri.parse("content://app.omymoney.android.test.ocr/blocked")
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            compose.runOnIdle { compose.activity.importer.accept(listOf(uri, blocked)); compose.activity.importer.start() }
            compose.waitUntil(15000) { compose.activity.importer.state.value.items.first().stage == ScreenshotStage.QUEUED &&
                resolver.call(blocked, "opened", null, null)!!.getBoolean("opened") }
            compose.onNodeWithContentDescription("核对草稿").assertIsNotEnabled()
            assertTrue(compose.activity.importer.state.value.busy)
            compose.runOnIdle { compose.activity.importer.cancel() }
            compose.waitUntil(5000) { !compose.activity.importer.state.value.busy }
            compose.onNodeWithContentDescription("核对草稿").assertIsEnabled()
            assertEquals(ScreenshotStage.CANCELLED, compose.activity.importer.state.value.items[1].stage)
        } finally {
            compose.runOnIdle { compose.activity.importer.cancel() }
            resolver.call(blocked, "close", null, null)
        }
    }
}
