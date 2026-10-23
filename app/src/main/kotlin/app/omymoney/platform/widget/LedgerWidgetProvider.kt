package app.omymoney.platform.widget

import android.app.PendingIntent
import android.app.KeyguardManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import app.omymoney.AppContainer
import app.omymoney.OMyMoneyApplication
import app.omymoney.android.R
import app.omymoney.core.model.*
import app.omymoney.platform.security.LaunchRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class WidgetBooks(private val context: Context) {
    private val preferences = context.getSharedPreferences("widget-books", Context.MODE_PRIVATE)
    fun read(id: Int) = preferences.getString(id.toString(), null)?.takeIf(LaunchRequest::canonicalId)
    fun save(id: Int, bookId: String): Boolean = id != AppWidgetManager.INVALID_APPWIDGET_ID &&
        LaunchRequest.canonicalId(bookId) && preferences.edit().putString(id.toString(), bookId).commit()
    fun remove(id: Int) { preferences.edit().remove(id.toString()).apply() }
}

class LedgerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { refresh(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { refresh(context) }
    override fun onDeleted(context: Context, ids: IntArray) { ids.forEach { WidgetBooks(context).remove(it) }; refresh(context) }
    override fun onDisabled(context: Context) { (context.applicationContext as OMyMoneyApplication).container.widgets.observeInstalled() }
    private fun refresh(context: Context) {
        val pending = goAsync()
        val container = (context.applicationContext as OMyMoneyApplication).container
        container.applicationScope.launch {
            try { withTimeout(8_000) { container.widgets.refresh() } }
            finally { pending.finish() }
        }
    }
}

class LedgerWidgets(private val context: Context, private val container: AppContainer) {
    private val manager = AppWidgetManager.getInstance(context)
    private val books = WidgetBooks(context)
    private var observer: Job? = null
    private data class Snapshot(val books: List<Book>, val bills: List<Bill>, val rates: List<ManualExchangeRate>)
    private fun ids() = manager.getAppWidgetIds(ComponentName(context, LedgerWidgetProvider::class.java))
    fun observeInstalled() {
        if (ids().isEmpty()) { observer?.cancel(); observer = null; return }
        if (observer?.isActive == true) return
        observer = container.applicationScope.launch {
            combine(container.store.catalog.observeBooks(true), container.store.ledger.observeBills(BillQuery()),
                container.store.catalog.observeExchangeRates(), container.preferences.state) { catalog, ledger, rates, _ -> Snapshot(catalog, ledger, rates) }
                .catch { if (it is CancellationException) throw it else publish(null) }
                .collect { publish(it) }
        }
    }
    suspend fun refresh() {
        observeInstalled()
        if (ids().isEmpty()) return
        try {
            val snapshot = withContext(Dispatchers.IO) { Snapshot(container.store.catalog.observeBooks(true).first(),
                container.store.ledger.observeBills(BillQuery()).first(), container.store.catalog.observeExchangeRates().first()) }
            withContext(Dispatchers.Main.immediate) { publish(snapshot) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { withContext(Dispatchers.Main.immediate) { publish(null) } }
    }
    // Called synchronously on screen-off, before any asynchronous database refresh.
    fun redact() {
        val context = ContextCompat.getContextForLanguage(context)
        ids().forEach { manager.updateAppWidget(it, views(context, it, books.read(it), WidgetSummary.hidden(context))) }
    }
    private fun publish(snapshot: Snapshot?) {
        val context = ContextCompat.getContextForLanguage(context)
        ids().forEach { id ->
            val bookId = books.read(id)
            val private = container.preferences.state.value.lockEnabled || context.getSystemService(KeyguardManager::class.java).isDeviceLocked
            val category = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
            val model = when {
                private || category and AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD != 0 -> WidgetSummary.hidden(context)
                bookId == null -> WidgetSummary(context.getString(R.string.app_name), context.getString(R.string.widget_choose), "", "")
                snapshot == null -> WidgetSummary(context.getString(R.string.app_name), context.getString(R.string.widget_refresh_failed), "", "")
                else -> WidgetSummary.create(context, snapshot.books.firstOrNull { it.id == bookId }, snapshot.bills, snapshot.rates, false, category, java.time.Instant.now())
            }
            val unavailable = snapshot != null && snapshot.books.none { it.id == bookId && !it.archived }
            manager.updateAppWidget(id, views(context, id, bookId, model.copy(requiresConfiguration = unavailable || model.requiresConfiguration)))
        }
    }
    companion object {
        fun views(context: Context, id: Int, bookId: String?, model: WidgetSummary) = RemoteViews(context.packageName, R.layout.ledger_widget).apply {
            val context = ContextCompat.getContextForLanguage(context)
            setTextViewText(R.id.widget_title, model.title)
            setTextViewText(R.id.widget_status, model.status)
            setTextViewText(R.id.widget_income, model.income)
            setTextViewText(R.id.widget_expense, model.expense)
            setTextViewText(R.id.widget_income_label, context.getString(R.string.income))
            setTextViewText(R.id.widget_expense_label, context.getString(R.string.expense))
            setTextViewText(R.id.widget_open, context.getString(R.string.widget_open))
            setContentDescription(R.id.widget_add, context.getString(R.string.new_bill))
            setViewVisibility(R.id.widget_totals, if (model.income.isEmpty() && model.expense.isEmpty()) View.GONE else View.VISIBLE)
            setContentDescription(R.id.widget_income, context.getString(R.string.widget_income, model.income))
            setContentDescription(R.id.widget_expense, context.getString(R.string.widget_expense, model.expense))
            fun action(new: Boolean): PendingIntent {
                val intent = if (bookId != null && !model.requiresConfiguration) LaunchRequest.intent(context, bookId, new) else
                    Intent(context, WidgetConfigureActivity::class.java)
                        .setData(Uri.Builder().scheme("omymoney").authority("configure-widget").appendPath(id.toString()).build())
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                return PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            setOnClickPendingIntent(R.id.widget_open, action(false))
            setOnClickPendingIntent(R.id.widget_add, action(true))
        }
    }
}
