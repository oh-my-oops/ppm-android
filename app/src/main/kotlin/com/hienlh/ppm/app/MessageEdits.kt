package com.hienlh.ppm.app

import android.os.SystemClock
import com.hienlh.ppm.protocol.ChatReply
import com.hienlh.ppm.protocol.ReplyReference
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.jsTrim
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.RejectedSend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/** The message the next send replaces (`editFork`): what the edit goes on from, and the message itself, ringed while it is armed. */
data class EditFork(val anchor: String?, val messageId: String)

/** An edited message as the box hands it on (`handleInputSend`): its text with its files' markers, what it goes on from, and what it answers. */
class EditMessage(val content: String, val anchor: String?, val replyTo: ReplyReference?, val permissionMode: String?)

/** An edited message waiting for its version's socket (`pendingSendRef`), and the session whose draft it was written as. */
class PendingEdit(val content: String, val replyTo: ReplyReference?, val permissionMode: String?, val draftId: String)

/**
 * Fork, Edit and the version switcher on a chat's messages (chat-tab.tsx): a fork opens in a tab
 * of its own, while an edit and a switch go on in the same tab, on another session of the same
 * conversation. Main thread only, as the model is.
 */
internal class MessageEdits(private val model: AppModel) {
    /**
     * Fork on a message (`handleFork`): a new session holding the chat up to [anchor], opened in a
     * tab of its own titled by the message, which waits in that tab's box to be sent as it is or
     * changed first.
     */
    fun fork(chat: ChatTab, item: ChatItem, anchor: String?) {
        val api = model.api ?: return
        val session = chat.session ?: return
        val project = chat.project
        val provider = chat.providerId
        val message = item.text
        model.scope.launch {
            val forked = try {
                api.forkSession(project, session.id, provider, anchor, edit = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                model.signInIfRefused(error)
                val text = error.message?.takeIf { it.isNotEmpty() } ?: "Unknown error"
                model.toast(ErrorToastMessage(
                    "Cannot fork from this message",
                    if (gone(text)) "The original message is no longer available in the session transcript. Try forking from a different message." else text,
                ))
                return@launch
            }
            // Titled by its body: the message as stored ends in its quote when it was a reply.
            val body = ChatReply.decode(message).content
            val listed = SessionInfo(id = forked.id, providerId = provider, title = body.take(50), createdAt = Instant.now())
            model.addSession(project, listed)
            model.openFork(project, listed, "Fork: ${body.take(30)}", message)
        }
    }

    /**
     * An edited message sent (`handleEditSend`): a new version of the chat holding it up to the
     * edit's anchor, the tab moved onto it at once, and the message sent there once its socket is
     * up. The chat is covered by "Creating edited version…" until the answer starts, a minute at
     * most. A step that fails puts the message back in the box and says why.
     */
    fun send(chat: ChatTab, message: EditMessage) {
        val api = model.api ?: return
        val source = chat.session ?: return
        if (message.content.jsTrim().isEmpty()) return
        val tabId = chat.tabId
        val project = chat.project
        val provider = chat.providerId
        chat.editJob?.cancel()
        chat.editForking = true
        chat.editJob = model.scope.launch {
            val coverEnds = SystemClock.uptimeMillis() + EDIT_COVER_MILLIS
            val cover = launch {
                delay(EDIT_COVER_MILLIS)
                chat.editForking = false
            }
            try {
                fun failed(text: String) {
                    chat.editForking = false
                    model.toast(ErrorToastMessage(
                        "Cannot edit from this message",
                        if (gone(text)) "The original message is no longer available in the session transcript." else text,
                    ))
                    // The box emptied as the message went: it goes back there, as `restoreUnsentMessage` puts it.
                    chat.restore(RejectedSend(message.content, message.replyTo, text))
                    model.toast(ErrorToastMessage("Message not sent", "$text Your text is back in the input."))
                }
                // Bounded as a new chat's session is, at the same cost: a fork the server finished
                // after the wait is a stray version in the tree.
                val forked = try {
                    withTimeoutOrNull(SESSION_CREATE_MILLIS) { api.forkSession(project, source.id, provider, message.anchor, edit = true) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    model.signInIfRefused(error)
                    failed(error.message?.takeIf { it.isNotEmpty() } ?: "Unknown error")
                    return@launch
                }
                if (forked == null) {
                    failed("The server did not answer in time.")
                    return@launch
                }
                val listed = SessionInfo(id = forked.id, providerId = provider, title = ChatReply.decode(message.content).content.take(50), createdAt = Instant.now())
                model.addSession(project, listed)
                // The edit goes on in the fork, so its reply is the fork's. The draft it was written
                // as is the source's, and goes once the message does.
                chat.pendingEdit = PendingEdit(message.content, message.replyTo?.copy(sessionId = forked.id), message.permissionMode, source.id)
                chat.swapSession(listed, moveDraft = true)
                model.patchTabMetadata(tabId, "sessionId" to JsonPrimitive(forked.id))
                if (com.hienlh.ppm.protocol.isAssistantProject(chat.project)) model.assistant.followBranch(source.id, forked.id)
                // Bounded: a socket that never says hello would keep the message with nothing on screen.
                val connected = withTimeoutOrNull(PENDING_SEND_MILLIS) {
                    chat.controller?.state?.first { it.sessionId == forked.id && it.connected }
                }
                if (connected == null) {
                    abandon(chat, "The chat did not connect in time.")
                    return@launch
                }
                chat.flushEdit()
                // Uncovered once the answer starts, whose own activity takes over.
                withTimeoutOrNull(coverEnds - SystemClock.uptimeMillis()) { chat.controller?.state?.first { it.isRunning } }
                chat.editForking = false
            } finally {
                cover.cancel()
            }
        }
    }

    /**
     * ‹ or › on a message's versions (`handleSwitchVersion`): the tab goes on in that version's
     * session, with an edit still waiting for its socket put back first, and the box emptied for
     * the draft that version has.
     */
    fun switchVersion(chat: ChatTab, sessionId: String) {
        val current = chat.session ?: return
        if (sessionId.isEmpty() || sessionId == current.id) return
        // A queued edit must not land in the version switched to.
        abandon(chat, "You switched to another version before it connected.")
        val next = model.sessions.firstOrNull { it.id == sessionId } ?: SessionInfo(id = sessionId, providerId = chat.providerId, title = current.title)
        chat.swapSession(next, moveDraft = false)
        model.patchTabMetadata(chat.tabId, "sessionId" to JsonPrimitive(sessionId))
        if (com.hienlh.ppm.protocol.isAssistantProject(chat.project)) model.assistant.followBranch(current.id, sessionId)
    }

    /**
     * An edited message that never reached its version's socket (`abandonPendingSend`): what would
     * still follow stops, and the message is back in the box, said out loud.
     */
    fun abandon(chat: ChatTab, reason: String) {
        chat.editJob?.cancel()
        chat.editForking = false
        val pending = chat.takePendingEdit() ?: return
        chat.abandonEdit(pending, reason)
        model.toast(ErrorToastMessage("Message not sent", "$reason Your text is back in the input."))
    }

    /** The server's word for a message its transcript no longer holds. */
    private fun gone(message: String) = message.contains("not found") || message.contains("Invalid upToMessageId")
}

/** How long "Creating edited version…" may cover the chat. */
private const val EDIT_COVER_MILLIS = 60_000L
