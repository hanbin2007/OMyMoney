package app.omymoney.feature.savings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.R
import app.omymoney.core.model.*
import app.omymoney.core.savings.*
import app.omymoney.core.schedule.ScheduleFrequency
import app.omymoney.data.LedgerStore
import app.omymoney.feature.assets.commonCurrencies
import app.omymoney.feature.schedule.scheduleFrequencyLabel
import app.omymoney.ui.components.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private class SavingsFormFocus(fields: List<String>) {
    val positions = fields.associateWith { BringIntoViewRequester() }
    val focus = fields.associateWith { FocusRequester() }
    val attachedInputs = mutableSetOf<String>()
}
@Composable private fun rememberSavingsFormFocus(issue: SavingsIssue?, attempt: Int): SavingsFormFocus {
    val fields = remember { SavingsFormFocus(listOf("general", "name", "amount", "increment", "count", "interval", "start", "end", "note", "date", "transfer")) }
    val manager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val shortHeight = LocalConfiguration.current.screenHeightDp < 480
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    LaunchedEffect(attempt) { issue?.let {
        if (!shortHeight && it.field in fields.attachedInputs) fields.focus[it.field]?.requestFocus()
        else {
            manager.clearFocus(); keyboard?.hide()
            withTimeoutOrNull(1500) { snapshotFlow { ime.getBottom(density) }.first { it == 0 } }
        }
        withFrameNanos { }
        fields.positions[it.field]?.bringIntoView()
    } }
    return fields
}

