package app.omymoney

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.omymoney.core.model.*
import app.omymoney.data.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BatchActionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = (compose.activity.application as OMyMoneyApplication).container.store
    private data class Fixture(val book: Book, val account: AssetAccount, val bills: List<Bill>)
    private fun fixture(): Fixture {
        val suffix = UUID.randomUUID().toString().take(6)
        val book = Book(UUID.randomUUID().toString(), "Batch $suffix", zoneId = ZoneOffset.UTC)
        val account = AssetAccount(UUID.randomUUID().toString(), "Batch cash $suffix")
        val bills = runBlocking {
            assertTrue(store.catalog.saveBook(book) is CatalogResult.Saved)
            assertTrue(store.catalog.saveAccount(account) is CatalogResult.Saved)
            repeat(2) { index ->
                assertTrue(store.ledger.save(TransactionDraft(book.id, kind = BillKind.EXPENSE,
                    amount = Money(100L + index, CurrencySpec.CNY), fromAccountId = account.id,
                    occurredAt = Instant.parse("2026-10-11T12:00:00Z"), zoneId = book.zoneId, timeSource = TimeSource.USER, note = "Original $index"), null) is SaveResult.Saved)
            }
            store.catalog.selectBook(book.id)
            store.ledger.observeBills(BillQuery(bookId = book.id)).first()
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bill_more").performClick()
        compose.onNodeWithTag("bill_batch").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("batch_list").fetchSemanticsNodes().isNotEmpty() }
        return Fixture(book, account, bills)
    }
    private fun bills(book: Book) = runBlocking { store.ledger.observeBills(BillQuery(bookId = book.id)).first() }
    private fun editNote(text: String) {
        compose.onNodeWithTag("batch_select_all").performClick()
        compose.onNodeWithTag("batch_edit").performClick()
        compose.onNodeWithTag("batch_change_note").performScrollTo().performClick()
        compose.onNodeWithTag("batch_note").performScrollTo().performTextReplacement(text)
    }
    @Test fun cancellingBatchEditAfterRecreationLeavesEveryBillUntouched() {
        val f = fixture()
        editNote("Not saved")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("batch_note").performScrollTo().assertTextContains("Not saved")
        compose.waitForIdle(); captureUiEvidence("c9-batch-form.png")
        compose.onNodeWithTag("screen_back").performClick()
        compose.onNodeWithTag("batch_discard").performClick()
        assertEquals(f.bills, bills(f.book))
        assertEquals(-201L, runBlocking { store.catalog.observeAccounts().first().single { it.id == f.account.id }.balance.minor })
    }
    @Test fun batchEditDeleteConfirmationAndPersistentUndoStayAtomic() {
        val f = fixture()
        editNote("Batch revised")
        compose.onNodeWithTag("batch_save").performClick()
        compose.waitUntil(5000) { bills(f.book).all { it.note == "Batch revised" } }
        compose.waitForIdle(); captureUiEvidence("c9-batch-edited.png")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("batch_history").performClick()
        compose.waitForIdle(); captureUiEvidence("c9-batch-history.png")
        compose.onNodeWithTag("batch_undo_latest").performScrollTo().performClick()
        compose.waitUntil(5000) { bills(f.book).all { it.note.startsWith("Original") } }
        compose.onNodeWithTag("batch_history_close").performClick()
        compose.onNodeWithTag("batch_select_all").performClick()
        compose.onNodeWithTag("batch_delete").performClick()
        compose.onNodeWithTag("batch_delete_cancel").performClick()
        assertEquals(2, bills(f.book).size)
        compose.onNodeWithTag("batch_delete").performClick()
        compose.waitForIdle(); captureUiEvidence("c9-batch-confirm.png")
        compose.onNodeWithTag("batch_delete_confirm").performClick()
        compose.waitUntil(5000) { bills(f.book).isEmpty() }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("batch_history").performClick()
        compose.onNodeWithTag("batch_undo_latest").performScrollTo().performClick()
        compose.waitUntil(5000) { bills(f.book).size == 2 }
        assertEquals(-201L, runBlocking { store.catalog.observeAccounts().first().single { it.id == f.account.id }.balance.minor })
    }
    @Test fun staleMemberRefusesTheWholeEditAndKeepsRawInputVisible() {
        val f = fixture()
        editNote("Retained correction")
        val original = f.bills.first()
        runBlocking {
            assertTrue(store.ledger.save(original.toDraft().copy(note = "Concurrent edit"), original.revision) is SaveResult.Saved)
        }
        compose.onNodeWithTag("batch_save").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("未完成。账单可能已被修改、有关联记录或存储不可用。未写入部分更改，请重新选择后重试。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("未完成。账单可能已被修改、有关联记录或存储不可用。未写入部分更改，请重新选择后重试。").assertIsDisplayed()
        compose.onNodeWithTag("batch_note").assertTextContains("Retained correction")
        val after = bills(f.book)
        assertEquals("Concurrent edit", after.single { it.id == original.id }.note)
        assertEquals(f.bills.last().note, after.single { it.id == f.bills.last().id }.note)
        compose.waitForIdle(); captureUiEvidence("c9-batch-stale.png")
        val error = compose.onNodeWithText("未完成。账单可能已被修改、有关联记录或存储不可用。未写入部分更改，请重新选择后重试。").fetchSemanticsNode().boundsInWindow
        var visibleBottom = 0
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val ime = androidx.core.view.ViewCompat.getRootWindowInsets(view)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
            visibleBottom = view.height - ime
        }
        assertTrue("Failure message bottom ${error.bottom} must be above the native IME top $visibleBottom", error.bottom <= visibleBottom)
    }

    @Test fun sameMinuteSameCountHistoryExposesTheExactMembersBeforeUndo() {
        val f = fixture()
        val actions = runBlocking {
            f.bills.map { bill -> store.batchActions.edit(mapOf(bill.id to bill.revision), BatchPatch(note = "Changed ${bill.note}")) }
        }
        assertEquals(actions.first().expiresAt.epochSecond / 60, actions.last().expiresAt.epochSecond / 60)
        compose.onNodeWithTag("batch_history").performClick()
        actions.forEach { action ->
            val bill = action.before.single()
            compose.onNodeWithTag("batch_history_list").performScrollToNode(hasTestTag("batch_action_${action.id}"))
            compose.onNodeWithTag("batch_action_${action.id}").performScrollTo().assertTextContains(bill.note, substring = true)
            compose.onNodeWithTag("batch_action_${action.id}").performClick()
            compose.onNodeWithTag("batch_member_${bill.id}").assertTextContains(bill.note, substring = true)
            compose.onNodeWithTag("batch_member_${bill.id}").assertTextContains("2026-10-11 12:00:00", substring = true)
            compose.waitForIdle(); captureUiEvidence("c9-batch-members.png")
            compose.onNode(hasText("关闭") and hasAnyAncestor(isDialog())).performClick()
            val undoTag = if (action.id == actions.last().id) "batch_undo_latest" else "batch_undo_${action.id}"
            compose.onNodeWithTag(undoTag).performScrollTo().assertContentDescriptionEquals("撤销：批量修改 · 1 · ${bill.note} · ${action.id.take(8)}")
        }
        assertTrue(bills(f.book).all { it.note.startsWith("Changed") })
    }
}
