package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.HistoryMessage
import com.hienlh.ppm.protocol.SessionFileChange
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Each turn's file changes, the session's writes, its turns and each turn's review against the
 * web's own answers for one transcript, from `android/scripts/gen-turn-changes-cases.ts`.
 */
class TurnChangesTest {
    private val cases = json(javaClass.getResource("/turn-changes-cases.json")!!.readText())
    private val items = cases["messages"].array!!.mapNotNull { HistoryMessage.from(it)?.let(ChatItem::fromHistory) }

    /** The runs of answers, each with the index its last one has. */
    private val turns: List<List<ChatItem>> = buildList {
        var run = mutableListOf<ChatItem>()
        for (item in items) {
            if (item.role == ChatItem.Role.ASSISTANT) run += item else if (run.isNotEmpty()) add(run).also { run = mutableListOf() }
        }
        if (run.isNotEmpty()) add(run)
    }

    private fun strings(json: JsonElement?) = json.array!!.map { it.string!! }

    @Test
    fun `each turn's changes are the web's`() {
        val expected = cases["turns"].array!!
        assertEquals(expected.size, turns.size)
        turns.zip(expected).forEach { (turn, case) ->
            val want = case["changes"].array!!.map { c ->
                listOf(
                    c["filePath"].string, c["op"].string, c["editCount"].int, c["linesAdded"].int, c["linesRemoved"].int, c["viaSubagent"].bool,
                    c["edits"].array!!.map { e -> listOf(e["oldStr"].string, e["newStr"].string, e["toolUseId"].string, e["editIndex"].int, e["editRef"].string, e["viaSubagent"].bool) },
                )
            }
            val got = TurnFileChanges.of(turn).map { c ->
                listOf(
                    c.filePath, c.op.name.lowercase(), c.editCount, c.linesAdded, c.linesRemoved, c.viaSubagent,
                    c.edits.map { e -> listOf(e.oldStr, e.newStr, e.toolUseId, e.editIndex, e.editRef, e.viaSubagent) },
                )
            }
            assertEquals(want, got)
        }
    }

    @Test
    fun `the session's writes and how many finished are the web's`() {
        val writes = cases["writes"]
        assertEquals(strings(writes["paths"]) to writes["settled"].int, SessionWrites().of(items))
    }

    @Test
    fun `a message walked once is not walked again while it stays the same`() {
        val writes = SessionWrites()
        val first = writes.of(items)
        assertEquals(first, writes.of(items.toList()))
        // A message replaced by a new one is read again.
        val last = items.indexOfLast { it.role == ChatItem.Role.ASSISTANT && it.blocks.any { b -> b is ChatBlock.Tool } }
        val dropped = items.toMutableList().also { it[last] = it[last].copy(blocks = emptyList()) }
        assertEquals(false, writes.of(dropped) == first)
    }

    @Test
    fun `the session's turns are the web's`() {
        val want = cases["sessionTurns"].array!!.map { t -> listOf(t["n"].int, t["messageId"].string, t["prompt"].string, strings(t["calls"])) }
        val got = SessionTurns.of(items).map { t -> listOf(t.n, t.messageId, t.prompt, t.calls) }
        assertEquals(want, got)
        assertEquals(listOf("Earlier turn", "Turn 1", "Turn 2", "Turn 3"), SessionTurns.of(items).map(SessionTurns::label))
    }

    @Test
    fun `each turn's review, summary and answers are the web's`() {
        val files = cases["files"].array!!.map { SessionFileChange.from(it)!! }
        val reverted = cases["reverted"].array!!.associate { it.array!![0].string!! to it.array!![1].string!! }
        turns.zip(cases["turns"].array!!).forEach { (turn, case) ->
            val reviews = TurnReview.editReviews(TurnFileChanges.of(turn), files, reverted)
            val want = case["reviews"].array!!.map { r ->
                listOf(r["key"].string, r["state"].string, r["path"].takeIf { it != JsonNull }?.string, strings(r["keys"]), r["undoId"].takeIf { it != JsonNull }?.string)
            }
            val got = reviews.map { (key, r) -> listOf(key, r.state?.name?.lowercase(), r.file?.path, r.keys, r.undoId) }
            assertEquals(want, got)
            val s = case["summary"]
            assertEquals(TurnReviewSummary(s["open"].int!!, s["kept"].int!!, s["reverted"].int!!, s["known"].int!!), TurnReview.summary(reviews))
            fun answers(json: JsonElement?) = json.array!!.map { listOf(it["path"].string, it["version"].string, strings(it["keys"])) }
            fun answers(files: List<com.hienlh.ppm.protocol.SessionAnswerFile>) = files.map { listOf(it.path, it.version, it.keys) }
            assertEquals(answers(case["keepOpen"]), answers(TurnReview.answerFiles(reviews.values.toList(), openOnly = true)))
            assertEquals(answers(case["all"]), answers(TurnReview.answerFiles(reviews.values.toList(), openOnly = false)))
        }
    }

    @Test
    fun `the pill's words`() {
        assertEquals(null, TurnReview.label(TurnReviewSummary()))
        assertEquals(TurnReviewLabel(TurnReviewLabel.Tone.TODO, "1 edit to review"), TurnReview.label(TurnReviewSummary(open = 1, kept = 2, known = 3)))
        assertEquals(TurnReviewLabel(TurnReviewLabel.Tone.TODO, "4 edits to review"), TurnReview.label(TurnReviewSummary(open = 4, known = 4)))
        assertEquals(TurnReviewLabel(TurnReviewLabel.Tone.REVERTED, "2 reverted"), TurnReview.label(TurnReviewSummary(kept = 1, reverted = 2, known = 3)))
        assertEquals(TurnReviewLabel(TurnReviewLabel.Tone.KEPT, "Kept"), TurnReview.label(TurnReviewSummary(kept = 3, known = 3)))
        assertEquals("1 edit in 1 file", TurnReview.editsInFiles(TurnFileChanges.of(turns[0])))
        assertEquals("7 edits in 4 files", TurnReview.editsInFiles(TurnFileChanges.of(turns[1])))
    }

    @Test
    fun `display paths and review marks`() {
        assertEquals("src/a.ts", SessionChangeList.displayPath("/p/src/a.ts", "/p/"))
        assertEquals("/pp/a.ts", SessionChangeList.displayPath("/pp/a.ts", "/p"))
        assertEquals("/p", SessionChangeList.displayPath("/p", "/p"))
        assertEquals("b.ts" to "C:\\x", SessionChangeList.splitPath("C:\\x\\b.ts"))
        assertEquals("b.ts" to "", SessionChangeList.splitPath("b.ts"))
        assertEquals("1 file", SessionChangeList.fileCount(1))
        val files = listOf(
            SessionFileChange("/a", SessionFileChange.Status.ADDED, 3, 0, version = "1", sinceReview = true),
            SessionFileChange("/b", SessionFileChange.Status.DELETED, null, null, version = "", reviewed = true),
        )
        assertEquals(3 to 0, SessionChangeList.totals(files))
        val marked = SessionChangeList.withReviewed(files, setOf("/a"), true)
        assertEquals(listOf(true, true), marked.map { it.reviewed })
        assertEquals(false, marked[0].sinceReview)
        assertEquals(listOf("/a") to listOf("/b"), SessionChangeList.splitReviewed(files).let { (p, r) -> p.map { it.path } to r.map { it.path } })
    }
}
