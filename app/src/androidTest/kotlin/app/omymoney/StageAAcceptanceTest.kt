package app.omymoney

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.core.model.*
import app.omymoney.data.backup.RestoreMode
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StageAAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = (compose.activity.application as OMyMoneyApplication).container.store
    private val suffix = UUID.randomUUID().toString().take(6)
    private val prefs get() = compose.activity.getSharedPreferences("qa-stage-a", Context.MODE_PRIVATE)

    @Test fun completeManualLedgerFlowHasExactBalancesAfterBackupAndRestart() {
        val phase = InstrumentationRegistry.getArguments().getString("stageAPhase")
        if (phase == "verify") {
            verify(requireNotNull(prefs.getString("book", null)), requireNotNull(prefs.getString("cash", null)), requireNotNull(prefs.getString("bank", null)))
            compose.waitForIdle()
            compose.onNodeWithTag("book_switch").assertIsDisplayed()
            compose.onNodeWithTag("nav_bills").assertIsDisplayed()
            captureUiEvidence("a9-reopened.png")
            return
        }
        waitFor("book_switch")
        val bookName = "验收账本 $suffix"
        val cashName = "验收现金 $suffix"
        val bankName = "验收储蓄 $suffix"
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_books").performClick()
        compose.onNodeWithTag("add_book").performClick()
        compose.onNodeWithTag("catalog_name").performTextInput(bookName)
        compose.onNodeWithTag("catalog_save").performClick()
        waitFor("book_list")
        compose.onNodeWithTag("book_list").performScrollToNode(hasText(bookName))
        compose.onNodeWithTag("screen_back").performClick()
        compose.onNodeWithTag("nav_assets").performClick()
        account(cashName, "100.00"); account(bankName, "50.00")
        val bookId = runBlocking { store.catalog.observeCurrentBookId().first()!! }
        val accounts = runBlocking { store.catalog.observeAccounts().first() }
        val cash = accounts.single { it.name == cashName }.id
        val bank = accounts.single { it.name == bankName }.id
        compose.onNodeWithTag("nav_bills").performClick()
        compose.onNodeWithTag("book_switch").assertTextContains(bookName)
        bill(BillKind.INCOME, "25.30", cashName, null, "验收收入")
        bill(BillKind.EXPENSE, "12.30", cashName, null, "验收支出")
        bill(BillKind.TRANSFER, "40.00", cashName, bankName, "验收转账")
        val expenseId = runBlocking { store.ledger.observeBills(BillQuery(bookId = bookId)).first().single { it.kind == BillKind.EXPENSE }.id }
        assertBalances(cash, bank, 7300, 9000)
        compose.onNodeWithTag("bill_search_action").performClick()
        compose.onNodeWithTag("bill_search").performTextInput("验收支出")
        compose.onNodeWithTag("bill_row_$expenseId").performClick()
        compose.onNodeWithTag("bill_edit").performClick()
        waitFor("bill_amount")
        compose.onNodeWithTag("bill_amount").performTextReplacement("15.60")
        compose.onNodeWithTag("bill_save").performClick()
        waitFor("bill_detail")
        assertBalances(cash, bank, 6970, 9000)
        compose.onNodeWithTag("bill_delete").performClick()
        waitFor("add_bill")
        assertBalances(cash, bank, 8530, 9000)
        compose.waitUntil(3000) { compose.onAllNodesWithText("撤销").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("撤销").performClick()
        waitFor("bill_row_$expenseId")
        assertBalances(cash, bank, 6970, 9000)
        val backup = runBlocking { ByteArrayOutputStream().also { store.backup.exportTo(it) }.toByteArray() }
        runBlocking {
            val expense = store.ledger.observeBill(expenseId).first()!!
            assertTrue(store.ledger.save(expense.toDraft().copy(amount = Money(2000, CurrencySpec.CNY)), expense.revision) is SaveResult.Saved)
            assertBalances(cash, bank, 6530, 9000)
            val preview = store.backup.inspect(backup.inputStream())
            assertTrue(preview.conflicts.isNotEmpty())
            store.backup.restore(preview.previewId, RestoreMode.REPLACE)
        }
        assertBalances(cash, bank, 6970, 9000)
        compose.activityRule.scenario.recreate()
        waitFor("bill_row_$expenseId")
        compose.onNodeWithTag("bill_row_$expenseId").assertIsDisplayed()
        compose.waitForIdle()
        captureUiEvidence("a9-ledger.png")
        assertTrue(prefs.edit().putString("book", bookId).putString("cash", cash).putString("bank", bank).commit())
        verify(bookId, cash, bank)
    }

    private fun account(name: String, opening: String) {
        compose.onNodeWithTag("add_account").performClick()
        compose.onNodeWithTag("catalog_name").performTextInput(name)
        compose.onNodeWithTag("account_opening").performTextReplacement(opening)
        compose.onNodeWithTag("catalog_save").performClick()
        waitFor("add_account")
    }
    private fun bill(kind: BillKind, amount: String, firstAccount: String, secondAccount: String?, note: String) {
        compose.onNodeWithTag("add_bill").performClick()
        waitFor("bill_amount")
        compose.onNodeWithTag("bill_kind_${kind.name}").performClick()
        compose.onNodeWithTag("bill_amount").performTextReplacement(amount)
        selectAccount(if (kind == BillKind.INCOME) "bill_to_account" else "bill_from_account", firstAccount)
        if (secondAccount != null) selectAccount("bill_to_account", secondAccount)
        compose.onNodeWithTag("bill_note").performScrollTo().performTextInput(note)
        compose.onNodeWithTag("bill_save").performClick()
        waitFor("add_bill")
    }
    private fun selectAccount(tag: String, name: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.onNodeWithTag("selection_search").performTextInput(name)
        compose.onNode(hasText(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performClick()
    }
    private fun assertBalances(cash: String, bank: String, cashMinor: Long, bankMinor: Long) = runBlocking {
        val accounts = store.catalog.observeAccounts(true).first().associateBy { it.id }
        assertEquals(cashMinor, accounts.getValue(cash).balance.minor)
        assertEquals(bankMinor, accounts.getValue(bank).balance.minor)
    }
    private fun verify(book: String, cash: String, bank: String) {
        assertBalances(cash, bank, 6970, 9000)
        val bills = runBlocking { store.ledger.observeBills(BillQuery(bookId = book)).first() }
        assertEquals(3, bills.size)
        assertEquals(1560L, bills.single { it.kind == BillKind.EXPENSE }.amount.minor)
        assertEquals(2530L, bills.single { it.kind == BillKind.INCOME }.amount.minor)
        assertEquals(4000L, bills.single { it.kind == BillKind.TRANSFER }.amount.minor)
        assertEquals(book, runBlocking { store.catalog.observeCurrentBookId().first() })
        waitFor("book_switch")
    }
    private fun waitFor(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
}
