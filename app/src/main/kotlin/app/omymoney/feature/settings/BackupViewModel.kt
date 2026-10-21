package app.omymoney.feature.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omymoney.data.backup.*
import app.omymoney.data.openCancellableInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

enum class BackupOperation { LOAD, EXPORT, INSPECT, RESTORE, DISCARD }
data class BackupState(val busy: Boolean = false, val preview: RestorePreview? = null, val error: String? = null,
    val notice: String? = null, val recoveryAvailable: Boolean = false, val operation: BackupOperation? = null) {
    val cancellable: Boolean get() = busy && operation in setOf(BackupOperation.LOAD, BackupOperation.EXPORT, BackupOperation.INSPECT)
}

class BackupViewModel(private val archive: BackupArchive, private val resolver: ContentResolver,
    private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(BackupState())
    private var job: Job? = null
    private sealed interface PickerResult {
        data class Export(val uri: Uri, val recovery: Boolean) : PickerResult
        data class Inspect(val uri: Uri) : PickerResult
    }
    private var pendingPickerResult: PickerResult? = null
    private val stream = AtomicReference<Closeable?>(null)
    val state = mutable.asStateFlow()
    init {
        perform(BackupOperation.LOAD) {
            val preview = saved.get<String>(PREVIEW_KEY)?.let { archive.preview(it) }
            mutable.value = mutable.value.copy(preview = preview, recoveryAvailable = archive.hasRecovery())
        }
    }
    fun prepareExport(recovery: Boolean) { saved[EXPORT_KEY] = recovery }
    fun export(uri: Uri?) {
        if (uri == null) return
        accept(PickerResult.Export(uri, saved.get<Boolean>(EXPORT_KEY) ?: false))
    }
    fun inspect(uri: Uri?) {
        if (uri != null) accept(PickerResult.Inspect(uri))
    }
    private fun accept(result: PickerResult) {
        if (mutable.value.operation == BackupOperation.LOAD) { pendingPickerResult = result; return }
        when (result) {
            is PickerResult.Export -> exportDocument(result.uri, result.recovery)
            is PickerResult.Inspect -> inspectDocument(result.uri)
        }
    }
    private fun exportDocument(uri: Uri, recovery: Boolean) {
        perform(BackupOperation.EXPORT) {
            withContext(Dispatchers.IO) {
                require(uri.scheme == "content")
                val output = resolver.openOutputStream(uri, "wt") ?: throw BackupException("storage")
                output.use {
                    stream.set(it)
                    try { if (recovery) archive.exportRecovery(it) else archive.exportTo(it) }
                    finally { stream.compareAndSet(it, null) }
                }
            }
            mutable.value = mutable.value.copy(notice = "exported")
        }
    }
    private fun inspectDocument(uri: Uri) {
        perform(BackupOperation.INSPECT) {
            val preview = withContext(Dispatchers.IO) {
                require(uri.scheme == "content")
                val input = resolver.openCancellableInput(uri)
                input.use {
                    stream.set(it)
                    try { archive.inspect(it) } finally { stream.compareAndSet(it, null) }
                }
            }
            saved.get<String>(PREVIEW_KEY)?.let { archive.cancel(it) }
            saved[PREVIEW_KEY] = preview.previewId
            mutable.value = mutable.value.copy(preview = preview)
        }
    }
    fun cancelPreview() = perform(BackupOperation.DISCARD) {
        val id = mutable.value.preview?.previewId
        saved.remove<String>(PREVIEW_KEY)
        mutable.value = mutable.value.copy(preview = null)
        id?.let { archive.cancel(it) }
    }
    fun restore(mode: RestoreMode) = perform(BackupOperation.RESTORE) {
        val preview = mutable.value.preview ?: return@perform
        val result = archive.restore(preview.previewId, mode)
        saved.remove<String>(PREVIEW_KEY)
        mutable.value = mutable.value.copy(preview = null, recoveryAvailable = result.recoveryAvailable, notice = "restored")
    }
    fun clearNotice() { mutable.value = mutable.value.copy(notice = null) }
    fun cancelOperation() {
        if (!mutable.value.cancellable) return
        pendingPickerResult = null
        job?.cancel()
        val activeStream = stream.getAndSet(null)
        viewModelScope.launch(Dispatchers.IO) { runCatching { activeStream?.close() } }
    }
    private fun perform(operation: BackupOperation, block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null, notice = null, operation = operation)
        job = viewModelScope.launch(Dispatchers.Main.immediate) {
            try { block() }
            catch (error: CancellationException) {
                pendingPickerResult = null
                mutable.value = mutable.value.copy(notice = if (operation == BackupOperation.EXPORT) "export_cancelled" else "cancelled")
                throw error
            }
            catch (error: Exception) {
                val code = (error as? BackupException)?.code ?: "storage"
                if (code in setOf("expired_preview", "invalid_preview")) {
                    saved.remove<String>(PREVIEW_KEY)
                    mutable.value = mutable.value.copy(preview = null)
                }
                mutable.value = mutable.value.copy(error = code)
            }
            finally {
                withContext(NonCancellable) {
                    mutable.value = mutable.value.copy(busy = false, operation = null, recoveryAvailable = archive.hasRecovery())
                }
            }
            if (operation == BackupOperation.LOAD) {
                pendingPickerResult?.let { result -> pendingPickerResult = null; accept(result) }
            }
        }
    }
    companion object { const val PREVIEW_KEY = "backup-preview"; private const val EXPORT_KEY = "backup-recovery-export" }
}
