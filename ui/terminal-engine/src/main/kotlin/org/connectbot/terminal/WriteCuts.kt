package org.connectbot.terminal

import java.nio.ByteBuffer

/**
 * Follows libvterm's parser over everything the emulator writes to it, however the bytes are cut into writes, for three things the library
 * cannot be asked about: where a write has to be cut, what the scroll region of the screen that is shown has to be, and what to answer when
 * a program asks for the terminal's colours or whether the alternate screen is on.
 *
 * **Links.** libvterm says where a link is when the link ends, but what text is there the emulator learns by reading the screen
 * afterwards, and anything written after the link may already have replaced those cells. A link whose cells were erased or overwritten
 * before that read (a replay is one write, and live output arrives in writes the snapshot has not caught up with) was shown over the text
 * that is there now: `ls --hyperlink`, a `clear`, then a plain listing came back from a reconnect with the old addresses under the first
 * characters of the new lines. So the emulator hands the library the bytes up to the end of each OSC 8, reads the screen, and goes on
 * (`TerminalEmulatorImpl.writeInput`).
 *
 * **The scroll region.** libvterm keeps one region for the whole terminal and shows either screen through it; xterm.js, which the web app
 * is, keeps one for each screen, gives the alternate one a whole-screen region every time it is entered, hands the shell's its own back when
 * it is left, and resets both whenever the size really changes. Programs rely on that. Vim sets `ESC [ 1 ; 18 r` on the alternate screen
 * and leaves it with `?1049l`, never `ESC [ r`, so in libvterm the shell went on with vim's region: a line feed on the bottom row no longer
 * scrolled, and everything the shell printed after that was drawn over the last rows of the screen. And `ESC [ 1 ; 18 r` on a screen that is
 * not 18 rows tall is not "the whole screen" to libvterm but an explicit bottom of 18 (or of the height at that moment), which a resize only
 * ever clamps downwards: a replay written before the screen had its final height left a region the screen outgrew.
 *
 * So this keeps the region each screen would have in xterm.js, from what the stream says (DECSTBM exactly as `state.c` takes it, the modes
 * 1047 and 1049, RIS, DECSTR, the origin mode) and from what the emulator says (a real size change, [resized]), and the one libvterm holds.
 * Wherever the two differ it ends a piece at the next place the parser is between sequences and [regionFix] gives the bytes that make the
 * library's region the one the screen shown has, and put the cursor back where it was, which setting a region moves. A plain shell never
 * differs, so it is never written to; neither is a program that leaves its region the way it found it.
 *
 * **Colour and alternate-screen queries.** xterm.js answers `OSC 10 / 11 / 12 ; ?` with the theme's foreground, background and cursor
 * colours (`ESC ] Ps ; rgb:rrrr/gggg/bbbb ESC \`, stepping through slots 10..12 for each `;`-separated parameter as `_setOrReportSpecialColor`
 * does), and answers `CSI ? 47 / 1047 / 1049 $ p` (DECRQM) from whether the alternate buffer is active (`1` on the alternate screen, `2` on
 * the main one). libvterm says nothing to the colour queries and answers `0` ("not recognised") to `47` and `1049`. Ending a piece at each
 * such query lets the emulator send [queryAnswer] right after the piece's own libvterm replies, in the order the queries arrived.
 *
 * **The shell's screen.** libvterm keeps both screens but shows one, and the only rows of the other it ever hands over are the ones a resize
 * pushes into the history, read through the screen it shows. A resize with the alternate screen up therefore gave the history the alternate
 * screen's rows, and a taller screen took them back as the shell's. The emulator keeps the shell's screen itself from the moment the
 * alternate one is entered, and that moment is the last one the library's screen is the shell's: the `h` of `CSI ? 1049 h` (or 1047) makes
 * the switch as it is read, so the write is cut right before it ([beforeEntry]) and the emulator reads the screen then
 * (`TerminalEmulatorImpl.saveShell`). And the `l` of `CSI ? 1049 l` (or 1047) puts the shell's screen back, with its whole-screen damage
 * wiping its links and prompts and any resize in between having cleared its wraps, so the write is cut right after that `l` ([afterExit])
 * and the emulator puts them back from the copy (`TerminalEmulatorImpl.restoreShell`).
 *
 * Everything follows libvterm's own parser, in UTF-8 mode, which is the one the JNI sets: no 8-bit controls (so a lone 0x9c ends nothing),
 * NUL and DEL ignored wherever they are, CAN and SUB cancel anything open, ESC starts over and stays pending until the byte that completes
 * it, and the other controls run and change nothing (BEL ends a string). A string (OSC, DCS, APC, PM, SOS) ends at BEL or ESC \ and an ESC
 * followed by anything else cancels it and is itself an escape sequence, so `ESC [` inside a string starts no CSI. A CSI is read as the parser
 * reads it: leader bytes `<=>?`, arguments (a number wraps as the library's `long` does and its low 31 bits are the value), intermediate
 * bytes, then a final byte, and `on_csi` accepts one leader and one intermediate at most. Where this could be wrong it is wrong towards no
 * change: a region it does not know of is never taken off. The state is kept between calls, because a sequence is as likely to be split
 * between two writes as not.
 */
