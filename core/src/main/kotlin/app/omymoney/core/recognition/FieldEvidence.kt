package app.omymoney.core.recognition

import app.omymoney.core.model.*
import kotlinx.serialization.Serializable

@Serializable data class FieldEvidence(val field: String, val nodeIndices: List<Int>, val rawText: String,
    val redacted: Boolean = false)

@Serializable sealed interface RecognitionResult {
    @Serializable data class Matched(val draft: TransactionDraft, val evidence: List<FieldEvidence>, val ruleId: String) : RecognitionResult
    @Serializable data class Unsupported(val reason: String) : RecognitionResult
    @Serializable data class NeedsReview(val partialDraft: TransactionDraft, val issues: List<FieldError>,
        val evidence: List<FieldEvidence> = emptyList(), val ruleId: String? = null) : RecognitionResult
}

enum class PaymentState { SUCCEEDED, PENDING, FAILED, UNKNOWN, REFUND_PENDING }

val ORDER_ID_FIELDS: Set<String> = setOf("transactionId", "orderId", "merchantOrderId")
