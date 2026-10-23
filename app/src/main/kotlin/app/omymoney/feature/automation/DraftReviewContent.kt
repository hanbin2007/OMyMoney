package app.omymoney.feature.automation

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.ui.Alignment
import app.omymoney.ui.components.IconBadge
import app.omymoney.ui.components.groupedShape
import app.omymoney.ui.theme.ExpressiveMotion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.R
import app.omymoney.core.model.*
import app.omymoney.core.recognition.*
import app.omymoney.data.*
import app.omymoney.feature.bills.*
import app.omymoney.feature.catalog.*
import app.omymoney.ui.components.ErrorText
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CancellationException
import java.time.format.DateTimeFormatter

@Composable
fun DraftReviewContent(editor: BillEditorViewModel, catalog: CatalogViewModel, state: CatalogState, store: LedgerStore,
    onBack: () -> Unit, onSaved: (String) -> Unit, overlayMode: Boolean = false, onOpenApp: (() -> Unit)? = null) {
    val editorState by editor.state.collectAsStateWithLifecycle()
    val entry = editorState.recognitionEntry
    val settingsFlow = remember(store) { store.automation.observe() }
    val settings by settingsFlow.collectAsStateWithLifecycle(AutomationSettingsState())
    var duplicateConfirmed by rememberSaveable(entry?.id) { mutableStateOf(false) }
    val confirmDuplicate = entry?.decision?.kind == DedupKind.SIMILAR && settings.settings.dedupPresentation == DedupPresentation.CONFIRM
    BillEditorScreen(editor, catalog, state, store.attachments, onBack, onSaved, overlayMode, onOpenApp,
        saveEnabled = !confirmDuplicate || duplicateConfirmed, header = {
            if (entry != null) Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReviewEvidence(entry, state, store, showSimilar = settings.settings.dedupPresentation != DedupPresentation.SKIP_SIMILARITY)
                    if (confirmDuplicate) BillCheckRow(stringResource(R.string.review_confirm_duplicate), duplicateConfirmed,
                        !editorState.saving) { duplicateConfirmed = it }
                    TextButton(editor::discard, enabled = !editorState.saving, modifier = Modifier.testTag("review_ignore"),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Outlined.Block, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.review_ignore))
                    }
                }
            }
        })
}

