package app.omymoney

import android.content.Context
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.core.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class AttachmentProcessTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun savedBillAndPrivateAttachmentSurviveProcessRestart() {
        val phase = InstrumentationRegistry.getArguments().getString("processPhase")
        assumeTrue("Run prepare and verify with a force-stop between them", phase in listOf("prepare", "verify", "pickerVerify"))
        val store = (compose.activity.application as OMyMoneyApplication).container.store
        val preferences = compose.activity.getSharedPreferences("qa-attachment-process", Context.MODE_PRIVATE)
        if (phase == "prepare") {
            val book = Book(UUID.randomUUID().toString(), "图片重启 ${UUID.randomUUID().toString().take(6)}")
            val source = insertReceiptFixture(compose.activity)
            val saved = runBlocking {
                store.catalog.ensureLocalDefaults(); store.catalog.saveBook(book)
                val ref = store.attachments.`import`(source)
                store.ledger.save(store.catalog.initialDraft(book.id).copy(amount = Money(1230, CurrencySpec.CNY),
                    note = "进程重启后回读的合成收据", attachments = listOf(ref)), null) as SaveResult.Saved
            }
            assertTrue(preferences.edit().putString("billId", saved.billId).putString("bookId", book.id).putString("source", source.toString()).commit())
        } else if (phase == "pickerVerify") {
            val book = requireNotNull(preferences.getString("bookId", null))
            val originalId = requireNotNull(preferences.getString("billId", null))
            runBlocking {
                val bills = store.ledger.observeBills(BillQuery(bookId = book)).first()
                assertEquals(2, bills.size)
                val original = bills.single { it.id == originalId }
                val added = bills.single { it.id != originalId }
                assertEquals(500L, added.amount.minor)
                assertEquals(original.attachments.single().sha256, added.attachments.single().sha256)
                assertTrue(store.attachments.verifiedFile(added.attachments.single()).isFile)
            }
            preferences.getString("source", null)?.let { compose.activity.contentResolver.delete(Uri.parse(it), null, null) }
            assertTrue(preferences.edit().clear().commit())
        } else {
            val id = requireNotNull(preferences.getString("billId", null))
            val bill = runBlocking { store.ledger.observeBill(id).first()!! }
            assertEquals(1230L, bill.amount.minor)
            val bitmap = runBlocking { store.attachments.preview(bill.attachments.single()) }
            assertEquals(Color.rgb(20, 110, 80), bitmap.getPixel(20, 20))
            bitmap.recycle()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("bill_row_$id").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("bill_row_$id").performClick()
            compose.onNodeWithTag("attachment_gallery").performScrollTo()
            compose.onNodeWithTag("attachment_${bill.attachments.single().id}").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("close_image").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            captureUiEvidence("a7-attachment-reopened.png")
            compose.onNodeWithTag("close_image").performClick()
            preferences.getString("source", null)?.let { compose.activity.contentResolver.delete(Uri.parse(it), null, null) }
            assertTrue(preferences.edit().clear().commit())
        }
    }
}
