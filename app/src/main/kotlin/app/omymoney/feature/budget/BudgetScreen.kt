package app.omymoney.feature.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.R
import app.omymoney.core.budget.*
import app.omymoney.core.model.*
import app.omymoney.data.*
import app.omymoney.ui.components.*
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private data class BudgetViewState(val loading: Boolean = true, val failed: Boolean = false,
    val plan: BudgetPlan? = null, val summary: BudgetSummary? = null, val categories: List<Category> = emptyList())

@Composable
private fun currentDate(book: Book): LocalDate {
    var date by remember(book.zoneId) { mutableStateOf(LocalDate.now(book.zoneId)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, book.zoneId) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) date = LocalDate.now(book.zoneId) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(book.zoneId) {
        while (true) {
            val now = ZonedDateTime.now(book.zoneId)
            date = now.toLocalDate()
            delay(Duration.between(now, date.plusDays(1).atStartOfDay(book.zoneId)).toMillis().coerceAtLeast(1))
        }
    }
    return date
}

@Composable
private fun budgetState(store: LedgerStore, book: Book, period: BudgetPeriod, today: LocalDate, retry: Int): State<BudgetViewState> =
    key(book, period, today, retry) {
        val flow = remember {
            combine(store.budgets.observe(book.id, period), store.ledger.observeBills(BillQuery(bookId = book.id)),
                store.catalog.observeCategories(book.id, includeArchived = true), store.catalog.observeExchangeRates()) { plan, bills, categories, rates ->
                BudgetViewState(loading = false, plan = plan, categories = categories,
                    summary = plan?.let { BudgetCalculator.calculate(period, bills, it, categories, book.zoneId, today, rates) })
            }.onStart {
                check(store.budgets.copyPreviousIfEligible(book.id, period) is CatalogResult.Saved)
            }.flowOn(Dispatchers.Default).catch { emit(BudgetViewState(loading = false, failed = true)) }
        }
        flow.collectAsStateWithLifecycle(BudgetViewState())
    }

internal fun budgetMoney(value: BigInteger, currency: CurrencySpec) = "${currency.code} ${BigDecimal(value, currency.scale).toPlainString()}"

@Composable
fun HomeBudgetSummary(store: LedgerStore, book: Book, onOpen: () -> Unit) {
    val today = currentDate(book)
    val period = BudgetPeriod(today.year, today.monthValue)
    var retry by remember { mutableIntStateOf(0) }
    val state by budgetState(store, book, period, today, retry)
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onOpen, Modifier.testTag("home_budget_open")) { Text(stringResource(R.string.budget_month_heading, period.key)) }
        when {
            state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            state.failed -> { ErrorText(stringResource(R.string.budget_load_failed)); TextButton({ retry++ }) { Text(stringResource(R.string.retry)) } }
            state.summary == null -> Text(stringResource(R.string.budget_unset), style = MaterialTheme.typography.bodyMedium)
            else -> BudgetRemaining(requireNotNull(state.summary).total, "home_budget_remaining")
        }
    }
    HorizontalDivider()
}

