package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.ChangelogSection
import com.hienlh.ppm.protocol.parseChangelog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/** PPM's published CHANGELOG.md, which the version popover lists (changelog.ts). */
object ReleaseNotes {
    const val URL = "https://raw.githubusercontent.com/hienlh/ppm/main/CHANGELOG.md"

    /**
     * The newest [limit] sections (`fetchRecentChangelog`); throws [IOException] when GitHub cannot
     * be asked. The time in the query gets past the raw CDN's five minutes of cache, which would
     * otherwise leave out a release the registry already has.
     */
    suspend fun recent(limit: Int = 10, url: String = URL, now: Long = System.currentTimeMillis()): List<ChangelogSection> {
        val request = Request.Builder().url("$url?t=$now").build()
        val text = PpmHttp.getClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("CHANGELOG fetch failed (${response.code})")
            withContext(Dispatchers.IO) { response.body.string() }
        }
        return parseChangelog(text).take(limit)
    }
}
