package app.omymoney.platform.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.OMyMoneyApplication
import app.omymoney.android.R
import app.omymoney.core.model.Book
import app.omymoney.platform.security.PrivacyGate
import app.omymoney.ui.components.*
import app.omymoney.ui.theme.LedgerTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WidgetConfigureActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val manager = AppWidgetManager.getInstance(this)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || manager.getAppWidgetInfo(id)?.provider != ComponentName(this, LedgerWidgetProvider::class.java)) {
            finish(); return
        }
        enableEdgeToEdge()
        val container = (application as OMyMoneyApplication).container
        setContent {
            val preferences by container.preferences.state.collectAsStateWithLifecycle()
            LedgerTheme(preferences.theme, preferences.dynamicColor) { PrivacyGate(container.privacyLock) {
                val books by remember { container.store.catalog.observeBooks() }.collectAsStateWithLifecycle(emptyList())
                var loading by remember { mutableStateOf(true) }
                var loadFailed by remember { mutableStateOf(false) }
                var loadAttempt by remember { mutableIntStateOf(0) }
                LaunchedEffect(loadAttempt) {
                    loading = true; loadFailed = false
                    try { container.store.catalog.ensureLocalDefaults() }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { loadFailed = true }
                    finally { loading = false }
                }
                WidgetConfigurationScreen(books, WidgetBooks(this).read(id), loading, loadFailed,
                    onRetry = { loadAttempt++ }, onBack = { finish() }, onSave = { bookId ->
                        if (!WidgetBooks(this@WidgetConfigureActivity).save(id, bookId)) false else {
                            container.widgets.refresh()
                            withContext(Dispatchers.Main.immediate) {
                                setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                finish()
                            }
                            true
                        }
                    })
            } }
        }
    }
}

@Composable
internal fun WidgetConfigurationScreen(books: List<Book>, initialSelection: String?, loading: Boolean, loadFailed: Boolean,
    onRetry: () -> Unit, onBack: () -> Unit, onSave: suspend (String) -> Boolean) {
    var selected by rememberSaveable { mutableStateOf(initialSelection) }
    var saving by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val saveError = stringResource(R.string.privacy_storage)
    val scope = rememberCoroutineScope()
    val canSave = !loading && !loadFailed && !saving && books.any { it.id == selected }
    BackHandler(saving) {}
    ScreenScaffold(stringResource(R.string.widget_choose), onBack, backEnabled = !saving, snackbarHostState = snackbar, actions = {
        LedgerIconButton(Icons.Outlined.Check, stringResource(R.string.save), {
            if (saving) return@LedgerIconButton
            val book = books.firstOrNull { it.id == selected } ?: return@LedgerIconButton
            saving = true
            snackbar.currentSnackbarData?.dismiss()
            scope.launch {
                val saved = try { onSave(book.id) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                finally { saving = false }
                if (!saved) snackbar.showSnackbar(saveError, withDismissAction = true, duration = SnackbarDuration.Indefinite)
            }
        }, Modifier.testTag("widget_config_save"), enabled = canSave)
    }) { padding ->
        if (loading) LoadingState(Modifier.fillMaxSize().padding(padding))
        else if (loadFailed) Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            ErrorText(stringResource(R.string.database_open_failed))
            Button(onRetry) { Text(stringResource(R.string.retry)) }
        }
        else LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("widget_books")) {
            items(books, key = { it.id }) { book ->
                ListItem(headlineContent = { Text(book.name) }, trailingContent = { RadioButton(selected == book.id, null) },
                    modifier = Modifier.selectable(selected == book.id, enabled = !saving, role = Role.RadioButton, onClick = { selected = book.id }).testTag("widget_book_${book.id}"))
            }
        }
    }
}
