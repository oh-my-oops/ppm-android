package com.hienlh.ppm.state.editor

import com.hienlh.ppm.state.ReviewBlocks
import com.hienlh.ppm.state.workspace.deriveTabId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class BlameDiffConflictStateTest {

    @Test
    fun `blame formatting, timezone-aware absolute time, markdown escaping and 200-entry cache work`() {
        val uncommitted = BlameCommitInfo(
            hash = UNCOMMITTED_HASH,
            author = "Alice",
            authorMail = "<alice@example.com>",
            authorTime = 1700000000L,
            summary = "WIP",
        )
        assertEquals("You, uncommitted changes", formatBlameAnnotation(uncommitted))

        val committed = BlameCommitInfo(
            hash = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
            author = "Thang Ho",
            authorMail = "<thang@example.com>",
            authorTime = 1_764_667_020L, // 2025-12-02 09:17:00 UTC = 2025-12-02 16:17:00 +07:00
            summary = "fix: handle <script> & [link](command:evil)",
        )
        val nowMs = (1_764_667_020L + 3 * 86400L) * 1000L
        assertEquals(
            "Thang Ho, 3 days ago • fix: handle <script> & [link](command:evil)",
            formatBlameAnnotation(committed, nowMs),
        )
        assertEquals(
            "December 2nd, 2025 4:17 PM",
            formatAbsoluteTime(committed.authorTime * 1000L, ZoneId.of("Asia/Ho_Chi_Minh")),
        )
        assertEquals("TH", authorInitials("Thang Ho"))
        val escaped = escapeBlameMarkdown("fix <script>alert(1)</script> [click](command:x)")
        assertTrue(escaped.contains("\\<script\\>"))
        assertTrue(escaped.contains("\\[click\\]"))

        // Bounded 200-entry cache evicts oldest and preserves null entries
        val cache = BoundedCommitLineCache(maxSize = 3)
        cache.put("k1", null)
        assertTrue(cache.contains("k1"))
        assertNull(cache.get("k1"))
        cache.put("k2", null)
        cache.put("k3", null)
        cache.put("k4", null)
        assertEquals(3, cache.size())
        assertFalse(cache.contains("k1"))
    }

    @Test
    fun `side-by-side diff pairs removed and added lines and pads unequal replacement blocks`() {
        val original = "line 1\nold A\nold B\nline 4\n"
        val modified = "line 1\nnew A\nline 4\n"
        val diff = ReviewBlocks.computeBlocks(original, modified)
        assertNotNull(diff)

        val entries = buildSideBySideDiffEntries(
            diff = diff,
            original = original,
            modified = modified,
            openedGaps = mapOf(0 to true),
        )
        val allRows = entries.filterIsInstance<SideBySideDiffEntry.Rows>().flatMap { it.rows }
        // Find the replacement rows for old A / old B vs new A
        val remA = allRows.first { it.leftText == "old A" }
        assertEquals("new A", remA.rightText)
        val remB = allRows.first { it.leftText == "old B" }
        assertNull(remB.rightText)
        assertNull(remB.rightLineNumber)
    }

    @Test
    fun `conflict parser separates diff3 base block and resolves 3 conflict regions accurately`() {
        val raw = """
            header
            <<<<<<< HEAD
            const a = 1;
            ||||||| merged common ancestors
            const a = 0;
            =======
            const a = 2;
            >>>>>>> feature/a
            middle
            <<<<<<< HEAD
            const b = "cur";
            =======
            const b = "inc";
            >>>>>>> feature/b
            footer
            <<<<<<< HEAD
            const c = true;
            =======
            const c = false;
            >>>>>>> feature/c
        """.trimIndent()

        val conflicts = parseConflicts(raw)
        assertEquals(3, conflicts.size)
        // First conflict has diff3 base block separated from currentContent
        assertEquals("const a = 1;", conflicts[0].currentContent)
        assertEquals("const a = 0;", conflicts[0].baseContent)
        assertEquals("const a = 2;", conflicts[0].incomingContent)

        // Resolve 1st as CURRENT, 2nd as INCOMING, 3rd as BOTH
        val step1 = resolveConflictRegion(raw, conflicts[0].id, ConflictResolutionAction.CURRENT)
        val after1 = parseConflicts(step1)
        assertEquals(2, after1.size)

        val step2 = resolveConflictRegion(step1, after1[0].id, ConflictResolutionAction.INCOMING)
        val after2 = parseConflicts(step2)
        assertEquals(1, after2.size)

        val step3 = resolveConflictRegion(step2, after2[0].id, ConflictResolutionAction.BOTH)
        assertEquals(0, parseConflicts(step3).size)
        assertEquals(
            listOf(
                "header",
                "const a = 1;",
                "middle",
                "const b = \"inc\";",
                "footer",
                "const c = true;",
                "const c = false;",
            ).joinToString("\n"),
            step3,
        )
    }

    @Test
    fun `compare store caps dirty content, clears on project change and derives unique tab ids per file pair`() {
        val store = CompareStoreState()
        store.selectForCompare(
            CompareSelection(
                filePath = "src/a.ts",
                projectName = "ppm",
                dirtyContent = "unsaved A",
            ),
        )
        assertEquals("unsaved A", store.selection?.dirtyContent)

        // Switching to another project clears selection
        store.onProjectChanged("other-project")
        assertNull(store.selection)

        // Distinct git-diff tab IDs per file compare pair (fixes web `git-diff:unknown` collision)
        val id1 = deriveTabId(
            "git-diff",
            buildJsonObject {
                put("file1", "src/a.ts")
                put("file2", "src/b.ts")
            },
        )
        val id2 = deriveTabId(
            "git-diff",
            buildJsonObject {
                put("file1", "src/a.ts")
                put("file2", "src/c.ts")
            },
        )
        assertEquals("git-diff:compare:src/a.ts↔src/b.ts", id1)
        assertEquals("git-diff:compare:src/a.ts↔src/c.ts", id2)
    }

    @Test
    fun `C10 diff3 conflict with empty current or incoming side resolves without spurious blank line and preserves CRLF`() {
        val diff3EmptyCurrent = listOf(
            "before",
            "<<<<<<< HEAD",
            "||||||| merged common ancestors",
            "const deletedInHead = 1;",
            "=======",
            "const modifiedInBranch = 2;",
            ">>>>>>> feature/branch",
            "after",
        ).joinToString("\n")

        val parsed = parseConflicts(diff3EmptyCurrent)
        assertEquals(1, parsed.size)
        assertTrue(parsed[0].currentLines.isEmpty())
        assertEquals(listOf("const deletedInHead = 1;"), parsed[0].baseLines)
        assertEquals(listOf("const modifiedInBranch = 2;"), parsed[0].incomingLines)

        assertEquals(
            "before\nafter",
            resolveConflictRegion(diff3EmptyCurrent, parsed[0].id, ConflictResolutionAction.CURRENT),
        )
        assertEquals(
            "before\nconst modifiedInBranch = 2;\nafter",
            resolveConflictRegion(diff3EmptyCurrent, parsed[0].id, ConflictResolutionAction.BOTH),
        )

        val diff3EmptyIncomingCrlf = listOf(
            "before",
            "<<<<<<< HEAD",
            "const keptInHead = 1;",
            "||||||| base",
            "const old = 0;",
            "=======",
            ">>>>>>> feature/delete",
            "after",
        ).joinToString("\r\n")

        assertEquals(
            "before\r\nafter",
            resolveConflictRegion(diff3EmptyIncomingCrlf, 0, ConflictResolutionAction.INCOMING),
        )
        assertEquals(
            "before\r\nconst keptInHead = 1;\r\nafter",
            resolveConflictRegion(diff3EmptyIncomingCrlf, 0, ConflictResolutionAction.BOTH),
        )
    }
}
