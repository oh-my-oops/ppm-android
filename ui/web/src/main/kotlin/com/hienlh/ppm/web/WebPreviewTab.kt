package com.hienlh.ppm.web

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.state.workspace.safePreviewUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where the tab is: the address it shows ([url], followed as the page navigates) and the one its
 * frame loads ([src]), both put back to what was asked for when a new [command] arrives.
 */
private class PreviewAddress(var command: String, start: String?) {
    var url by mutableStateOf(start)
    var src by mutableStateOf(start)
}

/**
 * A web server on the host, forwarded and shown in a tab (web-preview-tab.tsx): the tab Port
 * Forwarding opens and the AI's `open_url` opens. The toolbar sits at the bottom in the phone
 * layout, in the thumb zone, as a phone browser's address bar does, and on top otherwise.
 *
 * [requestedUrl] and [savedUrl] are the tab's `url` and `currentUrl` as [safePreviewUrl] lets
 * them through; the page reopens where it was when it is still on the address asked for. Each
 * `open_url` stamps [aiOpenAt], which loads the address asked for again. [onNavigate] hears every
 * address the page moves to on the same origin, for the tab to remember.
 *
 * The web frames the page; here it is the WebView's own page, so it needs no permission to be
 * framed, and opens what the frame's sandbox let it open — popups, downloads and other schemes —
 * the way a WebView can: links in place, downloads and other schemes in the system's apps.
 */
@Composable
fun WebPreviewTab(
    requestedUrl: String?,
    savedUrl: String?,
    via: String?,
    aiOpenAt: String,
    appUrl: HttpUrl?,
    onNavigate: (String) -> Unit,
    onCopyFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val command = "$requestedUrl:$aiOpenAt"
    val address = remember {
        val start = if (savedUrl != null && requestedUrl != null && sameOrigin(savedUrl, requestedUrl)) savedUrl else requestedUrl
        PreviewAddress(command, start)
    }
    if (address.command != command) {
        address.command = command
        address.url = requestedUrl
        address.src = requestedUrl
    }
    var reloadKey by remember { mutableIntStateOf(0) }
    var crashed by remember { mutableStateOf(false) }
    val url = address.url
    if (url == null) {
        Box(modifier.fillMaxSize()) {
            BasicText(
                "This preview has no address PPM can show.",
                Modifier.padding(16.dp),
                style = ppmText(14f, lineHeight = 20f).copy(color = tokens.error.color),
            )
        }
        return
    }
    val toolbar: @Composable ColumnScope.() -> Unit = {
        PreviewToolbar(
            url = url,
            via = via,
            mobile = mobile,
            onReload = {
                address.src = url
                crashed = false
                reloadKey++
            },
            onCopyFailed = onCopyFailed,
        )
    }
    Column(modifier.fillMaxSize().background(tokens.bgSolid.color)) {
        if (!mobile) toolbar()
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.White)) {
            val src = address.src
            if (crashed) {
                BasicText(
                    "The page crashed. Use Reload to retry.",
                    Modifier.padding(16.dp),
                    style = ppmText(14f, lineHeight = 20f).copy(color = tokens.error.color),
                )
            } else if (src != null) {
                key("$reloadKey:$aiOpenAt:$src") {
                    PreviewFrame(
                        src = src,
                        onNavigate = { next ->
                            val safe = safePreviewUrl(next, appUrl)
                            if (safe != null && requestedUrl != null && sameOrigin(safe, requestedUrl)) {
                                address.url = safe
                                onNavigate(safe)
                            }
                        },
                        onCrash = { crashed = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        if (mobile) toolbar()
    }
}

/** `role="toolbar"`: who can open the address, the address, Reload, Copy URL and Open in browser. */
@Composable
private fun PreviewToolbar(url: String, via: String?, mobile: Boolean, onReload: () -> Unit, onCopyFailed: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    // `local` is the AI's open_url on the host's own browser: no forward, so only that machine reaches it.
    val private = via == "tailscale" || via == "local"
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (mobile) 48.dp else 32.dp)
            .cssBorder(tokens.border.color, sides = if (mobile) CssSides.Top else CssSides.Bottom)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (mobile) 8.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            PpmIcon(if (private) ProductIcon.Lock else ProductIcon.Globe, 14.dp, tokens.text2.color)
        }
        BasicText(
            url,
            Modifier.weight(1f),
            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        ToolbarAction(ProductIcon.RotateCw, "Reload", mobile, onClick = onReload)
        ToolbarAction(if (copied) ProductIcon.Check else ProductIcon.Copy, "Copy URL", mobile, tint = if (copied) tokens.success.color else null) {
            scope.launch {
                try {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("URL", url)))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    onCopyFailed()
                    return@launch
                }
                copied = true
                delay(2_000)
                copied = false
            }
        }
        ToolbarAction(ProductIcon.ExternalLink, "Open in browser", mobile) { openOutside(context, Uri.parse(url)) }
    }
}

/**
 * `actionClass`: `rounded min-h-11 min-w-11 md:min-h-7 md:min-w-7 text-muted-foreground
 * hover:text-foreground hover:bg-muted`, a 16 px icon ([tint] replacing the icon's colour).
 */
@Composable
private fun ToolbarAction(icon: ProductIcon, label: String, mobile: Boolean, tint: Color? = null, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(instant = true)
    val side = if (mobile) 44.dp else 28.dp
    Box(
        Modifier
            .sizeIn(minWidth = side, minHeight = side)
            .clip(RoundedCornerShape(4.dp))
            .ppmClickable(pressedColor = tokens.panel.color, hoverColor = tokens.panel.color, fade = false, onClickLabel = label, source = hover.source, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PpmIcon(icon, 16.dp, tint ?: tokens.text2.mix(tokens.text, hover.lit.toDouble()).color) }
}

/** The page itself, in a WebView of its own. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewFrame(src: String, onNavigate: (String) -> Unit, onCrash: () -> Unit, modifier: Modifier) {
    val navigate by rememberUpdatedState(onNavigate)
    val crash by rememberUpdatedState(onCrash)
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                // A wrap-content WebView lays its page out in a viewport 0 px high, so `height: 100%` is nothing.
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(android.graphics.Color.WHITE)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // A plain chrome client is what shows alert(), confirm() and prompt() (`allow-modals`).
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val scheme = request.url.scheme?.lowercase()
                        if (scheme == "http" || scheme == "https") return false
                        if (request.isForMainFrame) openOutside(view.context, request.url)
                        return true
                    }

                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                        if (url != null) navigate(url)
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        crash()
                        return true
                    }
                }
                setDownloadListener { url, _, _, _, _ -> openOutside(context, Uri.parse(url)) }
                loadUrl(src)
            }
        },
        modifier = modifier,
        onRelease = { it.destroy() },
    )
}

private fun sameOrigin(a: String, b: String): Boolean {
    val x = a.toHttpUrlOrNull() ?: return false
    val y = b.toHttpUrlOrNull() ?: return false
    return x.scheme == y.scheme && x.host == y.host && x.port == y.port
}

/** Hands [uri] to whatever app on the device opens it: the browser, for a web address. */
private fun openOutside(context: Context, uri: Uri) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}
