package app.omymoney.platform.overlay

class OverlaySession {
    var entryId: String? = null
        private set
    var expanded: Boolean = false
        private set
    fun offer(id: String, unlocked: Boolean, appVisible: Boolean): Boolean {
        if (!unlocked || appVisible || entryId != null) return false
        entryId = id
        return true
    }
    fun expand() { if (entryId != null) expanded = true }
    fun dismiss() { entryId = null; expanded = false }
}
