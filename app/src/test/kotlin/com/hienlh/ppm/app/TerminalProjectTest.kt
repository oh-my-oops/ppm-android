package com.hienlh.ppm.app

import com.hienlh.ppm.state.workspace.NewTab
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The project a terminal's shell is started for ([terminalProject]), which is also the project its
 * Send to Chat goes to. The web reads `metadata.projectName` and nothing else (`terminal-tab.tsx`),
 * and names a shell with none "" (`projectName ?? ""` in `useTerminal`): its socket is
 * `/ws/project//terminal/...`, which the server starts in the host's home folder, or in the tab's `cwd`.
 */
class TerminalProjectTest {
    private fun tab(opened: NewTab, id: String = "terminal:1") =
        WorkspaceTab(id, opened.type, opened.title, opened.projectId, opened.metadata, opened.closable)

    private fun terminalWith(projectId: String?, metadata: Map<String, JsonElement>, id: String = "terminal:1") =
        WorkspaceTab(id, "terminal", "Terminal", projectId, JsonObject(metadata))

    @Test
    fun `a terminal opened in a project is started for it`() {
        assertEquals("ppm", terminalProject(tab(terminalTab("ppm"))))
    }

    @Test
    fun `a terminal opened with no project open is started for none`() {
        assertEquals("", terminalProject(tab(terminalTab(null))))
    }

    @Test
    fun `a terminal opened for a folder of a project is started for the project`() {
        assertEquals("ppm", terminalProject(tab(terminalAtTab("/home/me/ppm/src", "ppm"))))
    }

    @Test
    fun `a terminal opened for a folder of the host's own explorer is started for none, whichever project is open`() {
        // Nothing about the open project reaches it: the tab alone decides, as the web's tab does.
        assertEquals("", terminalProject(tab(terminalAtTab("/home/me", null))))
        assertEquals("", terminalProject(tab(terminalAtTab("/home/me", ""))))
    }

    @Test
    fun `the tab's own project is not the shell's, only the metadata names it, as the web reads it`() {
        assertEquals("", terminalProject(terminalWith("ppm", emptyMap())))
        assertEquals("other", terminalProject(terminalWith("ppm", mapOf("projectName" to JsonPrimitive("other")))))
    }

    @Test
    fun `an empty or null project name is no project`() {
        assertEquals("", terminalProject(terminalWith("ppm", mapOf("projectName" to JsonPrimitive("")))))
        assertEquals("", terminalProject(terminalWith("ppm", mapOf("projectName" to JsonNull))))
    }
}
