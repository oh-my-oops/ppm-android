package com.hienlh.ppm.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.GalleryImage
import com.hienlh.ppm.design.LocalImageViewer
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ViewerImage
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** How a chat reads the pictures its markdown shows (`MdImage`); none, and every one is broken. */
interface MarkdownImages {
    /** A path on the server's disk, read with its credentials (`/api/fs/raw`). */
    suspend fun local(path: String): ToolImage

    /** An `http(s)` address, read as a browser reads an `<img>`'s: from wherever it is, with nothing of PPM's. */
    suspend fun web(url: String): ToolImage
}

val LocalMarkdownImages = staticCompositionLocalOf<MarkdownImages?> { null }

/** Which of a chat's blocks a markdown is, so that each picture it shows is one of the chat's gallery; none outside a chat. */
internal val LocalMarkdownImageScope = staticCompositionLocalOf<String?> { null }

/** A markdown picture's lightbox key: the web collects every `<img>`, so each block's is a picture of its own. */
internal fun markdownImageKey(scope: String?, src: String) = "md:${scope.orEmpty()}\u0000$src"

/** Where MdImage reads a picture from: PPM for a path on the server's disk, the browser for an address, nothing for the rest. */
internal enum class MarkdownImageSource { LOCAL, WEB, NONE }

/** `LOCAL_PATH_RE`. */
private val LOCAL_PATH = Regex("""^(/|[A-Za-z]:[/\\])""")

private val WEB_URL = Regex("^https?://", RegexOption.IGNORE_CASE)

/** A relative path, `data:` or `file:` never loads on the web: the page's own address is no file server. */
internal fun markdownImageSource(src: String): MarkdownImageSource = when {
    LOCAL_PATH.containsMatchIn(src) -> MarkdownImageSource.LOCAL
    WEB_URL.containsMatchIn(src) -> MarkdownImageSource.WEB
    else -> MarkdownImageSource.NONE
}

/** [src] read from where MdImage reads it, and kept once it is shown. */
internal suspend fun MarkdownImages.read(src: String): ToolImage {
    val read = when (markdownImageSource(src)) {
        MarkdownImageSource.LOCAL -> local(src)
        MarkdownImageSource.WEB -> web(src)
        MarkdownImageSource.NONE -> return ToolImage.Failed(null)
    }
    if (read is ToolImage.Loaded) {
        shownImages.put(markdownCacheKey(src), read)
        brokenSources.remove(src)
    } else {
        brokenSources.add(src)
    }
    return read
}

/** Sources that did not load the last time they were read. */
private val brokenSources: MutableSet<String> = ConcurrentHashMap.newKeySet()

/**
 * Whether [src] is a picture that will not show. `collectGallery` means to gather only images
 * whose source is good, so the lightbox does not step onto one; the web's test (`!!img.src`)
 * lets a broken one through, and this keeps it out.
 */
internal fun markdownImageBroken(src: String) = markdownImageSource(src) == MarkdownImageSource.NONE || src in brokenSources

/**
 * What a markdown picture showed, by source, kept with the tool cards' pictures: the web's
 * transcript keeps a message mounted and its picture read, a lazy list lets it go off screen.
 */
internal fun markdownImageShown(src: String): ToolImage.Loaded? = shownImages.get(markdownCacheKey(src))

private fun markdownCacheKey(src: String) = "md\u0000$src"

/**
 * `MdImage`: a block (`display: block` in Tailwind's preflight) at the picture's own size, scaled
 * to fit the line and 400 px, with a 1px border rounded 8px; a tap opens the lightbox on it. One
 * that does not load is Chrome's broken image, the line's width: its icon, then its alt text. A
 * path is shown that way, faded and 48 px tall, while PPM reads it — the web's `<img>` holds the
 * path itself until the blob comes, which no browser can load.
 */
@Composable
internal fun MarkdownImage(image: InlineMarkdown.Image, style: MarkdownStyle, size: ImageSize? = null) {
    val images = LocalMarkdownImages.current
    val src = image.url
    val name = image.alt.ifEmpty { basename(src) }
    val source = markdownImageSource(src)
    val state = remember(src, images) {
        mutableStateOf(markdownImageShown(src) ?: if (images == null || source == MarkdownImageSource.NONE) ToolImage.Failed(null) else null)
    }
    LaunchedEffect(state) {
        if (state.value == null) state.value = images?.read(src) ?: ToolImage.Failed(null)
    }
    when (val shown = state.value) {
        is ToolImage.Loaded -> LoadedImage(shown, src, name, size)
        is ToolImage.Failed -> BrokenImage(name, style, Modifier.attributeBox(size))
        null -> if (source == MarkdownImageSource.LOCAL) {
            BrokenImage(name, style, Modifier.attributeBox(size).alpha(0.3f).heightIn(min = 48.dp))
        } else {
            // An address the browser is still fetching: a box with no size yet, its border only.
            Box(Modifier.attributeBox(size).fillMaxWidth().cssBorder(LocalPpmTokens.current.border.color, RoundedCornerShape(8.dp)))
        }
    }
}

