package app.omymoney.feature.savings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.R
import app.omymoney.core.model.*
import app.omymoney.core.savings.*
import app.omymoney.data.*
import app.omymoney.ui.components.*
import java.math.BigDecimal
import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private data class SavingsView(val plan: SavingsPlan, val progress: SavingsProgress)
private data class SavingsViewState(val rows: List<SavingsView>? = null, val failed: Boolean = false)
private fun savingsFlow(store: LedgerStore, bookId: String): Flow<SavingsViewState> = combine(
    store.savings.observePlans(bookId), store.savings.observeBookDeposits(bookId), store.ledger.observeBills(BillQuery(bookId = bookId)),
) { plans, deposits, bills ->
    val byPlan = deposits.groupBy { it.planId }; val byBill = bills.associateBy { it.id }
    SavingsViewState(plans.map { SavingsView(it, SavingsCalculator.progress(it, byPlan[it.id].orEmpty(), byBill)) })
}.catch { emit(SavingsViewState(failed = true)) }

internal fun savingsMoney(minor: BigInteger, currency: CurrencySpec) = "${currency.code} ${BigDecimal(minor, currency.scale).toPlainString()}"
internal fun savingsMoney(amount: Money) = "${amount.currency.code} ${amount.decimalText()}"
@Composable internal fun savingsModeLabel(mode: SavingsMode) = stringResource(when (mode) {
    SavingsMode.FIXED -> R.string.savings_fixed
    SavingsMode.INCREASING -> R.string.savings_increasing
    SavingsMode.DECREASING -> R.string.savings_decreasing
    SavingsMode.CUSTOM -> R.string.savings_custom
})
@Composable internal fun savingsError(code: String) = stringResource(when (code) {
    "name" -> R.string.savings_error_name
    "amount" -> R.string.savings_error_amount
    "increment" -> R.string.savings_error_increment
    "count" -> R.string.savings_error_count
    "interval" -> R.string.savings_error_interval
    "date" -> R.string.savings_error_date
    "end" -> R.string.savings_error_end
    "range" -> R.string.savings_error_range
    "note" -> R.string.savings_error_note
    "overflow" -> R.string.savings_error_overflow
    "currency_locked" -> R.string.savings_currency_locked
    "transfer" -> R.string.savings_error_transfer
    "duplicate_transfer" -> R.string.savings_error_duplicate
    "paused" -> R.string.savings_error_paused
    "conflict" -> R.string.savings_error_conflict
    else -> R.string.savings_error_storage
})

