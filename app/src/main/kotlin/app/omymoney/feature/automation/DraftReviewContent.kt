package app.omymoney.feature.automation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
            if (entry != null) {
                ReviewEvidence(entry, state, store, showSimilar = settings.settings.dedupPresentation != DedupPresentation.SKIP_SIMILARITY)
                if (confirmDuplicate) BillCheckRow(stringResource(R.string.review_confirm_duplicate), duplicateConfirmed,
                    !editorState.saving) { duplicateConfirmed = it }
                TextButton(editor::discard, enabled = !editorState.saving, modifier = Modifier.testTag("review_ignore")) {
                    Icon(Icons.Outlined.Block, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.review_ignore))
                }
            }
        })
}

@Composable private fun ReviewEvidence(entry: QueueEntry, catalog: CatalogState, store: LedgerStore, showSimilar: Boolean) {
    var evidenceOpen by rememberSaveable(entry.id) { mutableStateOf(false) }
    var comparisonOpen by rememberSaveable(entry.id) { mutableStateOf(false) }
    val evidence = entry.result.evidence()
    val suggestions = remember(entry, catalog.accounts) {
        AccountMatcher.match(entry.draft, catalog.accounts, evidence.filter { it.field in setOf("accountHint", "fromAccountHint", "toAccountHint") }
            .map { it.copy(field = "accountHint") })
    }
    Text(stringResource(R.string.review_source, applicationLabel(entry.draft.sourcePackage ?: entry.draft.source.name)),
        style = MaterialTheme.typography.labelLarge)
    val issues = (entry.result as? RecognitionResult.NeedsReview)?.issues.orEmpty()
    if (issues.isNotEmpty()) ErrorText(stringResource(R.string.review_needs_check))
    if (entry.draft.source == BillSource.NOTIFICATION) Text(stringResource(R.string.notification_capture_review),
        style = MaterialTheme.typography.bodySmall)
    if (showSimilar && entry.decision.kind == DedupKind.SIMILAR) {
        TextButton({ comparisonOpen = !comparisonOpen }, Modifier.testTag("review_compare")) {
            Text(stringResource(R.string.review_similar, entry.decision.matches.size))
        }
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
                        differences.joinToString("、") { evidenceLabel(it) }))
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
            Text(listOfNotNull(account?.name, suggestion.reasons.joinToString("、") { evidenceLabel(it) },
                if (suggestion.differences.isEmpty()) null else stringResource(R.string.review_account_conflict)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall)
        }
    }
    TextButton({ evidenceOpen = !evidenceOpen }, Modifier.testTag("review_evidence")) { Text(stringResource(R.string.review_evidence)) }
    if (evidenceOpen) evidence.forEach { field ->
        Text(evidenceLabel(field.field), style = MaterialTheme.typography.labelLarge)
        Text(field.rawText, style = MaterialTheme.typography.bodyMedium)
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
    Text(summary(draft))
    Text("收支类型：${draft.kind?.let { billKindLabel(it) } ?: "未确认"}")
    Text(stringResource(R.string.review_source, applicationLabel(draft.sourcePackage ?: draft.source.name)))
    evidence.filter { it.field == "merchant" || it.field in ORDER_ID_FIELDS }.forEach { Text("${evidenceLabel(it.field)}：${it.rawText}") }
    val accounts = listOfNotNull(draft.fromAccountId, draft.toAccountId).map { id -> catalog.accounts.firstOrNull { it.id == id }?.name ?: "账户不可用" }
    Text("账户：${accounts.joinToString(" → ").ifEmpty { "未选择" }}")
}

internal fun summary(draft: TransactionDraft): String = listOfNotNull(
    draft.amount?.let { "${it.currency.code} ${it.decimalText()}" } ?: "金额未确认",
    draft.occurredAt?.atZone(draft.zoneId)?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")) ?: "时间未确认",
    draft.note.take(160).takeIf(String::isNotBlank),
).joinToString("\n")

private fun evidenceLabel(field: String): String = when (field) {
    "amount" -> "金额"; "kind" -> "收支类型"; "occurredAt" -> "交易时间"; "merchant" -> "商户"; "orderId" -> "订单号"
    "transactionId" -> "交易单号"; "merchantOrderId" -> "商户订单号"
    "sourcePackage" -> "来源应用"; "accounts", "accountHint" -> "账户"; "fromAccountHint" -> "付款账户依据"; "toAccountHint" -> "收款账户依据"
    "note" -> "备注"; "paymentState" -> "付款状态"; "fee" -> "手续费"; "name" -> "名称匹配"; "similar_name" -> "相似名称"; "card_tail" -> "尾号匹配"; "scenario" -> "应用场景"
    "notification" -> "通知内容"
    else -> field
}
