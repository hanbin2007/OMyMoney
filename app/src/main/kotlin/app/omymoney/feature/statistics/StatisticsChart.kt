package app.omymoney.feature.statistics

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.omymoney.android.R
import app.omymoney.core.model.CurrencySpec
import app.omymoney.core.statistics.*
import app.omymoney.ui.theme.ExpressiveMotion
import app.omymoney.ui.theme.LocalLedgerColors
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

internal fun statsMoney(minor: BigInteger, currency: CurrencySpec) = "${currency.code} ${BigDecimal(minor, currency.scale).toPlainString()}"

@Composable internal fun amountText(amount: StatisticsAmount, currency: CurrencySpec): String =
    amount.minor?.let { statsMoney(it, currency) } ?: stringResource(R.string.stats_unknown, amount.missingCurrencies.joinToString { it.code })

@Composable internal fun StatisticsValue(amount: StatisticsAmount, currency: CurrencySpec, modifier: Modifier = Modifier,
                                         style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium) {
    if (amount.minor == null) Text(amountText(amount, currency), modifier, color = MaterialTheme.colorScheme.error)
    else Text(statsMoney(requireNotNull(amount.minor), currency), modifier, style = style.copy(fontFeatureSettings = "tnum"))
}

internal data class ChartPoint(val label: String, val totals: StatisticsTotals)

/**
 * Income and expense trend drawn as smooth curves over soft gradient fills. The curves sweep in from the
 * left whenever the data changes. Expense keeps a dashed stroke so the two series differ without colour.
 */
