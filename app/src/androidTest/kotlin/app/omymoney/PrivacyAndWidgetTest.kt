package app.omymoney

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.platform.security.*
import app.omymoney.ui.theme.LedgerTheme
import app.omymoney.ui.theme.ThemeMode
import app.omymoney.platform.widget.WidgetSummary
import app.omymoney.core.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PrivacyAndWidgetTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun preferences(): PrivacyPreferences = PrivacyPreferences(context.getSharedPreferences("privacy-test-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE))
    private class Auth(var available: Boolean = true) : DeviceAuthentication {
        var result: ((AuthenticationResult) -> Unit)? = null
        var cancelled = false
        override fun available(activity: ComponentActivity) = available
        override fun authenticate(activity: ComponentActivity, result: (AuthenticationResult) -> Unit): () -> Unit {
            this.result = result
            return { cancelled = true }
        }
    }

    @Test fun cancellingAuthenticationNeverExposesContentOrUnlocks() {
        val prefs = preferences().apply { setLockEnabled(true) }
        val auth = Auth()
        val lock = PrivacyLock(prefs, auth)
        lateinit var activity: ComponentActivity
        compose.setContent {
            activity = LocalActivity.current as ComponentActivity
            LedgerTheme(ThemeMode.SYSTEM, false) { PrivacyGate(lock) { BasicText("Private balance 123.45") } }
        }
        compose.onNodeWithText("Private balance 123.45").assertDoesNotExist()
        compose.onNodeWithTag("privacy_unlock").performClick()
        compose.runOnIdle { auth.result!!(AuthenticationResult.CANCELLED) }
        compose.onNodeWithText(context.getString(app.omymoney.android.R.string.privacy_cancelled)).assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.LiveRegion, androidx.compose.ui.semantics.LiveRegionMode.Polite))
        compose.onNodeWithText("Private balance 123.45").assertDoesNotExist()
        assertTrue(lock.state.value.locked)
        compose.waitForIdle(); captureUiEvidence("c8-locked.png")
        compose.runOnIdle { lock.requestUnlock(activity); auth.result!!(AuthenticationResult.SUCCESS) }
        compose.onNodeWithText("Private balance 123.45").assertIsDisplayed()
        compose.runOnIdle { lock.lock() }
        compose.onNodeWithText("Private balance 123.45").assertDoesNotExist()
        assertFalse(lock.allowOverlay)
    }

    @Test fun screenLockInvalidatesInFlightSuccessAndRestartAlwaysRelocks() {
        val prefs = preferences().apply { setLockEnabled(true) }
        val auth = Auth()
        val lock = PrivacyLock(prefs, auth)
        lateinit var activity: ComponentActivity
        compose.setContent { activity = LocalActivity.current as ComponentActivity; LedgerTheme(ThemeMode.SYSTEM, false) { PrivacyGate(lock) {} } }
        compose.runOnIdle {
            lock.requestUnlock(activity)
            val stale = auth.result!!
            lock.lock(cancelAuthentication = true)
            stale(AuthenticationResult.SUCCESS)
            assertTrue(lock.state.value.locked)
            assertTrue(auth.cancelled)
            lock.requestUnlock(activity)
            auth.result!!(AuthenticationResult.SUCCESS)
            assertFalse(lock.state.value.locked)
            assertTrue(PrivacyLock(prefs, Auth()).state.value.locked)
        }
    }

    @Test fun enablingWithoutSecureDeviceIsRefusedAndDisablingRequiresAuthentication() {
        val prefs = preferences()
        val auth = Auth(false)
        val lock = PrivacyLock(prefs, auth)
        lateinit var activity: ComponentActivity
        compose.setContent { activity = LocalActivity.current as ComponentActivity; LedgerTheme(ThemeMode.SYSTEM, false) { PrivacyGate(lock) {} } }
        compose.runOnIdle {
            lock.setEnabled(activity, true)
            assertFalse(lock.state.value.enabled)
            assertEquals(LockError.NO_CREDENTIAL, lock.state.value.error)
            auth.available = true
            lock.setEnabled(activity, true)
            auth.result!!(AuthenticationResult.SUCCESS)
            assertTrue(prefs.state.value.lockEnabled)
            assertFalse("Privacy mode must also cover the pause-to-stop overlay window", lock.allowOverlay)
            lock.setEnabled(activity, false)
            auth.result!!(AuthenticationResult.CANCELLED)
            assertTrue(lock.state.value.enabled)
            lock.setEnabled(activity, false)
            auth.result!!(AuthenticationResult.SUCCESS)
            assertFalse(lock.state.value.enabled)
        }
    }

    @Test fun themeAndSecureScreenOptionsSurvivePreferenceReopening() {
        val name = "appearance-test-${java.util.UUID.randomUUID()}"
        val prefs = PrivacyPreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE))
        assertTrue(prefs.setTheme(ThemeMode.DARK))
        assertTrue(prefs.setDynamicColor(false))
        assertTrue(prefs.setSecureScreens(true))
        val reopened = PrivacyPreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE))
        assertEquals(ThemeMode.DARK, reopened.state.value.theme)
        assertFalse(reopened.state.value.dynamicColor)
        assertTrue(reopened.state.value.secureScreens)
    }

    @Test fun widgetUsesOnlyCommittedBillsAndHidesMoneyAndBookOnPrivateHosts() = runBlocking {
        val store = (context.applicationContext as OMyMoneyApplication).container.store
        store.catalog.ensureLocalDefaults()
        val book = Book(java.util.UUID.randomUUID().toString(), "Private widget book", zoneId = java.time.ZoneOffset.UTC)
        val account = AssetAccount(java.util.UUID.randomUUID().toString(), "Widget cash")
        store.catalog.saveBook(book); store.catalog.saveAccount(account)
        val draft = TransactionDraft(book.id, kind = BillKind.EXPENSE, amount = Money(4321, CurrencySpec.CNY),
            fromAccountId = account.id, occurredAt = java.time.Instant.now(), zoneId = book.zoneId, timeSource = TimeSource.USER)
        fun summary(bills: List<Bill>, privacy: Boolean = false, host: Int = android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) =
            WidgetSummary.create(context, book, bills, emptyList(), privacy, host, java.time.Instant.now())
        store.recognitionQueue.enqueue(app.omymoney.core.recognition.RecognitionResult.Matched(draft, emptyList(), "widget-test"))
        assertEquals("CNY 0.00", summary(store.ledger.observeBills(BillQuery(book.id)).first()).expense)
        val saved = store.ledger.save(draft, null)
        assertTrue(saved is SaveResult.Saved)
        val bills = store.ledger.observeBills(BillQuery(bookId = book.id)).first()
        assertEquals("CNY 43.21", summary(bills).expense)
        for (hidden in listOf(summary(bills, privacy = true), summary(bills, host = android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD))) {
            assertTrue(hidden.hidden)
            assertFalse(hidden.toString().contains("43.21"))
            assertFalse(hidden.toString().contains(book.name))
        }
        val foreign = summary(bills.map { it.copy(amount = Money(100, CurrencySpec("USD", 2))) })
        assertTrue(foreign.expense.contains("USD"))
        assertFalse(foreign.expense.contains("0.00"))
        store.ledger.delete((saved as SaveResult.Saved).billId)
        assertEquals("CNY 0.00", summary(store.ledger.observeBills(BillQuery(book.id)).first()).expense)
    }

    @Test fun widgetMissingCurrencyAndArchivedBookNeverPretendToBeZero() {
        val book = Book(java.util.UUID.randomUUID().toString(), "Archived", archived = true)
        val model = WidgetSummary.create(context, book, emptyList(), emptyList(), false,
            android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, java.time.Instant.now())
        assertEquals(context.getString(app.omymoney.android.R.string.widget_book_unavailable), model.status)
        assertEquals("", model.expense)
    }
}
