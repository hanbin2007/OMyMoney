package app.omymoney.core.recognition

import app.omymoney.core.model.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class DeduplicatorTest {
    private val time = Instant.parse("2026-10-11T10:00:00Z")
    private val draft = TransactionDraft("book", kind = BillKind.EXPENSE, amount = Money(1230, CurrencySpec.CNY),
        occurredAt = time, recognizedAt = time, sourcePackage = "com.tencent.mm")
    private fun candidate(id: String, draft: TransactionDraft = this.draft.copy(submissionId = id), merchant: String = "shop", order: String? = null) =
        DedupCandidate(id, draft, listOf(FieldEvidence("merchant", listOf(1), merchant)) +
            listOfNotNull(order?.let { FieldEvidence("orderId", listOf(2), it) }))

    @Test fun sameSubmissionOrExplicitOrderIsExactButAmountAloneIsNot() {
        assertEquals(DedupKind.EXACT_DUPLICATE, Deduplicator.compare(candidate("a"), listOf(candidate("a"))).kind)
        val first = candidate("a", order = "WX1234")
        assertEquals(DedupKind.EXACT_DUPLICATE, Deduplicator.compare(candidate("b", order = "WX1234"), listOf(first)).kind)
        assertEquals(DedupKind.SIMILAR, Deduplicator.compare(candidate("b"), listOf(first)).kind)
    }
    @Test fun differentMerchantAndConflictingOrderContentRemainReviewable() {
        val decision = Deduplicator.compare(candidate("b", merchant = "other"), listOf(candidate("a")))
        assertEquals(DedupKind.SIMILAR, decision.kind)
        assertTrue("merchant" in decision.matches.single().differences)
        val conflict = candidate("b", draft.copy(submissionId = "b", amount = Money(999, CurrencySpec.CNY)), order = "one")
        assertEquals(DedupKind.SIMILAR, Deduplicator.compare(conflict, listOf(candidate("a", order = "one"))).kind)
    }
    @Test fun missingTimeUsesOneHourCaptureWindowWithoutPermanentSuppression() {
        val old = candidate("a", draft.copy(occurredAt = null, recognizedAt = time, submissionId = "a"))
        val recent = candidate("b", draft.copy(occurredAt = null, recognizedAt = time.plusSeconds(3600), submissionId = "b"))
        val decision = Deduplicator.compare(recent, listOf(old))
        assertEquals(DedupKind.SIMILAR, decision.kind)
        assertTrue("unknown_time_window" in decision.matches.single().reasons)
        assertEquals(DedupKind.NEW, Deduplicator.compare(recent.copy(draft = recent.draft.copy(recognizedAt = time.plusSeconds(3601))), listOf(old)).kind)
        assertEquals(DedupKind.NEW, Deduplicator.compare(recent.copy(draft = recent.draft.copy(recognizedAt = null)), listOf(old)).kind)
    }
    @Test fun sixtySecondBoundaryAndSourceScopeAreExplicit() {
        val old = candidate("a")
        assertEquals(DedupKind.SIMILAR, Deduplicator.compare(candidate("b", draft.copy(submissionId = "b", occurredAt = time.plusSeconds(60))), listOf(old)).kind)
        assertEquals(DedupKind.NEW, Deduplicator.compare(candidate("b", draft.copy(submissionId = "b", occurredAt = time.plusSeconds(61))), listOf(old)).kind)
        assertEquals(DedupKind.NEW, Deduplicator.compare(candidate("b", draft.copy(bookId = "other", submissionId = "a")), listOf(old)).kind)
        assertNotEquals(DedupKind.EXACT_DUPLICATE, Deduplicator.compare(candidate("b", draft.copy(sourcePackage = "other", submissionId = "b"), order = "one"), listOf(candidate("a", order = "one"))).kind)
    }
    @Test fun conflictingOrderEvidenceAndDifferentExplicitOrdersNeverBecomeExact() {
        val second = candidate("b", order = "two")
        assertNotEquals(DedupKind.EXACT_DUPLICATE, Deduplicator.compare(second, listOf(candidate("a", order = "one"))).kind)
        val ambiguous = candidate("b", order = "one").let { it.copy(evidence = it.evidence + FieldEvidence("orderId", listOf(3), "two")) }
        assertNotEquals(DedupKind.EXACT_DUPLICATE, Deduplicator.compare(ambiguous, listOf(candidate("a", order = "one"))).kind)
    }
}
