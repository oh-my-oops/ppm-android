package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The Review tab's blocks against the web's own answers for many pairs of texts, from
 * `android/scripts/gen-review-blocks-cases.ts`: a key worked out any differently here is one the
 * server never finds, so every block, row, key and span has to be the web's.
 */
class ReviewBlocksTest {
    private val cases = json(javaClass.getResource("/review-blocks-cases.json")!!.readText()).array!!

    private fun row(r: ReviewBlocks.Row) = listOf(r.kind.sign.toString(), r.text, r.o, r.n)

    private fun row(json: JsonElement) = listOf(json["k"].string, json["text"].string, json["o"].int, json["n"].int)

    @Test
    fun `every block is the web's, key included`() {
        var blocks = 0
        for (case in cases) {
            val name = case["name"].string
            val diff = ReviewBlocks.computeBlocks(case["base"].string!!, case["current"].string!!)!!
            val want = case["diff"]!!
            assertEquals("$name additions", want["additions"].int, diff.additions)
            assertEquals("$name deletions", want["deletions"].int, diff.deletions)
            assertEquals("$name oldLines", want["oldLines"].int, diff.oldLines)
            assertEquals("$name newLines", want["newLines"].int, diff.newLines)
            val wantBlocks = want["blocks"].array!!
            assertEquals("$name blocks", wantBlocks.size, diff.blocks.size)
            diff.blocks.zip(wantBlocks).forEachIndexed { i, (b, w) ->
                assertEquals("$name block $i key", w["key"].string, b.key)
                assertEquals("$name block $i range", listOf(w["oldFrom"].int, w["oldTo"].int, w["newFrom"].int, w["newTo"].int), listOf(b.oldFrom, b.oldTo, b.newFrom, b.newTo))
                assertEquals("$name block $i counts", listOf(w["added"].int, w["removed"].int), listOf(b.added, b.removed))
                assertEquals("$name block $i rows", w["rows"].array!!.map(::row), b.rows.map(::row))
                val spans = w["spans"].array!!.map { s -> s.array?.let { it[0].int!! until it[1].int!! } }
                assertEquals("$name block $i spans", spans, SessionReviewModel.changedSpans(b.rows))
            }
            blocks += diff.blocks.size
        }
        assertEquals(274, blocks)
    }

    @Test
    fun `every line map is the web's`() {
        for (case in cases) {
            val want = case["lineMap"].array!!.map { it.int }
            assertEquals(case["name"].string, want, ReviewBlocks.lineMap(case["base"].string!!, case["current"].string!!)!!.toList())
        }
    }

    @Test
    fun `split lines keeps terminators, CRLF and a last line without one`() {
        assertEquals(listOf("a\r\n", "b\n", "c"), ReviewBlocks.splitLines("a\r\nb\nc"))
        assertEquals(emptyList<String>(), ReviewBlocks.splitLines(""))
        assertEquals(listOf("\n", "\n"), ReviewBlocks.splitLines("\n\n"))
    }

    @Test
    fun `a block's key moves with its lines, not with lines elsewhere`() {
        val base = (1..40).joinToString("") { "line $it\n" }
        fun edit(text: String, at: Int, by: List<String>) = ReviewBlocks.splitLines(text).toMutableList().apply { removeAt(at); addAll(at, by.map { "$it\n" }) }.joinToString("")
        val once = edit(base, 30, listOf("agent 31"))
        val key = ReviewBlocks.computeBlocks(base, once)!!.blocks.single().key
        val moved = ReviewBlocks.computeBlocks(base, edit(once, 2, listOf("line 3", "inserted")))!!.blocks
        assertEquals(key, moved[1].key)
        assertNotEquals(key, ReviewBlocks.computeBlocks(base, edit(once, 30, listOf("agent 31 again")))!!.blocks.single().key)
    }

    @Test
    fun `block start is read off the key as parseInt reads it`() {
        assertEquals(12, ReviewBlocks.blockStart("12.abc"))
        assertEquals(0, ReviewBlocks.blockStart(SessionReviewModel.WHOLE_FILE))
        assertEquals(0, ReviewBlocks.blockStart("b1"))
    }
}
