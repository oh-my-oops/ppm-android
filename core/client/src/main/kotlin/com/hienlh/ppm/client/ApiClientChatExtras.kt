package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.SessionBookmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Response
import java.io.IOException
import java.net.URLDecoder

/*
 * A chat's archive state, its bookmarked turns and its Markdown export
 * (`PUT /chat/sessions/:id/archive`, `/chat/sessions/:id/bookmarks[/:turnId]`, `GET /chat/sessions/:id/export.md`).
 */

private fun ApiClient.sessionPath(project: String, sessionId: String) =
    "${chatPath(project)}/sessions/${ApiClient.escape(sessionId)}"

/** Archive a chat (`archived`), keep it out of auto-archive (`kept`), or forget either (null). */
suspend fun ApiClient.setSessionArchive(project: String, sessionId: String, state: String?) {
    call("PUT", "${sessionPath(project, sessionId)}/archive", body = buildJsonObject {
        if (state == null) put("state", JsonNull) else put("state", state)
    })
}

/** The chat's bookmarks, in turn order. */
suspend fun ApiClient.sessionBookmarks(project: String, sessionId: String): List<SessionBookmark> =
    SessionBookmark.listFrom(call("GET", "${sessionPath(project, sessionId)}/bookmarks"))

/** Bookmark a turn, or rename one; answers the whole list as the server keeps it. */
suspend fun ApiClient.putSessionBookmark(project: String, sessionId: String, turnId: String, turnN: Int?, label: String): List<SessionBookmark> =
    SessionBookmark.listFrom(
        call("PUT", "${sessionPath(project, sessionId)}/bookmarks/${ApiClient.escape(turnId)}", body = buildJsonObject {
            if (turnN != null) put("turnN", turnN)
            put("label", label)
        }),
    )

/** Takes a turn's bookmark off; answers the list left. */
suspend fun ApiClient.deleteSessionBookmark(project: String, sessionId: String, turnId: String): List<SessionBookmark> =
    SessionBookmark.listFrom(call("DELETE", "${sessionPath(project, sessionId)}/bookmarks/${ApiClient.escape(turnId)}"))

/** A chat exported as Markdown: the name the server chose and the document. */
class ChatExport(val fileName: String, val markdown: String)

/** The whole chat as Markdown (`exportChatMarkdown` in `chat-export.ts`). */
suspend fun ApiClient.exportChatMarkdown(project: String, sessionId: String, providerId: String): ChatExport {
    val request = request("GET", "${sessionPath(project, sessionId)}/export.md", listOf("providerId" to providerId))
    val response: Response = try {
        PpmHttp.client.newCall(request).await()
    } catch (error: IOException) {
        throw PpmError.Unreachable(error.message ?: "Network error")
    }
    return withContext(Dispatchers.IO) {
        response.use { res ->
            val text = res.body.string()
            if (res.code !in 200..299) {
                // The error the server sent, or the status when it sent none (`Export failed (404)`).
                try {
                    ApiClient.unwrap(res.code, res.header("Content-Type"), text)
                } catch (e: PpmError.Server) {
                    throw e
                } catch (_: Exception) {
                }
                throw PpmError.Server(res.code, "Export failed (${res.code})")
            }
            ChatExport(dispositionFileName(res.header("Content-Disposition"), "chat.md"), text)
        }
    }
}

/** The file name a `Content-Disposition` header carries, preferring its UTF-8 form (`dispositionFileName`). */
fun dispositionFileName(header: String?, fallback: String): String {
    if (header == null) return fallback
    Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(header)?.let { star ->
        try {
            return URLDecoder.decode(star.groupValues[1].trim().replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
        }
    }
    return Regex("filename=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1) ?: fallback
}
