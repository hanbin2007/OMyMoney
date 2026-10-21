package app.omymoney.feature.assets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.omymoney.android.R
import app.omymoney.core.model.*
import app.omymoney.data.CatalogResult
import app.omymoney.feature.catalog.CatalogViewModel
import app.omymoney.feature.catalog.catalogErrorMessage
import app.omymoney.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun CreditSettingsScreen(account: AssetAccount, catalog: CatalogViewModel, onBack: () -> Unit) {
    val original = account.creditTerms
    var enabled by rememberSaveable { mutableStateOf(original != null) }
    var statement by rememberSaveable { mutableStateOf(original?.statementDay?.toString() ?: "1") }
    var mode by rememberSaveable { mutableStateOf(original?.dueMode ?: CreditDueMode.DAY_OF_MONTH) }
    var due by rememberSaveable { mutableStateOf(original?.dueValue?.toString() ?: "5") }
    var limit by rememberSaveable { mutableStateOf(original?.creditLimit?.decimalText().orEmpty()) }
    var choosingMode by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var invalidField by rememberSaveable { mutableStateOf<String?>(null) }
    var validationAttempt by remember { mutableIntStateOf(0) }
    val fields = remember { listOf("statement", "due", "limit").associateWith { FocusRequester() } }
    val positions = remember { listOf("statement", "due", "limit", "form").associateWith { BringIntoViewRequester() } }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(validationAttempt) {
        val target = invalidField ?: return@LaunchedEffect
        fields[target]?.requestFocus() ?: run { focus.clearFocus(); keyboard?.hide() }
        withFrameNanos { }
        positions.getValue(target).bringIntoView()
    }
    fun fieldModifier(field: String) = Modifier.fillMaxWidth().testTag("credit_$field")
        .bringIntoViewRequester(positions.getValue(field)).focusRequester(fields.getValue(field))
    fun clearError() { error = null; invalidField = null }
    @Composable fun fieldError(field: String): (@Composable () -> Unit)? = if (invalidField != field) null else {
        { ErrorText(stringResource(when (field) {
            "statement" -> R.string.credit_invalid_statement
            "due" -> if (mode == CreditDueMode.DAY_OF_MONTH) R.string.credit_invalid_due_day else R.string.credit_invalid_due_days
            else -> R.string.credit_invalid_limit
        }), Modifier.testTag("credit_${field}_error")) }
    }
    val dirty = enabled != (original != null) || statement != (original?.statementDay?.toString() ?: "1") ||
        mode != (original?.dueMode ?: CreditDueMode.DAY_OF_MONTH) || due != (original?.dueValue?.toString() ?: "5") ||
        limit != original?.creditLimit?.decimalText().orEmpty()
    fun back() { if (!saving) { focus.clearFocus(); if (dirty) discard = true else onBack() } }
    BackHandler { back() }
    ScreenScaffold(stringResource(R.string.credit_settings), ::back, backEnabled = !saving, modifier = Modifier.imePadding(), actions = {
        TextButton({
            if (!saving) {
                val parsedLimit = limit.takeIf(String::isNotBlank)?.let { runCatching { Money.parse(it, account.currency) }.getOrNull() }
                invalidField = if (!enabled) null else when {
                    statement.toIntOrNull() !in 1..31 -> "statement"
                    due.toIntOrNull() !in 1..(if (mode == CreditDueMode.DAY_OF_MONTH) 31 else 90) -> "due"
                    limit.isNotBlank() && (parsedLimit == null || parsedLimit.minor < 0) -> "limit"
                    else -> null
                }
                if (invalidField != null) validationAttempt++
                else {
                    val terms = if (enabled) CreditTerms(statement.toInt(), mode, due.toInt(), parsedLimit) else null
                    saving = true
                    scope.launch(Dispatchers.Main.immediate) {
                        try {
                            val result = catalog.saveAccount(account.copy(creditTerms = terms))
                            if (result is CatalogResult.Saved) onBack() else error = catalogErrorMessage(context, result)
                        } catch (cancel: CancellationException) { throw cancel
                        } catch (_: Exception) { error = context.getString(R.string.error_save)
                        } finally {
                            saving = false
                            if (error != null) { invalidField = "form"; validationAttempt++ }
                        }
                    }
                }
            }
        }, Modifier.testTag("credit_save"), enabled = !saving) { Text(stringResource(R.string.save)) }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(account.name, style = MaterialTheme.typography.titleMedium)
            Column(Modifier.bringIntoViewRequester(positions.getValue("form"))) { error?.let { ErrorText(it) } }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("credit_enabled")
                .toggleable(enabled, enabled = !saving, role = Role.Switch, onValueChange = { enabled = it; clearError() }),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.credit_configured), Modifier.weight(1f))
                Switch(enabled, null, enabled = !saving)
            }
            if (enabled) {
                OutlinedTextField(statement, { statement = it; clearError() }, fieldModifier("statement"), enabled = !saving,
                    label = { Text(stringResource(R.string.credit_statement_day)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = invalidField == "statement", supportingText = fieldError("statement"))
                PickerField(stringResource(R.string.credit_due_mode), stringResource(if (mode == CreditDueMode.DAY_OF_MONTH) R.string.credit_month_day else R.string.credit_days_after),
                    { choosingMode = true }, enabled = !saving)
                OutlinedTextField(due, { due = it; clearError() }, fieldModifier("due"), enabled = !saving,
                    label = { Text(stringResource(if (mode == CreditDueMode.DAY_OF_MONTH) R.string.credit_due_day else R.string.credit_due_days)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = invalidField == "due", supportingText = fieldError("due"))
                OutlinedTextField(limit, { limit = it; clearError() }, fieldModifier("limit"), enabled = !saving,
                    label = { Text(stringResource(R.string.credit_limit)) }, prefix = { Text(account.currency.code) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = invalidField == "limit", supportingText = fieldError("limit"))
            }
            Text(stringResource(R.string.credit_local_only), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (choosingMode) SelectionSheet(stringResource(R.string.credit_due_mode), CreditDueMode.entries.map {
        SelectionOption(it.name, stringResource(if (it == CreditDueMode.DAY_OF_MONTH) R.string.credit_month_day else R.string.credit_days_after))
    }, mode.name, { mode = CreditDueMode.valueOf(it); clearError(); choosingMode = false }, { choosingMode = false })
    if (discard) DiscardDialog({ discard = false; onBack() }, { discard = false })
}
