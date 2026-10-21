package app.omymoney.core.recognition.rules

import app.omymoney.core.recognition.*
import app.omymoney.core.model.BillKind
import app.omymoney.core.model.FieldError

object MeituanRules {
    private const val PACKAGE = "com.sankuai.meituan"
    private val actualLabels = arrayOf("实付", "实付款", "实付金额", "实际支付", "已支付")
    private val bikeAmount = Regex("已支付([0-9]+(?:\\.[0-9]{1,2})?)元")
    private val bikeDuration = Regex("骑行[0-9]+分[0-9]+秒")
    private val walletState = Regex("(?:支付成功|先用后付自动扣款成功|极速支付成功|已退款[0-9]+(?:\\.[0-9]{1,2})?元)")
    private fun state(page: PageFields, fallback: PaymentState = PaymentState.UNKNOWN): PaymentState {
        val base = page.state(fallback)
        if (base in setOf(PaymentState.FAILED, PaymentState.REFUND_PENDING, PaymentState.PENDING)) return base
        if (page.hasStatus("先用后付自动扣款成功", "极速支付成功", "买单成功！")) return PaymentState.SUCCEEDED
        if (page.has("待扣款") || page.allText.any { it.contains("先用后付") }) return PaymentState.PENDING
        return base
    }

    fun create(context: RecognitionContext = RecognitionContext()): List<PageRule> {
        fun extract(page: PageFields, id: String, amounts: List<FieldCandidate> = page.fields(*actualLabels),
            times: List<FieldCandidate> = page.fields("支付时间", "交易时间", "扣款时间"),
            state: PaymentState = state(page), account: List<FieldCandidate> = page.fields("支付方式", "付款方式"),
            remark: FieldCandidate? = page.fields("商家名称", "商品名称", "备注").firstOrNull()): RecognitionResult =
            DraftExtractor.extract(page.snapshot, context, id, BillKind.EXPENSE, state, amounts, times, remark = remark)
                .withEvidence(page.evidence("fromAccountHint", account) + page.evidence("orderId", page.fields("订单编号", "订单号码")) +
                    page.evidence("usageState", page.matching(Regex("已使用|待到店使用|已消费|订单已完成|订单完成|放映结束"))))
        fun rule(id: String, priority: Int, matches: (PageFields) -> Boolean, extract: (PageFields) -> RecognitionResult) =
            pageRule(id, PACKAGE, priority, { p -> (id == "MeiTuanWalletBillDetail" || !p.has("账单详情")) && matches(p) }, extract)
        return listOf(
            rule("MeiTuanOrderDetail", 0, { p -> p.has("已消费") && p.hasLabel("实付") && p.has("订单信息") && p.hasLabel("订单编号") && p.hasLabel("下单时间") }) { p ->
                extract(p, "MeiTuanOrderDetail")
            },
            rule("MeiTuanVoucherDetail", 1, { p -> (p.has("已使用") || p.has("待到店使用")) && p.hasLabel("实付") &&
                listOf("使用须知", "有效日期", "使用时间").all(p::has) && !p.has("团购详情") }) { p ->
                extract(p, "MeiTuanVoucherDetail")
            },
            rule("MeiTuanVoucherSuccessDetail", 2, { p -> p.hasStatus("买单成功！") &&
                p.hasLabel("实付金额") && p.hasLabel("订单编号") && p.has("复制") && p.hasLabel("消费时间") }) { p ->
                val amounts = p.fields(*actualLabels)
                val times = if (p.hasLabel("支付时间", "交易时间")) p.fields("支付时间", "交易时间")
                    else p.fields("消费时间")
                extract(p, "MeiTuanVoucherSuccessDetail", amounts, times,
                    remark = p.statusCandidates("买单成功！").firstOrNull()?.nodeIndices?.single()?.let(p::after))
                    .withEvidence(p.evidence("orderId", p.fields("订单编号")))
            },
            rule("MeiTuanGrouponDetail", 3, { p -> p.has("已使用") && p.hasLabel("实付") &&
                listOf("团购详情", "使用须知", "有效日期", "使用时间", "订单信息").all(p::has) }) { p ->
                extract(p, "MeiTuanGrouponDetail")
            },
            rule("MeiTuanMovieDetail", 4, { p -> (p.has("放映结束") || p.any(Regex(".*[0-9]{2}:[0-9]{2}开场.*"))) &&
                (p.has("展开取票码") || p.has("取电影票")) && p.has("订单详情") && p.hasLabel("实付金额") && p.hasLabel("订单号码") && p.hasLabel("订单时间") }) { p ->
                extract(p, "MeiTuanMovieDetail")
            },
            rule("MeiTuanChargeOrderDetail", 5, { p -> listOf("订单完成", "充电宝已归还，感谢使用", "使用时长", "订单金额", "元", "再次租借").all(p::has) }) { p ->
                extract(p, "MeiTuanChargeOrderDetail", if (p.hasLabel(*actualLabels)) p.fields(*actualLabels) else p.fields("订单金额"))
            },
            rule("MeiTuanBikeOrderDetail", 6, { p -> p.any(bikeAmount) && p.has("费用申诉") && p.has("车辆报修") && p.any(PageFields.FULL_TIME) && p.any(bikeDuration) }) { p ->
                extract(p, "MeiTuanBikeOrderDetail", p.matching(bikeAmount, 1), p.times("支付时间"), state(p, PaymentState.SUCCEEDED), remark = p.matching(bikeDuration).firstOrNull())
            },
            rule("MeiTuanPaySuccess", 7, { p -> p.typedStatus("支付成功", "android.webkit.WebView") && p.statusAmounts("支付成功").isNotEmpty() }) { p ->
                extract(p, "MeiTuanPaySuccess", p.amounts(*actualLabels), account = p.statusAmounts("支付成功").map { p.after(it.nodeIndices.single()) })
            },
            rule("MeiTuanWalletBillDetail", 8, { p -> p.has("账单详情") &&
                (p.any(walletState) || p.hasStatus("支付成功", "先用后付自动扣款成功", "极速支付成功")) && p.hasLabel("支付方式") && p.hasLabel("下单时间") }) { p ->
                val amounts = if (p.hasLabel(*actualLabels)) p.amounts(*actualLabels) else
                    (p.matching(walletState) + p.statusCandidates("支付成功", "先用后付自动扣款成功", "极速支付成功")).flatMap { p.before(it.value, PageFields.MONEY) } + p.statusAmounts("支付成功", "先用后付自动扣款成功", "极速支付成功")
                extract(p, "MeiTuanWalletBillDetail", amounts)
            },
            rule("MeiTuanDeliveryDetail", 9, { p -> p.any(Regex(".*(?:订单已完成|已提前[0-9]+分钟送达|预计送达).*")) &&
                (p.has("合计") || p.has("实付款")) && p.has("¥") }) { p ->
                val paidAmount = p.hasLabel(*actualLabels)
                val result = extract(p, "MeiTuanDeliveryDetail", if (paidAmount) p.fields(*actualLabels) else p.fields("合计"))
                // A delivery total can prefill review, but is not evidence of the amount settled.
                val issue = FieldError("amount", "order_total_unconfirmed", "Confirm the amount actually paid")
                if (paidAmount) result else when (result) {
                    is RecognitionResult.Matched -> RecognitionResult.NeedsReview(result.draft, listOf(issue), result.evidence, result.ruleId)
                    is RecognitionResult.NeedsReview -> if (result.partialDraft.amount == null) result else result.copy(issues = result.issues + issue)
                    is RecognitionResult.Unsupported -> result
                }
            }
        )
    }
}
