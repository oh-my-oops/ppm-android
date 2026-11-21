package com.hienlh.ppm.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.long
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.SessionChangesList
import com.hienlh.ppm.state.SessionReview
import com.hienlh.ppm.state.SessionReviewBackend
import com.hienlh.ppm.state.SessionTurns
import com.hienlh.ppm.state.SessionTurnsStore
import com.hienlh.ppm.state.workspace.NewTab
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What a session-review tab holds while it is open (`SessionReviewTab`): the session's review,
 * which keeps every answer and its Undo, and what the tab's metadata last asked for — the
 * transcript's paths, a file the changes bar picked, the chat's name for the header's link.
 * Started once the tab is first shown and stopped when it closes, as the web's tab mounts once
 * and stays mounted while hidden.
 */
class ReviewTab(val tabId: String, val project: String, val sessionId: String) {
    /** The chat's provider, which fetching the session's turns needs; none on a tab opened without it. */
    var providerId by mutableStateOf<String?>(null)
        private set

    var chatTitle by mutableStateOf<String?>(null)
        private set

    var review by mutableStateOf<SessionReview?>(null)
        private set

    val started: Boolean get() = review != null

    private var paths: List<String> = emptyList()
    private var select: Pair<String?, Long?>? = null
    private var job: Job? = null

    /** What the tab's metadata says now; a fresh `select` picks its file again. */
    fun follow(tab: WorkspaceTab) {
        providerId = tab.metaString("providerId")?.takeIf { it.isNotEmpty() }
        chatTitle = tab.metaString("chatTitle")?.takeIf { it.isNotEmpty() }
        paths = (tab.metadata["paths"] as? JsonArray)?.mapNotNull { it.string }.orEmpty()
        select = tab.metadata["select"]?.let { it["path"].string to it["at"].long }
        review?.let(::apply)
    }

    private fun apply(review: SessionReview) {
        review.setPaths(paths)
        review.select(select?.first, select?.second)
    }

    /**
     * Lists the session's changes and follows them. [onAnswered] tells the session's changes bars
     * an answer landed; [turns] is where the turn behind each block is read, fetched here when no
     * open chat publishes the session's (`useSessionTurns`).
     */
    fun start(api: ApiClient, scope: CoroutineScope, turns: SessionTurnsStore, onAnswered: () -> Unit) {
        if (review != null) return
        val job = SupervisorJob(scope.coroutineContext[Job]).also { this.job = it }
        val own = scope + job
        val review = SessionReview(SessionReviewBackend.api(api, project, sessionId), own, paths, onAnswered)
        this.review = review
        apply(review)
        review.start()
        own.launch {
            val calls = review.state
                .map { s -> s.reviews.flatMap { r -> r.file.blocks?.flatMap { it.calls.orEmpty() }.orEmpty() } }
                .distinctUntilChanged()
            val entry = turns.entries.map { it[sessionId] }.distinctUntilChanged()
            combine(snapshotFlow { providerId }, calls, entry) { provider, c, _ -> provider to c }.collectLatest { (provider, c) ->
                provider ?: return@collectLatest
                turns.follow(sessionId, c) {
                    val messages = api.history(project, sessionId, provider).messages
                    withContext(Dispatchers.Default) { SessionTurns.of(messages.mapNotNull(ChatItem::fromHistory)) }
                }
            }
        }
    }

    /** A list a changes bar on the same session got (`SESSION_CHANGES_EVENT`). */
    fun takeLive(list: SessionChangesList) {
        review?.takeLive(list.files, list.paths, list.askedAt)
    }

    fun stop() {
        review?.stop()
        job?.cancel()
        job = null
        review = null
    }
}

/**
 * The review's chat tab (`openChat`), carrying [text] as a context chip named [label] for its
 * composer when the tab is opened for it (`pendingContexts`, as `sendToChat` hands text over).
 */
internal fun reviewChatTab(review: ReviewTab, text: String, label: String) = NewTab(
    type = "chat",
    title = review.chatTitle ?: "Chat",
    projectId = review.project,
    metadata = buildJsonObject {
        put("projectName", review.project)
        put("sessionId", review.sessionId)
        review.providerId?.let { put("providerId", it) }
        put("pendingContexts", JsonArray(listOf(buildJsonObject { put("text", text); put("label", label) })))
    },
)
