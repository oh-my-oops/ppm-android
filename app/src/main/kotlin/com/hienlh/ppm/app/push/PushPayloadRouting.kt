package com.hienlh.ppm.app.push

import android.app.NotificationManager
import com.hienlh.ppm.client.FeedKind
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement
import org.unifiedpush.android.connector.data.PushMessage
import java.net.URI
import java.net.URLEncoder

/**
 * Android notification channels mapped from [FeedKind] / `WebPushPayload.kind`
 * (Requirements 7.2 & 7.3).
 */
enum class PushChannel(
    val id: String,
    val title: String,
    val description: String,
    val importance: Int,
) {
    APPROVAL_QUESTION(
        id = "ppm_push_approvals",
        title = "Approvals and questions",
        description = "Permission requests and agent questions waiting for your response",
        importance = NotificationManager.IMPORTANCE_HIGH,
    ),
    DONE(
        id = "ppm_push_done",
        title = "Finished chats",
        description = "Notifications when an AI turn completes",
        importance = NotificationManager.IMPORTANCE_DEFAULT,
    ),
    SCHEDULE(
        id = "ppm_push_schedules",
        title = "Schedules",
        description = "Scheduled task runs and failures",
        importance = NotificationManager.IMPORTANCE_DEFAULT,
    ),
    OTHER(
        id = "ppm_push_other",
        title = "Other",
        description = "General server and project notifications",
        importance = NotificationManager.IMPORTANCE_DEFAULT,
    ),
    ;

    companion object {
        fun forKind(kind: FeedKind): PushChannel = when (kind) {
            FeedKind.APPROVAL, FeedKind.QUESTION -> APPROVAL_QUESTION
            FeedKind.DONE -> DONE
            FeedKind.SCHEDULE -> SCHEDULE
            FeedKind.OTHER -> OTHER
        }

        fun forRawKind(rawKind: String?): PushChannel =
            forKind(FeedKind.fromWire(rawKind))
    }
}

/**
 * Decrypted Web Push JSON payload delivered by PPM (`src/shared/web-push-payload.ts`).
 */
data class WebPushPayload(
    val title: String,
    val body: String,
    val url: String = "/",
    val tag: String? = null,
    val project: String? = null,
    val sessionId: String? = null,
    val providerId: String? = null,
    val kind: FeedKind = FeedKind.OTHER,
    val rawKind: String? = null,
) {
    val channel: PushChannel
        get() = PushChannel.forKind(kind)

    companion object {
        /**
         * Parse a [PushMessage] from UnifiedPush.
         *
         * Drops any message where [PushMessage.decrypted] is `false` (failed RFC 8291
         * authentication/decryption).
         */
        fun fromPushMessage(message: PushMessage): WebPushPayload? =
            fromBytes(bytes = message.content, decrypted = message.decrypted)

        /**
         * Parse UTF-8 JSON bytes into a [WebPushPayload].
         *
         * Returns `null` when [decrypted] is `false`, when [bytes] is empty, or when the JSON
         * does not represent a valid notification payload.
         */
        fun fromBytes(bytes: ByteArray, decrypted: Boolean = true): WebPushPayload? {
            if (!decrypted || bytes.isEmpty()) return null
            val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: return null
            return fromJson(text)
        }

        /**
         * Parse a raw JSON string into a [WebPushPayload].
         */
        fun fromJson(raw: String): WebPushPayload? {
            if (raw.isBlank()) return null
            val element = WireJson.parseOrNull(raw) ?: return null
            return fromJson(element)
        }

        /**
         * Parse a [JsonElement] into a [WebPushPayload].
         *
         * Older PPM servers omit `kind`; when absent or unknown, [kind] defaults to [FeedKind.OTHER]
         * so the notification routes to the "Other" channel.
         */
        fun fromJson(element: JsonElement?): WebPushPayload? {
            val obj = element?.obj ?: return null
            val title = obj["title"].string?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val body = obj["body"].string ?: ""
            val url = obj["url"].string?.trim()?.takeIf { it.isNotEmpty() } ?: "/"
            val tag = obj["tag"].string?.trim()?.takeIf { it.isNotEmpty() }
            val project = obj["project"].string?.trim()?.takeIf { it.isNotEmpty() }
            val sessionId = obj["sessionId"].string?.trim()?.takeIf { it.isNotEmpty() }
            val providerId = obj["providerId"].string?.trim()?.takeIf { it.isNotEmpty() }
            val rawKind = obj["kind"].string?.trim()?.takeIf { it.isNotEmpty() }
            val kind = FeedKind.fromWire(rawKind)
            return WebPushPayload(
                title = title,
                body = body,
                url = url,
                tag = tag,
                project = project,
                sessionId = sessionId,
                providerId = providerId,
                kind = kind,
                rawKind = rawKind,
            )
        }
    }
}

