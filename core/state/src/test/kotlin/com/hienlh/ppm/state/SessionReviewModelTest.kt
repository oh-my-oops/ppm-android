package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.SessionBlockSummary
import com.hienlh.ppm.protocol.SessionFileChange
import com.hienlh.ppm.state.SessionReviewModel.WHOLE_FILE
import com.hienlh.ppm.state.SessionReviewModel.answerKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Review tab's pure half (`session-review-model.test.ts`): block states across files, where a
 * block reverted in this sitting still sits, and where focus goes after an answer.
 */
class SessionReviewModelTest {
    private val open = BlockState.OPEN
    private val kept = BlockState.KEPT
    private val reverted = BlockState.REVERTED

    private fun numbered(n: Int) = (1..n).joinToString("") { "line $it\n" }
    private fun replaceLine(text: String, at: Int, by: String) = text.split("\n").mapIndexed { i, l -> if (i == at - 1) by else l }.joinToString("\n")

    private fun file(path: String, blocks: List<Pair<String, Boolean>>?, version: String = "v1", base: String = "b1", binary: Boolean = false, reviewed: Boolean = false) =
        SessionFileChange(
            path = path, status = SessionFileChange.Status.MODIFIED, version = version, base = base, binary = binary, reviewed = reviewed,
            blocks = blocks?.map { (key, isKept) -> SessionBlockSummary(key, 1, 1, kept = isKept) },
        )

    private fun ghost(key: String, drawnVersion: String = "v0", base: String = "b1") =
        RevertedBlock(key, emptyList(), key.substringBefore('.').toInt(), key.substringBefore('.').toInt() + 4, 1, 1, base, drawnVersion)

    private fun states(r: FileReview) = r.blocks.map { it.state }

    @Test
    fun `lists every block of every file in file order, a reverted one where it sat`() {
        val a = SessionReviewModel.fileReviews(
            order = listOf("/a.ts"),
            files = listOf(file("/a.ts", listOf("2.x" to true, "40.y" to false))),
            reverted = mapOf("/a.ts" to listOf(ghost("20.z"))),
            gone = emptyMap(),
        ).single()
        assertEquals(listOf(ReviewBlockState("2.x", kept), ReviewBlockState("20.z", reverted), ReviewBlockState("40.y", open)), a.blocks)
        assertEquals(1, a.open)
    }

    @Test
    fun `shows a still-listed block as reverted only while the list predates the revert`() {
        val ghosts = mapOf("/a.ts" to listOf(ghost("2.x", drawnVersion = "v1")))
        val before = SessionReviewModel.fileReviews(listOf("/a.ts"), listOf(file("/a.ts", listOf("2.x" to false))), ghosts, emptyMap())
        assertEquals(listOf(reverted), states(before.single()))
        // A newer version still has the block: the agent wrote those lines again.
        val after = SessionReviewModel.fileReviews(listOf("/a.ts"), listOf(file("/a.ts", listOf("2.x" to false), version = "v2")), ghosts, emptyMap())
        assertEquals(listOf(open), states(after.single()))
    }

    @Test
    fun `leaves out a reverted block cut against another base`() {
        val a = SessionReviewModel.fileReviews(listOf("/a.ts"), listOf(file("/a.ts", listOf("2.x" to false), base = "b2")), mapOf("/a.ts" to listOf(ghost("20.z"))), emptyMap()).single()
        assertEquals(listOf("2.x"), a.blocks.map { it.key })
    }

    @Test
    fun `answers a file without blocks whole, and keeps a fully reverted file in its place`() {
        val reviews = SessionReviewModel.fileReviews(
            order = listOf("/gone.ts", "/logo.png", "/a.ts"),
            files = listOf(file("/a.ts", listOf("2.x" to false)), file("/logo.png", null, binary = true, reviewed = true)),
            reverted = mapOf("/gone.ts" to listOf(ghost("0.q"))),
            gone = mapOf("/gone.ts" to RevertedFile(file("/gone.ts", listOf("0.q" to false)), "")),
        )
        assertEquals(
            listOf(Triple("/gone.ts", true, listOf(reverted)), Triple("/logo.png", false, listOf(kept)), Triple("/a.ts", false, listOf(open))),
            reviews.map { Triple(it.path, it.gone, states(it)) },
        )
        assertEquals(WHOLE_FILE, reviews[1].blocks[0].key)
    }

