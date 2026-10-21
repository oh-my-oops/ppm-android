package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.ChatDraft
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `GET /api/project/:p/chat/drafts/:id`: a session's unsent message, or a new chat's under `__new__`; null when there is none. */
suspend fun ApiClient.draft(project: String, id: String): ChatDraft? =
    ChatDraft.from(call("GET", "${chatPath(project)}/drafts/${ApiClient.escape(id)}"))

/** `PUT …/chat/drafts/:id`. The server keeps the first 50,000 characters. */
suspend fun ApiClient.saveDraft(project: String, id: String, draft: ChatDraft) {
    val body = buildJsonObject {
        put("content", draft.storedContent)
        put("attachments", draft.storedAttachments)
    }
    call("PUT", "${chatPath(project)}/drafts/${ApiClient.escape(id)}", body = body)
}

/** `DELETE …/chat/drafts/:id`, once its message has gone. */
suspend fun ApiClient.deleteDraft(project: String, id: String) {
    call("DELETE", "${chatPath(project)}/drafts/${ApiClient.escape(id)}")
}
