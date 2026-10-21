package app.omymoney.ui.navigation

import app.omymoney.ui.theme.ExpressiveMotion
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.omymoney.ui.AppDestination
import app.omymoney.ui.theme.DesignTokens

@Composable
fun LedgerNavigation(selected: AppDestination, onSelect: (AppDestination) -> Unit, expanded: Boolean = false) {
    if (expanded) {
        NavigationRail(Modifier.fillMaxHeight().testTag("navigation_rail"), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(top = DesignTokens.SectionGap), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppDestination.entries.forEach { destination ->
                    NavigationRailItem(
                        selected = selected == destination,
                        onClick = { onSelect(destination) },
                        modifier = Modifier.heightIn(min = 64.dp).testTag("nav_${destination.route}"),
                        icon = { DestinationIcon(destination, selected == destination) },
                        label = { DestinationLabel(destination, selected == destination) },
                        colors = NavigationRailItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer),
                    )
                }
            }
        }
    } else {
        NavigationBar(Modifier.testTag("navigation_bar"), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            AppDestination.entries.forEach { destination ->
                NavigationBarItem(
                    selected = selected == destination,
                    onClick = { onSelect(destination) },
                    modifier = Modifier.testTag("nav_${destination.route}"),
                    icon = { DestinationIcon(destination, selected == destination) },
                    label = { DestinationLabel(destination, selected == destination) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer),
                )
            }
        }
    }
}

/** Selected destinations swap to their filled glyph and pop with a small overshooting spring. */
@Composable
private fun DestinationIcon(destination: AppDestination, selected: Boolean) {
    val pop = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected) {
            pop.snapTo(0.78f)
            pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
    val effects = ExpressiveMotion.fastEffects<Float>()
    AnimatedContent(selected, Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value },
        transitionSpec = { (fadeIn(effects) + scaleIn(effects, initialScale = 0.8f)) togetherWith fadeOut(effects) },
        label = "nav-icon") { filled ->
        Icon(if (filled) destination.selectedIcon else destination.icon, contentDescription = null)
    }
}

@Composable
private fun DestinationLabel(destination: AppDestination, selected: Boolean) {
    Text(stringResource(destination.label), fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
}
