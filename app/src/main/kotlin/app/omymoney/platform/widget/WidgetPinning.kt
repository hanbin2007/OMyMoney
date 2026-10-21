package app.omymoney.platform.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.omymoney.OMyMoneyApplication
import app.omymoney.platform.security.LaunchRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

object WidgetPinning {
    fun callback(context: Context, bookId: String): PendingIntent {
        require(LaunchRequest.canonicalId(bookId))
        // The launcher fills in only the widget ID. The requested book stays bound
        // in the explicit intent's data, and each request can complete only once.
        return PendingIntent.getBroadcast(context, 0,
            Intent(context, WidgetPinnedReceiver::class.java).setData(Uri.Builder().scheme("omymoney")
                .authority("pinned-widget").appendPath(bookId).appendPath(UUID.randomUUID().toString()).build()),
            PendingIntent.FLAG_ONE_SHOT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
    }
}

class WidgetPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme != "omymoney" || data.authority != "pinned-widget" || data.pathSegments.size != 2) return
        val bookId = data.pathSegments[0].takeIf(LaunchRequest::canonicalId) ?: return
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val manager = AppWidgetManager.getInstance(context)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || manager.getAppWidgetInfo(id)?.provider != ComponentName(context, LedgerWidgetProvider::class.java)) return
        val pending = goAsync()
        val container = (context.applicationContext as OMyMoneyApplication).container
        container.applicationScope.launch {
            try {
                withTimeout(8_000) {
                    if (container.store.catalog.observeBooks().first().any { it.id == bookId }) WidgetBooks(context).save(id, bookId)
                    container.widgets.refresh()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The unconfigured widget remains a configuration entry point. */ }
            finally { pending.finish() }
        }
    }
}
