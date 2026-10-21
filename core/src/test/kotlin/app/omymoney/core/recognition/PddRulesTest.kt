package app.omymoney.core.recognition

import app.omymoney.core.recognition.rules.PddRules
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PddRulesTest {
    private val rules = PddRules.create(RecognitionContext(bookId = "book", zoneId = ZoneId.of("Asia/Shanghai")))
    private val engine = RuleEngine(rules)
    private val assertions = RuleFixtureAssertions("pdd", "com.xunmeng.pinduoduo", rules, 3)
    @Test fun allThreeRuleIdsHaveIndependentPairs() = assertions.coverage()
    @Test fun positivesHaveExpectedFieldsAndStates() = assertions.positives()
    @Test fun negativesDoNotBecomeSuccessfulTransactions() = assertions.negatives()

    @Test fun futureDebitOnDeliveredOrderStillNeedsPaymentConfirmation() {
        val result = order("交易成功", "先用后付", "确认收货后自动付款￥12.30")
        assertEquals(1230L, result.draft().amount?.minor)
        assertNull(result.draft().occurredAt)
        assertTrue((result as RecognitionResult.NeedsReview).issues.any { it.field == "paymentState" && it.code == "pending" })
    }

    @Test fun actualAmountIsNotDiscountedTwiceAndConflictingActualAmountsRemainUnknown() {
        val paid = order("交易成功", "实付：优惠7.70元,12.30元", "拼单价￥20.00", "平台优惠-￥7.70", "支付时间：2026-10-11 20:30:00")
        assertEquals(1230L, paid.draft().amount?.minor)
        assertNull(paid.draft().fee)
        assertEquals(Instant.parse("2026-10-11T12:30:00Z"), paid.draft().occurredAt)
        assertEquals("实付：优惠7.70元,12.30元", paid.evidence().first { it.field == "amount" }.rawText)
        val conflict = order("交易成功", "实付12.30元", "实付13.30元")
        assertNull(conflict.draft().amount)
        assertTrue((conflict as RecognitionResult.NeedsReview).issues.any { it.code == "ambiguous" })
    }

    @Test fun listPriceAndOrderTimeAloneDoNotMakeAPaidTransaction() {
        val result = order("待发货", "拼单价￥20.00", "平台优惠-￥7.70")
        assertNull(result.draft().amount)
        assertNull(result.draft().occurredAt)
        assertTrue(result is RecognitionResult.NeedsReview)
    }

    @Test fun cancelledWalletAndPasswordPagesAreRejected() {
        val fixture = fixtures("pdd").first { it.ruleId == "PddWalletBillDetail" && it.positive }.snapshot("com.xunmeng.pinduoduo")
        fun extra(text: String) = engine.recognize(UiSnapshot(fixture.packageName, fixture.windowId, fixture.capturedAt, fixture.nodes + UiNode(text)))
        assertTrue(extra("订单已取消") is RecognitionResult.Unsupported)
        assertTrue(extra("请输入支付密码") is RecognitionResult.Unsupported)
    }

    private fun order(vararg text: String) = engine.recognize(UiSnapshot("com.xunmeng.pinduoduo", 1, Instant.EPOCH,
        (listOf("下单时间：2026-10-10 10:00:00", "商品名称：测试商品", "联系商家") + text).map { UiNode(it) }))
}
