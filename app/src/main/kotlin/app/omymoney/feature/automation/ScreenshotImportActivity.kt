package app.omymoney.feature.automation

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.omymoney.OMyMoneyApplication
import app.omymoney.MainActivity
import app.omymoney.android.R
import app.omymoney.platform.ocr.LocalTextRecognizer
import app.omymoney.ui.theme.LedgerTheme
import app.omymoney.ui.theme.ThemeMode
import app.omymoney.ui.components.*

class ScreenshotImportActivity : ComponentActivity() {
    internal lateinit var importer: ScreenshotImportViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val shared = runCatching { sharedImages(intent) }
        // External extras must not become SavedStateHandle defaults or navigation arguments.
        intent = Intent(intent).replaceExtras(Bundle())
        val container = (application as OMyMoneyApplication).container
        val store = container.store
        importer = ViewModelProvider(this, viewModelFactory { initializer {
            ScreenshotImportViewModel(store.catalog, store.recognitionQueue, LocalTextRecognizer(applicationContext)::recognize, createSavedStateHandle())
        } })[ScreenshotImportViewModel::class.java]
        if (savedInstanceState == null) {
            if (shared.isFailure) importer.rejectInput() else importer.accept(shared.getOrDefault(emptyList()))
        }
        setContent {
            val options by container.preferences.state.collectAsStateWithLifecycle()
            LedgerTheme(options.theme, options.dynamicColor) { app.omymoney.platform.security.PrivacyGate(container.privacyLock) {
            val state by importer.state.collectAsStateWithLifecycle()
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), importer::accept)
            fun close() { importer.cancel(); finish() }
            BackHandler(onBack = ::close)
            ScreenshotImportScreen(state, { picker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }, importer::start,
                importer::cancel, importer::rotate, ::close, onReview = { id ->
                    if (importer.state.value.busy) return@ScreenshotImportScreen
                    startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_QUEUE)
                        .putExtra(MainActivity.EXTRA_REVIEW_ID, id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                })
        } } }
    }
}

internal fun sharedImages(intent: Intent?): List<Uri> {
    if (intent?.action == null || intent.action == Intent.ACTION_MAIN) return emptyList()
    require(intent.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE) && intent.type?.startsWith("image/") == true)
    val streams = if (intent.action == Intent.ACTION_SEND) listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        else IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    val images = if (streams.isNotEmpty()) streams.take(11) else intent.clipData?.let { clip ->
        (0 until minOf(clip.itemCount, 11)).mapNotNull { clip.getItemAt(it).uri }
    }.orEmpty()
    require(images.isNotEmpty() && images.all { it.scheme == "content" })
    return images
}

@Composable
private fun ScreenshotImportScreen(state: ScreenshotImportState, onChoose: () -> Unit, onStart: () -> Unit,
    onCancel: () -> Unit, onRotate: (String) -> Unit, onBack: () -> Unit, onReview: (String) -> Unit) {
    ScreenScaffold(stringResource(R.string.ocr_title), onBack, actions = {
        LedgerIconButton(Icons.Outlined.FolderOpen, stringResource(R.string.ocr_choose), onChoose,
            Modifier.testTag("ocr_choose"), enabled = !state.busy)
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).testTag("screenshot_import")) {
            if (state.error != null) ErrorText(ocrError(state.error), Modifier.padding(16.dp))
            if (state.items.isEmpty()) EmptyState(stringResource(R.string.ocr_empty), Modifier.weight(1f))
            else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                    ListItem(headlineContent = { Text(stringResource(R.string.ocr_image_number, index + 1)) },
                        supportingContent = { Column {
                            Text(stringResource(when (item.stage) {
                                ScreenshotStage.READY -> R.string.ocr_ready; ScreenshotStage.READING -> R.string.ocr_reading
                                ScreenshotStage.QUEUED -> R.string.ocr_queued; ScreenshotStage.FAILED -> R.string.ocr_failed
                                ScreenshotStage.CANCELLED -> R.string.ocr_cancelled
                            }))
                            if (item.rotation != 0) Text(stringResource(R.string.ocr_rotation, item.rotation))
                            item.error?.let { ErrorText(ocrError(it)) }
                        } }, trailingContent = {
                            if (item.entryId != null) LedgerIconButton(Icons.Outlined.Edit, stringResource(R.string.ocr_review), { onReview(item.entryId) }, enabled = !state.busy)
                            else LedgerIconButton(Icons.Outlined.RotateRight, stringResource(R.string.ocr_rotate), { onRotate(item.id) }, enabled = !state.busy)
                        })
                    HorizontalDivider()
                }
            }
            if (state.busy) {
                val finished = state.items.count { it.stage in setOf(ScreenshotStage.QUEUED, ScreenshotStage.FAILED) }
                LinearProgressIndicator(progress = { finished.toFloat() / state.items.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(stringResource(R.string.ocr_progress, finished, state.items.size), Modifier.weight(1f))
                    LedgerIconButton(Icons.Outlined.Close, stringResource(R.string.cancel), onCancel)
                }
            } else Button(onStart, Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp).testTag("ocr_start"),
                enabled = state.items.any { it.stage != ScreenshotStage.QUEUED }) {
                Icon(Icons.Outlined.DocumentScanner, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ocr_start))
            }
        }
    }
}

@Composable private fun ocrError(code: String) = stringResource(when (code) {
    "batch_limit" -> R.string.ocr_batch_limit
    "empty_image" -> R.string.ocr_no_text
    "payment_failed" -> R.string.ocr_payment_failed
    "image_too_large", "size_limit", "text_limit", "invalid_ocr_document" -> R.string.ocr_size_limit
    "active_limit", "history_limit" -> R.string.ocr_queue_limit
    "storage" -> R.string.automation_settings_failed
    else -> R.string.ocr_unreadable
})