@Composable
private fun LoadedImage(image: ToolImage.Loaded, src: String, name: String, size: ImageSize?) {
    val tokens = LocalPpmTokens.current
    val gallery = LocalChatGallery.current
    val viewer = LocalImageViewer.current
    val scope = LocalMarkdownImageScope.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .then(ImageBoxElement(image.natural, size))
            .pointerHoverIcon(WebCursor.Pointer)
            .ppmClickable(pressedColor = Color.Transparent, onClickLabel = "Open $name") {
                val self = GalleryImage(markdownImageKey(scope, src), name) { ViewerImage(name, image.bytes, image.type) }
                if (gallery != null) gallery.open(self) else viewer?.show(listOf(self), 0)
            }
            .cssBorder(tokens.border.color, shape)
            .clip(innerShape(8f)),
    ) {
        Image(image.preview, name, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    }
}

/** Chrome's broken image: the icon floats at the start of the alt text, which wraps under it past its first line. */
@Composable
private fun BrokenImage(name: String, style: MarkdownStyle, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val indent = with(LocalDensity.current) { 16.dp.toSp() }
    // What it holds is clipped to the rounded border, the icon's corner with it.
    Box(modifier.fillMaxWidth().cssBorder(tokens.border.color, RoundedCornerShape(8.dp)).clip(innerShape(8f))) {
        BasicText(
            name,
            style = ppmText(style.size, style.weight, lineHeight = style.lineHeight, italic = style.italic)
                .copy(color = (style.color ?: tokens.text).color, textIndent = TextIndent(firstLine = indent)),
        )
        BrokenImageIcon(Modifier.size(16.dp))
    }
}

private val sky = arrayOf(0f to Color(214, 228, 252), 0.1f to Color(207, 223, 249), 1f to Color(183, 201, 234))
private val hill = Color(88, 174, 57)
private val outline = Color(117, 117, 118)
private val fold = Color(250, 250, 250)
private val crease = Color(177, 177, 177)

/**
 * Chrome's broken-image icon, 16 px: a picture of a sky, a cloud and a hill on a page with its
 * corner folded, torn across. Measured off Chrome's own on a 2.625 screen, in 16ths of the icon.
 */
@Composable
private fun BrokenImageIcon(modifier: Modifier) {
    // Offscreen, so that the tear is cut out of it with an edge as smooth as the rest's.
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val u = size.width / 16f
        fun at(x: Float, y: Float) = Offset(x * u, y * u)
        fun shape(vararg points: Float) = Path().apply {
            moveTo(points[0] * u, points[1] * u)
            for (i in 2 until points.size step 2) lineTo(points[i] * u, points[i + 1] * u)
            close()
        }
        val page = shape(0.3f, 0.3f, 11.2f, 0.3f, 15.7f, 4.4f, 15.7f, 15.7f, 0.3f, 15.7f)
        val tear = shape(5.73f, 16.5f, 16.8f, 7.53f, 16.8f, 10.5f, 15.05f, 11.24f, 14.48f, 11.62f, 13.52f, 12.38f, 12f, 13.9f, 11.24f, 14.67f, 10.86f, 15.43f, 10.4f, 16.5f)
        val ground = Path().apply {
            moveTo(-0.5f * u, 17f * u)
            cubicTo(2.5f * u, 12.5f * u, 5.2f * u, 9.9f * u, 7.6f * u, 9.9f * u)
            cubicTo(10f * u, 9.9f * u, 12.7f * u, 12.5f * u, 15.7f * u, 17f * u)
            close()
        }
        val line = 0.5f * u
        drawPath(page, Brush.verticalGradient(*sky, startY = 0.5f * u, endY = 15.5f * u))
        drawPath(Path().apply { op(ground, page, PathOperation.Intersect) }, hill)
        clipRect(bottom = 6.5f * u) {
            drawCircle(Color.White, 1.6f * u, at(5.4f, 4.6f))
            drawCircle(Color.White, 0.95f * u, at(6.6f, 5.6f))
            drawCircle(Color.White, 1.2f * u, at(3.6f, 5.6f))
            drawRoundRect(Color.White, at(2f, 5.5f), Size(5.4f * u, 1f * u), CornerRadius(0.5f * u))
        }
        drawPath(shape(11.2f, 0.3f, 15.7f, 4.4f, 11.2f, 4.4f), fold)
        drawLine(crease, at(11.2f, 0.3f), at(11.2f, 4.4f), line)
        drawLine(crease, at(11.2f, 4.4f), at(15.7f, 4.4f), line)
        drawPath(page, outline, style = Stroke(width = line))
        drawPath(tear, Color.Black, blendMode = BlendMode.Clear)
    }
}

