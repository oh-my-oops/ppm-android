package com.hienlh.ppm.app

import com.hienlh.ppm.app.ui.SettingsCategory
import com.hienlh.ppm.app.ui.settingsSectionsShown
import com.hienlh.ppm.state.workspace.WorkspacePanel
import com.hienlh.ppm.state.workspace.WorkspaceState
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What another device's change makes this one read again (`use-global-events.ts`). */
class LiveSyncTest {
    private fun chat(id: String, session: String, title: String, design: String? = null) = WorkspaceTab(
        id = id,
        type = "chat",
        title = title,
        metadata = buildJsonObject {
            put("sessionId", JsonPrimitive(session))
            if (design != null) put("designSlug", JsonPrimitive(design))
        },
    )

    private val layout = WorkspaceState(
        panels = mapOf(
            "p1" to WorkspacePanel("p1", listOf(chat("a", "s-1", "Old"), chat("b", "s-2", "Other"))),
            "p2" to WorkspacePanel("p2", listOf(chat("c", "s-1", "New"), chat("d", "s-1", "Old", design = "logo"))),
        ),
        grid = listOf(listOf("p1", "p2")),
        focusedPanelId = "p1",
    )

    @Test
    fun `a rename retitles every open tab of that chat, but not a Design chat's or one already named so`() {
        assertEquals(listOf("a"), chatTabsToRetitle(layout, "ppm", "s-1", "New"))
        assertTrue(chatTabsToRetitle(layout, "__assistant__", "s-1", "New").isEmpty())
        assertTrue(chatTabsToRetitle(null, "ppm", "s-1", "New").isEmpty())
    }

    @Test
    fun `a settings change reaches the panes that show that section`() {
        val revisions = LiveSyncState()
        val ai = settingsSectionsShown(SettingsCategory.Ai)
        val before = revisions.revision(ai)
        revisions.bump("files")
        assertEquals(before, revisions.revision(ai))
        revisions.bump("ai")
        assertEquals(before + 1, revisions.revision(ai))
        assertEquals(listOf("ai"), settingsSectionsShown(SettingsCategory.Tools))
        assertEquals(listOf("keybindings"), settingsSectionsShown(SettingsCategory.Shortcuts))
    }
}
