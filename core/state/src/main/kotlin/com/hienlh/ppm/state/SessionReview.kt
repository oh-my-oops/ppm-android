package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.answerSessionChanges
import com.hienlh.ppm.client.sessionFileChanges
import com.hienlh.ppm.client.sessionFileDiff
import com.hienlh.ppm.client.undoSessionAnswer
import com.hienlh.ppm.protocol.SessionAnswer
import com.hienlh.ppm.protocol.SessionAnswerFile
import com.hienlh.ppm.protocol.SessionAnswerResult
import com.hienlh.ppm.protocol.SessionFileAnswer
import com.hienlh.ppm.protocol.SessionFileChange
import com.hienlh.ppm.protocol.SessionFileDiff
import com.hienlh.ppm.state.SessionReviewModel.WHOLE_FILE
import com.hienlh.ppm.state.SessionReviewModel.answerKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the Review tab asks of the server, so it runs in a unit test without one. */
interface SessionReviewBackend {
    suspend fun list(paths: List<String>): List<SessionFileChange>
    suspend fun diff(path: String): SessionFileDiff
    suspend fun answer(answer: SessionAnswer, files: List<SessionAnswerFile>): SessionAnswerResult
    suspend fun undo(undoId: String): SessionAnswerResult

    companion object {
        fun api(api: ApiClient, project: String, sessionId: String): SessionReviewBackend = object : SessionReviewBackend {
            override suspend fun list(paths: List<String>) = api.sessionFileChanges(project, sessionId, paths)
            override suspend fun diff(path: String) = api.sessionFileDiff(project, sessionId, path)
            override suspend fun answer(answer: SessionAnswer, files: List<SessionAnswerFile>) = api.answerSessionChanges(project, sessionId, answer, files)
            override suspend fun undo(undoId: String) = api.undoSessionAnswer(project, sessionId, undoId)
        }
    }
}

/** What the tab says after an answer: "Kept block 2 of", then the file's name in bold, then the rest. */
data class ReviewToast(
    val id: Int,
    val lead: String,
    val name: String? = null,
    val tail: String? = null,
    val undoId: String? = null,
    /** Where focus goes back to when the answer is undone. */
    val focus: ReviewFocus? = null,
)

/** The file in focus as the pane draws it. */
sealed interface ReviewPane {
    data object None : ReviewPane
    data object Loading : ReviewPane
    data class Error(val message: String) : ReviewPane

    /** No blocks to step through: binary, too large, or too slow to cut. [undoId] names a revert that took it whole. */
    data class Whole(val review: FileReview, val undoId: String? = null) : ReviewPane

    class Blocks(
        val review: FileReview,
        val model: PaneModel,
        /** The version the blocks were drawn at: what an answer about them names. */
        val version: String,
        /** What the blocks were cut against, and its name. */
        val original: String,
        val base: String,
    ) : ReviewPane
}

data class SessionReviewState(
    /** Null until the first list lands. */
    val files: List<SessionFileChange>? = null,
    val reviews: List<FileReview> = emptyList(),
    val progress: ReviewProgress = ReviewProgress(0, 0, 0, 0),
    val focus: ReviewFocus? = null,
    val focused: FileReview? = null,
    val pane: ReviewPane = ReviewPane.None,
    val loading: Boolean = false,
    val error: String? = null,
    val toast: ReviewToast? = null,
) {
    private val focusedBlock: ReviewBlockState?
        get() = focus?.let { f -> focused?.blocks?.firstOrNull { it.key == f.key } }

    private val focusedItem: PaneItem.Block?
        get() = (pane as? ReviewPane.Blocks)?.let { p -> focus?.let { f -> p.model.items.firstOrNull { it is PaneItem.Block && it.key == f.key } as PaneItem.Block? } }

    /** How the block in focus was answered; null with nothing in focus (`reviewView`). */
    val focusState: BlockState? get() = focusedBlock?.state

    /** Its place among its file's blocks, 0-based, and how many the file has. */
    val place: Pair<Int, Int>?
        get() {
            focusedItem?.let { item -> return item.index to (pane as ReviewPane.Blocks).model.total }
            val file = focused ?: return null
            val block = focusedBlock ?: return null
            return file.blocks.indexOf(block) to file.blocks.size
        }

    /** The next file with a block left: after the one in focus in the rail's order, else any other. */
    val nextFile: FileReview?
        get() {
            val rail = SessionReviewModel.railOrder(reviews)
            val at = focused?.let { f -> rail.indexOfFirst { it.path == f.path } } ?: -1
            return rail.filterIndexed { n, r -> n > at && r.open > 0 }.firstOrNull()
                ?: rail.firstOrNull { it.open > 0 && it.path != focused?.path }
        }

    /** The revert that put back the block in focus, when Change can undo it. */
    val focusedUndoId: String?
        get() = focusedItem?.undoId ?: (pane as? ReviewPane.Whole)?.undoId

    /** Whether Change can open the block in focus again: a kept one, or a reverted one whose revert can be undone. */
    val canChange: Boolean
        get() = focusState == BlockState.KEPT || (focusState == BlockState.REVERTED && focusedUndoId != null)
}

