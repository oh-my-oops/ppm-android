package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `routes/git.ts`: which repository a project's git runs in, the branch the status bar shows, and Source Control routes. */
class GitTest {
    private fun <T> withServer(vararg responses: MockResponse, block: suspend (MockWebServer, ApiClient) -> T): T = runBlocking {
        val server = MockWebServer()
        responses.forEach(server::enqueue)
        server.start()
        try {
            block(server, ApiClient(server.url("/"), "t"))
        } finally {
            server.close()
        }
    }

    private fun ok(data: String) = MockResponse.Builder().body("""{"ok":true,"data":$data}""").build()

    @Test
    fun `a project that is a repository is asked without a scope`() = withServer(
        ok("""{"root":"/w/web","rootIsRepo":true,"repos":[{"path":"/w/web","name":"web","relative":"."}]}"""),
        ok("""{"current":"main","ahead":2,"behind":0,"tracking":"origin/main","staged":[],"unstaged":[],"untracked":["a"]}"""),
    ) { server, api ->
        val repo = api.gitRepos("my web").resolved
        assertEquals(GitRepo("/w/web", "web", "."), repo)
        assertEquals(GitBranchStatus("main", 2, 0, "origin/main", untracked = listOf("a")), api.gitStatus("my web", repo))
        assertEquals("/api/project/my%20web/git/repos", server.takeRequest().target)
        assertEquals("/api/project/my%20web/git/status", server.takeRequest().target)
    }

    @Test
    fun `the only repository under a container folder is scoped by its path`() = withServer(
        ok("""{"root":"/w","rootIsRepo":false,"repos":[{"path":"/w/api server","name":"api server","relative":"api server"}]}"""),
        ok("""{"current":"dev","ahead":0,"behind":3,"tracking":null,"staged":[],"unstaged":[],"untracked":[]}"""),
    ) { server, api ->
        val repo = api.gitRepos("w").resolved
        assertEquals(GitBranchStatus("dev", 0, 3, null), api.gitStatus("w", repo))
        server.takeRequest()
        assertEquals("/api/project/w/git/status?repo=%2Fw%2Fapi%20server", server.takeRequest().target)
    }

    @Test
    fun `several repositories, or none, are a choice the app does not make`() {
        val two = GitRepos(false, listOf(GitRepo("/w/a", "a", "a"), GitRepo("/w/b", "b", "b")))
        assertNull(two.resolved)
        assertNull(GitRepos(false, emptyList()).resolved)
    }

