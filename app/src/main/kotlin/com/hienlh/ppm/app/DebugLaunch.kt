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
 * Release builds ignore the extras.
 */
data class DebugLaunch(val url: HttpUrl, val password: String, val project: String? = null, val session: String? = null) {
    companion object {
        fun from(context: Context, intent: Intent?): DebugLaunch? {
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
            val extras = intent?.extras ?: return null
            val url = extras.getString("url")?.let(ApiClient::normalizedServerUrl) ?: return null
            val password = extras.getString("password") ?: return null
            return DebugLaunch(url, password, extras.getString("project"), extras.getString("session"))
        }
    }
}
