package app.omymoney.feature.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.R
import app.omymoney.core.model.*
import app.omymoney.data.CatalogResult
import app.omymoney.ui.components.*
import app.omymoney.ui.theme.DesignTokens
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

enum class CatalogEditorKind { BOOK, ACCOUNT, CATEGORY }
private enum class Picker { CURRENCY, ZONE, ACCOUNT_KIND, PARENT }

@Composable
fun CatalogEditorScreen(
    viewModel: CatalogViewModel,
    state: CatalogState,
    kind: CatalogEditorKind,
    itemId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    bookId: String? = null,
    initialCategoryKind: BillKind = BillKind.EXPENSE,
    initialParentId: String? = null,
) {
    val flow = remember(viewModel, bookId) { viewModel.observeCategories(bookId.orEmpty(), includeArchived = true) }
    val categories by flow.collectAsStateWithLifecycle(initialValue = null)
    if (!state.initialized || (kind == CatalogEditorKind.CATEGORY && categories == null)) {
        LoadingState()
        return
    }
    val book = state.books.firstOrNull { it.id == itemId }
    val account = state.accounts.firstOrNull { it.id == itemId }
    val category = categories?.firstOrNull { it.id == itemId }
    val exists = when (kind) { CatalogEditorKind.BOOK -> book != null; CatalogEditorKind.ACCOUNT -> account != null; CatalogEditorKind.CATEGORY -> category != null }
    if (itemId != null && !exists) {
        ScreenScaffold(stringResource(R.string.error_not_found), onBack) { padding -> EmptyState(stringResource(R.string.error_not_found), Modifier.padding(padding)) }
        return
    }
    CatalogForm(viewModel, state, kind, itemId, book, account, category, categories.orEmpty(), bookId,
        initialCategoryKind, initialParentId, onBack, onSaved)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogForm(
    viewModel: CatalogViewModel,
    state: CatalogState,
    kind: CatalogEditorKind,
    itemId: String?,
    originalBook: Book?,
    originalAccount: AssetAccount?,
    originalCategory: Category?,
    categories: List<Category>,
    bookId: String?,
    initialCategoryKind: BillKind,
    initialParentId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val initialCurrency = originalAccount?.currency ?: originalBook?.currency ?: state.currentBook?.currency ?: CurrencySpec.CNY
    val originalName = originalBook?.name ?: originalAccount?.name ?: originalCategory?.name.orEmpty()
    val initialOpening = originalAccount?.openingBalance?.decimalText() ?: Money(0, initialCurrency).decimalText()
    val originalZone = originalBook?.zoneId?.id ?: ZoneId.systemDefault().id
    val originalAliases = originalAccount?.aliases?.joinToString("\n").orEmpty()
    val savedId = rememberSaveable { itemId ?: UUID.randomUUID().toString() }
    var name by rememberSaveable { mutableStateOf(originalName) }
    var currencyCode by rememberSaveable { mutableStateOf(initialCurrency.code) }
    var currencyScale by rememberSaveable { mutableIntStateOf(initialCurrency.scale) }
    val currency = CurrencySpec(currencyCode, currencyScale)
    var zone by rememberSaveable { mutableStateOf(originalZone) }
    var opening by rememberSaveable { mutableStateOf(initialOpening) }
    var accountKind by rememberSaveable { mutableStateOf(originalAccount?.kind ?: AccountKind.CASH) }
    var lastFour by rememberSaveable { mutableStateOf(originalAccount?.cardLastFour.orEmpty()) }
    var aliases by rememberSaveable { mutableStateOf(originalAliases) }
    var group by rememberSaveable { mutableStateOf(originalAccount?.group.orEmpty()) }
    var categoryKind by rememberSaveable { mutableStateOf(originalCategory?.kind ?: initialCategoryKind) }
    var parent by rememberSaveable { mutableStateOf(originalCategory?.parentId ?: initialParentId) }
    var picker by rememberSaveable { mutableStateOf<Picker?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var errorCode by rememberSaveable { mutableStateOf<String?>(null) }
    var errorField by rememberSaveable { mutableStateOf<String?>(null) }
    var validationAttempt by rememberSaveable { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    val scope = rememberCoroutineScope()
    val fieldFocus = remember { listOf("name", "openingBalance", "cardLastFour", "aliases", "group").associateWith { FocusRequester() } }
    val fieldPosition = remember { (fieldFocus.keys + listOf("currency", "kind", "parentId", "form")).associateWith { BringIntoViewRequester() } }
    val dirty = name != originalName || currency != initialCurrency || zone != originalZone || opening != initialOpening ||
        accountKind != (originalAccount?.kind ?: AccountKind.CASH) || lastFour != originalAccount?.cardLastFour.orEmpty() ||
        aliases != originalAliases || group != originalAccount?.group.orEmpty() || categoryKind != (originalCategory?.kind ?: initialCategoryKind) || parent != (originalCategory?.parentId ?: initialParentId)

    fun clearError() { errorCode = null; errorField = null }
    fun navigateBack() {
        if (!saving) {
            keyboard?.hide()
            focus.clearFocus()
            if (dirty) discard = true else onBack()
        }
    }
    BackHandler {
        if (!saving) {
            if (imeVisible) { keyboard?.hide(); focus.clearFocus() } else navigateBack()
        }
    }
    LaunchedEffect(savedId) { if (itemId == null) fieldFocus.getValue("name").requestFocus() }
    LaunchedEffect(errorCode, errorField, validationAttempt) {
        if (errorCode != null) {
            val target = fieldFocus[errorField]
            if (target != null) target.requestFocus() else { focus.clearFocus(); keyboard?.hide() }
            withFrameNanos { }
            (fieldPosition[errorField] ?: fieldPosition.getValue("form")).bringIntoView()
        }
    }
    fun fieldError(field: String): String? = errorCode?.takeIf { errorField == field }?.let { catalogErrorMessage(context, it) }

    fun save() {
        if (saving) return
        clearError()
        val openingMoney = if (kind == CatalogEditorKind.ACCOUNT) {
            try { Money.parse(opening, currency) } catch (_: IllegalArgumentException) {
                errorCode = "invalid_amount"; errorField = "openingBalance"; validationAttempt++; return
            }
        } else Money(0, currency)
        saving = true
        scope.launch(Dispatchers.Main.immediate) {
            val result: CatalogResult<*> = when (kind) {
                CatalogEditorKind.BOOK -> viewModel.saveBook((originalBook ?: Book(savedId, name)).copy(name = name, currency = currency, zoneId = ZoneId.of(zone)))
                CatalogEditorKind.ACCOUNT -> viewModel.saveAccount((originalAccount ?: AssetAccount(savedId, name, currency)).copy(
                    name = name, currency = currency, openingBalance = openingMoney,
                    balance = Money(originalAccount?.balance?.minor ?: openingMoney.minor, currency), kind = accountKind,
                    cardLastFour = lastFour.trim().takeIf(String::isNotEmpty), aliases = aliases.split('\n', ',', '，').map(String::trim).filter(String::isNotEmpty),
                    group = group, creditTerms = originalAccount?.creditTerms.takeIf { accountKind == AccountKind.CREDIT },
                ))
                CatalogEditorKind.CATEGORY -> viewModel.saveCategory((originalCategory ?: Category(savedId, requireNotNull(bookId), name, categoryKind)).copy(
                    name = name, kind = categoryKind, parentId = parent,
                ))
            }
            saving = false
            if (result is CatalogResult.Saved) {
                keyboard?.hide(); focus.clearFocus(); onSaved()
            } else {
                val error = (result as? CatalogResult.Invalid)?.fieldErrors?.firstOrNull()
                errorCode = error?.code ?: "storage"
                errorField = error?.field
                validationAttempt++
            }
        }
    }

    val title = stringResource(when (kind) {
        CatalogEditorKind.BOOK -> if (itemId == null) R.string.new_book else R.string.edit_book
        CatalogEditorKind.ACCOUNT -> if (itemId == null) R.string.new_account else R.string.edit_account
        CatalogEditorKind.CATEGORY -> if (itemId == null) R.string.new_category else R.string.edit_category
    })
    ScreenScaffold(title, ::navigateBack, modifier = Modifier.imePadding(), backEnabled = !saving,
        actions = {
            TextButton(::save, enabled = !saving, modifier = Modifier.widthIn(min = 64.dp).heightIn(min = 48.dp).testTag("catalog_save")) {
                if (saving) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.save))
            }
        }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = DesignTokens.ReadingWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (errorCode != null && errorField !in fieldPosition.keys) {
                    ErrorText(catalogErrorMessage(context, errorCode!!), Modifier.bringIntoViewRequester(fieldPosition.getValue("form")))
                }
                OutlinedTextField(name, { name = it; clearError() }, label = { Text(stringResource(when (kind) {
                    CatalogEditorKind.BOOK -> R.string.book_name
                    CatalogEditorKind.ACCOUNT -> R.string.account_name
                    CatalogEditorKind.CATEGORY -> R.string.category_name
                })) }, singleLine = true, isError = errorField == "name",
                    supportingText = if (errorField == "name" && errorCode != null) ({ ErrorText(catalogErrorMessage(context, errorCode!!)) }) else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().bringIntoViewRequester(fieldPosition.getValue("name"))
                        .focusRequester(fieldFocus.getValue("name")).testTag("catalog_name"))
                if (kind == CatalogEditorKind.ACCOUNT) {
                    OutlinedTextField(opening, { opening = it; clearError() }, label = { Text(stringResource(R.string.opening_balance)) },
                        prefix = { Text(currencyCode) }, singleLine = true, isError = errorField == "openingBalance",
                        supportingText = if (errorField == "openingBalance" && errorCode != null) ({ ErrorText(catalogErrorMessage(context, errorCode!!)) }) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(fieldPosition.getValue("openingBalance"))
                            .focusRequester(fieldFocus.getValue("openingBalance")).testTag("account_opening"))
                    PickerField(stringResource(R.string.account_kind), stringResource(accountKindLabel(accountKind)), { picker = Picker.ACCOUNT_KIND },
                        Modifier.bringIntoViewRequester(fieldPosition.getValue("kind")), error = fieldError("kind"))
                }
                if (kind != CatalogEditorKind.CATEGORY) {
                    PickerField(stringResource(R.string.currency), currencyCode, { picker = Picker.CURRENCY },
                        Modifier.bringIntoViewRequester(fieldPosition.getValue("currency")), error = fieldError("currency"))
                }
                if (kind == CatalogEditorKind.BOOK) PickerField(stringResource(R.string.book_timezone), zone, { picker = Picker.ZONE })
                if (kind == CatalogEditorKind.ACCOUNT) {
                    OutlinedTextField(group, { group = it; clearError() }, label = { Text(stringResource(R.string.asset_group)) },
                        singleLine = true, isError = errorField == "group", supportingText = fieldError("group")?.let { message -> { ErrorText(message) } },
                        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(fieldPosition.getValue("group"))
                            .focusRequester(fieldFocus.getValue("group")).testTag("account_group"))
                    OutlinedTextField(lastFour, { lastFour = it; clearError() }, label = { Text(stringResource(R.string.last_four)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        isError = errorField == "cardLastFour", supportingText = fieldError("cardLastFour")?.let { message -> { ErrorText(message) } },
                        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(fieldPosition.getValue("cardLastFour"))
                            .focusRequester(fieldFocus.getValue("cardLastFour")))
                    OutlinedTextField(aliases, { aliases = it; clearError() }, label = { Text(stringResource(R.string.account_aliases)) },
                        minLines = 2, maxLines = 5, isError = errorField == "aliases",
                        supportingText = fieldError("aliases")?.let { message -> { ErrorText(message) } },
                        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(fieldPosition.getValue("aliases"))
                            .focusRequester(fieldFocus.getValue("aliases")).testTag("account_aliases"))
                }
                if (kind == CatalogEditorKind.CATEGORY) {
                    Column(Modifier.bringIntoViewRequester(fieldPosition.getValue("kind"))) {
                        KindSelector(categoryKind, { next ->
                            if (next != categoryKind) { categoryKind = next; parent = null; clearError() }
                        })
                        fieldError("kind")?.let { ErrorText(it) }
                    }
                    PickerField(stringResource(R.string.parent_category), categories.firstOrNull { it.id == parent }?.name ?: stringResource(R.string.no_parent),
                        { picker = Picker.PARENT }, Modifier.bringIntoViewRequester(fieldPosition.getValue("parentId")), error = fieldError("parentId"))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (discard) DiscardDialog(onDiscard = { discard = false; onBack() }, onKeep = { discard = false })
    val currencies = remember(initialCurrency) { (listOf(CurrencySpec.CNY, CurrencySpec("HKD", 2), CurrencySpec("USD", 2), CurrencySpec("EUR", 2),
        CurrencySpec("GBP", 2), CurrencySpec("JPY", 0), CurrencySpec("KWD", 3)) + initialCurrency).distinct() }
    when (picker) {
        Picker.CURRENCY -> SelectionSheet(stringResource(R.string.currency), currencies.map { SelectionOption(it.code, it.code) }, currencyCode,
            onSelect = { code ->
                val next = currencies.first { it.code == code }
                currencyCode = next.code; currencyScale = next.scale
                opening = runCatching { BigDecimal(opening).setScale(next.scale, RoundingMode.UNNECESSARY).toPlainString() }.getOrDefault(opening)
                clearError(); picker = null
            }, onDismiss = { picker = null })
        Picker.ZONE -> SelectionSheet(stringResource(R.string.book_timezone), remember { ZoneId.getAvailableZoneIds().sorted().map { SelectionOption(it, it) } }, zone,
            onSelect = { zone = it; clearError(); picker = null }, onDismiss = { picker = null })
        Picker.ACCOUNT_KIND -> SelectionSheet(stringResource(R.string.account_kind), AccountKind.entries.map { SelectionOption(it.name, stringResource(accountKindLabel(it))) }, accountKind.name,
            onSelect = { accountKind = AccountKind.valueOf(it); clearError(); picker = null }, onDismiss = { picker = null })
        Picker.PARENT -> {
            val eligible = if (categories.any { it.parentId == savedId }) emptyList() else categories.filter { it.parentId == null && !it.archived && it.kind == categoryKind && it.id != savedId }
            SelectionSheet(stringResource(R.string.parent_category), listOf(SelectionOption("", stringResource(R.string.no_parent))) + eligible.map { SelectionOption(it.id, it.name) }, parent.orEmpty(),
                onSelect = { parent = it.takeIf(String::isNotEmpty); clearError(); picker = null }, onDismiss = { picker = null })
        }
        null -> Unit
    }
}

private fun accountKindLabel(kind: AccountKind): Int = when (kind) {
    AccountKind.CASH -> R.string.account_cash
    AccountKind.DEBIT -> R.string.account_debit
    AccountKind.CREDIT -> R.string.account_credit
    AccountKind.WALLET -> R.string.account_wallet
    AccountKind.INVESTMENT -> R.string.account_investment
    AccountKind.OTHER -> R.string.account_other
    AccountKind.LIABILITY -> R.string.account_liability
    AccountKind.RECEIVABLE -> R.string.account_receivable
}
