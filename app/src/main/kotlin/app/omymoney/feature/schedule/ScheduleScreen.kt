package app.omymoney.feature.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.R
import app.omymoney.core.model.*
import app.omymoney.core.schedule.ScheduleCalculator
import app.omymoney.data.*
import app.omymoney.feature.bills.billKindLabel
import app.omymoney.ui.components.*
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private data class ScheduleListState(val plans: List<ScheduledPlan>? = null, val failed: Boolean = false)

@Composable
fun ScheduleScreen(store: LedgerStore, book: Book, onBack: () -> Unit, onNew: () -> Unit, onOpen: (String) -> Unit) {
    var retry by remember { mutableIntStateOf(0) }
    val flow = remember(store, book.id, retry) { store.schedules.observePlans(book.id).map { ScheduleListState(it) }
        .catch { emit(ScheduleListState(failed = true)) } }
    val state by flow.collectAsStateWithLifecycle(ScheduleListState())
    ScreenScaffold(stringResource(R.string.schedule_title), onBack, actions = {
        LedgerIconButton(Icons.Outlined.Add, stringResource(R.string.schedule_new), onNew, Modifier.testTag("new_schedule"))
    }) { padding ->
        when {
            state.failed -> Column(Modifier.padding(padding).padding(16.dp)) {
                ErrorText(stringResource(R.string.schedule_load_failed)); TextButton({ retry++ }) { Text(stringResource(R.string.retry)) }
            }
            state.plans == null -> LoadingState(Modifier.padding(padding))
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("sch_plans"), contentPadding = PaddingValues(bottom = 16.dp)) {
                item { Text(book.name, Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall) }
                if (state.plans.orEmpty().isEmpty()) item { EmptyState(stringResource(R.string.schedule_no_plans)) }
                items(state.plans.orEmpty(), key = { it.id }) { plan ->
                    val next = ScheduleCalculator.occurrence(plan.rule, plan.nextIndex)
                    ListItem(modifier = Modifier.testTag("sch_plan_${plan.id}").clickable { onOpen(plan.id) },
                        headlineContent = { Text(plan.name) }, supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(if (plan.installments) R.string.schedule_installments else R.string.schedule_recurring) +
                                    " · " + billKindLabel(requireNotNull(plan.template.kind)))
                                plan.template.amount?.let { Text("${it.currency.code} ${it.decimalText()}") }
                                Text(stringResource(if (plan.automatic) R.string.schedule_mode_automatic else R.string.schedule_mode_review))
                                Text(when {
                                    plan.paused -> stringResource(R.string.schedule_paused)
                                    next == null -> stringResource(R.string.schedule_generated)
                                    else -> stringResource(R.string.schedule_next, scheduleTime(next.plannedAt, plan.rule.zone))
                                })
                                Text(stringResource(R.string.schedule_generated_count, plan.nextIndex))
                            }
                        })
                    HorizontalDivider()
                }
            }
        }
    }
}

private data class ScheduleDetailState(val loaded: Boolean = false, val failed: Boolean = false, val plan: ScheduledPlan? = null,
    val executions: List<ScheduleExecution> = emptyList(), val activeBills: Set<String> = emptySet())

