package com.hienlh.ppm.app.ui.phone.files

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.hienlh.ppm.app.ui.files.PDF_MAX_ZOOM
import com.hienlh.ppm.app.ui.files.PDF_MIN_ZOOM
import com.hienlh.ppm.app.ui.files.PdfBottomControlsCard
import com.hienlh.ppm.app.ui.files.PdfLoadState
import com.hienlh.ppm.app.ui.files.PdfPageByPageCanvas
import com.hienlh.ppm.app.ui.files.PdfPageSource
import com.hienlh.ppm.app.ui.files.PdfPages
import com.hienlh.ppm.app.ui.files.clampPdfPage
import com.hienlh.ppm.app.ui.files.formatPdfZoomPercent
import com.hienlh.ppm.app.ui.files.parsePdfPageJump
import com.hienlh.ppm.app.ui.files.resolvePdfLoadState
import com.hienlh.ppm.app.ui.files.stepPdfZoom
import com.hienlh.ppm.chat.MarkdownView
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTheme
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.kit.BottomActionCard
import com.hienlh.ppm.design.kit.Chip
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// ───────────────────────── V1 · Phone Image Viewer ─────────────────────────

/**
 * Stateless Phone 3 · `V1 · Image` viewer (`designs/ppm-mobile-tablet-redesign/phone/3-files/index.html`,
 * Notes 33–36, 71 & Requirement 8.1).
 */
