package com.hienlh.ppm.state.workspace

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** use-url-sync.ts, as the app reads it from links and for Back. */
class TabUrlTest {
    private val uuid = "0b7f3c2e-9d41-4f6a-8a8e-2f1c5d6e7a90"

    @Test
    fun `an address names a project, a tab type and the rest of the tab's id`() {
        assertEquals(UrlState("demo", "chat", "claude/$uuid"), parseUrlState("/project/demo/chat/claude/$uuid"))
        assertEquals(UrlState("demo", "editor", "src/app/main.kt"), parseUrlState("/project/demo/editor/src/app/main.kt"))
        assertEquals(UrlState("demo", "settings", null), parseUrlState("/project/demo/settings"))
        assertEquals(UrlState("demo", null, null), parseUrlState("/project/demo"))
        assertEquals(UrlState(null, null, null), parseUrlState("/"))
        // The project is decoded, the rest is not; a broken escape stays as it was.
        assertEquals("my app", parseUrlState("/project/my%20app").projectName)
        assertEquals("100%", parseUrlState("/project/100%").projectName)
        assertEquals("a%20b.ts", parseUrlState("/project/x/editor/a%20b.ts").tabIdentifier)
        // A type the address cannot name, and the legacy /tab/ form, open nothing.
        assertEquals(UrlState("demo", null, "x"), parseUrlState("/project/demo/problems/x"))
        assertEquals(UrlState("demo", null, null), parseUrlState("/project/demo/tab/tab-123"))
    }

    @Test
    fun `tab ids and metadata come back as the web builds them`() {
        assertEquals("settings", tabIdFromUrl("settings", null))
        assertEquals("terminal:2", tabIdFromUrl("terminal", "2"))
        val chat = buildMetadataFromUrl("chat", "codex/$uuid", "demo")!!
        assertEquals(uuid, chat["sessionId"]!!.jsonPrimitive.content)
        assertEquals("codex", chat["providerId"]!!.jsonPrimitive.content)
        // A derived random id is not a session.
        val unstarted = buildMetadataFromUrl("chat", "default/k3j9x2", "demo")!!
        assertNull(unstarted["sessionId"])
        assertNull(buildMetadataFromUrl("chat", "/$uuid", "demo")!!["providerId"])
        assertEquals(1, buildMetadataFromUrl("terminal", null, "demo")!!["terminalIndex"]!!.jsonPrimitive.int)
        assertEquals(3, buildMetadataFromUrl("terminal", "3", "demo")!!["terminalIndex"]!!.jsonPrimitive.int)
        assertEquals(JsonPrimitive(true), buildMetadataFromUrl("design", "landing-v2", "demo")!!["providerPending"])
        assertNull(buildMetadataFromUrl("design", "systems", "demo"))
        assertNull(buildMetadataFromUrl("design", "Bad_Slug", "demo"))
        assertNull(buildMetadataFromUrl("editor", null, "demo"))
        // Until the database phase ports `dbTabMetadataFromId`.
        assertNull(buildMetadataFromUrl("database", "3:app:public:users", "demo"))
        assertEquals("Git Graph", buildTitleFromUrl("extension", "git-graph"))
        assertEquals("main.kt", buildTitleFromUrl("editor", "src/main.kt"))
        assertEquals("Terminal 1", buildTitleFromUrl("terminal", null))
    }

    @Test
    fun `a tab reopens from its address only under the id it had`() {
        val chat = reopenedTab("chat:claude/$uuid", "demo")!!
        assertEquals("chat", chat.type)
        assertEquals("demo", chat.projectId)
        assertEquals("chat:claude/$uuid", deriveTabId(chat.type, chat.metadata))
        // Split copies drop their panel suffix.
        assertEquals("editor", reopenedTab("editor:src/a.kt@panel-x1", "demo")!!.type)
        assertEquals("terminal:2", reopenedTab("terminal:2", "demo")!!.let { deriveTabId(it.type, it.metadata) })
        assertNull(reopenedTab("settings", "demo")!!.projectId)
        // A chat that never had a session would come back as another new one.
        assertNull(reopenedTab("chat:default/k3j9x2", "demo"))
        assertNull(reopenedTab("problems", "demo"))
        assertNull(reopenedTab("database:3:app:public:users", "demo"))
    }

    @Test
    fun `ppm links name the server and the web's address on it`() {
        val link = parsePpmLink("ppm://10.0.2.2:8937/project/demo/chat/mock/$uuid")!!
        assertEquals("10.0.2.2", link.host)
        assertEquals(8937, link.port)
        assertEquals(UrlState("demo", "chat", "mock/$uuid"), link.state)
        // Host only, case folded; an IPv6 address; a decoded file name.
        val tunnel = parsePpmLink("PPM://My-Box.Example.com/project/a%20b/editor/docs/read%20me.md")!!
        assertEquals("my-box.example.com", tunnel.host)
        assertNull(tunnel.port)
        assertEquals("a b", tunnel.state.projectName)
        assertEquals("docs/read me.md", tunnel.state.tabIdentifier)
        val v6 = parsePpmLink("ppm://[fd7a:115c::1]:8080/project/demo")!!
        assertEquals("fd7a:115c::1", v6.host)
        assertEquals(8080, v6.port)
        // `?openChat=`, read as URLSearchParams reads it.
        assertEquals("claude/$uuid", parsePpmLink("ppm://host/project/demo?openChat=claude%2F$uuid")!!.state.openChat)
        assertEquals("a b", parsePpmLink("ppm://host/project/demo?x=1&openChat=a+b#frag")!!.state.openChat)
        assertNull(parsePpmLink("https://host/project/demo"))
        assertNull(parsePpmLink("ppm:///project/demo"))
    }

    @Test
    fun `history keeps each tab shown once in a row and steps back through them`() {
        val history = NavHistory(limit = 3)
        assertFalse(history.canGoBack)
        assertNull(history.back())
        history.record(NavEntry("demo", "chat:a"))
        history.record(NavEntry("demo", "chat:a"))
        assertFalse(history.canGoBack)
        history.record(NavEntry("demo", "chat:b"))
        history.record(NavEntry("api", "settings"))
        assertTrue(history.canGoBack)
        assertEquals(NavEntry("demo", "chat:b"), history.back())
        // Showing the entry stepped back to is not a new one.
        history.record(NavEntry("demo", "chat:b"))
        assertEquals(NavEntry("demo", "chat:a"), history.back())
        assertNull(history.back())
        // The oldest go past the limit.
        for (id in listOf("1", "2", "3", "4")) history.record(NavEntry("demo", id))
        assertEquals(NavEntry("demo", "3"), history.back())
        assertEquals(NavEntry("demo", "2"), history.back())
        assertNull(history.back())
    }
}
