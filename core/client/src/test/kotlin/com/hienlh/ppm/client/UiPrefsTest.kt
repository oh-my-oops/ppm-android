package com.hienlh.ppm.client

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** `/api/settings/ui-prefs` as the app reads it, the copy a device keeps of it, and how a new chat picks its provider. */
class UiPrefsTest {
    @Test
    fun `a value of the wrong kind or a width out of range is left out, as the web reads it`() {
        val prefs = UiPrefs.from(
            Json.parseToJsonElement(
                """{"projectSortMode":"recent","recentOpen":{"web":1760000000000,"api":"yesterday"},"sidebarCollapsed":"yes","sidebarWidth":640,"keepScreenAwake":0,"tabWrap":"on","mcpSignInDismissed":"linear"}""",
            ),
        )
        assertEquals(UiPrefs(projectSortMode = "recent", recentOpen = mapOf("web" to 1760000000000)), prefs)
    }

    @Test
    fun `the copy a device keeps reads back the same`() {
        val prefs = UiPrefs(
            "name", mapOf("web" to 1760000000000, "api server" to 1750000000000), sidebarCollapsed = true, sidebarWidth = 344,
            keepScreenAwake = false, tabWrap = true, mcpSignInDismissed = listOf("linear", "plugin:x:notion"),
        )
        assertEquals(prefs, UiPrefs.from(Json.parseToJsonElement(prefs.toJson().toString())))
        assertEquals(UiPrefs(), UiPrefs.from(UiPrefs().toJson()))
    }

    @Test
    fun `the hidden MCP servers are read as the web sanitizes them`() {
        val prefs = UiPrefs.from(Json.parseToJsonElement("""{"mcpSignInDismissed":["linear","",5,"github"]}"""))
        assertEquals(listOf("linear", "github"), prefs.mcpSignInDismissed)
        assertEquals(emptyList<String>(), UiPrefs.from(Json.parseToJsonElement("""{"mcpSignInDismissed":[]}""")).mcpSignInDismissed)
    }

    @Test
    fun `the sidebar section and the rail's order are read as the web sanitizes them`() {
        val prefs = UiPrefs.from(
            Json.parseToJsonElement("""{"sidebarActiveTab":"teams","sidebarTabOrder":["teams","bogus",3,"ext:todo","teams","history"]}"""),
        )
        assertEquals("teams", prefs.sidebarActiveTab)
        assertEquals(listOf("teams", "ext:todo", "history"), prefs.sidebarTabOrder)
        assertEquals(prefs, UiPrefs.from(Json.parseToJsonElement(prefs.toJson().toString())))
        val wrong = UiPrefs.from(Json.parseToJsonElement("""{"sidebarActiveTab":"chat","sidebarTabOrder":"teams"}"""))
        assertEquals(null, wrong.sidebarActiveTab)
        assertEquals(null, wrong.sidebarTabOrder)
    }
}
