package app.omymoney.feature.assets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.R
import app.omymoney.core.ledger.DebtRules
import app.omymoney.core.ledger.LedgerRepository
import app.omymoney.core.model.*
import app.omymoney.feature.bills.billAmountText
import app.omymoney.feature.bills.billKindLabel
import app.omymoney.ui.components.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private data class DebtRecords(val bills: List<Bill> = emptyList(), val loading: Boolean = true, val failed: Boolean = false)

@Composable
fun DebtRecordsScreen(ledger: LedgerRepository, sourceId: String, snackbar: SnackbarHostState, onBack: () -> Unit,
                      onBill: (String) -> Unit, onCreate: (Bill, BillKind) -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    val flow = remember(ledger, sourceId, attempt) {
        ledger.observeBills(BillQuery()).map { DebtRecords(it, false) }.catch { emit(DebtRecords(loading = false, failed = true)) }
    }
    val state by flow.collectAsStateWithLifecycle(DebtRecords())
    val source = state.bills.firstOrNull { it.id == sourceId && it.kind in DebtRules.sourceKinds }
    ScreenScaffold(stringResource(R.string.debt_records), onBack, snackbarHostState = snackbar) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.failed -> Column(Modifier.padding(padding).padding(16.dp)) {
                ErrorText(stringResource(R.string.bill_load_failed))
                TextButton({ attempt++ }) { Text(stringResource(R.string.retry)) }
            }
            source == null -> EmptyState(stringResource(R.string.bill_not_found), Modifier.padding(padding))
            else -> {
                val children = state.bills.filter { it.linkedBillId == sourceId }.sortedWith(compareBy({ it.occurredAt }, { it.id }))
                val remaining = source.amount.minor - children.filter { it.kind in DebtRules.principalKinds }.sumOf { it.amount.minor }
                val repay = if (source.kind == BillKind.DEBT_BORROW) BillKind.DEBT_REPAY else BillKind.DEBT_COLLECT
                val interest = if (source.kind == BillKind.DEBT_BORROW) BillKind.INTEREST_EXPENSE else BillKind.INTEREST_INCOME
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(billKindLabel(source.kind), style = MaterialTheme.typography.titleMedium)
                    if (source.note.isNotBlank()) Text(source.note)
                    Text(stringResource(R.string.debt_source_amount, "${source.amount.currency.code} ${source.amount.decimalText()}"))
                    Text(stringResource(R.string.debt_remaining, "${source.amount.currency.code} ${source.amount.copy(minor = remaining).decimalText()}"),
                        Modifier.testTag("debt_remaining"), style = MaterialTheme.typography.titleLarge)
                    FilledTonalButton({ onCreate(source, repay) }, Modifier.testTag("debt_repay"), enabled = remaining > 0) {
                        Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(billKindLabel(repay))
                    }
                    OutlinedButton({ onCreate(source, interest) }, Modifier.testTag("debt_interest")) {
                        Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(billKindLabel(interest))
                    }
                    HorizontalDivider()
                    children.forEach { bill ->
                        ListItem(headlineContent = { MoneyText(billAmountText(bill)) }, overlineContent = { Text(billKindLabel(bill.kind)) },
                            supportingContent = { Text(listOf(bill.occurredAt.atZone(bill.zoneId).toLocalDate().toString(), bill.note).filter(String::isNotBlank).joinToString("\n")) },
                            modifier = Modifier.testTag("debt_bill_${bill.id}").clickable { onBill(bill.id) })
                    }
                    if (children.isEmpty()) Text(stringResource(R.string.no_settlements))
                }
            }
        }
    }
}
