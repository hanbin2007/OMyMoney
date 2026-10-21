package app.omymoney.feature.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import app.omymoney.ui.theme.LocalLedgerColors
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

private data class HubEntry(val title: Int, val icon: ImageVector, val tag: String, val onClick: () -> Unit, val subtitle: String? = null)

@Composable
fun CatalogSettingsScreen(state: CatalogState, onBooks: () -> Unit, onCategories: () -> Unit, onBackup: () -> Unit, onAutomation: () -> Unit, onQueue: () -> Unit, onScreenshot: () -> Unit = {}, onCurrencies: () -> Unit = {}, onSchedules: () -> Unit = {}, onSavings: () -> Unit = {}, onPreferences: () -> Unit = {}, onExchange: () -> Unit = {}) {
    val book = state.currentBook?.name.orEmpty()
    val sections = listOf(
        R.string.settings_section_ledger to listOf(
            HubEntry(R.string.savings_title, Icons.Outlined.Savings, "settings_savings", onSavings, book),
            HubEntry(R.string.schedule_title, Icons.Outlined.EventRepeat, "settings_schedules", onSchedules, book),
            HubEntry(R.string.currency_rates, Icons.Outlined.CurrencyExchange, "settings_currencies", onCurrencies)),
        R.string.settings_section_automation to listOf(
            HubEntry(R.string.automation_title, Icons.Outlined.AutoAwesomeMotion, "settings_automation", onAutomation),
            HubEntry(R.string.review_queue, Icons.Outlined.Inbox, "settings_queue", onQueue),
            HubEntry(R.string.ocr_title, Icons.Outlined.DocumentScanner, "settings_screenshot", onScreenshot)),
        R.string.settings_section_data to listOf(
            HubEntry(R.string.manage_books, Icons.AutoMirrored.Outlined.MenuBook, "settings_books", onBooks),
            HubEntry(R.string.manage_categories, Icons.Outlined.Category, "settings_categories", onCategories, book),
            HubEntry(R.string.backup_title, Icons.Outlined.Backup, "settings_backup", onBackup),
            HubEntry(R.string.exchange_title, Icons.Outlined.ImportExport, "settings_exchange", onExchange, book)),
        R.string.settings_section_app to listOf(
            HubEntry(R.string.preferences_title, Icons.Outlined.Tune, "settings_preferences", onPreferences)),
    )
    val accents = LocalLedgerColors.current.accents
    LazyColumn(Modifier.fillMaxSize().testTag("catalog_settings"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "hero") {
            EnterAnimated(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                HeroCard(containerColor = MaterialTheme.colorScheme.tertiaryContainer, accentColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        IconBadge(Icons.Outlined.AccountBalanceWallet, size = 56.dp, shape = ExpressiveShapes.Cookie,
                            containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.settings_tagline), style = MaterialTheme.typography.bodyMedium)
                            if (book.isNotEmpty()) Text(book, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        var accentIndex = 0
        sections.forEach { (title, entries) ->
            item(key = "section/$title") { SectionHeader(stringResource(title)) }
            val tinted = entries.map { it to accents[accentIndex++ % accents.size] }
            groupedItems(tinted, key = { it.first.tag }) { (entry, accent), shape ->
                GroupedRow(shape, onClick = entry.onClick, modifier = Modifier.testTag(entry.tag),
                    headline = { Text(stringResource(entry.title)) },
                    supporting = entry.subtitle?.takeIf(String::isNotEmpty)?.let { subtitle -> { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    leading = { IconBadge(entry.icon, containerColor = accent.first, contentColor = accent.second) },
                    trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) })
            }
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
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("book_list"), contentPadding = PaddingValues(bottom = 112.dp)) {
            item { ArchiveSwitch(showArchived, { showArchived = it }) }
            groupedItems(state.books.filter { showArchived || !it.archived }, key = { it.id }) { book, shape ->
                val index = active.indexOfFirst { it.id == book.id }
                CatalogRow(shape, book.name, if (state.currentBookId == book.id) stringResource(R.string.current_book_currency, book.currency.code) else book.currency.code,
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
    LazyColumn(Modifier.fillMaxSize().testTag("account_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
        val live = state.accounts.filterNot { it.archived }
        if (live.isNotEmpty()) item(key = "overview") { AssetsOverview(live) }
        item(key = "tools") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onGroups, Modifier.testTag("asset_groups")) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.asset_groups))
                }
            }
        }
        item(key = "archived") { ArchiveSwitch(showArchived, { showArchived = it }) }
        state.accounts.filter { showArchived || !it.archived }.groupBy { it.group }.forEach { (group, members) ->
            item(key = "group/${group.orEmpty()}") { SectionHeader(group ?: stringResource(R.string.asset_ungrouped), Modifier.animateItem()) }
            groupedItems(members, key = { it.id }) { account, shape ->
                val index = active.indexOfFirst { it.id == account.id }
                CatalogRow(shape, account.name, "${account.currency.code} ${account.balance.decimalText()}", accountKindIcon(account.kind),
                    account.archived, onEdit = { onEdit(account.id) }, onArchive = { archiveId = account.id },
                    onOpen = { onOpen(account.id) }, money = true, negative = account.balance.minor < 0,
                    tint = LocalLedgerColors.current.accents.let { it[account.kind.ordinal % it.size] },
                    onRestore = { mutate({ viewModel.restoreAccount(account.id) }, context.getString(R.string.restored)) },
                    onMoveUp = if (index > 0 && active[index - 1].group == account.group) ({ mutate({ viewModel.reorderAccounts(moved(active.map { it.id }, index, -1)) }) }) else null,
                    onMoveDown = if (index in 0 until active.lastIndex && active[index + 1].group == account.group) ({ mutate({ viewModel.reorderAccounts(moved(active.map { it.id }, index, 1)) }) }) else null)
            }
        }
        if (state.accounts.none { showArchived || !it.archived }) item { EmptyMessage(stringResource(R.string.empty_assets), icon = Icons.Outlined.AccountBalanceWallet) }
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
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("category_list"), contentPadding = PaddingValues(bottom = 112.dp)) {
            item { Text(book.name, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { KindSelector(kind, { kind = it }, Modifier.padding(horizontal = 16.dp)) }
            item { ArchiveSwitch(showArchived, { showArchived = it }) }
            groupedItems(rows, key = { it.id }) { category, shape ->
                val siblings = categories.filter { !it.archived && it.parentId == category.parentId }
                val index = siblings.indexOfFirst { it.id == category.id }
                CatalogRow(shape, category.name, category.parentId?.let { id -> categories.firstOrNull { it.id == id }?.name },
                    Icons.Outlined.Category, category.archived, monogram = true,
                    modifier = Modifier.padding(start = if (category.parentId == null) 0.dp else 24.dp),
                    onEdit = { onEdit(category.id, category.kind, category.parentId) }, onArchive = { archiveId = category.id },
                    onRestore = { mutate({ viewModel.restoreCategory(category.id) }, context.getString(R.string.restored)) },
                    onMoveUp = if (index > 0) ({ mutate({ viewModel.reorderCategories(book.id, kind, category.parentId, moved(siblings.map { it.id }, index, -1)) }) }) else null,
                    onMoveDown = if (index in 0 until siblings.lastIndex) ({ mutate({ viewModel.reorderCategories(book.id, kind, category.parentId, moved(siblings.map { it.id }, index, 1)) }) }) else null,
                    onAddChild = if (category.parentId == null && !category.archived) ({ onEdit(null, category.kind, category.id) }) else null)
            }
            if (rows.isEmpty()) item { EmptyMessage(stringResource(R.string.empty_categories), icon = Icons.Outlined.Category) }
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
    shape: Shape,
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
    monogram: Boolean = false,
    money: Boolean = false,
    negative: Boolean = false,
    tint: Pair<Color, Color>? = null,
) {
    var menu by remember { mutableStateOf(false) }
    GroupedRow(
        shape = shape,
        onClick = onOpen,
        containerColor = if (archived) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
        headline = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supporting = { Column {
            subtitle?.let {
                if (money) MoneyText(it, style = MaterialTheme.typography.titleMedium,
                    color = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                else Text(it)
            }
            if (archived) Text(stringResource(R.string.archived), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        } },
        leading = { if (monogram) MonogramBadge(name) else tint?.let { (container, content) -> IconBadge(icon, containerColor = container, contentColor = content) } ?: IconBadge(icon) },
        trailing = {
            Box {
                LedgerIconButton(Icons.Outlined.MoreVert, stringResource(R.string.item_actions, name), { menu = true })
                DropdownMenu(menu, { menu = false }, shape = MaterialTheme.shapes.large) {
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
        modifier = modifier,
    )
}

/** Net balance per currency across live accounts; currencies are never mixed without a rate. */
@Composable
private fun AssetsOverview(accounts: List<AssetAccount>) {
    val totals = remember(accounts) {
        accounts.groupBy { it.currency }.mapNotNull { (currency, members) ->
            runCatching { members.map { it.balance }.reduce { a, b -> a + b } }.getOrNull()?.let { currency to it }
        }
    }
    EnterAnimated(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        HeroCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconBadge(Icons.Outlined.AccountBalance, size = 32.dp, shape = ExpressiveShapes.Sunny,
                    containerColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f), contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(stringResource(R.string.assets_overview), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.assets_account_count, accounts.size), style = MaterialTheme.typography.labelMedium)
            }
            totals.forEachIndexed { index, (currency, total) ->
                MoneyText("${currency.code} ${total.decimalText()}", Modifier.fillMaxWidth().padding(top = if (index == 0) 4.dp else 0.dp),
                    style = if (index == 0) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleLarge,
                    color = if (total.minor < 0) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        }
    }
}

private fun accountKindIcon(kind: AccountKind): ImageVector = when (kind) {
    AccountKind.CASH -> Icons.Outlined.Payments
    AccountKind.DEBIT -> Icons.Outlined.AccountBalance
    AccountKind.CREDIT -> Icons.Outlined.CreditCard
    AccountKind.WALLET -> Icons.Outlined.AccountBalanceWallet
    AccountKind.INVESTMENT -> Icons.AutoMirrored.Outlined.ShowChart
    AccountKind.OTHER -> Icons.Outlined.Wallet
    AccountKind.LIABILITY -> Icons.Outlined.MoneyOff
    AccountKind.RECEIVABLE -> Icons.Outlined.RequestQuote
}

@Composable
private fun ArchiveDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit, childCategoryCount: Int = 0) {
    AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
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
