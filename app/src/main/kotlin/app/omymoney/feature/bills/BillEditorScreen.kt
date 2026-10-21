package app.omymoney.feature.bills

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import app.omymoney.ui.theme.ExpressiveMotion
import app.omymoney.ui.theme.LocalLedgerColors
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CurrencyExchange
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.R
import app.omymoney.core.model.*
import app.omymoney.core.ledger.PostingCalculator
import app.omymoney.core.ledger.LinkedBillRules
import app.omymoney.core.ledger.ExchangeRules
import app.omymoney.data.AttachmentStore
import app.omymoney.feature.catalog.CatalogState
import app.omymoney.feature.catalog.CatalogViewModel
import app.omymoney.ui.components.*
import java.time.*
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BillEditorScreen(
    editor: BillEditorViewModel,
    catalog: CatalogViewModel,
    catalogState: CatalogState,
    attachments: AttachmentStore,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    overlayMode: Boolean = false,
    onOpenApp: (() -> Unit)? = null,
    header: @Composable () -> Unit = {},
    saveEnabled: Boolean = true,
) {
    val state by editor.state.collectAsStateWithLifecycle()
    val imagePicker = if (overlayMode) null else rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(editor::importAttachment)
    }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var askLeave by rememberSaveable { mutableStateOf(false) }
    var datePicker by rememberSaveable { mutableStateOf(false) }
    var timeDate by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusTargets = remember { listOf("amount", "note", "tags", "fee", "exchangeRate", "exchangeDate", "exchangeAmount").associateWith { FocusRequester() } }
    val positions = remember { listOf("form", "amount", "kind", "categoryId", "fromAccountId", "toAccountId", "occurredAt", "note", "tags", "fee", "exchange", "exchangeRate", "exchangeDate", "exchangeAmount")
        .associateWith { BringIntoViewRequester() } }
    fun leave() {
        if (state.saving) return
        keyboard?.hide(); focus.clearFocus()
        if (overlayMode && picker != null) { picker = null; return }
        if (overlayMode && datePicker) { datePicker = false; return }
        if (editor.recognitionId != null) editor.keepAndClose()
        else if (state.dirty) askLeave = true else onBack()
    }
    BackHandler(enabled = picker == null && !datePicker && timeDate == null && !askLeave) { leave() }
    LaunchedEffect(state.saved, state.closed) {
        state.saved?.let { keyboard?.hide(); focus.clearFocus(); onSaved(it.billId) }
        if (state.closed) onBack()
    }
    LaunchedEffect(state.validationAttempt) {
        val field = state.errors.firstOrNull()?.field ?: return@LaunchedEffect
        val target = focusTargets[field]
        if (target != null) target.requestFocus() else { focus.clearFocus(); keyboard?.hide() }
        withFrameNanos { }
        (positions[field] ?: positions.getValue("form")).bringIntoView()
    }
    LaunchedEffect(state.record?.key) {
        if (editor.recognitionId == null && state.record?.dirty == false && state.revision == null) {
            withFrameNanos { }
            focusTargets.getValue("amount").requestFocus()
            keyboard?.show()
        }
    }
    fun chipModifier(field: String, tag: String) = Modifier.testTag(tag).bringIntoViewRequester(positions.getValue(field))
    fun fieldModifier(field: String, tag: String) = Modifier.fillMaxWidth().testTag(tag)
        .bringIntoViewRequester(positions.getValue(field))
        .then(focusTargets[field]?.let { Modifier.focusRequester(it) } ?: Modifier)

    val embeddedPicker = overlayMode && (picker != null || datePicker)
    val title = when {
        overlayMode && datePicker -> R.string.transaction_time
        overlayMode && picker == "categoryId" -> R.string.select_category
        overlayMode && picker == "currency" -> R.string.currency
        overlayMode && picker != null -> R.string.select_account
        editor.recognitionId != null -> R.string.review_title
        state.revision == null -> R.string.new_bill
        else -> R.string.edit_bill
    }
    ScreenScaffold(stringResource(title), ::leave,
        backEnabled = !state.saving, actions = {
            if (!embeddedPicker) Button(editor::submit, Modifier.padding(end = 8.dp).testTag("bill_save"),
                enabled = saveEnabled && state.record != null && !state.saving && !state.importingAttachment) {
                if (state.saving) CircularProgressIndicator(Modifier.size(20.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                else Text(stringResource(R.string.save))
            }
        }) { padding ->
        val record = state.record
        if (record == null) {
            if (state.loadFailed) Column(Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center) {
                ErrorText(stringResource(R.string.draft_load_failed))
                TextButton(editor::load) { Text(stringResource(R.string.retry)) }
            } else LoadingState(Modifier.padding(padding))
        } else {
            val draft = record.transaction
            val categories by remember(catalog, draft.bookId) { catalog.observeCategories(draft.bookId, includeArchived = true) }
                .collectAsStateWithLifecycle(emptyList())
            val accounts = catalogState.accounts
            val fixedFrom = draft.kind == BillKind.DEBT_COLLECT
            val fixedTo = draft.kind == BillKind.DEBT_REPAY
            val principalId = if (fixedFrom) draft.fromAccountId else draft.toAccountId.takeIf { fixedTo }
            val book = catalogState.books.firstOrNull { it.id == draft.bookId }
            @Composable fun error(field: String): String? = state.errors.firstOrNull { it.field == field }?.let { billErrorText(it) }
            @Composable fun catalogName(name: String, archived: Boolean) =
                if (archived) stringResource(R.string.archived_name, name) else name
            Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp).testTag("bill_editor_form"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    header()
                    Row(Modifier.fillMaxWidth().bringIntoViewRequester(positions.getValue("form")), verticalAlignment = Alignment.CenterVertically) {
                        Text(book?.name ?: draft.bookId, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!state.persistenceFailed && state.dirty) {
                            Text(stringResource(if (state.persistencePending) R.string.draft_saving else R.string.draft_saved),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("draft_status"))
                        }
                    }
                    error("form")?.let { ErrorText(it) }
                    error("bookId")?.let { ErrorText(it) }
                    if (state.persistenceFailed) {
                        ErrorText(stringResource(R.string.draft_persist_failed))
                        TextButton(editor::retryPersistence) { Text(stringResource(R.string.retry)) }
                    }
                    if (draft.kind == null || draft.kind in BillEditorViewModel.BASIC_KINDS + BillKind.REIMBURSEMENT) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().bringIntoViewRequester(positions.getValue("kind"))) {
                        BillEditorViewModel.BASIC_KINDS.forEachIndexed { index, kind ->
                            val selected = draft.kind == kind || (draft.kind == BillKind.REIMBURSEMENT && kind == BillKind.EXPENSE)
                            SegmentedButton(selected = selected, onClick = { if (!selected) editor.onAction(EditorAction.Kind(kind)) },
                                enabled = !state.saving, shape = SegmentedButtonDefaults.itemShape(index, 3),
                                modifier = Modifier.testTag("bill_kind_${kind.name}")) { Text(billKindLabel(kind)) }
                        }
                    } else Text(billKindLabel(requireNotNull(draft.kind)), style = MaterialTheme.typography.titleMedium)
                    error("kind")?.let { ErrorText(it) }
                    error("linkedBillId")?.let { ErrorText(it) }
                    // The amount is the one thing every bill needs, so it gets a large, kind-tinted stage of its own.
                    val ledgerColors = LocalLedgerColors.current
                    val (stage, onStage) = when (draft.kind) {
                        in PostingCalculator.incomingKinds -> ledgerColors.incomeContainer to ledgerColors.onIncomeContainer
                        in PostingCalculator.outgoingKinds -> ledgerColors.expenseContainer to ledgerColors.onExpenseContainer
                        in PostingCalculator.transferKinds -> ledgerColors.transferContainer to ledgerColors.onTransferContainer
                        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                    }
                    val stageColor by animateColorAsState(stage, ExpressiveMotion.slowEffects(), label = "amount-stage")
                    val currencySelectable = draft.linkedBillId == null && draft.kind in PostingCalculator.outgoingKinds + PostingCalculator.incomingKinds
                    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = stageColor, contentColor = onStage) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (currencySelectable) Surface(onClick = { picker = "currency" }, enabled = !state.saving,
                                    modifier = Modifier.padding(start = 4.dp).testTag("bill_currency"), shape = CircleShape, color = onStage.copy(alpha = 0.1f)) {
                                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(record.currency.code, style = MaterialTheme.typography.titleMedium)
                                        Icon(Icons.Outlined.ArrowDropDown, contentDescription = stringResource(R.string.currency_nominal))
                                    }
                                } else Text(record.currency.code, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                                TextField(record.amountText, { editor.onAction(EditorAction.Amount(it)) },
                                    modifier = Modifier.weight(1f).testTag("bill_amount").bringIntoViewRequester(positions.getValue("amount"))
                                        .focusRequester(focusTargets.getValue("amount")), enabled = !state.saving,
                                    placeholder = { Text("0.00", style = MaterialTheme.typography.displaySmall, color = onStage.copy(alpha = 0.35f)) },
                                    label = { Text(stringResource(R.string.amount)) },
                                    textStyle = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"), singleLine = true,
                                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                        disabledContainerColor = Color.Transparent, errorContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent,
                                        focusedTextColor = onStage, unfocusedTextColor = onStage, focusedLabelColor = onStage.copy(alpha = 0.7f),
                                        unfocusedLabelColor = onStage.copy(alpha = 0.7f), cursorColor = onStage),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                                    isError = error("amount") != null)
                            }
                            error("amount")?.let { ErrorText(it, Modifier.padding(horizontal = 12.dp)) }
                        }
                    }
                    // One tap for the common categories; the full searchable list stays a chip away.
                    if (PostingCalculator.categoryKind(draft.kind) != null) {
                        val quick = remember(categories, draft.kind, draft.categoryId) {
                            val usable = categories.filter { it.kind == PostingCalculator.categoryKind(draft.kind) && !it.archived }
                            val chosen = usable.firstOrNull { it.id == draft.categoryId }
                            (listOfNotNull(chosen) + usable.filter { it.parentId == null && it.id != chosen?.id }).take(10)
                        }
                        if (quick.isNotEmpty()) LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(quick, key = { it.id }) { category ->
                                val selected = draft.categoryId == category.id
                                FilterChip(selected, { editor.onAction(EditorAction.Category(if (selected) null else category.id)) },
                                    label = { Text(category.name) }, enabled = !state.saving, modifier = Modifier.animateItem(),
                                    leadingIcon = { MonogramBadge(category.name, size = 20.dp) }, shape = CircleShape)
                            }
                            item(key = "more") {
                                AssistChip({ picker = "categoryId" }, label = { Text(stringResource(R.string.editor_more_categories)) }, enabled = !state.saving,
                                    leadingIcon = { Icon(Icons.Outlined.GridView, null, Modifier.size(18.dp)) }, shape = CircleShape)
                            }
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (draft.kind in PostingCalculator.outgoingKinds || draft.kind in PostingCalculator.transferKinds || (draft.kind == BillKind.ADJUSTMENT && draft.fromAccountId != null)) ParamChip(
                            Icons.Outlined.AccountBalanceWallet,
                            stringResource(if (draft.kind in PostingCalculator.transferKinds) R.string.from_account else R.string.paying_account),
                            accounts.firstOrNull { it.id == draft.fromAccountId }?.let { catalogName(it.name, it.archived) }
                                ?: if (PostingCalculator.allowsUnassignedAccounts(draft)) unassignedFundingLabel(draft, true) else stringResource(R.string.select_account),
                            { picker = "fromAccountId" }, chipModifier("fromAccountId", "bill_from_account"),
                            isError = error("fromAccountId") != null, enabled = !state.saving && !fixedFrom)
                        if (draft.kind in PostingCalculator.incomingKinds || draft.kind in PostingCalculator.transferKinds || (draft.kind == BillKind.ADJUSTMENT && draft.toAccountId != null)) ParamChip(
                            Icons.Outlined.Savings,
                            stringResource(if (draft.kind in PostingCalculator.transferKinds) R.string.to_account else R.string.receiving_account),
                            accounts.firstOrNull { it.id == draft.toAccountId }?.let { catalogName(it.name, it.archived) }
                                ?: if (PostingCalculator.allowsUnassignedAccounts(draft)) unassignedFundingLabel(draft, false) else stringResource(R.string.select_account),
                            { picker = "toAccountId" }, chipModifier("toAccountId", "bill_to_account"),
                            isError = error("toAccountId") != null, enabled = !state.saving && !fixedTo)
                        if (PostingCalculator.categoryKind(draft.kind) != null) ParamChip(Icons.Outlined.Category, stringResource(R.string.category),
                            categories.firstOrNull { it.id == draft.categoryId }?.let { catalogName(it.name, it.archived) } ?: stringResource(R.string.uncategorized),
                            { picker = "categoryId" }, chipModifier("categoryId", "bill_category"),
                            isError = error("categoryId") != null, enabled = !state.saving)
                        ParamChip(Icons.Outlined.Schedule, stringResource(R.string.transaction_time),
                            if (draft.timeSource == TimeSource.UNKNOWN) stringResource(R.string.time_unconfirmed)
                            else draft.occurredAt?.atZone(draft.zoneId)?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"))
                                ?: stringResource(R.string.time_unconfirmed),
                            { datePicker = true }, chipModifier("occurredAt", "bill_time"),
                            isError = error("occurredAt") != null, enabled = !state.saving)
                        if (draft.kind in listOf(BillKind.EXPENSE, BillKind.REIMBURSEMENT)) {
                            val pending = draft.kind == BillKind.REIMBURSEMENT
                            FilterChip(pending, { editor.onAction(EditorAction.Kind(if (pending) BillKind.EXPENSE else BillKind.REIMBURSEMENT)) },
                                label = { Text(stringResource(R.string.pending_reimbursement)) }, enabled = !state.saving,
                                leadingIcon = { Icon(if (pending) Icons.Outlined.Check else Icons.AutoMirrored.Outlined.ReceiptLong, null, Modifier.size(18.dp)) },
                                shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp).semantics { role = Role.Checkbox; toggleableState = ToggleableState(pending) }
                                    .testTag("bill_pending_reimbursement"))
                        }
                    }
                    listOf("fromAccountId", "toAccountId", "categoryId", "occurredAt").forEach { field -> error(field)?.let { ErrorText(it) } }
                    if (draft.timeSource == TimeSource.CAPTURED) Text(stringResource(R.string.time_captured),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (fixedFrom || fixedTo) {
                        Text(stringResource(R.string.debt_principal_fixed), style = MaterialTheme.typography.bodySmall)
                        if (accounts.firstOrNull { it.id == principalId }?.archived == true && principalId !in state.originalAccountIds) {
                            ErrorText(stringResource(R.string.debt_restore_principal_account))
                        }
                    }
                    if (draft.source in setOf(BillSource.ACCESSIBILITY, BillSource.NOTIFICATION)) {
                        draft.sourcePackage?.let { Text(stringResource(R.string.payment_channel) + " · " + app.omymoney.feature.automation.applicationLabel(it),
                            style = MaterialTheme.typography.bodyMedium) }
                        OutlinedTextField(draft.payment?.counterparty.orEmpty(), { editor.onAction(EditorAction.Counterparty(it)) },
                            Modifier.fillMaxWidth().testTag("bill_counterparty"), label = { Text(stringResource(R.string.payment_counterparty)) },
                            enabled = !state.saving, maxLines = 3, shape = MaterialTheme.shapes.large)
                    }
                    state.foreignCurrency?.let { currency ->
                        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(12.dp).bringIntoViewRequester(positions.getValue("exchange")), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            error("exchange")?.let { ErrorText(it) }
                            OutlinedTextField(record.exchangeRateText, { editor.onAction(EditorAction.ExchangeRate(it)) },
                                fieldModifier("exchangeRate", "bill_exchange_rate"), enabled = !state.saving, singleLine = true,
                                label = { Text(stringResource(R.string.currency_rate, record.exchangeRateSource?.code ?: record.currency.code, record.exchangeRateTarget?.code ?: currency.code)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = error("exchangeRate") != null,
                                supportingText = error("exchangeRate")?.let { { ErrorText(it) } })
                            OutlinedTextField(record.exchangeDateText, { editor.onAction(EditorAction.ExchangeDate(it)) },
                                fieldModifier("exchangeDate", "bill_exchange_date"), enabled = !state.saving, singleLine = true,
                                label = { Text(stringResource(R.string.currency_rate_date)) }, isError = error("exchangeDate") != null,
                                supportingText = error("exchangeDate")?.let { { ErrorText(it) } })
                            TextButton(editor::applyCurrentRate, Modifier.testTag("bill_use_rate"), enabled = !state.saving) {
                                Icon(Icons.Outlined.CurrencyExchange, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.currency_use_manual_rate))
                            }
                            OutlinedTextField(record.exchangeAmountText, { editor.onAction(EditorAction.ExchangeAmount(it)) },
                                fieldModifier("exchangeAmount", "bill_exchange_amount"), enabled = !state.saving, singleLine = true,
                                prefix = { Text(record.exchangeAmountCurrency?.code ?: currency.code) }, label = { Text(stringResource(R.string.currency_actual)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = error("exchangeAmount") != null,
                                supportingText = error("exchangeAmount")?.let { { ErrorText(it) } })
                            TextButton(editor::recalculateExchange, Modifier.testTag("bill_recalculate"), enabled = !state.saving) {
                                Icon(Icons.Outlined.Calculate, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.currency_recalculate))
                            }
                            val difference = runCatching {
                                require(record.exchangeRateSource == record.currency && record.exchangeRateTarget == currency && record.exchangeAmountCurrency == currency)
                                ExchangeRules.difference(Money.parse(record.amountText, record.currency),
                                ExchangeSnapshot(requireNotNull(ExchangeRules.foreignAccount(draft)), Money.parse(record.exchangeAmountText, currency),
                                    record.exchangeRateText, LocalDate.parse(record.exchangeDateText))) }.getOrNull()
                            difference?.let { Text(stringResource(R.string.currency_difference, "${it.currency.code} ${it.decimalText()}"),
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("bill_exchange_difference")) }
                        }
                        }
                    }
                    TextField(draft.note, { editor.onAction(EditorAction.Note(it)) }, fieldModifier("note", "bill_note"),
                        placeholder = { Text(stringResource(R.string.note)) }, maxLines = 6, enabled = !state.saving,
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Notes, contentDescription = null) },
                        shape = MaterialTheme.shapes.large, colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent),
                        isError = error("note") != null, supportingText = error("note")?.let { { ErrorText(it) } })
                    // Rarely used details stay folded away, but open on their own when they hold something or need attention.
                    val optionalNeeded = record.feeText.isNotBlank() || record.tagsText.isNotBlank() || draft.attachments.isNotEmpty() ||
                        draft.excludeStats || draft.excludeBudget || state.foreignCurrency != null || state.importingAttachment ||
                        state.attachmentError != null || state.errors.any { it.field in setOf("fee", "tags") }
                    var moreOpen by rememberSaveable { mutableStateOf(false) }
                    val showMore = moreOpen || optionalNeeded
                    Surface(onClick = { moreOpen = !showMore }, enabled = !optionalNeeded, modifier = Modifier.fillMaxWidth().testTag("bill_more_options"),
                        shape = MaterialTheme.shapes.large, color = Color.Transparent) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.editor_more_options), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                if (!showMore) Text(stringResource(R.string.editor_more_summary), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val turn by animateFloatAsState(if (showMore) 180f else 0f, ExpressiveMotion.defaultSpatial(), label = "more-arrow")
                            Icon(Icons.Outlined.ExpandMore, null, Modifier.graphicsLayer { rotationZ = turn })
                        }
                    }
                    AnimatedVisibility(showMore, enter = expandVertically(ExpressiveMotion.defaultSpatial()) + fadeIn(ExpressiveMotion.defaultEffects()),
                        exit = shrinkVertically(ExpressiveMotion.fastSpatial()) + fadeOut(ExpressiveMotion.fastEffects())) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (draft.kind != BillKind.ADJUSTMENT || record.feeText.isNotBlank()) OutlinedTextField(record.feeText,
                                { editor.onAction(EditorAction.Fee(it)) }, fieldModifier("fee", "bill_fee"), enabled = !state.saving,
                                prefix = { Text((record.feeCurrency?.takeIf { record.feeText.isNotBlank() } ?: state.feeCurrency)?.code.orEmpty()) },
                                label = { Text(stringResource(R.string.currency_fee)) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = error("fee") != null,
                                supportingText = error("fee")?.let { { ErrorText(it) } })
                            OutlinedTextField(record.tagsText, { editor.onAction(EditorAction.Tags(it)) }, fieldModifier("tags", "bill_tags"),
                                label = { Text(stringResource(R.string.tags)) }, singleLine = true, enabled = !state.saving,
                                isError = error("tags") != null, supportingText = error("tags")?.let { { ErrorText(it) } })
                            if (overlayMode) {
                                onOpenApp?.let { open -> TextButton(open, enabled = !state.saving) { Text(stringResource(R.string.review_open_app)) } }
                            } else AttachmentEditor(draft.attachments, attachments, state.importingAttachment, state.attachmentError,
                                enabled = !state.saving, onAdd = { imagePicker?.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                onRemove = { ref -> editor.onAction(EditorAction.Attachments(draft.attachments.filterNot { it.id == ref.id })) },
                                onCancel = editor::cancelAttachmentImport)
                            BillCheckRow(stringResource(R.string.exclude_stats), draft.excludeStats, !state.saving) { editor.onAction(EditorAction.ExcludeStats(it)) }
                            BillCheckRow(stringResource(R.string.exclude_budget), draft.excludeBudget, !state.saving) { editor.onAction(EditorAction.ExcludeBudget(it)) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            picker?.let { field ->
                val currencies = (app.omymoney.feature.assets.commonCurrencies + accounts.map { it.currency } + record.currency).distinctBy { it.code }
                val options = if (field == "currency") currencies.map { SelectionOption(it.code, it.code) }
                else if (field == "categoryId") listOf(SelectionOption("none", stringResource(R.string.uncategorized))) +
                    categories.filter { it.kind == PostingCalculator.categoryKind(draft.kind) && (!it.archived || it.id == state.originalCategoryId) }.map {
                        SelectionOption(it.id, catalogName(it.name, it.archived), it.parentId?.let { parent -> categories.firstOrNull { category -> category.id == parent }?.name })
                    }
                else (if (PostingCalculator.allowsUnassignedAccounts(draft)) listOf(SelectionOption("unassigned", stringResource(R.string.payment_account_unknown))) else emptyList()) +
                    accounts.filter { (!it.archived || it.id in state.originalAccountIds) &&
                    app.omymoney.core.ledger.DebtRules.acceptsAccount(draft.kind, field == "fromAccountId", it) }.map {
                    SelectionOption(it.id, catalogName(it.name, it.archived), "${it.currency.code} ${it.balance.decimalText()}")
                }
                SelectionSheet(stringResource(when (field) { "currency" -> R.string.currency; "categoryId" -> R.string.select_category; else -> R.string.select_account }), options,
                    when (field) { "currency" -> record.currency.code; "categoryId" -> draft.categoryId ?: "none"; "fromAccountId" -> draft.fromAccountId ?: "unassigned"; else -> draft.toAccountId ?: "unassigned" },
                    onSelect = { id ->
                        when (field) {
                            "currency" -> editor.onAction(EditorAction.Currency(currencies.first { it.code == id }))
                            "categoryId" -> editor.onAction(EditorAction.Category(id.takeUnless { it == "none" }))
                            "fromAccountId" -> if (id == "unassigned" && PostingCalculator.allowsUnassignedAccounts(draft)) editor.onAction(EditorAction.FromAccount(null, record.currency))
                                else accounts.firstOrNull { it.id == id }?.let { editor.onAction(EditorAction.FromAccount(it.id, it.currency)) }
                            else -> if (id == "unassigned" && PostingCalculator.allowsUnassignedAccounts(draft)) editor.onAction(EditorAction.ToAccount(null, record.currency))
                                else accounts.firstOrNull { it.id == id }?.let { editor.onAction(EditorAction.ToAccount(it.id, it.currency)) }
                        }
                        picker = null
                    }, onDismiss = { picker = null }, embedded = overlayMode, modifier = if (overlayMode) Modifier.padding(padding) else Modifier)
            }
            if (datePicker && overlayMode) InlineDateTimeEditor(draft, onConfirm = {
                editor.onAction(EditorAction.Time(it)); datePicker = false
            }, onDismiss = { datePicker = false }, modifier = Modifier.padding(padding))
            if (datePicker && !overlayMode) {
                val initial = draft.occurredAt?.atZone(draft.zoneId)?.toLocalDate() ?: LocalDate.now(draft.zoneId)
                val dateState = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                    initialDisplayMode = DisplayMode.Input)
                DatePickerDialog(onDismissRequest = { datePicker = false }, confirmButton = {
                    TextButton(onClick = {
                        timeDate = dateState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                        datePicker = false
                    }, enabled = dateState.selectedDateMillis != null) { Text(stringResource(R.string.next)) }
                }, dismissButton = { TextButton({ datePicker = false }) { Text(stringResource(R.string.cancel)) } }) {
                    DatePicker(dateState, modifier = Modifier.verticalScroll(rememberScrollState()))
                }
            }
            timeDate?.let { date ->
                val initial = draft.occurredAt?.atZone(draft.zoneId)?.toLocalTime() ?: LocalTime.now(draft.zoneId)
                val timeState = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
                var invalidTime by remember { mutableStateOf(false) }
                AlertDialog(onDismissRequest = { timeDate = null }, title = { Text(stringResource(R.string.transaction_time)) }, text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("$date · ${draft.zoneId.id}")
                        TimeInput(timeState)
                        if (invalidTime) ErrorText(stringResource(R.string.invalid_local_time))
                    }
                }, confirmButton = { TextButton({
                    val local = LocalDate.parse(date).atTime(timeState.hour, timeState.minute)
                    invalidTime = draft.zoneId.rules.getValidOffsets(local).isEmpty()
                    if (!invalidTime) { editor.onAction(EditorAction.Time(local.atZone(draft.zoneId).toInstant())); timeDate = null }
                }) { Text(stringResource(R.string.confirm)) } }, dismissButton = {
                    TextButton({ timeDate = null }) { Text(stringResource(R.string.cancel)) }
                })
            }
        }
    }
    if (askLeave) AlertDialog(onDismissRequest = { askLeave = false },
        icon = { Icon(Icons.Outlined.Drafts, contentDescription = null) },
        title = { Text(stringResource(R.string.leave_draft_title)) },
        text = if (editor.scheduleExecutionId != null) { { Text(stringResource(R.string.schedule_leave_detail)) } } else null,
        confirmButton = { TextButton({ askLeave = false; editor.keepAndClose() }) { Text(stringResource(R.string.keep_draft)) } },
        dismissButton = { Column(horizontalAlignment = Alignment.End) {
            TextButton({ askLeave = false }) { Text(stringResource(R.string.keep_editing)) }
            TextButton({ askLeave = false; editor.discard() }) {
                Text(stringResource(if (editor.scheduleExecutionId != null) R.string.schedule_skip else R.string.discard_draft))
            }
        } })
}

