package app.omymoney.core.recognition

import app.omymoney.core.model.BillKind
import app.omymoney.core.model.TimeSource
import app.omymoney.core.recognition.rules.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AlipayRulesTest {
    private val context = RecognitionContext(bookId = "book", zoneId = ZoneId.of("Asia/Shanghai"))
    private val samples = fixtures("alipay")
    private val rules = AlipayRules.create(context)
    private val engine = RuleEngine(rules)

    @Test fun sixteenRulesHaveIndependentPositiveAndNegativeFixtures() {
        assertEquals(16, rules.size)
        assertEquals(16, rules.map { it.id }.toSet().size)
        assertEquals(rules.map { it.id }.toSet(), samples.map { it.ruleId }.toSet())
        rules.forEach { rule ->
            assertTrue(samples.any { it.ruleId == rule.id && it.positive })
            assertTrue(samples.any { it.ruleId == rule.id && !it.positive })
        }
    }

    @Test fun allPositiveFieldsAndPaymentStatesMatchTheirIndependentExpectations() {
        samples.filter { it.positive }.forEach { fixture ->
            val result = engine.recognize(fixture.snapshot(PACKAGE))
            assertFalse("${fixture.name}: $result", result is RecognitionResult.Unsupported)
            assertEquals(fixture.name, fixture.ruleId, result.draft().ruleId)
            assertEquals(fixture.name, fixture.kind, result.draft().kind)
            assertEquals(fixture.name, fixture.minor, result.draft().amount?.minor)
            assertEquals(fixture.name, fixture.time?.let(Instant::parse), result.draft().occurredAt)
            assertEquals(fixture.name, fixture.outcome == "matched", result is RecognitionResult.Matched)
            assertEquals(fixture.name, if (fixture.time == null) TimeSource.UNKNOWN else TimeSource.PAGE, result.draft().timeSource)
            fixture.paymentIssue?.let { code -> assertTrue(fixture.name,
                (result as RecognitionResult.NeedsReview).issues.any { it.field == "paymentState" && it.code == code }) }
            assertTrue(fixture.name, result.evidence().any { it.field == "amount" })
        }
    }

    @Test fun negativesAreNotSuccessfulTransactions() {
        samples.filterNot { it.positive }.forEach { fixture ->
            val snapshot = fixture.snapshot(PACKAGE)
            val rule = rules.single { it.id == fixture.ruleId }
            assertTrue(fixture.name, !rule.matches(snapshot) || rule.extract(snapshot) is RecognitionResult.Unsupported)
            assertFalse(fixture.name, engine.recognize(snapshot) is RecognitionResult.Matched)
        }
    }

    @Test fun fastAndFallbackWithinOneCaptureEventEmitOnlyOneCandidate() {
        assertEquals(160L, AlipayCapturePass.FAST.delayMillis)
        assertEquals(450L, AlipayCapturePass.FALLBACK.delayMillis)
        assertEquals(listOf("AlipayPaySuccess"), AlipayRules.create(context, fastPass = true).map { it.id })
        val session = AlipayCaptureSession(context)
        val snapshot = sample("AlipayPaySuccess")
        assertNotNull(session.recognize(snapshot, AlipayCapturePass.FAST))
        val later = UiSnapshot(PACKAGE, snapshot.windowId, snapshot.capturedAt.plusMillis(290), snapshot.nodes)
        assertNull(session.recognize(later, AlipayCapturePass.FALLBACK))
        assertNotNull(AlipayCaptureSession(context).recognize(later, AlipayCapturePass.FAST))
    }

    @Test fun unsupportedFastPassDoesNotSuppressFallbackOrAllowCrossWindowCapture() {
        val snapshot = sample("AlipayCharge")
        val session = AlipayCaptureSession(context)
        assertNull(session.recognize(snapshot, AlipayCapturePass.FAST))
        assertEquals("AlipayCharge", session.recognize(snapshot, AlipayCapturePass.FALLBACK)?.draft()?.ruleId)
        val otherSession = AlipayCaptureSession(context)
        assertNull(otherSession.recognize(snapshot, AlipayCapturePass.FAST))
        assertNull(otherSession.recognize(UiSnapshot(PACKAGE, 99, snapshot.capturedAt, snapshot.nodes), AlipayCapturePass.FALLBACK))
    }

    @Test fun actualPaymentWinsOverOrderAmountCouponsAndCreationTime() {
        val result = recognize("交易详情", "交易成功", "实付款", "￥12.30", "订单金额", "￥20.00", "红包", "-￥7.70",
            "创建时间", "2026-10-10 10:00:00", "支付时间", "2026-10-11 20:30:00", "付款方式", "余额")
        assertEquals(1230L, result.draft().amount?.minor)
        assertNull(result.draft().fee)
        assertEquals(Instant.parse("2026-10-11T12:30:00Z"), result.draft().occurredAt)
        val creationOnly = recognize("交易详情", "交易成功", "实付款", "￥12.30", "创建时间", "2026-10-10 10:00:00")
        assertNull(creationOnly.draft().occurredAt)
    }

    @Test fun incomeAndPrincipalAreDifferentAndHintsHaveDirectionNotAssignedIds() {
        assertEquals(BillKind.INCOME, engine.recognize(sample("AlipayYuLiBaoIncomeDetail")).draft().kind)
        listOf("AlipayCharge", "AlipayChargeDetail", "AlipayWithdraw", "AlipayWithdrawDetail", "AlipayTransferIn", "AlipayTransferOut",
            "AlipayYuLiBaoTransferInDetail", "AlipayYuLiBaoTransferOutDetail", "AlipayYuLiBaoTransferInSuccess", "AlipayYuLiBaoTransferOutSuccess").forEach { id ->
            val result = engine.recognize(sample(id))
            assertEquals(id, BillKind.TRANSFER, result.draft().kind)
            assertNull(result.draft().fromAccountId)
            assertNull(result.draft().toAccountId)
        }
        val result = engine.recognize(sample("AlipayYuLiBaoTransferInDetail"))
        assertTrue(result.evidence().any { it.field == "fromAccountHint" && it.rawText.contains("测试银行") })
        assertTrue(result.evidence().any { it.field == "toAccountHint" && it.rawText.contains("余利宝") })
    }

    @Test fun conflictingAmountsRefundPendingAndMissingTimeStayReviewable() {
        val conflict = recognize("支付成功", "完成", "实付款", "￥12.30", "实际支付", "￥14.20")
        assertNull(conflict.draft().amount)
        val refund = recognize("交易详情", "已全额退款", "￥12.30", "支付时间", "2026-10-11 20:30:00")
        assertTrue((refund as RecognitionResult.NeedsReview).issues.any { it.field == "paymentState" })
        val pending = recognize("交易详情", "领取中", "￥12.30", "创建时间", "2026-10-11 20:30:00")
        assertTrue((pending as RecognitionResult.NeedsReview).issues.any { it.code == "pending" })
        assertNull(engine.recognize(sample("AlipayPaySuccess")).draft().occurredAt)
        assertTrue(recognize("支付成功", "完成", "￥12.30", "请输入支付密码") is RecognitionResult.Unsupported)
    }

    @Test fun integerCurrencyInlineAmountAndExplicitMerchantAreSupportedWithoutGuessingBalance() {
        val result = recognize("支付成功", "完成", "实付款：￥12", "收款方全称", "测试商店", "余额", "￥999.00")
        assertEquals(1200L, result.draft().amount?.minor)
        assertEquals("测试商店", result.draft().note)
        assertEquals("实付款：￥12", result.evidence().single { it.field == "amount" }.rawText)
    }

    @Test fun successReceiptPayeeBetweenStatusAndAmountIsExtractedWithoutTreatingItAsFunding() {
        val result = recognize("支付成功！0.01", "zjy", "0.01", "完成")
        assertEquals("zjy", result.draft().note)
        assertEquals("zjy", result.evidence().single { it.field == "merchant" }.rawText)
        assertNull(result.draft().fromAccountId)
        val advertisement = recognize("支付成功！0.01", "领取优惠", "0.01", "完成")
        assertEquals("", advertisement.draft().note)
    }

    private fun sample(ruleId: String) = samples.first { it.ruleId == ruleId && it.positive }.snapshot(PACKAGE)
    private fun recognize(vararg text: String) = engine.recognize(UiSnapshot(PACKAGE, 7, Instant.EPOCH, text.map { UiNode(it) }))
    companion object { private const val PACKAGE = "com.eg.android.AlipayGphone" }
}
