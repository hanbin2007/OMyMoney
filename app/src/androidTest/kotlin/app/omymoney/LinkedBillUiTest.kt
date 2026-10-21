package app.omymoney

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.omymoney.core.model.*
import app.omymoney.data.CatalogResult
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LinkedBillUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = (compose.activity.application as OMyMoneyApplication).container.store

    @Test fun refundAndPartialReimbursementUseContextualEntryWithCancelAndConfirmedDelete() {
        val (source, cash) = seedSource(BillKind.EXPENSE)
        waitFor("bill_row_$source")
        compose.onNodeWithTag("bill_row_$source").performClick()
        compose.onNodeWithTag("bill_linked").performScrollTo().performClick()
        waitFor("linked_add")
        compose.onNodeWithTag("linked_add").performScrollTo().performClick()
        waitFor("bill_amount")
        compose.onNodeWithTag("bill_amount").performTextReplacement("30.00")
        compose.onNodeWithTag("bill_save").performClick()
        waitFor("linked_summary")
        compose.onNodeWithTag("linked_summary").assertTextContains("70.00", substring = true)
        val refund = runBlocking { store.ledger.observeBills(BillQuery()).first().single { it.linkedBillId == source } }
        assertBalance(cash, 13000)
        compose.onNodeWithTag("linked_row_${refund.id}").performScrollTo().performClick()
        waitFor("bill_delete")
        compose.onNodeWithTag("bill_delete").performClick()
        compose.onNodeWithTag("linked_delete_cancel").performClick()
        assertBalance(cash, 13000)
        compose.onNodeWithTag("bill_delete").performClick()
        compose.onNodeWithTag("linked_delete_confirm").performClick()
        waitFor("linked_summary")
        assertBalance(cash, 10000)
        compose.waitUntil(3000) { compose.onAllNodesWithText("撤销").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("撤销").assertIsDisplayed().performClick()
        waitFor("linked_row_${refund.id}")
        assertBalance(cash, 13000)
        compose.onNodeWithTag("linked_row_${refund.id}").performScrollTo().performClick()
        compose.onNodeWithTag("bill_delete").performClick()
        compose.onNodeWithTag("linked_delete_confirm").performClick()
        waitFor("linked_summary")
        assertBalance(cash, 10000)
        compose.onNodeWithTag("screen_back").performClick()
        compose.onNodeWithTag("bill_edit").performClick()
        waitFor("bill_pending_reimbursement")
        compose.onNodeWithTag("bill_pending_reimbursement").performScrollTo().performClick()
        compose.onNodeWithTag("bill_save").performClick()
        waitFor("bill_detail")
        compose.onNodeWithTag("bill_linked").performScrollTo().performClick()
        compose.onNodeWithTag("linked_add").performScrollTo().performClick()
        waitFor("bill_amount")
        compose.onNodeWithTag("bill_amount").performTextReplacement("60.00")
        compose.onNodeWithTag("bill_save").performClick()
        waitFor("linked_summary")
        compose.onNodeWithTag("linked_summary").assertTextContains("40.00", substring = true)
        assertBalance(cash, 16000)
        compose.activityRule.scenario.recreate()
        waitFor("linked_summary")
        compose.onNodeWithTag("linked_summary").assertTextContains("40.00", substring = true)
        captureUiEvidence("c1-linked.png")
    }

    @Test fun clickingTheSelectedExpenseSegmentPreservesPendingReimbursement() {
        val (source, _) = seedSource(BillKind.REIMBURSEMENT)
        waitFor("bill_row_$source")
        compose.onNodeWithTag("bill_row_$source").performClick()
        compose.onNodeWithTag("bill_edit").performClick()
        waitFor("bill_pending_reimbursement")
        compose.onNodeWithTag("bill_pending_reimbursement").assertIsOn()
        compose.onNodeWithTag("bill_kind_EXPENSE").performScrollTo().performClick()
        compose.onNodeWithTag("bill_pending_reimbursement").assertIsOn()
        compose.onNodeWithTag("bill_save").performClick()
        waitFor("bill_detail")
        assertEquals(BillKind.REIMBURSEMENT, runBlocking { store.ledger.observeBill(source).first()!!.kind })
    }

    @Test fun pendingReimbursementCanKeepAndSelectAnExpenseCategoryFilter() {
        val (source, _) = seedSource(BillKind.REIMBURSEMENT)
        val category = runBlocking {
            val bill = store.ledger.observeBill(source).first()!!
            val category = store.catalog.observeCategories(bill.bookId).first().first { it.kind == BillKind.EXPENSE }
            assertTrue(store.ledger.save(bill.toDraft().copy(categoryId = category.id), bill.revision) is SaveResult.Saved)
            category
        }
        waitFor("bill_row_$source")
        compose.onNodeWithTag("bill_filter_action").performClick()
        compose.onNodeWithText("支出").performScrollTo().performClick()
        compose.onNodeWithTag("filter_category").performScrollTo().performClick()
        compose.onNodeWithTag("option_${category.id}").performScrollTo().performClick()
        compose.onNodeWithText("待报销").performScrollTo().performClick()
        compose.onNodeWithTag("filter_category").assert(hasAnyDescendant(hasText(category.name, substring = true)))
        compose.onNodeWithTag("filter_category").performScrollTo().performClick()
        compose.onNodeWithTag("option_${category.id}").performScrollTo().performClick()
        compose.onNodeWithTag("apply_bill_filters").performClick()
        compose.onNodeWithTag("bill_row_$source").assertExists()
        compose.onNodeWithTag("bill_filter_summary").assertTextContains(category.name, substring = true)
    }

    private fun seedSource(kind: BillKind): Pair<String, String> = runBlocking {
        val book = UUID.randomUUID().toString()
        val cash = UUID.randomUUID().toString()
        assertTrue(store.catalog.saveBook(Book(book, "Linked acceptance ${book.take(6)}")) is CatalogResult.Saved)
        assertTrue(store.catalog.saveAccount(AssetAccount(cash, "Linked cash ${cash.take(6)}", openingBalance = Money(20000, CurrencySpec.CNY))) is CatalogResult.Saved)
        store.catalog.selectBook(book)
        val result = store.ledger.save(TransactionDraft(book, kind = kind, amount = Money(10000, CurrencySpec.CNY),
            fromAccountId = cash, occurredAt = Instant.now(), timeSource = TimeSource.USER, note = "Linked source"), null)
        assertTrue("Expected saved source, got $result", result is SaveResult.Saved)
        (result as SaveResult.Saved).billId to cash
    }

    private fun assertBalance(id: String, amount: Long) = runBlocking {
        assertEquals(amount, store.catalog.observeAccounts(true).first().single { it.id == id }.balance.minor)
    }
    private fun waitFor(tag: String) { compose.waitUntil(10000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
}
