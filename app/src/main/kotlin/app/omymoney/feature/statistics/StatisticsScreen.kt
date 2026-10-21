package app.omymoney.feature.statistics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.R
import app.omymoney.core.budget.BudgetPeriod
import app.omymoney.core.ledger.EconomicKind
import app.omymoney.core.model.*
import app.omymoney.core.statistics.*
import app.omymoney.data.LedgerStore
import app.omymoney.ui.components.*
import java.math.BigInteger
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private data class StatisticsState(val summary: StatisticsSummary? = null, val categories: List<Category> = emptyList(),
    val failed: Boolean = false)

@Composable
private fun statisticsState(store: LedgerStore, book: Book, period: BudgetPeriod, filter: StatisticsFilter, retry: Int): State<StatisticsState> =
    key(book, period, filter, retry) {
        val flow = remember {
            combine(store.ledger.observeBills(BillQuery(bookId = book.id)), store.catalog.observeCategories(book.id, includeArchived = true),
                store.catalog.observeExchangeRates()) { bills, categories, rates ->
                StatisticsState(StatisticsCalculator.summarize(bills, StatisticsRange(book.id, period.start, period.endExclusive, book.zoneId),
                    book.currency, filter, categories, rates), categories)
            }.flowOn(Dispatchers.Default).catch { emit(StatisticsState(failed = true)) }
        }
        flow.collectAsStateWithLifecycle(StatisticsState())
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(store: LedgerStore, book: Book, accounts: List<AssetAccount>, showFilter: Boolean,
    onFiltersClosed: () -> Unit, onBill: (String) -> Unit) {
    val today = LocalDate.now(book.zoneId)
    var year by rememberSaveable { mutableIntStateOf(today.year) }
    var month by rememberSaveable { mutableIntStateOf(today.monthValue) }
    var annual by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var account by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var tag by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var showPeriod by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val filter = StatisticsFilter(kind?.let(EconomicKind::valueOf), account, category, tag, search)
    val active = filter != StatisticsFilter()
    val period = BudgetPeriod(year, month.takeUnless { annual })
    val state by statisticsState(store, book, period, filter, retry)
    var calendarMonth by rememberSaveable(period.key) { mutableIntStateOf(if (annual) month else requireNotNull(period.month)) }
    var selectedDay by rememberSaveable(period.key) { mutableStateOf(
        if (YearMonth.from(today) == YearMonth.of(year, calendarMonth)) today.toString() else LocalDate.of(year, calendarMonth, 1).toString()) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentScope = listOf(tab.toString(), period.key, kind, account, category, tag, search)
    var previousScope by rememberSaveable { mutableStateOf(currentScope) }
    LaunchedEffect(currentScope) {
        if (previousScope != currentScope) { list.scrollToItem(0); previousScope = currentScope }
    }
    fun shift(direction: Long) {
        val date = if (annual) period.start.plusYears(direction) else period.start.plusMonths(direction)
        year = date.year; if (!annual) month = date.monthValue
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PrimaryScrollableTabRow(tab, Modifier.weight(1f), edgePadding = 0.dp) {
                listOf(R.string.stats_overview, R.string.stats_categories, R.string.stats_calendar, R.string.stats_entries).forEachIndexed { index, label ->
                    Tab(tab == index, { tab = index }, Modifier.testTag("stats_tab_$index"), text = { Text(stringResource(label)) })
                }
            }
            if (active) LedgerIconButton(Icons.Outlined.Close, stringResource(R.string.clear_filters), {
                kind = null; account = null; category = null; tag = ""; search = ""
            }, Modifier.testTag("stats_clear"))
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val shortHeight = maxHeight < 240.dp
            val calendarList = maxWidth < 368.dp || LocalDensity.current.fontScale >= 1.5f || shortHeight
            val summary = state.summary
            if (summary == null) {
                if (state.failed) Column(Modifier.padding(16.dp)) {
                    ErrorText(stringResource(R.string.stats_load_failed))
                    TextButton({ retry++ }) { Text(stringResource(R.string.retry)) }
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
            } else LazyColumn(Modifier.fillMaxSize().testTag("stats_list"), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "period") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf(false, true).forEachIndexed { index, value ->
                                SegmentedButton(annual == value, { annual = value }, SegmentedButtonDefaults.itemShape(index, 2),
                                    Modifier.testTag(if (value) "stats_annual" else "stats_monthly")) { Text(stringResource(if (value) R.string.stats_annual else R.string.stats_monthly)) }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            LedgerIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.budget_previous), { shift(-1) },
                                Modifier.testTag("stats_previous"), enabled = !(year == 1 && (annual || month == 1)))
                            TextButton({ showPeriod = true }, Modifier.weight(1f).testTag("stats_period")) { Text(period.key) }
                            LedgerIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.budget_next), { shift(1) },
                                Modifier.testTag("stats_next"), enabled = !(year == 9998 && (annual || month == 12)))
                        }
                        Text(stringResource(R.string.stats_date_scope, period.start.toString(), period.endExclusive.minusDays(1).toString(), book.zoneId.id), style = MaterialTheme.typography.bodySmall)
                        if (active) Text(buildList {
                            filter.kind?.let { add(economicLabel(it)) }
                            account?.let { id -> add(accounts.firstOrNull { it.id == id }?.name ?: stringResource(R.string.archived)) }
                            category?.let { id -> add(state.categories.firstOrNull { it.id == id }?.let { statisticsCategoryName(it, state.categories) } ?: stringResource(R.string.archived)) }
                            if (tag.isNotBlank()) add("#$tag")
                            if (search.isNotBlank()) add(search)
                        }.joinToString(" · "), Modifier.testTag("stats_filter_summary"), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                when {
                    tab == 0 -> {
                        listOf(EconomicKind.EXPENSE, EconomicKind.INCOME, EconomicKind.TRANSFER).forEach { value -> item {
                            Text(economicLabel(value), style = MaterialTheme.typography.labelLarge)
                            StatisticsValue(summary.totals[value], book.currency, Modifier.fillMaxWidth().testTag("stats_total_$value"))
                        } }
                        item {
                            Text(stringResource(R.string.stats_net), style = MaterialTheme.typography.labelLarge)
                            Text(summary.totals.net?.let { statsMoney(it, book.currency) } ?: stringResource(R.string.stats_unavailable), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
                            summary.ratesUsed.forEach { Text(stringResource(R.string.budget_rate_basis, it.source.code, it.target.code, it.rate, it.asOf.toString()), style = MaterialTheme.typography.bodySmall) }
                        }
                        if (summary.entries.isEmpty()) item { Text(stringResource(R.string.stats_empty), Modifier.testTag("stats_empty")) }
                        item { StatisticsChart(if (annual) summary.months.map { ChartPoint(it.month.toString(), it.totals) }
                            else summary.days.map { ChartPoint(it.date.toString().takeLast(5), it.totals) }, book.currency, shortHeight) }
                        if (annual) annualSummary(summary) else {
                            item { Text(stringResource(R.string.stats_daily), style = MaterialTheme.typography.titleMedium) }
                            items(summary.days, key = { "trend/${it.date}" }) { day ->
                                Column(Modifier.fillMaxWidth().testTag("stats_trend_${day.date}").semantics(mergeDescendants = true) {}) {
                                    Text(day.date.toString(), style = MaterialTheme.typography.titleSmall)
                                    TotalsData(day.totals, book.currency, "stats_trend_${day.date}")
                                    HorizontalDivider(Modifier.padding(top = 8.dp))
                                }
                            }
                        }
                    }
                    tab == 1 -> {
                        if (summary.categories.isEmpty()) item { Text(stringResource(R.string.stats_empty), Modifier.testTag("stats_empty")) }
                        val maximum = summary.categories.mapNotNull { it.amount.minor?.abs() }.maxOrNull()
                        if (maximum != null) item {
                            Text(stringResource(R.string.stats_category_scale, statsMoney(-maximum, book.currency), statsMoney(maximum, book.currency)), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.stats_zero_axis), Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                        }
                        items(summary.categories, key = { "category/${it.kind}/${it.categoryId}" }) { row ->
                            Column(Modifier.fillMaxWidth().testTag("stats_category_${row.kind}_${row.categoryId}").semantics(mergeDescendants = true) {},
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val name = state.categories.firstOrNull { it.id == row.categoryId }?.let { statisticsCategoryName(it, state.categories) } ?: stringResource(R.string.stats_uncategorized)
                                Text(stringResource(R.string.stats_category_description, economicLabel(row.kind), name), style = MaterialTheme.typography.titleSmall)
                                StatisticsValue(row.amount, book.currency, Modifier.fillMaxWidth())
                                if (maximum != null) CategoryBar(row.amount.minor, maximum)
                                HorizontalDivider()
                            }
                        }
                    }
                    tab == 2 -> {
                        val displayedMonth = YearMonth.of(year, if (annual) calendarMonth else month)
                        item { CalendarHeader(displayedMonth, annual) {
                            calendarMonth += it
                            selectedDay = LocalDate.of(year, calendarMonth, 1).toString()
                        } }
                        calendarDays(summary, displayedMonth, LocalDate.parse(selectedDay), calendarList, filter.kind ?: EconomicKind.EXPENSE) { date ->
                            selectedDay = date.toString()
                            scope.launch { list.animateScrollToItem(2 + if (calendarList) displayedMonth.lengthOfMonth() else 1) }
                        }
                        item { Text(selectedDay, Modifier.testTag("stats_selected_date"), style = MaterialTheme.typography.titleMedium) }
                        item { TotalsData(summary.days.single { it.date == LocalDate.parse(selectedDay) }.totals, book.currency, "stats_day") }
                        val entries = summary.entries.filter { it.date == LocalDate.parse(selectedDay) }
                        if (entries.isEmpty()) item { Text(stringResource(R.string.stats_no_day_entries)) }
                        statisticsEntries(entries, summary, onBill)
                    }
                    else -> {
                        if (summary.entries.isEmpty()) item { Text(stringResource(R.string.stats_empty), Modifier.testTag("stats_empty")) }
                        statisticsEntries(summary.entries, summary, onBill)
                    }
                }
            }
        }
    }
    if (showFilter) StatisticsFilterSheet(filter, accounts, state.categories, {
        kind = it.kind?.name; account = it.accountId; category = it.categoryId; tag = it.tag; search = it.search; onFiltersClosed()
    }, onFiltersClosed)
    if (showPeriod) StatisticsPeriodDialog(period, { year = it.year; it.month?.let { value -> month = value }; showPeriod = false }, { showPeriod = false })
}