@Composable private fun InlineDateTimeEditor(draft: TransactionDraft, onConfirm: (Instant) -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
    val initial = draft.occurredAt?.atZone(draft.zoneId) ?: ZonedDateTime.now(draft.zoneId)
    var date by rememberSaveable { mutableStateOf(initial.toLocalDate().toString()) }
    var time by rememberSaveable { mutableStateOf(initial.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onDismiss)
    Surface(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(draft.zoneId.id)
            OutlinedTextField(date, { date = it; invalid = false }, Modifier.fillMaxWidth().testTag("review_date"),
                label = { Text(stringResource(R.string.review_date)) }, singleLine = true)
            OutlinedTextField(time, { time = it; invalid = false }, Modifier.fillMaxWidth().testTag("review_time"),
                label = { Text(stringResource(R.string.review_time)) }, singleLine = true)
            if (invalid) ErrorText(stringResource(R.string.invalid_local_time))
            Button(onClick = {
                val local = runCatching { LocalDate.parse(date.trim()).atTime(LocalTime.parse(time.trim())) }.getOrNull()
                invalid = local == null || draft.zoneId.rules.getValidOffsets(local).isEmpty()
                if (!invalid) onConfirm(local!!.atZone(draft.zoneId).toInstant())
            }, Modifier.testTag("review_time_confirm")) { Text(stringResource(R.string.confirm)) }
        }
    }
}

