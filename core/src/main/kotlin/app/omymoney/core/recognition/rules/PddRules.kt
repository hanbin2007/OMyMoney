package app.omymoney.core.recognition.rules

import app.omymoney.core.recognition.*
import app.omymoney.core.model.BillKind

object PddRules {
    private const val PACKAGE = "com.xunmeng.pinduoduo"
    private val actual = Regex("实付\\s*[: ,]?\\s*¥?\\s*([0-9]+(?:\\.[0-9]{1,2})?)元?")
    private val discounted = Regex("实付:\\s*(?:平台优惠|店铺优惠|已优惠|优惠)[0-9]+(?:\\.[0-9]{1,2})?元,\\s*([0-9]+(?:\\.[0-9]{1,2})?)元")
    private val future = Regex("确认收货后,?\\s*自动付款\\s*¥([0-9]+(?:\\.[0-9]{1,2})?)")
    private val orderTimes = arrayOf("下单时间", "拼单时间", "成交时间")
    private val actualLabels = arrayOf("实付金额", "实际支付", "实付款", "支付金额")
    private val orderStates = listOf("交易成功", "商家已发货", "已发货", "拼团中", "待收货", "打包中", "待发货", "打包完成", "运输中", "已签收", "充值到账")

    private fun amounts(page: PageFields): List<FieldCandidate> = when {
        page.hasLabel(*actualLabels) -> page.fields(*actualLabels)
        else -> page.matching(actual, 1) + page.matching(discounted, 1)
    }
    private fun futurePayment(page: PageFields) = page.allText.any { it.contains("先用后付") } || page.any(future)
    private fun paymentState(page: PageFields): PaymentState {
        val state = page.state()
        return if (state == PaymentState.FAILED || state == PaymentState.REFUND_PENDING) state
        else if (futurePayment(page)) PaymentState.PENDING
        else state
    }

    fun create(context: RecognitionContext = RecognitionContext()): List<PageRule> {
        fun extract(page: PageFields, id: String, amounts: List<FieldCandidate>, times: List<FieldCandidate>) =
            DraftExtractor.extract(page.snapshot, context, id, BillKind.EXPENSE, paymentState(page), amounts, times,
                merchant = page.fields("商家名称").firstOrNull(),
                remark = page.fields("商品名称", "商品详情").firstOrNull())
                .withEvidence(page.evidence("fromAccountHint", page.fields("支付方式")) +
                    page.evidence("transactionId", page.fields("交易单号")) + page.evidence("orderId", page.fields("订单编号")) +
                    page.evidence("merchantOrderId", page.fields("商户单号")))
        return listOf(
            pageRule("PddOrderDetail", PACKAGE, 0, { p -> p.hasLabel(*orderTimes) && orderStates.any { p.starts(it) || p.hasStatus(it) } &&
                listOf("申请退款", "取消订单", "申请售后", "售后详情", "联系商家").any(p::has) && p.hasLabel("商品名称") &&
                (p.starts("实付") || p.any(future) || p.starts("拼单价")) }) { p ->
                val amounts = if (futurePayment(p)) p.matching(future, 1).ifEmpty { amounts(p) } else amounts(p)
                val times = if (futurePayment(p)) emptyList() else p.fields("支付时间", "成交时间")
                extract(p, "PddOrderDetail", amounts, times)
            },
            pageRule("PddPayFirstDialog", PACKAGE, 1, { p -> p.has("使用“先用后付”下单成功") && p.starts("先用后付下单，实付") &&
                p.has("等待收货") && p.any(future) && p.has("知道了") && p.has("查看订单") }) { p ->
                extract(p, "PddPayFirstDialog", p.matching(future, 1), emptyList())
            },
            pageRule("PddWalletBillDetail", PACKAGE, 2, { p -> p.has("账单详情") && p.hasStatus("支付成功") &&
                listOf("当前状态", "商品详情", "支付时间", "支付方式", "交易单号", "商户单号").all { p.hasLabel(it) } }) { p ->
                val amounts = p.amounts(*actualLabels)
                extract(p, "PddWalletBillDetail", amounts, p.fields("支付时间"))
            }
        )
    }
}
