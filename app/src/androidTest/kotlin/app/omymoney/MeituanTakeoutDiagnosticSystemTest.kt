package app.omymoney

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.core.model.BillQuery
import app.omymoney.core.recognition.*
import app.omymoney.core.recognition.rules.MeituanTakeoutRules
import app.omymoney.platform.accessibility.AccessibilityStatus
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in isolated emulator only, with the explicitly synthetic takeout accessibility fixture. */
class MeituanTakeoutDiagnosticSystemTest {
    @Test fun existingConsentCollectsUnrecognizedTakeoutNodesWithoutBookkeepingAndStopPreventsNewSamples(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("takeoutDiagnosticQa") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val application = ApplicationProvider.getApplicationContext<OMyMoneyApplication>()
        val container = application.container
        val manager = container.diagnostics
        assertFalse("Synthetic diagnostic samples must never upload to production", manager.state.value.paired)
        val component = "${application.packageName}/app.omymoney.platform.accessibility.LedgerAccessibilityService"
        val configured = Settings.Secure.getString(application.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').filter(String::isNotBlank)
        assertTrue("The isolated harness must pre-authorize this service", component in configured)
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        assertTrue("Only the labelled synthetic fixture may be restarted", shell("dumpsys package ${MeituanTakeoutRules.PACKAGE}")
            .contains("versionName=synthetic-diagnostic-qa"))
        // Instrumentation force-stops its target; rebind only the already-authorized service.
        val remaining = configured.filterNot { it == component }.joinToString(":")
        shell(if (remaining.isEmpty()) "settings delete secure enabled_accessibility_services"
            else "settings put secure enabled_accessibility_services $remaining")
        delay(250)
        shell("settings put secure enabled_accessibility_services ${configured.joinToString(":")}")
        assertEquals(configured.joinToString(":"), Settings.Secure.getString(application.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        withTimeout(30_000) { AccessibilityStatus.runtime.first { it.connected } }
        assertTrue(AccessibilityStatus.granted(application))
        val original = container.store.automation.read().settings
        val wasRecording = manager.enabled
        val book = container.store.catalog.recognitionContext().bookId
        val queueBefore = container.store.recognitionQueue.entries().size
        val billsBefore = container.store.ledger.observeBills(BillQuery(bookId = book)).first().size
        val sample = "QA_TAKEOUT_VISIBLE_${UUID.randomUUID().toString().replace('-', '_')}"
        val stoppedSample = "QA_TAKEOUT_STOPPED_${UUID.randomUUID().toString().replace('-', '_')}"

        suspend fun records(): List<JsonObject> {
            val output = ByteArrayOutputStream()
            manager.export(output)
            val records = mutableListOf<JsonObject>()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name.endsWith(".gz")) {
                        GZIPInputStream(zip.readBytes().inputStream()).bufferedReader().useLines { lines ->
                            lines.filter(String::isNotBlank).forEach { records += Json.parseToJsonElement(it).jsonObject }
                        }
                    }
                }
            }
            return records
        }
        @Suppress("DEPRECATION")
        fun shown(value: String): Boolean {
            val root = automation.rootInActiveWindow ?: return false
            return try {
                val matches = root.findAccessibilityNodeInfosByText(value)
                try { matches.any { it.text?.toString() == value } }
                finally { matches.forEach { it.recycle() } }
            } finally { root.recycle() }
        }
        suspend fun launch(value: String) {
            shell("am force-stop ${MeituanTakeoutRules.PACKAGE}")
            val command = "am start -W -n ${MeituanTakeoutRules.PACKAGE}/qa.TakeoutDiagnosticActivity --es sample $value"
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
            withTimeout(5000) { while (!shown(value)) delay(50) }
        }
        try {
            container.store.automation.setEnabled(false)
            manager.setEnabled(true)
            assertTrue(MeituanTakeoutRules.PACKAGE in manager.state.value.packages)
            withTimeout(10_000) {
                while (application.getSystemService(AccessibilityManager::class.java)
                    .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .none { it.resolveInfo.serviceInfo.packageName == application.packageName &&
                        MeituanTakeoutRules.PACKAGE in it.packageNames.orEmpty() }) delay(50)
            }
            assertTrue(RuleEngine(RuleRegistry.enabledRules(setOf(MeituanTakeoutRules.PACKAGE), RecognitionContext(book)))
                .recognize(UiSnapshot(MeituanTakeoutRules.PACKAGE, 1, java.time.Instant.now(), listOf(UiNode(sample)))) is RecognitionResult.Unsupported)
            launch(sample)
            val captured = withTimeout(15_000) {
                var current = records()
                while (current.none { it["kind"]?.jsonPrimitive?.content == "snapshot_nodes" && it.toString().contains(sample) }) {
                    delay(100)
                    current = records()
                }
                current
            }
            val chunk = captured.first { it["kind"]?.jsonPrimitive?.content == "snapshot_nodes" && it.toString().contains(sample) }
                .getValue("fields").jsonObject
            val id = chunk.getValue("snapshotId")
            val snapshot = captured.first { it["kind"]?.jsonPrimitive?.content == "snapshot" &&
                it.getValue("fields").jsonObject["snapshotId"] == id }.getValue("fields").jsonObject
            assertEquals(MeituanTakeoutRules.PACKAGE, snapshot.getValue("package").jsonPrimitive.content)
            assertTrue(snapshot.getValue("source").jsonPrimitive.content.startsWith("diagnostic/"))
            assertFalse(snapshot.getValue("metadataOnly").jsonPrimitive.boolean)
            val nodes = chunk.getValue("nodes").jsonArray.map { it.jsonObject }
            assertTrue(nodes.any { it["text"]?.jsonPrimitive?.content == sample && "parent" in it && "class" in it && "viewId" in it })
            assertEquals(queueBefore, container.store.recognitionQueue.entries().size)
            assertEquals(billsBefore, container.store.ledger.observeBills(BillQuery(bookId = book)).first().size)
            manager.setEnabled(false)
            launch(stoppedSample)
            delay(500)
            assertFalse(records().any { it.toString().contains(stoppedSample) })
        } finally {
            manager.setEnabled(wasRecording)
            container.store.automation.setEnabled(original.enabled)
        }
    }
}
