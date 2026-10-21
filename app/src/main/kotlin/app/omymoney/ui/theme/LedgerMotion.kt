package app.omymoney.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry

/**
 * Navigation choreography: top-level tabs fade through (they are peers), everything else uses the
 * shared X axis so depth reads as moving forward and back. Spatial movement is springy, fades are short.
 */
object LedgerMotion {
    private val slide = spring<IntOffset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    private const val SHIFT = 5

    fun enter(topLevel: Set<String>): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (isTabSwitch(topLevel)) fadeIn(tween(220, delayMillis = 70)) + scaleIn(tween(260, delayMillis = 70, easing = FastOutSlowInEasing), initialScale = 0.94f)
        else slideInHorizontally(slide) { it / SHIFT } + fadeIn(tween(200, delayMillis = 50))
    }

    fun exit(topLevel: Set<String>): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (isTabSwitch(topLevel)) fadeOut(tween(90))
        else slideOutHorizontally(slide) { -it / SHIFT } + fadeOut(tween(120))
    }

    fun popEnter(topLevel: Set<String>): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (isTabSwitch(topLevel)) fadeIn(tween(220, delayMillis = 70))
        else slideInHorizontally(slide) { -it / SHIFT } + fadeIn(tween(200, delayMillis = 50))
    }

    fun popExit(topLevel: Set<String>): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (isTabSwitch(topLevel)) fadeOut(tween(90))
        else slideOutHorizontally(slide) { it / SHIFT } + fadeOut(tween(120)) + scaleOut(tween(200), targetScale = 0.96f)
    }

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(topLevel: Set<String>) =
        initialState.destination.route in topLevel && targetState.destination.route in topLevel
}
