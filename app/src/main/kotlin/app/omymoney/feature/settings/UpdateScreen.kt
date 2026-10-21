package app.omymoney.feature.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.BuildConfig
import app.omymoney.android.R
import app.omymoney.platform.updates.UpdateManager
import app.omymoney.ui.components.ScreenScaffold
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun UpdateScreen(manager: UpdateManager, onBack: () -> Unit) {
    val state by manager.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current ?: return
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun settings(check: Boolean, download: Boolean) { scope.launch {
        try { manager.settings(check, download) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { snackbar.showSnackbar(activity.getString(R.string.remote_error)) }
    } }
    fun install() {
        try {
            if (!activity.packageManager.canRequestPackageInstalls()) activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            else manager.startInstall()
        } catch (_: Exception) { scope.launch { snackbar.showSnackbar(activity.getString(R.string.remote_error)) } }
    }
    ScreenScaffold(stringResource(R.string.updates_title), onBack, snackbarHostState = snackbar) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("updates_screen"), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { ListItem(headlineContent = { Text("OMyMoney ${BuildConfig.VERSION_NAME}") }, supportingContent = { Text("Build ${BuildConfig.VERSION_CODE}") },
                leadingContent = { Icon(Icons.Outlined.SystemUpdate, null) }) }
            item { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(when (state.phase) {
                    "checking" -> R.string.updates_checking
                    "downloading" -> R.string.updates_downloading
                    "verifying" -> R.string.updates_verifying
                    "available" -> R.string.updates_available
                    "ready" -> R.string.updates_ready
                    "current" -> R.string.updates_current
                    "paused" -> R.string.updates_paused
                    "installing" -> R.string.updates_installing
                    "confirmation" -> R.string.updates_confirmation
                    "interrupted" -> R.string.updates_interrupted
                    "installed" -> R.string.updates_installed
                    "awaiting_restart" -> R.string.updates_restart
                    "error" -> R.string.remote_error
                    else -> R.string.updates_idle
                }), style = MaterialTheme.typography.titleMedium)
                if (state.lastCheck > 0) Text(stringResource(R.string.updates_last_check, DateFormat.getDateTimeInstance().format(Date(state.lastCheck))), style = MaterialTheme.typography.bodySmall)
                state.error?.let { error -> Text(when (error) {
                    "verification_failed" -> stringResource(R.string.updates_verification_failed)
                    "install_permission" -> stringResource(R.string.updates_permission)
                    "install_cancelled" -> stringResource(R.string.updates_cancelled)
                    else -> remoteErrorText(error, diagnostics = false)
                }, color = MaterialTheme.colorScheme.error) }
                OutlinedButton(onClick = { scope.launch { manager.check() } }, enabled = !state.busy, modifier = Modifier.testTag("updates_check")) {
                    Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.updates_check))
                }
                state.release?.let { release ->
                    Text("${release.versionName} (${release.versionCode})", style = MaterialTheme.typography.titleMedium)
                    Text(android.text.format.Formatter.formatShortFileSize(activity, release.apkBytes))
                    if (release.notes.isNotEmpty()) Text(release.notes)
                    if (state.phase == "downloading") {
                        LinearProgressIndicator(progress = { (state.downloaded.toFloat() / release.apkBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("${android.text.format.Formatter.formatShortFileSize(activity, state.downloaded)} / ${android.text.format.Formatter.formatShortFileSize(activity, release.apkBytes)}")
                        OutlinedButton(onClick = manager::cancelDownload) { Icon(Icons.Outlined.Pause, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.updates_pause)) }
                    } else if (!state.busy) {
                        if (state.phase in setOf("ready", "interrupted", "confirmation")) {
                            Button(onClick = {
                                val confirmation = state.confirmation
                                if (confirmation != null) {
                                    try { activity.startActivity(confirmation); manager.confirmationLaunched() }
                                    catch (_: Exception) { scope.launch { snackbar.showSnackbar(activity.getString(R.string.remote_error)) } }
                                } else install()
                            }, modifier = Modifier.testTag("updates_install")) {
                                Icon(Icons.Outlined.InstallMobile, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.updates_install))
                            }
                        } else if (state.phase != "awaiting_restart") Button(onClick = manager::startDownload, Modifier.testTag("updates_download")) {
                            Icon(Icons.Outlined.Download, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.updates_download))
                        }
                    }
                }
            } }
            item { HorizontalDivider() }
            item { SettingSwitch(R.string.updates_auto_check, state.autoCheck, "updates_auto_check") { settings(it, state.autoDownload) } }
            item { SettingSwitch(R.string.updates_auto_download, state.autoDownload, "updates_auto_download", state.autoCheck) { settings(state.autoCheck, it) } }
            item { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.updates_disclosure), style = MaterialTheme.typography.bodyMedium)
                Text(BuildConfig.SERVICE_ORIGIN, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.remote_network_disclosure), style = MaterialTheme.typography.bodySmall)
            } }
        }
    }
}
