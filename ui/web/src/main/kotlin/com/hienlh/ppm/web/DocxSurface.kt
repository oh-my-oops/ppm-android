package com.hienlh.ppm.web

import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.hienlh.ppm.design.LocalPpmTheme
import com.hienlh.ppm.design.LocalPpmTokens
import java.io.ByteArrayInputStream

/** Sanitized Word HTML only. No script, app origin, bridge, file/content access or navigation. */
@Composable
fun DocxSurface(safeHtml: String, onLink: (String) -> Unit, onCrash: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val dark = LocalPpmTheme.current.isDark
    val html = remember(safeHtml, tokens, dark) { docxPage(safeHtml, tokens, dark) }
    val link by rememberUpdatedState(onLink)
    val crash by rememberUpdatedState(onCrash)
    AndroidView(factory = { context ->
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.textZoom = 100
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.host == DOCX_HOST && url.path == "/" && url.fragment != null) return false
                    if (request.isForMainFrame && request.hasGesture() && url.scheme in setOf("http", "https", "mailto")) link(url.toString())
                    return true
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val uri = request.url
                    // loadDataWithBaseURL starts with a data document; sanitized embedded images do too.
                    if (uri.scheme == "data") return null
                    if (uri.host == DOCX_HOST) {
                        val resource = docxFontResources[uri.path]
                        if (resource != null) return WebResourceResponse("font/ttf", null, context.resources.openRawResource(resource))
                        return blockedDocumentResource()
                    }
                    return if (!request.isForMainFrame && uri.scheme in setOf("http", "https")) null else blockedDocumentResource()
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { crash(); return true }
            }
        }
    }, update = { view ->
        if (view.tag != html) {
            view.tag = html
            view.loadDataWithBaseURL("https://$DOCX_HOST/", html, "text/html", "utf-8", null)
        }
    }, onRelease = { it.stopLoading(); it.destroy() }, modifier = modifier)
}

internal const val DOCX_HOST = "ppm-document.invalid"
private fun blockedDocumentResource() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