/**
 * Route a [WebPushPayload] to its Android [PushChannel].
 */
fun channelForPushPayload(payload: WebPushPayload): PushChannel =
    PushChannel.forKind(payload.kind)

/**
 * Route a raw `kind` wire string (`"approval"`, `"question"`, `"done"`, `"schedule"`, `"other"`,
 * or `null` on older servers) to its Android [PushChannel].
 */
fun channelForPushKind(rawKind: String?): PushChannel =
    PushChannel.forRawKind(rawKind)

/**
 * Build a `ppm://<host>[:<port>]/...` deep link that `MainActivity` and `parsePpmLink` can open
 * when the user taps a push notification (Requirement 7.4).
 *
 * Resolution rules:
 * 1. If [WebPushPayload.url] is already a `ppm://` link, normalize and enrich it with
 *    [WebPushPayload.project] / [WebPushPayload.sessionId] when the path is root.
 * 2. If [WebPushPayload.url] is an `http://` or `https://` URL, use its authority (`host[:port]`)
 *    and path/query.
 * 3. If [WebPushPayload.url] is a relative path (e.g. `"/project/my-app?openChat=..."` or `"/"`),
 *    extract the authority from [fallbackServerUrl].
 */
fun buildPpmDeepLink(
    payload: WebPushPayload,
    fallbackServerUrl: String? = null,
): String? {
    val rawUrl = payload.url.trim()
    val parsedUrl = parseAuthorityAndPath(rawUrl)
    val fallback = fallbackServerUrl?.trim()?.takeIf { it.isNotEmpty() }?.let(::parseAuthorityAndPath)

    val authority = parsedUrl?.authority ?: fallback?.authority ?: return null
    val rawPath = parsedUrl?.path?.takeIf { it.isNotEmpty() } ?: "/"
    val rawQuery = parsedUrl?.query

    val hasSpecificProjectPath = rawPath.startsWith("/project/") && rawPath.length > "/project/".length
    val (finalPath, finalQuery) = if (hasSpecificProjectPath) {
        val enrichedQuery = if (
            rawQuery.isNullOrBlank() &&
            !payload.sessionId.isNullOrBlank() &&
            rawPath.count { it == '/' } == 2
        ) {
            val chatTarget = if (!payload.providerId.isNullOrBlank()) {
                "${payload.providerId}/${payload.sessionId}"
            } else {
                payload.sessionId
            }
            "openChat=${encodeUriComponent(chatTarget)}"
        } else {
            rawQuery
        }
        rawPath to enrichedQuery
    } else if (!payload.project.isNullOrBlank()) {
        val projectPath = "/project/${encodeUriComponent(payload.project)}"
        val chatQuery = if (!payload.sessionId.isNullOrBlank()) {
            val chatTarget = if (!payload.providerId.isNullOrBlank()) {
                "${payload.providerId}/${payload.sessionId}"
            } else {
                payload.sessionId
            }
            "openChat=${encodeUriComponent(chatTarget)}"
        } else {
            rawQuery
        }
        projectPath to chatQuery
    } else {
        rawPath to rawQuery
    }

    return buildString {
        append("ppm://")
        append(authority)
        append(if (finalPath.startsWith('/')) finalPath else "/$finalPath")
        if (!finalQuery.isNullOrBlank()) {
            append('?')
            append(finalQuery)
        }
    }
}

private data class ParsedUrlParts(
    val authority: String?,
    val path: String,
    val query: String?,
)

private fun parseAuthorityAndPath(raw: String): ParsedUrlParts? {
    if (raw.isBlank()) return null
    if (raw.startsWith('/')) {
        val path = raw.substringBefore('#').substringBefore('?')
        val query = raw.substringBefore('#').substringAfter('?', "").takeIf { it.isNotEmpty() }
        return ParsedUrlParts(authority = null, path = path, query = query)
    }
    return runCatching {
        val normalized = when {
            raw.startsWith("ppm://", ignoreCase = true) -> "http://" + raw.substring("ppm://".length)
            raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> raw
            else -> "https://$raw"
        }
        val uri = URI(normalized)
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val port = uri.port
        val defaultPort = when {
            raw.startsWith("http://", ignoreCase = true) -> 80
            raw.startsWith("https://", ignoreCase = true) -> 443
            else -> -1
        }
        val authority = if (port > 0 && port != defaultPort) "$host:$port" else host
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val query = uri.rawQuery?.takeIf { it.isNotEmpty() }
        ParsedUrlParts(authority = authority, path = path, query = query)
    }.getOrNull()
}

private fun encodeUriComponent(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
