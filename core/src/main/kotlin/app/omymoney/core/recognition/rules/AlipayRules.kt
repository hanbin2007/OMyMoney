package app.omymoney.core.recognition.rules

import app.omymoney.core.recognition.*
import app.omymoney.core.model.BillKind

enum class AlipayCapturePass(val delayMillis: Long) { FAST(160), FALLBACK(450) }

object AlipayRules {
    const val PACKAGE = "com.eg.android.AlipayGphone"
    private val actualLabels = arrayOf("实付款", "实付金额", "实际支付", "已支付", "支出", "收入")
    private val amountLabels = arrayOf("支付金额", "转账金额", "收款金额", "提现金额", "转入金额", "转出金额")
    private val timeLabels = arrayOf("支付时间", "转账时间", "收款时间", "到账时间", "时间")
    private val money = Regex("(?:[+-]?\\s*¥\\s*[+-]?\\s*[0-9,]+(?:\\.[0-9]{1,2})?|[+-]?[0-9,]+\\.[0-9]{1,2}|[+-]?[0-9,]+(?:\\.[0-9]{1,2})?元)")
    private val packetProgress = Regex("已领取[0-9]+/[0-9]+个,共[0-9]+\\.[0-9]{2}/([0-9]+\\.[0-9]{2})元")
    private val billStates = setOf("交易成功", "支付成功", "充值成功", "代付成功", "免密支付成功", "自动扣款成功", "等待确认收货",
        "等待发货", "等待对方确认收货", "还款成功", "收款成功", "已全额退款", "领取中")

    private fun amounts(page: PageFields): List<FieldCandidate> = when {
        page.hasLabel(*actualLabels) -> page.amounts(*actualLabels)
        page.hasLabel(*amountLabels) -> page.amounts(*amountLabels)
        else -> page.amounts()
    }
    private fun hasAmount(page: PageFields) = page.hasAmount(*actualLabels, *amountLabels)
    private fun merchant(page: PageFields): FieldCandidate? {
        val labeled = page.fields("收款方全称", "商户全称", "收款方")
            .filter { it.value.isNotBlank() }.distinctBy { it.value }
        if (labeled.isNotEmpty()) return labeled.singleOrNull()
        // The success receipt puts the payee between its status and repeated amount.
        val status = page.statusCandidates("支付成功").map { it.nodeIndices.single() }.distinct().singleOrNull() ?: return null
        val name = status + 1
        val amount = status + 2
        if (amount !in page.text.indices || listOf(name, amount).any {
            page.snapshot.nodes[it].parentIndex != page.snapshot.nodes[status].parentIndex
        }) return null
        if (!PageFields.MONEY.matches(page.text[amount])) return null
        val value = page.text[name]
        if (value.isBlank() || value.length > 80 || value.any(Char::isISOControl) ||
            Regex("[0-9¥￥.,+\\- ]+").matches(value) ||
            listOf("完成", "优惠", "领取", "积分", "红包", "付款", "支付", "账单", "余额", "返回", "首页").any(value::contains)) return null
        return page.candidate(name)
    }
    private fun times(page: PageFields): List<FieldCandidate> = when {
        page.hasLabel(*timeLabels) -> page.fields(*timeLabels)
        page.hasLabel("创建时间") -> emptyList()
        else -> page.matching(PageFields.FULL_TIME)
    }
    private fun billKind(page: PageFields) = when {
        page.hasStatus("还款成功") -> BillKind.CREDIT_PAYMENT
        page.has("余额充值") || page.hasStatus("充值成功") || page.hasLabel("提现到") && page.has("处理进度") -> BillKind.TRANSFER
        page.hasStatus("收款成功") || page.hasLabel("收入") || page.allText.any { it.startsWith("+") && money.matches(it) || it.endsWith("-收益发放") } -> BillKind.INCOME
        else -> BillKind.EXPENSE
    }

