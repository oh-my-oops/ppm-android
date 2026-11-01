package org.connectbot.terminal

/**
 * Whether a row that scrolled off the screen was soft-wrapped, as libvterm's scrollback callback reports it, put right.
 *
 * The shipped JNI (`Terminal.cpp`, `termSbPushline`) tells [TerminalEmulatorImpl.pushScrollbackLine] that the row it is
 * pushing continues onto the next one when `lineinfo(1).continuation` is set. libvterm shifts its line infos up before
 * it calls the screen back (`state.c` `scroll()` moves them, then `scrollrect` pushes the rows), so by then index 1
 * holds what was the third row's flag. The flag that arrives with a pushed row is the true flag of the row *after* it,
 * which is the next row pushed, and a row's own flag is the one that arrived with the push before it. Taken as it
 * comes, every wrapped URL in the history is cut a row early or glued to the next line, and a selection copied from
 * the history breaks its lines in the wrong places.
 *
 * The first row after a [reset] is taken as not wrapped, as it would be on a fresh screen. Rows libvterm pushes in a scroll of
 * several rows at once carry no usable flag, as before. The rows a resize pushes do not come through here: the library reads one
 * flag for the whole batch, from line infos it is about to clear, so the screen's own flags say (see [ResizedRows]), and [top]
 * leaves the chain waiting with the flag of the row the resize left on top.
 */
internal class ScrollbackWrap {
    private var next = false

    /**
     * [sent] is what arrived with a pushed row; returns that row's own flag. [keptBelow]: the row after the pushed one has a flag that
     * the library no longer holds (it clears every flag when the screen is resized, see [TerminalLine.wrapKept]), so what came with this
     * push, read from the library's line infos, says nothing about it.
     */
    fun push(sent: Boolean, keptBelow: Boolean = false): Boolean = next.also { next = sent || keptBelow }

    /** A row came back off the history onto the screen: the next one pushed is that row again, with the flag it had. */
    fun pop(own: Boolean) = top(own)

    /** The row now on top of the screen is the next one that will be pushed, and this is its flag. */
    fun top(flag: Boolean) {
        next = flag
    }

    fun reset() {
        next = false
    }
}