/** Compact, tappable summary of one picker-backed field: icon, small label, current value. */
@Composable
private fun ParamChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, onClick: () -> Unit,
                      modifier: Modifier = Modifier, isError: Boolean = false, enabled: Boolean = true) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    // The tag sits on a wrapper so the disabled clickable surface stays visible to tests and accessibility as its own child.
    Box(modifier) {
        Surface(onClick = { keyboard?.hide(); focus.clearFocus(); onClick() }, enabled = enabled, shape = MaterialTheme.shapes.large,
            color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.heightIn(min = 56.dp).semantics { role = Role.DropdownList }) {
            Row(Modifier.padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, Modifier.size(20.dp), tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Column {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                }
            }
        }
    }
}

@Composable
fun BillCheckRow(label: String, checked: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.weight(1f).padding(start = 12.dp))
    }
}

@Composable
fun billKindLabel(kind: BillKind): String = stringResource(when (kind) {
    BillKind.EXPENSE -> R.string.expense
    BillKind.INCOME -> R.string.income
    BillKind.TRANSFER -> R.string.transfer
    BillKind.REFUND -> R.string.refund
    BillKind.REIMBURSEMENT -> R.string.pending_reimbursement
    BillKind.REIMBURSED -> R.string.reimbursed
    BillKind.DEBT_BORROW -> R.string.debt_borrow
    BillKind.DEBT_LEND -> R.string.debt_lend
    BillKind.DEBT_REPAY -> R.string.debt_repay
    BillKind.DEBT_COLLECT -> R.string.debt_collect
    BillKind.INTEREST_EXPENSE -> R.string.interest_expense
    BillKind.INTEREST_INCOME -> R.string.interest_income
    BillKind.CREDIT_PAYMENT -> R.string.credit_payment
    BillKind.ADJUSTMENT -> R.string.balance_adjustment
})