@Composable
fun PhoneImageViewerScreen(
    filePath: String,
    bytes: ByteArray?,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    error: String? = null,
    dimensionsLabel: String? = null,
    sizeBytes: Long? = null,
    onRetry: () -> Unit = {},
    onDownload: () -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val isDark = LocalPpmTheme.current.isDark
    var zoom by remember(filePath) { mutableFloatStateOf(1.0f) }

    val decodedBitmap = remember(bytes) {
        val raw = bytes ?: return@remember null
        runCatching { BitmapFactory.decodeByteArray(raw, 0, raw.size)?.asImageBitmap() }.getOrNull()
    }

    val formatLabel = remember(filePath) {
        val ext = filePath.substringAfterLast('.', "").uppercase(Locale.ROOT)
        ext.ifEmpty { "IMAGE" }
    }
    val resolvedDimensions = remember(dimensionsLabel, decodedBitmap) {
        dimensionsLabel ?: decodedBitmap?.let { "${it.width} × ${it.height} px" } ?: "512 × 512 px"
    }
    val resolvedSize = remember(sizeBytes, bytes) {
        val count = sizeBytes ?: bytes?.size?.toLong() ?: 0L
        formatPhoneFileSize(count)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.panel2.color)
            .testTag("phone-image-viewer-screen"),
    ) {
        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("phone-image-viewer-loading"),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text2.color, spinning = true)
                    BasicText(
                        text = "Loading image...",
                        style = ppmText(13.5f, lineHeight = 18f).copy(color = tokens.text2.color),
                    )
                }
            }
            return@Column
        }

        if (error != null) {
            PhoneFilesEmptyBlock(
                icon = ProductIcon.ImageOff,
                isError = true,
                title = "Failed to load image",
                retryLabel = "Retry",
                onRetry = onRetry,
                testTag = "phone-image-viewer-error",
            )
            return@Column
        }

        // Checkerboard stage (`vstage3`, Note 36)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .pointerInput(filePath) {
                    detectTransformGestures { _, _, gestureZoom, _ ->
                        if (gestureZoom != 1f) {
                            zoom = (zoom * gestureZoom).coerceIn(PDF_MIN_ZOOM, PDF_MAX_ZOOM)
                        }
                    }
                }
                .testTag("phone-image-stage"),
            contentAlignment = Alignment.Center,
        ) {
            val checkerA = if (isDark) Color(0xFF1B253A) else Color(0xFFE8EDF7)
            val checkerB = if (isDark) Color(0xFF131B2E) else Color(0xFFF7F9FC)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cellPx = 12.dp.toPx().coerceAtLeast(4f)
                val cols = (size.width / cellPx).toInt() + 1
                val rows = (size.height / cellPx).toInt() + 1
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val color = if ((r + c) % 2 == 0) checkerA else checkerB
                        drawRect(
                            color = color,
                            topLeft = Offset(c * cellPx, r * cellPx),
                            size = Size(cellPx, cellPx),
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(rememberScrollState())
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center,
            ) {
                val baseDp = (200f * zoom).dp
                if (decodedBitmap != null) {
                    Image(
                        bitmap = decodedBitmap,
                        contentDescription = filePath.substringAfterLast('/'),
                        modifier = Modifier.size(baseDp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    val cardShape = RoundedCornerShape(28.dp)
                    Box(
                        modifier = Modifier
                            .size(baseDp)
                            .ppmShadow(shadow = tokens.shadowFloat, shape = cardShape)
                            .clip(cardShape)
                            .background(tokens.accent.color, cardShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(ProductIcon.Sparkles, (88f * zoom).dp, Color.White)
                    }
                }
            }

            // Zoom chip (`zchip3`, top-end)
            val pillShape = RoundedCornerShape(999.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 14.dp)
                    .height(28.dp)
                    .clip(pillShape)
                    .background(Color(0xC70F1A33), pillShape)
                    .padding(horizontal = 10.dp)
                    .testTag("phone-image-zoom-chip"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = formatPdfZoomPercent(zoom),
                    style = ppmText(11.5f, FontWeight.Medium, FontKind.MONO, lineHeight = 15f)
                        .copy(color = Color.White),
                    maxLines = 1,
                )
            }
        }

        // Metadata strip (`vmeta3`: "512 × 512 px · 24.1 KB · PNG")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.panel.color)
                .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Top)
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .testTag("phone-image-meta-row"),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = "$resolvedDimensions · $resolvedSize · $formatLabel",
                style = ppmText(12f, FontWeight.Normal, FontKind.MONO, lineHeight = 16f)
                    .copy(color = tokens.text2.color),
                maxLines = 1,
            )
        }

        // Bottom controls card (`vfoot3`, Note 36 & 71)
        val cardShape = RoundedCornerShape(22.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .ppmShadow(shadow = tokens.shadowFloat, shape = cardShape)
                .clip(cardShape)
                .background(tokens.panel2.color, cardShape)
                .cssBorder(tokens.border.color, cardShape, width = 1.dp)
                .padding(6.dp)
                .testTag("phone-image-bottom-controls"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ViewerIconButton(
                icon = ProductIcon.ZoomOut,
                label = "Zoom out",
                enabled = zoom > PDF_MIN_ZOOM + 0.001f,
                onClick = { zoom = stepPdfZoom(zoom, zoomIn = false) },
            )
            BasicText(
                text = formatPdfZoomPercent(zoom),
                modifier = Modifier.padding(horizontal = 4.dp),
                style = ppmText(13f, FontWeight.Normal, FontKind.MONO, lineHeight = 16f)
                    .copy(color = tokens.text2.color),
                maxLines = 1,
            )
            ViewerIconButton(
                icon = ProductIcon.ZoomIn,
                label = "Zoom in",
                enabled = zoom < PDF_MAX_ZOOM - 0.001f,
                onClick = { zoom = stepPdfZoom(zoom, zoomIn = true) },
            )
            ViewerIconButton(
                icon = ProductIcon.Maximize2,
                label = "Fit to screen",
                enabled = true,
                onClick = { zoom = 1.0f },
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .semantics { contentDescription = "Actual size (1:1)" }
                    .ppmClickable(
                        shape = CircleShape,
                        onClickLabel = "Actual size (1:1)",
                        onClick = { zoom = 1.0f },
                    )
                    .testTag("phone-image-actual-size"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = "1:1",
                    style = ppmText(12f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 15f)
                        .copy(color = tokens.text2.color),
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(tokens.accent.color, CircleShape)
                    .semantics { contentDescription = "Download" }
                    .ppmClickable(
                        shape = CircleShape,
                        onClickLabel = "Download",
                        onClick = onDownload,
                    )
                    .testTag("phone-image-download-button"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.Download, 18.dp, tokens.accentFg.color)
            }
        }
    }
}