/** `max-h-[400px]`. */
private const val MAX_HEIGHT = 400

/**
 * An `<img>`'s `width` and `height` attributes, CSS px; the width a percentage of the line when
 * [percent]. Tailwind's preflight sets `height: auto` over the height, which then only gives the
 * box its shape until the picture comes (`aspect-ratio: auto w / h`).
 */
internal data class ImageSize(val width: Float?, val percent: Boolean, val height: Float?)

/** The border box the `width` attribute gives against a line [maxWidth] wide, which caps it (`max-w-full`); null when it gives none. */
private fun Density.attributeWidth(size: ImageSize?, maxWidth: Int): Int? {
    val width = size?.width ?: return null
    val px = if (size.percent) {
        if (maxWidth == Constraints.Infinity) return null
        maxWidth * width / 100f
    } else {
        width.dp.toPx()
    }
    return minOf(px.roundToInt(), maxWidth).coerceAtLeast(2 * cssBorderPx(1.dp))
}

/**
 * A picture not shown, or not yet, in the box its attributes give it: the `width` attribute's,
 * and as tall as their ratio makes its content box when both are set; else as tall as what it holds.
 */
private fun Modifier.attributeBox(size: ImageSize?): Modifier = if (size?.width == null) this else layout { measurable, constraints ->
    val width = attributeWidth(size, constraints.maxWidth)
    if (width == null) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val border = 2 * cssBorderPx(1.dp)
    val height = size.height?.takeIf { !size.percent && size.width > 0f }?.let { ((width - border) * it / size.width).roundToInt() + border }
    val placeable = measurable.measure(if (height != null) Constraints.fixed(width, height) else Constraints.fixedWidth(width))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

private data class ImageBoxElement(val natural: IntSize, val size: ImageSize?) : ModifierNodeElement<ImageBoxNode>() {
    override fun create() = ImageBoxNode(natural, size)

    override fun update(node: ImageBoxNode) {
        node.natural = natural
        node.size = size
        node.invalidateMeasurement()
    }
}

/**
 * The `<img>`'s box under `max-w-full max-h-[400px]` and `box-sizing: border-box`: the picture at
 * its own size, a pixel of it a CSS pixel, scaled down until it fits the line and 400 px with its
 * border. A `width` attribute sets the width, the line capping it, and the picture's ratio the
 * height, which 400 px caps alone, squeezing the picture (`object-fit: fill`). A table column may
 * squeeze it to nothing, as CSS lets it squeeze a replaced element with a percentage `max-width`.
 */
private class ImageBoxNode(var natural: IntSize, var size: ImageSize?) : Modifier.Node(), LayoutModifierNode {
    private fun Density.box(maxWidth: Int): IntSize {
        val border = 2 * cssBorderPx(1.dp)
        val width = natural.width.dp.toPx()
        val height = natural.height.dp.toPx()
        val set = attributeWidth(size, maxWidth)
        if (set != null) {
            val content = if (width > 0f) (set - border) * height / width else 0f
            return IntSize(set, minOf(content.roundToInt() + border, MAX_HEIGHT.dp.roundToPx()))
        }
        val scale = minOf(1f, (maxWidth - border) / width, (MAX_HEIGHT.dp.toPx() - border) / height).coerceAtLeast(0f)
        return IntSize((width * scale).roundToInt() + border, (height * scale).roundToInt() + border)
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val size = box(constraints.maxWidth)
        val placeable = measurable.measure(Constraints.fixed(size.width, size.height))
        return layout(size.width, size.height) { placeable.place(0, 0) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) = 2 * cssBorderPx(1.dp)

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) = box(Constraints.Infinity).width

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) = box(width).height

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) = box(width).height
}