@Composable
fun ScheduleDetailScreen(store: LedgerStore, book: Book, planId: String, onBack: () -> Unit, onEdit: () -> Unit,
    onReview: (String) -> Unit, onBill: (String) -> Unit) {
    var retry by remember { mutableIntStateOf(0) }
    val flow = remember(store, book.id, planId, retry) {
        combine(store.schedules.observePlans(book.id), store.schedules.observeExecutions(planId), store.ledger.observeBills(BillQuery(bookId = book.id))) {
            plans, executions, bills -> ScheduleDetailState(true, plan = plans.firstOrNull { it.id == planId }, executions = executions, activeBills = bills.map { it.id }.toSet())
        }.catch { emit(ScheduleDetailState(loaded = true, failed = true)) }
    }
    val state by flow.collectAsStateWithLifecycle(ScheduleDetailState())
    val plan = state.plan
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var resume by rememberSaveable { mutableStateOf(false) }
    var skip by rememberSaveable { mutableStateOf<String?>(null) }
    fun operation(block: suspend () -> String?) {
        if (busy) return
        busy = true; error = false
        scope.launch {
            val message = try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = true; context.getString(R.string.schedule_storage_failed) }
            finally { busy = false }
            if (message != null) { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(message) }
        }
    }
    fun run() = operation {
        val result = store.schedules.executeDue(Instant.now(), planId)
        when {
            result.moreDue -> context.getString(R.string.schedule_more_due)
            result.created == 0 -> context.getString(R.string.schedule_nothing_due)
            else -> context.getString(R.string.schedule_created_count, result.created)
        }
    }
    ScreenScaffold(stringResource(R.string.schedule_detail), onBack, snackbarHostState = snackbar, actions = {
        LedgerIconButton(Icons.Outlined.Refresh, stringResource(R.string.schedule_run), ::run, Modifier.testTag("sch_run"), enabled = plan != null && !plan.paused && !busy)
        LedgerIconButton(Icons.Outlined.Edit, stringResource(R.string.schedule_edit), onEdit, Modifier.testTag("sch_edit"), enabled = plan != null && !busy)
    }) { padding ->
        when {
            !state.loaded -> LoadingState(Modifier.padding(padding))
            state.failed -> Column(Modifier.padding(padding).padding(16.dp)) {
                ErrorText(stringResource(R.string.schedule_load_failed)); TextButton({ retry++ }) { Text(stringResource(R.string.retry)) }
            }
            plan == null -> EmptyState(stringResource(R.string.error_not_found), Modifier.padding(padding))
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("sch_detail"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().testTag("sch_summary").semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(plan.name, style = MaterialTheme.typography.titleLarge)
                        Text(book.name, style = MaterialTheme.typography.titleSmall)
                        Text(billKindLabel(requireNotNull(plan.template.kind)))
                        Text(stringResource(if (plan.installments) R.string.schedule_principal else R.string.schedule_amount, requireNotNull(plan.template.amount).currency.code))
                        plan.template.amount?.let { Text("${it.currency.code} ${it.decimalText()}", style = MaterialTheme.typography.titleMedium) }
                        Text(stringResource(if (plan.automatic) R.string.schedule_mode_automatic else R.string.schedule_mode_review))
                        Text(stringResource(R.string.schedule_generated_count, plan.nextIndex))
                        Text(stringResource(R.string.schedule_counts, state.executions.count { it.state == ScheduleExecutionState.PENDING },
                            state.executions.count { it.state == ScheduleExecutionState.POSTED }, state.executions.count { it.state == ScheduleExecutionState.SKIPPED }))
                        Text(zoneLabel(plan.rule.zone.id), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (error) item { ErrorText(stringResource(R.string.schedule_storage_failed)) }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item {
                    ScheduleSwitch(stringResource(R.string.schedule_enabled), !plan.paused, !busy, Modifier.testTag("sch_enabled")) { enabled ->
                        if (enabled) resume = true else operation { check(store.schedules.setPaused(planId, true) is ScheduleSaveResult.Saved); null }
                    }
                }
                val upcoming = previewOccurrences(plan, plan.nextIndex)
                if (upcoming.isNotEmpty()) {
                    item { Text(stringResource(R.string.schedule_upcoming), style = MaterialTheme.typography.titleMedium) }
                    items(upcoming, key = { "upcoming/${it.index}" }) { SchedulePreviewRow(plan, it, "sch_upcoming_${it.index}") }
                } else item { Text(stringResource(R.string.schedule_generated)) }
                item { Text(stringResource(R.string.schedule_executions), style = MaterialTheme.typography.titleMedium) }
                if (state.executions.isEmpty()) item { Text(stringResource(R.string.schedule_no_executions)) }
                items(state.executions, key = { "execution/${it.id}" }) { execution ->
                    val deleted = execution.state == ScheduleExecutionState.POSTED && execution.billId !in state.activeBills
                    val label = stringResource(when (execution.state) {
                        ScheduleExecutionState.PENDING -> R.string.schedule_pending
                        ScheduleExecutionState.SKIPPED -> R.string.schedule_skipped
                        ScheduleExecutionState.POSTED -> if (deleted) R.string.schedule_bill_deleted else R.string.schedule_posted
                    })
                    val clickable = !busy && (execution.state == ScheduleExecutionState.PENDING || (execution.state == ScheduleExecutionState.POSTED && !deleted))
                    ListItem(modifier = Modifier.testTag("sch_execution_${execution.id}").clickable(enabled = clickable) {
                        if (execution.state == ScheduleExecutionState.PENDING) onReview(execution.id) else execution.billId?.let(onBill)
                    }, headlineContent = { Text(stringResource(R.string.schedule_period, execution.index + 1, scheduleTime(execution.plannedAt, plan.rule.zone))) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(label)
                                execution.template.amount?.let { Text(stringResource(if (execution.state == ScheduleExecutionState.POSTED)
                                    R.string.schedule_recorded_amount else R.string.schedule_planned_amount, "${it.currency.code} ${it.decimalText()}"),
                                    style = MaterialTheme.typography.titleMedium) }
                                if (execution.state == ScheduleExecutionState.PENDING && execution.failure != null) ErrorText(stringResource(R.string.schedule_failed_posting))
                                Text(stringResource(R.string.schedule_generated_at, scheduleTime(execution.generatedAt, plan.rule.zone)), style = MaterialTheme.typography.bodySmall)
                            }
                        }, trailingContent = {
                            if (execution.state == ScheduleExecutionState.PENDING) LedgerIconButton(Icons.Outlined.Close,
                                stringResource(R.string.schedule_skip_period, execution.index + 1), { skip = execution.id }, Modifier.testTag("sch_skip_${execution.id}"), enabled = !busy)
                        })
                    HorizontalDivider()
                }
            }
        }
    }
    if (resume && plan != null) AlertDialog(onDismissRequest = { resume = false }, title = { Text(stringResource(R.string.schedule_resume_title)) },
        text = { Text(stringResource(R.string.schedule_resume_detail, stringResource(if (plan.automatic) R.string.schedule_mode_automatic else R.string.schedule_mode_review))) },
        confirmButton = { TextButton({ resume = false; operation {
            val result = store.schedules.resumeAndExecute(planId, Instant.now())
            if (result.moreDue) context.getString(R.string.schedule_more_due) else null
        } }, Modifier.testTag("sch_resume_confirm")) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton({ resume = false }) { Text(stringResource(R.string.cancel)) } })
    val skippedExecution = state.executions.firstOrNull { it.id == skip && it.state == ScheduleExecutionState.PENDING }
    if (skippedExecution != null && plan != null) AlertDialog(onDismissRequest = { skip = null }, title = { Text(stringResource(R.string.schedule_skip_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.schedule_period, skippedExecution.index + 1, scheduleTime(skippedExecution.plannedAt, plan.rule.zone)))
            skippedExecution.template.amount?.let { Text(stringResource(R.string.schedule_planned_amount, "${it.currency.code} ${it.decimalText()}")) }
            Text(stringResource(R.string.schedule_skip_detail))
        } },
        confirmButton = { TextButton({ val id = requireNotNull(skip); skip = null; operation { store.drafts.remove("schedule/$id"); null } },
            Modifier.testTag("sch_skip_confirm")) { Text(stringResource(R.string.schedule_skip)) } },
        dismissButton = { TextButton({ skip = null }) { Text(stringResource(R.string.cancel)) } })
}
