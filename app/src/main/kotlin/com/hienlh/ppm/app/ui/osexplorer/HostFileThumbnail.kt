package com.hienlh.ppm.app.ui.osexplorer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import com.hienlh.ppm.app.ui.LocalWindowContentVisible
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.state.files.FileDocumentClassifier
import com.hienlh.ppm.state.files.FileDocumentViewer
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shared across windows; queued/offscreen work never holds a slot or a live HTTP call. */
internal val hostThumbnailSlots = Semaphore(24)
private val thumbnailHttp = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .readTimeout(30, TimeUnit.SECONDS).build()

/** Raw Bearer-authenticated image, natively decoded at 48/96dp; icons remain the loading/error fallback. */
@Composable
internal fun HostFileThumbnail(api: ApiClient, entry: HostFileEntry, size: Dp, modifier: Modifier = Modifier, visible: Boolean = true) {
    val context = LocalContext.current.applicationContext
    val pixels = with(LocalDensity.current) { size.roundToPx().coerceAtLeast(1) }
    val shown = visible && LocalWindowContentVisible.current
    var inViewport by remember(entry.path) { mutableStateOf(false) }
    var bitmap by remember(api, entry.path, entry.modified, entry.size, pixels) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(api, entry.path, entry.modified, entry.size, pixels) { mutableStateOf(false) }
    val image = !entry.isDirectory && FileDocumentClassifier.classify(entry.name) == FileDocumentViewer.IMAGE
    LaunchedEffect(api, entry.path, entry.modified, entry.size, pixels, shown, inViewport) {
        if (!shown || !inViewport || !image || bitmap != null || failed) return@LaunchedEffect
        try {
            bitmap = hostThumbnailSlots.withPermit {
                loadHostThumbnail(api, entry.path, pixels, context.cacheDir)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    Box(modifier.size(size).onGloballyPositioned { inViewport = it.isAttached && !it.boundsInWindow().isEmpty }, contentAlignment = Alignment.Center) {
        val loaded = bitmap
        if (loaded == null) HostEntryIcon(entry, Modifier.size(size / 2))
        else Image(loaded.asImageBitmap(), null, Modifier.size(size).clip(RoundedCornerShape(3.dp)), contentScale = ContentScale.Fit)
    }
}

private suspend fun loadHostThumbnail(api: ApiClient, path: String, pixels: Int, cacheDir: File): Bitmap = coroutineScope {
    val request = hostThumbnailRequest(api, path)
    val call = thumbnailHttp.newCall(request)
    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    var temporary: File? = null
    try {
        withContext(Dispatchers.IO) {
            val file = File.createTempFile("ppm-thumbnail-", ".image", cacheDir).also { temporary = it }
            var svg = path.endsWith(".svg", true)
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Thumbnail unavailable")
                val body = response.body ?: throw IOException("Empty image response")
                svg = svg || body.contentType()?.let { it.type == "image" && it.subtype == "svg+xml" } == true
                body.byteStream().use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                } }
            }
            ensureActive()
            if (svg) decodeThumbnailSvg(file, pixels) else decodeThumbnailRaster(file, pixels)
        }
    } finally {
        cancellation.cancel()
        withContext(NonCancellable + Dispatchers.IO) { temporary?.delete() }
    }
}

internal fun hostThumbnailRequest(api: ApiClient, path: String): Request {
    require(path.startsWith('/') || Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(path) || path.startsWith("\\\\")) { "Thumbnail needs an absolute host path" }
    val url = api.baseUrl.newBuilder().encodedPath(api.baseUrl.encodedPath.trimEnd('/') + "/api/fs/raw")
        .query(null).addQueryParameter("path", path).build()
    return Request.Builder().url(url).apply { if (api.token.isNotEmpty()) header("Authorization", "Bearer ${api.token}") }.build()
}

private fun decodeThumbnailRaster(file: File, pixels: Int): Bitmap {
    if (Build.VERSION.SDK_INT >= 28) return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val (w, h) = hostThumbnailSize(info.size.width, info.size.height, pixels)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetSize(w, h)
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val sample = hostThumbnailSample(bounds.outWidth, bounds.outHeight, pixels)
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw IOException("Unsupported image")
}

private fun decodeThumbnailSvg(file: File, pixels: Int): Bitmap {
    val svg = file.inputStream().use { SVG.getFromInputStream(it) }
    val box = svg.documentViewBox
    val w = svg.documentWidth.takeIf { it.isFinite() && it > 0 } ?: box?.width()?.takeIf { it > 0 } ?: 300f
    val h = svg.documentHeight.takeIf { it.isFinite() && it > 0 } ?: box?.height()?.takeIf { it > 0 } ?: 150f
    val (width, height) = hostThumbnailSize(w.toInt(), h.toInt(), pixels)
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        svg.renderToCanvas(Canvas(it), RenderOptions.create().viewPort(0f, 0f, width.toFloat(), height.toFloat()))
    }
}
