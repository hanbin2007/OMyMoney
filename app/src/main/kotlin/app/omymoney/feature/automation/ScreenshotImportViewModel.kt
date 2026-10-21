package app.omymoney.feature.automation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.omymoney.data.*
import app.omymoney.core.recognition.*
import app.omymoney.platform.ocr.OcrException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ScreenshotStage { READY, READING, QUEUED, FAILED, CANCELLED }
data class ScreenshotItem(val id: String, val uri: String, val rotation: Int = 0, val stage: ScreenshotStage = ScreenshotStage.READY,
    val entryId: String? = null, val error: String? = null)
data class ScreenshotImportState(val items: List<ScreenshotItem> = emptyList(), val busy: Boolean = false, val error: String? = null)

class ScreenshotImportViewModel(private val catalog: CatalogRepository, private val queue: RecognitionQueue,
    private val reader: suspend (Uri, Int) -> OcrDocument, private val savedState: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    private val mutable = MutableStateFlow(ScreenshotImportState(restore()))
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun rejectInput() { mutable.update { it.copy(error = "unsupported_uri") } }
    fun accept(uris: List<Uri>) {
        if (mutable.value.busy || uris.isEmpty()) return
        if (uris.size > 10) { mutable.update { it.copy(error = "batch_limit") }; return }
        if (uris.any { it.scheme != "content" || it.toString().length > 4096 }) { mutable.update { it.copy(error = "unsupported_uri") }; return }
        mutable.value = ScreenshotImportState(uris.distinct().map { ScreenshotItem(UUID.randomUUID().toString(), it.toString()) })
        persist()
    }
    fun start() {
        if (mutable.value.busy || mutable.value.items.none { it.stage != ScreenshotStage.QUEUED }) return
        mutable.update { it.copy(busy = true, error = null) }
        job = viewModelScope.launch {
            try {
                catalog.ensureLocalDefaults()
                val context = catalog.recognitionContext()
                for (item in mutable.value.items.filter { it.stage != ScreenshotStage.QUEUED }) {
                    currentCoroutineContext().ensureActive()
                    update(item.copy(stage = ScreenshotStage.READING, error = null))
                    try {
                        val result = withContext(Dispatchers.IO) {
                            val document = reader(Uri.parse(item.uri), item.rotation)
                            ScreenshotRules.recognize(document, context, Instant.now())
                        }
                        currentCoroutineContext().ensureActive()
                        if (result is RecognitionResult.Unsupported) update(item.copy(stage = ScreenshotStage.FAILED, error = result.reason))
                        else {
                            val stable = when (result) {
                                is RecognitionResult.Matched -> result.copy(draft = result.draft.copy(submissionId = item.id))
                                is RecognitionResult.NeedsReview -> result.copy(partialDraft = result.partialDraft.copy(submissionId = item.id))
                                is RecognitionResult.Unsupported -> result
                            }
                            val entry = queue.enqueue(stable)
                            update(item.copy(stage = ScreenshotStage.QUEUED, entryId = entry.id))
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { update(item.copy(stage = ScreenshotStage.FAILED, error = when (error) {
                        is OcrException -> error.code; is QueueException -> error.code; else -> "unreadable"
                    })) }
                }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(items = it.items.map { item ->
                    if (item.stage in setOf(ScreenshotStage.READING, ScreenshotStage.READY)) item.copy(stage = ScreenshotStage.CANCELLED) else item
                }) }
                throw cancelled
            } catch (_: Exception) { mutable.update { it.copy(error = "storage") } }
            finally { mutable.update { it.copy(busy = false) }; persist() }
        }
    }
    fun cancel() { job?.cancel() }
    fun rotate(id: String) {
        if (mutable.value.busy) return
        val item = mutable.value.items.firstOrNull { it.id == id && it.stage != ScreenshotStage.QUEUED } ?: return
        update(item.copy(rotation = (item.rotation + 90) % 360, stage = ScreenshotStage.READY, error = null))
    }
    private fun update(item: ScreenshotItem) {
        mutable.update { it.copy(items = it.items.map { previous -> if (previous.id == item.id) item else previous }) }
        persist()
    }
    private fun persist() {
        savedState["images"] = ArrayList(mutable.value.items.map { arrayListOf(it.id, it.uri, it.rotation.toString(), it.stage.name, it.entryId.orEmpty(), it.error.orEmpty()) })
    }
    private fun restore(): List<ScreenshotItem> = savedState.get<ArrayList<ArrayList<String>>>("images").orEmpty().take(10).mapNotNull { values ->
        if (values.size != 6 || Uri.parse(values[1]).scheme != "content") return@mapNotNull null
        val stage = runCatching { ScreenshotStage.valueOf(values[3]) }.getOrDefault(ScreenshotStage.CANCELLED)
        ScreenshotItem(values[0], values[1], values[2].toIntOrNull()?.takeIf { it in setOf(0, 90, 180, 270) } ?: 0,
            if (stage == ScreenshotStage.READING) ScreenshotStage.CANCELLED else stage, values[4].ifEmpty { null }, values[5].ifEmpty { null })
    }
}
