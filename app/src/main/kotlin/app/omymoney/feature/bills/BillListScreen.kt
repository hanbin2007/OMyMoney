package app.omymoney.feature.bills

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import app.omymoney.ui.theme.ExpressiveMotion
import app.omymoney.ui.theme.LocalLedgerColors
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.R
import app.omymoney.core.model.*
import app.omymoney.feature.catalog.CatalogState
import app.omymoney.feature.catalog.CatalogViewModel
import app.omymoney.ui.components.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

@Composable
fun BillListActions(viewModel: BillListViewModel, onBatch: () -> Unit = {}, onExport: () -> Unit = {}, onFilter: () -> Unit) {
    LedgerIconButton(Icons.Outlined.Search, stringResource(R.string.search_bills), viewModel::toggleSearch, Modifier.testTag("bill_search_action"))
    LedgerIconButton(Icons.Outlined.FilterList, stringResource(R.string.filter_bills), onFilter, Modifier.testTag("bill_filter_action"))
    var more by rememberSaveable { mutableStateOf(false) }
    Box {
        LedgerIconButton(Icons.Outlined.MoreVert, stringResource(R.string.more_actions), { more = true }, Modifier.testTag("bill_more"))
        DropdownMenu(more, { more = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.batch_title)) }, leadingIcon = { Icon(Icons.Outlined.Checklist, null) },
                onClick = { more = false; onBatch() }, modifier = Modifier.testTag("bill_batch"))
            DropdownMenuItem(text = { Text(stringResource(R.string.exchange_export)) }, leadingIcon = { Icon(Icons.Outlined.Download, null) },
                onClick = { more = false; onExport() }, modifier = Modifier.testTag("bill_export"))
        }
    }
}