@Composable private fun SavingsInput(prefix: String, field: String, value: String, label: String, focus: SavingsFormFocus,
    issue: SavingsIssue?, enabled: Boolean, keyboard: KeyboardType = KeyboardType.Text, multiline: Boolean = false,
    onChange: (String) -> Unit) {
    DisposableEffect(focus, field) {
        focus.attachedInputs += field
        onDispose { focus.attachedInputs -= field }
    }
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().testTag("${prefix}_$field")
        .focusRequester(focus.focus.getValue(field)).bringIntoViewRequester(focus.positions.getValue(field)),
        enabled = enabled, label = { Text(label) }, singleLine = !multiline, minLines = if (multiline) 2 else 1,
        maxLines = if (multiline) 6 else 1, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        isError = issue?.field == field, supportingText = issue?.takeIf { it.field == field }?.let {
            { ErrorText(savingsError(it.code), Modifier.testTag("${prefix}_${field}_error")) }
        })
}
@Composable private fun GeneralError(prefix: String, issue: SavingsIssue?, focus: SavingsFormFocus) {
    if (issue?.field == "general") ErrorText(savingsError(issue.code), Modifier.testTag("${prefix}_general_error")
        .bringIntoViewRequester(focus.positions.getValue("general")))
}
@Composable private fun SavingsDiscardDialog(onDiscard: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(onDismissRequest = onKeep, title = { Text(stringResource(R.string.savings_discard_title)) },
        confirmButton = { TextButton(onDiscard, Modifier.testTag("sav_discard")) { Text(stringResource(R.string.savings_discard)) } },
        dismissButton = { TextButton(onKeep, Modifier.testTag("sav_keep_editing")) { Text(stringResource(R.string.savings_keep_editing)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavingsPlanEditorScreen(editor: SavingsPlanEditorViewModel, book: Book, currencies: List<CurrencySpec>,
    onBack: () -> Unit, onSaved: (String) -> Unit) {
    val state by editor.state.collectAsStateWithLifecycle()
    val form = state.form
    val preview by editor.preview.collectAsStateWithLifecycle()
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val focus = rememberSavingsFormFocus(state.issue, state.attempt)
    val manager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.savedId) { state.savedId?.let(onSaved) }
    fun back() { if (!state.saving) { if (form?.dirty == true) discard = true else onBack() } }
    BackHandler(onBack = ::back)
    @Composable fun input(field: String, value: String, label: String, numeric: Boolean = false, multiline: Boolean = false, change: (String) -> Unit) {
        SavingsInput("sav", field, value, label, focus, state.issue, !state.saving,
            if (numeric) KeyboardType.Decimal else KeyboardType.Text, multiline, change)
    }
    ScreenScaffold(stringResource(if (form?.base?.revision == 0L) R.string.savings_new else R.string.savings_edit), ::back, backEnabled = !state.saving, actions = {
        LedgerIconButton(Icons.Outlined.Visibility, stringResource(R.string.savings_preview), { manager.clearFocus(); keyboard?.hide(); editor.showPreview() },
            Modifier.testTag("sav_preview"), enabled = form != null && !state.saving)
        LedgerIconButton(Icons.Outlined.Save, stringResource(R.string.save), editor::save, Modifier.testTag("sav_save"), enabled = form != null && !state.saving)
    }) { padding -> when {
        state.loadFailed -> Column(Modifier.padding(padding).padding(16.dp)) {
            ErrorText(stringResource(R.string.savings_load_failed)); TextButton(editor::load) { Text(stringResource(R.string.retry)) }
        }
        form == null -> LoadingState(Modifier.padding(padding))
        else -> Column(Modifier.fillMaxSize().padding(padding).imePadding().testTag("sav_editor").verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(book.name, style = MaterialTheme.typography.titleSmall)
            GeneralError("sav", state.issue, focus)
            if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            input("name", form.name, stringResource(R.string.savings_name)) { editor.update { f -> f.copy(name = it) } }
            PickerField(stringResource(R.string.savings_mode), savingsModeLabel(form.mode), { picker = "mode" }, Modifier.testTag("sav_mode"), enabled = !state.saving)
            PickerField(stringResource(R.string.currency), form.currency.code, { picker = "currency" }, Modifier.testTag("sav_currency"), enabled = !state.saving && !form.currencyLocked)
            if (form.currencyLocked) Text(stringResource(R.string.savings_currency_locked), style = MaterialTheme.typography.bodySmall)
            input("amount", form.amount, stringResource(when (form.mode) {
                SavingsMode.CUSTOM -> R.string.savings_target_amount
                SavingsMode.INCREASING, SavingsMode.DECREASING -> R.string.savings_initial_amount
                else -> R.string.savings_amount
            }, form.currency.code), numeric = true) { editor.update { f -> f.copy(amount = it) } }
            if (form.mode in listOf(SavingsMode.INCREASING, SavingsMode.DECREASING))
                input("increment", form.increment, stringResource(R.string.savings_increment, form.currency.code), numeric = true) { editor.update { f -> f.copy(increment = it) } }
            if (form.mode != SavingsMode.CUSTOM) {
                input("count", form.count, stringResource(R.string.savings_count), numeric = true) { editor.update { f -> f.copy(count = it) } }
                PickerField(stringResource(R.string.savings_frequency), scheduleFrequencyLabel(form.frequency), { picker = "frequency" }, Modifier.testTag("sav_frequency"), enabled = !state.saving)
                input("interval", form.interval, stringResource(R.string.savings_interval), numeric = true) { editor.update { f -> f.copy(interval = it) } }
            }
            input("start", form.start, stringResource(R.string.savings_start)) { editor.update { f -> f.copy(start = it) } }
            if (form.mode == SavingsMode.CUSTOM) input("end", form.end, stringResource(R.string.savings_end)) { editor.update { f -> f.copy(end = it) } }
            input("note", form.note, stringResource(R.string.note), multiline = true) { editor.update { f -> f.copy(note = it) } }
        }
    } }
    if (form != null) when (picker) {
        "mode" -> SelectionSheet(stringResource(R.string.savings_mode), SavingsMode.entries.map { SelectionOption(it.name, savingsModeLabel(it)) }, form.mode.name,
            { value -> editor.update { it.copy(mode = SavingsMode.valueOf(value)) }; picker = null }, { picker = null })
        "currency" -> {
            val options = (currencies + commonCurrencies + form.currency).distinct()
            SelectionSheet(stringResource(R.string.currency), options.map { SelectionOption("${it.code}/${it.scale}", it.code) }, "${form.currency.code}/${form.currency.scale}",
                { value -> editor.chooseCurrency(options.first { "${it.code}/${it.scale}" == value }); picker = null }, { picker = null })
        }
        "frequency" -> SelectionSheet(stringResource(R.string.savings_frequency), ScheduleFrequency.entries.map { SelectionOption(it.name, scheduleFrequencyLabel(it)) },
            form.frequency.name, { value -> editor.update { it.copy(frequency = ScheduleFrequency.valueOf(value)) }; picker = null }, { picker = null })
    }
    preview?.let { plan -> ModalBottomSheet(onDismissRequest = editor::closePreview, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.savings_preview), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                LedgerIconButton(Icons.Outlined.Close, stringResource(R.string.savings_close_preview), editor::closePreview, Modifier.testTag("sav_preview_close"))
            } }
            item { Column(Modifier.testTag("sav_preview_context").semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(plan.name); Text(book.name); Text(savingsModeLabel(plan.rule.mode)); Text(stringResource(R.string.savings_target, savingsMoney(SavingsCalculator.target(plan.rule))))
            } }
            items(SavingsCalculator.steps(plan.rule), key = { it.index }) { step ->
                Text(stringResource(R.string.savings_step, step.index + 1, step.date.toString()) + "\n" + savingsMoney(step.amount), Modifier.testTag("sav_preview_${step.index}"))
            }
        }
    } }
    if (discard) SavingsDiscardDialog(onBack) { discard = false }
}

