package com.hienlh.ppm.app.ui.phone.git

import com.hienlh.ppm.client.ChangeSide
import com.hienlh.ppm.client.ChangedFile
import com.hienlh.ppm.client.GitBranch
import com.hienlh.ppm.client.GitBranchState
import com.hienlh.ppm.client.GitOperation
import com.hienlh.ppm.state.git.GitChangeGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for Phone Source Control UI derivations (`CommitBox`, `PhoneBranchSheet`,
 * `PhoneSourceControlScreen` helpers — Requirements 9.1–9.6).
 */
class PhoneGitModelsTest {

    @Test
    fun `phoneSyncChipState derives Publish Sync Pull Push and Synced modes`() {
        val noUpstream = GitBranchState(
            head = "feat/new",
            oid = "111",
            upstream = null,
            upstreamGone = false,
            ahead = 0,
            behind = 0,
        )
        val publishChip = phoneSyncChipState(noUpstream, totalChangedFiles = 0)
        assertNotNull(publishChip)
        assertEquals("publish", publishChip!!.mode)
        assertEquals("Publish", publishChip.label)
        assertTrue(publishChip.isPrimary)

        val aheadAndBehind = noUpstream.copy(
            upstream = "origin/feat/new",
            ahead = 2,
            behind = 3,
        )
        val syncChip = phoneSyncChipState(aheadAndBehind, totalChangedFiles = 1)
        assertEquals("sync", syncChip?.mode)
        assertEquals("Sync", syncChip?.label)
        assertEquals("↓3 ↑2", syncChip?.secondaryLabel)
        assertFalse(syncChip!!.isPrimary)

        val pullPausedByConflict = phoneSyncChipState(
            branch = aheadAndBehind.copy(ahead = 0, behind = 2),
            totalChangedFiles = 1,
            conflictCount = 1,
        )
        assertEquals("pull", pullPausedByConflict?.mode)
        assertFalse(pullPausedByConflict!!.enabled)
    }

    @Test
    fun `phoneCommitHint and phoneCanCommit reflect staged count message and conflicts`() {
        assertEquals("Nothing to commit", phoneCommitHint(0, 0, 0, ""))
        assertEquals("Tick a file to stage", phoneCommitHint(0, 2, 0, ""))
        assertEquals("2 files staged · write a message to commit", phoneCommitHint(2, 3, 0, "  "))
        assertEquals("1 file staged · ready to commit", phoneCommitHint(1, 2, 0, "feat: add phone git"))
        assertEquals("Resolve 2 conflicts to commit", phoneCommitHint(1, 2, 2, "feat: add phone git"))

        assertFalse(phoneCanCommit(stagedCount = 0, conflictCount = 0, message = "msg"))
        assertFalse(phoneCanCommit(stagedCount = 1, conflictCount = 0, message = "   "))
        assertFalse(phoneCanCommit(stagedCount = 1, conflictCount = 1, message = "msg"))
        assertFalse(
            phoneCanCommit(
                stagedCount = 1,
                conflictCount = 0,
                message = "msg",
                operation = GitOperation(kind = "rebase"),
            ),
        )
        assertTrue(phoneCanCommit(stagedCount = 1, conflictCount = 0, message = "feat: phone git"))
    }

    @Test
    fun `localBranchesForPhone keeps only local branches and puts current first`() {
        val branches = listOf(
            GitBranch(name = "origin/main", current = false, commit = "111", remote = true),
            GitBranch(name = "zeta", current = false, commit = "222", tracking = "origin/zeta", ahead = 1, behind = 0),
            GitBranch(name = "main", current = true, commit = "333", tracking = "origin/main", ahead = 0, behind = 0),
            GitBranch(name = "alpha", current = false, commit = "444", tracking = null),
        )
        val local = localBranchesForPhone(branches, currentHead = "main")
        assertEquals(listOf("main", "alpha", "zeta"), local.map { it.name })
        assertEquals("origin/main · up to date", formatPhoneBranchSubtitle(local[0]))
        assertEquals("local branch · no upstream", formatPhoneBranchSubtitle(local[1]))
        assertEquals("origin/zeta · ↑1 ahead", formatPhoneBranchSubtitle(local[2]))
    }

    @Test
    fun `formatPhoneGitSubline formats branch ahead behind and changed file counts`() {
        val branch = GitBranchState(
            head = "main",
            oid = "1234567",
            upstream = "origin/main",
            upstreamGone = false,
            ahead = 1,
            behind = 0,
        )
        val file = ChangedFile(
            path = "src/App.kt",
            staged = ChangeSide(added = 3, removed = 1, blocks = listOf("0")),
            unstaged = ChangeSide(added = 2, removed = 0, blocks = listOf("1")),
            conflict = false,
            x = "M",
            y = "M",
            untracked = false,
        )
        val groups = GitChangeGroups.from(listOf(file))
        assertEquals("main · ↑1 · 1 changed", formatPhoneGitSubline(branch, groups))
    }
}
