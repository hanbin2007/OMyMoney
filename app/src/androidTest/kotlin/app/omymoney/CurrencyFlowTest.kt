package app.omymoney

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.omymoney.core.model.*
import app.omymoney.data.EditorDraft
import app.omymoney.data.CatalogResult
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CurrencyFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = (compose.activity.application as OMyMoneyApplication).container.store

    @Test fun transferEditorShowsQuoteDateActualFeeAndSavesBothCurrencies() {
        val book = UUID.randomUUID().toString(); val from = UUID.randomUUID().toString(); val to = UUID.randomUUID().toString()
        val usd = CurrencySpec("USD", 2)
        runBlocking {
            assertTrue(store.catalog.saveBook(Book(book, "Transfer ${book.take(6)}")) is CatalogResult.Saved)
            assertTrue(store.catalog.saveAccount(AssetAccount(from, "USD ${from.take(6)}", usd, Money(10000, usd))) is CatalogResult.Saved)
            assertTrue(store.catalog.saveAccount(AssetAccount(to, "CNY ${to.take(6)}")) is CatalogResult.Saved)
            assertTrue(store.catalog.saveExchangeRate(ManualExchangeRate(usd, CurrencySpec.CNY, "7.2", java.time.LocalDate.of(2026, 10, 11))) is CatalogResult.Saved)
            store.catalog.selectBook(book)
            store.drafts.write(EditorDraft("manual/$book", TransactionDraft(book, kind = BillKind.TRANSFER,
                fromAccountId = from, toAccountId = to, occurredAt = java.time.Instant.now(), timeSource = TimeSource.USER), usd, "10.00"))
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Transfer ${book.take(6)}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("add_bill").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("bill_use_rate").fetchSemanticsNodes().isNotEmpty() }
        fun keyboardVisible() = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        compose.waitUntil(5000) { keyboardVisible() }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5000) { !keyboardVisible() }
        compose.onNodeWithTag("bill_use_rate").performScrollTo().assertIsDisplayed().performClick()
        try {
            compose.waitUntil(5000) { compose.onAllNodesWithTag("bill_exchange_amount").fetchSemanticsNodes().any {
                it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.EditableText) { androidx.compose.ui.text.AnnotatedString("") }.text == "72.00"
            } }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError(compose.onRoot().printToString(), error)
        }
        compose.onNodeWithTag("bill_exchange_date").performScrollTo().assertTextContains("2026-10-11")
        compose.onNodeWithTag("bill_exchange_amount").performScrollTo().performTextReplacement("71.99")
        compose.onNodeWithTag("bill_fee").performScrollTo().performTextReplacement("0.10")
        compose.waitForIdle()
        captureUiEvidence("c3-editor.png")
        compose.onNodeWithTag("bill_save").performClick()
        compose.waitUntil(5000) { runBlocking { store.ledger.observeBills(BillQuery(bookId = book)).first().size == 1 } }
        val bill = runBlocking { store.ledger.observeBills(BillQuery(bookId = book)).first().single() }
        assertEquals(Money(7199, CurrencySpec.CNY), bill.exchange!!.amount)
        assertEquals(Money(10, usd), bill.fee)
        compose.onNodeWithTag("bill_list").performScrollToNode(hasTestTag("bill_row_${bill.id}"))
        compose.onNodeWithTag("bill_row_${bill.id}").performClick()
        compose.onNodeWithTag("detail_exchange_amount").assertTextContains("CNY 71.99", substring = true)
        compose.onNodeWithTag("detail_fee").performScrollTo().assertTextContains("USD 0.10", substring = true)
        captureUiEvidence("c3-detail.png")
    }

    @Test fun manualQuoteCanBeCreatedEditedAndRemovedWithDateVisible() {
        runBlocking { store.catalog.removeExchangeRate(CurrencySpec("USD", 2), CurrencySpec.CNY) }
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_currencies").performScrollTo().performClick()
        compose.onNodeWithTag("rate_add").performClick()
        compose.onNodeWithTag("rate_source").performClick()
        compose.onNodeWithTag("option_USD").performScrollTo().performClick()
        compose.onNodeWithTag("rate_value").performScrollTo().performTextReplacement("7.2")
        compose.onNodeWithTag("rate_date").performScrollTo().performTextReplacement("2026-10-11")
        compose.onNodeWithTag("rate_save").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("rate_edit_USD_CNY").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("rate_list").performScrollToNode(hasTestTag("rate_edit_USD_CNY"))
        compose.onNodeWithText("2026-10-11", substring = true).assertIsDisplayed()
        captureUiEvidence("c3-rates.png")
        compose.onNodeWithTag("rate_edit_USD_CNY").performClick()
        compose.onNodeWithTag("rate_value").performScrollTo().performTextReplacement("7.3")
        compose.onNodeWithTag("rate_save").performClick()
        compose.waitUntil(5000) { runBlocking { store.catalog.observeExchangeRates().first().any { it.source.code == "USD" && it.target.code == "CNY" && it.rate == "7.3" } } }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("rate_list").performScrollToNode(hasTestTag("rate_delete_USD_CNY"))
        compose.onNodeWithTag("rate_delete_USD_CNY").performClick()
        compose.onNodeWithTag("rate_delete_confirm").performClick()
        compose.waitUntil(5000) { runBlocking { store.catalog.observeExchangeRates().first().none { it.source.code == "USD" && it.target.code == "CNY" } } }
    }

    @Test fun historicalBalanceShowsOpeningAndDatedNativeCurrencyChanges() {
        val id = UUID.randomUUID().toString(); val name = "History ${id.take(6)}"
        val book = UUID.randomUUID().toString(); val usd = CurrencySpec("USD", 2)
        runBlocking {
            store.catalog.saveBook(Book(book, "History ${book.take(6)}"))
            store.catalog.saveAccount(AssetAccount(id, name, usd, Money(10000, usd)))
            val draft = TransactionDraft(book, kind = BillKind.EXPENSE, amount = Money(1000, usd), fromAccountId = id,
                fee = Money(10, usd), occurredAt = java.time.Instant.parse("2026-10-11T12:00:00Z"), timeSource = TimeSource.USER)
            assertTrue(store.ledger.save(draft, null) is SaveResult.Saved)
        }
        compose.onNodeWithTag("nav_assets").performClick()
        compose.onNodeWithTag("account_list").performScrollToNode(hasText(name))
        compose.onNodeWithText(name).performScrollTo().performClick()
        compose.onNodeWithTag("asset_history").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("asset_history_list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("USD 89.90").assertIsDisplayed()
        compose.onNodeWithText("USD -10.10").assertIsDisplayed()
        captureUiEvidence("c3-history.png")
    }
}
