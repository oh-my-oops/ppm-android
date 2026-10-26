package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.HistoryMessage
import com.hienlh.ppm.protocol.HistoryPage
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.ProjectTags
import com.hienlh.ppm.protocol.ProviderInfo
import com.hienlh.ppm.protocol.ResourceSample
import com.hienlh.ppm.protocol.ServerInfo
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SessionPage
import com.hienlh.ppm.protocol.SessionUnread
import com.hienlh.ppm.protocol.TaskItem
import com.hienlh.ppm.protocol.ThemeSelection
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * REST calls to one PPM server. Every route answers `{ ok, data?, error? }`; this returns
 * `data` or throws the error as a [PpmError].
 */
class ApiClient(val baseUrl: HttpUrl, val token: String) {
    // Routes

    suspend fun info(): ServerInfo {
        // Every PPM has this route, so a page instead of JSON means it is not PPM at all.
        val json = try {
            call("GET", "/api/info")
        } catch (_: PpmError.OutdatedServer) {
            throw PpmError.BadResponse(NOT_PPM)
        }
        return ServerInfo.from(json) ?: throw PpmError.BadResponse(NOT_PPM)
    }

    /** Throws [PpmError.Unauthorized] when the password is wrong. */
    suspend fun checkAuth() {
        call("GET", "/api/auth/check")
    }

    /** `GET /api/health`, which answers whenever the server runs. */
    suspend fun health() {
        call("GET", "/api/health")
    }

    /** The server's address on its own network (`/api/tunnel`'s `localUrl`), when it knows one. */
    suspend fun localUrl(): String? = call("GET", "/api/tunnel")["localUrl"].string

    /** The server's OS as Node names it (`win32`, `darwin`, `linux`), from `/api/system/host`. */
    suspend fun hostPlatform(): String? = call("GET", "/api/system/host")["platform"].string

    /** The permanent hostname of the server's named tunnel, while it is set to one. */
    suspend fun namedTunnelHostname(): String? {
        val status = call("GET", "/api/tunnel/named/status")
        return status["hostname"].string?.takeIf { status["mode"].string == "named" }
    }

    suspend fun projects(): List<ProjectInfo> =
        call("GET", "/api/projects").array.orEmpty().mapNotNull(ProjectInfo::from)

    suspend fun providers(project: String): List<ProviderInfo> =
        call("GET", "${chatPath(project)}/providers").array.orEmpty().mapNotNull(ProviderInfo::from)

    /**
     * A page of sessions, pinned first. [search] keeps those whose title contains it. [offset]
     * counts unpinned sessions only: the server puts every pinned one on the first page.
     */
    suspend fun sessions(project: String, limit: Int = 50, offset: Int = 0, search: String? = null): SessionPage {
        val query = mutableListOf("limit" to "$limit", "offset" to "$offset")
        if (!search.isNullOrEmpty()) query += "q" to search
        val json = call("GET", "${chatPath(project)}/sessions", query)
        return SessionPage.from(json) ?: throw PpmError.BadResponse("Unexpected session list.")
    }

    suspend fun setPinned(pinned: Boolean, project: String, sessionId: String) {
        call(if (pinned) "PUT" else "DELETE", "${chatPath(project)}/sessions/${escape(sessionId)}/pin")
    }

    /**
     * A new session; the server's default provider when [providerId] is null. [clearedFrom] is the
     * session a `/clear` left, which the server keeps as its lineage.
     */
    suspend fun createSession(
        project: String,
        providerId: String?,
        title: String? = null,
        clearedFrom: String? = null,
        /** The account the tab claimed before it had a session, which the session starts on. */
        accountId: String? = null,
    ): SessionInfo {
        val body = buildJsonObject {
            if (providerId != null) put("providerId", providerId)
            if (title != null) put("title", title)
            if (clearedFrom != null) put("clearedFrom", clearedFrom)
            if (accountId != null) put("accountId", accountId)
        }
        val json = call("POST", "${chatPath(project)}/sessions", body = body)
        return SessionInfo.from(json) ?: throw PpmError.BadResponse("Unexpected session.")
    }

    /**
     * `POST …/sessions/:id/fork`: a new session holding [sessionId]'s messages up to and including
     * [messageId], or an empty one without it. With [edit] it is another version of the same
     * conversation, kept under the original's title; otherwise a thread of its own.
     */
    suspend fun forkSession(project: String, sessionId: String, providerId: String, messageId: String?, edit: Boolean): SessionInfo {
        val query = mutableListOf("providerId" to providerId)
        if (edit) query += "mode" to "edit"
        val body = buildJsonObject { if (messageId != null) put("messageId", messageId) }
        val json = call("POST", "${chatPath(project)}/sessions/${escape(sessionId)}/fork", query, body)
        return SessionInfo.from(json) ?: throw PpmError.BadResponse("Unexpected session.")
    }

