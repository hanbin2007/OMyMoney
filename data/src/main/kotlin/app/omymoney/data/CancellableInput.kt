package app.omymoney.data

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

suspend fun ContentResolver.openCancellableInput(uri: Uri): InputStream {
    val context = currentCoroutineContext()
    val descriptor = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        try {
            val opened = openAssetFileDescriptor(uri, "r", signal) ?: throw IOException("Source is unavailable")
            continuation.resume(opened) { _, value, _ -> value.close() }
        } catch (error: Exception) { continuation.resumeWithException(error) }
    }
    return try {
        descriptor.createInputStream().cancellable(context, descriptor.declaredLength.takeIf { it >= 0 })
    } catch (error: Exception) { descriptor.close(); throw error }
}

fun InputStream.cancellable(context: CoroutineContext, length: Long? = null): InputStream =
    CancellableInput(this, context, length)

private class CancellableInput(source: InputStream, private val context: CoroutineContext, private var remaining: Long?) : FilterInputStream(source) {
    private val descriptor = (source as? FileInputStream)?.fd
    private var markedRemaining: Long? = remaining
    init { require(remaining == null || remaining!! >= 0) }

    override fun read(): Int {
        val byte = ByteArray(1)
        var count: Int
        do { count = read(byte, 0, 1) } while (count == 0)
        return if (count < 0) -1 else byte[0].toInt() and 255
    }
    override fun read(buffer: ByteArray): Int = read(buffer, 0, buffer.size)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || length > buffer.size - offset) throw IndexOutOfBoundsException()
        context.ensureActive()
        if (length == 0) return 0
        if (remaining == 0L) return -1
        awaitReadable()
        val count = `in`.read(buffer, offset, remaining?.coerceAtMost(length.toLong())?.toInt() ?: length)
        if (count > 0) remaining = remaining?.minus(count)
        if (count < 0) remaining = 0
        return count
    }
    private fun awaitReadable() {
        val fd = descriptor ?: return
        val poll = StructPollfd().apply { this.fd = fd; events = (OsConstants.POLLIN or OsConstants.POLLHUP or OsConstants.POLLERR).toShort() }
        // Closing a blocked pipe does not interrupt its reader on older Android releases.
        while (true) {
            context.ensureActive()
            val ready = try { Os.poll(arrayOf(poll), 100) }
            catch (error: ErrnoException) {
                context.ensureActive()
                if (error.errno == OsConstants.EINTR) continue else throw IOException(error)
            }
            context.ensureActive()
            if (poll.revents.toInt() and OsConstants.POLLNVAL != 0) throw IOException("Source is closed")
            if (ready > 0) return
        }
    }
    override fun skip(count: Long): Long {
        var left = count.coerceAtLeast(0)
        val buffer = ByteArray(minOf(left, 8192).toInt())
        while (left > 0) {
            val read = read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (read < 0) break
            left -= read
        }
        return count.coerceAtLeast(0) - left
    }
    override fun mark(readlimit: Int) { `in`.mark(readlimit); markedRemaining = remaining }
    override fun reset() { `in`.reset(); remaining = markedRemaining }
    override fun available(): Int = remaining?.coerceAtMost(`in`.available().toLong())?.toInt() ?: `in`.available()
}
