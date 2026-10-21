package app.omymoney

import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.core.diagnostics.recordSafely
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveReceiverAcceptanceTest {
    @Test fun signedReleaseUploadsRedactedBatchesThroughProductionTls(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveReceiver") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = ApplicationProvider.getApplicationContext<OMyMoneyApplication>()
        assertEquals("This acceptance must use the signed release", 0, application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val manager = application.container.diagnostics
        val code = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "cat /data/local/tmp/omymoney-qa-pair-code")).bufferedReader().use { it.readText().trim() }
        val marker = UUID.randomUUID().toString()
        manager.disconnect()
        manager.setEnabled(true)
        manager.recordSafely("production_roundtrip", buildJsonObject {
            put("marker", marker); put("amount", "12.34"); put("password", "MUST_NOT_LEAVE_DEVICE")
            put("token", "MUST_NOT_LEAVE_DEVICE_TOKEN")
        })
        manager.setEnabled(false)
        val bytes = ByteArrayOutputStream()
        manager.export(bytes)
        val batches = buildJsonArray {
            ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name.endsWith(".ndjson.gz")) {
                        val payload = zip.readBytes()
                        add(buildJsonObject {
                            put("id", entry.name.removeSuffix(".ndjson.gz"))
                            put("sha256", MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) })
                            put("bytes", payload.size)
                        })
                    }
                }
            }
        }
        manager.pair(code)
        assertTrue(manager.state.value.paired)
        assertTrue(manager.upload())
        assertEquals(0, manager.state.value.pendingBatches)
        assertTrue(manager.state.value.lastUpload > 0)
        val device = application.getSharedPreferences("diagnostics", 0).getString("device_id", null)
        val evidence = File(application.filesDir, "qa/remote/live-upload.json").apply { parentFile!!.mkdirs() }
        evidence.writeText(buildJsonObject { put("deviceId", device); put("marker", marker); put("batches", batches) }.toString())
        manager.disconnect()
    }
}
