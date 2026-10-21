package app.omymoney.core.recognition

import app.omymoney.core.model.BillKind
import app.omymoney.core.recognition.rules.JdRules
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class JdRulesTest {
    private val rules = JdRules.create(RecognitionContext(bookId = "book", zoneId = ZoneId.of("Asia/Shanghai")))
    private val engine = RuleEngine(rules)
    private val assertions = RuleFixtureAssertions("jd", PACKAGE, rules, 6)
    @Test fun allSixIdsHaveIndependentPairs() = assertions.coverage()
    @Test fun positivesPreserveLayoutSpecificFields() = assertions.positives()
    @Test fun negativesAreRejected() = assertions.negatives()

    @Test fun originalPriceCannotOverrideActualPaymentInBothOrderLayouts() {
        for (id in listOf("JingDongOrderDetail", "JingDongOrderWeb")) {
            val result = withExtra(id, "商品原价", "￥999.99", "共减", "￥7.70")
            assertEquals(id, 1230L, result.draft().amount?.minor)
            assertNull(result.draft().fee)
            val conflict = withExtra(id, "实际支付", "￥13.30")
            assertNull(conflict.draft().amount)
        }
    }

    @Test fun walletVersionsKeepCreationTimeUnknownAndRefundRequiresReview() {
        for (id in listOf("JingDongWalletBillDetail", "JingDongWalletBillDetailV2")) {
            val snapshot = sample(id)
            val refund = snapshot.nodes.map { node -> node.copy(text = node.text?.replace("交易成功", "已退款(￥12.30)")) }
            val result = engine.recognize(UiSnapshot(PACKAGE, 1, Instant.EPOCH, refund))
            assertNull(result.draft().occurredAt)
            assertTrue((result as RecognitionResult.NeedsReview).issues.any { it.field == "paymentState" })
        }
    }

    @Test fun aihuishouIsIncomeOnlyAfterRecyclingSuccess() {
        val result = engine.recognize(sample("JingDongAihuishouOrderDetail"))
        assertEquals(BillKind.INCOME, result.draft().kind)
        assertTrue(result.evidence().any { it.field == "toAccountHint" })
        assertNull(result.draft().toAccountId)
    }

    @Test fun incompleteWebPaymentAndDateOnlyTextAreNotClaimedAsCompleted() {
        val original = sample("JingDongOrderWeb")
        val pending = original.nodes.map { it.copy(text = it.text?.replace("已完成", "等待处理")) }
        val result = engine.recognize(UiSnapshot(PACKAGE, 1, Instant.EPOCH, pending))
        assertTrue((result as RecognitionResult.NeedsReview).issues.any { it.code == "pending" })
        val dateOnly = original.nodes.map { it.copy(text = it.text?.replace("2026-10-11 20:30:00", "2026-10-11")) }
        assertNull(engine.recognize(UiSnapshot(PACKAGE, 1, Instant.EPOCH, dateOnly)).draft().occurredAt)
    }

    @Test fun mergedWalletTextDoesNotLeakCardNumberThroughAmountEvidence() {
        val original = sample("JingDongWalletBillDetailV2")
        val nodes = original.nodes.map { it.copy(text = it.text?.replace("京东支付(1234)", "测试银行6222020123456789")) }
        val result = engine.recognize(UiSnapshot(PACKAGE, 1, Instant.EPOCH, nodes))
        assertEquals(1230L, result.draft().amount?.minor)
        assertTrue(result.evidence().any { it.field == "amount" && it.redacted })
        assertFalse(Json.encodeToString<RecognitionResult>(result).contains("6222020123456789"))
    }

    @Test fun typedPaymentResultKeepsPaidAmountNotCouponAndDoesNotCaptureWechatHandoff() {
        val result = engine.recognize(sample("JingDongPaySuccess"))
        assertEquals(1230L, result.draft().amount?.minor)
        assertNull(result.draft().fee)
        val excluded = UiSnapshot(PACKAGE, 1, Instant.EPOCH, listOf(UiNode("支付成功", className = "android.widget.TextView"),
            UiNode("微信支付¥12.30", className = "android.widget.TextView")))
        assertTrue(engine.recognize(excluded) is RecognitionResult.Unsupported)
    }

    private fun sample(id: String) = fixtures("jd").first { it.ruleId == id && it.positive }.snapshot(PACKAGE)
    private fun withExtra(id: String, vararg extra: String): RecognitionResult {
        val original = sample(id)
        return engine.recognize(UiSnapshot(PACKAGE, 1, original.capturedAt, original.nodes + extra.map { UiNode(it) }))
    }
    companion object { private const val PACKAGE = "com.jingdong.app.mall" }
}
