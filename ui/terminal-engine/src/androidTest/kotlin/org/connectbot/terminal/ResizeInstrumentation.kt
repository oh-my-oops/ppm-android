package org.connectbot.terminal

/**
 * What a resize does, on the real library (`EngineInstrumentation` runs these on a device). The JVM tests run the same scenarios against
 * [FakeVterm], a model of libvterm that is only worth anything while it does what the library does: [verifyResizeModel] runs the scripted
 * scenarios of [ResizeDiff] through the library and holds the CRC of each trace to [ResizeDiff.GOLDEN], which `ResizeModelTest` holds the
 * model to; [verifyResize] is `ResizeWrapTest` on the real emulator over the real library.
 */
internal object ResizeChecks {
    private const val ESC = "\u001b"

    private class DeviceHost(private val rec: ResizeDiff.Rec, rows: Int, cols: Int) : ResizeDiff.Host {
        private val nativeImpl = TerminalNative(rec).also { it.resize(rows, cols) }
        override val native: ScreenNative = TerminalNativeAdapter(nativeImpl)
        override val cursor: Pair<Int, Int> get() = rec.cursor
        override fun close() = nativeImpl.close()
    }

    fun verifyResizeModel() {
        val coverage = ResizeDiff.Coverage()
        val actual = ResizeDiff.SEEDS.map { seed -> ResizeDiff.checksum(ResizeDiff.trace(seed, coverage) { rec, rows, cols -> DeviceHost(rec, rows, cols) }) }
        check(actual == ResizeDiff.GOLDEN) { "The library's traces of ${ResizeDiff.SEEDS} are\n$actual\nnot\n${ResizeDiff.GOLDEN}" }
        check(coverage.withPushes >= 25 && coverage.withPops >= 25 && coverage.withWrappedPushes >= 10 && coverage.alternateWithPushes >= 8 && coverage.inColumns >= 25) {
            "The scenarios no longer reach the branches of a resize: $coverage"
        }
    }

    private class Screen(rows: Int, cols: Int) : AutoCloseable {
        val emulator = TerminalEmulatorFactory.create(initialRows = rows, initialCols = cols) as TerminalEmulatorImpl

        fun write(text: String) = emulator.writeInput(text.toByteArray(Charsets.UTF_8))

        fun resize(rows: Int, cols: Int) = emulator.resize(rows, cols, cols * 10, rows * 20)

        fun history(): List<TerminalLine> { emulator.getBufferSnapshot(); return emulator.snapshot.value.scrollback }

        fun screen(): List<TerminalLine> { emulator.getBufferSnapshot(); return emulator.snapshot.value.lines }

        override fun close() = emulator.close()
    }

    private fun List<TerminalLine>.wraps() = map { it.text.replace('\u0000', ' ').trimEnd() to it.softWrapped }

    private fun List<TerminalLine>.links() = map { line -> line.semanticSegments.filter { it.semanticType == SemanticType.HYPERLINK }.map { it.metadata } }

    private fun <T> expect(what: String, actual: T, expected: T) = check(actual == expected) { "$what: $actual, not $expected" }

    private fun link(n: Int) = "$ESC]8;;https://l$n.test/\u0007link$n$ESC]8;;\u0007"

    private val page = "xx\r\n" + "B".repeat(10) + "C".repeat(10) + "D".repeat(5) + "\r\n" + "EEEEE\r\n" + "$ "

    private val pageRows = listOf("xx" to false, "BBBBBBBBBB" to true, "CCCCCCCCCC" to true, "DDDDD" to false, "EEEEE" to false, "$" to false, "" to false, "" to false)

