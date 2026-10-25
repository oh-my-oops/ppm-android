package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.answerSessionChanges
import com.hienlh.ppm.client.revertSessionTurn
import com.hienlh.ppm.client.sessionFileChanges
import com.hienlh.ppm.client.setSessionFilesReviewed
import com.hienlh.ppm.client.undoSessionAnswer
import com.hienlh.ppm.protocol.SessionAnswer
import com.hienlh.ppm.protocol.SessionAnswerFile
import com.hienlh.ppm.protocol.SessionAnswerResult
import com.hienlh.ppm.protocol.SessionFileChange
import com.hienlh.ppm.protocol.TurnRevertFile
import com.hienlh.ppm.protocol.TurnRevertResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a session's changes ask of the server, so the bar and the tray run in a unit test without one. */
interface SessionChangesBackend {
    suspend fun list(paths: List<String>): List<SessionFileChange>
    suspend fun setReviewed(files: List<SessionFileChange>, reviewed: Boolean)
    suspend fun answer(answer: SessionAnswer, files: List<SessionAnswerFile>): SessionAnswerResult
    suspend fun revertTurn(calls: List<String>, apply: List<Pair<String, String>>?): TurnRevertResult
    suspend fun undo(undoId: String): SessionAnswerResult

    companion object {
        fun api(api: ApiClient, project: String, sessionId: String): SessionChangesBackend = object : SessionChangesBackend {
            override suspend fun list(paths: List<String>) = api.sessionFileChanges(project, sessionId, paths)
            override suspend fun setReviewed(files: List<SessionFileChange>, reviewed: Boolean) {
                api.setSessionFilesReviewed(project, sessionId, files, reviewed)
            }
            override suspend fun answer(answer: SessionAnswer, files: List<SessionAnswerFile>) = api.answerSessionChanges(project, sessionId, answer, files)
            override suspend fun revertTurn(calls: List<String>, apply: List<Pair<String, String>>?) = api.revertSessionTurn(project, sessionId, calls, apply)
            override suspend fun undo(undoId: String) = api.undoSessionAnswer(project, sessionId, undoId)
        }
    }
}

/**
 * Every file one chat session has changed, kept current while the agent works
 * (`use-session-file-changes.ts`). The server holds the answer; the transcript only says when to
 * ask again: whenever a file write or a shell command finishes, a new file is named, or a turn
 * starts or ends — folded into one request [delayMillis] after the last of them.
 *
 * Marking files reviewed changes the list at once and asks again once the marks are saved; no
 * list is asked for while they are on their way, as one answered before they landed would bring
 * the rows back for a moment. One per session; everything on [scope]'s single thread.
 */
class SessionChanges(
    val backend: SessionChangesBackend,
    private val scope: CoroutineScope,
    private val delayMillis: Long = REFETCH_DELAY_MILLIS,
) {
    private val _files = MutableStateFlow<List<SessionFileChange>>(emptyList())
    val files: StateFlow<List<SessionFileChange>> = _files.asStateFlow()

    /** The paths the transcript names, which every request adds to the server's own list. */
    var paths: List<String> = emptyList()
        private set

    private var watched: Triple<List<String>, Int, Boolean>? = null
    private var pending: Job? = null
    private var marking = 0

    /** What the transcript says now: the files its tools wrote, how many writing calls finished, and whether a turn runs. */
    fun update(paths: List<String>, settled: Int, streaming: Boolean) {
        val next = Triple(paths, settled, streaming)
        if (next == watched) return
        watched = next
        this.paths = paths
        ask()
    }

    /** Ask again, after an answer changed what the server holds. */
    fun refresh() = ask()

    private fun ask() {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMillis)
            // Saving the marks asks again.
            if (marking > 0) return@launch
            try {
                _files.value = backend.list(paths)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the last list: a failed refresh says nothing about what changed.
            }
        }
    }

    /** Mark [targets] reviewed or not: shown at once, saved, then asked for again. */
    fun setReviewed(targets: List<SessionFileChange>, reviewed: Boolean) {
        if (targets.isEmpty()) return
        val paths = targets.mapTo(HashSet()) { it.path }
        marking++
        _files.update { SessionChangeList.withReviewed(it, paths, reviewed) }
        scope.launch {
            try {
                backend.setReviewed(targets, reviewed)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The list asked for next says what was saved, and brings back a row that was not.
            } finally {
                marking--
                ask()
            }
        }
    }

    private val reviews = HashMap<String, Triple<List<TurnFileChange>, SessionTurn?, TurnReviewModel>>()

    /**
     * The review of the turn whose answers end with message [key], the same one while the turn
     * stays as it is: a tray scrolled away and back finds its reverts and its notice as it left them.
     */
    fun turnReview(key: String, changes: List<TurnFileChange>, turn: SessionTurn?): TurnReviewModel {
        reviews[key]?.let { (seen, seenTurn, model) -> if (seen == changes && seenTurn == turn) return model }
        return TurnReviewModel(changes, turn, this, scope).also { reviews[key] = Triple(changes, turn, it) }
    }

    companion object {
        const val REFETCH_DELAY_MILLIS = 400L
    }
}

