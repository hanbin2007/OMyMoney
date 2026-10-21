package app.omymoney

import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.diagnostics.recordSafely
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DiagnosticRuntimeTest {
    @Test fun enablingDiagnosticsIncludesRuntimeAndExitCoverageWithoutProtectedDetails(): Unit = runBlocking {
        val manager = ApplicationProvider.getApplicationContext<OMyMoneyApplication>().container.diagnostics
        manager.setEnabled(true)
        try {
            val output = ByteArrayOutputStream()
            manager.export(output)
            val records = buildString {
                ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.name.endsWith(".gz")) append(GZIPInputStream(zip.readBytes().inputStream()).bufferedReader().readText())
                    }
                }
            }
            assertTrue("Missing permission/power/queue runtime evidence", records.contains("\"kind\":\"runtime_snapshot\""))
            assertTrue(records.contains("accessibilityGranted"))
            assertTrue(records.contains("accessibilitySettingReadable"))
            assertTrue(records.contains("accessibilitySystemBound"))
            assertTrue(records.contains("accessibilityCallbackConnected"))
            val snapshots = records.lineSequence().filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }
                .filter { it["kind"]?.jsonPrimitive?.content == "runtime_snapshot" }.toList()
            val counts = snapshots.mapNotNull { it["fields"]?.jsonObject?.get("notificationCounts")?.jsonObject }.last()
            assertEquals(setOf("received", "candidates", "filtered", "dropped", "suppressed", "failed"), counts.keys)
            assertTrue(counts.values.all { it.jsonPrimitive.long >= 0 })
            assertTrue(records.contains("batteryOptimizationExempt"))
            assertTrue("Missing bounded historical exit coverage", records.contains("\"kind\":\"process_exit_coverage\""))
            assertFalse(records.contains("traceInputStream"))
        } finally { manager.setEnabled(false) }
    }

    @Test fun optInControlsRecordingAndExportNeverContainsPairingOrPasswordValues(): Unit = runBlocking {
        val manager = ApplicationProvider.getApplicationContext<OMyMoneyApplication>().container.diagnostics
        manager.setEnabled(false)
        manager.recordSafely("must_not_record", buildJsonObject { put("text", "DISABLED_SENTINEL") })
        manager.setEnabled(true)
        try {
            manager.recordSafely("test_payment", buildJsonObject {
                put("amount", "12.30"); put("password", "PASSWORD_SENTINEL"); put("token", "TOKEN_SENTINEL")
            })
            val output = ByteArrayOutputStream()
            manager.export(output)
            val records = StringBuilder()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name.endsWith(".gz")) records.append(GZIPInputStream(zip.readBytes().inputStream()).bufferedReader().readText())
                }
            }
            assertTrue(records.contains("12.30"))
            assertFalse(records.contains("DISABLED_SENTINEL"))
            assertFalse(records.contains("PASSWORD_SENTINEL"))
            assertFalse(records.contains("TOKEN_SENTINEL"))
        } finally { manager.setEnabled(false) }
    }

    @Test fun additionalPackagesAreValidatedWithoutChangingRecognitionRules(): Unit = runBlocking {
        val manager = ApplicationProvider.getApplicationContext<OMyMoneyApplication>().container.diagnostics
        manager.setExtraPackages(setOf("com.taobao.taobao"))
        assertTrue("com.taobao.taobao" in manager.state.value.packages)
        assertFalse("com.taobao.taobao" in app.omymoney.core.recognition.RuleRegistry.supportedPackages)
        try { manager.setExtraPackages(setOf("not a package")); fail("invalid package accepted") }
        catch (_: IllegalArgumentException) { }
        manager.setExtraPackages(emptySet())
    }
}
