package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.SessionAnswer
import com.hienlh.ppm.protocol.SessionAnswerFile
import com.hienlh.ppm.protocol.SessionAnswerResult
import com.hienlh.ppm.protocol.SessionFileChange
import com.hienlh.ppm.protocol.SessionFileDiff
import com.hienlh.ppm.protocol.SessionReviewResult
import com.hienlh.ppm.protocol.TurnRevertResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/* A chat session's changes (`session-file-changes-client.ts`), under `/chat/sessions/:id/file-changes`. */

private fun ApiClient.changesPath(project: String, sessionId: String) =
    "${chatPath(project)}/sessions/${ApiClient.escape(sessionId)}/file-changes"

/** Every file the session changed; [paths] adds the ones its transcript names. */
suspend fun ApiClient.sessionFileChanges(project: String, sessionId: String, paths: List<String>): List<SessionFileChange> =
    SessionFileChange.listFrom(call("POST", changesPath(project, sessionId), body = buildJsonObject { put("paths", JsonArray(paths.map(::JsonPrimitive))) }))

/** Both sides of one file the session changed: what the Review tab cuts its blocks from. */
suspend fun ApiClient.sessionFileDiff(project: String, sessionId: String, path: String): SessionFileDiff =
    SessionFileDiff.from(call("GET", "${changesPath(project, sessionId)}/diff", listOf("path" to path)))
        ?: throw PpmError.BadResponse("Unexpected diff.")

/**
 * Mark files reviewed, or unmark them, each at the version it was shown at: the server leaves a
 * file that has changed since rather than hide a change nobody has read.
 */
suspend fun ApiClient.setSessionFilesReviewed(project: String, sessionId: String, files: List<SessionFileChange>, reviewed: Boolean): SessionReviewResult =
    SessionReviewResult.from(
        call(
            "POST", "${changesPath(project, sessionId)}/reviewed",
            body = buildJsonObject {
                putJsonArray("files") {
                    for (f in files) addJsonObject { put("path", f.path); put("version", f.version) }
                }
                put("reviewed", reviewed)
            },
        ),
    )

/** Keep blocks, open them again, or revert them on disk — every block of a file whose keys are null. */
suspend fun ApiClient.answerSessionChanges(project: String, sessionId: String, answer: SessionAnswer, files: List<SessionAnswerFile>): SessionAnswerResult =
    SessionAnswerResult.from(
        call(
            "POST", "${changesPath(project, sessionId)}/answer",
            body = buildJsonObject {
                put("answer", answer.wire)
                putJsonArray("files") {
                    for (f in files) {
                        addJsonObject {
                            put("path", f.path)
                            put("version", f.version)
                            f.keys?.let { keys -> putJsonArray("keys") { keys.forEach { add(it) } } }
                        }
                    }
                }
            },
        ),
    )

/**
 * What reverting a turn (named by its [calls]) would put back; with [apply], each file at the
 * version shown, it is written — or nothing is, when one moved on (`stale`).
 */
suspend fun ApiClient.revertSessionTurn(project: String, sessionId: String, calls: List<String>, apply: List<Pair<String, String>>? = null): TurnRevertResult =
    TurnRevertResult.from(
        call(
            "POST", "${changesPath(project, sessionId)}/revert-turn",
            body = buildJsonObject {
                putJsonArray("calls") { calls.forEach { add(it) } }
                if (apply != null) {
                    putJsonArray("apply") {
                        for ((path, version) in apply) addJsonObject { put("path", path); put("version", version) }
                    }
                }
            },
        ),
    )

/** Undo an answer or a turn's revert: every file it touched, or none of them (`stale`). */
suspend fun ApiClient.undoSessionAnswer(project: String, sessionId: String, undoId: String): SessionAnswerResult =
    SessionAnswerResult.from(call("POST", "${changesPath(project, sessionId)}/undo", body = buildJsonObject { put("undoId", undoId) }))
