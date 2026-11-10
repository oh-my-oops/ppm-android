package com.hienlh.ppm.app.ui.phone.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hienlh.ppm.app.ui.TerminalMobileToolbar
import com.hienlh.ppm.app.ui.shouldShowTerminalKeyRow
import com.hienlh.ppm.app.ui.tablet.DOCK_PILL_CAP
import com.hienlh.ppm.app.ui.tablet.DockEmptyState
import com.hienlh.ppm.app.ui.tablet.dockPositionOptions
import com.hienlh.ppm.app.ui.tablet.visibleDockPillIds
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.terminal.MobileKey
import com.hienlh.ppm.state.terminal.TerminalState
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h844dp-xxhdpi")
class PhoneTerminalTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    // ─────────────────── Requirement 12.2: AI run_in_terminal & newest terminal ───────────────────

    @Test
    fun `resolvePhoneTerminalTab selects AI run_in_terminal tab while keeping user terminal alive`() {
        val userTab = WorkspaceTab(
            id = "terminal:1",
            type = "terminal",
            title = "Terminal",
            projectId = "ppm",
            metadata = buildJsonObject {
                put("projectName", "ppm")
                put("terminalIndex", 1)
                put("sessionId", "term-user-1")
                put("cwd", "~/Projects/ppm")
            },
        )
        val aiCommandTab = WorkspaceTab(
            id = "terminal:2",
            type = "terminal",
            title = "ppm",
            projectId = "ppm",
            metadata = buildJsonObject {
                put("projectName", "ppm")
                put("terminalIndex", 2)
                put("sessionId", "term-ai-2")
                put("cwd", "~/Projects/ppm")
                put("pendingCommand", "bun run test:unit")
            },
        )

        val allTerminals = collectPhoneProjectTerminals(
            gridTabs = listOf(userTab),
            dockTabs = listOf(aiCommandTab),
            currentProject = "ppm",
        )
        // Both the user's terminal and the AI's terminal remain alive in the project list.
        assertEquals(listOf("terminal:1", "terminal:2"), allTerminals.map { it.id })

        // Phone screen resolves to the AI's newest terminal holding the pending command.
        val resolved = resolvePhoneTerminalTab(
            requestedTab = userTab,
            gridTabs = listOf(userTab),
            dockTabs = listOf(aiCommandTab),
            activeDockTabId = "terminal:2",
            currentProject = "ppm",
        )
        assertEquals("terminal:2", resolved.id)
        assertEquals("bun run test:unit", resolved.metaString("pendingCommand"))

        // Even after pendingCommand is taken, the active newer dock terminal remains selected.
        val aiTabAfterCommandTaken = aiCommandTab.withMetadata("pendingCommand" to null)
        val resolvedAfterTaken = resolvePhoneTerminalTab(
            requestedTab = userTab,
            gridTabs = listOf(userTab),
            dockTabs = listOf(aiTabAfterCommandTaken),
            activeDockTabId = "terminal:2",
            currentProject = "ppm",
        )
        assertEquals("terminal:2", resolvedAfterTaken.id)
    }

    // ─────────────────── Requirement 12.1: KeyRow, Select Mode, Sideways (915x412) ───────────────────

    @Test
    fun `buildPhoneTerminalKeyItems includes Esc Tab sticky Ctrl repeating arrows CtrlC Paste and select mode tools`() {
        val normalItems = buildPhoneTerminalKeyItems(selectMode = false, ctrlArmed = true)
        val normalButtons = normalItems.filterIsInstance<PhoneTerminalKeyItem.KeyButton>()
        assertEquals(
            listOf("Esc", "Tab", "Ctrl", "↑", "↓", "←", "→", "^C", "Paste"),
            normalButtons.map { it.label },
        )
        val ctrlBtn = normalButtons.first { it.id == "ctrl" }
        assertTrue(ctrlBtn.highlighted)
        assertTrue(normalButtons.filter { it.id in setOf("up", "down", "left", "right") }.all { it.repeats })

        val selectItems = buildPhoneTerminalKeyItems(selectMode = true, ctrlArmed = false)
        assertTrue(selectItems[0] is PhoneTerminalKeyItem.KeyButton)
        assertEquals("Select text", (selectItems[0] as PhoneTerminalKeyItem.KeyButton).label)
        assertTrue((selectItems[0] as PhoneTerminalKeyItem.KeyButton).highlighted)
        assertEquals("Copy", (selectItems[1] as PhoneTerminalKeyItem.KeyButton).label)
        assertEquals(PhoneTerminalKeyItem.Divider, selectItems[2])
    }

    @Test
    fun `shouldFoldPhoneTerminalBarInLandscape folds to 24dp status line at 915x412 when keyboard is up`() {
        // Sideways phone (915 x 412) with keyboard up folds to the 24dp status line (Phone 4 TL1, Note 40)
        assertTrue(
            shouldFoldPhoneTerminalBarInLandscape(
                widthDp = 915f,
                heightDp = 412f,
                imeVisible = true,
                manuallyExpanded = false,
            ),
        )
        // Tapping "Show bar" expands the full bar even while keyboard is up
        assertFalse(
            shouldFoldPhoneTerminalBarInLandscape(
                widthDp = 915f,
                heightDp = 412f,
                imeVisible = true,
                manuallyExpanded = true,
            ),
        )
        // Sideways phone with keyboard down shows the full bar (Phone 4 TL2)
        assertFalse(
            shouldFoldPhoneTerminalBarInLandscape(
                widthDp = 915f,
                heightDp = 412f,
                imeVisible = false,
                manuallyExpanded = false,
            ),
        )
        // Portrait phone never folds to the landscape 24dp strip
        assertFalse(
            shouldFoldPhoneTerminalBarInLandscape(
                widthDp = 412f,
                heightDp = 915f,
                imeVisible = true,
                manuallyExpanded = false,
            ),
        )
    }

    // ─────────────────── Phone 4 TM5 & TM8: Actions Sheet & Exited/Error Bar ───────────────────

    @Test
    fun `buildPhoneTerminalActionRows and resolvePhoneTerminalBottomBarMode match Phone 4 spec`() {
        val noSelectionRows = buildPhoneTerminalActionRows(
            hasSelection = false,
            selectMode = false,
            linkCount = 3,
            exited = false,
        )
        assertEquals(
            listOf("Copy", "Paste", "Select text", "Links", "Send to chat", "Restart"),
            noSelectionRows.map { it.title },
        )
        assertEquals("last output", noSelectionRows.first { it.id == "copy" }.hint)
        assertEquals("last command + output", noSelectionRows.first { it.id == "send_to_chat" }.hint)
        assertEquals(3, noSelectionRows.first { it.id == "links" }.badgeCount)

        val withSelectionRows = buildPhoneTerminalActionRows(
            hasSelection = true,
            selectMode = true,
            linkCount = 1,
            exited = true,
        )
        assertEquals("selection", withSelectionRows.first { it.id == "copy" }.hint)
        assertEquals("selection", withSelectionRows.first { it.id == "send_to_chat" }.hint)
        assertTrue(withSelectionRows.first { it.id == "select_text" }.checked)
        assertTrue(withSelectionRows.first { it.id == "restart" }.highlightAccent)

        assertEquals(
            "session term-a8c1 · ~/Projects/ppm",
            formatPhoneTerminalSheetSubtitle("term-a8c1", "~/Projects/ppm"),
        )

        assertEquals(
            PhoneTerminalBottomBarMode.KeyRow,
            resolvePhoneTerminalBottomBarMode(TerminalState(connected = true, shellReady = true)),
        )
        assertEquals(
            PhoneTerminalBottomBarMode.ExitedRestartBar,
            resolvePhoneTerminalBottomBarMode(TerminalState(connected = false, exited = true)),
        )
        assertEquals(
            PhoneTerminalBottomBarMode.ErrorRestartBar,
            resolvePhoneTerminalBottomBarMode(TerminalState(connected = false, reconnecting = false, error = "PTY failed")),
        )
    }

    // ─────────────────── Requirement 12.3: Tablet Dock & KeyRow Hardware Keyboard ───────────────────

    @Test
    fun `tablet dock pill cap and hardware keyboard key row visibility follow Tablet 4 rules`() {
        val tabs = listOf("terminal:1", "terminal:2", "terminal:3", "terminal:4")

        // Bottom dock shows all pills
        val (bottomVisible, bottomOverflow) = visibleDockPillIds(
            tabIds = tabs,
            activeId = "terminal:3",
            sideDock = false,
        )
        assertEquals(tabs, bottomVisible)
        assertTrue(bottomOverflow.isEmpty())

        // Side dock (Left/Right) caps visible pills at DOCK_PILL_CAP (2) and keeps activeId visible
        val (sideVisible, sideOverflow) = visibleDockPillIds(
            tabIds = tabs,
            activeId = "terminal:3",
            sideDock = true,
            pillCap = DOCK_PILL_CAP,
        )
        assertEquals(listOf("terminal:1", "terminal:3"), sideVisible)
        assertEquals(listOf("terminal:2", "terminal:4"), sideOverflow)

        // Dock positions include Left, Bottom, Right
        assertEquals(listOf("left", "bottom", "right"), dockPositionOptions().map { it.first })

        // Hardware keyboard hides the key row by default, unless toggled on via "Show the key row"
        assertTrue(shouldShowTerminalKeyRow(hardwareKeyboardConnected = false, forceVisible = false))
        assertFalse(shouldShowTerminalKeyRow(hardwareKeyboardConnected = true, forceVisible = false))
        assertTrue(shouldShowTerminalKeyRow(hardwareKeyboardConnected = true, forceVisible = true))
    }

    // ─────────────────── Compose UI Tests ───────────────────

    @Test
    fun `phone terminal key row renders keys, toggles select mode tools, and handles landscape folded bar`() {
        var ctrlMode by mutableStateOf(false)
        var selectMode by mutableStateOf(false)
        var foldedLandscape by mutableStateOf(true)
        var pasted = 0
        val pressedKeys = mutableListOf<MobileKey>()

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                Column {
                    PhoneTerminalStatusBar(
                        state = TerminalState(connected = true, shellReady = true, sessionId = "term-a8c1"),
                        cwd = "~/Projects/ppm",
                        foldedInLandscape = foldedLandscape,
                        canFoldInLandscape = !foldedLandscape,
                        onShowFullBar = { foldedLandscape = false },
                        onFoldBar = { foldedLandscape = true },
                        onOpenActionsSheet = {},
                    )
                    TerminalMobileToolbar(
                        ctrlMode = ctrlMode,
                        onToggleCtrl = { ctrlMode = !ctrlMode },
                        selectMode = selectMode,
                        onToggleSelect = { selectMode = !selectMode },
                        onKey = { key, _ -> pressedKeys += key },
                        onCopy = {},
                        onPaste = { pasted++ },
                        onSendToChat = {},
                        onOpenLinks = {},
                        phoneKeyRowMode = true,
                        hideWhenHardwareKeyboardConnected = false,
                    )
                }
            }
        }

        // Verify folded 24dp landscape bar renders and expands when "Show bar ▾" is clicked
        composeRule.onNodeWithTag("phone-terminal-landscape-folded-bar").assertExists()
        composeRule.onNodeWithTag("phone-terminal-show-bar").performClick()
        assertFalse(foldedLandscape)
        composeRule.onNodeWithTag("phone-terminal-status-bar").assertExists()

        // Verify Phone key row keys exist and fire callbacks
        composeRule.onNodeWithTag("terminal-key-row").assertExists()
        composeRule.onNodeWithText("Esc").performClick()
        composeRule.onNodeWithText("Ctrl").performClick()
        composeRule.onNodeWithText("Paste").performScrollTo().performClick()
        assertEquals(listOf(MobileKey.Esc), pressedKeys)
        assertTrue(ctrlMode)
        assertEquals(1, pasted)

        // Switch to select mode and verify "Select text" and "Copy" appear at the start of the key row
        selectMode = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Select text").assertExists()
        composeRule.onNodeWithText("Copy").assertExists()
    }

    @Test
    fun `phone exited bottom bar and tablet empty dock state render and trigger actions`() {
        var restarted = 0
        var openedInDock = 0

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                Column {
                    PhoneTerminalExitedBottomBar(
                        isError = false,
                        cwd = "~/Projects/ppm",
                        sessionId = "term-a8c1",
                        onRestart = { restarted++ },
                    )
                    DockEmptyState(
                        onOpenTerminal = { openedInDock++ },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("phone-terminal-exited-bar").assertExists()
        composeRule.onNodeWithText("Process exited").assertExists()
        composeRule.onNodeWithTag("phone-terminal-restart-button").performClick()
        assertEquals(1, restarted)

        composeRule.onNodeWithTag("dock-empty-state").assertExists()
        composeRule.onNodeWithText("No terminals").assertExists()
        composeRule.onNodeWithTag("dock-empty-open-terminal").performClick()
        assertEquals(1, openedInDock)
    }
}
