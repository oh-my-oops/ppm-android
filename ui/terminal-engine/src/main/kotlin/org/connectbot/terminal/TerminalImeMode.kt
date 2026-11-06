package org.connectbot.terminal

/** IME editing is independent of the optional, line-buffered [ComposeController]. */
enum class TerminalImeMode {
    /** Preserve termlib's TYPE_NULL / explicit ComposeMode behavior. */
    Legacy,
    /** Keep IME candidates local; send finalized Unicode text and hardware keys immediately. */
    CommittedText,
}

/** What the host does to the soft keyboard's editor from outside the IME; call on the UI thread. */
interface TerminalImeController {
    /**Input sent outside the IME must end its editable context first: flush pending composition, then invalidate history before toolbar keys, paste or commands. */
    fun finishBeforeExternalInput()

    /** Ask the focused terminal editor to show the soft keyboard when the user tapped the terminal or a toolbar key. */
    fun showKeyboard() {}

    /**
     * Hide the soft keyboard of a terminal that is about to lose the focus without another editor taking it,
     * as a browser does when a tap lands on nothing. Only a view that still holds the focus can hide it, so this
     * comes before the focus is cleared, not after.
     */
    fun hideKeyboard()
}
