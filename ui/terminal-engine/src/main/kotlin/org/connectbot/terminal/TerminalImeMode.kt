package org.connectbot.terminal

/** IME editing is independent of the optional, line-buffered [ComposeController]. */
enum class TerminalImeMode {
    /** Preserve termlib's TYPE_NULL / explicit ComposeMode behavior. */
    Legacy,
    /** Keep IME candidates local; send finalized Unicode text and hardware keys immediately. */
    CommittedText,
}

/** Input sent outside the IME must end its editable context first; call on the UI thread. */
fun interface TerminalImeController {
    /** Flush pending composition, then invalidate history before toolbar keys, paste or commands. */
    fun finishBeforeExternalInput()
}
