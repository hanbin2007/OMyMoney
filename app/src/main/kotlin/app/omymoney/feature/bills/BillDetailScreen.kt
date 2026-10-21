package app.omymoney.feature.bills

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.R
import app.omymoney.core.ledger.LedgerRepository
import app.omymoney.core.ledger.LedgerMutationException
import app.omymoney.core.ledger.LinkedBillRules
import app.omymoney.core.ledger.DebtRules
import app.omymoney.core.model.*
import app.omymoney.data.AttachmentStore
import app.omymoney.feature.catalog.CatalogState
import app.omymoney.feature.catalog.CatalogViewModel
import app.omymoney.feature.automation.applicationLabel
import app.omymoney.ui.components.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class BillDetailState(val bill: Bill? = null, val loading: Boolean = true, val failed: Boolean = false)

class BillDetailViewModel(private val ledger: LedgerRepository, private val id: String) : ViewModel() {
    private val result = MutableStateFlow(BillDetailState())
    val state = result.asStateFlow()
    private val deletingState = MutableStateFlow(false)
    val deleting = deletingState.asStateFlow()
    private val deletionState = MutableStateFlow<UndoToken?>(null)
    val deletion = deletionState.asStateFlow()
    private val errorState = MutableStateFlow<String?>(null)
    val deleteFailed = errorState.asStateFlow()
    private var observation: kotlinx.coroutines.Job? = null
    init { retry() }
    fun retry() {
        observation?.cancel()
        observation = viewModelScope.launch {
            result.value = BillDetailState()
            ledger.observeBill(id).map { BillDetailState(it, loading = false) }
                .catch { emit(BillDetailState(loading = false, failed = true)) }.collect { result.value = it }
        }
    }
    fun delete() {
        if (deletingState.value || deletionState.value != null) return
        deletingState.value = true
        errorState.value = null
        viewModelScope.launch(Dispatchers.Main.immediate) {
            try { deletionState.value = ledger.delete(id) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { errorState.value = (error as? LedgerMutationException)?.code ?: "storage" }
            finally { deletingState.value = false }
        }
    }
}

@Composable
fun BillDetailScreen(viewModel: BillDetailViewModel, catalog: CatalogViewModel, catalogState: CatalogState,
                     onBack: () -> Unit, onEdit: (Bill) -> Unit, onDeleted: (UndoToken) -> Unit, snackbar: SnackbarHostState,
                     attachments: AttachmentStore, onLinked: (Bill) -> Unit = {}, onSource: (String) -> Unit = {}, onDebt: (Bill) -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val deleting by viewModel.deleting.collectAsStateWithLifecycle()
    val deletion by viewModel.deletion.collectAsStateWithLifecycle()
    val failed by viewModel.deleteFailed.collectAsStateWithLifecycle()
    LaunchedEffect(deletion) { deletion?.let(onDeleted) }
    BackHandler(enabled = deleting) { }
    val bill = state.bill
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text(stringResource(R.string.delete_linked_title)) },
        text = { Text(stringResource(R.string.delete_linked_message)) },
        confirmButton = { TextButton({ confirmDelete = false; viewModel.delete() }, Modifier.testTag("linked_delete_confirm")) { Text(stringResource(R.string.delete_bill)) } },
        dismissButton = { TextButton({ confirmDelete = false }, Modifier.testTag("linked_delete_cancel")) { Text(stringResource(R.string.cancel)) } })
    ScreenScaffold(stringResource(R.string.bill_details), onBack, backEnabled = !deleting, snackbarHostState = snackbar, actions = {
        if (bill != null) {
            LedgerIconButton(Icons.Outlined.Edit, stringResource(R.string.edit_bill), { onEdit(bill) }, Modifier.testTag("bill_edit"), enabled = !deleting)
            LedgerIconButton(Icons.Outlined.Delete, stringResource(R.string.delete_bill), {
                if (bill.kind in LinkedBillRules.settlementKinds + DebtRules.linkedKinds) confirmDelete = true else viewModel.delete()
            }, Modifier.testTag("bill_delete"), enabled = !deleting)
        }
    }) { padding ->
        when {
            state.loading || deleting -> LoadingState(Modifier.padding(padding))
            state.failed -> Column(Modifier.padding(padding).padding(24.dp)) {
                ErrorText(stringResource(R.string.bill_load_failed))
                TextButton(viewModel::retry) { Text(stringResource(R.string.retry)) }
            }
            bill == null -> EmptyState(stringResource(R.string.bill_not_found), Modifier.padding(padding))
            else -> {
                val categories by remember(bill.bookId) { catalog.observeCategories(bill.bookId, includeArchived = true) }.collectAsStateWithLifecycle(emptyList())
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).testTag("bill_detail"),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (failed != null) ErrorText(stringResource(if (failed == "linked_records") R.string.source_has_links else R.string.bill_delete_failed))
                        Text(billKindLabel(bill.kind), style = MaterialTheme.typography.titleMedium)
                        MoneyText(billAmountText(bill), Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall)
                        bill.exchange?.let { exchange ->
                            DetailField(stringResource(R.string.currency_actual), "${exchange.amount.currency.code} ${exchange.amount.decimalText()}", Modifier.testTag("detail_exchange_amount"))
                            Text("1 ${bill.amount.currency.code} = ${exchange.rate} ${exchange.amount.currency.code}")
                            Text(stringResource(R.string.currency_rate_dated, exchange.asOf.toString()))
                            val difference = runCatching { app.omymoney.core.ledger.ExchangeRules.difference(bill.amount, exchange) }.getOrNull()
                            if (difference == null) ErrorText(stringResource(R.string.error_overflow))
                            else Text(stringResource(R.string.currency_difference, "${difference.currency.code} ${difference.decimalText()}"))
                        }
                        bill.fee?.let { DetailField(stringResource(R.string.currency_fee), "${it.currency.code} ${it.decimalText()}", Modifier.testTag("detail_fee")) }
                        if (bill.kind in listOf(BillKind.EXPENSE, BillKind.REIMBURSEMENT)) TextButton({ onLinked(bill) }, Modifier.testTag("bill_linked")) {
                            Text(stringResource(if (bill.kind == BillKind.REIMBURSEMENT) R.string.reimbursement_records else R.string.refund_records))
                        }
                        bill.linkedBillId?.let { id -> TextButton({ onSource(id) }, Modifier.testTag("bill_source_link")) { Text(stringResource(R.string.linked_source)) } }
                        if (bill.kind in DebtRules.sourceKinds) TextButton({ onDebt(bill) }, Modifier.testTag("bill_debt_records")) { Text(stringResource(R.string.debt_records)) }
                        HorizontalDivider()
                        DetailField(stringResource(R.string.book), catalogState.books.firstOrNull { it.id == bill.bookId }?.name ?: bill.bookId)
                        bill.payment?.counterparty?.takeIf(String::isNotBlank)?.let {
                            DetailField(stringResource(R.string.payment_counterparty), it, Modifier.testTag("detail_counterparty"))
                        }
                        categories.firstOrNull { it.id == bill.categoryId }?.let { DetailField(stringResource(R.string.category), it.name) }
                        bill.fromAccountId?.let { id -> DetailField(stringResource(if (bill.kind == BillKind.TRANSFER) R.string.from_account else R.string.paying_account),
                            catalogState.accounts.firstOrNull { it.id == id }?.name ?: stringResource(R.string.unavailable_account)) }
                        bill.toAccountId?.let { id -> DetailField(stringResource(if (bill.kind == BillKind.TRANSFER) R.string.to_account else R.string.receiving_account),
                            catalogState.accounts.firstOrNull { it.id == id }?.name ?: stringResource(R.string.unavailable_account)) }
                        if (app.omymoney.core.ledger.PostingCalculator.allowsUnassignedAccounts(bill.toDraft())) {
                            if (bill.fromAccountId == null && bill.kind != BillKind.INCOME) DetailField(stringResource(R.string.paying_account),
                                unassignedFundingLabel(bill.toDraft(), true), Modifier.testTag("detail_unknown_from"))
                            if (bill.toAccountId == null && bill.kind != BillKind.EXPENSE) DetailField(stringResource(R.string.receiving_account),
                                unassignedFundingLabel(bill.toDraft(), false), Modifier.testTag("detail_unknown_to"))
                        }
                        DetailField(stringResource(R.string.transaction_time), bill.occurredAt.atZone(bill.zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")) +
                            if (bill.timeSource == TimeSource.CAPTURED) "\n" + stringResource(R.string.time_captured) else "", Modifier.testTag("detail_time"))
                        if (bill.note.isNotBlank()) DetailField(stringResource(R.string.note), bill.note, Modifier.testTag("detail_note"))
                        if (bill.tags.isNotEmpty()) DetailField(stringResource(R.string.filter_tag), bill.tags.joinToString(" · "))
                        if (bill.attachments.isNotEmpty()) AttachmentGallery(bill.attachments, attachments)
                        DetailField(stringResource(R.string.bill_source), if (bill.source in setOf(BillSource.ACCESSIBILITY, BillSource.NOTIFICATION) && bill.sourcePackage != null)
                            applicationLabel(requireNotNull(bill.sourcePackage)) else billSourceLabel(bill.source), Modifier.testTag("detail_source"))
                        if (bill.excludeStats) Text(stringResource(R.string.exclude_stats))
                        if (bill.excludeBudget) Text(stringResource(R.string.exclude_budget))
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun billSourceLabel(source: BillSource): String = stringResource(when (source) {
    BillSource.MANUAL -> R.string.source_manual
    BillSource.ACCESSIBILITY -> R.string.source_accessibility
    BillSource.OCR -> R.string.source_ocr
    BillSource.IMPORT -> R.string.source_import
    BillSource.SCHEDULE -> R.string.source_schedule
    BillSource.NOTIFICATION -> R.string.source_notification
})
