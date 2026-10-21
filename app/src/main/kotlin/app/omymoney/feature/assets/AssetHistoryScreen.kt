package app.omymoney.feature.assets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.R
import app.omymoney.data.AssetHistoryPoint
import app.omymoney.data.AssetHistoryRepository
import app.omymoney.feature.catalog.CatalogState
import app.omymoney.ui.components.*
import java.time.ZoneId
import java.math.BigDecimal
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

@Composable
fun AssetHistoryScreen(accountId: String, repository: AssetHistoryRepository, state: CatalogState, onBack: () -> Unit) {
    val account = state.accounts.firstOrNull { it.id == accountId }
    val zone = state.currentBook?.zoneId ?: ZoneId.systemDefault()
    var attempt by remember { mutableIntStateOf(0) }
    val flow = remember(repository, accountId, zone, attempt) { repository.observeHistory(accountId, zone)
        .map<Result<List<AssetHistoryPoint>>, Result<List<AssetHistoryPoint>>?> { it }.catch { emit(Result.failure(it)) } }
    val result by flow.collectAsStateWithLifecycle(null)
    ScreenScaffold(stringResource(R.string.asset_history_title), onBack) { padding ->
        when {
            !state.initialized || result == null -> LoadingState(Modifier.padding(padding))
            account == null -> EmptyState(stringResource(R.string.error_not_found), Modifier.padding(padding))
            result!!.isFailure -> Column(Modifier.padding(padding).padding(16.dp)) {
                ErrorText(stringResource(R.string.bill_load_failed))
                TextButton({ attempt++ }) { Text(stringResource(R.string.retry)) }
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("asset_history_list"), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(account.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.asset_history_basis, zone.id), style = MaterialTheme.typography.bodySmall)
                }
                items(result!!.getOrThrow().asReversed(), key = { it.date?.toString() ?: "opening" }) { point ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(point.date?.toString() ?: stringResource(R.string.opening_balance), style = MaterialTheme.typography.labelLarge)
                        MoneyText("${point.balance.currency.code} ${point.balance.decimalText()}", Modifier.fillMaxWidth(), MaterialTheme.typography.titleLarge)
                        if (point.date != null) {
                            Text(stringResource(R.string.asset_history_change), style = MaterialTheme.typography.labelSmall)
                            MoneyText("${point.balance.currency.code} ${if (point.change.signum() > 0) "+" else ""}${BigDecimal(point.change, point.balance.currency.scale).toPlainString()}", Modifier.fillMaxWidth())
                        }
                    }
                    HorizontalDivider(Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}
