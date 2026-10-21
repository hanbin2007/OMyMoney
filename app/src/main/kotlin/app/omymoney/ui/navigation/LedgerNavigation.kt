package app.omymoney.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.omymoney.ui.AppDestination
import app.omymoney.ui.theme.DesignTokens

@Composable
fun LedgerNavigation(selected: AppDestination, onSelect: (AppDestination) -> Unit, expanded: Boolean = false) {
    if (expanded) {
        NavigationRail(Modifier.fillMaxHeight().testTag("navigation_rail")) {
            Column(Modifier.padding(top = DesignTokens.SectionGap), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppDestination.entries.forEach { destination ->
                    NavigationRailItem(
                        selected = selected == destination,
                        onClick = { onSelect(destination) },
                        modifier = Modifier.heightIn(min = 64.dp).testTag("nav_${destination.route}"),
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.label)) },
                    )
                }
            }
        }
    } else {
        NavigationBar(Modifier.testTag("navigation_bar")) {
            AppDestination.entries.forEach { destination ->
                NavigationBarItem(
                    selected = selected == destination,
                    onClick = { onSelect(destination) },
                    modifier = Modifier.testTag("nav_${destination.route}"),
                    icon = { Icon(destination.icon, contentDescription = null) },
                    label = { Text(stringResource(destination.label)) },
                )
            }
        }
    }
}