    @Test
    fun `parses changes hunks refs stashes worktrees and actions with repo scope`() = withServer(
        ok(
            """
            {
              "branch": {"head":"feat/sc","oid":"1234567890abcdef","upstream":"origin/feat/sc","upstreamGone":false,"ahead":3,"behind":1},
              "operation": {"kind":"rebase","head":"abc1234","step":2,"total":5},
              "lastCommit": {"hash":"1234567890abcdef","shortHash":"1234567","subject":"feat: add sc","date":"2026-11-07T00:00:00Z","hasParent":true,"pushed":false},
              "files": [
                {"path":"src/App.kt","staged":{"status":"M","added":4,"removed":1,"blocks":["add","change"]},"unstaged":{"status":"M","added":2,"removed":0,"blocks":["add"]}},
                {"path":"CONFLICT.md","conflict":"UU"}
              ],
              "truncated": false,
              "stashes": 2
            }
            """.trimIndent(),
        ),
        ok(
            """
            {
              "path": "src/App.kt",
              "binary": false,
              "hunks": [
                {
                  "index": 0,
                  "header": "@@ -1,2 +1,3 @@",
                  "oldStart": 1,
                  "oldLines": 2,
                  "newStart": 1,
                  "newLines": 3,
                  "id": "hunk-0",
                  "lines": [
                    {"type":" ","content":"package app","oldNumber":1,"newNumber":1},
                    {"type":"+","content":"import kotlin.test.*","newNumber":2}
                  ]
                }
              ]
            }
            """.trimIndent(),
        ),
        ok("""{"staged":true}"""),
        ok("""{"id":"disc-1","createdAt":100,"files":["src/App.kt"],"skipped":[]}"""),
        ok("""{"hash":"abcdef1234567"}"""),
        ok(
            """
            [
              {"name":"main","kind":"branch","hash":"1111111","shortHash":"1111111","subject":"init","author":"Alice","date":"2026-11-07T00:00:00Z","current":true,"upstream":"origin/main","upstreamGone":false,"ahead":0,"behind":0}
            ]
            """.trimIndent(),
        ),
        ok(
            """
            [
              {"index":0,"hash":"aaaa111","message":"WIP on main","date":"2026-11-07T00:00:00Z"}
            ]
            """.trimIndent(),
        ),
        ok(
            """
            [
              {"path":"/work/ppm","head":"1111111","branch":"main","isMain":true,"isLocked":false,"prunable":false}
            ]
            """.trimIndent(),
        ),
    ) { server, api ->
        val nestedRepo = GitRepo("/work/ppm/apps/mobile", "mobile", "apps/mobile")
        val changes = api.gitChanges("ppm", nestedRepo)
        assertEquals("feat/sc", changes.branch.head)
        assertEquals("rebase", changes.operation?.kind)
        assertEquals(2, changes.operation?.step)
        assertEquals("1234567", changes.lastCommit?.shortHash)
        assertEquals(2, changes.files.size)
        assertEquals(4, changes.files[0].staged?.added)
        assertEquals("UU", changes.files[1].conflict)
        assertEquals(2, changes.stashes)

        val hunks = api.gitHunks("ppm", "src/App.kt", "worktree", nestedRepo)
        assertEquals(1, hunks.hunks.size)
        assertEquals("hunk-0", hunks.hunks[0].id)
        assertEquals(2, hunks.hunks[0].lines.size)

        api.gitStageHunks("ppm", "src/App.kt", listOf(HunkSelectionItem(0, "hunk-0", listOf(1))), nestedRepo)
        val discard = api.gitDiscard("ppm", listOf("src/App.kt"), nestedRepo)
        assertEquals("disc-1", discard?.id)

        val commit = api.gitCommit("ppm", "feat: commit", amend = false, signoff = true, repo = nestedRepo)
        assertEquals("abcdef1234567", commit.hash)

        val refs = api.gitRefs("ppm", nestedRepo)
        assertEquals(1, refs.size)
        assertTrue(refs[0].current)

        val stashes = api.gitStashes("ppm", nestedRepo)
        assertEquals(1, stashes.size)
        assertEquals("WIP on main", stashes[0].message)

        val worktrees = api.gitWorktrees("ppm", nestedRepo)
        assertEquals(1, worktrees.size)
        assertTrue(worktrees[0].isMain)

        assertEquals("/api/project/ppm/git/changes?repo=%2Fwork%2Fppm%2Fapps%2Fmobile", server.takeRequest().target)
        assertEquals("/api/project/ppm/git/hunks?path=src%2FApp.kt&scope=worktree&repo=%2Fwork%2Fppm%2Fapps%2Fmobile", server.takeRequest().target)
        val stageHunksReq = server.takeRequest()
        assertEquals("/api/project/ppm/git/stage-hunks?repo=%2Fwork%2Fppm%2Fapps%2Fmobile", stageHunksReq.target)
        assertTrue(stageHunksReq.body?.utf8()?.contains(""""lines":[1]""") == true)
    }