    /** The newest [limit] messages (aligned to a turn start), the ones [before] an index, or everything [from] an index on. */
    suspend fun history(
        project: String,
        sessionId: String,
        providerId: String,
        limit: Int? = null,
        before: Int? = null,
        from: Int? = null,
    ): HistoryPage {
        val query = mutableListOf("providerId" to providerId)
        if (limit != null) query += "limit" to "$limit"
        if (before != null) query += "before" to "$before"
        if (from != null) query += "from" to "$from"
        val json = call("GET", "${chatPath(project)}/sessions/${escape(sessionId)}/messages", query)
        return HistoryPage.from(json) ?: throw PpmError.BadResponse("Unexpected history.")
    }

    /**
     * The compaction segment that ends at the message [before] in the transcript at [jsonlPath],
     * the one a compact summary names: the conversation that summary replaced, its own summary first.
     */
    suspend fun preCompactMessages(project: String, jsonlPath: String, before: String): List<HistoryMessage> =
        call("GET", "${chatPath(project)}/pre-compact-messages", listOf("jsonlPath" to jsonlPath, "before" to before))
            .array.orEmpty().mapNotNull(HistoryMessage::from)

    /** Claude's tasks in a chat, rebuilt from its whole transcript. */
    suspend fun sessionTasks(project: String, sessionId: String): List<TaskItem> =
        call("GET", "${chatPath(project)}/sessions/${escape(sessionId)}/tasks").array.orEmpty().mapNotNull(TaskItem::from)

    /** Read on every device (the server broadcasts it), and a notification still waiting for its delay is dropped. */
    suspend fun markRead(project: String, sessionId: String) {
        call("POST", "${chatPath(project)}/sessions/${escape(sessionId)}/read")
    }

    /** Every session with a turn in flight, on any project. */
    suspend fun runningSessions(): Set<String> =
        call("GET", "/api/chat/sessions/running").array.orEmpty().mapNotNull { it["sessionId"].string }.toSet()

    /** Every unread session, on any project: the route sits under one but answers for all. */
    suspend fun unreadSessions(project: String): Map<String, SessionUnread> =
        SessionUnread.list(call("GET", "${chatPath(project)}/sessions/unread"))

    fun chatSocketUrl(project: String, sessionId: String, providerId: String): String =
        socketUrl("/ws/project/${escape(project)}/chat/${escape(sessionId)}", listOf("providerId" to providerId))

    /** The app-wide socket: running and unread sessions, file changes, and other broadcasts. */
    fun globalSocketUrl(): String = socketUrl("/ws/global", emptyList())

    /** A group's feed as it is written, and the client's `message` and `stop`. */
    fun groupSocketUrl(project: String, groupId: String): String =
        socketUrl("/ws/project/${escape(project)}/group/${escape(groupId)}", emptyList())

    /** Sockets authenticate by `?token=` only: the server reads no header on upgrade. */
    private fun socketUrl(path: String, query: List<Pair<String, String>>): String {
        val http = url(path, query + ("token" to token)).toString()
        return if (http.startsWith("https:")) "wss:" + http.removePrefix("https:") else "ws:" + http.removePrefix("http:")
    }

    /** The project's session tags and how many sessions carry each. */
    suspend fun tags(project: String): ProjectTags = ProjectTags.from(call("GET", tagsPath(project)))

    /** The theme picked on any device; null before anyone picked one. */
    suspend fun themeSelection(): ThemeSelection? = ThemeSelection.from(call("GET", "/api/settings/theme")["theme"])

    /** Picks the theme for every client, as the web's theme picker does. */
    suspend fun setTheme(selection: ThemeSelection) {
        call("PUT", "/api/settings/theme", body = selection.json)
    }

