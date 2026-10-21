package app.omymoney.core.recognition

import app.omymoney.core.diagnostics.*
import kotlinx.serialization.json.*

class RuleEngine(rules: List<PageRule>, private val diagnostics: DiagnosticSink = DiagnosticSink.NONE) {
    private val rules = rules.toList().sortedWith(compareBy({ it.priority }, { it.id }))
    init { require(this.rules.map { it.id }.distinct().size == this.rules.size) { "Duplicate rule identity" } }

    fun recognize(snapshot: UiSnapshot): RecognitionResult {
        if (snapshot.truncated || (snapshot.windowClassName?.length ?: 0) > MAX_FIELD_CHARS || snapshot.nodes.size > MAX_NODES || snapshot.nodes.any { node ->
            listOfNotNull(node.text, node.description, node.viewId, node.className).any { it.length > MAX_FIELD_CHARS }
        } || (snapshot.windowClassName?.length ?: 0) + snapshot.nodes.sumOf { node ->
            listOfNotNull(node.text, node.description, node.viewId, node.className).sumOf { it.length }
        } > MAX_TOTAL_CHARS) {
            return RecognitionResult.Unsupported("snapshot_limit")
        }
        if (snapshot.nodes.indices.any { index -> snapshot.nodes[index].parentIndex?.let { it !in 0 until index } == true }) {
            return RecognitionResult.Unsupported("invalid_hierarchy")
        }
        val applicable = rules.filter { it.packageName == snapshot.packageName }
        if (applicable.isEmpty()) return RecognitionResult.Unsupported("unsupported_package")
        return try {
            applicable.firstOrNull { rule ->
                rule.matches(snapshot).also { matched -> diagnostics.recordSafely("rule_match", buildJsonObject {
                    put("ruleId", rule.id); put("package", snapshot.packageName); put("windowId", snapshot.windowId); put("matched", matched)
                }) }
            }?.extract(snapshot) ?: RecognitionResult.Unsupported("no_rule")
        } catch (error: IllegalArgumentException) {
            diagnostics.recordSafely("rule_error", DiagnosticRedactor.failure(error)); RecognitionResult.Unsupported("invalid_fields")
        } catch (error: IndexOutOfBoundsException) {
            diagnostics.recordSafely("rule_error", DiagnosticRedactor.failure(error)); RecognitionResult.Unsupported("invalid_fields")
        }
    }

    companion object {
        const val MAX_NODES = 1024
        const val MAX_FIELD_CHARS = 4096
        const val MAX_TOTAL_CHARS = 65_536
    }
}
