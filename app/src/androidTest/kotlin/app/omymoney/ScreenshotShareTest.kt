package app.omymoney

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.feature.automation.ScreenshotImportActivity
import app.omymoney.feature.automation.ScreenshotStage
import org.junit.Assert.*
import org.junit.Test

class ScreenshotShareTest {
    @Test fun sharedImagesRequireAnExplicitStartAndUntrustedExtrasCannotReplaceSavedState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = Uri.parse("content://app.omymoney.android.test.ocr/receipt.png")
        val intent = Intent(context, ScreenshotImportActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra("images", arrayListOf(arrayListOf("malicious", "content://wrong/provider", "0", "QUEUED", "bad/id", "")))
        ActivityScenario.launch<ScreenshotImportActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val item = activity.importer.state.value.items.single()
                assertEquals(uri.toString(), item.uri)
                assertEquals(ScreenshotStage.READY, item.stage)
                assertNull(item.entryId)
                assertFalse(activity.importer.state.value.busy)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(uri.toString(), activity.importer.state.value.items.single().uri)
                assertEquals(ScreenshotStage.READY, activity.importer.state.value.items.single().stage)
            }
        }
    }
}
