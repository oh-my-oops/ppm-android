package com.hienlh.ppm.app.ui.extensions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
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

    @Test
    fun `TreeView nodes start closed auto-expand once when children arrive do not set row tooltip contentDescription and reset on root update`() {
        var headerClickedCmd: String? = null
        val expandedIds = mutableListOf<String>()
        var itemsState by androidx.compose.runtime.mutableStateOf(
            listOf(
                TreeItemUI(
                    id = "conn-1",
                    label = "Postgres 1",
                    tooltip = "Primary DB",
                    // Initial item with collapsibleState = "expanded" and no children starts closed
                    collapsibleState = "expanded",
                    children = null,
                ),
                TreeItemUI(
                    id = "conn-2",
                    label = "Postgres 2",
                    // Even with collapsibleState = "collapsed", non-empty children auto-expand once
                    collapsibleState = "collapsed",
                    children = listOf(
                        TreeItemUI(id = "tables", label = "Tables (2)", collapsibleState = "none"),
                    ),
                ),
            ),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                ExtensionTreeViewList(
                    viewId = "db.tree",
                    viewName = "Connections",
                    items = itemsState,
                    onExpand = { expandedIds += it },
                    onCommand = { cmd, _ -> headerClickedCmd = cmd },
                    headerActions = listOf(
                        com.hienlh.ppm.state.extensions.TreeHeaderAction(
                            command = "db.refresh",
                            title = "Refresh Connections",
                            icon = "refresh",
                        ),
                    ),
                )
            }
        }

        // Row does not set contentDescription to tooltip
        val rowSemantics = composeRule.onNodeWithTag("ext-tree-item-conn-1", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(
            "Expected no ContentDescription on tree row",
            !rowSemantics.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription),
        )

        // conn-1 started closed despite collapsibleState = "expanded"; clicking it requests children
        composeRule.onNodeWithTag("ext-tree-item-conn-1").performClick()
        assertEquals(listOf("conn-1"), expandedIds)

        // Children arrive for conn-1 (same child id "tables" under both parents must not crash LazyColumn)
        itemsState = listOf(
            TreeItemUI(
                id = "conn-1",
                label = "Postgres 1",
                tooltip = "Primary DB",
                collapsibleState = "collapsed",
                children = listOf(
                    TreeItemUI(id = "tables", label = "Tables (1)", collapsibleState = "none"),
                ),
            ),
            TreeItemUI(
                id = "conn-2",
                label = "Postgres 2",
                collapsibleState = "collapsed",
                children = listOf(
                    TreeItemUI(id = "tables", label = "Tables (2)", collapsibleState = "none"),
                ),
            ),
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Tables (1)").assertIsDisplayed()
        composeRule.onNodeWithText("Tables (2)").assertIsDisplayed()
        composeRule.onNodeWithTag("ext-tree-header-action-db.refresh").performClick()
        assertEquals("db.refresh", headerClickedCmd)

        // Root update replaces items with collapsed roots and no children -> stale expanded state is cleared
        itemsState = listOf(
            TreeItemUI(
                id = "conn-1",
                label = "Postgres 1",
                collapsibleState = "collapsed",
                children = null,
            ),
        )
        composeRule.waitForIdle()
        // Clicking conn-1 now expands it and triggers onExpand("conn-1") instead of collapsing
        composeRule.onNodeWithTag("ext-tree-item-conn-1").performClick()
        assertEquals(listOf("conn-1", "conn-1"), expandedIds)
    }

    @Test
    fun `LoadingOrFailedView displays activationError when failed`() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                ExtensionLoadingOrFailedView(
                    title = "Git Graph",
                    failed = true,
                    activationError = "RPC timeout: ext:activate (10000ms)",
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText("Extension failed to load").assertIsDisplayed()
        composeRule.onNodeWithText("RPC timeout: ext:activate (10000ms)").assertIsDisplayed()

        // resolveActivationError matches extensionId or ext-$viewType only, not ext-$slug (extension-webview.tsx:151)
        val errors = mapOf("ext-git-graph" to "Failed graph", "@ppm/ext-custom" to "Failed custom")
        assertEquals("Failed custom", resolveActivationError(errors, "@ppm/ext-custom", "git-graph.view"))
        assertEquals("Failed graph", resolveActivationError(errors, null, "git-graph"))
        assertNull(resolveActivationError(errors, null, "git-graph.view"))
    }

    @Test
    fun `loadGitRepos deduplicates concurrent in-flight requests and clears inflight by identity on force`() {
        val server = mockwebserver3.MockWebServer()
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        val gate1 = java.util.concurrent.CountDownLatch(1)
        val gate2 = java.util.concurrent.CountDownLatch(1)
        val entered1 = java.util.concurrent.CountDownLatch(1)
        val entered2 = java.util.concurrent.CountDownLatch(1)

        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
                if (request.target.endsWith("/git/repos")) {
                    val idx = callCount.incrementAndGet()
                    if (idx == 1) {
                        entered1.countDown()
                        gate1.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    } else if (idx == 2) {
                        entered2.countDown()
                        gate2.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    return mockwebserver3.MockResponse.Builder()
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"projectPath":"/work/app","rootIsRepo":true,"repos":[{"path":"/work/app","name":"app","relative":"."}]}}""")
                        .build()
                }
                return mockwebserver3.MockResponse.Builder()
                    .addHeader("Content-Type", "application/json")
                    .body("""{"ok":true,"data":{}}""")
                    .build()
            }
        }
        server.start()
        try {
            val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            val model = com.hienlh.ppm.app.AppModel(ctx)
            val client1 = com.hienlh.ppm.client.ApiClient(server.url("/"), "tok1")
            val client2 = com.hienlh.ppm.client.ApiClient(server.url("/"), "tok2")
            val client3 = com.hienlh.ppm.client.ApiClient(server.url("/"), "tok3")
            model.api = client1

            // First call starts request 1
            val job1 = model.scope.launch { model.loadGitRepos("app", force = false) }
            composeRule.waitUntil(5_000) { entered1.count == 0L }
            assertEquals(1, callCount.get())

            // Even with a different ApiClient token (bypassing HTTP-level InflightGets), a second non-force call joins gitReposInflight
            model.api = client2
            val job2 = model.scope.launch { model.loadGitRepos("app", force = false) }
            assertEquals(1, callCount.get())

            // Start a force reload while request 1 is still in flight
            val jobForce = model.scope.launch { model.loadGitRepos("app", force = true) }
            composeRule.waitUntil(5_000) { entered2.count == 0L }
            assertEquals(2, callCount.get())

            // Release request 1 first; its cleanup must NOT delete request 2's inflight entry
            gate1.countDown()
            composeRule.waitUntil(5_000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                job1.isCompleted && job2.isCompleted
            }

            // A third caller joining with force = false (even on client3) while request 2 is still running must join request 2
            model.api = client3
            val job3 = model.scope.launch { model.loadGitRepos("app", force = false) }
            assertEquals(2, callCount.get())

            model.api = client2
            gate2.countDown()
            composeRule.waitUntil(5_000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                jobForce.isCompleted && job3.isCompleted
            }

            assertEquals(2, callCount.get())
            assertEquals(1, model.gitDiscovery["app"]?.repos?.size)
        } finally {
            server.close()
        }
    }
}
