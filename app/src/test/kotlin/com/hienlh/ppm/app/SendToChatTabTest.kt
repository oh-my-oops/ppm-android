package com.hienlh.ppm.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat Send to Chat opens when no chat takes the text (`sendToChat` in the web's `send-to-chat.ts`). */
class SendToChatTabTest {
    @Test
    fun `it is a plain chat on no project, which names its project only in its metadata`() {
        val tab = sendToChatTab("npm test output", "Terminal output", "ppm", asContext = false)
        assertEquals("chat", tab.type)
        assertEquals("Chat", tab.title)
        assertNull(tab.projectId)
        assertTrue(tab.closable)
        assertEquals(
            mapOf("projectName" to JsonPrimitive("ppm"), "pendingMessage" to JsonPrimitive("npm test output")),
            tab.metadata.toMap(),
        )
        assertEquals(listOf("projectName", "pendingMessage"), tab.metadata.keys.toList())
    }

    @Test
    fun `a context chip carries the text and its label instead of a message`() {
        val tab = sendToChatTab("npm test output", "Terminal output", "ppm", asContext = true)
        assertNull(tab.projectId)
        assertEquals(
            mapOf(
                "projectName" to JsonPrimitive("ppm"),
                "pendingContexts" to JsonArray(listOf(buildJsonObject {
                    put("text", "npm test output")
                    put("label", "Terminal output")
                })),
            ),
            tab.metadata.toMap(),
        )
    }

    @Test
    fun `with no project, or an empty one, the chat names none anywhere`() {
        for (project in listOf(null, "")) {
            val tab = sendToChatTab("ls", "Terminal output", project, asContext = false)
            assertNull(tab.projectId)
            assertEquals(mapOf("pendingMessage" to JsonPrimitive("ls")), tab.metadata.toMap())
        }
    }
}
