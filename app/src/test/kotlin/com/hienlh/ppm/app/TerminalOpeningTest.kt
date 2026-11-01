package com.hienlh.ppm.app

import com.hienlh.ppm.state.KEY_ACTIONS
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What "New Terminal" and the open-terminal key open, and the keys the system's list names. */
class TerminalOpeningTest {
    @Test
    fun `a terminal in a project names it on the tab and in its metadata`() {
        val tab = terminalTab("ppm")
        assertEquals("terminal", tab.type)
        assertEquals("Terminal", tab.title)
        assertEquals("ppm", tab.projectId)
        assertEquals(mapOf("projectName" to JsonPrimitive("ppm")), tab.metadata.toMap())
    }

    @Test
    fun `a terminal with no project carries none, as the web's openTab does`() {
        val tab = terminalTab(null)
        assertNull(tab.projectId)
        assertTrue(tab.metadata.isEmpty())
    }

    @Test
    fun `the system's list names Open Terminal Tab after Open Chat, in the web's order`() {
        val ids = shortcutActionIds(wide = true, projectCount = 0)
        assertEquals(ids.indexOf("open-chat") + 1, ids.indexOf("open-terminal"))
        assertEquals(ids.indexOf("open-terminal") + 1, ids.indexOf("open-settings"))
        val catalog = KEY_ACTIONS.map { it.id }
        assertEquals(ids.sortedBy { catalog.indexOf(it) }, ids)
    }

    @Test
    fun `every key the list names is one the catalog has`() {
        val catalog = KEY_ACTIONS.map { it.id }.toSet()
        assertTrue(catalog.containsAll(shortcutActionIds(wide = true, projectCount = 12)))
        assertTrue(catalog.containsAll(shortcutActionIds(wide = false, projectCount = 3)))
    }

    @Test
    fun `a narrow window leaves out the sidebar and the terminal panel, and the project keys stop at nine`() {
        val narrow = shortcutActionIds(wide = false, projectCount = 12)
        assertTrue("toggle-sidebar" !in narrow && "toggle-dock" !in narrow)
        assertEquals((1..9).map { "switch-project-$it" }, narrow.filter { it.startsWith("switch-project-") })
        assertEquals(listOf("switch-project-1", "switch-project-2"), shortcutActionIds(wide = true, projectCount = 2).filter { it.startsWith("switch-project-") })
    }
}