internal class WriteCuts(rows: Int = 24) {
    /** libvterm's height: it decides what a bottom of 18 means to a DECSTBM. */
    private var rows = rows

    /** A scroll region as `state.c` keeps it: the first row, and the row after the last, or -1 for "down to the last row, however tall the screen gets". */
    private data class Region(val top: Int, val bottom: Int)

    /** What the library has: one region, for whichever screen it shows. */
    private var held = WHOLE

    /** What the screen shown has by xterm.js's rule, and what the other has. */
    private var shown = WHOLE
    private var hidden = WHOLE

    /** Whether the library shows the alternate screen, and whether it counts rows from the top of the region (DECOM). */
    var onAlternate = false
        private set
    var origin = false
        private set

    /** The cut [next] last returned stands just before the `h` that makes the library switch to the alternate screen, and is not one of the others. */
    var beforeEntry = false
        private set

    /** The cut [next] last returned stands just past the `l` that makes the library switch back to the shell's screen. */
    var afterExit = false
        private set

    /** That cut has been made and the `h` after it has not been read yet: the next byte is not cut before again. */
    private var entryCut = false

    /** The region the library has, as it would answer a DECRQSS: the first row counted from 1 and the last row. */
    val heldTop: Int get() = held.top + 1
    val heldBottom: Int get() = if (held.bottom > -1) held.bottom else rows

    private var state = NORMAL

    /** libvterm's `in_esc`: an ESC has been read and the byte that completes it has not. And whether that escape sequence has had an intermediate byte. */
    private var inEsc = false
    private var escIntermediate = false

    /** The number of the OSC being read, -1 until its first digit. */
    private var command = -1

    // Special-colour query slots (0 = OSC 10 fg, 1 = OSC 11 bg, 2 = OSC 12 cursor) in the OSC being read, and what the last cut asked for.
    private var colorSlot = 0
    private var colorSlotState = SLOT_EMPTY
    private var pendingColors = 0
    private var queriedColors = 0
    private var queriedMode = 0

    // The CSI being read, as far as the library's parser keeps it.
    private var leaders = 0
    private var firstLeader = 0
    private var intermediates = 0
    private var firstIntermediate = 0
    private val args = LongArray(MAX_ARGS)
    private var argIndex = 0
    private var argCount = 0

    /** The parser is between sequences: what is written next starts one or is text, and bytes handed to the library now cannot cut one short. */
    internal fun idle() = state == NORMAL && !inEsc

    private fun isString() = state >= OSC_COMMAND

    /** The library is new (or the emulator was reset): nothing is open, it holds no region, and it is [rows] tall. */
    fun reset(rows: Int) {
        this.rows = rows
        state = NORMAL
        inEsc = false
        command = -1
        colorSlot = 0
        colorSlotState = SLOT_EMPTY
        pendingColors = 0
        queriedColors = 0
        queriedMode = 0
        held = WHOLE
        shown = WHOLE
        hidden = WHOLE
        onAlternate = false
        origin = false
        beforeEntry = false
        afterExit = false
        entryCut = false
    }

