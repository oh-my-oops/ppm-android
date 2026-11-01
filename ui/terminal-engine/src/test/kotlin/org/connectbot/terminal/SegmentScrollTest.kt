package org.connectbot.terminal

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.spy
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which row a link is on after the screen scrolled. libvterm is set to merge the scrolls of one write
 * (`VTERM_DAMAGE_SCROLL`): it sends each row it pushes into the history, and every OSC with the cursor's row, as they happen, but
 * the one `moverect` for all of them only when the write is flushed. The engine kept a link on the row of its cursor in the rows
 * it had *before* the write, so a write that scrolled gave every row it pushed the top row's links, and put a link written after
 * a scroll on a row that was about to move. Nothing showed it in an interactive shell, which writes one line at a time; `ls
 * --hyperlink` and anything faster than a frame did, and the Links work of a terminal that is always full and scrolling is exactly
 * that.
 *
 * The real emulator runs here and the callbacks come in the order libvterm sends them, from `screen.c` (`moverect_internal` pushes,
 * `screen_scrollrect` merges, `vterm_screen_flush_damage` ends the write with the one `moverect`); only the parse is missing, since
 * the JNI cannot load on the JVM. `EngineInstrumentation.verifyLinks` runs the same writes through the real library on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SegmentScrollTest {
    private val rows = 6
    private val columns = 40

    private val emulator: TerminalEmulatorImpl = spy(TerminalEmulatorFactory.create(initialRows = rows, initialCols = columns) as TerminalEmulatorImpl).also {
        // The snapshot worker would reach for libvterm's JNI. What these tests read is the state the callbacks leave behind.
        doAnswer { null }.`when`(it).processPendingUpdates()
    }

    private fun cells(text: String): ByteBuffer {
        val buffer = CellData.buffer()
        for (col in 0 until columns) {
            val base = col * CellData.BYTES
            for (slot in 0 until CellData.STRIDE) buffer.putInt(base + slot * 4, 0)
            buffer.putInt(base, text.getOrElse(col) { ' ' }.code)
            buffer.putInt(base + CellData.WIDTH, 1)
        }
        return buffer
    }

    /** `ESC ] 8 ; ; url BEL text ESC ] 8 ; ; BEL` with the cursor at [row] and [col] where the text starts. */
    private fun link(row: Int, col: Int, text: String, url: String) {
        emulator.onTextFragment(TerminalTextDecoder.OSC, 8, ";$url".toByteArray(), true, true, row, col)
        emulator.onTextFragment(TerminalTextDecoder.OSC, 8, ";".toByteArray(), true, true, row, col + text.length)
    }

    /** libvterm's `moverect_internal` for one row scrolled off the top: the row's cells go to the history. */
    private fun push(text: String) {
        emulator.pushScrollbackLine(columns, 0, columns, cells(text), false)
    }

    /** A row of [count] cells going into the history, which is not the width the links on the screen were made for. */
    private fun pushNarrow(text: String, count: Int) {
        emulator.pushScrollbackLine(count, 0, count, cells(text), false)
    }

    /** The one `moverect` of a flushed write that scrolled the whole screen by [by] rows. */
    private fun flush(by: Int) {
        emulator.moverect(TermRect(0, rows - by, 0, columns), TermRect(by, rows, 0, columns))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T {
        val field = TerminalEmulatorImpl::class.java.getDeclaredField(name).apply { isAccessible = true }
        return field.get(emulator) as T
    }

    private fun urls(lines: Collection<TerminalLine>) = lines.map { line -> line.semanticSegments.map { it.metadata } }

    private fun screen() = urls(field<List<TerminalLine>>("currentLines"))

    private fun history() = urls(field<Collection<TerminalLine>>("scrollback"))

    private val none = emptyList<String>()

    @Test fun `every row a write pushes into the history keeps its own links, and the top row's are not given to the others`() {
        link(0, 0, "linkA", "https://a.test/")
        link(2, 0, "linkC", "https://c.test/")
        // Four scrolls in one write: the rows go to the history one by one, the rows move once, at the flush.
        push("linkA"); push("plain1"); push("linkC"); push("plain3")
        flush(4)
        assertEquals(listOf(listOf("https://a.test/"), none, listOf("https://c.test/"), none), history())
        assertEquals(List(rows) { none }, screen())
    }

    @Test fun `a link written after the screen scrolled is on the row it was written on, not on a row that was about to move`() {
        // The cursor stays on the bottom row while the text scrolls up under it.
        link(5, 0, "first", "https://first.test/")
        push("old0")
        link(5, 0, "second", "https://second.test/")
        push("old1")
        link(5, 0, "third", "https://third.test/")
        flush(2)
        assertEquals(listOf(none, none, none, listOf("https://first.test/"), listOf("https://second.test/"), listOf("https://third.test/")), screen())
        assertEquals(listOf(none, none), history())
    }

    @Test fun `a link on the screen goes up with its text when a write scrolls it, and the rows it leaves behind have none`() {
        link(3, 0, "keep", "https://keep.test/")
        link(5, 0, "end", "https://end.test/")
        push("row0"); push("row1")
        flush(2)
        assertEquals(listOf(none, listOf("https://keep.test/"), none, listOf("https://end.test/"), none, none), screen())
    }

    @Test fun `rows written and scrolled off inside one write have their links in the history, in the order they scrolled`() {
        // Eight link rows on a six-row screen, the way the shell wrote them: six fill it, each of the next scrolls the top row off.
        for (n in 0..5) link(n, 0, "link$n", "https://l$n.test/")
        push("link0"); link(5, 0, "link6", "https://l6.test/")
        push("link1"); link(5, 0, "link7", "https://l7.test/")
        push("link2")
        flush(3)
        assertEquals((0..2).map { listOf("https://l$it.test/") }, history())
        assertEquals((3..7).map { listOf("https://l$it.test/") } + listOf(none), screen())
    }

    @Test fun `a write that scrolled the whole screen away leaves no link behind on the rows it replaced`() {
        for (n in 0..5) link(n, 0, "link$n", "https://l$n.test/")
        // More scrolls than the screen has rows: libvterm erases the lot and sends no moverect at all.
        for (n in 0..5) push("link$n")
        push("blank1"); push("blank2")
        emulator.endScrollBatch()
        assertEquals(List(rows) { none }, screen())
        assertEquals((0..5).map { listOf("https://l$it.test/") } + listOf(none, none), history())
    }

    @Test fun `the pushes of one write are not counted against the next write's scroll`() {
        link(0, 0, "link0", "https://l0.test/")
        for (n in 0..7) push("row$n")
        emulator.endScrollBatch()
        // The next write scrolls by one and flushes; its link is on the row it was written on.
        link(5, 0, "later", "https://later.test/")
        push("row8")
        link(5, 0, "latest", "https://latest.test/")
        flush(1)
        assertEquals(listOf(none, none, none, none, listOf("https://later.test/"), listOf("https://latest.test/")), screen())
    }

    @Test fun `a flushed write's pushes are not counted against the next flushed write`() {
        link(5, 0, "one", "https://one.test/")
        push("row0")
        flush(1)
        // The first write is over. This one scrolls by one as well, and writes a link after it.
        link(5, 0, "two", "https://two.test/")
        push("row1")
        link(5, 0, "three", "https://three.test/")
        flush(1)
        assertEquals(listOf(none, none, none, listOf("https://one.test/"), listOf("https://two.test/"), listOf("https://three.test/")), screen())
    }

    private fun pendingRows(): List<Int> = field<List<Any>>("pendingSemanticSegments").map { item ->
        item.javaClass.getDeclaredMethod("getRow").apply { isAccessible = true }.invoke(item) as Int
    }

    @Test fun `a link still waiting for its text is on the row its text moved to, and one whose row scrolled off is not waiting any more`() {
        link(0, 0, "top", "https://top.test/")
        link(5, 0, "bottom", "https://bottom.test/")
        assertEquals(listOf(0, 5), pendingRows())
        push("top")
        push("row1")
        assertEquals(listOf(3), pendingRows())
    }

    @Test fun `a link whose text is no longer on the row that scrolled off is not carried into the history`() {
        link(0, 0, "gone", "https://gone.test/")
        // The row was overwritten with something shorter before it scrolled, so nothing is under the link's columns.
        push("")
        link(5, 0, "kept", "https://kept.test/")
        flush(1)
        assertEquals(listOf(none), history())
        assertEquals(List(rows - 1) { none } + listOf(listOf("https://kept.test/")), screen())
    }

    @Test fun `a link that reaches past the cells of the row that scrolled off is not carried into the history`() {
        link(0, 30, "tail", "https://tail.test/")
        // The row that goes to the history has 20 cells, and the link was made for columns 30 to 34 of a wider one.
        pushNarrow("", 20)
        assertEquals(listOf(none), history())
    }

    @Test fun `a scroll that is not the screen's is not guessed at, the links the screen holds are dropped`() {
        link(1, 0, "one", "https://one.test/")
        link(2, 0, "two", "https://two.test/")
        // Two rows pushed but the screen moved one: the write also scrolled the other way, which libvterm merges into the same flush.
        push("row0"); push("one")
        flush(1)
        assertEquals(List(rows) { none }, screen())
        assertEquals(listOf(none, listOf("https://one.test/")), history())
    }

    @Test fun `a scroll region that stops above the last row is not guessed at either, the rows under it lose their links`() {
        link(0, 0, "top", "https://top.test/")
        link(5, 0, "status", "https://status.test/")
        // A region of five rows: one row pushed, rows 1 to 4 move up, the status line on row 5 stays. Moving every row's links up
        // by the push put the status line's on row 4, inside the region.
        push("top")
        emulator.moverect(TermRect(0, 4, 0, columns), TermRect(1, 5, 0, columns))
        assertEquals(List(rows) { none }, screen())
        assertEquals(listOf(listOf("https://top.test/")), history())
    }

    /** What the emulator remembers of the text a link was made for: set here as `applySemanticSegment` sets it once the cells are read. */
    private fun rememberText(row: Int, segment: SemanticSegment, text: String) {
        val key = Class.forName("org.connectbot.terminal.SemanticSegmentKey")
            .getDeclaredConstructor(Int::class.javaPrimitiveType, SemanticSegment::class.java)
            .apply { isAccessible = true }
            .newInstance(row, segment)
        field<MutableMap<Any, String>>("semanticSegmentTexts")[key] = text
    }

    private fun segmentOf(row: Int) = field<List<TerminalLine>>("currentLines")[row].semanticSegments.single()

    @Test fun `the text a link was made for moves up with the link, so a row that was rewritten before it scrolled off loses it`() {
        link(2, 0, "keep", "https://keep.test/")
        rememberText(2, segmentOf(2), "keep")
        push("row0")
        push("row1")
        // The text remembered for the link's row went up with it: row 2 is on top now. It scrolls off as something else.
        push("other")
        assertEquals(listOf(none, none, none), history())
    }

    @Test fun `the text a link was made for is still matched after it moved up, so the row scrolls off with its link`() {
        link(2, 0, "keep", "https://keep.test/")
        rememberText(2, segmentOf(2), "keep")
        push("row0")
        push("row1")
        push("keep")
        assertEquals(listOf(none, none, listOf("https://keep.test/")), history())
    }

    @Test fun `the rows a resize pushes are not a scroll, each has its own links and the screen's stay where they were`() {
        link(0, 0, "zero", "https://zero.test/")
        link(1, 0, "one", "https://one.test/")
        link(4, 0, "four", "https://four.test/")
        val resizing = TerminalEmulatorImpl::class.java.getDeclaredField("resizePushes").apply { isAccessible = true }
        resizing.setInt(emulator, 0)
        push("zero")
        push("one")
        resizing.setInt(emulator, -1)
        assertEquals(listOf(listOf("https://zero.test/"), listOf("https://one.test/")), history())
        assertEquals(listOf(listOf("https://zero.test/"), listOf("https://one.test/"), none, none, listOf("https://four.test/"), none), screen())
    }

    @Test fun `each row a resize pushes is checked against the text its own link was made for`() {
        link(0, 0, "zero", "https://zero.test/")
        link(1, 0, "one", "https://one.test/")
        rememberText(0, segmentOf(0), "zero")
        rememberText(1, segmentOf(1), "one")
        val resizing = TerminalEmulatorImpl::class.java.getDeclaredField("resizePushes").apply { isAccessible = true }
        resizing.setInt(emulator, 0)
        push("zero")
        // The second row was written over before the screen shrank: nothing of "one" is under its link any more.
        push("other")
        resizing.setInt(emulator, -1)
        assertEquals(listOf(listOf("https://zero.test/"), none), history())
    }

    @Test fun `a scroll that pushed nothing moves the links with the cells as it always did`() {
        // The alternate screen, or a region that does not start at the top: libvterm pushes no row there.
        link(3, 0, "keep", "https://keep.test/")
        emulator.moverect(TermRect(2, 4, 0, columns), TermRect(3, 5, 0, columns))
        assertEquals(listOf(none, none, listOf("https://keep.test/"), none, none, none), screen())
        assertEquals(emptyList<List<String?>>(), history())
    }
}
