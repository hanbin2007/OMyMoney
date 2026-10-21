package app.omymoney.core.recognition.rules

import app.omymoney.core.model.BillKind
import app.omymoney.core.recognition.*

object WeChatRules {
    private val packetTitle = Regex(".+的红包")
    private val sentPacket = Regex("红包金额([0-9]+\\.[0-9]{1,2})元,等待对方领取")
    private val sentPacketCount = Regex("[0-9]+个红包共([0-9]+\\.[0-9]{1,2})元")
    private val progress = Regex("(?:已?领取[0-9]+/[0-9]+个(?:,共[0-9.]+/[0-9.]+元)?|.*被抢光.*)")
    private val waiting = Regex("(?:待.+收款|.+已收款|你已收款,.*)")
    private val amountLabels = arrayOf("实付金额", "实际支付", "支付金额", "转账金额", "收款金额", "提现金额", "支出", "收入")
    private val timeLabels = arrayOf("支付时间", "转账时间", "收款时间", "到账时间", "时间")

    fun create(context: RecognitionContext = RecognitionContext()): List<PageRule> {
        fun rule(id: String, priority: Int, matches: (PageFields) -> Boolean,
            extract: (PageFields, String) -> RecognitionResult): PageRule = object : PageRule {
            override val id = id
            override val packageName = "com.tencent.mm"
            override val priority = priority
            override fun matches(snapshot: UiSnapshot): Boolean {
                val page = PageFields(snapshot)
                return snapshot.packageName == packageName && !page.allText.any { it.contains("支付密码") } &&
                    !listOf("微信", "通讯录", "发现", "我").all(page::has) && matches(page)
            }
            override fun extract(snapshot: UiSnapshot) = extract(PageFields(snapshot), id)
        }
        fun extract(page: PageFields, id: String, kind: BillKind?, state: PaymentState = page.state(),
            amounts: List<FieldCandidate> = page.amounts(*amountLabels),
            times: List<FieldCandidate> = page.times(*timeLabels)): RecognitionResult {
            val merchant = page.fields("商户全称", "付款商家").firstOrNull()
            val account = page.fields("支付方式", "收款方式", "到账银行卡", "提现银行")
            return DraftExtractor.extract(page.snapshot, context, id, kind, state, amounts, times, merchant,
                remark = page.fields("付款方留言", "转账说明", "备注", "商品", "收款方备注", "付款备注").firstOrNull(),
                fees = page.fields("服务费"))
                .withEvidence(page.evidence("accountHint", account) + page.evidence("transactionId", page.fields("交易单号")) +
                    page.evidence("merchantOrderId", page.fields("商户单号")) +
                    page.evidence("paymentState", page.text.indices.filter { index ->
                        PageFields(UiSnapshot(page.snapshot.packageName, 0, page.snapshot.capturedAt, listOf(page.snapshot.nodes[index])))
                            .state() != PaymentState.UNKNOWN
                    }.map { page.candidate(it) }))
        }
        return listOf(
            rule("WechatPersonalRedPacketSend", 0, { it.any(packetTitle) && (it.any(sentPacket) || it.any(sentPacketCount)) }) { p, id ->
                extract(p, id, BillKind.EXPENSE, p.state(PaymentState.PENDING), p.matching(sentPacket, 1) + p.matching(sentPacketCount, 1))
            },
            rule("WechatGroupRedPacketDetail", 1, { it.any(packetTitle) && it.has("元") && it.any(progress) && it.icon("返回") && it.icon("更多") }) { p, id ->
                val kind = when { p.hasStatus("本人已领取") || p.starts("已存入零钱") -> BillKind.INCOME; p.has("我发出") -> BillKind.EXPENSE; else -> null }
                extract(p, id, kind, amounts = p.before("元", PageFields.DECIMAL))
            },
            rule("WechatPersonalRedPacketReceive", 2, { it.any(packetTitle) && it.any(PageFields.DECIMAL) && it.any(Regex("[0-9]+\\.[0-9]{1,2}元")) }) { p, id ->
                extract(p, id, BillKind.INCOME, amounts = p.matching(Regex("[0-9]+\\.[0-9]{1,2}元")))
            },
            rule("WechatPersonalRedPacketReceive2", 3, { it.any(packetTitle) && it.any(PageFields.DECIMAL) && it.has("元") && it.icon("返回") && it.icon("更多") }) { p, id ->
                extract(p, id, BillKind.INCOME, amounts = p.before("元", PageFields.DECIMAL))
            },
            rule("WeChatTransferDetail", 4, { it.starts("转账-转给") && it.hasLabel("当前状态") && it.hasLabel("转账时间") && it.hasLabel("支付方式") }) { p, id ->
                extract(p, id, BillKind.EXPENSE, times = p.fields("转账时间"))
            },
            rule("WeChatTransferInDetail", 5, { it.starts("商家转账-来自") && it.hasLabel("付款商家") && it.hasLabel("收款方式") && it.hasLabel("转账时间") }) { p, id ->
                extract(p, id, BillKind.INCOME, times = p.fields("转账时间"))
            },
            rule("WeChatBillDetail", 6, { it.hasLabel("当前状态") && it.hasLabel(*timeLabels) &&
                !it.starts("零钱充值-") && !it.starts("零钱提现-") && it.state() != PaymentState.UNKNOWN }) { p, id ->
                val incoming = listOf("微信红包-来自", "转账-来自", "经营收款-来自", "二维码收款-").any(p::starts) || p.hasLabel("收入") || p.allText.any { it.startsWith("+") && PageFields.MONEY.matches(it) }
                extract(p, id, if (incoming) BillKind.INCOME else BillKind.EXPENSE)
            },
            rule("WeChatTransferDetailWaiting", 7, { it.any(waiting) && it.any(PageFields.MONEY) && (it.hasLabel(*timeLabels) || it.any(PageFields.FULL_TIME)) }) { p, id ->
                val incoming = p.has("待你收款") || p.starts("你已收款，")
                extract(p, id, if (incoming) BillKind.INCOME else BillKind.EXPENSE)
            },
            rule("WechatWithdrawSuccess", 8, { it.has("零钱提现") && it.has("发起提现申请") && it.hasLabel("提现金额") && it.hasLabel("到账银行卡") }) { p, id ->
                extract(p, id, BillKind.TRANSFER, p.state(PaymentState.PENDING), p.fields("提现金额"), p.fields("到账时间"))
            },
            rule("WechatWithdrawDetail", 9, { it.starts("零钱提现-") && it.hasLabel("当前状态") && it.has("发起提现") && it.hasLabel("提现金额") && it.hasLabel("申请时间") && it.hasLabel("到账时间") }) { p, id ->
                extract(p, id, BillKind.TRANSFER, p.state(PaymentState.PENDING), p.fields("提现金额"), p.fields("到账时间"))
            },
            rule("WechatChargeDetail", 10, { it.starts("零钱充值-") && it.hasLabel("当前状态") && it.hasStatus("充值完成") && it.hasLabel("充值时间") && it.hasLabel("支付方式") }) { p, id ->
                extract(p, id, BillKind.TRANSFER, amounts = p.amounts(*amountLabels), times = p.fields("充值时间"))
            },
            rule("WeChatPaySuccess", 12, { it.hasStatus("支付成功", "充值成功") &&
                it.hasAmount(*amountLabels) && !(it.has("首页") && it.has("点单")) && !it.has("去下单") }) { p, id ->
                extract(p, id, if (p.hasStatus("充值成功")) BillKind.TRANSFER else BillKind.EXPENSE)
            }
        )
    }
}
