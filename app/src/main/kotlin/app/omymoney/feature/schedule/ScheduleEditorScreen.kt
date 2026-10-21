package app.omymoney.feature.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.R
import app.omymoney.core.ledger.DebtRules
import app.omymoney.core.ledger.PostingCalculator
import app.omymoney.core.model.*
import app.omymoney.core.schedule.*
import app.omymoney.data.ScheduledPlan
import app.omymoney.data.scheduledDraft
import app.omymoney.feature.bills.billKindLabel
import app.omymoney.ui.components.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun ScheduleEditorScreen(editor: ScheduleEditorViewModel, book: Book, accounts: List<AssetAccount>, categories: List<Category>,
    bills: List<Bill>, onBack: () -> Unit, onSaved: (ScheduledPlan) -> Unit) {
    val state by editor.state.collectAsStateWithLifecycle()
    val form = state.form
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val fields = remember { listOf("general", "name", "amount", "fee", "start", "time", "zone", "interval", "count", "end", "from", "to", "linked") }
    val positions = remember { fields.associateWith { BringIntoViewRequester() } }
    val focus = remember { fields.associateWith { FocusRequester() } }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val shortHeight = LocalConfiguration.current.screenHeightDp < 480
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val textFields = remember { setOf("name", "amount", "fee", "start", "time", "interval", "count", "end") }
    LaunchedEffect(state.saved) { state.saved?.let(onSaved) }
    LaunchedEffect(state.attempt) {
        state.issue?.let { issue ->
            if (!shortHeight && issue.field in textFields) focus[issue.field]?.requestFocus()
            else {
                focusManager.clearFocus()
                keyboard?.hide()
                withTimeoutOrNull(1500) { snapshotFlow { ime.getBottom(density) }.first { it == 0 } }
            }
            withFrameNanos { }
            positions[issue.field]?.bringIntoView()
        }
    }
    fun back() { if (!state.saving) { if (form?.dirty == true) discard = true else onBack() } }
    BackHandler(onBack = ::back)
    @Composable fun error(field: String): (@Composable () -> Unit)? = state.issue?.takeIf { it.field == field }?.let { issue ->
        { ErrorText(scheduleError(issue.code), Modifier.testTag("sch_${field}_error")
            .then(if (field in textFields) Modifier else Modifier.bringIntoViewRequester(positions.getValue(field)))) }
    }
    @Composable fun textField(field: String, value: String, label: String, enabled: Boolean = true,
        keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
        OutlinedTextField(value, onChange, Modifier.fillMaxWidth().testTag("sch_$field").focusRequester(focus.getValue(field))
            .bringIntoViewRequester(positions.getValue(field)),
            enabled = enabled && !state.saving, label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard), isError = state.issue?.field == field, supportingText = error(field))
    }
    ScreenScaffold(stringResource(if (form?.base?.revision == 0L) R.string.schedule_new else R.string.schedule_edit), ::back,
        backEnabled = !state.saving, actions = {
            LedgerIconButton(Icons.Outlined.Visibility, stringResource(R.string.schedule_preview), {
                focusManager.clearFocus(); keyboard?.hide(); editor.preview()
            },
                Modifier.testTag("sch_preview"), enabled = form != null && !state.saving)
            LedgerIconButton(Icons.Outlined.Save, stringResource(R.string.save), editor::save,
                Modifier.testTag("sch_save"), enabled = form != null && !state.saving)
        }) { padding ->
        when {
            state.loadFailed -> Column(Modifier.padding(padding).padding(16.dp)) {
                ErrorText(stringResource(R.string.schedule_load_failed)); TextButton(editor::load) { Text(stringResource(R.string.retry)) }
            }
            form == null -> LoadingState(Modifier.padding(padding))
            else -> Column(Modifier.fillMaxSize().padding(padding).imePadding().testTag("sch_editor_form").verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(book.name, style = MaterialTheme.typography.titleSmall)
                error("general")?.invoke()
                if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                textField("name", form.name, stringResource(R.string.schedule_name)) { editor.update { form -> form.copy(name = it) } }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(false, true).forEachIndexed { index, installments ->
                        SegmentedButton(form.installments == installments, { editor.update { it.copy(installments = installments) } },
                            SegmentedButtonDefaults.itemShape(index, 2), Modifier.testTag(if (installments) "sch_mode_installments" else "sch_mode_recurring"),
                            enabled = !form.calendarLocked && !state.saving) {
                            Text(stringResource(if (installments) R.string.schedule_installments else R.string.schedule_recurring))
                        }
                    }
                }
                if (form.calendarLocked) Text(stringResource(R.string.schedule_calendar_locked), style = MaterialTheme.typography.bodySmall)
                PickerField(stringResource(R.string.schedule_kind), billKindLabel(form.kind), { picker = "kind" }, enabled = !state.saving)
                textField("amount", form.amount, stringResource(if (form.installments) R.string.schedule_principal else R.string.schedule_amount, form.currency.code),
                    enabled = !form.amountLocked, keyboard = KeyboardType.Decimal) { editor.update { form -> form.copy(amount = it) } }
                if (form.linked) {
                    val source = bills.firstOrNull { it.id == form.linkedId }
                    PickerField(stringResource(R.string.schedule_linked), source?.let { "${it.amount.currency.code} ${it.amount.decimalText()} ${it.note}" }
                        ?: stringResource(R.string.schedule_linked), { picker = "linked" }, Modifier.testTag("sch_linked"), enabled = !state.saving)
                    error("linked")?.invoke()
                }
                listOf(true, false).forEach { from ->
                    val applicable = form.kind in (if (from) PostingCalculator.outgoingKinds else PostingCalculator.incomingKinds) + PostingCalculator.transferKinds
                    if (applicable) {
                        val field = if (from) "from" else "to"
                        val id = if (from) form.fromId else form.toId
                        val fixedPrincipal = form.linkedId != null && ((form.kind == BillKind.DEBT_REPAY && !from) || (form.kind == BillKind.DEBT_COLLECT && from))
                        PickerField(stringResource(if (from) R.string.from_account else R.string.to_account), accounts.firstOrNull { it.id == id }?.name
                            ?: stringResource(R.string.select_account), { picker = field }, Modifier.testTag("sch_$field"), enabled = !state.saving && !fixedPrincipal)
                        error(field)?.invoke()
                    }
                }
                if (PostingCalculator.categoryKind(form.kind) != null) PickerField(stringResource(R.string.category),
                    categories.firstOrNull { it.id == form.categoryId }?.let { scheduleCategory(it, categories) } ?: stringResource(R.string.stats_uncategorized),
                    { picker = "category" }, Modifier.testTag("sch_category"), enabled = !state.saving)
                textField("fee", form.fee, stringResource(R.string.schedule_fee, form.currency.code), keyboard = KeyboardType.Decimal) {
                    editor.update { form -> form.copy(fee = it) }
                }
                PickerField(stringResource(R.string.schedule_frequency), scheduleFrequencyLabel(form.frequency), { picker = "frequency" },
                    Modifier.testTag("sch_frequency"), enabled = !state.saving && !form.calendarLocked)
                textField("interval", form.interval, stringResource(R.string.schedule_interval, scheduleFrequencyLabel(form.frequency)),
                    enabled = !form.calendarLocked, keyboard = KeyboardType.Number) { editor.update { form -> form.copy(interval = it) } }
                textField("start", form.start, stringResource(R.string.schedule_start), enabled = !form.calendarLocked) { editor.update { form -> form.copy(start = it) } }
                textField("time", form.time, stringResource(R.string.schedule_time), enabled = !form.calendarLocked) { editor.update { form -> form.copy(time = it) } }
                PickerField(stringResource(R.string.schedule_zone), zoneLabel(form.zone), { picker = "zone" }, Modifier.testTag("sch_zone"),
                    enabled = !state.saving && !form.calendarLocked)
                error("zone")?.invoke()
                textField("count", form.count, stringResource(if (form.installments) R.string.schedule_installment_count else R.string.schedule_count),
                    enabled = !form.calendarLocked, keyboard = KeyboardType.Number) { editor.update { form -> form.copy(count = it) } }
                textField("end", form.end, stringResource(R.string.schedule_end), enabled = !form.calendarLocked) { editor.update { form -> form.copy(end = it) } }
                OutlinedTextField(form.note, { value -> editor.update { it.copy(note = value) } }, Modifier.fillMaxWidth().testTag("sch_note"),
                    label = { Text(stringResource(R.string.note)) }, minLines = 2, maxLines = 6, enabled = !state.saving)
                OutlinedTextField(form.tags, { value -> editor.update { it.copy(tags = value) } }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.tags)) }, singleLine = true, enabled = !state.saving)
                ScheduleSwitch(stringResource(R.string.exclude_stats), form.excludeStats, !state.saving) { value -> editor.update { it.copy(excludeStats = value) } }
                ScheduleSwitch(stringResource(R.string.exclude_budget), form.excludeBudget, !state.saving) { value -> editor.update { it.copy(excludeBudget = value) } }
                ScheduleSwitch(stringResource(R.string.schedule_automatic), form.automatic, !state.saving, Modifier.testTag("sch_automatic"),
                    stringResource(R.string.schedule_automatic_consent)) { value -> editor.update { it.copy(automatic = value) } }
            }
        }
    }
    if (form != null && picker != null) {
        val selected = when (picker) {
            "kind" -> form.kind.name; "frequency" -> form.frequency.name; "zone" -> form.zone
            "from" -> form.fromId; "to" -> form.toId; "category" -> form.categoryId; else -> form.linkedId
        }
        val options = when (picker) {
            "kind" -> BillKind.entries.filterNot { it == BillKind.ADJUSTMENT }.map { SelectionOption(it.name, billKindLabel(it)) }
            "frequency" -> ScheduleFrequency.entries.map { SelectionOption(it.name, scheduleFrequencyLabel(it)) }
            "zone" -> (ZoneId.getAvailableZoneIds() + form.zone).sorted().map { SelectionOption(it, zoneLabel(it)) }
            "from", "to" -> accounts.filter { !it.archived && DebtRules.acceptsAccount(form.kind, picker == "from", it) &&
                (!form.amountLocked || it.currency == form.currency) &&
                (form.kind !in PostingCalculator.transferKinds || picker == "from" || form.fromId == null || it.currency == form.currency)
            }.map { SelectionOption(it.id, it.name, it.currency.code) }
            "category" -> listOf(SelectionOption("", stringResource(R.string.stats_uncategorized))) + categories.filter {
                !it.archived && it.kind == PostingCalculator.categoryKind(form.kind)
            }.map { SelectionOption(it.id, scheduleCategory(it, categories)) }
            else -> bills.filter { it.kind == scheduleSourceKind(form.kind) && (!form.amountLocked || it.amount.currency == form.currency) }.map {
                SelectionOption(it.id, "${it.amount.currency.code} ${it.amount.decimalText()} ${it.note}", it.occurredAt.atZone(it.zoneId).toLocalDate().toString())
            }
        }
        SelectionSheet(stringResource(when (picker) {
            "kind" -> R.string.schedule_kind; "frequency" -> R.string.schedule_frequency; "zone" -> R.string.schedule_zone
            "category" -> R.string.category; "linked" -> R.string.schedule_linked; else -> R.string.select_account
        }), options, selected, { id ->
            when (picker) {
                "kind" -> editor.chooseKind(BillKind.valueOf(id))
                "frequency" -> editor.update { it.copy(frequency = ScheduleFrequency.valueOf(id)) }
                "zone" -> editor.update { it.copy(zone = id) }
                "from", "to" -> editor.chooseAccount(picker == "from", accounts.first { it.id == id })
                "category" -> editor.update { it.copy(categoryId = id.takeIf(String::isNotBlank)) }
                else -> editor.chooseSource(bills.first { it.id == id })
            }
            picker = null
        }, { picker = null })
    }
    state.preview?.let { SchedulePreviewSheet(it, it.nextIndex, book, accounts, editor::closePreview) }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.schedule_discard_title)) },
        confirmButton = { TextButton(onBack, Modifier.testTag("sch_discard")) { Text(stringResource(R.string.schedule_discard)) } },
        dismissButton = { TextButton({ discard = false }, Modifier.testTag("sch_continue_editing")) { Text(stringResource(R.string.schedule_continue)) } })
}

