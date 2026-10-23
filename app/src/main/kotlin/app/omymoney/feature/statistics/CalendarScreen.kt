package app.omymoney.feature.statistics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import app.omymoney.ui.components.GroupedRow
import app.omymoney.ui.components.groupedShape
import app.omymoney.ui.theme.ExpressiveMotion
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
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
        Text(month.toString(), Modifier.weight(1f).padding(vertical = 12.dp).testTag("stats_calendar_month"), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (annual) LedgerIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.budget_next), { onShift(1) },
            Modifier.testTag("stats_calendar_next"), enabled = month.monthValue < 12)
    }
}

internal fun LazyListScope.calendarDays(summary: StatisticsSummary, month: YearMonth, selected: LocalDate,
    listMode: Boolean, displayedKind: EconomicKind, onChoose: (LocalDate) -> Unit) {
    val days = summary.days.filter { YearMonth.from(it.date) == month }
    if (listMode) itemsIndexed(days, key = { _, day -> "calendar/${day.date}" }) { index, day ->
        val locale = LocalConfiguration.current.locales[0]
        val description = dayDescription(day, summary)
        val chosen = day.date == selected
        GroupedRow(groupedShape(index, days.size), onClick = { onChoose(day.date) },
            modifier = Modifier.testTag("stats_day_${day.date}").semantics { contentDescription = description; this.selected = chosen },
            containerColor = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            headline = { Text(day.date.format(DateTimeFormatter.ofPattern("MM-dd EEE", locale))) },
            supporting = { Column {
                Text(economicLabel(displayedKind), style = MaterialTheme.typography.labelMedium)
                StatisticsValue(day.totals[displayedKind], summary.currency, Modifier.fillMaxWidth())
            } })
    } else item {
        val locale = LocalConfiguration.current.locales[0]
        // Each day is tinted by its share of the month's busiest day, turning the grid into a spending heatmap.
        val peak = days.mapNotNull { it.totals[displayedKind].minor?.abs() }.maxOrNull()?.takeIf { it.signum() > 0 }
        val heat = MaterialTheme.colorScheme.primary
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    java.time.DayOfWeek.entries.forEach { day -> Text(day.getDisplayName(java.time.format.TextStyle.NARROW, locale),
                        Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                val cells = List<StatisticsDay?>(month.atDay(1).dayOfWeek.value - 1) { null } + days
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (week + List<StatisticsDay?>(7 - week.size) { null }).forEach { day ->
                            if (day == null) Spacer(Modifier.weight(1f).height(48.dp)) else {
                                val description = dayDescription(day, summary)
                                val chosen = day.date == selected
                                val intensity = peak?.let { max -> day.totals[displayedKind].minor?.abs()?.toBigDecimal()
                                    ?.divide(max.toBigDecimal(), 4, java.math.RoundingMode.HALF_EVEN)?.toFloat() } ?: 0f
                                val container by animateColorAsState(when {
                                    chosen -> MaterialTheme.colorScheme.primary
                                    intensity > 0f -> heat.copy(alpha = 0.10f + 0.38f * intensity)
                                    else -> Color.Transparent
                                }, ExpressiveMotion.defaultEffects(), label = "day")
                                val corner by animateDpAsState(if (chosen) 24.dp else 12.dp, ExpressiveMotion.fastSpatial(), label = "day-corner")
                                Surface(onClick = { onChoose(day.date) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    .testTag("stats_day_${day.date}").semantics { contentDescription = description; this.selected = chosen },
                                    shape = RoundedCornerShape(corner), color = container,
                                    contentColor = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(day.date.dayOfMonth.toString(), style = MaterialTheme.typography.titleSmall)
                                    }
                                }
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
