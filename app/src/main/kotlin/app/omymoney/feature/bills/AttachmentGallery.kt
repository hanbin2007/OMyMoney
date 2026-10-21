package app.omymoney.feature.bills

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.omymoney.android.R
import app.omymoney.core.model.AttachmentRef
import app.omymoney.data.AttachmentStore
import app.omymoney.ui.components.ErrorText
import app.omymoney.ui.components.LedgerIconButton
import kotlinx.coroutines.CancellationException
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun AttachmentEditor(refs: List<AttachmentRef>, store: AttachmentStore, importing: Boolean, error: String?, enabled: Boolean,
                     onAdd: () -> Unit, onRemove: (AttachmentRef) -> Unit, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.attachments_count, refs.size), style = MaterialTheme.typography.titleSmall)
        if (refs.isNotEmpty()) AttachmentGallery(refs, store, onRemove = onRemove, enabled = enabled)
        if (importing) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(Modifier.weight(1f))
            TextButton(onCancel) { Text(stringResource(R.string.cancel)) }
        }
        error?.let { ErrorText(stringResource(when (it) {
            "count_limit" -> R.string.attachment_count_limit
            "size_limit" -> R.string.attachment_size_limit
            "image_too_large" -> R.string.attachment_pixel_limit
            "unsupported_image" -> R.string.attachment_format
            else -> R.string.attachment_import_failed
        }), Modifier.testTag("attachment_error")) }
        OutlinedButton(onAdd, Modifier.testTag("add_attachment"), enabled = enabled && !importing && refs.size < 10) {
            Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_image))
        }
    }
}

