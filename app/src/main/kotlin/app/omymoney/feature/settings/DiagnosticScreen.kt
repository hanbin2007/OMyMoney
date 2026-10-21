package app.omymoney.feature.settings

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.BuildConfig
import app.omymoney.android.R
import app.omymoney.platform.accessibility.AccessibilityStatus
import app.omymoney.platform.diagnostics.DiagnosticManager
import app.omymoney.platform.diagnostics.DebugSessionController
import app.omymoney.platform.diagnostics.DebugSessionService
import app.omymoney.platform.remote.HttpFailure
import app.omymoney.platform.notification.NotificationCaptureStatus
import app.omymoney.ui.components.ScreenScaffold
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DiagnosticScreen(manager: DiagnosticManager, debugSession: DebugSessionController? = null, onBack: () -> Unit) {
    val state by manager.state.collectAsStateWithLifecycle()
    val runtime by AccessibilityStatus.runtime.collectAsStateWithLifecycle()
    val debugState = debugSession?.state?.collectAsStateWithLifecycle()?.value
    val context = LocalContext.current
    val notificationCounts by (context.applicationContext as app.omymoney.OMyMoneyApplication).container.notificationCounts.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var consent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf(state.extraPackages.sorted().joinToString("\n")) }
    var notificationsAllowed by remember { mutableStateOf(DebugSessionService.notificationsAllowed(context)) }
    var accessibilityGranted by remember { mutableStateOf(AccessibilityStatus.granted(context)) }
    var batteryExempt by remember { mutableStateOf(context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) }
    var notificationAccess by remember { mutableStateOf(NotificationCaptureStatus.granted(context)) }
    val notificationRuntime by NotificationCaptureStatus.runtime.collectAsStateWithLifecycle()
    LaunchedEffect(notificationRuntime.connected, notificationRuntime.error) {
        notificationAccess = NotificationCaptureStatus.granted(context)
    }
    LaunchedEffect(runtime.systemEnabled, runtime.systemBound, runtime.connected) {
        accessibilityGranted = AccessibilityStatus.granted(context)
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                notificationsAllowed = DebugSessionService.notificationsAllowed(context)
                accessibilityGranted = AccessibilityStatus.granted(context)
                batteryExempt = context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
                notificationAccess = NotificationCaptureStatus.granted(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun action(pairing: Boolean = false, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { snackbar.showSnackbar(context.getString(
                if (pairing && error is HttpFailure && error.status == 401) R.string.diagnostics_pair_invalid
                else if (pairing) R.string.diagnostics_pair_failed else R.string.remote_error)) }
            finally { busy = false }
        }
    }
    fun stop(block: suspend () -> Unit) { scope.launch {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { snackbar.showSnackbar(context.getString(R.string.remote_error)) }
    } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) action {
            val output = context.contentResolver.openOutputStream(uri) ?: error("export_unavailable")
            output.use { manager.export(it) }
            snackbar.showSnackbar(context.getString(R.string.remote_exported))
        }
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text(stringResource(R.string.diagnostics_consent_title)) },
        text = { androidx.compose.foundation.rememberScrollState().let { scroll ->
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(scroll)) {
                Text(stringResource(R.string.diagnostics_consent))
                Spacer(Modifier.height(12.dp))
                Text(state.packages.sorted().joinToString("\n"), Modifier.testTag("diagnostics_consent_scope"), style = MaterialTheme.typography.bodySmall)
            }
        } },
        confirmButton = { TextButton(onClick = { consent = false; action { manager.setEnabled(true) } }, Modifier.testTag("diagnostics_consent_accept")) { Text(stringResource(R.string.diagnostics_accept)) } },
        dismissButton = { TextButton(onClick = { consent = false }, Modifier.testTag("diagnostics_consent_cancel")) { Text(stringResource(R.string.remote_cancel)) } })
    ScreenScaffold(stringResource(R.string.diagnostics_title), onBack, snackbarHostState = snackbar) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("diagnostics_screen"), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { SettingSwitch(R.string.diagnostics_recording, state.enabled, "diagnostics_recording", (!busy || state.enabled) && debugState?.stopping != true) {
                if (it) consent = true else stop {
                    if (debugSession?.state?.value?.let { session -> session.active || session.starting } == true) debugSession.stop()
                    else manager.setEnabled(false)
                }
            } }
            item {
                ListItem(headlineContent = { Text(stringResource(R.string.notification_capture_title)) }, supportingContent = {
                    Text(stringResource(when {
                        !notificationAccess -> R.string.notification_capture_not_granted
                        notificationRuntime.error != null -> R.string.notification_capture_error
                        !notificationRuntime.connected -> R.string.notification_capture_binding
                        !state.enabled -> R.string.diagnostics_notifications_stopped
                        else -> R.string.diagnostics_notifications_active
                    }))
                }, modifier = Modifier.testTag("diagnostics_notification_status"))
                OutlinedButton(onClick = { action { NotificationCaptureStatus.openSettings(context) } },
                    modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp).testTag("diagnostics_notification_access")) {
                    Icon(Icons.Outlined.Notifications, null); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.notification_capture_permission))
                }
                Text(stringResource(R.string.notification_capture_counts, notificationCounts.received, notificationCounts.candidates,
                    notificationCounts.filtered, notificationCounts.dropped), Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("diagnostics_notification_counts"), style = MaterialTheme.typography.bodySmall)
            }
            if (debugSession != null) item {
                val session by debugSession.state.collectAsStateWithLifecycle()
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.debug_session_title), style = MaterialTheme.typography.titleMedium)
                    if (state.interruptedSession) Text(stringResource(R.string.debug_session_interrupted), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(if (session.stopping) R.string.debug_session_stopping else if (session.active) R.string.debug_session_active else if (session.starting) R.string.debug_session_starting else R.string.debug_session_inactive))
                    if (session.active || session.starting) {
                        OutlinedButton(onClick = { stop { debugSession.stop() } }, Modifier.testTag("debug_session_stop")) {
                            Icon(Icons.Outlined.Stop, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.debug_session_stop))
                        }
                    } else {
                        Button(onClick = { action { debugSession.start() } }, Modifier.testTag("debug_session_start"),
                            enabled = state.enabled && runtime.available && notificationsAllowed && !busy && !session.stopping) {
                            Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.debug_session_start))
                        }
                        if (!state.enabled) Text(stringResource(R.string.debug_session_needs_recording), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.debug_session_disclosure), style = MaterialTheme.typography.bodySmall)
                    if (session.error) Text(stringResource(R.string.debug_session_error), color = MaterialTheme.colorScheme.error)
                    if (!notificationsAllowed) {
                        Text(stringResource(R.string.debug_session_needs_notifications), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
                                .onFailure { scope.launch { snackbar.showSnackbar(context.getString(R.string.remote_error)) } }
                        }) { Icon(Icons.Outlined.Notifications, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.debug_session_notifications)) }
                    }
                    OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${context.packageName}"))) }
                        .onFailure { scope.launch { snackbar.showSnackbar(context.getString(R.string.remote_error)) } } }) {
                        Icon(Icons.Outlined.Settings, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.debug_session_app_settings))
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!runtime.available) Text(stringResource(if (accessibilityGranted) R.string.diagnostics_waiting_connection else R.string.diagnostics_no_permission),
                        Modifier.testTag("diagnostics_accessibility_status"), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        .onFailure { scope.launch { snackbar.showSnackbar(context.getString(R.string.remote_error)) } } }) {
                        Icon(Icons.Outlined.AccessibilityNew, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.diagnostics_accessibility))
                    }
                    Text(stringResource(R.string.diagnostics_status, state.pendingBatches, android.text.format.Formatter.formatShortFileSize(context, state.pendingBytes),
                        state.sentBatches, state.droppedRecords, state.uncleanSessions), style = MaterialTheme.typography.bodyMedium)
                    if (state.lastUpload > 0) Text(stringResource(R.string.diagnostics_last_upload, DateFormat.getDateTimeInstance().format(Date(state.lastUpload))), style = MaterialTheme.typography.bodySmall)
                    if (state.error != null) Text(remoteErrorText(state.error!!), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(if (batteryExempt) R.string.diagnostics_battery_exempt else R.string.diagnostics_battery_optimized), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                            .onFailure { scope.launch { snackbar.showSnackbar(context.getString(R.string.remote_error)) } }
                    }, modifier = Modifier.heightIn(min = 48.dp).testTag("diagnostics_battery_settings")) {
                        Icon(Icons.Outlined.BatteryFull, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.diagnostics_battery_settings))
                    }
                }
            }
            item { RemoteHeading(R.string.diagnostics_pair_title) }
            item { Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(BuildConfig.SERVICE_ORIGIN, style = MaterialTheme.typography.bodySmall)
                if (state.paired) {
                    Text(stringResource(R.string.diagnostics_paired))
                    Button(onClick = { action {
                        val before = manager.state.value.sentBatches
                        if (!manager.upload(manual = true)) error("upload_failed")
                        snackbar.showSnackbar(context.getString(R.string.diagnostics_upload_result, manager.state.value.sentBatches - before))
                    } }, enabled = !busy && !state.uploading) {
                        Icon(Icons.Outlined.CloudUpload, null); Spacer(Modifier.width(8.dp)); Text(stringResource(if (state.uploading) R.string.diagnostics_uploading else R.string.diagnostics_upload))
                    }
                    OutlinedButton(onClick = { stop { manager.disconnect() } }) { Text(stringResource(R.string.diagnostics_disconnect)) }
                    Text(stringResource(R.string.diagnostics_disconnect_note), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.diagnostics_pair_note), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(code, { code = it.take(16) }, label = { Text(stringResource(R.string.diagnostics_pair_code)) },
                        modifier = Modifier.fillMaxWidth().testTag("diagnostics_pair_code"), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation())
                    Button(onClick = { val input = code; action(pairing = true) { manager.pair(input); code = "" } }, enabled = !busy && code.matches(Regex("[0-9a-fA-F]{16}"))) {
                        Icon(Icons.Outlined.Link, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.diagnostics_pair))
                    }
                }
                OutlinedButton(onClick = { export.launch("OMyMoney-diagnostics.zip") }, enabled = !busy) {
                    Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.diagnostics_export))
                }
                Text(stringResource(R.string.diagnostics_export_warning), style = MaterialTheme.typography.bodySmall)
            } }
            item { RemoteHeading(R.string.diagnostics_scope) }
            item { Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(app.omymoney.core.recognition.RuleRegistry.supportedPackages.sorted().joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(extra, { extra = it.take(6500) }, label = { Text(stringResource(R.string.diagnostics_extra)) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Text(stringResource(R.string.diagnostics_extra_note), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { action {
                    manager.setExtraPackages(extra.lines().map(String::trim).filter(String::isNotEmpty).toSet())
                    snackbar.showSnackbar(context.getString(R.string.remote_saved))
                } }, enabled = !busy) { Icon(Icons.Outlined.Save, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.remote_save)) }
                Text(stringResource(R.string.remote_network_disclosure), style = MaterialTheme.typography.bodySmall)
            } }
        }
    }
}

@Composable
internal fun RemoteHeading(label: Int) {
    HorizontalDivider(Modifier.padding(top = 16.dp))
    Text(stringResource(label), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
}

@Composable
internal fun remoteErrorText(code: String, diagnostics: Boolean = true): String = when (code) {
    "http_401", "http_403" -> if (diagnostics) "配对凭据无效或已撤销，请重新配对。" else "更新服务器拒绝访问，请稍后重试。应用更新无需配对日志上传。"
    "http_429" -> if (diagnostics) "请求较多，请稍后重试；日志仍保存在本机。" else "更新服务器繁忙，请稍后重试。"
    "http_503" -> "服务器暂不可用或空间不足，请稍后重试。"
    "storage", "disk_space" -> "设备存储不可用或剩余空间不足。"
    else -> stringResource(R.string.remote_error)
}