// ───────────────────────── V2 · Phone Page-by-Page PDF Viewer ─────────────────────────

/**
 * Stateless Phone 3 · `V2 · PDF` page-by-page viewer (`designs/ppm-mobile-tablet-redesign/phone/3-files/index.html`,
 * Notes 37–39, 71 & Requirement 8.2).
 *
 * Uses [PdfPages] / [PdfPageSource] and [PdfPageByPageCanvas] + [PdfBottomControlsCard] shared with
 * tablet (`PdfPages.kt`). Supports scrolling page by page, Prev/Next buttons, tapping the page count
 * (`"3 / 12"`) to type a page number (`[PhonePdfPageJumpSheet]`), Zoom out/in, Fit width, and
 * opening in an external PDF reader (`Open with…`).
 */
@Composable
fun PhonePdfViewerScreen(
    filePath: String,
    bytes: ByteArray? = null,
    modifier: Modifier = Modifier,
    initialZoom: Float = 0.84f,
    loading: Boolean = false,
    error: String? = null,
    onDownload: () -> Unit = {},
    onOpenExternal: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var openedPages by remember(filePath, bytes) { mutableStateOf<PdfPages?>(null) }
    var openError by remember(filePath, bytes) { mutableStateOf<String?>(null) }

    LaunchedEffect(filePath, bytes) {
        openedPages?.close()
        openedPages = null
        openError = null
        val raw = bytes ?: return@LaunchedEffect
        var pages: PdfPages? = null
        try {
            pages = withContext(Dispatchers.IO) {
                PdfPages.open(context.cacheDir, raw)
            }
            openedPages = pages
        } catch (cancelled: CancellationException) {
            pages?.close()
            throw cancelled
        } catch (err: Exception) {
            pages?.close()
            openError = err.message ?: "Could not open PDF"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            openedPages?.close()
        }
    }

    PhonePdfPageSourceViewer(
        filePath = filePath,
        pages = openedPages,
        modifier = modifier,
        initialZoom = initialZoom,
        loading = loading || (bytes != null && openedPages == null && openError == null),
        error = error ?: openError,
        onDownload = onDownload,
        onOpenExternal = onOpenExternal ?: {
            bytes?.let { raw ->
                openPdfInExternalReader(context, filePath, raw)
            }
            Unit
        },
    )
}

/**
 * Page-by-page PDF viewer backed by a [PdfPageSource] (testable with a fake [PdfPageSource] or real [PdfPages]).
 */
