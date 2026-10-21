package app.omymoney

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.omymoney.core.model.Book
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BillTimePickerTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun dateAndTimeConfirmationAreVisibleAndCancellable() {
        val book = Book(UUID.randomUUID().toString(), "时间 ${UUID.randomUUID().toString().take(6)}")
        val store = (compose.activity.application as OMyMoneyApplication).container.store
        runBlocking { store.catalog.ensureLocalDefaults(); store.catalog.saveBook(book) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("add_bill").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("bill_amount").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bill_amount").performTextInput("10")
        compose.onNodeWithTag("bill_time").performScrollTo().performClick()
        compose.onNodeWithText("下一步").assertIsDisplayed()
        compose.waitForIdle()
        captureUiEvidence("a6-date-picker.png")
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithText("确定").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.waitForIdle()
        captureUiEvidence("a6-time-picker.png")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("bill_amount").assertTextContains("10")
        compose.onNodeWithTag("screen_back").performClick()
        compose.onNodeWithText("放弃草稿").performClick()
    }
}
