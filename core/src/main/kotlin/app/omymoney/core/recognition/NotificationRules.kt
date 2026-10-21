package app.omymoney.core.recognition

import app.omymoney.core.diagnostics.*
import app.omymoney.core.model.*
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

data class TransactionNotification(val packageName: String, val key: String, val postedAt: Instant,
    val fields: Map<String, String>, val truncated: Boolean = false) {
    val identity: String get() = UUID.nameUUIDFromBytes(
        "$packageName\u0000$key\u0000$postedAt".toByteArray(Charsets.UTF_8)).toString()
}

/** Notification templates are unverified evidence, never a trigger for automatic ledger writes. */
object NotificationRules {
    const val MAX_CHARS = 16_384
    private val fields = setOf("title", "text", "bigText") + (0..15).map { "line$it" }
    private val transaction = Regex("支付(?!宝)|付款|收款|到账|到賬|入账|入賬|扣款|消费|消費|交易|退款|转账|轉賬|(?i:payment|paid|received|purchase|transaction|refund)")
    private val wechatFinancialTitle = Regex("微信支付|微信收款助手|收款助手|支付通知|付款通知|收款通知|到账通知|到賬通知|交易提醒|交易通知|退款通知|(?i:WeChat Pay)")
    private val advertising = Regex("优惠|優惠|优惠券|優惠券|红包待领|紅包待領|立减|立減|折扣|满.*减|滿.*減|(?i:coupon|discount|promotion)")
    private val incomplete = Regex("失败|失敗|待支付|待付款|等待付款|处理中|處理中|退款申请|退款申請|待收款|待领取|待領取|(?i:failed|pending|declined)")
    private val number = "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]{1,6})?"
    private val completed = Regex("支付成功|付款成功|扣款成功|收款成功|已收款|已到账|已到賬|退款成功|退款到账|退款到賬|已支付|已付款|有一笔${number}元的(?:支出|收入|(?:免密/)?自动扣款支付)|(?i:payment successful|payment received|refunded|\\bpaid\\b)")
    private val money = Regex("(?i)(CNY|RMB|HKD|HK\\$|USD|US\\$|EUR|GBP|JPY|人民币|人民幣|港元|美元|欧元|歐元|英镑|英鎊|日元|円|[¥￥$€£])\\s*($number)(?![0-9.,])|(?<![0-9.,])($number)\\s*(港元|美元|人民币|人民幣|日元|欧元|歐元|英镑|英鎊|元|円)")
    private val merchant = Regex("(?:商户|商戶|商家|收款方|交易对方|交易對方|(?i:merchant))\\s*[:：]\\s*([^\\n,，;；]+)")

    private fun text(sample: TransactionNotification) = sample.fields.values.joinToString("\n")
    fun isCandidate(sample: TransactionNotification): Boolean {
        if (sample.packageName !in RuleRegistry.supportedPackages || sample.truncated || sample.key.length !in 1..1024 ||
            sample.fields.isEmpty() || sample.fields.keys.any { it !in fields } ||
            sample.fields.values.any { it.length > 4096 } || sample.fields.values.sumOf { it.length } > MAX_CHARS) return false
        val text = text(sample)
        // A chat mentioning payment is not a payment-service notification.
        val title = sample.fields["title"]?.trim().orEmpty()
        if (sample.packageName == "com.tencent.mm" && title.isNotEmpty() && !wechatFinancialTitle.matches(title)) return false
        return DiagnosticRedactor.text(text) != "[REDACTED]" && transaction.containsMatchIn(text) &&
            (!advertising.containsMatchIn(text) || completed.containsMatchIn(text))
    }

