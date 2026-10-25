package com.hienlh.ppm.protocol

/**
 * The questions Codex asks without stopping its turn (`shared/chat-questions.ts`). They arrive as a
 * `RequestUserInput` tool call and are answered by an ordinary message that names the call, so
 * they can be answered during the turn or long after it, and the card knows from that message,
 * after a reload too, that it was.
 */
object ChatQuestions {
    /** The tool name such a question arrives under. */
    const val ASYNC_QUESTION_TOOL = "RequestUserInput"

    /** How the reply to the call [itemId] starts. */
    fun replyPrefix(itemId: String): String = "Answers to Codex question ${encodeUriComponent(itemId)}:\n"

    /** The message answering [questions] with [answers] (keyed by [Question.key]), or skipping them when there are none. */
    fun formatReply(itemId: String, questions: List<Question>, answers: Map<String, String>?): String =
        replyPrefix(itemId) + (
            answers?.let { map -> questions.joinToString("\n\n") { "${it.question}\n${map[it.key].orEmpty()}" } }
                ?: "Skipped by the user."
            )
}
