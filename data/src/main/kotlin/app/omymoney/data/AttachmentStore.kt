package app.omymoney.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.omymoney.core.model.AttachmentRef
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class AttachmentException(val code: String, cause: Throwable? = null) : Exception(code, cause)

class AttachmentStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.applicationContext.filesDir, "attachments")

    suspend fun `import`(uri: Uri): AttachmentRef = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") throw AttachmentException("unsupported_uri")
        try {
            val input = resolver.openCancellableInput(uri)
            input.use { importImage(it) }
        } catch (error: CancellationException) { throw error
        } catch (error: AttachmentException) { throw error
        } catch (error: Exception) { throw AttachmentException("unreadable", error) }
    }

    internal suspend fun importImage(input: InputStream): AttachmentRef = withContext(Dispatchers.IO) {
        if (!directory.exists() && !directory.mkdirs()) throw AttachmentException("storage")
        val id = UUID.randomUUID().toString()
        val temporary = File(directory, "$id.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            input.cancellable(currentCoroutineContext()).use { source -> temporary.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.readCancellable(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_BYTES) throw AttachmentException("size_limit")
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
                output.fd.sync()
            } }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, options)
            val mime = options.outMimeType
            val extension = EXTENSIONS[mime] ?: throw AttachmentException("unsupported_image")
            if (options.outWidth <= 0 || options.outHeight <= 0) throw AttachmentException("unsupported_image")
            if (options.outWidth.toLong() * options.outHeight > MAX_PIXELS) throw AttachmentException("image_too_large")
            val target = File(directory, "$id.$extension")
            if (!temporary.renameTo(target)) throw AttachmentException("storage")
            AttachmentRef(id, "attachments/${target.name}", mime, digest.digest().hex())
        } catch (error: CancellationException) { throw error
        } catch (error: AttachmentException) { throw error
        } catch (error: Exception) { throw AttachmentException("unreadable", error)
        } finally { temporary.delete() }
    }

    suspend fun verifiedFile(ref: AttachmentRef): File = withContext(Dispatchers.IO) {
        val extension = EXTENSIONS[ref.mimeType] ?: throw AttachmentException("unsafe_path")
        if (!UUID_PATTERN.matches(ref.id) || ref.relativePath != "attachments/${ref.id}.$extension" || !HASH_PATTERN.matches(ref.sha256)) {
            throw AttachmentException("unsafe_path")
        }
        val file = File(directory, "${ref.id}.$extension")
        if (file.canonicalFile.parentFile != directory.canonicalFile) throw AttachmentException("unsafe_path")
        if (!file.isFile) throw AttachmentException("missing")
        if (file.length() > MAX_BYTES) throw AttachmentException("corrupt")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (digest.digest().hex() != ref.sha256) throw AttachmentException("corrupt")
        file
    }

    suspend fun preview(ref: AttachmentRef, maxSize: Int = 1024): Bitmap = withContext(Dispatchers.IO) {
        require(maxSize in 64..2048)
        val file = verifiedFile(ref)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) {
            throw AttachmentException("corrupt")
        }
        var sample = 1
        while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw AttachmentException("corrupt")
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

    // Cancellation must close the provider pipe even while read is waiting for bytes.
    private suspend fun InputStream.readCancellable(buffer: ByteArray): Int = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { close() } }
        continuation.resumeWith(runCatching { read(buffer) })
    }

    companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
        const val MAX_PIXELS = 64_000_000L
        private val EXTENSIONS = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp", "image/gif" to "gif")
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val HASH_PATTERN = Regex("[0-9a-f]{64}")
    }
}
