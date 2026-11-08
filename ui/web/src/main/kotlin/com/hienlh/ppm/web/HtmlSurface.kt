package com.hienlh.ppm.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The channel to the page's frame: set while its view exists. */
class FrameChannel {
    internal var view: WebView? = null

    /** Posts [envelope] to the frame; false when there is no frame to post to. */
    fun post(envelope: JsonElement): Boolean {
        val view = view ?: return false
        view.evaluateJavascript("window.ppmSend && window.ppmSend($envelope)", null)
        return true
    }
}

private const val EXTENSION_HOST_ORIGIN = "https://appassets.androidplatform.net"

fun webViewMajorVersion(context: Context): Int? {
    val pkg = try {
        WebViewCompat.getCurrentWebViewPackage(context)
    } catch (_: Throwable) {
        null
    } ?: return null
    val ver = pkg.versionName ?: return null
    return ver.substringBefore('.').toIntOrNull()
}

fun isWebViewModernEnough(context: Context, minMajor: Int = 80): Boolean =
    webViewMajorVersion(context)?.let { it >= minMajor } ?: true

/** The relay object the host page gets, under PPM's origin only. */
private const val HOST_OBJECT = "ppmHost"

/** What the host page posts when the frame fires `load`; a frame message is JSON, never this bare word. */
private const val LOAD_SIGNAL = "load"

/** A frame message bigger than this is not the bridge's (a report with its screenshot is ~550 KB). */
private const val MAX_RELAY_CHARS = 2 * 1024 * 1024

