package app.omymoney.core.recognition

import app.omymoney.core.model.TimeSource
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MeituanTakeoutRulesTest {
    private val context = RecognitionContext(bookId = "book", zoneId = ZoneId.of("Asia/Shanghai"))
    private val rules = RuleRegistry.enabledRules(setOf(PACKAGE), context)
    private val engine = RuleEngine(rules)
    private val assertions = RuleFixtureAssertions("meituan-takeout", PACKAGE, rules, 2)

    @Test fun independentReceiptAndOrderFixturesCoverRegisteredRules() = assertions.coverage()
    @Test fun paidPendingAndRefundedOrdersKeepTheirPaymentSemantics() = assertions.positives()
    @Test fun checkoutAdvertisementsAndFailedPaymentsCannotBecomeBills() = assertions.negatives()

    @Test fun standaloneConsentDoesNotEnableMainAppOrUnrelatedPackages() {
        assertTrue(PACKAGE in RuleRegistry.supportedPackages)
        assertEquals(setOf(PACKAGE), rules.map { it.packageName }.toSet())
        assertTrue(engine.recognize(page("支付成功 ¥25.80", "完成").copyPackage("com.sankuai.meituan")) is RecognitionResult.Unsupported)
        assertTrue(RuleRegistry.enabledRules(emptySet()).isEmpty())
        val all = RuleRegistry.enabledRules(RuleRegistry.supportedPackages, context)
        assertEquals(all.size, all.map { it.id }.distinct().size)
        RuleEngine(all)
    }

    @Test fun actualPaidAmountWinsWithoutSubtractingDiscountsAgain() {
        val result = engine.recognize(order("支付成功", "支付时间", "2026-10-18 19:30:00",
            "订单金额", "¥48.40", "优惠券", "¥20.00", "商家名称", "测试餐厅", "支付方式", "微信支付"))
        assertTrue(result is RecognitionResult.Matched)
        assertEquals(2840L, result.draft().amount?.minor)
        assertNull(result.draft().fee)
        assertEquals("测试餐厅", result.draft().payment?.counterparty)
        assertEquals(PACKAGE, result.draft().sourcePackage)
        assertTrue(result.evidence().any { it.field == "orderId" && it.rawText == "TEST-TAKEOUT-1" })
        assertTrue(result.evidence().any { it.field == "fromAccountHint" && it.rawText == "微信支付" })
    }

    @Test fun deliveredOrderAndActualPaidLabelAloneDoNotProveSettlement() {
        val result = engine.recognize(order("订单已完成"))
        assertTrue(result is RecognitionResult.NeedsReview)
        assertNull(AutomaticPayment.prepare(result, emptyList()))
    }

    @Test fun futureDebitAndRefundOverrideAnIncidentalSuccessTitle() {
        listOf("待扣款", "先用后付", "退款成功", "部分退款", "退款处理中").forEach { status ->
            val result = engine.recognize(order("支付成功", status))
            assertTrue(status, result is RecognitionResult.NeedsReview)
            assertNull(status, AutomaticPayment.prepare(result, emptyList()))
        }
    }

    @Test fun unpaidAndClosedOrdersOverrideStaleSuccessText() {
        listOf("未支付", "未付款", "订单已关闭", "支付已取消").forEach { status ->
            assertTrue(status, engine.recognize(order("支付成功", status)) is RecognitionResult.Unsupported)
        }
    }

    @Test fun paymentWordsInsideProductNamesAreNotStatusEvidence() {
        val result = engine.recognize(order("商品名称", "支付成功"))
        assertTrue(result is RecognitionResult.NeedsReview)
        assertNull(AutomaticPayment.prepare(result, emptyList()))
        assertTrue(engine.recognize(page("商品名称", "支付成功 ¥25.80", "完成")) is RecognitionResult.Unsupported)
    }

    @Test fun missingPaymentTimeUsesExplicitCapturedTimeNotOrderCreationTime() {
        val result = engine.recognize(order("已支付", "下单时间", "2026-10-17 19:00:00"))
        assertNull(result.draft().occurredAt)
        val prepared = requireNotNull(AutomaticPayment.prepare(result, emptyList()))
        assertEquals(Instant.parse("2026-10-18T11:30:00Z"), prepared.occurredAt)
        assertEquals(TimeSource.CAPTURED, prepared.timeSource)
    }

    @Test fun conflictingActualAmountsRemainForReview() {
        val result = engine.recognize(order("支付成功", "实际支付", "¥18.40"))
        assertNull(result.draft().amount)
        assertNull(AutomaticPayment.prepare(result, emptyList()))
    }

    @Test fun passwordAndTruncatedWindowsStayUnsupported() {
        assertTrue(engine.recognize(order("支付成功", "请输入支付密码")) is RecognitionResult.Unsupported)
        val original = order("支付成功")
        assertTrue(engine.recognize(UiSnapshot(PACKAGE, 1, original.capturedAt, original.nodes, truncated = true)) is RecognitionResult.Unsupported)
    }

    private fun page(vararg text: String) = UiSnapshot(PACKAGE, 1, Instant.parse("2026-10-18T11:30:00Z"), text.map { UiNode(it) })
    private fun order(vararg extra: String) = page("订单详情", "订单编号", "TEST-TAKEOUT-1", "实付款", "¥28.40", *extra)
    private fun UiSnapshot.copyPackage(name: String) = UiSnapshot(name, windowId, capturedAt, nodes)

    companion object { const val PACKAGE = "com.sankuai.meituan.takeoutnew" }
}
