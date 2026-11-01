package com.hienlh.ppm.app

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tab "Open in Terminal" opens (`openTerminalAt` in the web's `open-terminal-at.ts`). */
class TerminalAtTabTest {
    @Test
    fun `a folder of a project opens a terminal titled by it, starting in it, on that project`() {
        val tab = terminalAtTab("/home/me/ppm/src", "ppm")
        assertEquals("terminal", tab.type)
        assertEquals("src", tab.title)
        assertEquals("ppm", tab.projectId)
        assertTrue(tab.closable)
        assertEquals(
            mapOf("cwd" to JsonPrimitive("/home/me/ppm/src"), "projectName" to JsonPrimitive("ppm")),
            tab.metadata.toMap(),
        )
    }

    @Test
    fun `a folder of the host's own explorer gives the tab no project, on it or in its metadata`() {
        val tab = terminalAtTab("/home/me", null)
        assertNull(tab.projectId)
        assertEquals(mapOf("cwd" to JsonPrimitive("/home/me")), tab.metadata.toMap())
    }

    @Test
    fun `an empty project is the tab's as given but not named in the metadata, as the web does`() {
        // `projectId: projectName ?? null` keeps "", `projectName ? { projectName } : {}` drops it.
        val tab = terminalAtTab("/home/me", "")
        assertEquals("", tab.projectId)
        assertEquals(mapOf("cwd" to JsonPrimitive("/home/me")), tab.metadata.toMap())
    }

    @Test
    fun `the title is the last part of either kind of path, and the whole path when that is empty`() {
        assertEquals("proj", terminalAtTab("""C:\Users\me\proj""", null).title)
        assertEquals("b", terminalAtTab("""docs/a\b""", null).title)
        assertEquals("/", terminalAtTab("/", null).title)
        assertEquals("/home/me/", terminalAtTab("/home/me/", null).title)
        assertEquals("""C:\""", terminalAtTab("""C:\""", null).title)
    }
}
