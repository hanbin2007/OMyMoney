package app.omymoney

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.omymoney.core.model.*
import app.omymoney.core.recognition.*
import app.omymoney.data.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QueueReviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun missingRecognitionKindShowsTheSelectorAndCanBeCorrected() {
        val store = (compose.activity.application as OMyMoneyApplication).container.store
        val book = UUID.randomUUID().toString()
        val id = runBlocking {
            assertTrue(store.catalog.saveBook(Book(book, "Unclassified ${book.take(6)}")) is CatalogResult.Saved)
            val draft = store.catalog.initialDraft(book).copy(kind = null, categoryId = null, amount = Money(1200, CurrencySpec.CNY))
            store.recognitionQueue.enqueue(RecognitionResult.Matched(draft, emptyList(), "fixture")).id
        }
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_queue").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("queue_$id").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("queue_$id").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("bill_kind_EXPENSE").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bill_kind_EXPENSE").performScrollTo().performClick()
        compose.onNodeWithTag("bill_kind_EXPENSE").assertIsSelected()
        compose.onNodeWithTag("bill_save").performClick()
        compose.waitUntil(5000) { runBlocking { store.recognitionQueue.read(id)!!.state == QueueState.SAVED } }
        assertEquals(BillKind.EXPENSE, runBlocking { store.ledger.observeBills(BillQuery(bookId = book)).first().single().kind })
    }
    @Test fun comparisonShowsLatestSavedBillTypeSecondsAndCurrentDifferences() {
        val store = (compose.activity.application as OMyMoneyApplication).container.store
        var savedBillId = ""
        val id = runBlocking {
            val first = store.catalog.initialDraft().copy(amount = Money(4321, CurrencySpec.CNY),
                occurredAt = Instant.parse("2026-10-11T12:00:10Z"), zoneId = java.time.ZoneId.of("UTC"), note = "原始记录")
            val original = store.recognitionQueue.enqueue(RecognitionResult.Matched(first, emptyList(), "fixture"))
            store.recognitionQueue.resolve(original.id, QueueOutcome.Save(first))
            val next = store.recognitionQueue.enqueue(RecognitionResult.Matched(first.copy(submissionId = UUID.randomUUID().toString(),
                occurredAt = first.occurredAt!!.plusSeconds(30)), emptyList(), "fixture"))
            val saved = store.ledger.observeBill(store.recognitionQueue.read(original.id)!!.billId!!).first()!!
            savedBillId = saved.id
            val result = store.ledger.save(saved.toDraft().copy(kind = BillKind.INCOME, fromAccountId = null,
                toAccountId = first.fromAccountId, categoryId = null, amount = Money(5678, CurrencySpec.CNY), note = "更新后的记录"), saved.revision)
            assertTrue(result.toString(), result is SaveResult.Saved)
            next.id
        }
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_queue").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("queue_$id").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("queue_$id").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("review_compare").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("review_compare").performScrollTo().performClick()
        compose.onNodeWithText("CNY 56.78", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("12:00:10", substring = true).assertExists()
        compose.onNodeWithText("12:00:40", substring = true).assertExists()
        compose.onNodeWithText("收支类型：收入").assertExists()
        compose.onNodeWithText("收支类型：支出").assertExists()
        compose.onNode(hasText("差异", substring = true) and hasText("金额", substring = true) and hasText("收支类型", substring = true)).assertExists()
        runBlocking { store.ledger.delete(savedBillId) }
        compose.waitUntil(5000) { compose.onAllNodesWithText(compose.activity.getString(app.omymoney.android.R.string.review_history_unavailable)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(compose.activity.getString(app.omymoney.android.R.string.review_no_differences)).assertDoesNotExist()
    }
    @Test fun similarCandidatesCanBeComparedEditedRetainedAndSavedFromApp() {
        val store = (compose.activity.application as OMyMoneyApplication).container.store
        val id = runBlocking {
            val first = store.catalog.initialDraft().copy(amount = Money(2345, CurrencySpec.CNY), note = "同额记录 A")
            store.recognitionQueue.enqueue(RecognitionResult.Matched(first, listOf(FieldEvidence("merchant", emptyList(), "商户 A")), "fixture"))
            store.recognitionQueue.enqueue(RecognitionResult.Matched(first.copy(submissionId = UUID.randomUUID().toString(), note = "同额记录 B"),
                listOf(FieldEvidence("merchant", emptyList(), "商户 B")), "fixture")).id
        }
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_queue").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("queue_$id").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("queue_$id").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("bill_amount").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bill_save").assertIsNotEnabled()
        compose.onNodeWithTag("review_compare").performScrollTo().performClick()
        compose.onNodeWithText("商户：商户 A").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("商户：商户 B").performScrollTo().assertIsDisplayed()
        captureUiEvidence("b11-comparison.png")
        compose.onNodeWithText("确认仍作为新账单保存").performScrollTo().performClick()
        compose.onNodeWithTag("bill_amount").performScrollTo().performTextReplacement("24.00")
        compose.onNodeWithTag("screen_back").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("recognition_queue").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(QueueState.PENDING, runBlocking { store.recognitionQueue.read(id)!!.state })
        compose.onNodeWithTag("queue_$id").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("bill_amount").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bill_amount").assertTextContains("24.00")
        compose.onNodeWithText("确认仍作为新账单保存").performScrollTo().performClick()
        compose.onNodeWithTag("bill_save").performClick()
        compose.waitUntil(5000) { runBlocking { store.recognitionQueue.read(id)!!.state == QueueState.SAVED } }
        compose.onNodeWithText("已处理").performClick()
        compose.onNodeWithTag("queue_$id").assertIsDisplayed()
        captureUiEvidence("b11-history.png")
    }
}
