package app.omymoney.platform.overlay

import app.omymoney.core.model.*
import app.omymoney.core.recognition.*
import app.omymoney.data.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ReceiptSelectionTest {
    private val time = Instant.parse("2026-10-15T10:00:00Z")
    private fun receipt(id: String, at: Instant = time): QueueEntry {
        val draft = TransactionDraft("book", kind = BillKind.EXPENSE, amount = Money(1, CurrencySpec.CNY))
        return QueueEntry(id, RecognitionResult.Matched(draft, emptyList(), "test"), draft, QueueState.SAVED, at, at,
            DedupDecision(DedupKind.NEW), listOf(QueueEvent(QueueState.SAVED, at)), billId = "bill-$id", savedRevision = 1, automaticRevision = 1)
    }
    @Test fun alreadyDisplayedReceiptSurvivesFreshnessWindowUntilItsAccessibleTimeoutOrDismissal() {
        val entry = receipt("one")
        assertEquals(entry, selectReceipt(listOf(entry), "one", emptySet(), time.plusSeconds(45)))
        assertNull(selectReceipt(listOf(entry), null, emptySet(), time.plusSeconds(45)))
        assertNull(selectReceipt(listOf(entry), "one", setOf("one"), time.plusSeconds(45)))
    }
    @Test fun onlyCommittedReceiptsCanAppearAndANewReceiptReplacesTheCurrentOne() {
        val old = receipt("one")
        val next = receipt("two", time.plusSeconds(40))
        assertEquals(next, selectReceipt(listOf(old, next), "one", emptySet(), time.plusSeconds(45)))
        assertNull(selectReceipt(listOf(old.copy(state = QueueState.PENDING, automaticRevision = null, billId = null)), null, emptySet(), time))
    }
}
