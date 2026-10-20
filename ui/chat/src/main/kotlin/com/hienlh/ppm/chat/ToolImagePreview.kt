package com.hienlh.ppm.chat

import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.os.Build
import android.util.LruCache
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTheme
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.text.Bidi
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** An image a tool card previews, read with the server's credentials (`useBlobUrl`). */
sealed interface ToolImage {
    /** [preview] is decoded no larger than the card needs; [bytes] are the file's, for the lightbox; [type] is what it was served as. */
    class Loaded(val preview: ImageBitmap, val natural: IntSize, val bytes: ByteArray, val type: String?) : ToolImage

    /** [code]: the HTTP status or `network`; null when the bytes are not a picture. */
    data class Failed(val code: String?) : ToolImage

    companion object {
        /**
         * [bytes] as the card shows them: turned as the file's EXIF says, as Chrome turns an
         * `<img>`, and decoded at no more than twice the size that keeps the longest side at
         * [PREVIEW_LONGEST_SIDE]. What this device cannot decode fails with no code, as an image
         * the web cannot decode (`naturalWidth` 0) does.
         */
        fun decode(bytes: ByteArray, type: String?): ToolImage {
            val (bitmap, natural) = decodePreview(bytes) ?: return Failed(null)
            // Mipmaps: a picture drawn at a fraction of its size stays smooth, as Chrome draws it.
            bitmap.setHasMipMap(true)
            return Loaded(bitmap.asImageBitmap(), natural, bytes, type?.lowercase())
        }
    }
}

/** The longest side a preview is decoded at least as large as, when the image is that large. */
internal const val PREVIEW_LONGEST_SIDE = 1024

/** The largest power of two to divide [natural] by and still keep [PREVIEW_LONGEST_SIDE]. */
internal fun previewSampleSize(natural: IntSize): Int {
    var sample = 1
    while (max(natural.width, natural.height) / (sample * 2) >= PREVIEW_LONGEST_SIDE) sample *= 2
    return sample
}

private fun decodePreview(bytes: ByteArray): Pair<Bitmap, IntSize>? {
    if (Build.VERSION.SDK_INT >= 28) {
        var natural = IntSize.Zero
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                natural = IntSize(info.size.width, info.size.height)
                // Software, so the backdrop can be scaled down where there is no blur to draw it with.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(previewSampleSize(natural))
            }
        }.getOrNull() ?: return null
        return if (natural.width > 0 && natural.height > 0) bitmap to natural else null
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val natural = IntSize(bounds.outWidth, bounds.outHeight)
    val options = BitmapFactory.Options().apply { inSampleSize = previewSampleSize(natural) }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { it to natural }
}

/** How this chat reads the images its tool cards preview; none, and every preview is unavailable. */
fun interface ToolImages {
    suspend fun load(path: String): ToolImage
}

val LocalToolImages = staticCompositionLocalOf<ToolImages?> { null }

/** The tabs a chat opens (`openTab`): a file in the editor, an edit's diff. */
interface ChatTabs {
    fun openFile(path: String)

    fun openDiff(path: String, original: String, modified: String)
}

/** How this chat opens tabs; none, and its file buttons do nothing, as the web's do without a project. */
val LocalChatTabs = staticCompositionLocalOf<ChatTabs?> { null }

/** One image of a lightbox: its name and the file's bytes. */
class ViewerImage(val name: String, val bytes: ByteArray, val type: String?)

/** The lightbox (`useImageOverlay`): [images] to move between, opened on [index]. */
fun interface ImageViewer {
    fun show(images: List<ViewerImage>, index: Int)
}

/** None, and "full size" does nothing. */
val LocalImageViewer = staticCompositionLocalOf<ImageViewer?> { null }

// Geometry (image-preview-geometry.ts)

internal const val TILE_MIN_RATIO = 0.62
internal const val TILE_MAX_RATIO = 2.4

