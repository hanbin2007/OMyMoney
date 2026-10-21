package app.omymoney.feature.assets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.R
import app.omymoney.core.ledger.DebtRules
import app.omymoney.core.ledger.LedgerRepository
import app.omymoney.core.ledger.PostingCalculator
import app.omymoney.core.model.*
import app.omymoney.feature.bills.billKindLabel
import app.omymoney.feature.catalog.CatalogState
import app.omymoney.ui.components.*
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private data class AssetBills(val items: List<Bill> = emptyList(), val loading: Boolean = true, val failed: Boolean = false)

@Composable
fun AssetDetailScreen(accountId: String, state: CatalogState, ledger: LedgerRepository, snackbar: SnackbarHostState,
                      onBack: () -> Unit, onEdit: () -> Unit, onCreditSettings: () -> Unit,
                      onCreate: (Book, AssetAccount, BillKind) -> Unit, onBill: (String) -> Unit, onHistory: () -> Unit = {}) {
    var attempt by remember { mutableIntStateOf(0) }
    val flow = remember(ledger, accountId, attempt) {
        ledger.observeBills(BillQuery()).map { AssetBills(it, loading = false) }.catch { emit(AssetBills(loading = false, failed = true)) }
    }
    val bills by flow.collectAsStateWithLifecycle(AssetBills())
    val account = state.accounts.firstOrNull { it.id == accountId }
    var adjustBook by rememberSaveable { mutableStateOf<String?>(null) }
    ScreenScaffold(account?.name ?: stringResource(R.string.transaction), onBack, snackbarHostState = snackbar, actions = {
        if (account != null) LedgerIconButton(Icons.Outlined.Edit, stringResource(R.string.edit_account), onEdit, Modifier.testTag("asset_edit"))
    }) { padding ->
        when {
            !state.initialized || bills.loading -> LoadingState(Modifier.padding(padding))
            account == null -> EmptyState(stringResource(R.string.error_not_found), Modifier.padding(padding))
            bills.failed -> Column(Modifier.padding(padding).padding(16.dp)) {
                ErrorText(stringResource(R.string.bill_load_failed))
                TextButton({ attempt++ }) { Text(stringResource(R.string.retry)) }
            }
            else -> {
                val history = bills.items.filter { it.fromAccountId == accountId || it.toAccountId == accountId }
                LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("asset_transactions"),
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(stringResource(R.string.asset_current_balance), style = MaterialTheme.typography.labelLarge)
                        MoneyText("${account.currency.code} ${account.balance.decimalText()}", Modifier.fillMaxWidth().testTag("asset_balance"), MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.asset_all_books), style = MaterialTheme.typography.bodySmall)
                        if (account.archived) Text(stringResource(R.string.archived))
                    }
                    item { Text("${stringResource(R.string.opening_balance)}: ${account.currency.code} ${account.openingBalance.decimalText()}") }
                    item { TextButton(onHistory, Modifier.testTag("asset_history")) {
                        Icon(Icons.Outlined.History, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.asset_history_title))
                    } }
                    if (account.kind in DebtRules.debtAccountKinds) item {
                        val position = runCatching { DebtRules.position(accountId, bills.items) }.getOrNull()
                        if (position == null) ErrorText(stringResource(R.string.error_overflow)) else {
                            Text("${stringResource(R.string.asset_principal)}: ${account.currency.code} ${Money(position.principal, account.currency).decimalText()}")
                            Text("${stringResource(R.string.asset_interest)}: ${account.currency.code} ${Money(position.interest, account.currency).decimalText()}")
                        }
                    }
                    if (account.kind == AccountKind.CREDIT) item {
                        account.creditTerms?.let { terms ->
                            val cycle = CreditCycles.latestStatement(terms, LocalDate.now(state.currentBook?.zoneId ?: java.time.ZoneId.systemDefault()))
                            Text(stringResource(R.string.credit_cycle_range, cycle.start.toString(), cycle.statement.toString()))
                            Text(stringResource(R.string.credit_due_date, cycle.due.toString()))
                            terms.creditLimit?.let { Text(stringResource(R.string.credit_limit_value, "${it.currency.code} ${it.decimalText()}")) }
                            Text(stringResource(R.string.credit_local_only), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onCreditSettings, Modifier.testTag("asset_credit_settings")) {
                            Icon(Icons.Outlined.CalendarMonth, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.credit_settings))
                        }
                    }
                    if (!account.archived) item {
                        val book = state.currentBook
                        if (book == null) ErrorText(stringResource(R.string.asset_no_book)) else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val entry = when (account.kind) {
                                AccountKind.LIABILITY -> BillKind.DEBT_BORROW
                                AccountKind.RECEIVABLE -> BillKind.DEBT_LEND
                                AccountKind.CREDIT -> BillKind.CREDIT_PAYMENT
                                else -> null
                            }
                            entry?.let { kind -> FilledTonalButton({ onCreate(book, account, kind) }, Modifier.testTag("asset_new_debt")) {
                                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(billKindLabel(kind))
                            } }
                            OutlinedButton({ adjustBook = book.id }, Modifier.testTag("asset_adjust")) {
                                Icon(Icons.Outlined.Tune, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.balance_adjustment))
                            }
                        }
                    }
                    item { HorizontalDivider() }
                    items(history, key = { it.id }) { bill ->
                        val change = PostingCalculator.calculate(bill.toDraft()).filter { it.accountId == accountId }
                            .fold(Money(0, account.currency)) { total, posting -> total + posting.amount }
                        ListItem(headlineContent = { MoneyText("${change.currency.code} ${if (change.minor > 0) "+" else ""}${change.decimalText()}") },
                            overlineContent = { Text(billKindLabel(bill.kind)) },
                            supportingContent = { Text(listOf(state.books.firstOrNull { it.id == bill.bookId }?.name.orEmpty(),
                                bill.occurredAt.atZone(bill.zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), bill.note)
                                .filter(String::isNotBlank).joinToString("\n")) },
                            modifier = Modifier.fillMaxWidth().testTag("asset_bill_${bill.id}").clickable { onBill(bill.id) })
                        HorizontalDivider()
                    }
                    if (history.isEmpty()) item { Text(stringResource(R.string.no_settlements)) }
                }
            }
        }
    }
    if (adjustBook != null && account != null) {
        val book = state.books.firstOrNull { it.id == adjustBook && !it.archived }
        if (book == null || account.archived) LaunchedEffect(Unit) { adjustBook = null }
        else AdjustmentDialog(account, book, ledger) { adjustBook = null }
    }
}

