package com.hienlh.ppm.state.git

import com.hienlh.ppm.client.ChangeSide
import com.hienlh.ppm.client.ChangedFile
import com.hienlh.ppm.client.GitBranchState
import com.hienlh.ppm.client.GitBranchStatus
import com.hienlh.ppm.client.GitChanges
import com.hienlh.ppm.client.GitFileChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [GitChangeGroups], [FileChange], and [PhoneGitCapabilities]
 * (Requirements 9.1, 9.2, 9.3, 9.6).
 */
class GitChangeGroupsTest {

    @Test
    fun `splits purely staged and purely unstaged files into Staged and Changes groups`() {
        val stagedOnly = ChangedFile(
            path = "src/server/index.ts",
            staged = ChangeSide(added = 12, removed = 3, blocks = listOf("b0")),
            unstaged = null,
            conflict = false,
            x = "M",
            y = ".",
            untracked = false,
        )
        val unstagedOnly = ChangedFile(
            path = "src/web/app.tsx",
            staged = null,
            unstaged = ChangeSide(added = 5, removed = 1, blocks = listOf("b0")),
            conflict = false,
            x = ".",
            y = "M",
            untracked = false,
        )
        val untrackedFile = ChangedFile(
            path = "notes/todo.md",
            staged = null,
            unstaged = ChangeSide(added = 20, removed = 0, blocks = emptyList(), whole = "new file"),
            conflict = false,
            x = "?",
            y = "?",
            untracked = true,
        )

        val groups = GitChangeGroups.from(listOf(stagedOnly, unstagedOnly, untrackedFile))

        assertEquals(1, groups.stagedCount)
        assertEquals(2, groups.changesCount)
        assertEquals(3, groups.totalFiles)
        assertTrue(groups.partiallyStagedPaths.isEmpty())

        val s = groups.staged.single()
        assertEquals("src/server/index.ts", s.path)
        assertEquals("index.ts", s.fileName)
        assertEquals("src/server", s.directoryPath)
        assertEquals(GitChangeGroup.STAGED, s.group)
        assertFalse(s.partiallyStaged)
        assertNull(s.partialLabel)
        assertEquals("src/server", s.subline)
        assertEquals(12 to 3, s.lineCounts)
        assertTrue(s.isChecked)
        assertEquals("staged", s.diffScope)

        val u = groups.changes[0]
        assertEquals("src/web/app.tsx", u.path)
        assertEquals(GitChangeGroup.CHANGES, u.group)
        assertFalse(u.partiallyStaged)
        assertNull(u.partialLabel)
        assertEquals(5 to 1, u.lineCounts)
        assertFalse(u.isChecked)
        assertEquals("worktree", u.diffScope)

        val untracked = groups.changes[1]
        assertEquals("notes/todo.md", untracked.path)
        assertEquals("A", untracked.statusLetter)
        assertTrue(untracked.untracked)
        assertTrue(untracked.canDiscard)
    }

    @Test
    fun `lists partially staged file in both Staged and Changes with per-side counts and labels`() {
        val partiallyStaged = ChangedFile(
            path = "src/server/routes/git.ts",
            staged = ChangeSide(added = 18, removed = 4, blocks = listOf("b0")),
            unstaged = ChangeSide(added = 7, removed = 2, blocks = listOf("b1")),
            conflict = false,
            x = "M",
            y = "M",
            untracked = false,
        )
        val changesPayload = GitChanges(
            branch = GitBranchState(
                head = "main",
                oid = "1234567",
                upstream = "origin/main",
                upstreamGone = false,
                ahead = 1,
                behind = 0,
            ),
            operation = null,
            lastCommit = null,
            files = listOf(partiallyStaged),
            truncated = false,
            stashes = 0,
        )

        val groups = GitChangeGroups.from(changesPayload)

        assertEquals(1, groups.stagedCount)
        assertEquals(1, groups.changesCount)
        assertEquals(1, groups.totalFiles)
        assertEquals(setOf("src/server/routes/git.ts"), groups.partiallyStagedPaths)

        val stagedRow = groups.staged.single()
        assertEquals("src/server/routes/git.ts", stagedRow.path)
        assertEquals(GitChangeGroup.STAGED, stagedRow.group)
        assertTrue(stagedRow.partiallyStaged)
        assertEquals("part of the file", stagedRow.partialLabel)
        assertEquals("src/server/routes · part of the file", stagedRow.subline)
        assertEquals(18 to 4, stagedRow.lineCounts)
        assertTrue(stagedRow.isChecked)

        val changesRow = groups.changes.single()
        assertEquals("src/server/routes/git.ts", changesRow.path)
        assertEquals(GitChangeGroup.CHANGES, changesRow.group)
        assertTrue(changesRow.partiallyStaged)
        assertEquals("rest of the file", changesRow.partialLabel)
        assertEquals("src/server/routes · rest of the file", changesRow.subline)
        assertEquals(7 to 2, changesRow.lineCounts)
        assertFalse(changesRow.isChecked)
    }

