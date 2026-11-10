package com.hienlh.ppm.app.ui.tablet

import android.app.Application
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.compose.runtime.CompositionLocalProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.KeyboardShortcuts
import com.hienlh.ppm.app.LocalRedesign
import com.hienlh.ppm.app.TabletEscapeStack
import com.hienlh.ppm.app.TabletShortcutBridge
import com.hienlh.ppm.app.shortcutActionIds
import com.hienlh.ppm.app.ui.CommandPalette
import com.hienlh.ppm.app.ui.PaletteGroup
import com.hienlh.ppm.app.ui.cyclePaletteFilter
import com.hienlh.ppm.app.ui.shouldShowDesktopOnlyPaletteActions
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.workspace.WorkspaceTab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xxhdpi")
class TabletShellKeyboardMouseTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private lateinit var model: AppModel
    private lateinit var shortcuts: KeyboardShortcuts

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        model = AppModel(context)
        model.setWindowWide(true)
        shortcuts = KeyboardShortcuts(model)
        TabletEscapeStack.clearForTest()
        TabletShortcutBridge.onToggleShortcutsSheet = null
        TabletShortcutBridge.onOpenTabSwitcher = null
    }

    @After
    fun tearDown() {
        TabletEscapeStack.clearForTest()
        TabletShortcutBridge.onToggleShortcutsSheet = null
        TabletShortcutBridge.onOpenTabSwitcher = null
    }

    private fun down(code: Int, meta: Int = 0) =
        KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta)

    @Test
    fun `shortcutActionIds includes web and tablet global actions`() {
        val ids = shortcutActionIds(wide = true, projectCount = 3)
        assertTrue("new-file" in ids)
        assertTrue("open-git-status" in ids)
        assertTrue("open-search" in ids)
        assertTrue("open-problems" in ids)
        assertTrue("compare-files" in ids)
    }

    @Test
    fun `keyboard shortcuts dispatch web and tablet chords`() {
        // Ctrl+Shift+E -> git sidebar section
        assertTrue(shortcuts.dispatch(down(KeyEvent.KEYCODE_E, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)))
        assertEquals("git", model.sidebarSection)

        // Ctrl+Shift+F -> search sidebar section
        assertTrue(shortcuts.dispatch(down(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)))
        assertEquals("search", model.sidebarSection)

        // Ctrl+T -> open palette
        assertNull(model.paletteQuery)
        assertTrue(shortcuts.dispatch(down(KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON)))
        assertEquals("", model.paletteQuery)
        model.closePalette()

        // Alt+Z -> toggle word wrap
        val initialWrap = model.wordWrap
        assertTrue(shortcuts.dispatch(down(KeyEvent.KEYCODE_Z, KeyEvent.META_ALT_ON)))
        assertEquals(!initialWrap, model.wordWrap)

        // Ctrl+/ -> TabletShortcutBridge.onToggleShortcutsSheet
        var sheetToggled = 0
        TabletShortcutBridge.onToggleShortcutsSheet = {
            sheetToggled++
            true
        }
        assertTrue(shortcuts.dispatch(down(KeyEvent.KEYCODE_SLASH, KeyEvent.META_CTRL_ON)))
        assertEquals(1, sheetToggled)

        // Ctrl+Tab -> TabletShortcutBridge.onOpenTabSwitcher
        var tabSwitcherForward: Boolean? = null
        TabletShortcutBridge.onOpenTabSwitcher = { forward ->
            tabSwitcherForward = forward
            true
        }
        assertTrue(shortcuts.dispatch(down(KeyEvent.KEYCODE_TAB, KeyEvent.META_CTRL_ON)))
        assertEquals(true, tabSwitcherForward)
    }

    @Test
    fun `TabletShellState closeTopLayer dismisses open layers in strict priority order`() {
        val state = TabletShellState()
        model.openPalette("query")
        state.shortcutsSheetOpen = true
        state.tabListPanelId = "panel-1"
        state.moreMenuOpen = true
        state.statusPopover = "connection"
        model.cloudShareOpen = true
        model.openBranchPicker("ppm")

        // 1. Palette
        assertTrue(state.closeTopLayer(model))
        assertNull(model.paletteQuery)
        assertTrue(state.shortcutsSheetOpen)

        // 2. Shortcuts sheet
        assertTrue(state.closeTopLayer(model))
        assertFalse(state.shortcutsSheetOpen)
        assertEquals("panel-1", state.tabListPanelId)

        // 3. All Tabs popup
        assertTrue(state.closeTopLayer(model))
        assertNull(state.tabListPanelId)
        assertTrue(state.moreMenuOpen)

        // 4. More utility menu
        assertTrue(state.closeTopLayer(model))
        assertFalse(state.moreMenuOpen)
        assertEquals("connection", state.statusPopover)

        // 5. Status bar popover
        assertTrue(state.closeTopLayer(model))
        assertNull(state.statusPopover)
        assertTrue(model.cloudShareOpen)

        // 6. Cloud & Share
        assertTrue(state.closeTopLayer(model))
        assertFalse(model.cloudShareOpen)
        assertEquals("ppm", model.branchPickerProject)

        // 7. Branch picker
        assertTrue(state.closeTopLayer(model))
        assertNull(model.branchPickerProject)

        // 8. Portrait slide-over sidebar
        state.isPortrait = true
        if (model.sidebarCollapsed) model.toggleSidebar()
        assertTrue(state.closeTopLayer(model))
        assertTrue(model.sidebarCollapsed)
    }

    @Test
    fun `moveRailSection reorders rail sections and cyclePaletteFilter cycles groups`() {
        val sections = listOf("history", "teams", "explorer", "search")
        assertEquals(listOf("teams", "history", "explorer", "search"), moveRailSection(sections, "history", 1))
        assertEquals(listOf("history", "teams", "search", "explorer"), moveRailSection(sections, "search", -1))
        assertEquals(sections, moveRailSection(sections, "history", -1))

        val groups = listOf(PaletteGroup.ACTION, PaletteGroup.FILE, PaletteGroup.FILESYSTEM)
        var active = emptySet<PaletteGroup>()
        active = cyclePaletteFilter(groups, active, forward = true)
        assertEquals(setOf(PaletteGroup.ACTION), active)
        active = cyclePaletteFilter(groups, active, forward = true)
        assertEquals(setOf(PaletteGroup.FILE), active)
        active = cyclePaletteFilter(groups, active, forward = true)
        assertEquals(setOf(PaletteGroup.FILESYSTEM), active)
        active = cyclePaletteFilter(groups, active, forward = true)
        assertEquals(emptySet<PaletteGroup>(), active)
        active = cyclePaletteFilter(groups, active, forward = false)
        assertEquals(setOf(PaletteGroup.FILESYSTEM), active)

        assertFalse(shouldShowDesktopOnlyPaletteActions(mobile = true, hasHardwareKeyboard = true, hasPointer = true))
        assertFalse(shouldShowDesktopOnlyPaletteActions(mobile = false, hasHardwareKeyboard = false, hasPointer = false))
        assertTrue(shouldShowDesktopOnlyPaletteActions(mobile = false, hasHardwareKeyboard = true, hasPointer = false))
        assertTrue(shouldShowDesktopOnlyPaletteActions(mobile = false, hasHardwareKeyboard = false, hasPointer = true))
    }

    @Test
    fun `TabBar AllTabsPopupCard and StatusBar ConnectionPopoverCard render controls`() {
        val tabs = listOf(
            WorkspaceTab(id = "tab-1", type = "chat", title = "Architecture Chat", closable = true),
            WorkspaceTab(id = "tab-2", type = "terminal", title = "Terminal 1", closable = true),
        )
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalRedesign provides true) {
                    Box(Modifier.size(1280.dp, 800.dp)) {
                        TabBar(model = model, panelId = "panel-1", tabs = tabs, activeTabId = "tab-1")
                        AllTabsPopupCard(model = model, currentPanelId = "panel-1", onDismiss = {})
                        ConnectionPopoverCard(model = model, onDismiss = {})
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription("All tabs").assertIsDisplayed()
        composeRule.onNodeWithText("All Tabs").assertIsDisplayed()
        composeRule.onNodeWithText("Wrap tabs").assertIsDisplayed()
        composeRule.onNodeWithText("Filter open tabs…").assertIsDisplayed()
        composeRule.onNodeWithText("Connection").assertIsDisplayed()
        composeRule.onNodeWithText("Reconnect now").assertIsDisplayed()
        composeRule.onNodeWithText("Switch server").assertIsDisplayed()
    }

    @Test
    fun `TabletShell landscape renders More button and opens Keyboard Shortcuts sheet`() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalRedesign provides true) {
                    Box(Modifier.size(1280.dp, 800.dp)) {
                        TabletShell(model = model)
                    }
                }
            }
        }

        // Landscape sidebar footer renders More utility button
        composeRule.onNodeWithContentDescription("More").assertIsDisplayed()

        // Ctrl+/ opens the Keyboard Shortcuts modal in-tree
        assertNotNull(TabletShortcutBridge.onToggleShortcutsSheet)
        composeRule.runOnIdle {
            TabletShortcutBridge.onToggleShortcutsSheet?.invoke()
        }
        composeRule.onNodeWithText("Keyboard shortcuts").assertIsDisplayed()
        composeRule.onNodeWithText("Customize in Settings → Keybindings").assertIsDisplayed()
    }
}
