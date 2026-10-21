package app.omymoney.feature.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.R
import app.omymoney.core.model.*
import app.omymoney.data.CatalogResult
import app.omymoney.ui.components.*
import kotlinx.coroutines.launch

@Composable
fun BookPickerSheet(state: CatalogState, onSelect: (String) -> Unit, onManage: () -> Unit, onDismiss: () -> Unit) {
    SelectionSheet(
        title = stringResource(R.string.switch_book),
        options = state.books.filterNot { it.archived }.map { SelectionOption(it.id, it.name, it.currency.code) },
        selectedId = state.currentBookId,
        onSelect = onSelect,
        onDismiss = onDismiss,
        footer = {
            TextButton(onManage, Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp)) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.manage_books))
            }
        },
    )
}

@Composable
fun CatalogSettingsScreen(state: CatalogState, onBooks: () -> Unit, onCategories: () -> Unit, onBackup: () -> Unit, onAutomation: () -> Unit, onQueue: () -> Unit, onScreenshot: () -> Unit = {}, onCurrencies: () -> Unit = {}, onSchedules: () -> Unit = {}, onSavings: () -> Unit = {}, onPreferences: () -> Unit = {}, onExchange: () -> Unit = {}) {
    LazyColumn(Modifier.testTag("catalog_settings")) {
        item { Text(stringResource(R.string.app_name), Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge) }
        item {
            ListItem(headlineContent = { Text(stringResource(R.string.preferences_title)) }, leadingContent = { Icon(Icons.Outlined.Settings, null) },
                modifier = Modifier.clickable(onClick = onPreferences).testTag("settings_preferences"))
        }
        item {
            ListItem(headlineContent = { Text(stringResource(R.string.savings_title)) }, supportingContent = { Text(state.currentBook?.name.orEmpty()) },
                leadingContent = { Icon(Icons.Outlined.Savings, null) }, modifier = Modifier.clickable(onClick = onSavings).testTag("settings_savings"))
        }
        item {
            ListItem(headlineContent = { Text(stringResource(R.string.schedule_title)) }, supportingContent = { Text(state.currentBook?.name.orEmpty()) },
                leadingContent = { Icon(Icons.Outlined.EventRepeat, null) }, modifier = Modifier.clickable(onClick = onSchedules).testTag("settings_schedules"))
        }
        item {
            ListItem(headlineContent = { Text(stringResource(R.string.currency_rates)) },
                leadingContent = { Icon(Icons.Outlined.CurrencyExchange, null) },
                modifier = Modifier.clickable(onClick = onCurrencies).testTag("settings_currencies"))
        }
        item {
            ListItem(headlineContent = { Text(stringResource(R.string.automation_title)) },
                leadingContent = { Icon(Icons.Outlined.AutoAwesomeMotion, null) },
                modifier = Modifier.clickable(onClick = onAutomation).testTag("settings_automation"))
        }
        item {
            ListItem(headlineContent = { Text(stringResource(R.string.review_queue)) },
                leadingContent = { Icon(Icons.Outlined.Inbox, null) },
                modifier = Modifier.clickable(onClick = onQueue).testTag("settings_queue"))
        }
        item {
            ListItem(headlineContent = { Text(stringResource(R.string.ocr_title)) },
                leadingContent = { Icon(Icons.Outlined.DocumentScanner, null) },
                modifier = Modifier.clickable(onClick = onScreenshot).testTag("settings_screenshot"))
        }
        item {
            ListItem(headlineContent = { Text(stringResource(R.string.manage_books)) },
                leadingContent = { Icon(Icons.AutoMirrored.Outlined.MenuBook, null) },
                modifier = Modifier.clickable(onClick = onBooks).testTag("settings_books"))
        }
        item {
            ListItem(headlineContent = { Text(stringResource(R.string.manage_categories)) },
                supportingContent = { Text(state.currentBook?.name.orEmpty()) },
                leadingContent = { Icon(Icons.Outlined.Category, null) },
                modifier = Modifier.clickable(onClick = onCategories).testTag("settings_categories"))
        }
        item {
            ListItem(headlineContent = { Text(stringResource(R.string.backup_title)) },
                leadingContent = { Icon(Icons.Outlined.Backup, null) },
                modifier = Modifier.clickable(onClick = onBackup).testTag("settings_backup"))
        }
        item {
            ListItem(headlineContent = { Text(stringResource(R.string.exchange_title)) },
                supportingContent = { Text(state.currentBook?.name.orEmpty()) },
                leadingContent = { Icon(Icons.Outlined.CurrencyExchange, null) },
                modifier = Modifier.clickable(onClick = onExchange).testTag("settings_exchange"))
        }
    }
}

