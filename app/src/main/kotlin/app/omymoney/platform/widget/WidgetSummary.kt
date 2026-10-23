package app.omymoney.platform.widget

import android.content.Context
import android.appwidget.AppWidgetProviderInfo
import androidx.core.content.ContextCompat
import app.omymoney.android.R
import app.omymoney.core.model.*
import app.omymoney.core.statistics.*
import java.math.BigDecimal
import java.time.Instant

data class WidgetSummary(val title: String, val status: String, val income: String, val expense: String, val hidden: Boolean = false,
    val requiresConfiguration: Boolean = false) {
    companion object {
        fun hidden(context: Context) = ContextCompat.getContextForLanguage(context).let {
            WidgetSummary(it.getString(R.string.app_name), it.getString(R.string.widget_private), "", "", true)
        }
        fun unavailable(context: Context) = ContextCompat.getContextForLanguage(context).let {
            WidgetSummary(it.getString(R.string.app_name), it.getString(R.string.widget_book_unavailable), "", "", requiresConfiguration = true)
        }
        fun create(context: Context, book: Book?, bills: List<Bill>, rates: List<ManualExchangeRate>, privacy: Boolean, host: Int, now: Instant): WidgetSummary {
            val context = ContextCompat.getContextForLanguage(context)
            if (privacy || (host and AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD) != 0) return hidden(context)
            if (book == null || book.archived) return unavailable(context)
            val month = now.atZone(book.zoneId).toLocalDate().withDayOfMonth(1)
            val summary = StatisticsCalculator.summarize(bills, StatisticsRange(book.id, month, month.plusMonths(1), book.zoneId), book.currency, rates = rates)
            fun amount(value: StatisticsAmount) = value.minor?.let { "${book.currency.code} ${BigDecimal(it, book.currency.scale).toPlainString()}" }
                ?: context.getString(R.string.stats_unknown, value.missingCurrencies.joinToString { it.code })
            return WidgetSummary(book.name, java.time.YearMonth.from(month).toString(), amount(summary.totals.income), amount(summary.totals.expense))
        }
    }
}
