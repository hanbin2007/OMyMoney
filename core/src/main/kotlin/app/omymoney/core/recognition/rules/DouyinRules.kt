package app.omymoney.core.recognition.rules

import app.omymoney.core.recognition.*
import app.omymoney.core.model.BillKind

object DouyinRules {
    private const val PACKAGE = "com.ss.android.ugc.aweme"
    private const val LYNX = "com.lynx.tasm.behavior.ui.text.FlattenUIText"
    private val amountLabels = arrayOf("实付金额", "实际支付", "支付金额", "转账金额")
    private val waiting = Regex("待\\s*(.+)收款")
    private val waitingShort = Regex("待\\s+(.+)")
    private val received = Regex("(.+)已收款")
    private fun amount(page: PageFields) = page.amounts(*amountLabels)
    private fun receivedTitles(page: PageFields) = page.matching(received, 1).filter { page.snapshot.nodes[it.nodeIndices.single()].className == "android.widget.LinearLayout" }

    fun create(context: RecognitionContext = RecognitionContext()): List<PageRule> {
        fun extract(page: PageFields, id: String, kind: BillKind?, state: PaymentState = page.state(),
            amounts: List<FieldCandidate> = amount(page), times: List<FieldCandidate>,
            merchant: FieldCandidate? = null): RecognitionResult {
            val account = page.fields("支付方式").map { candidate ->
                val value = candidate.value.removePrefix("抖音支付(").let { if (candidate.value.startsWith("抖音支付(") && it.endsWith(")")) it.dropLast(1) else it }
                candidate.copy(value = value)
            }
            return DraftExtractor.extract(page.snapshot, context, id, kind, state, amounts, times, merchant,
                remark = page.fields("转账附言", "商品名称", "备注").firstOrNull()?.takeUnless { it.value == "请收款" })
                .withEvidence(page.evidence(if (kind == BillKind.INCOME) "toAccountHint" else "fromAccountHint", account) +
                    page.evidence("transactionId", page.fields("交易单号")) + page.evidence("orderId", page.fields("订单号")))
        }
        return listOf(
            pageRule("DouyingTransferWaiting", PACKAGE, 0, { p -> p.has("转账详情") &&
                (p.matching(waiting).any { p.snapshot.nodes[it.nodeIndices.single()].className == "android.widget.LinearLayout" } || p.any(waitingShort)) &&
                listOf("转账时间", "转账附言", "提醒对方收款", "24小时内对方未收款，将退还给你").all(p::has) &&
                (p.any(PageFields.MONEY) || p.hasLabel(*amountLabels)) }) { p ->
                val state = p.state(PaymentState.PENDING).let { if (it in setOf(PaymentState.FAILED, PaymentState.REFUND_PENDING)) it else PaymentState.PENDING }
                extract(p, "DouyingTransferWaiting", BillKind.EXPENSE, state, times = p.fields("转账时间"),
                    merchant = (p.matching(waiting, 1) + p.matching(waitingShort, 1)).firstOrNull())
            },
            pageRule("DouyingTransferReceived", PACKAGE, 1, { p -> p.has("转账详情") && (p.has("已收款") || receivedTitles(p).isNotEmpty()) &&
                listOf("收款时间", "转账时间", "转账附言").all(p::has) && (p.any(PageFields.MONEY) || p.hasLabel(*amountLabels)) }) { p ->
                val titles = receivedTitles(p)
                val kind = titles.map { if (it.value in setOf("你", "我")) BillKind.INCOME else BillKind.EXPENSE }.distinct().singleOrNull()
                extract(p, "DouyingTransferReceived", kind, times = p.fields("收款时间"), merchant = titles.singleOrNull())
            },
            pageRule("DouyingWalletBillDetail", PACKAGE, 2, { p ->
                (p.typed("详情页", "android.webkit.WebView") || p.has("申请电子交易凭证") || p.typed("点击展开", "android.widget.Button")) &&
                    p.hasStatus("支付成功") && p.hasLabel("支付时间") && p.hasLabel("支付方式") && p.hasLabel("交易单号") &&
                    p.hasAmount(*amountLabels) }) { p ->
                extract(p, "DouyingWalletBillDetail", BillKind.EXPENSE, times = p.fields("支付时间"))
            },
            pageRule("DouyingPaySuccess", PACKAGE, 3, { p -> p.typedStatus("支付成功", LYNX) && listOf("支付方式", "支付时间", "完成").all { p.typed(it, LYNX) } &&
                (p.moneyValues() + p.statusAmounts("支付成功")).any { p.snapshot.nodes[it.nodeIndices.last()].className == LYNX } }) { p ->
                val amounts = p.amounts().filter { p.snapshot.nodes[it.nodeIndices.last()].className == LYNX }
                extract(p, "DouyingPaySuccess", BillKind.EXPENSE, amounts = amounts, times = p.fields("支付时间"))
            }
        )
    }
}