    /**
     * The size really changed and the library has been told. It clamps the bottom of its region to the new height and never grows it; xterm.js
     * resets the region of both screens, and that is what the next [regionFix] does.
     */
    fun resized(rows: Int) {
        this.rows = rows
        if (held.bottom > -1) held = Region(held.top, minOf(held.bottom, rows))
        shown = WHOLE
        hidden = WHOLE
    }

    /**
     * The bytes that give the library the region the screen it shows has, and put its cursor back at [row] and [column] (0-based, where it is now), or null
     * when the library has it already or is in the middle of a sequence, in which case the cut comes again where the next sequence ends. With the origin mode
     * on the row is counted from the top of the region, and a cursor outside it cannot be put back.
     */
    fun regionFix(row: Int, column: Int): ByteArray? {
        if (shown == held || !idle()) return null
        val region = shown
        held = region
        val out = StringBuilder(24).append(CSI)
        if (region != WHOLE) {
            out.append(region.top + 1).append(';')
            if (region.bottom > -1) out.append(region.bottom)
        }
        out.append('r')
        val top = region.top
        val bottom = if (region.bottom > -1) region.bottom else rows
        val cursorRow = if (!origin) row + 1 else if (row in top until bottom) row - top + 1 else 0
        if (cursorRow > 0) out.append(CSI).append(cursorRow).append(';').append(column + 1).append('H')
        return out.toString().toByteArray(Charsets.US_ASCII)
    }

    /**
     * What xterm.js answers to the colour query (`OSC 10 / 11 / 12 ; ?`) or alternate-screen DECRQM (`CSI ? 47 / 1047 / 1049 $ p`) that ended
     * at the last cut, or null when the cut was for a link or a region fix. Each 8-bit channel of [foreground], [background] and [cursor]
     * (ARGB) is doubled to four lowercase hex digits (`rgb:rrrr/gggg/bbbb`) and ended by `ESC \`, matching xterm.js's `toRgbString` and
     * `_reportColor`.
     */
    fun queryAnswer(foreground: Int, background: Int, cursor: Int): ByteArray? {
        if (queriedColors != 0) {
            val colors = queriedColors
            queriedColors = 0
            val out = StringBuilder(32)
            for (slot in 0..2) {
                if (colors and (1 shl slot) == 0) continue
                val color = when (slot) {
                    0 -> foreground
                    1 -> background
                    else -> cursor
                }
                out.append(OSC).append(10 + slot).append(";rgb:")
                appendChannel(out, (color ushr 16) and 0xFF)
                out.append('/')
                appendChannel(out, (color ushr 8) and 0xFF)
                out.append('/')
                appendChannel(out, color and 0xFF)
                out.append(ST_ESCAPED)
            }
            return out.toString().toByteArray(Charsets.US_ASCII)
        }
        if (queriedMode != 0) {
            val mode = queriedMode
            queriedMode = 0
            val status = if (onAlternate) 1 else 2
            return "$CSI?$mode;$status\$y".toByteArray(Charsets.US_ASCII)
        }
        return null
    }

    private fun appendChannel(out: StringBuilder, byte: Int) {
        val hi = HEX[(byte ushr 4) and 0x0F]
        val lo = HEX[byte and 0x0F]
        out.append(hi).append(lo).append(hi).append(lo)
    }

