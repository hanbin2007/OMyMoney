package app.omymoney.core.recognition.rules

import app.omymoney.core.model.BillKind
import app.omymoney.core.model.FieldError
import app.omymoney.core.recognition.*

/** Native China app only. Initial templates stay review-only until real device evidence is available. */
object McDonaldsRules {
    const val PACKAGE = "com.mcdonalds.gma.cn"
    private val paidLabels = arrayOf("实付金额", "实付款", "实付", "实际支付", "已支付", "支付金额", "付款金额", "Actually paid")
    private val totalLabels = arrayOf("合计", "总计", "订单金额", "Subtotal")
    private val orderLabels = arrayOf("订单号", "订单编号", "订单号码", "Order ID")
    private val success = arrayOf("支付成功", "付款成功", "已支付", "已付款", "Payment Success")
    private val blocked = arrayOf("待付款", "待支付", "未支付", "未付款", "支付失败", "订单已取消", "已取消",
        "订单已关闭", "交易关闭", "退款中", "退款成功", "退款完成", "已退款", "部分退款",
        "Payment Failed", "Cancelled", "Canceled", "Refunded", "Refund in progress", "Awaiting Payment", "Pending Payment")

    fun create(context: RecognitionContext = RecognitionContext()): List<PageRule> {
        fun extract(page: PageFields, id: String): RecognitionResult {
            val state = page.state()
            if (page.hasStatus(*blocked) || state in setOf(PaymentState.FAILED, PaymentState.REFUND_PENDING)) {
                return RecognitionResult.Unsupported("mcdonalds_not_completed")
            }
            val paid = page.hasLabel(*paidLabels)
            val amount = if (paid) page.fields(*paidLabels) else page.fields(*totalLabels)
            val merchant = page.fields("餐厅名称", "门店名称", "商家名称").distinctBy { it.value }.singleOrNull()
            val result = DraftExtractor.extract(page.snapshot, context, id, BillKind.EXPENSE,
                if (state == PaymentState.PENDING) state else if (page.hasStatus(*success)) PaymentState.SUCCEEDED else state,
                amount, page.fields("支付时间", "付款时间", "交易时间", "扣款时间", "Pay time"), merchant = merchant)
                .withEvidence(page.evidence("orderId", page.fields(*orderLabels)) +
                    page.evidence("fromAccountHint", page.fields("支付方式", "付款方式", "Payment Method")))
            val issues = listOf(FieldError("recognition", "unverified_template", "Confirm the native app transaction")) +
                if (paid) emptyList() else listOf(FieldError("amount", "order_total_unconfirmed", "Confirm the amount actually paid"))
            return when (result) {
                is RecognitionResult.Matched -> RecognitionResult.NeedsReview(result.draft, issues, result.evidence, id)
                is RecognitionResult.NeedsReview -> result.copy(issues = result.issues + issues)
                is RecognitionResult.Unsupported -> result
            }
        }
        return listOf(
            pageRule("McDonaldsOrderDetail", PACKAGE, 0, { page ->
                (page.has("订单详情") || page.has("订单信息") || page.has("Order Details")) && page.fields(*orderLabels).any { it.value.isNotBlank() } &&
                    page.hasLabel(*paidLabels, *totalLabels)
            }) { extract(it, "McDonaldsOrderDetail") },
            pageRule("McDonaldsPaymentResult", PACKAGE, 1, { page ->
                page.hasStatus(*success) && page.hasLabel(*paidLabels) &&
                    listOf("完成", "返回订单", "查看订单", "支付结果").any(page::has)
            }) { extract(it, "McDonaldsPaymentResult") },
        )
    }
}