@Composable
private fun billErrorText(error: FieldError): String = stringResource(when {
    error.code == "overflow" -> R.string.error_overflow
    error.code == "missing_rate" -> R.string.currency_missing_pair
    error.code == "exchange_currency_changed" -> R.string.currency_exchange_changed
    error.code == "exchange_account_changed" -> R.string.currency_account_changed
    error.field == "exchangeRate" -> R.string.currency_rate_invalid
    error.field == "exchangeDate" -> R.string.currency_date_invalid
    error.field == "exchangeAmount" -> R.string.invalid_bill_amount
    error.field == "exchange" -> R.string.currency_snapshot_invalid
    error.code == "fee_currency_changed" -> R.string.currency_fee_changed
    error.field == "fee" -> R.string.currency_fee_invalid
    error.code == "exceeds_remaining" -> R.string.settlement_exceeds_remaining
    error.code == "account_role" -> R.string.asset_account_role
    error.code == "adjustment_reason" -> R.string.asset_adjust_required
    error.field == "linkedBillId" -> R.string.invalid_linked_source
    error.field == "amount" -> R.string.invalid_bill_amount
    error.code == "same_account" -> R.string.transfer_same_account
    error.code == "currency_mismatch" -> R.string.bill_currency_mismatch
    error.field == "occurredAt" -> R.string.confirm_transaction_time
    error.field == "fromAccountId" || error.field == "toAccountId" -> R.string.select_available_account
    error.field == "categoryId" -> R.string.select_available_category
    error.code == "invalid_tags" -> R.string.invalid_tags
    error.code == "note_too_long" -> R.string.note_too_long
    error.code == "conflict" -> R.string.bill_conflict
    error.code == "draft_storage" -> R.string.draft_persist_failed
    error.code == "storage" -> R.string.bill_save_failed
    else -> R.string.bill_invalid
})