    /**
     * The index of the first cut in [from] until [to], or -1 when there is none. A cut stands just past the last byte of an OSC 8, just past
     * a colour or alternate-screen query (`OSC 10 / 11 / 12 ; ?`, `CSI ? 47 / 1047 / 1049 $ p`), and just past the byte after which the
     * library's region is not the one the screen shown has and the parser is between sequences (the caller then asks for [regionFix] and
     * [queryAnswer]); and just *before* the `h` that makes the library switch to the alternate screen, [beforeEntry] then being true: the last
     * moment the library's screen is the shell's, which it never is again until the alternate screen is left (see [entersAlternate]). The scan goes
     * on from the state it stopped in, so the next call takes the bytes after the last one given, or from the cut, which for that one is the `h`.
     */
    fun next(bytes: ByteArray, from: Int, to: Int): Int {
        beforeEntry = false
        afterExit = false
        queriedColors = 0
        queriedMode = 0
        var i = from
        while (i < to) {
            if (state == NORMAL && !inEsc) {
                // Nothing matters until an ESC, and most output has none.
                while (i < to && bytes[i] != ESC_BYTE) i++
                if (i == to) return -1
                // Most of what follows is a colour or a cursor movement, which changes nothing here.
                val after = skipPlainCsi(bytes, i, to)
                if (after > i) {
                    i = after
                    if (shown != held) return i
                    continue
                }
            }
            val b = bytes[i].toInt() and 0xFF
            if (entersAlternate(b)) return i
            if (step(b) || (shown != held && idle())) return i + 1
            i++
        }
        return -1
    }

    /**
     * Whether [b], the next byte, is the `h` that ends a `CSI ? Pm h` with 1047 or 1049 among its arguments while the library shows the shell's
     * screen: `set_dec_mode` switches the screen as it reads that `h`, so a write handed over up to here is the last the library reads of
     * the shell's, and what it holds can be read from it. True once for that byte, which is the one the next scan starts with and goes through
     * [step] like any other. The same arguments and the same leader and intermediates that [setModes] takes.
     */
    private fun entersAlternate(b: Int): Boolean {
        if (entryCut) {
            entryCut = false
            return false
        }
        if (b != 'h'.code || state != CSI_ARGUMENTS || onAlternate || leaders != 1 || firstLeader != '?'.code) return false
        for (i in 0..minOf(argIndex, MAX_ARGS - 1)) {
            val mode = (args[i] and MASK).toInt()
            if (mode == 1047 || mode == 1049) {
                entryCut = true
                beforeEntry = true
                return true
            }
        }
        return false
    }

    /**
     * The index just past the CSI that begins at [at] (an ESC, the parser being between sequences) when it lies whole in [bytes] up to [to], is made of
     * nothing but the bytes a CSI is made of, and ends in a final byte that is not one `finishCsi` reads (`r`, `p`, `h`, `l`); otherwise [at]. What such a
     * sequence does to the parser is to leave it where it was, which is all [step] would do with it, a byte at a time.
     */
    private fun skipPlainCsi(bytes: ByteArray, at: Int, to: Int): Int {
        if (at + 2 >= to || bytes[at + 1] != LEFT_BRACKET) return at
        var i = at + 2
        while (i < to) {
            val b = bytes[i].toInt() and 0xFF
            if (b < FINAL_FIRST) {
                if (b < SPACE) return at
            } else {
                return if (b > FINAL_LAST || b == 'r'.code || b == 'p'.code || b == 'h'.code || b == 'l'.code) at else i + 1
            }
            i++
        }
        return at
    }

    /** [next] over the first [to] bytes of a direct buffer, by absolute index: the buffer's position and limit are not read or moved. */
    fun next(buffer: ByteBuffer, from: Int, to: Int): Int {
        beforeEntry = false
        afterExit = false
        queriedColors = 0
        queriedMode = 0
        var i = from
        while (i < to) {
            if (state == NORMAL && !inEsc) {
                while (i < to && buffer.get(i) != ESC_BYTE) i++
                if (i == to) return -1
                val after = skipPlainCsi(buffer, i, to)
                if (after > i) {
                    i = after
                    if (shown != held) return i
                    continue
                }
            }
            val b = buffer.get(i).toInt() and 0xFF
            if (entersAlternate(b)) return i
            if (step(b) || (shown != held && idle())) return i + 1
            i++
        }
        return -1
    }

    /** [skipPlainCsi] over a direct buffer, by absolute index. */
    private fun skipPlainCsi(buffer: ByteBuffer, at: Int, to: Int): Int {
        if (at + 2 >= to || buffer.get(at + 1) != LEFT_BRACKET) return at
        var i = at + 2
        while (i < to) {
            val b = buffer.get(i).toInt() and 0xFF
            if (b < FINAL_FIRST) {
                if (b < SPACE) return at
            } else {
                return if (b > FINAL_LAST || b == 'r'.code || b == 'p'.code || b == 'h'.code || b == 'l'.code) at else i + 1
            }
            i++
        }
        return at
    }

