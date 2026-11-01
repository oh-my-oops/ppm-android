package org.connectbot.terminal

import java.nio.ByteBuffer
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a write is cut: just past the end of every OSC 8, in whatever pieces the bytes arrive. */
class HyperlinkCutsTest {
    private val esc = "\u001b"

    private fun bytes(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    /** The index in the whole stream just past every OSC 8, the chunks given one after another to one scanner. */
    private fun cuts(vararg chunks: String): List<Int> {
        val scanner = HyperlinkCuts()
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
        val scanner = HyperlinkCuts()
        assertEquals(data.size, scanner.next(data, 0, data.size))
    }

    @Test fun `NUL, DEL and other control characters inside a string do not end it`() {
        assertEquals(listOf("$esc]8;;u\u0000\u007f\r\nv\u0007".length), cuts("$esc]8;;u\u0000\u007f\r\nv\u0007"))
        assertEquals(listOf("$esc]8;;u$esc\r\\".length), cuts("$esc]8;;u$esc\r\\"))
    }

    @Test fun `an OSC started inside a string is taken as an OSC, which costs a cut at worst`() {
        // libvterm cancels the first string at the ESC and does not start the second; the scan cuts at the BEL anyway.
        assertEquals(listOf("$esc]0;title$esc]8;;u\u0007".length), cuts("$esc]0;title$esc]8;;u\u0007"))
    }

    @Test fun `a half-read sequence is forgotten by reset`() {
        val scanner = HyperlinkCuts()
        val first = bytes("$esc]8;;https://a.te")
        assertEquals(-1, scanner.next(first, 0, first.size))
        scanner.reset()
        val second = bytes("st/\u0007")
        assertEquals(-1, scanner.next(second, 0, second.size))
    }

    @Test fun `a scan that stops at a cut goes on from the byte after it`() {
        val data = bytes("$esc]8;;a\u0007xy$esc]8;;\u0007z")
        val scanner = HyperlinkCuts()
        val first = scanner.next(data, 0, data.size)
        assertEquals("$esc]8;;a\u0007".length, first)
        val second = scanner.next(data, first, data.size)
        assertEquals("$esc]8;;a\u0007xy$esc]8;;\u0007".length, second)
        assertEquals(-1, scanner.next(data, second, data.size))
    }

    @Test fun `a scan looks only inside the range it is given`() {
        val data = bytes("$esc]8;;a\u0007")
        val scanner = HyperlinkCuts()
        assertEquals(-1, scanner.next(data, 0, data.size - 1))
        // The BEL was not given, so it comes next.
        assertEquals(data.size, scanner.next(data, data.size - 1, data.size))
    }

    @Test fun `random streams are cut in the same places whatever the chunks, the array or the buffer, a byte at a time or not`() {
        val random = Random(2026)
        val pieces = listOf(
            "text", "\r\n", "$esc[31m", "$esc]8;;https://a.test/\u0007", "$esc]8;;\u0007", "$esc]8;id=7;https://b.test/$esc\\", "$esc]8;;$esc\\",
            "$esc]0;t\u0007", "$esc]133;A$esc\\", esc, "\\", "]", "8", ";", "\u0007", "\u0018", "\u001a", "X", "\u0000", "\u007f", "\u009c", "é",
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

    /** The cuts of [stream] given to one scanner in chunks of the given sizes in turn, as indexes into the stream. */
    private fun scan(stream: ByteArray, sizes: List<Int>): List<Int> {
        val scanner = HyperlinkCuts()
        val found = mutableListOf<Int>()
        var from = 0
        var next = 0
        while (from < stream.size) {
            val to = minOf(stream.size, from + sizes[next++ % sizes.size])
            var at = from
            while (true) {
                val cut = scanner.next(stream, at, to)
                if (cut < 0) break
                found += cut
                at = cut
            }
            from = to
        }
        return found
    }

    private fun scanBuffer(stream: ByteArray, sizes: List<Int>): List<Int> {
        val buffer = ByteBuffer.allocateDirect(stream.size + 3).apply { put(stream) }
        val scanner = HyperlinkCuts()
        val found = mutableListOf<Int>()
        var from = 0
        var next = 0
        while (from < stream.size) {
            val to = minOf(stream.size, from + sizes[next++ % sizes.size])
            var at = from
            while (true) {
                val cut = scanner.next(buffer, at, to)
                if (cut < 0) break
                found += cut
                at = cut
            }
            from = to
        }
        return found
    }
}
