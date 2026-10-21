package app.omymoney.core.recognition

import app.omymoney.core.model.BillKind
import app.omymoney.core.recognition.rules.UnionPayRules
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class UnionPayRulesTest {
    private val rules = UnionPayRules.create(RecognitionContext(bookId = "book", zoneId = ZoneId.of("Asia/Shanghai")))
    private val engine = RuleEngine(rules)
    private val assertions = RuleFixtureAssertions("unionpay", PACKAGE, rules, 5)
    @Test fun allFiveIdsHaveIndependentPairs() = assertions.coverage()
    @Test fun positivesPreserveFields() = assertions.positives()
    @Test fun negativesAreRejected() = assertions.negatives()

    @Test fun fullCardNumbersAreRedactedToTailHintsAndNeverPersistAsEvidence() {
        val result = message("消费", "测试银行 6222 0201 2345 6789")
        val hint = result.evidence().single { it.field == "fromAccountHint" }
        assertTrue(hint.redacted)
        assertEquals("测试银行 ****6789", hint.rawText)
        assertFalse(Json.encodeToString<RecognitionResult>(result).contains("6222"))
        assertNull(result.draft().fromAccountId)
    }

    @Test fun incomingAndCashWithdrawalMessageTypesHaveDifferentDirections() {
        val income = message("入账", "尾号1234")
        assertEquals(BillKind.INCOME, income.draft().kind)
        assertTrue(income.evidence().any { it.field == "toAccountHint" })
        assertEquals(BillKind.TRANSFER, message("取款", "尾号1234").draft().kind)
        assertTrue(message("未知交易", "尾号1234") is RecognitionResult.Unsupported)
    }

    @Test fun mainActivityAndHomeNavigationRejectEvenEmbeddedPaymentBanners() {
        val snapshot = fixtures("unionpay").first { it.ruleId == "UnionpayPaySuccess" && it.positive }.snapshot(PACKAGE)
        assertTrue(engine.recognize(UiSnapshot(PACKAGE, 1, snapshot.capturedAt, snapshot.nodes,
            windowClassName = "com.unionpay.activity.UPActivityMain")) is RecognitionResult.Unsupported)
        assertTrue(engine.recognize(UiSnapshot(PACKAGE, 1, snapshot.capturedAt,
            snapshot.nodes + listOf(UiNode("首页"), UiNode("我的"), UiNode("收付款")))) is RecognitionResult.Unsupported)
    }

    @Test fun everyScenarioRetainsUnknownTimeWhenActualTimestampIsAbsent() {
        fixtures("unionpay").filter { it.positive }.forEach { fixture ->
            val original = fixture.snapshot(PACKAGE)
            val nodes = original.nodes.map { if (it.text?.startsWith("2026-") == true) it.copy(text = "未提供") else it }
            val result = engine.recognize(UiSnapshot(PACKAGE, 1, Instant.EPOCH, nodes))
            assertNull(fixture.name, result.draft().occurredAt)
            assertTrue(fixture.name, result is RecognitionResult.NeedsReview)
        }
    }

    @Test fun paidAmountOverridesOrderTotalAndDiscountAndOrderTimeIsNotPaymentTime() {
        val original = fixtures("unionpay").first { it.ruleId == "UnionpayBillDetail" && it.positive }.snapshot(PACKAGE)
        val result = engine.recognize(UiSnapshot(PACKAGE, 1, Instant.EPOCH, original.nodes + listOf(
            UiNode("实付金额"), UiNode("￥10.30"), UiNode("优惠信息"), UiNode("-￥2.00"))))
        assertEquals(1030L, result.draft().amount?.minor)
        assertNull(result.draft().fee)
        val noPaymentTime = original.nodes.filterNot { it.text == "支付时间" || it.text == "2026-10-11 20:30:00" }
        assertNull(engine.recognize(UiSnapshot(PACKAGE, 1, Instant.EPOCH, noPaymentTime)).draft().occurredAt)
    }

    private fun message(type: String, account: String) = engine.recognize(UiSnapshot(PACKAGE, 1, Instant.EPOCH,
        listOf("交易详情", "￥12.30", "卡号", account, "交易时间", "2026-10-11 20:30:00", "交易类别", type, "分类", "其他", "交易成功").map { UiNode(it) }))
    companion object { private const val PACKAGE = "com.unionpay" }
}
