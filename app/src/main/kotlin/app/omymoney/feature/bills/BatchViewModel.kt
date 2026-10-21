package app.omymoney.feature.bills

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omymoney.core.model.*
import app.omymoney.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.UUID

class BatchViewModel(private val store: LedgerStore, val book: Book, val filters: BillFilters,
    private val saved: SavedStateHandle) : ViewModel() {
    val bills = store.ledger.observeBills(filters.query(book.id, book.zoneId))
        .map { BillListState(it, loading = false) }.catch { emit(BillListState(loading = false, failed = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BillListState())
    val actions = store.batchActions.observeActions(book.id).map { it.sortedByDescending { action -> action.expiresAt } }
        .catch { error.value = true }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val selection = MutableStateFlow(readSelection(saved["batch-selection"]))
    val selected = selection.asStateFlow()
    val form = saved.getStateFlow("batch-form", Bundle())
    val editing = saved.getStateFlow("batch-editing", false)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow(false)
    val notice = MutableStateFlow<String?>(null)

    fun toggle(bill: Bill) {
        if (busy.value) return
        setSelection(Bundle(selected.value).apply {
            if (containsKey(bill.id)) remove(bill.id) else if (size() < 10_000) putLong(bill.id, bill.revision)
        })
    }
    fun selectAll() {
        if (busy.value) return
        val all = bills.value.bills.take(10_000)
        setSelection(Bundle().apply {
            if (all.any { !selected.value.containsKey(it.id) }) all.forEach { putLong(it.id, it.revision) }
        })
    }
    fun startEdit() { if (!busy.value && !selected.value.isEmpty) saved["batch-editing"] = true }
    fun field(key: String, value: String) { saved["batch-form"] = Bundle(form.value).apply { putString(key, value) }; error.value = false }
    fun discard() { if (!busy.value) { saved["batch-editing"] = false; saved["batch-form"] = Bundle() } }
    private fun expected() = selected.value.keySet().associateWith { selected.value.getLong(it) }
    fun save() = perform {
        val values = form.value
        fun changed(key: String) = values.getString("change-$key") == "true"
        fun flag(key: String) = values.getString(key)?.takeUnless { it == "keep" }?.toBooleanStrictOrNull()
        val patch = BatchPatch(note = if (changed("note")) values.getString("note").orEmpty() else null,
            tags = if (changed("tags")) values.getString("tags").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).distinct() else null,
            changeCategory = changed("category"), categoryId = values.getString("category")?.takeIf(String::isNotEmpty),
            excludeStats = flag("stats"), excludeBudget = flag("budget"))
        store.batchActions.edit(expected(), patch)
        completed("saved")
    }
    fun delete() = perform { store.batchActions.delete(expected()); completed("deleted") }
    fun undo(id: String) = perform {
        if (!store.batchActions.undo(id)) error.value = true else notice.value = "restored"
    }
    private suspend fun completed(message: String) = withContext(Dispatchers.Main.immediate) {
        setSelection(Bundle()); saved["batch-form"] = Bundle(); saved["batch-editing"] = false
        notice.value = message
    }
    private fun setSelection(value: Bundle) {
        // UUID bits plus revisions keep 10000 selections below the shared Activity Binder budget.
        val buffer = ByteBuffer.allocate(value.size() * 24)
        value.keySet().forEach { key ->
            val id = UUID.fromString(key)
            buffer.putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).putLong(value.getLong(key))
        }
        saved["batch-selection"] = buffer.array()
        selection.value = value
    }
    private fun readSelection(value: Any?): Bundle = when (value) {
        is Bundle -> Bundle(value)
        is ByteArray -> Bundle().apply {
            if (value.size % 24 == 0 && value.size <= 240_000) {
                val buffer = ByteBuffer.wrap(value)
                while (buffer.hasRemaining()) putLong(UUID(buffer.long, buffer.long).toString(), buffer.long)
            }
        }
        else -> Bundle()
    }
    private fun perform(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true; error.value = false; notice.value = null
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error.value = true }
            finally { busy.value = false }
        }
    }
}
