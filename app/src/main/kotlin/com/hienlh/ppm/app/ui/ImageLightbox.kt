package com.hienlh.ppm.app.ui

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.hienlh.ppm.app.LightboxRequest
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.GalleryImage
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.ViewerImage
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.design.webTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A picture as the lightbox draws it: its file, decoded, and its size in the file's pixels. */
private class Shown(val file: ViewerImage, val bitmap: ImageBitmap, val natural: IntSize)

/** The longest side a picture is decoded at: past what any GPU here fails to draw, a halved copy. */
private const val LIGHTBOX_LONGEST_SIDE = 4096

private fun decodeShown(file: ViewerImage): Shown? {
    val bytes = file.bytes
    fun sample(natural: IntSize): Int {
        var sample = 1
        while (max(natural.width, natural.height) / sample > LIGHTBOX_LONGEST_SIDE) sample *= 2
        return sample
    }
    if (Build.VERSION.SDK_INT >= 28) {
        var natural = IntSize.Zero
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                natural = IntSize(info.size.width, info.size.height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(sample(natural))
            }
        }.getOrNull() ?: return null
        if (natural.width <= 0 || natural.height <= 0) return null
        // Mipmaps: a large picture drawn fitted stays smooth, as Chrome draws it.
        bitmap.setHasMipMap(true)
        return Shown(file, bitmap.asImageBitmap(), natural)
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val natural = IntSize(bounds.outWidth, bounds.outHeight)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample(natural) }) ?: return null
    bitmap.setHasMipMap(true)
    return Shown(file, bitmap.asImageBitmap(), natural)
}

private val white = Rgba(1.0, 1.0, 1.0, 1.0)

/** `text-green-400`, as Chrome draws its `oklch()`. */
private val green400 = Rgba(5.07 / 255, 223.2 / 255, 114.37 / 255, 1.0)

/** `shadow-2xl`, on the picture. */
private val shadow2xl = ThemeShadow(0.0, 25.0, 50.0, -12.0, Rgba(0.0, 0.0, 0.0, 0.25))

/** `font-mono text-xs`. */
private val monoXs = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f)

/**
 * The image lightbox (image-overlay.tsx), over everything but the connection-lost overlay and the
 * toasts: the picture on a black stage, to zoom, pan, turn and mirror, the arrows through the
 * chat's other pictures, Download and Copy image. A picture that will not load leaves the set, as
 * one the web never loaded is never collected into it; the ones either side are read ahead.
 */
