package app.omymoney.core.recognition

import app.omymoney.core.model.*
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RecognitionBoundaryTest {
    private val apps = mapOf("wechat" to "com.tencent.mm", "alipay" to "com.eg.android.AlipayGphone",
        "pdd" to "com.xunmeng.pinduoduo", "unionpay" to "com.unionpay", "jd" to "com.jingdong.app.mall",
        "meituan" to "com.sankuai.meituan", "douyin" to "com.ss.android.ugc.aweme")
    private val context = RecognitionContext("book", zoneId = ZoneId.of("Asia/Shanghai"))

    @Test fun refundProcessingOverridesRetainedSuccessOnEverySettledFixture() {
        apps.forEach { (app, pkg) ->
            val engine = RuleEngine(RuleRegistry.enabledRules(setOf(pkg), context))
            fixtures(app).filter { it.positive && it.outcome == "matched" }.forEach { fixture ->
                val original = fixture.snapshot(pkg)
                val result = engine.recognize(UiSnapshot(pkg, original.windowId, original.capturedAt, original.nodes + UiNode("退款处理中")))
                assertTrue(fixture.ruleId, result is RecognitionResult.NeedsReview)
                assertTrue(fixture.ruleId, (result as RecognitionResult.NeedsReview).issues.any { it.field == "paymentState" && it.code == "refund_pending" })
            }
        }
    }
    @Test fun explicitYuanCannotBeRelabeledWithTheSelectedBooksCurrency() {
        listOf("pdd" to "PddOrderDetail", "meituan" to "MeiTuanPaySuccess", "meituan" to "MeiTuanBikeOrderDetail",
            "jd" to "JingDongPaySuccess").forEach { (app, ruleId) ->
            val pkg = apps.getValue(app)
            val fixture = fixtures(app).first { it.ruleId == ruleId && it.positive }
            val result = RuleEngine(RuleRegistry.enabledRules(setOf(pkg), context.copy(currency = CurrencySpec("USD", 2))))
                .recognize(fixture.snapshot(pkg))
            assertNull(ruleId, result.draft().amount)
            assertTrue(result is RecognitionResult.NeedsReview)
        }
    }
    @Test fun dualIdentifiersRetainTheirNamespacesAndDeduplicateRepeatedReceipts() {
        val pkg = apps.getValue("pdd")
        val fixture = fixtures("pdd").first { it.ruleId == "PddWalletBillDetail" && it.positive }
        val snapshot = fixture.snapshot(pkg)
        val engine = RuleEngine(RuleRegistry.enabledRules(setOf(pkg), context))
        val first = engine.recognize(snapshot)
        val second = engine.recognize(UiSnapshot(pkg, snapshot.windowId, snapshot.capturedAt.plusSeconds(10), snapshot.nodes))
        val a = DedupCandidate("a", first.draft(), first.evidence())
        val b = DedupCandidate("b", second.draft(), second.evidence())
        assertNotEquals(a.draft.submissionId, b.draft.submissionId)
        assertEquals(DedupKind.EXACT_DUPLICATE, Deduplicator.compare(b, listOf(a)).kind)
        assertEquals("TEST-PDD-1", a.evidence.single { it.field == "transactionId" }.rawText)
        assertEquals("TEST-SHOP-1", a.evidence.single { it.field == "merchantOrderId" }.rawText)
        val crossed = b.copy(evidence = listOf(FieldEvidence("transactionId", listOf(1), "TEST-SHOP-1")))
        assertNotEquals(DedupKind.EXACT_DUPLICATE, Deduplicator.compare(crossed, listOf(a)).kind)
        val combined = b.copy(evidence = b.evidence + FieldEvidence("orderId", listOf(2), "PLATFORM-OTHER"))
        assertEquals(DedupKind.EXACT_DUPLICATE, Deduplicator.compare(combined, listOf(a)).kind)
    }
}
