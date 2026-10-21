package app.omymoney

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.model.*
import app.omymoney.data.*
import app.omymoney.data.exchange.*
import app.omymoney.feature.bills.BillFilters
import app.omymoney.feature.exchange.*
import app.omymoney.ui.theme.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImportPreviewUiTest {
    @get:Rule val rule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store get() = (context.applicationContext as OMyMoneyApplication).container.store
    private lateinit var model: ExchangeViewModel
    private fun show(book: Book, preview: ImportPreview) {
        model = ExchangeViewModel(store.billImport, store.billExport, store.ledger, context.contentResolver, book,
            BillFilters(), SavedStateHandle(mapOf(ExchangeViewModel.PREVIEW_KEY to preview.previewId)))
        rule.setContent { LedgerTheme(ThemeMode.SYSTEM, dynamicColor = false) { ExchangeScreen(model, store) {} } }
        rule.waitUntil(5000) { !model.state.value.busy && model.state.value.preview != null }
    }

    private fun mappedFixture(): Pair<Book, ImportPreview> = runBlocking {
        val suffix = UUID.randomUUID().toString().take(6)
        val book = Book(UUID.randomUUID().toString(), "Mapped $suffix", zoneId = java.time.ZoneOffset.UTC)
        val usd = AssetAccount(UUID.randomUUID().toString(), "Dollar $suffix", currency = CurrencySpec("USD", 2))
        val cash = AssetAccount(UUID.randomUUID().toString(), "Cash $suffix")
        assertTrue(store.catalog.saveBook(book) is CatalogResult.Saved)
        assertTrue(store.catalog.saveAccount(usd) is CatalogResult.Saved)
        assertTrue(store.catalog.saveAccount(cash) is CatalogResult.Saved)
        val source = "occurred_at,kind,amount,currency,from_account,to_account,note,fee,exchange_account,exchange_currency,exchange_amount,exchange_rate,exchange_date\n" +
            "2026-10-11T12:34:56Z,TRANSFER,10.00,USD,External,${cash.name},Mapped transfer,0.10,${cash.name},CNY,71.99,7.2,2026-10-11\n"
        val preview = store.billImport.preview(source.byteInputStream(), ExchangeFormat.CSV, book.id)
        book to store.billImport.remap(preview.previewId, preview.mapping.copy(accounts = mapOf("External" to usd.id)))
    }

    @Test fun rowSelectionHasAnAccessibleNameAndState() {
        val (book, preview) = mappedFixture()
        assertTrue(preview.rows.single().selectable)
        show(book, preview)
        rule.onNodeWithTag("exchange_select_2").performScrollTo()
            .assertContentDescriptionEquals("选择第 2 行，转账，USD 10.00").assertIsOff().performClick().assertIsOn()
    }

    @Test fun mappedTransactionDetailsShowActualAccountsDateFeeAndExchange() {
        val (book, preview) = mappedFixture()
        assertTrue(preview.rows.single().selectable)
        val accounts = runBlocking { store.catalog.observeAccounts().first() }
        val draft = preview.rows.single().draft!!
        show(book, preview)
        rule.onNodeWithTag("exchange_row_2").performScrollTo().assertTextContains("转账", substring = true)
        rule.onNodeWithTag("exchange_row_2").assertTextContains(accounts.single { it.id == draft.fromAccountId }.name, substring = true)
        rule.onNodeWithTag("exchange_row_2").performClick()
        rule.onNodeWithTag("exchange_parsed_time").performScrollTo().assertTextContains("12:34:56", substring = true)
        rule.onNodeWithTag("exchange_parsed_to").performScrollTo().assertTextContains(accounts.single { it.id == draft.toAccountId }.name, substring = true)
        rule.onNodeWithTag("exchange_parsed_fee").performScrollTo().assertTextContains("USD 0.10", substring = true)
        rule.onNodeWithTag("exchange_parsed_fx").performScrollTo().assertTextContains("CNY 71.99", substring = true)
        rule.waitForIdle(); captureUiEvidence("c9-import-parsed.png")
        rule.onNodeWithTag("exchange_raw_4").performScrollTo().assertTextContains("External")
    }

    @Test fun mappedPreviewShowsRawErrorsAndImportsOnlyExplicitSelections() {
        val book = Book(UUID.randomUUID().toString(), "Preview ${UUID.randomUUID().toString().take(6)}")
        val cash = AssetAccount(UUID.randomUUID().toString(), "Cash ${UUID.randomUUID().toString().take(6)}")
        val preview = runBlocking {
            assertTrue(store.catalog.saveBook(book) is CatalogResult.Saved)
            assertTrue(store.catalog.saveAccount(cash) is CatalogResult.Saved)
            store.billImport.preview(("occurred_at,kind,amount,currency,from_account,note\n" +
                "2026-10-11T12:00:00Z,EXPENSE,12.30,CNY,${cash.name},Good\n" +
                "bad,EXPENSE,1,CNY,${cash.name},\"Raw, source\nsecond line\"\n" +
                "2026-10-11T12:00:00Z,EXPENSE,12.30,CNY,External,Mapping\n").byteInputStream(), ExchangeFormat.CSV, book.id)
        }
        show(book, preview)
        rule.onNodeWithTag("exchange_commit").assertIsNotEnabled()
        rule.onNodeWithTag("exchange_select_all").performScrollTo().performClick()
        assertArrayEquals(intArrayOf(2), model.selected.value)
        rule.onNodeWithTag("exchange_preview_list").performScrollToNode(hasTestTag("exchange_row_3"))
        rule.onNodeWithTag("exchange_row_3").performClick()
        rule.onNodeWithTag("exchange_raw_5").performScrollTo().assertTextContains("Raw, source\nsecond line")
        rule.waitForIdle(); captureUiEvidence("c9-import-raw.png")
        rule.onNode(hasText("关闭") and hasAnyAncestor(isDialog())).performClick()
        rule.onNodeWithTag("exchange_mapping").performClick()
        rule.onNodeWithTag("exchange_mapping_list").performScrollToNode(hasTestTag("exchange_account_External"))
        rule.onNodeWithTag("exchange_account_External").performClick()
        rule.onNodeWithTag("selection_search").performTextReplacement(cash.name)
        rule.onNodeWithTag("selection_search").performImeAction()
        rule.onNodeWithTag("option_${cash.id}").performScrollTo()
        rule.waitForIdle(); captureUiEvidence("c9-import-picker.png")
        rule.onNodeWithTag("option_${cash.id}").assertIsDisplayed()
        rule.onNodeWithTag("option_${cash.id}").performClick()
        rule.waitUntil(5000) { !model.state.value.busy && model.state.value.preview!!.rows.last().selectable }
        assertTrue(model.selected.value.isEmpty())
        rule.waitForIdle(); captureUiEvidence("c9-import-mapping.png")
        rule.onNodeWithTag("screen_back").performClick()
        rule.onNodeWithTag("exchange_select_all").performScrollTo().performClick()
        assertArrayEquals(intArrayOf(2, 4), model.selected.value)
        rule.onNodeWithTag("exchange_preview_list").performScrollToIndex(0)
        rule.waitForIdle(); captureUiEvidence("c9-import-preview.png")
        rule.onNodeWithTag("exchange_commit").performClick()
        rule.waitUntil(5000) { !model.state.value.busy && model.state.value.preview!!.rows.count { it.duplicate } == 2 }
        assertEquals(2, runBlocking { store.ledger.observeBills(BillQuery(bookId = book.id)).first().size })
        assertEquals(-2460L, runBlocking { store.catalog.observeAccounts().first().single { it.id == cash.id }.balance.minor })
        rule.onNodeWithTag("exchange_commit").assertIsNotEnabled()
        rule.waitForIdle(); captureUiEvidence("c9-import-complete.png")
    }

    @Test fun invalidFileIsNotReportedAsScannedAndPreviewCanBeDiscarded() {
        val book = Book(UUID.randomUUID().toString(), "Invalid ${UUID.randomUUID().toString().take(6)}")
        val preview = runBlocking {
            assertTrue(store.catalog.saveBook(book) is CatalogResult.Saved)
            store.billImport.preview("corrupt workbook".byteInputStream(), ExchangeFormat.XLSX, book.id)
        }
        show(book, preview)
        rule.onNodeWithTag("exchange_commit").assertIsNotEnabled()
        rule.onNodeWithText("文件无法完整读取", substring = true).assertIsDisplayed()
        rule.waitForIdle(); captureUiEvidence("c9-import-invalid.png")
        rule.onNodeWithTag("screen_back").performClick()
        rule.onNodeWithTag("exchange_discard").performClick()
        rule.waitUntil(5000) { model.state.value.preview == null }
        assertTrue(runBlocking { store.ledger.observeBills(BillQuery(bookId = book.id)).first().isEmpty() })
    }
}
