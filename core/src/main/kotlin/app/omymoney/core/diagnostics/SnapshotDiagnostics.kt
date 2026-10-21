package app.omymoney.core.diagnostics

import app.omymoney.core.recognition.UiSnapshot
import java.util.UUID
import kotlinx.serialization.json.*

fun DiagnosticSink.recordSnapshot(snapshot: UiSnapshot, source: String, pruned: Map<String, Int> = emptyMap(),
    metadataOnlyNow: () -> Boolean = { false }) {
    if (!enabled) return
    val id = UUID.randomUUID().toString()
    val restricted = metadataOnlyNow() || pruned.getOrDefault("restricted_window", 0) > 0 ||
        pruned.getOrDefault("password", 0) > 0 || pruned.getOrDefault("sensitive", 0) > 0 ||
        snapshot.nodes.any { node -> listOfNotNull(node.text, node.description).any { DiagnosticRedactor.text(it) == "[REDACTED]" } }
    recordSafely("snapshot", buildJsonObject {
        put("snapshotId", id); put("source", source); put("package", snapshot.packageName); put("windowId", snapshot.windowId)
        put("capturedAt", snapshot.capturedAt.toString()); put("truncated", snapshot.truncated)
        put("nodeCount", snapshot.nodes.size); put("metadataOnly", restricted)
        put("pruned", buildJsonObject { pruned.forEach { (key, count) -> put(key, count) } })
    })
    if (restricted) return
    var nodes = mutableListOf<JsonElement>()
    var size = 0
    var chunk = 0
    var privacyInterrupted = false
    fun emit() {
        if (nodes.isEmpty()) return
        if (metadataOnlyNow()) { nodes.clear(); size = 0; privacyInterrupted = true; return }
        recordSafely("snapshot_nodes", buildJsonObject {
            put("snapshotId", id); put("chunk", chunk++); put("nodes", JsonArray(nodes.toList()))
        })
        nodes = mutableListOf(); size = 0
    }
    snapshot.nodes.forEachIndexed { index, node ->
        val data = DiagnosticRedactor.clean(buildJsonObject {
            put("index", index); node.parentIndex?.let { put("parent", it) }
            node.text?.let { put("text", it) }; node.description?.let { put("description", it) }
            node.viewId?.let { put("viewId", it) }; node.className?.let { put("class", it) }
        })
        val bytes = data.toString().toByteArray().size
        if (size + bytes > 64 * 1024) emit()
        nodes += data; size += bytes
    }
    emit()
    recordSafely("snapshot_end", buildJsonObject { put("snapshotId", id); put("chunks", chunk); put("privacyInterrupted", privacyInterrupted) })
}
