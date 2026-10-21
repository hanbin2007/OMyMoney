package app.omymoney.core.recognition

import app.omymoney.core.model.BillKind
import app.omymoney.core.model.TimeSource
import app.omymoney.core.recognition.rules.WeChatRules
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WeChatRulesTest {
    private val context = RecognitionContext(bookId = "book", zoneId = ZoneId.of("Asia/Shanghai"))
    private val samples = fixtures("wechat")
    private val rules = WeChatRules.create(context)
    private val engine = RuleEngine(rules)

    @Test fun allTwelveRegisteredRulesHaveIndependentPositiveAndNegativeFixtures() {
        assertEquals(12, rules.size)
        assertEquals(12, rules.map { it.id }.toSet().size)
        assertEquals(rules.map { it.id }.toSet(), samples.map { it.ruleId }.toSet())
        rules.forEach { rule ->
            assertTrue(rule.id, samples.any { it.ruleId == rule.id && it.positive })
            assertTrue(rule.id, samples.any { it.ruleId == rule.id && !it.positive })
        }
    }

    @Test fun positiveFixturesPreserveAmountsDirectionTimeStateAndRuleIdentity() {
        samples.filter { it.positive }.forEach { fixture ->
            val snapshot = fixture.snapshot("com.tencent.mm")
            val result = engine.recognize(snapshot)
            assertFalse("${fixture.name}: $result", result is RecognitionResult.Unsupported)
            val draft = result.draft()
            assertEquals(fixture.name, fixture.ruleId, draft.ruleId)
            assertEquals(fixture.name, fixture.kind, draft.kind)
            assertEquals(fixture.name, fixture.minor, draft.amount?.minor)
            assertEquals(fixture.name, fixture.time?.let(Instant::parse), draft.occurredAt)
            assertEquals(fixture.name, if (fixture.time == null) TimeSource.UNKNOWN else TimeSource.PAGE, draft.timeSource)
            assertEquals(fixture.name, fixture.outcome == "matched", result is RecognitionResult.Matched)
            fixture.paymentIssue?.let { expected ->
                assertTrue(fixture.name, (result as RecognitionResult.NeedsReview).issues.any {
                    it.field == "paymentState" && it.code == expected
                })
            }
            assertTrue(fixture.name, result.evidence().any { it.field == "amount" && it.nodeIndices.isNotEmpty() })
        }
    }

    @Test fun negativeFixturesCannotBecomeSuccessfulTransactions() {
        samples.filterNot { it.positive }.forEach { fixture ->
            val snapshot = fixture.snapshot("com.tencent.mm")
            val rule = rules.single { it.id == fixture.ruleId }
            assertTrue(fixture.name, !rule.matches(snapshot) || rule.extract(snapshot) is RecognitionResult.Unsupported)
            assertFalse(fixture.name, engine.recognize(snapshot) is RecognitionResult.Matched)
        }
    }

    @Test fun explicitAmountBeatsDiscountAndEvidenceRetainsFullInlineText() {
        val result = recognize("支付成功", "实付金额：￥12.30", "原价", "￥20.00", "共优惠￥7.70", "支付时间", "2026-10-11 20:30:00")
        assertEquals(1230L, result.draft().amount?.minor)
        assertEquals("实付金额：￥12.30", result.evidence().single { it.field == "amount" }.rawText)
        assertEquals(Instant.parse("2026-10-11T12:30:00Z"), result.draft().occurredAt)
    }

    @Test fun pendingAndReceivedTransferRemainDifferentStates() {
        val pending = recognize("待你收款", "￥12.30", "转账时间", "2026-10-11 20:30:00")
        val received = recognize("你已收款，来自张三", "￥12.30", "收款时间", "2026-10-11 20:30:00")
        assertEquals(BillKind.INCOME, pending.draft().kind)
        assertTrue((pending as RecognitionResult.NeedsReview).issues.any { it.code == "pending" })
        assertTrue(received is RecognitionResult.Matched)
    }

    @Test fun groupReceiptDoesNotGuessDirectionAndWithdrawalApplicationDoesNotGuessArrival() {
        val group = engine.recognize(samples.first { it.ruleId == "WechatGroupRedPacketDetail" && it.positive }.snapshot("com.tencent.mm"))
        assertNull(group.draft().kind)
        assertTrue((group as RecognitionResult.NeedsReview).issues.any { it.field == "kind" })
        val withdrawal = recognize("零钱提现-到测试银行", "当前状态", "发起提现", "提现金额", "￥12.30", "申请时间",
            "2026-10-11 20:30:00", "到账时间", "预计明日", "提现银行", "测试银行(1234)")
        assertNull(withdrawal.draft().occurredAt)
        assertTrue((withdrawal as RecognitionResult.NeedsReview).issues.any { it.code == "pending" })
    }

    @Test fun missingTimeConflictRefundAndPaymentFailureNeverBecomeMatched() {
        assertNull(recognize("支付成功", "￥12.30", "完成").draft().occurredAt)
        val conflict = recognize("支付成功", "实付金额", "￥12.30", "实际支付", "￥13.00", "支付时间", "2026-10-11 20:30:00")
        assertNull(conflict.draft().amount)
        assertTrue((conflict as RecognitionResult.NeedsReview).issues.any { it.field == "amount" && it.code == "ambiguous" })
        val refund = recognize("交易详情", "当前状态", "已退款(￥12.30)", "支付金额", "￥12.30", "支付时间", "2026-10-11 20:30:00")
        assertTrue((refund as RecognitionResult.NeedsReview).issues.any { it.field == "paymentState" })
        assertTrue(recognize("支付成功", "待付款", "￥12.30") is RecognitionResult.Unsupported)
    }

    @Test fun passwordHomepageWrongPackageAndDetachedValueAreNotCaptured() {
        assertTrue(recognize("输入支付密码", "支付成功", "￥12.30") is RecognitionResult.Unsupported)
        assertTrue(recognize("微信", "通讯录", "发现", "我", "支付成功", "￥12.30") is RecognitionResult.Unsupported)
        val fixture = samples.first { it.ruleId == "WeChatPaySuccess" && it.positive }
        assertTrue(engine.recognize(fixture.snapshot("other.package")) is RecognitionResult.Unsupported)
        val detached = UiSnapshot("com.tencent.mm", 1, Instant.EPOCH, listOf(UiNode("支付成功"), UiNode("实付金额"),
            UiNode("￥12.30", parentIndex = 0)))
        assertNull(engine.recognize(detached).draft().amount)
    }

    @Test fun descriptionAndReorderedLabelsPreserveMeaningAndSignedCurrencyIsExact() {
        val snapshot = UiSnapshot("com.tencent.mm", 1, Instant.EPOCH, listOf(UiNode(description = "支付成功"),
            UiNode(description = "支付时间"), UiNode(description = "2026-10-11 20:30:00"),
            UiNode(description = "实付金额"), UiNode(description = "-￥12.30")))
        assertEquals(1230L, engine.recognize(snapshot).draft().amount?.minor)
        assertEquals(Instant.parse("2026-10-11T12:30:00Z"), engine.recognize(snapshot).draft().occurredAt)
    }

    @Test fun transferFeeRemarkAndAccountHintsRetainIndependentEvidence() {
        val result = recognize("转账-转给测试人", "当前状态", "已收钱", "转账金额", "￥12.30", "服务费", "￥0.20",
            "转账时间", "2026-10-11 20:30:00", "支付方式", "测试银行(1234)", "转账说明", "测试午餐", "交易单号", "TEST-20261011")
        assertEquals(1230L, result.draft().amount?.minor)
        assertEquals(20L, result.draft().fee?.minor)
        assertEquals("测试午餐", result.draft().note)
        assertTrue(result.evidence().any { it.field == "accountHint" && it.rawText == "测试银行(1234)" })
        assertTrue(result.evidence().any { it.field == "transactionId" && it.rawText == "TEST-20261011" })
        assertTrue(result.evidence().any { it.field == "fee" && it.rawText == "￥0.20" })
    }

    @Test fun explicitZeroFeeIsValidButDiscountIsNeverAFee() {
        val zero = recognize("支付成功", "实付金额", "￥12.30", "服务费", "￥0.00", "共优惠￥1.00")
        assertEquals(0L, zero.draft().fee?.minor)
        assertEquals(1230L, zero.draft().amount?.minor)
        val discount = recognize("支付成功", "实付金额", "￥12.30", "共优惠￥1.00")
        assertNull(discount.draft().fee)
    }

    @Test fun incompleteCurrencyFragmentRemainsPartialInsteadOfThrowingAwayThePage() {
        val result = recognize("支付成功", "实付金额", "￥")
        assertTrue(result is RecognitionResult.NeedsReview)
        assertNull(result.draft().amount)
    }

    private fun recognize(vararg text: String) = engine.recognize(UiSnapshot("com.tencent.mm", 1,
        Instant.parse("2026-10-11T14:00:00Z"), text.map { UiNode(it) }))
}
