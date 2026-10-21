package app.omymoney

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import app.omymoney.core.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
@OptIn(ExperimentalTestApi::class)
class AttachmentUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = (compose.activity.application as OMyMoneyApplication).container.store

    @Test fun savedImageOpensAndMissingImageCanBeRemovedWithoutChangingTheBill() {
        val book = Book(UUID.randomUUID().toString(), "图片 ${UUID.randomUUID().toString().take(6)}")
        val source = insertReceiptFixture(compose.activity)
        lateinit var ref: AttachmentRef
        val id = runBlocking {
            store.catalog.ensureLocalDefaults(); store.catalog.saveBook(book)
            ref = store.attachments.`import`(source)
            val result = store.ledger.save(store.catalog.initialDraft(book.id).copy(amount = Money(1230, CurrencySpec.CNY),
                note = "带有合成测试收据", attachments = listOf(ref)), null)
            assertTrue(result is SaveResult.Saved)
            (result as SaveResult.Saved).billId
        }
        compose.activity.contentResolver.delete(source, null, null)
        waitFor("bill_row_$id")
        compose.onNodeWithTag("bill_row_$id").performClick()
        compose.onNodeWithTag("attachment_gallery").performScrollTo()
        compose.onNodeWithTag("attachment_${ref.id}").performClick()
        waitFor("close_image")
        compose.onNodeWithContentDescription("放大").assertIsEnabled().performClick()
        compose.onNodeWithTag("image_canvas").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))
        compose.onNodeWithTag("image_canvas").performSemanticsAction(SemanticsActions.RequestFocus)
        val before = compose.onNodeWithTag("image_canvas").fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        compose.onNodeWithTag("image_canvas").performKeyInput { pressKey(Key.DirectionRight) }
        val after = compose.onNodeWithTag("image_canvas").fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        assertTrue(after > before)
        compose.waitForIdle()
        captureUiEvidence("a7-attachment-viewer.png")
        compose.onNodeWithTag("close_image").performClick()
        compose.onNodeWithTag("bill_edit").performClick()
        waitFor("bill_amount")
        runBlocking { assertTrue(store.attachments.verifiedFile(ref).delete()) }
        compose.activityRule.scenario.recreate()
        waitFor("bill_amount")
        compose.onNodeWithTag("attachment_gallery").performScrollTo()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("attachment_missing_${ref.id}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("attachment_missing_${ref.id}", useUnmergedTree = true).assertIsDisplayed()
        compose.waitForIdle()
        captureUiEvidence("a7-missing-attachment.png")
        compose.onNodeWithTag("remove_attachment_${ref.id}").performClick()
        compose.onNodeWithTag("bill_save").performClick()
        waitFor("bill_detail")
        val bill = runBlocking { store.ledger.observeBill(id).first()!! }
        assertEquals(1230L, bill.amount.minor)
        assertTrue(bill.attachments.isEmpty())
        assertEquals(2L, bill.revision)
    }
    private fun waitFor(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
}

@androidx.annotation.RequiresApi(29)
internal fun insertReceiptFixture(context: android.content.Context): Uri {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "OMyMoney-QA-${UUID.randomUUID().toString().take(6)}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/OMyMoneyQA")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
    val image = Bitmap.createBitmap(720, 1000, Bitmap.Config.ARGB_8888)
    image.eraseColor(Color.WHITE)
    val canvas = Canvas(image)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.rgb(20, 110, 80)
    canvas.drawRect(0f, 0f, 720f, 80f, paint)
    paint.color = Color.BLACK; paint.textSize = 32f
    canvas.drawText("OMYMONEY TEST RECEIPT", 40f, 180f, paint)
    canvas.drawText("SYNTHETIC TEST DATA", 40f, 250f, paint)
    canvas.drawText("Lunch", 40f, 410f, paint)
    canvas.drawText("CNY 12.30", 480f, 410f, paint)
    canvas.drawLine(40f, 470f, 680f, 470f, paint)
    canvas.drawText("Total: CNY 12.30", 40f, 540f, paint)
    context.contentResolver.openOutputStream(uri)!!.use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    image.recycle()
    context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    return uri
}
