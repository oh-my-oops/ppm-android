package com.hienlh.ppm.state.git

import com.hienlh.ppm.client.ChangeBlock
import com.hienlh.ppm.client.ChangeLine
import com.hienlh.ppm.state.ReviewBlocks
import com.hienlh.ppm.state.editor.buildSideBySideDiffEntries
import com.hienlh.ppm.state.git.GitReviewSideBySide.Hunk
import com.hienlh.ppm.state.git.GitReviewSideBySide.Item
import com.hienlh.ppm.state.git.GitReviewSideBySide.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the Review changes tab puts each block's bar when it shows the whole file side by side:
 * blocks from the staged diff, the open diff and the tab's discards, all placed in working-tree
 * lines (`tests/unit/web/git-review-side-by-side.test.ts`).
 */
class GitReviewSideBySideTest {
    private fun lines(spec: String): List<ChangeLine> = spec.split("|").map { ChangeLine(kind = it.substring(0, 1), text = it.substring(1)) }

    private fun block(
        key: String,
        side: String,
        state: String? = null,
        oldStart: Int,
        newStart: Int,
        spec: String? = null,
        whole: String? = null,
    ): GitReviewBlock {
        val ls = spec?.let(::lines).orEmpty()
        val oldLines = ls.count { it.kind != "+" }
        val newLines = ls.count { it.kind != "-" }
        return GitReviewBlock(
            key = key,
            state = state ?: if (side == "staged") "staged" else "open",
            side = side,
            hunk = if (whole != null) null else ChangeBlock(key, 0, oldStart, oldLines, newStart, newLines, 0, 0),
            whole = whole,
            parts = spec?.let { listOf(BlockPart(oldStart, newStart, ls)) },
            anchor = 0.0,
            first = 0,
            last = 0,
            added = 0,
            removed = 0,
        )
    }

    // Index lines 10-12 replaced by 4 lines (worktree 10-13), and 2 lines inserted after index line 30.
    private val hunks = listOf(
        Hunk(9, 5, 9, 6, lines(" a|-b|-c|-d|+B|+C|+D|+E| e")),
        Hunk(30, 0, 32, 2),
    )

    @Test
    fun `indexToWorktree leaves lines before any change where they are`() {
        assertEquals(5, GitReviewSideBySide.indexToWorktree(5, hunks))
    }

    @Test
    fun `indexToWorktree shifts lines after a change by what it added`() {
        assertEquals(21, GitReviewSideBySide.indexToWorktree(20, hunks))
        assertEquals(31, GitReviewSideBySide.indexToWorktree(30, hunks))
        assertEquals(34, GitReviewSideBySide.indexToWorktree(31, hunks))
    }

    @Test
    fun `indexToWorktree maps a line inside a hunk exactly when its lines are known`() {
        assertEquals(9, GitReviewSideBySide.indexToWorktree(9, hunks))
        // A removed line maps to where it was: the next line of the working tree.
        assertEquals(10, GitReviewSideBySide.indexToWorktree(10, hunks))
        assertEquals(14, GitReviewSideBySide.indexToWorktree(13, hunks))
    }

    @Test
    fun `puts an open block's bar at its first changed line, in working-tree numbers`() {
        val open = block("u:1", "unstaged", oldStart = 4, newStart = 4, spec = " a| b| c|-d|+D| e")
        assertEquals(listOf(Place("u:1", 7, 4, 8)), GitReviewSideBySide.placeBlocks(listOf(open)))
    }

    @Test
    fun `puts a removal-only block's bar on the line that now follows it`() {
        val open = block("u:1", "unstaged", oldStart = 1, newStart = 1, spec = " a|-b|-c| d")
        assertEquals(2, GitReviewSideBySide.placeBlocks(listOf(open))[0].line)
    }

    @Test
    fun `moves a staged block below an open one by the lines the open one added`() {
        // Open: 2 lines added near the top of the working tree. Staged: changes index line 20.
        val open = block("u:1", "unstaged", oldStart = 2, newStart = 2, spec = " a|+x|+y| b")
        val staged = block("s:1", "staged", oldStart = 18, newStart = 18, spec = " p| q|-r|+R| s")
        assertEquals(Place("s:1", 22, 20, 23), GitReviewSideBySide.placeBlocks(listOf(open, staged))[1])
    }

    @Test
    fun `places a discarded block by its index lines, the working tree having taken them back`() {
        val open = block("u:1", "unstaged", oldStart = 2, newStart = 2, spec = " a|+x| b")
        val gone = block("d:r:0", "unstaged", state = "discarded", oldStart = 40, newStart = 40, spec = " k|-l|+L| m")
        assertEquals(Place("d:r:0", 42, 41, 43), GitReviewSideBySide.placeBlocks(listOf(open, gone))[1])
    }