@Composable internal fun ScheduleSwitch(label: String, checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier,
    supporting: String? = null, onChange: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Text(label); supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
        Switch(checked, null, enabled = enabled)
    }
}

internal fun scheduleCategory(category: Category, categories: List<Category>) =
    listOfNotNull(categories.firstOrNull { it.id == category.parentId }?.name, category.name).joinToString(" / ")
internal fun zoneLabel(zone: String) = if (zone == "Z") "UTC" else zone
internal fun scheduleSourceKind(kind: BillKind) = when (kind) {
    BillKind.REFUND -> BillKind.EXPENSE
    BillKind.REIMBURSED -> BillKind.REIMBURSEMENT
    BillKind.DEBT_REPAY, BillKind.INTEREST_EXPENSE -> BillKind.DEBT_BORROW
    else -> BillKind.DEBT_LEND
}
internal fun scheduleTime(at: java.time.Instant, zone: ZoneId) = at.atZone(zone).format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm XXX"))
@Composable internal fun scheduleFrequencyLabel(frequency: ScheduleFrequency) = stringResource(when (frequency) {
    ScheduleFrequency.DAILY -> R.string.schedule_daily; ScheduleFrequency.WEEKLY -> R.string.schedule_weekly
    ScheduleFrequency.MONTHLY -> R.string.schedule_monthly; ScheduleFrequency.YEARLY -> R.string.schedule_yearly
})
@Composable internal fun scheduleError(code: String) = stringResource(when (code) {
    "name" -> R.string.schedule_error_name; "amount", "overflow" -> R.string.schedule_error_amount
    "date" -> R.string.schedule_error_date; "time" -> R.string.schedule_error_time; "zone" -> R.string.schedule_error_zone
    "interval" -> R.string.schedule_error_interval; "count" -> R.string.schedule_error_count
    "end", "end_installments" -> R.string.schedule_error_end; "small_installment" -> R.string.schedule_error_small
    "account", "unavailable_account", "account_role", "currency_mismatch" -> R.string.schedule_error_account
    "same_account" -> R.string.schedule_error_same; "linked", "invalid_link" -> R.string.schedule_error_linked
    "exceeds_remaining" -> R.string.schedule_error_remaining
    "schedule_started" -> R.string.schedule_calendar_locked; "conflict" -> R.string.schedule_error_conflict
    "storage" -> R.string.schedule_storage_failed; else -> R.string.schedule_error_invalid
})

