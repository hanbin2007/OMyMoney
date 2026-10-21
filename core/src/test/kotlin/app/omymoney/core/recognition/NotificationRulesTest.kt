package app.omymoney.core.recognition

import app.omymoney.core.diagnostics.DiagnosticSink
import app.omymoney.core.model.*
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NotificationRulesTest {
    private val time = Instant.parse("2026-10-18T10:00:00Z")
    private val context = RecognitionContext(UUID.randomUUID().toString())
    private fun sample(pkg: String = "com.tencent.mm", text: String = "支付成功，商户：测试店\n实付 CNY 12.30", key: String = "private-key") =
        TransactionNotification(pkg, key, time, mapOf("title" to "支付通知", "text" to text))

    @Test fun allCurrentSourcesUseReviewableNotificationDraftsWithoutAutomaticPosting() {
        RuleRegistry.supportedPackages.forEach { pkg ->
            val result = NotificationRules.recognize(sample(pkg), context) as RecognitionResult.NeedsReview
            assertEquals(BillSource.NOTIFICATION, result.partialDraft.source)
            assertEquals(pkg, result.partialDraft.sourcePackage)
            assertEquals(Money(1230, CurrencySpec.CNY), result.partialDraft.amount)
            assertEquals(BillKind.EXPENSE, result.partialDraft.kind)
            assertEquals("测试店", result.partialDraft.payment?.counterparty)
            assertNull(AutomaticPayment.prepare(result, emptyList()))
        }
    }

    @Test fun identifiersAreStableForReplayButNotForTwoEqualPayments() {
        fun id(value: TransactionNotification) = (NotificationRules.recognize(value, context) as RecognitionResult.NeedsReview).partialDraft.submissionId
        val first = sample()
        assertEquals(id(first), id(first.copy(fields = first.fields + ("bigText" to first.fields.getValue("text")))))
        assertNotEquals(id(first), id(first.copy(postedAt = time.plusSeconds(1))))
        assertNotEquals(id(first), id(first.copy(key = "other")))
    }

    @Test fun advertisementsChatSecretsUnsupportedSourcesAndOversizeAreNotCollected() {
        listOf(sample().copy(fields = mapOf("title" to "微信", "text" to "今天吃什么")), sample(text = "支付优惠，满 20 元减 5 元"),
            sample(text = "付款验证码 123456，金额 CNY 12.30"), sample(pkg = "com.example.other"),
            sample(text = "支付成功 CNY 12.30" + "a".repeat(17000))).forEach { value ->
            assertFalse(NotificationRules.isCandidate(value))
            assertTrue(NotificationRules.recognize(value, context) is RecognitionResult.Unsupported)
        }
    }

    @Test fun uploadedChatLinksAlipayPromotionsAndSecurityNoticesAreNotTransactions() {
        val noise = listOf(
            sample().copy(fields = mapOf("title" to "测试联系人", "text" to "不消费也能在店里坐着")),
            sample().copy(fields = mapOf("title" to "测试联系人", "text" to "[链接] 门店明天起开始施行消费入座")),
            sample().copy(fields = mapOf("title" to "测试联系人", "text" to "我刚支付成功 CNY 12.30")),
            sample("com.eg.android.AlipayGphone").copy(fields = mapOf("title" to "支付宝卡包", "text" to "你有商城3元红包今晚失效")),
            sample("com.eg.android.AlipayGphone").copy(fields = mapOf("title" to "安全中心提醒", "text" to "当前操作可能存在风险，请前往支付宝APP安全中心解除账户限制。")),
        )
        noise.forEach {
            assertFalse(it.fields.toString(), NotificationRules.isCandidate(it))
            assertTrue(NotificationRules.recognize(it, context) is RecognitionResult.Unsupported)
        }
    }

    @Test fun unrecognizedTransactionLanguageIsOnlyADiagnosticSampleNotAReviewDraft() {
        val unknown = sample(text = "交易提醒：金额稍后显示")
        assertTrue(NotificationRules.isCandidate(unknown))
        assertTrue(NotificationRules.recognize(unknown, context) is RecognitionResult.Unsupported)
    }

    @Test fun uploadedPaidAndAutoDebitTemplatesStillProduceExpenseDrafts() {
        val payments = listOf(
            sample().copy(fields = mapOf("title" to "微信支付", "text" to "已支付¥14.30")) to 1430L,
            sample("com.eg.android.AlipayGphone").copy(fields = mapOf("title" to "交易提醒", "text" to "你有一笔12.30元的支出，点此查看详情。")) to 1230L,
            sample("com.eg.android.AlipayGphone").copy(fields = mapOf("title" to "交易提醒", "text" to "你在测试商户有一笔0.01元的免密/自动扣款支付，点击领取2个支付宝积分。")) to 1L,
        )
        payments.forEach { (sample, minor) ->
            val result = NotificationRules.recognize(sample, context) as RecognitionResult.NeedsReview
            assertEquals(BillKind.EXPENSE, result.partialDraft.kind)
            assertEquals(Money(minor, CurrencySpec.CNY), result.partialDraft.amount)
            assertNull(result.partialDraft.occurredAt)
            assertNull(AutomaticPayment.prepare(result, emptyList()))
        }
    }

    @Test fun ambiguousAmountsCurrenciesAndPendingStatesNeverBecomeCompletedTransactions() {
        val ambiguous = NotificationRules.recognize(sample(text = "支付成功，实付 CNY 12.30，账户余额 CNY 88.00"), context) as RecognitionResult.NeedsReview
        assertNull(ambiguous.partialDraft.amount)
        val currency = NotificationRules.recognize(sample(text = "支付成功 $12.30"), context) as RecognitionResult.NeedsReview
        assertNull(currency.partialDraft.amount)
        listOf("付款失败 CNY 12.30", "待支付 CNY 12.30", "退款处理中 CNY 12.30").forEach {
            assertTrue(NotificationRules.recognize(sample(text = it), context) is RecognitionResult.Unsupported)
        }
        val income = NotificationRules.recognize(sample(text = "收款成功 HKD 25.00"), context) as RecognitionResult.NeedsReview
        assertEquals(BillKind.INCOME, income.partialDraft.kind)
        assertEquals(Money(2500, CurrencySpec("HKD", 2)), income.partialDraft.amount)
    }

    @Test fun malformedGroupedNumbersDoNotBecomeSmallerAmounts() {
        listOf("支付成功 CNY 1,23.45", "支付成功 1,23.45元", "支付成功 CNY 12.3456789").forEach {
            val result = NotificationRules.recognize(sample(text = it), context) as RecognitionResult.NeedsReview
            assertNull(it, result.partialDraft.amount)
        }
    }

    @Test fun completedPaymentWithADiscountIsStillCollectedAsATransactionSample() {
        assertTrue(NotificationRules.isCandidate(sample(text = "支付成功，实付 CNY 12.30，优惠 CNY 1.00")))
    }

    @Test fun diagnosticSamplesIncludeUnrecognizedPaymentTemplatesButExcludeSecretsAndUnrelatedApps() {
        val records = mutableListOf<Pair<String, JsonObject>>()
        val sink = DiagnosticSink { kind, fields -> records += kind to fields }
        NotificationRules.recordSample(sample(text = "交易提醒：卡号 6222021234567890，金额稍后显示"), RecognitionResult.Unsupported("no_rule"), sink)
        assertEquals(1, records.size)
        assertEquals("notification_sample", records.single().first)
        val json = records.single().second.toString()
        assertTrue(json.contains("金额稍后显示"))
        assertTrue(json.contains("****7890"))
        assertFalse(json.contains("6222021234567890"))
        assertFalse(json.contains("private-key"))
        NotificationRules.recordSample(sample(text = "付款验证码 123456"), RecognitionResult.Unsupported("secret"), sink)
        NotificationRules.recordSample(sample(pkg = "com.other.app"), RecognitionResult.Unsupported("package"), sink)
        assertEquals(1, records.size)
        NotificationRules.recordSample(sample(), RecognitionResult.Unsupported("test"), DiagnosticSink.NONE)
        assertEquals(1, records.size)
    }
}
