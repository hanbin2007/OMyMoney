package app.omymoney.core.recognition.rules

import app.omymoney.core.recognition.*
import app.omymoney.core.model.BillKind

object JdRules {
    private const val PACKAGE = "com.jingdong.app.mall"
    private val actualLabels = arrayOf("实付款", "实际支付", "实付金额")
    private val walletState = Regex("(?:交易成功|已退款\\(¥[0-9.]+\\))")
    private const val SUCCESS_BOUNDARY = "交易成功(?=$|\\s+(?:支付方式|付款方式|创建时间|支付时间|收款时间|交易时间|总订单编号|订单编号|京东平台商户|服务详情)(?:\\s|:))"
    private val mergedAmount = Regex("(?:京东平台商户\\s*)?(-[0-9,]+\\.[0-9]{2})\\s*(?:$SUCCESS_BOUNDARY|已退款\\(¥)")
    private val paymentLine = Regex("(?!微信支付)([^¥]{1,80}?)¥([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)(?:\\s*共优惠¥[0-9]+(?:\\.[0-9]{1,2})?)?")
    private val mergedTime = Regex("(?:支付时间|收款时间|交易时间)\\s*:?\\s*([0-9]{4}-[0-9]{1,2}-[0-9]{1,2} [0-9]{1,2}:[0-9]{2}:[0-9]{2})")
    private fun walletTitle(page: PageFields) = page.typed("账单详情", "android.webkit.WebView")
    private fun state(page: PageFields, fallback: PaymentState = PaymentState.UNKNOWN): PaymentState {
        val base = page.state(fallback)
        if (base in setOf(PaymentState.FAILED, PaymentState.REFUND_PENDING, PaymentState.PENDING)) return base
        val merged = page.allText
        return when {
            merged.any { it.contains("交易失败") || it.contains("已取消") || it == "待付款" } -> PaymentState.FAILED
            merged.any { it.contains("已退款") || it.contains("部分退款") || it.contains("退款中") } -> PaymentState.REFUND_PENDING
            page.has("等待处理") || page.has("处理中") -> PaymentState.PENDING
            page.captures(mergedAmount).isNotEmpty() -> PaymentState.SUCCEEDED
            else -> base
        }
    }

    fun create(context: RecognitionContext = RecognitionContext()): List<PageRule> {
        fun extract(page: PageFields, id: String, kind: BillKind, amounts: List<FieldCandidate>,
            state: PaymentState = state(page), times: List<FieldCandidate> = page.fields("支付时间", "收款时间", "交易时间"),
            account: List<FieldCandidate> = page.fields("支付方式", "付款方式")): RecognitionResult {
            val incoming = kind == BillKind.INCOME
            val hints = if (incoming) page.fields("收款方式", "收款银行") else account
            return DraftExtractor.extract(page.snapshot, context, id, kind, state, amounts, times,
                merchant = page.fields("商家名称").firstOrNull(),
                remark = page.fields("商品名称", "旧机信息", "备注").firstOrNull())
                .withEvidence(page.evidence(if (incoming) "toAccountHint" else "fromAccountHint", hints) +
                    page.evidence("orderId", if (id.endsWith("V2")) page.captures(Regex("总订单编号\\s+(\\S+)")) else page.fields("总订单编号", "订单编号")))
        }
        return listOf(
            pageRule("JingDongWalletBillDetail", PACKAGE, 0, { p -> walletTitle(p) && (p.hasLabel("支付方式") || p.hasLabel("付款方式")) &&
                p.hasLabel("创建时间") && p.hasLabel("总订单编号") && (p.any(walletState) || p.hasStatus("交易成功")) && p.any(Regex("共[0-9]+笔订单")) }) { p ->
                val amounts = if (p.hasLabel(*actualLabels)) p.amounts(*actualLabels) else
                    (p.matching(walletState) + p.statusCandidates("交易成功")).flatMap { p.before(it.value, PageFields.MONEY) } + p.statusAmounts("交易成功")
                extract(p, "JingDongWalletBillDetail", BillKind.EXPENSE, amounts)
            },
            pageRule("JingDongWalletBillDetailV2", PACKAGE, 1, { p -> walletTitle(p) && (p.starts("支付方式") || p.starts("支付立减")) &&
                p.allText.any { it.contains("创建时间") } && p.allText.any { it.contains("总订单编号") } &&
                (p.captures(mergedAmount).isNotEmpty() || p.hasStatus("交易成功")) }) { p ->
                val amounts = if (p.hasLabel(*actualLabels)) p.amounts(*actualLabels) else p.captures(mergedAmount)
                val accounts = p.captures(Regex("支付方式\\s+(.{1,80}?)\\s+(?:创建时间|支付时间|总订单编号|京东平台商户)"))
                extract(p, "JingDongWalletBillDetailV2", BillKind.EXPENSE, amounts, times = p.captures(mergedTime), account = accounts)
            },
            pageRule("JingDongAihuishouOrderDetail", PACKAGE, 2, { p -> p.typed("订单详情", "android.webkit.WebView") &&
                p.hasStatus("回收成功啦") && listOf("合计收款", "订单编号", "收款方式").all { p.hasLabel(it) } }) { p ->
                extract(p, "JingDongAihuishouOrderDetail", BillKind.INCOME, p.fields("合计收款"), state(p, PaymentState.SUCCEEDED))
            },
            pageRule("JingDongOrderDetail", PACKAGE, 3, { p -> (listOf("正在出库", "商品出库", "完成", "订单状态 完成", "等待收货").any(p::has) || p.hasStatus("付款成功")) &&
                p.starts("数量 ×") && p.hasLabel("实付款") && p.hasLabel("合计") && p.hasLabel("订单编号") && p.has("复制") && p.hasLabel("支付时间", "下单时间") }) { p ->
                extract(p, "JingDongOrderDetail", BillKind.EXPENSE, p.fields(*actualLabels))
            },
            pageRule("JingDongOrderWeb", PACKAGE, 4, { p -> p.typed("订单详情", "android.webkit.WebView") && (p.has("已完成") || p.has("等待处理")) &&
                p.hasLabel("订单编号") && p.hasLabel("下单时间") && p.hasLabel("支付时间") && p.has("商品总额") && p.hasLabel("实付款") }) { p ->
                extract(p, "JingDongOrderWeb", BillKind.EXPENSE, p.fields(*actualLabels), state(p, if (p.has("已完成")) PaymentState.SUCCEEDED else PaymentState.PENDING))
            },
            pageRule("JingDongPaySuccess", PACKAGE, 5, { p -> p.typedStatus("支付成功", "android.widget.TextView") && p.matching(paymentLine).any {
                p.snapshot.nodes[it.nodeIndices.single()].className == "android.widget.TextView"
            } }) { p ->
                extract(p, "JingDongPaySuccess", BillKind.EXPENSE,
                    p.matching(paymentLine, 2).filter { p.snapshot.nodes[it.nodeIndices.single()].className == "android.widget.TextView" } + p.statusAmounts("支付成功"),
                    account = p.matching(paymentLine, 1))
            }
        )
    }
}
