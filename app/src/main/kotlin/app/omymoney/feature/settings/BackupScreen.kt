package app.omymoney.feature.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.R
import app.omymoney.data.backup.*
import app.omymoney.ui.components.ErrorText
import app.omymoney.ui.components.ScreenScaffold
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun BackupScreen(viewModel: BackupViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedMode by rememberSaveable(state.preview?.previewId) { mutableStateOf(RestoreMode.MERGE) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), viewModel::export)
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), viewModel::inspect)
    fun back() { when { state.cancellable -> viewModel.cancelOperation(); state.busy -> Unit; state.preview != null -> viewModel.cancelPreview(); else -> onBack() } }
    BackHandler(state.busy || state.preview != null, ::back)
    ScreenScaffold(stringResource(R.string.backup_title), ::back, backEnabled = !state.busy || state.cancellable) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).testTag("backup_screen"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("backup_busy"))
                Text(stringResource(when (state.operation) {
                    BackupOperation.EXPORT -> R.string.backup_exporting
                    BackupOperation.RESTORE -> R.string.backup_restoring
                    else -> R.string.backup_checking
                }))
                if (state.cancellable) TextButton(viewModel::cancelOperation, Modifier.testTag("backup_cancel_operation")) { Text(stringResource(R.string.cancel)) }
            }
            state.error?.let { ErrorText(stringResource(when (it) {
                "stale_preview" -> R.string.backup_stale
                "merge_conflict" -> R.string.backup_conflict_error
                "expired_preview", "invalid_preview" -> R.string.backup_expired
                "size_limit" -> R.string.backup_size_error
                "unsupported_schema" -> R.string.backup_schema_error
                else -> R.string.backup_failed
            })) }
            state.notice?.let { Text(stringResource(when (it) {
                "exported" -> R.string.backup_exported
                "restored" -> R.string.backup_restored
                "export_cancelled" -> R.string.backup_export_cancelled
                else -> R.string.backup_cancelled
            }), Modifier.testTag("backup_notice"), color = MaterialTheme.colorScheme.primary) }
            val preview = state.preview
            if (preview == null) {
                Button({ viewModel.prepareExport(false); export.launch("OMyMoney-${LocalDate.now()}.zip") },
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("backup_export")) {
                    Icon(Icons.Outlined.FileUpload, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.backup_export))
                }
                OutlinedButton({ open.launch(arrayOf("application/zip", "application/octet-stream")) },
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("backup_import")) {
                    Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.backup_import))
                }
                if (state.recoveryAvailable) OutlinedButton({ viewModel.prepareExport(true); export.launch("OMyMoney-before-replace.zip") },
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("backup_recovery")) {
                    Icon(Icons.Outlined.Restore, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.backup_recovery))
                }
                Text(stringResource(R.string.backup_privacy), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(stringResource(R.string.backup_preview), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.backup_counts, preview.summary.books, preview.summary.accounts, preview.summary.bills, preview.summary.attachments))
                if (preview.summary.scheduledPlans > 0) Text(stringResource(R.string.backup_schedule_counts, preview.summary.scheduledPlans,
                    preview.summary.automaticPlans, preview.summary.pendingExecutions), Modifier.testTag("backup_schedule_counts"))
                if (preview.summary.savingsPlans > 0) Text(stringResource(R.string.backup_savings_counts, preview.summary.savingsPlans,
                    preview.summary.savingsDeposits), Modifier.testTag("backup_savings_counts"))
                if (preview.summary.importReceipts > 0 || preview.summary.batchActions > 0) Text(stringResource(R.string.backup_exchange_counts,
                    preview.summary.importReceipts, preview.summary.batchActions), Modifier.testTag("backup_exchange_counts"))
                val date = remember(preview.createdAt) { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").format(Instant.parse(preview.createdAt).atZone(ZoneId.systemDefault())) }
                Text(stringResource(R.string.backup_created, date), style = MaterialTheme.typography.bodySmall)
                Column(Modifier.selectableGroup()) {
                    RestoreMode.entries.forEach { mode ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selectedMode == mode,
                            enabled = !state.busy, role = Role.RadioButton, onClick = { selectedMode = mode })
                            .testTag("restore_${mode.name.lowercase()}").padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selectedMode == mode, onClick = null, enabled = !state.busy)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(if (mode == RestoreMode.MERGE) R.string.backup_merge else R.string.backup_replace))
                                Text(stringResource(if (mode == RestoreMode.MERGE) R.string.backup_merge_detail else R.string.backup_replace_detail), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (preview.conflicts.isNotEmpty()) {
                    ErrorText(stringResource(R.string.backup_conflicts, preview.conflicts.size))
                    preview.conflicts.take(5).forEach { conflict ->
                        Text(stringResource(when (conflict.kind) {
                            ConflictKind.BOOK -> R.string.backup_conflict_book
                            ConflictKind.ACCOUNT -> R.string.backup_conflict_account
                            ConflictKind.CATEGORY -> R.string.backup_conflict_category
                            ConflictKind.BILL -> R.string.backup_conflict_bill
                            ConflictKind.POSTING -> R.string.backup_conflict_posting
                            ConflictKind.SUBMISSION -> R.string.backup_conflict_submission
                            ConflictKind.DELETION -> R.string.backup_conflict_deletion
                            ConflictKind.DRAFT -> R.string.backup_conflict_draft
                            ConflictKind.QUEUE -> R.string.backup_conflict_queue
                            ConflictKind.BALANCE_OVERFLOW -> R.string.backup_conflict_balance_overflow
                            ConflictKind.LINKED_RULE -> R.string.backup_conflict_linked_rule
                            ConflictKind.EXCHANGE_RATE -> R.string.backup_conflict_exchange_rate
                            ConflictKind.BUDGET -> R.string.backup_conflict_budget
                            ConflictKind.SCHEDULE -> R.string.backup_conflict_schedule
                            ConflictKind.SCHEDULE_EXECUTION -> R.string.backup_conflict_schedule_execution
                            ConflictKind.SAVINGS_PLAN -> R.string.backup_conflict_savings_plan
                            ConflictKind.SAVINGS_DEPOSIT -> R.string.backup_conflict_savings_deposit
                            ConflictKind.IMPORT_RECEIPT -> R.string.backup_conflict_import_receipt
                            ConflictKind.BATCH_ACTION -> R.string.backup_conflict_batch_action
                        }), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.backup_conflict_labels, conflict.localLabel, conflict.backupLabel), style = MaterialTheme.typography.bodySmall)
                    }
                    if (preview.conflicts.size > 5) Text(stringResource(R.string.backup_more_conflicts, preview.conflicts.size - 5), style = MaterialTheme.typography.bodySmall)
                }
                Button({ confirming = true }, enabled = !state.busy && (selectedMode == RestoreMode.REPLACE || preview.conflicts.isEmpty()),
                    modifier = Modifier.fillMaxWidth().testTag("backup_restore")) { Text(stringResource(R.string.backup_restore)) }
                TextButton(viewModel::cancelPreview, enabled = !state.busy, modifier = Modifier.align(Alignment.End).testTag("backup_cancel")) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
    if (confirming && state.preview != null) AlertDialog(onDismissRequest = { confirming = false },
        title = { Text(stringResource(if (selectedMode == RestoreMode.REPLACE) R.string.backup_replace_confirm_title else R.string.backup_merge)) },
        text = { Text(stringResource(if (selectedMode == RestoreMode.REPLACE) R.string.backup_replace_confirm else R.string.backup_merge_confirm)) },
        confirmButton = { TextButton({ confirming = false; viewModel.restore(selectedMode) }, Modifier.testTag("backup_confirm")) {
            Text(stringResource(if (selectedMode == RestoreMode.REPLACE) R.string.backup_replace else R.string.backup_merge))
        } }, dismissButton = { TextButton({ confirming = false }) { Text(stringResource(R.string.cancel)) } })
}
