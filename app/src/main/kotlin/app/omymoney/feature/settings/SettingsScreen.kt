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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import app.omymoney.ui.components.GroupedRow
import app.omymoney.ui.components.IconBadge
import app.omymoney.ui.components.SectionHeader
import app.omymoney.ui.components.groupedShape
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
            item { SectionHeader(stringResource(R.string.preferences_appearance)) }
            item {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 2.dp), shape = groupedShape(0, 2),
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        IconBadge(Icons.Outlined.Palette, containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                            ThemeMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(selected = options.theme == mode,
                                    onClick = { write { container.preferences.setTheme(mode) } },
                                    shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size), Modifier.testTag("theme_${mode.name}")) {
                                    Text(stringResource(when (mode) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark }))
                                }
                            }
                        }
                    }
                }
            }
            item { SettingSwitch(R.string.theme_dynamic, options.dynamicColor, "theme_dynamic", shape = groupedShape(1, 2), icon = Icons.Outlined.ColorLens) { write { container.preferences.setDynamicColor(it) } } }
            item { SectionHeader(stringResource(R.string.preferences_privacy)) }
            item { SettingSwitch(R.string.privacy_lock, lock.enabled, "privacy_enable", !lock.authenticating, shape = groupedShape(0, 2), icon = Icons.Outlined.Lock) { lockAttempt++; container.privacyLock.setEnabled(activity, it) } }
            item { SettingSwitch(R.string.privacy_secure_screen, options.secureScreens || options.lockEnabled, "privacy_screenshots", !options.lockEnabled, shape = groupedShape(1, 2), icon = Icons.Outlined.ScreenLockPortrait) { write { container.preferences.setSecureScreens(it) } } }
            item { Disclosure(stringResource(R.string.privacy_disclosure)) }
            item { Disclosure(stringResource(R.string.privacy_screenshot_disclosure)) }
            if (book != null) {
                item { SectionHeader(stringResource(R.string.preferences_widgets)) }
                item {
                    Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 2.dp)) {
                        GroupedRow(groupedShape(0, 2), headline = { Text(stringResource(R.string.widget_pin)) }, supporting = { Text(book.name) },
                            leading = { IconBadge(Icons.Outlined.Widgets, containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) },
                            modifier = Modifier.testTag("widget_pin"), onClick = {
                                val manager = android.appwidget.AppWidgetManager.getInstance(activity)
                                val requested = runCatching { manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(
                                    android.content.ComponentName(activity, app.omymoney.platform.widget.LedgerWidgetProvider::class.java), null,
                                    app.omymoney.platform.widget.WidgetPinning.callback(activity, book.id)) }.getOrDefault(false)
                                if (!requested) launcherUnavailable()
                            })
                    }
                }
                item {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        GroupedRow(groupedShape(1, 2), headline = { Text(stringResource(R.string.shortcut_pin)) }, supporting = { Text(book.name) },
                            leading = { IconBadge(Icons.Outlined.AddToHomeScreen, containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) },
                            modifier = Modifier.testTag("shortcut_pin"), onClick = {
                                val requested = runCatching {
                                val manager = activity.getSystemService(android.content.pm.ShortcutManager::class.java)
                                val shortcut = android.content.pm.ShortcutInfo.Builder(activity, "new/${book.id}").setShortLabel(book.name)
                                    .setLongLabel(activity.getString(R.string.new_bill) + " · " + book.name)
                                    .setIcon(android.graphics.drawable.Icon.createWithResource(activity, android.R.drawable.ic_input_add))
                                    .setIntent(app.omymoney.platform.security.LaunchRequest.intent(activity, book.id, true)).build()
                                manager.isRequestPinShortcutSupported && manager.requestPinShortcut(shortcut, null)
                                }.getOrDefault(false)
                                if (!requested) launcherUnavailable()
                            })
                    }
                }
            }
            item { Disclosure(stringResource(R.string.widget_disclosure)) }
            item { SectionHeader(stringResource(R.string.preferences_support)) }
            item {
                Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 2.dp)) {
                    GroupedRow(groupedShape(0, 2), headline = { Text(stringResource(R.string.diagnostics_title)) }, onClick = onDiagnostics,
                        leading = { IconBadge(Icons.Outlined.BugReport, containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) },
                        trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) }, modifier = Modifier.testTag("open_diagnostics"))
                }
            }
            item {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    GroupedRow(groupedShape(1, 2), headline = { Text(stringResource(R.string.updates_title)) }, onClick = onUpdates,
                        leading = { IconBadge(Icons.Outlined.SystemUpdate, containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) },
                        trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) }, modifier = Modifier.testTag("open_updates"))
                }
            }
        }
    }
}

@Composable
private fun Disclosure(text: String) {
    Text(text, Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SettingSwitch(label: Int, checked: Boolean, tag: String, enabled: Boolean = true, shape: Shape = MaterialTheme.shapes.large,
                           icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 2.dp), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        ListItem(headlineContent = { Text(stringResource(label)) },
            leadingContent = icon?.let { { IconBadge(it, containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) } },
            trailingContent = { Switch(checked, null, enabled = enabled, thumbContent = if (checked) ({
                Icon(Icons.Outlined.Check, contentDescription = null, Modifier.size(SwitchDefaults.IconSize)) }) else null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.toggleable(checked, enabled, Role.Switch, onValueChange = onChange).testTag(tag))
    }
}
