package app.omymoney.ui.components

import app.omymoney.ui.theme.ExpressiveMotion
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.omymoney.ui.theme.DesignTokens
import app.omymoney.ui.theme.LocalLedgerColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A circle whose rim undulates into [lobes] soft bumps, like the Material 3 Expressive "cookie" shapes. */
class WavyCircleShape(private val lobes: Int, private val depth: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = min(cx, cy)
        val steps = lobes * 24
        val path = Path()
        for (step in 0..steps) {
            val angle = 2.0 * PI * step / steps - PI / 2
            val r = radius * (1f - depth * (1f - cos(lobes * angle).toFloat()) / 2f)
            val x = cx + r * cos(angle).toFloat()
            val y = cy + r * sin(angle).toFloat()
            if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

object ExpressiveShapes {
    val Cookie: Shape = WavyCircleShape(lobes = 9, depth = 0.11f)
    val Clover: Shape = WavyCircleShape(lobes = 4, depth = 0.22f)
    val Sunny: Shape = WavyCircleShape(lobes = 8, depth = 0.07f)
    val Puffy: Shape = WavyCircleShape(lobes = 6, depth = 0.14f)
    val all = listOf(Cookie, Clover, Sunny, Puffy)
}

/** Corner shape of one member of a vertically grouped list: round on the group's outside, tight between members. */
fun groupedShape(index: Int, count: Int, outer: Dp = 24.dp, inner: Dp = 6.dp): Shape = RoundedCornerShape(
    topStart = if (index == 0) outer else inner, topEnd = if (index == 0) outer else inner,
    bottomStart = if (index == count - 1) outer else inner, bottomEnd = if (index == count - 1) outer else inner,
)

/** Emits [items] as one visually grouped block of rounded surfaces separated by hairline gaps. */
fun <T> LazyListScope.groupedItems(items: List<T>, key: (T) -> Any, content: @Composable LazyItemScope.(item: T, shape: Shape) -> Unit) {
    itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
        Box(Modifier.animateItem().padding(horizontal = DesignTokens.PagePadding).padding(bottom = 2.dp)) {
            content(item, groupedShape(index, items.size))
        }
    }
}

/**
 * List row living inside a grouped block. Pressing squishes the row slightly and rounds its
 * corners, giving the tactile, shape-morphing feedback of Material 3 Expressive.
 */
@Composable
fun GroupedRow(
    shape: Shape,
    onClick: (() -> Unit)?,
    headline: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supporting: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    overline: (@Composable () -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, ExpressiveMotion.fastSpatial(), label = "row-scale")
    val color by animateColorAsState(containerColor, ExpressiveMotion.defaultEffects(), label = "row-color")
    val pressedShape = if (pressed) MaterialTheme.shapes.large else shape
    val content: @Composable () -> Unit = {
        ListItem(headlineContent = headline, supportingContent = supporting, leadingContent = leading, trailingContent = trailing,
            overlineContent = overline, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
    }
    val layer = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
    if (onClick != null) Surface(onClick = onClick, modifier = modifier.fillMaxWidth().then(layer), enabled = enabled, shape = pressedShape,
        color = color, interactionSource = interaction, content = content)
    else Surface(modifier.fillMaxWidth(), shape = shape, color = color, content = content)
}

/** Icon set on a tonal, wavy badge. */
@Composable
fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier, containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
              contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer, shape: Shape = ExpressiveShapes.Cookie, size: Dp = 40.dp) {
    Box(modifier.size(size).clip(shape).background(containerColor), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(size * 0.5f))
    }
}

