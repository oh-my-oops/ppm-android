package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.ContextUsageView
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `GET …/chat/sessions/:id/context-usage?detail=…`: what fills the session's context window, asked
 * of its live Claude CLI (the footer's context meter). Null when no Claude process runs for it.
 */
suspend fun ApiClient.contextUsage(project: String, sessionId: String, detail: String = "full"): ContextUsageView? =
    ContextUsageView.from(
        call("GET", "${chatPath(project)}/sessions/${ApiClient.escape(sessionId)}/context-usage", listOf("detail" to detail))["usage"],
    )

/**
 * `POST …/chat/sessions/:id/side-question { question }`: Claude Code's `/btw`, answered from the
 * session's context by its live CLI and never added to the chat. Null when Claude gave no answer;
 * a 409 (no Claude process running) and a 400 come back as [PpmError.Server] with the server's words.
 */
suspend fun ApiClient.askSideQuestion(project: String, sessionId: String, question: String): String? =
    call(
        "POST",
        "${chatPath(project)}/sessions/${ApiClient.escape(sessionId)}/side-question",
        body = buildJsonObject { put("question", question) },
    )["response"].string