    /** One byte of the stream, in the order `vterm_input_write` takes it: true when it is the last of an OSC 8 or a query this answers. */
    private fun step(b: Int): Boolean {
        if (b == NUL || b == DEL) return false
        if (b == CAN || b == SUB) {
            inEsc = false
            state = NORMAL
            return false
        }
        if (b == ESC) {
            escIntermediate = false
            if (!isString()) state = NORMAL
            inEsc = true
            return false
        }
        // BEL ends a string; every other control runs and changes nothing, in a CSI too.
        if (b < SPACE && !(b == BEL && isString())) return false

        var c = b
        var hoisted = false
        if (inEsc) {
            if (!escIntermediate && c in LETTER_FIRST..LETTER_LAST && (!isString() || c == BACKSLASH)) {
                // ESC and a letter are the C1 control that letter stands for; inside a string only ESC \ is one.
                c += 0x40
                hoisted = true
                inEsc = false
            } else {
                // Anything else cancels the string, and is the end of an escape sequence of its own.
                state = NORMAL
            }
        }

        when (state) {
            NORMAL -> if (hoisted) c1(c) else if (inEsc) escape(c)

            CSI_LEADER -> csiLeader(c)
            CSI_ARGUMENTS -> csiArguments(c)
            CSI_INTERMEDIATE -> csiIntermediate(c)

            // `ESC P`, then bytes up to the first one that can end a command (0x40 to 0x7e); BEL and ESC end nothing here.
            DCS_COMMAND -> if (c in FINAL_FIRST..FINAL_LAST) state = STRING

            // The number of the OSC, then `;` or the first character of a string that has none.
            OSC_COMMAND -> when {
                // Int arithmetic that wraps, as libvterm's `int command` does: a number that wraps to 8 is an OSC 8 to the library too.
                c in '0'.code..'9'.code -> command = (if (command == -1) 0 else command * 10) + (c - '0'.code)
                c == ';'.code -> when (command) {
                    8 -> state = OSC_8
                    in 10..12 -> {
                        colorSlot = command - 10
                        colorSlotState = SLOT_EMPTY
                        pendingColors = 0
                        state = OSC_COLOR
                    }
                    else -> state = OSC_OTHER
                }
                else -> {
                    state = if (command == 8) OSC_8 else OSC_OTHER
                    return endOfString(c, hoisted)
                }
            }

            OSC_COLOR -> return stepOscColor(c, hoisted)

            OSC_8, OSC_OTHER, STRING -> return endOfString(c, hoisted)
        }
        return queriedMode != 0 || afterExit
    }

    /**
     * One byte of an `OSC 10 / 11 / 12 ; ...` payload. xterm.js splits the payload on `;` and walks `slot` from `command - 10` up to 2,
     * reporting each slot whose parameter is `?`.
     */
    private fun stepOscColor(c: Int, hoisted: Boolean): Boolean {
        if (c == BEL || (hoisted && c == ST)) {
            if (colorSlotState == SLOT_QUESTION && colorSlot in 0..2) {
                pendingColors = pendingColors or (1 shl colorSlot)
            }
            queriedColors = pendingColors
            state = NORMAL
            return queriedColors != 0
        }
        if (!hoisted && c == ';'.code) {
            if (colorSlotState == SLOT_QUESTION && colorSlot in 0..2) {
                pendingColors = pendingColors or (1 shl colorSlot)
            }
            colorSlot++
            colorSlotState = SLOT_EMPTY
        } else {
            colorSlotState = if (!hoisted && colorSlotState == SLOT_EMPTY && c == '?'.code) SLOT_QUESTION else SLOT_OTHER
        }
        return false
    }

