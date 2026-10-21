package app.omymoney

import android.app.PendingIntent
import android.appwidget.*
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.TextView
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.android.R
import app.omymoney.core.model.Book
import app.omymoney.platform.widget.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class WidgetPinningTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as OMyMoneyApplication).container
    private fun withHost(block: (AppWidgetHost, Int) -> Unit) {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val host = AppWidgetHost(context, 43157)
        val id = host.allocateAppWidgetId()
        val manager = AppWidgetManager.getInstance(context)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
        try { assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, LedgerWidgetProvider::class.java))) }
        finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        try { block(host, id) }
        finally { host.stopListening(); host.deleteAppWidgetId(id); WidgetBooks(context).remove(id) }
    }
    @Test fun pinCallbackBindsTheRequestedBookOnlyAfterTheLauncherReturnsItsId() = withHost { _, id ->
        val book = Book(UUID.randomUUID().toString(), "Pin ${UUID.randomUUID().toString().take(6)}")
        runBlocking { container.store.catalog.ensureLocalDefaults(); container.store.catalog.saveBook(book) }
        val callback = WidgetPinning.callback(context, book.id)
        assertNull(WidgetBooks(context).read(id))
        callback.send(context, 0, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra("bookId", UUID.randomUUID().toString()))
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (WidgetBooks(context).read(id) == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals("Pinning skips the configuration activity, so its completion must bind the chosen book", book.id, WidgetBooks(context).read(id))
        assertThrows(PendingIntent.CanceledException::class.java) { callback.send() }
    }
    @Test fun unconfiguredWidgetOpensConfigurationInsteadOfTheCurrentBooksEditor() = configurationEntry(null)
    @Test fun archivedWidgetCanReconfigureInsteadOfRepeatedlyOpeningTheInvalidBook() =
        configurationEntry(Book(UUID.randomUUID().toString(), "Reconfigure ${UUID.randomUUID().toString().take(6)}"))
    private fun configurationEntry(book: Book?) = withHost { host, id ->
        runBlocking {
            container.store.catalog.ensureLocalDefaults()
            if (book != null) {
                container.store.catalog.saveBook(book); container.store.catalog.archiveBook(book.id)
                assertTrue(WidgetBooks(context).save(id, book.id))
            }
        }
        val manager = AppWidgetManager.getInstance(context)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var widget: AppWidgetHostView
            scenario.onActivity { activity ->
                widget = host.createView(activity, id, manager.getAppWidgetInfo(id))
                activity.setContentView(FrameLayout(activity).apply { addView(widget, FrameLayout.LayoutParams(900, 900)) })
                host.startListening()
            }
            runBlocking { container.widgets.refresh() }
            val deadline = SystemClock.uptimeMillis() + 5_000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync { ready = widget.findViewById<TextView>(R.id.widget_status)?.text == context.getString(if (book == null) R.string.widget_choose else R.string.widget_book_unavailable) }
                SystemClock.sleep(40)
            }
            scenario.onActivity { assertTrue(widget.findViewById<View>(R.id.widget_add).performClick()) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("widget_config_save").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("bill_amount").assertDoesNotExist()
        }
    }
    @Test fun archivedMissingAndUnboundPinTargetsRemainUnconfigured() = withHost { _, id ->
        val book = Book(UUID.randomUUID().toString(), "Archived pin ${UUID.randomUUID().toString().take(6)}")
        runBlocking {
            container.store.catalog.ensureLocalDefaults()
            container.store.catalog.saveBook(book)
            assertTrue(container.store.catalog.archiveBook(book.id) is app.omymoney.data.CatalogResult.Saved)
        }
        WidgetPinning.callback(context, book.id).send(context, 0, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        WidgetPinning.callback(context, UUID.randomUUID().toString()).send(context, 0, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        WidgetPinning.callback(context, book.id).send(context, 0, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, Int.MAX_VALUE))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        assertNull(WidgetBooks(context).read(id))
        assertNull(WidgetBooks(context).read(Int.MAX_VALUE))
    }
}
