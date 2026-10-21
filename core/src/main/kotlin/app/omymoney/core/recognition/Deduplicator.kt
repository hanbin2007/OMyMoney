package app.omymoney.core.recognition

import app.omymoney.core.model.TransactionDraft
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable

data class DedupCandidate(val id: String, val draft: TransactionDraft, val evidence: List<FieldEvidence> = emptyList())
@Serializable enum class DedupKind { EXACT_DUPLICATE, SIMILAR, NEW }
@Serializable data class DedupMatch(val id: String, val reasons: List<String>, val differences: List<String>)
@Serializable data class DedupDecision(val kind: DedupKind, val matches: List<DedupMatch> = emptyList())

object Deduplicator {
    fun compare(candidate: DedupCandidate, history: List<DedupCandidate>): DedupDecision {
        val exact = mutableListOf<DedupMatch>()
        val similar = mutableListOf<DedupMatch>()
        for (previous in history) {
            val first = candidate.draft
            val old = previous.draft
            if (first.bookId != old.bookId) continue
            val differences = differences(candidate, previous)
            val sameSubmission = first.submissionId.isNotBlank() && first.submissionId == old.submissionId
            val merchant = value(candidate, "merchant")
            val matchingIdentity = ORDER_ID_FIELDS.any { field ->
                val id = value(candidate, field)
                id != null && id == value(previous, field) && (field != "merchantOrderId" ||
                    merchant != null && merchant == value(previous, "merchant"))
            }
            val identifierConflict = ORDER_ID_FIELDS.any { field ->
                val a = value(candidate, field); val b = value(previous, field)
                a != null && b != null && a != b
            }
            val sameOrder = matchingIdentity &&
                !first.sourcePackage.isNullOrBlank() && first.sourcePackage == old.sourcePackage
            if (sameSubmission || (sameOrder && !identifierConflict && differences.none { it in setOf("amount", "kind", "merchant") })) {
                exact += DedupMatch(previous.id, listOf(if (sameSubmission) "submission_id" else "order_id"), differences)
            } else if (sameOrder) {
                similar += DedupMatch(previous.id, listOf("order_conflict"), differences)
            } else if (first.amount != null && first.amount == old.amount && first.kind == old.kind) {
                val knownTime = first.occurredAt != null && old.occurredAt != null
                val inWindow = if (knownTime) near(first.occurredAt, old.occurredAt, 60)
                    else near(first.recognizedAt, old.recognizedAt, 3600)
                if (inWindow) similar += DedupMatch(previous.id, listOf(if (knownTime) "same_amount_60s" else "unknown_time_window"), differences)
            }
        }
        return when {
            exact.isNotEmpty() -> DedupDecision(DedupKind.EXACT_DUPLICATE, exact.sortedBy { it.id })
            similar.isNotEmpty() -> DedupDecision(DedupKind.SIMILAR, similar.sortedBy { it.id })
            else -> DedupDecision(DedupKind.NEW)
        }
    }

    fun differences(candidate: DedupCandidate, previous: DedupCandidate): List<String> = buildList {
        val first = candidate.draft
        val old = previous.draft
        if (first.amount != old.amount) add("amount")
        if (first.kind != old.kind) add("kind")
        if (first.occurredAt != old.occurredAt) add("occurredAt")
        if (value(candidate, "merchant") != value(previous, "merchant")) add("merchant")
        if (ORDER_ID_FIELDS.any { value(candidate, it) != value(previous, it) }) add("orderId")
        if (first.sourcePackage != old.sourcePackage) add("sourcePackage")
        if (first.fromAccountId != old.fromAccountId || first.toAccountId != old.toAccountId) add("accounts")
    }

    private fun value(candidate: DedupCandidate, field: String): String? = candidate.evidence.filter { it.field == field }
        .map { it.rawText.trim() }.filter { it.isNotBlank() }.distinct().singleOrNull()

    private fun near(first: Instant?, second: Instant?, seconds: Long): Boolean = first != null && second != null &&
        Duration.between(first, second).abs() <= Duration.ofSeconds(seconds)
}
