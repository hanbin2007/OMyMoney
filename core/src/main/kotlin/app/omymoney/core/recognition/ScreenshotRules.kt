package app.omymoney.core.recognition

import java.time.Instant
import app.omymoney.core.model.*

object ScreenshotRules {
    fun recognize(document: OcrDocument, context: RecognitionContext = RecognitionContext(),
        capturedAt: Instant = Instant.now()): RecognitionResult {
        if (document.width !in 1..8192 || document.height !in 1..8192 || document.lines.size > 1024 ||
            document.lines.sumOf { it.text.length.toLong() } > 65536 || document.lines.any {
                it.text.length > 4096 || it.left < 0 || it.top < 0 || it.right <= it.left || it.bottom <= it.top ||
                    it.right > document.width || it.bottom > document.height
            }) return RecognitionResult.Unsupported("invalid_ocr_document")
        val lines = document.lines.filter { it.text.isNotBlank() }.sortedWith(compareBy(OcrLine::top, OcrLine::left, OcrLine::text))
        if (lines.isEmpty()) return RecognitionResult.Unsupported("empty_image")
        val texts = lines.map { cleanText(it.text) }
        val snapshot = UiSnapshot("ocr", 0, capturedAt, lines.map { UiNode(text = it.text) })
        val failed = texts.any { it in setOf("支付失败", "交易失败", "已取消", "交易关闭") }
        val refund = texts.any { it in setOf("退款中", "退款处理中", "等待退款") }
        val pending = texts.any { it in setOf("待付款", "等待付款", "待支付", "等待支付", "等待收款", "待收款") }
        val success = texts.any { it in setOf("支付成功", "付款成功", "交易成功", "收款成功", "转账成功") }
        val known = failed || refund || pending || success
        val payment = when { failed -> PaymentState.FAILED; refund -> PaymentState.REFUND_PENDING; pending -> PaymentState.PENDING
            success -> PaymentState.SUCCEEDED; else -> PaymentState.UNKNOWN }
        val kind = when {
            !known || texts.any { it == "转账成功" } -> null
            texts.any { it == "收款成功" } -> BillKind.INCOME
            texts.any { it in setOf("支付成功", "付款成功", "待付款", "等待付款", "待支付", "等待支付") } -> BillKind.EXPENSE
            else -> null
        }
        fun fields(vararg labels: String): List<FieldCandidate> = lines.indices.flatMap { index ->
            val text = texts[index]
            val label = labels.sortedByDescending(String::length).firstOrNull(text::startsWith) ?: return@flatMap emptyList()
            val inline = text.removePrefix(label).trim().removePrefix(":").trim()
            if (inline.isNotEmpty()) return@flatMap listOf(FieldCandidate(inline, listOf(index), lines[index].text))
            val anchor = lines[index]
            val height = anchor.bottom - anchor.top
            val beside = lines.indices.filter { other -> other != index && lines[other].left >= anchor.right &&
                lines[other].left - anchor.right <= document.width * 0.6 &&
                kotlin.math.abs((lines[other].top + lines[other].bottom) / 2 - (anchor.top + anchor.bottom) / 2) <= height / 2 }
            val nearby = if (beside.isNotEmpty()) beside else lines.indices.filter { other ->
                lines[other].top >= anchor.bottom && lines[other].top - anchor.bottom <= maxOf(height * 3 / 2, 40) &&
                    kotlin.math.abs(lines[other].left - anchor.left) <= maxOf(32, document.width / 12)
            }.let { below -> below.minOfOrNull { lines[it].top }?.let { top -> below.filter { lines[it].top == top } }.orEmpty() }
            nearby.map { other -> FieldCandidate(texts[other], listOf(index, other), "${lines[index].text}\n${lines[other].text}") }
        }
        val amounts = if (known) fields("实付金额", "实际付款", "实际支付", "付款金额", "支付金额", "实付款", "收款金额") else emptyList()
        val times = if (known) fields("支付时间", "付款时间", "交易时间", "收款时间") else emptyList()
        val merchant = if (known) fields("商户名称", "收款商户").distinctBy { it.value }.singleOrNull() else null
        val extracted = DraftExtractor.extract(snapshot, context, if (known) "ScreenshotReceipt" else "ScreenshotUnknown", kind, payment, amounts, times, merchant)
        val source = when { texts.any { it == "微信支付" } -> "com.tencent.mm"; texts.any { it == "支付宝" } -> "com.eg.android.AlipayGphone"; else -> null }
        val extra = if (known) listOf("accountHint" to fields("支付方式", "付款方式"), "orderId" to fields("订单号"),
            "transactionId" to fields("交易单号"), "merchantOrderId" to fields("商户单号", "商家订单号"))
            .mapNotNull { (field, values) -> values.distinctBy { it.value }.singleOrNull()?.takeIf { it.value.length <= 256 }?.let {
                val proof = FieldEvidence(field, it.nodeIndices, it.value)
                if (field in ORDER_ID_FIELDS) proof else EvidencePrivacy.redact(proof)
            } } else emptyList()
        fun draft(value: TransactionDraft) = value.copy(source = BillSource.OCR, sourcePackage = source,
            timeSource = if (value.occurredAt == null) TimeSource.UNKNOWN else TimeSource.OCR)
        return when (extracted) {
            is RecognitionResult.Matched -> extracted.copy(draft = draft(extracted.draft), evidence = extracted.evidence + extra)
            is RecognitionResult.NeedsReview -> extracted.copy(partialDraft = draft(extracted.partialDraft), evidence = extracted.evidence + extra)
            is RecognitionResult.Unsupported -> extracted
        }
    }
}
