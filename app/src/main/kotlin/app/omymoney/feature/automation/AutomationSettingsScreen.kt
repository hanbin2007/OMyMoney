package app.omymoney.feature.automation

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SettingsAccessibility
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.R
import app.omymoney.core.recognition.RuleRegistry
import app.omymoney.data.AutomationSettingsRepository
import app.omymoney.data.AutomationSettingsState
import app.omymoney.data.DedupPresentation
import app.omymoney.ui.components.SelectionOption
import app.omymoney.ui.components.SelectionSheet
import app.omymoney.platform.accessibility.*
import app.omymoney.platform.notification.*
import app.omymoney.OMyMoneyApplication
import app.omymoney.ui.components.ScreenScaffold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AutomationSettingsScreen(repository: AutomationSettingsRepository, onBack: () -> Unit, onQueue: () -> Unit = {}) {
    val flow = remember(repository) { repository.observe() }
    val state by flow.collectAsStateWithLifecycle(AutomationSettingsState())
    val runtime by AccessibilityStatus.runtime.collectAsStateWithLifecycle()
    val notificationRuntime by NotificationCaptureStatus.runtime.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationCounts by (context.applicationContext as OMyMoneyApplication).container.notificationCounts.state.collectAsStateWithLifecycle()
    val notifications = remember(context) { RecognitionNotification(context) }
    val permissionPreferences = remember(context) { context.getSharedPreferences("notification_permission", android.content.Context.MODE_PRIVATE) }
    var permissionAsked by remember { mutableStateOf(permissionPreferences.getBoolean("asked", false)) }
    var notificationsAllowed by remember { mutableStateOf(notifications.allowed()) }
    var notificationGranted by remember { mutableStateOf(notifications.permissionGranted()) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        (context.applicationContext as OMyMoneyApplication).container.refreshNotificationPermission()
        notificationsAllowed = notifications.allowed()
        notificationGranted = notifications.permissionGranted()
    }
    var granted by remember { mutableStateOf(AccessibilityStatus.granted(context)) }
    var notificationAccess by remember { mutableStateOf(NotificationCaptureStatus.granted(context)) }
    var consent by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var dedupPicker by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LifecycleResumeEffect(runtime to notificationRuntime.connected) {
        granted = AccessibilityStatus.granted(context)
        notificationAccess = NotificationCaptureStatus.granted(context)
        notificationsAllowed = notifications.allowed()
        notificationGranted = notifications.permissionGranted()
        (context.applicationContext as OMyMoneyApplication).container.refreshNotificationPermission()
        onPauseOrDispose { }
    }
    DisposableEffect(context) {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val listener = AccessibilityManager.AccessibilityStateChangeListener { granted = AccessibilityStatus.granted(context) }
        manager.addAccessibilityStateChangeListener(listener)
        onDispose { manager.removeAccessibilityStateChangeListener(listener) }
    }
    fun mutate(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { snackbar.showSnackbar(context.getString(R.string.automation_settings_failed)) }
            finally { busy = false }
        }
    }
    val settings = state.settings
    val status = when {
        state.corrupt -> R.string.automation_settings_corrupt
        !settings.enabled -> R.string.automation_disabled
        settings.paused -> R.string.automation_paused
        (!granted || !runtime.available) && notificationRuntime.connected -> when {
            notificationRuntime.error != null -> R.string.automation_capture_error
            settings.packages.isEmpty() -> R.string.automation_no_apps
            else -> R.string.notification_capture_active
        }
        !granted -> R.string.automation_not_granted
        !runtime.available -> R.string.automation_binding
        !runtime.unlocked -> R.string.automation_locked
        runtime.error != null -> R.string.automation_capture_error
        settings.packages.isEmpty() -> R.string.automation_no_apps
        else -> R.string.automation_active
    }
    ScreenScaffold(stringResource(R.string.automation_title), onBack, snackbarHostState = snackbar) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("automation_settings"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { ListItem(headlineContent = { Text(stringResource(status)) }, modifier = Modifier.testTag("automation_status")) }
            item { ToggleSetting(stringResource(R.string.automation_enable), settings.enabled, !busy, "automation_enable") {
                if (it) consent = true else mutate { repository.setEnabled(false) }
            } }
            item { ToggleSetting(stringResource(R.string.automation_pause), settings.paused, !busy && settings.enabled, "automation_pause") {
                mutate { repository.setPaused(it) }
            } }
            item {
                FilledTonalButton(onClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).putExtra(Intent.EXTRA_COMPONENT_NAME,
                        ComponentName(context, LedgerAccessibilityService::class.java))
                    try { context.startActivity(intent) }
                    catch (_: Exception) { scope.launch { snackbar.showSnackbar(context.getString(R.string.automation_settings_failed)) } }
                }, modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp).testTag("accessibility_settings")) {
                    Icon(Icons.Outlined.SettingsAccessibility, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.automation_permission))
                }
            }
            item {
                ListItem(headlineContent = { Text(stringResource(R.string.notification_capture_title)) },
                    supportingContent = { Text(stringResource(when {
                        !notificationAccess -> R.string.notification_capture_not_granted
                        notificationRuntime.error != null -> R.string.notification_capture_error
                        !notificationRuntime.connected -> R.string.notification_capture_binding
                        !settings.enabled -> R.string.automation_disabled
                        settings.paused -> R.string.automation_paused
                        notificationRuntime.error != null -> R.string.automation_capture_error
                        settings.packages.isEmpty() -> R.string.automation_no_apps
                        else -> R.string.notification_capture_active
                    })) }, modifier = Modifier.testTag("notification_capture_status"))
                FilledTonalButton(onClick = {
                    try { NotificationCaptureStatus.openSettings(context) }
                    catch (_: Exception) { scope.launch { snackbar.showSnackbar(context.getString(R.string.automation_settings_failed)) } }
                }, modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp).testTag("notification_capture_settings")) {
                    Icon(Icons.Outlined.Notifications, null); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.notification_capture_permission))
                }
                Text(stringResource(R.string.notification_capture_counts, notificationCounts.received, notificationCounts.candidates,
                    notificationCounts.filtered, notificationCounts.dropped), Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("notification_capture_counts"), style = MaterialTheme.typography.bodySmall)
            }
            item { Text(stringResource(R.string.automation_apps), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
            items(RuleRegistry.supportedPackages.toList(), key = { it }) { name ->
                ListItem(headlineContent = { Text(applicationLabel(name)) },
                    trailingContent = { Checkbox(name in settings.packages, onCheckedChange = null, enabled = !busy) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("automation_app_$name")
                        .toggleable(name in settings.packages, enabled = !busy, role = Role.Checkbox) { value ->
                            mutate { repository.setPackage(name, value) }
                        })
            }
            item { TextButton(onQueue, Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp).testTag("automation_queue")) { Text(stringResource(R.string.review_queue)) } }
            item {
                Text(stringResource(if (notificationsAllowed) R.string.review_notifications_enabled else R.string.review_notifications_disabled),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("notification_status"))
                TextButton({
                    if (notificationAction(Build.VERSION.SDK_INT, notificationGranted, permissionAsked) == NotificationAction.REQUEST) {
                        permissionPreferences.edit().putBoolean("asked", true).apply()
                        permissionAsked = true
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        val intent = if (notificationGranted && notifications.channelBlocked()) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, RecognitionNotification.CHANNEL)
                        else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        try { context.startActivity(intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
                        catch (_: Exception) { scope.launch { snackbar.showSnackbar(context.getString(R.string.automation_settings_failed)) } }
                    }
                }, Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp).testTag("automation_notifications")) {
                    Text(stringResource(if (notificationAction(Build.VERSION.SDK_INT, notificationGranted, permissionAsked) == NotificationAction.REQUEST)
                        R.string.review_notification_permission else R.string.review_notification_settings))
                }
            }
            item { TextButton({ dedupPicker = true }, Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.review_dedup_setting, dedupLabel(settings.dedupPresentation)))
            } }
        }
    }
    if (dedupPicker) SelectionSheet(stringResource(R.string.review_dedup_title), DedupPresentation.entries.map {
        SelectionOption(it.name, dedupLabel(it))
    }, settings.dedupPresentation.name, onSelect = { name ->
        dedupPicker = false; mutate { repository.setDedupPresentation(DedupPresentation.valueOf(name)) }
    }, onDismiss = { dedupPicker = false })
    if (consent) AlertDialog(onDismissRequest = { consent = false },
        title = { Text(stringResource(R.string.automation_consent_title)) },
        text = { Text(stringResource(R.string.automation_service_description), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { consent = false; mutate { repository.setEnabled(true) } }, Modifier.testTag("automation_consent")) {
            Text(stringResource(R.string.automation_consent_accept))
        } }, dismissButton = { TextButton(onClick = { consent = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun ToggleSetting(label: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(label) }, trailingContent = { Switch(checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange))
}

fun applicationLabel(name: String): String = when (name) {
    "com.tencent.mm" -> "微信"
    "com.eg.android.AlipayGphone" -> "支付宝"
    "com.xunmeng.pinduoduo" -> "拼多多"
    "com.unionpay" -> "云闪付"
    "com.jingdong.app.mall" -> "京东"
    "com.sankuai.meituan" -> "美团"
    "com.sankuai.meituan.takeoutnew" -> "美团外卖"
    "com.ss.android.ugc.aweme" -> "抖音"
    "OCR" -> "截图"
    "MANUAL" -> "手工输入"
    "ACCESSIBILITY" -> "无障碍"
    "NOTIFICATION" -> "交易通知"
    "IMPORT" -> "导入"
    "SCHEDULE" -> "周期账单"
    else -> name
}

@Composable private fun dedupLabel(value: DedupPresentation): String = stringResource(when (value) {
    DedupPresentation.IGNORE_PROMPT -> R.string.review_ignore_prompt
    DedupPresentation.CONFIRM -> R.string.review_confirm_prompt
    DedupPresentation.SKIP_SIMILARITY -> R.string.review_skip_similarity
})
