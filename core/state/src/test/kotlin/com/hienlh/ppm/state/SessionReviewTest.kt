package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.SessionAnswer
import com.hienlh.ppm.protocol.SessionAnswerFile
import com.hienlh.ppm.protocol.SessionAnswerResult
import com.hienlh.ppm.protocol.SessionBlockSummary
import com.hienlh.ppm.protocol.SessionFileAnswer
import com.hienlh.ppm.protocol.SessionFileChange
import com.hienlh.ppm.protocol.SessionFileDiff
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A server holding real texts, as the session's baselines do: blocks are cut from them, kept by
 * key, reverted by putting the base's lines back (which moves the version), and undone.
 */
private class FakeReviewServer : SessionReviewBackend {
    class File(val path: String, val base: String, var text: String, var version: Int = 1, val kept: MutableSet<String> = mutableSetOf(), val binary: Boolean = false, var reviewed: Boolean = false)

    val files = linkedMapOf<String, File>()
    val answers = mutableListOf<Pair<SessionAnswer, List<SessionAnswerFile>>>()
    val diffsAsked = mutableListOf<String>()
    private val undo = HashMap<String, List<Triple<String, String, Set<String>>>>()
    private var undoCount = 0

    fun add(path: String, base: String, text: String) {
        files[path] = File(path, base, text)
    }

    fun row(f: File): SessionFileChange? {
        if (f.binary) return SessionFileChange(f.path, SessionFileChange.Status.ADDED, version = "v${f.version}", binary = true, reviewed = f.reviewed)
        val diff = ReviewBlocks.computeBlocks(f.base, f.text)!!
        if (diff.blocks.isEmpty()) return null
        return SessionFileChange(
            f.path, SessionFileChange.Status.MODIFIED, diff.additions, diff.deletions, version = "v${f.version}", base = "base-${f.path}",
            blocks = diff.blocks.map { SessionBlockSummary(it.key, it.added, it.removed, kept = it.key in f.kept, calls = listOf("call-${it.key}")) },
        )
    }

    override suspend fun list(paths: List<String>) = files.values.mapNotNull(::row)

    override suspend fun diff(path: String): SessionFileDiff {
        diffsAsked += path
        val f = files.getValue(path)
        return SessionFileDiff(row(f)!!, f.base, f.text)
    }

    override suspend fun answer(answer: SessionAnswer, files: List<SessionAnswerFile>): SessionAnswerResult {
        answers += answer to files
        val before = mutableListOf<Triple<String, String, Set<String>>>()
        val out = mutableListOf<SessionFileAnswer>()
        for (a in files) {
            val f = this.files[a.path]
            if (f == null) {
                out += SessionFileAnswer(a.path, stale = true)
                continue
            }
            if ("v${f.version}" != a.version) {
                out += SessionFileAnswer(a.path, stale = true, file = row(f))
                continue
            }
            before += Triple(f.path, f.text, f.kept.toSet())
            val blocks = if (f.binary) emptyList() else ReviewBlocks.computeBlocks(f.base, f.text)!!.blocks
            val hit = blocks.filter { a.keys == null || it.key in a.keys!! }
            when (answer) {
                SessionAnswer.KEEP -> if (f.binary) f.reviewed = true else f.kept += hit.map { it.key }
                SessionAnswer.OPEN -> f.kept -= hit.map { it.key }.toSet()
                SessionAnswer.REVERT -> {
                    f.text = revertBlocks(f.base, f.text, hit)
                    f.version++
                }
            }
            out += SessionFileAnswer(a.path, file = row(f))
        }
        // Every answer is journalled, keeps and reopens too.
        if (before.isEmpty()) return SessionAnswerResult(out)
        val id = "undo-${++undoCount}"
        undo[id] = before
        return SessionAnswerResult(out, id)
    }

    override suspend fun undo(undoId: String): SessionAnswerResult {
        val saved = undo.remove(undoId) ?: return SessionAnswerResult(emptyList(), stale = true)
        return SessionAnswerResult(
            saved.map { (path, text, kept) ->
                val f = files.getValue(path)
                f.text = text
                f.kept.clear()
                f.kept += kept
                f.version++
                SessionFileAnswer(path, file = row(f))
            },
        )
    }
}