private fun LazyListScope.statisticsEntries(entries: List<StatisticsEntry>, summary: StatisticsSummary, onBill: (String) -> Unit) {
    items(entries, key = { "entry/${it.key}" }) { entry ->
        val fact = entry.fact
        val label = when {
            fact.fee -> stringResource(R.string.stats_fee)
            fact.bill.kind == BillKind.REFUND -> stringResource(if (fact.kind == EconomicKind.EXPENSE) R.string.stats_refund_reduction else R.string.stats_refund_income)
            else -> economicLabel(fact.kind)
        }
        ListItem(modifier = Modifier.testTag("stats_entry_${entry.key}").clickable { onBill(fact.bill.id) },
            headlineContent = { Text(fact.bill.note.ifBlank { label }) }, supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${entry.date} · $label", style = MaterialTheme.typography.bodySmall)
                    StatisticsValue(StatisticsAmount(entry.valuation.minor, setOfNotNull(entry.valuation.missingCurrency)), summary.currency, Modifier.fillMaxWidth())
                    if (fact.amount.currency != summary.currency) Text(stringResource(R.string.stats_nominal, fact.amount.currency.code, fact.amount.decimalText()), style = MaterialTheme.typography.bodySmall)
                }
            })
        HorizontalDivider()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatisticsPeriodDialog(period: BudgetPeriod, onChoose: (BudgetPeriod) -> Unit, onDismiss: () -> Unit) {
    var year by rememberSaveable { mutableStateOf(period.year.toString()) }
    var month by rememberSaveable { mutableStateOf(period.month?.toString().orEmpty()) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val errorPosition = remember { BringIntoViewRequester() }
    LaunchedEffect(invalid) { if (invalid) { withFrameNanos { }; errorPosition.bringIntoView() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.budget_choose_period), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(year, { year = it; invalid = false }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.budget_year)) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = invalid && year.toIntOrNull() !in 1..9998)
            if (period.month != null) OutlinedTextField(month, { month = it; invalid = false }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.budget_month)) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = invalid && month.toIntOrNull() !in 1..12)
            if (invalid) Box(Modifier.bringIntoViewRequester(errorPosition)) { ErrorText(stringResource(R.string.budget_invalid_period)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onDismiss) { Text(stringResource(R.string.cancel)) }
                TextButton({
                    invalid = year.toIntOrNull() !in 1..9998 || (period.month != null && month.toIntOrNull() !in 1..12)
                    if (!invalid) onChoose(BudgetPeriod(year.toInt(), if (period.month == null) null else month.toInt()))
                }) { Text(stringResource(R.string.confirm)) }
            }
        }
    }
}
