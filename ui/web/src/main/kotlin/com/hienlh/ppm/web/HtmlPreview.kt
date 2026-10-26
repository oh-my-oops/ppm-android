package com.hienlh.ppm.web

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.htmlPreviewUrl
import com.hienlh.ppm.client.origin
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.newBridgeNonce
import com.hienlh.ppm.state.preview.PreviewBridge
import com.hienlh.ppm.state.preview.PreviewLoads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One document the preview shows: where it is served, the bridge nonce it was given, its load number. */
private data class PreviewDocument(val url: String, val nonce: String, val seq: Int)

private const val PREVIEW_REQUEST_TIMEOUT_MS = 30_000L

/**
 * An `.html` file's tab in preview (html-preview.tsx): the page served sandboxed by the server
 * (`POST /api/html-preview`), each load with a fresh nonce for the bridge the server puts in it,
 * which reports the page's errors and failed loads and answers the self-check the AI's
 * `open_preview` asks for through [previews]. A new [revision] asks for the page again.
 */
@Composable
fun HtmlPreview(
    filePath: String,
    projectName: String?,
    revision: Int,
    api: ApiClient,
    previews: PreviewLoads,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current.applicationContext
    val key = PreviewLoads.key(projectName, filePath)
    val channel = remember { FrameChannel() }
    val bridge = remember { PreviewBridge(channel::post) }
    var document by remember { mutableStateOf<PreviewDocument?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(api, filePath, projectName, revision) {
        document = null
        error = null
        try {
            val url = withTimeout(PREVIEW_REQUEST_TIMEOUT_MS) { api.htmlPreviewUrl(filePath, projectName) }
            val nonce = newBridgeNonce()
            bridge.startLoad(nonce)
            val seq = previews.begin(key) { screenshot, frame -> bridge.check(screenshot, frame) { ScreenshotLib.source(context) } }
            document = PreviewDocument(url, nonce, seq)
        } catch (_: TimeoutCancellationException) {
            error = "Preview request timed out. Try Refresh."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Could not open HTML preview"
        }
    }

    val text = ppmText(14f, lineHeight = 20f)
    val current = document
    when {
        error != null -> Box(modifier.fillMaxSize()) {
            BasicText("$error. Use Refresh to retry.", Modifier.padding(16.dp), style = text.copy(color = tokens.error.color))
        }
        current == null -> Row(
            modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PpmIcon(ProductIcon.Loader2, 20.dp, tokens.text2.color, spinning = true)
            BasicText("Loading preview…", style = text.copy(color = tokens.text2.color))
        }
        else -> {
            DisposableEffect(key, current.seq) { onDispose { previews.end(key, current.seq) } }
            key(current.seq) {
                HtmlSurface(
                    src = "${current.url}?n=${Uri.encode(current.nonce)}",
                    origin = api.origin,
                    channel = channel,
                    onMessage = bridge::receive,
                    onFrameLoad = {
                        bridge.frameLoaded()
                        previews.finish(key, current.seq)
                    },
                    onCrash = {
                        document = null
                        error = "The page crashed"
                    },
                    modifier = modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * modern-screenshot's browser build, which a check with a screenshot hands to the page (the web
 * imports it `?raw`): an asset copied from node_modules at build time, read once.
 */
private object ScreenshotLib {
    private const val ASSET = "preview/modern-screenshot.js"
    private val lock = Mutex()
    private var source: String? = null

    suspend fun source(context: Context): String? = lock.withLock {
        source ?: withContext(Dispatchers.IO) {
            context.assets.open(ASSET).use { it.readBytes().decodeToString() }
        }.also { source = it }
    }
}
