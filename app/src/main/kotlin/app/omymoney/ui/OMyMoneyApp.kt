package app.omymoney.ui

import app.omymoney.ui.theme.ExpressiveMotion
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import app.omymoney.AppContainer
import app.omymoney.OMyMoneyApplication
import app.omymoney.android.R
import app.omymoney.core.model.*
import app.omymoney.data.CatalogResult
import app.omymoney.feature.catalog.*
import app.omymoney.feature.bills.*
import app.omymoney.feature.settings.*
import app.omymoney.feature.automation.*
import app.omymoney.feature.budget.*
import app.omymoney.feature.schedule.*
import app.omymoney.feature.savings.*
import app.omymoney.feature.exchange.*
import app.omymoney.ui.components.*
import app.omymoney.ui.navigation.LedgerNavigation
import app.omymoney.ui.theme.DesignTokens
import app.omymoney.ui.theme.LedgerMotion
import app.omymoney.ui.theme.LedgerTheme
import app.omymoney.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

enum class AppDestination(
    val route: String,
    @param:StringRes val label: Int,
    @param:StringRes val emptyMessage: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    BILLS("bills", R.string.nav_bills, R.string.empty_bills, Icons.AutoMirrored.Outlined.ReceiptLong, Icons.AutoMirrored.Filled.ReceiptLong),
    ASSETS("assets", R.string.nav_assets, R.string.empty_assets, Icons.Outlined.AccountBalanceWallet, Icons.Filled.AccountBalanceWallet),
    STATS("stats", R.string.nav_stats, R.string.empty_stats, Icons.Outlined.BarChart, Icons.Filled.BarChart),
    SETTINGS("settings", R.string.nav_settings, R.string.local_storage, Icons.Outlined.Settings, Icons.Filled.Settings),
}