    @Test
    fun `parses server wire format for gitChanges, gitHunks, gitDiscard undo envelope, gitStash envelope, gitRefs, and gitWorktrees`() = withServer(
        ok(
            """
            {
              "branch": {"head":"main","oid":"ff1802272c2536b0eeb12285cb05bc5d7b874b75","upstream":null,"upstreamGone":false,"ahead":0,"behind":0,"hasRemote":false},
              "operation": null,
              "files": [
                {
                  "path": "src/App.kt",
                  "x": ".",
                  "y": "M",
                  "untracked": false,
                  "conflict": false,
                  "staged": null,
                  "unstaged": {
                    "blocks": [{"id":"1f2ca95832d8a1ee4d0cde66ca6e09e0","index":0,"oldStart":1,"oldLines":3,"newStart":1,"newLines":4,"added":2,"removed":1}],
                    "added": 2,
                    "removed": 1
                  }
                },
                {
                  "path": "README.md",
                  "x": "?",
                  "y": "?",
                  "untracked": true,
                  "conflict": false,
                  "staged": null,
                  "unstaged": {
                    "blocks": [{"id":"17fff46245253863bc28cbc79bb2b88a","index":0,"oldStart":0,"oldLines":0,"newStart":1,"newLines":1,"added":1,"removed":0}],
                    "added": 1,
                    "removed": 0
                  }
                }
              ],
              "stashes": 0,
              "lastCommit": {"hash":"ff1802272c2536b0eeb12285cb05bc5d7b874b75","subject":"chore: initial commit","author":"Test User","date":"2026-11-07T19:14:05+07:00","pushed":false,"hasParent":false},
              "truncated": false
            }
            """.trimIndent(),
        ),
        ok(
            """
            {
              "filePath": "src/App.kt",
              "scope": "worktree",
              "hunks": [
                {
                  "oldStart": 1,
                  "oldLines": 3,
                  "newStart": 1,
                  "newLines": 4,
                  "heading": "fun main()",
                  "lines": [
                    {"kind": " ", "text": "line 1"},
                    {"kind": "-", "text": "line 2"},
                    {"kind": "+", "text": "line 2 modified"}
                  ],
                  "id": "1f2ca95832d8a1ee4d0cde66ca6e09e0"
                }
              ],
              "binary": false
            }
            """.trimIndent(),
        ),
        ok(
            """
            {
              "discarded": ["src/App.kt"],
              "undo": {"id":"undo-99","createdAt":1700000000000,"kind":"files","paths":["src/App.kt"],"skipped":[]}
            }
            """.trimIndent(),
        ),
        ok(
            """
            {
              "stashed": true,
              "stash": {"index":0,"hash":"bbbb222","message":"WIP on main","date":"2026-11-07T10:00:00Z"}
            }
            """.trimIndent(),
        ),
        ok(
            """
            [
              {"refName":"refs/heads/main","name":"main","type":"branch","current":true,"hash":"ff18022","shortHash":"ff18022","subject":"init","author":"Alice","date":"2026-11-07T00:00:00Z","upstream":null,"ahead":0,"behind":0,"gone":true}
            ]
            """.trimIndent(),
        ),
        ok(
            """
            [
              {"path":"/tmp/ppm-s3-git-repo","branch":"","head":"ff18022","isMain":true,"isBare":false,"isDetached":true,"locked":true,"prunable":false}
            ]
            """.trimIndent(),
        ),
    ) { _, api ->
        val changes = api.gitChanges("s3-git")
        assertEquals(false, changes.branch.hasRemote)
        assertEquals(2, changes.files.size)
        assertEquals("M", changes.files[0].unstaged?.status)
        assertEquals(listOf("1f2ca95832d8a1ee4d0cde66ca6e09e0"), changes.files[0].unstaged?.blocks)
        assertEquals("A", changes.files[1].unstaged?.status)
        assertEquals(null, changes.files[0].conflict)

        val hunks = api.gitHunks("s3-git", "src/App.kt")
        assertEquals("src/App.kt", hunks.path)
        assertEquals("@@ -1,3 +1,4 @@ fun main()", hunks.hunks[0].header)
        assertEquals(1, hunks.hunks[0].lines[0].oldNumber)
        assertEquals(1, hunks.hunks[0].lines[0].newNumber)
        assertEquals(2, hunks.hunks[0].lines[1].oldNumber)
        assertEquals(null, hunks.hunks[0].lines[1].newNumber)
        assertEquals(null, hunks.hunks[0].lines[2].oldNumber)
        assertEquals(2, hunks.hunks[0].lines[2].newNumber)

        val discard = api.gitDiscard("s3-git", listOf("src/App.kt"))
        assertEquals("undo-99", discard?.id)
        assertEquals(listOf("src/App.kt"), discard?.files)

        val stash = api.gitStash("s3-git")
        assertEquals("bbbb222", stash?.hash)

        val refs = api.gitRefs("s3-git")
        assertEquals("branch", refs[0].kind)
        assertTrue(refs[0].upstreamGone)

        val worktrees = api.gitWorktrees("s3-git")
        assertEquals(null, worktrees[0].branch)
        assertTrue(worktrees[0].isLocked)
    }