@Composable
fun AttachmentGallery(refs: List<AttachmentRef>, store: AttachmentStore, onRemove: ((AttachmentRef) -> Unit)? = null, enabled: Boolean = true) {
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().testTag("attachment_gallery")) {
        itemsIndexed(refs, key = { _, ref -> ref.id }) { index, ref ->
            val preview by imagePreview(store, ref, 256)
            Column(Modifier.width(120.dp).clickable(enabled, role = Role.Button) { viewing = ref.id }
                .testTag("attachment_${ref.id}")) {
                Box(Modifier.size(120.dp)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        when (val value = preview) {
                            ImagePreview.Loading -> Box(contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                            ImagePreview.Unavailable -> Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.BrokenImage, null) }
                            is ImagePreview.Ready -> Image(value.bitmap.asImageBitmap(), null,
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    if (onRemove != null) Surface(Modifier.align(Alignment.TopEnd), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        LedgerIconButton(Icons.Outlined.Close, stringResource(R.string.remove_image, index + 1), { onRemove(ref) },
                            Modifier.testTag("remove_attachment_${ref.id}"), enabled = enabled)
                    }
                }
                val unavailable = preview == ImagePreview.Unavailable
                Text(if (unavailable) stringResource(R.string.attachment_unavailable) else stringResource(R.string.image_number, index + 1),
                    Modifier.fillMaxWidth().padding(top = 4.dp).then(if (unavailable) Modifier.testTag("attachment_missing_${ref.id}") else Modifier),
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, minLines = 2, textAlign = TextAlign.Center,
                    color = if (unavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    refs.firstOrNull { it.id == viewing }?.let { AttachmentViewer(it, store, onDismiss = { viewing = null }) }
}

private sealed interface ImagePreview {
    data object Loading : ImagePreview
    data object Unavailable : ImagePreview
    data class Ready(val bitmap: Bitmap) : ImagePreview
}

@Composable
private fun imagePreview(store: AttachmentStore, ref: AttachmentRef, maxSize: Int): State<ImagePreview> =
    produceState<ImagePreview>(ImagePreview.Loading, store, ref, maxSize) {
        value = try { ImagePreview.Ready(store.preview(ref, maxSize)) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { ImagePreview.Unavailable }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentViewer(ref: AttachmentRef, store: AttachmentStore, onDismiss: () -> Unit) {
    val preview by imagePreview(store, ref, 2048)
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val canvasFocus = remember { FocusRequester() }
    val panStep = with(LocalDensity.current) { 64.dp.toPx() }
    fun limits(): Offset {
        val bitmap = (preview as? ImagePreview.Ready)?.bitmap ?: return Offset.Zero
        val fit = min(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height)
        return Offset(((bitmap.width * fit * zoom - size.width) / 2).coerceAtLeast(0f),
            ((bitmap.height * fit * zoom - size.height) / 2).coerceAtLeast(0f))
    }
    fun transformed(scale: Float, pan: Offset) {
        zoom = scale.coerceIn(1f, 5f)
        val limit = limits()
        offset = Offset(pan.x.coerceIn(-limit.x, limit.x), pan.y.coerceIn(-limit.y, limit.y))
    }
    fun pan(delta: Offset): Boolean {
        val previous = offset
        transformed(zoom, offset + delta)
        return previous != offset
    }
    val zoomDescription = stringResource(R.string.image_zoom, (zoom * 100).roundToInt())
    val panLeft = stringResource(R.string.pan_left)
    val panRight = stringResource(R.string.pan_right)
    val panUp = stringResource(R.string.pan_up)
    val panDown = stringResource(R.string.pan_down)
    val transform = rememberTransformableState { zoomChange, panChange, _ -> transformed(zoom * zoomChange, offset + panChange) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(title = { Text(stringResource(R.string.image)) }, navigationIcon = {
                    LedgerIconButton(Icons.Outlined.Close, stringResource(R.string.close_image), onDismiss, Modifier.testTag("close_image"))
                }, actions = {
                    LedgerIconButton(Icons.Outlined.ZoomOut, stringResource(R.string.zoom_out), { transformed(zoom / 1.5f, offset) }, enabled = zoom > 1f)
                    LedgerIconButton(Icons.Outlined.ZoomIn, stringResource(R.string.zoom_in), { transformed(zoom * 1.5f, offset) }, enabled = zoom < 5f && preview is ImagePreview.Ready)
                })
                Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().clipToBounds().onSizeChanged { size = it }
                    .transformable(transform).testTag("image_canvas").focusRequester(canvasFocus).onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                            Key.DirectionLeft -> pan(Offset(panStep, 0f))
                            Key.DirectionRight -> pan(Offset(-panStep, 0f))
                            Key.DirectionUp -> pan(Offset(0f, panStep))
                            Key.DirectionDown -> pan(Offset(0f, -panStep))
                            else -> false
                        }
                    }.focusable().semantics(mergeDescendants = true) {
                        stateDescription = zoomDescription
                        horizontalScrollAxisRange = ScrollAxisRange({ limits().x - offset.x }, { limits().x * 2 })
                        verticalScrollAxisRange = ScrollAxisRange({ limits().y - offset.y }, { limits().y * 2 })
                        scrollBy { x, y -> pan(Offset(-x, -y)) }
                        customActions = listOf(
                            CustomAccessibilityAction(panLeft) { pan(Offset(panStep, 0f)) },
                            CustomAccessibilityAction(panRight) { pan(Offset(-panStep, 0f)) },
                            CustomAccessibilityAction(panUp) { pan(Offset(0f, panStep)) },
                            CustomAccessibilityAction(panDown) { pan(Offset(0f, -panStep)) },
                        )
                    },
                    contentAlignment = Alignment.Center) {
                    when (val value = preview) {
                        ImagePreview.Loading -> CircularProgressIndicator()
                        ImagePreview.Unavailable -> ErrorText(stringResource(R.string.attachment_unavailable), Modifier.padding(24.dp))
                        is ImagePreview.Ready -> Image(value.bitmap.asImageBitmap(), stringResource(R.string.image), contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y })
                    }
                }
            }
        }
    }
}
