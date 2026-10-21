package app.omymoney

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import app.omymoney.platform.diagnostics.DiagnosticManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticSessionRecoveryTest {
    private class IsolatedContext : ContextWrapper(ApplicationProvider.getApplicationContext<OMyMoneyApplication>()) {
        val id = "debug-recovery-${UUID.randomUUID()}"
        var failCommit = false
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = File(baseContext.cacheDir, id).apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val preferences = baseContext.getSharedPreferences("$id-$name", mode)
            return object : SharedPreferences by preferences {
                override fun edit(): SharedPreferences.Editor {
                    val editor = preferences.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { editor.putBoolean(key, value); return this }
                        override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                        override fun commit(): Boolean = if (failCommit) false else editor.commit()
                    }
                }
            }
        }
    }

    @Test fun failedPreferenceWriteCannotLeaveRecordingEnabled(): Unit = runBlocking {
        val context = IsolatedContext()
        val manager = DiagnosticManager(context)
        manager.setEnabled(true)
        context.failCommit = true
        try { manager.setEnabled(false); fail("Expected storage failure") } catch (_: IllegalStateException) { }
        assertFalse("Stop must disable runtime recording before persisting", manager.enabled)
        assertEquals("storage", manager.state.value.error)
        manager.record("must_not_survive_stop", buildJsonObject {})
        assertFalse(export(manager).contains("must_not_survive_stop"))
    }

    @Test fun interruptedContinuousSessionCannotResumeCaptureWithoutNotification(): Unit = runBlocking {
        val context = IsolatedContext()
        context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true).putBoolean("debug_session_open", true).commit()
        val manager = DiagnosticManager(context)
        assertFalse("Interrupted continuous capture must start off", manager.enabled)
        manager.record("must_not_resume_capture", buildJsonObject {})
        withTimeout(5000) {
            while (!export(manager).contains("debug_session_interrupted")) delay(50)
        }
        assertFalse(export(manager).contains("must_not_resume_capture"))
        assertFalse(context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).getBoolean("enabled", true))
    }

    private suspend fun export(manager: DiagnosticManager): String {
        val output = ByteArrayOutputStream()
        manager.export(output)
        return buildString {
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name.endsWith(".gz")) append(GZIPInputStream(zip.readBytes().inputStream()).bufferedReader().readText())
                }
            }
        }
    }
}