/**
 * The tile's width, and under a 470 px container its narrow one. The layout is worked out at the
 * wide width whatever the container (the web's is in JavaScript, its widths in container queries),
 * so a picture read as native at 132 is only held inside the 64 px tile, label and all.
 */
internal const val TILE_WIDTH = 132
internal const val TILE_WIDTH_NARROW = 64

/** The ratio the skeleton assumes for an image never measured. */
private const val FALLBACK_ASPECT = 4.0 / 5.0

internal enum class ImageFit {
    /** The box matches the image, so cover crops nothing. */
    COVER,

    /** Taller than the clamp: fitted inside the box, on the checkerboard. */
    CONTAIN,

    /** Smaller than the box: at its own size, centred, never enlarged. */
    NATIVE,
}

/** [band]: a panorama, across the whole stage instead of beside its facts. [aspect]: the box's width over its height. */
internal data class PreviewLayout(val band: Boolean, val aspect: Double, val fit: ImageFit)

/**
 * `previewLayout`: the tile follows the image's ratio so nothing is cropped, within a clamp — a very
 * tall capture would be a sliver, a panorama would waste the width it could use.
 */
internal fun previewLayout(natural: IntSize, tileWidth: Int): PreviewLayout {
    val ratio = natural.width.toDouble() / natural.height
    if (ratio > TILE_MAX_RATIO) return PreviewLayout(band = true, aspect = ratio, fit = ImageFit.CONTAIN)
    val aspect = max(TILE_MIN_RATIO, ratio)
    val boxHeight = tileWidth / aspect
    if (natural.width < tileWidth && natural.height < boxHeight) return PreviewLayout(band = false, aspect = aspect, fit = ImageFit.NATIVE)
    return PreviewLayout(band = false, aspect = aspect, fit = if (ratio < TILE_MIN_RATIO) ImageFit.CONTAIN else ImageFit.COVER)
}

/** `formatBytes`. */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${fixed(bytes / (1024.0 * 1024.0), 1)} MB"
    bytes >= 1024 -> "${Math.round(bytes / 1024.0)} KB"
    else -> "$bytes B"
}

/** `dimensionsLabel`. */
internal fun dimensionsLabel(natural: IntSize, fit: ImageFit): String {
    val base = "${natural.width} × ${natural.height} px"
    return if (fit == ImageFit.NATIVE) "$base · shown 1:1" else base
}

/** Whether [path] names a picture the server will serve (`isImageExtension`); SVG is not one, as it could run script. */
internal fun isImageExtension(path: String): Boolean {
    val dot = path.lastIndexOf('.')
    if (dot < 0) return false
    return path.substring(dot).lowercase() in IMAGE_EXTENSIONS
}

private val IMAGE_EXTENSIONS = setOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".avif", ".ico")

/** Natural sizes outlive the card, so one scrolled away and back keeps its height while it reads again. */
private val naturalSizes = mutableMapOf<String, IntSize>()

/**
 * What each card showed, by call and path. The web's transcript keeps a card mounted, so its
 * picture is read once; a lazy list lets it go off screen, and without this it would be read
 * again, skeleton and all, every time it scrolled back.
 */
private val shownImages by lazy {
    object : LruCache<String, ToolImage.Loaded>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ToolImage.Loaded) = value.preview.width * value.preview.height * 4 + value.bytes.size
    }
}

// The panel

/** `--img-*`: the stage's surfaces are veils over the picture, so each mode has its own. */
private class StageColors(dark: Boolean) {
    private fun navy(alpha: Double) = Rgba(10 / 255.0, 16 / 255.0, 32 / 255.0, alpha)
    private fun white(alpha: Double) = Rgba(1.0, 1.0, 1.0, alpha)
    private fun slate(alpha: Double) = Rgba(15 / 255.0, 23 / 255.0, 42 / 255.0, alpha)

