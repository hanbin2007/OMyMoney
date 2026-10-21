package app.omymoney.feature.bills

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.R
import app.omymoney.core.ledger.PostingCalculator
import app.omymoney.core.model.*
import app.omymoney.data.LedgerStore
import app.omymoney.ui.components.*
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
fun BatchEditScreen(model: BatchViewModel, store: LedgerStore, onBack: () -> Unit) {
    val list by model.bills.collectAsStateWithLifecycle()
    val selected by model.selected.collectAsStateWithLifecycle()
    val editing by model.editing.collectAsStateWithLifecycle()
    val form by model.form.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val actions by model.actions.collectAsStateWithLifecycle()
    val accounts by store.catalog.observeAccounts().collectAsStateWithLifecycle(emptyList())
    val categories by remember(model.book.id) { store.catalog.observeCategories(model.book.id) }.collectAsStateWithLifecycle(emptyList())
    var discard by rememberSaveable { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var historyDetail by rememberSaveable { mutableStateOf<String?>(null) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    fun back() { if (!busy) { if (editing) discard = true else if (history) history = false else onBack() } }
    BackHandler { back() }
    LaunchedEffect(error, notice) {
        if (!error && notice == null) return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(context.getString(if (error) R.string.batch_failed else when (notice) {
            "deleted" -> R.string.batch_deleted; "restored" -> R.string.bill_restored; else -> R.string.saved
        }), withDismissAction = true, duration = if (error) SnackbarDuration.Indefinite else SnackbarDuration.Short)
        model.error.value = false; model.notice.value = null
    }
    val changes = form.keySet().any { (it.startsWith("change-") && form.getString(it) == "true") ||
        (it in setOf("stats", "budget") && form.getString(it) in setOf("true", "false")) }
    ScreenScaffold(stringResource(if (history) R.string.batch_history else if (editing) R.string.batch_edit else R.string.batch_title),
        ::back, modifier = Modifier.imePadding(), backEnabled = !busy, snackbarHostState = snackbar, actions = {
            if (editing) LedgerIconButton(Icons.Outlined.Save, stringResource(R.string.save), model::save,
                Modifier.testTag("batch_save"), enabled = !busy && changes)
            else if (!history) {
                LedgerIconButton(Icons.Outlined.Edit, stringResource(R.string.batch_edit), model::startEdit,
                    Modifier.testTag("batch_edit"), enabled = !busy && !selected.isEmpty)
                LedgerIconButton(Icons.Outlined.Delete, stringResource(R.string.batch_delete), { delete = true },
                    Modifier.testTag("batch_delete"), enabled = !busy && !selected.isEmpty)
                LedgerIconButton(Icons.Outlined.History, stringResource(R.string.batch_history), { history = true }, Modifier.testTag("batch_history"), enabled = !busy)
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                history -> LazyColumn(Modifier.fillMaxSize().testTag("batch_history_list")) {
                    item { Text(stringResource(R.string.batch_undo_policy), Modifier.padding(16.dp)) }
                    item { TextButton({ history = false }, Modifier.testTag("batch_history_close"), enabled = !busy) { Text(stringResource(R.string.close)) } }
                    if (actions.isEmpty()) item { Text(stringResource(R.string.no_matches), Modifier.padding(16.dp)) }
                    items(actions, key = { it.id }) { action ->
                        val canUndo = !action.used && Instant.now().isBefore(action.expiresAt)
                        val operation = stringResource(if (action.deletions.isEmpty()) R.string.batch_edit else R.string.batch_delete)
                        val summary = action.before.take(2).joinToString(" / ") { it.note.take(80) }.ifBlank { action.before.first().occurredAt.toString() }
                        val identity = "$operation · ${action.before.size} · $summary · ${action.id.take(8)}"
                        val undoLabel = stringResource(R.string.batch_undo_identity, identity)
                        ListItem(modifier = Modifier.clickable { historyDetail = action.id }.testTag("batch_action_${action.id}"),
                            headlineContent = { Text("$operation · ${action.before.size}") },
                            supportingContent = { Column {
                                Text(summary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(action.id.take(8), style = MaterialTheme.typography.labelSmall)
                                Text(stringResource(R.string.batch_expires, action.expiresAt.atZone(model.book.zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))))
                                TextButton({ model.undo(action.id) }, Modifier.testTag(if (action == actions.firstOrNull { !it.used }) "batch_undo_latest" else "batch_undo_${action.id}")
                                    .semantics { contentDescription = undoLabel }, enabled = !busy && canUndo) {
                                    Text(stringResource(if (action.used) R.string.bill_restored else if (canUndo) R.string.undo else R.string.undo_unavailable))
                                }
                            } })
                        HorizontalDivider()
                    }
                }
                editing -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.batch_selected, selected.size()), style = MaterialTheme.typography.titleMedium)
                    listOf("note" to R.string.note, "tags" to R.string.tags).forEach { (key, label) ->
                        val change = form.getString("change-$key") == "true"
                        BatchCheckbox(stringResource(label), change, { model.field("change-$key", it.toString()) }, "batch_change_$key", !busy)
                        if (change) OutlinedTextField(form.getString(key).orEmpty(), { model.field(key, it) }, label = { Text(stringResource(label)) },
                            modifier = Modifier.fillMaxWidth().testTag("batch_$key"), enabled = !busy, minLines = if (key == "note") 2 else 1)
                    }
                    val categoryKind = list.bills.filter { selected.containsKey(it.id) }.map { PostingCalculator.categoryKind(it.kind) }.distinct().singleOrNull()
                    BatchCheckbox(stringResource(R.string.category), form.getString("change-category") == "true", { model.field("change-category", it.toString()) }, "batch_change_category", !busy)
                    if (form.getString("change-category") == "true") PickerField(stringResource(R.string.category),
                        categories.firstOrNull { it.id == form.getString("category") }?.name ?: stringResource(R.string.batch_clear_category),
                        { picker = "category" }, enabled = !busy)
                    listOf("stats" to R.string.exclude_stats, "budget" to R.string.exclude_budget).forEach { (key, label) ->
                        PickerField(stringResource(label), stringResource(when (form.getString(key)) { "true" -> R.string.batch_exclude; "false" -> R.string.batch_include; else -> R.string.batch_keep }), { picker = key }, enabled = !busy)
                    }
                    if (picker != null) {
                        val key = requireNotNull(picker)
                        val options = if (key == "category") listOf(SelectionOption("clear", stringResource(R.string.batch_clear_category))) +
                            categories.filter { !it.archived && categoryKind != null && it.kind == categoryKind }.map {
                                SelectionOption(it.id, it.name, it.parentId?.let { parent -> categories.firstOrNull { c -> c.id == parent }?.name })
                            }
                        else listOf(SelectionOption("keep", stringResource(R.string.batch_keep)), SelectionOption("false", stringResource(R.string.batch_include)), SelectionOption("true", stringResource(R.string.batch_exclude)))
                        SelectionSheet(stringResource(if (key == "category") R.string.category else if (key == "stats") R.string.exclude_stats else R.string.exclude_budget), options,
                            form.getString(key), { model.field(key, if (it == "clear") "" else it); picker = null }, { picker = null })
                    }
                }
                list.loading -> LoadingState()
                list.failed -> ErrorText(stringResource(R.string.bill_load_failed), Modifier.padding(16.dp))
                else -> {
                    Text(model.book.name, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                    BatchCheckbox(stringResource(R.string.batch_selection, selected.size(), list.bills.size),
                        list.bills.isNotEmpty() && list.bills.all { selected.containsKey(it.id) }, { model.selectAll() }, "batch_select_all", !busy && list.bills.isNotEmpty())
                    if (model.filters.active) Text(stringResource(R.string.batch_filtered), Modifier.padding(horizontal = 16.dp))
                    LazyColumn(Modifier.fillMaxSize().testTag("batch_list")) {
                        if (list.bills.isEmpty()) item { Text(stringResource(R.string.no_matching_bills), Modifier.padding(16.dp)) }
                        items(list.bills, key = { it.id }) { bill ->
                            ListItem(modifier = Modifier.toggleable(selected.containsKey(bill.id), enabled = !busy, role = Role.Checkbox, onValueChange = { model.toggle(bill) }).testTag("batch_row_${bill.id}"),
                                leadingContent = { Checkbox(selected.containsKey(bill.id), null, enabled = !busy) },
                                headlineContent = { Text(bill.note.ifBlank { billKindLabel(bill.kind) }) },
                                supportingContent = { Column {
                                    Text(bill.occurredAt.atZone(model.book.zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                                    MoneyText(billAmountText(bill), Modifier.fillMaxWidth())
                                } })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.discard_title)) },
        text = { Text(stringResource(R.string.discard_message)) }, confirmButton = { TextButton({ discard = false; model.discard() }, Modifier.testTag("batch_discard")) { Text(stringResource(R.string.discard_changes)) } },
        dismissButton = { TextButton({ discard = false }) { Text(stringResource(R.string.cancel)) } })
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text(stringResource(R.string.batch_delete)) },
        text = { Text(stringResource(R.string.batch_delete_confirm, selected.size())) },
        confirmButton = { TextButton({ delete = false; model.delete() }, Modifier.testTag("batch_delete_confirm")) { Text(stringResource(R.string.batch_delete)) } },
        dismissButton = { TextButton({ delete = false }, Modifier.testTag("batch_delete_cancel")) { Text(stringResource(R.string.cancel)) } })
    actions.firstOrNull { it.id == historyDetail }?.let { action ->
        AlertDialog(onDismissRequest = { historyDetail = null }, title = { Text(stringResource(R.string.batch_members)) },
            text = { LazyColumn(Modifier.heightIn(max = 440.dp)) {
                item { Text(stringResource(R.string.batch_before), Modifier.padding(bottom = 12.dp)) }
                items(action.before, key = { it.id }) { bill ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("batch_member_${bill.id}").semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(billKindLabel(bill.kind), style = MaterialTheme.typography.labelLarge)
                        MoneyText(billAmountText(bill), Modifier.fillMaxWidth())
                        Text(bill.occurredAt.atZone(bill.zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")))
                        listOfNotNull(bill.fromAccountId, bill.toAccountId).forEach { id ->
                            Text((accounts.firstOrNull { it.id == id }?.name ?: id))
                        }
                        if (bill.note.isNotEmpty()) Text(bill.note)
                        categories.firstOrNull { it.id == bill.categoryId }?.let { Text(it.name) }
                        if (bill.tags.isNotEmpty()) Text(bill.tags.joinToString(" · "))
                        if (bill.excludeStats) Text(stringResource(R.string.exclude_stats))
                        if (bill.excludeBudget) Text(stringResource(R.string.exclude_budget))
                    }
                    HorizontalDivider()
                }
            } }, confirmButton = { TextButton({ historyDetail = null }) { Text(stringResource(R.string.close)) } })
    }
}

@Composable
private fun BatchCheckbox(label: String, checked: Boolean, change: (Boolean) -> Unit, tag: String, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = change).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null, enabled = enabled)
        Text(label, Modifier.weight(1f))
    }
}
