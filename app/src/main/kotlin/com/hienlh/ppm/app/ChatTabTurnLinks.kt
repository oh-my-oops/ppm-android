package com.hienlh.ppm.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import com.hienlh.ppm.chat.ChatTurnTools
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.resolveTurnId
import com.hienlh.ppm.client.turnReference
import com.hienlh.ppm.client.turnWindow
import com.hienlh.ppm.protocol.TurnLinks
import com.hienlh.ppm.protocol.generated.TurnReference
import com.hienlh.ppm.state.ComposerTurnChips
import com.hienlh.ppm.state.HistoryWindowController
import kotlinx.coroutines.CoroutineScope

/**
 * A chat tab's turn links (`useComposerTurnRefs`, `useHistoryWindow`): the composer's turn chips,
 * which pasted turn links become, and what its transcript needs to copy a turn link and to open
 * a turn it has not loaded. Both exist once the tab is started; main thread only.
 */
class ChatTabTurnLinks(private val tab: ChatTab) {
    /** The composer's chips; null until the tab is started. */
    var chips by mutableStateOf<ComposerTurnChips?>(null)
        private set

    /** What the transcript gets for Copy turn link and the history window; null until started. */
    var tools by mutableStateOf<ChatTurnTools?>(null)
        private set

    private var isProject: (String) -> Boolean = { false }

    /** The references the ready chips send. */
    val refs: List<TurnReference> get() = chips?.refs.orEmpty()

    /** Why the send waits on the chips (one still loading, one gone); null when it need not. */
    val blockedReason: String? get() = chips?.blockedReason

    fun start(api: ApiClient, scope: CoroutineScope, isProject: (String) -> Boolean, toast: (title: String, detail: String?) -> Unit) {
        if (chips != null) return
        this.isProject = isProject
        chips = ComposerTurnChips(scope) { target -> api.turnReference(target) }.also {
            it.onOverflow = { title, detail -> toast(title, detail) }
            it.onChanged = { tab.edited() }
        }
        val history = HistoryWindowController(scope) { query ->
            val session = tab.session ?: error("This chat has no session yet")
            api.turnWindow(tab.project, session.id, session.providerId, query)
        }
        tools = ChatTurnTools(
            projectName = tab.project,
            origin = api.baseUrl.toString().trimEnd('/'),
            resolveTurnId = { sessionId, providerId, uuid -> api.resolveTurnId(tab.project, sessionId, providerId, uuid) },
            history = history,
            toast = { toast(it, null) },
        )
    }

    /**
     * A paste into the box (one change of more than a character, as the IME or the text menu makes
     * it): every turn link in it becomes a chip and leaves the text, the rest goes in exactly as
     * it was (`pasteWithoutTurnLinks`). Links there is no room for stay in the text.
     */
    @OptIn(ExperimentalFoundationApi::class)
    fun takePastedLinks(buffer: TextFieldBuffer) {
        val chips = chips ?: return
        if (buffer.changes.changeCount != 1) return
        val range = buffer.changes.getRange(0)
        if (range.length < 2) return
        val pasted = buffer.asCharSequence().subSequence(range.min, range.max).toString()
        if (!pasted.contains("/project/")) return
        val insert = TurnLinks.pasteWithout(pasted, isProject, chips::addLinks) ?: return
        buffer.replace(range.min, range.max, insert)
        buffer.selection = TextRange(range.min + insert.length)
    }

    /** The box emptied: a send, a cleared draft. */
    fun clear() = chips?.replace(null)

    /** A stored message's chips in place of what the box held (an edit, a recalled message). */
    fun replace(refs: List<TurnReference>?) = chips?.replace(refs)

    /** A refused message's chips back, after the ones pasted since. */
    fun merge(refs: List<TurnReference>?) {
        chips?.merge(refs)
    }

    /** The tab goes on in another session: the window was the old one's. */
    fun sessionChanged() {
        tools?.history?.exit()
    }
}
