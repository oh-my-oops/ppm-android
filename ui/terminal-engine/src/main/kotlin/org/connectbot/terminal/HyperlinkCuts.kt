package org.connectbot.terminal

import java.nio.ByteBuffer

/**
 * Finds where every OSC 8 (hyperlink) sequence ends in the bytes written to a terminal, however they are cut into writes.
 *
 * The emulator needs the places because libvterm says where a link is when the link ends, but what text is there the emulator
 * learns by reading the screen afterwards, and anything written after the link may already have replaced those cells. A link
 * whose cells were erased or overwritten before that read (a replay is one write, and live output arrives in writes the snapshot
 * has not caught up with) was shown over the text that is there now: `ls --hyperlink`, a `clear`, then a plain listing came back
 * from a reconnect with the old addresses under the first characters of the new lines. So the emulator hands the library the bytes
 * up to the end of each OSC 8, reads the screen, and goes on (`TerminalEmulatorImpl.writeInput`).
 *
 * This follows libvterm's own parser, in UTF-8 mode, which is the one the JNI sets (only BEL and ESC \ end a string, a lone 0x9c
 * does not; CAN and SUB cancel; ESC followed by anything but `\` cancels a string). Where it can be wrong it is wrong towards a
 * cut: a cut the library sees no OSC 8 for costs a little time and is otherwise invisible, since libvterm takes its input in any
 * pieces, while a cut that is missing is the bug this exists for. The state is kept between calls, because a sequence is as
 * likely to be split between two writes as not.
 */
internal class HyperlinkCuts {
    private var state = NORMAL

    /** The number of the OSC being read, -1 until its first digit. */
    private var command = -1

    fun reset() {
        state = NORMAL
        command = -1
    }

    /**
     * The index just past the first OSC 8 terminator in [from] until [to], or -1 when there is none. The scan goes on from the
     * state it stopped in, so the next call takes the bytes after the last one given.
     */
    fun next(bytes: ByteArray, from: Int, to: Int): Int {
        var i = from
        while (i < to) {
            if (state == NORMAL) {
                // Nothing matters until an ESC, and most output has none.
                while (i < to && bytes[i] != ESC_BYTE) i++
                if (i == to) return -1
            }
            if (step(bytes[i].toInt() and 0xFF)) return i + 1
            i++
        }
        return -1
    }

    /** [next] over the first [to] bytes of a direct buffer, by absolute index: the buffer's position and limit are not read or moved. */
    fun next(buffer: ByteBuffer, from: Int, to: Int): Int {
        var i = from
        while (i < to) {
            if (state == NORMAL) {
                while (i < to && buffer.get(i) != ESC_BYTE) i++
                if (i == to) return -1
            }
            if (step(buffer.get(i).toInt() and 0xFF)) return i + 1
            i++
        }
        return -1
    }

    /** One byte of the stream; true when it is the last of an OSC 8. */
    private fun step(b: Int): Boolean {
        if (b == CAN || b == SUB) {
            state = NORMAL
            return false
        }
        when (state) {
            NORMAL -> if (b == ESC) state = ESCAPE

            // An ESC that is not inside a string. Other control characters run and leave the ESC pending, as libvterm does.
            ESCAPE -> when {
                b == ']'.code -> startOsc()
                b == '\\'.code -> state = NORMAL
                b < SPACE || b == DEL -> Unit
                else -> state = NORMAL
            }

            // `ESC ]`, then the digits of the number, then `;`: a string that goes on to its terminator.
            OSC_COMMAND -> when {
                // Int arithmetic that wraps, as libvterm's `int command` does: a number that wraps to 8 is an OSC 8 to the library too.
                b in '0'.code..'9'.code -> command = (if (command == -1) 0 else command * 10) + (b - '0'.code)
                b == ';'.code -> state = if (command == 8) OSC_8 else OSC_OTHER
                b == BEL -> {
                    // An OSC with a number and no string at all: still an OSC 8 as far as the library is concerned.
                    state = NORMAL
                    return command == 8
                }
                b == ESC -> state = if (command == 8) OSC_8_ESCAPE else OSC_OTHER_ESCAPE
                b < SPACE || b == DEL -> Unit
                // Not a digit and not `;`: the library takes this character as the start of the string.
                else -> state = if (command == 8) OSC_8 else OSC_OTHER
            }

            OSC_8, OSC_OTHER -> when (b) {
                BEL -> {
                    val ended8 = state == OSC_8
                    state = NORMAL
                    return ended8
                }
                ESC -> state = if (state == OSC_8) OSC_8_ESCAPE else OSC_OTHER_ESCAPE
            }

            // An ESC inside a string: `\` ends it, and so does nothing else. Another control character, a second ESC included, leaves
            // it pending, and `]` is taken as a new OSC here as well, which is wrong only for a string the library cancels there, and
            // costs nothing then.
            OSC_8_ESCAPE, OSC_OTHER_ESCAPE -> when {
                b == '\\'.code -> {
                    val ended8 = state == OSC_8_ESCAPE
                    state = NORMAL
                    return ended8
                }
                b == ']'.code -> startOsc()
                b == BEL -> state = ESCAPE
                b < SPACE || b == DEL -> Unit
                else -> state = NORMAL
            }
        }
        return false
    }

    private fun startOsc() {
        command = -1
        state = OSC_COMMAND
    }

    private companion object {
        const val NORMAL = 0
        const val ESCAPE = 1
        const val OSC_COMMAND = 2
        const val OSC_8 = 3
        const val OSC_OTHER = 4
        const val OSC_8_ESCAPE = 5
        const val OSC_OTHER_ESCAPE = 6

        const val ESC = 0x1B
        const val ESC_BYTE = 0x1B.toByte()
        const val BEL = 0x07
        const val CAN = 0x18
        const val SUB = 0x1A
        const val SPACE = 0x20
        const val DEL = 0x7F
    }
}