    /** A string ends at BEL, or at the ST that `ESC \` is; true when it was an OSC 8, which is a cut. */
    private fun endOfString(c: Int, hoisted: Boolean): Boolean {
        if (c != BEL && !(hoisted && c == ST)) return false
        val link = state == OSC_8
        state = NORMAL
        return link
    }

    /** The C1 control an ESC and a letter stand for, outside a string: the ones that start something. */
    private fun c1(c: Int) {
        when (c) {
            0x90 -> state = DCS_COMMAND
            0x98, 0x9E, 0x9F -> state = STRING
            0x9B -> {
                leaders = 0
                firstLeader = 0
                state = CSI_LEADER
            }
            0x9D -> {
                command = -1
                state = OSC_COMMAND
            }
        }
    }

    /** The byte after an ESC that is not a C1 letter: intermediates wait for the final byte, and a final byte of `c` alone is RIS. */
    private fun escape(c: Int) {
        when {
            c in SPACE..INTERMEDIATE_LAST -> escIntermediate = true
            c in ESCAPE_FINAL_FIRST..FINAL_LAST -> {
                inEsc = false
                if (c == 'c'.code && !escIntermediate) hardReset()
            }
            // A byte that is neither (one of a character, say): the library leaves the ESC pending.
        }
    }

    private fun csiLeader(c: Int) {
        if (c in LEADER_FIRST..LEADER_LAST) {
            if (leaders == 0) firstLeader = c
            if (leaders < MAX_STORED) leaders++
            return
        }
        argIndex = 0
        args[0] = MISSING
        state = CSI_ARGUMENTS
        csiArguments(c)
    }

    private fun csiArguments(c: Int) {
        when {
            c in '0'.code..'9'.code -> if (argIndex < MAX_ARGS) {
                // `if(args == MISSING) args = 0; args *= 10; args += c - '0'`: an argument that has come to the value that stands for none starts over.
                args[argIndex] = (if (args[argIndex] == MISSING) 0 else args[argIndex]) * 10 + (c - '0'.code)
            }
            c == ':'.code || c == ';'.code -> {
                if (c == ':'.code && argIndex < MAX_ARGS) args[argIndex] = args[argIndex] or MORE
                argIndex++
                if (argIndex < MAX_ARGS) args[argIndex] = MISSING
            }
            else -> {
                argCount = minOf(argIndex + 1, MAX_ARGS)
                intermediates = 0
                firstIntermediate = 0
                state = CSI_INTERMEDIATE
                csiIntermediate(c)
            }
        }
    }

    private fun csiIntermediate(c: Int) {
        when {
            c in SPACE..INTERMEDIATE_LAST -> {
                if (intermediates == 0) firstIntermediate = c
                if (intermediates < MAX_STORED) intermediates++
            }
            c in FINAL_FIRST..FINAL_LAST -> {
                state = NORMAL
                finishCsi(c)
            }
            // Not a byte a CSI can have: the library drops it.
            else -> state = NORMAL
        }
    }

    /** `on_csi`: what a finished CSI means to the region or a mode query. Only a leader `?` alone, or an intermediate `!` or `$` alone, or none of either, makes a command of these. */
    private fun finishCsi(final: Int) {
        when {
            leaders == 0 && intermediates == 0 && final == 'r'.code -> setRegion()
            leaders == 0 && intermediates == 1 && firstIntermediate == '!'.code && final == 'p'.code -> softReset()
            leaders == 1 && firstLeader == '?'.code && intermediates == 0 && (final == 'h'.code || final == 'l'.code) -> setModes(final == 'h'.code)
            leaders == 1 && firstLeader == '?'.code && intermediates == 1 && firstIntermediate == '$'.code && final == 'p'.code -> requestMode()
        }
    }

    /** `CSI ? Pm $ p` (DECRQM): xterm.js answers `1` (set) or `2` (reset) for 47, 1047 and 1049 from whether the alternate buffer is active. */
    private fun requestMode() {
        if (args[0] == MISSING) return
        val mode = (args[0] and MASK).toInt()
        if (mode == 47 || mode == 1047 || mode == 1049) queriedMode = mode
    }

