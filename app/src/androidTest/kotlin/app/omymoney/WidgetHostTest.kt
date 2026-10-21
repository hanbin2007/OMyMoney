package app.omymoney

import android.appwidget.*
import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.android.R
import app.omymoney.core.model.*
import app.omymoney.platform.widget.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class WidgetHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as OMyMoneyApplication).container
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(60)
        }
        fail("Widget host did not publish the expected committed state")
    }
    private fun assertFits(view: TextView) {
        val layout = requireNotNull(view.layout)
        assertTrue("Widget text must have usable height: ${view.text}", view.height - view.compoundPaddingTop - view.compoundPaddingBottom >= layout.height)
        assertTrue("Widget text must not be clipped: ${view.text}", layout.lineCount > 0 && layout.getLineEnd(layout.lineCount - 1) == view.text.length)
        assertTrue("Widget text must not be ellipsized: ${view.text}", (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
    }
    @Test fun realHostRefreshResizeRedactionAndBookSpecificClick() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, 43155)
        val suffix = UUID.randomUUID().toString().take(6)
        val book = Book(UUID.randomUUID().toString(), "Widget $suffix")
        val account = AssetAccount(UUID.randomUUID().toString(), "Widget account $suffix")
        runBlocking {
            container.store.catalog.ensureLocalDefaults()
            assertTrue(container.store.catalog.saveBook(book) is app.omymoney.data.CatalogResult.Saved)
            assertTrue(container.store.catalog.saveAccount(account) is app.omymoney.data.CatalogResult.Saved)
        }
        val id = host.allocateAppWidgetId()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
        try { assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, LedgerWidgetProvider::class.java))) }
        finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        try {
            assertTrue(WidgetBooks(context).save(id, book.id))
            lateinit var widget: AppWidgetHostView
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    widget = host.createView(activity, id, manager.getAppWidgetInfo(id))
                    widget.setAppWidget(id, manager.getAppWidgetInfo(id))
                    val frame = FrameLayout(activity)
                    frame.addView(widget, FrameLayout.LayoutParams((300 * activity.resources.displayMetrics.density).toInt(), manager.getAppWidgetInfo(id).minHeight))
                    activity.setContentView(frame)
                    host.startListening()
                    container.widgets.observeInstalled()
                }
                runBlocking { container.widgets.refresh() }
                fun amount() = widget.findViewById<TextView>(R.id.widget_expense)?.text?.toString()
                waitUntil { amount() == "CNY 0.00" }
                val saved = runBlocking { container.store.ledger.save(TransactionDraft(book.id, kind = BillKind.EXPENSE,
                    amount = Money(4321, CurrencySpec.CNY), fromAccountId = account.id, timeSource = TimeSource.USER,
                    occurredAt = java.time.Instant.now(), zoneId = book.zoneId), null) as SaveResult.Saved }
                waitUntil { amount() == "CNY 43.21" }
                captureUiEvidence("c8-widget.png")
                val info = manager.getAppWidgetInfo(id)
                scenario.onActivity { activity ->
                    widget.layoutParams = FrameLayout.LayoutParams(info.minResizeWidth, info.minResizeHeight)
                    val width = (info.minResizeWidth / activity.resources.displayMetrics.density).toInt()
                    val height = (info.minResizeHeight / activity.resources.displayMetrics.density).toInt()
                    @Suppress("DEPRECATION") widget.updateAppWidgetSize(Bundle(), width, height, width, height)
                }
                waitUntil { amount() == "CNY 43.21" && widget.width == info.minResizeWidth }
                instrumentation.runOnMainSync {
                    listOf(R.id.widget_status, R.id.widget_income, R.id.widget_expense, R.id.widget_open).forEach { assertFits(widget.findViewById(it)) }
                }
                runBlocking { assertTrue(container.store.ledger.save(TransactionDraft(book.id, kind = BillKind.INCOME,
                    amount = Money(Long.MAX_VALUE, CurrencySpec.CNY), toAccountId = account.id, timeSource = TimeSource.USER,
                    occurredAt = java.time.Instant.now(), zoneId = book.zoneId), null) is SaveResult.Saved) }
                waitUntil { widget.findViewById<TextView>(R.id.widget_income)?.text?.toString() == "CNY 92233720368547758.07" }
                instrumentation.runOnMainSync { assertFits(widget.findViewById(R.id.widget_income)) }
                captureUiEvidence("c8-widget-narrow.png")
                scenario.onActivity { container.preferences.setLockEnabled(true) }
                waitUntil { amount() == "" }
                captureUiEvidence("c8-widget-private.png")
                // This in-process test host is also a secure app window. Draw only its
                // already-redacted RemoteViews to inspect what an external launcher sees.
                instrumentation.runOnMainSync {
                    val bitmap = android.graphics.Bitmap.createBitmap(widget.width, widget.height, android.graphics.Bitmap.Config.ARGB_8888)
                    widget.draw(android.graphics.Canvas(bitmap))
                    java.io.File(context.filesDir, "qa/c8-widget-private-view.png").outputStream().use {
                        assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    }
                }
                scenario.onActivity { container.preferences.setLockEnabled(false) }
                waitUntil { amount() == "CNY 43.21" }
                manager.updateAppWidgetOptions(id, Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD) })
                runBlocking { container.widgets.refresh() }
                waitUntil { amount() == "" }
                manager.updateAppWidgetOptions(id, Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) })
                runBlocking { container.widgets.refresh(); container.store.ledger.delete(saved.billId) }
                waitUntil { amount() == "CNY 0.00" }
                scenario.onActivity { assertTrue(widget.findViewById<View>(R.id.widget_add).performClick()) }
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("bill_amount").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(book.id, runBlocking { container.store.catalog.observeCurrentBookId().first() })
                compose.onNodeWithTag("bill_amount").assertIsDisplayed()
            }
        } finally {
            host.stopListening(); host.deleteAppWidgetId(id); WidgetBooks(context).remove(id)
            instrumentation.runOnMainSync { container.preferences.setLockEnabled(false); container.widgets.observeInstalled() }
        }
    }
}
