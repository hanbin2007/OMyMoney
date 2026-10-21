package app.omymoney.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.omymoney.core.model.AttachmentRef
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = AttachmentStore(context)
    private val files = mutableListOf<File>()
    @After fun cleanup() { files.forEach { it.delete() } }

    @Test fun importedImageIsPrivateVerifiedAndReadableAfterStoreRecreation() = runBlocking {
        val bytes = imageBytes()
        val ref = store.importImage(ByteArrayInputStream(bytes))
        val file = store.verifiedFile(ref).also(files::add)
        assertTrue(file.canonicalPath.startsWith(File(context.filesDir, "attachments").canonicalPath + "/"))
        assertArrayEquals(bytes, file.readBytes())
        assertEquals("image/png", ref.mimeType)
        assertEquals(file.canonicalPath, AttachmentStore(context).verifiedFile(ref).canonicalPath)
        val preview = store.preview(ref)
        assertEquals(24, preview.width)
        assertEquals(Color.rgb(26, 140, 90), preview.getPixel(10, 10))
        preview.recycle()
    }

    @Test fun unreadableContentAndNonImagesAreRejectedWithoutACompletedFile() = runBlocking {
        expect("unreadable") { store.`import`(Uri.parse("content://nonexistent.omymoney.test/image")) }
        expect("unsupported_image") { store.importImage(ByteArrayInputStream("not an image".toByteArray())) }
        expect("unsupported_uri") { store.`import`(Uri.parse("https://example.invalid/image.png")) }
    }

    @Test fun anImageHeaderWithoutDecodablePixelsIsNotAccepted() = runBlocking {
        expect("unsupported_image") { store.importImage(ByteArrayInputStream(imageBytes().copyOf(40))) }
    }

    @Test fun pathTraversalAndTamperedAttachmentsAreRejected() = runBlocking {
        val ref = store.importImage(ByteArrayInputStream(imageBytes()))
        val file = store.verifiedFile(ref).also(files::add)
        expect("unsafe_path") { store.verifiedFile(ref.copy(relativePath = "../omymoney.db")) }
        expect("unsafe_path") { store.verifiedFile(ref.copy(relativePath = file.absolutePath)) }
        expect("unsafe_path") { store.verifiedFile(ref.copy(id = "../escape")) }
        file.appendBytes(byteArrayOf(0))
        expect("corrupt") { store.verifiedFile(ref) }
        file.delete()
        expect("missing") { store.verifiedFile(ref) }
    }

    @Test fun byteLimitStopsAnUnboundedSourceAndRemovesItsPartialFile() = runBlocking {
        val root = File(context.filesDir, "attachments")
        val before = root.list()?.toSet().orEmpty()
        val unbounded = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { buffer.fill(0, offset, offset + length); return length }
        }
        expect("size_limit") { store.importImage(unbounded) }
        assertEquals(before, root.list()?.toSet().orEmpty())
    }

    @Test fun cancellationClosesABlockedSourceAndCleansItsPartialFile() = runBlocking {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val stream = object : InputStream() {
            override fun read(): Int { entered.countDown(); closed.await(); return -1 }
            override fun close() { closed.countDown() }
        }
        val directory = File(context.filesDir, "attachments")
        val before = directory.list()?.toSet().orEmpty()
        val job = launch(Dispatchers.IO) { runCatching { store.importImage(stream) } }
        try {
            withTimeout(2000) { while (entered.count > 0) delay(10) }
            job.cancel()
            assertTrue("Cancellation must close the source rather than wait for another byte", withTimeoutOrNull(2000) { job.join(); true } ?: false)
            assertEquals(0L, closed.count)
            assertEquals(before, directory.list()?.toSet().orEmpty())
        } finally { stream.close(); job.cancelAndJoin() }
    }

    @Test fun cancellationDoesNotWaitForARealProviderPipeToProduceAnotherByte() = runBlocking {
        val pipe = android.os.ParcelFileDescriptor.createPipe()
        val stream = android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe[0])
        val directory = File(context.filesDir, "attachments")
        val before = directory.list()?.toSet().orEmpty()
        val job = launch(Dispatchers.IO) { runCatching { store.importImage(stream) } }
        try {
            withTimeout(2000) { while (directory.list()?.none { it.endsWith(".part") } != false) delay(10) }
            delay(100)
            job.cancel()
            assertTrue("A stalled pipe must not hold the cancelled import", withTimeoutOrNull(2000) { job.join(); true } ?: false)
            assertEquals(before, directory.list()?.toSet().orEmpty())
        } finally { pipe[1].close(); stream.close(); job.cancelAndJoin() }
    }

    private suspend fun expect(code: String, block: suspend () -> Any) {
        try { block(); fail("Expected $code") } catch (error: AttachmentException) { assertEquals(code, error.code) }
    }
    private fun imageBytes(): ByteArray {
        val image = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.rgb(26, 140, 90))
        val output = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.PNG, 100, output)
        image.recycle()
        return output.toByteArray()
    }
}
