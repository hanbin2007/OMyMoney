package app.omymoney.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5B), onPrimary = Color.White,
    primaryContainer = Color(0xFFA1F2DB), onPrimaryContainer = Color(0xFF002019),
    inversePrimary = Color(0xFF83D6BF),
    secondary = Color(0xFF755B00), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE08B), onSecondaryContainer = Color(0xFF241A00),
    tertiary = Color(0xFF385F98), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD5E3FF), onTertiaryContainer = Color(0xFF001B3F),
    background = Color(0xFFFAFAFA), onBackground = Color(0xFF1B1C1E),
    surface = Color(0xFFFAFAFA), onSurface = Color(0xFF1B1C1E),
    surfaceVariant = Color(0xFFE0E3E3), onSurfaceVariant = Color(0xFF414747),
    surfaceTint = Color(0xFF006B5B), inverseSurface = Color(0xFF2F3133), inverseOnSurface = Color(0xFFF0F1F2),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF707778), outlineVariant = Color(0xFFC1C7C8), scrim = Color.Black,
    surfaceBright = Color(0xFFFAFAFA), surfaceDim = Color(0xFFDADCDD),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF4F4F5),
    surfaceContainer = Color(0xFFEEEFF0), surfaceContainerHigh = Color(0xFFE8E9EA),
    surfaceContainerHighest = Color(0xFFE2E3E4),
    primaryFixed = Color(0xFFA1F2DB), primaryFixedDim = Color(0xFF83D6BF),
    onPrimaryFixed = Color(0xFF002019), onPrimaryFixedVariant = Color(0xFF005143),
    secondaryFixed = Color(0xFFFFE08B), secondaryFixedDim = Color(0xFFE9C16B),
    onSecondaryFixed = Color(0xFF241A00), onSecondaryFixedVariant = Color(0xFF584400),
    tertiaryFixed = Color(0xFFD5E3FF), tertiaryFixedDim = Color(0xFFABC8FF),
    onTertiaryFixed = Color(0xFF001B3F), onTertiaryFixedVariant = Color(0xFF1D477E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF83D6BF), onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF005143), onPrimaryContainer = Color(0xFFA1F2DB),
    inversePrimary = Color(0xFF006B5B),
    secondary = Color(0xFFE9C16B), onSecondary = Color(0xFF3D2F00),
    secondaryContainer = Color(0xFF584400), onSecondaryContainer = Color(0xFFFFE08B),
    tertiary = Color(0xFFABC8FF), onTertiary = Color(0xFF003061),
    tertiaryContainer = Color(0xFF1D477E), onTertiaryContainer = Color(0xFFD5E3FF),
    background = Color(0xFF111315), onBackground = Color(0xFFE1E3E5),
    surface = Color(0xFF111315), onSurface = Color(0xFFE1E3E5),
    surfaceVariant = Color(0xFF414747), onSurfaceVariant = Color(0xFFC1C7C8),
    surfaceTint = Color(0xFF83D6BF), inverseSurface = Color(0xFFE1E3E5), inverseOnSurface = Color(0xFF2F3133),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8B9293), outlineVariant = Color(0xFF414747), scrim = Color.Black,
    surfaceBright = Color(0xFF37393B), surfaceDim = Color(0xFF111315),
    surfaceContainerLowest = Color(0xFF0C0E10), surfaceContainerLow = Color(0xFF1A1C1E),
    surfaceContainer = Color(0xFF1E2022), surfaceContainerHigh = Color(0xFF27292B),
    surfaceContainerHighest = Color(0xFF323436),
    primaryFixed = LightColors.primaryFixed, primaryFixedDim = LightColors.primaryFixedDim,
    onPrimaryFixed = LightColors.onPrimaryFixed, onPrimaryFixedVariant = LightColors.onPrimaryFixedVariant,
    secondaryFixed = LightColors.secondaryFixed, secondaryFixedDim = LightColors.secondaryFixedDim,
    onSecondaryFixed = LightColors.onSecondaryFixed, onSecondaryFixedVariant = LightColors.onSecondaryFixedVariant,
    tertiaryFixed = LightColors.tertiaryFixed, tertiaryFixedDim = LightColors.tertiaryFixedDim,
    onTertiaryFixed = LightColors.onTertiaryFixed, onTertiaryFixedVariant = LightColors.onTertiaryFixedVariant,
)

private val BaseTypography = Typography()
private val LedgerTypography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(letterSpacing = 0.sp),
    displayMedium = BaseTypography.displayMedium.copy(letterSpacing = 0.sp),
    displaySmall = BaseTypography.displaySmall.copy(letterSpacing = 0.sp),
    headlineLarge = BaseTypography.headlineLarge.copy(letterSpacing = 0.sp),
    headlineMedium = BaseTypography.headlineMedium.copy(letterSpacing = 0.sp),
    headlineSmall = BaseTypography.headlineSmall.copy(letterSpacing = 0.sp),
    titleLarge = BaseTypography.titleLarge.copy(letterSpacing = 0.sp),
    titleMedium = BaseTypography.titleMedium.copy(letterSpacing = 0.sp),
    titleSmall = BaseTypography.titleSmall.copy(letterSpacing = 0.sp),
    bodyLarge = BaseTypography.bodyLarge.copy(letterSpacing = 0.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(letterSpacing = 0.sp),
    bodySmall = BaseTypography.bodySmall.copy(letterSpacing = 0.sp),
    labelLarge = BaseTypography.labelLarge.copy(letterSpacing = 0.sp),
    labelMedium = BaseTypography.labelMedium.copy(letterSpacing = 0.sp),
    labelSmall = BaseTypography.labelSmall.copy(letterSpacing = 0.sp),
)

@Composable
fun LedgerTheme(themeMode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            context.findActivity()?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = LedgerTypography, content = content)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
