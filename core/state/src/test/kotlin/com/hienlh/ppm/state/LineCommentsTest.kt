package com.hienlh.ppm.state

import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.ReviewLineComment.Side
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** line-comments.test.tsx: what line comments become for the AI, and where the Review tab keeps them. */
class LineCommentsTest {
    private class Memory : KeyValueStore {
        val values = HashMap<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    @Test
    fun `review comments group by file in line order, quote each line, and leave out empty comments`() {
        val text = LineComments.formatReviewComments(
            listOf(
                ReviewLineComment("3", "src/b.ts", 9, Side.NEW, "  return x;", "Handle null"),
                ReviewLineComment("1", "src/a.ts", 12, Side.NEW, "const a = `x`;", "Rename\nthis"),
                ReviewLineComment("2", "src/a.ts", 4, Side.OLD, "old()", "Why was this removed?"),
                ReviewLineComment("4", "src/c.ts", 1, Side.NEW, "x", "   "),
            ),
        )
        assertEquals(
            listOf(
                "Review comments on the changes in this chat — please address each one:",
                "**src/b.ts**",
                "- line 9 `return x;`\n  Handle null",
                "**src/a.ts**",
                "- removed line 4 `old()`\n  Why was this removed?",
                "- line 12 ``const a = `x`;``\n  Rename\n  this",
            ).joinToString("\n\n"),
            text,
        )
        assertEquals("", LineComments.formatReviewComments(emptyList()))
    }

    @Test
    fun `a long line is cut to 300 characters in its quote`() {
        val text = LineComments.formatReviewComments(listOf(ReviewLineComment("1", "a", 1, Side.NEW, "x".repeat(400), "Too long")))
        assertEquals("- line 1 `" + "x".repeat(299) + "…`\n  Too long", text.split("\n\n")[2])
    }

    private val plan = listOf("# Plan", "1. Add a table", "2. Write tests")

    @Test
    fun `plan comments quote each commented line with its number, then the overall note`() {
        assertEquals(
            listOf(
                "Comments on the plan — revise it to address each one, then present it again:",
                "> 2: 1. Add a table\n\nUse the lazy table",
                "> 3: 2. Write tests\n\nTests first",
                "Also: Keep it small",
            ).joinToString("\n\n"),
            LineComments.formatPlanComments(plan, mapOf(2 to "Tests first", 1 to "Use the lazy table"), "Keep it small"),
        )
    }

    @Test
    fun `plan feedback is the overall note alone with no line comments, and empty with nothing`() {
        assertEquals("Simpler please", LineComments.formatPlanComments(plan, emptyMap(), " Simpler please "))
        assertEquals("", LineComments.formatPlanComments(plan, mapOf(9 to "out of range", 0 to " ")))
    }

    @Test
    fun `an ExitPlanMode call carries a plan, any other call or an empty plan none`() {
        assertEquals("# P", LineComments.planOf("ExitPlanMode", WireJson.parse("""{"plan":"# P"}""")))
        assertNull(LineComments.planOf("ExitPlanMode", WireJson.parse("""{"plan":"  "}""")))
        assertNull(LineComments.planOf("Bash", WireJson.parse("""{"plan":"# P"}""")))
        assertEquals(listOf("a", "b", ""), LineComments.planLines("a\r\nb\n"))
    }

    @Test
    fun `the store keeps one comment per id, drops an emptied one, and persists across a restart`() {
        val memory = Memory()
        val store = ReviewComments(memory)
        val c = ReviewLineComment("a", "x.ts", 1, Side.NEW, "q", "first")
        store.save("s1", c)
        store.save("s1", c.copy(body = "second"))
        assertEquals(listOf(c.copy(body = "second")), store.of("s1"))
        val saved = WireJson.parse(memory.values[ReviewComments.KEY]!!) as JsonObject
        assertEquals(c.copy(body = "second"), ReviewLineComment.from((saved["s1"] as kotlinx.serialization.json.JsonArray)[0]))
        assertEquals(listOf(c.copy(body = "second")), ReviewComments(memory).of("s1"))

        store.save("s1", c.copy(body = " "))
        assertNull(store.state.value["s1"])
        assertEquals(emptyMap<String, Any>(), ReviewComments(memory).state.value)
    }

    @Test
    fun `remove and clear, and only the newest 50 sessions are kept`() {
        val store = ReviewComments(Memory())
        val c = ReviewLineComment("a", "x.ts", 1, Side.NEW, "q", "b")
        store.save("s1", c)
        store.save("s1", c.copy(id = "b"))
        store.remove("s1", "a")
        assertEquals(listOf("b"), store.of("s1").map { it.id })
        store.clear("s1")
        assertNull(store.state.value["s1"])

        for (i in 0 until 51) store.save("s$i", c)
        assertEquals(50, store.state.value.size)
        assertNull(store.state.value["s0"])
        // Commenting again on an old session keeps it as the newest.
        store.save("s1", c.copy(id = "z"))
        store.save("s99", c)
        assertEquals(2, store.of("s1").size)
        assertNull(store.state.value["s2"])
    }

    @Test
    fun `a card's choice is a scope or a denial's instructions`() {
        assertEquals(WireJson.parse("""{"scope":"chat"}"""), ApprovalChoice.scope("chat"))
        assertEquals(WireJson.parse("""{"message":"use bun install"}"""), ApprovalChoice.message(" use bun install "))
        assertNull(ApprovalChoice.message("  "))
    }
}
