package app.omymoney.feature.budget

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.omymoney.android.R
import app.omymoney.core.budget.*
import app.omymoney.core.model.*
import app.omymoney.data.*
import app.omymoney.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal fun budgetError(context: Context, code: String?): String = context.getString(when (code) {
    "category_budget_exceeds_total" -> R.string.budget_exceeds_total
    "child_budget_exceeds_parent" -> R.string.budget_exceeds_parent
    "invalid_budget_limit", "invalid_amount", "negative_budget" -> R.string.budget_invalid_amount
    "archived_category", "invalid_budget_category" -> R.string.budget_invalid_category
    "no_previous_budget" -> R.string.budget_no_previous
    else -> R.string.error_save
})

@Composable
internal fun BudgetEditorScreen(repository: BudgetRepository, book: Book, period: BudgetPeriod, original: BudgetPlan?,
                                categories: List<Category>, onBack: () -> Unit, onSaved: () -> Unit) {
    val initialLimits = remember(original) { original?.categories.orEmpty().associate { it.categoryId to it.limit.decimalText() } }
    var total by rememberSaveable { mutableStateOf(original?.total?.decimalText().orEmpty()) }
    var limits by rememberSaveable(stateSaver = mapSaver<Map<String, String>>(save = { it }, restore = { values -> values.mapValues { it.value as String } })) {
        mutableStateOf(initialLimits)
    }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var errorField by rememberSaveable { mutableStateOf<String?>(null) }
    var validationAttempt by remember { mutableIntStateOf(0) }
    val fieldIds = listOf("total") + limits.keys
    val focuses = remember(fieldIds) { fieldIds.associateWith { FocusRequester() } }
    val positions = remember(fieldIds) { (fieldIds + "form").associateWith { BringIntoViewRequester() } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dirty = total != original?.total?.decimalText().orEmpty() || limits != initialLimits
    fun clearError() { error = null; errorField = null }
    fun back() { if (!saving) { focus.clearFocus(); keyboard?.hide(); if (dirty) discard = true else onBack() } }
    BackHandler { back() }
    LaunchedEffect(validationAttempt) {
        val field = errorField ?: return@LaunchedEffect
        focuses[field]?.requestFocus() ?: run { focus.clearFocus(); keyboard?.hide() }
        withFrameNanos { }
        positions[field]?.bringIntoView()
    }
    @Composable fun supporting(field: String): (@Composable () -> Unit)? = if (errorField != field || error == null) null else {
        { ErrorText(requireNotNull(error), Modifier.testTag("budget_${field}_error")) }
    }
    fun fieldModifier(field: String) = Modifier.fillMaxWidth().testTag(if (field == "total") "budget_total" else "budget_limit_$field")
        .focusRequester(focuses.getValue(field)).bringIntoViewRequester(positions.getValue(field))
    ScreenScaffold(stringResource(R.string.budget_edit), ::back, modifier = Modifier.imePadding(), backEnabled = !saving, actions = {
        TextButton({
            if (!saving) {
                clearError()
                val parsedTotal = runCatching { Money.parse(total, book.currency) }.getOrNull()
                val parsedLimits = limits.mapValues { (_, raw) -> runCatching { Money.parse(raw, book.currency) }.getOrNull() }
                val invalidAmount = if (parsedTotal == null || parsedTotal.minor < 0) "total"
                    else parsedLimits.entries.firstOrNull { it.value == null || it.value!!.minor < 0 }?.key
                val plan = if (invalidAmount == null) BudgetPlan(book.id, period, requireNotNull(parsedTotal),
                    parsedLimits.map { BudgetLimit(it.key, requireNotNull(it.value)) }, original?.copiedFrom) else null
                val validation = plan?.let { BudgetCalculator.validate(it, categories).firstOrNull() }
                when {
                    invalidAmount != null -> { errorField = invalidAmount; error = budgetError(context, "invalid_amount"); validationAttempt++ }
                    validation != null -> { errorField = validation.field.takeIf { it in fieldIds } ?: "form"; error = budgetError(context, validation.code); validationAttempt++ }
                    else -> {
                        saving = true
                        scope.launch(Dispatchers.Main.immediate) {
                            try {
                                when (val result = repository.save(requireNotNull(plan))) {
                                    is CatalogResult.Saved -> { focus.clearFocus(); keyboard?.hide(); onSaved() }
                                    else -> {
                                        val issue = (result as? CatalogResult.Invalid)?.fieldErrors?.firstOrNull()
                                        errorField = issue?.field?.takeIf { it in fieldIds } ?: "form"
                                        error = budgetError(context, issue?.code); validationAttempt++
                                    }
                                }
                            } catch (cancel: CancellationException) { throw cancel
                            } catch (_: Exception) { errorField = "form"; error = context.getString(R.string.error_save); validationAttempt++
                            } finally { saving = false }
                        }
                    }
                }
            }
        }, Modifier.testTag("budget_save"), enabled = !saving) { Text(stringResource(R.string.save)) }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("${book.name} / ${period.key}", style = MaterialTheme.typography.titleMedium)
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.bringIntoViewRequester(positions.getValue("form"))) { if (errorField == "form") error?.let { ErrorText(it) } }
            OutlinedTextField(total, { total = it; clearError() }, fieldModifier("total"), enabled = !saving,
                label = { Text(stringResource(R.string.budget_total)) }, prefix = { Text(book.currency.code) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = errorField == "total", supportingText = supporting("total"))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.budget_categories), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                LedgerIconButton(Icons.Outlined.Add, stringResource(R.string.budget_add_category), { choosing = true }, Modifier.testTag("budget_category_add"), enabled = !saving)
            }
            limits.forEach { (id, raw) ->
                val category = categories.firstOrNull { it.id == id }
                val categoryName = category?.let { budgetCategoryName(it, categories) } ?: id
                val amountLabel = stringResource(R.string.budget_category_amount, categoryName)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(categoryName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        LedgerIconButton(Icons.Outlined.Close, stringResource(R.string.budget_remove_category, categoryName),
                            { limits = limits - id; clearError() }, Modifier.testTag("budget_remove_$id"), enabled = !saving)
                    }
                    if (category?.archived == true) Text(stringResource(R.string.archived), style = MaterialTheme.typography.bodySmall)
                    if (categories.any { it.parentId == id }) Text(stringResource(R.string.budget_includes_children), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(raw, { limits = limits + (id to it); clearError() },
                        fieldModifier(id).semantics { contentDescription = amountLabel }, enabled = !saving,
                        label = { Text(stringResource(R.string.budget_limit)) }, prefix = { Text(book.currency.code) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = errorField == id, supportingText = supporting(id))
                }
            }
        }
    }
    if (choosing) SelectionSheet(stringResource(R.string.budget_add_category),
        categories.filter { !it.archived && it.kind == BillKind.EXPENSE && it.id !in limits }.map { SelectionOption(it.id, budgetCategoryName(it, categories)) },
        null, { limits = limits + (it to ""); choosing = false; clearError() }, { choosing = false })
    if (discard) DiscardDialog({ discard = false; onBack() }, { discard = false })
}