@Composable
fun BooksScreen(viewModel: CatalogViewModel, state: CatalogState, onBack: () -> Unit, onEdit: (String?) -> Unit) {
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var archiveId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val active = state.books.filterNot { it.archived }
    fun mutate(block: suspend () -> CatalogResult<*>, success: String? = null) {
        scope.launch {
            val result = block()
            if (result !is CatalogResult.Saved) snackbar.showSnackbar(catalogErrorMessage(context, result))
            else if (success != null) snackbar.showSnackbar(success)
        }
    }
    ScreenScaffold(stringResource(R.string.manage_books), onBack, snackbarHostState = snackbar,
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { onEdit(null) }, modifier = Modifier.testTag("add_book"),
                icon = { Icon(Icons.Outlined.Add, stringResource(R.string.new_book)) }, text = { Text(stringResource(R.string.new_book)) })
        }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("book_list"), contentPadding = PaddingValues(bottom = 96.dp)) {
            item { ArchiveSwitch(showArchived, { showArchived = it }) }
            items(state.books.filter { showArchived || !it.archived }, key = { it.id }) { book ->
                val index = active.indexOfFirst { it.id == book.id }
                CatalogRow(book.name, if (state.currentBookId == book.id) stringResource(R.string.current_book_currency, book.currency.code) else book.currency.code,
                    Icons.AutoMirrored.Outlined.MenuBook, book.archived,
                    onEdit = { onEdit(book.id) }, onArchive = { archiveId = book.id },
                    onRestore = { mutate({ viewModel.restoreBook(book.id) }, context.getString(R.string.restored)) },
                    onMoveUp = if (index > 0) ({ mutate({ viewModel.reorderBooks(moved(active.map { it.id }, index, -1)) }) }) else null,
                    onMoveDown = if (index in 0 until active.lastIndex) ({ mutate({ viewModel.reorderBooks(moved(active.map { it.id }, index, 1)) }) }) else null)
            }
        }
    }
    state.books.firstOrNull { it.id == archiveId }?.let { book ->
        ArchiveDialog(book.name, onDismiss = { archiveId = null }, onConfirm = {
            archiveId = null
            mutate({ viewModel.archiveBook(book.id) }, context.getString(R.string.archived))
        })
    }
}