/**
 * The Review tab's state (`use-session-review.ts`): every file the session changed with its
 * blocks, the block in focus and its file's diff, and every answer — keep, revert, open again,
 * undo — shown at once and sent to the server one at a time.
 *
 * Four things are easy to get wrong:
 *
 * - The tab must work without its chat, so the list is fetched here too, with the transcript's
 *   paths the bar wrote into the tab. While the chat is open its lists arrive through [takeLive].
 *   Any list asked for before the last answer landed is dropped: it would bring answered blocks back.
 * - Every answer names the version of the file it was drawn at, and the server refuses one that
 *   moved on. A revert moves it, so a second answer on the same file, drawn before the first
 *   landed, is sent with the version the first one left (`versionAfter`) — never with a version
 *   the agent made, which nobody has read. Answers go out one at a time for the same reason.
 * - A reverted block is no longer a change and drops out of the server's list; the tab keeps it
 *   (`reverted`, and `gone` for a file with nothing left) to show it in place, and lets it go once
 *   the agent writes over its lines ([PaneModel.stale]).
 * - Answers are drawn against the diff on screen, not the list: the list may already be newer
 *   while the diff is still on its way.
 *
 * Like the web's render, every change is settled at once at the end of the event that made it
 * ([update]): the focus that follows a list, the diff to fetch, the pane, the reverted blocks to
 * let go. Everything runs on [scope]'s single thread; [compute] cuts a new diff off it.
 */
