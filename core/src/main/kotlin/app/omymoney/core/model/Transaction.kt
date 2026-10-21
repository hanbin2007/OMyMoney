@file:kotlinx.serialization.UseSerializers(InstantSerializer::class, ZoneIdSerializer::class)
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.omymoney.core.model

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
enum class BillKind {
    EXPENSE, INCOME, TRANSFER, CREDIT_PAYMENT, REFUND, REIMBURSEMENT, REIMBURSED,
    DEBT_BORROW, DEBT_LEND, DEBT_REPAY, DEBT_COLLECT, INTEREST_EXPENSE, INTEREST_INCOME, ADJUSTMENT,
}

@Serializable enum class TimeSource { UNKNOWN, USER, PAGE, OCR, IMPORT, SCHEDULE, CAPTURED }
@Serializable enum class BillSource { MANUAL, ACCESSIBILITY, OCR, IMPORT, SCHEDULE, NOTIFICATION }
@Serializable data class PaymentDetails(val counterparty: String? = null,
    val fromAccountHint: String? = null, val toAccountHint: String? = null)
@Serializable
data class AttachmentRef(val id: String, val relativePath: String, val mimeType: String, val sha256: String)

@Serializable
data class TransactionDraft(
    val bookId: String,
    val id: String? = null,
    val submissionId: String = UUID.randomUUID().toString(),
    val kind: BillKind? = null,
    val amount: Money? = null,
    val fromAccountId: String? = null,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    val note: String = "",
    val tags: List<String> = emptyList(),
    val attachments: List<AttachmentRef> = emptyList(),
    val occurredAt: Instant? = null,
    val timeSource: TimeSource = TimeSource.UNKNOWN,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val recognizedAt: Instant? = null,
    val linkedBillId: String? = null,
    val fee: Money? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val exchange: ExchangeSnapshot? = null,
    val excludeStats: Boolean = false,
    val excludeBudget: Boolean = false,
    val source: BillSource = BillSource.MANUAL,
    val sourcePackage: String? = null,
    val ruleId: String? = null,
    val ruleVersion: Int? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val payment: PaymentDetails? = null,
)

@Serializable data class Posting(val accountId: String, val amount: Money)

@Serializable
data class Bill(
    val id: String,
    val revision: Long,
    val savedAt: Instant,
    val submissionId: String,
    val bookId: String,
    val kind: BillKind,
    val amount: Money,
    val occurredAt: Instant,
    val timeSource: TimeSource,
    val zoneId: ZoneId,
    val fromAccountId: String? = null,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    val note: String = "",
    val tags: List<String> = emptyList(),
    val attachments: List<AttachmentRef> = emptyList(),
    val recognizedAt: Instant? = null,
    val linkedBillId: String? = null,
    val fee: Money? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val exchange: ExchangeSnapshot? = null,
    val excludeStats: Boolean = false,
    val excludeBudget: Boolean = false,
    val source: BillSource = BillSource.MANUAL,
    val sourcePackage: String? = null,
    val ruleId: String? = null,
    val ruleVersion: Int? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val payment: PaymentDetails? = null,
)

data class BillQuery(
    val bookId: String? = null,
    val accountId: String? = null,
    val kind: BillKind? = null,
    val categoryId: String? = null,
    val startInclusive: Instant? = null,
    val endExclusive: Instant? = null,
    val search: String = "",
    val tag: String? = null,
)

sealed interface SaveResult {
    data class Saved(val billId: String, val revision: Long) : SaveResult
    data class Invalid(val fieldErrors: List<FieldError>) : SaveResult
    data class Conflict(val currentRevision: Long?) : SaveResult
    data class Failure(val code: String) : SaveResult
}

@Serializable data class UndoToken(val id: String, val billId: String, val expiresAt: Instant)
@Serializable data class FieldError(val field: String, val code: String, val message: String)
class DraftValidationException(val errors: List<FieldError>) : IllegalArgumentException(errors.joinToString { it.message })

fun Bill.toDraft(submissionId: String = UUID.randomUUID().toString()) = TransactionDraft(
    bookId = bookId, id = id, submissionId = submissionId, kind = kind, amount = amount,
    fromAccountId = fromAccountId, toAccountId = toAccountId, categoryId = categoryId,
    note = note, tags = tags, attachments = attachments, occurredAt = occurredAt, timeSource = timeSource,
    zoneId = zoneId, recognizedAt = recognizedAt, linkedBillId = linkedBillId, fee = fee, exchange = exchange,
    excludeStats = excludeStats, excludeBudget = excludeBudget, source = source,
    sourcePackage = sourcePackage, ruleId = ruleId, ruleVersion = ruleVersion, payment = payment,
)