@Composable
private fun BudgetRemaining(usage: BudgetUsage, tag: String) {
    if (usage.remaining == null) ErrorText(stringResource(R.string.budget_missing_rates, usage.missingCurrencies.joinToString { it.code }))
    else {
        Text(stringResource(if (usage.remaining!!.signum() < 0) R.string.budget_overspent else R.string.budget_remaining),
            style = MaterialTheme.typography.labelLarge)
        MoneyText(budgetMoney(usage.remaining!!.abs(), usage.limit.currency), Modifier.fillMaxWidth().testTag(tag),
            style = MaterialTheme.typography.titleLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetScreen(store: LedgerStore, book: Book, onBack: () -> Unit) {
    val today = currentDate(book)
    var year by rememberSaveable { mutableIntStateOf(today.year) }
    var month by rememberSaveable { mutableIntStateOf(today.monthValue) }
    var annual by rememberSaveable { mutableStateOf(false) }
    val period = BudgetPeriod(year, month.takeUnless { annual })
    var retry by remember { mutableIntStateOf(0) }
    val state by budgetState(store, book, period, today, retry)
    var editing by rememberSaveable { mutableStateOf(false) }
    var editJson by rememberSaveable { mutableStateOf<String?>(null) }
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingPeriod by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun shift(forward: Boolean) {
        val shifted = if (annual) period.start.plusYears(if (forward) 1 else -1) else period.start.plusMonths(if (forward) 1 else -1)
        year = shifted.year
        if (!annual) month = shifted.monthValue
        error = null
    }
    if (editing) {
        val original = remember(editJson) { editJson?.let { Json.decodeFromString<BudgetPlan>(it) } }
        BudgetEditorScreen(store.budgets, book, period, original, state.categories, onBack = { editing = false }, onSaved = {
            editing = false
            scope.launch { snackbar.showSnackbar(context.getString(R.string.budget_saved)) }
        })
        return
    }
    ScreenScaffold(stringResource(R.string.budget_title), onBack, snackbarHostState = snackbar, actions = {
        LedgerIconButton(Icons.Outlined.ContentCopy, stringResource(R.string.budget_copy_previous), { confirm = "copy" },
            Modifier.testTag("budget_copy"), enabled = !busy && !state.loading && !(year == 1 && (annual || month == 1)))
        if (state.plan != null) LedgerIconButton(Icons.Outlined.Delete, stringResource(R.string.budget_delete), { confirm = "delete" },
            Modifier.testTag("budget_delete"), enabled = !busy)
        LedgerIconButton(if (state.plan == null) Icons.Outlined.Add else Icons.Outlined.Edit, stringResource(R.string.budget_edit), {
            editJson = state.plan?.let { Json.encodeToString(it) }; editing = true
        }, Modifier.testTag("budget_edit"), enabled = !busy && !state.loading && !state.failed)
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("budget_list"), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(book.name, style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    listOf(false, true).forEachIndexed { index, value ->
                        SegmentedButton(annual == value, { annual = value; error = null }, SegmentedButtonDefaults.itemShape(index, 2),
                            Modifier.testTag(if (value) "budget_annual" else "budget_monthly"), enabled = !busy) {
                            Text(stringResource(if (value) R.string.budget_annual else R.string.budget_monthly))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    LedgerIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.budget_previous), { shift(false) },
                        Modifier.testTag("budget_previous"), enabled = !busy && !(year == 1 && (annual || month == 1)))
                    TextButton({ choosingPeriod = true }, Modifier.weight(1f).testTag("budget_period"), enabled = !busy) { Text(period.key) }
                    LedgerIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.budget_next), { shift(true) },
                        Modifier.testTag("budget_next"), enabled = !busy && !(year == 9998 && (annual || month == 12)))
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { ErrorText(it) }
            }
            when {
                state.loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.failed -> item { ErrorText(stringResource(R.string.budget_load_failed)); TextButton({ retry++ }) { Text(stringResource(R.string.retry)) } }
                state.plan == null -> item { Text(stringResource(R.string.budget_unset), Modifier.testTag("budget_empty")) }
                else -> {
                    val summary = requireNotNull(state.summary)
                    val plan = requireNotNull(state.plan)
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            BudgetRemaining(summary.total, "budget_remaining")
                            Text(stringResource(R.string.budget_total), style = MaterialTheme.typography.labelMedium)
                            MoneyText(budgetMoney(BigInteger.valueOf(plan.total.minor), book.currency), Modifier.fillMaxWidth())
                            Text(stringResource(R.string.budget_used), style = MaterialTheme.typography.labelMedium)
                            MoneyText(summary.total.used?.let { budgetMoney(it, book.currency) } ?: stringResource(R.string.budget_unavailable), Modifier.fillMaxWidth())
                            Text(stringResource(R.string.budget_daily, summary.daysRemaining), style = MaterialTheme.typography.labelMedium)
                            MoneyText(summary.dailyAvailable?.let { budgetMoney(it, book.currency) } ?: stringResource(R.string.budget_unavailable), Modifier.fillMaxWidth())
                            plan.copiedFrom?.let { Text(stringResource(R.string.budget_copied_from, it.key), style = MaterialTheme.typography.bodySmall) }
                            summary.ratesUsed.forEach { Text(stringResource(R.string.budget_rate_basis, it.source.code, it.target.code, it.rate, it.asOf.toString()), style = MaterialTheme.typography.bodySmall) }
                        }
                        HorizontalDivider(Modifier.padding(top = 16.dp))
                    }
                    items(plan.categories, key = { it.categoryId }) { limit ->
                        val category = state.categories.firstOrNull { it.id == limit.categoryId }
                        val usage = summary.categories.getValue(limit.categoryId)
                        Column(Modifier.fillMaxWidth().testTag("budget_category_${limit.categoryId}").semantics(mergeDescendants = true) {},
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(category?.let { budgetCategoryName(it, state.categories) } ?: limit.categoryId, style = MaterialTheme.typography.titleMedium)
                            if (state.categories.any { it.parentId == limit.categoryId }) Text(stringResource(R.string.budget_includes_children), style = MaterialTheme.typography.bodySmall)
                            BudgetRemaining(usage, "budget_remaining_${limit.categoryId}")
                            Text(stringResource(R.string.budget_limit), style = MaterialTheme.typography.labelMedium)
                            MoneyText(budgetMoney(BigInteger.valueOf(limit.limit.minor), book.currency), Modifier.fillMaxWidth())
                        }
                        HorizontalDivider(Modifier.padding(top = 16.dp))
                    }
                }
            }
        }
    }
    if (confirm != null) AlertDialog(onDismissRequest = { if (!busy) confirm = null },
        title = { Text(stringResource(if (confirm == "delete") R.string.budget_delete else R.string.budget_copy_previous)) },
        text = { Text(stringResource(if (confirm == "delete") R.string.budget_delete_message else R.string.budget_copy_message)) },
        confirmButton = { TextButton({
            val action = confirm
            confirm = null; busy = true; error = null
            scope.launch(Dispatchers.Main.immediate) {
                try {
                    val result = if (action == "delete") store.budgets.delete(book.id, period) else store.budgets.copyPrevious(book.id, period)
                    if (result is CatalogResult.Saved) scope.launch {
                        snackbar.showSnackbar(context.getString(if (action == "delete") R.string.budget_deleted else R.string.budget_saved))
                    }
                    else error = budgetError(context, (result as? CatalogResult.Invalid)?.fieldErrors?.firstOrNull()?.code)
                } catch (cancel: CancellationException) { throw cancel
                } catch (_: Exception) { error = context.getString(R.string.error_save)
                } finally { busy = false }
            }
        }, Modifier.testTag(if (confirm == "delete") "budget_delete_confirm" else "budget_copy_confirm")) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.cancel)) } })
    if (choosingPeriod) BudgetPeriodDialog(period, { year = it.year; it.month?.let { value -> month = value }; choosingPeriod = false; error = null }, { choosingPeriod = false })
}

