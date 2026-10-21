package app.omymoney.core.recognition.rules

import app.omymoney.core.model.BillKind
import app.omymoney.core.recognition.*

/** Standalone takeout consent and rule identities are separate from the main Meituan app. */
object MeituanTakeoutRules {
    const val PACKAGE = "com.sankuai.meituan.takeoutnew"
    private val paidLabels = arrayOf("实付", "实付款", "实付金额", "实付款金额", "实际支付")
    private val orderLabels = arrayOf("订单编号", "订单号码", "订单号")
    private val success = arrayOf("支付成功", "付款成功", "已支付", "已付款", "先用后付自动扣款成功", "极速支付成功")

    fun create(context: RecognitionContext = RecognitionContext()): List<PageRule> {
        fun extract(page: PageFields, id: String, receipt: Boolean): RecognitionResult {
            val amounts = when {
                page.hasLabel(*paidLabels) -> page.amounts(*paidLabels)
                receipt -> page.amounts("已支付")
                else -> page.fields("已支付")
            }
            val merchant = page.fields("商家名称", "店铺名称").distinctBy { it.value }.singleOrNull()
            return DraftExtractor.extract(page.snapshot, context, id, BillKind.EXPENSE, state(page), amounts,
                page.fields("支付时间", "付款时间", "交易时间", "扣款时间"), merchant = merchant)
                .withEvidence(page.evidence("orderId", page.fields(*orderLabels)) +
                    page.evidence("fromAccountHint", page.fields("支付方式", "付款方式")))
        }
        return listOf(
            pageRule("MeiTuanTakeoutOrderDetail", PACKAGE, 0, { page ->
                (page.has("订单详情") || page.has("订单信息")) && page.hasLabel(*orderLabels) &&
                    page.hasLabel(*paidLabels, "已支付", "合计", "订单金额")
            }) { extract(it, "MeiTuanTakeoutOrderDetail", receipt = false) },
            pageRule("MeiTuanTakeoutPaySuccess", PACKAGE, 1, { page ->
                page.hasStatus(*success) && page.amounts(*paidLabels).isNotEmpty() &&
                    listOf("完成", "返回商家", "返回订单", "查看订单", "支付结果").any(page::has)
            }) { extract(it, "MeiTuanTakeoutPaySuccess", receipt = true) },
        )
    }

    private fun state(page: PageFields): PaymentState {
        val base = page.state()
        if (base in setOf(PaymentState.FAILED, PaymentState.REFUND_PENDING, PaymentState.PENDING)) return base
        if (page.hasStatus("未支付", "未付款", "订单已关闭", "支付已取消")) return PaymentState.FAILED
        if (page.hasStatus("退款成功", "退款申请中")) return PaymentState.REFUND_PENDING
        if (page.hasStatus("待扣款", "待支付", "等待支付", "先用后付待扣款", "稍后扣款")) return PaymentState.PENDING
        if (page.hasStatus("先用后付自动扣款成功", "极速支付成功")) return PaymentState.SUCCEEDED
        if (page.allText.any { it.contains("先用后付") }) return PaymentState.PENDING
        if (page.hasStatus(*success)) return PaymentState.SUCCEEDED
        return base
    }
}
