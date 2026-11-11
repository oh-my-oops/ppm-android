package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement

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

    /**
     * The preview Claude attaches to each option (`option.preview`: a plan, a mockup, a snippet),
     * question by question and option by option as [ApprovalRequest.questions] reads them; null
     * for an option with none.
     */
    fun optionPreviews(input: JsonElement?): List<List<String?>> =
        input["questions"].array.orEmpty().filter { it["question"].string != null }.map { question ->
            question["options"].array.orEmpty().filter { it["label"].string != null }.map { it["preview"].string }
        }

    /** Whether any option of a question carries a preview: the pane exists only then (`hasOptionPreviews`). */
    fun hasOptionPreviews(previews: List<String?>): Boolean = previews.any { !it.isNullOrBlank() }

    /**
     * Which option the preview pane describes (`previewOptionIndex`): the focused one; with
     * "Other" focused, the first selected one, so the pane does not go blank while the user types.
     * -1 for none.
     */
    fun previewOptionIndex(options: List<Question.Option>, focused: Int, selected: List<String>): Int {
        if (focused >= 0 && focused < options.size) return focused
        return options.indexOfFirst { it.label in selected }
    }
}