internal fun budgetCategoryName(category: Category, categories: List<Category>): String =
    listOfNotNull(category.parentId?.let { id -> categories.firstOrNull { it.id == id }?.name }, category.name).joinToString(" / ")

@Composable
private fun BudgetPeriodDialog(period: BudgetPeriod, onChoose: (BudgetPeriod) -> Unit, onDismiss: () -> Unit) {
    var year by rememberSaveable { mutableStateOf(period.year.toString()) }
    var month by rememberSaveable { mutableStateOf(period.month?.toString().orEmpty()) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.safeDrawingPadding().imePadding(),
        properties = androidx.compose.ui.window.DialogProperties(decorFitsSystemWindows = false),
        title = { Text(stringResource(R.string.budget_choose_period)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(year, { year = it; invalid = false }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.budget_year)) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = invalid && year.toIntOrNull() !in 1..9998)
            if (period.month != null) OutlinedTextField(month, { month = it; invalid = false }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.budget_month)) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = invalid && month.toIntOrNull() !in 1..12)
            if (invalid) ErrorText(stringResource(R.string.budget_invalid_period))
        } }, confirmButton = { TextButton({
            invalid = year.toIntOrNull() !in 1..9998 || (period.month != null && month.toIntOrNull() !in 1..12)
            if (!invalid) onChoose(BudgetPeriod(year.toInt(), if (period.month == null) null else month.toInt()))
        }) { Text(stringResource(R.string.confirm)) } }, dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}
