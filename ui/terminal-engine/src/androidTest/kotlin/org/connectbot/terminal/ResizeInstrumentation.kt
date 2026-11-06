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

    /**
     * [verifyResizeModel] for the scenarios of [ResizeDiff.regionScript]: the one scroll region libvterm keeps for both screens, set in every shape,
     * kept across the alternate screen and a resize, and shown by a probe, which `ScrollRegionTest` relies on [FakeVterm] to do as the library does.
     * A trace that is not the golden one is in the message, for the first scenario that differs.
     */
    fun verifyRegionModel() {
        val coverage = ResizeDiff.RegionCoverage()
        val traces = ResizeDiff.REGION_SEEDS.map { seed -> ResizeDiff.regionTrace(seed, coverage) { rec, rows, cols -> DeviceHost(rec, rows, cols) } }
        val actual = traces.map { ResizeDiff.checksum(it) }
        val first = actual.indices.firstOrNull { it >= ResizeDiff.GOLDEN_REGION.size || actual[it] != ResizeDiff.GOLDEN_REGION[it] }
        check(first == null && actual.size == ResizeDiff.GOLDEN_REGION.size) {
            "The library's traces of ${ResizeDiff.REGION_SEEDS} are\n$actual\nnot\n${ResizeDiff.GOLDEN_REGION}\nThe first that differs is index $first:\n${first?.let { traces[it] }}"
        }
        check(coverage.acrossResize >= 20 && coverage.acrossAlternate >= 20 && coverage.rejected >= 20 && coverage.scrolledInside >= 20 && coverage.originMode >= 10 && coverage.regionInOrigin >= 10 && coverage.explicitBottom >= 10) {
            "The region scenarios no longer reach the corners of the region: $coverage"
        }
    }

    private class Screen(rows: Int, cols: Int) : AutoCloseable {
        val emulator = TerminalEmulatorFactory.create(initialRows = rows, initialCols = cols) as TerminalEmulatorImpl

        fun write(text: String) = emulator.writeInput(text.toByteArray(Charsets.UTF_8))

        /** What a reconnect does: the shell's buffer, written as one. */
        fun replay(text: String) = emulator.writeReplay(text.toByteArray(Charsets.UTF_8))

        fun resize(rows: Int, cols: Int) = emulator.resize(rows, cols, cols * 10, rows * 20)

        fun history(): List<TerminalLine> { emulator.getBufferSnapshot(); return emulator.snapshot.value.scrollback }

        fun screen(): List<TerminalLine> { emulator.getBufferSnapshot(); return emulator.snapshot.value.lines }

        override fun close() = emulator.close()
    }

    private fun List<TerminalLine>.wraps() = map { it.text.replace('\u0000', ' ').trimEnd() to it.softWrapped }

    private fun List<TerminalLine>.links() = map { line -> line.semanticSegments.filter { it.semanticType == SemanticType.HYPERLINK }.map { it.metadata } }

    private fun List<TerminalLine>.texts() = map { it.text.replace('\u0000', ' ').trimEnd() }

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
            // The library reads the rows it pushes from the screen it shows, which is the alternate one: the history is given the shell's own.
            expect("History after a resize with the alternate screen up", s.history().wraps(), listOf("AAAAAAAAAA" to true, "BBBBB" to false))
            expect("Screen after it", s.screen().wraps(), listOf("ALTALTALTA" to false, "LT" to false, "ALT2" to false))
        }
        verifyShellUnderAlternateScreen()
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

    private val shell = (0..9).joinToString("\r\n") { "P$it" } + "\r\n$ "
    private val shellScreen = (0..9).map { "P$it" } + "$"
    private val altScreen = "$ESC[H" + (0..9).joinToString("\r\n") { "ALT$it" }

    /**
     * `ResizeWrapTest`'s cases of the shell's screen under the alternate screen, on the real library: what a screen shrunk and grown again under the
     * alternate screen gives back (the shell's own rows, where the library alone gave back the alternate screen's), with the shell's output and the
     * entry written as one, as a replay is, and with the entry split between two writes.
     */
    private fun verifyShellUnderAlternateScreen() {
        Screen(11, 12).use { s ->
            s.write(shell)
            s.write("$ESC[?1049h$altScreen")
            s.resize(6, 12)
            expect("History after a shrink with the alternate screen up", s.history().texts(), (0..4).map { "P$it" })
            s.resize(11, 12)
            s.write("$ESC[?1049l")
            expect("Shell's screen after the alternate screen is left", s.screen().texts(), shellScreen)
            expect("History after it", s.history().texts(), emptyList())
        }
        Screen(11, 12).use { s ->
            s.replay(shell + "$ESC[?1049h$altScreen")
            s.resize(6, 12)
            s.resize(11, 12)
            s.write("$ESC[?1049l")
            expect("Shell's screen after a replay that ends on the alternate screen", s.screen().texts(), shellScreen)
        }
        for (split in 1 until "$ESC[?1049h".length) {
            Screen(11, 12).use { s ->
                s.write(shell + "$ESC[?1049h".substring(0, split))
                s.write("$ESC[?1049h".substring(split) + altScreen)
                s.resize(6, 12)
                s.resize(11, 12)
                s.write("$ESC[?1049l")
                expect("Shell's screen after an entry split after $split", s.screen().texts(), shellScreen)
            }
        }
        for ((enter, leave) in listOf("?1047h" to "?1047l", "?25;1049h" to "?1049l")) {
            Screen(11, 12).use { s ->
                s.write(shell + "$ESC[" + enter + altScreen)
                s.resize(6, 12)
                s.resize(11, 12)
                s.write("$ESC[" + leave)
                expect("Shell's screen after $enter", s.screen().texts(), shellScreen)
            }
        }
        Screen(11, 12).use { s ->
            s.write(shell + "$ESC[?1049h$altScreen")
            s.resize(8, 12)
            s.resize(4, 12)
            expect("History after two shrinks", s.history().texts(), (0..6).map { "P$it" })
            s.resize(9, 12)
            s.write("$ESC[?1049l")
            expect("Screen after the screen grew and the alternate screen was left", s.screen().texts(), (2..9).map { "P$it" } + "$")
            expect("History after it", s.history().texts(), listOf("P0", "P1"))
        }
        Screen(11, 12).use { s ->
            s.write(shell + "$ESC[?1049h$altScreen")
            s.resize(11, 9)
            s.resize(6, 9)
            s.resize(11, 12)
            s.write("$ESC[?1049l")
            expect("Shell's screen after resizes in columns under the alternate screen", s.screen().texts(), shellScreen)
        }
        Screen(6, 10).use { s ->
            s.write("a0\r\na1\r\na2\r\na3\r\na4\r\n$ ")
            s.write("$ESC[?1049h$ESC[Halt")
            s.resize(3, 10)
            s.resize(6, 10)
            s.write("$ESC[?1049l")
            s.write("b0\r\nb1")
            s.write("$ESC[?1049h$ESC[Halt")
            s.resize(3, 10)
            expect("History after the second visit", s.history().texts(), listOf("a0", "a1", "a2", "a3"))
            s.resize(6, 10)
            s.write("$ESC[?1049l")
            expect("Screen after it", s.screen().texts(), listOf("a1", "a2", "a3", "a4", "$ b0", "b1"))
        }
        Screen(8, 40).use { s ->
            s.write("A".repeat(40) + "B".repeat(5) + "\r\n" + link(1) + "\r\n" + link(2) + "\r\n" + link(3) + "\r\n$ ")
            s.write("$ESC[?1049h$ESC[Halt")
            s.resize(4, 40)
            expect("History after a shrink with the alternate screen up", s.history().wraps(), listOf("A".repeat(40) to true, "B".repeat(5) to false))
            s.resize(8, 40)
            s.resize(3, 40)
            val url = { n: Int -> listOf("https://l$n.test/") }
            expect("Wraps of the history after two more", s.history().wraps().take(3), listOf("A".repeat(40) to true, "B".repeat(5) to false, "link1" to false))
            expect("Links of the history after them", s.history().links().take(3), listOf(emptyList(), emptyList(), url(1)))
            s.resize(8, 40)
            s.write("$ESC[?1049l")
            expect("Wraps of the shell's screen after leaving the alternate screen", s.screen().wraps().take(6), listOf("A".repeat(40) to true, "B".repeat(5) to false, "link1" to false, "link2" to false, "link3" to false, "$" to false))
            expect("Links of the shell's screen after leaving the alternate screen", s.screen().links().take(6), listOf(emptyList(), emptyList(), url(1), url(2), url(3), emptyList()))
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
