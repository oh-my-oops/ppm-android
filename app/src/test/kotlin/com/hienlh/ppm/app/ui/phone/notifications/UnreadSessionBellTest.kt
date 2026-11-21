package com.hienlh.ppm.app.ui.phone.notifications

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hienlh.ppm.app.ui.tablet.NotificationBellView
import com.hienlh.ppm.app.ui.tablet.NotificationBellCard
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.SessionUnread
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bell lists unread sessions, not notification events (`notification-bell-popover.tsx`): on a
 * phone as a sheet, on a tablet as the rail's card.
 */
@RunWith(RobolectricTestRunner::class)
class UnreadSessionBellTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val projects = listOf(ProjectInfo("ppm", "/p/ppm"), ProjectInfo("api", "/p/api", color = "#ff0000"))

    private fun entry(type: String, project: String, title: String?) =
        SessionUnread(type = type, count = 1, projectName = project, sessionTitle = title)

    private fun initial() = linkedMapOf(
        "s-done" to entry("done", "ppm", "Fix the bell"),
        "s-ask" to entry("question", "ppm", "Pick a colour"),
        "s-appr" to entry("approval_request", "api", "Deploy to prod"),
        "0123456789abcdef" to entry("done", "api", null),
        "s-assist" to entry("done", "__assistant__", "Morning digest"),
    )

    /** The opens a row asked for, in order: "assistant:<id>" or "<project>:<id>". */
    private val opened = mutableListOf<String>()

    private fun open(sessionId: String, project: String, clear: (String) -> Unit) =
        openBellSession(
            sessionId,
            project,
            openAssistant = { opened += "assistant:$it" },
            openChat = { id, p -> (projects.any { it.name == p }).also { ok -> if (ok) opened += "$p:$id" } },
            markRead = clear,
        )

    private fun assertListed() {
        // Project headings, the Assistant's under its tab title.
        composeRule.onNodeWithText("PPM").assertExists()
        composeRule.onNodeWithText("API").assertExists()
        composeRule.onNodeWithText("PPM ASSISTANT").assertExists()
        // One row per session, by its title, the untitled one by its id's first 12 characters.
        composeRule.onNodeWithText("Fix the bell").assertExists()
        composeRule.onNodeWithText("Pick a colour").assertExists()
        composeRule.onNodeWithText("Deploy to prod").assertExists()
        composeRule.onNodeWithText("0123456789ab...").assertExists()
        composeRule.onNodeWithText("Morning digest").assertExists()
        // What each was left on.
        composeRule.onNodeWithContentDescription("Question waiting", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Approval waiting", useUnmergedTree = true).assertExists()
        assertEquals(3, composeRule.onAllNodesWithContentDescriptionCount("Turn complete"))
        // No event rows any more.
        assertEquals(0, composeRule.onAllNodesWithText("Chat completed").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Waiting for approval").fetchSemanticsNodes().size)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithContentDescriptionCount(label: String) =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(label), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun `phone sheet lists unread sessions by project, and a tap opens that chat and clears it`() {
        var unread by mutableStateOf<Map<String, SessionUnread>>(initial())
        var dismissed = 0
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                NotificationsSheetContent(
                    unread = unread,
                    projects = projects,
                    onDismiss = { dismissed++ },
                    onClearAll = { unread = emptyMap() },
                    onOpenSession = { id, p -> if (open(id, p) { unread = unread - it }) dismissed++ },
                    onOpenSettings = {},
                )
            }
        }
        composeRule.onNodeWithText("5 unread sessions").assertExists()
        assertListed()

        composeRule.onNodeWithText("Deploy to prod").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("api:s-appr"), opened)
        assertEquals(1, dismissed)
        composeRule.onNodeWithText("4 unread sessions").assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("Deploy to prod").fetchSemanticsNodes().size)

        // An Assistant session opens in the Assistant.
        composeRule.onNodeWithText("Morning digest").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("api:s-appr", "assistant:s-assist"), opened)
        assertEquals(0, composeRule.onAllNodesWithText("PPM ASSISTANT").fetchSemanticsNodes().size)

        composeRule.onNodeWithContentDescription("Clear all").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No unread").assertExists()
        composeRule.onNodeWithText("All caught up").assertExists()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun `phone - a session under a project the server does not have opens nothing and stays unread`() {
        var unread by mutableStateOf<Map<String, SessionUnread>>(mapOf("s-x" to entry("done", "", "Orphan")))
        var dismissed = 0
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                NotificationsSheetContent(
                    unread = unread,
                    projects = projects,
                    onDismiss = { dismissed++ },
                    onClearAll = {},
                    onOpenSession = { id, p -> if (open(id, p) { unread = unread - it }) dismissed++ },
                    onOpenSettings = {},
                )
            }
        }
        composeRule.onNodeWithText("UNKNOWN").assertExists()
        composeRule.onNodeWithText("Orphan").performClick()
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), opened)
        assertEquals(0, dismissed)
        composeRule.onNodeWithText("1 unread session").assertExists()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1280dp-h900dp")
    fun `tablet bell badge counts the unread sessions and follows them`() {
        var unread by mutableStateOf<Map<String, SessionUnread>>(initial())
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                NotificationBellView(
                    unread = unread,
                    projects = projects,
                    onClearAll = {},
                    onOpenSession = { _, _ -> true },
                    onOpenSettings = {},
                )
            }
        }
        composeRule.onNodeWithTag("notification-bell-badge", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("5", useUnmergedTree = true).assertExists()
        composeRule.runOnIdle { unread = unread - "s-done" }
        composeRule.onNodeWithText("4", useUnmergedTree = true).assertExists()
        composeRule.runOnIdle { unread = emptyMap() }
        assertEquals(0, composeRule.onAllNodesWithTag("notification-bell-badge", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    // The card is drawn on its own: a popup window does not run under Robolectric.
    @Test
    @Config(sdk = [34], qualifiers = "w1280dp-h900dp")
    fun `tablet card lists unread sessions by project, and a tap opens that chat and clears it`() {
        var unread by mutableStateOf<Map<String, SessionUnread>>(initial())
        var closed = 0
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                NotificationBellCard(
                    unread = unread,
                    projects = projects,
                    tokens = com.hienlh.ppm.design.LocalPpmTokens.current,
                    onClearAll = { unread = emptyMap() },
                    onOpenSession = { id, p -> open(id, p) { unread = unread - it } },
                    onOpenSettings = {},
                    close = { closed++ },
                )
            }
        }
        composeRule.onNodeWithText("5 unread sessions").assertExists()
        assertListed()

        composeRule.onNodeWithText("Fix the bell").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("ppm:s-done"), opened)
        composeRule.onNodeWithText("4 unread sessions").assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("Fix the bell").fetchSemanticsNodes().size)

        composeRule.onNodeWithText("0123456789ab...").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("ppm:s-done", "api:0123456789abcdef"), opened)
        composeRule.onNodeWithText("3 unread sessions").assertExists()

        composeRule.onNodeWithText("Morning digest").performClick()
        composeRule.waitForIdle()
        assertEquals("assistant:s-assist", opened.last())

        composeRule.onNodeWithContentDescription("Clear all").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No unread").assertExists()
        composeRule.onNodeWithText("All caught up").assertExists()
        assertEquals(0, composeRule.onAllNodesWithContentDescriptionCount("Turn complete"))
    }
}
