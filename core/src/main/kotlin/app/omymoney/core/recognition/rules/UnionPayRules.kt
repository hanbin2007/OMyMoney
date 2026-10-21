package app.omymoney.core.recognition.rules

import app.omymoney.core.recognition.*
import app.omymoney.core.model.BillKind

object UnionPayRules {
    private const val PACKAGE = "com.unionpay"
    private val actualLabels = arrayOf("实付金额", "实际支付", "实付款", "支付金额")
    private fun hasAmount(page: PageFields) = page.hasAmount(*actualLabels, "订单金额", "转账金额", "交易金额")
    private fun detail(page: PageFields) = (page.has("账单详情") || page.has("云闪付交易详情")) &&
        page.typed("客服按钮", "android.view.ViewGroup") && page.hasLabel("当前状态") && page.hasStatus("交易成功") && page.hasLabel("付款方式") && page.hasLabel("订单时间")
    private fun messageKind(page: PageFields) = when (page.fields("交易类别").map { it.value }.distinct().singleOrNull()) {
        "消费" -> BillKind.EXPENSE
        "入账" -> BillKind.INCOME
        "取款" -> BillKind.TRANSFER
        else -> null
    }

    fun create(context: RecognitionContext = RecognitionContext()): List<PageRule> {
        fun rule(id: String, priority: Int, matches: (PageFields) -> Boolean,
            kind: (PageFields) -> BillKind?, amountLabels: Array<String> = emptyArray()): PageRule =
            pageRule(id, PACKAGE, priority, { page ->
                page.snapshot.windowClassName != "com.unionpay.activity.UPActivityMain" &&
                    !listOf("首页", "我的", "收付款").all(page::has) && matches(page)
            }) { page ->
                val kindValue = kind(page)
                if (kindValue == null) RecognitionResult.Unsupported("unsupported_transaction_type")
                else {
                    val amounts = when {
                        page.hasLabel(*actualLabels) -> page.amounts(*actualLabels)
                        id == "UnionpayBillDetail" && page.has("优惠信息") -> emptyList()
                        else -> page.amounts(*amountLabels)
                    }
                    val card = page.fields("卡号")
                    val from = page.fields("付款方式") + if (kindValue != BillKind.INCOME) card else emptyList()
                    val to = page.fields("转账到", "收款方式") + if (kindValue == BillKind.INCOME) card else emptyList()
                    DraftExtractor.extract(page.snapshot, context, id, kindValue, page.state(), amounts,
                        page.fields("支付时间", "交易时间", "转账时间", "到账时间"),
                        merchant = page.fields("收款方", "商户名称").firstOrNull(),
                        remark = page.fields("订单描述", "附言").firstOrNull(), fees = page.fields("手续费", "服务费"))
                        .withEvidence(page.evidence("fromAccountHint", from) + page.evidence("toAccountHint", to) +
                            page.evidence("orderId", page.fields("订单号")) + page.evidence("transactionId", page.fields("交易单号")) +
                            page.evidence("orderTime", page.fields("订单时间")))
                }
            }
        return listOf(
            rule("UnionpayBillDetail", 0, { detail(it) && it.hasLabel("订单金额") && !it.hasLabel("转账金额") && hasAmount(it) }, { BillKind.EXPENSE }, arrayOf("订单金额")),
            rule("UnionpayPaySuccess", 1, { it.hasStatus("支付成功") && it.hasLabel("付款方式") && it.has("完成") && hasAmount(it) }, { BillKind.EXPENSE }),
            rule("UnionpayTransferSuccess", 2, { it.hasStatus("转账成功") && listOf("转账详情", "继续转账", "完成").all(it::has) && hasAmount(it) }, { BillKind.TRANSFER }, arrayOf("转账金额")),
            rule("UnionpayTransferDetail", 3, { detail(it) && it.hasLabel("转账金额") && (it.hasLabel("转账到") || it.hasLabel("收款方式")) && hasAmount(it) }, { BillKind.TRANSFER }, arrayOf("转账金额")),
            rule("UnionpayMessageOrderDetail", 4, { (it.has("交易详情") || it.has("银联交易详情")) &&
                listOf("卡号", "交易时间", "交易类别", "分类").all { label -> it.hasLabel(label) } && hasAmount(it) }, ::messageKind, arrayOf("交易金额"))
        )
    }
}