    @Test
    fun `shows an answer on its way at once`() {
        val a = SessionReviewModel.fileReviews(
            listOf("/a.ts"), listOf(file("/a.ts", listOf("2.x" to false, "40.y" to false))), emptyMap(), emptyMap(),
            pending = mapOf(answerKey("/a.ts", "40.y") to kept),
        ).single()
        assertEquals(listOf(open, kept), states(a))
    }

    private val reviews = SessionReviewModel.fileReviews(
        order = listOf("/a.ts", "/b.ts", "/c.ts"),
        files = listOf(
            file("/a.ts", listOf("2.x" to true, "40.y" to false)),
            file("/b.ts", listOf("2.x" to true)),
            file("/c.ts", listOf("1.x" to false, "30.y" to false)),
        ),
        reverted = mapOf("/b.ts" to listOf(ghost("20.z"))),
        gone = emptyMap(),
    )

    @Test
    fun `counts blocks decided and left across files`() {
        assertEquals(ReviewProgress(total = 6, kept = 2, reverted = 1, open = 3), SessionReviewModel.reviewProgress(reviews))
        assertEquals(FileOutcome(FileOutcome.Tone.MIXED, "1 kept · 1 reverted"), SessionReviewModel.fileOutcome(reviews[1]))
    }

    @Test
    fun `moves on to the next open block - the rest of the file, the files after, then back round`() {
        assertEquals(ReviewFocus("/a.ts", "40.y"), SessionReviewModel.nextOpen(reviews, ReviewFocus("/a.ts", "2.x")))
        assertEquals(ReviewFocus("/c.ts", "1.x"), SessionReviewModel.nextOpen(reviews, ReviewFocus("/a.ts", "40.y")))
        assertEquals(ReviewFocus("/a.ts", "40.y"), SessionReviewModel.nextOpen(reviews, ReviewFocus("/c.ts", "30.y")))
        assertEquals(ReviewFocus("/a.ts", "40.y"), SessionReviewModel.nextOpen(reviews, null))
        val done = reviews.map { r -> r.copy(blocks = r.blocks.map { it.copy(state = kept) }, open = 0) }
        assertNull(SessionReviewModel.nextOpen(done, ReviewFocus("/a.ts", "2.x")))
    }

    @Test
    fun `steps through every block, files left to review first, wrapping`() {
        // b.ts is done, so it comes last.
        assertEquals(ReviewFocus("/b.ts", "2.x"), SessionReviewModel.stepBlock(reviews, ReviewFocus("/c.ts", "30.y"), 1))
        assertEquals(ReviewFocus("/b.ts", "20.z"), SessionReviewModel.stepBlock(reviews, ReviewFocus("/a.ts", "2.x"), -1))
    }

    private val base = numbered(40)
    private val agent = replaceLine(replaceLine(base, 5, "agent 5"), 30, "agent 30")
    private val first = ReviewBlocks.computeBlocks(base, agent)!!.blocks[0]
    private val second = ReviewBlocks.computeBlocks(base, agent)!!.blocks[1]

    private fun asGhost(b: ReviewBlocks.Block, undoId: String? = null, calls: List<String>? = null, drawnVersion: String = "v1") =
        RevertedBlock(b.key, b.rows, b.oldFrom, b.oldTo, b.added, b.removed, "b1", drawnVersion, undoId, calls)

    private fun blocks(m: PaneModel?) = m!!.items.filterIsInstance<PaneItem.Block>()

    @Test
    fun `draws blocks with the unchanged lines between them`() {
        val model = SessionReviewModel.paneModel(base, agent, "v1", "b1", setOf(first.key), emptyList())!!
        assertEquals(
            listOf("gap 0-1", "KEPT 0", "gap 8-26", "OPEN 1", "gap 33-40"),
            model.items.map { if (it is PaneItem.Gap) "gap ${it.from}-${it.to}" else (it as PaneItem.Block).let { b -> "${b.state} ${b.index}" } },
        )
        assertEquals(2, model.total)
        assertEquals("agent 5", model.lines[4])
        assertEquals(1, model.baseLine[0])
        assertEquals(0, model.baseLine[4])
    }

