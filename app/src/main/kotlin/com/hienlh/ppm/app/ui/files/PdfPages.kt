package com.hienlh.ppm.app.ui.files

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTheme
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.chromeScrollbar
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollbar
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val PDF_MIN_ZOOM: Float = 0.5f
const val PDF_MAX_ZOOM: Float = 3.0f
const val PDF_ZOOM_STEP: Float = 1.25f
internal const val PDF_MAX_DIMENSION_PX: Double = 4096.0
internal const val PDF_MAX_PIXELS: Double = 4_194_304.0

/** What the PDF view draws its pages from: the size of each, and a bitmap of one at a width. [PdfPages] is the real one. */
internal interface PdfPageSource {
    val sizes: List<PdfPages.Size>

    /** Call on IO. Null once the source is closed. */
    fun render(index: Int, requestedWidth: Int): Bitmap?
}

internal val PdfPageSource.pageCount: Int
    get() = sizes.size

/** PdfRenderer owns its descriptor; at most one page may be open, including while closing. */
internal class PdfPages private constructor(private val file: File, private val renderer: PdfRenderer) : AutoCloseable, PdfPageSource {
    data class Size(val width: Int, val height: Int) {
        val aspectRatio: Float
            get() = if (width > 0) height.toFloat() / width.toFloat() else 1.4142f
    }

    override val sizes: List<Size> = List(renderer.pageCount) { index -> renderer.openPage(index).use { Size(it.width, it.height) } }
    private var closed = false

    /** Call on IO. Only visible pages have bitmaps; cap each allocation at 16MiB. */
    @Synchronized
    override fun render(index: Int, requestedWidth: Int): Bitmap? {
        if (closed) return null
        val size = sizes[index]
        val factor = computePdfRenderFactor(size, requestedWidth)
        val bitmap = Bitmap.createBitmap((size.width * factor).roundToInt().coerceAtLeast(1),
            (size.height * factor).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(AndroidColor.WHITE)
            renderer.openPage(index).use { it.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try { renderer.close() } finally { file.delete() }
    }

    companion object {
        /** Worker thread only. Every failed constructor releases both descriptor and cache file. */
        fun open(cache: File, bytes: ByteArray): PdfPages {
            val dir = File(cache, "pdf-render").apply { mkdirs() }
            val file = File.createTempFile("page-", ".pdf", dir)
            var descriptor: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            try {
                file.writeBytes(bytes)
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(descriptor)
                return PdfPages(file, renderer)
            } catch (error: Throwable) {
                if (renderer != null) renderer.close() else descriptor?.close()
                file.delete()
                throw error
            }
        }
    }
}

/**
 * Computes the bitmap scale factor for a PDF page of [size] at [requestedWidth], capped so neither
 * side exceeds 4096 px and the total pixel count stays within 4M pixels (16 MiB at ARGB_8888).
 */
internal fun computePdfRenderFactor(size: PdfPages.Size, requestedWidth: Int): Double {
    if (size.width <= 0 || size.height <= 0 || requestedWidth <= 0) return 1.0
    return minOf(
        requestedWidth.toDouble() / size.width,
        PDF_MAX_DIMENSION_PX / maxOf(size.width, size.height),
        sqrt(PDF_MAX_PIXELS / size.width / size.height),
    )
}

/** Clamps a 1-based [page] number into `1..pageCount`, or returns `0` when [pageCount] is `<= 0`. */
fun clampPdfPage(page: Int, pageCount: Int): Int =
    if (pageCount <= 0) 0 else page.coerceIn(1, pageCount)

/**
 * Parses a user-entered 1-based page number (`Phone 3 · V2`: "Tap the page count to type a page
 * number") and clamps it to `1..pageCount`, or returns `null` when [input] is not a positive number.
 */
fun parsePdfPageJump(input: String, pageCount: Int): Int? {
    if (pageCount <= 0) return null
    val parsed = input.trim().toIntOrNull() ?: return null
    if (parsed <= 0) return null
    return clampPdfPage(parsed, pageCount)
}

/** Steps [zoom] up or down by [step] (`1.25f`), clamped to `[minZoom, maxZoom]` (`0.5f..3.0f`). */
fun stepPdfZoom(
    zoom: Float,
    zoomIn: Boolean,
    minZoom: Float = PDF_MIN_ZOOM,
    maxZoom: Float = PDF_MAX_ZOOM,
    step: Float = PDF_ZOOM_STEP,
): Float {
    val raw = if (zoomIn) zoom * step else zoom / step
    return raw.coerceIn(minZoom, maxZoom)
}

/** Formats the 1-based page counter (`"3 / 12"`). */
fun formatPdfPageCounter(currentPage: Int, pageCount: Int): String {
    val clamped = clampPdfPage(currentPage, pageCount)
    return "$clamped / ${pageCount.coerceAtLeast(0)}"
}

/** Formats a PDF zoom factor as an integer percentage (`"84%"`, `"100%"`). */
fun formatPdfZoomPercent(zoom: Float): String = "${(zoom * 100f).roundToInt()}%"

/** State of a PDF document rendered page by page (`Requirement 8.2`, `Phone 3 · V2`). */
sealed interface PdfLoadState {
    data object Loading : PdfLoadState
    data class Ready(val pageCount: Int) : PdfLoadState
    data class Error(val message: String, val detail: String? = null) : PdfLoadState
}

/** Resolves the [PdfLoadState] from an optional [error] and opened [pageCount]. */
fun resolvePdfLoadState(error: String?, pageCount: Int?): PdfLoadState = when {
    error != null -> PdfLoadState.Error(message = "Could not open PDF", detail = error)
    pageCount == null -> PdfLoadState.Loading
    pageCount <= 0 -> PdfLoadState.Error(message = "Could not open PDF")
    else -> PdfLoadState.Ready(pageCount)
}

/**
 * Shared page-by-page PDF canvas (`Phone 3 · V2` and `Tablet 3`, Requirement 8.2).
 *
 * Pages scroll top to bottom in a [LazyColumn], each rendered lazily to a [Bitmap] on [Dispatchers.IO]
 * via [PdfPageSource.render]. A floating page chip (`pgno3`, `"3 / 12"`) sits at the top right and
 * opens the page-jump picker when tapped.
 */
@Composable
internal fun PdfPageByPageCanvas(
    pages: PdfPageSource,
    zoom: Float,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    showFloatingPageChip: Boolean = true,
    onPageChipClick: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val isDark = LocalPpmTheme.current.isDark
    val stageBg = if (isDark) tokens.bgSolid.color else Color(0xFFE9EDF5)
    val count = pages.sizes.size
    val currentPage = if (count == 0) 0 else clampPdfPage(listState.firstVisibleItemIndex + 1, count)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(stageBg)
            .clipToBounds()
            .testTag("pdf-page-by-page-canvas"),
    ) {
        val density = LocalDensity.current
        val horizontalPadding = 28.dp
        val pageWidth = ((maxWidth - horizontalPadding) * zoom).coerceAtLeast(1.dp)
        val canvasWidth = maxOf(maxWidth, pageWidth + horizontalPadding)
        val pixels = with(density) { pageWidth.roundToPx().coerceAtLeast(1) }
        val horizontalScroll = rememberScrollState()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .cssScrollbar(listState)
                .chromeScrollbar(horizontalScroll, vertical = false, color = tokens.border.color),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .horizontalScroll(horizontalScroll)
                    .width(canvasWidth)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(count, contentType = { "pdf-page-card" }) { index ->
                    val size = pages.sizes[index]
                    val pageHeight = pageWidth * size.aspectRatio
                    PdfPageCard(
                        reader = pages,
                        index = index,
                        requestedWidthPx = pixels,
                        modifier = Modifier
                            .width(pageWidth)
                            .height(pageHeight)
                            .testTag("pdf-page-${index + 1}"),
                    )
                }
            }
        }

