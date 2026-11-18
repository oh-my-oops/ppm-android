package com.hienlh.ppm.state.assistant

import com.hienlh.ppm.state.windows.Bounds
import com.hienlh.ppm.state.workspace.WorkspacePanel
import com.hienlh.ppm.state.workspace.WorkspaceState
import com.hienlh.ppm.state.workspace.WorkspaceTab
import com.hienlh.ppm.state.workspace.parsePpmLink
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** What this device answers the Assistant's screen tools with: its layout, its commands, its windows and links. */
class AssistantDeviceTest {
    private fun state(tabs: List<WorkspaceTab>) = WorkspaceState(
        panels = mapOf("p1" to WorkspacePanel("p1", tabs, activeTabId = tabs.firstOrNull()?.id)),
        grid = listOf(listOf("p1")),
        focusedPanelId = "p1",
        currentProject = "web",
    )

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((k, v) in pairs) when (v) {
            is String -> put(k, v)
            is Boolean -> put(k, v)
            null -> put(k, kotlinx.serialization.json.JsonNull)
        }
    }

    @Test
    fun `the layout goes out with only the metadata that names what a tab shows`() {
        val chat = WorkspaceTab(
            "chat:1", "chat", "Fix it", "web",
            buildJsonObject {
                put("sessionId", "s1")
                put("providerId", "claude")
                put("pendingMessage", "a secret draft")
            },
        )
        val editor = WorkspaceTab("editor:a", "editor", "a.ts", "web", buildJsonObject { put("filePath", "src/a.ts"); put("unsavedContent", "x") })
        val snap = buildUiStateSnapshot(state(listOf(chat, editor)), emptyMap(), phone = true)
        assertEquals("phone", snap["layout"]!!.jsonPrimitive.content)
        assertEquals("web", snap["currentProject"]!!.jsonPrimitive.content)
        val tabs = snap["panels"]!!.jsonArray.single().jsonObject["tabs"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("sessionId", "providerId"), tabs[0]["details"]!!.jsonObject.keys)
        assertEquals(setOf("filePath"), tabs[1]["details"]!!.jsonObject.keys)
        assertTrue(tabs[0]["active"]!!.jsonPrimitive.content == "true")
        assertFalse(snap.toString().contains("secret"))
        assertEquals(0, (snap["windows"] as JsonArray).size)
    }

    @Test
    fun `a layout too big to send is cut down and says how many tabs it left out`() {
        val tabs = (1..400).map { WorkspaceTab("t$it", "terminal", "x".repeat(150), "web") }
        val snap = buildUiStateSnapshot(state(tabs), emptyMap(), phone = false)
        assertTrue(snap.toString().length <= MAX_SNAPSHOT_CHARS)
        assertTrue(snap["truncated"]!!.jsonPrimitive.content.endsWith("tabs left out to keep this answer small"))
    }

    @Test
    fun `closing a terminal or a dirty editor needs force, a clean tab does not`() {
        assertEquals("Its editor has changes that are not saved yet.", unsavedWorkReason(WorkspaceTab("e", "editor", "a"), dirty = true))
        assertTrue(unsavedWorkReason(WorkspaceTab("t", "terminal", "t"), dirty = false)!!.startsWith("Closing a terminal"))
        assertNull(unsavedWorkReason(WorkspaceTab("e", "editor", "a"), dirty = false))
        assertNull(unsavedWorkReason(WorkspaceTab("c", "chat", "c"), dirty = false))
    }

    @Test
    fun `commands are found by words, and one that changes data runs only as approved, for this project`() {
        var ran = 0
        val commands = listOf(
            AssistantCommand("terminal", "Open Terminal", "shell console") { ran++ },
            AssistantCommand("close-all-tabs", "Close All Tabs", "tabs", changesData = true) { ran++ },
        )
        val listed = listAssistantCommands(commands, args("query" to "shell"), "web")
        assertEquals(listOf("terminal"), listed["commands"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals("web", listed["project"]!!.jsonPrimitive.content)

        runAssistantCommand(commands, args("id" to "terminal"), "web")
        assertEquals(1, ran)
        fun refused(a: JsonObject, project: String?, says: String) {
            try {
                runAssistantCommand(commands, a, project)
                fail("ran without its approval")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message, e.message!!.contains(says))
            }
        }
        refused(args("id" to "nope"), "web", "No command \"nope\"")
        refused(args("id" to "close-all-tabs"), "web", "runs only once the user approved it")
        refused(args("id" to "close-all-tabs", "approved" to true, "approvedLabel" to "Close Tabs", "approvedProject" to "web"), "web", "no longer the command")
        refused(args("id" to "close-all-tabs", "approved" to true, "approvedLabel" to "Close All Tabs", "approvedProject" to "api"), "web", "was approved for \"api\"")
        assertEquals(1, ran)
        runAssistantCommand(commands, args("id" to "close-all-tabs", "approved" to true, "approvedLabel" to "Close All Tabs", "approvedProject" to "web"), "web")
        assertEquals(2, ran)
    }

    @Test
    fun `a manager chat a phone opened remembers where it came from, and the window fits the screen`() {
        val meta = buildJsonObject {
            put("returnTo", buildJsonObject { put("kind", "assistant"); put("project", "api") })
            put("assistantChatEpoch", 2)
        }
        assertEquals(AssistantReturn("api"), assistantReturnOf(meta))
        assertNull(assistantReturnOf(buildJsonObject { put("returnTo", buildJsonObject { put("kind", "other") }) }))
        assertEquals(3, nextAssistantChatEpoch(meta))
        assertEquals(1, nextAssistantChatEpoch(null))
        assertEquals("web · manager", managerChatTitle("web"))
        val rect = assistantSpawnRect(Bounds(1280.0, 800.0))
        assertEquals(470.0, rect.w, 0.0)
        assertTrue(rect.x + rect.w <= 1280.0 && rect.y >= 0.0)
        val small = assistantSpawnRect(Bounds(400.0, 500.0))
        assertTrue(small.w <= 400.0 && small.h <= 500.0)
    }

    @Test
    fun `an assistant link opens the Assistant, on the session it names`() {
        val link = parsePpmLink("ppm://host:8080/assistant?session=codex%2Fs-1")!!
        assertEquals(AssistantDeepLink("s-1", "codex"), link.state.assistant)
        assertNull(link.state.projectName)
        assertEquals(AssistantDeepLink(), parsePpmLink("ppm://host/assistant")!!.state.assistant)
        assertEquals(AssistantDeepLink("abc"), parsePpmLink("ppm://host/assistant/?session=other%2Fabc")!!.state.assistant)
        assertNull(parsePpmLink("ppm://host/project/web/chat/x")!!.state.assistant)
    }
}