@Composable
fun BillListScreen(viewModel: BillListViewModel, book: Book, catalog: CatalogViewModel, catalogState: CatalogState,
                   showFilters: Boolean, onFiltersClosed: () -> Unit, onBill: (String) -> Unit, header: @Composable () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val searchOpen by viewModel.searchOpen.collectAsStateWithLifecycle()
    val categories by remember(book.id) { catalog.observeCategories(book.id, includeArchived = true) }.collectAsStateWithLifecycle(emptyList())
    val listState = rememberLazyListState()
    val searchFocus = remember { FocusRequester() }
    var previousFilters by rememberSaveable { mutableStateOf(filters.bundle()) }
    LaunchedEffect(book) { viewModel.updateBook(book) }
    LaunchedEffect(filters) {
        if (BillFilters.read(previousFilters) != filters) listState.scrollToItem(0)
        previousFilters = filters.bundle()
    }
    LaunchedEffect(searchOpen) { if (searchOpen) searchFocus.requestFocus() }
    Column(Modifier.fillMaxSize().imePadding()) {
        AnimatedVisibility(searchOpen, enter = expandVertically(ExpressiveMotion.defaultSpatial()) + fadeIn(ExpressiveMotion.defaultEffects()),
            exit = shrinkVertically(ExpressiveMotion.fastSpatial()) + fadeOut(ExpressiveMotion.fastEffects())) {
            OutlinedTextField(filters.search, viewModel::search, singleLine = true, shape = CircleShape,
                label = { Text(stringResource(R.string.search_notes)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(searchFocus).testTag("bill_search"),
                trailingIcon = { if (filters.search.isNotEmpty()) LedgerIconButton(Icons.Outlined.Close, stringResource(R.string.clear_search), { viewModel.search("") }) })
        }
        AnimatedVisibility(filters.active, enter = expandVertically(ExpressiveMotion.defaultSpatial()) + fadeIn(),
            exit = shrinkVertically(ExpressiveMotion.fastSpatial()) + fadeOut()) {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.FilterList, contentDescription = null, Modifier.size(18.dp))
                    Text(filterSummary(filters, catalogState.accounts, categories), Modifier.weight(1f).padding(start = 8.dp).testTag("bill_filter_summary"),
                        style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    LedgerIconButton(Icons.Outlined.Close, stringResource(R.string.clear_filters), viewModel::clearFilters)
                }
            }
        }
        when {
            state.loading -> LoadingState()
            state.failed -> Column(Modifier.padding(24.dp)) {
                ErrorText(stringResource(R.string.bill_load_failed))
                TextButton(viewModel::retry) { Text(stringResource(R.string.retry)) }
            }
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("bill_list"), contentPadding = PaddingValues(bottom = 112.dp)) {
                if (!filters.active) item(key = "summary") { header() }
                if (state.bills.isEmpty()) item(key = "empty") {
                    EmptyMessage(stringResource(if (filters.active) R.string.no_matching_bills else R.string.empty_bills),
                        Modifier.padding(horizontal = 24.dp), icon = if (filters.active) Icons.Outlined.SearchOff else Icons.AutoMirrored.Outlined.ReceiptLong,
                        action = if (filters.active) ({
                            FilledTonalButton(viewModel::clearFilters, Modifier.testTag("clear_bill_filters")) { Text(stringResource(R.string.clear_filters)) }
                        }) else null)
                }
                val today = LocalDate.now(book.zoneId)
                val days = state.bills.groupBy { it.occurredAt.atZone(book.zoneId).toLocalDate() }
                days.forEach { (day, bills) ->
                    item(key = "date/$day") { DayHeader(day, today, bills, Modifier.animateItem()) }
                    groupedItems(bills, key = { it.id }) { bill, shape ->
                        val category = categories.firstOrNull { it.id == bill.categoryId }?.name
                        val title = bill.note.ifBlank { category ?: billKindLabel(bill.kind) }
                        GroupedRow(shape, onClick = { onBill(bill.id) }, modifier = Modifier.testTag("bill_row_${bill.id}"),
                            leading = { MonogramBadge(category ?: billKindLabel(bill.kind)) },
                            headline = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supporting = {
                                Text(listOfNotNull(billKindLabel(bill.kind), category, bill.occurredAt.atZone(book.zoneId).format(DateTimeFormatter.ofPattern("HH:mm")))
                                    .joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            trailing = {
                                Column(Modifier.widthIn(max = 168.dp), horizontalAlignment = Alignment.End) {
                                    MoneyText(billAmountText(bill), textAlign = TextAlign.End, style = MaterialTheme.typography.titleMedium,
                                        color = billAmountColor(bill.kind))
                                    bill.exchange?.let { exchange ->
                                        MoneyText("${exchange.amount.currency.code} ${exchange.amount.decimalText()}", textAlign = TextAlign.End,
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            })
                    }
                }
            }
        }
    }
    if (showFilters) BillFilterSheet(filters, book, catalogState.accounts, categories,
        onApply = { viewModel.updateFilters(it); onFiltersClosed() }, onDismiss = onFiltersClosed)
}

fun billAmountText(bill: Bill): String {
    val sign = when (bill.kind) {
        in app.omymoney.core.ledger.PostingCalculator.outgoingKinds -> "-"
        in app.omymoney.core.ledger.PostingCalculator.incomingKinds -> "+"
        else -> ""
    }
    return "${bill.amount.currency.code} $sign${bill.amount.decimalText()}"
}

/** Income reads green, spending keeps the neutral body colour, money moved between accounts is muted. */
@Composable
fun billAmountColor(kind: BillKind): Color = when (kind) {
    in app.omymoney.core.ledger.PostingCalculator.incomingKinds -> LocalLedgerColors.current.income
    in app.omymoney.core.ledger.PostingCalculator.outgoingKinds -> MaterialTheme.colorScheme.onSurface
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun DayHeader(day: LocalDate, today: LocalDate, bills: List<Bill>, modifier: Modifier = Modifier) {
    fun total(kinds: Set<BillKind>): String? = bills.filter { it.kind in kinds }.takeIf { it.isNotEmpty() }
        ?.groupBy { it.amount.currency }?.takeIf { it.size == 1 }?.entries?.single()?.let { (currency, members) ->
            runCatching { members.map { it.amount }.reduce { a, b -> a + b } }.getOrNull()?.let { "${currency.code} ${it.decimalText()}" }
        }
    val label = when (day) {
        today -> stringResource(R.string.today)
        today.minusDays(1) -> stringResource(R.string.yesterday)
        else -> null
    }
    Row(modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label ?: day.format(DateTimeFormatter.ofPattern("MM-dd")), style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface)
        Text(day.format(DateTimeFormatter.ofPattern(if (label == null) "EEE" else "MM-dd EEE")), Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val totals = listOfNotNull(total(app.omymoney.core.ledger.PostingCalculator.outgoingKinds)?.let { stringResource(R.string.day_expense_total, it) },
            total(app.omymoney.core.ledger.PostingCalculator.incomingKinds)?.let { stringResource(R.string.day_income_total, it) })
        if (totals.isNotEmpty()) Text(totals.joinToString("  "), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun filterSummary(filters: BillFilters, accounts: List<AssetAccount>, categories: List<Category>): String = buildList {
    if (filters.search.isNotBlank()) add("“${filters.search}”")
    filters.kind?.let { add(billKindLabel(it)) }
    filters.accountId?.let { id -> add(accounts.firstOrNull { it.id == id }?.name ?: stringResource(R.string.archived)) }
    filters.categoryId?.let { id -> add(categories.firstOrNull { it.id == id }?.let { "${categoryContext(it, categories)} · ${it.name}" }
        ?: stringResource(R.string.archived)) }
    if (filters.tag.isNotBlank()) add("#${filters.tag}")
    if (filters.from.isNotBlank() || filters.through.isNotBlank()) add("${filters.from.ifBlank { "…" }} ~ ${filters.through.ifBlank { "…" }}")
}.joinToString(" · ")

@Composable
private fun categoryContext(category: Category, categories: List<Category>): String = listOfNotNull(
    billKindLabel(category.kind), category.parentId?.let { id -> categories.firstOrNull { it.id == id }?.name },
).joinToString(" · ")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BillFilterSheet(initial: BillFilters, book: Book, accounts: List<AssetAccount>, categories: List<Category>,
                            onApply: (BillFilters) -> Unit, onDismiss: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf(initial.kind?.name) }
    var account by rememberSaveable { mutableStateOf(initial.accountId) }
    var category by rememberSaveable { mutableStateOf(initial.categoryId) }
    var tag by rememberSaveable { mutableStateOf(initial.tag) }
    var from by rememberSaveable { mutableStateOf(initial.from) }
    var through by rememberSaveable { mutableStateOf(initial.through) }
    var errorField by rememberSaveable { mutableStateOf<String?>(null) }
    var validationAttempt by remember { mutableIntStateOf(0) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    val dateFocus = remember { listOf("from", "through").associateWith { FocusRequester() } }
    val datePosition = remember { listOf("from", "through").associateWith { BringIntoViewRequester() } }
    LaunchedEffect(validationAttempt, picker) {
        val field = errorField ?: return@LaunchedEffect
        if (picker != null) return@LaunchedEffect
        dateFocus.getValue(field).requestFocus()
        withFrameNanos { }
        datePosition.getValue(field).bringIntoView()
    }
    if (picker == null) ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.filter_bills), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton({
                    val start = from.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    val end = through.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    errorField = when {
                        from.isNotBlank() && start == null -> "from"
                        through.isNotBlank() && end == null -> "through"
                        start != null && end != null && end < start -> "through"
                        else -> null
                    }
                    validationAttempt += 1
                    if (errorField == null) onApply(BillFilters(initial.search, kind?.let(BillKind::valueOf), account, category, tag, from, through))
                }, Modifier.testTag("apply_bill_filters")) { Text(stringResource(R.string.apply)) }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.date_range), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val today = LocalDate.now(book.zoneId)
                    listOf(R.string.all_dates to (null to null), R.string.today to (today to today),
                        R.string.this_week to (today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))),
                        R.string.this_month to (today.withDayOfMonth(1) to today.withDayOfMonth(today.lengthOfMonth())),
                        R.string.this_year to (today.withDayOfYear(1) to today.withDayOfYear(today.lengthOfYear()))).forEach { (label, range) ->
                        FilterChip(selected = from == range.first?.toString().orEmpty() && through == range.second?.toString().orEmpty(),
                            onClick = { from = range.first?.toString().orEmpty(); through = range.second?.toString().orEmpty(); errorField = null },
                            label = { Text(stringResource(label)) })
                    }
                }
                OutlinedTextField(from, { from = it; errorField = null }, label = { Text(stringResource(R.string.from_date)) },
                    placeholder = { Text("YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("filter_from")
                        .focusRequester(dateFocus.getValue("from")).bringIntoViewRequester(datePosition.getValue("from")), isError = errorField == "from",
                    supportingText = if (errorField == "from") ({ ErrorText(stringResource(R.string.invalid_date_range)) }) else null)
                OutlinedTextField(through, { through = it; errorField = null }, label = { Text(stringResource(R.string.through_date)) },
                    placeholder = { Text("YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("filter_through")
                        .focusRequester(dateFocus.getValue("through")).bringIntoViewRequester(datePosition.getValue("through")), isError = errorField == "through",
                    supportingText = if (errorField == "through") ({ ErrorText(stringResource(R.string.invalid_date_range)) }) else null)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind == null, { kind = null }, label = { Text(stringResource(R.string.all_kinds)) })
                    app.omymoney.core.ledger.PostingCalculator.supportedKinds.forEach { value -> FilterChip(kind == value.name,
                        {
                            if (kind != value.name) {
                                kind = value.name
                                if (categories.firstOrNull { it.id == category }?.kind != app.omymoney.core.ledger.PostingCalculator.categoryKind(value)) category = null
                            }
                        }, label = { Text(billKindLabel(value)) }) }
                }
                PickerField(stringResource(R.string.account), accounts.firstOrNull { it.id == account }?.name ?: stringResource(R.string.all_accounts), { picker = "account" })
                PickerField(stringResource(R.string.category), categories.firstOrNull { it.id == category }?.let { "${categoryContext(it, categories)} · ${it.name}" }
                    ?: stringResource(R.string.all_categories), { picker = "category" }, Modifier.testTag("filter_category"))
                OutlinedTextField(tag, { tag = it }, label = { Text(stringResource(R.string.filter_tag)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text(book.zoneId.id, style = MaterialTheme.typography.bodySmall)
            }
        }
    } else {
        val isAccount = picker == "account"
        val options = listOf(SelectionOption("all", stringResource(if (isAccount) R.string.all_accounts else R.string.all_categories))) +
            if (isAccount) accounts.map { SelectionOption(it.id, if (it.archived) stringResource(R.string.archived_name, it.name) else it.name) }
            else categories.filter { kind == null || it.kind == app.omymoney.core.ledger.PostingCalculator.categoryKind(kind?.let(BillKind::valueOf)) }.map { SelectionOption(it.id,
                if (it.archived) stringResource(R.string.archived_name, it.name) else it.name, categoryContext(it, categories)) }
        SelectionSheet(stringResource(if (isAccount) R.string.select_account else R.string.select_category), options,
            (if (isAccount) account else category) ?: "all", onSelect = {
                if (isAccount) account = it.takeUnless { id -> id == "all" } else category = it.takeUnless { id -> id == "all" }
                picker = null
            }, onDismiss = { picker = null })
    }
}
