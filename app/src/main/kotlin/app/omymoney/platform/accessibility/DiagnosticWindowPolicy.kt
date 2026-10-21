package app.omymoney.platform.accessibility

import android.view.accessibility.AccessibilityEvent

/** Widget events inherit their window's privacy classification instead of declaring the window safe. */
internal class DiagnosticWindowPolicy {
    private val windows = linkedMapOf<Pair<String, Int>, Boolean>()
    private var overflowRestricted = false
    private var revision = 0L

    @Synchronized fun observe(packageName: String, windowId: Int, type: Int, className: String?, password: Boolean): Boolean {
        val key = packageName to windowId
        val sensitive = password || className?.let { SENSITIVE.containsMatchIn(it) } == true
        val identifiedTransition = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            className != null && !className.startsWith("android.") && !className.startsWith("androidx.") &&
            (className.endsWith("Activity") || className.endsWith("Dialog"))
        if (sensitive || identifiedTransition) {
            revision++
            if (key !in windows && windows.size >= 32) {
                val removable = windows.entries.firstOrNull { !it.value } ?: windows.entries.first().also { overflowRestricted = true }
                windows.remove(removable.key)
            }
            windows[key] = sensitive
        }
        return windows[key] ?: overflowRestricted
    }

    @Synchronized fun restricted(packageName: String, windowId: Int) = windows[packageName to windowId] ?: overflowRestricted
    @Synchronized fun revision() = revision
    @Synchronized fun clear() { windows.clear(); overflowRestricted = false; revision++ }

    companion object { private val SENSITIVE = Regex("(?i)(password|pwd|login|verify|verification|auth)") }
}