class SessionReview(
    private val backend: SessionReviewBackend,
    private val scope: CoroutineScope,
    paths: List<String>,
    /** An answer landed: the chat's changes bar asks for its list again (`announceReviewMarks`). */
    private val onAnswered: () -> Unit = {},
    private val now: () -> Long = ::monotonicMillis,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val toastMillis: Long = TOAST_MILLIS,
) {
    private val _state = MutableStateFlow(SessionReviewState())
    val state: StateFlow<SessionReviewState> = _state.asStateFlow()

    // What the web keeps in useState. Setting any of the first five moves the reviews.
    private var files: List<SessionFileChange>? = null
        set(value) { field = value; reviewsMoved = true }
    private var order: List<String> = emptyList()
        set(value) { field = value; reviewsMoved = true }
    private var reverted: Map<String, List<RevertedBlock>> = emptyMap()
        set(value) { field = value; reviewsMoved = true }
    private var gone: Map<String, RevertedFile> = emptyMap()
        set(value) { field = value; reviewsMoved = true }
    private var pending: Map<String, BlockState> = emptyMap()
        set(value) { field = value; reviewsMoved = true }
    private var focus: ReviewFocus? = null
    private var diff: SessionFileDiff? = null
    private var diffError: Pair<String, String>? = null
    private var loading = false
    private var error: String? = null
    private var toast: ReviewToast? = null

    private var reviewsMoved = true
    private var reviews: List<FileReview> = emptyList()
    private var pane: ReviewPane = ReviewPane.None

    // What the web keeps in refs.
    private var answering = 0
    private var lastAnswerAt = -1L
    private val queue = Mutex()
    private val versionAfter = HashMap<String, HashMap<String, String>>()
    private var metadataPaths = paths
    private var livePaths: List<String> = emptyList()
    private var select: Pair<String, Long>? = null
    private var selectMoved = false
    private var selectAt: Long? = null
    private var listJob: Job? = null
    private var diffJob: Job? = null
    private var diffKey = ""
    private var staleKeys = ""
    private var toastJob: Job? = null
    private var toastId = 0

    private fun paths(): List<String> = (metadataPaths + livePaths).distinct()

    fun start() = reload()

    /** The transcript's paths, which cover a session older than the server's own copies; from the tab. */
    fun setPaths(paths: List<String>) {
        metadataPaths = paths
    }

    /** The changes bar asked for a file (`select`): a newer [at] selects it again. */
    fun select(path: String?, at: Long?) {
        val next = if (path != null && at != null) path to at else null
        if (next == select) return
        select = next
        selectMoved = true
        update()
    }

    /** Take a list asked for at [askedAt], unless an answer landed or is on its way since. */
    private fun takeList(next: List<SessionFileChange>, askedAt: Long) {
        if (answering > 0 || askedAt < lastAnswerAt) return
        files = next
        val known = order.toHashSet()
        val added = next.map { it.path }.filter { it !in known }
        if (added.isNotEmpty()) order = order + added
    }

    /** A list the chat's changes bar got (`SESSION_CHANGES_EVENT`). */
    fun takeLive(next: List<SessionFileChange>, paths: List<String>, askedAt: Long) {
        livePaths = paths
        takeList(next, askedAt)
        update()
    }

    fun reload() {
        listJob?.cancel()
        val askedAt = now()
        loading = true
        error = null
        update()
        listJob = scope.launch {
            try {
                takeList(backend.list(paths()), askedAt)
                loading = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Could not list this chat's changes"
                loading = false
            }
            update()
        }
    }

    /** Settles one event's changes, as one render and its effects do on the web. */
    private fun update() {
        repeat(4) {
            if (reviewsMoved) {
                reviews = SessionReviewModel.fileReviews(order, files.orEmpty(), reverted, gone, pending)
            }
            // The changes bar asks for a file by writing a fresh select; otherwise focus stays on
            // its block while it is there, and a block the agent rewrote hands focus to its file.
            if ((reviewsMoved || selectMoved) && files != null) {
                reviewsMoved = false
                selectMoved = false
                followFocus()
            }
            val focused = focused()
            // The diff of the file in focus, fetched again whenever what it would show moves.
            val listed = focused?.takeIf { !it.gone && it.file.blocks != null }?.file
            val key = listed?.let(SessionChangeList::changeKey).orEmpty()
            if (key != diffKey) {
                diffKey = key
                diffJob?.cancel()
                diffJob = listed?.let { fetchDiff(it.path, key) }
            }
            pane = pane(focused, key)
            // Let go of reverted blocks the agent wrote over, or cut against a base that has moved.
            val stale = (pane as? ReviewPane.Blocks)?.model?.stale.orEmpty().joinToString("\n")
            if (stale != staleKeys) {
                staleKeys = stale
                val p = pane
                if (stale.isNotEmpty() && p is ReviewPane.Blocks) {
                    val drop = stale.split("\n").toSet()
                    reverted = reverted + (p.review.path to reverted[p.review.path].orEmpty().filter { it.key !in drop })
                    return@repeat
                }
            }
            _state.value = SessionReviewState(
                files = files,
                reviews = reviews,
                progress = SessionReviewModel.reviewProgress(reviews),
                focus = focus,
                focused = focused,
                pane = pane,
                loading = loading,
                error = error,
                toast = toast,
            )
            return
        }
    }

    private fun focused(): FileReview? = focus?.let { f -> reviews.firstOrNull { it.path == f.path } }

    private fun followFocus() {
        val wanted = select?.takeIf { it.second != selectAt }
        if (wanted != null) {
            selectAt = wanted.second
            val review = reviews.firstOrNull { it.path == wanted.first }
            if (review != null) {
                focus = SessionReviewModel.firstInFile(review)
                return
            }
        }
        val current = focus
        focus = when {
            current == null -> if (answering > 0) null else SessionReviewModel.nextOpen(reviews, null)
            else -> {
                val review = reviews.firstOrNull { it.path == current.path }
                when {
                    review == null -> SessionReviewModel.nextOpen(reviews, null)
                    review.blocks.any { it.key == current.key } -> current
                    else -> SessionReviewModel.firstInFile(review)
                }
            }
        }
    }

    private fun fetchDiff(path: String, key: String): Job = scope.launch {
        try {
            val next = backend.diff(path)
            // Cut off this thread, so drawing it only finds the cut made.
            withContext(compute) { SessionReviewModel.prepare(next.original, next.modified) }
            diff = next
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diffError = key to (e.message ?: "Could not load this file's diff")
        }
        update()
    }

    private fun pane(focused: FileReview?, key: String): ReviewPane {
        focused ?: return ReviewPane.None
        val ghosts = reverted[focused.path].orEmpty()
        val filePending = HashMap<String, BlockState>()
        for (b in focused.blocks) pending[answerKey(focused.path, b.key)]?.let { filePending[b.key] = it }
        var model: PaneModel? = null
        var version = ""
        var original = ""
        var base = ""
        if (focused.gone) {
            val record = gone[focused.path]
            val text = record?.text
            // Every change reverted: the file is back to what it was compared with.
            if (record != null && text != null) {
                version = ""
                original = text
                base = record.file.base.orEmpty()
                model = SessionReviewModel.paneModel(text, text, version, base, emptySet(), ghosts, filePending)
            }
        } else if (focused.file.blocks != null) {
            val d = diff
            // A newer version keeps the one on screen up until it lands.
            if (d?.file?.path != focused.path) {
                val failed = diffError
                return if (failed != null && failed.first == key) ReviewPane.Error(failed.second) else ReviewPane.Loading
            }
            // Kept answers from the list, which every answer updates; the diff is fetched again only
            // when the file's text moves, and an answer that keeps or reopens a block does not move it.
            val blocks = focused.file.blocks.orEmpty()
            val kept = blocks.filter { it.kept }.mapTo(HashSet()) { it.key }
            val calls = blocks.mapNotNull { b -> b.calls?.takeIf { it.isNotEmpty() }?.let { b.key to it } }.toMap()
            version = d.file.version
            original = d.original
            base = d.file.base.orEmpty()
            model = SessionReviewModel.paneModel(d.original, d.modified, version, base, kept, ghosts, filePending, calls)
        }
        if (model == null) return ReviewPane.Whole(focused, if (focused.gone) gone[focused.path]?.undoId else null)
        return ReviewPane.Blocks(focused, model, version, original, base)
    }

    private fun showToast(lead: String, name: String? = null, tail: String? = null, undoId: String? = null, focus: ReviewFocus? = null) {
        toastJob?.cancel()
        val id = ++toastId
        toast = ReviewToast(id, lead, name, tail, undoId, focus)
        toastJob = scope.launch {
            delay(toastMillis)
            if (toast?.id == id) {
                toast = null
                update()
            }
        }
    }

    fun dismissToast() {
        toastJob?.cancel()
        toast = null
        update()
    }

    /** The version to send for a file drawn at [drawn]: past every version our own answers made. */
    private fun sendVersion(path: String, drawn: String): String {
        val chain = versionAfter[path]
        var version = drawn
        var hops = 0
        while (chain != null && hops < 64) {
            version = chain[version] ?: break
            hops++
        }
        return version
    }

    private fun noteVersion(path: String, from: String, to: String) {
        if (from == to) return
        versionAfter.getOrPut(path) { HashMap() }[from] = to
    }

    /** Put the server's word on each file into the list. */
    private fun takeAnswers(answers: List<SessionFileAnswer>) {
        val byPath = answers.associate { it.path to it.file }
        files = files?.let { current ->
            val next = current.flatMap { f -> if (f.path in byPath) listOfNotNull(byPath[f.path]) else listOf(f) }.toMutableList()
            for (a in answers) if (a.file != null && current.none { it.path == a.path }) next += a.file!!
            next
        }
        val added = answers.filter { it.file != null && it.path !in order }.map { it.path }
        if (added.isNotEmpty()) order = order + added
        // A file brought back by Undo is listed again.
        if (answers.any { it.file != null && it.path in gone }) gone = gone - answers.filter { it.file != null }.map { it.path }.toSet()
    }

    private fun baseName(path: String) = path.substring(maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1)

    /** What to say when a file moved on before its answer reached it. Returns whether it did. */
    private fun sayRefused(answers: List<SessionFileAnswer>): Boolean {
        val refused = answers.firstOrNull { it.stale || it.error != null } ?: return false
        val error = refused.error
        if (error != null) showToast(error)
        else showToast("", baseName(refused.path), " changed before the answer reached it, so it is shown again as it is now.")
        return true
    }

    /** Runs [job] after every answer before it, with lists held off while it is on its way. */
    private fun <T> enqueue(job: suspend () -> T): Deferred<T> {
        answering++
        return scope.async {
            queue.withLock {
                try {
                    job()
                } finally {
                    answering--
                    lastAnswerAt = now()
                    onAnswered()
                    update()
                }
            }
        }
    }

    private class Target(val review: FileReview, val drawn: String, val keys: List<String>? = null, val text: String? = null)

    /**
     * Sends one answer about [targets]. [states] and [ghosts] are what it shows at once: block
     * states, and blocks to keep showing as reverted. Null when it did not reach the server.
     */
    private fun send(
        answer: SessionAnswer,
        targets: List<Target>,
        states: Map<String, BlockState> = emptyMap(),
        ghosts: Map<String, List<RevertedBlock>> = emptyMap(),
    ): Deferred<SessionAnswerResult?> {
        if (targets.isEmpty()) return CompletableDeferred(null)
        if (states.isNotEmpty()) pending = pending + states
        if (ghosts.isNotEmpty()) {
            reverted = reverted.toMutableMap().apply {
                for ((path, list) in ghosts) put(path, get(path).orEmpty().filter { g -> list.none { it.key == g.key } } + list)
            }
        }
        fun updateGhosts(fn: (RevertedBlock, String) -> RevertedBlock?) {
            reverted = reverted.toMutableMap().apply {
                for ((path, list) in ghosts) put(path, get(path).orEmpty().mapNotNull { g -> if (list.any { it === g }) fn(g, path) else g })
            }
        }
        return enqueue {
            val body = targets.map { SessionAnswerFile(it.review.path, sendVersion(it.review.path, it.drawn), it.keys) }
            try {
                val result = backend.answer(answer, body)
                val refused = result.files.filter { it.stale || it.error != null }.mapTo(HashSet()) { it.path }
                // Undo needs the revert's id; the reverted blocks themselves already show.
                updateGhosts { g, path -> if (path in refused) null else g.copy(undoId = result.undoId) }
                result.files.forEachIndexed { i, f ->
                    if (f.path !in refused) noteVersion(f.path, body.getOrNull(i)?.version.orEmpty(), f.file?.version.orEmpty())
                }
                if (answer == SessionAnswer.REVERT) {
                    val away = targets.filter { t -> result.files.any { it.path == t.review.path && it.path !in refused && it.file == null } }
                    if (away.isNotEmpty()) gone = gone + away.associate { it.review.path to RevertedFile(it.review.file, it.text, result.undoId) }
                }
                takeAnswers(result.files)
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateGhosts { _, _ -> null }
                showToast(e.message ?: "Could not save the answer")
                null
            } finally {
                if (states.isNotEmpty()) pending = pending - states.keys
            }
        }
    }

    /** The version a file was drawn at: the diff on screen for the file in focus, the list's otherwise. */
    private fun drawnVersion(review: FileReview): String {
        val p = pane
        return if (p is ReviewPane.Blocks && p.review.path == review.path) p.version else review.file.version
    }

    private fun blockItem(key: String): PaneItem.Block? =
        (pane as? ReviewPane.Blocks)?.model?.items?.firstOrNull { it is PaneItem.Block && it.key == key } as PaneItem.Block?

    private fun ghostOf(item: PaneItem.Block, pane: ReviewPane.Blocks) =
        RevertedBlock(item.key, item.rows, item.oldFrom, item.oldTo, item.added, item.removed, pane.base, pane.version, calls = item.calls)

    /** Reviews as they will be once [states] land: where focus goes next. */
    private fun reviewsWith(states: Map<String, BlockState>) =
        SessionReviewModel.fileReviews(order, files.orEmpty(), reverted, gone, pending + states)

    /** Runs [after] with an answer's result, unless it failed or a file moved on before it. */
    private fun whenAnswered(answer: Deferred<SessionAnswerResult?>, after: (SessionAnswerResult) -> Unit) {
        scope.launch {
            val result = answer.await() ?: return@launch
            if (sayRefused(result.files)) {
                update()
                return@launch
            }
            after(result)
            update()
        }
    }

    fun keepBlock(key: String) = answerBlock(key, keep = true)

    fun revertBlock(key: String) = answerBlock(key, keep = false)

    /** Keep or revert one block of the file in focus. */
    private fun answerBlock(key: String, keep: Boolean) {
        val review = focused()?.takeUnless { it.gone } ?: return
        val item = blockItem(key)
        val states = mapOf(answerKey(review.path, key) to if (keep) BlockState.KEPT else BlockState.REVERTED)
        val after = reviewsWith(states)
        focus = SessionReviewModel.nextOpen(after, ReviewFocus(review.path, key))
        val p = pane as? ReviewPane.Blocks
        val ghosts = if (!keep && item != null && p != null) mapOf(review.path to listOf(ghostOf(item, p))) else emptyMap()
        val answer = send(
            if (keep) SessionAnswer.KEEP else SessionAnswer.REVERT,
            listOf(Target(review, drawnVersion(review), if (key == WHOLE_FILE) null else listOf(key), p?.original)),
            states, ghosts,
        )
        update()
        whenAnswered(answer) { result ->
            val done = after.firstOrNull { it.path == review.path }?.open == 0
            showToast(
                "${if (keep) "Kept" else "Reverted"} ${if (item != null) "block ${item.index + 1} of " else ""}",
                baseName(review.path), if (done) " — file done" else "", result.undoId, ReviewFocus(review.path, key),
            )
        }
    }

    /** Open a kept block again (Change). */
    fun reopenBlock(key: String) {
        val review = focused()?.takeUnless { it.gone } ?: return
        val item = blockItem(key)
        focus = ReviewFocus(review.path, key)
        val answer = send(
            SessionAnswer.OPEN,
            listOf(Target(review, drawnVersion(review), if (key == WHOLE_FILE) null else listOf(key))),
            mapOf(answerKey(review.path, key) to BlockState.OPEN),
        )
        update()
        whenAnswered(answer) { result ->
            showToast(item?.let { "Block ${it.index + 1} of " }.orEmpty(), baseName(review.path), " is open again", result.undoId, ReviewFocus(review.path, key))
        }
    }

    /** Undo an answer: the toast's, or a reverted block's (Change). */
    fun undo(undoId: String, focusBack: ReviewFocus?) {
        toastJob?.cancel()
        toast = null
        enqueue {
            try {
                val result = backend.undo(undoId)
                if (result.stale) {
                    showToast("Could not undo: the file changed since.")
                    return@enqueue
                }
                for (f in result.files) {
                    val before = files?.firstOrNull { it.path == f.path }?.version
                    val after = f.file?.version
                    if (before != null && after != null) noteVersion(f.path, before, after)
                }
                reverted = reverted.mapValues { (_, list) -> list.filter { it.undoId != undoId } }
                takeAnswers(result.files)
                if (focusBack != null) focus = focusBack
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast(e.message ?: "Could not undo")
            }
        }
        update()
    }

    /** Keep every open block of the file in focus. */
    fun keepFile() {
        val review = focused()?.takeUnless { it.gone || it.open == 0 } ?: return
        val open = review.blocks.filter { it.state == BlockState.OPEN }.map { it.key }
        val states = open.associate { answerKey(review.path, it) to BlockState.KEPT }
        focus = SessionReviewModel.nextOpen(reviewsWith(states), ReviewFocus(review.path, review.blocks.last().key))
        val answer = send(SessionAnswer.KEEP, listOf(Target(review, drawnVersion(review), if (WHOLE_FILE in open) null else open)), states)
        update()
        whenAnswered(answer) { result -> showToast("Kept ", baseName(review.path), null, result.undoId, SessionReviewModel.firstInFile(review)) }
    }

    /** Revert the whole file in focus, kept blocks included. */
    fun revertFile() {
        val review = focused()?.takeUnless { it.gone } ?: return
        val states = review.blocks.filter { it.state != BlockState.REVERTED }.associate { answerKey(review.path, it.key) to BlockState.REVERTED }
        focus = SessionReviewModel.nextOpen(reviewsWith(states), ReviewFocus(review.path, review.blocks.last().key))
        val p = (pane as? ReviewPane.Blocks)?.takeIf { it.review.path == review.path }
        val ghosts = if (p != null) {
            mapOf(review.path to p.model.items.filterIsInstance<PaneItem.Block>().filter { it.state != BlockState.REVERTED }.map { ghostOf(it, p) })
        } else {
            emptyMap()
        }
        val answer = send(SessionAnswer.REVERT, listOf(Target(review, drawnVersion(review), text = (pane as? ReviewPane.Blocks)?.original)), states, ghosts)
        update()
        whenAnswered(answer) { result -> showToast("Reverted ", baseName(review.path), null, result.undoId, SessionReviewModel.firstInFile(review)) }
    }

    /** Keep every open block of every file. */
    fun keepAll() {
        val targets = reviews.filter { it.open > 0 && !it.gone }
        if (targets.isEmpty()) return
        val states = targets.flatMap { r -> r.blocks.filter { it.state == BlockState.OPEN }.map { answerKey(r.path, it.key) to BlockState.KEPT } }.toMap()
        val back = focus
        focus = null
        val answer = send(
            SessionAnswer.KEEP,
            targets.map { review ->
                val open = review.blocks.filter { it.state == BlockState.OPEN }.map { it.key }
                Target(review, drawnVersion(review), if (WHOLE_FILE in open) null else open)
            },
            states,
        )
        update()
        whenAnswered(answer) { result -> showToast("Kept ${states.size} block${if (states.size == 1) "" else "s"}", undoId = result.undoId, focus = back) }
    }

    fun setFocus(next: ReviewFocus?) {
        focus = next
        update()
    }

    fun step(by: Int) {
        focus = SessionReviewModel.stepBlock(reviews, focus, by)
        update()
    }

    fun focusFile(path: String) {
        val review = reviews.firstOrNull { it.path == path } ?: return
        focus = SessionReviewModel.firstInFile(review)
        update()
    }

    /** Answer the block in focus with Keep; a file with no blocks to show is kept whole. */
    fun keepFocused() {
        val f = focus ?: return
        if (_state.value.focusState != BlockState.OPEN) return
        if (pane is ReviewPane.Whole) keepFile() else keepBlock(f.key)
    }

    /** Answer the block in focus with Revert; false when a file answered whole needs its revert confirmed first. */
    fun revertFocused(): Boolean {
        val f = focus ?: return true
        if (_state.value.focusState != BlockState.OPEN) return true
        if (pane is ReviewPane.Whole) return false
        revertBlock(f.key)
        return true
    }

    /** Open the decided block in focus again: a kept one is reopened, a reverted one's revert undone. */
    fun changeFocused() {
        val f = focus ?: return
        val s = _state.value
        when (s.focusState) {
            BlockState.KEPT -> reopenBlock(f.key)
            BlockState.REVERTED -> s.focusedUndoId?.let { undo(it, f) }
            else -> Unit
        }
    }

    fun stop() {
        listJob?.cancel()
        diffJob?.cancel()
        toastJob?.cancel()
    }

    companion object {
        /** How long an answer's Undo stays on screen. */
        const val TOAST_MILLIS = 5000L
    }
}