@Composable
internal fun PhonePdfPageSourceViewer(
    filePath: String,
    pages: PdfPageSource?,
    modifier: Modifier = Modifier,
    initialZoom: Float = 0.84f,
    loading: Boolean = false,
    error: String? = null,
    listState: LazyListState = rememberLazyListState(),
    onDownload: () -> Unit = {},
    onOpenExternal: () -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    var zoom by remember(filePath) { mutableFloatStateOf(initialZoom.coerceIn(PDF_MIN_ZOOM, PDF_MAX_ZOOM)) }
    var showPageJumpSheet by remember(filePath) { mutableStateOf(false) }

    val loadState = remember(loading, error, pages?.sizes?.size) {
        if (loading) {
            PdfLoadState.Loading
        } else {
            resolvePdfLoadState(error = error, pageCount = pages?.sizes?.size)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.panel2.color)
            .testTag("phone-pdf-viewer-screen"),
    ) {
        when (loadState) {
            PdfLoadState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("phone-pdf-viewer-loading"),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text2.color, spinning = true)
                        BasicText(
                            text = "Loading PDF...",
                            style = ppmText(13.5f, lineHeight = 18f).copy(color = tokens.text2.color),
                        )
                    }
                }
            }
            is PdfLoadState.Error -> {
                PhoneFilesEmptyBlock(
                    icon = ProductIcon.FileWarning,
                    isError = true,
                    title = "Could not open PDF",
                    retryLabel = "Download",
                    onRetry = onDownload,
                    testTag = "phone-pdf-viewer-error",
                )
            }
            is PdfLoadState.Ready -> {
                val activePages = pages ?: return@Column
                val pageCount = loadState.pageCount
                val currentPage = clampPdfPage(listState.firstVisibleItemIndex + 1, pageCount)

                PdfPageByPageCanvas(
                    pages = activePages,
                    zoom = zoom,
                    listState = listState,
                    showFloatingPageChip = true,
                    onPageChipClick = { showPageJumpSheet = true },
                    modifier = Modifier.weight(1f),
                )

                PdfBottomControlsCard(
                    currentPage = currentPage,
                    pageCount = pageCount,
                    zoom = zoom,
                    onPreviousPage = {
                        val target = clampPdfPage(currentPage - 1, pageCount)
                        if (target >= 1) {
                            scope.launch { listState.scrollToItem(target - 1) }
                        }
                    },
                    onNextPage = {
                        val target = clampPdfPage(currentPage + 1, pageCount)
                        if (target >= 1) {
                            scope.launch { listState.scrollToItem(target - 1) }
                        }
                    },
                    onPageCountClick = { showPageJumpSheet = true },
                    onZoomOut = { zoom = stepPdfZoom(zoom, zoomIn = false) },
                    onZoomIn = { zoom = stepPdfZoom(zoom, zoomIn = true) },
                    onFitWidth = { zoom = 1.0f },
                )

                if (showPageJumpSheet) {
                    PhonePdfPageJumpSheet(
                        currentPage = currentPage,
                        pageCount = pageCount,
                        onDismiss = { showPageJumpSheet = false },
                        onJumpToPage = { targetPage ->
                            showPageJumpSheet = false
                            val clamped = clampPdfPage(targetPage, pageCount)
                            if (clamped >= 1) {
                                scope.launch { listState.scrollToItem(clamped - 1) }
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Bottom sheet for jumping directly to a page when the user taps the page count (`"3 / 12"`,
 * `Phone 3 · V2`, Note 38).
 */
@Composable
fun PhonePdfPageJumpSheet(
    currentPage: Int,
    pageCount: Int,
    onDismiss: () -> Unit,
    onJumpToPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    var input by remember(currentPage) { mutableStateOf(currentPage.toString()) }
    val parsedPage = remember(input, pageCount) { parsePdfPageJump(input, pageCount) }

    Sheet(
        title = "Go to page (1–$pageCount)",
        onDismiss = onDismiss,
        forceBottomSheet = true,
        modifier = modifier.testTag("phone-pdf-page-jump-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val fieldShape = RoundedCornerShape(12.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(fieldShape)
                    .background(tokens.panel.color, fieldShape)
                    .cssBorder(tokens.border.color, fieldShape, width = 1.dp)
                    .padding(horizontal = 12.dp)
                    .testTag("phone-pdf-page-jump-input"),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = ppmText(14f, FontWeight.Medium, FontKind.MONO, lineHeight = 18f)
                        .copy(color = tokens.text.color),
                    cursorBrush = SolidColor(tokens.accent.color),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chip(
                    label = "Cancel",
                    onClick = onDismiss,
                )
                Chip(
                    label = "Go",
                    selected = parsedPage != null,
                    enabled = parsedPage != null,
                    onClick = {
                        parsedPage?.let(onJumpToPage)
                    },
                    modifier = Modifier.testTag("phone-pdf-page-jump-go"),
                )
            }
        }
    }
}

private fun openPdfInExternalReader(
    context: android.content.Context,
    filePath: String,
    bytes: ByteArray,
) {
    runCatching {
        val dir = File(context.cacheDir, "shared-pdf").apply { mkdirs() }
        val safeName = filePath.substringAfterLast('/').ifBlank { "document.pdf" }
        val file = File(dir, safeName).apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, safeName))
    }
}

// ───────────────────────── V3 · Phone Markdown Viewer ─────────────────────────

/**
 * Mode of the phone Markdown viewer (`Phone 3 · V3`, Note 40).
 */
enum class PhoneMarkdownMode(val label: String) {
    PREVIEW("Preview"),
    CODE("Code"),
}

/**
 * Stateless Phone 3 · `V3 · Markdown` viewer (`designs/ppm-mobile-tablet-redesign/phone/3-files/index.html`,
 * Note 40 & Requirement 8.1):
 * renders Markdown in `Preview` mode or displays the raw Markdown source in `Code` mode, controlled
 * by the bottom action card (`Preview` / `Code` segmented control + `Edit` chip).
 */
@Composable
fun PhoneMarkdownViewerScreen(
    filePath: String,
    content: String,
    modifier: Modifier = Modifier,
    initialMode: PhoneMarkdownMode = PhoneMarkdownMode.PREVIEW,
    loading: Boolean = false,
    error: String? = null,
    onEditInEditor: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    var mode by remember(filePath) { mutableStateOf(initialMode) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.panel2.color)
            .testTag("phone-markdown-viewer-screen"),
    ) {
        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("phone-markdown-viewer-loading"),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text2.color, spinning = true)
                    BasicText(
                        text = "Loading preview...",
                        style = ppmText(13.5f, lineHeight = 18f).copy(color = tokens.text2.color),
                    )
                }
            }
            return@Column
        }

        if (error != null) {
            PhoneFilesEmptyBlock(
                icon = ProductIcon.FileWarning,
                isError = true,
                title = "Failed to load file",
                retryLabel = "Retry",
                onRetry = onRetry,
                testTag = "phone-markdown-viewer-error",
            )
            return@Column
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (mode) {
                PhoneMarkdownMode.PREVIEW -> {
                    MarkdownView(
                        text = content,
                        modifier = Modifier
                            .fillMaxSize()
                            .cssScrollY()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .testTag("phone-markdown-preview-body"),
                    )
                }
                PhoneMarkdownMode.CODE -> {
                    PhoneCodeLinesView(
                        content = content,
                        cursorLine = 1,
                        wordWrap = true,
                        readOnly = true,
                    )
                }
            }
        }

        BottomActionCard(
            modifier = Modifier.testTag("phone-markdown-bottom-card"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PhoneSegmentedControl(
                    options = listOf(
                        PhoneMarkdownMode.PREVIEW.label to PhoneMarkdownMode.PREVIEW,
                        PhoneMarkdownMode.CODE.label to PhoneMarkdownMode.CODE,
                    ),
                    selected = mode,
                    onSelect = { mode = it },
                    modifier = Modifier.testTag("phone-markdown-mode-toggle"),
                )
                Chip(
                    label = "Edit",
                    icon = ProductIcon.Pencil,
                    onClick = onEditInEditor,
                    modifier = Modifier.testTag("phone-markdown-edit-chip"),
                )
            }
        }
    }
}

