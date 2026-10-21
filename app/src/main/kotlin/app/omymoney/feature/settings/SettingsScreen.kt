package app.omymoney.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.AddToHomeScreen
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.foundation.clickable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.AppContainer
import app.omymoney.android.R
import app.omymoney.core.model.Book
import app.omymoney.platform.security.lockErrorMessage
import app.omymoney.ui.components.ScreenScaffold
import app.omymoney.ui.theme.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, book: Book?, onBack: () -> Unit, onDiagnostics: () -> Unit = {}, onUpdates: () -> Unit = {}) {
    val activity = LocalActivity.current as ComponentActivity
    val options by container.preferences.state.collectAsStateWithLifecycle()
    val lock by container.privacyLock.state.collectAsStateWithLifecycle()
    var storageError by remember { mutableStateOf(false) }
    var storageAttempt by remember { mutableIntStateOf(0) }
    var lockAttempt by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun launcherUnavailable() { scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(activity.getString(R.string.widget_launcher_unavailable), withDismissAction = true, duration = SnackbarDuration.Indefinite)
    } }
    fun write(block: () -> Boolean) { storageAttempt++; storageError = !block() }
    LaunchedEffect(lock.error, lockAttempt) {
        val error = lock.error ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(activity.getString(lockErrorMessage(error)), withDismissAction = true, duration = SnackbarDuration.Indefinite)
    }
    LaunchedEffect(storageError, storageAttempt) {
        if (!storageError) return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(activity.getString(R.string.privacy_storage), withDismissAction = true, duration = SnackbarDuration.Indefinite)
    }
    ScreenScaffold(stringResource(R.string.preferences_title), onBack, snackbarHostState = snackbar) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("preferences_screen"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { ListItem(headlineContent = { Text(stringResource(R.string.diagnostics_title)) }, leadingContent = { Icon(Icons.Outlined.BugReport, null) },
                modifier = Modifier.clickable(onClick = onDiagnostics).testTag("open_diagnostics")) }
            item { ListItem(headlineContent = { Text(stringResource(R.string.updates_title)) }, leadingContent = { Icon(Icons.Outlined.SystemUpdate, null) },
                modifier = Modifier.clickable(onClick = onUpdates).testTag("open_updates")) }
            item { Text(stringResource(R.string.preferences_appearance), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(selected = options.theme == mode,
                            onClick = { write { container.preferences.setTheme(mode) } },
                            shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size), Modifier.testTag("theme_${mode.name}")) {
                            Text(stringResource(when (mode) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark }))
                        }
                    }
                }
            }
            item { SettingSwitch(R.string.theme_dynamic, options.dynamicColor, "theme_dynamic") { write { container.preferences.setDynamicColor(it) } } }
            item { HorizontalDivider(); Text(stringResource(R.string.preferences_privacy), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
            item { SettingSwitch(R.string.privacy_lock, lock.enabled, "privacy_enable", !lock.authenticating) { lockAttempt++; container.privacyLock.setEnabled(activity, it) } }
            item { Text(stringResource(R.string.privacy_disclosure), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium) }
            item { SettingSwitch(R.string.privacy_secure_screen, options.secureScreens || options.lockEnabled, "privacy_screenshots", !options.lockEnabled) { write { container.preferences.setSecureScreens(it) } } }
            item { Text(stringResource(R.string.privacy_screenshot_disclosure), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium) }
            item { HorizontalDivider(); Text(stringResource(R.string.widget_disclosure), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
            if (book != null) item {
                ListItem(headlineContent = { Text(stringResource(R.string.widget_pin)) }, supportingContent = { Text(book.name) }, leadingContent = { Icon(Icons.Outlined.Widgets, null) },
                    modifier = Modifier.clickable {
                        val manager = android.appwidget.AppWidgetManager.getInstance(activity)
                        val requested = runCatching { manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(
                            android.content.ComponentName(activity, app.omymoney.platform.widget.LedgerWidgetProvider::class.java), null,
                            app.omymoney.platform.widget.WidgetPinning.callback(activity, book.id)) }.getOrDefault(false)
                        if (!requested) launcherUnavailable()
                    }.testTag("widget_pin"))
            }
            if (book != null) item {
                ListItem(headlineContent = { Text(stringResource(R.string.shortcut_pin)) }, supportingContent = { Text(book.name) },
                    leadingContent = { Icon(Icons.Outlined.AddToHomeScreen, null) }, modifier = Modifier.clickable {
                        val requested = runCatching {
                        val manager = activity.getSystemService(android.content.pm.ShortcutManager::class.java)
                        val shortcut = android.content.pm.ShortcutInfo.Builder(activity, "new/${book.id}").setShortLabel(book.name)
                            .setLongLabel(activity.getString(R.string.new_bill) + " · " + book.name)
                            .setIcon(android.graphics.drawable.Icon.createWithResource(activity, android.R.drawable.ic_input_add))
                            .setIntent(app.omymoney.platform.security.LaunchRequest.intent(activity, book.id, true)).build()
                        manager.isRequestPinShortcutSupported && manager.requestPinShortcut(shortcut, null)
                        }.getOrDefault(false)
                        if (!requested) launcherUnavailable()
                    }.testTag("shortcut_pin"))
            }
        }
    }
}

@Composable
internal fun SettingSwitch(label: Int, checked: Boolean, tag: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(stringResource(label)) }, trailingContent = { Switch(checked, null, enabled = enabled) },
        modifier = Modifier.toggleable(checked, enabled, Role.Switch, onValueChange = onChange).testTag(tag))
}
