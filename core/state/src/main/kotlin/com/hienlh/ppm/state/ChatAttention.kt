package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ApprovalRequest
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string

/**
 * What a chat that is not in front of the user calls for (use-chat.ts): a sound when its turn
 * ends, and a sound and a toast that stays when it waits for an approval or an answer.
 */
sealed interface ChatAttention {
    /** An approval or a question came in live (`approval_request`, never its replay). */
    data class Asked(val request: ApprovalRequest) : ChatAttention

    /** It was answered, here or on another device (`approval_resolved`): the toast goes. */
    data object Answered : ChatAttention

    /** The turn ended (`done` while it still ran). */
    data object Done : ChatAttention
}

/** The toast and the sound an approval or a question gets (`approval_request` in use-chat.ts). */
data class AttentionNotice(val title: String, val description: String, val warning: Boolean, val sound: String) {
    companion object {
        fun of(request: ApprovalRequest, projectName: String, sessionId: String): AttentionNotice {
            val question = request.tool == "AskUserQuestion"
            val connection = dbConnectionName(request)
            return AttentionNotice(
                title = when {
                    question -> "AI has a question"
                    connection != null -> "AI wants to change $connection"
                    else -> "${request.tool} needs permission"
                },
                description = projectName.ifEmpty { "Session ${sessionId.take(8)}" },
                warning = !question,
                sound = if (question) NotificationTones.QUESTION else NotificationTones.APPROVAL,
            )
        }

        /** A `db_execute` approval's connection (`dbExecuteApprovalInput`); null for any other request. */
        private fun dbConnectionName(request: ApprovalRequest): String? {
            if (request.tool != "ppm:db_execute") return null
            val input = request.input
            if (input["connectionId"].double == null || input["sql"].string == null) return null
            return input["connectionName"].string
        }
    }
}