    val veilFrom = if (dark) navy(0.6) else white(0.62)
    val veilTo = if (dark) navy(0.84) else white(0.84)
    val plate = if (dark) white(0.06) else white(0.5)
    val plateBorder = if (dark) Rgba(148 / 255.0, 178 / 255.0, 1.0, 0.22) else white(0.7)
    val plateShadow = if (dark) {
        listOf(ThemeShadow(0.0, 2.0, 4.0, 0.0, Rgba(0.0, 0.0, 0.0, 0.3)), ThemeShadow(0.0, 16.0, 34.0, -20.0, Rgba(0.0, 0.0, 0.0, 0.7)))
    } else {
        listOf(ThemeShadow(0.0, 2.0, 4.0, 0.0, slate(0.14)), ThemeShadow(0.0, 16.0, 34.0, -20.0, slate(0.55)))
    }
    val ghost = if (dark) white(0.08) else white(0.7)
    val ghostBorder = if (dark) white(0.14) else white(0.85)
    val metaLabel = Rgba.hex(if (dark) "#8b97b4" else "#7c8899")!!
    val checker = if (dark) white(0.04) else slate(0.03)
    val skeleton = if (dark) white(0.09) else slate(0.07)
    val sweep = if (dark) white(0.14) else white(0.75)
}

/** `font-mono text-[11.5px]`, on the line the card's `text-xs` sets: 11.5 × 4/3. */
private val metaText = ppmText(11.5f, kind = FontKind.MONO, lineHeight = 11.5f * 4 / 3)

/**
 * `ToolImagePreview`: the image a Read (or Codex's ImageGen) opened, on a stage lit by a blurred
 * copy of itself, beside what is needed to decide whether to open it. [cardKey] names the call, so
 * the card shows what it read whenever it comes back on screen; the size it measured is kept for
 * every card, so a skeleton holds the loaded panel's box and the transcript does not move.
 */
@Composable
internal fun ToolImagePreview(path: String, cardKey: String?) {
    val tokens = LocalPpmTokens.current
    val images = LocalToolImages.current
    val cacheKey = cardKey?.let { "$it\u0000$path" }
    var attempt by remember(path) { mutableIntStateOf(0) }
    val image by produceState<ToolImage?>(cacheKey?.let(shownImages::get), path, images, attempt) {
        if (value is ToolImage.Loaded) return@produceState
        value = null
        val read = images?.load(path) ?: ToolImage.Failed(null)
        if (read is ToolImage.Loaded) {
            naturalSizes[path] = read.natural
            cacheKey?.let { shownImages.put(it, read) }
        }
        value = read
    }
    val colors = StageColors(LocalPpmTheme.current.isDark)
    // `@container border-t pt-1.5`: under 470 px the tile narrows and the labels go.
    BoxWithConstraints(Modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = CssSides.Top).padding(top = 6.dp)) {
        val narrow = maxWidth < 470.dp
        when (val shown = image) {
            is ToolImage.Failed -> ImagePreviewFailure(shown.code) { attempt++ }
            is ToolImage.Loaded -> Stage(shown, path, narrow, colors)
            null -> {
                val aspect = naturalSizes[path]?.let { previewLayout(it, TILE_WIDTH).aspect } ?: FALLBACK_ASPECT
                ImagePreviewSkeleton(aspect, narrow, colors) { Actions(path, narrow, colors, onFull = {}) }
            }
        }
    }
}

/** A rounded box's inside, where `overflow-hidden` clips: its radius less the border, as Chrome snaps the border. */
@Composable
private fun innerShape(radius: Float): Shape = with(LocalDensity.current) { RoundedCornerShape(radius.dp.toPx() - cssBorderPx(1.dp)) }

