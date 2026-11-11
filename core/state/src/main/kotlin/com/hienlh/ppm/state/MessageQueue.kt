package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ChatClientMessage
import com.hienlh.ppm.protocol.ChatFrame
import com.hienlh.ppm.protocol.QueuedMessage

/**
 * What became of a queued message this device asked back (`queue_taken_back`): its [content] for
 * the composer after Edit, or [error] when the AI had already started on it.
 */
data class QueueTakeBackAnswer(val id: String, val content: String?, val error: String?)

/**
 * The messages sent while a turn ran, not yet read by the AI (`queuedMessages` in `use-chat.ts`).
 * The list is the server's: it arrives whole as `queue_state` and in greetings, so every device
 * shows the same stack. Edit asks a message back for the composer; Remove asks it back and drops
 * what comes back. Not thread-safe: the chat's one thread drives it.
 */
class MessageQueue {
    var messages: List<QueuedMessage> = emptyList()
        private set

    /** The answer to the last Edit, or an Edit or Remove that came too late, until it is taken. */
    var answer: QueueTakeBackAnswer? = null
        private set

    /** Asked back to be removed: their text must not come back to the composer. */
    private val discarding = HashSet<String>()

    /** The server's list, from `queue_state` or a greeting that carries it. */
    fun replace(queue: List<QueuedMessage>) {
        messages = queue
    }

    /** The frame that asks [id] back; with [discard] it is removed, and nothing comes back to the composer. */
    fun takeBack(id: String, discard: Boolean): ChatClientMessage.QueueTakeBack {
        if (discard) discarding += id
        return ChatClientMessage.QueueTakeBack(id)
    }

    fun answered(frame: ChatFrame.QueueTakenBack) {
        val discard = discarding.remove(frame.id)
        answer = when {
            frame.content != null -> if (discard) null else QueueTakeBackAnswer(frame.id, frame.content, null)
            frame.error != null -> QueueTakeBackAnswer(frame.id, null, frame.error)
            else -> answer
        }
    }

    /** The composer took [answer], or said why it could not be taken. */
    fun answerTaken() {
        answer = null
    }
}
