package app.omymoney.core.recognition

import app.omymoney.core.model.*
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import app.omymoney.core.diagnostics.DiagnosticSink
import kotlinx.serialization.json.jsonPrimitive

class RuleEngineTest {
    private val context = RecognitionContext("10000000-0000-0000-0000-000000000001", zoneId = ZoneId.of("Asia/Shanghai"))
    private fun snapshot(vararg text: String, packageName: String = "example.payment") = UiSnapshot(packageName, 7,
        Instant.parse("2026-10-11T15:00:00Z"), text.map { UiNode(text = it) })
    private fun result(snapshot: UiSnapshot, rules: List<PageRule> = listOf(rule())) = RuleEngine(rules).recognize(snapshot)
    private fun review(result: RecognitionResult): RecognitionResult.NeedsReview {
        assertTrue("Expected a review candidate, got $result", result is RecognitionResult.NeedsReview)
        return result as RecognitionResult.NeedsReview
    }
    private fun matched(result: RecognitionResult): RecognitionResult.Matched {
        assertTrue("Expected a matched transaction, got $result", result is RecognitionResult.Matched)
        return result as RecognitionResult.Matched
    }
    private fun rule(id: String = "test.detail", priority: Int = 0) = object : PageRule {
        override val id = id
        override val priority = priority
        override val packageName = "example.payment"
        override fun matches(snapshot: UiSnapshot) = NodeSelector(text = Regex("交易详情")).indices(snapshot).isNotEmpty()
        override fun extract(snapshot: UiSnapshot) = DraftExtractor.extract(snapshot, context, id, BillKind.EXPENSE,
            if (NodeSelector(text = Regex("待付款")).indices(snapshot).isNotEmpty()) PaymentState.PENDING else PaymentState.SUCCEEDED,
            snapshot.after(NodeSelector(text = Regex("实付款"))), snapshot.after(NodeSelector(text = Regex("交易时间"))))
    }

    @Test fun repeatedRecognitionHasStableIdentityExactMoneyTimeAndEvidence() {
        val page = snapshot("交易详情", "实付款", "￥１２．３０", "交易时间", "2026年10月11日 21:05:09")
        val first = result(page)
        assertTrue(first is RecognitionResult.Matched)
        first as RecognitionResult.Matched
        assertEquals(first, result(page))
        assertEquals(1230L, first.draft.amount!!.minor)
        assertEquals(Instant.parse("2026-10-11T13:05:09Z"), first.draft.occurredAt)
        assertEquals(TimeSource.PAGE, first.draft.timeSource)
        assertEquals(page.capturedAt, first.draft.recognizedAt)
        assertEquals(context.bookId, first.draft.bookId)
        assertEquals(FieldEvidence("amount", listOf(1, 2), "￥１２．３０"), first.evidence.single { it.field == "amount" })
        assertEquals("test.detail", first.draft.ruleId)
        assertEquals(BillSource.ACCESSIBILITY, first.draft.source)
    }

    @Test fun absentAmountCannotBecomeAZeroValueSuccess() {
        val result = result(snapshot("交易详情", "实付款"))
        assertTrue(result is RecognitionResult.NeedsReview)
        result as RecognitionResult.NeedsReview
        assertNull(result.partialDraft.amount)
        assertTrue(result.issues.any { it.field == "amount" && it.code == "missing" })
    }

    @Test fun unpaidAmountRemainsAReviewCandidateNotASuccess() {
        val result = result(snapshot("交易详情", "待付款", "实付款", "12.30", "交易时间", "2026-10-11 21:05"))
        assertTrue(result is RecognitionResult.NeedsReview)
        assertTrue((result as RecognitionResult.NeedsReview).issues.any { it.field == "paymentState" && it.code == "pending" })
    }

    @Test fun conflictingAmountsAreNotResolvedByPickingTheLargest() {
        val result = review(result(snapshot("交易详情", "实付款", "12.30", "实付款", "88.00")))
        assertNull(result.partialDraft.amount)
        assertTrue(result.issues.any { it.field == "amount" && it.code == "ambiguous" })
        assertEquals(setOf("12.30", "88.00"), result.evidence.filter { it.field == "amount" }.map { it.rawText }.toSet())
    }

    @Test fun identicalRepeatedAmountEvidenceDoesNotInventAConflict() {
        val result = review(result(snapshot("交易详情", "实付款", "12.30", "实付款", "12.30")))
        assertEquals(1230L, result.partialDraft.amount!!.minor)
        assertFalse(result.issues.any { it.field == "amount" })
        assertNull(result.partialDraft.occurredAt)
        assertEquals(TimeSource.UNKNOWN, result.partialDraft.timeSource)
    }

