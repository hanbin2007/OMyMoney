package app.omymoney.core.recognition

import app.omymoney.core.model.*
import app.omymoney.core.recognition.rules.AlipayRules
import app.omymoney.core.ledger.PostingCalculator
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class AutomaticPaymentTest {
    private val time = Instant.parse("2026-10-15T03:39:41Z")
    private fun recognize(vararg text: String) = RuleEngine(AlipayRules.create(RecognitionContext("book")))
        .recognize(UiSnapshot(AlipayRules.PACKAGE, 7, time, text.map { UiNode(it) }))

    @Test fun missingTimeAndFundingStillProduceANormalBillWithDistinctPayeeAndChannel() {
        val result = recognize("支付成功0.01", "zjy", "0.01", "完成")
        val draft = AutomaticPayment.prepare(result, emptyList())!!
        assertEquals(time, draft.occurredAt)
        assertEquals(TimeSource.CAPTURED, draft.timeSource)
        assertEquals("zjy", draft.payment?.counterparty)
        assertEquals(AlipayRules.PACKAGE, draft.sourcePackage)
        assertNull(draft.fromAccountId)
        assertEquals(emptyList<app.omymoney.core.model.Posting>(), PostingCalculator.calculate(draft))
    }

    @Test fun channelOnlyOrAmbiguousAccountsAreNotSilentlySelected() {
        val wallet = AssetAccount("wallet", "支付宝", kind = AccountKind.WALLET)
        val result = recognize("支付成功0.01", "zjy", "0.01", "完成")
        assertNull(AutomaticPayment.prepare(result, listOf(wallet))!!.fromAccountId)
        val bank = AssetAccount("bank", "测试银行", kind = AccountKind.DEBIT, cardLastFour = "1234")
        val withHint = recognize("支付成功0.01", "zjy", "0.01", "付款方式", "测试银行(1234)", "完成")
        assertEquals("bank", AutomaticPayment.prepare(withHint, listOf(bank, wallet))!!.fromAccountId)
        assertEquals("测试银行(1234)", AutomaticPayment.prepare(withHint, emptyList())!!.payment?.fromAccountHint)
        assertNull(AutomaticPayment.prepare(withHint, listOf(bank, bank.copy(id = "other")))!!.fromAccountId)
    }

    @Test fun missingTimeIsFallbackButAmbiguousAmountsPendingRefundAndMalformedTimeNeverAutoSave() {
        val cases = listOf(
            arrayOf("支付成功", "实付款", "0.01", "实际支付", "0.02", "完成"),
            arrayOf("支付成功0.01", "处理中", "完成"),
            arrayOf("支付成功0.01", "退款中", "完成"),
            arrayOf("支付失败", "0.01", "完成"),
            arrayOf("支付成功0.01", "支付时间", "不合法的时间", "完成"),
        )
        cases.forEach { assertNull(it.toList().toString(), AutomaticPayment.prepare(recognize(*it), emptyList())) }
    }

    @Test fun observedFeeWithoutFundingIsRetainedWithoutAnInventedBalance() {
        val result = recognize("支付成功1.00", "付款方式", "未识别银行卡", "手续费", "0.10", "完成")
        val draft = AutomaticPayment.prepare(result, emptyList())!!
        assertEquals(Money(10, CurrencySpec.CNY), draft.fee)
        assertEquals(emptyList<app.omymoney.core.model.Posting>(), PostingCalculator.calculate(draft))
    }

    @Test fun fundingNamePrefixesAreSuggestionsNotPermissionToDebitAnAsset() {
        val balance = AssetAccount("balance", "余额", kind = AccountKind.WALLET)
        val result = recognize("支付成功0.01", "zjy", "0.01", "付款方式", "余额宝", "完成")
        assertNull(AutomaticPayment.prepare(result, listOf(balance))!!.fromAccountId)
        assertEquals("balance", AutomaticPayment.prepare(result, listOf(balance.copy(aliases = listOf("余额宝"))))!!.fromAccountId)
    }

    @Test fun matchingCardTailDoesNotOverrideAContradictoryFundingName() {
        val bank = AssetAccount("bank", "甲银行", kind = AccountKind.DEBIT, cardLastFour = "1234")
        val result = recognize("支付成功0.01", "zjy", "0.01", "付款方式", "乙银行(1234)", "完成")
        assertNull(AutomaticPayment.prepare(result, listOf(bank))!!.fromAccountId)
    }
}