    @Test
    fun `staging or unstaging a partially staged file moves the whole file between groups`() {
        val partiallyStaged = ChangedFile(
            path = "src/server/routes/git.ts",
            staged = ChangeSide(added = 10, removed = 2, blocks = listOf("b0")),
            unstaged = ChangeSide(added = 5, removed = 3, blocks = listOf("b1")),
            conflict = false,
            x = "M",
            y = "M",
            untracked = false,
        )
        val initial = GitChangeGroups.from(listOf(partiallyStaged))
        assertEquals(1, initial.stagedCount)
        assertEquals(1, initial.changesCount)

        // Tapping checkbox on the Changes row stages the whole file
        val afterStage = initial.toggleFile(initial.changes.single())
        assertEquals(1, afterStage.stagedCount)
        assertEquals(0, afterStage.changesCount)
        val whollyStaged = afterStage.staged.single()
        assertFalse(whollyStaged.partiallyStaged)
        assertNull(whollyStaged.partialLabel)
        assertEquals(15 to 5, whollyStaged.lineCounts)

        // Tapping checkbox on the Staged row unstages the whole file
        val afterUnstage = initial.toggleFile(initial.staged.single())
        assertEquals(0, afterUnstage.stagedCount)
        assertEquals(1, afterUnstage.changesCount)
        val whollyUnstaged = afterUnstage.changes.single()
        assertFalse(whollyUnstaged.partiallyStaged)
        assertNull(whollyUnstaged.partialLabel)
        assertEquals(15 to 5, whollyUnstaged.lineCounts)
    }

    @Test
    fun `staged rename includes both new and old paths in unstagePaths`() {
        val renamed = ChangedFile(
            path = "src/NewName.kt",
            oldPath = "src/OldName.kt",
            staged = ChangeSide(added = 2, removed = 2, blocks = listOf("b0")),
            unstaged = null,
            conflict = false,
            x = "R",
            y = ".",
            untracked = false,
        )
        val groups = GitChangeGroups.from(listOf(renamed))
        val row = groups.staged.single()
        assertEquals("R", row.statusLetter)
        assertEquals(listOf("src/NewName.kt", "src/OldName.kt"), row.unstagePaths)
        assertEquals("src/OldName.kt → src/NewName.kt", row.subline)
    }

    @Test
    fun `status fallback also marks files present in both staged and unstaged as partiallyStaged`() {
        val status = GitBranchStatus(
            branch = "feat/mobile",
            ahead = 2,
            behind = 0,
            tracking = "origin/feat/mobile",
            staged = listOf(GitFileChange(path = "src/Shared.kt", status = "M")),
            unstaged = listOf(
                GitFileChange(path = "src/Shared.kt", status = "M"),
                GitFileChange(path = "src/OnlyUnstaged.kt", status = "M"),
            ),
            untracked = listOf("src/New.kt"),
        )

        val groups = GitChangeGroups.from(status)
        assertEquals(1, groups.stagedCount)
        assertEquals(3, groups.changesCount)
        assertTrue(groups.staged.single().partiallyStaged)
        assertTrue(groups.changes.first { it.path == "src/Shared.kt" }.partiallyStaged)
        assertFalse(groups.changes.first { it.path == "src/OnlyUnstaged.kt" }.partiallyStaged)
        assertTrue(groups.changes.first { it.path == "src/New.kt" }.untracked)
    }

