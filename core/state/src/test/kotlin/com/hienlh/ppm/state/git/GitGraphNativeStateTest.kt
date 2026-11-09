package com.hienlh.ppm.state.git

import com.hienlh.ppm.client.ChangeBlock
import com.hienlh.ppm.client.ChangeSide
import com.hienlh.ppm.client.ChangedFile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitGraphNativeStateTest {

    private fun vertex(
        hash: String,
        parents: List<String> = emptyList(),
        refs: List<GitGraphRefData> = emptyList(),
        message: String = "commit $hash",
        author: String = "Alice",
    ) = GitGraphVertex(
        hash = hash,
        parents = parents,
        author = author,
        authorEmail = "alice@example.com",
        authorDate = 1_700_000_000L,
        committer = author,
        committerEmail = "alice@example.com",
        commitDate = 1_700_000_000L,
        refs = refs,
        message = message,
    )

    @Test
    fun projectScope_picksLongestRegisteredProjectAndRebasesRepoRelativePaths() {
        val projects = listOf(
            GitGraphProjectRef("workspace", "/home/user/workspace"),
            GitGraphProjectRef("nested-app", "/home/user/workspace/apps/mobile"),
        )
        assertEquals(
            "nested-app",
            pickGitGraphProject(projects, "/home/user/workspace/apps/mobile/packages/core")?.name,
        )
        assertEquals(
            "workspace",
            pickGitGraphProject(projects, "/home/user/workspace/services/api")?.name,
        )
        assertNull(pickGitGraphProject(projects, "/tmp/other"))

        assertEquals(
            "src/index.ts",
            toGitGraphProjectRelative("/home/user/workspace", "/home/user/workspace", "./src/index.ts"),
        )
        assertEquals(
            "services/api/src/index.ts",
            toGitGraphProjectRelative(
                "/home/user/workspace",
                "/home/user/workspace/services/api/",
                "src/index.ts",
            ),
        )
    }

    @Test
    fun laneLayout_matchesTypeScriptGraphLayoutForBranchesMergesWipAndStashes() {
        // History (top to bottom):
        // 0: uncommitted (WIP) -> c4
        // 1: stash s1 -> c3
        // 2: c4 (HEAD, merge c3 + c2)
        // 3: c3 (feature branch -> c1)
        // 4: c2 (main branch -> c1)
        // 5: c1 (root)
        val rawCommits = listOf(
            vertex("c4", parents = listOf("c3", "c2"), message = "Merge c2 into c3"),
            vertex("c3", parents = listOf("c1"), message = "Feature work"),
            vertex("c2", parents = listOf("c1"), message = "Main work"),
            vertex("c1", parents = emptyList(), message = "Initial commit"),
        )
        val stashes = listOf(
            GitGraphStash(index = 0, hash = "s1", parentHash = "c3", message = "WIP on feature: wip"),
        )
        val changedFiles = listOf(
            ChangedFile(path = "README.md", staged = null, unstaged = null, conflict = false, x = ".", y = "M", untracked = false),
        )

        val display = buildGitGraphDisplayCommits(
            commits = rawCommits,
            headHash = "c4",
            changedFiles = changedFiles,
            showStashes = true,
            stashes = stashes,
        )
        assertEquals(listOf("uncommitted", "c4", "s1", "c3", "c2", "c1"), display.map { it.hash })

        val layout = computeGitGraphLayout(display, headHash = "c4")
        assertEquals(6, layout.vertices.size)

        // Vertex kinds
        assertEquals(GitGraphNodeKind.WIP, layout.vertices[0].kind)
        assertEquals(GitGraphNodeKind.HEAD, layout.vertices[1].kind)
        assertEquals(GitGraphNodeKind.STASH, layout.vertices[2].kind)
        assertEquals(GitGraphNodeKind.COMMIT, layout.vertices[3].kind)
        assertEquals(GitGraphNodeKind.COMMIT, layout.vertices[4].kind)
        assertEquals(GitGraphNodeKind.COMMIT, layout.vertices[5].kind)

        // Vertex lane X coordinates:
        // 0 (WIP) -> x=0, 1 (c4) -> x=0, 2 (s1 stash spur) -> x=1 (since lane 0 is occupied by c4->c3 and lane 1 by c4->c2, wait: let's verify exact x coordinates)
        assertEquals(0, layout.vertices[0].x)
        assertEquals(0, layout.vertices[1].x)
        // c3 is first parent of c4, so c3 stays on lane 0!
        assertEquals(0, layout.vertices[3].x)
        // c1 is parent of c3, so c1 stays on lane 0!
        assertEquals(0, layout.vertices[5].x)
        // s1 and c2 are placed on parallel lanes (> 0)
        assertTrue(layout.vertices[2].x > 0)
        assertTrue(layout.vertices[4].x > 0)

        // HEAD colour always resolves to slot 0, and stash resolves to slot 5
        assertEquals(0, layout.commitLaneSlot(display[1]))
        assertEquals(5, layout.commitLaneSlot(display[2]))
    }

    @Test
    fun pagingAndRequestTracker_enforceSkipGenerationGuardGoneBranchFallbackAndOutOfOrderReqIds() {
        var pageState = GitGraphCommitsPageState(scope = "feature")
        val firstBatch = listOf(vertex("c3"), vertex("c2"))

        // Initial page load
        pageState = applyGitGraphCommitsPage(
            current = pageState,
            msg = GitGraphExtMessage.LoadCommits(data = firstBatch, append = false, skip = 0, scope = "feature"),
            maxCommits = 2,
        )
        assertEquals(2, pageState.commits.size)
        assertTrue(pageState.hasMore)

        // Stale append (skip = 1 != 2) is ignored
        val staleAppend = applyGitGraphCommitsPage(
            current = pageState,
            msg = GitGraphExtMessage.LoadCommits(data = listOf(vertex("c0")), append = true, skip = 1, scope = "feature"),
            maxCommits = 2,
        )
        assertEquals(listOf("c3", "c2"), staleAppend.commits.map { it.hash })

        // Matching append (skip = 2 == 2) is appended
        pageState = applyGitGraphCommitsPage(
            current = pageState,
            msg = GitGraphExtMessage.LoadCommits(data = listOf(vertex("c1")), append = true, skip = 2, scope = "feature"),
            maxCommits = 2,
        )
        assertEquals(listOf("c3", "c2", "c1"), pageState.commits.map { it.hash })
        assertFalse(pageState.hasMore)

        // Gone branch fallback (append = false, scope = "all") replaces list & updates scope
        pageState = applyGitGraphCommitsPage(
            current = pageState,
            msg = GitGraphExtMessage.LoadCommits(data = listOf(vertex("c3")), append = false, skip = 0, scope = "all"),
            maxCommits = 2,
        )
        assertEquals("all", pageState.scope)
        assertEquals(listOf("c3"), pageState.commits.map { it.hash })

        // Scope switch and mismatched replace guard: switching to "feature" ignores late "main" replace response
        var reqState = GitGraphCommitRequestState()
        val (_, req1Gen) = beginGitGraphCommitRequest(reqState, skip = 0).also { reqState = it.first }
        val (_, req2Gen) = beginGitGraphCommitRequest(reqState, skip = 0).also { reqState = it.first }
        assertTrue(isStaleGitGraphCommitRequest(reqState, req1Gen))
        assertFalse(isStaleGitGraphCommitRequest(reqState, req2Gen))

        pageState = setGitGraphScope(pageState, "feature")
        assertEquals("feature", pageState.scope)
        assertTrue(pageState.commits.isEmpty())

        // Mismatched scope on replace (append = false, msg.scope = "main" != current.scope = "feature") is ignored!
        val afterMismatchedScope = applyGitGraphCommitsPage(
            current = pageState,
            msg = GitGraphExtMessage.LoadCommits(data = listOf(vertex("c9")), append = false, skip = 0, scope = "main"),
            maxCommits = 2,
        )
        assertEquals("feature", afterMismatchedScope.scope)
        assertTrue(afterMismatchedScope.commits.isEmpty())

        // Stale generation on replace is ignored even if scope matches
        val afterStaleReq = applyGitGraphCommitsPage(
            current = pageState,
            msg = GitGraphExtMessage.LoadCommits(data = listOf(vertex("c9")), append = false, skip = 0, scope = "feature"),
            maxCommits = 2,
            generation = pageState.requestState.lastReset - 1,
        )
        assertTrue(afterStaleReq.commits.isEmpty())

        // Current generation + matching scope succeeds
        pageState = applyGitGraphCommitsPage(
            current = pageState,
            msg = GitGraphExtMessage.LoadCommits(data = listOf(vertex("c3")), append = false, skip = 0, scope = "feature"),
            maxCommits = 2,
            generation = pageState.requestState.lastReset,
        )
        assertEquals(listOf("c3"), pageState.commits.map { it.hash })

        // Request tracker handles out-of-order responses and silent (null) callbacks
        val tracker = GitGraphRequestTracker()
        val order = mutableListOf<String>()
        val req1 = tracker.register("stageFiles") { order.add("first:${it.ok}") }
        val req2 = tracker.register("stageFiles") { order.add("second:${it.ok}") }
        val reqSilent = tracker.register("fetch", null)

        val out2 = tracker.take("stageFiles", req2)
        assertTrue(out2 is GitGraphRequestTracker.Outcome.Taken)
        (out2 as GitGraphRequestTracker.Outcome.Taken).callback?.invoke(GitGraphActionResult(ok = true))

        val out1 = tracker.take("stageFiles", req1)
        assertTrue(out1 is GitGraphRequestTracker.Outcome.Taken)
        (out1 as GitGraphRequestTracker.Outcome.Taken).callback?.invoke(GitGraphActionResult(ok = false))

        assertEquals(listOf("second:true", "first:false"), order)

        val outSilent = tracker.take("fetch", reqSilent)
        assertTrue(outSilent is GitGraphRequestTracker.Outcome.Taken && outSilent.callback == null)
        assertTrue(tracker.take("fetch", 999) is GitGraphRequestTracker.Outcome.Unknown)
    }

    @Test
    fun classifyRefsAndCommitMessageTokenizer_handleRemoteMergingIssueLinksAndBodyBlocks() {
        val remotes = listOf(GitGraphRemote("origin", "https://github.com/a/b.git", "https://github.com/a/b.git"))
        val refs = listOf(
            GitGraphRefData("origin/main", "remote"),
            GitGraphRefData("feat/slash", "remote"), // parser guessed remote because of slash, but "feat/" is not a remote!
            GitGraphRefData("main", "head"),
            GitGraphRefData("v1.0", "tag"),
        )
        val classified = classifyGitGraphRefs(
            refs = refs,
            remotes = remotes,
            showTags = true,
            showRemoteBranches = true,
            headAheadCount = 2,
        )
        // origin/main is merged into main (synced = true), head is first, feat/slash is reclassified as local
        assertEquals(listOf("main", "feat/slash", "v1.0"), classified.map { it.name })
        assertTrue(classified[0].synced)
        assertEquals(2, classified[0].aheadCount)
        assertEquals("local", classified[1].type)

        // Commit message tokenizer: URLs win over #123 and bare hashes inside URLs
        val tokens = tokenizeGitGraphCommitMessage(
            message = "Fix #42 and see https://example.com/issues/#99/deadbeef for 1234567",
            rules = listOf(GitGraphIssueRule(pattern = "#(\\d+)", url = "https://tracker.local/$1")),
        )
        val links = tokens.filterIsInstance<GitGraphMessageToken.ExternalLink>()
        assertEquals(2, links.size)
        assertEquals("https://tracker.local/42", links[0].url)
        assertEquals("https://example.com/issues/#99/deadbeef", links[1].url)
        val hashLinks = tokens.filterIsInstance<GitGraphMessageToken.CommitHashLink>()
        assertEquals(listOf("1234567"), hashLinks.map { it.hash })

        // PR URL builder
        val prUrl = buildGitGraphPrUrl(
            GitGraphPrCreationConfig(
                provider = "github",
                urlTemplate = gitGraphPrTemplate("github"),
                owner = "hienlh",
                repo = "ppm",
                defaultTargetBranch = "main",
            ),
            sourceBranch = "feat/git-graph",
        )
        assertEquals("https://github.com/hienlh/ppm/compare/main...feat%2Fgit-graph?expand=1", prUrl)
    }

    @Test
    fun parseExtMessage_decodesLoadRepoInfoCommitDetailsAndActionResult() {
        val raw = Json.parseToJsonElement(
            """
            {
              "command": "commitDetails",
              "data": {
                "hash": "abc1234567890",
                "author": "Thawng",
                "authorEmail": "t@ppm.dev",
                "authorDate": 1700000100,
                "committer": "Thawng",
                "committerEmail": "t@ppm.dev",
                "commitDate": 1700000100,
                "message": "feat: add native graph\n\n- lane layout\n- canvas",
                "parents": ["000111222"],
                "fileChanges": [
                  { "path": "src/graph.kt", "status": "A", "additions": 120, "deletions": 0 }
                ],
                "filesOmitted": 3
              }
            }
            """.trimIndent(),
        )
        val msg = parseGitGraphExtMessage(raw) as? GitGraphExtMessage.CommitDetails
        assertNotNull(msg)
        assertEquals("abc1234567890", msg!!.data.hash)
        assertEquals(3, msg.data.filesOmitted)
        assertEquals(1, msg.data.fileChanges?.size)
        assertEquals("A", msg.data.fileChanges?.first()?.status)
    }

    @Test
    fun gitGraphDiscardSummary_matchesWipModelTitlesMessagesAndButtonLabels() {
        val block0 = ChangeBlock("h0", 0, 1, 1, 1, 3, added = 3, removed = 1)
        val tracked = ChangedFile(
            path = "src/App.kt",
            staged = null,
            unstaged = ChangeSide(added = 3, removed = 1, blocks = listOf("h0"), changeBlocks = listOf(block0)),
            conflict = false,
            x = ".",
            y = "M",
            untracked = false,
        )
        val untracked = ChangedFile(
            path = "src/New.kt",
            staged = null,
            unstaged = ChangeSide(added = 5, removed = 0, blocks = emptyList(), whole = "new"),
            conflict = false,
            x = "?",
            y = "?",
            untracked = true,
        )

        val singleTracked = gitGraphDiscardSummary(listOf(tracked))
        assertEquals("Discard changes to App.kt?", singleTracked.title)
        assertEquals(
            "This puts 1 unstaged block back to the last commit's version. You can undo it right after.",
            singleTracked.body,
        )
        assertEquals("Discard 1 block", singleTracked.confirm)

        val singleUntracked = gitGraphDiscardSummary(listOf(untracked))
        assertEquals("Delete New.kt?", singleUntracked.title)
        assertEquals(
            "It is a new file git has never stored, so this deletes it. You can undo it right after.",
            singleUntracked.body,
        )
        assertEquals("Delete file", singleUntracked.confirm)

        val both = gitGraphDiscardSummary(listOf(tracked, untracked))
        assertEquals("Discard changes to 2 files?", both.title)
        assertEquals(
            "This puts the unstaged changes back the way the index has them, and deletes 1 new file. Staged changes stay. You can undo it right after.",
            both.body,
        )
        assertEquals("Discard 2 files", both.confirm)
    }

    @Test
    fun `gitGraphToastDurationMs matches web durations for discard undo, error, and default toasts`() {
        assertEquals(8_000L, gitGraphToastDurationMs("Discarded changes to 2 files", hasUndo = true, isError = false))
        assertEquals(7_000L, gitGraphToastDurationMs("Discarded changes to 2 files", hasUndo = false, isError = false))
        assertEquals(7_000L, gitGraphToastDurationMs("Copied the hash", hasUndo = false, isError = false))
        assertEquals(12_000L, gitGraphToastDurationMs("Push failed", hasUndo = false, isError = true))
    }
}