/** The picture on its stage: a tile beside the facts, or across the stage when it is a panorama. */
@Composable
private fun Stage(image: ToolImage.Loaded, path: String, narrow: Boolean, colors: StageColors) {
    val tokens = LocalPpmTokens.current
    val viewer = LocalImageViewer.current
    val layout = previewLayout(image.natural, TILE_WIDTH)
    val name = basename(path)
    val openFull = { viewer?.show(listOf(ViewerImage(name, image.bytes, image.type)), 0) ?: Unit }
    val shape = RoundedCornerShape(8.dp)
    val inner = innerShape(8f)
    val gap = if (narrow) 11.dp else 14.dp
    Box(Modifier.fillMaxWidth().clip(shape).cssBorder(tokens.border.color, shape)) {
        Box(Modifier.matchParentSize().clip(inner)) {
            Backdrop(image.preview, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(CssLinearGradient(100.0, listOf(colors.veilFrom.color, colors.veilTo.color))))
        }
        if (layout.band) {
            Column(Modifier.fillMaxWidth().padding(gap), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Tile(image, layout, name, band = true, colors, Modifier.fillMaxWidth(), openFull)
                BandFacts(image, layout, path, narrow)
                Actions(path, narrow, colors, openFull)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(gap), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Tile(image, layout, name, band = false, colors, Modifier.width((if (narrow) TILE_WIDTH_NARROW else TILE_WIDTH).dp), openFull)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (!narrow) {
                        BasicText(
                            name,
                            style = ppmText(13f, FontWeight.SemiBold, lineHeight = 13f * 4 / 3).copy(color = tokens.text.color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Facts(image, layout, path, narrow, colors)
                    Actions(path, narrow, colors, openFull)
                }
            }
        }
    }
}

/**
 * The picture itself, `absolute inset-0 object-cover`, blurred then saturated (`--img-backdrop`, in
 * that order: saturating first would clip what the blur then averages) and then scaled by 1.3, as
 * CSS transforms what it has filtered. What lies past its edges blurs in as transparent, so the
 * card's panel shows through near them.
 */
@Composable
private fun Backdrop(image: ImageBitmap, modifier: Modifier) {
    val dark = LocalPpmTheme.current.isDark
    val matrix = remember(dark) {
        ColorMatrix().apply {
            setToSaturation(1.6f)
            // `brightness(0.95)` in light mode, after the saturation.
            if (!dark) timesAssign(ColorMatrix().apply { setToScale(0.95f, 0.95f, 0.95f, 1f) })
        }
    }
    val scaled = modifier.graphicsLayer {
        scaleX = 1.3f
        scaleY = 1.3f
    }
    if (Build.VERSION.SDK_INT >= 31) {
        // CSS blurs with a standard deviation of 40 px; Android's blur of radius r with one of 0.57735 r + 0.5.
        val radius = with(LocalDensity.current) { (40.dp.toPx() - 0.5f) / 0.57735f }
        val effect = remember(radius, matrix) {
            val blur = android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.DECAL)
            android.graphics.RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix.values), blur).asComposeRenderEffect()
        }
        Image(image, null, scaled.graphicsLayer { renderEffect = effect }, contentScale = ContentScale.Crop)
    } else {
        // No blur to draw with before Android 12: the picture a dozen pixels across, stretched, is as soft.
        val tiny = remember(image) { shrink(image, 12) }
        Image(tiny, null, scaled, contentScale = ContentScale.Crop, colorFilter = ColorFilter.colorMatrix(matrix))
    }
}

private fun shrink(image: ImageBitmap, width: Int): ImageBitmap {
    val bitmap = image.asAndroidBitmap()
    val height = max(1, bitmap.height * width / max(1, bitmap.width))
    return Bitmap.createScaledBitmap(bitmap, width, height, true).asImageBitmap()
}

/**
 * The tile (`cursor-zoom-in overflow-hidden rounded-lg border`): on a plate with its shadow beside
 * the facts, on the checkerboard where it letterboxes or sits at its own size; a tap opens the
 * lightbox.
 */
