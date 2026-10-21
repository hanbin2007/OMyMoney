package app.omymoney.feature.linked

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.R
import app.omymoney.core.ledger.LedgerRepository
import app.omymoney.core.ledger.LinkedBillRules
import app.omymoney.core.model.*
import app.omymoney.feature.bills.billAmountText
import app.omymoney.ui.components.*
import java.math.BigDecimal
import java.math.BigInteger
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private data class LinkedState(val bills: List<Bill> = emptyList(), val loading: Boolean = true, val failed: Boolean = false)

@Composable
fun LinkedBillScreen(ledger: LedgerRepository, sourceId: String, onBack: () -> Unit,
                     onBill: (String) -> Unit, onCreate: (Bill, BillKind) -> Unit, snackbar: SnackbarHostState) {
    var attempt by remember { mutableIntStateOf(0) }
    val flow = remember(ledger, sourceId, attempt) {
        ledger.observeBills(BillQuery()).map { LinkedState(it, loading = false) }
            .catch { emit(LinkedState(loading = false, failed = true)) }
    }
    val state by flow.collectAsStateWithLifecycle(LinkedState())
    val source = state.bills.firstOrNull { it.id == sourceId }
    val reimbursement = source?.kind == BillKind.REIMBURSEMENT
    ScreenScaffold(stringResource(if (reimbursement) R.string.reimbursement_records else R.string.refund_records), onBack,
        snackbarHostState = snackbar) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.failed -> Column(Modifier.padding(padding).padding(16.dp)) {
                ErrorText(stringResource(R.string.bill_load_failed))
                TextButton({ attempt++ }) { Text(stringResource(R.string.retry)) }
            }
            source == null || source.kind !in listOf(BillKind.EXPENSE, BillKind.REIMBURSEMENT) ->
                EmptyState(stringResource(R.string.bill_not_found), Modifier.padding(padding))
            else -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                val children = state.bills.filter { it.linkedBillId == sourceId }.sortedWith(compareBy({ it.occurredAt }, { it.id }))
                val remaining = if (reimbursement) LinkedBillRules.remainingReimbursement(source, children) else {
                    val reductions = LinkedBillRules.refundEffects(source, children).values.sumOf { it.expenseReduction.minor }
                    source.amount.copy(minor = source.amount.minor - reductions)
                }
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (source.note.isNotEmpty()) Text(source.note, style = MaterialTheme.typography.titleMedium)
                    Text(source.occurredAt.atZone(source.zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")))
                    MoneyText("${source.amount.currency.code} ${source.amount.decimalText()}", style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(if (reimbursement) R.string.remaining_reimbursement else R.string.net_expense,
                        "${remaining.currency.code} ${remaining.decimalText()}"), Modifier.testTag("linked_summary"), style = MaterialTheme.typography.titleLarge)
                    if (!reimbursement) {
                        val excess = LinkedBillRules.refundEffects(source, children).values.fold(BigInteger.ZERO) { total, effect ->
                            total + BigInteger.valueOf(effect.income.minor)
                        }
                        Text(stringResource(R.string.refund_income, "${source.amount.currency.code} ${BigDecimal(excess, source.amount.currency.scale).toPlainString()}"))
                    }
                    FilledTonalButton({ onCreate(source, if (reimbursement) BillKind.REIMBURSED else BillKind.REFUND) },
                        Modifier.testTag("linked_add"), enabled = !reimbursement || remaining.minor > 0) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (reimbursement) R.string.add_reimbursement else R.string.add_refund))
                    }
                    HorizontalDivider()
                    if (children.isEmpty()) Text(stringResource(R.string.no_settlements))
                    children.forEach { bill ->
                        ListItem(headlineContent = { MoneyText(billAmountText(bill)) },
                            supportingContent = { Text(listOf(bill.occurredAt.atZone(bill.zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")), bill.note)
                                .filter(String::isNotEmpty).joinToString("\n")) },
                            modifier = Modifier.fillMaxWidth().testTag("linked_row_${bill.id}").clickable { onBill(bill.id) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
