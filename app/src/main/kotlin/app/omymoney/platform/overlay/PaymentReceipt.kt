package app.omymoney.platform.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.omymoney.android.R
import app.omymoney.core.ledger.PostingCalculator
import app.omymoney.core.model.TransactionDraft
import app.omymoney.feature.automation.applicationLabel
import app.omymoney.feature.bills.billKindLabel
import app.omymoney.ui.components.ExpressiveShapes
import app.omymoney.ui.components.LedgerIconButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Island-style confirmation shown over other apps after a payment is recorded automatically.
 * It grows out of a small capsule at the top edge, counts down its own display time around the check
 * badge, and folds back into the capsule when closed or when the time runs out.
 */
@Composable
internal fun PaymentReceipt(draft: TransactionDraft, onEdit: () -> Unit, onDismiss: () -> Unit, displayMillis: Long? = null) {
    // The island stays dark in both themes, like hardware cut-out islands, but takes a hint of the accent hue.
    val primary = MaterialTheme.colorScheme.primary
    val container = lerp(Color(0xFF151816), primary, 0.08f)
    val foreground = Color(0xFFF4F7F5)
    val success = lerp(Color(0xFF8AD5AE), primary, 0.15f)
    val amountColor = when (draft.kind) {
        in PostingCalculator.incomingKinds -> success
        in PostingCalculator.outgoingKinds -> lerp(Color(0xFFFFB4BC), primary, 0.1f)
        else -> lerp(Color(0xFFAFC6FF), primary, 0.1f)
    }
    val scope = rememberCoroutineScope()
    val morph = remember { Animatable(0f) }
    val check = remember { Animatable(0f) }
    val countdown = remember { Animatable(1f) }
    var leaving by remember { mutableStateOf(false) }
    fun leave(then: () -> Unit) {
        if (leaving) return
        leaving = true
        scope.launch {
            morph.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium))
            then()
        }
    }
    LaunchedEffect(Unit) {
        launch { morph.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 420f)) }
        delay(140)
        check.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }
    if (displayMillis != null) LaunchedEffect(displayMillis) {
        // Fold away a moment before the host removes the window, so the timeout reads as a gesture rather than a cut.
        val fold = 320L
        countdown.animateTo(0f, tween((displayMillis - fold).coerceAtLeast(0L).toInt(), easing = LinearEasing))
        if (!leaving) { leaving = true; morph.animateTo(0f, tween(fold.toInt() - 40)) }
    }
    Surface(color = container, contentColor = foreground, shape = MaterialTheme.shapes.extraLarge, shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().testTag("payment_receipt").semantics { liveRegion = LiveRegionMode.Polite }
            .graphicsLayer {
                val value = morph.value
                transformOrigin = TransformOrigin(0.5f, 0f)
                scaleX = 0.42f + 0.58f * value
                scaleY = 0.55f + 0.45f * value
                alpha = (value * 1.6f).coerceIn(0f, 1f)
            }) {
        Box {
        Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp).graphicsLayer {
            alpha = ((morph.value - 0.35f) / 0.65f).coerceIn(0f, 1f)
        }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(start = 2.dp).size(38.dp).graphicsLayer { scaleX = check.value; scaleY = check.value }
                .clip(ExpressiveShapes.Cookie).background(success).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Check, null, Modifier.size(22.dp), tint = container)
            }
            Column(Modifier.weight(1f).heightIn(min = 56.dp).clickable(onClickLabel = stringResource(R.string.edit_bill)) { leave(onEdit) }
                .padding(horizontal = 10.dp, vertical = 2.dp), verticalArrangement = Arrangement.Center) {
                Text(billKindLabel(requireNotNull(draft.kind)) + stringResource(R.string.payment_recorded),
                    color = success, style = MaterialTheme.typography.labelMedium)
                BasicText(draft.amount?.let { "${it.currency.code} ${it.decimalText()}" }.orEmpty(),
                    style = MaterialTheme.typography.titleLarge.copy(color = amountColor, fontFeatureSettings = "tnum"), maxLines = 3,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.titleLarge.fontSize, stepSize = 1.sp))
                Text(listOfNotNull(draft.sourcePackage?.let { applicationLabel(it) }, draft.payment?.counterparty?.takeIf(String::isNotBlank)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = foreground.copy(alpha = 0.72f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            LedgerIconButton(Icons.Outlined.Edit, stringResource(R.string.edit_bill), { leave(onEdit) }, Modifier.testTag("receipt_edit"))
            LedgerIconButton(Icons.Outlined.Close, stringResource(R.string.payment_receipt_dismiss), { leave(onDismiss) }, Modifier.testTag("receipt_close"))
        }
        // Remaining display time: a faint hairline along the bottom edge that shortens toward the centre.
        if (displayMillis != null) Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 28.dp, vertical = 5.dp)
            .height(2.dp).clearAndSetSemantics { }) {
            val width = size.width * countdown.value
            drawLine(foreground.copy(alpha = 0.22f), Offset((size.width - width) / 2, size.height / 2),
                Offset((size.width + width) / 2, size.height / 2), size.height, cap = StrokeCap.Round)
        }
        }
    }
}

/** Collapsed reminder for drafts still waiting to be confirmed: a compact capsule at the top edge. */
@Composable
internal fun PendingIsland(count: Int, onReopen: () -> Unit, onLater: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val container = lerp(Color(0xFF151816), primary, 0.08f)
    val foreground = Color(0xFFF4F7F5)
    val accent = lerp(Color(0xFFFFD98A), primary, 0.12f)
    val morph = remember { Animatable(0f) }
    LaunchedEffect(Unit) { morph.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 420f)) }
    Surface(color = container, contentColor = foreground, shape = MaterialTheme.shapes.extraLarge, shadowElevation = 6.dp,
        modifier = Modifier.padding(horizontal = 12.dp).graphicsLayer {
            transformOrigin = TransformOrigin(0.5f, 0f)
            scaleX = 0.5f + 0.5f * morph.value; scaleY = 0.6f + 0.4f * morph.value
            alpha = (morph.value * 1.6f).coerceIn(0f, 1f)
        }) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(ExpressiveShapes.Sunny).background(accent))
            Text(stringResource(R.string.review_pending_count, count), Modifier.padding(start = 10.dp, end = 4.dp),
                style = MaterialTheme.typography.titleSmall)
            LedgerIconButton(Icons.Outlined.Edit, stringResource(R.string.review_reopen), onReopen)
            LedgerIconButton(Icons.Outlined.Close, stringResource(R.string.review_later), onLater)
        }
    }
}
