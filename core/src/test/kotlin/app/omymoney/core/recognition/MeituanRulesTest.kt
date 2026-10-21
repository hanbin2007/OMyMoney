package app.omymoney.core.recognition

import app.omymoney.core.recognition.rules.MeituanRules
import app.omymoney.core.ledger.PostingCalculator
import app.omymoney.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MeituanRulesTest {
    private val rules = MeituanRules.create(RecognitionContext(bookId = "book", zoneId = ZoneId.of("Asia/Shanghai")))
    private val engine = RuleEngine(rules)
    private val assertions = RuleFixtureAssertions("meituan", PACKAGE, rules, 10)
    @Test fun tenIdsHaveIndependentPairs() = assertions.coverage()
    @Test fun allScenariosHaveExpectedAmountsTimesAndStates() = assertions.positives()
    @Test fun negativesCannotBecomeSuccessfulTransactions() = assertions.negatives()

    @Test fun voucherFaceValueAndUsageTimeAreNotPaymentFields() {
        val result = extra("MeiTuanVoucherDetail", "券面值", "￥100.00", "使用时间", "2026-10-11 20:30:00")
        assertEquals(1230L, result.draft().amount?.minor)
        assertNull(result.draft().occurredAt)
        assertTrue(result.evidence().any { it.field == "usageState" && it.rawText == "待到店使用" })
        assertTrue(result is RecognitionResult.NeedsReview)
    }

    @Test fun walletWinsOverIncidentalOrderAndVoucherText() {
        val result = extra("MeiTuanWalletBillDetail", "已消费", "实付", "￥12.30", "订单信息", "订单编号：", "TEST-ORDER", "下单时间：", "2026-10-10 10:00:00",
            "已使用", "使用须知", "有效日期", "使用时间", "团购详情")
        assertEquals("MeiTuanWalletBillDetail", result.draft().ruleId)
        assertEquals(1230L, result.draft().amount?.minor)
    }

    @Test fun splitCharacterLabelsMustRemainContiguousAndWithinOneParent() {
        val original = sample("MeiTuanVoucherSuccessDetail")
        val amount = engine.recognize(original)
        assertEquals(1230L, amount.draft().amount?.minor)
        assertTrue(amount.evidence().single { it.field == "amount" }.nodeIndices.size >= 6)
        val altered = original.nodes.mapIndexed { index, node -> if (index == 3) node.copy(parentIndex = 0) else node }
        assertTrue(engine.recognize(UiSnapshot(PACKAGE, 1, Instant.EPOCH, altered)) is RecognitionResult.Unsupported)
    }

    @Test fun deliveryFulfillmentDoesNotProveAFutureDebitAndActualPaymentBeatsTotal() {
        val paid = extra("MeiTuanDeliveryDetail", "合计", "￥99.00", "已优惠7.70元")
        assertEquals(1230L, paid.draft().amount?.minor)
        assertNull(paid.draft().fee)
        val pending = extra("MeiTuanDeliveryDetail", "先用后付", "待扣款")
        assertTrue((pending as RecognitionResult.NeedsReview).issues.any { it.field == "paymentState" && it.code == "pending" })
    }

    @Test fun capturedDeliveryTotalPrefillsAmountWithoutInventingPaymentOrTime() {
        val result = engine.recognize(capturedDelivery()) as RecognitionResult.NeedsReview
        assertEquals("MeiTuanDeliveryDetail", result.ruleId)
        assertEquals(Money(2550, CurrencySpec.CNY), result.partialDraft.amount)
        assertNull(result.partialDraft.occurredAt)
        assertTrue(result.issues.any { it.field == "paymentState" && it.code == "unknown" })
        assertTrue(result.issues.any { it.field == "occurredAt" && it.code == "missing" })
        assertTrue(result.issues.any { it.field == "amount" && it.code == "order_total_unconfirmed" })
        assertNull(AutomaticPayment.prepare(result, emptyList()))
        assertTrue(result.evidence.any { it.field == "amount" && it.nodeIndices == listOf(12, 13, 14) })
        val confirmed = result.partialDraft.copy(occurredAt = Instant.parse("2026-10-21T09:10:00Z"), timeSource = TimeSource.USER)
        assertTrue(PostingCalculator.calculate(confirmed).isEmpty())
    }

    @Test fun orderTotalIsNeverAutomaticallyPostedEvenWithSuccessfulPaymentText() {
        val result = engine.recognize(capturedDelivery(UiNode("支付成功")))
        assertEquals(Money(2550, CurrencySpec.CNY), result.draft().amount)
        assertNull(AutomaticPayment.prepare(result, emptyList()))
        assertTrue((result as RecognitionResult.NeedsReview).issues.any { it.code == "order_total_unconfirmed" })
    }

    @Test fun deliveryTotalCannotOverrideInvalidPaidAmountOrResolveConflictingTotals() {
        val invalidPaid = engine.recognize(capturedDelivery(UiNode("实付"), UiNode("金额加载中")))
        assertNull(invalidPaid.draft().amount)
        val conflicting = engine.recognize(capturedDelivery(UiNode("合计"), UiNode("¥99.00")))
        assertNull(conflicting.draft().amount)
        assertNull(AutomaticPayment.prepare(conflicting, emptyList()))
    }

    @Test fun deliveryTotalDoesNotMakeUnpaidOrRefundedOrdersSuccessful() {
        assertTrue(engine.recognize(capturedDelivery(UiNode("待付款"))) is RecognitionResult.Unsupported)
        for (status in listOf("先用后付", "退款中")) {
            val result = engine.recognize(capturedDelivery(UiNode(status))) as RecognitionResult.NeedsReview
            assertEquals(Money(2550, CurrencySpec.CNY), result.partialDraft.amount)
            assertNull(AutomaticPayment.prepare(result, emptyList()))
            assertTrue(result.issues.any { it.field == "paymentState" && it.code in setOf("pending", "refund_pending") })
        }
    }

    @Test fun walletAutoDebitSuccessIsNotConfusedWithFuturePayLaterOrRefund() {
        val original = sample("MeiTuanWalletBillDetail")
        val successNodes = original.nodes.map { it.copy(text = it.text?.replace("支付成功", "先用后付自动扣款成功")) }
        val success = engine.recognize(UiSnapshot(PACKAGE, 1, Instant.EPOCH, successNodes))
        assertTrue(success is RecognitionResult.Matched)
        val refunded = extra("MeiTuanWalletBillDetail", "已退款12.30元")
        assertTrue((refunded as RecognitionResult.NeedsReview).issues.any { it.field == "paymentState" && it.code == "refund_pending" })
    }

    @Test fun orderCreationAndMovieShowtimeNeverSubstituteForMissingPaymentTime() {
        for (id in listOf("MeiTuanOrderDetail", "MeiTuanMovieDetail", "MeiTuanDeliveryDetail")) {
            val original = sample(id)
            val nodes = original.nodes.filterNot { it.text == "支付时间" || it.text == "2026-10-11 20:30:00" }
            assertNull(id, engine.recognize(UiSnapshot(PACKAGE, 1, Instant.EPOCH, nodes)).draft().occurredAt)
        }
    }

    @Test fun standaloneTakeoutPackageAndUnregisteredErrandScenarioStayUnsupported() {
        val original = sample("MeiTuanDeliveryDetail")
        assertTrue(engine.recognize(UiSnapshot("com.sankuai.meituan.takeoutnew", 1, Instant.EPOCH, original.nodes)) is RecognitionResult.Unsupported)
        assertFalse(rules.any { it.id == "MeiTuanErrandDetail" })
        assertTrue(extra("MeiTuanWalletBillDetail", "请输入支付密码") is RecognitionResult.Unsupported)
    }

    private fun sample(id: String) = fixtures("meituan").first { it.ruleId == id && it.positive }.snapshot(PACKAGE)
    private fun capturedDelivery(vararg extra: UiNode): UiSnapshot {
        val json = javaClass.getResource("/recognition/meituan/captured-delivery-total.json")!!.readText()
        val document = Json.parseToJsonElement(json).jsonObject
        val nodes = document.getValue("nodes").jsonArray.map { value ->
            val node = value.jsonObject
            fun text(key: String) = node[key]?.jsonPrimitive?.contentOrNull
            UiNode(text("text"), text("description"), text("viewId"), text("className"), node["parentIndex"]?.jsonPrimitive?.intOrNull)
        }
        return UiSnapshot(PACKAGE, 1, Instant.parse(document.getValue("capturedAt").jsonPrimitive.content), nodes + extra)
    }
    private fun extra(id: String, vararg text: String): RecognitionResult {
        val original = sample(id)
        return engine.recognize(UiSnapshot(PACKAGE, 1, original.capturedAt, original.nodes + text.map { UiNode(it) }))
    }
    companion object { private const val PACKAGE = "com.sankuai.meituan" }
}
