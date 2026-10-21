package app.omymoney.feature.statistics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.omymoney.android.R
import app.omymoney.core.ledger.EconomicKind
import app.omymoney.core.model.CurrencySpec
import app.omymoney.core.statistics.*

@Composable internal fun economicLabel(kind: EconomicKind) = stringResource(when (kind) {
    EconomicKind.INCOME -> R.string.stats_income; EconomicKind.EXPENSE -> R.string.stats_expense; EconomicKind.TRANSFER -> R.string.stats_transfer
})

@Composable internal fun TotalsData(totals: StatisticsTotals, currency: CurrencySpec, tag: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(EconomicKind.EXPENSE, EconomicKind.INCOME, EconomicKind.TRANSFER).forEach { kind ->
            Text(economicLabel(kind), style = MaterialTheme.typography.labelLarge)
            StatisticsValue(totals[kind], currency, Modifier.fillMaxWidth().testTag("${tag}_$kind"))
        }
    }
}

internal fun LazyListScope.annualSummary(summary: StatisticsSummary) {
    item { Text(stringResource(R.string.stats_months), style = MaterialTheme.typography.titleMedium) }
    items(summary.months, key = { "month/${it.month}" }) { month ->
        Column(Modifier.fillMaxWidth().testTag("stats_month_${month.month}").semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(month.month.toString(), style = MaterialTheme.typography.titleSmall)
            TotalsData(month.totals, summary.currency, "stats_month_${month.month}")
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }
    }
}