class SessionReviewTest {
    private fun numbered(n: Int) = (1..n).joinToString("") { "line $it\n" }
    private fun replaceLine(text: String, at: Int, by: String) = text.split("\n").mapIndexed { i, l -> if (i == at - 1) by else l }.joinToString("\n")

    private val base = numbered(40)
    private val agent = replaceLine(replaceLine(base, 5, "agent 5"), 30, "agent 30")

    private fun server() = FakeReviewServer().apply {
        add("/p/a.ts", base, agent)
        add("/p/b.ts", base, replaceLine(base, 12, "agent 12"))
        add("/p/c.ts", base, replaceLine(base, 20, "agent 20"))
    }

    private fun TestScope.review(server: FakeReviewServer, onAnswered: () -> Unit = {}) =
        SessionReview(server, backgroundScope, listOf("/p/a.ts"), onAnswered, now = { testScheduler.currentTime }, compute = StandardTestDispatcher(testScheduler))
            .also { it.start(); runCurrent() }

    private val SessionReview.shown get() = state.value

    private fun SessionReviewState.blocks() = (pane as ReviewPane.Blocks).model.items.filterIsInstance<PaneItem.Block>()

    private fun SessionReviewState.states(path: String) = reviews.first { it.path == path }.blocks.map { it.state }

    @Test
    fun `opens on the first open block with its file's blocks drawn in place`() = runTest {
        val server = server()
        val review = review(server)
        val s = review.shown
        assertEquals(listOf("/p/a.ts", "/p/b.ts", "/p/c.ts"), s.reviews.map { it.path })
        assertEquals(ReviewProgress(total = 4, kept = 0, reverted = 0, open = 4), s.progress)
        val first = s.reviews[0].blocks[0].key
        assertEquals(ReviewFocus("/p/a.ts", first), s.focus)
        assertEquals(listOf("/p/a.ts"), server.diffsAsked)
        assertEquals(listOf(BlockState.OPEN, BlockState.OPEN), s.blocks().map { it.state })
        assertEquals(0 to 2, s.place)
        assertEquals("/p/b.ts", s.nextFile?.path)
    }

    @Test
    fun `a keep shows at once, moves on, and names the version drawn`() = runTest {
        val server = server()
        val marks = mutableListOf<Unit>()
        val review = review(server) { marks += Unit }
        val (first, second) = review.shown.reviews[0].blocks.map { it.key }
        review.keepBlock(first)
        // Shown before the server answers.
        assertEquals(listOf(BlockState.KEPT, BlockState.OPEN), review.shown.states("/p/a.ts"))
        assertEquals(ReviewFocus("/p/a.ts", second), review.shown.focus)
        runCurrent()
        assertEquals(listOf(SessionAnswer.KEEP to listOf(SessionAnswerFile("/p/a.ts", "v1", listOf(first)))), server.answers)
        assertEquals(listOf(BlockState.KEPT, BlockState.OPEN), review.shown.states("/p/a.ts"))
        val toast = review.shown.toast!!
        assertEquals(listOf("Kept block 1 of ", "a.ts", ""), listOf(toast.lead, toast.name, toast.tail))
        assertEquals("undo-1", toast.undoId)
        assertEquals(1, marks.size)
        // The toast goes after five seconds.
        advanceTimeBy(5_001)
        runCurrent()
        assertNull(review.shown.toast)
    }

