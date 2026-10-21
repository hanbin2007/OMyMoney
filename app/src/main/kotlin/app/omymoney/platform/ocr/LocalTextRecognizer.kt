package app.omymoney.platform.ocr

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import app.omymoney.data.openCancellableInput
import app.omymoney.core.recognition.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OcrException(val code: String, cause: Throwable? = null) : Exception(code, cause)
class LocalTextRecognizer(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.applicationContext.cacheDir, "ocr")

    suspend fun recognize(uri: Uri, rotationDegrees: Int = 0): OcrDocument = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") throw OcrException("unsupported_uri")
        if (rotationDegrees !in setOf(0, 90, 180, 270)) throw OcrException("invalid_rotation")
        permits.acquire()
        var bitmap: Bitmap? = null
        var client: TextRecognizer? = null
        var transferred = false
        val temporary = File(directory, "${UUID.randomUUID()}.image")
        try {
            if (!directory.exists() && !directory.mkdirs()) throw OcrException("storage")
            resolver.openCancellableInput(uri).use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.readCancellable(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_BYTES) throw OcrException("size_limit")
                    output.write(buffer, 0, count)
                }
            } }
            currentCoroutineContext().ensureActive()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType !in setOf("image/png", "image/jpeg", "image/webp")) throw OcrException("unsupported_image")
            if (bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS || bounds.outWidth > 16384 || bounds.outHeight > 16384) throw OcrException("image_too_large")
            var sample = 1
            while (bounds.outWidth / sample > 3072 || bounds.outHeight / sample > 3072) sample *= 2
            bitmap = BitmapFactory.decodeFile(temporary.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                ?: throw OcrException("unsupported_image")
            val orientation = runCatching { ExifInterface(temporary.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                }
                postRotate(rotationDegrees.toFloat())
            }
            if (!matrix.isIdentity) {
                val original = requireNotNull(bitmap)
                bitmap = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
                if (bitmap !== original) original.recycle()
            }
            currentCoroutineContext().ensureActive()
            val image = requireNotNull(bitmap)
            val width = image.width
            val height = image.height
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            client = recognizer
            val task = recognizer.process(InputImage.fromBitmap(image, 0))
            transferred = true
            // ML Kit's task cannot be cancelled. Its completion owns the image and permit
            // even if the caller has already left, so native code never sees a recycled bitmap.
            val result = suspendCancellableCoroutine<Text> { continuation ->
                task.addOnCompleteListener(Executor(Runnable::run)) { completed ->
                    runCatching { recognizer.close() }
                    image.recycle()
                    permits.release()
                    if (continuation.isActive) {
                        if (completed.isSuccessful) continuation.resume(completed.result)
                        else continuation.resumeWithException(OcrException("recognition_failed", completed.exception))
                    }
                }
            }
            val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                line.boundingBox?.let { bounds -> OcrLine(line.text, bounds.left.coerceIn(0, width), bounds.top.coerceIn(0, height),
                    bounds.right.coerceIn(0, width), bounds.bottom.coerceIn(0, height)) }
            }.filter { it.right > it.left && it.bottom > it.top }
            if (lines.size > 1024 || lines.any { it.text.length > 4096 } || lines.sumOf { it.text.length.toLong() } > 65536) throw OcrException("text_limit")
            OcrDocument(width, height, lines)
        } catch (error: CancellationException) { throw error }
        catch (error: OcrException) { throw error }
        catch (error: Exception) { throw OcrException("unreadable", error) }
        finally {
            temporary.delete()
            if (!transferred) { runCatching { client?.close() }; bitmap?.recycle(); permits.release() }
        }
    }

    private suspend fun InputStream.readCancellable(buffer: ByteArray): Int = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { close() } }
        continuation.resumeWith(runCatching { read(buffer) })
    }

    companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
        const val MAX_PIXELS = 32_000_000L
        private val permits = Semaphore(1)
    }
}
