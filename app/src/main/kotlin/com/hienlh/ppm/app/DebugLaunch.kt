package com.hienlh.ppm.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.hienlh.ppm.client.ApiClient
import okhttp3.HttpUrl

/**
 * A server to open without typing, for checks on an emulator: a debuggable build started with
 * `am start -n com.hienlh.ppm/com.hienlh.ppm.app.MainActivity --es url … --es password …`
 * (optionally `--es project …` and `--es session …`) opens it in place of the saved servers.
 * `--es toasts 'kind:title|description;…'` raises toasts that stay, in that order, the last in
 * front ([toasts] reads the rest of the spec). Release builds ignore the extras.
 */
data class DebugLaunch(
    val url: HttpUrl,
    val password: String,
    val project: String? = null,
    val session: String? = null,
    val toasts: List<ErrorToastMessage> = emptyList(),
) {
    companion object {
        fun from(context: Context, intent: Intent?): DebugLaunch? {
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
            val extras = intent?.extras ?: return null
            val url = extras.getString("url")?.let(ApiClient::normalizedServerUrl) ?: return null
            val password = extras.getString("password") ?: return null
            val toasts = extras.getString("toasts")?.let(::toasts).orEmpty()
            return DebugLaunch(url, password, extras.getString("project"), extras.getString("session"), toasts)
        }

        /**
         * `error:Title|Description;success~:Saved>Undo`: a kind of [ErrorToastMessage.Kind] (error
         * when left out), `~` after it for one that times out, a title, maybe a description, and
         * after `>` the label of an action that only closes it.
         */
        private fun toasts(spec: String): List<ErrorToastMessage> = spec.split(';').filter { it.isNotBlank() }.map { entry ->
            val head = entry.substringBefore(':', "error")
            val kind = ErrorToastMessage.Kind.valueOf(head.removeSuffix("~").uppercase())
            val text = entry.substringAfter(':').substringBefore('>')
            val action = entry.substringAfter('>', "").ifEmpty { null }?.let { ErrorToastMessage.Action(it) {} }
            ErrorToastMessage(text.substringBefore('|'), text.substringAfter('|', "").ifEmpty { null }, action, kind, persistent = !head.endsWith("~"))
        }
    }
}