/**
 * A page PPM serves for preview (`/api/html-preview/content/…`), shown the way html-preview.tsx
 * shows it: in an `<iframe sandbox="allow-scripts">`, so it runs in an opaque origin and reaches
 * nothing of the app's, while the bridge the server put in it reports to [onMessage].
 *
 * The frame's host has to be a page on PPM's own [origin], since the preview is served with
 * `frame-ancestors 'self'`. It is the few lines below, loaded with that origin as its base URL,
 * and it only relays: what the frame posts (when the frame is the sender) and the frame's `load`
 * go to the app over a channel offered to that origin alone, so the page inside the sandbox never
 * has it; what the app sends through [channel] goes to the frame.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlSurface(
    src: String,
    origin: String,
    channel: FrameChannel,
    onMessage: (String) -> Unit,
    onFrameLoad: () -> Unit,
    onCrash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message by rememberUpdatedState(onMessage)
    val loaded by rememberUpdatedState(onFrameLoad)
    val crashed by rememberUpdatedState(onCrash)
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                // A wrap-content WebView lays its page out in a viewport 0 px high, so `height: 100%` is nothing.
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(Color.WHITE)
                settings.javaScriptEnabled = true
                // The sandboxed page has an opaque origin, where storage throws as it does in Chrome.
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    // The host page never leaves; the sandbox gives the frame no way to move it either.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.isForMainFrame

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        if (channel.view === view) channel.view = null
                        crashed()
                        return true
                    }
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.addWebMessageListener(this, HOST_OBJECT, setOf(origin)) { _, posted, _, isMainFrame, _ ->
                        val data = posted.data
                        if (isMainFrame && data != null) {
                            if (data == LOAD_SIGNAL) loaded() else message(data)
                        }
                    }
                }
                channel.view = this
                loadDataWithBaseURL("$origin/", hostPage(src), "text/html", "utf-8", null)
            }
        },
        modifier = modifier,
        onRelease = { view ->
            if (channel.view === view) channel.view = null
            view.destroy()
        },
    )
}

internal fun buildHostFramePage(
    title: String,
    bgHex: String,
    frameSrc: String? = null,
    frameSrcDoc: String? = null,
): String {
    val frameTitle = JsonPrimitive(title).toString().replace("</", "<\\/")
    val safeBg = bgHex.replace(Regex("[^#a-zA-Z0-9(),.%\\s-]"), "")
    val assignment = when {
        frameSrc != null -> {
            val escaped = JsonPrimitive(frameSrc).toString().replace("</", "<\\/")
            "frame.src = $escaped;"
        }
        frameSrcDoc != null -> {
            val escaped = JsonPrimitive(frameSrcDoc).toString().replace("</", "<\\/")
            "window.ppmSetSrcDoc = function (doc) { frame.srcdoc = doc; };\n  frame.srcdoc = $escaped;"
        }
        else -> ""
    }
    return """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;height:100%;overflow:hidden;background:$safeBg}iframe{display:block;width:100%;height:100%;border:0;background:$safeBg}</style>
</head><body><script>
(function () {
  var host = window.$HOST_OBJECT;
  if (!host) return;
  var frame = document.createElement("iframe");
  frame.title = $frameTitle;
  frame.setAttribute("sandbox", "allow-scripts");
  frame.setAttribute("referrerpolicy", "no-referrer");
  window.addEventListener("message", function (event) {
    if (!frame.contentWindow || event.source !== frame.contentWindow) return;
    var text;
    try { text = JSON.stringify(event.data); } catch (e) { return; }
    if (typeof text === "string" && text.length <= $MAX_RELAY_CHARS) host.postMessage(text);
  });
  frame.addEventListener("load", function () { host.postMessage("$LOAD_SIGNAL"); });
  window.ppmSend = function (message) {
    if (frame.contentWindow) frame.contentWindow.postMessage(message, "*");
  };
  $assignment
  document.body.appendChild(frame);
})();
</script></body></html>"""
}

private fun hostPage(src: String): String =
    buildHostFramePage(title = "HTML preview", bgHex = "#fff", frameSrc = src)

private fun hostSrcDocPage(srcDoc: String, title: String, bgHex: String): String =
    buildHostFramePage(title = title, bgHex = bgHex, frameSrcDoc = srcDoc)

/**
 * Sandboxed `<iframe sandbox="allow-scripts">` surface for extension webviews (e.g. Git Graph)
 * whose HTML arrives via `webview:html` (`srcdoc`).
 *
 * Host page runs at [EXTENSION_HOST_ORIGIN] (`https://appassets.androidplatform.net`) and bridges
 * `window.parent.postMessage` <-> [HOST_OBJECT] / `window.ppmSend`.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun ExtensionWebviewSurface(
    srcDoc: String,
    title: String,
    bgHex: String,
    channel: FrameChannel,
    onMessage: (String) -> Unit,
    onFrameLoad: () -> Unit,
    onCrash: () -> Unit,
    onUserGesture: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val message by rememberUpdatedState(onMessage)
    val loaded by rememberUpdatedState(onFrameLoad)
    val crashed by rememberUpdatedState(onCrash)
    val gesture by rememberUpdatedState(onUserGesture)
    var lastSrcDoc by remember { mutableStateOf(srcDoc) }

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                runCatching { setBackgroundColor(Color.parseColor(bgHex)) }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setOnTouchListener { _, event ->
                    val action = event.actionMasked
                    if (action == android.view.MotionEvent.ACTION_DOWN || action == android.view.MotionEvent.ACTION_UP) {
                        gesture()
                    }
                    false
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.isForMainFrame

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        if (channel.view === view) channel.view = null
                        crashed()
                        return true
                    }
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.addWebMessageListener(
                        this,
                        HOST_OBJECT,
                        setOf(EXTENSION_HOST_ORIGIN),
                    ) { _, posted, _, isMainFrame, _ ->
                        val data = posted.data
                        if (isMainFrame && data != null) {
                            if (data == LOAD_SIGNAL) loaded() else message(data)
                        }
                    }
                }
                channel.view = this
                lastSrcDoc = srcDoc
                loadDataWithBaseURL(
                    "$EXTENSION_HOST_ORIGIN/",
                    hostSrcDocPage(srcDoc, title, bgHex),
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
        update = { view ->
            runCatching { view.setBackgroundColor(Color.parseColor(bgHex)) }
            if (srcDoc != lastSrcDoc) {
                lastSrcDoc = srcDoc
                val escaped = JsonPrimitive(srcDoc).toString().replace("</", "<\\/")
                view.evaluateJavascript(
                    "if (window.ppmSetSrcDoc) window.ppmSetSrcDoc($escaped);",
                    null,
                )
            }
        },
        modifier = modifier,
        onRelease = { view ->
            if (channel.view === view) channel.view = null
            view.destroy()
        },
    )
}