@Composable
internal fun ImageLightbox(request: LightboxRequest, onClose: () -> Unit, onDownload: (ViewerImage) -> Unit, onCopy: suspend (ViewerImage) -> Boolean) {
    val images = remember(request) { mutableStateListOf<GalleryImage>().apply { addAll(request.images) } }
    var current by remember(request) { mutableStateOf(request.images.getOrNull(request.index) ?: request.images.firstOrNull()) }
    // Which way the arrows last went, so a picture that will not load is stepped over that way.
    var heading by remember(request) { mutableStateOf(1) }
    val ready = remember(request) { mutableStateMapOf<String, Shown>() }
    val reading = remember(request) { mutableMapOf<String, Job>() }
    val scope = rememberCoroutineScope()

    // The bars over a black stage are light, whatever the theme drew them as.
    val view = LocalView.current
    val activity = LocalActivity.current
    DisposableEffect(view, activity) {
        val window = activity?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val status = bars?.isAppearanceLightStatusBars
        val navigation = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            if (status != null) bars.isAppearanceLightStatusBars = status
            if (navigation != null) bars.isAppearanceLightNavigationBars = navigation
        }
    }

    fun drop(failed: GalleryImage) {
        val at = current?.let(images::indexOf) ?: -1
        if (current?.key == failed.key) {
            val ahead = (at + 1..images.lastIndex).map { images[it] }
            val behind = (at - 1 downTo 0).map { images[it] }
            current = (if (heading < 0) behind + ahead else ahead + behind).firstOrNull { it.key != failed.key }
        }
        images.removeAll { it.key == failed.key }
        if (current == null) onClose()
    }

    fun read(image: GalleryImage) {
        if (image.key in ready || reading[image.key]?.isActive == true) return
        reading[image.key] = scope.launch {
            val shown = image.read()?.let { file -> withContext(Dispatchers.Default) { decodeShown(file) } }
            // A read let go of half way (the picture moved out of reach) is no failure.
            ensureActive()
            if (shown != null) ready[image.key] = shown else drop(image)
        }
    }

    val shownImage = current ?: return
    val position = images.indexOf(shownImage).coerceAtLeast(0)
    LaunchedEffect(shownImage, images.size) {
        // The picture and the two either side; what is further away is let go.
        val near = (position - 1..position + 1).mapNotNull { images.getOrNull(it) }
        val keep = near.map { it.key }.toSet()
        ready.keys.retainAll(keep)
        reading.keys.filter { it !in keep }.forEach { reading.remove(it)?.cancel() }
        read(shownImage)
        near.forEach(::read)
    }
    val go: (Int) -> Unit = { delta ->
        images.getOrNull(position + delta)?.let {
            heading = delta
            current = it
        }
    }
    // Each picture opens fitted, as the web mounts the viewer again for each one.
    key(shownImage.key) {
        Viewer(ready[shownImage.key], position, images.size, go, onClose, onDownload, onCopy)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Viewer(
    shown: Shown?,
    position: Int,
    count: Int,
    go: (Int) -> Unit,
    onClose: () -> Unit,
    onDownload: (ViewerImage) -> Unit,
    onCopy: suspend (ViewerImage) -> Boolean,
) {
    val density = LocalDensity.current
    var transform by remember { mutableStateOf(ViewTransform()) }
    var stageSize by remember { mutableStateOf(IntSize.Zero) }
    val layout = remember(shown, stageSize, density) {
        if (shown == null || stageSize.width == 0) {
            null
        } else {
            val (w, h) = fittedSize(shown.natural.width * density.density, shown.natural.height * density.density, stageSize.width.toFloat(), stageSize.height.toFloat())
            StageLayout(w, h, stageSize.width.toFloat(), stageSize.height.toFloat(), shown.natural.width * density.density)
        }
    }
    // A resized window changes what fits: the pan is held inside the new limits.
    LaunchedEffect(layout) { layout?.let { transform = transform.settled(it) } }
    // Read through a state: a callback handed on and kept (the stage's gesture loops) still sees the current layout.
    val currentLayout by rememberUpdatedState(layout)
    fun change(step: ViewTransform.(StageLayout) -> ViewTransform) {
        currentLayout?.let { transform = transform.step(it) }
    }
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val copy = {
        shown?.let { picture ->
            scope.launch {
                if (onCopy(picture.file)) {
                    copied = true
                    delay(1_500)
                    copied = false
                }
            }
        }
        Unit
    }
    val download = { shown?.let { onDownload(it.file) } ?: Unit }

    BackHandler(onBack = onClose)
    // The web hears its keys on `document`, wherever focus is: here the lightbox takes focus back
    // whenever it loses it (a tap the root takes as "outside every field" clears it).
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) { if (!focused) focus.requestFocus() }
    val many = count > 1
    val safe = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

    Box(
        Modifier
            .fillMaxSize()
            // `bg-black/90`; what is under it is blurred by the root (`backdrop-blur-sm`).
            .background(Color.Black.copy(alpha = 0.9f))
            // Nothing under it takes a touch, as nothing under the web's fixed layer takes a click.
            .takesTouches()
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> onClose()
                    Key.DirectionLeft -> go(-1)
                    Key.DirectionRight -> go(1)
                    else -> when (event.nativeKeyEvent.unicodeChar.toChar()) {
                        '+', '=' -> change { zoomedTo(scale * 1.25f, it) }
                        '-', '_' -> change { zoomedTo(scale * 0.8f, it) }
                        '0' -> change { fitted(it) }
                        '1' -> change { actualSize(it) }
                        'r' -> change { rotated(90, it) }
                        'R' -> change { rotated(-90, it) }
                        'h' -> change { flipped(horizontal = true) }
                        'v' -> change { flipped(horizontal = false) }
                        else -> return@onPreviewKeyEvent false
                    }
                }
                true
            },
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(safe)) {
            Stage(
                shown,
                transform,
                layout,
                onChange = ::change,
                onClose = onClose,
                modifier = Modifier.weight(1f).fillMaxWidth().onSizeChanged { stageSize = it },
            )
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = max(12f, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().value).dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                // As wide as its buttons in one row, or the whole width once they wrap, as a
                // flex item shrunk to fit is; each row centred in it.
                val width = min(toolbarWidth(many), maxWidth.value).dp
                FlowRow(
                    Modifier
                        .width(width)
                        .clip(RoundedCornerShape(16.dp))
                        // `bg-white/10 backdrop-blur-sm`. The overlay's own `backdrop-blur-sm` makes it the
                        // toolbar's backdrop root, so what the toolbar blurs is the overlay's black alone,
                        // drawn a second time: the page no longer shows through, and the toolbar is darker
                        // than the close button's same white/10.
                        .background(Color.Black.copy(alpha = 0.9f))
                        .background(white.opacity(0.1).color)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (many) {
                        ToolButton("Previous image (←)", enabled = position > 0, onClick = { go(-1) }) { PpmIcon(ProductIcon.ChevronLeft, 20.dp, it) }
                        ToolButton("Next image (→)", enabled = position < count - 1, onClick = { go(1) }) { PpmIcon(ProductIcon.ChevronRight, 20.dp, it) }
                        Divider()
                    }
                    ToolButton("Zoom out (-)", onClick = { change { zoomedTo(scale * 0.8f, it) } }) { PpmIcon(ProductIcon.Minus, 20.dp, it) }
                    ZoomReadout(transform.percent) { transform = ViewTransform() }
                    ToolButton("Zoom in (+)", onClick = { change { zoomedTo(scale * 1.25f, it) } }) { PpmIcon(ProductIcon.Plus, 20.dp, it) }
                    ToolButton("Fit to screen (0)", onClick = { change { fitted(it) } }) { PpmIcon(ProductIcon.Maximize2, 20.dp, it) }
                    ToolButton("Actual size (1)", onClick = { change { actualSize(it) } }) { PpmIcon(LucideIcon.Scan, 20.dp, it) }
                    Divider()
                    val turned = transform.rotation != 0
                    ToolButton("Rotate left (Shift+R)", active = turned, onClick = { change { rotated(-90, it) } }) { PpmIcon(ProductIcon.RotateCcw, 20.dp, it) }
                    ToolButton("Rotate right (R)", active = turned, onClick = { change { rotated(90, it) } }) { PpmIcon(ProductIcon.RotateCw, 20.dp, it) }
                    ToolButton("Flip horizontally (H)", onClick = { change { flipped(horizontal = true) } }) { PpmIcon(ProductIcon.FlipHorizontal, 20.dp, it) }
                    ToolButton("Flip vertically (V)", onClick = { change { flipped(horizontal = false) } }) { PpmIcon(ProductIcon.FlipVertical, 20.dp, it) }
                    Divider()
                    ToolButton("Download", onClick = download) { PpmIcon(ProductIcon.Download, 20.dp, it) }
                    // The web's is off over plain HTTP, where a browser keeps the clipboard from it;
                    // an app is not kept from it.
                    ToolButton("Copy image", onClick = copy) {
                        if (copied) PpmIcon(ProductIcon.Check, 20.dp, green400.color) else PpmIcon(ProductIcon.Copy, 20.dp, it)
                    }
                }
            }
        }
        // `absolute top-3 right-3 z-20`, over the stage.
        CloseButton(onClose, Modifier.align(Alignment.TopEnd).windowInsetsPadding(safe).padding(12.dp))
        if (many) {
            BasicText(
                "${position + 1} / $count",
                Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(safe)
                    .padding(12.dp)
                    // A tap on it is on the counter, not on the dark around the picture.
                    .takesTouches()
                    .clip(CircleShape)
                    .background(white.opacity(0.1).color)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = monoXs.copy(color = white.opacity(0.8).color),
            )
        }
    }
}