// ───────────────────────── V9 · Phone Unsupported / Binary Viewers ─────────────────────────

/**
 * Stateless Phone 3 · `V9 · A file the phone does not open` (`designs/ppm-mobile-tablet-redesign/phone/3-files/index.html`,
 * Note 41 & Section 9):
 * Spreadsheets (`csv`, `tsv`, `xls`, `xlsx`), Word (`doc`, `docx`), audio, video, SQLite, or HTML
 * preview show what the file is, `"Open on a tablet to view or edit it."`, and `Download`.
 */
@Composable
fun PhoneUnsupportedFileScreen(
    filePath: String,
    modifier: Modifier = Modifier,
    sizeBytes: Long? = null,
    onDownload: () -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val fileName = remember(filePath) {
        filePath.substringAfterLast('/').substringAfterLast('\\').ifBlank { filePath }
    }
    val kindLabel = remember(filePath) { describePhoneFileKind(filePath) }
    val subtitle = remember(kindLabel, sizeBytes) {
        if (sizeBytes != null && sizeBytes > 0) {
            "$kindLabel · ${formatPhoneFileSize(sizeBytes)}"
        } else {
            kindLabel
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.panel2.color)
            .padding(horizontal = 24.dp, vertical = 28.dp)
            .testTag("phone-unsupported-file-screen"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val iconShape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(iconShape)
                    .background(tokens.panel.color, iconShape)
                    .cssBorder(tokens.borderSoft.color, iconShape, width = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.Table, 20.dp, tokens.text2.color)
            }
            BasicText(
                text = fileName,
                style = ppmText(14f, FontWeight.SemiBold, lineHeight = 19f).copy(
                    color = tokens.text.color,
                    textAlign = TextAlign.Center,
                ),
            )
            BasicText(
                text = subtitle,
                style = ppmText(12.5f, FontWeight.Normal, lineHeight = 18f).copy(
                    color = tokens.text2.color,
                    textAlign = TextAlign.Center,
                ),
            )
            BasicText(
                text = PHONE_UNSUPPORTED_FILE_LINE,
                style = ppmText(12.5f, FontWeight.Normal, lineHeight = 18f).copy(
                    color = tokens.text2.color,
                    textAlign = TextAlign.Center,
                ),
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val btnShape = RoundedCornerShape(12.dp)
                Row(
                    modifier = Modifier
                        .height(40.dp)
                        .clip(btnShape)
                        .background(tokens.accent.color, btnShape)
                        .ppmClickable(
                            shape = btnShape,
                            onClickLabel = "Download",
                            onClick = onDownload,
                        )
                        .padding(horizontal = 16.dp)
                        .testTag("phone-unsupported-download-button"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PpmIcon(ProductIcon.Download, 16.dp, tokens.accentFg.color)
                    BasicText(
                        text = "Download",
                        style = ppmText(13f, FontWeight.SemiBold, lineHeight = 16f)
                            .copy(color = tokens.accentFg.color),
                    )
                }
            }
        }
    }
}

