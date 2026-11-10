package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.long
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Notification feed item category returned by `/api/notifications/feed`.
 */
enum class FeedKind(val wire: String) {
    APPROVAL("approval"),
    QUESTION("question"),
    DONE("done"),
    SCHEDULE("schedule"),
    OTHER("other"),
    ;

    companion object {
        fun fromWire(value: String?): FeedKind = when (value?.trim()?.lowercase()) {
            "approval", "permission_request" -> APPROVAL
            "question", "ask_user_question" -> QUESTION
            "done", "turn_complete" -> DONE
            "schedule" -> SCHEDULE
            else -> OTHER
        }
    }
}

/**
 * One persisted notification row in the server's notification feed (`GET /api/notifications/feed`).
 */
data class FeedRow(
    val id: Long,
    val kind: FeedKind,
    val project: String,
    val sessionId: String? = null,
    val scheduleId: String? = null,
    val title: String,
    val body: String = "",
    val createdAt: Long = 0L,
    val read: Boolean = false,
) {
    constructor(
        id: Int,
        kind: FeedKind,
        project: String,
        sessionId: String? = null,
        scheduleId: String? = null,
        title: String,
        body: String = "",
        createdAt: Long = 0L,
        read: Boolean = false,
    ) : this(
        id = id.toLong(),
        kind = kind,
        project = project,
        sessionId = sessionId,
        scheduleId = scheduleId,
        title = title,
        body = body,
        createdAt = createdAt,
        read = read,
    )

    constructor(
        id: Long,
        kind: String,
        project: String,
        sessionId: String? = null,
        scheduleId: String? = null,
        title: String,
        body: String = "",
        createdAt: Long = 0L,
        read: Boolean = false,
    ) : this(
        id = id,
        kind = FeedKind.fromWire(kind),
        project = project,
        sessionId = sessionId,
        scheduleId = scheduleId,
        title = title,
        body = body,
        createdAt = createdAt,
        read = read,
    )

    val kindWire: String get() = kind.wire

    companion object {
        fun from(json: JsonElement?): FeedRow? {
            if (json?.obj == null) return null
            val id = json["id"].long ?: return null
            val kind = FeedKind.fromWire(json["kind"].string)
            val project = json["project"].string ?: ""
            val sessionId = json["sessionId"].string?.takeIf { it.isNotBlank() }
                ?: json["session_id"].string?.takeIf { it.isNotBlank() }
            val scheduleId = json["scheduleId"].string?.takeIf { it.isNotBlank() }
                ?: json["schedule_id"].string?.takeIf { it.isNotBlank() }
            val title = json["title"].string ?: ""
            val body = json["body"].string ?: ""
            val createdAt = json["createdAt"].long ?: json["created_at"].long ?: 0L
            val read = json["read"].bool ?: (json["readAt"].long != null || json["read_at"].long != null)
            return FeedRow(
                id = id,
                kind = kind,
                project = project,
                sessionId = sessionId,
                scheduleId = scheduleId,
                title = title,
                body = body,
                createdAt = createdAt,
                read = read,
            )
        }
    }
}

/**
 * Page returned by `GET /api/notifications/feed`.
 */
data class FeedPage(
    val rows: List<FeedRow>,
    val hasMore: Boolean = false,
) {
    companion object {
        fun from(json: JsonElement?): FeedPage {
            if (json is JsonArray) {
                return FeedPage(
                    rows = json.mapNotNull(FeedRow::from),
                    hasMore = false,
                )
            }
            if (json?.obj == null) return FeedPage(emptyList(), false)
            val rows = json["rows"].array?.mapNotNull(FeedRow::from) ?: emptyList()
            val hasMore = json["hasMore"].bool ?: false
            return FeedPage(rows = rows, hasMore = hasMore)
        }
    }
}

/**
 * Thrown when the connected PPM server predates `/api/notifications/feed` (HTTP 404).
 */
class FeedUnsupported(
    override val message: String = "This server does not support the notification feed.",
    cause: Throwable? = null,
) : Exception(message, cause)

private suspend inline fun <T> translateFeedUnsupported(block: suspend () -> T): T {
    return try {
        block()
    } catch (err: FeedUnsupported) {
        throw err
    } catch (err: PpmError.OutdatedServer) {
        throw FeedUnsupported(err.message ?: "This server does not support the notification feed.", err)
    } catch (err: PpmError.Server) {
        if (err.status == 404) {
            throw FeedUnsupported(err.serverMessage, err)
        }
        throw err
    } catch (err: PpmError.BadResponse) {
        if (err.reason.contains("404")) {
            throw FeedUnsupported(err.reason, err)
        }
        throw err
    }
}

/**
 * Fetch a page from `GET /api/notifications/feed?limit=&before=`.
 * Throws [FeedUnsupported] when the server returns HTTP 404.
 */
suspend fun ApiClient.feed(limit: Int? = null, before: Long? = null): FeedPage =
    translateFeedUnsupported {
        val query = buildList {
            if (limit != null) add("limit" to "$limit")
            if (before != null) add("before" to "$before")
        }
        val data = call("GET", "/api/notifications/feed", query = query)
        FeedPage.from(data)
    }

/**
 * Toggle a notification row's read state via `POST /api/notifications/feed/:id/read` or `/unread`.
 * Throws [FeedUnsupported] when the server returns HTTP 404.
 */
suspend fun ApiClient.setFeedRead(id: Long, read: Boolean): FeedRow =
    translateFeedUnsupported {
        val action = if (read) "read" else "unread"
        val data = call("POST", "/api/notifications/feed/$id/$action", body = JsonObject(emptyMap()))
        FeedRow.from(data) ?: throw PpmError.BadResponse("Invalid notification feed row response.")
    }

suspend fun ApiClient.setFeedRead(id: Int, read: Boolean): FeedRow =
    setFeedRead(id.toLong(), read)

/**
 * Alias matching the spec's `setRead(id, read)` naming on [ApiClient].
 */
suspend fun ApiClient.setRead(id: Long, read: Boolean): FeedRow =
    setFeedRead(id, read)

suspend fun ApiClient.setRead(id: Int, read: Boolean): FeedRow =
    setFeedRead(id.toLong(), read)

/**
 * Mark all notifications read via `POST /api/notifications/feed/read-all`.
 * Throws [FeedUnsupported] when the server returns HTTP 404.
 */
suspend fun ApiClient.readAllFeed() =
    translateFeedUnsupported {
        call("POST", "/api/notifications/feed/read-all", body = JsonObject(emptyMap()))
        Unit
    }

/**
 * Alias matching the spec's `readAll()` naming on [ApiClient].
 */
suspend fun ApiClient.readAll() =
    readAllFeed()

/**
 * Wrapper helper around [ApiClient] for the notification feed endpoints.
 */
class ApiClientFeed(private val api: ApiClient) {
    suspend fun feed(limit: Int? = null, before: Long? = null): FeedPage =
        api.feed(limit = limit, before = before)

    suspend fun setRead(id: Long, read: Boolean): FeedRow =
        api.setFeedRead(id = id, read = read)

    suspend fun setRead(id: Int, read: Boolean): FeedRow =
        api.setFeedRead(id = id.toLong(), read = read)

    suspend fun readAll() =
        api.readAllFeed()
}