    @Test
    fun `a reverted block stays where it sat, and a second answer follows the version the first left`() = runTest {
        val server = server()
        val review = review(server)
        val (first, second) = review.shown.reviews[0].blocks.map { it.key }
        review.revertBlock(second)
        // Answered before the revert lands: drawn at v1, sent at the version the revert leaves.
        review.setFocus(ReviewFocus("/p/a.ts", first))
        review.keepBlock(first)
        runCurrent()
        assertEquals(
            listOf(
                SessionAnswer.REVERT to listOf(SessionAnswerFile("/p/a.ts", "v1", listOf(second))),
                SessionAnswer.KEEP to listOf(SessionAnswerFile("/p/a.ts", "v2", listOf(first))),
            ),
            server.answers,
        )
        assertEquals(listOf(BlockState.KEPT, BlockState.REVERTED), review.shown.states("/p/a.ts"))
        // Back on the file: its text moved, so its diff is asked for again and the reverted block placed in it.
        review.setFocus(ReviewFocus("/p/a.ts", second))
        runCurrent()
        val s = review.shown
        assertEquals("v2", (s.pane as ReviewPane.Blocks).version)
        val drawn = s.blocks()
        assertEquals(listOf(BlockState.KEPT, BlockState.REVERTED), drawn.map { it.state })
        assertEquals("undo-1", drawn[1].undoId)
        assertEquals(1 to 2, s.place)
        assertTrue(s.canChange)
    }

    @Test
    fun `undo puts a revert back and returns focus to the block`() = runTest {
        val server = server()
        val review = review(server)
        val second = review.shown.reviews[0].blocks[1].key
        review.revertBlock(second)
        runCurrent()
        val toast = review.shown.toast!!
        assertEquals(listOf("Reverted block 2 of ", "a.ts", ""), listOf(toast.lead, toast.name, toast.tail))
        review.undo(toast.undoId!!, toast.focus)
        assertNull(review.shown.toast)
        runCurrent()
        val s = review.shown
        assertEquals(listOf(BlockState.OPEN, BlockState.OPEN), s.states("/p/a.ts"))
        assertEquals(ReviewFocus("/p/a.ts", second), s.focus)
        assertEquals(agent, server.files.getValue("/p/a.ts").text)
        assertEquals(listOf(BlockState.OPEN, BlockState.OPEN), s.blocks().map { it.state })
    }

    @Test
    fun `a file the agent changed before the answer reached it is shown again`() = runTest {
        val server = server()
        val review = review(server)
        val first = review.shown.reviews[0].blocks[0].key
        // The agent writes again while the diff on screen is still the old one.
        server.files.getValue("/p/a.ts").apply {
            text = replaceLine(text, 30, "agent 30 again")
            version++
        }
        review.revertBlock(first)
        runCurrent()
        val toast = review.shown.toast!!
        assertEquals(listOf("", "a.ts", " changed before the answer reached it, so it is shown again as it is now."), listOf(toast.lead, toast.name, toast.tail))
        assertNull(toast.undoId)
        // Nothing was reverted: the block is open again, and the newer text is drawn.
        assertEquals(agent.replace("agent 30", "agent 30 again"), server.files.getValue("/p/a.ts").text)
        assertEquals(BlockState.OPEN, review.shown.states("/p/a.ts")[0])
        assertTrue(review.shown.reviews[0].blocks.none { it.state == BlockState.REVERTED })
    }

    @Test
    fun `a list asked for before an answer landed is dropped`() = runTest {
        val server = server()
        val review = review(server)
        val first = review.shown.reviews[0].blocks[0].key
        val stale = server.list(emptyList())
        advanceTimeBy(10)
        review.keepBlock(first)
        runCurrent()
        review.takeLive(stale, listOf("/p/x.ts"), askedAt = 0)
        assertEquals(BlockState.KEPT, review.shown.states("/p/a.ts")[0])
        advanceTimeBy(10)
        review.takeLive(server.list(emptyList()), listOf("/p/x.ts"), askedAt = testScheduler.currentTime)
        assertEquals(BlockState.KEPT, review.shown.states("/p/a.ts")[0])
    }

    @Test
    fun `reverting a whole file keeps it in its place once the server forgets it`() = runTest {
        val server = server()
        val review = review(server)
        review.revertFile()
        runCurrent()
        val s = review.shown
        val a = s.reviews.first { it.path == "/p/a.ts" }
        assertTrue(a.gone)
        assertEquals(listOf(BlockState.REVERTED, BlockState.REVERTED), a.blocks.map { it.state })
        assertEquals(base, server.files.getValue("/p/a.ts").text)
        assertEquals(listOf("Reverted ", "a.ts"), listOf(s.toast!!.lead, s.toast!!.name))
        // Focus went on to the next file; the gone one still draws from the text it is back to.
        assertEquals("/p/b.ts", s.focus?.path)
        review.focusFile("/p/a.ts")
        assertEquals(listOf(BlockState.REVERTED, BlockState.REVERTED), review.shown.blocks().map { it.state })
        // Undo lists it again.
        review.undo(s.toast!!.undoId!!, null)
        runCurrent()
        assertFalse(review.shown.reviews.first { it.path == "/p/a.ts" }.gone)
    }