    @Test
    fun `keeps an open block being staged in the working tree's numbers until git answers`() {
        val moving = block("u:1", "unstaged", state = "staged", oldStart = 10, newStart = 12, spec = "-a|+A")
        assertEquals(Place("u:1", 12, 12, 12), GitReviewSideBySide.placeBlocks(listOf(moving))[0])
    }

    @Test
    fun `puts a deleted file's one block at the top`() {
        val deleted = block("u:whole", "unstaged", oldStart = 0, newStart = 0, whole = "deleted")
        assertEquals(1, GitReviewSideBySide.placeBlocks(listOf(deleted))[0].line)
    }

    @Test
    fun `finds the block covering a line, preferring an open one where a staged one overlaps`() {
        val staged = block("s:1", "staged", oldStart = 10, newStart = 10, spec = " a| b|-c|+C| d| e")
        val open = block("u:1", "unstaged", oldStart = 12, newStart = 12, spec = " C|-d|+D| e")
        val blocks = listOf(staged, open)
        val places = GitReviewSideBySide.placeBlocks(blocks)
        assertEquals("u:1", GitReviewSideBySide.blockAtLine(blocks, places, 13))
        assertEquals("s:1", GitReviewSideBySide.blockAtLine(blocks, places, 10))
        assertNull(GitReviewSideBySide.blockAtLine(blocks, places, 40))
    }

    @Test
    fun `takes line blocks and a deleted file, and leaves binary, renames and the like to the block list`() {
        assertTrue(GitReviewSideBySide.supported(listOf(block("u:1", "unstaged", oldStart = 1, newStart = 1, spec = "-a|+b"))))
        assertTrue(GitReviewSideBySide.supported(listOf(block("u:whole", "unstaged", oldStart = 0, newStart = 0, whole = "deleted"))))
        for (whole in listOf("binary", "rename", "large", "submodule", "mode", "empty")) {
            assertFalse(whole, GitReviewSideBySide.supported(listOf(block("s:whole", "staged", oldStart = 0, newStart = 0, whole = whole))))
        }
        assertFalse(GitReviewSideBySide.supported(emptyList()))
    }

    private fun entries(original: String, modified: String) =
        buildSideBySideDiffEntries(ReviewBlocks.computeBlocks(original, modified), original, modified, emptyMap())

    private fun describe(items: List<Item>): List<String> = items.map {
        when (it) {
            is Item.Bar -> "bar ${it.blockKey}"
            is Item.Gap -> "gap ${it.gap.fromNew + 1}+${it.gap.count}"
            is Item.Row -> "row ${it.row.leftLineNumber ?: "-"}/${it.row.rightLineNumber ?: "-"}"
        }
    }

    @Test
    fun `layout puts a bar above its first changed line, above the removed side of the change too`() {
        val original = (1..20).joinToString("") { "line $it\n" }
        val modified = original.replace("line 10\n", "LINE 10\n")
        val items = GitReviewSideBySide.layout(entries(original, modified), listOf(Place("u:1", 10, 7, 13)))
        val described = describe(items)
        val bar = described.indexOf("bar u:1")
        assertEquals("row 10/10", described[bar + 1])
        assertEquals("row 9/9", described[bar - 1])
    }

    @Test
    fun `layout puts a pure deletion's bar above the removed lines, and one past the end last`() {
        val original = (1..12).joinToString("") { "line $it\n" }
        val modified = original.replace("line 6\nline 7\n", "")
        val items = describe(GitReviewSideBySide.layout(entries(original, modified), listOf(Place("u:1", 6, 3, 8), Place("u:2", 99, 99, 98))))
        val bar = items.indexOf("bar u:1")
        assertEquals("row 6/-", items[bar + 1])
        assertEquals("row 5/5", items[bar - 1])
        assertEquals("bar u:2", items.last())
    }

    @Test
    fun `layout puts a bar whose line is folded away on the fold's edge, and keeps blocks on one line in order`() {
        val original = (1..40).joinToString("") { "line $it\n" }
        val modified = original.replace("line 35\n", "LINE 35\n")
        val items = describe(
            GitReviewSideBySide.layout(entries(original, modified), listOf(Place("d:a", 10, 9, 11), Place("d:b", 10, 9, 11))),
        )
        assertEquals("gap", items[2].substringBefore(' '))
        assertEquals(listOf("bar d:a", "bar d:b"), items.subList(0, 2))
    }
}
