package app.omymoney

import android.content.*
import android.database.Cursor
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.platform.ocr.*
import app.omymoney.core.recognition.*
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ScreenshotImportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val recognizer = LocalTextRecognizer(context)
    @Test fun bundledChineseModelReadsReceiptWithoutNetworkPermission(): Unit = runBlocking {
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertFalse(android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted)
        if (InstrumentationRegistry.getArguments().getString("offlineOcrDeviceTest") == "true") {
            assertEquals(1, android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON))
            instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.ACCESS_NETWORK_STATE)
            try { assertNull(context.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork) }
            finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        }
        val document = recognizer.recognize(fixture("receipt.png", receipt()))
        assertTrue(document.lines.any { it.text.replace(" ", "").contains("支付成功") })
        assertTrue(document.lines.any { it.text.contains("12.30") })
        val parsed = ScreenshotRules.recognize(document)
        val amount = when (parsed) { is RecognitionResult.Matched -> parsed.draft.amount; is RecognitionResult.NeedsReview -> parsed.partialDraft.amount; else -> null }
        assertEquals(document.lines.toString(), 1230L, amount?.minor)
        assertTrue(document.lines.all { it.right <= document.width && it.bottom <= document.height })
        assertTrue(ocrTemporaryFiles().isEmpty())
    }
    @Test fun blankImageInvalidUriAndOversizedImageHaveExplicitOutcomes(): Unit = runBlocking {
        val blank = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        assertTrue(recognizer.recognize(fixture("blank.png", blank)).lines.isEmpty())
        assertOcrError("unreadable", Uri.parse("content://app.omymoney.android.test.ocr/missing.png"))
        assertOcrError("unsupported_uri", Uri.parse("https://example.invalid/receipt.png"))
        val huge = Bitmap.createBitmap(9000, 5000, Bitmap.Config.RGB_565)
        assertOcrError("image_too_large", fixture("large.png", huge))
        assertTrue(ocrTemporaryFiles().isEmpty())
    }
    @Test fun exifRotationIsRespectedBeforeRecognition(): Unit = runBlocking {
        val upright = receipt()
        val turned = Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height, Matrix().apply { postRotate(90f) }, true)
        upright.recycle()
        val uri = fixture("rotated.jpg", turned, Bitmap.CompressFormat.JPEG)
        context.contentResolver.openFileDescriptor(uri, "rw")!!.use { file -> ExifInterface(file.fileDescriptor).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_270.toString()); saveAttributes()
        } }
        val doc = recognizer.recognize(uri)
        assertEquals(800, doc.width)
        assertTrue(doc.lines.any { it.text.replace(" ", "").contains("支付成功") })
        assertTrue(ocrTemporaryFiles().isEmpty())
    }
    @Test fun cancellationClosesBlockedProviderAndRemovesTemporaryImage(): Unit = runBlocking {
        val uri = Uri.parse("content://app.omymoney.android.test.ocr/blocked")
        val job = launch(Dispatchers.Default) { recognizer.recognize(uri) }
        try {
            withTimeout(3000) { while (context.contentResolver.call(uri, "opened", null, null)?.getBoolean("opened") != true) delay(10) }
            withTimeout(3000) { job.cancelAndJoin() }
            assertTrue(ocrTemporaryFiles().isEmpty())
        } finally { context.contentResolver.call(uri, "close", null, null); job.cancel() }
    }
    private suspend fun assertOcrError(code: String, uri: Uri) {
        val error = runCatching { recognizer.recognize(uri) }.exceptionOrNull()
        assertTrue(error.toString(), error is OcrException)
        assertEquals(code, (error as OcrException).code)
    }
    private fun ocrTemporaryFiles() = File(context.cacheDir, "ocr").listFiles().orEmpty().toList()
    private fun receipt() = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this); canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f }
        listOf("微信支付", "支付成功", "实付金额 12.30", "支付时间", "2026-10-11 19:59:30").forEachIndexed { i, text -> canvas.drawText(text, 40f, 120f + 140f * i, paint) }
    }
    private fun fixture(name: String, bitmap: Bitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): Uri {
        val uri = Uri.parse("content://app.omymoney.android.test.ocr/$name")
        try { context.contentResolver.openOutputStream(uri)!!.use { assertTrue(bitmap.compress(format, 100, it)) } }
        finally { bitmap.recycle() }
        return uri
    }
}
