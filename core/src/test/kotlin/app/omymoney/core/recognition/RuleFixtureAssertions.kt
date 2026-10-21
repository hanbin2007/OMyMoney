package app.omymoney.core.recognition

import app.omymoney.core.model.TimeSource
import org.junit.Assert.*
import java.time.Instant

internal class RuleFixtureAssertions(app: String, private val packageName: String,
    private val rules: List<PageRule>, private val count: Int) {
    private val samples = fixtures(app)
    private val engine = RuleEngine(rules)
    fun coverage() {
        assertEquals(count, rules.size)
        assertEquals(count, rules.map { it.id }.toSet().size)
        assertEquals(rules.map { it.id }.toSet(), samples.map { it.ruleId }.toSet())
        rules.forEach { rule ->
            assertTrue(rule.id, samples.any { it.ruleId == rule.id && it.positive })
            assertTrue(rule.id, samples.any { it.ruleId == rule.id && !it.positive })
        }
    }
    fun positives() {
        samples.filter { it.positive }.forEach { fixture ->
            val result = engine.recognize(fixture.snapshot(packageName))
            assertFalse("${fixture.name}: $result", result is RecognitionResult.Unsupported)
            val draft = result.draft()
            assertEquals(fixture.name, fixture.ruleId, draft.ruleId)
            assertEquals(fixture.name, fixture.kind, draft.kind)
            assertEquals(fixture.name, fixture.minor, draft.amount?.minor)
            assertEquals(fixture.name, fixture.time?.let(Instant::parse), draft.occurredAt)
            assertEquals(fixture.name, fixture.outcome == "matched", result is RecognitionResult.Matched)
            assertEquals(fixture.name, if (fixture.time == null) TimeSource.UNKNOWN else TimeSource.PAGE, draft.timeSource)
            fixture.paymentIssue?.let { code -> assertTrue(fixture.name,
                (result as RecognitionResult.NeedsReview).issues.any { it.field == "paymentState" && it.code == code }) }
            if (fixture.minor != null) assertTrue(fixture.name, result.evidence().any { it.field == "amount" && it.nodeIndices.isNotEmpty() })
        }
    }
    fun negatives() {
        samples.filterNot { it.positive }.forEach { fixture ->
            val snapshot = fixture.snapshot(packageName)
            val rule = rules.single { it.id == fixture.ruleId }
            assertTrue(fixture.name, !rule.matches(snapshot) || rule.extract(snapshot) is RecognitionResult.Unsupported)
            assertFalse(fixture.name, engine.recognize(snapshot) is RecognitionResult.Matched)
        }
    }
}
