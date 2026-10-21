package com.hienlh.ppm.chat

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string
import org.junit.Assert.assertEquals
import org.junit.Test

/** The preview's rows against the web's own, from `android/scripts/gen-edit-diff-cases.ts`. */
class EditDiffTest {
    @Test
    fun `rows are the web's, down to which of two equally short diffs is shown`() {
        val text = javaClass.getResource("/edit-diff-cases.json")!!.readText()
        val cases = WireJson.parse(text).array!!
        for (case in cases) {
            val old = case["old"].string!!
            val new = case["new"].string!!
            val expected = case["rows"].array!!.map { row ->
                val kind = when (row["kind"].string) {
                    "del" -> EditDiff.Kind.DEL
                    "ins" -> EditDiff.Kind.INS
                    else -> EditDiff.Kind.EQUAL
                }
                EditDiff.Row(kind, row["text"].string!!, row["ranges"].array!!.map { it.array!![0].int!! to it.array!![1].int!! })
            }
            val label = "${quoted(old)} -> ${quoted(new)}"
            assertEquals(label, expected, EditDiff.rows(old, new, Int.MAX_VALUE).rows)
            assertEquals(label, case["extra"].int, EditDiff.rows(old, new).extra)
        }
    }

    @Test
    fun `a rewrite too long to diff is one replacement between the shared head and tail`() {
        // jsdiff would keep "mid"; past the budget it goes with the rest.
        val old = listOf("head") + (1..3000).map { "old $it" } + "mid" + (1..3000).map { "old' $it" } + "end"
        val new = listOf("head") + (1..3000).map { "new $it" } + "mid" + (1..3000).map { "new' $it" } + "end"
        val (removed, inserted) = EditDiff.diff(old, new)
        assertEquals(listOf(false) + List(6001) { true } + false, removed.toList())
        assertEquals(listOf(false) + List(6001) { true } + false, inserted.toList())
    }

    private fun quoted(s: String) = "\"" + s.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\""
}
