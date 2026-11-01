package com.hienlh.ppm.app

import com.hienlh.ppm.state.workspace.NewTab
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The project a terminal's shell is started for ([terminalProject]), which is also the project its
 * Send to Chat goes to and the one a command run for a project looks for ([shownTerminalOf]). The
 * web reads `metadata.projectName` and nothing else (`terminal-tab.tsx`), and names a shell with
 * none "" (`projectName ?? ""` in `useTerminal`): its socket is `/ws/project//terminal/...`, which
 * the server starts in the host's home folder, or in the tab's `cwd`.
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

    @Test
    fun `a command for a project goes to the shown terminal that is the project's`() {
        val host = tab(terminalAtTab("/home/me", null), "terminal:1")
        val ppm = tab(terminalTab("ppm"), "terminal:2")
        val ppmToo = tab(terminalTab("ppm"), "terminal:3")
        assertSame(ppm, shownTerminalOf(listOf(host, ppm, ppmToo), "ppm"))
    }

    @Test
    fun `a terminal of the host's explorer does not take a project's command`() {
        assertNull(shownTerminalOf(listOf(tab(terminalAtTab("/home/me", null))), "ppm"))
        assertNull(shownTerminalOf(listOf(tab(terminalTab("other"))), "ppm"))
    }

    @Test
    fun `a tab that names the project but is not a terminal does not take it`() {
        val chat = WorkspaceTab("chat:1", "chat", "Chat", "ppm", JsonObject(mapOf("projectName" to JsonPrimitive("ppm"))))
        assertNull(shownTerminalOf(listOf(chat), "ppm"))
    }

    @Test
    fun `a terminal whose shell is not started for the project does not take its command, whatever its tab says`() {
        assertNull(shownTerminalOf(listOf(terminalWith("ppm", emptyMap())), "ppm"))
    }
}