    fun create(context: RecognitionContext = RecognitionContext(), fastPass: Boolean = false): List<PageRule> {
        fun rule(id: String, priority: Int, kind: (PageFields) -> BillKind?, matches: (PageFields) -> Boolean,
            amount: (PageFields) -> List<FieldCandidate> = ::amounts,
            state: (PageFields) -> PaymentState = { it.state() }): PageRule = pageRule(id, PACKAGE, priority, matches) { page ->
            val from = page.fields("付款方式", "交易方式", "付款账号", "从哪转出") +
                if (id == "AlipayChargeDetail") page.fields("对方账户") else emptyList()
            val to = page.fields("到账账户", "到账银行卡", "收款方式", "收款账号", "转入到", "提现到", "还款到", "收益发放") +
                if (id == "AlipayWithdrawDetail") page.fields("对方账户") else emptyList()
            val hints = page.evidence("fromAccountHint", from) + page.evidence("toAccountHint", to)
            DraftExtractor.extract(page.snapshot, context, id, kind(page), state(page), amount(page), times(page),
                merchant = merchant(page),
                remark = page.fields("商品说明", "转账说明", "转账备注", "服务详情", "提现说明", "红包说明", "充值说明", "交易说明", "备注").firstOrNull(),
                fees = page.fields("服务费", "手续费"))
                .withEvidence(hints + page.evidence("transactionId", page.fields("交易单号")) + page.evidence("orderId", page.fields("订单号")) +
                    page.evidence("merchantOrderId", page.fields("商家订单号")))
        }
        val transfer: (PageFields) -> BillKind = { BillKind.TRANSFER }
        val expense: (PageFields) -> BillKind = { BillKind.EXPENSE }
        val income: (PageFields) -> BillKind = { BillKind.INCOME }
        val all = listOf(
            rule("AlipayBillDetail", 0, ::billKind, { p ->
                (billStates.any { p.hasStatus(it) } || p.allText.any { it.startsWith("已退款(") }) && p.hasLabel(*timeLabels, "创建时间") && hasAmount(p) &&
                    !p.has("余额明细详情") && !(p.hasStatus("充值成功") && p.hasLabel("付款方式"))
            }),
            rule("AlipayPaySuccess", 1, expense, { p -> p.hasStatus("支付成功") && hasAmount(p) && p.has("完成") }),
            rule("AlipayTransfer", 2, expense, { p -> p.hasStatus("转账成功") && (p.hasLabel("交易方式") || p.hasLabel("付款方式")) && p.hasLabel("收款方") && hasAmount(p) }),
            rule("AlipayTransferOut", 3, transfer, { p -> p.hasStatus("转出成功") && p.hasLabel("到账账户") && p.has("完成") && hasAmount(p) }),
            rule("AlipayTransferIn", 4, transfer, { p -> p.type("android.webkit.WebView") && p.hasStatus("转入成功") && hasAmount(p) &&
                p.typed("完成", "android.widget.Button") && p.typed("返回", "android.widget.FrameLayout") && !p.has("开始计算收益") }),
            rule("AlipayCharge", 5, { p -> if (p.allText.any { it.contains("话费") || it.contains("运营商") }) BillKind.EXPENSE else BillKind.TRANSFER },
                { p -> p.hasStatus("充值成功") && p.hasLabel("付款方式") && hasAmount(p) }),
            rule("AlipayChargeDetail", 6, transfer, { p -> p.has("余额明细详情") && p.starts("余额充值") && p.hasLabel("支付时间") && p.hasLabel("对方账户") && hasAmount(p) }),
            rule("AlipayWithdraw", 7, transfer, { p -> p.hasStatus("提现成功") && p.hasLabel("提现金额") && p.hasLabel("到账银行卡") && p.has("完成") && hasAmount(p) }),
            rule("AlipayWithdrawDetail", 8, transfer, { p -> p.has("余额明细详情") && p.starts("余额提现") && p.hasLabel("支付时间") && p.hasLabel("付款方式") && p.hasLabel("对方账户") && hasAmount(p) }),
            rule("AlipaySendRedPacket", 9, expense, { p -> p.has("发的红包") && p.any(packetProgress) && p.has("24小时内未领取，红包金额将被退回") && p.has("查看红包记录") },
                amount = { it.matching(packetProgress, 1) }, state = { it.state(PaymentState.PENDING) }),
            rule("AlipayQRCodeReceiveDetail", 10, income, { p -> p.has("账单详情") && p.hasLabel("当前状态") && p.hasStatus("已收款") && p.hasLabel("收款时间") },
                amount = { if (it.hasLabel(*actualLabels)) it.amounts(*actualLabels) else it.amounts("订单金额") }),
            rule("AlipayYuLiBaoIncomeDetail", 11, income, { p -> p.has("收益") && p.hasStatus("收益到账") && p.hasLabel("收益发放") && p.starts("余额") && hasAmount(p) }),
            rule("AlipayYuLiBaoTransferOutSuccess", 12, transfer, { p -> p.typed("快赎结果页状态", "android.widget.Image") && p.hasStatus("转出成功") && p.hasLabel("收款账号") && hasAmount(p) }),
            rule("AlipayYuLiBaoTransferOutDetail", 13, transfer, { p -> p.typed("转出详情", "android.webkit.WebView") && p.starts("转出到") && p.hasStatus("成功转出") && p.hasLabel("从哪转出") && p.hasLabel("收款账号") && hasAmount(p) }),
            rule("AlipayYuLiBaoTransferInDetail", 14, transfer, { p -> p.typed("转入详情", "android.webkit.WebView") && p.has("转入") && p.hasStatus("成功转入") && p.hasLabel("转入到") && p.hasLabel("付款账号") && p.starts("余额") && hasAmount(p) }),
            rule("AlipayYuLiBaoTransferInSuccess", 15, transfer, { p -> p.hasStatus("转入成功") && p.hasLabel("付款方式") && p.has("开始计算收益") && p.hasStatus("收益到账") },
                amount = { it.amounts("转入成功") })
        )
        return if (fastPass) all.filter { it.id == "AlipayPaySuccess" } else all
    }
}

/** One instance belongs to one window event, never to a user's transaction history. */
class AlipayCaptureSession(context: RecognitionContext = RecognitionContext(),
    diagnostics: app.omymoney.core.diagnostics.DiagnosticSink = app.omymoney.core.diagnostics.DiagnosticSink.NONE) {
    private val fast = RuleEngine(AlipayRules.create(context, fastPass = true), diagnostics)
    private val fallback = RuleEngine(AlipayRules.create(context), diagnostics)
    private var windowId: Int? = null
    private var emitted = false
    private val attempted = mutableSetOf<AlipayCapturePass>()

    @Synchronized fun recognize(snapshot: UiSnapshot, pass: AlipayCapturePass): RecognitionResult? {
        if (emitted || snapshot.packageName != AlipayRules.PACKAGE || pass in attempted) return null
        if (windowId != null && windowId != snapshot.windowId) return null
        windowId = snapshot.windowId
        attempted += pass
        val result = (if (pass == AlipayCapturePass.FAST) fast else fallback).recognize(snapshot)
        if (result is RecognitionResult.Unsupported) return null
        emitted = true
        return result
    }
}