/**
 * Stateless Phone 3 · `Viewers — states: Binary — cannot display` screen:
 * raw binary files that no device renders inline show `"This file is a binary format and cannot be displayed."`
 * and `Download`.
 */
@Composable
fun PhoneBinaryUnreadableScreen(
    filePath: String,
    modifier: Modifier = Modifier,
    sizeBytes: Long? = null,
    onDownload: () -> Unit = {},
) {
    val fileName = remember(filePath) {
        filePath.substringAfterLast('/').substringAfterLast('\\').ifBlank { filePath }
    }
    val monoDetail = remember(fileName, sizeBytes) {
        if (sizeBytes != null && sizeBytes > 0) {
            "$fileName · ${formatPhoneFileSize(sizeBytes)}"
        } else {
            fileName
        }
    }
    PhoneFilesEmptyBlock(
        icon = ProductIcon.FileWarning,
        title = PHONE_BINARY_FILE_TITLE,
        monoDetail = monoDetail,
        retryLabel = "Download",
        onRetry = onDownload,
        modifier = modifier,
        testTag = "phone-binary-unreadable-screen",
    )
}

/**
 * Phone viewer `⋮` overflow sheet (`Phone 3 · Section 0 & 4`, Notes 33–35):
 * `Refresh`, `Download`, and `Open with…` (for PDFs / files that open in an external app).
 */
