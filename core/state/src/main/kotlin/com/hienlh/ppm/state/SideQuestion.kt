package com.hienlh.ppm.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * `/btw <question>`: Claude Code's side question (`side-question-panel.tsx`). Claude answers from
 * what the chat already knows, with no tools, even while a turn runs, and neither the question nor
 * the answer joins the conversation. The answer shows above the composer until closed.
 */
sealed interface SideQuestionState {
    val question: String

    data class Asking(override val question: String) : SideQuestionState
    /** [answer] null: Claude gave none. */
    data class Answered(override val question: String, val answer: String?) : SideQuestionState
    data class Failed(override val question: String, val error: String) : SideQuestionState
}

/** What sending a message that may be `/btw` comes to (the start of `chat-tab.tsx`'s `onSend`). */
sealed interface SideQuestionSend {
    /** Not a `/btw`: the message goes on as any other. */
    data object NotOne : SideQuestionSend

    /** Said as a toast; the message stays in the box. */
    data class Refused(val message: String) : SideQuestionSend

    /** Asked; the box empties. */
    data class Ask(val question: String) : SideQuestionSend

    companion object {
        private val BTW = Regex("^/btw(?:\\s+([\\s\\S]*))?$")

        /** `/btw <question>` → the question ("" when none follows); null for anything else (`sideQuestionOf`). */
        fun questionOf(content: String): String? {
            val m = BTW.matchEntire(content.trim()) ?: return null
            return m.groupValues[1].trim()
        }

        /**
         * [extras]: the message carries a reply, turn or task chips, or attachments, none of which a
         * side question can take.
         */
        fun of(content: String, sessionId: String?, providerId: String?, extras: Boolean): SideQuestionSend {
            val question = questionOf(content) ?: return NotOne
            return when {
                question.isEmpty() -> Refused("Ask something after /btw")
                sessionId.isNullOrEmpty() || providerId != "claude" -> Refused("/btw needs a Claude chat that has already started")
                extras -> Refused("A side question takes only text: remove the reply, chips and attachments first")
                else -> Ask(question)
            }
        }
    }
}

/**
 * Each chat's side question, by a key of the caller's (the tab): one at a time, a new one replacing
 * the last, an answer that comes after a close or a newer question dropped. Main thread only.
 */
class SideQuestions(private val scope: CoroutineScope) {
    private val _states = MutableStateFlow<Map<String, SideQuestionState>>(emptyMap())
    val states: StateFlow<Map<String, SideQuestionState>> = _states.asStateFlow()
    private val jobs = HashMap<String, Job>()
    private val sessions = HashMap<String, String>()

    /** [sessionId]: the session asked, whose answer it is; another shown under [key] closes it ([follow]). */
    fun ask(key: String, sessionId: String, question: String, call: suspend () -> String?) {
        jobs.remove(key)?.cancel()
        sessions[key] = sessionId
        set(key, SideQuestionState.Asking(question))
        // Registered before it runs: on an immediate dispatcher the answer can come inside launch.
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            val next = try {
                SideQuestionState.Answered(question, call())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SideQuestionState.Failed(question, e.message?.takeIf { it.isNotEmpty() } ?: "Claude did not answer")
            }
            if (jobs[key] === job) {
                jobs.remove(key)
                set(key, next)
            }
        }
        jobs[key] = job
        job.start()
    }

    /** [key] now shows [sessionId]: a side question asked of another session does not belong there. */
    fun follow(key: String, sessionId: String?) {
        val asked = sessions[key] ?: return
        if (asked != sessionId) close(key)
    }

    fun close(key: String) {
        jobs.remove(key)?.cancel()
        sessions.remove(key)
        if (key in _states.value) _states.value = _states.value - key
    }

    private fun set(key: String, state: SideQuestionState) {
        _states.value = _states.value + (key to state)
    }
}