@Composable
private fun Tile(image: ToolImage.Loaded, layout: PreviewLayout, name: String, band: Boolean, colors: StageColors, modifier: Modifier, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val checkered = band || layout.fit != ImageFit.COVER
    var tile = modifier.aspectRatio(layout.aspect.toFloat())
    if (!band) tile = colors.plateShadow.fold(tile) { m, shadow -> m.ppmShadow(shadow, shape) }
    tile = tile.clip(shape)
    if (!band) tile = tile.background(colors.plate.color)
    if (checkered) tile = tile.checkerboard(colors.checker.color)
    tile = tile
        .cssBorder(colors.plateBorder.color, shape)
        .clip(innerShape(8f))
        .pointerHoverIcon(WebCursor.ZoomIn)
        .ppmClickable(pressedColor = Color.Transparent, onClickLabel = "Open $name", onClick = onOpen)
    // `.img-checkerboard`'s padding keeps the artwork off the tile's edge.
    if (checkered) tile = tile.padding(6.dp)
    Box(tile, contentAlignment = Alignment.Center) {
        when (layout.fit) {
            ImageFit.COVER -> Image(image.preview, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            ImageFit.CONTAIN -> Image(image.preview, name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            // `max-h-full max-w-full`: its own size in CSS pixels, held inside the box.
            ImageFit.NATIVE -> Image(image.preview, name, Modifier.size(image.natural.width.dp, image.natural.height.dp), contentScale = ContentScale.Fit)
        }
    }
}

/**
 * `.img-checkerboard`: 7 px squares of `--img-checker`, every other one, counted from inside the
 * border as a background is (`background-origin: padding-box`) and painted under it as well.
 */
private fun Modifier.checkerboard(color: Color): Modifier = drawBehind {
    val square = 7.dp.toPx()
    val origin = cssBorderPx(1.dp).toFloat()
    var row = -1
    while (origin + row * square < size.height) {
        var column = -1
        while (origin + column * square < size.width) {
            if (Math.floorMod(row + column, 2) == 1) drawRect(color, Offset(origin + column * square, origin + row * square), Size(square, square))
            column++
        }
        row++
    }
}

/**
 * The facts beside a tile (`grid-cols-[auto_1fr] gap-x-[14px] gap-y-[4px]`): dimensions, size and
 * type, path; under 470 px the labels go and the values dim to `text-2`.
 */
@Composable
private fun Facts(image: ToolImage.Loaded, layout: PreviewLayout, path: String, narrow: Boolean, colors: StageColors) {
    val value = valueText(narrow)
    FactRows(narrow, colors) { index, modifier ->
        when (index) {
            0 -> BasicText(dimensionsLabel(image.natural, layout.fit), modifier, value, maxLines = 1, overflow = TextOverflow.Ellipsis)
            1 -> BasicText(sizeLabel(image), modifier, value, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else -> RtlTruncated(path, value, modifier)
        }
    }
}

/** The three rows, each its label (when wide) beside what [value] draws in the rest of the row. */
@Composable
private fun FactRows(narrow: Boolean, colors: StageColors, value: @Composable (Int, Modifier) -> Unit) {
    val label = metaText.copy(color = colors.metaLabel.color)
    val measurer = rememberTextMeasurer()
    // `auto`: the column is as wide as its widest label.
    val labelWidth = with(LocalDensity.current) { remember(label) { measurer.measure("dimensions", label).size.width }.toDp() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("dimensions", "size", "path").forEachIndexed { index, name ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (!narrow) BasicText(name, Modifier.width(labelWidth), label, maxLines = 1)
                value(index, Modifier.weight(1f))
            }
        }
    }
}

/** `truncate text-text`, `text-text-2` under 470 px. */
@Composable
private fun valueText(narrow: Boolean): TextStyle {
    val tokens = LocalPpmTokens.current
    return metaText.copy(color = (if (narrow) tokens.text2 else tokens.text).color)
}

/** A panorama's facts on one line (`grid-cols-[auto_auto_1fr] gap-x-[18px]`), values only. */
@Composable
private fun BandFacts(image: ToolImage.Loaded, layout: PreviewLayout, path: String, narrow: Boolean) {
    val value = valueText(narrow)
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        BasicText(dimensionsLabel(image.natural, layout.fit), style = value, maxLines = 1)
        BasicText(sizeLabel(image), style = value, maxLines = 1)
        RtlTruncated(path, value, Modifier.weight(1f))
    }
}

private fun sizeLabel(image: ToolImage.Loaded): String =
    formatBytes(image.bytes.size.toLong()) + (image.type?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")

/**
 * `<dd dir="rtl" class="truncate text-left">`: the path laid out as a right-to-left box lays it
 * out — its leading `/` lands on the right, as it does on the web — and, when it does not fit, cut
 * on the left, where that box puts its ellipsis, so the file name stays in view.
 */
@Composable
private fun RtlTruncated(text: String, style: TextStyle, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val drawn = style.copy(textAlign = TextAlign.Left, textDirection = TextDirection.Ltr)
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val shown = remember(text, width, drawn) {
            val visual = rtlVisualOrder(text)
            fun fits(candidate: String) = measurer.measure(candidate, drawn, softWrap = false, maxLines = 1).size.width <= width
            if (fits(visual)) {
                visual
            } else {
                // The fewest characters cut from the left that let the rest fit behind an ellipsis
                // (after the override mark, which stays first).
                var low = 2
                var high = visual.length
                while (low < high) {
                    val mid = (low + high) / 2
                    if (fits(LRO + "…" + visual.substring(mid))) high = mid else low = mid + 1
                }
                LRO + "…" + visual.substring(low)
            }
        }
        BasicText(shown, style = drawn, maxLines = 1, softWrap = false)
    }
}

/** Left-to-right override: what follows is already in the order it is drawn in. */
private const val LRO = "‭"

/**
 * [text] in the order a right-to-left paragraph draws it, left to right (UAX #9), behind a
 * left-to-right override so it is drawn as it reads here; a right-to-left run's brackets are
 * mirrored, as drawing it would mirror them.
 */
internal fun rtlVisualOrder(text: String): String {
    if (text.isEmpty()) return text
    val bidi = Bidi(text, Bidi.DIRECTION_RIGHT_TO_LEFT)
    val count = bidi.runCount
    val levels = ByteArray(count) { bidi.getRunLevel(it).toByte() }
    val runs = Array<Any>(count) { i ->
        val run = text.substring(bidi.getRunStart(i), bidi.getRunLimit(i))
        if (bidi.getRunLevel(i) % 2 == 1) run.reversed().map { MIRRORED[it] ?: it }.joinToString("") else run
    }
    Bidi.reorderVisually(levels, 0, runs, 0, count)
    return LRO + runs.joinToString("")
}

private val MIRRORED = mapOf('(' to ')', ')' to '(', '[' to ']', ']' to '[', '{' to '}', '}' to '{', '<' to '>', '>' to '<')

/**
 * The three actions (`flex flex-wrap gap-[6px]`): open in the editor, copy the path, the full size.
 * The web lights them on `can-hover:hover:` only, which Chrome on Android never applies.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(path: String, narrow: Boolean, colors: StageColors, onFull: () -> Unit) {
    val tabs = LocalChatTabs.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_200)
            copied = false
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GhostButton(if (narrow) "open" else "open in editor", narrow, colors) { tabs?.openFile(path) }
        GhostButton(if (copied) "copied" else "copy path", narrow, colors) {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("path", path)))
                copied = true
            }
        }
        GhostButton("full size", narrow, colors, onFull)
    }
}

/** `GHOST`: 30 px tall (36 under 470), `rounded-[7px] px-[9px]`, mono 11.5 px on a frosted pane. */
@Composable
private fun GhostButton(label: String, narrow: Boolean, colors: StageColors, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier
            .height(if (narrow) 36.dp else 30.dp)
            .clip(shape)
            .background(colors.ghost.color)
            .cssBorder(colors.ghostBorder.color, shape)
            .ppmClickable(pressedColor = Color.Transparent, onClick = onClick)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = metaText.copy(color = tokens.text2.color), maxLines = 1, softWrap = false)
    }
}

