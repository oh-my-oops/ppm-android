package org.connectbot.terminal

import java.nio.ByteBuffer
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a write is cut, in whatever pieces the bytes arrive: just past the end of every OSC 8, and where libvterm's scroll region has stopped being
 * the one the screen shown has, with the bytes the emulator then writes to put that right (see [WriteCuts]).
 */
class WriteCutsTest {
    private val esc = "\u001b"

    private fun bytes(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    /** The index in the whole stream just past every OSC 8, the chunks given one after another to one scanner. */
    private fun cuts(vararg chunks: String): List<Int> {
        val scanner = WriteCuts()
        val found = mutableListOf<Int>()
        var base = 0
        for (chunk in chunks) {
            val data = bytes(chunk)
            var from = 0
            while (true) {
                val cut = scanner.next(data, from, data.size)
                if (cut < 0) break
                found += base + cut
                from = cut
            }
            base += data.size
        }
        return found
    }

    @Test fun `a link ended by BEL is cut just after the BEL`() {
        val open = "$esc]8;;https://a.test/\u0007"
        val close = "$esc]8;;\u0007"
        assertEquals(listOf(open.length, open.length + 4 + close.length), cuts(open + "text" + close))
    }

    @Test fun `a link ended by ESC backslash is cut just after the backslash`() {
        val open = "$esc]8;;https://a.test/$esc\\"
        val close = "$esc]8;;$esc\\"
        assertEquals(listOf(open.length, open.length + 4 + close.length), cuts(open + "text" + close))
    }

    @Test fun `the parameters of a link do not matter, nor do leading zeros in the number`() {
        assertEquals(listOf("$esc]8;id=x:y=z;https://a.test/\u0007".length), cuts("$esc]8;id=x:y=z;https://a.test/\u0007"))
        assertEquals(1, cuts("$esc]8;id=x;https://a.test/\u0007").size)
        assertEquals(1, cuts("$esc]08;;https://a.test/\u0007").size)
        assertEquals(1, cuts("$esc]008;;https://a.test/\u0007").size)
    }

    @Test fun `an OSC with a number and no string is an OSC 8 to the library as well`() {
        assertEquals(listOf(4), cuts("$esc]8\u0007"))
        assertEquals(listOf(5), cuts("$esc]8$esc\\"))
    }

    @Test fun `a control character inside the number is run and the digits go on, so it is neither the end of the number nor the start of the string`() {
        // OSC 80, not OSC 8 and a string of "0;...".
        assertEquals(emptyList<Int>(), cuts("$esc]8\r0;;https://a.test/\u0007"))
        // And in front of the first digit: still OSC 8.
        assertEquals(listOf("$esc]\r8;;https://a.test/\u0007".length), cuts("$esc]\r8;;https://a.test/\u0007"))
    }

    @Test fun `other OSCs are not cut, whatever their terminator`() {
        for (number in listOf("0", "1", "2", "7", "9", "10", "11", "18", "52", "80", "88", "133", "1337", "8000")) {
            assertEquals(number, emptyList<Int>(), cuts("$esc]$number;payload\u0007$esc]$number;payload$esc\\"))
        }
        assertEquals(emptyList<Int>(), cuts("$esc]0;title\u0007plain text, $esc[1mbold$esc[0m, $esc[2J"))
    }

    @Test fun `a number that wraps to 8 is an OSC 8, as it is to libvterm's int`() {
        assertEquals(1, cuts("$esc]4294967304;;https://a.test/\u0007").size)
    }

    @Test fun `plain output has no cut`() {
        assertEquals(emptyList<Int>(), cuts("hello\r\nworld\u0007\u001b[31mred\u001b[0m"))
    }

    @Test fun `a sequence split between writes is found wherever it is split`() {
        val stream = "a$esc]8;;https://a.test/\u0007link$esc]8;;\u0007 b $esc]8;id=1;https://b.test/$esc\\other$esc]8;;$esc\\ end"
        val whole = cuts(stream)
        assertEquals(4, whole.size)
        for (split in 0..stream.length) {
            assertEquals("split at $split", whole, cuts(stream.substring(0, split), stream.substring(split)))
        }
        for (first in 0..stream.length step 3) for (second in first..stream.length step 5) {
            assertEquals("split at $first and $second", whole, cuts(stream.substring(0, first), stream.substring(first, second), stream.substring(second)))
        }
    }

    @Test fun `the ESC of an ST at the end of one write and its backslash at the start of the next are one terminator`() {
        assertEquals(listOf("$esc]8;;$esc\\".length), cuts("$esc]8;;$esc", "\\"))
    }

    @Test fun `CAN and SUB cancel a string, and its terminator is then plain text`() {
        assertEquals(emptyList<Int>(), cuts("$esc]8;;https://a.test/\u0018\u0007"))
        assertEquals(emptyList<Int>(), cuts("$esc]8;;https://a.test/\u001a\u0007"))
        assertEquals(emptyList<Int>(), cuts("$esc]8;;https://a.test/\u0018$esc\\"))
    }

    @Test fun `an ESC followed by anything but a backslash cancels the string`() {
        assertEquals(emptyList<Int>(), cuts("$esc]8;;https://a.test/${esc}Xmore\u0007"))
        assertEquals(emptyList<Int>(), cuts("$esc]8;;https://a.test/${esc}Xmore$esc\\"))
    }

    @Test fun `an ESC followed by a BEL cancels the string and leaves the ESC pending`() {
        // libvterm: the BEL runs as a control, the ESC stays, and what follows ends it as an escape sequence of its own.
        assertEquals(emptyList<Int>(), cuts("$esc]8;;https://a.test/$esc\u0007\u0007"))
    }

    @Test fun `a control character between the ESC and the bracket is run and the ESC is still pending`() {
        val stream = "$esc\r]8;;https://a.test/\u0007"
        assertEquals(listOf(stream.length), cuts(stream))
    }

    @Test fun `a string cancelled by an ESC and a BEL leaves the ESC pending for the character that follows`() {
        // The BEL runs as a control character and the ESC is still waiting: the `]` after it makes an OSC of it again.
        val stream = "$esc]8;;https://a.test/$esc\u0007]8;;https://b.test/\u0007"
        assertEquals(listOf(stream.length), cuts(stream))
    }

    @Test fun `several ESCs before the backslash are still one terminator`() {
        assertEquals(listOf("$esc]8;;u$esc$esc\\".length), cuts("$esc]8;;u$esc$esc\\"))
    }

    @Test fun `a lone 0x9c does not end a string, as the library is in UTF-8 mode`() {
        val data = bytes("$esc]8;;u") + byteArrayOf(0x9C.toByte()) + bytes("v\u0007")
        val scanner = WriteCuts()
        assertEquals(data.size, scanner.next(data, 0, data.size))
    }

    @Test fun `NUL, DEL and other control characters inside a string do not end it`() {
        assertEquals(listOf("$esc]8;;u\u0000\u007f\r\nv\u0007".length), cuts("$esc]8;;u\u0000\u007f\r\nv\u0007"))
        assertEquals(listOf("$esc]8;;u$esc\r\\".length), cuts("$esc]8;;u$esc\r\\"))
    }

    @Test fun `an OSC started inside a string is not one, as the library cancels the string and takes the ESC and the bracket as an escape sequence of its own`() {
        assertEquals(emptyList<Int>(), cuts("$esc]0;title$esc]8;;u\u0007"))
        assertEquals(emptyList<Int>(), cuts("$esc]8;;a$esc]8;;u\u0007$esc\\"))
        // Out of the string it is: the same bytes with the first string ended.
        assertEquals(listOf("$esc]0;title\u0007$esc]8;;u\u0007".length), cuts("$esc]0;title\u0007$esc]8;;u\u0007"))
    }

    @Test fun `a half-read sequence is forgotten by reset`() {
        val scanner = WriteCuts()
        val first = bytes("$esc]8;;https://a.te")
        assertEquals(-1, scanner.next(first, 0, first.size))
        scanner.reset(24)
        val second = bytes("st/\u0007")
        assertEquals(-1, scanner.next(second, 0, second.size))
    }

    @Test fun `a scan that stops at a cut goes on from the byte after it`() {
        val data = bytes("$esc]8;;a\u0007xy$esc]8;;\u0007z")
        val scanner = WriteCuts()
        val first = scanner.next(data, 0, data.size)
        assertEquals("$esc]8;;a\u0007".length, first)
        val second = scanner.next(data, first, data.size)
        assertEquals("$esc]8;;a\u0007xy$esc]8;;\u0007".length, second)
        assertEquals(-1, scanner.next(data, second, data.size))
    }

    @Test fun `a scan looks only inside the range it is given`() {
        val data = bytes("$esc]8;;a\u0007")
        val scanner = WriteCuts()
        assertEquals(-1, scanner.next(data, 0, data.size - 1))
        // The BEL was not given, so it comes next.
        assertEquals(data.size, scanner.next(data, data.size - 1, data.size))
    }

    // ---------------------------------------------------------------- the scroll region

    /** What an emulator that follows every cut with [WriteCuts.regionFix] writes, and where: (index in the stream, the bytes). The cursor is at row 4, column 6 each time. */
    private fun drive(scanner: WriteCuts, chunks: Array<out String>, row: Int = 4, column: Int = 6): List<Pair<Int, String>> {
        val found = mutableListOf<Pair<Int, String>>()
        var base = 0
        for (chunk in chunks) {
            val data = bytes(chunk)
            var from = 0
            while (true) {
                val cut = scanner.next(data, from, data.size)
                if (cut < 0) break
                scanner.regionFix(row, column)?.let { found += (base + cut) to String(it, Charsets.ISO_8859_1) }
                from = cut
            }
            base += data.size
        }
        return found
    }

    private fun fixes(rows: Int, vararg chunks: String) = drive(WriteCuts(rows), chunks)

    /** A scanner [rows] tall that has been given [chunks] the way the emulator gives them. */
    private fun scanner(rows: Int, vararg chunks: String) = WriteCuts(rows).also { drive(it, chunks) }

    /** The text of the bytes that put the cursor at row 4, column 6. */
    private val cursor = "$esc[5;7H"
    private val enter = "$esc[?1049h"
    private val leave = "$esc[?1049l"

    private fun cleared() = emptyList<Pair<Int, String>>()

    @Test fun `vim sets a region on the alternate screen and leaves it set, and the shell gets the whole screen back where it ends`() {
        // Thirty rows: a bottom of 18 is not "the screen" to the library, but an explicit one.
        val stream = "\$ vim\r\n$enter$esc[22;0;0t$esc[>4;2m$esc[1;18r$esc[H~$esc[2J$leave"
        assertEquals(listOf(stream.length to "$esc[r$cursor"), fixes(30, stream))
        assertEquals(listOf(stream.length to "$esc[r$cursor"), fixes(40, stream))
    }

    @Test fun `a region as tall as the screen is no region, and a plain shell is never written to`() {
        assertEquals(cleared(), fixes(18, "$enter$esc[1;18r$esc[H$leave"))
        assertEquals(cleared(), fixes(30, "$enter$leave$enter$esc[2J$leave"))
        assertEquals(cleared(), fixes(30, "ls$esc[0m\r\n$esc[31mred$esc[m$esc[2J$esc[H$esc[r$esc[1;30r$esc[;r$esc[?25l$esc[?25h$esc[?2004h"))
        assertEquals(cleared(), fixes(30, "$esc]0;title\u0007$esc]8;;https://a.test/\u0007link$esc]8;;\u0007$esc]133;A$esc\\"))
    }

    @Test fun `the shell's own region is its own, the alternate screen starts without one and the shell has it back when it is left`() {
        val primary = "$esc[3;8r"
        val firstLeave = primary + enter + "draw" + leave
        assertEquals(
            listOf(
                "$primary$enter".length to "$esc[r$cursor",
                firstLeave.length to "$esc[3;8r$cursor",
            ),
            fixes(30, "$firstLeave\$ "),
        )
    }

    @Test fun `a region set on the alternate screen is not the shell's, and the one the shell had is not lost to it`() {
        val stream = "$esc[3;8r$enter$esc[2;5r$leave$enter$leave"
        val at = listOf(
            "$esc[3;8r$enter",
            "$esc[3;8r$enter$esc[2;5r$leave",
            "$esc[3;8r$enter$esc[2;5r$leave$enter",
            stream,
        ).map { it.length }
        assertEquals(
            listOf(at[0] to "$esc[r$cursor", at[1] to "$esc[3;8r$cursor", at[2] to "$esc[r$cursor", at[3] to "$esc[3;8r$cursor"),
            fixes(30, stream),
        )
    }

    @Test fun `entering the alternate screen again, or leaving the shell's, changes nothing`() {
        val stream = "$esc[3;8r$enter$enter$enter$leave$leave$leave"
        val first = "$esc[3;8r$enter".length
        val back = "$esc[3;8r$enter$enter$enter$leave".length
        assertEquals(listOf(first to "$esc[r$cursor", back to "$esc[3;8r$cursor"), fixes(30, stream))
    }

    @Test fun `the modes 1047 and 1049 switch the screen and nothing else does`() {
        for ((mode, switches) in listOf("1049" to true, "1047" to true, "47" to false, "1048" to false)) {
            val scanner = scanner(30, "$esc[3;8r$esc[?" + mode + "h")
            assertEquals(mode, switches, scanner.onAlternate)
        }
        // 1047 leaves the screen too, and 47 and 1048 do not enter one that was entered.
        assertEquals(false, scanner(30, "$esc[?1047h$esc[?1047l").onAlternate)
        assertEquals(true, scanner(30, "$esc[?1049h$esc[?47l$esc[?1048l").onAlternate)
    }

    @Test fun `a real size change takes the region off both screens, and the bytes come at once when the parser is between sequences`() {
        val scanner = scanner(30, "$esc[3;8r")
        scanner.resized(40)
        assertEquals("$esc[r$cursor", String(scanner.regionFix(4, 6)!!, Charsets.ISO_8859_1))
        assertNull(scanner.regionFix(4, 6))
        // And the shell's region, which was parked on the alternate screen, is gone too.
        val parked = scanner(30, "$esc[3;8r$enter")
        parked.resized(40)
        assertNull(parked.regionFix(4, 6))
        assertEquals(cleared(), drive(parked, arrayOf("$leave")))
    }

    @Test fun `a size change with no region held asks for nothing, and what the library holds is the one it clamped to`() {
        val scanner = scanner(30, "text$esc[2J")
        scanner.resized(40)
        assertNull(scanner.regionFix(4, 6))
        assertEquals(cleared(), drive(scanner, arrayOf("$enter$leave")))

        // The library cuts the bottom of its region down to a shorter screen and never up: held, it is a region, however short the screen is.
        val clamped = scanner(30, "$esc[1;18r")
        assertEquals(18, clamped.heldBottom)
        clamped.resized(12)
        assertEquals(12, clamped.heldBottom)
        assertEquals("$esc[r$cursor", String(clamped.regionFix(4, 6)!!, Charsets.ISO_8859_1))
        assertEquals(12, clamped.heldBottom)
    }

    @Test fun `a region set on the shell's screen is taken off when the screen really changes size`() {
        val scanner = scanner(24, "$esc[3;8r")
        scanner.resized(24)
        assertEquals("$esc[r$cursor", String(scanner.regionFix(4, 6)!!, Charsets.ISO_8859_1))
        assertEquals(1, scanner.heldTop)
        assertEquals(24, scanner.heldBottom)
    }

    @Test fun `a size change in the middle of a sequence waits for the end of it, and the cut stands just past it`() {
        val scanner = scanner(30, "$esc[1;18r$esc[1")
        scanner.resized(40)
        assertNull(scanner.regionFix(4, 6))
        // The sequence is not cut short: its end is the cut.
        assertEquals(listOf(";2H".length to "$esc[r$cursor"), drive(scanner, arrayOf(";2Hmore")))
    }

    @Test fun `a difference waiting for the end of a sequence is not a cut at every byte of it, in an array or a buffer`() {
        for (direct in listOf(false, true)) {
            val scanner = scanner(30, "$esc[1;18r$esc[1")
            scanner.resized(40)
            val data = bytes(";2Hmore")
            val cut = if (direct) scanner.next(ByteBuffer.allocateDirect(data.size).put(data), 0, data.size) else scanner.next(data, 0, data.size)
            assertEquals(if (direct) "buffer" else "array", ";2H".length, cut)
        }
    }

    @Test fun `a difference the caller has not put right is a cut just past the next sequence, a plain one too, in an array or a buffer`() {
        for (direct in listOf(false, true)) {
            val scanner = WriteCuts(30)
            fun cut(text: String, from: Int = 0): Int {
                val data = bytes(text)
                return if (direct) scanner.next(ByteBuffer.allocateDirect(data.size).put(data), from, data.size) else scanner.next(data, from, data.size)
            }
            // The alternate screen is entered with the region of the shell's set, and nobody writes the fix: the screen shown is not the one the library holds.
            // The first cut stands before the h, where the library still shows the shell's screen; the one that the region asks for is just past it.
            val enter = "$esc[1;18r$esc[?1049h"
            val before = cut(enter)
            assertEquals(if (direct) "buffer" else "array", enter.length - 1, before)
            assertEquals(if (direct) "buffer" else "array", enter.length, cut(enter, before))
            assertEquals(if (direct) "buffer" else "array", "$esc[2J".length, cut("$esc[2Jtext"))
        }
    }

    // ---------------------------------------------------------------- the alternate screen is entered

    /** The cuts of the chunks given one after another to one scanner that stand before an `h` that enters the alternate screen, as indexes in the whole stream. */
    private fun entries(direct: Boolean, vararg chunks: String): List<Int> {
        val scanner = WriteCuts()
        val found = mutableListOf<Int>()
        var base = 0
        for (chunk in chunks) {
            val data = bytes(chunk)
            val buffer = ByteBuffer.allocateDirect(data.size + 1).apply { put(data) }
            var from = 0
            while (true) {
                val cut = if (direct) scanner.next(buffer, from, data.size) else scanner.next(data, from, data.size)
                if (cut < 0) {
                    assertFalse(scanner.beforeEntry)
                    break
                }
                if (scanner.beforeEntry) found += base + cut
                from = cut
            }
            base += data.size
        }
        return found
    }

    private fun entries(vararg chunks: String) = entries(false, *chunks).also { assertEquals(it, entries(true, *chunks)) }

    @Test fun `a write is cut just before the h that enters the alternate screen, while the library still shows the shell's`() {
        val before = "ls\r\n$esc[?1049".length
        assertEquals(listOf(before), entries("ls\r\n$esc[?1049h$esc[H"))
        assertEquals(listOf(before), entries("ls\r\n$esc[?1049h"))
    }

    @Test fun `the scan goes on with that h, which is not cut before again, and the alternate screen is entered once it is read`() {
        val data = bytes("a$esc[?1049hb")
        val scanner = WriteCuts()
        val cut = scanner.next(data, 0, data.size)
        assertEquals("a$esc[?1049".length, cut)
        assertTrue(scanner.beforeEntry)
        assertFalse(scanner.onAlternate)
        assertEquals(-1, scanner.next(data, cut, data.size))
        assertFalse(scanner.beforeEntry)
        assertTrue(scanner.onAlternate)
    }

    @Test fun `every argument of the mode counts for an entry, in any place, and only 1047 and 1049 are one`() {
        for (args in listOf("1049", "1047", "25;1049", "1049;25", "1;1047;3", ";1049", "1049;", "1049:1", "25:1049", "01049", "0001047", "1049;1049", "0;0;1049", "21474836471049")) {
            val stream = "$esc[?" + args + "h"
            assertEquals(args, listOf(stream.length - 1), entries(stream))
        }
        for (args in listOf("47", "1048", "25", "1", "1000", "2004", "10490", "104", "4", "10491", "", ";", "47;1048", "0", "2147483647", "2147483648")) {
            assertEquals(args, emptyList<Int>(), entries("$esc[?" + args + "h"))
        }
    }

    @Test fun `only a question mark as the one leader, and no intermediate byte, makes an entry`() {
        for (stream in listOf(
            "$esc[1049h", "$esc[>1049h", "$esc[=1049h", "$esc[<1049h", "$esc[??1049h", "$esc[?>1049h", "$esc[>?1049h",
            "$esc[?1049 h", "$esc[?1049!h", "$esc[?1049\$h", "$esc[?1049\"h", "$esc[?1049/h", "$esc[?1049l", "$esc[?1047l",
        )) {
            assertEquals(stream.visible(), emptyList<Int>(), entries(stream))
        }
    }

    @Test fun `controls and NUL or DEL before the h are run first, and the cut is right before the h`() {
        for (stream in listOf("$esc[?1049\u0000h", "$esc[?1049\u007fh", "$esc[?1049\rh", "$esc[?1049\u0007h", "$esc[?10\n49\u0000\u007fh")) {
            assertEquals(stream.visible(), listOf(stream.length - 1), entries(stream))
        }
        // Not once CAN, SUB or an ESC has ended the sequence.
        for (stream in listOf("$esc[?1049\u0018h", "$esc[?1049\u001ah", "$esc[?1049$esc[?25h", "$esc[?10?49h")) {
            assertEquals(stream.visible(), emptyList<Int>(), entries(stream))
        }
    }

    @Test fun `an entry is a cut once, however many times the alternate screen is entered while it is up, and again after it is left`() {
        val enter = "$esc[?1049h"
        val leave = "$esc[?1049l"
        val enter1047 = "$esc[?1047h"
        val parts = listOf("a", enter, "b", enter, "c", enter1047, "d", leave, "e", enter1047, "f", "$esc[?1047l", enter)
        val starts = parts.runningFold(0) { at, part -> at + part.length }
        // The first enter, the 1047 after the leave and the last enter, each cut before its h; the two made while the screen is up are not.
        val expected = listOf(starts[1] + enter.length - 1, starts[9] + enter1047.length - 1, starts[12] + enter.length - 1)
        assertEquals(expected, entries(parts.joinToString("")))
    }

    @Test fun `an entry is found wherever the writes are split, even with the h alone in the next write`() {
        val stream = "ab$esc[3;8r" + "$esc[?1049h" + "draw$esc[2;5r" + "$esc[?1049l" + "$esc]8;;u\u0007x" + "$esc[?25;1047h" + "$esc[1;9r" + "$esc[?1047l" + "$esc[?1049h end"
        val whole = entries(stream)
        assertEquals(3, whole.size)
        for (split in 0..stream.length) {
            assertEquals("split at $split", whole, entries(stream.substring(0, split), stream.substring(split)))
        }
        for (first in 0..stream.length step 3) for (second in first..stream.length step 4) {
            assertEquals("split at $first and $second", whole, entries(stream.substring(0, first), stream.substring(first, second), stream.substring(second)))
        }
        // A write that is nothing but the h: the cut is at its start, and the piece before it is empty.
        val alone = entries("$esc[?1049", "h")
        assertEquals(listOf("$esc[?1049".length), alone)
    }

    @Test fun `no other cut says it stands before an entry`() {
        val scanner = WriteCuts(30)
        val link = bytes("$esc]8;;https://a.test/\u0007")
        assertEquals(link.size, scanner.next(link, 0, link.size))
        assertFalse(scanner.beforeEntry)
        val region = bytes("$esc[3;8r$esc[?1049h")
        var at = 0
        val said = mutableListOf<Pair<Int, Boolean>>()
        while (true) {
            val cut = scanner.next(region, at, region.size)
            if (cut < 0) break
            said += cut to scanner.beforeEntry
            scanner.regionFix(4, 6)
            at = cut
        }
        // Before the h, which the entry is; then just past it, where the shell's region is put right, which is not.
        assertEquals(listOf(region.size - 1 to true, region.size to false), said)
    }

    @Test fun `the bytes the region needs are not given at an entry's cut, where the parser is in the middle of a sequence`() {
        val scanner = WriteCuts(30)
        val data = bytes("$esc[3;8r$esc[?1049h")
        val cut = scanner.next(data, 0, data.size)
        assertTrue(scanner.beforeEntry)
        assertNull(scanner.regionFix(4, 6))
        assertEquals(data.size, scanner.next(data, cut, data.size))
        assertEquals("$esc[r$cursor", String(scanner.regionFix(4, 6)!!, Charsets.ISO_8859_1))
    }

    @Test fun `a reset forgets that an entry was cut, and the h that follows is only a character`() {
        val scanner = WriteCuts(30)
        val data = bytes("$esc[?1049h")
        val cut = scanner.next(data, 0, data.size)
        assertTrue(scanner.beforeEntry)
        scanner.reset(30)
        assertEquals(-1, scanner.next(data, cut, data.size))
        assertFalse(scanner.onAlternate)
        assertFalse(scanner.beforeEntry)
    }

    @Test fun `each place the parser is in the middle of something defers the bytes until it is out`() {
        val cases = listOf(
            "$esc[1;" to "2H",
            "$esc[?" to "25h",
            "$esc[1;2\$" to "p",
            "$esc[1;2 " to "q",
            "$esc[1;2/" to "m",
            "$esc" to "M",
            "$esc(" to "B",
            "$esc#" to "8",
            "$esc]0;title" to "\u0007",
            "$esc]0;title" to "$esc\\",
            "$esc]0;title$esc" to "\\",
            "$esc]" to "\u0007",
            "$esc]8;;https://a.test/" to "\u0007",
            esc + "P1;2" to "$esc\\",
            esc + "Pq1;2;3" to "\u0007",
            esc + "Pq1;2;3" to "$esc\\",
            esc + "_Gdata" to "$esc\\",
            esc + "_Gdata" to "\u0007",
            "$esc^private" to "$esc\\",
            esc + "Xstring" to "\u0007",
            "$esc]0;title" to "\u0018",
            "$esc[1;" to "\u001a",
            "$esc" to "\u0018",
            "$esc\r" to "M",
            "$esc[1;\r" to "2H",
        )
        for ((open, close) in cases) {
            val scanner = scanner(30, "$esc[1;18r$open")
            scanner.resized(30)
            assertNull("after " + open.visible(), scanner.regionFix(4, 6))
            val done = drive(scanner, arrayOf(close))
            assertEquals(open.visible() + " then " + close.visible(), listOf(close.length to "$esc[r$cursor"), done)
        }
    }

    private fun String.visible() = replace("\u001b", "ESC").replace("\u0007", "BEL").replace("\r", "CR")

    @Test fun `a string ended by something that is not its terminator is still open`() {
        for (open in listOf("$esc]0;t", esc + "Pq1", esc + "_G1", "$esc^p", esc + "Xs", "$esc]8;;u")) {
            val scanner = scanner(30, "$esc[1;18r$open")
            scanner.resized(30)
            // An ordinary control character, a stray ESC [, and a lone 0x9c in UTF-8 mode do not end a string... except that ESC [ cancels it.
            for (inside in listOf("\r", "\n", "a", "\u0000", "\u007f", "\u009c", "é")) {
                assertNull(open.visible() + " then " + inside.visible(), drive(scanner, arrayOf(inside)).firstOrNull())
            }
        }
    }

    @Test fun `a string is cancelled by an ESC and any other byte, and that byte is then an escape sequence of its own`() {
        // ESC [ inside a string starts no CSI: the rest is text, so the mode is not set.
        for (open in listOf("$esc]0;t", esc + "Pq1", esc + "_G1", "$esc^p", esc + "Xs")) {
            assertFalse(open.visible(), scanner(30, "$open$esc[?1049h").onAlternate)
            assertFalse(open.visible(), scanner(30, "$open$esc\r[?1049h").onAlternate)
            // Out of the string it does.
            assertTrue(open.visible(), scanner(30, "$open\u0007$esc[?1049h").onAlternate || open.startsWith(esc + "Pq"))
        }
        // tmux's passthrough doubles every ESC, and a DCS string takes nothing of what is inside it for the library's own.
        assertFalse(scanner(30, esc + "Ptmux;$esc$esc[?1049h$esc\\").onAlternate)
        // A string cancelled by ESC and BEL: the BEL runs, the ESC is still waiting, and what follows is a sequence of its own.
        assertTrue(scanner(30, "$esc]0;t$esc\u0007$esc[?1049h").onAlternate)
        assertTrue(scanner(30, "$esc]0;t$esc\u0007[?1049h").onAlternate)
    }

    @Test fun `the command part of a DCS is not a string yet, so an ESC leaves it and a BEL in it is only a control`() {
        // libvterm's DCS_COMMAND sits below its string states until the byte that ends the command (0x40 to 0x7e) comes: ESC [ there is a CSI.
        val left = scanner(30, esc + "P1" + esc + "[2;4r")
        assertEquals(2, left.heldTop)
        assertEquals(4, left.heldBottom)
        // The BEL ends nothing, the `q` ends the command, and from there ESC [ is cancelled, as inside any string: no region is set.
        val belled = scanner(30, esc + "P1\u0007;2q" + esc + "[2;4r")
        assertEquals(1, belled.heldTop)
        assertEquals(30, belled.heldBottom)
        // The same string, ended: what follows it is read afresh.
        val ended = scanner(30, esc + "P1\u0007;2q" + esc + "\\" + esc + "[2;4r")
        assertEquals(2, ended.heldTop)
        assertEquals(4, ended.heldBottom)
    }

    @Test fun `an ESC that is waiting for its byte, after a control character or an intermediate byte`() {
        // ESC, a control character, then ] makes an OSC; ESC ( ] is a designation, not an OSC.
        assertEquals(listOf("$esc\r]8;;u\u0007".length), cuts("$esc\r]8;;u\u0007"))
        assertEquals(emptyList<Int>(), cuts("$esc(]8;;u\u0007"))
        assertFalse(scanner(30, "$esc ?1049h").onAlternate)
        // ESC, an intermediate and a [: the [ is the end of that sequence, not the start of a CSI.
        assertFalse(scanner(30, "$esc#[?1049h").onAlternate)
        assertTrue(scanner(30, "$esc#8$esc[?1049h").onAlternate)
        // A byte that is part of a character leaves the ESC waiting, so a [ after it still starts a CSI.
        assertTrue(scanner(30, esc + "é[?1049h").onAlternate)
    }

    @Test fun `RIS takes every region away and soft reset the shown one, and both leave the origin mode`() {
        // RIS: nothing is held, nothing is parked.
        assertEquals(cleared(), fixes(30, "$esc[3;8r" + esc + "c" + enter + leave))
        assertEquals(listOf("$esc[3;8r$enter".length to "$esc[r$cursor"), fixes(30, "$esc[3;8r$enter$esc[2;5r" + esc + "c" + leave))
        // DECSTR on the shell's screen: no region there either, and nothing to put right.
        assertEquals(cleared(), fixes(30, "$esc[3;8r$esc[!p$enter$leave"))
        // DECSTR on the alternate screen leaves the shell's region where it was parked.
        assertEquals(
            listOf(
                "$esc[3;8r$enter".length to "$esc[r$cursor",
                "$esc[3;8r$enter$esc[2;5r$esc[!p$leave".length to "$esc[3;8r$cursor",
            ),
            fixes(30, "$esc[3;8r$enter$esc[2;5r$esc[!p$leave"),
        )
        // ESC c with an intermediate is not RIS (the shell's region is put back at the end), and an ESC c split by a control character still is.
        assertEquals(2, fixes(30, "$esc[3;8r$enter$esc[1;5r$esc#c$leave").size)
        assertEquals(cleared(), fixes(30, "$esc[3;8r$esc\rc$enter$leave"))
        assertTrue(scanner(30, "$esc[?6h" + esc + "c").origin.not())
        assertTrue(scanner(30, "$esc[?6h$esc[!p").origin.not())
        assertTrue(scanner(30, "$esc[?6h").origin)
        assertTrue(scanner(30, "$esc[?6h$esc[?6l").origin.not())
    }

    @Test fun `only a DECSTBM with no leader and no intermediate sets a region, and DECSTR is a bang and nothing else`() {
        for (stream in listOf("$esc[?1;18r", "$esc[>1;18r", "$esc[=1;18r", "$esc[<1;18r", "$esc[1;18 r", "$esc[1;18\$r", "$esc[1;18!r", "$esc[1;18\"r", "$esc[??1;18r")) {
            val scanner = scanner(30, stream)
            assertEquals(stream.visible(), 1 to 30, scanner.heldTop to scanner.heldBottom)
        }
        for (stream in listOf("$esc[?!p", "$esc[!!p", "$esc[!\$p", "$esc[ !p", "$esc[>!p")) {
            val scanner = scanner(30, "$esc[3;8r$stream")
            assertEquals(stream.visible(), 3 to 8, scanner.heldTop to scanner.heldBottom)
        }
        assertEquals(1 to 30, scanner(30, "$esc[3;8r$esc[!p").let { it.heldTop to it.heldBottom })
    }

    @Test fun `DECSTBM is taken as libvterm takes it`() {
        // (rows, arguments) to (first row counted from 1, last row), as a DECRQSS would answer them.
        val cases = listOf(
            Triple(30, "", 1 to 30), Triple(30, "1;30", 1 to 30), Triple(30, "2;10", 2 to 10), Triple(30, "5", 5 to 30), Triple(30, ";10", 1 to 10),
            Triple(30, "5;", 5 to 30), Triple(30, "1;18", 1 to 18), Triple(30, "1;99", 1 to 30), Triple(30, "10;2", 1 to 30), Triple(30, "40;50", 1 to 30),
            Triple(30, "30;30", 30 to 30), Triple(30, "0;0", 1 to 30), Triple(30, "0;5", 1 to 5), Triple(30, "1;18;7", 1 to 18), Triple(30, "1:2;18", 1 to 2),
            Triple(30, ";", 1 to 30), Triple(30, "5;5", 5 to 5), Triple(30, "5;6", 5 to 6), Triple(30, "2147483647;9", 1 to 9), Triple(30, "4294967297;9", 1 to 9),
            Triple(30, "1;2147483647", 1 to 30), Triple(30, "1;2147483649", 1 to 1), Triple(30, "01;0018", 1 to 18), Triple(18, "1;18", 1 to 18),
            Triple(1, "1;1", 1 to 1), Triple(2, "2;2", 2 to 2), Triple(2, "1;2", 1 to 2),
        )
        for ((rows, args, expected) in cases) {
            val scanner = scanner(rows, "$esc[" + args + "r")
            assertEquals("$rows rows, ESC[" + args + "r", expected, scanner.heldTop to scanner.heldBottom)
        }
    }

    @Test fun `an explicit bottom as tall as the screen is told apart from none, as the library keeps them apart`() {
        // 1;31 on thirty rows is an explicit bottom of 30 (the check for none comes before the bound), and a later resize asks for it to be taken off;
        // 1;30 is none, whatever the height becomes.
        val explicit = scanner(30, "$esc[1;31r")
        explicit.resized(40)
        assertEquals("$esc[r$cursor", String(explicit.regionFix(4, 6)!!, Charsets.ISO_8859_1))
        val none = scanner(30, "$esc[1;30r")
        none.resized(40)
        assertNull(none.regionFix(4, 6))
    }

    @Test fun `every argument of a mode counts, in any place and whatever the separator`() {
        for (args in listOf("1049", "1047", "25;1049", "1049;25", "1;2;1047;3", ";1049", "1049;", "1049:1", "25:1049", "01049", "0001047", "1049;1049", "0;0;1049")) {
            assertTrue(args, scanner(30, "$esc[?" + args + "h").onAlternate)
        }
        for (args in listOf("47", "1048", "25", "1", "1000", "2004", "10490", "104", "4", "10491", "", ";", "47;1048", "0")) {
            assertFalse(args, scanner(30, "$esc[?" + args + "h").onAlternate)
        }
    }

    @Test fun `a mode with more arguments than the scan holds is read as far as it holds them, and the scan goes on after it`() {
        // The library's own array has 16 places and its parser does not stop at the last (a longer sequence writes past it), which is not
        // modelled; what the scan has to do is not throw on one, keep what it holds and read what follows.
        val many = (1..40).joinToString(";") { "0" }
        val scanner = scanner(30, "$esc[?1049;" + many + "h$esc[3;10r")
        assertTrue(scanner.onAlternate)
        assertEquals(3, scanner.heldTop)
        assertEquals(10, scanner.heldBottom)
    }

    @Test fun `an entry is looked for among as many arguments as the scan holds, and a longer mode that has none is no entry and no error`() {
        val many = (1..40).joinToString(";") { "0" }
        assertEquals(emptyList<Int>(), entries("$esc[?" + many + "h"))
        // The first sixteen are read, the rest are not kept.
        val sixteenth = (1..15).joinToString(";") { "0" } + ";1049;" + many
        assertEquals(listOf("$esc[?".length + sixteenth.length), entries("$esc[?" + sixteenth + "h"))
        val seventeenth = (1..16).joinToString(";") { "0" } + ";1049"
        assertEquals(emptyList<Int>(), entries("$esc[?" + seventeenth + "h"))
        // Text with an h in it, where nothing is open, is not an entry either, nor is an h inside a string after one.
        assertEquals(emptyList<Int>(), entries("$esc[?1049l hello $esc]0;hello\u0007$esc[?25h"))
    }

    @Test fun `only a question mark as the one leader, and no intermediate byte, makes a mode`() {
        for (stream in listOf(
            "$esc[1049h", "$esc[>1049h", "$esc[=1049h", "$esc[<1049h", "$esc[??1049h", "$esc[?>1049h", "$esc[>?1049h",
            "$esc[?1049 h", "$esc[?1049!h", "$esc[?1049\$h", "$esc[?1049\"h", "$esc[?1049/h",
        )) {
            assertFalse(stream.visible(), scanner(30, stream).onAlternate)
        }
    }

    @Test fun `a number is its low 31 bits and starts over once it holds the value that stands for none`() {
        // 2^32 + 1049 and 2^31 + 1049.
        assertTrue(scanner(30, "$esc[?4294968345h").onAlternate)
        assertTrue(scanner(30, "$esc[?2147484697h").onAlternate)
        // 2147483647 is the library's "no argument": the next digit starts a new number.
        assertTrue(scanner(30, "$esc[?21474836471049h").onAlternate)
        // 2^31 is mode 0.
        assertFalse(scanner(30, "$esc[?2147483648h").onAlternate)
        // And that one is no argument at all, which no mode is.
        assertFalse(scanner(30, "$esc[?2147483647h").onAlternate)
    }

    @Test fun `controls run and leave the sequence open, NUL and DEL are ignored, and CAN, SUB or an ESC end it`() {
        for (stream in listOf("$esc[?10\r49h", "$esc[?\n1049h", "$esc[?1049\u0007h", "$esc[?\u0000104\u007f9h", "$esc[\r?1049h", "$esc[?1049\u0008h", "$esc[?1049\u007fh", "$esc[?1049\u0000h")) {
            assertTrue(stream.visible(), scanner(30, stream).onAlternate)
        }
        // A DEL right before the end of a sequence that has no control character before it, which a plain sequence skipped over would not see.
        for (stream in listOf("$esc[1;18\u007fr", "$esc[1;\u007f18r", "$esc[\u007f1;18r")) {
            val scanner = scanner(30, stream)
            assertEquals(stream.visible(), 1 to 18, scanner.heldTop to scanner.heldBottom)
        }
        for (stream in listOf("$esc[?1049\u0018h", "$esc[?1049\u001ah", "$esc[?1049$esc[?25h", "$esc[?1049" + esc + "Mh", "$esc[?1049é" + "h", "$esc[?10?49h", "$esc[?1049~h")) {
            assertFalse(stream.visible(), scanner(30, stream).onAlternate)
        }
        assertTrue(scanner(30, "$esc[?1049$esc[?1047h").onAlternate)
    }

    @Test fun `the origin mode counts rows from the top of the region, and a cursor that is outside it cannot be put back`() {
        val stream = "$esc[3;8r$esc[?6h$enter$esc[1;5r$leave"
        val at = "$esc[3;8r$esc[?6h$enter".length
        // The alternate screen has no region, so the row is as it is; the shell's has rows 3 to 8, and row 4 is the second of them.
        assertEquals(listOf(at to "$esc[r$cursor", stream.length to "$esc[3;8r$esc[3;7H"), fixes(30, stream))
        // Row 1 is above rows 3 to 8: the region is put right, and the cursor is left where the region puts it.
        assertEquals(listOf(at to "$esc[r$esc[2;7H", stream.length to "$esc[3;8r"), drive(WriteCuts(30), arrayOf(stream), row = 1))
        // Row 8 is the one after the last.
        assertEquals(stream.length to "$esc[3;8r", drive(WriteCuts(30), arrayOf(stream), row = 8).last())
        assertEquals(stream.length to "$esc[3;8r$esc[6;7H", drive(WriteCuts(30), arrayOf(stream), row = 7).last())
    }

    @Test fun `a region with no bottom is given back with none`() {
        val stream = "$esc[4r$enter$leave"
        assertEquals(
            listOf("$esc[4r$enter".length to "$esc[r$cursor", stream.length to "$esc[4;r$cursor"),
            fixes(30, stream),
        )
    }

    @Test fun `a region split between writes is found wherever the split falls`() {
        val stream = "ab$esc[3;8r" + enter + "draw$esc[2;5r" + leave + "$esc]8;;u\u0007x" + enter + "$esc[1;9r" + leave + " end"
        val whole = fixes(30, stream)
        assertEquals(4, whole.count { it.second.contains("r") })
        for (split in 0..stream.length) {
            assertEquals("split at $split", whole, fixes(30, stream.substring(0, split), stream.substring(split)))
        }
        for (first in 0..stream.length step 3) for (second in first..stream.length step 4) {
            assertEquals("split at $first and $second", whole, fixes(30, stream.substring(0, first), stream.substring(first, second), stream.substring(second)))
        }
    }

    @Test fun `a reset forgets what was open and what was held`() {
        val scanner = scanner(30, "$esc[3;8r$enter$esc[1")
        scanner.reset(20)
        assertEquals(1 to 20, scanner.heldTop to scanner.heldBottom)
        assertEquals(false, scanner.onAlternate)
        assertEquals(cleared(), drive(scanner, arrayOf(";2H$enter$leave")))
        // 20 rows now: a bottom of 20 is none.
        assertEquals(cleared(), drive(scanner, arrayOf("$enter$esc[1;20r$leave")))
        assertEquals(1, drive(scanner, arrayOf("$enter$esc[1;19r$leave")).size)
        // The origin mode went with the library that was in it: a new one starts with it off.
        val inOrigin = scanner(30, "$esc[?6h")
        assertTrue(inOrigin.origin)
        inOrigin.reset(30)
        assertFalse(inOrigin.origin)
    }

    @Test fun `the bytes the library is given are a region and a cursor position and nothing it could answer`() {
        val shape = Regex("\u001b\\[(\\d+;(\\d+)?)?r(\u001b\\[\\d+;\\d+H)?")
        for (region in listOf("", "3;8", "4", "1;18", "30;30")) {
            // When the alternate screen is left, and when the size changes.
            val left = drive(scanner(30, "$esc[" + region + "r$enter$esc[2;5r"), arrayOf(leave), row = 7, column = 9).single().second
            assertTrue(left, shape.matches(left))
            val resized = scanner(30, "$esc[2;5r")
            resized.resized(31)
            val fix = String(resized.regionFix(7, 9)!!, Charsets.ISO_8859_1)
            assertTrue(fix, shape.matches(fix))
        }
    }

    @Test fun `random streams are cut in the same places whatever the chunks, the array or the buffer, a byte at a time or not`() {
        val random = Random(2026)
        val pieces = listOf(
            "text", "\r\n", "$esc[31m", "$esc]8;;https://a.test/\u0007", "$esc]8;;\u0007", "$esc]8;id=7;https://b.test/$esc\\", "$esc]8;;$esc\\",
            "$esc]0;t\u0007", "$esc]133;A$esc\\", esc, "\\", "]", "8", ";", "\u0007", "\u0018", "\u001a", "X", "\u0000", "\u007f", "\u009c", "é",
            "$esc[?1049h", "$esc[?1049l", "$esc[?1047;6h", "$esc[?6l", "$esc[1;18r", "$esc[3;8r", "$esc[r", "$esc[!p", esc + "c", "$esc[?", "$esc[1;", "1049", "h", "l", "r",
            ";", ":", "$esc[2J", esc + "Pq1;2", esc + "_G", "$esc^", esc + "(", "$esc#",
        )
        repeat(500) { round ->
            val stream = bytes((1..random.nextInt(1, 40)).joinToString("") { pieces[random.nextInt(pieces.size)] })
            val whole = scan(stream, listOf(stream.size))
            val sizes = generateSequence { random.nextInt(1, 12) }.take(stream.size + 1).toList()
            assertEquals("round $round, chunked", whole, scan(stream, sizes))
            assertEquals("round $round, a byte at a time", whole, scan(stream, List(stream.size) { 1 }))
            assertEquals("round $round, direct buffer", whole, scanBuffer(stream, sizes))
        }
    }

    /**
     * The cuts of [stream] given to one scanner in chunks of the given sizes in turn, as indexes into the stream, each with the bytes an emulator
     * that asks for [WriteCuts.regionFix] at every cut writes there (null for none), and the screen of 30 rows it ends up holding a region of.
     */
    private fun scan(stream: ByteArray, sizes: List<Int>): List<Pair<Int, String?>> {
        val scanner = WriteCuts(30)
        val found = mutableListOf<Pair<Int, String?>>()
        var from = 0
        var next = 0
        while (from < stream.size) {
            val to = minOf(stream.size, from + sizes[next++ % sizes.size])
            var at = from
            while (true) {
                val cut = scanner.next(stream, at, to)
                if (cut < 0) break
                found += cut to scanner.regionFix(4, 6)?.let { String(it, Charsets.ISO_8859_1) }
                at = cut
            }
            from = to
        }
        found += stream.size to (scanner.heldTop.toString() + ";" + scanner.heldBottom + scanner.onAlternate + scanner.origin)
        return found
    }

    private fun scanBuffer(stream: ByteArray, sizes: List<Int>): List<Pair<Int, String?>> {
        val buffer = ByteBuffer.allocateDirect(stream.size + 3).apply { put(stream) }
        val scanner = WriteCuts(30)
        val found = mutableListOf<Pair<Int, String?>>()
        var from = 0
        var next = 0
        while (from < stream.size) {
            val to = minOf(stream.size, from + sizes[next++ % sizes.size])
            var at = from
            while (true) {
                val cut = scanner.next(buffer, at, to)
                if (cut < 0) break
                found += cut to scanner.regionFix(4, 6)?.let { String(it, Charsets.ISO_8859_1) }
                at = cut
            }
            from = to
        }
        found += stream.size to (scanner.heldTop.toString() + ";" + scanner.heldBottom + scanner.onAlternate + scanner.origin)
        return found
    }
}
