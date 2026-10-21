package app.omymoney.feature.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.omymoney.android.R
import app.omymoney.core.model.CurrencySpec
import app.omymoney.core.statistics.*
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

internal fun statsMoney(minor: BigInteger, currency: CurrencySpec) = "${currency.code} ${BigDecimal(minor, currency.scale).toPlainString()}"

@Composable internal fun amountText(amount: StatisticsAmount, currency: CurrencySpec): String =
    amount.minor?.let { statsMoney(it, currency) } ?: stringResource(R.string.stats_unknown, amount.missingCurrencies.joinToString { it.code })

@Composable internal fun StatisticsValue(amount: StatisticsAmount, currency: CurrencySpec, modifier: Modifier = Modifier) {
    if (amount.minor == null) Text(amountText(amount, currency), modifier, color = MaterialTheme.colorScheme.error)
    else Text(statsMoney(requireNotNull(amount.minor), currency), modifier, style = MaterialTheme.typography.titleMedium)
}

internal data class ChartPoint(val label: String, val totals: StatisticsTotals)

@Composable
internal fun StatisticsChart(points: List<ChartPoint>, currency: CurrencySpec, compact: Boolean) {
    val known = points.flatMap { listOfNotNull(it.totals.income.minor, it.totals.expense.minor) }
    val low = minOf(known.minOrNull() ?: BigInteger.ZERO, BigInteger.ZERO)
    val high = maxOf(known.maxOrNull() ?: BigInteger.ZERO, BigInteger.ZERO)
    val span = (high - low).coerceAtLeast(BigInteger.ONE)
    val description = stringResource(R.string.stats_chart_description, currency.code, statsMoney(low, currency), statsMoney(high, currency))
    val income = MaterialTheme.colorScheme.primary
    val expense = MaterialTheme.colorScheme.tertiary
    val axis = MaterialTheme.colorScheme.outline
    val plot: @Composable () -> Unit = {
        Canvas(Modifier.fillMaxWidth().height(if (compact) 96.dp else 160.dp).semantics { contentDescription = description }) {
            val inset = 6.dp.toPx()
            fun y(value: BigInteger) = inset + (high - value).toBigDecimal().divide(span.toBigDecimal(), 8, RoundingMode.HALF_EVEN)
                .toFloat() * (size.height - 2 * inset)
            fun x(index: Int) = if (points.size <= 1) size.width / 2 else inset + index.toFloat() / (points.size - 1) * (size.width - 2 * inset)
            drawLine(axis, Offset(inset, y(BigInteger.ZERO)), Offset(size.width - inset, y(BigInteger.ZERO)), 1.dp.toPx())
            listOf(false, true).forEach { isExpense ->
                val color = if (isExpense) expense else income
                val dash = if (isExpense) PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 4.dp.toPx())) else null
                var previous: Offset? = null
                points.forEachIndexed { index, point ->
                    val value = (if (isExpense) point.totals.expense else point.totals.income).minor
                    if (value == null) previous = null else {
                        val position = Offset(x(index), y(value))
                        previous?.let { drawLine(color, it, position, 2.dp.toPx(), pathEffect = dash) }
                        if (isExpense) drawRect(color, position - Offset(3.dp.toPx(), 3.dp.toPx()), Size(6.dp.toPx(), 6.dp.toPx()))
                        else drawCircle(color, 3.dp.toPx(), position)
                        previous = position
                    }
                }
            }
        }
    }
    val dates: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(points.firstOrNull()?.label.orEmpty(), style = MaterialTheme.typography.bodySmall)
            Text(points.lastOrNull()?.label.orEmpty(), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (compact) Row(Modifier.fillMaxWidth().testTag("stats_chart"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.stats_trend), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.stats_units, currency.code), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.stats_income_legend), color = income, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.stats_expense_legend), color = expense, style = MaterialTheme.typography.bodySmall)
        }
        Column(Modifier.weight(1f).heightIn(min = 96.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(statsMoney(high, currency), style = MaterialTheme.typography.bodySmall)
            Text(statsMoney(low, currency), style = MaterialTheme.typography.bodySmall)
        }
        Column(Modifier.weight(3f)) { plot(); dates() }
    } else Column(Modifier.fillMaxWidth().testTag("stats_chart"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.stats_trend), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.stats_units, currency.code), style = MaterialTheme.typography.bodySmall)
        Text(statsMoney(high, currency), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
        plot()
        Text(statsMoney(low, currency), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
        dates()
        Text(stringResource(R.string.stats_income_legend), color = income, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.stats_expense_legend), color = expense, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun CategoryBar(value: BigInteger?, maximum: BigInteger) {
    val color = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().height(16.dp).clearAndSetSemantics { }) {
        val center = size.width / 2
        drawLine(axis, Offset(center, 0f), Offset(center, size.height), 1.dp.toPx())
        if (value != null) {
            val width = value.abs().toBigDecimal().divide(maximum.coerceAtLeast(BigInteger.ONE).toBigDecimal(), 8, RoundingMode.HALF_EVEN)
                .toFloat() * center
            drawRect(color, Offset(if (value.signum() < 0) center - width else center, 3.dp.toPx()), Size(width, 10.dp.toPx()))
        }
    }
}