@Composable
fun AccountsScreen(viewModel: CatalogViewModel, state: CatalogState, snackbar: SnackbarHostState, onEdit: (String) -> Unit,
                   onOpen: (String) -> Unit = onEdit, onGroups: () -> Unit = {}) {
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var archiveId by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val active = state.accounts.filterNot { it.archived }.groupBy { it.group }.values.flatten()
    fun mutate(block: suspend () -> CatalogResult<*>, success: String? = null) {
        scope.launch {
            val result = block()
            if (result !is CatalogResult.Saved) snackbar.showSnackbar(catalogErrorMessage(context, result))
            else if (success != null) snackbar.showSnackbar(success)
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("account_list"), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { TextButton(onGroups, Modifier.padding(horizontal = 16.dp).testTag("asset_groups")) {
            Icon(Icons.Outlined.Folder, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.asset_groups))
        } }
        item { ArchiveSwitch(showArchived, { showArchived = it }) }
        state.accounts.filter { showArchived || !it.archived }.groupBy { it.group }.forEach { (group, members) ->
            item(key = "group/${group.orEmpty()}") { Text(group ?: stringResource(R.string.asset_ungrouped), Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall) }
            items(members, key = { it.id }) { account ->
                val index = active.indexOfFirst { it.id == account.id }
                CatalogRow(account.name, "${account.currency.code} ${account.balance.decimalText()}", Icons.Outlined.AccountBalanceWallet,
                    account.archived, onEdit = { onEdit(account.id) }, onArchive = { archiveId = account.id },
                    onOpen = { onOpen(account.id) },
                    onRestore = { mutate({ viewModel.restoreAccount(account.id) }, context.getString(R.string.restored)) },
                    onMoveUp = if (index > 0 && active[index - 1].group == account.group) ({ mutate({ viewModel.reorderAccounts(moved(active.map { it.id }, index, -1)) }) }) else null,
                    onMoveDown = if (index in 0 until active.lastIndex && active[index + 1].group == account.group) ({ mutate({ viewModel.reorderAccounts(moved(active.map { it.id }, index, 1)) }) }) else null)
            }
        }
        if (state.accounts.none { showArchived || !it.archived }) item { Text(stringResource(R.string.empty_assets), Modifier.padding(24.dp)) }
    }
    state.accounts.firstOrNull { it.id == archiveId }?.let { account ->
        ArchiveDialog(account.name, onDismiss = { archiveId = null }, onConfirm = {
            archiveId = null
            mutate({ viewModel.archiveAccount(account.id) }, context.getString(R.string.archived))
        })
    }
}

@Composable
fun CategoriesScreen(viewModel: CatalogViewModel, book: Book, onBack: () -> Unit, onEdit: (String?, BillKind, String?) -> Unit) {
    var kind by rememberSaveable { mutableStateOf(BillKind.EXPENSE) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var archiveId by rememberSaveable { mutableStateOf<String?>(null) }
    val flow = remember(viewModel, book.id, kind) { viewModel.observeCategories(book.id, kind, true) }
    val categories by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val visible = categories.filter { showArchived || !it.archived }
    val rows = visible.filter { it.parentId == null }.flatMap { parent -> listOf(parent) + visible.filter { it.parentId == parent.id } }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun mutate(block: suspend () -> CatalogResult<*>, success: String? = null) {
        scope.launch {
            val result = block()
            if (result !is CatalogResult.Saved) snackbar.showSnackbar(catalogErrorMessage(context, result))
            else if (success != null) snackbar.showSnackbar(success)
        }
    }
    ScreenScaffold(stringResource(R.string.manage_categories), onBack, snackbarHostState = snackbar,
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { onEdit(null, kind, null) }, modifier = Modifier.testTag("add_category"),
                icon = { Icon(Icons.Outlined.Add, stringResource(R.string.new_category)) }, text = { Text(stringResource(R.string.new_category)) })
        }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("category_list"), contentPadding = PaddingValues(bottom = 96.dp)) {
            item { Text(book.name, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
            item { KindSelector(kind, { kind = it }, Modifier.padding(horizontal = 16.dp)) }
            item { ArchiveSwitch(showArchived, { showArchived = it }) }
            items(rows, key = { it.id }) { category ->
                val siblings = categories.filter { !it.archived && it.parentId == category.parentId }
                val index = siblings.indexOfFirst { it.id == category.id }
                CatalogRow(category.name, category.parentId?.let { id -> categories.firstOrNull { it.id == id }?.name },
                    Icons.Outlined.Category, category.archived,
                    modifier = Modifier.padding(start = if (category.parentId == null) 0.dp else 24.dp),
                    onEdit = { onEdit(category.id, category.kind, category.parentId) }, onArchive = { archiveId = category.id },
                    onRestore = { mutate({ viewModel.restoreCategory(category.id) }, context.getString(R.string.restored)) },
                    onMoveUp = if (index > 0) ({ mutate({ viewModel.reorderCategories(book.id, kind, category.parentId, moved(siblings.map { it.id }, index, -1)) }) }) else null,
                    onMoveDown = if (index in 0 until siblings.lastIndex) ({ mutate({ viewModel.reorderCategories(book.id, kind, category.parentId, moved(siblings.map { it.id }, index, 1)) }) }) else null,
                    onAddChild = if (category.parentId == null && !category.archived) ({ onEdit(null, category.kind, category.id) }) else null)
            }
            if (rows.isEmpty()) item { Text(stringResource(R.string.empty_categories), Modifier.padding(24.dp)) }
        }
    }
    categories.firstOrNull { it.id == archiveId }?.let { category ->
        ArchiveDialog(category.name, childCategoryCount = categories.count { it.parentId == category.id && !it.archived }, onDismiss = { archiveId = null }, onConfirm = {
            archiveId = null
            mutate({ viewModel.archiveCategory(category.id) }, context.getString(R.string.archived))
        })
    }
}

