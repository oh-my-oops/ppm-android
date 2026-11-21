package com.hienlh.ppm.app

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.PpmLog

/**
 * The lines the app logs about itself, through [PpmLog] so they reach "This device" and PPM's
 * Logs. Before these the app logged almost nothing in ordinary use, so a healthy app never sent
 * the server a single batch and never appeared in Logs at all.
 */
internal object AppLogLines {
    const val TAG = "PPM"

    /** A server was opened: its address (never its token), which every later line is about. */
    fun opened(api: ApiClient) {
        PpmLog.i(TAG, "opened server ${server(api)}")
    }

    /** The connection-lost overlay came up or went: [was] and [now] are its state before and after a health check. */
    fun connection(api: ApiClient, was: Boolean, now: Boolean) {
        if (was == now) return
        if (now) PpmLog.w(TAG, "lost the connection to ${server(api)}")
        else PpmLog.i(TAG, "the connection to ${server(api)} is back")
    }

    /** A toast the user was shown: an error or warning one is a line at that level, others are left out. */
    fun toast(message: ErrorToastMessage) {
        val text = listOfNotNull(message.title, message.description?.takeIf { it.isNotBlank() }).joinToString(" — ")
        when (message.kind) {
            ErrorToastMessage.Kind.ERROR -> PpmLog.e("toast", text)
            ErrorToastMessage.Kind.WARNING -> PpmLog.w("toast", text)
            else -> Unit
        }
    }

    private fun server(api: ApiClient): String =
        api.baseUrl.let { url -> "${url.scheme}://${url.host}:${url.port}${url.encodedPath.trimEnd('/')}" }
}
