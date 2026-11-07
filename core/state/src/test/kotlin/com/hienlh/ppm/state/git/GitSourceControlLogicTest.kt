package com.hienlh.ppm.state.git

import com.hienlh.ppm.client.ChangeSide
import com.hienlh.ppm.client.ChangedFile
import com.hienlh.ppm.client.DiffHunk
import com.hienlh.ppm.client.DiffLine
import com.hienlh.ppm.client.GitBranchState
import com.hienlh.ppm.client.GitBranchStatus
import com.hienlh.ppm.client.GitChanges
import com.hienlh.ppm.client.GitFileChange
import com.hienlh.ppm.client.GitOperation
import com.hienlh.ppm.client.GitRef
import com.hienlh.ppm.client.GitRepo
import com.hienlh.ppm.client.GitRepos
import com.hienlh.ppm.client.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            staged = ChangeSide("R", added = 5, removed = 2, blocks = listOf("add", "delete")),
        )
        val partialFile = ChangedFile(
            path = "src/B.kt",
            staged = ChangeSide("M", added = 1, removed = 0, blocks = listOf("add")),
            unstaged = ChangeSide("M", added = 3, removed = 1, blocks = listOf("change")),
        )
        val untrackedFile = ChangedFile(
            path = "src/new/C.kt",
            unstaged = ChangeSide("A", added = 10, removed = 0, blocks = listOf("add")),
        )
        val conflictFile = ChangedFile(
            path = "src/Conflict.kt",
            conflict = "UU",
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

        assertTrue(GitChangesView.hasConflictMarkers("line 1\n<<<<<<< HEAD\nfoo\n=======\nbar\n>>>>>>> main"))
        assertFalse(GitChangesView.hasConflictMarkers("normal text"))

        val totalsWithConflict = GitChangesView.changeTotals(listOf(stagedFile, partialFile, untrackedFile, conflictFile))
        assertEquals(4, totalsWithConflict.total)
        assertEquals(2, totalsWithConflict.staged)
        assertEquals(2, totalsWithConflict.unstaged)
        assertEquals(1, totalsWithConflict.conflicts)
        assertFalse(totalsWithConflict.implicitAll)
        assertEquals(2, totalsWithConflict.commitCount)
        assertEquals("Resolve 1 conflict before committing", GitChangesView.commitHint(totalsWithConflict, "msg"))
        assertFalse(GitChangesView.canCommit(totalsWithConflict, "msg", null))

        val cleanTotals = GitChangesView.changeTotals(listOf(untrackedFile))
        assertTrue(cleanTotals.implicitAll)
        assertEquals(1, cleanTotals.commitCount)
        assertEquals("Commit 1 file", GitChangesView.commitLabel(cleanTotals))
        assertTrue(GitChangesView.canCommit(cleanTotals, "feat: add C", null))

        assertEquals("delete 1 new file", GitChangesView.discardSummary(listOf(untrackedFile)))
        assertEquals("revert 1 modified file and delete 1 new file", GitChangesView.discardSummary(listOf(partialFile, untrackedFile)))

        val branch = GitBranchState(head = "main", oid = "1234567", upstream = "origin/main", upstreamGone = false, ahead = 2, behind = 1)
        assertEquals("sync", GitChangesView.syncMode(branch))
        assertTrue(GitChangesView.syncIsPrimary("sync", 0))
        assertFalse(GitChangesView.syncIsPrimary("sync", 1))
        assertFalse(GitChangesView.syncIsPrimary("synced", 0))

        val changes = GitChanges(
            branch = branch,
            operation = null,
            lastCommit = null,
            files = listOf(stagedFile, partialFile, untrackedFile),
            truncated = false,
            stashes = 0,
        )
        val status = GitChangesView.changesToStatus(changes)
        val fileMap = GitChangesView.buildFileStatusMap(status)
        assertEquals("R", fileMap["src/A.kt"])
        assertEquals("M", fileMap["src/B.kt"])
        assertEquals("U", fileMap["src/new/C.kt"])

        val folderMap = GitChangesView.buildFolderStatusMap(fileMap)
        assertEquals("M", folderMap["src"]) // M (4) > R (3) > U (1)
        assertEquals("U", folderMap["src/new"])
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
