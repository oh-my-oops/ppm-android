package com.hienlh.ppm.app.ui.files

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import com.hienlh.ppm.app.ui.StageLayout
import com.hienlh.ppm.app.ui.ViewTransform
import com.hienlh.ppm.app.ui.actualSize
import com.hienlh.ppm.app.ui.doubleTapped
import com.hienlh.ppm.app.ui.fittedSize
import com.hienlh.ppm.app.ui.panned
import com.hienlh.ppm.app.ui.settled
import com.hienlh.ppm.app.ui.zoomedTo
import com.hienlh.ppm.chat.ToolImage
import com.hienlh.ppm.client.RawFile
import com.hienlh.ppm.design.GalleryImage
import com.hienlh.ppm.design.LocalImageViewer
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ViewerImage
import com.hienlh.ppm.design.ppmText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Native decoding and the existing lightbox transform math; no WebView or second file request. */
@Composable
internal fun FileImageView(raw: RawFile, path: String) {
    val decoded = remember(raw) { mutableStateOf<ToolImage?>(null) }
    LaunchedEffect(decoded) { decoded.value = withContext(Dispatchers.Default) { decodeFileImage(raw, path) } }
    val shown = decoded.value
    if (shown == null) { FileNoteView(FileNote.Spinner); return }
    if (shown !is ToolImage.Loaded) { FileNoteView(FileNote.Warning("Failed to load image.")); return }
    val density = LocalDensity.current
    val colors = LocalPpmTokens.current
    val viewer = LocalImageViewer.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var transform by remember(raw) { mutableStateOf(ViewTransform()) }
    val stage = remember(shown, size, density) {
        // One file pixel per dp, as the shared lightbox's actual-size view does.
        val naturalWidth = shown.natural.width * density.density
        val naturalHeight = shown.natural.height * density.density
        val fit = fittedSize(naturalWidth, naturalHeight, size.width.toFloat(), size.height.toFloat())
        StageLayout(fit.first, fit.second, size.width.toFloat(), size.height.toFloat(), naturalWidth)
    }
    LaunchedEffect(stage) { transform = transform.settled(stage) }
    val latestStage by rememberUpdatedState(stage)
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            FileAction(ProductIcon.Minus, "Zoom out") { transform = transform.zoomedTo(transform.scale * 0.8f, stage) }
            BasicText("${transform.percent}%", style = ppmText(12f, lineHeight = 16f).copy(color = colors.text2.color))
            FileAction(ProductIcon.Plus, "Zoom in") { transform = transform.zoomedTo(transform.scale * 1.25f, stage) }
            FileAction(ProductIcon.Maximize2, "Fit image") { transform = ViewTransform() }
            FileAction(ProductIcon.Image, "Actual size", label = "1:1") { transform = transform.actualSize(stage) }
            if (viewer != null && !isSvgFile(raw, path)) FileAction(ProductIcon.ExternalLink, "Open image viewer") {
                viewer.show(listOf(GalleryImage("file:$path", name) { ViewerImage(name, raw.bytes, raw.contentType) }), 0)
            }
        }
        // image-preview.tsx: `p-4 bg-surface` around the picture, which is fitted to what the padding leaves.
        Box(Modifier.weight(1f).fillMaxWidth().background(colors.panel.color).padding(16.dp)) {
            Box(
                Modifier.fillMaxSize().clipToBounds().onSizeChanged { size = it }
                    .pointerInput(raw) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val layout = latestStage
                            if (layout.width > 0 && layout.height > 0) transform = transform
                                .zoomedTo(transform.scale * zoom, layout, centroid.x - layout.stageWidth / 2, centroid.y - layout.stageHeight / 2)
                                .panned(pan.x, pan.y, layout)
                        }
                    }
                    .pointerInput(raw) {
                        detectTapGestures(onDoubleTap = { at ->
                            val layout = latestStage
                            if (layout.width > 0 && layout.height > 0) transform = transform.doubleTapped(layout,
                                at.x - layout.stageWidth / 2, at.y - layout.stageHeight / 2)
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (stage.width > 0 && stage.height > 0) Image(shown.preview, name,
                    Modifier.size(with(density) { stage.width.toDp() }, with(density) { stage.height.toDp() }).graphicsLayer {
                        scaleX = transform.scale; scaleY = transform.scale
                        translationX = transform.x; translationY = transform.y
                    }, contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.High)
            }
        }
    }
}

/** Android's raster decoder covers the existing formats; AndroidSVG handles static SVG natively. */
private fun decodeFileImage(raw: RawFile, path: String): ToolImage {
    if (!isSvgFile(raw, path)) {
        return ToolImage.decode(raw.bytes, raw.contentType)
    }
    return runCatching {
        val svg = SVG.getFromInputStream(raw.bytes.inputStream())
        val bounds = svg.documentViewBox
        val width = svg.documentWidth.takeIf { it.isFinite() && it > 0 } ?: bounds?.width()?.takeIf { it > 0 } ?: 300f
        val height = svg.documentHeight.takeIf { it.isFinite() && it > 0 } ?: bounds?.height()?.takeIf { it > 0 } ?: 150f
        val shrink = minOf(1f, 2048f / maxOf(width, height))
        val w = (width * shrink).roundToInt().coerceAtLeast(1)
        val h = (height * shrink).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bitmap), RenderOptions.create().viewPort(0f, 0f, w.toFloat(), h.toFloat()))
        bitmap.setHasMipMap(true)
        ToolImage.Loaded(bitmap.asImageBitmap(), IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)), raw.bytes, raw.contentType)
    }.getOrElse { ToolImage.Failed(null) }
}

private fun isSvgFile(raw: RawFile, path: String) = path.endsWith(".svg", true) || raw.contentType?.substringBefore(';') == "image/svg+xml"