@Composable
fun KindSelector(selected: BillKind, onSelect: (BillKind) -> Unit, modifier: Modifier = Modifier) {
    val kinds = listOf(BillKind.EXPENSE, BillKind.INCOME)
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        kinds.forEachIndexed { index, kind ->
            SegmentedButton(selected = selected == kind, onClick = { onSelect(kind) },
                shape = SegmentedButtonDefaults.itemShape(index, kinds.size)) {
                Text(stringResource(if (kind == BillKind.EXPENSE) R.string.expense else R.string.income))
            }
        }
    }
}

@Composable
private fun CatalogRow(
    name: String,
    subtitle: String?,
    icon: ImageVector,
    archived: Boolean,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onAddChild: (() -> Unit)? = null,
    onOpen: () -> Unit = onEdit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = { Column { subtitle?.let { Text(it) }; if (archived) Text(stringResource(R.string.archived)) } },
        leadingContent = { Icon(icon, null) },
        trailingContent = {
            Box {
                LedgerIconButton(Icons.Outlined.MoreVert, stringResource(R.string.item_actions, name), { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = { menu = false; onEdit() }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                    if (!archived) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.move_up)) }, enabled = onMoveUp != null,
                            onClick = { menu = false; onMoveUp?.invoke() }, leadingIcon = { Icon(Icons.Outlined.ArrowUpward, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.move_down)) }, enabled = onMoveDown != null,
                            onClick = { menu = false; onMoveDown?.invoke() }, leadingIcon = { Icon(Icons.Outlined.ArrowDownward, null) })
                        if (onAddChild != null) DropdownMenuItem(text = { Text(stringResource(R.string.new_subcategory)) },
                            onClick = { menu = false; onAddChild() }, leadingIcon = { Icon(Icons.Outlined.Add, null) })
                    }
                    DropdownMenuItem(text = { Text(stringResource(if (archived) R.string.restore else R.string.archive)) },
                        onClick = { menu = false; if (archived) onRestore() else onArchive() }, leadingIcon = { Icon(Icons.Outlined.Archive, null) })
                }
            }
        },
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
    )
}

@Composable
private fun ArchiveDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit, childCategoryCount: Int = 0) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.archive_title)) },
        text = { Text(if (childCategoryCount > 0) stringResource(R.string.archive_category_group_message, name, childCategoryCount)
            else stringResource(R.string.archive_message, name), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onConfirm) { Text(stringResource(R.string.archive)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}

private fun moved(ids: List<String>, index: Int, offset: Int): List<String> = ids.toMutableList().apply {
    val other = index + offset
    if (index in indices && other in indices) {
        val value = this[index]
        this[index] = this[other]
        this[other] = value
    }
}
