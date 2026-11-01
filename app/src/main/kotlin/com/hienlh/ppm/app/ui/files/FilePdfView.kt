package com.hienlh.ppm.app.ui.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.client.RawFile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.chromeScrollbar
import com.hienlh.ppm.design.cssScrollbar
import com.hienlh.ppm.design.ppmText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** Authenticated bytes from the document owner. Rendering, file IO and disposal stay off UI. */
@Composable
internal fun FilePdfView(model: AppModel, raw: RawFile, path: String) {
    val context = LocalContext.current
    val colors = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    var reader by remember(raw) { mutableStateOf<PdfPages?>(null) }
    var error by remember(raw) { mutableStateOf<String?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    var exporting by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(raw) {
        var opened: PdfPages? = null
        try {
            withContext(Dispatchers.IO) { opened = PdfPages.open(context.cacheDir, raw.bytes) }
            reader = opened
            awaitCancellation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "This PDF could not be opened."
        } finally {
            reader = null
            withContext(NonCancellable + Dispatchers.IO) { opened?.close() }
        }
    }
    val pages = reader
    // As the web's: a failed PDF or one still opening is a line of its own, with no page controls around it.
    if (error != null) { FileNoteView(FileNote.Warning("Failed to load PDF.")); return }
    if (pages == null) { FileNoteView(FileNote.Spinner); return }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            val count = pages.sizes.size
            val current = if (count == 0) 0 else (list.firstVisibleItemIndex + 1).coerceAtMost(count)
            FileAction(ProductIcon.ChevronLeft, "Previous page", enabled = current > 1) { scope.launch { list.scrollToItem(current - 2) } }
            BasicText("$current / $count", style = ppmText(12f).copy(color = colors.text2.color))
            FileAction(ProductIcon.ChevronRight, "Next page", enabled = current in 1 until count) { scope.launch { list.scrollToItem(current) } }
            FileAction(ProductIcon.Minus, "Zoom out", enabled = zoom > 0.5f) { zoom = (zoom / 1.25f).coerceAtLeast(0.5f) }
            BasicText("${(zoom * 100).roundToInt()}%", style = ppmText(12f).copy(color = colors.text2.color))
            FileAction(ProductIcon.Plus, "Zoom in", enabled = zoom < 3f) { zoom = (zoom * 1.25f).coerceAtMost(3f) }
            FileAction(ProductIcon.Maximize2, "Fit width") { zoom = 1f }
            FileAction(ProductIcon.ExternalLink, "Open with…", enabled = !exporting) {
                exporting = true
                scope.launch {
                    try {
                        val uri = withContext(Dispatchers.IO) {
                            val directory = File(context.cacheDir, "documents").apply { mkdirs() }
                            val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
                            directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
                            val target = File(File(directory, UUID.randomUUID().toString()).apply { mkdirs() },
                                path.substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotEmpty() } ?: "document.pdf")
                            target.writeBytes(raw.bytes)
                            FileProvider.getUriForFile(context, "${context.packageName}.images", target)
                        }
                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        intent.clipData = ClipData.newRawUri("PDF", uri)
                        context.startActivity(Intent.createChooser(intent, "Open with…"))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: ActivityNotFoundException) { model.toast(ErrorToastMessage("No PDF app is available.")) }
                    catch (failure: Exception) { model.toast(ErrorToastMessage("Could not open PDF", failure.message)) }
                    finally { exporting = false }
                }
            }
        }
        when {
            pages.sizes.isEmpty() -> FileMessage("Empty PDF file")
            else -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                val density = LocalDensity.current
                val pageWidth = ((maxWidth - 16.dp) * zoom).coerceAtLeast(1.dp)
                val canvasWidth = maxOf(maxWidth, pageWidth + 16.dp)
                val pixels = with(density) { pageWidth.roundToPx() }
                val horizontal = rememberScrollState()
                Box(Modifier.fillMaxSize().cssScrollbar(list).chromeScrollbar(horizontal, vertical = false, color = colors.border.color)) {
                    LazyColumn(Modifier.horizontalScroll(horizontal).width(canvasWidth).fillMaxHeight(), state = list,
                        contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        items(pages.sizes.size, contentType = { "pdf-page" }) { index ->
                            val size = pages.sizes[index]
                            PdfPage(pages, index, pixels, Modifier.width(pageWidth).height(pageWidth * (size.height.toFloat() / size.width)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(reader: PdfPages, index: Int, width: Int, modifier: Modifier) {
    var image by remember(reader, index, width) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(reader, index, width) { mutableStateOf(false) }
    LaunchedEffect(reader, index, width) {
        var bitmap: Bitmap? = null
        var published = false
        try {
            withContext(Dispatchers.IO) { bitmap = reader.render(index, width) }
            image = bitmap
            published = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { if (!published) bitmap?.recycle() }
        // Published bitmaps are released by Compose/GC after the last draw, never recycled mid-frame.
    }
    Box(modifier.background(Color.White), contentAlignment = Alignment.Center) {
        val page = image
        if (page != null) Image(page.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        else BasicText(if (failed) "Page ${index + 1} unavailable" else "Loading page ${index + 1}…",
            Modifier.padding(8.dp), style = ppmText(12f).copy(color = Color.DarkGray))
    }
}
