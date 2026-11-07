package com.hienlh.ppm.app.ui.extensions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.extensions.InputBoxOptions
import com.hienlh.ppm.state.extensions.InputBoxRequest
import com.hienlh.ppm.state.extensions.QuickPickItemUI
import com.hienlh.ppm.state.extensions.QuickPickOptions
import com.hienlh.ppm.state.extensions.QuickPickRequest
import com.hienlh.ppm.state.extensions.StatusBarItemUI
import com.hienlh.ppm.state.extensions.TreeItemAction
import com.hienlh.ppm.state.extensions.TreeItemUI
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class ExtensionUiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun `TreeView renders items badges actions and expands on click`() {
        var expandedId: String? = null
        var dispatchedCmd: String? = null
        var dispatchedArgs: List<JsonElement> = emptyList()

        val items = listOf(
            TreeItemUI(
                id = "branch-main",
                label = "main",
                description = "origin/main",
                badge = "2",
                icon = "git-branch",
                collapsibleState = "collapsed",
                actions = listOf(
                    TreeItemAction(
                        icon = "refresh",
                        tooltip = "Refresh",
                        command = "git-graph.refresh",
                        commandArgs = listOf(JsonPrimitive("main")),
                    ),
                ),
            ),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                ExtensionTreeViewList(
                    viewId = "git-graph.branches",
                    viewName = "Branches",
                    items = items,
                    onExpand = { expandedId = it },
                    onCommand = { cmd, args ->
                        dispatchedCmd = cmd
                        dispatchedArgs = args
                    },
                )
            }
        }

        composeRule.onNodeWithText("BRANCHES").assertIsDisplayed()
        composeRule.onNodeWithText("main").assertIsDisplayed()
        composeRule.onNodeWithText("origin/main").assertIsDisplayed()
        composeRule.onNodeWithText("2").assertIsDisplayed()

        composeRule.onNodeWithTag("ext-tree-item-branch-main").performClick()
        assertEquals("branch-main", expandedId)

        composeRule.onNodeWithTag("ext-tree-action-branch-main-git-graph.refresh").performClick()
        assertEquals("git-graph.refresh", dispatchedCmd)
        assertEquals(listOf(JsonPrimitive("main")), dispatchedArgs)
    }

    @Test
    fun `StatusBarRow strips codicons sorts by priority and dispatches command`() {
        var clickedCmd: String? = null
        val items = listOf(
            StatusBarItemUI(
                id = "sb-low",
                text = "$(history) History",
                command = "ext.history",
                alignment = "left",
                priority = 10,
            ),
            StatusBarItemUI(
                id = "sb-high",
                text = "$(git-branch) Git Graph",
                command = "git-graph.view",
                alignment = "left",
                priority = 100,
            ),
            StatusBarItemUI(
                id = "sb-right",
                text = "Right Only",
                alignment = "right",
                priority = 50,
            ),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                ExtensionStatusBarRow(
                    items = items,
                    alignment = "left",
                    onCommand = { clickedCmd = it },
                )
            }
        }

        composeRule.onNodeWithText("Git Graph").assertIsDisplayed()
        composeRule.onNodeWithText("History").assertIsDisplayed()
        composeRule.onNodeWithTag("ext-statusbar-sb-high").performClick()
        assertEquals("git-graph.view", clickedCmd)
    }

    @Test
    fun `QuickPick filters and resolves single or multi selection`() {
        var resolved: List<QuickPickItemUI>? = null
        val req = QuickPickRequest(
            requestId = "qp-1",
            items = listOf(
                QuickPickItemUI(label = "main", description = "default branch", picked = true),
                QuickPickItemUI(label = "feat/android", description = "feature branch"),
            ),
            options = QuickPickOptions(placeholder = "Choose branches", canPickMany = true),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                ExtensionQuickPickContent(
                    request = req,
                    onResolve = { resolved = it },
                )
            }
        }

        composeRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeRule.onNodeWithTag("ext-quickpick-item-1").performClick()
        composeRule.onNodeWithText("2 selected").assertIsDisplayed()
        composeRule.onNodeWithTag("ext-quickpick-ok").performClick()
        assertEquals(2, resolved?.size)
    }

    @Test
    fun `InputBox edits text and resolves on OK or Cancel`() {
        var resolved: String? = "unset"
        val req = InputBoxRequest(
            requestId = "ib-1",
            options = InputBoxOptions(
                prompt = "Enter tag name",
                value = "v1.0.0",
                placeholder = "vX.Y.Z",
            ),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                ExtensionInputBoxContent(
                    request = req,
                    onResolve = { resolved = it },
                )
            }
        }

        composeRule.onNodeWithText("Enter tag name").assertIsDisplayed()
        composeRule.onNodeWithTag("ext-inputbox-input").performTextReplacement("v2.0.0")
        composeRule.onNodeWithTag("ext-inputbox-ok").performClick()
        assertEquals("v2.0.0", resolved)

        composeRule.onNodeWithTag("ext-inputbox-cancel").performClick()
        assertNull(resolved)
    }

    @Test
    fun `LoadingOrFailedView shows retry button when failed`() {
        var retried = false
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                ExtensionLoadingOrFailedView(
                    title = "Git Graph",
                    failed = true,
                    onRetry = { retried = true },
                )
            }
        }

        composeRule.onNodeWithTag("ext-webview-failed").assertIsDisplayed()
        composeRule.onNodeWithText("Extension failed to load").assertIsDisplayed()
        composeRule.onNodeWithTag("ext-webview-retry").performClick()
        assertTrue(retried)
    }
}