@Composable
fun PhoneViewerOverflowSheet(
    fileName: String,
    supportsOpenWith: Boolean,
    onDismiss: () -> Unit,
    onAction: (PhoneViewerOverflowAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Sheet(
        title = fileName,
        onDismiss = onDismiss,
        forceBottomSheet = true,
        modifier = modifier.testTag("phone-viewer-overflow-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        ) {
            for (action in PhoneViewerOverflowAction.actionsFor(supportsOpenWith = supportsOpenWith)) {
                val icon = when (action) {
                    PhoneViewerOverflowAction.REFRESH -> ProductIcon.RefreshCw
                    PhoneViewerOverflowAction.DOWNLOAD -> ProductIcon.Download
                    PhoneViewerOverflowAction.OPEN_WITH -> ProductIcon.ExternalLink
                }
                PhoneSheetActionRow(
                    icon = icon,
                    label = action.label,
                    onClick = { onAction(action) },
                    testTag = "phone-viewer-overflow-${action.id}",
                )
            }
        }
    }
}

/**
 * Top-level Phone File Tab router (`Phone 3 · Sections 2 & 4`, Requirements 8.1 & 8.2):
 * routes [filePath] via [classifyPhoneFileView] to the phone code editor, image viewer (`V1`),
 * page-by-page PDF viewer (`V2`), Markdown viewer (`V3`), tablet-only file screen (`V9`), or
 * binary unreadable screen.
 */
@Composable
fun PhoneFileTabScreen(
    filePath: String,
    modifier: Modifier = Modifier,
    textContent: String = "",
    binaryBytes: ByteArray? = null,
    sizeBytes: Long? = null,
    forceCode: Boolean = false,
    cannotDisplayBinary: Boolean = false,
    dirty: Boolean = false,
    saving: Boolean = false,
    loading: Boolean = false,
    error: String? = null,
    onSave: () -> Unit = {},
    onDownload: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    var editMarkdownAsCode by remember(filePath) { mutableStateOf(false) }
    val kind = remember(filePath, forceCode, editMarkdownAsCode, cannotDisplayBinary) {
        classifyPhoneFileView(
            path = filePath,
            forceCode = forceCode || editMarkdownAsCode,
            cannotDisplayBinary = cannotDisplayBinary,
        )
    }

    when (kind) {
        PhoneFileViewKind.CODE -> PhoneEditorScreen(
            filePath = filePath,
            content = textContent,
            dirty = dirty,
            saving = saving,
            loading = loading,
            loadError = error,
            onSave = onSave,
            onDownload = onDownload,
            onRetryLoad = onRetry,
            modifier = modifier,
        )
        PhoneFileViewKind.IMAGE -> PhoneImageViewerScreen(
            filePath = filePath,
            bytes = binaryBytes,
            sizeBytes = sizeBytes,
            loading = loading,
            error = error,
            onRetry = onRetry,
            onDownload = onDownload,
            modifier = modifier,
        )
        PhoneFileViewKind.PDF -> PhonePdfViewerScreen(
            filePath = filePath,
            bytes = binaryBytes,
            loading = loading,
            error = error,
            onDownload = onDownload,
            modifier = modifier,
        )
        PhoneFileViewKind.MARKDOWN -> PhoneMarkdownViewerScreen(
            filePath = filePath,
            content = textContent,
            loading = loading,
            error = error,
            onEditInEditor = { editMarkdownAsCode = true },
            onRetry = onRetry,
            modifier = modifier,
        )
        PhoneFileViewKind.TABLET_ONLY_VIEWER -> PhoneUnsupportedFileScreen(
            filePath = filePath,
            sizeBytes = sizeBytes,
            onDownload = onDownload,
            modifier = modifier,
        )
        PhoneFileViewKind.BINARY_UNREADABLE -> PhoneBinaryUnreadableScreen(
            filePath = filePath,
            sizeBytes = sizeBytes,
            onDownload = onDownload,
            modifier = modifier,
        )
    }
}

@Composable
private fun ViewerIconButton(
    icon: ProductIcon,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .semantics { contentDescription = label }
            .ppmClickable(
                enabled = enabled,
                shape = CircleShape,
                pressedColor = tokens.surfaceHover.color,
                hoverColor = tokens.surfaceHover.color,
                onClickLabel = label,
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
