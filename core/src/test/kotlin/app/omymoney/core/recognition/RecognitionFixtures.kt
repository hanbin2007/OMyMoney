package app.omymoney.core.recognition

import app.omymoney.core.model.BillKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

@Serializable data class FixtureNode(val text: String? = null, val description: String? = null,
    val className: String? = null, val viewId: String? = null, val parentIndex: Int? = null)
@Serializable data class RecognitionFixture(val name: String, val ruleId: String, val positive: Boolean,
    val nodes: List<FixtureNode>, val kind: BillKind? = null, val minor: Long? = null,
    val time: String? = null, val outcome: String = "review", val paymentIssue: String? = null) {
    fun snapshot(packageName: String) = UiSnapshot(packageName, 7, Instant.parse("2026-10-11T14:00:00Z"),
        nodes.map { UiNode(it.text, it.description, it.viewId, it.className, it.parentIndex) })
}

fun fixtures(app: String): List<RecognitionFixture> = Json.decodeFromString(
    checkNotNull(RecognitionFixture::class.java.getResource("/recognition/$app/scenarios.json")).readText())

fun RecognitionResult.draft() = when (this) {
    is RecognitionResult.Matched -> draft
    is RecognitionResult.NeedsReview -> partialDraft
    is RecognitionResult.Unsupported -> error("Unexpected unsupported result: $reason")
}

fun RecognitionResult.evidence() = when (this) {
    is RecognitionResult.Matched -> evidence
    is RecognitionResult.NeedsReview -> evidence
    is RecognitionResult.Unsupported -> emptyList()
}