@Composable
fun SavingsDepositEditorScreen(editor: SavingsDepositEditorViewModel, store: LedgerStore, book: Book,
    onBack: () -> Unit, onSaved: (String) -> Unit) {
    val state by editor.state.collectAsStateWithLifecycle()
    val form = state.form
    val bills by remember(store, book.id) { store.ledger.observeBills(BillQuery(bookId = book.id)) }.collectAsStateWithLifecycle(emptyList())
    val deposits by remember(store, book.id) { store.savings.observeBookDeposits(book.id) }.collectAsStateWithLifecycle(emptyList())
    val accounts by remember(store) { store.catalog.observeAccounts(true) }.collectAsStateWithLifecycle(emptyList())
    var picker by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val focus = rememberSavingsFormFocus(state.issue, state.attempt)
    LaunchedEffect(state.savedId) { state.savedId?.let(onSaved) }
    fun back() { if (!state.saving) { if (form?.dirty == true) discard = true else onBack() } }
    BackHandler(onBack = ::back)
    @Composable fun input(field: String, value: String, label: String, numeric: Boolean = false, multiline: Boolean = false, change: (String) -> Unit) {
        SavingsInput("dep", field, value, label, focus, state.issue, !state.saving,
            if (numeric) KeyboardType.Decimal else KeyboardType.Text, multiline, change)
    }
    ScreenScaffold(stringResource(if (form?.base?.revision == 0L) R.string.savings_add else R.string.savings_edit_deposit), ::back, backEnabled = !state.saving, actions = {
        LedgerIconButton(Icons.Outlined.Save, stringResource(R.string.save), editor::save, Modifier.testTag("dep_save"), enabled = form != null && !state.saving)
    }) { padding -> when {
        state.loadFailed -> Column(Modifier.padding(padding).padding(16.dp)) {
            ErrorText(stringResource(R.string.savings_load_failed)); TextButton(editor::load) { Text(stringResource(R.string.retry)) }
        }
        form == null -> LoadingState(Modifier.padding(padding))
        else -> Column(Modifier.fillMaxSize().padding(padding).imePadding().testTag("dep_editor").verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(form.plan.name, style = MaterialTheme.typography.titleMedium)
            Text(book.name, style = MaterialTheme.typography.titleSmall)
            GeneralError("dep", state.issue, focus)
            if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { listOf(false, true).forEachIndexed { index, linked ->
                SegmentedButton(form.linked == linked, { editor.update { it.copy(linked = linked) } }, SegmentedButtonDefaults.itemShape(index, 2),
                    Modifier.testTag(if (linked) "dep_mode_link" else "dep_mode_manual"), enabled = !state.saving) {
                    Text(stringResource(if (linked) R.string.savings_linked else R.string.savings_manual))
                }
            } }
            Text(stringResource(if (form.linked) R.string.savings_linked_effect else R.string.savings_manual_effect), style = MaterialTheme.typography.bodySmall)
            if (form.linked) {
                val bill = bills.firstOrNull { it.id == form.linkedId }
                val resolved = form.linkedId?.let { SavingsCalculator.resolve(form.plan, form.base.copy(amount = null, linkedBillId = it), bills.associateBy { b -> b.id }) }
                PickerField(stringResource(R.string.savings_transfer), bill?.let { "${it.occurredAt.atZone(form.plan.zone).toLocalDate()} ${it.note}" }
                    ?: stringResource(R.string.savings_select_transfer), { picker = true }, Modifier.testTag("dep_transfer")
                        .bringIntoViewRequester(focus.positions.getValue("transfer")), enabled = !state.saving,
                    error = state.issue?.takeIf { it.field == "transfer" }?.let { savingsError(it.code) })
                resolved?.amount?.let { Text(stringResource(R.string.savings_received, savingsMoney(it))) }
                if (resolved?.problem != null) ErrorText(stringResource(R.string.savings_changed_link))
            } else {
                input("amount", form.amount, stringResource(R.string.savings_amount, form.plan.rule.amount.currency.code), numeric = true) { editor.update { f -> f.copy(amount = it) } }
                input("date", form.date, stringResource(R.string.savings_date)) { editor.update { f -> f.copy(date = it) } }
            }
            input("note", form.note, stringResource(R.string.note), multiline = true) { editor.update { f -> f.copy(note = it) } }
        }
    } }
    if (picker && form != null) {
        val assigned = deposits.filter { it.id != form.base.id }.mapNotNull { it.linkedBillId }.toSet()
        val options = bills.filter { bill -> bill.id !in assigned && SavingsCalculator.resolve(form.plan,
            form.base.copy(amount = null, linkedBillId = bill.id), mapOf(bill.id to bill)).problem == null }
        SelectionSheet(stringResource(R.string.savings_transfer), options.map { bill ->
            val amount = requireNotNull(SavingsCalculator.resolve(form.plan, form.base.copy(amount = null, linkedBillId = bill.id), mapOf(bill.id to bill)).amount)
            SelectionOption(bill.id, "${bill.occurredAt.atZone(form.plan.zone).toLocalDate()} ${savingsMoney(amount)}",
                "${accounts.firstOrNull { it.id == bill.fromAccountId }?.name.orEmpty()} → ${accounts.firstOrNull { it.id == bill.toAccountId }?.name.orEmpty()} ${bill.note}")
        }, form.linkedId, { id -> editor.chooseTransfer(options.first { it.id == id }); picker = false }, { picker = false })
    }
    if (discard) SavingsDiscardDialog(onBack) { discard = false }
}
