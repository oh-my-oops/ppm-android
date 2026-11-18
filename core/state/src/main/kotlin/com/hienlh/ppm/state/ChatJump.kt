package com.hienlh.ppm.state

/**
 * A request to show a place in a chat — a call's tool card, or else the prompt that asked for
 * it — made from outside the chat (the Review tab's turn chip; `chat-jump-store.ts`). The chat
 * showing that session takes it up once the place is on screen, which may be only after its tab
 * opens and loads. Compared by identity: taking one up clears it only if it is still the same.
 */
class ChatJumpRequest(
    val sessionId: String,
    /** The call to show; its card is drawn only once the message holding it is. */
    val toolUseId: String?,
    /** The user message to show when the call's card is not drawn (a sub-agent's call, a collapsed card). */
    val messageId: String?,
    /** When it was asked ([monotonicMillis]): a request nothing can take up is dropped after [GIVE_UP_MILLIS]. */
    val at: Long,
    /**
     * The turn to show (a turn chip, a turn link): its first message pinned near the top when it is
     * drawn, else the history window opened around it.
     */
    val turnId: String? = null,
) {
    companion object {
        /** `CHAT_JUMP_GIVE_UP_MS`. */
        const val GIVE_UP_MILLIS = 8_000L

        /** `CHAT_JUMP_MAX_WAIT_MS`: the longest a request waits in all, for a tab still opening (another project's). */
        const val MAX_WAIT_MILLIS = 60_000L
    }
}