/**
 * `ImagePreviewSkeleton`: the loaded panel's box — the tile's ratio, the three facts, the actions —
 * so nothing moves when the image lands; the bars sweep as `.img-skeleton` does.
 */
@Composable
private fun ImagePreviewSkeleton(aspect: Double, narrow: Boolean, colors: StageColors, actions: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val gap = if (narrow) 11.dp else 14.dp
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_250, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, shape)
            .padding(gap),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        Box(Modifier.width((if (narrow) TILE_WIDTH_NARROW else TILE_WIDTH).dp).aspectRatio(aspect.toFloat()).skeleton(colors, { sweep }, shape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (!narrow) Box(Modifier.fillMaxWidth(0.4f).height(13.dp).skeleton(colors, { sweep }, RoundedCornerShape(4.dp)))
            // The labels stay as text, so only the values arrive later; each bar is centred on its row.
            FactRows(narrow, colors) { index, modifier ->
                Box(modifier.height(with(LocalDensity.current) { metaText.lineHeight.toDp() }), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.fillMaxWidth(BAR_WIDTHS[index]).height(11.dp).skeleton(colors, { sweep }, RoundedCornerShape(4.dp)))
                }
            }
            actions()
        }
    }
}

/** Widths that keep the bars from reading as one block. */
private val BAR_WIDTHS = listOf(0.6f, 0.45f, 0.8f)

