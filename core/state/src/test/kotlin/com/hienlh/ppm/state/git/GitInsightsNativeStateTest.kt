package com.hienlh.ppm.state.git

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitInsightsNativeStateTest {

    @Test
    fun `parses git blame porcelain, computes age weights, and interpolates heat color`() {
        val porcelain = """
            aaaaaaaa11111111aaaaaaaa11111111aaaaaaaa 1 1 2
            author Alice Nguyen
            author-mail <alice@ppm.dev>
            author-time 1700000000
            summary feat: initial file
            filename src/old-name.ts
            ${"\t"}const a = 1;
            aaaaaaaa11111111aaaaaaaa11111111aaaaaaaa 2 2
            ${"\t"}const b = 2;
            bbbbbbbb22222222bbbbbbbb22222222bbbbbbbb 3 3 1
            author Bob Tran
            author-mail <bob@ppm.dev>
            author-time 1700001000
            summary fix: update line 3
            boundary
            ${"\t"}const c = 3;
            0000000000000000000000000000000000000000 4 4 1
            ${"\t"}const d = 4;
        """.trimIndent()

        val result = parseBlamePorcelain(porcelain)
        assertEquals(4, result.lines.size)
        assertEquals("const a = 1;", result.lines[0].content)
        assertEquals(1, result.lines[0].finalLine)
        assertEquals("Alice Nguyen", result.commits["aaaaaaaa11111111aaaaaaaa11111111aaaaaaaa"]?.author)
        assertEquals("alice@ppm.dev", result.commits["aaaaaaaa11111111aaaaaaaa11111111aaaaaaaa"]?.authorMail)
        assertEquals("src/old-name.ts", result.commits["aaaaaaaa11111111aaaaaaaa11111111aaaaaaaa"]?.filename)
        assertTrue(result.commits["bbbbbbbb22222222bbbbbbbb22222222bbbbbbbb"]?.boundary == true)
        assertTrue(isGitBlameUncommittedHash("0000000000000000000000000000000000000000"))

        val weights = computeBlameAgeWeights(result.commits)
        assertEquals(0.0, weights["aaaaaaaa11111111aaaaaaaa11111111aaaaaaaa"] ?: -1.0, 0.001)
        assertEquals(1.0, weights["bbbbbbbb22222222bbbbbbbb22222222bbbbbbbb"] ?: -1.0, 0.001)
        assertEquals(1.0, weights["0000000000000000000000000000000000000000"] ?: -1.0, 0.001)

        assertEquals(Triple(59, 130, 246), blameHeatColorRgb(0.0))
        assertEquals(Triple(239, 68, 68), blameHeatColorRgb(1.0))
    }

    @Test
    fun `parses file history records with rename status and builds line range arg`() {
        val raw = buildString {
            append(FILE_HISTORY_RECORD_SEP)
            append("c2222222").append(FILE_HISTORY_FIELD_SEP)
            append("c1111111").append(FILE_HISTORY_FIELD_SEP)
            append("Alice").append(FILE_HISTORY_FIELD_SEP)
            append("alice@ppm.dev").append(FILE_HISTORY_FIELD_SEP)
            append("1700000200").append(FILE_HISTORY_FIELD_SEP)
            append("refactor: rename helper\n")
            append("R100\tsrc/old.ts\tsrc/new.ts\n")
            append(FILE_HISTORY_RECORD_SEP)
            append("c1111111").append(FILE_HISTORY_FIELD_SEP)
            append("").append(FILE_HISTORY_FIELD_SEP)
            append("Bob").append(FILE_HISTORY_FIELD_SEP)
            append("bob@ppm.dev").append(FILE_HISTORY_FIELD_SEP)
            append("1700000100").append(FILE_HISTORY_FIELD_SEP)
            append("feat: add old.ts\n")
            append("A\tsrc/old.ts\n")
        }

        val entries = parseFileHistory(raw)
        assertEquals(2, entries.size)
        assertEquals("R", entries[0].status)
        assertEquals("src/old.ts", entries[0].oldPath)
        assertEquals("src/new.ts", entries[0].path)
        assertEquals("A", entries[1].status)
        assertEquals("-L10,25:src/new.ts", buildLineRangeArg(25, 10, "src/new.ts"))
    }

    @Test
    fun `parses compare numstat-z, ahead-behind counts, and ref list`() {
        val numstat = "12\t3\tsrc/App.tsx\u0000-\t-\tassets/logo.png\u00005\t2\t\u0000src/old.ts\u0000src/new.ts\u0000"
        val files = parseNumstatZ(numstat)
        assertEquals(3, files.size)
        assertEquals("src/App.tsx", files[0].path)
        assertEquals(12, files[0].additions)
        assertEquals(3, files[0].deletions)
        assertFalse(files[0].binary)
        assertTrue(files[1].binary)
        assertEquals("src/new.ts", files[2].path)
        assertEquals("src/old.ts", files[2].oldPath)

        val counts = parseAheadBehind("4\t9\n")
        assertEquals(4, counts.behind)
        assertEquals(9, counts.ahead)
        assertEquals("main...feature/x", buildRangeSpec("main", "feature/x", "three-dot"))
        assertEquals("main..feature/x", buildRangeSpec("main", "feature/x", "two-dot"))

        val refs = parseRefList(
            "refs/heads/main\u001faaa111\u001fmain\n" +
                "refs/remotes/origin/main\u001faaa111\u001forigin/main\n" +
                "refs/tags/v1.0.0\u001fbbb222\u001fv1.0.0\n",
        )
        assertEquals(3, refs.size)
        assertEquals("head", refs[0].kind)
        assertEquals("remote", refs[1].kind)
        assertEquals("tag", refs[2].kind)
    }

    @Test
    fun `validates interactive rebase plan and builds todo with reword env vars`() {
        // Newest-first in UI
        val uiPlan = listOf(
            GitRebasePlanEntry(hash = "c3333333", subject = "feat: third", action = "squash"),
            GitRebasePlanEntry(hash = "c2222222", subject = "feat: second", action = "reword", message = "feat: second (updated)"),
            GitRebasePlanEntry(hash = "c1111111", subject = "feat: first", action = "pick"),
        )
        assertNull(validateRebasePlanNewestFirst("b0000000", uiPlan))

        // Oldest kept commit cannot be squash
        val invalidSquash = listOf(
            GitRebasePlanEntry(hash = "c2222222", subject = "feat: second", action = "pick"),
            GitRebasePlanEntry(hash = "c1111111", subject = "feat: first", action = "squash"),
        )
        assertNotNull(validateRebasePlanNewestFirst("b0000000", invalidSquash))

        val todoPlan = buildRebaseTodo(uiPlan.reversed())
        assertEquals(mapOf("PPM_REWORD_0" to "feat: second (updated)"), todoPlan.env)
        assertTrue(todoPlan.todo.contains("pick c1111111 feat: first"))
        assertTrue(todoPlan.todo.contains("pick c2222222 feat: second"))
        assertTrue(todoPlan.todo.contains("exec git commit --amend --allow-empty -m \"\$PPM_REWORD_0\""))
        assertTrue(todoPlan.todo.contains("squash c3333333 feat: third"))
    }

    @Test
    fun `parses reflog entries, filters by kind and text, and enforces reset hard refusal`() {
        val raw = buildString {
            append("a1111111").append(REFLOG_FIELD_SEP)
            append("HEAD@{0}").append(REFLOG_FIELD_SEP)
            append("rebase (finish): returning to refs/heads/main").append(REFLOG_FIELD_SEP)
            append("Alice").append(REFLOG_FIELD_SEP)
            append("alice@ppm.dev").append(REFLOG_FIELD_SEP)
            append("1700000300").append(REFLOG_FIELD_SEP)
            append("feat: login").append(REFLOG_RECORD_SEP)
            append("b2222222").append(REFLOG_FIELD_SEP)
            append("HEAD@{1}").append(REFLOG_FIELD_SEP)
            append("commit: fix header").append(REFLOG_FIELD_SEP)
            append("Alice").append(REFLOG_FIELD_SEP)
            append("alice@ppm.dev").append(REFLOG_FIELD_SEP)
            append("1700000200").append(REFLOG_FIELD_SEP)
            append("fix header").append(REFLOG_RECORD_SEP)
        }
        val entries = parseReflog(raw)
        assertEquals(2, entries.size)
        assertEquals("rebase", entries[0].kind)
        assertEquals("commit", entries[1].kind)
        assertEquals(1, filterReflogEntries(entries, "", "rebase").size)
        assertEquals(1, filterReflogEntries(entries, "header", "").size)

        assertTrue(isValidReflogSelector("HEAD@{0}"))
        assertFalse(isValidReflogSelector("HEAD@{0}..HEAD@{1}"))
        assertNull(resetHardRefusal(0, ""))
        assertNotNull(resetHardRefusal(0, " M src/App.tsx\n"))
        assertNotNull(resetHardRefusal(128, ""))

        val loadMsg = parseGitReflogExtMessage(
            Json.parseToJsonElement(
                """
                {
                  "command": "loadEntries",
                  "data": {
                    "branch": "main",
                    "truncated": false,
                    "entries": [
                      {
                        "hash": "a1111111",
                        "selector": "HEAD@{0}",
                        "action": "commit: init",
                        "kind": "commit",
                        "author": "Alice",
                        "authorEmail": "alice@ppm.dev",
                        "authorDate": 1700000100,
                        "subject": "init"
                      }
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )
        assertTrue(loadMsg is GitReflogExtMessage.LoadEntries)
    }
}