/** What the tray says after an answer: what went wrong, or what to undo. */
data class TurnReviewNotice(val text: String, val undoId: String? = null)

/** Asking to revert the turn: the preview the server worked out, then the revert itself. */
data class RevertTurnFlow(
    val preview: TurnRevertResult? = null,
    val loading: Boolean = true,
    /** A file moved on between the preview and the revert: [preview] is the newer one. */
    val moved: Boolean = false,
) {
    /** The files a revert writes. */
    val reverted: List<TurnRevertFile> get() = preview?.files?.filter { it.action != TurnRevertFile.Action.NONE }.orEmpty()
}

data class TurnReviewLocal(
    /** Reverts made from this tray: edit key to the revert's undo id. */
    val reverted: Map<String, String> = emptyMap(),
    val busy: Boolean = false,
    val notice: TurnReviewNotice? = null,
    val flow: RevertTurnFlow? = null,
)

/**
 * The change tray's half of the session review for one turn (`use-turn-review.ts` and
 * `useRevertTurnFlow`): keep, revert or reopen an edit's blocks, keep the whole turn, and revert
 * the turn from the session's history, each undoable right after. The list is the session's own
 * ([changes]), so the tray and the bar never disagree; every answer asks for it again.
 */
class TurnReviewModel(
    private val turnChanges: List<TurnFileChange>,
    private val turn: SessionTurn?,
    private val changes: SessionChanges,
    private val scope: CoroutineScope,
) {
    private val _local = MutableStateFlow(TurnReviewLocal())
    val local: StateFlow<TurnReviewLocal> = _local.asStateFlow()

    /** Each edit's place in the review, against [files] (the session's list as it stands). */
    fun reviews(files: List<SessionFileChange> = changes.files.value): Map<String, EditReview> =
        TurnReview.editReviews(turnChanges, files, _local.value.reverted)

    fun dismissNotice() = _local.update { it.copy(notice = null) }

    private fun notice(text: String, undoId: String? = null) = _local.update { it.copy(notice = TurnReviewNotice(text, undoId)) }

    /** One request; one that may have written asks for the list again, whatever it said. */
    private suspend fun <T> request(writes: Boolean = true, job: suspend () -> T): T? {
        _local.update { it.copy(busy = true) }
        return try {
            job()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice(e.message ?: "The change could not be saved.")
            null
        } finally {
            _local.update { it.copy(busy = false) }
            if (writes) changes.refresh()
        }
    }

    /** Edits whose blocks an answer about [keys] in [path] reached. */
    private fun reached(reviews: Map<String, EditReview>, path: String, keys: List<String>): List<String> =
        reviews.filter { (_, r) -> r.file?.path == path && r.keys.any { it in keys } }.keys.toList()

    private fun answer(kind: SessionAnswer, targets: List<EditReview>, openOnly: Boolean) {
        val files = TurnReview.answerFiles(targets, openOnly)
        if (files.isEmpty()) return
        val reviews = reviews()
        scope.launch {
            request {
                val result = changes.backend.answer(kind, files)
                val moved = result.files.filter { it.stale || it.error != null }
                if (moved.isNotEmpty()) {
                    notice(if (moved.any { it.stale }) "A file changed since this was shown. Look again before answering." else moved[0].error!!)
                }
                val undoId = result.undoId
                if (kind == SessionAnswer.REVERT && undoId != null) {
                    val blocks = files.sumOf { it.keys.orEmpty().size }
                    _local.update { local ->
                        val next = local.reverted.toMutableMap()
                        for (f in files) for (key in reached(reviews, f.path, f.keys.orEmpty())) next[key] = undoId
                        local.copy(reverted = next)
                    }
                    notice(if (blocks == 1) "Reverted 1 block." else "Reverted $blocks blocks.", undoId)
                }
            }
        }
    }

    fun undo(undoId: String) {
        scope.launch {
            request {
                val result = changes.backend.undo(undoId)
                if (result.stale) {
                    notice("Nothing was put back: a file changed after the revert.")
                } else {
                    _local.update { local -> local.copy(reverted = local.reverted.filterValues { it != undoId }, notice = null) }
                }
            }
        }
    }

    fun keep(key: String) {
        val r = reviews()[key] ?: return
        if (r.state == EditReviewState.OPEN) answer(SessionAnswer.KEEP, listOf(r), openOnly = true)
    }

    fun revert(key: String) {
        val r = reviews()[key] ?: return
        if (r.state == EditReviewState.OPEN || r.state == EditReviewState.KEPT) answer(SessionAnswer.REVERT, listOf(r), openOnly = false)
    }

    /** Open a kept edit again, or undo the revert that took it away. */
    fun change(key: String) {
        val r = reviews()[key] ?: return
        val undoId = r.undoId
        if (r.state == EditReviewState.KEPT && r.keys.isNotEmpty()) answer(SessionAnswer.OPEN, listOf(r), openOnly = false)
        else if (r.state == EditReviewState.REVERTED && undoId != null) undo(undoId)
    }

    fun keepAll() = answer(SessionAnswer.KEEP, reviews().values.filter { it.state == EditReviewState.OPEN }, openOnly = true)

    /** "Revert turn…": asks the server what reverting the turn would do; closes again when it cannot say. */
    fun startRevert() {
        val turn = turn ?: return
        _local.update { it.copy(flow = RevertTurnFlow()) }
        scope.launch {
            val preview = request(writes = false) { changes.backend.revertTurn(turn.calls, null) }
            _local.update { it.copy(flow = preview?.let { p -> RevertTurnFlow(p, loading = false) }) }
        }
    }

    fun cancelRevert() = _local.update { it.copy(flow = null) }

    /** Reverts the turn as previewed; a file that moved on since shows the newer preview instead. */
    fun confirmRevert() {
        val turn = turn ?: return
        val preview = _local.value.flow?.preview ?: return
        _local.update { it.copy(flow = it.flow?.copy(loading = true)) }
        val reviews = reviews()
        scope.launch {
            val result = request { changes.backend.revertTurn(turn.calls, preview.files.map { it.path to it.version }) }
            if (result?.stale == true) {
                _local.update { it.copy(flow = RevertTurnFlow(TurnRevertResult(result.files), loading = false, moved = true)) }
                return@launch
            }
            _local.update { it.copy(flow = null) }
            result ?: return@launch
            val failed = result.files.filter { it.error != null }
            val undoId = result.undoId
            if (undoId != null) {
                val paths = result.files.filter { it.action != TurnRevertFile.Action.NONE }.mapTo(HashSet()) { it.path.replace('\\', '/') }
                _local.update { local ->
                    val next = local.reverted.toMutableMap()
                    for ((key, r) in reviews) {
                        val path = (r.file?.path ?: key.substringBefore('\u0000')).replace('\\', '/')
                        if (path in paths) next[key] = undoId
                    }
                    local.copy(reverted = next)
                }
            }
            when {
                failed.isNotEmpty() -> notice("${failed.size} file${if (failed.size == 1) "" else "s"} could not be written: ${failed[0].error}", undoId)
                undoId != null -> notice("Reverted this turn's changes.", undoId)
                else -> notice("Nothing to put back.")
            }
        }
    }
}