/** `.img-skeleton`: `--img-skeleton` with a lighter band swept across it, left to right. */
private fun Modifier.skeleton(colors: StageColors, sweep: () -> Float, shape: Shape): Modifier =
    clip(shape).background(colors.skeleton.color).drawBehind {
        val band = CssLinearGradient(100.0, listOf(Color.Transparent, colors.sweep.color, Color.Transparent), listOf(0.2f, 0.5f, 0.8f))
        translate(sweep() * size.width, 0f) { drawRect(band) }
    }

/** `ImagePreviewFailure`: a chip, since there is nothing to show — and a retry. */
@Composable
private fun ImagePreviewFailure(code: String?, onRetry: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val text = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(ProductIcon.AlertCircle, 12.dp, tokens.text2.color)
        // `truncate`, with the retry pushed to the far end (`ml-auto`).
        BasicText(
            "preview unavailable" + (code?.let { " · $it" } ?: ""),
            Modifier.weight(1f),
            text.copy(color = tokens.text2.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BasicText("retry", Modifier.ppmClickable(pressedColor = Color.Transparent, onClick = onRetry), text.copy(color = tokens.accent.color))
    }
}

/**
 * CSS `linear-gradient(<angle>deg, …)`: along the line through the centre at [angle] (0 points up,
 * 90 right), as long as the box's corners need, with [colors] at [stops] (spread evenly when null).
 */
internal class CssLinearGradient(
    private val angle: Double,
    private val colors: List<Color>,
    private val stops: List<Float>? = null,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val radians = Math.toRadians(angle)
        val direction = Offset(sin(radians).toFloat(), -cos(radians).toFloat())
        val half = (abs(size.width * direction.x) + abs(size.height * direction.y)) / 2
        val center = Offset(size.width / 2, size.height / 2)
        return LinearGradientShader(center - direction * half, center + direction * half, colors, stops)
    }

    override fun equals(other: Any?) = other is CssLinearGradient && other.angle == angle && other.colors == colors && other.stops == stops

    override fun hashCode() = angle.hashCode() * 31 + colors.hashCode()
}
