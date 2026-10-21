package app.omymoney.feature.exchange

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omymoney.core.ledger.LedgerRepository
import app.omymoney.core.model.Book
import app.omymoney.data.exchange.*
import app.omymoney.data.openCancellableInput
import app.omymoney.feature.bills.BillFilters
import app.omymoney.feature.bills.BillListState
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class ExchangeOperation { LOAD, INSPECT, EXPORT, REMAP, COMMIT, DISCARD }
data class ExchangeState(val operation: ExchangeOperation? = null, val preview: ImportPreview? = null,
    val error: String? = null, val notice: String? = null, val count: Int = 0) {
    val busy get() = operation != null
    val cancellable get() = operation in setOf(ExchangeOperation.LOAD, ExchangeOperation.INSPECT, ExchangeOperation.EXPORT, ExchangeOperation.REMAP)
}

class ExchangeViewModel(private val importer: BillImportService, private val exporter: BillExportService,
    ledger: LedgerRepository, private val resolver: ContentResolver, val book: Book, val filters: BillFilters,
    private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(ExchangeState())
    val state = mutable.asStateFlow()
    val selected = saved.getStateFlow("exchange-rows", intArrayOf())
    val format = saved.getStateFlow("exchange-format", ExchangeFormat.CSV.name)
    val bills = ledger.observeBills(filters.query(book.id, book.zoneId)).map { BillListState(it, loading = false) }
        .catch { emit(BillListState(loading = false, failed = true)) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BillListState())
    private var job: Job? = null
    private val stream = AtomicReference<Closeable?>(null)
    private data class PickerResult(val uri: Uri, val format: ExchangeFormat, val export: Boolean)
    private var pending: PickerResult? = null
    init { perform(ExchangeOperation.LOAD) {
        val preview = saved.get<String>(PREVIEW_KEY)?.let { importer.preview(it) }
        require(preview == null || preview.bookId == book.id)
        mutable.value = mutable.value.copy(preview = preview)
    } }
    fun chooseFormat(format: ExchangeFormat) { if (!state.value.busy) saved["exchange-format"] = format.name }
    fun preparePicker() { saved["exchange-picker-format"] = format.value }
    fun import(uri: Uri?) { accept(uri, false) }
    fun export(uri: Uri?) { accept(uri, true) }
    private fun accept(uri: Uri?, export: Boolean) {
        if (uri == null) return
        val result = PickerResult(uri, ExchangeFormat.valueOf(saved.get<String>("exchange-picker-format") ?: format.value), export)
        if (state.value.operation == ExchangeOperation.LOAD) pending = result else document(result)
    }
    private fun document(result: PickerResult) = perform(if (result.export) ExchangeOperation.EXPORT else ExchangeOperation.INSPECT) {
        require(result.uri.scheme == "content")
        if (result.export) {
            val count = withContext(Dispatchers.IO) {
                val output = resolver.openOutputStream(result.uri, "wt") ?: throw ExchangeException("storage")
                output.use { stream.set(it); try { exporter.export(filters.query(book.id, book.zoneId), result.format, it) } finally { stream.compareAndSet(it, null) } }
            }
            mutable.value = mutable.value.copy(notice = "exported", count = count)
        } else {
            val preview = withContext(Dispatchers.IO) {
                val input = resolver.openCancellableInput(result.uri)
                input.use { stream.set(it); try { importer.preview(it, result.format, book.id) } finally { stream.compareAndSet(it, null) } }
            }
            saved.get<String>(PREVIEW_KEY)?.let { importer.cancel(it) }
            withContext(Dispatchers.Main.immediate) {
                saved[PREVIEW_KEY] = preview.previewId; saved["exchange-rows"] = intArrayOf()
                mutable.value = mutable.value.copy(preview = preview)
            }
        }
    }
    fun select(number: Int) {
        if (state.value.busy || state.value.preview?.rows?.firstOrNull { it.number == number }?.selectable != true) return
        val rows = selected.value.toSet()
        saved["exchange-rows"] = (if (number in rows) rows - number else rows + number).toIntArray()
    }
    fun selectAll() {
        if (state.value.busy) return
        val valid = state.value.preview?.rows.orEmpty().filter { it.selectable }.map { it.number }
        saved["exchange-rows"] = if (valid.all { it in selected.value }) intArrayOf() else valid.toIntArray()
    }
    fun remap(mapping: ImportMapping) = perform(ExchangeOperation.REMAP) {
        val preview = state.value.preview ?: return@perform
        val updated = importer.remap(preview.previewId, mapping)
        withContext(Dispatchers.Main.immediate) {
            saved["exchange-rows"] = intArrayOf()
            mutable.value = mutable.value.copy(preview = updated)
        }
    }
    fun commit() = perform(ExchangeOperation.COMMIT) {
        val preview = state.value.preview ?: return@perform
        val result = importer.commit(preview.previewId, ImportChoices(selected.value.toSet()))
        withContext(Dispatchers.Main.immediate) {
            saved["exchange-rows"] = intArrayOf()
            mutable.value = mutable.value.copy(notice = "imported", count = result.imported)
        }
        try {
            mutable.value = mutable.value.copy(preview = importer.preview(preview.previewId))
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            // The ledger transaction is already committed; a failed read must not report a failed import.
            withContext(Dispatchers.Main.immediate) {
                saved.remove<String>(PREVIEW_KEY)
                mutable.value = mutable.value.copy(preview = null, notice = "imported_refresh_failed")
            }
        }
    }
    fun discard() = perform(ExchangeOperation.DISCARD) {
        state.value.preview?.let { importer.cancel(it.previewId) }
        withContext(Dispatchers.Main.immediate) {
            saved.remove<String>(PREVIEW_KEY); saved["exchange-rows"] = intArrayOf()
            mutable.value = mutable.value.copy(preview = null)
        }
    }
    fun clearMessage() { mutable.value = mutable.value.copy(error = null, notice = null) }
    fun cancelOperation() {
        if (!state.value.cancellable) return
        pending = null; job?.cancel()
        val current = stream.getAndSet(null)
        viewModelScope.launch(Dispatchers.IO) { runCatching { current?.close() } }
    }
    private fun perform(operation: ExchangeOperation, block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.value = state.value.copy(operation = operation, error = null, notice = null)
        job = viewModelScope.launch(Dispatchers.Main.immediate) {
            try { block() }
            catch (failure: Exception) {
                // Closing a cancelled document can surface an IOException before the context checks cancellation.
                if (failure is CancellationException || !currentCoroutineContext().isActive) {
                    pending = null
                    mutable.value = state.value.copy(notice = if (operation == ExchangeOperation.EXPORT) "export_cancelled" else "cancelled")
                    throw CancellationException("Document operation cancelled", failure)
                }
                val code = (failure as? ExchangeException)?.code ?: "storage_or_conflict"
                withContext(Dispatchers.Main.immediate) {
                    if (code in setOf("expired_preview", "invalid_preview")) {
                        saved.remove<String>(PREVIEW_KEY); saved["exchange-rows"] = intArrayOf()
                        mutable.value = state.value.copy(preview = null)
                    }
                    mutable.value = state.value.copy(error = code)
                }
            } finally { mutable.value = state.value.copy(operation = null) }
            if (operation == ExchangeOperation.LOAD) pending?.let { pending = null; document(it) }
        }
    }
    companion object { const val PREVIEW_KEY = "exchange-preview" }
}