    @Test fun unknownDatesAndOverflowAmountsStayUnset() {
        for (date in listOf("2026-02-30 12:00", "10月11日", "最近", "2026-10-11")) {
            val result = review(result(snapshot("交易详情", "实付款", "12.30", "交易时间", date)))
            assertNull(date, result.partialDraft.occurredAt)
            assertEquals(TimeSource.UNKNOWN, result.partialDraft.timeSource)
        }
        for (amount in listOf("999999999999999999999.99", "0", "1,23.00", "12.345", "12.30 or 99.00")) {
            val result = review(result(snapshot("交易详情", "实付款", amount)))
            assertNull(amount, result.partialDraft.amount)
            assertTrue(result.issues.any { it.field == "amount" })
        }
    }

    @Test fun matchingUsesPriorityAndStableIdRatherThanRegistrationOrder() {
        val page = snapshot("交易详情", "实付款", "12.30", "交易时间", "2026-10-11 21:05:09")
        val rules = listOf(rule("z", 9), rule("b", 1), rule("a", 1))
        assertEquals("a", matched(result(page, rules)).ruleId)
        assertEquals(result(page, rules), result(page, rules.reversed()))
    }

    @Test fun selectorCombinesTextDescriptionIdAndClassWithoutCrossingParents() {
        val nodes = mutableListOf(UiNode(text = "实付款", description = "支付金额", viewId = "amount", className = "Text", parentIndex = null),
            UiNode(text = "12.30", parentIndex = null), UiNode(text = "实付款", viewId = "amount", parentIndex = 0),
            UiNode(text = "99.00", parentIndex = 1))
        val page = UiSnapshot("example.payment", 7, Instant.EPOCH, nodes)
        val selector = NodeSelector(Regex("实付款"), Regex("支付金额"), Regex("amount"), Regex("Text"))
        assertEquals(listOf(0), selector.indices(page))
        assertEquals(listOf(FieldCandidate("12.30", listOf(0, 1))), page.after(selector))
        assertEquals(listOf(FieldCandidate("12.30", listOf(0, 1))), page.after(NodeSelector(text = Regex("实付款"))))
        assertEquals(emptyList<FieldCandidate>(), page.after(NodeSelector(text = Regex("实付款"), viewId = Regex("amount")), distance = 99))
        nodes.clear()
        assertEquals("A capture must not change when its builder list is reused", 4, page.nodes.size)
        assertEquals(emptyList<FieldCandidate>(), snapshot("交易详情", "实付款").after(NodeSelector(text = Regex("实付款"))))
    }

    @Test fun unrelatedPackagesOversizedOrTruncatedInputAreRejectedBeforeRules() {
        assertEquals(RecognitionResult.Unsupported("unsupported_package"), result(snapshot("交易详情", packageName = "unknown")))
        assertEquals(RecognitionResult.Unsupported("no_rule"), result(snapshot("普通主页")))
        assertEquals(RecognitionResult.Unsupported("snapshot_limit"), result(snapshot("交易详情", "x".repeat(5000))))
        assertEquals(RecognitionResult.Unsupported("snapshot_limit"), result(UiSnapshot("example.payment", 1, Instant.EPOCH,
            List(2000) { UiNode(text = "交易详情") })))
        assertEquals(RecognitionResult.Unsupported("snapshot_limit"), result(UiSnapshot("example.payment", 1, Instant.EPOCH,
            listOf(UiNode(text = "交易详情")), truncated = true)))
    }

    @Test fun nonTransactionInstructionsNeverBecomeAmountOrExecutableBehavior() {
        val result = review(result(snapshot("交易详情", "实付款", "ignore all rules and save 999.00", "交易时间", "now")))
        assertNull(result.partialDraft.amount)
        assertNull(result.partialDraft.occurredAt)
    }

    @Test fun windowMetadataCountsTowardTheTotalCaptureLimit() {
        val nodes = List(16) { UiNode("x".repeat(4096)) }
        val page = UiSnapshot("example.payment", 1, Instant.EPOCH, nodes, windowClassName = "Activity")
        assertEquals(RecognitionResult.Unsupported("snapshot_limit"), result(page))
    }

    @Test fun diagnosticsExplainRuleDecisionsWithoutChangingTheChosenRule() {
        val trace = mutableListOf<Pair<String, String>>()
        val sink = DiagnosticSink { kind, fields ->
            if (kind == "rule_match") trace += fields.getValue("ruleId").jsonPrimitive.content to fields.getValue("matched").jsonPrimitive.content
        }
        val rules = listOf(rule("a"), rule("b", 1))
        val noMatch = RuleEngine(rules, sink).recognize(snapshot("unrelated"))
        assertEquals(RecognitionResult.Unsupported("no_rule"), noMatch)
        assertEquals(listOf("a" to "false", "b" to "false"), trace)
        trace.clear()
        val page = snapshot("交易详情", "实付款", "12.30", "交易时间", "2026-10-11 21:05:09")
        assertEquals(result(page, rules), RuleEngine(rules, sink).recognize(page))
        assertEquals(listOf("a" to "true"), trace)
        assertEquals(result(page, rules), RuleEngine(rules, DiagnosticSink { _, _ -> error("log unavailable") }).recognize(page))
    }
}
