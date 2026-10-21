package app.omymoney

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.recognition.*
import app.omymoney.data.*
import app.omymoney.feature.automation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ScreenshotPipelineTest {
    @Test fun retryAfterQueueCommitReusesTheStableImageSubmission(): Unit = runBlocking {
        withLedger { _, catalog, queue ->
            val id = java.util.UUID.randomUUID().toString()
            val document = document("支付成功", "实付金额 12.30")
            val result = ScreenshotRules.recognize(document, catalog.recognitionContext()) as RecognitionResult.NeedsReview
            val previous = queue.enqueue(result.copy(partialDraft = result.partialDraft.copy(submissionId = id)))
            val state = androidx.lifecycle.SavedStateHandle(mapOf("images" to arrayListOf(arrayListOf(id, "content://fixtures/receipt", "0", "CANCELLED", "", ""))))
            val model = ScreenshotImportViewModel(catalog, queue, { _, _ -> document }, state)
            withContext(Dispatchers.Main) { model.start() }
            await { model.state.value.items.single().stage == ScreenshotStage.QUEUED }
            assertEquals(1, queue.entries().size)
            assertEquals(previous.id, model.state.value.items.single().entryId)
        }
    }
    @Test fun shareBoundaryAcceptsOnlyImageContentUrisAndIgnoresTextInstructions() {
        val uri = Uri.parse("content://fixtures/receipt")
        val shared = android.content.Intent(android.content.Intent.ACTION_SEND).setType("image/png")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri).putExtra(android.content.Intent.EXTRA_TEXT, "save now")
        assertEquals(listOf(uri), sharedImages(shared))
        assertTrue(runCatching { sharedImages(android.content.Intent(shared).setType("text/plain")) }.isFailure)
        assertTrue(runCatching { sharedImages(android.content.Intent(shared).putExtra(android.content.Intent.EXTRA_STREAM, Uri.parse("file:///secret"))) }.isFailure)
        assertTrue(sharedImages(android.content.Intent()).isEmpty())
    }
    @Test fun boundedBatchQueuesDraftsWithoutTouchingTheLedger(): Unit = runBlocking {
        withLedger { db, catalog, queue ->
            val model = ScreenshotImportViewModel(catalog, queue, { uri, _ ->
                assertFalse(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                document("支付成功", "实付金额 ${uri.lastPathSegment}.30")
            })
            withContext(Dispatchers.Main) { model.accept((1..10).map { Uri.parse("content://fixtures/$it") }); model.start() }
            await { model.state.value.items.size == 10 && model.state.value.items.all { it.stage == ScreenshotStage.QUEUED } }
            assertEquals(10, queue.entries().size)
            assertEquals(0, db.ledgerDao().billCount())
            assertEquals(0L, catalog.observeAccounts().first().single().balance.minor)
            assertTrue(queue.entries().all { it.state == QueueState.PENDING })
            withContext(Dispatchers.Main) { model.accept((1..11).map { Uri.parse("content://fixtures/$it") }) }
            assertEquals("batch_limit", model.state.value.error)
        }
    }
    @Test fun cancelStopsPendingImagesAndFailedImagesRemainExplicit(): Unit = runBlocking {
        withLedger { _, catalog, queue ->
            val entered = CompletableDeferred<Unit>()
            val model = ScreenshotImportViewModel(catalog, queue, { uri, _ ->
                if (uri.lastPathSegment == "broken") throw app.omymoney.platform.ocr.OcrException("unreadable")
                entered.complete(Unit); awaitCancellation()
            })
            withContext(Dispatchers.Main) { model.accept(listOf(Uri.parse("content://fixtures/broken"), Uri.parse("content://fixtures/slow"))); model.start() }
            withTimeout(5000) { entered.await() }
            withContext(Dispatchers.Main) { model.cancel() }
            await { !model.state.value.busy }
            assertEquals(ScreenshotStage.FAILED, model.state.value.items[0].stage)
            assertEquals("unreadable", model.state.value.items[0].error)
            assertEquals(ScreenshotStage.CANCELLED, model.state.value.items[1].stage)
            assertTrue(queue.entries().isEmpty())
        }
    }
    private fun document(vararg text: String) = OcrDocument(800, 1000, text.mapIndexed { i, value -> OcrLine(value, 20, 10 + 80 * i, 700, 60 + 80 * i) })
    private suspend fun await(condition: () -> Boolean) { withTimeout(5000) { while (!condition()) delay(10) } }
    private suspend fun withLedger(block: suspend (LedgerDatabase, CatalogRepository, RecognitionQueue) -> Unit) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try { val catalog = CatalogRepository(db); catalog.ensureLocalDefaults(); block(db, catalog, RecognitionQueue(db)) }
        finally { db.close() }
    }
}
