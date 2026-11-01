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
 * The first row after a [reset] is taken as not wrapped, as it would be on a fresh screen. Rows libvterm pushes in a
 * burst that is not one line feed (a resize, a scroll of several rows at once) carry no usable flag, as before.
 */
internal class ScrollbackWrap {
    private var next = false

    /** [sent] is what arrived with a pushed row; returns that row's own flag. */
    fun push(sent: Boolean): Boolean = next.also { next = sent }

    /** A row came back off the history onto the screen: the next one pushed is that row again, with the flag it had. */
    fun pop(own: Boolean) {
        next = own
    }

    fun reset() {
        next = false
    }
}