@Composable
fun SavingsScreen(store: LedgerStore, book: Book, onBack: () -> Unit, onNew: () -> Unit, onOpen: (String) -> Unit) {
    var retry by remember { mutableIntStateOf(0) }
    val flow = remember(store, book.id, retry) { savingsFlow(store, book.id) }
    val state by flow.collectAsStateWithLifecycle(SavingsViewState())
    var sorting by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    fun move(id: String, direction: Int) {
        if (busy) return
        busy = true; snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            val failed = try { store.savings.move(id, direction) !is SavingsResult.Saved }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { true }
            finally { busy = false }
            if (failed && snackbar.showSnackbar(context.getString(R.string.savings_error_storage),
                    actionLabel = context.getString(R.string.retry), withDismissAction = true,
                    duration = SnackbarDuration.Indefinite) == SnackbarResult.ActionPerformed) move(id, direction)
        }
    }
    ScreenScaffold(stringResource(R.string.savings_title), onBack, snackbarHostState = snackbar, actions = {
        LedgerIconButton(if (sorting) Icons.Outlined.Done else Icons.Outlined.Sort, stringResource(if (sorting) R.string.savings_finish_sort else R.string.savings_sort),
            { sorting = !sorting }, Modifier.testTag("sav_sort"), enabled = !busy)
        LedgerIconButton(Icons.Outlined.Add, stringResource(R.string.savings_new), onNew, Modifier.testTag("sav_new"), enabled = !busy)
    }) { padding ->
        when {
            state.failed -> Column(Modifier.padding(padding).padding(16.dp)) {
                ErrorText(stringResource(R.string.savings_load_failed)); TextButton({ retry++ }) { Text(stringResource(R.string.retry)) }
            }
            state.rows == null -> LoadingState(Modifier.padding(padding))
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("sav_plans"), contentPadding = PaddingValues(bottom = 16.dp)) {
                item { Text(book.name, Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall) }
                if (state.rows.orEmpty().isEmpty()) item { EmptyState(stringResource(R.string.savings_empty)) }
                items(state.rows.orEmpty(), key = { it.plan.id }) { row ->
                    val plan = row.plan; val progress = row.progress
                    ListItem(modifier = Modifier.testTag("sav_plan_${plan.id}").clickable(enabled = !busy) { onOpen(plan.id) },
                        headlineContent = { Text(plan.name) }, supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(savingsModeLabel(plan.rule.mode) + " · " + stringResource(when {
                                    plan.paused -> R.string.savings_paused
                                    progress.completed -> R.string.savings_completed
                                    else -> R.string.savings_active
                                }))
                                Text(stringResource(R.string.savings_saved, savingsMoney(progress.savedMinor, progress.target.currency)))
                                Text(stringResource(R.string.savings_target, savingsMoney(progress.target)))
                                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                                if (progress.records.any { it.problem != null }) ErrorText(stringResource(R.string.savings_invalid_links))
                            }
                        }, trailingContent = if (sorting) { {
                            Column {
                                LedgerIconButton(Icons.Outlined.ArrowUpward, stringResource(R.string.savings_move_up, plan.name), { move(plan.id, -1) },
                                    Modifier.testTag("sav_up_${plan.id}"), enabled = !busy && state.rows?.firstOrNull()?.plan?.id != plan.id)
                                LedgerIconButton(Icons.Outlined.ArrowDownward, stringResource(R.string.savings_move_down, plan.name), { move(plan.id, 1) },
                                    Modifier.testTag("sav_down_${plan.id}"), enabled = !busy && state.rows?.lastOrNull()?.plan?.id != plan.id)
                            }
                        } } else null)
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavingsDetailScreen(store: LedgerStore, book: Book, planId: String, onBack: () -> Unit, onEdit: () -> Unit,
    onDeposit: (String?) -> Unit, onBill: (String) -> Unit) {
    var retry by remember { mutableIntStateOf(0) }
    val flow = remember(store, book.id, retry) { savingsFlow(store, book.id) }
    val state by flow.collectAsStateWithLifecycle(SavingsViewState())
    val row = state.rows?.firstOrNull { it.plan.id == planId }
    val plan = row?.plan
    var showSchedule by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var remove by remember { mutableStateOf<ResolvedSavingsDeposit?>(null) }
    var delete by remember { mutableStateOf<SavingsPlan?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    fun operation(block: suspend () -> SavingsResult<*>) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { error = when (val result = block()) {
                is SavingsResult.Saved -> null
                is SavingsResult.Invalid -> result.code
                SavingsResult.Conflict -> "conflict"
                SavingsResult.Failure -> "storage"
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "storage" }
            finally { busy = false }
            if (error != null) { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(context.getString(R.string.savings_error_storage)) }
        }
    }
    ScreenScaffold(stringResource(R.string.savings_detail), onBack, snackbarHostState = snackbar, actions = {
        LedgerIconButton(Icons.Outlined.Delete, stringResource(R.string.savings_delete_plan), { delete = plan }, Modifier.testTag("sav_delete_plan"), enabled = plan != null && !busy)
        LedgerIconButton(Icons.Outlined.Edit, stringResource(R.string.savings_edit), onEdit, Modifier.testTag("sav_edit"), enabled = plan != null && !busy)
        LedgerIconButton(Icons.Outlined.Add, stringResource(R.string.savings_add), { onDeposit(null) }, Modifier.testTag("sav_add"), enabled = plan != null && !plan.paused && !busy)
    }) { padding ->
        when {
            state.failed -> Column(Modifier.padding(padding).padding(16.dp)) {
                ErrorText(stringResource(R.string.savings_load_failed)); TextButton({ retry++ }) { Text(stringResource(R.string.retry)) }
            }
            state.rows == null -> LoadingState(Modifier.padding(padding))
            row == null -> EmptyState(stringResource(R.string.error_not_found), Modifier.padding(padding))
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("sav_detail"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { Column(Modifier.fillMaxWidth().testTag("sav_summary").semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.plan.name, style = MaterialTheme.typography.titleLarge)
                    Text(book.name, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.savings_target, savingsMoney(row.progress.target)))
                    Text(stringResource(R.string.savings_saved, savingsMoney(row.progress.savedMinor, row.progress.target.currency)), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.savings_remaining, savingsMoney(row.progress.remainingMinor, row.progress.target.currency)))
                    if (row.progress.completed) Text(stringResource(R.string.savings_excess, savingsMoney(row.progress.excessMinor, row.progress.target.currency)))
                    Text("${(row.progress.fraction * 100).toInt()}% · " + stringResource(when {
                        row.plan.paused -> R.string.savings_paused
                        row.progress.completed -> R.string.savings_completed
                        else -> R.string.savings_active
                    }))
                    LinearProgressIndicator(progress = { row.progress.fraction }, modifier = Modifier.fillMaxWidth())
                    if (row.plan.note.isNotBlank()) Text(row.plan.note)
                } }
                error?.let { item { ErrorText(savingsError(it)) } }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item { Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sav_enabled")
                    .toggleable(!row.plan.paused, enabled = !busy, role = Role.Switch) { enabled -> operation { store.savings.savePlan(row.plan.copy(paused = !enabled)) } },
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(stringResource(R.string.savings_enabled), Modifier.weight(1f))
                    Switch(!row.plan.paused, null, enabled = !busy)
                } }
                item { PrimaryTabRow(selectedTabIndex = if (showSchedule) 1 else 0) {
                    Tab(!showSchedule, { showSchedule = false }, Modifier.testTag("sav_tab_deposits"), text = { Text(stringResource(R.string.savings_records)) })
                    Tab(showSchedule, { showSchedule = true }, Modifier.testTag("sav_tab_schedule"), text = { Text(stringResource(R.string.savings_schedule)) })
                } }
                if (showSchedule) {
                    val steps = SavingsCalculator.steps(row.plan.rule)
                    if (steps.isEmpty()) item { Text(stringResource(R.string.savings_custom_dates, row.plan.rule.start.toString(), row.plan.rule.end?.toString() ?: stringResource(R.string.savings_no_end))) }
                    items(steps, key = { "step/${it.index}" }) { step ->
                        ListItem(modifier = Modifier.testTag("sav_step_${step.index}").semantics(mergeDescendants = true) {},
                            headlineContent = { Text(stringResource(R.string.savings_step, step.index + 1, step.date.toString())) },
                            supportingContent = { Text(savingsMoney(step.amount)) })
                        HorizontalDivider()
                    }
                } else {
                    if (row.progress.records.isEmpty()) item { Text(stringResource(R.string.savings_no_records)) }
                    items(row.progress.records.sortedWith(compareByDescending<ResolvedSavingsDeposit> { it.date }.thenBy { it.deposit.id }), key = { it.deposit.id }) { record ->
                        ListItem(modifier = Modifier.testTag("sav_deposit_${record.deposit.id}").clickable(enabled = !busy) { onDeposit(record.deposit.id) },
                            headlineContent = { Text(record.date.toString()) }, supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(stringResource(if (record.deposit.linkedBillId == null) R.string.savings_manual else R.string.savings_linked))
                                    record.amount?.let { Text(savingsMoney(it), style = MaterialTheme.typography.titleMedium) }
                                    if (record.problem != null) ErrorText(stringResource(if (record.problem == SavingsDepositProblem.MISSING_TRANSFER)
                                        R.string.savings_missing_link else R.string.savings_changed_link))
                                    if (record.deposit.note.isNotBlank()) Text(record.deposit.note)
                                }
                            }, trailingContent = {
                                Column {
                                    if (record.deposit.linkedBillId != null && record.problem != SavingsDepositProblem.MISSING_TRANSFER)
                                        LedgerIconButton(Icons.Outlined.OpenInNew, stringResource(R.string.savings_open_transfer), { onBill(requireNotNull(record.deposit.linkedBillId)) }, enabled = !busy)
                                    LedgerIconButton(Icons.Outlined.Delete, stringResource(R.string.savings_remove_label, record.date.toString()),
                                        { remove = record }, Modifier.testTag("sav_remove_${record.deposit.id}"), enabled = !busy)
                                }
                            })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    remove?.let { record -> AlertDialog(onDismissRequest = { remove = null }, title = { Text(stringResource(R.string.savings_remove_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(record.date.toString()); record.amount?.let { Text(savingsMoney(it)) }; Text(stringResource(R.string.savings_remove_detail))
        } }, confirmButton = { TextButton({ remove = null; operation { store.savings.removeDeposit(record.deposit.id, record.deposit.revision) } },
            Modifier.testTag("sav_remove_confirm")) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton({ remove = null }) { Text(stringResource(R.string.cancel)) } }) }
    delete?.let { target -> AlertDialog(onDismissRequest = { delete = null }, title = { Text(stringResource(R.string.savings_delete_plan)) },
        text = { Text(stringResource(R.string.savings_delete_detail, target.name), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton({ delete = null; operation {
            store.savings.removePlan(target.id, target.revision).also { if (it is SavingsResult.Saved) onBack() }
        } }, Modifier.testTag("sav_delete_confirm")) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton({ delete = null }) { Text(stringResource(R.string.cancel)) } }) }
}
