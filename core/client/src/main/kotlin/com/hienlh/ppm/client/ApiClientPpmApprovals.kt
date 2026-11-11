package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.SessionCollabAnswer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * PPM's own approvals, answered over authenticated HTTP and never over the chat socket, which
 * drops an `approval_response` for them (`ws/chat-ppm-approvals.ts`). The card leaves when the
 * server's `approval_resolved` reaches the chat.
 */

/**
 * A session tool's card (create a session, pair limit, escalation): `POST
 * /api/sessions-collab/approvals/:requestId` (`answerCollabApproval`). A 404 once the card no
 * longer waits, a 400 for an answer its kind does not offer.
 */
suspend fun ApiClient.answerCollabApproval(requestId: String, answer: SessionCollabAnswer) {
    call("POST", "/api/sessions-collab/approvals/${ApiClient.escape(requestId)}", body = buildJsonObject { put("answer", answer.wire) })
}

/**
 * A `db_execute` change: `POST /api/db/ai-approvals/:requestId` with `{ approved, password? }`.
 * Approving takes PPM's [password]; a wrong one is a 403 that leaves the approval waiting.
 */
suspend fun ApiClient.answerDbApproval(requestId: String, approved: Boolean, password: String? = null) {
    call(
        "POST",
        "/api/db/ai-approvals/${ApiClient.escape(requestId)}",
        body = buildJsonObject {
            put("approved", approved)
            if (approved && password != null) put("password", password)
        },
    )
}