/** Hit, so whatever is drawn under it is not. */
private fun Modifier.takesTouches(): Modifier = pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }

/** The toolbar's one-row width: its buttons (44), the zoom readout (72), the dividers (1 + 4 either side), 2 between each, 4 around. */
private fun toolbarWidth(many: Boolean): Float {
    val widths = buildList {
        if (many) addAll(listOf(44f, 44f, 9f))
        addAll(listOf(44f, 72f, 44f, 44f, 44f, 9f, 44f, 44f, 44f, 44f, 9f, 44f, 44f))
    }
    return widths.sum() + 2f * (widths.size - 1) + 8f
}

/**
 * The stage (`flex-1 overflow-hidden touch-none`): the picture in its middle, fitted, under its
 * transform. One finger pans, two zoom about the point between them, a double tap zooms in to
 * 2.5x and out again; a tap on the dark around the picture closes. The web ties that close to a
 * press that started there, so a pinch begun off the picture closed it too, and its double tap
 * never zoomed (`onDoubleClick` is bound as a `doubleclick` event no browser sends): both are
 * done here as they were meant.
 */
@Composable
private fun Stage(
    shown: Shown?,
    transform: ViewTransform,
    layout: StageLayout?,
    onChange: (ViewTransform.(StageLayout) -> ViewTransform) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    // A wheel notch in the web's pixels: Chrome scales the platform's scroll factor to CSS pixels.
    val notch = remember(context, density) { ViewConfiguration.get(context).scaledVerticalScrollFactor / density.density }
    val current by rememberUpdatedState(transform)
    val stage by rememberUpdatedState(layout)
    // The gesture loops start once: what they call is read fresh at each step.
    val change by rememberUpdatedState(onChange)
    val close by rememberUpdatedState(onClose)
    fun onPicture(point: Offset): Boolean {
        val l = stage ?: return false
        val t = current
        val w = (if (t.quarterTurned) l.height else l.width) * t.scale
        val h = (if (t.quarterTurned) l.width else l.height) * t.scale
        return abs(point.x - (l.stageWidth / 2 + t.x)) <= w / 2 && abs(point.y - (l.stageHeight / 2 + t.y)) <= h / 2
    }
    Box(
        modifier
            .clipToBounds()
            .pointerInput(Unit) {
                // @use-gesture's tap threshold: a press that moves less is a tap.
                val slop = 3.dp.toPx()
                var lastTap = 0L
                var lastTapAt = Offset.Zero
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Handled here, so the root does not take it for a tap outside every field.
                    down.consume()
                    val offPicture = !onPicture(down.position)
                    var dragging = false
                    var pinching = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            event.changes.forEach { it.consume() }
                            break
                        }
                        if (pressed.size >= 2) {
                            // Once two fingers are down the drag is over, as the web cancels it.
                            pinching = true
                            val zoom = event.calculateZoom()
                            val centre = event.calculateCentroid(useCurrent = true)
                            if (zoom != 1f) change { zoomedTo(scale * zoom, it, centre.x - it.stageWidth / 2, centre.y - it.stageHeight / 2) }
                        } else if (!pinching) {
                            val finger = pressed.first()
                            if (!dragging && (finger.position - down.position).getDistance() > slop) dragging = true
                            if (dragging) {
                                val step = finger.positionChange()
                                change { panned(step.x, step.y, it) }
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                    if (dragging || pinching) return@awaitEachGesture
                    if (offPicture) {
                        close()
                        return@awaitEachGesture
                    }
                    val now = down.uptimeMillis
                    if (now - lastTap < viewConfiguration.doubleTapTimeoutMillis && (down.position - lastTapAt).getDistance() < viewConfiguration.touchSlop * 4) {
                        lastTap = 0L
                        change { doubleTapped(it, down.position.x - it.stageWidth / 2, down.position.y - it.stageHeight / 2) }
                    } else {
                        lastTap = now
                        lastTapAt = down.position
                    }
                }
            }
            .pointerInput(notch) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val wheel = event.changes.firstOrNull() ?: continue
                        val dx = wheel.scrollDelta.x * notch
                        val dy = wheel.scrollDelta.y * notch
                        if (dx == 0f && dy == 0f) continue
                        wheel.consume()
                        val point = wheel.position
                        // Ctrl + wheel is a trackpad's pinch; a mouse wheel zooms bare; a trackpad's scroll pans.
                        if (event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed || looksLikeMouseWheel(dx, dy)) {
                            change { zoomedTo(scale * wheelZoom(dy), it, point.x - it.stageWidth / 2, point.y - it.stageHeight / 2) }
                        } else {
                            change { panned(-dx * density.density, -dy * density.density, it) }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (shown != null && layout != null) {
            val shape = RoundedCornerShape(12.dp)
            with(density) {
                Box(
                    Modifier
                        .size(layout.width.toDp(), layout.height.toDp())
                        .graphicsLayer {
                            translationX = transform.x
                            translationY = transform.y
                            rotationZ = transform.rotation.toFloat()
                            scaleX = transform.scale * (if (transform.flipX) -1 else 1)
                            scaleY = transform.scale * (if (transform.flipY) -1 else 1)
                        }
                        .ppmShadow(shadow2xl, shape),
                ) {
                    Image(
                        shown.bitmap,
                        shown.file.name,
                        Modifier.fillMaxSize().clip(shape),
                        contentScale = ContentScale.FillBounds,
                        filterQuality = FilterQuality.High,
                    )
                }
            }
        }
    }
}

/**
 * `ToolButton`: `size-11 rounded-lg text-white`, `hover:bg-white/15 active:bg-white/25`, the icon
 * in the accent when [active]; disabled, `pointer-events-none opacity-30`, so no title either.
 */
@Composable
private fun ToolButton(label: String, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(44.dp)
            .alpha(if (enabled) 1f else 0.3f)
            .clip(shape)
            .then(if (enabled) Modifier.webTitle(label) else Modifier)
            .ppmClickable(enabled = enabled, pressedColor = white.opacity(0.25).color, hoverColor = white.opacity(0.15).color, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { icon((if (active) tokens.accent else white).color) }
}

/** The zoom readout, which resets the view: `h-11 min-w-[4.5rem] rounded-lg px-2 font-mono text-xs text-white/80`. */
@Composable
private fun ZoomReadout(percent: Int, onReset: () -> Unit) {
    Box(
        Modifier
            .height(44.dp)
            .widthIn(min = 72.dp)
            .clip(RoundedCornerShape(12.dp))
            .webTitle("Reset view")
            .ppmClickable(pressedColor = white.opacity(0.2).color, hoverColor = white.opacity(0.1).color, onClickLabel = "Reset view", onClick = onReset)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText("$percent%", style = monoXs.copy(color = white.opacity(0.8).color)) }
}

/** `mx-1 h-6 w-px bg-white/20`. */
@Composable
private fun Divider() {
    Box(Modifier.padding(horizontal = 4.dp).size(1.dp, 24.dp).background(white.opacity(0.2).color))
}

/**
 * `size-11 rounded-full bg-white/10 text-white hover:bg-white/20 active:bg-white/30`: its own fill
 * changes, so the hover's is not laid over it.
 */
@Composable
private fun CloseButton(onClose: () -> Unit, modifier: Modifier) {
    val hover = rememberHover()
    val pressed by hover.source.collectIsPressedAsState()
    val fill = if (pressed) white.opacity(0.3) else white.opacity(0.1).mix(white.opacity(0.2), 1.0 - hover.lit)
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(fill.color)
            .hoverable(hover.source)
            .clickable(hover.source, indication = null, onClickLabel = "Close", onClick = onClose)
            .semantics { contentDescription = "Close" },
        contentAlignment = Alignment.Center,
    ) { PpmIcon(ProductIcon.X, 20.dp, white.color) }
}