@Composable private fun ReviewEvidence(entry: QueueEntry, catalog: CatalogState, store: LedgerStore, showSimilar: Boolean) {
    val context = LocalContext.current
    var evidenceOpen by rememberSaveable(entry.id) { mutableStateOf(false) }
    var comparisonOpen by rememberSaveable(entry.id) { mutableStateOf(false) }
    val evidence = entry.result.evidence()
    val suggestions = remember(entry, catalog.accounts) {
        AccountMatcher.match(entry.draft, catalog.accounts, evidence.filter { it.field in setOf("accountHint", "fromAccountHint", "toAccountHint") }
            .map { it.copy(field = "accountHint") })
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(if (entry.draft.source == BillSource.NOTIFICATION) Icons.Outlined.Notifications else Icons.Outlined.AutoAwesome,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer)
        Text(stringResource(R.string.review_source, applicationLabel(entry.draft.sourcePackage ?: entry.draft.source.name)),
            Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
    }
    val issues = (entry.result as? RecognitionResult.NeedsReview)?.issues.orEmpty()
    if (issues.isNotEmpty()) Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp))
            ErrorText(stringResource(R.string.review_needs_check))
        }
    }
    if (entry.draft.source == BillSource.NOTIFICATION) Text(stringResource(R.string.notification_capture_review),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (showSimilar && entry.decision.kind == DedupKind.SIMILAR) {
        FilterChip(comparisonOpen, { comparisonOpen = !comparisonOpen }, label = { Text(stringResource(R.string.review_similar, entry.decision.matches.size)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.CompareArrows, null, Modifier.size(18.dp)) }, modifier = Modifier.testTag("review_compare"))
        if (comparisonOpen) {
            val comparisonFlow = remember(entry.id, store) {
                val flows = entry.decision.matches.take(20).map { comparisonFlow(it, store) }
                if (flows.isEmpty()) flowOf(emptyList()) else combine(flows) { it.toList() }
            }
            val comparisons by comparisonFlow.collectAsStateWithLifecycle(emptyList())
            Text(stringResource(R.string.review_this_candidate), style = MaterialTheme.typography.titleSmall)
            ComparisonFields(entry.result.draft(), evidence, catalog)
            comparisons.forEach { (match, draft, fields) ->
                HorizontalDivider()
                Text(stringResource(R.string.review_previous_candidate), style = MaterialTheme.typography.titleSmall)
                if (draft == null) Text(stringResource(R.string.review_history_unavailable)) else {
                    ComparisonFields(draft, fields, catalog)
                    val differences = Deduplicator.differences(DedupCandidate(entry.id, entry.result.draft(), evidence),
                        DedupCandidate(match.id, draft, fields)) + if (entry.result.draft().note != draft.note) listOf("note") else emptyList()
                    if (differences.isNotEmpty()) Text(stringResource(R.string.review_differences,
                        differences.joinToString(context.getString(R.string.runtime_list_separator)) { evidenceLabel(context, it) }))
                    else Text(stringResource(R.string.review_no_differences))
                }
            }
            if (entry.decision.matches.size > 20) Text(stringResource(R.string.review_comparison_limit))
        }
    }
    if (suggestions.options.isNotEmpty()) {
        Text(stringResource(R.string.review_account_candidates), style = MaterialTheme.typography.labelLarge)
        suggestions.options.take(5).forEach { suggestion ->
            val account = catalog.accounts.firstOrNull { it.id == suggestion.accountId }
            Text(listOfNotNull(account?.name, suggestion.reasons.joinToString(context.getString(R.string.runtime_list_separator)) { evidenceLabel(context, it) },
                if (suggestion.differences.isEmpty()) null else stringResource(R.string.review_account_conflict)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall)
        }
    }
    FilterChip(evidenceOpen, { evidenceOpen = !evidenceOpen }, label = { Text(stringResource(R.string.review_evidence)) },
        leadingIcon = { Icon(Icons.Outlined.FactCheck, null, Modifier.size(18.dp)) }, modifier = Modifier.testTag("review_evidence"))
    AnimatedVisibility(evidenceOpen, enter = expandVertically(ExpressiveMotion.defaultSpatial()) + fadeIn(),
        exit = shrinkVertically(ExpressiveMotion.fastSpatial()) + fadeOut()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            evidence.forEachIndexed { index, field ->
                Surface(Modifier.fillMaxWidth(), shape = groupedShape(index, evidence.size, outer = 16.dp, inner = 4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(evidenceLabel(context, field.field), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(field.rawText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private data class Comparison(val match: DedupMatch, val draft: TransactionDraft?, val evidence: List<FieldEvidence> = emptyList())

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun comparisonFlow(match: DedupMatch, store: LedgerStore): Flow<Comparison> = (
    if (match.id.startsWith("bill/")) store.ledger.observeBill(match.id.removePrefix("bill/")).map { Comparison(match, it?.toDraft()) }
    else store.recognitionQueue.observe().map { entries -> entries.firstOrNull { it.id == match.id } }.distinctUntilChanged().flatMapLatest { previous ->
        val evidence = previous?.result?.evidence().orEmpty()
        if (previous?.state == QueueState.SAVED) previous.billId?.let { id ->
            store.ledger.observeBill(id).map { Comparison(match, it?.toDraft(), evidence) }
        } ?: flowOf(Comparison(match, null))
        else flowOf(Comparison(match, previous?.draft, evidence))
    }).catch { error ->
        if (error is CancellationException) throw error
        emit(Comparison(match, null))
    }

@Composable private fun ComparisonFields(draft: TransactionDraft, evidence: List<FieldEvidence>, catalog: CatalogState) {
    val context = LocalContext.current
    Text(summary(context, draft))
    Text(stringResource(R.string.runtime_field_value, stringResource(R.string.runtime_kind),
        draft.kind?.let { billKindLabel(it) } ?: stringResource(R.string.runtime_unconfirmed)))
    Text(stringResource(R.string.review_source, applicationLabel(draft.sourcePackage ?: draft.source.name)))
    evidence.filter { it.field == "merchant" || it.field in ORDER_ID_FIELDS }.forEach {
        Text(stringResource(R.string.runtime_field_value, evidenceLabel(context, it.field), it.rawText))
    }
    val accounts = listOfNotNull(draft.fromAccountId, draft.toAccountId).map { id ->
        catalog.accounts.firstOrNull { it.id == id }?.name ?: context.getString(R.string.unavailable_account)
    }
    Text(stringResource(R.string.runtime_field_value, stringResource(R.string.account),
        accounts.joinToString(" → ").ifEmpty { context.getString(R.string.runtime_not_selected) }))
}

internal fun summary(context: Context, draft: TransactionDraft): String = listOfNotNull(
    draft.amount?.let { "${it.currency.code} ${it.decimalText()}" } ?: context.getString(R.string.review_amount_unknown),
    draft.occurredAt?.atZone(draft.zoneId)?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")) ?: context.getString(R.string.runtime_time_unconfirmed),
    draft.note.take(160).takeIf(String::isNotBlank),
).joinToString("\n")

private fun evidenceLabel(context: Context, field: String): String {
    val label = when (field) {
        "amount" -> R.string.amount; "kind" -> R.string.runtime_kind; "occurredAt" -> R.string.transaction_time
        "merchant" -> R.string.runtime_merchant; "orderId" -> R.string.runtime_order_id
        "transactionId" -> R.string.runtime_transaction_id; "merchantOrderId" -> R.string.runtime_merchant_order_id
        "sourcePackage" -> R.string.runtime_source_app; "accounts", "accountHint" -> R.string.account
        "fromAccountHint" -> R.string.runtime_payment_account_evidence; "toAccountHint" -> R.string.runtime_receiving_account_evidence
        "note" -> R.string.note; "paymentState" -> R.string.runtime_payment_state; "fee" -> R.string.currency_fee
        "name" -> R.string.runtime_name_match; "similar_name" -> R.string.runtime_similar_name
        "card_tail" -> R.string.runtime_card_tail_match; "scenario" -> R.string.runtime_scenario
        "notification" -> R.string.runtime_notification_content
        else -> return field
    }
    return context.getString(label)
}
