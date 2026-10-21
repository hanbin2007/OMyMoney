package app.omymoney.data

import android.content.res.AssetFileDescriptor
import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.File
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CancellableInputTest {
    @Test fun aDeclaredAssetBoundaryReturnsEofWithoutWaitingForAnOpenWriter() = runBlocking {
        val pipe = ParcelFileDescriptor.createPipe()
        val writer = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        try {
            writer.write(byteArrayOf(10, 20, 30))
            val asset = AssetFileDescriptor(pipe[0], 0, 2)
            asset.createInputStream().cancellable(currentCoroutineContext(), asset.declaredLength).use { input ->
                assertEquals(10, input.read())
                assertEquals(20, input.read())
                assertEquals(-1, input.read())
                assertEquals(0, input.read(ByteArray(0)))
            }
        } finally { writer.close(); pipe[0].close() }
    }

    @Test fun fileSlicesAndGenericMarkResetRetainTheOriginalStreamContract() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val file = File.createTempFile("bounded-input", ".bin", context.cacheDir)
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            val asset = AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 1, 3)
            asset.createInputStream().cancellable(currentCoroutineContext(), asset.declaredLength).use { input ->
                assertEquals(1L, input.skip(1))
                assertArrayEquals(byteArrayOf(3, 4), input.readBytes())
            }
            ByteArrayInputStream(byteArrayOf(6, 7)).cancellable(currentCoroutineContext(), 2).use { input ->
                input.mark(2)
                assertEquals(6, input.read())
                input.reset()
                assertArrayEquals(byteArrayOf(6, 7), input.readBytes())
            }
        } finally { file.delete() }
    }
}
