package app.omymoney.core.recognition

import app.omymoney.core.model.*
import app.omymoney.core.recognition.rules.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PaymentSemanticsRegressionTest {
    private val context = RecognitionContext("book", zoneId = ZoneId.of("Asia/Shanghai"))
    private val apps = listOf(
        Triple("alipay", AlipayRules.PACKAGE, "AlipayPaySuccess"),
        Triple("wechat", "com.tencent.mm", "WeChatPaySuccess"),
        Triple("unionpay", "com.unionpay", "UnionpayPaySuccess"),
        Triple("jd", "com.jingdong.app.mall", "JingDongPaySuccess"),
        Triple("douyin", "com.ss.android.ugc.aweme", "DouyingPaySuccess"),
        Triple("meituan", "com.sankuai.meituan", "MeiTuanPaySuccess"),
        Triple("pdd", "com.xunmeng.pinduoduo", "PddWalletBillDetail"),
    )

    @Test fun actualAnonymizedAlipayHierarchyProducesAReviewableOneCentExpense() {
        val fixture = Json.decodeFromString<RecognitionFixture>(javaClass.getResource(
            "/recognition/alipay/captured-merged-success.json")!!.readText())
        val result = engine(AlipayRules.PACKAGE).recognize(fixture.snapshot(AlipayRules.PACKAGE))
        assertEquals(1L, result.draft().amount?.minor)
        assertEquals(BillKind.EXPENSE, result.draft().kind)
        assertNull(result.draft().occurredAt)
        assertNull(result.draft().fromAccountId)
        assertTrue(result is RecognitionResult.NeedsReview)
        assertFalse((result as RecognitionResult.NeedsReview).issues.any { it.field == "paymentState" })
        assertTrue(result.evidence().any { it.field == "amount" && 6 in it.nodeIndices })
        assertTrue(result.evidence().any { it.field == "fromAccountHint" && it.rawText == "账户余额" && 11 in it.nodeIndices })
        val session = AlipayCaptureSession(context)
        assertNotNull(session.recognize(fixture.snapshot(AlipayRules.PACKAGE), AlipayCapturePass.FAST))
        assertNull(session.recognize(fixture.snapshot(AlipayRules.PACKAGE), AlipayCapturePass.FALLBACK))
    }

    @Test fun allSupportedAppsRecognizeMergedNormalizedAndDescriptionSuccessTitles() {
        apps.forEach { (app, pkg, rule) ->
            val original = fixtures(app).first { it.ruleId == rule && it.positive }.snapshot(pkg)
            listOf("支付成功￥12.30", "支付成功： ￥１２．３０", "支付成功\n¥12.30", "\u200B支付成功\u00a0￥12.30").forEach { title ->
                for (description in listOf(false, true)) {
                    val nodes = original.nodes.map { node -> if (node.text == "支付成功") {
                        if (description) node.copy(text = "支付结果", description = title) else node.copy(text = title)
                    } else node }
                    val result = engine(pkg).recognize(page(pkg, nodes))
                    assertEquals("$app/$title/$description", rule, result.draft().ruleId)
                    assertEquals("$app/$title/$description", 1230L, result.draft().amount?.minor)
                    if (result is RecognitionResult.NeedsReview) assertFalse("$app status", result.issues.any { it.field == "paymentState" })
                }
            }
        }
    }

    @Test fun aMergedTitleCanProvideTheOnlyAmountWithoutInventingTime() {
        val result = alipay("支付成功￥0.01", "付款方式", "账户余额", "完成")
        assertEquals(1L, result.draft().amount?.minor)
        assertNull(result.draft().occurredAt)
        assertFalse((result as RecognitionResult.NeedsReview).issues.any { it.field == "paymentState" })
    }

    @Test fun integerAndSplitCurrencyAmountsAreNotLostOrRelabeled() {
        assertEquals(1200L, alipay("支付成功", "￥12", "完成").draft().amount?.minor)
        assertEquals(1200L, alipay("支付成功", "￥", "12", "完成").draft().amount?.minor)
        val usd = RuleEngine(AlipayRules.create(context.copy(currency = CurrencySpec("USD", 2))))
            .recognize(page(AlipayRules.PACKAGE, listOf(UiNode("支付成功"), UiNode("￥"), UiNode("12.30"), UiNode("完成"))))
        assertNull(usd.draft().amount)
    }

    @Test fun statusWordsInProductAndRemarkFieldsAreNotPaymentConfirmation() {
        assertTrue(alipay("商品名称", "支付成功￥12.30", "￥12.30", "完成") is RecognitionResult.Unsupported)
        val result = alipay("支付成功￥12.30", "完成", "备注", "支付失败")
        assertEquals(1230L, result.draft().amount?.minor)
        assertFalse((result as RecognitionResult.NeedsReview).issues.any { it.field == "paymentState" })
    }

    @Test fun differentPrimaryOrUnlabeledDuplicateAmountsRemainAmbiguous() {
        for (extra in listOf(listOf("￥14.20"), listOf("实付款", "￥14.20"))) {
            val result = alipay(*(listOf("支付成功￥12.30", "完成") + extra).toTypedArray())
            assertNull(result.draft().amount)
            assertTrue((result as RecognitionResult.NeedsReview).issues.any { it.field == "amount" && it.code == "ambiguous" })
        }
        val alias = engine(AlipayRules.PACKAGE).recognize(page(AlipayRules.PACKAGE, listOf(
            UiNode("支付成功￥12.30", "支付成功￥14.20"), UiNode("完成"))))
        assertNull(alias.draft().amount)
    }

    @Test fun differentLabelAliasesAndCurrencyAliasesCannotHideAmountConflicts() {
        val conflictNodes = listOf(
            listOf(UiNode("实付款：￥12.30", "实际支付：￥14.20")),
            listOf(UiNode("￥", "￥14.20"), UiNode("12.30")),
        )
        conflictNodes.forEach { nodes ->
            val result = engine(AlipayRules.PACKAGE).recognize(page(AlipayRules.PACKAGE,
                listOf(UiNode("支付成功"), UiNode("完成"), UiNode("支付时间"), UiNode("2026-10-11 20:30:00")) + nodes))
            assertNull(result.draft().amount)
            assertTrue(result is RecognitionResult.NeedsReview)
        }
    }

    @Test fun explicitFailureAliasAndExplanatoryStatusCannotBeHiddenByNarrative() {
        val base = listOf(UiNode("支付成功"), UiNode("￥12.30"), UiNode("完成"), UiNode("支付时间"), UiNode("2026-10-11 20:30:00"))
        val failed = engine(AlipayRules.PACKAGE).recognize(page(AlipayRules.PACKAGE, base + UiNode("备注：测试", "当前状态：交易失败")))
        assertTrue(failed is RecognitionResult.Unsupported)
        for ((status, code) in listOf("当前状态：退款处理中，请耐心等待" to "refund_pending", "交易状态：处理中，请稍候" to "pending")) {
            val result = engine(AlipayRules.PACKAGE).recognize(page(AlipayRules.PACKAGE, base + UiNode(status)))
            assertTrue(result is RecognitionResult.NeedsReview)
            assertTrue((result as RecognitionResult.NeedsReview).issues.any { it.code == code })
        }
    }

    @Test fun knownBalancesOrderTotalsAndDiscountsCannotReplaceThePaidHeaderAmount() {
        val result = alipay("支付成功￥12.30", "完成", "账户余额", "￥999.00", "订单金额", "￥20.00", "优惠金额", "￥7.70")
        assertEquals(1230L, result.draft().amount?.minor)
        assertNull(result.draft().fee)
    }

    @Test fun labeledFailureRefundAndPendingOverrideSuccessAcrossAllApps() {
        val states = mapOf("交易状态：交易失败" to "failed", "当前状态：处理中" to "pending",
            "当前状态：已退款" to "refund_pending", "部分退款" to "refund_pending")
        apps.forEach { (app, pkg, rule) ->
            val original = fixtures(app).first { it.ruleId == rule && it.positive }.snapshot(pkg)
            states.forEach { (status, expected) ->
                val result = engine(pkg).recognize(page(pkg, original.nodes + UiNode(status)))
                if (expected == "failed") assertTrue("$app/$status", result is RecognitionResult.Unsupported)
                else {
                    assertTrue("$app/$status: $result", result is RecognitionResult.NeedsReview)
                    assertTrue("$app/$status", (result as RecognitionResult.NeedsReview).issues.any { it.field == "paymentState" && it.code == expected })
                }
            }
        }
    }

    @Test fun mergedIncomeRepaymentAndRechargeRetainTheirKinds() {
        assertEquals(BillKind.INCOME, alipay("收款成功￥12.30", "支付时间", "2026-10-11 20:30:00").draft().kind)
        assertEquals(BillKind.CREDIT_PAYMENT, alipay("还款成功￥12.30", "支付时间", "2026-10-11 20:30:00").draft().kind)
        val wechat = engine("com.tencent.mm").recognize(page("com.tencent.mm", listOf(UiNode("充值成功￥12.30"), UiNode("完成"))))
        assertEquals(BillKind.TRANSFER, wechat.draft().kind)
    }

    @Test fun negationProseMalformedAmountsAndPasswordsNeverBecomeSuccessPages() {
        for (title in listOf("未支付成功￥12.30", "支付成功后到账", "支付成功率99%", "支付成功￥12.30优惠2元",
                "支付成功￥1,23.00", "支付成功￥12.345")) {
            assertTrue(title, alipay(title, "￥12.30", "完成") is RecognitionResult.Unsupported)
        }
        val secretAlias = engine(AlipayRules.PACKAGE).recognize(page(AlipayRules.PACKAGE,
            listOf(UiNode("支付成功", "请输入支付密码"), UiNode("￥12.30"), UiNode("完成"))))
        assertTrue(secretAlias is RecognitionResult.Unsupported)
    }

    @Test fun mergedLabeledBillStatusAndInlineFieldGatesUseTheSameSemantics() {
        val result = engine("com.tencent.mm").recognize(page("com.tencent.mm", listOf(
            UiNode("当前状态：支付成功"), UiNode("支付金额：￥12.30"), UiNode("支付时间：2026-10-11 20:30:00"))))
        assertEquals("WeChatBillDetail", result.draft().ruleId)
        assertTrue(result is RecognitionResult.Matched)
    }

    @Test fun typedSuccessGuardsAndUnsupportedPackagesAreNotBroadened() {
        val original = fixtures("douyin").first { it.ruleId == "DouyingPaySuccess" && it.positive }.snapshot("com.ss.android.ugc.aweme")
        val wrong = original.nodes.map { if (it.text == "支付成功") it.copy(text = "支付成功￥12.30", className = "android.widget.TextView") else it }
        assertTrue(engine(original.packageName).recognize(page(original.packageName, wrong)) is RecognitionResult.Unsupported)
        assertTrue(engine(AlipayRules.PACKAGE).recognize(page("unrelated.app", listOf(UiNode("支付成功￥12.30"), UiNode("完成")))) is RecognitionResult.Unsupported)
    }

    @Test fun jdMergedSuccessWordInsideProductProseIsNotAStatus() {
        val pkg = "com.jingdong.app.mall"
        val original = fixtures("jd").first { it.ruleId == "JingDongWalletBillDetailV2" && it.positive }.snapshot(pkg)
        val changed = original.nodes.map { it.copy(text = it.text?.replace("交易成功", "交易成功纪念杯")) }
        assertTrue(engine(pkg).recognize(page(pkg, changed)) is RecognitionResult.Unsupported)
        for (negated in listOf("未交易成功", "尚未交易成功")) {
            val nodes = original.nodes.map { it.copy(text = it.text?.replace("交易成功", negated)) } +
                listOf(UiNode("实付款：￥12.30"), UiNode("支付时间：2026-10-11 20:30:00"))
            assertTrue(negated, engine(pkg).recognize(page(pkg, nodes)) is RecognitionResult.Unsupported)
        }
    }

    @Test fun labeledStatusVariantsPreserveTheFullExistingRuleCatalog() {
        val statuses = setOf("交易成功", "支付成功", "转账成功", "转出成功", "转入成功", "充值成功", "提现成功", "收款成功",
            "还款成功", "已收款", "充值完成", "收益到账", "成功转出", "成功转入", "买单成功！", "先用后付自动扣款成功", "极速支付成功", "回收成功啦")
        apps.forEach { (app, pkg, _) ->
            fixtures(app).filter { it.positive && it.nodes.any { node -> node.text in statuses || node.description in statuses } }.forEach { fixture ->
                val original = fixture.snapshot(pkg)
                val nodes = original.nodes.map { node -> node.copy(
                    text = node.text?.let { if (it in statuses) "当前状态：$it" else it },
                    description = node.description?.let { if (it in statuses) "当前状态：$it" else it }) }
                val result = engine(pkg).recognize(page(pkg, nodes))
                assertFalse("$app/${fixture.ruleId}: $result", result is RecognitionResult.Unsupported)
                assertEquals("$app/${fixture.ruleId}", fixture.ruleId, result.draft().ruleId)
                assertEquals("$app/${fixture.ruleId}", fixture.kind, result.draft().kind)
                assertEquals("$app/${fixture.ruleId}", fixture.minor, result.draft().amount?.minor)
                assertEquals("$app/${fixture.ruleId}", fixture.outcome == "matched", result is RecognitionResult.Matched)
            }
        }
    }

    @Test fun ambiguousNarrativeContainersNeverBecomeStandalonePaymentEvidence() {
        for (count in listOf(2, 70)) {
            val nodes = listOf(UiNode("商品名称"), UiNode()) +
                (0 until count).map { UiNode(if (it == count - 1) "支付成功￥12.30" else "纪念杯", parentIndex = 1) } +
                listOf(UiNode("完成"), UiNode("支付时间"), UiNode("2026-10-11 20:30:00"))
            assertTrue(engine(AlipayRules.PACKAGE).recognize(page(AlipayRules.PACKAGE, nodes)) is RecognitionResult.Unsupported)
        }
    }

    @Test fun descriptionPayLaterWarningsCannotBeHiddenByPrimaryText() {
        listOf(Triple("pdd", "com.xunmeng.pinduoduo", "PddOrderDetail"),
            Triple("meituan", "com.sankuai.meituan", "MeiTuanPaySuccess")).forEach { (app, pkg, rule) ->
            val original = fixtures(app).first { it.positive && it.ruleId == rule }.snapshot(pkg)
            val result = engine(pkg).recognize(page(pkg, original.nodes + UiNode("支付方式", "先用后付")))
            assertTrue("$app: $result", result is RecognitionResult.NeedsReview)
            assertTrue((result as RecognitionResult.NeedsReview).issues.any { it.field == "paymentState" })
        }
    }

    @Test fun descriptionAliasesPreserveRechargeAndSignedIncomeKinds() {
        val recharge = fixtures("alipay").first { it.positive && it.ruleId == "AlipayCharge" }.snapshot(AlipayRules.PACKAGE)
        val charged = engine(AlipayRules.PACKAGE).recognize(page(AlipayRules.PACKAGE,
            recharge.nodes + UiNode("充值说明", "运营商话费")))
        assertEquals(BillKind.EXPENSE, charged.draft().kind)
        val pkg = "com.tencent.mm"
        val result = engine(pkg).recognize(page(pkg, listOf(UiNode("账单详情"), UiNode("当前状态"),
            UiNode("交易成功"), UiNode("金额", "+12.30"), UiNode("支付时间"), UiNode("2026-10-11 20:30:00"))))
        assertEquals(BillKind.INCOME, result.draft().kind)
    }

    private fun engine(pkg: String) = RuleEngine(RuleRegistry.enabledRules(setOf(pkg), context))
    private fun page(pkg: String, nodes: List<UiNode>) = UiSnapshot(pkg, 7, Instant.parse("2026-10-13T14:49:44Z"), nodes)
    private fun alipay(vararg text: String) = engine(AlipayRules.PACKAGE).recognize(page(AlipayRules.PACKAGE, text.map { UiNode(it) }))
}
