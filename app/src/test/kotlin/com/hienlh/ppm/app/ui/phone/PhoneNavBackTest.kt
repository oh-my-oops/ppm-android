package com.hienlh.ppm.app.ui.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNavBackTest {

    @Test
    fun `back follows strict priority order - top sheet then drawer then previous tab then Exit`() {
        val nav = PhoneNav(initialTabId = "chat:1")
        nav.visitTab("terminal:1")
        nav.visitTab("editor:README.md")
        nav.openDrawer()
        nav.pushSheet(SheetKey.TabSwitcher)
        nav.pushSheet(SheetKey.ProjectSwitcher)

        assertTrue(nav.canHandleBack)
        assertEquals("editor:README.md", nav.currentTabId)
        assertEquals(listOf("chat:1", "terminal:1"), nav.history)
        assertEquals(SheetKey.ProjectSwitcher, nav.topSheet)
        assertTrue(nav.drawerOpen)

        // 1a. Topmost sheet closes first
        assertEquals(BackResult.ClosedSheet(SheetKey.ProjectSwitcher), nav.back())
        assertEquals(listOf(SheetKey.TabSwitcher), nav.sheets)
        assertTrue(nav.drawerOpen)
        assertEquals("editor:README.md", nav.currentTabId)

        // 1b. Next sheet in stack closes second
        assertEquals(BackResult.ClosedSheet(SheetKey.TabSwitcher), nav.back())
        assertTrue(nav.sheets.isEmpty())
        assertNull(nav.topSheet)
        assertTrue(nav.drawerOpen)

        // 2. Drawer closes next when no sheets remain
        assertEquals(BackResult.ClosedDrawer, nav.back())
        assertFalse(nav.drawerOpen)
        assertEquals("editor:README.md", nav.currentTabId)

        // 3a. Previous tab in history is popped next
        assertEquals(BackResult.PoppedTab("terminal:1"), nav.back())
        assertEquals("terminal:1", nav.currentTabId)
        assertEquals(listOf("chat:1"), nav.history)

        // 3b. Earlier tab in history is popped next
        assertEquals(BackResult.PoppedTab("chat:1"), nav.back())
        assertEquals("chat:1", nav.currentTabId)
        assertTrue(nav.history.isEmpty())
        assertFalse(nav.canHandleBack)

        // 4. Exit when nothing remains
        assertEquals(BackResult.Exit, nav.back())
    }

    @Test
    fun `visiting the same tab consecutively does not duplicate history`() {
        val nav = PhoneNav(initialTabId = "chat:1")
        nav.visitTab("chat:1")
        assertTrue(nav.history.isEmpty())

        nav.visitTab("terminal:1")
        nav.visitTab("terminal:1")
        assertEquals(listOf("chat:1"), nav.history)
        assertEquals("terminal:1", nav.currentTabId)
    }

    @Test
    fun `re-pushing an already open sheet moves it to the top without duplicating`() {
        val nav = PhoneNav(initialTabId = "chat:1")
        nav.pushSheet(SheetKey.TabSwitcher)
        nav.pushSheet(SheetKey.Notifications)
        nav.pushSheet(SheetKey.TabSwitcher)

        assertEquals(listOf(SheetKey.Notifications, SheetKey.TabSwitcher), nav.sheets)
        assertEquals(BackResult.ClosedSheet(SheetKey.TabSwitcher), nav.back())
        assertEquals(BackResult.ClosedSheet(SheetKey.Notifications), nav.back())
        assertEquals(BackResult.Exit, nav.back())
    }

    @Test
    fun `closing a tab removes it from history and updates currentTabId`() {
        val nav = PhoneNav(initialTabId = "chat:1")
        nav.visitTab("terminal:1")
        nav.visitTab("logs")

        // Close a tab in history
        nav.onTabClosed("terminal:1", nextActiveTabId = "logs")
        assertEquals("logs", nav.currentTabId)
        assertEquals(listOf("chat:1"), nav.history)

        // Close the currently active tab
        nav.onTabClosed("logs", nextActiveTabId = "chat:1")
        assertEquals("chat:1", nav.currentTabId)
        assertTrue(nav.history.isEmpty())
        assertEquals(BackResult.Exit, nav.back())
    }

    @Test
    fun `syncOpenTabs prunes closed workspace tabs while preserving synthetic phone area entries`() {
        val nav = PhoneNav(initialTabId = "chat:1")
        nav.visitTab("terminal:1")
        nav.visitTab(PhoneNav.areaTabId(PhoneArea.FILES))

        nav.syncOpenTabs(openTabIds = setOf("chat:1"), activeTabId = "chat:1")

        // Synthetic area stays current; closed "terminal:1" is pruned from history while "chat:1" remains
        assertEquals(PhoneNav.areaTabId(PhoneArea.FILES), nav.currentTabId)
        assertEquals(listOf("chat:1"), nav.history)
        assertEquals(BackResult.PoppedTab("chat:1"), nav.back())
        assertEquals("chat:1", nav.currentTabId)
    }
}
