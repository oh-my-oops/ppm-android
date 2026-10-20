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
                """{"projectSortMode":"recent","recentOpen":{"web":1760000000000,"api":"yesterday"},"sidebarCollapsed":"yes","sidebarWidth":640,"keepScreenAwake":0,"tabWrap":"on"}""",
            ),
        )
        assertEquals(UiPrefs(projectSortMode = "recent", recentOpen = mapOf("web" to 1760000000000)), prefs)
    }

    @Test
    fun `the copy a device keeps reads back the same`() {
        val prefs = UiPrefs("name", mapOf("web" to 1760000000000, "api server" to 1750000000000), sidebarCollapsed = true, sidebarWidth = 344, keepScreenAwake = false, tabWrap = true)
        assertEquals(prefs, UiPrefs.from(Json.parseToJsonElement(prefs.toJson().toString())))
        assertEquals(UiPrefs(), UiPrefs.from(UiPrefs().toJson()))
    }

    @Test
    fun `a new chat follows the focused chat's provider only when the mode says so`() {
        val follow = NewChatProviderSettings("codex", "follow-focus")
        assertEquals("mock", follow.resolve("mock"))
        assertEquals("codex", follow.resolve("default"))
        assertEquals("codex", follow.resolve(null))
        assertEquals("codex", NewChatProviderSettings("codex", "default").resolve("mock"))
        assertEquals("claude", NewChatProviderSettings("", null).resolve("mock"))
    }
}
