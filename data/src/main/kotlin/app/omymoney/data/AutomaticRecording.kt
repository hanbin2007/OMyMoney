package app.omymoney.data

import app.omymoney.core.model.*
import app.omymoney.core.recognition.*
import kotlinx.serialization.Serializable

@Serializable internal data class CaptureSlot(val windowKey: String, val ruleId: String?, val entryId: String)
@Serializable internal data class CaptureWindows(val slots: List<CaptureSlot> = emptyList())

internal fun decodeCaptureWindows(value: String?): CaptureWindows = runCatching {
    if (value == null) CaptureWindows() else LedgerJson.format.decodeFromString<CaptureWindows>(value).also { windows ->
        require(windows.slots.size <= 16 && windows.slots.all { it.windowKey.length in 1..256 && (it.ruleId?.length ?: 0) <= 80 })
    }
}.getOrDefault(CaptureWindows())

internal fun QueueEntry.continuationResult(): RecognitionResult {
    val source = result.draft().copy(ruleId = draft.ruleId)
    return when (val current = result) {
        is RecognitionResult.Matched -> current.copy(draft = source)
        is RecognitionResult.NeedsReview -> current.copy(partialDraft = source)
        is RecognitionResult.Unsupported -> current
    }
}

private val successDetailRules = mapOf(
    "AlipayPaySuccess" to setOf("AlipayBillDetail"),
    "AlipayTransfer" to setOf("AlipayBillDetail"),
    "AlipayCharge" to setOf("AlipayChargeDetail", "AlipayBillDetail"),
    "AlipayWithdraw" to setOf("AlipayWithdrawDetail", "AlipayBillDetail"),
    "AlipayTransferIn" to setOf("AlipayBillDetail"),
    "AlipayTransferOut" to setOf("AlipayBillDetail"),
    "AlipayYuLiBaoTransferInSuccess" to setOf("AlipayYuLiBaoTransferInDetail"),
    "AlipayYuLiBaoTransferOutSuccess" to setOf("AlipayYuLiBaoTransferOutDetail"),
    "WeChatPaySuccess" to setOf("WeChatBillDetail", "WechatChargeDetail"),
    "WechatWithdrawSuccess" to setOf("WechatWithdrawDetail"),
    "UnionpayPaySuccess" to setOf("UnionpayBillDetail", "UnionpayMessageOrderDetail"),
    "UnionpayTransferSuccess" to setOf("UnionpayTransferDetail"),
    "MeiTuanPaySuccess" to setOf("MeiTuanWalletBillDetail", "MeiTuanVoucherSuccessDetail"),
    "MeiTuanTakeoutPaySuccess" to setOf("MeiTuanTakeoutOrderDetail"),
    "JingDongPaySuccess" to setOf("JingDongWalletBillDetail", "JingDongWalletBillDetailV2", "JingDongOrderDetail"),
    "DouyingPaySuccess" to setOf("DouyingWalletBillDetail"),
)

internal fun continuesToCaptureDetails(first: RecognitionResult, second: RecognitionResult): Boolean {
    val old = first.draft()
    val next = second.draft()
    if (!sameCapturePayment(first, second) || old.amount == null || old.amount != next.amount || old.kind != next.kind) return false
    val oldRule = old.ruleId ?: return false
    if (next.ruleId !in successDetailRules[oldRule].orEmpty()) return false
    val start = old.recognizedAt ?: return false
    val end = next.recognizedAt ?: return false
    return java.time.Duration.between(start, end).seconds in 0..120
}

internal fun sameCapturePayment(first: RecognitionResult, second: RecognitionResult): Boolean {
    val old = first.draft()
    val next = second.draft()
    if (old.bookId != next.bookId || old.sourcePackage != next.sourcePackage) return false
    if (old.kind != null && next.kind != null && old.kind != next.kind) return false
    if (old.amount != null && next.amount != null && old.amount != next.amount) return false
    if (old.occurredAt != null && next.occurredAt != null && old.occurredAt != next.occurredAt) return false
    if (old.occurredAt == null && next.occurredAt != null && old.ruleId in successDetailRules) {
        val observed = old.recognizedAt ?: return false
        if (java.time.Duration.between(observed, next.occurredAt).abs().seconds > 120) return false
    }
    if (old.payment?.counterparty != null && next.payment?.counterparty != null &&
        old.payment?.counterparty != next.payment?.counterparty) return false
    return ORDER_ID_FIELDS.none { field ->
        val a = first.evidence().filter { it.field == field }.map { it.rawText.trim() }.toSet()
        val b = second.evidence().filter { it.field == field }.map { it.rawText.trim() }.toSet()
        a.isNotEmpty() && b.isNotEmpty() && a != b
    }
}

internal fun mergeCaptureResult(first: RecognitionResult, second: RecognitionResult): RecognitionResult {
    val old = first.draft()
    val next = second.draft()
    val draft = next.copy(submissionId = old.submissionId, recognizedAt = old.recognizedAt,
        occurredAt = next.occurredAt ?: old.occurredAt,
        timeSource = if (next.occurredAt != null) next.timeSource else old.timeSource,
        payment = next.payment ?: old.payment)
    val evidence = (second.evidence() + first.evidence().filter { oldEvidence ->
        second.evidence().none { it.field == oldEvidence.field }
    }).take(256)
    return when (second) {
        is RecognitionResult.Matched -> second.copy(draft = draft, evidence = evidence)
        is RecognitionResult.NeedsReview -> second.copy(partialDraft = draft, evidence = evidence,
            issues = second.issues.filterNot { it.field == "occurredAt" && it.code == "missing" && draft.occurredAt != null })
        is RecognitionResult.Unsupported -> second
    }
}

internal fun enrichAutomaticDraft(before: TransactionDraft, incoming: TransactionDraft): TransactionDraft {
    val old = before.payment
    val next = incoming.payment
    return before.copy(
        fromAccountId = before.fromAccountId ?: incoming.fromAccountId,
        toAccountId = before.toAccountId ?: incoming.toAccountId,
        occurredAt = if (before.timeSource == TimeSource.CAPTURED && incoming.timeSource == TimeSource.PAGE) incoming.occurredAt else before.occurredAt,
        timeSource = if (before.timeSource == TimeSource.CAPTURED && incoming.timeSource == TimeSource.PAGE) TimeSource.PAGE else before.timeSource,
        note = before.note.ifBlank { incoming.note },
        fee = before.fee ?: incoming.fee,
        payment = PaymentDetails(old?.counterparty ?: next?.counterparty,
            old?.fromAccountHint ?: next?.fromAccountHint, old?.toAccountHint ?: next?.toAccountHint),
    )
}
