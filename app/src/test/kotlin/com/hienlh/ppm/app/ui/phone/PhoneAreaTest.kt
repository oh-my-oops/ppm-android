package com.hienlh.ppm.app.ui.phone

import com.hienlh.ppm.design.kit.KitSpec
import com.hienlh.ppm.state.workspace.LayoutStorage
import com.hienlh.ppm.state.workspace.NewTab
import com.hienlh.ppm.state.workspace.PanelLayout
import com.hienlh.ppm.state.workspace.WorkspacePanel
import com.hienlh.ppm.state.workspace.WorkspaceStore
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAreaTest {

    @Test
    fun `PhoneArea enum contains the closed set of 12 phone areas from Requirement 3_1`() {
        val expectedIds = setOf(
            "chat",
            "projects",
            "files",
            "source-control",
            "terminal",
            "database",
            "monitor",
            "logs",
            "schedules",
            "ports",
            "cloud-share",
            "settings",
        )
        assertEquals(expectedIds, PhoneArea.entries.map { it.id }.toSet())
        assertEquals(12, PhoneArea.entries.size)
    }

    @Test
    fun `phone-supported tab types resolve to PhoneTabRoute Area`() {
        val phoneTabCases = listOf(
            "chat" to PhoneArea.CHAT,
            "editor" to PhoneArea.FILES,
            "git-diff" to PhoneArea.SOURCE_CONTROL,
            "terminal" to PhoneArea.TERMINAL,
            "database" to PhoneArea.DATABASE,
            "sqlite" to PhoneArea.DATABASE,
            "db-query" to PhoneArea.DATABASE,
            "db-sql" to PhoneArea.DATABASE,
            "system-monitor" to PhoneArea.MONITOR,
            "logs" to PhoneArea.LOGS,
            "schedules" to PhoneArea.SCHEDULES,
            "ports" to PhoneArea.PORT_FORWARDING,
            "web-preview" to PhoneArea.PORT_FORWARDING,
            "settings" to PhoneArea.SETTINGS,
        )

        for ((type, expectedArea) in phoneTabCases) {
            val tab = WorkspaceTab(id = "$type:1", type = type, title = type)
            assertFalse("Expected $type to be supported on phone", isTabletOnlyTab(tab))
            assertEquals(expectedArea, PhoneArea.fromTab(tab))
            val route = resolvePhoneTabRoute(tab)
            assertTrue("Expected Area route for $type", route is PhoneTabRoute.Area)
            assertEquals(expectedArea, (route as PhoneTabRoute.Area).area)
        }
    }

    @Test
    fun `tablet-only tab types resolve to PhoneTabRoute TabletOnly with concise tablet notice`() {
        val tabletOnlyTypes = listOf(
            "conflict-editor",
            "git-review",
            "session-review",
            "branch-review",
            "git-graph",
            "git-log",
            "db-connection",
            "db-structure",
            "db-impexp",
            "remote-desktop",
            "android",
            "extension",
            "design",
            "group",
            "jira",
            "ai-resource",
            "problems",
            "os-explorer",
            "search",
        )

        for (type in tabletOnlyTypes) {
            val tab = WorkspaceTab(id = "$type:1", type = type, title = "Tab $type")
            assertTrue("Expected $type to be tablet-only", isTabletOnlyTab(tab))
            assertNull("Expected PhoneArea.fromTab($type) to be null", PhoneArea.fromTab(tab))

            val route = resolvePhoneTabRoute(tab)
            assertTrue("Expected TabletOnly route for $type", route is PhoneTabRoute.TabletOnly)
            val tabletRoute = route as PhoneTabRoute.TabletOnly
            assertEquals(tab, tabletRoute.tab)
            assertTrue(
                "Expected notice for $type to mention tablet, got: ${tabletRoute.notice}",
                tabletRoute.notice.contains("tablet", ignoreCase = true),
            )
        }
    }

    @Test
    fun `tablet-only tabs remain intact in WorkspaceStore when opened or active on phone`() {
        val savedLayouts = mutableMapOf<String, PanelLayout>()
        val storage = object : LayoutStorage {
            override fun load(project: String): PanelLayout? = savedLayouts[project]
            override fun save(project: String, layout: PanelLayout) {
                savedLayouts[project] = layout
            }
        }

        // Pre-populate project layout as if opened on a tablet/desktop with both phone & tablet-only tabs
        val chatTab = WorkspaceTab(id = "chat:claude/s1", type = "chat", title = "Main Chat", projectId = "ppm")
        val reviewTab = WorkspaceTab(id = "git-review:ppm", type = "git-review", title = "Review changes", projectId = "ppm")
        val designTab = WorkspaceTab(id = "design:mobile", type = "design", title = "Mobile Design", projectId = "ppm")
        val structureTab = WorkspaceTab(id = "db-structure:users", type = "db-structure", title = "users structure", projectId = "ppm")
        val remoteDesktopTab = WorkspaceTab(id = "remote-desktop:1", type = "remote-desktop", title = "Remote Desktop", projectId = "ppm")

        val initialPanel = WorkspacePanel(
            id = "panel-1",
            tabs = listOf(chatTab, reviewTab, designTab, structureTab, remoteDesktopTab),
            activeTabId = reviewTab.id,
            tabHistory = listOf(chatTab.id, reviewTab.id),
        )
        savedLayouts["ppm"] = PanelLayout(
            panels = mapOf("panel-1" to initialPanel),
            grid = listOf(listOf("panel-1")),
            focusedPanelId = "panel-1",
        )

        val store = WorkspaceStore(storage = storage, isMobile = { true })
        store.switchProject("ppm")

        // Active tab is tablet-only `git-review:ppm` -> routes to OpenOnTablet
        val activeBefore = store.state.value.activeGridTab
        assertNotNull(activeBefore)
        val route = resolvePhoneTabRoute(activeBefore!!)
        assertTrue(route is PhoneTabRoute.TabletOnly)
        assertEquals("Review changes opens on a tablet.", (route as PhoneTabRoute.TabletOnly).notice)

        // Switching across tablet-only and phone tabs on phone never drops any tablet-only tab from WorkspaceStore
        store.setActiveTab(designTab.id)
        assertTrue(resolvePhoneTabRoute(store.state.value.activeGridTab!!) is PhoneTabRoute.TabletOnly)

        store.setActiveTab(chatTab.id)
        assertTrue(resolvePhoneTabRoute(store.state.value.activeGridTab!!) is PhoneTabRoute.Area)

        // Open a new phone tab while on phone
        store.openTab(
            NewTab(
                type = "terminal",
                title = "Terminal 1",
                projectId = "ppm",
                metadata = buildJsonObject { put("projectName", "ppm") },
            ),
        )

        val remainingIds = store.state.value.mergedTabs.map { it.first.id }.toSet()
        assertTrue(remainingIds.contains(chatTab.id))
        assertTrue(remainingIds.contains(reviewTab.id))
        assertTrue(remainingIds.contains(designTab.id))
        assertTrue(remainingIds.contains(structureTab.id))
        assertTrue(remainingIds.contains(remoteDesktopTab.id))

        // Saved layout also preserves all tablet-only tabs for other devices
        val persistedIds = savedLayouts["ppm"]?.panels?.values?.flatMap { it.tabs }?.map { it.id }.orEmpty().toSet()
        assertTrue(persistedIds.containsAll(listOf(reviewTab.id, designTab.id, structureTab.id, remoteDesktopTab.id)))
    }

    @Test
    fun `phone settings sections vs tablet-only settings sections are classified accurately`() {
        for (section in listOf("connection", "appearance", "notifications", "about")) {
            assertFalse("Expected $section to be supported on phone", PhoneArea.isTabletOnlySettingsSection(section))
        }
        for (section in listOf("ai-providers", "accounts", "ssh-keys", "tunnels", "db-backup")) {
            assertTrue("Expected $section to be tablet-only", PhoneArea.isTabletOnlySettingsSection(section))
        }
    }

    @Test
    fun `phone AppBar actions never exceed KitSpec APP_BAR_MAX_ACTIONS`() {
        for (area in PhoneArea.entries + listOf(null)) {
            val actions = buildPhoneAppBarActions(
                activeArea = area,
                syntheticArea = null,
                hasRunningChat = true,
                onOpenChatHistory = {},
                onOpenTerminal = {},
                onNewChat = {},
                onNewTerminal = {},
                onRefreshGit = {},
                onBackToChat = {},
            )
            assertTrue(
                "Expected <= ${KitSpec.APP_BAR_MAX_ACTIONS} actions for area=$area, got ${actions.size}",
                actions.size <= KitSpec.APP_BAR_MAX_ACTIONS,
            )
        }
    }
}