    fun recognize(sample: TransactionNotification, context: RecognitionContext): RecognitionResult {
        if (!isCandidate(sample)) return RecognitionResult.Unsupported("notification_not_transaction")
        val text = text(sample)
        if (incomplete.containsMatchIn(text) || !completed.containsMatchIn(text)) return RecognitionResult.Unsupported("notification_not_completed")
        val issues = mutableListOf(FieldError("notification", "unverified_template", "Notification requires review"))
        val amounts = money.findAll(text).map { match ->
            val marker = match.groupValues[1].ifEmpty { match.groupValues[4] }.uppercase(java.util.Locale.ROOT)
            val code = when (marker) {
                "CNY", "RMB", "人民币", "人民幣", "元" -> "CNY"
                "HKD", "HK$", "港元" -> "HKD"
                "USD", "US$", "美元" -> "USD"
                "EUR", "€", "欧元", "歐元" -> "EUR"
                "GBP", "£", "英镑", "英鎊" -> "GBP"
                "JPY", "日元", "円" -> "JPY"
                "¥", "￥" -> context.currency.code.takeIf { it in setOf("CNY", "JPY") }
                else -> null
            }
            code?.let { runCatching { Money.parse(match.groupValues[2].ifEmpty { match.groupValues[3] }.replace(",", ""),
                CurrencySpec(it, if (it == "JPY") 0 else 2)).takeIf { value -> value.minor > 0 } }.getOrNull() }
        }.toList()
        val amount = amounts.takeIf { it.isNotEmpty() && it.none { value -> value == null } }?.distinct()?.singleOrNull()
        if (amount == null) issues += FieldError("amount", "ambiguous_or_missing", "Confirm amount and currency")
        val incoming = Regex("收款成功|已收款|已到账|已到賬|到账通知|到賬通知|收入|(?i:payment received)").containsMatchIn(text)
        val outgoing = Regex("支付成功|付款成功|扣款成功|已支付|已付款|自动扣款支付|支出|消费|消費|(?i:payment successful|paid|purchase)").containsMatchIn(text)
        val refund = Regex("退款成功|退款到账|退款到賬|(?i:refunded)").containsMatchIn(text)
        val kind = when {
            refund -> BillKind.REFUND
            incoming && !outgoing -> BillKind.INCOME
            outgoing && !incoming -> BillKind.EXPENSE
            else -> null
        }
        if (kind == null) issues += FieldError("kind", "unknown", "Confirm transaction direction")
        val counterparty = merchant.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.length in 1..256 }?.let(EvidencePrivacy::text)
        val evidence = mutableListOf<FieldEvidence>()
        sample.fields.values.distinct().forEachIndexed { index, value ->
            evidence += EvidencePrivacy.redact(FieldEvidence("notification", listOf(index), value))
        }
        counterparty?.let { evidence += FieldEvidence("merchant", emptyList(), it) }
        val draft = TransactionDraft(bookId = context.bookId,
            submissionId = UUID.nameUUIDFromBytes("notification/${context.bookId}/${sample.identity}".toByteArray(Charsets.UTF_8)).toString(),
            kind = kind, amount = amount, note = counterparty.orEmpty(), recognizedAt = sample.postedAt,
            zoneId = context.zoneId, source = BillSource.NOTIFICATION, sourcePackage = sample.packageName,
            ruleId = "NotificationTransaction", ruleVersion = 2, payment = counterparty?.let { PaymentDetails(it) })
        // Posting time is not transaction time: the editor must confirm it explicitly.
        issues += FieldError("occurredAt", "missing", "Confirm transaction time")
        return RecognitionResult.NeedsReview(draft, issues, evidence, draft.ruleId)
    }

    fun recordSample(sample: TransactionNotification, result: RecognitionResult, diagnostics: DiagnosticSink) {
        if (!diagnostics.enabled || !isCandidate(sample)) return
        diagnostics.recordSafely("notification_sample") { buildJsonObject {
            put("package", sample.packageName); put("sampleId", sample.identity); put("postedAt", sample.postedAt.toString())
            put("templateVersion", 2)
            put("content", buildJsonObject { sample.fields.forEach { (key, value) -> put(key, DiagnosticRedactor.text(EvidencePrivacy.text(value))) } })
            put("result", when (result) {
                is RecognitionResult.Unsupported -> result.reason
                is RecognitionResult.NeedsReview -> "needs_review"
                is RecognitionResult.Matched -> "matched"
            })
        } }
    }
}
