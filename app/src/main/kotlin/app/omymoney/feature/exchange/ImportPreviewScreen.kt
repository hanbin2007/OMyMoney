package app.omymoney.feature.exchange

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Upload
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
import app.omymoney.data.exchange.*
import app.omymoney.feature.bills.billKindLabel
import app.omymoney.ui.components.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Currency

@Composable
fun ExchangeScreen(model: ExchangeViewModel, store: LedgerStore, onBack: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val bills by model.bills.collectAsStateWithLifecycle()
    val selected by model.selected.collectAsStateWithLifecycle()
    val formatName by model.format.collectAsStateWithLifecycle()
    val accounts by store.catalog.observeAccounts().collectAsStateWithLifecycle(emptyList())
    val categories by remember(model.book.id) { store.catalog.observeCategories(model.book.id) }.collectAsStateWithLifecycle(emptyList())
    val format = ExchangeFormat.valueOf(formatName)
    var mapping by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var formatPicker by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    fun back() {
        when {
            state.busy -> if (state.cancellable) model.cancelOperation()
            mapping -> mapping = false
            state.preview != null -> discard = true
            else -> onBack()
        }
    }
    BackHandler { back() }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), model::import)
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(when (format) {
        ExchangeFormat.CSV -> "text/csv"; ExchangeFormat.XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; ExchangeFormat.JSON -> "application/json"
    }), model::export)
    LaunchedEffect(state.error, state.notice) {
        if (state.error == null && state.notice == null) return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        val text = state.error?.let { context.getString(R.string.exchange_failed, it) } ?: when (state.notice) {
            "exported" -> context.getString(R.string.exchange_exported, state.count)
            "imported" -> context.getString(R.string.exchange_imported, state.count)
            "imported_refresh_failed" -> context.getString(R.string.exchange_imported_refresh_failed, state.count)
            "export_cancelled" -> context.getString(R.string.exchange_export_cancelled)
            else -> context.getString(R.string.exchange_cancelled)
        }
        snackbar.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Indefinite)
        model.clearMessage()
    }
    ScreenScaffold(stringResource(if (mapping) R.string.exchange_mapping else if (state.preview != null) R.string.exchange_preview else R.string.exchange_title),
        ::back, backEnabled = !state.busy || state.cancellable, snackbarHostState = snackbar,
        actions = { if (state.preview?.fatalError == null && state.preview != null && !mapping)
            LedgerIconButton(Icons.Outlined.Tune, stringResource(R.string.exchange_mapping), { mapping = true }, Modifier.testTag("exchange_mapping"), enabled = !state.busy)
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.cancellable) TextButton(model::cancelOperation, Modifier.testTag("exchange_cancel_operation")) { Text(stringResource(R.string.cancel)) }
            }
            val preview = state.preview
            when {
                preview != null && mapping -> ImportMappingScreen(preview, accounts, categories, model.book, state.busy, model::remap)
                preview != null -> ImportPreviewScreen(preview, model.book, accounts, categories, selected.toSet(), state.busy, model::select, model::selectAll, model::commit)
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(model.book.name, Modifier.testTag("exchange_book"), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.exchange_sensitive))
                    Text(stringResource(R.string.exchange_scope, if (model.filters.active) listOfNotNull(
                        model.filters.search.takeIf(String::isNotBlank), model.filters.kind?.let { billKindLabel(it) },
                        accounts.firstOrNull { it.id == model.filters.accountId }?.name,
                        categories.firstOrNull { it.id == model.filters.categoryId }?.name,
                        model.filters.tag.takeIf(String::isNotBlank)?.let { "#$it" },
                        model.filters.from.takeIf(String::isNotBlank), model.filters.through.takeIf(String::isNotBlank)
                    ).joinToString(" · ") else stringResource(R.string.exchange_all)), Modifier.testTag("exchange_scope"))
                    when { bills.loading -> CircularProgressIndicator(); bills.failed -> ErrorText(stringResource(R.string.bill_load_failed))
                        else -> Text(stringResource(R.string.exchange_count, bills.bills.size), Modifier.testTag("exchange_count")) }
                    PickerField(stringResource(R.string.exchange_format), format.name, { formatPicker = true }, Modifier.testTag("exchange_format"), enabled = !state.busy)
                    Button({ model.preparePicker(); exportPicker.launch("OMyMoney-${LocalDate.now(model.book.zoneId)}.${format.name.lowercase()}") },
                        Modifier.fillMaxWidth().testTag("exchange_export"), enabled = !state.busy && !bills.loading && !bills.failed) {
                        Icon(Icons.Outlined.Download, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.exchange_export))
                    }
                    HorizontalDivider()
                    Text(stringResource(R.string.exchange_import_destination, model.book.name))
                    Text(stringResource(R.string.exchange_supported))
                    OutlinedButton({ model.preparePicker(); importPicker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().testTag("exchange_import"), enabled = !state.busy) {
                        Icon(Icons.Outlined.Upload, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.exchange_import))
                    }
                }
            }
        }
    }
    if (formatPicker) SelectionSheet(stringResource(R.string.exchange_format), ExchangeFormat.entries.map { SelectionOption(it.name, it.name) }, format.name,
        { model.chooseFormat(ExchangeFormat.valueOf(it)); formatPicker = false }, { formatPicker = false })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.exchange_discard)) },
        text = { Text(stringResource(R.string.exchange_discard_message)) },
        confirmButton = { TextButton({ discard = false; mapping = false; model.discard() }, Modifier.testTag("exchange_discard")) { Text(stringResource(R.string.exchange_discard)) } },
        dismissButton = { TextButton({ discard = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun ImportPreviewScreen(preview: ImportPreview, book: Book, accounts: List<AssetAccount>, categories: List<Category>, selected: Set<Int>, busy: Boolean,
    onSelect: (Int) -> Unit, onSelectAll: () -> Unit, onCommit: () -> Unit) {
    var detail by rememberSaveable(preview.previewId) { mutableStateOf<Int?>(null) }
    val valid = preview.rows.filter { it.selectable }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("exchange_preview_list")) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.exchange_import_destination, book.name), style = MaterialTheme.typography.titleMedium)
                    if (preview.profile == "QIANJI_MODELED") Text(stringResource(R.string.exchange_modeled))
                    if (preview.fatalError != null) ErrorText(stringResource(R.string.exchange_unreadable, preview.fatalError.orEmpty()))
                    else Text(stringResource(R.string.exchange_summary, valid.size, preview.rows.count { it.issues.isNotEmpty() }, preview.rows.count { it.duplicate }), Modifier.testTag("exchange_preview_summary"))
                }
            }
            if (valid.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(valid.all { it.number in selected }, enabled = !busy, role = Role.Checkbox, onValueChange = { onSelectAll() }).testTag("exchange_select_all"), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(valid.all { it.number in selected }, null, enabled = !busy)
                    Text(stringResource(R.string.exchange_select_valid), Modifier.weight(1f))
                }
            }
            items(preview.rows, key = { it.number }) { row ->
                val kind = row.draft?.kind?.let { billKindLabel(it) } ?: stringResource(R.string.exchange_kind_error)
                val amount = row.draft?.amount?.let { "${it.currency.code} ${it.decimalText()}" }.orEmpty()
                val selectionLabel = stringResource(R.string.exchange_select_row, row.number, kind, amount)
                ListItem(modifier = Modifier.clickable { detail = row.number }.testTag("exchange_row_${row.number}"),
                    leadingContent = { Checkbox(row.number in selected, { onSelect(row.number) }, enabled = !busy && row.selectable,
                        modifier = Modifier.testTag("exchange_select_${row.number}").semantics { contentDescription = selectionLabel }) },
                    headlineContent = { Text(stringResource(R.string.exchange_row, row.number) + " · " +
                        (row.draft?.note?.takeIf(String::isNotBlank) ?: preview.sourceValue(row, ImportField.NOTE)).take(160), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(kind)
                        row.draft?.fromAccountId?.let { id -> Text(stringResource(R.string.paying_account) + ": " +
                            (accounts.firstOrNull { it.id == id }?.name ?: stringResource(R.string.unavailable_account))) }
                        row.draft?.toAccountId?.let { id -> Text(stringResource(R.string.receiving_account) + ": " +
                            (accounts.firstOrNull { it.id == id }?.name ?: stringResource(R.string.unavailable_account))) }
                        row.draft?.amount?.let { MoneyText("${it.currency.code} ${it.decimalText()}", Modifier.fillMaxWidth()) }
                        when {
                            row.duplicate -> Text(stringResource(R.string.exchange_duplicate))
                            row.issues.isNotEmpty() -> row.issues.forEach { ErrorText(issueLabel(it)) }
                            else -> Text(stringResource(R.string.exchange_valid))
                        }
                    } }, trailingContent = { Icon(Icons.Outlined.Info, stringResource(R.string.exchange_raw)) })
                HorizontalDivider()
            }
        }
        Button(onCommit, Modifier.fillMaxWidth().padding(16.dp).testTag("exchange_commit"), enabled = !busy && selected.isNotEmpty()) {
            Text(stringResource(R.string.exchange_commit, selected.size))
        }
    }
    val row = preview.rows.firstOrNull { it.number == detail }
    if (row != null) AlertDialog(onDismissRequest = { detail = null }, title = { Text(stringResource(R.string.exchange_row, row.number)) },
        text = { SelectionContainer { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            row.issues.forEach { ErrorText(issueLabel(it)) }
            row.draft?.let { draft ->
                Text(stringResource(R.string.exchange_parsed), style = MaterialTheme.typography.titleMedium)
                draft.kind?.let { Text(billKindLabel(it)) }
                draft.amount?.let { MoneyText("${it.currency.code} ${it.decimalText()}", Modifier.fillMaxWidth()) }
                draft.occurredAt?.let { ImportDetailField(stringResource(R.string.transaction_time),
                    it.atZone(draft.zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX '['VV']'")), "time") }
                listOf(draft.fromAccountId to "from", draft.toAccountId to "to").forEach { (id, tag) ->
                    id?.let { ImportDetailField(stringResource(if (tag == "from") R.string.paying_account else R.string.receiving_account),
                        accounts.firstOrNull { it.id == id }?.name ?: stringResource(R.string.unavailable_account), tag) }
                }
                val category = categories.firstOrNull { it.id == draft.categoryId }
                ImportDetailField(stringResource(R.string.category), category?.let {
                    listOfNotNull(categories.firstOrNull { parent -> parent.id == it.parentId }?.name, it.name).joinToString(" / ")
                } ?: stringResource(R.string.uncategorized), "category")
                draft.fee?.let { ImportDetailField(stringResource(R.string.currency_fee), "${it.currency.code} ${it.decimalText()}", "fee") }
                draft.exchange?.let { fx ->
                    ImportDetailField(stringResource(R.string.currency_actual),
                        "${accounts.firstOrNull { it.id == fx.accountId }?.name ?: fx.accountId}\n${fx.amount.currency.code} ${fx.amount.decimalText()}", "fx")
                    ImportDetailField(stringResource(R.string.exchange_snapshot_rate), "${fx.rate} · ${fx.asOf}", "rate")
                }
                if (draft.note.isNotEmpty()) ImportDetailField(stringResource(R.string.note), draft.note, "note")
                if (draft.tags.isNotEmpty()) ImportDetailField(stringResource(R.string.tags), draft.tags.joinToString(" · "), "tags")
                draft.linkedBillId?.let { id -> ImportDetailField(stringResource(R.string.linked_source),
                    preview.rows.firstOrNull { it.draft?.id == id }?.let { stringResource(R.string.exchange_row, it.number) } ?: id, "link") }
                if (draft.excludeStats) Text(stringResource(R.string.exclude_stats))
                if (draft.excludeBudget) Text(stringResource(R.string.exclude_budget))
                HorizontalDivider()
            }
            Text(stringResource(R.string.exchange_raw), style = MaterialTheme.typography.titleMedium)
            row.raw.forEachIndexed { index, value ->
                Text(preview.columns.getOrNull(index) ?: "${index + 1}", style = MaterialTheme.typography.labelLarge)
                Text(value.ifEmpty { "∅" }, Modifier.testTag("exchange_raw_$index"))
            }
        } } }, confirmButton = { TextButton({ detail = null }) { Text(stringResource(R.string.close)) } })
}

@Composable
private fun ImportDetailField(label: String, value: String, key: String) {
    Column(Modifier.fillMaxWidth().testTag("exchange_parsed_$key").semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value)
    }
}

