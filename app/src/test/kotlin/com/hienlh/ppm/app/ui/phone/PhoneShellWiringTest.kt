package com.hienlh.ppm.app.ui.phone

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
class PhoneShellWiringTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun everyPhoneAreaAndWorkspaceTab_rendersDedicatedRedesignedScreen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PrefsStore(context).apply {
            put("onboarding.dismissed", "1")
        }
        val model = AppModel(context).apply {
            selectedProject = "ppm"
        }
        val editorTab = WorkspaceTab(
            id = "editor:src/Main.kt",
            type = "editor",
            title = "Main.kt",
            projectId = "ppm",
            metadata = buildJsonObject {
                put("filePath", "src/Main.kt")
                put("inlineContent", "fun main() {\n    println(\"hi\")\n}\n")
            },
        )
        val diffTab = WorkspaceTab(
            id = "git-diff:src/Main.kt",
            type = "git-diff",
            title = "Main.kt",
            projectId = "ppm",
            metadata = buildJsonObject {
                put("filePath", "src/Main.kt")
                put("diff", "@@ -1,1 +1,2 @@\n-old\n+new\n")
            },
        )
        val testTabs = listOf(editorTab, diffTab)
        val nav = PhoneNav(initialTabId = PhoneNav.areaTabId(PhoneArea.CHAT))

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneShell(
                    model = model,
                    nav = nav,
                    prefs = prefs,
                    overrideOpenTabs = testTabs,
                )
            }
        }

        // 1. Chat area renders PhoneChatScreen and day-grouped history sheet when visited as Chat History
        composeRule.onNodeWithTag("phone-chat-screen").assertExists()
        composeRule.onNodeWithTag("phone-chat-history-sheet").assertExists()
        composeRule.onNodeWithTag("phone-chat-history-list", useUnmergedTree = true).assertExists()

        // 2. Files area renders PhoneFilesTreeScreen
        composeRule.runOnIdle {
            nav.visitTab(PhoneNav.areaTabId(PhoneArea.FILES))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-files-screen").assertExists()

        // 2b. Visiting an editor tab renders PhoneEditorScreen via PhoneFileTabScreen
        composeRule.runOnIdle {
            nav.visitTab(editorTab.id)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-editor-screen").assertExists()

        // 3. Source Control area renders PhoneSourceControlScreen
        composeRule.runOnIdle {
            nav.visitTab(PhoneNav.areaTabId(PhoneArea.SOURCE_CONTROL))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-source-control-screen").assertExists()

        // 3b. Visiting a git-diff tab renders PhoneDiffScreen
        composeRule.runOnIdle {
            nav.visitTab(diffTab.id)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-diff-screen").assertExists()

        // 4. Terminal area renders phone terminal screen + status bar + phone 2-row key toolbar
        composeRule.runOnIdle {
            nav.visitTab(PhoneNav.areaTabId(PhoneArea.TERMINAL))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-terminal-screen").assertExists()
        composeRule.onNodeWithTag("phone-terminal-status-bar").assertExists()
        composeRule.onNodeWithTag("terminal-key-row").assertExists()

        // 5. Database area renders DatabaseSidebarPane in read-only mode
        composeRule.runOnIdle {
            nav.visitTab(PhoneNav.areaTabId(PhoneArea.DATABASE))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-database-screen").assertExists()
        composeRule.onNodeWithTag("db-sidebar-readonly-badge").assertExists()

        // 6. Monitor area renders PhoneSystemMonitorScreen
        composeRule.runOnIdle {
            nav.visitTab(PhoneNav.areaTabId(PhoneArea.MONITOR))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-sysmon-screen").assertExists()

        // 7. Logs area renders PhoneLogsScreen
        composeRule.runOnIdle {
            nav.visitTab(PhoneNav.areaTabId(PhoneArea.LOGS))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-logs-screen").assertExists()

        // 8. Port Forwarding area renders PhonePortForwardingScreen
        composeRule.runOnIdle {
            nav.visitTab(PhoneNav.areaTabId(PhoneArea.PORT_FORWARDING))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-tunnels-screen").assertExists()

        // 9. Schedules area renders PhoneSchedulesScreen
        composeRule.runOnIdle {
            nav.visitTab(PhoneNav.areaTabId(PhoneArea.SCHEDULES))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-schedules-screen").assertExists()

        // 10. Settings area renders PhoneSettingsScreen
        composeRule.runOnIdle {
            nav.visitTab(PhoneNav.areaTabId(PhoneArea.SETTINGS))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-settings-screen").assertExists()
        composeRule.onNodeWithTag("phone-settings-home-list").assertExists()
    }

    @Test
    fun drawerSheets_openDedicatedProjectSwitcherNotificationsAndCloudShareSheets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PrefsStore(context).apply {
            put("onboarding.dismissed", "1")
        }
        val model = AppModel(context).apply {
            selectedProject = "ppm"
        }
        val nav = PhoneNav()

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneShell(
                    model = model,
                    nav = nav,
                    prefs = prefs,
                )
            }
        }

        // Open ProjectSwitcherSheet
        composeRule.runOnIdle {
            nav.pushSheet(SheetKey.ProjectSwitcher)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("project-switcher-sheet").assertExists()
        composeRule.onNodeWithText("Search projects…", substring = true, useUnmergedTree = true).assertExists()

        // Close ProjectSwitcherSheet and open NotificationsSheet
        composeRule.runOnIdle {
            nav.closeSheet(SheetKey.ProjectSwitcher)
            nav.pushSheet(SheetKey.Notifications)
        }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithTag("project-switcher-sheet").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("notifications-sheet").assertExists()

        // Close NotificationsSheet and open PhoneCloudShareSheet
        composeRule.runOnIdle {
            nav.closeSheet(SheetKey.Notifications)
            nav.pushSheet(SheetKey.CloudShare)
        }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithTag("notifications-sheet").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("phone-cloud-share-sheet").assertExists()
    }

    @Test
    fun onboardingHost_showsOnlyOnFirstLaunchAndRemainsHiddenAfterDismissal() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PrefsStore(context).apply {
            put("onboarding.dismissed", "0")
        }
        val model = AppModel(context).apply {
            selectedProject = "ppm"
        }
        var launchCount by mutableIntStateOf(0)

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                key(launchCount) {
                    PhoneShell(
                        model = model,
                        prefs = prefs,
                    )
                }
            }
        }

        // First launch: onboarding welcome sheet is displayed
        composeRule.onNodeWithText("Welcome to PPM").assertIsDisplayed()

        // Dismiss via "Skip for now" -> persists onboarding.dismissed = "1"
        composeRule.onNodeWithText("Skip for now").performClick()
        composeRule.waitForIdle()
        assertEquals("1", prefs.get("onboarding.dismissed"))
        assertEquals(0, composeRule.onAllNodesWithText("Welcome to PPM").fetchSemanticsNodes().size)

        // Second launch with the same PrefsStore: onboarding does not appear
        launchCount = 1
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("Welcome to PPM").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("phone-chat-screen").assertExists()
    }
}
