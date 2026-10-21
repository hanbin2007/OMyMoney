package app.omymoney.core.recognition

import app.omymoney.core.model.BillKind
import app.omymoney.core.recognition.rules.DouyinRules
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DouyinRulesTest {
    private val rules = DouyinRules.create(RecognitionContext(bookId = "book", zoneId = ZoneId.of("Asia/Shanghai")))
    private val engine = RuleEngine(rules)
    private val assertions = RuleFixtureAssertions("douyin", PACKAGE, rules, 4)
    @Test fun fourExactOriginalIdsHaveFixturePairs() = assertions.coverage()
    @Test fun positivesKeepFieldsAndPaymentState() = assertions.positives()
    @Test fun negativesAreNotSuccesses() = assertions.negatives()

    @Test fun waitingAndReceivedTransfersDoNotShareStateOrGuessRecipientOwnership() {
        val pending = engine.recognize(sample("DouyingTransferWaiting"))
        assertTrue((pending as RecognitionResult.NeedsReview).issues.any { it.field == "paymentState" && it.code == "pending" })
        val received = engine.recognize(sample("DouyingTransferReceived"))
        assertTrue(received is RecognitionResult.Matched)
        assertEquals(BillKind.EXPENSE, received.draft().kind)
        assertEquals(Instant.parse("2026-10-11T12:30:00Z"), received.draft().occurredAt)
        val original = sample("DouyingTransferReceived")
        fun title(text: String) = engine.recognize(UiSnapshot(PACKAGE, 1, Instant.EPOCH, original.nodes.map {
            if (it.text == "测试人已收款") it.copy(text = text) else it
        }))
        assertEquals(BillKind.INCOME, title("你已收款").draft().kind)
        assertNull(title("已收款").draft().kind)
    }

    @Test fun zeroWidthFormattingIsRemovedForParsingButLiteralEvidenceRemains() {
        val original = sample("DouyingPaySuccess")
        val amount = "\u200B￥12.30\u200F"
        val nodes = original.nodes.map { node -> node.copy(text = when (node.text) {
            "￥12.30" -> amount
            "2026-10-11 20:30:00" -> "\uFEFF2026-10-11 20:30:00"
            else -> node.text
        }) }
        val result = engine.recognize(UiSnapshot(PACKAGE, 1, Instant.EPOCH, nodes))
        assertEquals(1230L, result.draft().amount?.minor)
        assertEquals(Instant.parse("2026-10-11T12:30:00Z"), result.draft().occurredAt)
        assertEquals(amount, result.evidence().single { it.field == "amount" }.rawText)
    }

    @Test fun missingYearReceiptTimeOrConflictingPaidAmountsAreNotGuessed() {
        val original = sample("DouyingTransferReceived")
        val nodes = original.nodes.map { it.copy(text = it.text?.replace("2026-10-11 20:30:00", "10月11日20:30")) }
        assertNull(engine.recognize(UiSnapshot(PACKAGE, 1, Instant.EPOCH, nodes)).draft().occurredAt)
        val wallet = sample("DouyingWalletBillDetail")
        val conflict = engine.recognize(UiSnapshot(PACKAGE, 1, Instant.EPOCH, wallet.nodes + listOf(UiNode("实际支付"), UiNode("￥13.30"))))
        assertNull(conflict.draft().amount)
    }

    @Test fun walletDiscountAndPaymentWrapperDoNotChangeDebitAndRefundIsReviewable() {
        val wallet = sample("DouyingWalletBillDetail")
        val result = engine.recognize(wallet)
        assertEquals(1230L, result.draft().amount?.minor)
        assertNull(result.draft().fee)
        assertTrue(result.evidence().any { it.field == "fromAccountHint" && it.rawText == "测试银行(1234)" })
        val refund = engine.recognize(UiSnapshot(PACKAGE, 1, Instant.EPOCH, wallet.nodes + UiNode("退款中")))
        assertTrue((refund as RecognitionResult.NeedsReview).issues.any { it.field == "paymentState" && it.code == "refund_pending" })
    }

    private fun sample(id: String) = fixtures("douyin").first { it.ruleId == id && it.positive }.snapshot(PACKAGE)
    companion object { private const val PACKAGE = "com.ss.android.ugc.aweme" }
}
