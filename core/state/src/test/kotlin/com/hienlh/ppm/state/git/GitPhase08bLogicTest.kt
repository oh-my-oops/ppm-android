package com.hienlh.ppm.state.git

import com.hienlh.ppm.client.BranchDiffFile
import com.hienlh.ppm.client.ChangeBlock
import com.hienlh.ppm.client.ChangeHunk
import com.hienlh.ppm.client.ChangeLine
import com.hienlh.ppm.client.ChangeSide
import com.hienlh.ppm.client.ChangedFile
import com.hienlh.ppm.client.GitBranch
import com.hienlh.ppm.client.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitPhase08bLogicTest {

    @Test
    fun `GitReviewModel builds blocks handles duplicate hunk keys discards and focus navigation`() {
        val dupBlock0 = ChangeBlock("dup-id", 0, 10, 2, 10, 3, added = 2, removed = 1)
        val dupBlock1 = ChangeBlock("dup-id", 1, 30, 2, 31, 3, added = 2, removed = 1)
        val stagedBlock = ChangeBlock("stg-id", 0, 1, 1, 1, 2, added = 1, removed = 0)

        val fileA = ChangedFile(
            path = "src/A.kt",
            conflict = false,
            x = "M",
            y = "M",
            untracked = false,
            staged = ChangeSide(
                added = 1,
                removed = 0,
                blocks = listOf("stg-id"),
                changeBlocks = listOf(stagedBlock),
            ),
            unstaged = ChangeSide(
                added = 4,
                removed = 2,
                blocks = listOf("dup-id", "dup-id"),
                changeBlocks = listOf(dupBlock0, dupBlock1),
            ),
        )
        val fileConflict = ChangedFile(
            path = "src/Conflict.kt",
            conflict = true,
            x = "U",
            y = "U",
            untracked = false,
        )
        val fileStagedOnly = ChangedFile(
            path = "src/StagedOnly.kt",
            conflict = false,
            x = "A",
            y = ".",
            untracked = false,
            staged = ChangeSide(
                added = 5,
                removed = 0,
                whole = "added",
                blocks = emptyList(),
                changeBlocks = emptyList(),
            ),
        )

        val blocksA = GitReviewModel.fileBlocks("src/A.kt", fileA, detail = null)
        assertEquals(3, blocksA.size)
        // Sorted by anchor: stagedBlock (anchor 1.0) -> dupBlock0 (anchor 10.0) -> dupBlock1 (anchor 30.0)
        assertEquals("s:stg-id", blocksA[0].key)
        assertEquals("staged", blocksA[0].state)
        assertEquals("u:dup-id", blocksA[1].key)
        assertEquals("open", blocksA[1].state)
        assertEquals("u:dup-id#2", blocksA[2].key)

        // Hunk index resolution for duplicate keys
        val hunks = listOf(
            ChangeHunk("dup-id", 0, 10, 2, 10, 3, 2, 1, "@@ -10,2 +10,3 @@", emptyList()),
            ChangeHunk("other", 1, 20, 1, 21, 1, 0, 0, "@@ -20,1 +21,1 @@", emptyList()),
            ChangeHunk("dup-id", 2, 30, 2, 31, 3, 2, 1, "@@ -30,2 +31,3 @@", emptyList()),
        )
        assertEquals(0, GitReviewModel.hunkIndexFromDetail(hunks, "u:dup-id"))
        assertEquals(2, GitReviewModel.hunkIndexFromDetail(hunks, "u:dup-id#2"))
        assertNull(GitReviewModel.hunkIndexFromDetail(hunks, "u:dup-id#3"))

        // Discarded entry stays in place and suppresses optimistic pending="discarded" live block
        val discardedHunk = ChangeHunk("dup-id", 0, 10, 2, 10, 3, 2, 1, "@@", emptyList())
        val discarded = DiscardedEntry(
            path = "src/A.kt",
            recordId = "rec-1",
            hunks = listOf(discardedHunk),
            whole = null,
            added = 2,
            removed = 1,
        )
        val blocksWithDiscard = GitReviewModel.fileBlocks(
            path = "src/A.kt",
            file = fileA,
            detail = null,
            discards = listOf(discarded),
            pending = mapOf(GitReviewModel.blockId("src/A.kt", "u:dup-id") to "discarded"),
        )
        val discBlock = blocksWithDiscard.first { it.state == "discarded" }
        assertEquals("rec-1", discBlock.recordId)
        assertEquals("d:rec-1:0", discBlock.key)
        // Live u:dup-id was covered by discard and filtered out
        assertFalse(blocksWithDiscard.any { it.key == "u:dup-id" })

        // File reviews & rail order: changes -> staged -> discarded (conflicts are excluded from fileReviews)
        val reviews = GitReviewModel.fileReviews(
            files = listOf(fileStagedOnly, fileA, fileConflict),
            discards = listOf(
                DiscardedEntry(
                    path = "src/Gone.kt",
                    recordId = "rec-gone",
                    hunks = emptyList(),
                    whole = "deleted",
                    added = 0,
                    removed = 3,
                ),
            ),
        )
        assertEquals(3, reviews.size) // fileConflict is excluded because it goes to the Conflicts group
        val ordered = GitReviewModel.railOrder(reviews)
        assertEquals("changes", GitReviewModel.railGroup(ordered[0]))
        assertEquals("src/A.kt", ordered[0].path)
        assertEquals("staged", GitReviewModel.railGroup(ordered[1]))
        assertEquals("src/StagedOnly.kt", ordered[1].path)
        assertEquals("discarded", GitReviewModel.railGroup(ordered[2]))
        assertEquals("src/Gone.kt", ordered[2].path)

        val totals = GitReviewModel.reviewTotals(reviews)
        assertEquals(2, totals.files) // A + StagedOnly
        assertEquals(4, totals.blocks) // 3 in A + 1 in StagedOnly
        assertEquals(2, totals.staged) // 1 in A + 1 in StagedOnly
        assertEquals(1, totals.discarded) // 1 in Gone.kt

        // Focus resolution & stepping
        val initialFocus = GitReviewModel.resolveFocus(reviews, null)
        assertNotNull(initialFocus)
        assertEquals("src/A.kt", initialFocus!!.path)
        assertEquals("u:dup-id", initialFocus.key) // First open block
        val nextFocus = GitReviewModel.stepFocus(reviews, initialFocus, 1)
        assertNotNull(nextFocus)
        assertEquals("u:dup-id#2", nextFocus!!.key)
        val nextOpen = GitReviewModel.nextOpen(reviews, initialFocus)
        assertNotNull(nextOpen)
        assertEquals("u:dup-id#2", nextOpen!!.key)
    }

    @Test
    fun `GitReviewModel computes paneItems numberedRows rangeText and pickRequest`() {
        val part1 = BlockPart(
            oldStart = 5,
            newStart = 5,
            lines = listOf(
                ChangeLine(" ", "ctx"),
                ChangeLine("-", "old"),
                ChangeLine("+", "new1"),
                ChangeLine("+", "new2"),
            ),
        )
        val b1 = GitReviewBlock(
            key = "u:h1",
            state = "open",
            side = "unstaged",
            hunk = ChangeBlock("h1", 0, 5, 2, 5, 3, added = 2, removed = 1),
            whole = null,
            parts = listOf(part1),
            anchor = 5.0,
            first = 5,
            last = 6,
            added = 2,
            removed = 1,
        )
        val b2 = GitReviewBlock(
            key = "u:h2",
            state = "open",
            side = "unstaged",
            hunk = ChangeBlock("h2", 1, 20, 1, 21, 2, added = 1, removed = 0),
            whole = null,
            parts = emptyList(),
            anchor = 20.0,
            first = 20,
            last = 20,
            added = 1,
            removed = 0,
        )

        val items = GitReviewModel.paneItems(listOf(b1, b2))
        assertEquals(4, items.size) // Gap(1..4), Block(b1), Gap(7..19), Block(b2)
        assertEquals(4, (items[0] as GitPaneItem.Gap).lines)
        assertEquals(13, (items[2] as GitPaneItem.Gap).lines)

        val rows = GitReviewModel.numberedRows(part1)
        assertEquals(4, rows.size)
        assertEquals(5, rows[0].old)
        assertEquals(5, rows[0].new)
        assertEquals(6, rows[1].old)
        assertNull(rows[1].new)
        assertNull(rows[2].old)
        assertEquals(6, rows[2].new)

        assertEquals("Lines 5–7", GitReviewModel.rangeText(b1))
        assertEquals(listOf(1, 2, 3), GitReviewModel.pickableLines(b1))
        // Picking all pickable lines returns null (meaning full hunk stage/unstage/discard)
        assertNull(GitReviewModel.pickRequest(b1, setOf(1, 2, 3)))
        assertEquals(listOf(1, 3), GitReviewModel.pickRequest(b1, setOf(1, 3)))
    }

    @Test
    fun `BranchReviewState tracks reviewed blobs prunes removed paths and evicts LRU at 20`() {
        val files = listOf(
            BranchDiffFile(path = "src/A.kt", status = "M", additions = 3, deletions = 1, binary = false, blob = "blob-a1"),
            BranchDiffFile(path = "src/B.kt", status = "A", additions = 10, deletions = 0, binary = false, blob = "blob-b1"),
            BranchDiffFile(path = "src/Deleted.kt", status = "D", additions = 0, deletions = 5, binary = false, blob = ""),
        )

        assertEquals("ppm:branch-review:proj:main:feat/x", BranchReviewState.reviewKey("proj", "main", "feat/x"))

        var map = emptyMap<String, String>()
        val aFile = files[0]
        val delFile = files[2]
        assertFalse(BranchReviewState.isReviewed(map, aFile))
        map = BranchReviewState.toggleReviewed(map, aFile)
        assertTrue(BranchReviewState.isReviewed(map, aFile))
        // If blob changes on disk/commit, isReviewed becomes false automatically
        assertFalse(BranchReviewState.isReviewed(map, aFile.copy(blob = "blob-a2")))

        map = BranchReviewState.toggleReviewed(map, delFile)
        assertTrue(BranchReviewState.isReviewed(map, delFile))

        // Prune removes stale paths
        val withStale = map + ("src/Stale.kt" to "blob-s")
        val pruned = BranchReviewState.pruneReviewed(withStale, files)
        assertFalse(pruned.containsKey("src/Stale.kt"))
        assertTrue(pruned.containsKey("src/A.kt"))

        // Next unreviewed wraps around
        assertEquals(files[1], BranchReviewState.nextUnreviewed(pruned, files, "src/A.kt"))

        // Store & 20-key LRU eviction
        val backing = mutableMapOf<String, String>()
        val kv = object : KeyValueStore {
            override fun get(key: String): String? = backing[key]
            override fun put(key: String, value: String?) {
                if (value == null) backing.remove(key) else backing[key] = value
            }
        }
        val store = BranchReviewStore(kv)
        for (i in 1..22) {
            val k = BranchReviewState.reviewKey("proj", "main", "b$i")
            store.saveReviewed(k, mapOf("src/A.kt" to "blob-$i"))
        }
        // First 2 keys should have been evicted
        assertTrue(store.loadReviewed(BranchReviewState.reviewKey("proj", "main", "b1")).isEmpty())
        assertTrue(store.loadReviewed(BranchReviewState.reviewKey("proj", "main", "b2")).isEmpty())
        assertEquals(
            mapOf("src/A.kt" to "blob-22"),
            store.loadReviewed(BranchReviewState.reviewKey("proj", "main", "b22")),
        )

        // Default base picks main -> master -> first non-current local
        val branches = listOf(
            GitBranch(name = "feat/x", current = true, commit = "111"),
            GitBranch(name = "develop", current = false, commit = "222"),
            GitBranch(name = "main", current = false, commit = "333"),
            GitBranch(name = "remotes/origin/main", current = false, commit = "333", remote = true),
        )
        assertEquals("main", BranchReviewState.defaultBase(branches, "feat/x"))
        val items = BranchReviewState.branchItems(branches)
        assertEquals(4, items.size)
        assertEquals("Branches", items[0].group)
        assertEquals("Remote branches", items[3].group)
    }

    @Test
    fun `GitLogRefBadges merges local and remote branches parses diff and checks binary preview`() {
        val branches = listOf(
            GitBranch(
                name = "main",
                current = true,
                commit = "abc1234",
                ahead = 2,
                behind = 0,
                remotes = listOf("origin/main"),
            ),
            GitBranch(
                name = "remotes/origin/main",
                current = false,
                commit = "abc1234",
                remote = true,
            ),
            GitBranch(
                name = "remotes/origin/release",
                current = false,
                commit = "abc1234",
                remote = true,
            ),
        )
        val badges = GitLogRefBadges.buildRefBadges(
            commitHash = "abc1234",
            refs = listOf("HEAD -> main", "tag: v1.0.0"),
            branches = branches,
            head = "abc1234",
        )
        assertEquals(3, badges.size)
        assertEquals(GitRefBadgeModel("v1.0.0", "synced", isHead = false, isTag = true), badges[0])
        assertEquals(GitRefBadgeModel("main", "ahead", isHead = true, isTag = false), badges[1])
        assertEquals(GitRefBadgeModel("origin/release", "remote-only", isHead = false, isTag = false), badges[2])

        val rawDiff = """
            diff --git a/foo.txt b/foo.txt
            index 111..222 100644
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,2 +1,2 @@
             keep
            -old
            +new
        """.trimIndent()
        val (orig, mod) = GitLogRefBadges.parseDiff(rawDiff)
        assertEquals("keep\nold", orig)
        assertEquals("keep\nnew", mod)

        assertEquals("0123456", GitLogRefBadges.shortRef("0123456789abcdef0123456789abcdef01234567"))
        assertEquals("HEAD", GitLogRefBadges.shortRef("HEAD"))
        assertTrue(GitLogRefBadges.canPreviewBinary("docs/logo.png"))
        assertFalse(GitLogRefBadges.canPreviewBinary("fonts/inter.woff2"))
    }
}