internal fun previewOccurrences(plan: ScheduledPlan, from: Int): List<Occurrence> {
    val next = (from until (from + 12).coerceAtMost(ScheduleCalculator.MAX_OCCURRENCES)).mapNotNull { ScheduleCalculator.occurrence(plan.rule, it) }
    val last = if (plan.installments) plan.rule.count?.let { ScheduleCalculator.occurrence(plan.rule, it - 1) }?.takeIf { it.index >= from } else null
    return (next + listOfNotNull(last)).distinctBy { it.index }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SchedulePreviewSheet(plan: ScheduledPlan, from: Int, book: Book, accounts: List<AssetAccount>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.schedule_preview), style = MaterialTheme.typography.titleLarge)
            Column(Modifier.fillMaxWidth().testTag("sch_preview_context").semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(plan.name, style = MaterialTheme.typography.titleMedium)
                Text("${book.name} · ${billKindLabel(requireNotNull(plan.template.kind))}")
                plan.template.fromAccountId?.let { id -> Text("${stringResource(R.string.from_account)}：${accounts.firstOrNull { it.id == id }?.name.orEmpty()}") }
                plan.template.toAccountId?.let { id -> Text("${stringResource(R.string.to_account)}：${accounts.firstOrNull { it.id == id }?.name.orEmpty()}") }
                Text(stringResource(if (plan.automatic) R.string.schedule_mode_automatic else R.string.schedule_mode_review))
            }
            Text(zoneLabel(plan.rule.zone.id))
            Text(stringResource(R.string.schedule_preview_limit), style = MaterialTheme.typography.bodySmall)
            previewOccurrences(plan, from).forEach { occurrence -> SchedulePreviewRow(plan, occurrence, "sch_preview_${occurrence.index}") }
            TextButton(onDismiss, Modifier.testTag("sch_preview_close")) { Text(stringResource(R.string.schedule_preview_close)) }
        }
    }
}

@Composable internal fun SchedulePreviewRow(plan: ScheduledPlan, occurrence: Occurrence, tag: String) {
    val draft = scheduledDraft(plan, occurrence)
    Column(Modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.schedule_period, occurrence.index + 1, scheduleTime(occurrence.plannedAt, plan.rule.zone)), style = MaterialTheme.typography.titleSmall)
        draft.amount?.let { Text("${it.currency.code} ${it.decimalText()}", style = MaterialTheme.typography.titleMedium) }
        draft.fee?.let { Text("${stringResource(R.string.stats_fee)} ${it.currency.code} ${it.decimalText()}") }
        HorizontalDivider()
    }
}
