package com.hienlh.ppm.chat

import com.hienlh.ppm.protocol.WireJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `design_screenshot` calls and results, as `collab-design-shot.ts` writes them. */
class DesignShotCardTest {
    private fun json(text: String) = WireJson.parse(text)

    private val idA = "0123456789abcdef0123456789abcdef"
    private val idB = "fedcba9876543210fedcba9876543210"

    private val listing = """{
  "project": "web",
  "shots": [
    { "id": "$idA", "design": "landing", "file": "index.html", "label": "Hero", "width": 1280, "height": 900 },
    { "id": "$idB", "design": "landing", "file": "variant-2.html", "label": "", "width": 1280, "height": 900 }
  ]
}"""

    @Test
    fun `the tool under every provider's name, the arguments wrapped or not`() {
        val args = json("""{"design":"landing"}""")
        assertEquals(DesignShotCall("landing"), designShotCall("mcp__ppm-sessions__design_screenshot", args))
        assertEquals(DesignShotCall("landing"), designShotCall("mcp_ppm-sessions_design_screenshot", args))
        val wrapped = json("""{"server":"ppm_sessions","tool":"design_screenshot","arguments":{"design":"landing"}}""")
        assertEquals(DesignShotCall("landing"), designShotCall("ppm_sessions:design_screenshot", wrapped))
        // Still the card while the input streams in.
        assertEquals(DesignShotCall(""), designShotCall("mcp__ppm-sessions__design_screenshot", null))
    }

    @Test
    fun `other tools are not a design screenshot`() {
        val args = json("""{"design":"landing"}""")
        assertNull(designShotCall("mcp__ppm-sessions__session_status", args))
        assertNull(designShotCall("mcp__ppm-design__design_screenshot", args))
        assertNull(designShotCall("mcp__ppm-sessions__design_screenshot\n", args))
        // The session card does not take it either: it keeps the ordinary path.
        assertNull(sessionCollabToolCall("mcp__ppm-sessions__design_screenshot", args))
    }

    @Test
    fun `the shots come from the first text block, with the pictures after it ignored`() {
        val blocks = (
            kotlinx.serialization.json.JsonArray(
                listOf(
                    kotlinx.serialization.json.buildJsonObject {
                        put("type", kotlinx.serialization.json.JsonPrimitive("text"))
                        put("text", kotlinx.serialization.json.JsonPrimitive(listing))
                    },
                    kotlinx.serialization.json.buildJsonObject {
                        put("type", kotlinx.serialization.json.JsonPrimitive("text"))
                        put("text", kotlinx.serialization.json.JsonPrimitive("index.html (Hero):"))
                    },
                    kotlinx.serialization.json.buildJsonObject {
                        put("type", kotlinx.serialization.json.JsonPrimitive("text"))
                        put("text", kotlinx.serialization.json.JsonPrimitive("[image · 120KB]"))
                    },
                ),
            )
        ).toString()
        assertEquals(
            listOf(
                DesignShot(idA, "landing", "index.html", "Hero"),
                DesignShot(idB, "landing", "variant-2.html", null),
            ),
            designShots(blocks),
        )
    }

    @Test
    fun `a plain-text result reads the same`() {
        assertEquals(2, designShots(listing + "\n\nindex.html (Hero):")?.size)
    }

    @Test
    fun `an id that is not a shot id is dropped, and nothing readable is null`() {
        val bad = """{"shots":[{"id":"../../etc/passwd","file":"a.html"},{"id":"$idA","file":"b.html"}]}"""
        assertEquals(listOf(DesignShot(idA, "", "b.html", null)), designShots(bad))
        assertNull(designShots("""{"shots":[]}"""))
        assertNull(designShots("No screenshot was taken: the design has no variants"))
        assertNull(designShots("{not json"))
    }
}
