package app.omymoney

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.core.model.Book
import app.omymoney.core.model.BillQuery
import app.omymoney.core.model.Money
import app.omymoney.core.model.CurrencySpec
import app.omymoney.core.model.SaveResult
import app.omymoney.data.EditorDraft
import app.omymoney.data.LedgerJson
import app.omymoney.data.QueueState
import app.omymoney.core.model.TransactionDraft
import app.omymoney.core.recognition.*
import app.omymoney.core.recognition.rules.AlipayRules
import app.omymoney.platform.updates.version
import app.omymoney.ui.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in, isolated emulator only: prepare a real signed update, then verify after Android replaces the package. */
class UpdateAcceptanceTest {
    @Test fun sameKeySystemUpgradePreservesLocalState(): Unit = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("upgradePhase")
        assumeTrue(phase in setOf("prepare", "verify"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = ApplicationProvider.getApplicationContext<OMyMoneyApplication>()
        val container = application.container
        val preferences = application.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
        val arguments = InstrumentationRegistry.getArguments()
        val fromVersion = arguments.getString("upgradeFromVersion", "4").toLong()
        val toVersion = arguments.getString("upgradeToVersion", "5").toLong()
        val bookId = "86ee80b2-01f8-4ee3-8ef7-353e87d184c3"
        assertTrue(toVersion > fromVersion)
        if (phase == "prepare") {
            assertEquals(fromVersion, application.packageManager.getPackageInfo(application.packageName, 0).version())
            container.updates.settings(false, false)
            container.store.catalog.ensureLocalDefaults()
            container.store.catalog.saveBook(Book(bookId, "Upgrade acceptance"))
            // JSON keeps this instrumentation compatible with the previous APK's model constructors.
            val fields = LedgerJson.format.encodeToJsonElement(container.store.catalog.initialDraft(bookId)).jsonObject.toMutableMap()
            fields["note"] = JsonPrimitive("Retain raw draft across APK replacement")
            val transaction = LedgerJson.format.decodeFromJsonElement<TransactionDraft>(JsonObject(fields))
            fields["amount"] = LedgerJson.format.encodeToJsonElement(Money(1234, CurrencySpec.CNY))
            val billDraft = LedgerJson.format.decodeFromJsonElement<TransactionDraft>(JsonObject(fields))
            assertTrue(container.store.ledger.save(billDraft, null) is SaveResult.Saved)
            container.store.drafts.write(EditorDraft("upgrade-draft", transaction, amountText = "7..25", dirty = true))
            val recognition = RuleEngine(AlipayRules.create(RecognitionContext(bookId))).recognize(UiSnapshot(AlipayRules.PACKAGE, 91,
                java.time.Instant.now(), listOf("支付成功0.01", "完成").map { UiNode(it) }))
            val pending = container.store.recognitionQueue.enqueue(recognition)
            assertEquals(QueueState.PENDING, pending.state)
            assertTrue(preferences.edit().putString("upgrade_pending_entry", pending.id).commit())
            assertTrue(container.preferences.setTheme(ThemeMode.DARK))
            assertTrue(container.preferences.setDynamicColor(false))
            assertTrue(container.preferences.setSecureScreens(true))
            container.diagnostics.setExtraPackages(setOf("com.taobao.taobao"))
            container.diagnostics.setEnabled(false)
            if (InstrumentationRegistry.getArguments().getString("upgradeNetwork") == "true") return@runBlocking
            val manifest = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "cat /data/local/tmp/omymoney-qa-update.json")).bufferedReader().use { it.readText() }
            val signed = Json.parseToJsonElement(manifest).jsonObject.getValue("payload").jsonPrimitive.content
            val payload = Json.parseToJsonElement(String(java.util.Base64.getDecoder().decode(signed))).jsonObject
            val sha = payload.getValue("apkSha256").jsonPrimitive.content
            val directory = File(application.noBackupFilesDir, "updates").apply { mkdirs() }
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "cat /data/local/tmp/omymoney-qa-update.apk")).use { input ->
                File(directory, "$sha.apk").outputStream().use { input.copyTo(it) }
            }
            assertTrue(preferences.edit().putString("manifest", manifest).commit())
        } else {
            assertEquals(toVersion, application.packageManager.getPackageInfo(application.packageName, 0).version())
            // The installer callback can launch the new package before instrumentation.
            // Its next process may be idle already; persisted cleanup is the contract.
            withTimeout(10_000) {
                while (preferences.contains("install_target") || preferences.contains("install_session")) delay(20)
            }
            assertFalse("Install target must be cleared only by observing the new installed version", preferences.contains("install_target"))
            assertFalse(preferences.contains("install_session"))
            assertTrue(container.store.catalog.observeBooks().first().any { it.id == bookId && it.name == "Upgrade acceptance" })
            val bills = container.store.ledger.observeBills(BillQuery(bookId = bookId)).first()
            assertEquals(1, bills.size)
            assertEquals(Money(1234, CurrencySpec.CNY), bills.single().amount)
            val draft = requireNotNull(container.store.drafts.read("upgrade-draft"))
            assertEquals("7..25", draft.amountText)
            assertEquals("Retain raw draft across APK replacement", draft.transaction.note)
            assertTrue(draft.dirty)
            val pending = container.store.recognitionQueue.read(requireNotNull(preferences.getString("upgrade_pending_entry", null)))
            assertEquals(QueueState.PENDING, pending?.state)
            assertEquals(Money(1, CurrencySpec.CNY), pending?.draft?.amount)
            assertNull(pending?.billId)
            assertEquals(ThemeMode.DARK, container.preferences.state.value.theme)
            assertFalse(container.preferences.state.value.dynamicColor)
            assertTrue(container.preferences.state.value.secureScreens)
            assertTrue("com.taobao.taobao" in container.diagnostics.state.value.packages)
            assertFalse(container.diagnostics.state.value.enabled)
        }
    }
}
