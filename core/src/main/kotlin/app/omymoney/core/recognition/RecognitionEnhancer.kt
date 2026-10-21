package app.omymoney.core.recognition

import app.omymoney.core.model.*
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

fun interface RecognitionEnhancer {
    suspend fun enhance(request: EnhancementRequest): EnhancementResult
}

@Serializable enum class EnhancementField { AMOUNT, FROM_ACCOUNT, TO_ACCOUNT, CATEGORY, NOTE }
@Serializable enum class EnhancementFailure { INVALID_RESPONSE, PROVIDER_FAILURE }
@Serializable sealed interface EnhancementValue {
    @Serializable data class Amount(val money: Money) : EnhancementValue
    @Serializable data class CandidateId(val id: String) : EnhancementValue
    @Serializable data class Text(val text: String) : EnhancementValue
}
@Serializable data class FieldSuggestion(val field: EnhancementField, val value: EnhancementValue,
    val evidenceIndex: Int, val quote: String)
sealed interface EnhancementResult {
    data object Unavailable : EnhancementResult
    @Serializable data class Suggestions(val draftVersion: Long, val fields: List<FieldSuggestion>) : EnhancementResult
    data class Failed(val reason: EnhancementFailure) : EnhancementResult
}

data class EnhancementState(val draft: TransactionDraft, val version: Long,
    val allowedAccountIds: Set<String>, val allowedCategoryIds: Set<String>,
    val manuallyEdited: Set<EnhancementField> = emptySet())

data class EnhancementDraft(val bookId: String, val id: String?, val submissionId: String,
    val kind: BillKind?, val amount: Money?, val fromAccountId: String?, val toAccountId: String?,
    val categoryId: String?, val note: String)

class EnhancementRequest(state: EnhancementState, evidence: List<FieldEvidence>, val timeoutMillis: Long = 1_000) {
    init {
        require(state.version >= 0 && timeoutMillis in 1..30_000)
        require(evidence.size <= 256 && evidence.sumOf { it.rawText.length.toLong() } <= 65_536)
        require(evidence.all { it.rawText.length <= 4096 && it.nodeIndices.size <= 1024 })
        require((state.allowedAccountIds + state.allowedCategoryIds).all { it.length in 1..128 })
        require(state.allowedAccountIds.size <= 1000 && state.allowedCategoryIds.size <= 1000)
    }
    val draftVersion = state.version
    val draft = snapshot(state.draft)
    val allowedAccountIds: Set<String> = Collections.unmodifiableSet(state.allowedAccountIds.toSet())
    val allowedCategoryIds: Set<String> = Collections.unmodifiableSet(state.allowedCategoryIds.toSet())
    val manuallyEdited: Set<EnhancementField> = Collections.unmodifiableSet(state.manuallyEdited.toSet())
    val evidence: List<FieldEvidence> = Collections.unmodifiableList(evidence.filter { it.field in setOf(
        "amount", "merchant", "note", "accountHint", "fromAccountHint", "toAccountHint", "categoryHint") }
        .map { EvidencePrivacy.redact(it).copy(nodeIndices = Collections.unmodifiableList(it.nodeIndices.toList())) })

    companion object {
        internal fun snapshot(draft: TransactionDraft) = EnhancementDraft(draft.bookId, draft.id, draft.submissionId,
            draft.kind, draft.amount, draft.fromAccountId, draft.toAccountId, draft.categoryId, EvidencePrivacy.text(draft.note).take(2048))
    }
}

enum class EnhancementStatus { UNAVAILABLE, FAILED, TIMED_OUT, STALE, VALIDATED }
data class ValidatedEnhancement(val draft: TransactionDraft, val status: EnhancementStatus,
    val accepted: Set<EnhancementField> = emptySet(), val rejected: Set<EnhancementField> = emptySet())

class EnhancementCoordinator(private val enhancer: RecognitionEnhancer = NoOpRecognitionEnhancer()) {
    suspend fun enhance(request: EnhancementRequest, current: () -> EnhancementState): ValidatedEnhancement {
        currentCoroutineContext().ensureActive()
        val result = try {
            withTimeoutOrNull(request.timeoutMillis) { enhancer.enhance(request) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            EnhancementResult.Failed(EnhancementFailure.PROVIDER_FAILURE)
        }
        currentCoroutineContext().ensureActive()
        val latest = current()
        return if (result == null) ValidatedEnhancement(latest.draft, EnhancementStatus.TIMED_OUT)
        else EnhancementValidator.apply(request, latest, result)
    }
}
