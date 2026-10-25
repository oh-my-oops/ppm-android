package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement

/*
 * What a chat session's `file-changes` routes answer (`src/shared/session-file-changes.ts`): the
 * session's changes file by file, against the state each file was in before the session first
 * touched it, and what keeping, reopening, reverting or undoing them did.
 */

/** One file the session changed. */
data class SessionFileChange(
    /** Absolute. */
    val path: String,
    val status: Status,
    /** Lines; null when they cannot be counted (binary or oversized on either side). */
    val additions: Int? = null,
    val deletions: Int? = null,
    /** Where the "before" came from: `session` (the session's own copy) or `head` (git HEAD). */
    val baseline: String = "session",
    val binary: Boolean = false,
    val tooLarge: Boolean = false,
    /** Size and modification time on disk ("" once deleted): an answer names it, and is refused once it moves. */
    val version: String = "",
    /** Marked reviewed and still as marked: the lists hide it. */
    val reviewed: Boolean = false,
    /** Marked reviewed and changed since: its counts are against the state it was marked in. */
    val sinceReview: Boolean = false,
    /** Its change blocks in file order; null when the file can only be answered whole. */
    val blocks: List<SessionBlockSummary>? = null,
    /** Names the text the blocks were cut against. */
    val base: String? = null,
) {
    enum class Status(val wire: String) { ADDED("added"), MODIFIED("modified"), DELETED("deleted") }

    companion object {
        /** Null for an entry with no path: nothing could answer for it. */
        fun from(json: JsonElement?): SessionFileChange? {
            val path = json["path"].string?.ifEmpty { null } ?: return null
            return SessionFileChange(
                path = path,
                status = Status.entries.firstOrNull { it.wire == json["status"].string } ?: Status.MODIFIED,
                additions = json["additions"].int,
                deletions = json["deletions"].int,
                baseline = json["baseline"].string ?: "session",
                binary = json["binary"].bool ?: false,
                tooLarge = json["tooLarge"].bool ?: false,
                version = json["version"].string.orEmpty(),
                reviewed = json["reviewed"].bool ?: false,
                sinceReview = json["sinceReview"].bool ?: false,
                blocks = json["blocks"].array?.mapNotNull(SessionBlockSummary::from),
                base = json["base"].string,
            )
        }

        /** `{ files }`, or none for an answer of any other shape: the bar is in every chat and must not fail with it. */
        fun listFrom(json: JsonElement?): List<SessionFileChange> = json["files"].array?.mapNotNull(::from).orEmpty()
    }
}

data class SessionBlockSummary(
    val key: String,
    val added: Int,
    val removed: Int,
    /** The user kept it, or the whole file is marked reviewed. */
    val kept: Boolean = false,
    /** The calls that wrote it, in the order they ran; null where the session's history names none. */
    val calls: List<String>? = null,
) {
    companion object {
        fun from(json: JsonElement?): SessionBlockSummary? {
            val key = json["key"].string?.ifEmpty { null } ?: return null
            return SessionBlockSummary(
                key = key,
                added = json["added"].int ?: 0,
                removed = json["removed"].int ?: 0,
                kept = json["kept"].bool ?: false,
                calls = json["calls"].array?.mapNotNull { it.string },
            )
        }
    }
}

/** Both sides of one file the session changed (`…/file-changes/diff`): what the Review tab cuts its blocks from. */
data class SessionFileDiff(
    val file: SessionFileChange,
    /** The state it is compared with: before the session, at its mark, or at git HEAD. */
    val original: String,
    /** The file now; "" once it is deleted. */
    val modified: String,
) {
    companion object {
        fun from(json: JsonElement?): SessionFileDiff? {
            val file = SessionFileChange.from(json) ?: return null
            return SessionFileDiff(file, json["original"].string.orEmpty(), json["modified"].string.orEmpty())
        }
    }
}

/**
 * `session:file-changes` on `/ws/global` (`SessionFileChangesPushEvent`): the rows of the files the
 * agent has just changed in a session whose list someone asked for, and the files that are no
 * longer changes.
 */
data class SessionFileChangesPush(
    val projectName: String,
    val sessionId: String,
    val files: List<SessionFileChange>,
    /** Absolute paths. */
    val gone: List<String>,
) {
    companion object {
        fun from(json: JsonElement?): SessionFileChangesPush? {
            val project = json["projectName"].string?.ifEmpty { null } ?: return null
            val sessionId = json["sessionId"].string?.ifEmpty { null } ?: return null
            return SessionFileChangesPush(
                project,
                sessionId,
                json["files"].array?.mapNotNull(SessionFileChange::from).orEmpty(),
                json["gone"].array?.mapNotNull { it.string }.orEmpty(),
            )
        }
    }
}

