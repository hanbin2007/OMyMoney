package app.omymoney.feature.statistics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.omymoney.android.R
import app.omymoney.core.ledger.EconomicKind
import app.omymoney.core.statistics.*
import app.omymoney.ui.components.LedgerIconButton
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable internal fun CalendarHeader(month: YearMonth, annual: Boolean, onShift: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (annual) LedgerIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.budget_previous), { onShift(-1) }, enabled = month.monthValue > 1)
        Text(month.toString(), Modifier.weight(1f).testTag("stats_calendar_month"), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (annual) LedgerIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.budget_next), { onShift(1) },
            Modifier.testTag("stats_calendar_next"), enabled = month.monthValue < 12)
    }
}

internal fun LazyListScope.calendarDays(summary: StatisticsSummary, month: YearMonth, selected: LocalDate,
    listMode: Boolean, displayedKind: EconomicKind, onChoose: (LocalDate) -> Unit) {
    val days = summary.days.filter { YearMonth.from(it.date) == month }
    if (listMode) items(days, key = { "calendar/${it.date}" }) { day ->
        val description = dayDescription(day, summary)
        ListItem(modifier = Modifier.testTag("stats_day_${day.date}").clickable { onChoose(day.date) }
            .semantics { contentDescription = description; this.selected = day.date == selected },
            headlineContent = { Text(day.date.format(DateTimeFormatter.ofPattern("MM-dd EEE"))) },
            supportingContent = { Column {
                Text(economicLabel(displayedKind), style = MaterialTheme.typography.labelMedium)
                StatisticsValue(day.totals[displayedKind], summary.currency, Modifier.fillMaxWidth())
            } },
            colors = ListItemDefaults.colors(containerColor = if (day.date == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface))
    } else item {
        Column {
            Row(Modifier.fillMaxWidth()) {
                java.time.DayOfWeek.entries.forEach { day -> Text(day.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()),
                    Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium) }
            }
            val cells = List<StatisticsDay?>(month.atDay(1).dayOfWeek.value - 1) { null } + days
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    (week + List<StatisticsDay?>(7 - week.size) { null }).forEach { day ->
                        if (day == null) Spacer(Modifier.weight(1f).height(48.dp)) else {
                            val description = dayDescription(day, summary)
                            TextButton({ onChoose(day.date) }, Modifier.weight(1f).heightIn(min = 48.dp)
                                .testTag("stats_day_${day.date}").semantics { contentDescription = description; this.selected = day.date == selected },
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.textButtonColors(containerColor = if (day.date == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
                                Text(day.date.dayOfMonth.toString())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun dayDescription(day: StatisticsDay, summary: StatisticsSummary) = stringResource(R.string.stats_day_description,
    day.date.toString(), amountText(day.totals.income, summary.currency), amountText(day.totals.expense, summary.currency), amountText(day.totals.transfer, summary.currency))