    @Test
    fun `keep all keeps every open block and then has nothing in focus`() = runTest {
        val server = server()
        val review = review(server)
        review.keepAll()
        assertNull(review.shown.focus)
        runCurrent()
        val s = review.shown
        assertEquals(ReviewProgress(total = 4, kept = 4, reverted = 0, open = 0), s.progress)
        assertNull(s.focus)
        assertEquals("Kept 4 blocks", s.toast!!.lead)
        assertEquals(3, server.answers.single().second.size)
    }

    @Test
    fun `the changes bar selects a file, once per ask`() = runTest {
        val server = server()
        val review = review(server)
        review.select("/p/c.ts", 1)
        assertEquals("/p/c.ts", review.shown.focus?.path)
        review.focusFile("/p/b.ts")
        review.select("/p/c.ts", 1)
        assertEquals("/p/b.ts", review.shown.focus?.path)
        review.select("/p/c.ts", 2)
        assertEquals("/p/c.ts", review.shown.focus?.path)
    }

    @Test
    fun `stepping walks every block in the rail's order`() = runTest {
        val server = server()
        val review = review(server)
        val keys = review.shown.reviews.flatMap { r -> r.blocks.map { ReviewFocus(r.path, it.key) } }
        review.step(1)
        assertEquals(keys[1], review.shown.focus)
        review.step(1)
        assertEquals(keys[2], review.shown.focus)
        review.step(-1)
        review.step(-1)
        review.step(-1)
        assertEquals(keys[3], review.shown.focus)
    }

    @Test
    fun `a binary file is answered whole and its revert is confirmed first`() = runTest {
        val server = FakeReviewServer().apply { files["/p/logo.png"] = FakeReviewServer.File("/p/logo.png", "", "", binary = true) }
        val review = review(server)
        val s = review.shown
        assertTrue(s.pane is ReviewPane.Whole)
        assertEquals(ReviewFocus("/p/logo.png", SessionReviewModel.WHOLE_FILE), s.focus)
        assertFalse(review.revertFocused())
        review.keepFocused()
        runCurrent()
        assertEquals(listOf(SessionAnswer.KEEP to listOf(SessionAnswerFile("/p/logo.png", "v1", null))), server.answers)
        assertEquals(listOf(BlockState.KEPT), review.shown.states("/p/logo.png"))
    }

    @Test
    fun `change reopens a kept block and undoes a reverted one`() = runTest {
        val server = server()
        val review = review(server)
        val (first, second) = review.shown.reviews[0].blocks.map { it.key }
        review.keepBlock(first)
        runCurrent()
        review.setFocus(ReviewFocus("/p/a.ts", first))
        assertTrue(review.shown.canChange)
        review.changeFocused()
        runCurrent()
        assertEquals(SessionAnswer.OPEN to listOf(SessionAnswerFile("/p/a.ts", "v1", listOf(first))), server.answers.last())
        assertEquals(listOf("Block 1 of ", "a.ts", " is open again"), review.shown.toast!!.let { listOf(it.lead, it.name, it.tail) })
        review.revertBlock(second)
        runCurrent()
        review.setFocus(ReviewFocus("/p/a.ts", second))
        runCurrent()
        assertEquals(BlockState.REVERTED, review.shown.focusState)
        assertEquals("undo-3", review.shown.focusedUndoId)
        review.changeFocused()
        runCurrent()
        assertEquals(agent, server.files.getValue("/p/a.ts").text)
        assertEquals(listOf(BlockState.OPEN, BlockState.OPEN), review.shown.states("/p/a.ts"))
    }
}