    @Test
    fun `parses Phase 08b routes gitFileChanges, gitBranchDiff, gitGraph, gitFileFullDiff, gitDiff, and filesCompare`() = withServer(
        ok(
            """
            {
              "path":"src/App.kt","x":"M","y":"M","untracked":false,"conflict":false,
              "staged":{
                "added":1,"removed":0,
                "hunks":[
                  {"id":"s-hunk-1","index":0,"oldStart":1,"oldLines":1,"newStart":1,"newLines":2,"added":1,"removed":0,"heading":"","lines":[
                    {"kind":" ","text":"package demo"},
                    {"kind":"+","text":"import kotlin.math.max"}
                  ]}
                ]
              },
              "unstaged":{
                "added":1,"removed":1,
                "hunks":[
                  {"id":"u-hunk-1","index":0,"oldStart":5,"oldLines":1,"newStart":6,"newLines":1,"added":1,"removed":1,"heading":"fun main()","lines":[
                    {"kind":"-","text":"println(1)"},
                    {"kind":"+","text":"println(2)"}
                  ]}
                ]
              }
            }
            """.trimIndent(),
        ),
        ok(
            """
            {
              "base":"main","head":"feat/review","mode":"three-dot",
              "mergeBase":"abc1111","headCommit":"def2222","omitted":2,
              "files":[
                {"path":"src/App.kt","status":"M","additions":10,"deletions":3,"binary":false,"blob":"blob-app-1"},
                {"path":"logo.png","status":"A","additions":0,"deletions":0,"binary":true,"blob":"blob-logo-1"}
              ]
            }
            """.trimIndent(),
        ),
        ok(
            """
            {
              "head":"def2222000",
              "commits":[
                {"hash":"def2222000","abbreviatedHash":"def2222","subject":"feat: add review","body":"","authorName":"Dev","authorEmail":"dev@test","authorDate":"2026-11-07T10:00:00Z","parents":["abc1111000"],"refs":["HEAD -> feat/review","tag: v1.0"]}
              ],
              "branches":[
                {"name":"feat/review","current":true,"remote":false,"commitHash":"def2222000","ahead":1,"behind":0,"remotes":["origin"]}
              ]
            }
            """.trimIndent(),
        ),
        ok(
            """
            {"original":"old text","modified":"new text","binary":false,"originalSize":8,"modifiedSize":8}
            """.trimIndent(),
        ),
        ok("""{"diff":"diff --git a/a.kt b/a.kt\n@@ -1 +1 @@\n-old\n+new"}"""),
        ok("""{"original":"left","modified":"right"}"""),
    ) { server, api ->
        val detail = api.gitFileChanges("s4-git", "src/App.kt")
        assertEquals("src/App.kt", detail.path)
        assertEquals(1, detail.staged?.hunks?.size)
        assertEquals("s-hunk-1", detail.staged?.hunks?.get(0)?.id)
        assertEquals("u-hunk-1", detail.unstaged?.hunks?.get(0)?.id)
        assertEquals(2, detail.unstaged?.hunks?.get(0)?.lines?.size)

        val branchDiff = api.gitBranchDiff("s4-git", "main", "feat/review")
        assertEquals("abc1111", branchDiff.mergeBase)
        assertEquals("def2222", branchDiff.headCommit)
        assertEquals(2, branchDiff.omitted)
        assertEquals(2, branchDiff.files.size)
        assertEquals("blob-app-1", branchDiff.files[0].blob)
        assertTrue(branchDiff.files[1].binary)

        val graph = api.gitGraph("s4-git", max = 100, skip = 0)
        assertEquals("def2222000", graph.head)
        assertEquals(1, graph.commits.size)
        assertEquals(listOf("HEAD -> feat/review", "tag: v1.0"), graph.commits[0].refs)
        assertEquals(listOf("origin"), graph.branches[0].remotes)

        val fullDiff = api.gitFileFullDiff("s4-git", "src/App.kt", ref = "abc1111", ref2 = "def2222")
        assertEquals("old text", fullDiff.original)
        assertEquals("new text", fullDiff.modified)

        val rawDiff = api.gitDiff("s4-git", ref1 = "HEAD~1")
        assertTrue(rawDiff.contains("+new"))

        val compared = api.filesCompare("s4-git", "a.kt", "b.kt")
        assertEquals("left" to "right", compared)

        assertEquals("/api/project/s4-git/git/changes/file?path=src%2FApp.kt", server.takeRequest().target)
        assertEquals("/api/project/s4-git/git/branch-diff?base=main&head=feat%2Freview", server.takeRequest().target)
        assertEquals("/api/project/s4-git/git/graph?max=100&skip=0", server.takeRequest().target)
        assertEquals("/api/project/s4-git/git/file-full-diff?file=src%2FApp.kt&ref=abc1111&ref2=def2222", server.takeRequest().target)
    }
}
