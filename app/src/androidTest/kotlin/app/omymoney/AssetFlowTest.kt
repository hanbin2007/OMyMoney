package app.omymoney

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.omymoney.core.model.*
import app.omymoney.data.CatalogResult
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AssetFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = (compose.activity.application as OMyMoneyApplication).container.store

    @Test fun groupsCanBeAssignedRenamedAndOrderedWithoutLosingArchivedHistory() {
        val id = UUID.randomUUID().toString()
        val name = "Grouped ${id.take(6)}"
        val group = "Group ${id.take(6)}"
        runBlocking { assertTrue(store.catalog.saveAccount(AssetAccount(id, name, group = group)) is CatalogResult.Saved) }
        compose.onNodeWithTag("nav_assets").performClick()
        waitFor("account_list")
        compose.onNodeWithTag("account_list").performScrollToNode(hasTestTag("asset_groups"))
        compose.onNodeWithTag("asset_groups").performClick()
        compose.onNodeWithTag("group_list").performScrollToNode(hasTestTag("group_rename_$group"))
        compose.onNodeWithTag("group_rename_$group").performClick()
        compose.onNodeWithTag("group_name").performTextReplacement("Renamed ${id.take(6)}")
        compose.onNodeWithTag("group_save").performClick()
        val renamed = "Renamed ${id.take(6)}"
        compose.waitUntil(5000) { compose.onAllNodesWithTag("group_up_$renamed").fetchSemanticsNodes().isNotEmpty() }
        val previousIndex = runBlocking { store.catalog.observeAccounts().first().groupBy { it.group }.keys.toList().indexOf(renamed) }
        compose.onNodeWithTag("group_up_$renamed").performClick()
        compose.waitUntil(5000) { runBlocking { store.catalog.observeAccounts().first().groupBy { it.group }.keys.toList().indexOf(renamed) == previousIndex - 1 } }
        compose.activityRule.scenario.recreate()
        waitFor("group_list")
        assertEquals(previousIndex - 1, runBlocking { store.catalog.observeAccounts().first().groupBy { it.group }.keys.toList().indexOf(renamed) })
        compose.onNodeWithTag("screen_back").performClick()
        compose.onNodeWithTag("account_list").performScrollToNode(hasText(name))
        compose.onNodeWithContentDescription("更多操作：$name").performScrollTo().performClick()
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithTag("account_group").performScrollTo().performTextReplacement("Saved ${id.take(6)}")
        compose.onNodeWithTag("catalog_save").performClick()
        waitFor("account_list")
        compose.onNodeWithTag("account_list").performScrollToNode(hasText(name))
        compose.onNodeWithContentDescription("更多操作：$name").performScrollTo().performClick()
        compose.onNodeWithText("归档").performClick()
        compose.onAllNodesWithText("归档").onLast().performClick()
        compose.waitUntil(5000) { runBlocking { store.catalog.observeAccounts(true).first().single { it.id == id }.archived } }
        runBlocking { assertTrue(store.catalog.restoreAccount(id) is CatalogResult.Saved) }
        compose.activityRule.scenario.recreate()
        waitFor("account_list")
        assertEquals("Saved ${id.take(6)}", runBlocking { store.catalog.observeAccounts().first().single { it.id == id }.group })
    }

    @Test fun creditCycleConfigurationSurvivesRecreationAndCanBeCancelled() {
        val id = UUID.randomUUID().toString()
        val name = "Credit ${id.take(6)}"
        runBlocking { assertTrue(store.catalog.saveAccount(AssetAccount(id, name, kind = AccountKind.CREDIT)) is CatalogResult.Saved) }
        compose.onNodeWithTag("nav_assets").performClick()
        compose.onNodeWithTag("account_list").performScrollToNode(hasText(name))
        compose.onNodeWithText(name).performScrollTo().performClick()
        waitFor("asset_credit_settings")
        compose.onNodeWithTag("asset_credit_settings").performScrollTo().performClick()
        compose.onNodeWithTag("credit_enabled").performClick()
        compose.onNodeWithTag("credit_statement").performTextReplacement("31")
        compose.onNodeWithTag("credit_due").performTextReplacement("5")
        compose.onNodeWithTag("credit_limit").performScrollTo().performTextReplacement("1000.00")
        compose.onNodeWithTag("credit_save").performClick()
        assertNotNull(runBlocking { store.catalog.observeAccounts().first().single { it.id == id }.creditTerms })
        waitFor("asset_credit_settings")
        compose.activityRule.scenario.recreate()
        waitFor("asset_credit_settings")
        compose.onNodeWithTag("asset_credit_settings").performScrollTo().performClick()
        compose.onNodeWithTag("credit_statement").assertTextContains("31")
        compose.onNodeWithTag("credit_statement").performTextReplacement("20")
        compose.onNodeWithTag("screen_back").performClick()
        compose.onNodeWithText("放弃更改").performClick()
        assertEquals(31, runBlocking { store.catalog.observeAccounts().first().single { it.id == id }.creditTerms!!.statementDay })
        compose.onNodeWithTag("asset_transactions").performScrollToIndex(0)
        waitFor("asset_balance")
        captureUiEvidence("c2-credit.png")
    }

    @Test fun loanEntryPartialRepaymentAndInterestUseTheSameAccountAndLinkedSource() {
        val book = UUID.randomUUID().toString()
        val cash = UUID.randomUUID().toString()
        val debt = UUID.randomUUID().toString()
        val otherDebt = UUID.randomUUID().toString()
        val name = "Loan ${debt.take(6)}"
        runBlocking {
            assertTrue(store.catalog.saveBook(Book(book, "Debt book ${book.take(6)}")) is CatalogResult.Saved)
            assertTrue(store.catalog.saveAccount(AssetAccount(cash, "Funds ${cash.take(6)}")) is CatalogResult.Saved)
            assertTrue(store.catalog.saveAccount(AssetAccount(debt, name, kind = AccountKind.LIABILITY)) is CatalogResult.Saved)
            assertTrue(store.catalog.saveAccount(AssetAccount(otherDebt, "Other ${otherDebt.take(6)}", kind = AccountKind.LIABILITY)) is CatalogResult.Saved)
            store.catalog.selectBook(book)
        }
        compose.onNodeWithTag("nav_assets").performClick()
        compose.onNodeWithTag("account_list").performScrollToNode(hasText(name))
        compose.onNodeWithText(name).performScrollTo().performClick()
        waitFor("asset_new_debt")
        compose.onNodeWithTag("asset_new_debt").performScrollTo().performClick()
        waitFor("bill_amount")
        compose.onNodeWithTag("bill_amount").performTextReplacement("100.00")
        compose.onNodeWithTag("bill_to_account").performScrollTo().performClick()
        compose.onNodeWithTag("option_$debt").assertDoesNotExist()
        compose.onNodeWithTag("selection_search").performTextInput("Funds ${cash.take(6)}")
        compose.onNodeWithTag("option_$cash").performScrollTo().performClick()
        compose.onNodeWithTag("bill_save").performClick()
        waitFor("asset_balance")
        val source = runBlocking { store.ledger.observeBills(BillQuery(accountId = debt)).first().single() }
        compose.onNodeWithTag("asset_transactions").performScrollToNode(hasTestTag("asset_bill_${source.id}"))
        compose.onNodeWithTag("asset_bill_${source.id}").performClick()
        compose.onNodeWithTag("bill_debt_records").performScrollTo().performClick()
        waitFor("debt_repay")
        compose.onNodeWithTag("debt_repay").performScrollTo().performClick()
        waitFor("bill_amount")
        compose.onNodeWithTag("bill_amount").performTextReplacement("40.00")
        compose.onNodeWithTag("bill_to_account").performScrollTo().onChildren().filter(isNotEnabled()).assertCountEquals(1)
        compose.onNodeWithTag("bill_from_account").assertIsEnabled()
        compose.onNodeWithTag("bill_save").performClick()
        waitFor("debt_remaining")
        compose.onNodeWithTag("debt_remaining").assertTextContains("60.00", substring = true)
        compose.onNodeWithTag("debt_interest").performScrollTo().performClick()
        waitFor("bill_amount")
        compose.onNodeWithTag("bill_amount").performTextReplacement("2.00")
        compose.onNodeWithTag("bill_save").performClick()
        waitFor("debt_remaining")
        assertBalance(cash, 5800)
        assertBalance(debt, -6000)
        compose.activityRule.scenario.recreate()
        waitFor("debt_remaining")
        compose.onNodeWithTag("debt_remaining").assertTextContains("60.00", substring = true)
        captureUiEvidence("c2-debt.png")
    }

    @Test fun balanceAdjustmentCanBeCancelledAndSavedWithVisibleSourceAcrossBooks() {
        val id = UUID.randomUUID().toString()
        val name = "Asset ${id.take(6)}"
        runBlocking {
            assertTrue(store.catalog.saveAccount(AssetAccount(id, name, openingBalance = Money(10000, CurrencySpec.CNY))) is CatalogResult.Saved)
        }
        compose.onNodeWithTag("nav_assets").performClick()
        compose.onNodeWithTag("account_list").performScrollToNode(hasText(name))
        compose.onNodeWithText(name).performScrollTo().performClick()
        compose.waitForIdle()
        waitFor("asset_balance")
        compose.onNodeWithTag("asset_balance").assertTextContains("100.00", substring = true)
        compose.onNodeWithTag("asset_transactions").performScrollToNode(hasTestTag("asset_adjust"))
        compose.onNodeWithTag("asset_adjust").performScrollTo().performClick()
        compose.onNodeWithTag("adjust_amount").performTextReplacement("5.00")
        compose.onNodeWithTag("adjust_reason").performTextReplacement("Cash count")
        compose.onNodeWithTag("adjust_cancel").performClick()
        assertBalance(id, 10000)
        compose.onNodeWithTag("asset_adjust").performScrollTo().performClick()
        compose.onNodeWithTag("adjust_amount").performTextReplacement("5.00")
        compose.onNodeWithTag("adjust_reason").performTextReplacement("Cash count")
        compose.onNodeWithTag("adjust_confirm").performClick()
        compose.waitUntil(10000) { runBlocking { store.catalog.observeAccounts(true).first().single { it.id == id }.balance.minor == 10500L } }
        compose.onNodeWithTag("asset_transactions").performScrollToIndex(0)
        compose.onNodeWithTag("asset_balance").assertTextContains("105.00", substring = true)
        compose.onNodeWithTag("asset_transactions").performScrollToNode(hasText("Cash count", substring = true))
        compose.onNodeWithText("Cash count", substring = true).assertIsDisplayed()
        runBlocking {
            val other = UUID.randomUUID().toString()
            assertTrue(store.catalog.saveBook(Book(other, "Other ${other.take(6)}")) is CatalogResult.Saved)
            store.catalog.selectBook(other)
        }
        compose.activityRule.scenario.recreate()
        waitFor("asset_transactions")
        compose.onNodeWithTag("asset_transactions").performScrollToIndex(0)
        waitFor("asset_balance")
        compose.onNodeWithTag("asset_balance").assertTextContains("105.00", substring = true)
        captureUiEvidence("c2-asset.png")
    }
    private fun assertBalance(id: String, amount: Long) = runBlocking {
        assertEquals(amount, store.catalog.observeAccounts(true).first().single { it.id == id }.balance.minor)
    }
    private fun waitFor(tag: String) {
        try { compose.waitUntil(10000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
        catch (failure: Throwable) {
            compose.onRoot().printToLog("C2Timeout")
            captureUiEvidence("c2-timeout-$tag.png")
            throw failure
        }
    }
}