@Composable
private fun issueLabel(issue: ImportIssue): String = stringResource(R.string.exchange_issue, issue.field, when (issue.code) {
    "overflow" -> stringResource(R.string.error_overflow)
    "unavailable_account" -> stringResource(R.string.exchange_account_error)
    "unavailable_category" -> stringResource(R.string.exchange_category_error)
    "unknown_currency" -> stringResource(R.string.exchange_currency_error)
    "invalid_date" -> stringResource(R.string.exchange_date_error)
    "invalid_amount" -> stringResource(R.string.exchange_amount_error)
    "unsupported_kind" -> stringResource(R.string.exchange_kind_error)
    else -> issue.code
})

@Composable
private fun ImportMappingScreen(preview: ImportPreview, accounts: List<AssetAccount>, categories: List<Category>,
    book: Book, busy: Boolean, remap: (ImportMapping) -> Unit) {
    var pickerType by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerKey by rememberSaveable { mutableStateOf("") }
    val accountNames = remember(preview) { preview.rows.flatMap { row -> listOf(ImportField.FROM, ImportField.TO, ImportField.FX_ACCOUNT).map { preview.sourceValue(row, it) } }.filter(String::isNotEmpty).distinct() }
    val currencyNames = remember(preview) { preview.rows.flatMap { row -> listOf(ImportField.CURRENCY, ImportField.FX_CURRENCY).map { preview.sourceValue(row, it) } }.filter(String::isNotEmpty).distinct() }
    val categoryNames = remember(preview) { preview.rows.mapNotNull { row ->
        val name = preview.sourceValue(row, ImportField.CATEGORY).takeIf(String::isNotEmpty) ?: return@mapNotNull null
        val kind = runCatching { BillKind.valueOf(preview.sourceValue(row, ImportField.KIND)) }.getOrNull()
        "${PostingCalculator.categoryKind(kind)?.name}|$name"
    }.distinct() }
    val units = remember(accounts, book) { (accounts.map { it.currency } + book.currency + Currency.getAvailableCurrencies().filter { it.defaultFractionDigits >= 0 }
        .map { CurrencySpec(it.currencyCode, it.defaultFractionDigits) }).distinct().sortedWith(compareBy({ it.code }, { it.scale })) }
    LazyColumn(Modifier.fillMaxSize().testTag("exchange_mapping_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.exchange_mapping_policy)) }
        item { Text(stringResource(R.string.exchange_columns), style = MaterialTheme.typography.titleMedium) }
        items(ImportField.entries, key = { "field/${it.name}" }) { field ->
            PickerField(field.column, preview.mapping.columns[field]?.let { preview.columns.getOrNull(it) } ?: stringResource(R.string.exchange_unmapped),
                { pickerType = "column"; pickerKey = field.name }, Modifier.testTag("exchange_column_${field.name}"), enabled = !busy)
        }
        item { Text(stringResource(R.string.account), style = MaterialTheme.typography.titleMedium) }
        items(accountNames, key = { "account/$it" }) { name ->
            val matched = accounts.filter { !it.archived && (it.id == preview.mapping.accounts[name] || preview.mapping.accounts[name] == null && it.name == name) }.singleOrNull()
            PickerField(name, matched?.name ?: stringResource(R.string.exchange_unmapped), { pickerType = "account"; pickerKey = name }, Modifier.testTag("exchange_account_$name"), enabled = !busy)
        }
        item { Text(stringResource(R.string.category), style = MaterialTheme.typography.titleMedium) }
        items(categoryNames, key = { "category/$it" }) { key ->
            PickerField(key, categories.firstOrNull { it.id == preview.mapping.categories[key] }?.name ?: stringResource(R.string.exchange_auto_match), { pickerType = "category"; pickerKey = key }, enabled = !busy)
        }
        item { Text(stringResource(R.string.currency), style = MaterialTheme.typography.titleMedium) }
        items(currencyNames, key = { "currency/$it" }) { name ->
            PickerField(name, preview.mapping.currencies[name]?.let { "${it.code} (${it.scale})" } ?: stringResource(R.string.exchange_auto_match), { pickerType = "currency"; pickerKey = name }, enabled = !busy)
        }
    }
    pickerType?.let { type ->
        val reset = SelectionOption("reset", stringResource(if (type == "column") R.string.exchange_unmapped else R.string.exchange_auto_match))
        val options = listOf(reset) + when (type) {
            "column" -> preview.columns.mapIndexed { index, title -> SelectionOption(index.toString(), title, "${index + 1}") }
            "account" -> accounts.filterNot { it.archived }.map { SelectionOption(it.id, it.name, it.currency.code) }
            "category" -> categories.filter { !it.archived && it.kind.name == pickerKey.substringBefore('|') }.map {
                SelectionOption(it.id, it.name, it.parentId?.let { parent -> categories.firstOrNull { c -> c.id == parent }?.name })
            }
            else -> units.mapIndexed { index, unit -> SelectionOption(index.toString(), unit.code, stringResource(R.string.exchange_scale, unit.scale)) }
        }
        val current = when (type) {
            "column" -> preview.mapping.columns[ImportField.valueOf(pickerKey)]?.toString()
            "account" -> preview.mapping.accounts[pickerKey]
            "category" -> preview.mapping.categories[pickerKey]
            else -> units.indexOf(preview.mapping.currencies[pickerKey]).takeIf { it >= 0 }?.toString()
        } ?: "reset"
        SelectionSheet(pickerKey, options, current, { value ->
            val mapping = preview.mapping
            val resetValue = value == "reset"
            remap(when (type) {
                "column" -> mapping.copy(columns = mapping.columns.toMutableMap().apply { val field = ImportField.valueOf(pickerKey); if (resetValue) remove(field) else put(field, value.toInt()) })
                "account" -> mapping.copy(accounts = mapping.accounts.toMutableMap().apply { if (resetValue) remove(pickerKey) else put(pickerKey, value) })
                "category" -> mapping.copy(categories = mapping.categories.toMutableMap().apply { if (resetValue) remove(pickerKey) else put(pickerKey, value) })
                else -> mapping.copy(currencies = mapping.currencies.toMutableMap().apply { if (resetValue) remove(pickerKey) else put(pickerKey, units[value.toInt()]) })
            })
            pickerType = null
        }, { pickerType = null })
    }
}