@Composable
private fun AdjustmentDialog(account: AssetAccount, book: Book, ledger: LedgerRepository, onDismiss: () -> Unit) {
    var amount by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var increase by rememberSaveable { mutableStateOf(true) }
    val submission = rememberSaveable { UUID.randomUUID().toString() }
    val occurredAt = rememberSaveable { Instant.now().toString() }
    var saving by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var invalidField by rememberSaveable { mutableStateOf<String?>(null) }
    var validationAttempt by remember { mutableIntStateOf(0) }
    val fields = remember { listOf("amount", "reason").associateWith { FocusRequester() } }
    val positions = remember { listOf("amount", "reason", "form").associateWith { BringIntoViewRequester() } }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(validationAttempt) {
        val target = invalidField ?: return@LaunchedEffect
        fields[target]?.requestFocus() ?: run { focus.clearFocus(); keyboard?.hide() }
        withFrameNanos { }
        positions.getValue(target).bringIntoView()
    }
    BackHandler(saving) { }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(stringResource(R.string.balance_adjustment)) },
        modifier = Modifier.safeDrawingPadding().imePadding(), properties = DialogProperties(decorFitsSystemWindows = false),
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.asset_adjust_target, account.name))
                Text(stringResource(R.string.asset_adjust_book, book.name))
                Column(Modifier.bringIntoViewRequester(positions.getValue("form"))) {
                    if (error != null) ErrorText(stringResource(if (error == "overflow") R.string.error_overflow else R.string.bill_save_failed))
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(true, false).forEachIndexed { index, value ->
                        SegmentedButton(increase == value, { increase = value; error = null }, SegmentedButtonDefaults.itemShape(index, 2), enabled = !saving) {
                            Text(stringResource(if (value) R.string.asset_adjust_increase else R.string.asset_adjust_decrease))
                        }
                    }
                }
                OutlinedTextField(amount, { amount = it; error = null; invalidField = null }, Modifier.fillMaxWidth().testTag("adjust_amount")
                    .bringIntoViewRequester(positions.getValue("amount")).focusRequester(fields.getValue("amount")), enabled = !saving,
                    label = { Text(stringResource(R.string.asset_adjust_amount)) }, prefix = { Text(account.currency.code) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = invalidField == "amount",
                    supportingText = if (invalidField == "amount") { { ErrorText(stringResource(R.string.asset_adjust_invalid_amount), Modifier.testTag("adjust_amount_error")) } } else null)
                OutlinedTextField(reason, { reason = it; error = null; invalidField = null }, Modifier.fillMaxWidth().testTag("adjust_reason")
                    .bringIntoViewRequester(positions.getValue("reason")).focusRequester(fields.getValue("reason")), enabled = !saving,
                    label = { Text(stringResource(R.string.asset_adjust_reason)) }, minLines = 2, maxLines = 4, isError = invalidField == "reason",
                    supportingText = if (invalidField == "reason") { { ErrorText(stringResource(R.string.asset_adjust_invalid_reason), Modifier.testTag("adjust_reason_error")) } } else null)
                Text(stringResource(R.string.asset_adjust_excluded), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton({
            val money = runCatching { Money.parse(amount, account.currency) }.getOrNull()
            invalidField = when {
                money == null || money.minor <= 0 -> "amount"
                reason.isBlank() || reason.length > 4000 -> "reason"
                else -> null
            }
            if (invalidField != null) validationAttempt++
            else if (!saving) {
                saving = true
                scope.launch(Dispatchers.Main.immediate) {
                    try {
                        val result = ledger.save(TransactionDraft(book.id, submissionId = submission, kind = BillKind.ADJUSTMENT,
                            amount = requireNotNull(money), fromAccountId = account.id.takeUnless { increase }, toAccountId = account.id.takeIf { increase },
                            note = reason.trim(), occurredAt = Instant.parse(occurredAt), timeSource = TimeSource.USER, zoneId = book.zoneId,
                            excludeStats = true, excludeBudget = true), null)
                        if (result is SaveResult.Saved) onDismiss()
                        else error = (result as? SaveResult.Invalid)?.fieldErrors?.firstOrNull()?.code ?: "storage"
                    } catch (cancel: CancellationException) { throw cancel
                    } catch (_: Exception) { error = "storage"
                    } finally {
                        saving = false
                        if (error != null) { invalidField = "form"; validationAttempt++ }
                    }
                }
            }
        }, Modifier.testTag("adjust_confirm"), enabled = !saving) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss, Modifier.testTag("adjust_cancel"), enabled = !saving) { Text(stringResource(R.string.cancel)) } })
}
