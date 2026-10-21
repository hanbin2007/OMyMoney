package app.omymoney.feature.statistics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.omymoney.android.R
import app.omymoney.core.ledger.EconomicKind
import app.omymoney.core.model.*
import app.omymoney.core.statistics.StatisticsFilter
import app.omymoney.feature.bills.billKindLabel
import app.omymoney.ui.components.*

internal fun statisticsCategoryName(category: Category, categories: List<Category>) =
    listOfNotNull(category.parentId?.let { id -> categories.firstOrNull { it.id == id }?.name }, category.name).joinToString(" / ")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun StatisticsFilterSheet(initial: StatisticsFilter, accounts: List<AssetAccount>, categories: List<Category>,
    onApply: (StatisticsFilter) -> Unit, onDismiss: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf(initial.kind?.name) }
    var account by rememberSaveable { mutableStateOf(initial.accountId) }
    var category by rememberSaveable { mutableStateOf(initial.categoryId) }
    var tag by rememberSaveable { mutableStateOf(initial.tag) }
    var search by rememberSaveable { mutableStateOf(initial.search) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    if (picker == null) ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.stats_filter), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton({ onApply(StatisticsFilter(kind?.let(EconomicKind::valueOf), account, category, tag, search)) },
                    Modifier.testTag("stats_filter_apply")) { Text(stringResource(R.string.apply)) }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind == null, { kind = null }, label = { Text(stringResource(R.string.all_kinds)) })
                    EconomicKind.entries.forEach { value -> FilterChip(kind == value.name, { kind = value.name }, label = { Text(economicLabel(value)) }) }
                }
                PickerField(stringResource(R.string.account), accounts.firstOrNull { it.id == account }?.name ?: stringResource(R.string.all_accounts),
                    { picker = "account" }, Modifier.testTag("stats_filter_account"))
                PickerField(stringResource(R.string.category), categories.firstOrNull { it.id == category }?.let { statisticsCategoryName(it, categories) }
                    ?: stringResource(R.string.all_categories), { picker = "category" }, Modifier.testTag("stats_filter_category"))
                OutlinedTextField(tag, { tag = it }, Modifier.fillMaxWidth().testTag("stats_filter_tag"), label = { Text(stringResource(R.string.filter_tag)) }, singleLine = true)
                OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().testTag("stats_filter_search"), label = { Text(stringResource(R.string.search_notes)) }, singleLine = true)
            }
        }
    } else {
        val isAccount = picker == "account"
        val options = listOf(SelectionOption("all", stringResource(if (isAccount) R.string.all_accounts else R.string.all_categories))) +
            if (isAccount) accounts.map { SelectionOption(it.id, if (it.archived) stringResource(R.string.archived_name, it.name) else it.name) }
            else categories.map { SelectionOption(it.id, if (it.archived) stringResource(R.string.archived_name, it.name) else it.name,
                listOfNotNull(billKindLabel(it.kind), it.parentId?.let { id -> categories.firstOrNull { parent -> parent.id == id }?.name }).joinToString(" · ")) }
        SelectionSheet(stringResource(if (isAccount) R.string.select_account else R.string.select_category), options,
            (if (isAccount) account else category) ?: "all", onSelect = {
                if (isAccount) account = it.takeUnless { value -> value == "all" } else category = it.takeUnless { value -> value == "all" }
                picker = null
            }, onDismiss = { picker = null })
    }
}