        if (showFloatingPageChip && count > 0) {
            val pillShape = RoundedCornerShape(999.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 14.dp)
                    .height(28.dp)
                    .clip(pillShape)
                    .background(Color(0xC70F1A33), pillShape)
                    .then(
                        if (onPageChipClick != null) {
                            Modifier.ppmClickable(
                                shape = pillShape,
                                onClickLabel = "Jump to page",
                                onClick = onPageChipClick,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 10.dp)
                    .testTag("pdf-floating-page-chip"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = formatPdfPageCounter(currentPage, count),
                    style = ppmText(11.5f, FontWeight.Medium, FontKind.MONO, lineHeight = 15f)
                        .copy(color = Color.White),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PdfPageCard(
    reader: PdfPageSource,
    index: Int,
    requestedWidthPx: Int,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(reader, index, requestedWidthPx) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(reader, index, requestedWidthPx) { mutableStateOf(false) }

    LaunchedEffect(reader, index, requestedWidthPx) {
        var rendered: Bitmap? = null
        var published = false
        try {
            withContext(Dispatchers.IO) {
                rendered = reader.render(index, requestedWidthPx)
            }
            bitmap = rendered
            published = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            if (!published) {
                rendered?.recycle()
            }
        }
    }

    val pageShape = RoundedCornerShape(4.dp)
    val pageShadow = remember {
        ThemeShadow(0.0, 2.0, 8.0, 0.0, Rgba(15.0 / 255.0, 26.0 / 255.0, 51.0 / 255.0, 0.35))
    }
    Box(
        modifier = modifier
            .ppmShadow(shadow = pageShadow, shape = pageShape)
            .clip(pageShape)
            .background(Color.White, pageShape),
        contentAlignment = Alignment.Center,
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val barShape = RoundedCornerShape(3.dp)
                val barColor = Color(0xFFD6DBE5)
                Box(Modifier.fillMaxWidth(0.4f).height(12.dp).background(barColor, barShape))
                Box(Modifier.fillMaxWidth(0.9f).height(6.dp).background(barColor, barShape))
                Box(Modifier.fillMaxWidth(0.84f).height(6.dp).background(barColor, barShape))
                Box(Modifier.fillMaxWidth(0.7f).height(6.dp).background(barColor, barShape))
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!failed) {
                        PpmIcon(
                            icon = ProductIcon.Loader2,
                            size = 14.dp,
                            tint = Color(0xFF4A5878),
                            spinning = true,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    BasicText(
                        text = if (failed) "Page ${index + 1} unavailable" else "Loading page ${index + 1}…",
                        style = ppmText(12.5f, lineHeight = 16f).copy(color = Color(0xFF4A5878)),
                    )
                }
            }
        }
    }
}

/**
 * Bottom viewer controls card for a page-by-page PDF (`Phone 3 · V2`, `vfoot3` in `wave3.css`):
 * Previous page, tappable `"3 / 12"` page counter, Next page, divider, Zoom out, zoom percentage,
 * Zoom in, and primary `Fit width` button.
 */
@Composable
internal fun PdfBottomControlsCard(
    currentPage: Int,
    pageCount: Int,
    zoom: Float,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onPageCountClick: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onFitWidth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val cardShape = RoundedCornerShape(22.dp)
    val clampedCurrent = clampPdfPage(currentPage, pageCount)
    val canPrev = clampedCurrent > 1
    val canNext = clampedCurrent in 1 until pageCount
    val canZoomOut = zoom > PDF_MIN_ZOOM + 0.001f
    val canZoomIn = zoom < PDF_MAX_ZOOM - 0.001f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .ppmShadow(shadow = tokens.shadowFloat, shape = cardShape)
            .clip(cardShape)
            .background(tokens.panel2.color, cardShape)
            .cssBorder(tokens.border.color, cardShape, width = 1.dp)
            .padding(6.dp)
            .testTag("pdf-bottom-controls"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PdfControlIconButton(
            icon = ProductIcon.ChevronLeft,
            contentDescription = "Previous page",
            enabled = canPrev,
            onClick = onPreviousPage,
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .ppmClickable(
                    enabled = pageCount > 0,
                    onClickLabel = "Jump to page",
                    onClick = onPageCountClick,
                )
                .padding(horizontal = 6.dp, vertical = 8.dp)
                .testTag("pdf-page-counter"),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = formatPdfPageCounter(clampedCurrent, pageCount),
                style = ppmText(13f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 16f)
                    .copy(color = tokens.text.color),
                maxLines = 1,
            )
        }
        PdfControlIconButton(
            icon = ProductIcon.ChevronRight,
            contentDescription = "Next page",
            enabled = canNext,
            onClick = onNextPage,
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(24.dp)
                .background(tokens.borderSoft.color),
        )
        PdfControlIconButton(
            icon = ProductIcon.ZoomOut,
            contentDescription = "Zoom out",
            enabled = canZoomOut,
            onClick = onZoomOut,
        )
        BasicText(
            text = formatPdfZoomPercent(zoom),
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .testTag("pdf-zoom-percent"),
            style = ppmText(13f, FontWeight.Normal, FontKind.MONO, lineHeight = 16f)
                .copy(color = tokens.text2.color),
            maxLines = 1,
        )
        PdfControlIconButton(
            icon = ProductIcon.ZoomIn,
            contentDescription = "Zoom in",
            enabled = canZoomIn,
            onClick = onZoomIn,
        )
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(tokens.accent.color, CircleShape)
                .semantics { contentDescription = "Fit width" }
                .ppmClickable(
                    shape = CircleShape,
                    onClickLabel = "Fit width",
                    onClick = onFitWidth,
                )
                .testTag("pdf-fit-width-button"),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(
                icon = ProductIcon.ArrowLeftRight,
                size = 18.dp,
                tint = tokens.accentFg.color,
            )
        }
    }
}

@Composable
private fun PdfControlIconButton(
    icon: ProductIcon,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = CircleShape
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(shape)
            .semantics { this.contentDescription = contentDescription }
            .ppmClickable(
                enabled = enabled,
                shape = shape,
                pressedColor = tokens.surfaceHover.color,
                hoverColor = tokens.surfaceHover.color,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(
            icon = icon,
            size = 18.dp,
            tint = if (enabled) tokens.text2.color else tokens.text3.color,
        )
    }
}