    /**
     * The machine's CPU and memory, one sample per server snapshot (every ~2 s), from the same
     * light-tier stream the web's status bar reads. The stream holds a lease the server expires
     * after 30 s, so it is renewed every 10 s and dropped when the stream ends.
     */
    fun resourceSamples(): Flow<ResourceSample> = callbackFlow {
        var sid: String? = null
        val lease = launch {
            while (isActive) {
                delay(10_000)
                sid?.let { runCatching { call("POST", "/api/system/resources/stream/${escape(it)}/ping") } }
            }
        }
        val request = request("GET", "/api/system/resources/stream", listOf("processes" to "0"))
            .newBuilder().header("Accept", "text/event-stream").build()
        val call = PpmHttp.streamClient.newCall(request)
        val reader = launch(Dispatchers.IO) {
            try {
                call.await().use { response ->
                    if (response.code == 401) throw PpmError.Unauthorized()
                    if (response.code != 200) {
                        throw PpmError.Server(response.code, "The metrics stream answered HTTP ${response.code}.")
                    }
                    val source = response.body.source()
                    var event = ""
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> {
                                val json = WireJson.parseOrNull(line.removePrefix("data:"))
                                if (json != null) {
                                    if (event == "session") {
                                        sid = json["sid"].string
                                    } else if (event == "snapshot") {
                                        ResourceSample.from(json)?.let { trySend(it) }
                                    }
                                }
                                event = ""
                            }
                        }
                    }
                }
                close()
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                close(PpmError.Unreachable(describe(error)))
            } catch (error: Throwable) {
                close(error)
            }
        }
        awaitClose {
            reader.cancel()
            lease.cancel()
            call.cancel()
            sid?.let { id -> Background.scope.launch { runCatching { call("DELETE", "/api/system/resources/stream/${escape(id)}") } } }
        }
    }.flowOn(Dispatchers.IO)

    // Transport

    internal fun url(path: String, query: List<Pair<String, String>>): HttpUrl =
        baseUrl.newBuilder()
            .encodedPath(baseUrl.encodedPath.trimEnd('/') + path)
            .encodedQuery(if (query.isEmpty()) null else query(query))
            .build()

    internal fun request(method: String, path: String, query: List<Pair<String, String>> = emptyList(), body: RequestBody? = null): Request =
        Request.Builder()
            .url(url(path, query))
            .method(method, body ?: if (method == "GET" || method == "HEAD") null else EMPTY_BODY)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("x-ppm-client", PpmHttp.CLIENT_NAME)
            .build()

    suspend fun call(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: JsonElement? = null,
    ): JsonElement {
        val request = request(method, path, query, body?.toString()?.toRequestBody(JSON))
        if (method != "GET") return send(PpmHttp.client, request)
        // Several views asking for the same thing at once share one request, as on the web.
        return InflightGets.value("$token\u0000${request.url}") { send(PpmHttp.getClient, request) }
    }

    internal suspend fun send(client: OkHttpClient, request: Request): JsonElement {
        val response = try {
            client.newCall(request).await()
        } catch (error: IOException) {
            throw PpmError.Unreachable(describe(error))
        }
        return withContext(Dispatchers.IO) {
            response.use {
                val text = try {
                    it.body.string()
                } catch (error: IOException) {
                    throw PpmError.Unreachable(describe(error))
                }
                unwrap(it.code, it.header("Content-Type"), text)
            }
        }
    }

    internal fun chatPath(project: String) = "/api/project/${escape(project)}/chat"

    internal fun tagsPath(project: String) = "/api/project/${escape(project)}/tags"

    companion object {
        internal const val NOT_PPM = "This address answers, but not like a PPM server."
        private val JSON = "application/json".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

        /** `data` of a `{ ok, data, error }` answer, or the error it carries. */
        internal fun unwrap(status: Int, contentType: String?, body: String): JsonElement {
            if (status == 401) throw PpmError.Unauthorized()
            val json = WireJson.parseOrNull(body)
            if (json == null || json["ok"] == null) {
                // Before 404s were JSON, a route the server lacks answered with the web app's page.
                if (status == 200 && contentType?.contains("text/html") == true) throw PpmError.OutdatedServer()
                throw PpmError.BadResponse(if (status == 200) NOT_PPM else "The server answered HTTP $status.")
            }
            if (json["ok"].bool != true) {
                throw PpmError.Server(status, json["error"].string ?: "The server answered HTTP $status.", json["code"].string)
            }
            return json["data"] ?: JsonNull
        }

        /** RFC 3986 unreserved characters only, so `+`, `&` and `/` in a password or a project name reach the server as themselves. */
        fun escape(text: String): String {
            val out = StringBuilder(text.length)
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xff
                if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                    c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
                ) {
                    out.append(c.toChar())
                } else {
                    out.append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
                }
            }
            return out.toString()
        }

        private const val HEX = "0123456789ABCDEF"

        internal fun query(items: List<Pair<String, String>>): String =
            items.joinToString("&") { (key, value) -> "${escape(key)}=${escape(value)}" }

        /** Accepts what people type: `192.168.1.10:8080`, `http://host:8080/`, `https://x.trycloudflare.com`. */
        fun normalizedServerUrl(text: String): HttpUrl? {
            var trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            if (!trimmed.contains("://")) trimmed = "http://$trimmed"
            trimmed = trimmed.trimEnd('/')
            val scheme = trimmed.substringBefore("://").lowercase()
            if (scheme != "http" && scheme != "https") return null
            return trimmed.toHttpUrlOrNull()
        }
    }
}