    fun verifyResize() {
        Screen(8, 10).use { s ->
            s.write(page)
            s.resize(4, 10)
            expect("History after a shrink that pushes two rows", s.history().wraps(), listOf("xx" to false, "BBBBBBBBBB" to true))
            expect("Screen after it", s.screen().wraps(), listOf("CCCCCCCCCC" to true, "DDDDD" to false, "EEEEE" to false, "$" to false))
            s.write("\r\nfoo")
            expect("History after a line feed", s.history().wraps(), listOf("xx" to false, "BBBBBBBBBB" to true, "CCCCCCCCCC" to true))
            expect("Screen after it", s.screen().wraps(), listOf("DDDDD" to false, "EEEEE" to false, "$" to false, "foo" to false))
        }
        Screen(8, 10).use { s ->
            s.write("xx\r\nyy\r\n" + "C".repeat(10) + "D".repeat(10) + "E".repeat(5) + "\r\n$ ")
            s.resize(4, 10)
            expect("Screen with two wrapped rows on top", s.screen().wraps(), listOf("CCCCCCCCCC" to true, "DDDDDDDDDD" to true, "EEEEE" to false, "$" to false))
            s.write("\r\n\r\nfoo")
            expect("History after two line feeds in one write", s.history().wraps(), listOf("xx" to false, "yy" to false, "CCCCCCCCCC" to true, "DDDDDDDDDD" to true))
        }
        Screen(8, 10).use { s ->
            // The rows the history gives back are read as the library holds them, which need not be the cells that were pushed: colours,
            // underline and italic, wide characters, a combining mark, truecolor.
            s.write("xx\r\n" + "$ESC[31;1m" + "B".repeat(6) + "$ESC[4;3;42m" + "C".repeat(6) + "$ESC[0m" + "ああああ" + "$ESC[38;2;1;2;3mé$ESC[0m" + "D".repeat(10) + "FF\r\nEEEEE\r\n$ ")
            val before = s.screen().wraps()
            check(before.count { it.second } >= 3) { "The scenario wraps too little: $before" }
            s.resize(4, 10)
            s.resize(8, 10)
            expect("Screen of a line in other attributes after the keyboard opened and closed", s.screen().wraps(), before)
        }
        Screen(8, 10).use { s ->
            s.write(page)
            s.resize(4, 10)
            s.resize(8, 10)
            // Every row is damaged and read again in other colours, and none is written to.
            s.emulator.setDefaultColors(0xFF102030.toInt(), 0xFFE0D0C0.toInt())
            expect("Screen after a new colour scheme", s.screen().wraps(), pageRows)
        }
        Screen(8, 10).use { s ->
            s.write(page)
            repeat(3) {
                s.resize(4, 10)
                s.resize(8, 10)
                expect("Screen after the keyboard opened and closed", s.screen().wraps(), pageRows)
                expect("History after it", s.history().wraps(), emptyList())
            }
            s.write("\r\n\r\n\r\n\r\n")
            expect("History after two scrolls", s.history().wraps(), listOf("xx" to false, "BBBBBBBBBB" to true))
            expect("Screen after them", s.screen().wraps(), listOf("CCCCCCCCCC" to true, "DDDDD" to false, "EEEEE" to false, "$" to false, "" to false, "" to false, "" to false, "" to false))
        }
        Screen(8, 10).use { s ->
            s.write(page)
            s.resize(4, 10)
            s.resize(8, 10)
            s.write("$ESC[3;1H$ESC[K")
            expect("Screen with row 2 erased", s.screen().wraps(), listOf("xx" to false, "BBBBBBBBBB" to true, "" to false, "DDDDD" to false, "EEEEE" to false, "$" to false, "" to false, "" to false))
        }
        Screen(4, 10).use { s ->
            s.write("AA\r\n" + "B".repeat(10) + "C".repeat(5))
            s.screen()
            // The screen has shown row 1 as wrapped. The write erases it, which clears its flag, and scrolls row 0 off; the next one scrolls it off.
            s.write("$ESC[2;1H$ESC[K$ESC[4;1H\r\n")
            s.write("$ESC[4;1H\r\n")
            expect("History after a row was erased and scrolled off", s.history().wraps(), listOf("AA" to false, "" to false))
        }
        Screen(4, 10).use { s ->
            s.write("AA\r\n" + "B".repeat(10) + "C".repeat(5))
            s.resize(3, 10)
            s.resize(4, 10)
            // The same line is written again from row 1 and wraps again: the library holds its flag now, and it is no longer a kept one.
            s.write("$ESC[2;1H" + "B".repeat(10) + "C".repeat(5))
            s.screen()
            s.write("$ESC[2;1H$ESC[K$ESC[4;1H\r\n")
            s.write("$ESC[4;1H\r\n")
            expect("History after a row the library had flagged was erased and scrolled off", s.history().wraps(), listOf("AA" to false, "" to false))
        }
        Screen(8, 10).use { s ->
            s.write(page)
            s.resize(4, 10)
            s.resize(8, 10)
            // One rectangle reports the rows between these two writes as damaged; the row between them still wraps, the rewritten one is not known to.
            s.write("$ESC[2;1Hzz$ESC[7;1Hlater")
            expect(
                "Screen with a row between two writes", s.screen().wraps(),
                listOf("xx" to false, "zzBBBBBBBB" to false, "CCCCCCCCCC" to true, "DDDDD" to false, "EEEEE" to false, "$" to false, "later" to false, "" to false),
            )
        }
        Screen(6, 10).use { s ->
            s.write("A".repeat(10) + "BBB\r\n$ ")
            s.resize(4, 10)
            expect("Screen shrunk by blank rows", s.screen().wraps(), listOf("AAAAAAAAAA" to true, "BBB" to false, "$" to false, "" to false))
            expect("History after it", s.history().wraps(), emptyList())
        }
        Screen(6, 10).use { s ->
            s.write("A".repeat(10) + "BBB\r\n$ ")
            s.resize(6, 12)
            expect("Screen after a resize in columns", s.screen().map { it.softWrapped }, List(6) { false })
        }
        Screen(8, 10).use { s ->
            s.write(page)
            s.resize(4, 12)
            expect("History after a resize in rows and columns", s.history().wraps(), listOf("xx" to false, "BBBBBBBBBB" to true))
            expect("Screen after it", s.screen().map { it.softWrapped }, List(4) { false })
        }
        Screen(8, 10).use { s ->
            s.write(page)
            s.resize(4, 10)
            s.resize(8, 10)
            s.write("$ESC[7;1H" + "F".repeat(10) + "GGG")
            s.resize(5, 10)
            s.resize(8, 10)
            expect("Screen with a line wrapped after the first resize", s.screen().wraps(), pageRows.take(6) + listOf("FFFFFFFFFF" to true, "GGG" to false))
        }
        verifyAlternateScreenResize()
        verifyResizedLinks()
    }

