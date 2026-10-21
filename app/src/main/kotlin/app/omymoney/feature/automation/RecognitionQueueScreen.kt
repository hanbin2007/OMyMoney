package app.omymoney.feature.automation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.omymoney.android.R
import app.omymoney.data.*
import app.omymoney.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun RecognitionQueueScreen(queue: RecognitionQueue, onBack: () -> Unit, onReview: (QueueEntry) -> Unit, onBill: (String) -> Unit) {
    var history by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var entries by remember { mutableStateOf(emptyList<QueueEntry>()) }
    var busyId by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(queue, retry) {
        try {
            error = false; loading = true
            queue.expire()
            queue.observe().collect { entries = it; loading = false }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = true; loading = false }
    }
    ScreenScaffold(stringResource(R.string.review_queue), onBack, snackbarHostState = snackbar) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(if (history) 1 else 0) {
                Tab(!history, onClick = { history = false }, text = { Text(stringResource(R.string.review_pending)) })
                Tab(history, onClick = { history = true }, text = { Text(stringResource(R.string.review_history)) })
            }
            if (error) Column(Modifier.padding(16.dp)) {
                ErrorText(stringResource(R.string.review_load_failed))
                TextButton({ retry++ }) { Text(stringResource(R.string.retry)) }
            } else if (loading) LoadingState()
            else {
                val rows = entries.filter { (it.state in RecognitionQueue.ACTIVE_STATES) != history }.sortedByDescending { it.createdAt }
                LazyColumn(Modifier.fillMaxSize().testTag("recognition_queue")) {
                    if (rows.isEmpty()) item { Text(stringResource(R.string.review_queue_empty), Modifier.padding(24.dp)) }
                    items(rows, key = { it.id }) { entry ->
                        ListItem(headlineContent = { Text(entry.draft.amount?.let { "${it.currency.code} ${it.decimalText()}" } ?: stringResource(R.string.review_amount_unknown)) },
                            supportingContent = { Column {
                                Text(queueStateLabel(entry.state))
                                Text(applicationLabel(entry.draft.sourcePackage ?: entry.draft.source.name))
                                if (entry.draft.note.isNotBlank()) Text(entry.draft.note, maxLines = 2)
                            } },
                            trailingContent = if (entry.state in setOf(QueueState.IGNORED, QueueState.EXPIRED)) ({
                                LedgerIconButton(Icons.Outlined.Refresh, stringResource(R.string.review_reopen), enabled = busyId == null, onClick = {
                                    busyId = entry.id
                                    scope.launch {
                                        try { onReview(queue.resolve(entry.id, QueueOutcome.Reopen).entry) }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { snackbar.showSnackbar("未能重新打开，请重试") }
                                        finally { busyId = null }
                                    }
                                })
                            }) else null,
                            modifier = Modifier.testTag("queue_${entry.id}").clickable(enabled = entry.state in RecognitionQueue.ACTIVE_STATES || entry.state == QueueState.SAVED) {
                                if (entry.state == QueueState.SAVED) entry.billId?.let(onBill) else onReview(entry)
                            })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable private fun queueStateLabel(state: QueueState) = stringResource(when (state) {
    QueueState.RECOGNIZED, QueueState.PENDING -> R.string.review_pending
    QueueState.EDITING -> R.string.review_editing
    QueueState.IGNORED -> R.string.review_ignored
    QueueState.FAILED -> R.string.review_failed
    QueueState.SAVED -> R.string.review_saved
    QueueState.EXPIRED -> R.string.review_expired
})
