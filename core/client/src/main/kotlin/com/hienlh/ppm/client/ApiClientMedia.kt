package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.MediaProbeInfo
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import okhttp3.HttpUrl
import java.util.Locale
import java.util.UUID

/** Authentication travels in this URL, as media-url.ts does. Do not log it. No body is downloaded here. */
fun ApiClient.rawMediaUrl(project: String?, path: String): HttpUrl =
    url("${mediaPrefix(project, path)}/raw", mediaQuery(path))

/** The player GET opens the live fMP4 stream. Never preflight it with a second GET. */
fun ApiClient.transcodeMediaUrl(project: String?, path: String, startSeconds: Double = 0.0, sessionId: String): HttpUrl {
    require(startSeconds.isFinite() && startSeconds >= 0) { "Transcode start must be finite and non-negative." }
    requireValidMediaSessionId(sessionId)
    val query = mutableListOf("path" to path)
    if (startSeconds > 0) query += "start" to String.format(Locale.ROOT, "%.3f", startSeconds)
    query += "sid" to sessionId
    if (token.isNotEmpty()) query += "token" to token
    return url("${mediaPrefix(project, path)}/transcode", query)
}

suspend fun ApiClient.probeMedia(project: String?, path: String): MediaProbeInfo =
    MediaProbeInfo.from(call("GET", "${mediaPrefix(project, path)}/probe", listOf("path" to path)))
        ?: throw PpmError.BadResponse("The media probe could not be read.")

/** Uses Bearer auth, not a query token; false means the session had already stopped. */
suspend fun ApiClient.stopMediaTranscode(project: String?, path: String, sessionId: String): Boolean {
    requireValidMediaSessionId(sessionId)
    return call("DELETE", "${mediaPrefix(project, path)}/transcode", listOf("sid" to sessionId))["stopped"].bool
        ?: throw PpmError.BadResponse("The transcode stop result could not be read.")
}

fun newMediaSessionId(): String = UUID.randomUUID().toString()
fun isValidMediaSessionId(value: String): Boolean = MEDIA_SESSION.matches(value)
fun requireValidMediaSessionId(value: String) {
    require(isValidMediaSessionId(value)) { "Media session id must contain 1–64 letters, digits, underscores or hyphens." }
}

private fun ApiClient.mediaQuery(path: String) = buildList {
    add("path" to path)
    if (token.isNotEmpty()) add("token" to token)
}
private fun mediaPrefix(project: String?, path: String): String {
    if (MEDIA_ABSOLUTE.containsMatchIn(path)) return "/api/fs"
    require(!project.isNullOrEmpty()) { "A relative media path needs a project." }
    return "/api/project/${ApiClient.escape(project)}/files"
}
private val MEDIA_ABSOLUTE = Regex("""^(/|[A-Za-z]:[/\\])""")
private val MEDIA_SESSION = Regex("[A-Za-z0-9_-]{1,64}")