    private fun verifyAlternateScreenResize() {
        Screen(6, 10).use { s ->
            s.write("A".repeat(10) + "BBBBB\r\np2\r\np3\r\n$ ")
            s.write("$ESC[H$ESC[?1049h" + "ALTALTALTA" + "LT\r\nALT2")
            s.resize(3, 10)
            expect("History after a resize with the alternate screen up", s.history().wraps(), listOf("ALTALTALTA" to true, "LT" to false))
            expect("Screen after it", s.screen().wraps(), listOf("ALTALTALTA" to false, "LT" to false, "ALT2" to false))
        }
        Screen(4, 40).use { s ->
            s.write((0..5).joinToString("\r\n") { "h$it" } + "\r\n$ $ESC[H$ESC[?1049h" + link(1) + "\r\n" + link(2))
            expect("History before the alternate screen grew", s.history().wraps().map { it.first }, listOf("h0", "h1", "h2"))
            s.resize(6, 40)
            expect("History after it, two rows given back to the shell's screen", s.history().wraps().map { it.first }, listOf("h0"))
            val url = { n: Int -> listOf("https://l$n.test/") }
            expect("Links of the alternate screen after it", s.screen().links(), listOf(url(1), url(2), emptyList(), emptyList(), emptyList(), emptyList()))
        }
        Screen(6, 40).use { s ->
            s.write("p0\r\n\$ $ESC[H$ESC[?1049h" + "a0\r\n$ESC]133;A\u0007\$ $ESC]133;B\u0007ls\r\na2")
            s.resize(8, 40)
            expect("Prompts of the alternate screen after it grew", s.screen().map { it.hasPrompt() }, listOf(false, true, false, false, false, false, false, false))
            s.resize(4, 40)
            expect("Prompts of the alternate screen after it shrank", s.screen().map { it.hasPrompt() }, List(4) { false })
        }
        Screen(6, 40).use { s ->
            s.write("p0\r\np1\r\n$ $ESC[H$ESC[?1049h" + link(1) + "\r\n" + link(2) + "\r\n" + link(3))
            val url = { n: Int -> listOf("https://l$n.test/") }
            s.resize(8, 40)
            expect("Links of the alternate screen after it grew", s.screen().links(), listOf(url(1), url(2), url(3), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))
            s.resize(4, 40)
            expect("Links of the alternate screen after it shrank", s.screen().links(), List(4) { emptyList<String?>() })
        }
    }

    private fun verifyResizedLinks() {
        val url = { n: Int -> listOf("https://l$n.test/") }
        Screen(8, 40).use { s ->
            s.write((0..5).joinToString("") { link(it) + "\r\n" })
            s.resize(4, 40)
            expect("Links of the history after a shrink", s.history().links(), listOf(url(0), url(1), url(2)))
            expect("Links of the screen after it", s.screen().links(), listOf(url(3), url(4), url(5), emptyList()))
            s.resize(8, 40)
            expect("Links of the history after it grew back", s.history().links(), emptyList())
            expect("Links of the screen after it", s.screen().links(), listOf(url(0), url(1), url(2), url(3), url(4), url(5), emptyList(), emptyList()))
            s.write("$ESC[2;1Hother")
            expect("Links of the screen with a row overwritten", s.screen().links(), listOf(url(0), emptyList(), url(2), url(3), url(4), url(5), emptyList(), emptyList()))
        }
        Screen(8, 40).use { s ->
            s.write("a\r\nb\r\nc\r\nd\r\n$ESC]133;A\u0007\$ $ESC]133;B\u0007ls")
            s.resize(4, 40)
            expect("Prompts after a shrink", s.screen().map { it.hasPrompt() }, listOf(false, false, false, true))
            s.resize(8, 40)
            expect("Prompts after it grew back", s.screen().map { it.hasPrompt() }, listOf(false, false, false, false, true, false, false, false))
        }
        Screen(4, 40).use { s ->
            s.write("  " + link(1) + "\r\n" + " ".repeat(30) + link(2) + "\r\n")
            s.resize(4, 20)
            expect("Links after a resize in columns", s.screen().links().take(2), listOf(url(1), emptyList()))
            s.resize(4, 40)
            expect("Links after growing back", s.screen().links()[0], url(1))
        }
    }
}