    @Test
    fun `conflicted files stay in Changes with conflict badge and phone capabilities forbid tablet-only actions`() {
        val conflictFile = ChangedFile(
            path = "src/Conflict.kt",
            staged = null,
            unstaged = null,
            conflict = true,
            x = "U",
            y = "U",
            untracked = false,
        )
        val groups = GitChangeGroups.from(listOf(conflictFile))
        assertEquals(0, groups.stagedCount)
        assertEquals(1, groups.changesCount)
        assertTrue(groups.hasConflicts)
        assertEquals(1, groups.conflictCount)

        val row = groups.changes.single()
        assertTrue(row.conflict)
        assertEquals("U", row.statusLetter)
        assertEquals("conflict", row.partialLabel)
        assertFalse(row.canToggleStage)
        assertFalse(row.canDiscard)

        // A phone resolves conflicts, reviews, opens the graph and picks lines; Discard all, stash, worktrees and blame stay off.
        assertTrue(PhoneGitCapabilities.ALLOWS_BLOCK_OR_LINE_STAGING)
        assertTrue(PhoneGitCapabilities.ALLOWS_BLOCK_OR_LINE_DISCARD)
        assertFalse(PhoneGitCapabilities.ALLOWS_DISCARD_ALL)
        assertTrue(PhoneGitCapabilities.ALLOWS_REVIEW_CHANGES)
        assertTrue(PhoneGitCapabilities.ALLOWS_GIT_GRAPH)
        assertFalse(PhoneGitCapabilities.ALLOWS_STASH)
        assertFalse(PhoneGitCapabilities.ALLOWS_WORKTREES)
        assertTrue(PhoneGitCapabilities.ALLOWS_CONFLICT_RESOLUTION)
        assertFalse(PhoneGitCapabilities.ALLOWS_BLAME)
        assertFalse(PhoneGitCapabilities.CONFLICT_PAUSE_BANNER.contains("tablet"))
        // `git-change-row.tsx`: a conflict offers Resolve conflict, Open file, Mark as resolved.
        assertEquals(
            listOf(PhoneFileActionKind.RESOLVE_CONFLICT, PhoneFileActionKind.OPEN_FILE, PhoneFileActionKind.MARK_RESOLVED),
            PhoneGitCapabilities.actionsFor(row),
        )
    }

    @Test
    fun `phone row actions follow the web context menu and pick lines on the row's own side`() {
        val file = ChangedFile(
            path = "src/Both.kt",
            staged = ChangeSide(added = 1, removed = 0, blocks = listOf("b0")),
            unstaged = ChangeSide(added = 2, removed = 1, blocks = listOf("b1")),
            conflict = false,
            x = "M",
            y = "M",
            untracked = false,
        )
        val groups = GitChangeGroups.from(listOf(file))
        assertEquals(
            listOf(
                PhoneFileActionKind.REVIEW_CHANGES,
                PhoneFileActionKind.OPEN_DIFF,
                PhoneFileActionKind.OPEN_FILE,
                PhoneFileActionKind.UNSTAGE_FILE,
                PhoneFileActionKind.UNSTAGE_LINES,
                // The file still has unstaged changes, so its staged row can discard them too (`hasUnstaged`).
                PhoneFileActionKind.DISCARD_CHANGES,
            ),
            PhoneGitCapabilities.actionsFor(groups.staged.single()),
        )
        assertEquals(
            listOf(
                PhoneFileActionKind.REVIEW_CHANGES,
                PhoneFileActionKind.OPEN_DIFF,
                PhoneFileActionKind.OPEN_FILE,
                PhoneFileActionKind.STAGE_FILE,
                PhoneFileActionKind.STAGE_LINES,
                PhoneFileActionKind.DISCARD_CHANGES,
            ),
            PhoneGitCapabilities.actionsFor(groups.changes.single()),
        )
    }
}
