package app.omymoney.platform.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import android.os.Build
import android.os.SystemClock
import app.omymoney.core.recognition.*
import java.time.Instant
import app.omymoney.core.diagnostics.*
import kotlinx.serialization.json.*

internal interface SnapshotNode {
    val packageName: String?
    val windowId: Int
    val password: Boolean
    val editable: Boolean
    val sensitive: Boolean
    val visible: Boolean
    val text: String?
    val description: String?
    val viewId: String?
    val className: String?
    val childCount: Int
    fun child(index: Int): SnapshotNode?
    fun release()
}

class NodeSnapshotReader(private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val source: String = "recognition", private val maxReadMillis: Long = 250,
    private val metadataOnlyNow: () -> Boolean = { false }) {
    fun read(root: AccessibilityNodeInfo, allowedPackage: String, metadataOnly: Boolean = false): UiSnapshot? =
        read(AndroidNode(root), allowedPackage, metadataOnly)

    internal fun read(root: SnapshotNode, allowedPackage: String, metadataOnly: Boolean = false): UiSnapshot? {
        if (allowedPackage.isBlank() || root.packageName != allowedPackage) {
            diagnostics.recordSafely("snapshot_rejected", buildJsonObject { put("reason", "root_package"); put("source", source) })
            return null
        }
        val nodes = mutableListOf<UiNode>()
        var truncated = false
        var chars = 0
        var visited = 0
        val deadline = SystemClock.uptimeMillis() + maxReadMillis.coerceIn(1, 250)
        val pruned = mutableMapOf<String, Int>()
        if (metadataOnly) pruned["restricted_window"] = 1
        fun prune(reason: String) { pruned[reason] = pruned.getOrDefault(reason, 0) + 1 }
        fun visit(node: SnapshotNode, parent: Int?, depth: Int) {
            if (++visited > RuleEngine.MAX_NODES || depth > 40 || SystemClock.uptimeMillis() > deadline) {
                truncated = true; prune("traversal_limit"); return
            }
            val reason = when {
                node.password -> "password"
                node.editable -> "editable"
                node.sensitive -> "sensitive"
                !node.visible -> "invisible"
                node.packageName != null && node.packageName != allowedPackage -> "foreign_package"
                else -> null
            }
            if (reason != null) { prune(reason); return }
            val fields = listOf(node.text, node.description, node.viewId, node.className)
            if (fields.any { (it?.length ?: 0) > RuleEngine.MAX_FIELD_CHARS } ||
                chars + fields.sumOf { it?.length ?: 0 } > RuleEngine.MAX_TOTAL_CHARS) { truncated = true; prune("text_limit"); return }
            chars += fields.sumOf { it?.length ?: 0 }
            val index = nodes.size
            nodes += UiNode(fields[0], fields[1], fields[2], fields[3], parent)
            val count = node.childCount
            if (count > 256) { truncated = true; prune("child_limit") }
            for (childIndex in 0 until count.coerceIn(0, 256)) {
                if (visited >= RuleEngine.MAX_NODES || SystemClock.uptimeMillis() > deadline) { truncated = true; prune("traversal_limit"); break }
                val child = node.child(childIndex) ?: continue
                try { visit(child, index, depth + 1) } finally { child.release() }
            }
        }
        return try {
            visit(root, null, 0)
            UiSnapshot(allowedPackage, root.windowId, Instant.now(), nodes, truncated).also {
                diagnostics.recordSnapshot(it, source, pruned, metadataOnlyNow)
            }
        } catch (error: IllegalStateException) { diagnostics.recordSafely("snapshot_error", DiagnosticRedactor.failure(error)); null
        } catch (error: SecurityException) { diagnostics.recordSafely("snapshot_error", DiagnosticRedactor.failure(error)); null }
    }

    private class AndroidNode(private val node: AccessibilityNodeInfo) : SnapshotNode {
        override val packageName get() = node.packageName?.toString()
        override val windowId get() = node.windowId
        override val password get() = node.isPassword
        override val editable get() = node.isEditable
        override val sensitive get() = Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive
        override val visible get() = node.isVisibleToUser
        override val text get() = node.text?.toString()
        override val description get() = node.contentDescription?.toString()
        override val viewId get() = node.viewIdResourceName
        override val className get() = node.className?.toString()
        override val childCount get() = node.childCount
        override fun child(index: Int): SnapshotNode? = node.getChild(index)?.let(::AndroidNode)
        @Suppress("DEPRECATION") override fun release() { node.recycle() }
    }
}