    /** DECSTBM exactly as `state.c` takes it, and the screen shown has that region as the library does (it is the same sequence). */
    private fun setRegion() {
        var top = (if (args[0] == MISSING) 1L else args[0] and MASK).toInt() - 1
        val second = if (argCount < 2 || args[1] == MISSING) -1 else (args[1] and MASK).toInt()
        top = top.coerceIn(0, rows)
        var bottom = maxOf(second, -1)
        bottom = if (top == 0 && bottom == rows) -1 else minOf(bottom, rows)
        if ((if (bottom > -1) bottom else rows) <= top) {
            top = 0
            bottom = -1
        }
        shown = Region(top, bottom)
        held = shown
    }

    /** `CSI ? Pm h` and `l`: `set_dec_mode` for every argument that is not missing. Only 1047 and 1049 switch the screen; 47 is not a mode of this library. */
    private fun setModes(on: Boolean) {
        for (i in 0 until argCount) {
            if (args[i] == MISSING) continue
            when ((args[i] and MASK).toInt()) {
                6 -> origin = on
                1047, 1049 -> if (on) enterAlternate() else leaveAlternate()
            }
        }
    }

    /** The alternate screen starts with a region of the whole screen, and keeps the shell's for later. */
    private fun enterAlternate() {
        if (onAlternate) return
        onAlternate = true
        hidden = shown
        shown = WHOLE
    }

    /** The shell's screen has the region it had when the alternate one was entered. */
    private fun leaveAlternate() {
        if (!onAlternate) return
        onAlternate = false
        shown = hidden
        hidden = WHOLE
        afterExit = true
    }

    /** DECSTR: the screen shown has no region, the library has none either, and the origin mode is off. */
    private fun softReset() {
        shown = WHOLE
        held = WHOLE
        origin = false
    }

    /** RIS: neither screen has a region. The library goes on showing the screen it showed (its parser leaves that to the next mode). */
    private fun hardReset() {
        shown = WHOLE
        hidden = WHOLE
        held = WHOLE
        origin = false
    }

    private companion object {
        val WHOLE = Region(0, -1)

        // Parser states, in libvterm's order: everything from OSC_COMMAND on is a string.
        const val NORMAL = 0
        const val CSI_LEADER = 1
        const val CSI_ARGUMENTS = 2
        const val CSI_INTERMEDIATE = 3
        const val DCS_COMMAND = 4
        const val OSC_COMMAND = 5
        const val OSC_8 = 6
        const val OSC_COLOR = 7
        const val OSC_OTHER = 8
        const val STRING = 9

        const val SLOT_EMPTY = 0
        const val SLOT_QUESTION = 1
        const val SLOT_OTHER = 2

        const val NUL = 0x00
        const val ESC = 0x1B
        const val ESC_BYTE = 0x1B.toByte()
        const val LEFT_BRACKET = '['.code.toByte()
        const val BEL = 0x07
        const val CAN = 0x18
        const val SUB = 0x1A
        const val SPACE = 0x20
        const val DEL = 0x7F
        const val BACKSLASH = 0x5C
        const val ST = 0x9C
        const val LETTER_FIRST = 0x40
        const val LETTER_LAST = 0x5F
        const val LEADER_FIRST = 0x3C
        const val LEADER_LAST = 0x3F
        const val INTERMEDIATE_LAST = 0x2F
        const val ESCAPE_FINAL_FIRST = 0x30
        const val FINAL_FIRST = 0x40
        const val FINAL_LAST = 0x7E

        /** `CSI_ARGS_MAX`, and how many leader and intermediate bytes the parser keeps (`CSI_LEADER_MAX - 1`). */
        const val MAX_ARGS = 16
        const val MAX_STORED = 15

        /** `CSI_ARG_MISSING`, `CSI_ARG_MASK` and `CSI_ARG_FLAG_MORE` of vterm.h. */
        const val MISSING = 0x7FFFFFFFL
        const val MASK = 0x7FFFFFFFL
        const val MORE = 0x80000000L

        const val CSI = "\u001b["
        const val OSC = "\u001b]"
        const val ST_ESCAPED = "\u001b\\"
        const val HEX = "0123456789abcdef"
    }
}