@Composable
internal fun StatisticsChart(points: List<ChartPoint>, currency: CurrencySpec, compact: Boolean) {
    val known = points.flatMap { listOfNotNull(it.totals.income.minor, it.totals.expense.minor) }
    val low = minOf(known.minOrNull() ?: BigInteger.ZERO, BigInteger.ZERO)
    val high = maxOf(known.maxOrNull() ?: BigInteger.ZERO, BigInteger.ZERO)
    val span = (high - low).coerceAtLeast(BigInteger.ONE)
    val description = stringResource(R.string.stats_chart_description, currency.code, statsMoney(low, currency), statsMoney(high, currency))
    val income = LocalLedgerColors.current.income
    val expense = LocalLedgerColors.current.expense
    val axis = MaterialTheme.colorScheme.outlineVariant
    val reveal = remember { Animatable(0f) }
    val revealSpec = ExpressiveMotion.slowEffects<Float>()
    LaunchedEffect(points) { reveal.snapTo(0f); reveal.animateTo(1f, revealSpec) }
    val plot: @Composable () -> Unit = {
        Canvas(Modifier.fillMaxWidth().height(if (compact) 96.dp else 180.dp).semantics { contentDescription = description }) {
            val inset = 8.dp.toPx()
            fun y(value: BigInteger) = inset + (high - value).toBigDecimal().divide(span.toBigDecimal(), 8, RoundingMode.HALF_EVEN)
                .toFloat() * (size.height - 2 * inset)
            fun x(index: Int) = if (points.size <= 1) size.width / 2 else inset + index.toFloat() / (points.size - 1) * (size.width - 2 * inset)
            val zero = y(BigInteger.ZERO)
            // Quiet horizontal guides at quarter heights, with the zero line slightly stronger.
            for (step in 0..4) {
                val guide = inset + step / 4f * (size.height - 2 * inset)
                drawLine(axis.copy(alpha = 0.45f), Offset(inset, guide), Offset(size.width - inset, guide), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())))
            }
            drawLine(axis, Offset(inset, zero), Offset(size.width - inset, zero), 1.dp.toPx())
            clipRect(right = size.width * reveal.value) {
                listOf(false, true).forEach { isExpense ->
                    val color = if (isExpense) expense else income
                    val runs = mutableListOf<MutableList<Offset>>(mutableListOf())
                    points.forEachIndexed { index, point ->
                        val value = (if (isExpense) point.totals.expense else point.totals.income).minor
                        if (value == null) runs.add(mutableListOf()) else runs.last().add(Offset(x(index), y(value)))
                    }
                    runs.filter { it.isNotEmpty() }.forEach { run ->
                        val line = smoothPath(run)
                        if (run.size > 1) {
                            val area = Path().apply {
                                addPath(line)
                                lineTo(run.last().x, zero); lineTo(run.first().x, zero); close()
                            }
                            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f)), startY = 0f, endY = zero.coerceAtLeast(1f)))
                        }
                        drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round,
                            pathEffect = if (isExpense) PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())) else null))
                        val end = run.last()
                        if (isExpense) drawRoundRect(color, end - Offset(4.dp.toPx(), 4.dp.toPx()), Size(8.dp.toPx(), 8.dp.toPx()), CornerRadius(2.dp.toPx()))
                        else drawCircle(color, 4.5.dp.toPx(), end)
                        if (run.size == 1 && !isExpense) drawCircle(color, 4.5.dp.toPx(), run.single())
                    }
                }
            }
        }
    }
    val dates: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(points.firstOrNull()?.label.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(points.lastOrNull()?.label.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val legend: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendDot(stringResource(R.string.stats_income_legend), income, square = false)
            LegendDot(stringResource(R.string.stats_expense_legend), expense, square = true)
        }
    }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        if (compact) Row(Modifier.fillMaxWidth().padding(12.dp).testTag("stats_chart"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.stats_trend), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.stats_units, currency.code), style = MaterialTheme.typography.bodySmall)
                LegendDot(stringResource(R.string.stats_income_legend), income, square = false)
                LegendDot(stringResource(R.string.stats_expense_legend), expense, square = true)
            }
            Column(Modifier.weight(1f).heightIn(min = 96.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(statsMoney(high, currency), style = MaterialTheme.typography.bodySmall)
                Text(statsMoney(low, currency), style = MaterialTheme.typography.bodySmall)
            }
            Column(Modifier.weight(3f)) { plot(); dates() }
        } else Column(Modifier.fillMaxWidth().padding(16.dp).testTag("stats_chart"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.stats_trend), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.stats_units, currency.code), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            legend()
            Text(statsMoney(high, currency), Modifier.fillMaxWidth().padding(top = 4.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            plot()
            Text(statsMoney(low, currency), Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            dates()
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color, square: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(if (square) MaterialTheme.shapes.extraSmall else CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** Monotone-ish cubic curve through [points]: control points sit a third of the way to each neighbour, flattened horizontally. */
private fun smoothPath(points: List<Offset>): Path = Path().apply {
    moveTo(points.first().x, points.first().y)
    for (index in 1 until points.size) {
        val previous = points[index - 1]
        val current = points[index]
        val dx = (current.x - previous.x) / 3f
        cubicTo(previous.x + dx, previous.y, current.x - dx, current.y, current.x, current.y)
    }
}

/** Diverging bar around a centre axis; grows from the axis with a spring when it first appears or changes. */
@Composable
internal fun CategoryBar(value: BigInteger?, maximum: BigInteger) {
    val color = MaterialTheme.colorScheme.primary
    val negative = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val axis = MaterialTheme.colorScheme.outline
    val target = value?.abs()?.toBigDecimal()?.divide(maximum.coerceAtLeast(BigInteger.ONE).toBigDecimal(), 8, RoundingMode.HALF_EVEN)?.toFloat() ?: 0f
    val grow = remember { Animatable(0f) }
    val spec = ExpressiveMotion.slowSpatial<Float>()
    LaunchedEffect(target) { grow.animateTo(target, spec) }
    Canvas(Modifier.fillMaxWidth().height(16.dp).clearAndSetSemantics { }) {
        val center = size.width / 2
        val height = 10.dp.toPx()
        val top = (size.height - height) / 2
        val radius = CornerRadius(height / 2)
        drawRoundRect(track, Offset(0f, top), Size(size.width, height), radius)
        if (value != null) {
            val width = grow.value.coerceIn(0f, 1f) * center
            drawRoundRect(if (value.signum() < 0) negative else color, Offset(if (value.signum() < 0) center - width else center, top), Size(width, height), radius)
        }
        drawLine(axis, Offset(center, 0f), Offset(center, size.height), 1.dp.toPx())
    }
}