    @Test
    fun `keeps a reverted block where its lines are now, once the file is redrawn`() {
        val now = revertBlocks(base, agent, listOf(second))
        val model = SessionReviewModel.paneModel(base, now, "v2", "b1", emptySet(), listOf(asGhost(second, undoId = "u1")))!!
        assertEquals(listOf(Triple(first.key, open, 0), Triple(second.key, reverted, 1)), blocks(model).map { Triple(it.key, it.state, it.index) })
        assertEquals("u1", blocks(model)[1].undoId)
        assertEquals(emptyList<String>(), model.stale)
        // No unchanged line is drawn twice: the reverted block's lines are not in a gap too.
        val gaps = model.items.filterIsInstance<PaneItem.Gap>().sumOf { it.to - it.from }
        assertEquals(40, gaps + 7 + 7)
    }

    @Test
    fun `keeps the calls a reverted block named, so its turns stay on it`() {
        // Still in the diff on screen, which predates the revert: the list no longer names it.
        val drawn = SessionReviewModel.paneModel(base, agent, "v1", "b1", emptySet(), listOf(asGhost(second, calls = listOf("toolu_2"))))
        assertEquals(listOf(null, listOf("toolu_2")), blocks(drawn).map { it.calls })
        // Redrawn: kept in place from what was reverted.
        val now = revertBlocks(base, agent, listOf(second))
        val redrawn = SessionReviewModel.paneModel(base, now, "v2", "b1", emptySet(), listOf(asGhost(second, calls = listOf("toolu_2"))))
        assertEquals(listOf(null, listOf("toolu_2")), blocks(redrawn).map { it.calls })
    }

    @Test
    fun `shows a block as reverted while the diff on screen predates the revert`() {
        val model = SessionReviewModel.paneModel(base, agent, "v1", "b1", emptySet(), listOf(asGhost(second)))
        assertEquals(listOf(open, reverted), blocks(model).map { it.state })
    }

    @Test
    fun `lets go of a reverted block the agent wrote over, or one cut against another base`() {
        val rewritten = replaceLine(revertBlocks(base, agent, listOf(second)), 30, "agent again")
        assertEquals(listOf(second.key), SessionReviewModel.paneModel(base, rewritten, "v3", "b1", emptySet(), listOf(asGhost(second)))!!.stale)
        val moved = revertBlocks(base, agent, listOf(second))
        assertEquals(listOf(second.key), SessionReviewModel.paneModel(base, moved, "v2", "b2", emptySet(), listOf(asGhost(second)))!!.stale)
    }

    @Test
    fun `shows an answer on its way in the pane`() {
        val model = SessionReviewModel.paneModel(base, agent, "v1", "b1", emptySet(), emptyList(), pending = mapOf(first.key to kept))
        assertEquals(listOf(kept, open), blocks(model).map { it.state })
    }

    @Test
    fun `draws a file whose every change was reverted, from the text it is back to`() {
        val model = SessionReviewModel.paneModel(base, base, "", "b1", emptySet(), listOf(asGhost(first), asGhost(second)))
        assertEquals(listOf(reverted, reverted), blocks(model).map { it.state })
    }

    @Test
    fun `shows the lines that stay once a block is answered`() {
        val block = ReviewBlocks.computeBlocks("a\nold\nb\n", "a\nnew\nb\n")!!.blocks.single()
        assertEquals(listOf("a", "new", "b"), SessionReviewModel.shownRows(block.rows, kept).map { it.text })
        assertEquals(listOf("a", "old", "b"), SessionReviewModel.shownRows(block.rows, reverted).map { it.text })
        assertEquals(4, SessionReviewModel.shownRows(block.rows, open).size)
    }

    @Test
    fun `marks the changed part of a paired line, widened to whole words`() {
        val block = ReviewBlocks.computeBlocks("const limit = 5;\n", "const limit = max(5);\n")!!.blocks.single()
        assertEquals(listOf(14 until 15, 14 until 20), SessionReviewModel.changedSpans(block.rows))
        // A line with no partner is all change, which the row's colour already says.
        val lone = ReviewBlocks.computeBlocks("a\n", "a\nb\n")!!.blocks.single()
        assertEquals(listOf(null, null), SessionReviewModel.changedSpans(lone.rows))
    }
}
