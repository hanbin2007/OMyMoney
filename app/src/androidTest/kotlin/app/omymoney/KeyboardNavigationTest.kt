package app.omymoney

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.core.model.*
import app.omymoney.data.CatalogResult
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class KeyboardNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun nativeTabTextAndEnterCanCreateABillWithoutTouchingItsControls() {
        val store = (compose.activity.application as OMyMoneyApplication).container.store
        val book = Book(UUID.randomUUID().toString(), "键盘验收 ${UUID.randomUUID().toString().take(6)}")
        runBlocking { store.catalog.ensureLocalDefaults(); assertTrue(store.catalog.saveBook(book) is CatalogResult.Saved) }
        compose.waitUntil(5000) { compose.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
        focusWithTabs("add_bill")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("bill_amount") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        instrumentation.sendStringSync("9.50")
        compose.onNodeWithTag("bill_amount").assertTextContains("9.50")
        focusWithTabs("bill_save", reverse = true)
        captureUiEvidence("c10-keyboard-save.png")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(5000) { runBlocking { store.ledger.observeBills(BillQuery(bookId = book.id)).first().size == 1 } }
        assertEquals(950L, runBlocking { store.ledger.observeBills(BillQuery(bookId = book.id)).first().single().amount.minor })
        compose.onNodeWithTag("add_bill").assertIsDisplayed()
    }

    private fun focusWithTabs(tag: String, reverse: Boolean = false) {
        repeat(80) {
            if (compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()) return
            if (reverse) {
                val now = android.os.SystemClock.uptimeMillis()
                instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, KeyEvent.META_SHIFT_ON))
                instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 0, KeyEvent.META_SHIFT_ON))
            } else instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            compose.waitForIdle()
        }
        withFailureEvidence("failure-keyboard-$tag") { compose.onNodeWithTag(tag).assertIsFocused() }
    }
}
