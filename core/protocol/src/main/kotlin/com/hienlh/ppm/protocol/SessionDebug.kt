package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement

/** `GET …/chat/sessions/:id/debug`: the ids behind a session and its transcript on disk (session-debug-dialog.tsx). */
data class SessionDebugInfo(
    val ppmSessionId: String,
    val sdkSessionId: String,
    /** Null when the server found no transcript. */
    val jsonlPath: String?,
    val projectPath: String,
    val jsonlSizeBytes: Long?,
    /** Not counted past 64 MB, where counting would hold the server up. */
    val jsonlLines: Long?,
) {
    companion object {
        fun from(json: JsonElement): SessionDebugInfo = SessionDebugInfo(
            ppmSessionId = json["ppmSessionId"].string ?: "",
            sdkSessionId = json["sdkSessionId"].string ?: "",
            jsonlPath = json["jsonlPath"].string,
            projectPath = json["projectPath"].string ?: "",
            jsonlSizeBytes = json["jsonlSizeBytes"].long,
            jsonlLines = json["jsonlLines"].long,
        )
    }
}

/** A turn of `GET …/chat/sessions/:id/usage`, which lists them newest first. */
data class TurnRecord(val id: Long, val recordedAt: String, val usage: TurnUsage) {
    companion object {
        fun list(json: JsonElement): List<TurnRecord> = json["turns"].array.orEmpty().mapNotNull { row ->
            val usage = TurnUsage.from(row["usage"]) ?: return@mapNotNull null
            TurnRecord(row["id"].long ?: 0, row["recordedAt"].string ?: "", usage)
        }
    }
}

/**
 * `GET …/chat/sessions/:id/images`: the image payloads a transcript replays on every turn, and
 * those past the API's per-image side [limit], which fail a turn that carries several images.
 */
data class ImageAudit(
    val total: Int,
    val oversized: Int,
    /** Base64 bytes of all of them. */
    val bytes: Long,
    val oversizedBytes: Long,
    val largestSide: Int,
    /** Images the user attached, which the plain buttons leave alone. */
    val attachments: Int,
    val oversizedAttachments: Int,
    val limit: Int,
) {
    companion object {
        fun from(json: JsonElement?): ImageAudit? {
            if (json["total"].long == null) return null
            return ImageAudit(
                total = json["total"].int ?: 0,
                oversized = json["oversized"].int ?: 0,
                bytes = json["bytes"].long ?: 0,
                oversizedBytes = json["oversizedBytes"].long ?: 0,
                largestSide = json["largestSide"].int ?: 0,
                attachments = json["attachments"].int ?: 0,
                oversizedAttachments = json["oversizedAttachments"].int ?: 0,
                limit = json["limit"].int ?: 0,
            )
        }
    }
}

/** `POST …/chat/sessions/:id/images/strip`: how many images went, the bytes freed, and what is left. */
data class ImageStripResult(val removed: Int, val bytesFreed: Long, val remaining: ImageAudit) {
    companion object {
        fun from(json: JsonElement): ImageStripResult? {
            val remaining = ImageAudit.from(json["remaining"]) ?: return null
            return ImageStripResult(json["removed"].int ?: 0, json["bytesFreed"].long ?: 0, remaining)
        }
    }
}
