package app.omymoney.feature.bills

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omymoney.core.ledger.LedgerRepository
import app.omymoney.core.model.UndoToken
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class PendingUndo(val token: UndoToken, val displayUntil: Long, val displayDuration: Long, val failed: Boolean = false) {
    fun bundle() = Bundle().apply {
        putString("id", token.id); putString("billId", token.billId); putString("expires", token.expiresAt.toString())
        putLong("displayUntil", displayUntil); putLong("displayDuration", displayDuration); putBoolean("failed", failed)
    }
    companion object {
        fun read(bundle: Bundle?): PendingUndo? = bundle?.let {
            PendingUndo(UndoToken(requireNotNull(it.getString("id")), requireNotNull(it.getString("billId")),
                Instant.parse(it.getString("expires"))), it.getLong("displayUntil"), it.getLong("displayDuration"), it.getBoolean("failed"))
        }
    }
}

enum class UndoNotice { RESTORED, UNAVAILABLE }

class UndoViewModel(private val ledger: LedgerRepository, private val savedState: SavedStateHandle) : ViewModel() {
    val pending = savedState.getStateFlow<Bundle?>("pendingUndo", null).map(PendingUndo::read)
        .stateIn(viewModelScope, SharingStarted.Eagerly, PendingUndo.read(savedState["pendingUndo"]))
    private val busyState = MutableStateFlow(false)
    val busy = busyState.asStateFlow()
    private val noticeState = MutableStateFlow<UndoNotice?>(null)
    val notice = noticeState.asStateFlow()

    fun offer(token: UndoToken, displayDuration: Long) {
        noticeState.value = null
        savedState["pendingUndo"] = PendingUndo(token, deadline(token, displayDuration), displayDuration).bundle()
    }
    fun dismiss(token: UndoToken) {
        if (PendingUndo.read(savedState["pendingUndo"])?.token == token) savedState["pendingUndo"] = null
    }
    fun acknowledgeNotice(value: UndoNotice) { if (noticeState.value == value) noticeState.value = null }

    fun undo() {
        val current = PendingUndo.read(savedState["pendingUndo"]) ?: return
        if (busyState.value) return
        busyState.value = true
        viewModelScope.launch(Dispatchers.Main.immediate) {
            try {
                val restored = ledger.undo(current.token)
                dismiss(current.token)
                noticeState.value = if (restored) UndoNotice.RESTORED else UndoNotice.UNAVAILABLE
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) {
                if (PendingUndo.read(savedState["pendingUndo"])?.token == current.token) {
                    savedState["pendingUndo"] = current.copy(failed = true, displayUntil = deadline(current.token, current.displayDuration)).bundle()
                }
            } finally { busyState.value = false }
        }
    }

    private fun deadline(token: UndoToken, duration: Long): Long {
        val now = System.currentTimeMillis()
        return now + duration.coerceIn(0, (token.expiresAt.toEpochMilli() - now).coerceAtLeast(0))
    }
}
