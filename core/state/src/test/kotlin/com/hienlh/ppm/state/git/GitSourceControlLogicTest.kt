package com.hienlh.ppm.state.git

import com.hienlh.ppm.client.ChangeBlock
import com.hienlh.ppm.client.ChangeSide
import com.hienlh.ppm.client.ChangedFile
import com.hienlh.ppm.client.DiffHunk
import com.hienlh.ppm.client.DiffLine
import com.hienlh.ppm.client.DiscardRecord
import com.hienlh.ppm.client.GitBranchState
import com.hienlh.ppm.client.GitBranchStatus
import com.hienlh.ppm.client.GitChanges
import com.hienlh.ppm.client.GitFileChange
import com.hienlh.ppm.client.GitOperation
import com.hienlh.ppm.client.GitRef
import com.hienlh.ppm.client.GitRepo
import com.hienlh.ppm.client.GitRepos
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.client.StashEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitSourceControlLogicTest {
    @Test
    fun `GitRepoScope resolves repos and converts paths`() {
        val rootRepo = GitRepo("/work/ppm", "ppm", ".")
        val subA = GitRepo("/work/ppm/apps/api", "api", "apps/api")
        val subB = GitRepo("/work/ppm/apps/web", "web", "apps/web")

        val multi = GitRepos(rootIsRepo = false, repos = listOf(subA, subB), root = "/work/ppm")
        assertTrue(GitRepoScope.needsPick(multi, null))
        assertNull(GitRepoScope.resolveRepo(multi, null))
        assertEquals(subB, GitRepoScope.resolveRepo(multi, "/work/ppm/apps/web"))
        assertFalse(GitRepoScope.needsPick(multi, "/work/ppm/apps/web"))

        val empty = GitRepos(rootIsRepo = false, repos = emptyList(), root = "/work/ppm")
        assertTrue(GitRepoScope.hasNoRepo(empty))

        assertEquals("src/index.ts", GitRepoScope.repoRelativePath("apps/api/src/index.ts", subA))
        assertNull(GitRepoScope.repoRelativePath("apps/web/src/index.ts", subA))
        assertEquals("apps/api/src/index.ts", GitRepoScope.projectRelativePath("src/index.ts", subA))
        assertEquals("src/index.ts", GitRepoScope.projectRelativePath("src/index.ts", rootRepo))

        val status = GitBranchStatus(
            branch = "main",
            ahead = 1,
            behind = 0,
            tracking = "origin/main",
            staged = listOf(GitFileChange("src/a.ts", "M", "src/old.ts")),
            unstaged = listOf(GitFileChange("src/b.ts", "D")),
            untracked = listOf("src/c.ts"),
        )
        val rebased = GitRepoScope.rebaseStatusPaths(status, subA)
        assertEquals("apps/api/src/a.ts", rebased.staged[0].path)
        assertEquals("apps/api/src/old.ts", rebased.staged[0].oldPath)
        assertEquals("apps/api/src/b.ts", rebased.unstaged[0].path)
        assertEquals("apps/api/src/c.ts", rebased.untracked[0])

        val map = mutableMapOf<String, String>()
        val store = object : KeyValueStore {
            override fun get(key: String): String? = map[key]
            override fun put(key: String, value: String?) {
                if (value == null) map.remove(key) else map[key] = value
            }
        }
        val choices = GitRepoChoices(store)
        choices.choose("ppm", subB.path)
        assertEquals(subB.path, GitRepoChoices(store).chosen("ppm"))
        choices.clear("ppm")
        assertNull(GitRepoChoices(store).chosen("ppm"))
    }

    @Test
    fun `GitChangesView derives check states totals sync mode and Explorer maps`() {
        val stagedFile = ChangedFile(
            path = "src/A.kt",
            oldPath = "src/OldA.kt",
            x = "R",
            y = " ",
            staged = ChangeSide(
                "R",
                added = 5,
                removed = 2,
                blocks = listOf("add", "delete"),
                changeBlocks = listOf(
                    ChangeBlock("add", 0, 1, 0, 1, 5, 5, 0),
                    ChangeBlock("delete", 1, 10, 2, 15, 0, 0, 2),
                ),
            ),
        )
        val partialFile = ChangedFile(
            path = "src/B.kt",
            x = "A",
            y = "M",
            staged = ChangeSide(
                "A",
                added = 1,
                removed = 0,
                blocks = listOf("add"),
                changeBlocks = listOf(ChangeBlock("add", 0, 1, 0, 1, 1, 1, 0)),
            ),
            unstaged = ChangeSide(
                "M",
                added = 3,
                removed = 1,
                blocks = listOf("change"),
                changeBlocks = listOf(ChangeBlock("change", 0, 5, 1, 5, 3, 3, 1)),
            ),
        )
        val untrackedFile = ChangedFile(
            path = "src/new/C.kt",
            x = "?",
            y = "?",
            untracked = true,
            unstaged = ChangeSide(
                "A",
                added = 10,
                removed = 0,
                blocks = listOf("add"),
                changeBlocks = listOf(ChangeBlock("add", 0, 0, 0, 1, 10, 10, 0)),
            ),
        )
        val conflictFile = ChangedFile(
            path = "src/Conflict.kt",
            x = "U",
            y = "U",
            conflict = "both-modified",
            staged = ChangeSide(
                "U",
                added = 2,
                removed = 1,
                blocks = listOf("c-staged"),
                changeBlocks = listOf(ChangeBlock("c-staged", 0, 1, 1, 1, 2, 2, 1)),
            ),
            unstaged = ChangeSide(
                "U",
                added = 1,
                removed = 1,
                blocks = listOf("c-unstaged-1", "c-unstaged-2"),
                changeBlocks = listOf(
                    ChangeBlock("c-unstaged-1", 0, 10, 1, 10, 1, 1, 1),
                    ChangeBlock("c-unstaged-2", 1, 20, 1, 20, 1, 1, 1),
                ),
            ),
        )

        assertEquals(CheckState.ALL, GitChangesView.fileCheckState(stagedFile))
        assertEquals(CheckState.SOME, GitChangesView.fileCheckState(partialFile))
        assertEquals(CheckState.NONE, GitChangesView.fileCheckState(untrackedFile))
        assertEquals(CheckState.NONE, GitChangesView.fileCheckState(conflictFile))
        assertEquals(CheckState.SOME, GitChangesView.allCheckState(listOf(stagedFile, partialFile, untrackedFile)))
        assertEquals(CheckState.ALL, GitChangesView.allCheckState(listOf(stagedFile)))

        assertEquals(listOf("src/A.kt", "src/OldA.kt"), GitChangesView.unstagePaths(stagedFile))
        assertEquals(4 to 1, GitChangesView.changeCounts(partialFile))
        assertEquals("U", GitChangesView.changeLetter(conflictFile))
        assertEquals("R", GitChangesView.changeLetter(stagedFile))
        assertEquals("A", GitChangesView.changeLetter(partialFile))
        assertEquals("A", GitChangesView.changeLetter(untrackedFile))

        assertTrue(GitChangesView.hasConflictMarkers("line 1\n<<<<<<< HEAD\nfoo\n=======\nbar\n>>>>>>> main"))
        assertFalse(GitChangesView.hasConflictMarkers("normal text"))

        val totalsWithConflict = GitChangesView.changeTotals(listOf(stagedFile, partialFile, untrackedFile, conflictFile))
        assertEquals(4, totalsWithConflict.files)
        assertEquals(3, totalsWithConflict.filesStaged)
        assertEquals(4, totalsWithConflict.blocksStaged)
        assertEquals(8, totalsWithConflict.blocks)
        assertEquals(1, totalsWithConflict.conflicts)
        assertEquals("Resolve 1 conflict to commit", GitChangesView.commitHint(totalsWithConflict, "msg"))
        assertFalse(GitChangesView.canCommit(totalsWithConflict, "msg", null))

        // Unstaged-only files: filesStaged == 0 -> cannot commit, label is "Commit", hint tells user to tick a file or stage a block
        val unstagedOnlyTotals = GitChangesView.changeTotals(listOf(untrackedFile))
        assertEquals(1, unstagedOnlyTotals.files)
        assertEquals(0, unstagedOnlyTotals.filesStaged)
        assertEquals("Commit", GitChangesView.commitLabel(unstagedOnlyTotals))
        assertEquals("Tick a file or stage a block to commit", GitChangesView.commitHint(unstagedOnlyTotals, "feat: add C"))
        assertFalse(GitChangesView.canCommit(unstagedOnlyTotals, "feat: add C", null))

        // Staged files: filesStaged > 0 -> can commit when message is non-empty
        val stagedTotals = GitChangesView.changeTotals(listOf(stagedFile, partialFile))
        assertEquals(2, stagedTotals.filesStaged)
        assertEquals("Commit 2 files", GitChangesView.commitLabel(stagedTotals))
        assertEquals("Write a message to commit", GitChangesView.commitHint(stagedTotals, "   "))
        assertEquals("3 of 4 blocks staged · ⌘↵ commits", GitChangesView.commitHint(stagedTotals, "feat: commit staged"))
        assertTrue(GitChangesView.canCommit(stagedTotals, "feat: commit staged", null))

        // Amend: allowed with empty message when filesStaged > 0 and !lastCommitPushed; blocked when lastCommitPushed
        assertTrue(
            GitChangesView.canCommit(
                stagedTotals,
                "",
                operation = null,
                amend = true,
                hasLastCommit = true,
                lastCommitPushed = false,
            ),
        )
        assertFalse(
            GitChangesView.canCommit(
                stagedTotals,
                "feat: amend pushed",
                operation = null,
                amend = true,
                hasLastCommit = true,
                lastCommitPushed = true,
            ),
        )
        assertFalse(
            GitChangesView.canCommit(
                unstagedOnlyTotals,
                "",
                operation = null,
                amend = true,
                hasLastCommit = true,
                lastCommitPushed = false,
            ),
        )

        val untrackedSummary = GitChangesView.discardSummary(listOf(untrackedFile))
        assertEquals("Delete \"C.kt\"?", untrackedSummary.title)
        assertEquals("Delete file", untrackedSummary.confirm)
        assertTrue(untrackedSummary.body.contains("not in any commit"))

        val modifiedOnlyFile = ChangedFile(
            path = "src/Mod.kt",
            x = " ",
            y = "M",
            unstaged = ChangeSide("M", added = 1, removed = 1, blocks = emptyList()),
        )
        val mixedSummary = GitChangesView.discardSummary(listOf(modifiedOnlyFile, untrackedFile))
        assertEquals("Discard 2 changes?", mixedSummary.title)
        assertEquals("Discard 2 files", mixedSummary.confirm)
        assertEquals("This will discard edits in 1 file and delete 1 untracked file.", mixedSummary.body)

        val branch = GitBranchState(head = "main", oid = "1234567", upstream = "origin/main", upstreamGone = false, ahead = 2, behind = 1)
        assertEquals("sync", GitChangesView.syncMode(branch))
        assertTrue(GitChangesView.syncIsPrimary("sync", 0))
        assertFalse(GitChangesView.syncIsPrimary("sync", 1))
        assertFalse(GitChangesView.syncIsPrimary("synced", 0))

        val changes = GitChanges(
            branch = branch,
            operation = null,
            lastCommit = null,
            files = listOf(stagedFile, partialFile, untrackedFile, conflictFile),
            truncated = false,
            stashes = 0,
        )
        val status = GitChangesView.changesToStatus(changes)
        // Conflict file maps to unstaged with status "M"
        assertTrue(status.unstaged.any { it.path == "src/Conflict.kt" && it.status == "M" })
        val fileMap = GitChangesView.buildFileStatusMap(status)
        assertEquals("R", fileMap["src/A.kt"])
        // Staged ("A") overrides unstaged ("M") on partialFile
        assertEquals("A", fileMap["src/B.kt"])
        assertEquals("U", fileMap["src/new/C.kt"])
        assertEquals("M", fileMap["src/Conflict.kt"])

        val folderMap = GitChangesView.buildFolderStatusMap(fileMap)
        assertEquals("A", folderMap["src"]) // D (6) > A (5) > M (4) > R (3) > C (2) > U (1)
        assertEquals("U", folderMap["src/new"])
    }

    @Test
    fun `ChangeBlock rejects string primitives and DiscardRecord and StashEntry handle null server payloads`() {
        assertNull(ChangeBlock.from(JsonPrimitive("add")))
        val sideWithStrings = ChangeSide.from(
            Json.parseToJsonElement("""{"status":"M","added":1,"removed":0,"blocks":["add"]}""").jsonObject,
        )
        assertTrue(sideWithStrings!!.changeBlocks.isEmpty())

        // Server returns {"undo": null} when nothing could be backed up
        val nullUndoJson = Json.parseToJsonElement("""{"undo":null}""").jsonObject
        assertNull(DiscardRecord.from(nullUndoJson))

        // Server returns {"undo":{"id":"u1","createdAt":100,"paths":["a.kt"],"skipped":["big.bin"]}}
        val undoWithSkippedJson = Json.parseToJsonElement(
            """{"undo":{"id":"u1","createdAt":100,"paths":["a.kt"],"skipped":["big.bin"]}}""",
        ).jsonObject
        val record = DiscardRecord.from(undoWithSkippedJson)
        assertNotNull(record)
        assertEquals("u1", record!!.id)
        assertEquals(listOf("a.kt"), record.files)
        assertEquals(listOf("big.bin"), record.skipped)

        // Server returns {"stashed":false,"stash":null} when nothing was stashed
        val nullStashJson = Json.parseToJsonElement("""{"stashed":false,"stash":null}""").jsonObject
        assertNull(StashEntry.from(nullStashJson))

        // blockDots returns all dots in file order without truncation
        val manyBlocksFile = ChangedFile(
            path = "src/Many.kt",
            staged = ChangeSide(
                status = "M",
                added = 1,
                removed = 0,
                blocks = listOf("s0"),
                changeBlocks = listOf(ChangeBlock("s0", 0, 15, 1, 15, 2, 1, 0)),
            ),
            unstaged = ChangeSide(
                status = "M",
                added = 7,
                removed = 0,
                blocks = emptyList(),
                changeBlocks = (0 until 7).map { idx ->
                    ChangeBlock("b$idx", idx, idx * 10, 1, idx * 10, 2, 1, 0)
                },
            ),
        )
        val dots = GitChangesView.blockDots(manyBlocksFile)
        assertEquals(listOf(false, false, true, false, false, false, false, false), dots)
    }

    @Test
    fun `GitFileTree compacts single-child folders and collects leaf files`() {
        val f1 = ChangedFile(path = "src/web/components/git/A.tsx", unstaged = ChangeSide("M", 1, 0, emptyList()))
        val f2 = ChangedFile(path = "src/web/components/git/B.tsx", unstaged = ChangeSide("A", 2, 0, emptyList()))
        val tree = GitFileTree.buildTree(listOf(f2, f1))
        assertEquals(1, tree.size)
        val root = tree[0]
        assertEquals("src/web/components/git", root.name)
        assertEquals("src/web/components/" to "git", GitFileTree.splitFolderName(root.name))
        assertEquals(2, root.fileCount)
        assertEquals(listOf(f1, f2), GitFileTree.collectFiles(root))
    }

    @Test
    fun `GitRefPicker builds rows and resolves checkoutTarget`() {
        val mainRef = GitRef("main", "branch", "111", "111", "init", "Alice", "2026-11-07T00:00:00Z", current = true)
        val originMain = GitRef("origin/main", "remote", "111", "111", "init", "Alice", "2026-11-07T00:00:00Z")
        val originFeat = GitRef("origin/feat/new", "remote", "222", "222", "feat", "Bob", "2026-11-07T00:00:00Z")
        val allRefs = listOf(mainRef, originMain, originFeat)

        val rows = GitRefPicker.buildRows(allRefs, "")
        assertEquals(0, GitRefPicker.firstSelectable(rows))
        assertEquals(CheckoutTarget("main", "checkout"), GitRefPicker.checkoutTarget(originMain, allRefs))
        assertEquals(CheckoutTarget("origin/feat/new", "track"), GitRefPicker.checkoutTarget(originFeat, allRefs))
    }

    @Test
    fun `HunkSelection toggles lines and builds fast-path or partial requests`() {
        val hunk = DiffHunk(
            index = 0,
            header = "@@ -1,2 +1,3 @@",
            oldStart = 1,
            oldLines = 2,
            newStart = 1,
            newLines = 3,
            id = "h0",
            lines = listOf(
                DiffLine(" ", "ctx"),
                DiffLine("-", "old"),
                DiffLine("+", "new1"),
                DiffLine("+", "new2"),
            ),
        )
        val initial = HunkSelection.allChangedKeys(listOf(hunk))
        assertEquals(setOf("0:1", "0:2", "0:3"), initial)
        assertEquals(CheckState.ALL, HunkSelection.hunkState(hunk, initial))

        // Full hunk selected -> lines is null
        val fullReq = HunkSelection.buildHunkRequest(listOf(hunk), initial)
        assertEquals(1, fullReq.size)
        assertNull(fullReq[0].lines)

        // Uncheck one line -> partial lines list
        val partial = HunkSelection.toggleLine(0, 2, initial)
        assertEquals(CheckState.SOME, HunkSelection.hunkState(hunk, partial))
        val partialReq = HunkSelection.buildHunkRequest(listOf(hunk), partial)
        assertEquals(listOf(1, 3), partialReq[0].lines)
    }

    @Test
    fun `CommitDraftSync ignores own clientId echo and preserves post-commit edits`() {
        val sync = CommitDraftSync(CoroutineScope(SupervisorJob()), clientId = "me")
        sync.setMessage(null, "ppm", null, "wip commit")
        assertEquals("wip commit", sync.message("ppm", null))

        // Echo with our own clientId is ignored even if message differs
        sync.onRemoteDraft("ppm", null, "stale", senderClientId = "me", activeRepo = null)
        assertEquals("wip commit", sync.message("ppm", null))

        // Broadcast from another client updates the draft
        sync.onRemoteDraft("ppm", null, "from web", senderClientId = "web-1", activeRepo = null)
        assertEquals("from web", sync.message("ppm", null))

        // Consumed clears when message matches, preserves when user typed something new
        sync.consumed("ppm", null, "other")
        assertEquals("from web", sync.message("ppm", null))
        sync.consumed("ppm", null, "from web")
        assertEquals("", sync.message("ppm", null))
    }
}
