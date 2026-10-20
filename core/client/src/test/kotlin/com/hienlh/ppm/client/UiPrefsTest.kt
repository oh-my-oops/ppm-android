package com.hienlh.ppm.client

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** `/api/settings/ui-prefs` as the app reads it, and the copy a device keeps of it. */
class UiPrefsTest {
    @Test
    fun `a value of the wrong kind or a width out of range is left out, as the web reads it`() {
        val prefs = UiPrefs.from(
            Json.parseToJsonElement(
                """{"projectSortMode":"recent","recentOpen":{"web":1760000000000,"api":"yesterday"},"sidebarCollapsed":"yes","sidebarWidth":640}""",
            ),
        )
        assertEquals(UiPrefs(projectSortMode = "recent", recentOpen = mapOf("web" to 1760000000000)), prefs)
    }

    @Test
    fun `the copy a device keeps reads back the same`() {
        val prefs = UiPrefs("name", mapOf("web" to 1760000000000, "api server" to 1750000000000), sidebarCollapsed = true, sidebarWidth = 344)
        assertEquals(prefs, UiPrefs.from(Json.parseToJsonElement(prefs.toJson().toString())))
        assertEquals(UiPrefs(), UiPrefs.from(UiPrefs().toJson()))
    }
}
