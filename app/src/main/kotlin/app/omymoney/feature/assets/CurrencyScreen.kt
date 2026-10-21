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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.R
import app.omymoney.core.ledger.ExchangeRules
import app.omymoney.core.model.*
import app.omymoney.data.CatalogRepository
import app.omymoney.data.CatalogResult
import app.omymoney.feature.catalog.CatalogState
import app.omymoney.ui.components.*
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

val commonCurrencies = listOf(CurrencySpec.CNY, CurrencySpec("HKD", 2), CurrencySpec("USD", 2),
    CurrencySpec("EUR", 2), CurrencySpec("GBP", 2), CurrencySpec("JPY", 0), CurrencySpec("KWD", 3))

private fun ManualExchangeRate.key() = "${source.code}:${source.scale}/${target.code}:${target.scale}"

@Composable
fun CurrencyScreen(repository: CatalogRepository, state: CatalogState, onBack: () -> Unit) {
    val rates by remember(repository) { repository.observeExchangeRates() }.collectAsStateWithLifecycle(emptyList())
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val currency = state.currentBook?.currency ?: CurrencySpec.CNY
    val options = (commonCurrencies + state.accounts.map { it.currency } + rates.flatMap { listOf(it.source, it.target) }).distinct()
    val failureMessage = stringResource(R.string.bill_save_failed)
    val original = rates.firstOrNull { it.key() == editing }
    if (editing != null) {
        key(editing) { CurrencyRateEditor(original, currency, options, rates, repository) { editing = null } }
        return
    }
    ScreenScaffold(stringResource(R.string.currency_rates), onBack, snackbarHostState = snackbar, actions = {
        LedgerIconButton(Icons.Outlined.Add, stringResource(R.string.currency_rate_add), { editing = "new" }, Modifier.testTag("rate_add"))
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("rate_list")) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.currency_valuation), style = MaterialTheme.typography.labelLarge)
                    val valuation = runCatching { ExchangeRules.value(state.accounts.filterNot { it.archived }.map { it.balance }, currency, rates) }.getOrNull()
                    when {
                        valuation == null -> ErrorText(stringResource(R.string.error_overflow))
                        valuation.total == null -> Text(stringResource(R.string.currency_missing, valuation.missingCurrencies.joinToString { "${it.code}/${currency.code}" }), Modifier.testTag("valuation_missing"))
                        else -> MoneyText("${currency.code} ${requireNotNull(valuation.total).decimalText()}", Modifier.fillMaxWidth().testTag("valuation_total"), MaterialTheme.typography.headlineSmall)
                    }
                }
                HorizontalDivider()
            }
            if (rates.isEmpty()) item { EmptyState(stringResource(R.string.currency_rate_empty), Modifier.padding(24.dp)) }
            items(rates, key = { it.key() }) { rate ->
                ListItem(headlineContent = { Text("${rate.source.code} / ${rate.target.code}") },
                    supportingContent = { Column {
                        Text("1 ${rate.source.code} = ${rate.rate} ${rate.target.code}")
                        Text(stringResource(R.string.currency_rate_dated, rate.asOf.toString()))
                    } }, trailingContent = {
                        LedgerIconButton(Icons.Outlined.Delete, stringResource(R.string.currency_rate_delete, rate.source.code, rate.target.code),
                            { deleting = rate.key() }, Modifier.testTag("rate_delete_${rate.source.code}_${rate.target.code}"), enabled = !busy)
                    }, modifier = Modifier.clickable(enabled = !busy) { editing = rate.key() }.testTag("rate_edit_${rate.source.code}_${rate.target.code}"))
            }
        }
    }
    rates.firstOrNull { it.key() == deleting }?.let { rate ->
        AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text(stringResource(R.string.currency_rate_delete, rate.source.code, rate.target.code)) },
            text = { Text(stringResource(R.string.currency_rate_delete_note)) }, confirmButton = {
                TextButton({ busy = true; scope.launch(Dispatchers.Main.immediate) {
                    val result = repository.removeExchangeRate(rate.source, rate.target)
                    busy = false; deleting = null
                    if (result !is CatalogResult.Saved) snackbar.showSnackbar(failureMessage)
                } }, enabled = !busy, modifier = Modifier.testTag("rate_delete_confirm")) { Text(stringResource(R.string.currency_delete_confirm)) }
            }, dismissButton = { TextButton({ deleting = null }, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun CurrencyRateEditor(original: ManualExchangeRate?, base: CurrencySpec, currencies: List<CurrencySpec>,
                               rates: List<ManualExchangeRate>, repository: CatalogRepository, onBack: () -> Unit) {
    var sourceCode by rememberSaveable { mutableStateOf(original?.source?.code ?: if (base.code == "USD") "CNY" else "USD") }
    var sourceScale by rememberSaveable { mutableIntStateOf(original?.source?.scale ?: 2) }
    var targetCode by rememberSaveable { mutableStateOf(original?.target?.code ?: base.code) }
    var targetScale by rememberSaveable { mutableIntStateOf(original?.target?.scale ?: base.scale) }
    var rateText by rememberSaveable { mutableStateOf(original?.rate.orEmpty()) }
    var dateText by rememberSaveable { mutableStateOf((original?.asOf ?: LocalDate.now()).toString()) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var dirty by rememberSaveable { mutableStateOf(false) }
    var askLeave by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val focus = remember { listOf("rate", "date").associateWith { FocusRequester() } }
    val positions = remember { listOf("pair", "rate", "date", "form").associateWith { BringIntoViewRequester() } }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    fun back() { if (!busy) { keyboard?.hide(); if (dirty) askLeave = true else onBack() } }
    BackHandler(picker == null && !askLeave) { back() }
    LaunchedEffect(attempt) {
        val field = error ?: return@LaunchedEffect
        focus[field]?.requestFocus()
        withFrameNanos { }
        positions.getValue(field.takeIf { it in positions } ?: "form").bringIntoView()
    }
    val source = CurrencySpec(sourceCode, sourceScale); val target = CurrencySpec(targetCode, targetScale)
    ScreenScaffold(stringResource(if (original == null) R.string.currency_rate_add else R.string.currency_rate_edit), ::back,
        backEnabled = !busy, actions = {
            FilledTonalButton({
                error = when {
                    source.code == target.code -> "pair"
                    original == null && rates.any { it.source == source && it.target == target } -> "duplicate"
                    runCatching { ExchangeRules.parseRate(rateText) }.isFailure -> "rate"
                    runCatching { LocalDate.parse(dateText) }.isFailure -> "date"
                    else -> null
                }
                attempt++
                if (error == null) { busy = true; scope.launch(Dispatchers.Main.immediate) {
                    val result = repository.saveExchangeRate(ManualExchangeRate(source, target, rateText, LocalDate.parse(dateText)))
                    busy = false
                    if (result is CatalogResult.Saved) { keyboard?.hide(); onBack() } else { error = "form"; attempt++ }
                } }
            }, Modifier.padding(end = 8.dp).testTag("rate_save"), enabled = !busy) { Text(stringResource(R.string.save)) }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.bringIntoViewRequester(positions.getValue("form"))) {
                if (error == "form") ErrorText(stringResource(R.string.bill_save_failed))
                if (error == "duplicate") ErrorText(stringResource(R.string.currency_pair_exists))
            }
            Column(Modifier.bringIntoViewRequester(positions.getValue("pair")), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PickerField(stringResource(R.string.currency_source), source.code, { picker = "source" }, Modifier.testTag("rate_source"), enabled = !busy && original == null)
                PickerField(stringResource(R.string.currency_target), target.code, { picker = "target" }, Modifier.testTag("rate_target"), enabled = !busy && original == null)
                if (error == "pair") ErrorText(stringResource(R.string.currency_pair_invalid))
            }
            OutlinedTextField(rateText, { rateText = it; dirty = true; error = null },
                Modifier.fillMaxWidth().focusRequester(focus.getValue("rate")).bringIntoViewRequester(positions.getValue("rate")).testTag("rate_value"),
                label = { Text(stringResource(R.string.currency_rate, source.code, target.code)) }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = error == "rate",
                supportingText = if (error == "rate") ({ ErrorText(stringResource(R.string.currency_rate_invalid)) }) else null)
            OutlinedTextField(dateText, { dateText = it; dirty = true; error = null },
                Modifier.fillMaxWidth().focusRequester(focus.getValue("date")).bringIntoViewRequester(positions.getValue("date")).testTag("rate_date"),
                label = { Text(stringResource(R.string.currency_rate_date)) }, singleLine = true, enabled = !busy,
                isError = error == "date", supportingText = if (error == "date") ({ ErrorText(stringResource(R.string.currency_date_invalid)) }) else null)
        }
    }
    picker?.let { field ->
        SelectionSheet(stringResource(R.string.currency), currencies.distinctBy { it.code }.map { SelectionOption(it.code, it.code) },
            if (field == "source") source.code else target.code, onSelect = { code ->
                val currency = currencies.first { it.code == code }
                if (field == "source") { sourceCode = code; sourceScale = currency.scale } else { targetCode = code; targetScale = currency.scale }
                picker = null; dirty = true; error = null
            }, onDismiss = { picker = null })
    }
    if (askLeave) AlertDialog(onDismissRequest = { askLeave = false }, title = { Text(stringResource(R.string.leave_draft_title)) },
        confirmButton = { TextButton(onBack) { Text(stringResource(R.string.discard_changes)) } },
        dismissButton = { TextButton({ askLeave = false }) { Text(stringResource(R.string.keep_editing)) } })
}