/** First glyph of [text] on a tonal badge whose colour and shape are stable for the same text. */
@Composable
fun MonogramBadge(text: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val accents = LocalLedgerColors.current.accents
    val seed = text.hashCode().let { if (it < 0) -it else it }
    val (container, content) = accents[seed % accents.size]
    Box(modifier.size(size).clip(ExpressiveShapes.all[seed % ExpressiveShapes.all.size]).background(container)
        .clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Text(text.trim().take(1).ifEmpty { "·" }, color = content,
            style = if (size < 32.dp) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = DesignTokens.PagePadding + 8.dp, end = DesignTokens.PagePadding, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke(this)
    }
}

/**
 * Large gradient card used at the top of key screens. Decorative wavy blobs float behind the content.
 */
@Composable
fun HeroCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    accentColor: Color = MaterialTheme.colorScheme.tertiaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, ExpressiveMotion.fastSpatial(), label = "hero-scale")
    val blob = contentColor.copy(alpha = 0.06f)
    val brush = Brush.linearGradient(listOf(containerColor, androidx.compose.ui.graphics.lerp(containerColor, accentColor, 0.55f)))
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().background(brush).drawBehind {
            val big = size.minDimension * 0.9f
            drawCircle(blob, big * 0.55f, Offset(size.width - big * 0.18f, big * 0.05f))
            drawCircle(blob, big * 0.32f, Offset(size.width * 0.62f, size.height + big * 0.08f))
        }.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
    val layer = modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
    if (onClick != null) Surface(onClick = onClick, modifier = layer, shape = MaterialTheme.shapes.extraLarge, color = containerColor,
        contentColor = contentColor, interactionSource = interaction, content = body)
    else Surface(layer, shape = MaterialTheme.shapes.extraLarge, color = containerColor, contentColor = contentColor, content = body)
}

/**
 * Thick rounded progress bar with the Material 3 gap-and-stop-indicator styling; value changes glide with a spring.
 */
@Composable
fun ExpressiveProgress(progress: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary,
                       trackColor: Color = MaterialTheme.colorScheme.secondaryContainer, height: Dp = 12.dp) {
    val target = progress.coerceIn(0f, 1f)
    val animated by animateFloatAsState(target, ExpressiveMotion.slowSpatial(), label = "progress")
    Canvas(modifier.fillMaxWidth().height(height).semantics { progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f) }) {
        val radius = CornerRadius(size.height / 2, size.height / 2)
        val gap = 4.dp.toPx()
        val filled = (size.width * animated.coerceIn(0f, 1f))
        val trackStart = if (filled > 0f) filled + gap else 0f
        if (trackStart < size.width) drawRoundRect(trackColor, Offset(trackStart, 0f), Size(size.width - trackStart, size.height), radius)
        if (filled > 0f) drawRoundRect(color, Offset.Zero, Size(filled.coerceAtLeast(size.height), size.height), radius)
        if (animated < 0.98f) drawCircle(color, size.height / 6, Offset(size.width - size.height / 2, size.height / 2))
    }
}

/** Compact statistic: label, value and a tinted icon, on a container coloured for its meaning. */
@Composable
fun StatTile(label: String, icon: ImageVector, containerColor: Color, contentColor: Color, modifier: Modifier = Modifier,
             value: @Composable () -> Unit) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = containerColor, contentColor = contentColor) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconBadge(icon, containerColor = contentColor.copy(alpha = 0.12f), contentColor = contentColor, size = 32.dp, shape = ExpressiveShapes.Sunny)
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            value()
        }
    }
}

/** Fades and lifts [content] into place the first time it is composed; [order] staggers siblings. */
@Composable
fun EnterAnimated(modifier: Modifier = Modifier, order: Int = 0, content: @Composable BoxScope.() -> Unit) {
    val progress = remember { Animatable(0f) }
    val spec = ExpressiveMotion.slowSpatial<Float>()
    LaunchedEffect(Unit) {
        if (order > 0) kotlinx.coroutines.delay(order * 45L)
        progress.animateTo(1f, spec)
    }
    Box(modifier.graphicsLayer {
        val value = progress.value
        alpha = value.coerceIn(0f, 1f)
        translationY = (1f - value) * 24.dp.toPx()
    }, content = content)
}
