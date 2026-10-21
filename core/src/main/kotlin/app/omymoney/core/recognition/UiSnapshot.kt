package app.omymoney.core.recognition

import java.time.Instant
import java.time.ZoneId
import java.text.Normalizer
import java.util.Collections
import app.omymoney.core.model.CurrencySpec

data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)
class OcrDocument(val width: Int, val height: Int, lines: List<OcrLine>) {
    val lines: List<OcrLine> = Collections.unmodifiableList(lines.toList())
}

data class UiNode(val text: String? = null, val description: String? = null, val viewId: String? = null,
    val className: String? = null, val parentIndex: Int? = null)

class UiSnapshot(val packageName: String, val windowId: Int, val capturedAt: Instant, nodes: List<UiNode>,
    val truncated: Boolean = false, val windowClassName: String? = null) {
    val nodes: List<UiNode> = Collections.unmodifiableList(nodes.toList())
}

data class RecognitionContext(val bookId: String = "", val currency: CurrencySpec = CurrencySpec.CNY,
    val zoneId: ZoneId = ZoneId.of("UTC"))

data class NodeSelector(val text: Regex? = null, val description: Regex? = null, val viewId: Regex? = null,
    val className: Regex? = null) {
    fun indices(snapshot: UiSnapshot): List<Int> = snapshot.nodes.indices.filter { index ->
        val node = snapshot.nodes[index]
        (text == null || node.text?.let { text.matches(cleanText(it)) } == true) &&
            (description == null || node.description?.let { description.matches(cleanText(it)) } == true) &&
            (viewId == null || node.viewId?.let(viewId::matches) == true) &&
            (className == null || node.className?.let(className::matches) == true)
    }
}

data class FieldCandidate(val value: String, val nodeIndices: List<Int>, val rawText: String = value)

fun UiSnapshot.after(selector: NodeSelector, distance: Int = 1): List<FieldCandidate> {
    if (distance !in 1..8) return emptyList()
    return selector.indices(this).mapNotNull { anchor ->
        val index = anchor + distance
        val node = nodes.getOrNull(index) ?: return@mapNotNull null
        if (node.parentIndex != nodes[anchor].parentIndex) return@mapNotNull null
        val value = node.text?.takeIf { it.isNotBlank() } ?: node.description?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        FieldCandidate(value, listOf(anchor, index))
    }
}

internal fun cleanText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .replace(Regex("[\\u200B-\\u200F\\uFEFF]"), "").replace(Regex("[\\s\\u00a0]+"), " ").trim()
