package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.ImageAudit
import com.hienlh.ppm.protocol.ImageStripResult
import com.hienlh.ppm.protocol.SessionDebugInfo
import com.hienlh.ppm.protocol.TurnRecord
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// A session's debug facts and transcript upkeep (session-debug-dialog.tsx), and the bell's Clear all.

private fun ApiClient.sessionPath(project: String, sessionId: String) =
    "${chatPath(project)}/sessions/${ApiClient.escape(sessionId)}"

/** The session's ids and where its transcript is, how big and how many records. */
suspend fun ApiClient.sessionDebug(project: String, sessionId: String): SessionDebugInfo =
    SessionDebugInfo.from(call("GET", "${sessionPath(project, sessionId)}/debug", listOf("project" to project)))

/** The token split of the session's last turns, newest first. */
suspend fun ApiClient.sessionTurnUsage(project: String, sessionId: String): List<TurnRecord> =
    TurnRecord.list(call("GET", "${sessionPath(project, sessionId)}/usage", listOf("project" to project)))

/** The images the transcript replays on every turn. */
suspend fun ApiClient.sessionImages(project: String, sessionId: String): ImageAudit =
    ImageAudit.from(call("GET", "${sessionPath(project, sessionId)}/images", listOf("project" to project)))
        ?: throw PpmError.BadResponse("The server sent no image count.")

/**
 * Replaces the transcript's oversized images, or all of them, with their placeholder text; the
 * user's own attachments only with [includeAttachments]. Refused (409) while a turn runs.
 */
suspend fun ApiClient.stripSessionImages(project: String, sessionId: String, all: Boolean, includeAttachments: Boolean): ImageStripResult {
    val json = call(
        "POST",
        "${sessionPath(project, sessionId)}/images/strip",
        listOf("project" to project),
        body = buildJsonObject {
            put("mode", if (all) "all" else "oversized")
            put("includeAttachments", includeAttachments)
        },
    )
    return ImageStripResult.from(json) ?: throw PpmError.BadResponse("The server sent no image count.")
}

/**
 * Reads every session in [sessionIds] at once, on a route that takes no project: the per-session
 * one is mounted under its project, whose middleware refuses a session whose project was renamed
 * or deleted — the ones that most need clearing.
 */
suspend fun ApiClient.markAllRead(sessionIds: List<String>) {
    call("POST", "/api/chat/sessions/read", body = buildJsonObject {
        put("sessionIds", buildJsonArray { sessionIds.forEach { add(JsonPrimitive(it)) } })
    })
}
