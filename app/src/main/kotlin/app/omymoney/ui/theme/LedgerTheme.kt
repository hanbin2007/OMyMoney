package app.omymoney.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

enum class ThemeMode { SYSTEM, LIGHT, DARK }

// Brand fallback when Material You is off or unavailable. Neutrals carry a faint primary hue,
// as generated tonal palettes do, so surfaces feel related to the accent instead of plain grey.
private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5B), onPrimary = Color.White,
    primaryContainer = Color(0xFFA1F2DB), onPrimaryContainer = Color(0xFF002019),
    inversePrimary = Color(0xFF83D6BF),
    secondary = Color(0xFF755B00), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE08B), onSecondaryContainer = Color(0xFF241A00),
    tertiary = Color(0xFF385F98), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD5E3FF), onTertiaryContainer = Color(0xFF001B3F),
    background = Color(0xFFF5FBF7), onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF5FBF7), onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDBE5E0), onSurfaceVariant = Color(0xFF3F4945),
    surfaceTint = Color(0xFF006B5B), inverseSurface = Color(0xFF2B322F), inverseOnSurface = Color(0xFFECF2EE),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF6F7975), outlineVariant = Color(0xFFBFC9C4), scrim = Color.Black,
    surfaceBright = Color(0xFFF5FBF7), surfaceDim = Color(0xFFD5DBD7),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEFF5F1),
    surfaceContainer = Color(0xFFE9EFEB), surfaceContainerHigh = Color(0xFFE3EAE5),
    surfaceContainerHighest = Color(0xFFDEE4E0),
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
    background = Color(0xFF0F1513), onBackground = Color(0xFFDEE4E0),
    surface = Color(0xFF0F1513), onSurface = Color(0xFFDEE4E0),
    surfaceVariant = Color(0xFF3F4945), onSurfaceVariant = Color(0xFFBFC9C4),
    surfaceTint = Color(0xFF83D6BF), inverseSurface = Color(0xFFDEE4E0), inverseOnSurface = Color(0xFF2B322F),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF89938F), outlineVariant = Color(0xFF3F4945), scrim = Color.Black,
    surfaceBright = Color(0xFF353B39), surfaceDim = Color(0xFF0F1513),
    surfaceContainerLowest = Color(0xFF0A0F0D), surfaceContainerLow = Color(0xFF171D1B),
    surfaceContainer = Color(0xFF1B211F), surfaceContainerHigh = Color(0xFF252B29),
    surfaceContainerHighest = Color(0xFF303634),
    primaryFixed = LightColors.primaryFixed, primaryFixedDim = LightColors.primaryFixedDim,
    onPrimaryFixed = LightColors.onPrimaryFixed, onPrimaryFixedVariant = LightColors.onPrimaryFixedVariant,
    secondaryFixed = LightColors.secondaryFixed, secondaryFixedDim = LightColors.secondaryFixedDim,
    onSecondaryFixed = LightColors.onSecondaryFixed, onSecondaryFixedVariant = LightColors.onSecondaryFixedVariant,
    tertiaryFixed = LightColors.tertiaryFixed, tertiaryFixedDim = LightColors.tertiaryFixedDim,
    onTertiaryFixed = LightColors.onTertiaryFixed, onTertiaryFixedVariant = LightColors.onTertiaryFixedVariant,
)

/** Money semantics that Material's scheme has no role for; harmonized with the active primary. */
@Immutable
data class LedgerColors(
    val income: Color, val onIncomeContainer: Color, val incomeContainer: Color,
    val expense: Color, val onExpenseContainer: Color, val expenseContainer: Color,
    val transfer: Color, val onTransferContainer: Color, val transferContainer: Color,
    val accents: List<Pair<Color, Color>>,
)

