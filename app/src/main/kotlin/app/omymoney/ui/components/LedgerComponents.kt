package app.omymoney.ui.components

import app.omymoney.ui.theme.ExpressiveMotion
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.omymoney.android.R
import app.omymoney.ui.theme.DesignTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick, modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), enabled = enabled) {
            Icon(icon, contentDescription = label)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    // The bar tints itself once content scrolls beneath it, so the hierarchy stays readable without a divider.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
                LedgerIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), onBack,
                    Modifier.testTag("screen_back"), enabled = backEnabled)
            }, actions = actions, scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer))
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = floatingActionButton,
        content = content,
    )
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.Inbox) {
    Box(modifier.fillMaxSize().padding(DesignTokens.SectionGap), contentAlignment = Alignment.Center) {
        EmptyMessage(text, icon = icon)
    }
}

/** Friendly empty-content illustration: an icon on a large wavy badge above the message. */
@Composable
fun EmptyMessage(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.Inbox, action: (@Composable () -> Unit)? = null) {
    EnterAnimated(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            IconBadge(icon, size = 96.dp, shape = ExpressiveShapes.Cookie, containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            action?.invoke()
        }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(strokeCap = StrokeCap.Round, trackColor = MaterialTheme.colorScheme.secondaryContainer)
    }
}

@Composable
fun ErrorText(message: String, modifier: Modifier = Modifier) {
    Text(message, modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium)
}

data class SelectionOption(val id: String, val title: String, val subtitle: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionSheet(
    title: String,
    options: List<SelectionOption>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
    embedded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = remember(search, options) { options.filter { it.title.contains(search.trim(), true) || it.subtitle?.contains(search.trim(), true) == true } }
    @Composable fun Content() {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        Column(Modifier.fillMaxWidth().imePadding()) {
            if (!embedded) Text(title, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = search, onValueChange = { search = it }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() }),
                label = { Text(stringResource(R.string.search)) }, shape = CircleShape,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = { if (search.isNotEmpty()) LedgerIconButton(Icons.Outlined.Close, stringResource(R.string.clear_search), { search = "" }) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("selection_search"),
            )
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().padding(top = 8.dp)) {
                items(filtered, key = { it.id }) { option ->
                    val isSelected = selectedId == option.id
                    val container by animateColorAsState(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        ExpressiveMotion.defaultEffects(), label = "option")
                    ListItem(
                        headlineContent = { Text(option.title) },
                        supportingContent = option.subtitle?.let { subtitle -> { Text(subtitle) } },
                        leadingContent = { RadioButton(selected = isSelected, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.animateItem().padding(horizontal = 12.dp, vertical = 1.dp).clip(MaterialTheme.shapes.large)
                            .background(container)
                            .selectable(isSelected, role = Role.RadioButton, onClick = { onSelect(option.id) })
                            .heightIn(min = 56.dp).testTag("option_${option.id}"),
                    )
                }
                if (filtered.isEmpty()) item { EmptyMessage(stringResource(R.string.no_matches), icon = Icons.Outlined.SearchOff) }
            }
            footer?.invoke()
            Spacer(Modifier.height(16.dp))
        }
    }
    if (embedded) {
        BackHandler(onBack = onDismiss)
        Surface(modifier.fillMaxSize()) { Content() }
    } else ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) { Content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerField(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier, isError: Boolean = false, error: String? = null, enabled: Boolean = true) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    ExposedDropdownMenuBox(expanded = false, onExpandedChange = { if (enabled) { keyboard?.hide(); focus.clearFocus(); onClick() } }, modifier = modifier) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true,
            label = { Text(label) }, trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
            isError = isError || error != null, maxLines = 3, enabled = enabled,
            supportingText = error?.let { message -> { ErrorText(message) } },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = enabled).fillMaxWidth(),
        )
    }
}

@Composable
fun ArchiveSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(MaterialTheme.shapes.large)
        .background(MaterialTheme.colorScheme.surfaceContainer).heightIn(min = DesignTokens.MinimumTouchTarget)
        .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.show_archived), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onCheckedChange = null, thumbContent = if (checked) ({
            Icon(Icons.Outlined.Check, contentDescription = null, Modifier.size(SwitchDefaults.IconSize))
        }) else null)
    }
}

@Composable
fun DiscardDialog(onDiscard: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.discard_title)) },
        text = { Text(stringResource(R.string.discard_message)) },
        confirmButton = { TextButton(onDiscard) { Text(stringResource(R.string.discard_changes)) } },
        dismissButton = { TextButton(onKeep) { Text(stringResource(R.string.keep_editing)) } },
    )
}