private val topLevelRoutes = AppDestination.entries.map { it.route }.toSet()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OMyMoneyApp(themeMode: ThemeMode? = null, dynamicColor: Boolean? = null, container: AppContainer? = null,
    queueRequest: Int = 0, reviewId: String? = null, onQueueConsumed: () -> Unit = {},
    launchRequest: app.omymoney.platform.security.LaunchRequest? = null, onLaunchConsumed: () -> Unit = {},
    diagnosticsRequest: Int = 0, onDiagnosticsConsumed: () -> Unit = {}) {
    val context = LocalContext.current
    val dependencies = container ?: (context.applicationContext as OMyMoneyApplication).container
    val options by dependencies.preferences.state.collectAsStateWithLifecycle()
    LedgerTheme(themeMode ?: options.theme, dynamicColor ?: options.dynamicColor) {
        app.omymoney.platform.security.PrivacyGate(dependencies.privacyLock) {
            UnlockedApp(themeMode ?: options.theme, dynamicColor ?: options.dynamicColor, dependencies,
                queueRequest, reviewId, onQueueConsumed, launchRequest, onLaunchConsumed, diagnosticsRequest, onDiagnosticsConsumed)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnlockedApp(themeMode: ThemeMode, dynamicColor: Boolean, container: AppContainer,
    queueRequest: Int, reviewId: String?, onQueueConsumed: () -> Unit,
    launchRequest: app.omymoney.platform.security.LaunchRequest?, onLaunchConsumed: () -> Unit,
    diagnosticsRequest: Int, onDiagnosticsConsumed: () -> Unit) {
    val context = LocalContext.current
    val dependencies = container
    val factory = remember(dependencies) { viewModelFactory { initializer { CatalogViewModel(dependencies.store.catalog) } } }
    val catalog: CatalogViewModel = viewModel(factory = factory)
    val undoFactory = remember(dependencies) { viewModelFactory {
        initializer { UndoViewModel(dependencies.store.ledger, createSavedStateHandle()) }
    } }
    val undo: UndoViewModel = viewModel(factory = undoFactory)
    val pendingUndo by undo.pending.collectAsStateWithLifecycle()
    val undoBusy by undo.busy.collectAsStateWithLifecycle()
    val undoNotice by undo.notice.collectAsStateWithLifecycle()
    val undoDisplayDuration = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = 10_000, containsIcons = true, containsText = true, containsControls = true) ?: 10_000L
    val state by catalog.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: AppDestination.BILLS.route
    val selected = AppDestination.entries.firstOrNull { it.route == route }
    var bookPicker by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val undoSnackbar = remember { SnackbarHostState() }
    var bottomBarHeight by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val listStateHolder = rememberSaveableStateHolder()
    fun navigate(route: String) { nav.navigate(route) { launchSingleTop = true } }
    fun selectTab(destination: AppDestination) {
        nav.navigate(destination.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    fun categoryRoute(bookId: String, id: String?, kind: BillKind, parent: String?): String =
        "category/$bookId/${id ?: "new"}?kind=${kind.name}" + (parent?.let { "&parent=$it" } ?: "")
    LaunchedEffect(pendingUndo, undoBusy) {
        val pending = pendingUndo ?: return@LaunchedEffect
        if (undoBusy) return@LaunchedEffect
        undoSnackbar.currentSnackbarData?.dismiss()
        val remaining = (pending.displayUntil - System.currentTimeMillis()).coerceAtLeast(0)
        val result = withTimeoutOrNull(remaining) {
            undoSnackbar.showSnackbar(context.getString(if (pending.failed) R.string.undo_failed else R.string.bill_deleted),
                actionLabel = context.getString(R.string.undo), withDismissAction = true, duration = SnackbarDuration.Indefinite)
        }
        if (result == SnackbarResult.ActionPerformed) undo.undo() else undo.dismiss(pending.token)
    }
    LaunchedEffect(undoNotice) {
        val notice = undoNotice ?: return@LaunchedEffect
        undoSnackbar.currentSnackbarData?.dismiss()
        undoSnackbar.showSnackbar(context.getString(if (notice == UndoNotice.RESTORED) R.string.bill_restored else R.string.undo_unavailable))
        undo.acknowledgeNotice(notice)
    }
    LedgerTheme(themeMode, dynamicColor) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expanded = maxWidth >= DesignTokens.ExpandedNavigationWidth && maxHeight >= DesignTokens.MinimumRailHeight
            Row(Modifier.fillMaxSize()) {
                if (expanded && selected != null) LedgerNavigation(selected, ::selectTab, expanded = true)
                NavHost(nav, startDestination = AppDestination.BILLS.route, modifier = Modifier.weight(1f),
                    enterTransition = LedgerMotion.enter(topLevelRoutes), exitTransition = LedgerMotion.exit(topLevelRoutes),
                    popEnterTransition = LedgerMotion.popEnter(topLevelRoutes), popExitTransition = LedgerMotion.popExit(topLevelRoutes)) {
                    AppDestination.entries.forEach { destination ->
                        composable(destination.route) { rootEntry ->
                            val currentBook = state.currentBook
                            var showFilters by rememberSaveable(currentBook?.id) { mutableStateOf(false) }
                            val billList: BillListViewModel? = if (destination == AppDestination.BILLS && currentBook != null) {
                                val listFactory = remember(dependencies, currentBook.id) { viewModelFactory {
                                    initializer { BillListViewModel(dependencies.store.ledger, createSavedStateHandle(), currentBook) }
                                } }
                                viewModel(rootEntry, key = "bill-list/${currentBook.id}", factory = listFactory)
                            } else null
                            RootScreen(destination, expanded, state, snackbar, ::selectTab, { bookPicker = true },
                                onBottomBarHeight = { bottomBarHeight = it },
                                onAddAccount = { navigate("account/new") },
                                onAddBill = { state.currentBookId?.let { navigate("bill-editor/$it/new") } },
                                actions = {
                                    billList?.let { list -> BillListActions(list,
                                        onBatch = { rootEntry.savedStateHandle["batch-filters"] = list.filters.value.bundle(); navigate("batch/${requireNotNull(currentBook).id}") },
                                        onExport = { rootEntry.savedStateHandle["exchange-filters"] = list.filters.value.bundle(); navigate("exchange/${requireNotNull(currentBook).id}") },
                                        onFilter = { showFilters = true }) }
                                    if (destination == AppDestination.STATS && currentBook != null) {
                                        LedgerIconButton(Icons.Outlined.PieChart, stringResource(R.string.budget_title),
                                            { navigate("budget/${currentBook.id}") }, Modifier.testTag("open_budget"))
                                        LedgerIconButton(Icons.Outlined.FilterList, stringResource(R.string.stats_filter),
                                            { showFilters = true }, Modifier.testTag("stats_filter"))
                                    }
                                }) {
                                when {
                                    state.startupError -> Column(Modifier.fillMaxSize().padding(24.dp),
                                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                        ErrorText(stringResource(R.string.database_open_failed))
                                        Button(catalog::initialize) { Text(stringResource(R.string.retry)) }
                                    }
                                    !state.initialized || state.currentBook == null -> LoadingState()
                                    destination == AppDestination.BILLS -> {
                                        listStateHolder.SaveableStateProvider(requireNotNull(currentBook).id) {
                                            BillListScreen(requireNotNull(billList), currentBook, catalog, state, showFilters,
                                                { showFilters = false }, { navigate("bill/$it") },
                                                header = { HomeBudgetSummary(dependencies.store, currentBook) { navigate("budget/${currentBook.id}") } })
                                        }
                                    }
                                    destination == AppDestination.ASSETS -> AccountsScreen(catalog, state, snackbar,
                                        onEdit = { navigate("account/$it") }, onOpen = { navigate("asset/$it") }, onGroups = { navigate("asset-groups") })
                                    destination == AppDestination.STATS -> {
                                        listStateHolder.SaveableStateProvider("statistics/${requireNotNull(currentBook).id}") {
                                            app.omymoney.feature.statistics.StatisticsScreen(dependencies.store, currentBook, state.accounts,
                                                showFilters, { showFilters = false }, onBill = { navigate("bill/$it") })
                                        }
                                    }
                                    else -> CatalogSettingsScreen(state, onBooks = { navigate("books") },
                                        onPreferences = { navigate("preferences") },
                                        onExchange = { state.currentBookId?.let { navigate("exchange/$it") } },
                                        onCategories = { state.currentBookId?.let { navigate("categories/$it") } }, onBackup = { navigate("backup") },
                                        onAutomation = { navigate("automation") }, onQueue = { navigate("recognition") }, onCurrencies = { navigate("currencies") },
                                        onSchedules = { state.currentBookId?.let { navigate("schedules/$it") } },
                                        onSavings = { state.currentBookId?.let { navigate("savings/$it") } },
                                        onScreenshot = { context.startActivity(android.content.Intent(context, ScreenshotImportActivity::class.java)) })
                                }
                            }
                        }
                    }
                    composable("books") { BooksScreen(catalog, state, { nav.popBackStack() }, { navigate("book/${it ?: "new"}") }) }
                    listOf("batch", "exchange").forEach { feature ->
                        composable("$feature/{bookId}") { backStack ->
                            val id = requireNotNull(backStack.arguments?.getString("bookId"))
                            val book = state.books.firstOrNull { it.id == id && !it.archived }
                            val filters = remember(backStack) {
                                val bundle = backStack.savedStateHandle.get<android.os.Bundle>("frozen-filters")
                                    ?: nav.previousBackStackEntry?.savedStateHandle?.remove<android.os.Bundle>("$feature-filters") ?: android.os.Bundle()
                                backStack.savedStateHandle["frozen-filters"] = bundle
                                BillFilters.read(bundle)
                            }
                            if (!state.initialized) LoadingState()
                            else if (book == null) ScreenScaffold(stringResource(R.string.exchange_title), { nav.popBackStack() }) {
                                EmptyState(stringResource(R.string.error_not_found), Modifier.padding(it))
                            } else if (feature == "batch") {
                                val factory = remember(dependencies, book, filters) { viewModelFactory { initializer {
                                    BatchViewModel(dependencies.store, book, filters, createSavedStateHandle())
                                } } }
                                val model: BatchViewModel = viewModel(backStack, factory = factory)
                                BatchEditScreen(model, dependencies.store) { nav.popBackStack() }
                            } else {
                                val factory = remember(dependencies, book, filters) { viewModelFactory { initializer {
                                    ExchangeViewModel(dependencies.store.billImport, dependencies.store.billExport, dependencies.store.ledger,
                                        context.contentResolver, book, filters, createSavedStateHandle())
                                } } }
                                val model: ExchangeViewModel = viewModel(backStack, factory = factory)
                                ExchangeScreen(model, dependencies.store) { nav.popBackStack() }
                            }
                        }
                    }
                    composable("savings/{bookId}") { backStack ->
                        val id = requireNotNull(backStack.arguments?.getString("bookId"))
                        val book = state.books.firstOrNull { it.id == id && !it.archived }
                        if (!state.initialized) LoadingState()
                        else if (book != null) SavingsScreen(dependencies.store, book, { nav.popBackStack() },
                            { navigate("savings-edit/$id/new") }, { navigate("savings-plan/$id/$it") })
                        else ScreenScaffold(stringResource(R.string.savings_title), { nav.popBackStack() }) { EmptyState(stringResource(R.string.error_not_found), Modifier.padding(it)) }
                    }
                    composable("savings-plan/{bookId}/{id}") { backStack ->
                        val bookId = requireNotNull(backStack.arguments?.getString("bookId"))
                        val id = requireNotNull(backStack.arguments?.getString("id"))
                        val book = state.books.firstOrNull { it.id == bookId && !it.archived }
                        if (!state.initialized) LoadingState()
                        else if (book != null) SavingsDetailScreen(dependencies.store, book, id, { nav.popBackStack() },
                            { navigate("savings-edit/$bookId/$id") }, { navigate("savings-deposit/$bookId/$id/${it ?: "new"}") }, { navigate("bill/$it") })
                        else ScreenScaffold(stringResource(R.string.savings_title), { nav.popBackStack() }) { EmptyState(stringResource(R.string.error_not_found), Modifier.padding(it)) }
                    }
                    composable("savings-edit/{bookId}/{id}") { backStack ->
                        val bookId = requireNotNull(backStack.arguments?.getString("bookId"))
                        val id = backStack.arguments?.getString("id")?.takeUnless { it == "new" }
                        val book = state.books.firstOrNull { it.id == bookId && !it.archived }
                        val factory = remember(dependencies, bookId, id) { viewModelFactory { initializer {
                            SavingsPlanEditorViewModel(dependencies.store, bookId, id, createSavedStateHandle())
                        } } }
                        val editor: SavingsPlanEditorViewModel = viewModel(backStack, factory = factory)
                        if (!state.initialized) LoadingState()
                        else if (book != null) SavingsPlanEditorScreen(editor, book, state.accounts.map { it.currency } + book.currency, { nav.popBackStack() }) { saved ->
                            nav.popBackStack()
                            if (id == null) navigate("savings-plan/$bookId/$saved")
                        }
                        else ScreenScaffold(stringResource(R.string.savings_title), { nav.popBackStack() }) { EmptyState(stringResource(R.string.error_not_found), Modifier.padding(it)) }
                    }
                    composable("savings-deposit/{bookId}/{planId}/{id}") { backStack ->
                        val bookId = requireNotNull(backStack.arguments?.getString("bookId"))
                        val planId = requireNotNull(backStack.arguments?.getString("planId"))
                        val id = backStack.arguments?.getString("id")?.takeUnless { it == "new" }
                        val book = state.books.firstOrNull { it.id == bookId && !it.archived }
                        val factory = remember(dependencies, bookId, planId, id) { viewModelFactory { initializer {
                            SavingsDepositEditorViewModel(dependencies.store, bookId, planId, id, createSavedStateHandle())
                        } } }
                        val editor: SavingsDepositEditorViewModel = viewModel(backStack, factory = factory)
                        if (!state.initialized) LoadingState()
                        else if (book != null) SavingsDepositEditorScreen(editor, dependencies.store, book, { nav.popBackStack() }) { nav.popBackStack() }
                        else ScreenScaffold(stringResource(R.string.savings_title), { nav.popBackStack() }) { EmptyState(stringResource(R.string.error_not_found), Modifier.padding(it)) }
                    }
                    composable("schedules/{bookId}") { backStack ->
                        val id = requireNotNull(backStack.arguments?.getString("bookId"))
                        val book = state.books.firstOrNull { it.id == id && !it.archived }
                        if (!state.initialized) LoadingState()
                        else if (book != null) ScheduleScreen(dependencies.store, book, { nav.popBackStack() },
                            { navigate("schedule-edit/$id/new") }, { navigate("schedule/$id/$it") })
                        else ScreenScaffold(stringResource(R.string.schedule_title), { nav.popBackStack() }) { EmptyState(stringResource(R.string.error_not_found), Modifier.padding(it)) }
                    }
                    composable("schedule/{bookId}/{id}") { backStack ->
                        val bookId = requireNotNull(backStack.arguments?.getString("bookId"))
                        val id = requireNotNull(backStack.arguments?.getString("id"))
                        val book = state.books.firstOrNull { it.id == bookId && !it.archived }
                        if (!state.initialized) LoadingState()
                        else if (book != null) ScheduleDetailScreen(dependencies.store, book, id, { nav.popBackStack() },
                            { navigate("schedule-edit/$bookId/$id") }, { navigate("schedule-review/$bookId/$it") }, { navigate("bill/$it") })
                        else ScreenScaffold(stringResource(R.string.schedule_title), { nav.popBackStack() }) { EmptyState(stringResource(R.string.error_not_found), Modifier.padding(it)) }
                    }
                    composable("schedule-edit/{bookId}/{id}") { backStack ->
                        val bookId = requireNotNull(backStack.arguments?.getString("bookId"))
                        val id = backStack.arguments?.getString("id")?.takeUnless { it == "new" }
                        val book = state.books.firstOrNull { it.id == bookId && !it.archived }
                        val editorFactory = remember(dependencies, bookId, id) { viewModelFactory { initializer {
                            ScheduleEditorViewModel(dependencies.store, bookId, id, createSavedStateHandle())
                        } } }
                        val editor: ScheduleEditorViewModel = viewModel(backStack, factory = editorFactory)
                        val categoriesFlow = remember(bookId) { dependencies.store.catalog.observeCategories(bookId, includeArchived = true) }
                        val categories by categoriesFlow.collectAsStateWithLifecycle(emptyList())
                        val billsFlow = remember(bookId) { dependencies.store.ledger.observeBills(BillQuery(bookId = bookId)) }
                        val bills by billsFlow.collectAsStateWithLifecycle(emptyList())
                        if (!state.initialized) LoadingState()
                        else if (book != null) ScheduleEditorScreen(editor, book, state.accounts, categories, bills, { nav.popBackStack() }) { saved ->
                            dependencies.checkSchedules()
                            nav.popBackStack()
                            if (id == null) navigate("schedule/$bookId/${saved.id}")
                        }
                        else ScreenScaffold(stringResource(R.string.schedule_title), { nav.popBackStack() }) { EmptyState(stringResource(R.string.error_not_found), Modifier.padding(it)) }
                    }
                    composable("schedule-review/{bookId}/{id}") { backStack ->
                        val bookId = requireNotNull(backStack.arguments?.getString("bookId"))
                        val id = requireNotNull(backStack.arguments?.getString("id"))
                        val editorFactory = remember(dependencies, bookId, id) { viewModelFactory { initializer {
                            BillEditorViewModel(dependencies.store.drafts, dependencies.store.catalog, dependencies.store.attachments,
                                dependencies.applicationScope, bookId, scheduleExecutionId = id)
                        } } }
                        val editor: BillEditorViewModel = viewModel(backStack, factory = editorFactory)
                        BillEditorScreen(editor, catalog, state, dependencies.store.attachments, onBack = { nav.popBackStack() }, onSaved = { nav.popBackStack() })
                    }
                    composable("budget/{bookId}") { backStack ->
                        val book = state.books.firstOrNull { it.id == backStack.arguments?.getString("bookId") }
                        if (!state.initialized) LoadingState()
                        else if (book != null && !book.archived) BudgetScreen(dependencies.store, book) { nav.popBackStack() }
                        else ScreenScaffold(stringResource(R.string.budget_title), { nav.popBackStack() }) { EmptyState(stringResource(R.string.error_not_found), Modifier.padding(it)) }
                    }
                    composable("currencies") { app.omymoney.feature.assets.CurrencyScreen(dependencies.store.catalog, state) { nav.popBackStack() } }
                    composable("asset-history/{id}") { backStack ->
                        app.omymoney.feature.assets.AssetHistoryScreen(requireNotNull(backStack.arguments?.getString("id")),
                            dependencies.store.assetHistory, state) { nav.popBackStack() }
                    }
                    composable("automation") { AutomationSettingsScreen(dependencies.store.automation,
                        onBack = { nav.popBackStack() }, onQueue = { navigate("recognition") }) }
                    composable("recognition") { RecognitionQueueScreen(dependencies.store.recognitionQueue, { nav.popBackStack() },
                        onReview = { navigate("recognition-review/${it.draft.bookId}/${it.id}") }, onBill = { navigate("bill/$it") }) }
                    composable("recognition-review/{bookId}/{id}") { backStack ->
                        val bookId = requireNotNull(backStack.arguments?.getString("bookId"))
                        val id = requireNotNull(backStack.arguments?.getString("id"))
                        val factory = remember(dependencies, id) { viewModelFactory { initializer {
                            BillEditorViewModel(dependencies.store.drafts, dependencies.store.catalog, dependencies.store.attachments,
                                dependencies.applicationScope, bookId, recognitionQueue = dependencies.store.recognitionQueue, recognitionId = id)
                        } } }
                        val editor: BillEditorViewModel = viewModel(backStack, factory = factory)
                        DraftReviewContent(editor, catalog, state, dependencies.store, onBack = { nav.popBackStack() },
                            onSaved = { nav.popBackStack() })
                    }
                    composable("backup") { backStack ->
                        val backupFactory = remember(dependencies) { viewModelFactory {
                            initializer { BackupViewModel(dependencies.store.backup, context.contentResolver, createSavedStateHandle()) }
                        } }
                        val backup: BackupViewModel = viewModel(backStack, factory = backupFactory)
                        val backupState by backup.state.collectAsStateWithLifecycle()
                        LaunchedEffect(backupState.notice) { if (backupState.notice == "restored") catalog.initialize() }
                        BackupScreen(backup, onBack = { nav.popBackStack() })
                    }
                    composable("bill/{id}") { backStack ->
                        val id = requireNotNull(backStack.arguments?.getString("id"))
                        val detailFactory = remember(dependencies, id) { viewModelFactory {
                            initializer { BillDetailViewModel(dependencies.store.ledger, id) }
                        } }
                        val detail: BillDetailViewModel = viewModel(backStack, factory = detailFactory)
                        BillDetailScreen(detail, catalog, state, onBack = { nav.popBackStack() },
                            onEdit = { navigate("bill-editor/${it.bookId}/${it.id}") },
                            onLinked = { navigate("linked/${it.id}") }, onSource = { navigate("bill/$it") },
                            onDebt = { navigate("debt/${it.id}") },
                            onDeleted = { nav.popBackStack(); undo.offer(it, undoDisplayDuration) }, snackbar = snackbar, attachments = dependencies.store.attachments)
                    }
                    composable("linked/{id}") { backStack ->
                        app.omymoney.feature.linked.LinkedBillScreen(dependencies.store.ledger,
                            requireNotNull(backStack.arguments?.getString("id")), { nav.popBackStack() },
                            onBill = { navigate("bill/$it") }, onCreate = { source, kind -> navigate("linked-editor/${source.bookId}/${source.id}/${kind.name}") }, snackbar = snackbar)
                    }
                    composable("asset/{id}") { backStack ->
                        val id = requireNotNull(backStack.arguments?.getString("id"))
                        app.omymoney.feature.assets.AssetDetailScreen(id, state, dependencies.store.ledger, snackbar,
                            onBack = { nav.popBackStack() }, onEdit = { navigate("account/$id") },
                            onCreditSettings = { navigate("credit/$id") }, onBill = { navigate("bill/$it") }, onHistory = { navigate("asset-history/$id") },
                            onCreate = { book, account, kind -> navigate("asset-editor/${book.id}/${account.id}/${kind.name}") })
                    }
                    composable("asset-groups") { app.omymoney.feature.assets.AssetGroupScreen(catalog, state) { nav.popBackStack() } }
                    composable("credit/{id}") { backStack ->
                        val account = state.accounts.firstOrNull { it.id == backStack.arguments?.getString("id") }
                        if (!state.initialized) LoadingState()
                        else if (account?.kind == AccountKind.CREDIT) app.omymoney.feature.assets.CreditSettingsScreen(account, catalog) { nav.popBackStack() }
                        else ScreenScaffold(stringResource(R.string.error_not_found), { nav.popBackStack() }) { EmptyState(stringResource(R.string.error_not_found), Modifier.padding(it)) }
                    }
                    composable("debt/{id}") { backStack ->
                        app.omymoney.feature.assets.DebtRecordsScreen(dependencies.store.ledger,
                            requireNotNull(backStack.arguments?.getString("id")), snackbar, { nav.popBackStack() },
                            onBill = { navigate("bill/$it") }, onCreate = { source, kind -> navigate("linked-editor/${source.bookId}/${source.id}/${kind.name}") })
                    }
                    composable("asset-editor/{bookId}/{id}/{kind}") { backStack ->
                        val book = requireNotNull(backStack.arguments?.getString("bookId"))
                        val id = requireNotNull(backStack.arguments?.getString("id"))
                        val kind = BillKind.valueOf(requireNotNull(backStack.arguments?.getString("kind")))
                        val factory = remember(dependencies, book, id, kind) { viewModelFactory { initializer {
                            BillEditorViewModel(dependencies.store.drafts, dependencies.store.catalog, dependencies.store.attachments,
                                dependencies.applicationScope, book, assetId = id, assetKind = kind)
                        } } }
                        val editor: BillEditorViewModel = viewModel(backStack, factory = factory)
                        BillEditorScreen(editor, catalog, state, dependencies.store.attachments, onBack = { nav.popBackStack() }, onSaved = { nav.popBackStack() })
                    }
                    composable("linked-editor/{bookId}/{id}/{kind}") { backStack ->
                        val bookId = requireNotNull(backStack.arguments?.getString("bookId"))
                        val source = requireNotNull(backStack.arguments?.getString("id"))
                        val kind = BillKind.valueOf(requireNotNull(backStack.arguments?.getString("kind")))
                        val factory = remember(dependencies, source, kind) { viewModelFactory { initializer {
                            BillEditorViewModel(dependencies.store.drafts, dependencies.store.catalog, dependencies.store.attachments,
                                dependencies.applicationScope, bookId, linkedSourceId = source, linkedKind = kind)
                        } } }
                        val editor: BillEditorViewModel = viewModel(backStack, factory = factory)
                        BillEditorScreen(editor, catalog, state, dependencies.store.attachments,
                            onBack = { nav.popBackStack() }, onSaved = { nav.popBackStack() })
                    }
                    composable("bill-editor/{bookId}/{id}") { backStack ->
                        val bookId = requireNotNull(backStack.arguments?.getString("bookId"))
                        val billId = backStack.arguments?.getString("id")?.takeUnless { it == "new" }
                        val editorFactory = remember(dependencies, bookId, billId) { viewModelFactory {
                            initializer { BillEditorViewModel(dependencies.store.drafts, dependencies.store.catalog,
                                dependencies.store.attachments, dependencies.applicationScope, bookId, billId) }
                        } }
                        val editor: BillEditorViewModel = viewModel(backStack, factory = editorFactory)
                        BillEditorScreen(editor, catalog, state, dependencies.store.attachments, onBack = { nav.popBackStack() }, onSaved = {
                            nav.popBackStack()
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                snackbar.showSnackbar(context.getString(R.string.saved))
                            }
                        })
                    }
                    composable("preferences") { SettingsScreen(dependencies, state.currentBook, { nav.popBackStack() },
                        { navigate("diagnostics") }, { navigate("updates") }) }
                    composable("diagnostics") { app.omymoney.feature.settings.DiagnosticScreen(dependencies.diagnostics, dependencies.debugSession) { nav.popBackStack() } }
                    composable("updates") { app.omymoney.feature.settings.UpdateScreen(dependencies.updates) { nav.popBackStack() } }
                    composable("book/{id}") { backStack ->
                        CatalogEditorScreen(catalog, state, CatalogEditorKind.BOOK, backStack.arguments?.getString("id")?.takeUnless { it == "new" },
                            onBack = { nav.popBackStack() }, onSaved = { nav.popBackStack() })
                    }
                    composable("account/{id}") { backStack ->
                        CatalogEditorScreen(catalog, state, CatalogEditorKind.ACCOUNT, backStack.arguments?.getString("id")?.takeUnless { it == "new" },
                            onBack = { nav.popBackStack() }, onSaved = { nav.popBackStack() })
                    }
                    composable("categories/{bookId}") { backStack ->
                        val book = state.books.firstOrNull { it.id == backStack.arguments?.getString("bookId") }
                        if (book == null) LoadingState() else CategoriesScreen(catalog, book, { nav.popBackStack() },
                            onEdit = { id, kind, parent -> navigate(categoryRoute(book.id, id, kind, parent)) })
                    }
                    composable("category/{bookId}/{id}?kind={kind}&parent={parent}", arguments = listOf(
                        navArgument("kind") { defaultValue = BillKind.EXPENSE.name },
                        navArgument("parent") { type = NavType.StringType; nullable = true; defaultValue = null },
                    )) { backStack ->
                        CatalogEditorScreen(catalog, state, CatalogEditorKind.CATEGORY,
                            backStack.arguments?.getString("id")?.takeUnless { it == "new" },
                            onBack = { nav.popBackStack() }, onSaved = { nav.popBackStack() },
                            bookId = backStack.arguments?.getString("bookId"),
                            initialCategoryKind = BillKind.valueOf(backStack.arguments?.getString("kind") ?: BillKind.EXPENSE.name),
                            initialParentId = backStack.arguments?.getString("parent"))
                    }
                }
            }
            val bottomPadding = with(LocalDensity.current) { if (selected != null && !expanded) bottomBarHeight.toDp() else 0.dp }
            // Undo belongs to the ledger, not to whichever feature opened its detail page.
            SnackbarHost(undoSnackbar, Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(bottom = bottomPadding).consumeWindowInsets(PaddingValues(bottom = bottomPadding))
                .navigationBarsPadding().imePadding().testTag("global_undo"))
        }
        if (bookPicker) BookPickerSheet(state, onSelect = { id ->
            scope.launch {
                val result = catalog.selectBook(id)
                bookPicker = false
                if (result !is CatalogResult.Saved) snackbar.showSnackbar(catalogErrorMessage(context, result))
            }
        }, onManage = { bookPicker = false; navigate("books") }, onDismiss = { bookPicker = false })
        LaunchedEffect(diagnosticsRequest) {
            if (diagnosticsRequest > 0) { navigate("diagnostics"); onDiagnosticsConsumed() }
        }
        LaunchedEffect(queueRequest) {
            if (queueRequest > 0) {
                nav.currentBackStackEntryFlow.first()
                val requested = try { reviewId?.let { dependencies.store.recognitionQueue.read(it) } }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    when {
                        requested?.state == app.omymoney.data.QueueState.SAVED && requested.billId != null && requested.automaticRevision != null ->
                            navigate("bill-editor/${requested.draft.bookId}/${requested.billId}")
                        requested?.state == app.omymoney.data.QueueState.SAVED && requested.billId != null -> navigate("bill/${requested.billId}")
                        requested != null && requested.state in app.omymoney.data.RecognitionQueue.ACTIVE_STATES -> navigate("recognition-review/${requested.draft.bookId}/${requested.id}")
                        else -> navigate("recognition")
                    }
                    onQueueConsumed()
                }
            }
        }
        LaunchedEffect(launchRequest, state.initialized) {
            val request = launchRequest ?: return@LaunchedEffect
            if (!state.initialized) return@LaunchedEffect
            nav.currentBackStackEntryFlow.first()
            val target = if (request.bookId == null) state.currentBook else state.books.firstOrNull { it.id == request.bookId && !it.archived }
            fun openBills() {
                // Open a fresh bills destination while keeping interrupted forms on Back.
                nav.navigate(AppDestination.BILLS.route) {
                    launchSingleTop = true
                }
            }
            fun unavailable() {
                openBills()
                scope.launch { snackbar.showSnackbar(context.getString(R.string.widget_book_unavailable)) }
            }
            val selectedBook = target?.let { catalog.selectBook(it.id) }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                if (target != null && selectedBook is CatalogResult.Saved) {
                    if (request.newBill) navigate("bill-editor/${target.id}/new") else openBills()
                } else unavailable()
                onLaunchConsumed()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootScreen(
    destination: AppDestination,
    expanded: Boolean,
    state: CatalogState,
    snackbar: SnackbarHostState,
    onSelect: (AppDestination) -> Unit,
    onBookSwitch: () -> Unit,
    onBottomBarHeight: (Int) -> Unit,
    onAddAccount: () -> Unit,
    onAddBill: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // The FAB tucks its label away while the list scrolls down and unfolds again on the way back up.
    var fabExpanded by remember { mutableStateOf(true) }
    val fabConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -8f) fabExpanded = false else if (available.y > 8f) fabExpanded = true
                return Offset.Zero
            }
        }
    }
    Scaffold(
        modifier = Modifier.nestedScroll(fabConnection).nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(title = {
                if (destination in listOf(AppDestination.BILLS, AppDestination.STATS) && state.currentBook != null) {
                    Surface(onClick = onBookSwitch, modifier = Modifier.heightIn(min = 48.dp).testTag("book_switch"), shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(state.currentBook!!.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                        }
                    }
                } else Text(stringResource(if (state.currentBook == null) R.string.app_name else destination.label),
                    style = MaterialTheme.typography.headlineSmall)
            }, actions = {
                actions()
                if (destination == AppDestination.ASSETS && state.initialized) {
                    LedgerIconButton(Icons.Outlined.Add, stringResource(R.string.new_account), onAddAccount, Modifier.testTag("add_account"))
                }
            }, scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer))
        },
        bottomBar = { if (!expanded) Box(Modifier.onSizeChanged { onBottomBarHeight(it.height) }) { LedgerNavigation(destination, onSelect) } },
        floatingActionButton = {
            AnimatedVisibility(destination == AppDestination.BILLS && state.currentBook != null,
                enter = scaleIn(ExpressiveMotion.fastSpatial()), exit = scaleOut(ExpressiveMotion.fastEffects())) {
                ExtendedFloatingActionButton(onClick = onAddBill, modifier = Modifier.testTag("add_bill"), expanded = fabExpanded,
                    containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = { Icon(Icons.Outlined.Add, stringResource(R.string.new_bill)) }, text = { Text(stringResource(R.string.new_bill)) })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding -> Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("screen_${destination.route}")) { content() } }
}