private fun ledgerColors(scheme: ColorScheme, dark: Boolean): LedgerColors {
    fun harmonize(color: Color) = lerp(color, scheme.primary, 0.12f)
    return if (dark) LedgerColors(
        income = harmonize(Color(0xFF87DDB5)), onIncomeContainer = Color(0xFFA3F5CF), incomeContainer = harmonize(Color(0xFF00513A)),
        expense = harmonize(Color(0xFFFFADB5)), onExpenseContainer = Color(0xFFFFD9DC), expenseContainer = harmonize(Color(0xFF7A2932)),
        transfer = scheme.tertiary, onTransferContainer = scheme.onTertiaryContainer, transferContainer = scheme.tertiaryContainer,
        accents = listOf(scheme.primaryContainer to scheme.onPrimaryContainer, scheme.secondaryContainer to scheme.onSecondaryContainer,
            scheme.tertiaryContainer to scheme.onTertiaryContainer, harmonize(Color(0xFF6B2D5C)) to Color(0xFFFFD7F0),
            harmonize(Color(0xFF6A3B00)) to Color(0xFFFFDCC2), harmonize(Color(0xFF39377A)) to Color(0xFFE2DFFF)),
    ) else LedgerColors(
        income = harmonize(Color(0xFF006C4B)), onIncomeContainer = Color(0xFF002114), incomeContainer = harmonize(Color(0xFF8BF7C6)),
        expense = harmonize(Color(0xFFB3261E)), onExpenseContainer = Color(0xFF40000A), expenseContainer = harmonize(Color(0xFFFFDADB)),
        transfer = scheme.tertiary, onTransferContainer = scheme.onTertiaryContainer, transferContainer = scheme.tertiaryContainer,
        accents = listOf(scheme.primaryContainer to scheme.onPrimaryContainer, scheme.secondaryContainer to scheme.onSecondaryContainer,
            scheme.tertiaryContainer to scheme.onTertiaryContainer, harmonize(Color(0xFFFFD7F0)) to Color(0xFF3A0732),
            harmonize(Color(0xFFFFDCC2)) to Color(0xFF2E1500), harmonize(Color(0xFFE2DFFF)) to Color(0xFF14105F)),
    )
}

val LocalLedgerColors = staticCompositionLocalOf { ledgerColors(LightColors, false) }

private val BaseTypography = Typography()
private val LedgerTypography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(letterSpacing = 0.sp, fontWeight = FontWeight.Medium),
    displayMedium = BaseTypography.displayMedium.copy(letterSpacing = 0.sp, fontWeight = FontWeight.Medium),
    displaySmall = BaseTypography.displaySmall.copy(letterSpacing = 0.sp, fontWeight = FontWeight.Medium),
    headlineLarge = BaseTypography.headlineLarge.copy(letterSpacing = 0.sp, fontWeight = FontWeight.Medium),
    headlineMedium = BaseTypography.headlineMedium.copy(letterSpacing = 0.sp, fontWeight = FontWeight.Medium),
    headlineSmall = BaseTypography.headlineSmall.copy(letterSpacing = 0.sp, fontWeight = FontWeight.Medium),
    titleLarge = BaseTypography.titleLarge.copy(letterSpacing = 0.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(letterSpacing = 0.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = BaseTypography.titleSmall.copy(letterSpacing = 0.sp),
    bodyLarge = BaseTypography.bodyLarge.copy(letterSpacing = 0.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(letterSpacing = 0.sp),
    bodySmall = BaseTypography.bodySmall.copy(letterSpacing = 0.sp),
    labelLarge = BaseTypography.labelLarge.copy(letterSpacing = 0.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = BaseTypography.labelMedium.copy(letterSpacing = 0.sp),
    labelSmall = BaseTypography.labelSmall.copy(letterSpacing = 0.sp),
)

// Expressive corner scale: rounder containers make the grouped lists and hero cards read as soft, touchable objects.
private val LedgerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * Spring tokens of the Material 3 Expressive motion scheme. Spatial springs (position, size, shape) may
 * overshoot a little; effects springs (colour, alpha) settle without bounce.
 */
object ExpressiveMotion {
    fun <T> defaultSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)
    fun <T> slowSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 200f)
    fun <T> defaultEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
    fun <T> fastEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 3800f)
    fun <T> slowEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 800f)
}

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
    val ledgerColors = remember(colors, dark) { ledgerColors(colors, dark) }
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
    CompositionLocalProvider(LocalLedgerColors provides ledgerColors) {
        MaterialTheme(colorScheme = colors, shapes = LedgerShapes,
            typography = LedgerTypography, content = content)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