/** What `…/file-changes/reviewed` answers. */
data class SessionReviewResult(val updated: List<String>, val stale: List<String>) {
    companion object {
        fun from(json: JsonElement?) = SessionReviewResult(
            json["updated"].array?.mapNotNull { it.string }.orEmpty(),
            json["stale"].array?.mapNotNull { it.string }.orEmpty(),
        )
    }
}

/** What an answer does to the blocks it names (`…/file-changes/answer`). */
enum class SessionAnswer(val wire: String) { KEEP("keep"), OPEN("open"), REVERT("revert") }

/** One file in an answer, at the version it was drawn at; every block when [keys] is null. */
data class SessionAnswerFile(val path: String, val version: String, val keys: List<String>? = null)

data class SessionFileAnswer(
    val path: String,
    /** Nothing was done: the file is no longer as it was drawn. */
    val stale: Boolean = false,
    /** Nothing was done for another reason. */
    val error: String? = null,
    /** The file as it stands now; null once it has no change left. */
    val file: SessionFileChange? = null,
)

/** What an answer, and its undo, come back with. */
data class SessionAnswerResult(
    val files: List<SessionFileAnswer>,
    /** Names the answer for `…/file-changes/undo`; null when it changed nothing. */
    val undoId: String? = null,
    /** Undo only: nothing was put back, because a file is no longer as the answer left it. */
    val stale: Boolean = false,
) {
    companion object {
        fun from(json: JsonElement?) = SessionAnswerResult(
            files = json["files"].array?.mapNotNull { f ->
                val path = f["path"].string ?: return@mapNotNull null
                SessionFileAnswer(path, f["stale"].bool ?: false, f["error"].string?.ifEmpty { null }, SessionFileChange.from(f["file"]))
            }.orEmpty(),
            undoId = json["undoId"].string?.ifEmpty { null },
            stale = json["stale"].bool ?: false,
        )
    }
}

/** What reverting a turn does to one file (`…/file-changes/revert-turn`). */
data class TurnRevertFile(
    val path: String,
    /** The version this was worked out against; the revert is refused once it moves. */
    val version: String,
    val action: Action,
    /** The turn's changes put back, and the lines they had added and taken out. */
    val changes: Int = 0,
    val added: Int = 0,
    val removed: Int = 0,
    /** The turn's changes left alone because their lines changed since. */
    val skipped: List<Skipped> = emptyList(),
    /** Why nothing could be worked out for this file. */
    val error: String? = null,
) {
    /**
     * `edit`: lines go back. `delete`: the turn created the file and it goes. `restore`: the turn
     * deleted it and it comes back. `none`: nothing to put back.
     */
    enum class Action(val wire: String) { EDIT("edit"), DELETE("delete"), RESTORE("restore"), NONE("none") }

    /** Where the line sits now (1-based), and the calls that changed it — "" for a change no call made. */
    data class Skipped(val line: Int, val by: List<String>)
}

data class TurnRevertResult(
    val files: List<TurnRevertFile>,
    /** Names the revert for `…/file-changes/undo`; null when nothing was written. */
    val undoId: String? = null,
    /** A file moved on since [files] was shown: nothing was written, and [files] is worked out again. */
    val stale: Boolean = false,
) {
    companion object {
        fun from(json: JsonElement?) = TurnRevertResult(
            files = json["files"].array?.mapNotNull { f ->
                val path = f["path"].string ?: return@mapNotNull null
                TurnRevertFile(
                    path = path,
                    version = f["version"].string.orEmpty(),
                    action = TurnRevertFile.Action.entries.firstOrNull { it.wire == f["action"].string } ?: TurnRevertFile.Action.NONE,
                    changes = f["changes"].int ?: 0,
                    added = f["added"].int ?: 0,
                    removed = f["removed"].int ?: 0,
                    skipped = f["skipped"].array?.mapNotNull { s ->
                        val line = s["line"].int ?: return@mapNotNull null
                        TurnRevertFile.Skipped(line, s["by"].array?.mapNotNull { it.string }.orEmpty())
                    }.orEmpty(),
                    error = f["error"].string?.ifEmpty { null },
                )
            }.orEmpty(),
            undoId = json["undoId"].string?.ifEmpty { null },
            stale = json["stale"].bool ?: false,
        )
    }
}
