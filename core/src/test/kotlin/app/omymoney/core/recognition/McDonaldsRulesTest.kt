package app.omymoney.core.recognition

import app.omymoney.core.model.*
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class McDonaldsRulesTest {
    private val context = RecognitionContext("book", zoneId = ZoneId.of("Asia/Shanghai"))
    private val engine = RuleEngine(RuleRegistry.enabledRules(setOf(PACKAGE), context))
    private val time = Instant.parse("2026-10-23T04:30:00Z")
    private fun page(vararg text: String) = UiSnapshot(PACKAGE, 12, time, text.map { UiNode(it) })
    private fun order(vararg extra: String) = page("订单详情", "订单号", "MCD-TEST-ORDER", *extra)

    @Test fun nativePackageIsRegisteredIndependently() {
        assertTrue(PACKAGE in RuleRegistry.supportedPackages)
        assertEquals(setOf("McDonaldsOrderDetail", "McDonaldsPaymentResult"), engineRules().map { it.id }.toSet())
        assertTrue(RuleRegistry.enabledRules(setOf("com.mcdonalds.fake"), context).isEmpty())
    }

    @Test fun explicitPaidOrderPrefillsReviewWithoutClaimingAnUnseenTemplateIsVerified() {
        val result = engine.recognize(order("实付金额", "¥28.50", "支付成功", "支付时间", "2026-10-23 12:30:00",
            "餐厅名称", "麦当劳测试餐厅", "支付方式", "微信支付"))
        assertTrue(result is RecognitionResult.NeedsReview)
        val review = result as RecognitionResult.NeedsReview
        assertEquals(Money(2850, CurrencySpec.CNY), review.partialDraft.amount)
        assertEquals(BillKind.EXPENSE, review.partialDraft.kind)
        assertEquals(time, review.partialDraft.occurredAt)
        assertEquals("麦当劳测试餐厅", review.partialDraft.payment?.counterparty)
        assertTrue(review.evidence.any { it.field == "orderId" && it.rawText == "MCD-TEST-ORDER" })
        assertTrue(review.evidence.any { it.field == "fromAccountHint" && it.rawText == "微信支付" })
        assertTrue(review.issues.any { it.code == "unverified_template" })
        assertNull(AutomaticPayment.prepare(result, emptyList()))
    }

    @Test fun fulfillmentAndTotalDoNotInventPaymentStateOrTime() {
        val result = engine.recognize(order("订单已完成", "合计", "¥25.50", "下单时间", "2026-10-23 12:20:00",
            "预计取餐时间", "2026-10-23 12:30:00"))
        assertTrue(result is RecognitionResult.NeedsReview)
        val review = result as RecognitionResult.NeedsReview
        assertEquals(Money(2550, CurrencySpec.CNY), review.partialDraft.amount)
        assertNull(review.partialDraft.occurredAt)
        assertTrue(review.issues.any { it.field == "paymentState" && it.code == "unknown" })
        assertTrue(review.issues.any { it.code == "order_total_unconfirmed" })
        assertNull(AutomaticPayment.prepare(result, emptyList()))
    }

    @Test fun paidAmountBeatsTotalsButMalformedPaidAmountCannotFallBack() {
        val paid = engine.recognize(order("实付", "¥19.90", "合计", "¥25.50", "优惠", "¥5.60", "支付成功"))
        assertTrue(paid is RecognitionResult.NeedsReview)
        assertEquals(Money(1990, CurrencySpec.CNY), (paid as RecognitionResult.NeedsReview).partialDraft.amount)
        val invalid = engine.recognize(order("实付", "加载中", "合计", "¥25.50", "支付成功")) as RecognitionResult.NeedsReview
        assertNull(invalid.partialDraft.amount)
    }

    @Test fun paymentResultRequiresExplicitAmountAndReceiptContext() {
        val result = engine.recognize(page("支付成功", "实付金额", "¥9.90", "查看订单"))
        assertTrue(result is RecognitionResult.NeedsReview)
        assertEquals(Money(990, CurrencySpec.CNY), (result as RecognitionResult.NeedsReview).partialDraft.amount)
        assertNull(result.partialDraft.occurredAt)
        assertNull(AutomaticPayment.prepare(result, emptyList()))
        assertTrue(engine.recognize(page("支付成功", "限时优惠", "¥9.90")) is RecognitionResult.Unsupported)
    }

    @Test fun officialNativeInlinePaidLabelPreservesTheDisplayedAmount() {
        val result = engine.recognize(order("实付\u00a0¥\u00a028.50", "支付成功"))
        assertTrue(result is RecognitionResult.NeedsReview)
        assertEquals(Money(2850, CurrencySpec.CNY), (result as RecognitionResult.NeedsReview).partialDraft.amount)
    }

    @Test fun officialEnglishResourceLabelsWorkIndependentlyOfOurInterfaceLanguage() {
        val result = engine.recognize(page("Order Details", "Order ID", "MCD-EN-TEST",
            "Actually paid ¥ 28.50", "Pay time", "2026-10-23 12:30:00", "Payment Method", "WeChat Pay", "Payment Success"))
        assertTrue(result is RecognitionResult.NeedsReview)
        val review = result as RecognitionResult.NeedsReview
        assertEquals(Money(2850, CurrencySpec.CNY), review.partialDraft.amount)
        assertEquals(time, review.partialDraft.occurredAt)
        assertNull(AutomaticPayment.prepare(review, emptyList()))
        assertTrue(engine.recognize(page("Order Details", "Order ID", "MCD-EN-TEST", "Actually paid ¥ 28.50", "Payment Failed")) is RecognitionResult.Unsupported)
    }

    @Test fun unpaidCancelledRefundedAndCheckoutPagesNeverBecomeExpenseDrafts() {
        listOf("待付款", "待支付", "未支付", "支付失败", "订单已取消", "交易关闭", "退款中", "退款成功", "已退款").forEach { status ->
            assertTrue(status, engine.recognize(order(status, "实付金额", "¥25.50")) is RecognitionResult.Unsupported)
        }
        assertTrue(engine.recognize(page("确认订单", "合计", "¥25.50", "立即支付")) is RecognitionResult.Unsupported)
        assertTrue(engine.recognize(page("订单详情", "取餐号", "A123", "合计", "¥25.50")) is RecognitionResult.Unsupported)
    }

    @Test fun multipleTotalsCurrenciesAndPasswordPagesStayUnresolved() {
        val ambiguous = engine.recognize(order("合计", "¥25.50", "合计", "¥28.50"))
        assertTrue(ambiguous is RecognitionResult.NeedsReview)
        assertNull((ambiguous as RecognitionResult.NeedsReview).partialDraft.amount)
        val foreign = engine.recognize(order("实付金额", "HK$25.50", "支付成功")) as RecognitionResult.NeedsReview
        assertNull(foreign.partialDraft.amount)
        assertTrue(engine.recognize(order("实付金额", "¥25.50", "支付密码")) is RecognitionResult.Unsupported)
    }

    private fun engineRules() = RuleRegistry.enabledRules(setOf(PACKAGE), context)
    companion object { const val PACKAGE = "com.mcdonalds.gma.cn" }
}
