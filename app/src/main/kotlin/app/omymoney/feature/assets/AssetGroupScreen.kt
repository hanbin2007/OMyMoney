package app.omymoney.feature.assets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.omymoney.android.R
import app.omymoney.data.CatalogResult
import app.omymoney.feature.catalog.CatalogState
import app.omymoney.feature.catalog.CatalogViewModel
import app.omymoney.feature.catalog.catalogErrorMessage
import app.omymoney.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun AssetGroupScreen(catalog: CatalogViewModel, state: CatalogState, onBack: () -> Unit) {
    val grouped = state.accounts.filterNot { it.archived }.groupBy { it.group }
    val groups = grouped.keys.toList()
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun move(index: Int, delta: Int) {
        if (busy) return
        val next = groups.toMutableList().apply { add(index + delta, removeAt(index)) }
        busy = true
        scope.launch(Dispatchers.Main.immediate) {
            try {
                val result = catalog.reorderAccountGroups(next)
                if (result !is CatalogResult.Saved) snackbar.showSnackbar(catalogErrorMessage(context, result))
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { snackbar.showSnackbar(context.getString(R.string.error_save))
            } finally { busy = false }
        }
    }
    ScreenScaffold(stringResource(R.string.asset_groups), onBack, backEnabled = !busy, snackbarHostState = snackbar) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("group_list"), contentPadding = PaddingValues(vertical = 16.dp)) {
            itemsIndexed(groups, key = { _, group -> group ?: "" }) { index, group ->
                val label = group ?: stringResource(R.string.asset_ungrouped)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.asset_group_count, grouped[group].orEmpty().size), style = MaterialTheme.typography.bodySmall)
                    Row {
                        LedgerIconButton(Icons.Outlined.ArrowUpward, stringResource(R.string.move_up), { move(index, -1) },
                            Modifier.testTag("group_up_${group.orEmpty()}"), enabled = !busy && index > 0)
                        LedgerIconButton(Icons.Outlined.ArrowDownward, stringResource(R.string.move_down), { move(index, 1) },
                            Modifier.testTag("group_down_${group.orEmpty()}"), enabled = !busy && index < groups.lastIndex)
                        if (group != null) LedgerIconButton(Icons.Outlined.Edit, stringResource(R.string.edit), {
                            renaming = group; name = group; error = null
                        }, Modifier.testTag("group_rename_$group"), enabled = !busy)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    val previous = renaming
    if (previous != null) AlertDialog(onDismissRequest = { if (!busy) renaming = null }, title = { Text(stringResource(R.string.asset_groups)) },
        text = { OutlinedTextField(name, { name = it; error = null }, Modifier.fillMaxWidth().testTag("group_name"), enabled = !busy,
            label = { Text(stringResource(R.string.asset_group)) }, singleLine = true, isError = error != null,
            supportingText = error?.let { { ErrorText(it) } }) },
        confirmButton = { TextButton({
            if (!busy) {
                busy = true
                scope.launch(Dispatchers.Main.immediate) {
                    try {
                        val result = catalog.renameAccountGroup(previous, name)
                        if (result is CatalogResult.Saved) renaming = null else error = catalogErrorMessage(context, result)
                    } catch (cancel: CancellationException) { throw cancel
                    } catch (_: Exception) { error = context.getString(R.string.error_save)
                    } finally { busy = false }
                }
            }
        }, Modifier.testTag("group_save"), enabled = !busy) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton({ renaming = null }, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
}
