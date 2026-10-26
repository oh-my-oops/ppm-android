package com.hienlh.ppm.state.workspace

import com.hienlh.ppm.protocol.TabOpenRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/*
 * The web-preview tab: a web server running on the host, the tab Port Forwarding opens and the
 * AI's `open_url` opens (src/web/lib/web-preview-url.ts and the url half of open-ai-tab.ts).
 */

/**
 * The address a web-preview tab may load, or null (`safePreviewUrl`): only http(s), and never
 * PPM's own origin, [app]. The tab's metadata is persisted and restored, and a page on PPM's
 * origin would be one PPM itself signed in to.
 */
fun safePreviewUrl(value: String?, app: HttpUrl?): String? {
    val url = value?.toHttpUrlOrNull() ?: return null
    if (app != null && url.scheme == app.scheme && url.host == app.host && url.port == app.port) return null
    return url.toString()
}

/** The tab's title: the dev server as the host knows it (`previewTitle`). */
fun previewTitle(port: Int?, url: String): String {
    if (port != null && port != 0) return "localhost:$port"
    val parsed = url.toHttpUrlOrNull() ?: return url
    val host = if (':' in parsed.host) "[${parsed.host}]" else parsed.host
    return if (parsed.port == HttpUrl.defaultPort(parsed.scheme)) host else "$host:${parsed.port}"
}

private val LOOPBACK_V4 = Regex("""^127(?:\.\d{1,3}){3}$""")

/**
 * Whether this device is the machine PPM runs on, where `localhost` is the host's own
 * (`browserOnHost`): the app reached PPM at a loopback address, [host] as the server URL names it.
 */
fun browserOnHost(host: String): Boolean =
    host == "localhost" || host == "::1" || host == "[::1]" || LOOPBACK_V4.matches(host)

/**
 * Opens the web server `open_url` asked for in a web-preview tab beside the chat showing
 * [sessionId] (`openAiUrlTab`). A tab already showing that address is reused, and reloaded: its
 * metadata is replaced, `aiOpenAt` included. Throws, with what to tell the AI, when the address is
 * the host's own and this device is not the host ([serverHost] is the host the app reached PPM at),
 * or when there is nowhere to open it.
 */
fun WorkspaceStore.openAiUrlTab(request: TabOpenRequest.Url, sessionId: String, serverHost: String): String {
    if (request.via == "local" && !browserOnHost(serverHost)) {
        throw IllegalStateException("this device is not the machine PPM runs on, so it cannot reach localhost:${request.port}")
    }
    val st = state.value
    val placement = chooseAiTabPlacement(st.grid, st.panels, st.focusedPanelId, mobile, sessionId) { tab ->
        tab.type == "web-preview" && (tab.metadata["url"] as? JsonPrimitive)?.let { it.isString && it.content == request.url } == true
    } ?: throw IllegalStateException("PPM has no panel to open the tab in")
    val metadata = buildJsonObject {
        put("url", request.url)
        put("port", request.port)
        put("via", request.via)
        put("aiOpenAt", clock())
    }
    return when (placement) {
        is AiTabPlacement.Open -> {
            val tabId = openTab(NewTab("web-preview", previewTitle(request.port, request.url), null, metadata), placement.panelId)
            if (tabId.isEmpty()) throw IllegalStateException("PPM could not open the tab")
            if (placement.split) splitPanel(SplitDirection.Right, tabId, placement.panelId)
            tabId
        }
        is AiTabPlacement.Focus -> placement.tabId.also { reopen(it, placement, metadata) }
        is AiTabPlacement.Move -> placement.tabId.also { reopen(it, placement, metadata) }
    }
}

private fun WorkspaceStore.reopen(tabId: String, placement: AiTabPlacement, metadata: JsonObject) {
    updateTab(tabId) { it.copy(metadata = metadata) }
    showExistingTab(placement)
}
