package com.hienlh.ppm.app.ui.phone.git

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.editor.ConflictEditorContent
import com.hienlh.ppm.app.ui.phone.PhoneArea
import com.hienlh.ppm.app.ui.phone.PhoneNav
import com.hienlh.ppm.app.ui.phone.PhoneSourceControlAreaHost
import com.hienlh.ppm.app.ui.phone.PhoneTabRoute
import com.hienlh.ppm.app.ui.phone.isTabletOnlyTab
import com.hienlh.ppm.app.ui.phone.resolvePhoneTabRoute
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.git.FileChange
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The git surfaces a phone used to send to a tablet: conflicts (the operation banner, Resolve, Mark as resolved,
 * the conflict editor), Review changes, Branch review, the Git Graph / commit list and line picking, all on the
 * phone layout against a server that answers as PPM does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
class PhoneGitParityTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()
    private val requests = mutableListOf<Recorded>()
    private lateinit var model: AppModel
    private lateinit var api: ApiClient

    /** What `/files/read` answers for the conflicted file. */
    @Volatile private var conflictedText = "<<<<<<< HEAD\nval a = 1\n=======\nval a = 2\n>>>>>>> feature\n"

    private data class Recorded(val method: String, val path: String, val body: String)

    @Before
    fun connect() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                synchronized(requests) { requests += Recorded(request.method ?: "GET", path, request.body?.utf8().orEmpty()) }
                return when {
                    path.endsWith("/git/repos") ->
                        ok("""{"projectPath":"/work/ppm","rootIsRepo":true,"repos":[{"path":"/work/ppm","name":"ppm","relative":"."}]}""")
                    path.endsWith("/git/changes") -> ok(
                        """{"branch":{"head":"main","oid":null,"upstream":null,"upstreamGone":false,"ahead":0,"behind":0,"hasRemote":false},""" +
                            """"operation":{"kind":"merge","name":"feature"},"files":[""" +
                            """{"path":"src/C.kt","x":"U","y":"U","untracked":false,"conflict":true,"staged":null,"unstaged":null},""" +
                            """{"path":"src/A.kt","x":".","y":"M","untracked":false,"conflict":false,"staged":null,""" +
                            """"unstaged":{"added":1,"removed":0,"blocks":[{"id":"h1","index":0,"oldStart":1,"oldLines":1,"newStart":1,"newLines":2,"added":1,"removed":0}]}}],""" +
                            """"stashes":0,"lastCommit":null,"truncated":false}""",
                    )
                    path.endsWith("/files/read") -> ok(buildJsonObject { put("content", JsonPrimitive(conflictedText)) }.toString())
                    path.endsWith("/git/graph") -> ok(
                        """{"commits":[{"hash":"abcdef1234567","abbreviatedHash":"abcdef1","subject":"Fix the phone","authorName":"Ann","authorDate":"2026-11-18T10:00:00Z","parents":[],"refs":[]}],""" +
                            """"branches":[],"head":"abcdef1234567"}""",
                    )
                    else -> ok("{}")
                }
            }
        }
        server.start()
        model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        api = ApiClient(server.url("/"), "tok")
        model.api = api
        model.selectedProject = "ppm"
    }

    @After
    fun disconnect() = server.close()

    private fun ok(data: String) = MockResponse.Builder()
        .addHeader("Content-Type", "application/json")
        .body("""{"ok":true,"data":$data}""")
        .build()

    private fun recorded(method: String, suffix: String) = synchronized(requests) { requests.filter { it.method == method && it.path.endsWith(suffix) } }

    private fun shown(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun textShown(text: String) = composeRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun tab(type: String, vararg meta: Pair<String, String>) = WorkspaceTab(
        id = "$type:ppm",
        type = type,
        title = type,
        projectId = "ppm",
        metadata = buildJsonObject { meta.forEach { (k, v) -> put(k, JsonPrimitive(v)) } },
    )

    @Test
    fun gitTabs_andTheGitGraphsPanels_openInSourceControl_onAPhone() {
        for (type in PHONE_GIT_TAB_TYPES) {
            val t = tab(type)
            assertFalse("$type is a phone tab now", isTabletOnlyTab(t))
            assertEquals(PhoneArea.SOURCE_CONTROL, (resolvePhoneTabRoute(t) as PhoneTabRoute.Area).area)
        }
        for (viewType in listOf("git-graph", "git-graph.blame", "git-graph.reflog")) {
            val graph = tab("extension", "viewType" to viewType)
            assertTrue(isGitGraphExtensionTab(graph))
            assertEquals(PhoneArea.SOURCE_CONTROL, PhoneArea.fromTab(graph))
            assertFalse(isTabletOnlyTab(graph))
        }
        assertFalse(isGitGraphExtensionTab(tab("extension", "viewType" to "todo-tree")))
        assertNull("A plain tab type is not the Git Graph", PhoneArea.fromTabType("git-graph"))
    }

    @Test
    fun sourceControl_hostsTheGitLogTab_fullScreen() {
        val nav = PhoneNav(initialTabId = "git-log:ppm")
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneSourceControlAreaHost(model = model, tab = tab("git-log", "projectName" to "ppm"), nav = nav)
            }
        }
        composeRule.waitUntil(5_000) { textShown("Fix the phone") }
        assertTrue(shown("phone-git-tab-body"))
        assertTrue(shown("git-log-panel"))
        assertFalse("The change list is not drawn under a git tab", shown("phone-source-control-screen"))
    }

    @Test
    fun sourceControl_hostsTheConflictEditorTab_withThePhonesStackedButtons() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneSourceControlAreaHost(
                    model = model,
                    tab = tab("conflict-editor", "projectName" to "ppm", "filePath" to "src/C.kt"),
                    nav = PhoneNav(initialTabId = "conflict-editor:ppm"),
                )
            }
        }
        composeRule.waitUntil(5_000) { shown("conflict-region-list-phone") }
        assertTrue(textShown("1 conflict remaining"))
        composeRule.onNodeWithTag("conflict-accept-incoming-0", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { recorded("PUT", "/files/write").isNotEmpty() }
        val written = recorded("PUT", "/files/write").single().body
        assertTrue(written, written.contains("val a = 2") && !written.contains("val a = 1") && !written.contains("<<<<<<<"))
    }

    @Test
    fun conflictEditor_phoneAndTabletLayouts() {
        val text = "<<<<<<< HEAD\nx\n=======\ny\n>>>>>>> b\n"
        val phone = androidx.compose.runtime.mutableStateOf(true)
        val saved = mutableListOf<String>()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = phone.value) {
                ConflictEditorContent(fileName = "C.kt", initialContent = text, onSaveContent = { saved += it })
            }
        }
        composeRule.waitUntil(5_000) { shown("conflict-region-list-phone") }
        // A phone gives each accept button 44 dp.
        val button = composeRule.onNodeWithTag("conflict-accept-both-0", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(button.size.height >= 44 * 2) // xhdpi: 2 px per dp
        // The tablet keeps its one-row cards.
        composeRule.runOnIdle { phone.value = false }
        composeRule.waitUntil(5_000) { !shown("conflict-region-list-phone") }
        assertTrue(shown("conflict-region-0") && shown("conflict-accept-current-0"))
        composeRule.onNodeWithTag("conflict-accept-current-0", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { saved.isNotEmpty() }
        assertTrue(saved.last(), saved.last().contains("x") && !saved.last().contains("y\n"))
    }

    @Test
    fun conflictRow_resolvesInTheEditor_marksResolvedAfterAsking_andTheMergeBannerAborts() {
        val resolved = mutableListOf<String>()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneSourceControlScreen(
                    projectName = "ppm",
                    api = api,
                    tabActions = PhoneGitTabActions(onResolveConflict = { resolved += it.path }),
                )
            }
        }
        composeRule.waitUntil(5_000) { shown("phone-git-row-changes-src/C.kt") }

        // The web's operation banner: what is happening, how many conflicts are left, Continue held until none are.
        assertTrue(textShown("Merging feature into main"))
        assertTrue(textShown("Resolve 1 conflict, then continue."))
        composeRule.onNodeWithTag("git-op-continue", useUnmergedTree = true).assertExists()
        assertFalse("The tablet-only notice is gone", textShown("tablet"))

        // A conflict row says Resolve and opens the conflict editor.
        assertTrue(shown("phone-git-resolve-src/C.kt"))
        composeRule.onNodeWithTag("phone-git-row-changes-src/C.kt", useUnmergedTree = true).performClick()
        assertEquals(listOf("src/C.kt"), resolved)

        // Its check cell marks it resolved; with git's markers still in the file, it asks first.
        composeRule.onNodeWithTag("phone-git-check-changes-src/C.kt", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { textShown("C.kt still has conflict markers") }
        assertTrue(recorded("POST", "/git/stage").isEmpty())
        val reads = recorded("GET", "/git/changes").size
        composeRule.onNodeWithText("Mark resolved", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { recorded("POST", "/git/stage").isNotEmpty() }
        assertTrue(recorded("POST", "/git/stage").single().body.contains("src/C.kt"))
        // The write is over once the changes have been read again; the banner's buttons wait for it.
        composeRule.waitUntil(5_000) { recorded("GET", "/git/changes").size > reads }
        composeRule.waitForIdle()

        // Abort asks, then posts the operation's abort.
        composeRule.onNodeWithTag("git-op-abort", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { textShown("Abort the merge?") }
        composeRule.onAllNodesWithText("Abort merge", useUnmergedTree = true).onLast().performClick()
        composeRule.waitUntil(5_000) { recorded("POST", "/git/operation/abort").isNotEmpty() }
    }

    @Test
    fun conflictWithoutMarkers_isMarkedResolvedAtOnce_fromTheRowSheet() {
        conflictedText = "val a = 2\n"
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneSourceControlScreen(projectName = "ppm", api = api, tabActions = PhoneGitTabActions(onResolveConflict = {}))
            }
        }
        composeRule.waitUntil(5_000) { shown("phone-git-row-changes-src/C.kt") }
        composeRule.onNodeWithTag("phone-git-row-changes-src/C.kt", useUnmergedTree = true).performTouchInput { longClick() }
        composeRule.waitUntil(5_000) { textShown("Mark as resolved") }
        assertTrue(textShown("Resolve conflict"))
        composeRule.onNodeWithText("Mark as resolved", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { recorded("POST", "/git/stage").isNotEmpty() }
        assertFalse(textShown("still has conflict markers"))
    }

    @Test
    fun header_review_history_branchReview_andLinePicking_handOverToTheirTabs() {
        val calls = mutableListOf<String>()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneSourceControlScreen(
                    projectName = "ppm",
                    api = api,
                    tabActions = PhoneGitTabActions(
                        onOpenReview = { change: FileChange? -> calls += "review:${change?.path ?: "all"}" },
                        onResolveConflict = {},
                        onPickLines = { change, scope -> calls += "lines:${change.path}:$scope" },
                        onOpenHistory = { calls += "history" },
                        historyLabel = "Open Git Graph",
                        onOpenBranchReview = { calls += "branch" },
                    ),
                )
            }
        }
        composeRule.waitUntil(5_000) { shown("phone-git-row-changes-src/A.kt") }

        composeRule.onNodeWithTag("phone-git-history-button", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("phone-git-review-button", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("phone-git-more-button", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { textShown("Review branch…") }
        composeRule.onNodeWithText("Review branch…", useUnmergedTree = true).performClick()

        composeRule.onNodeWithTag("phone-git-row-changes-src/A.kt", useUnmergedTree = true).performTouchInput { longClick() }
        composeRule.waitUntil(5_000) { textShown("Stage lines…") }
        assertTrue(textShown("Review changes"))
        composeRule.onNodeWithText("Stage lines…", useUnmergedTree = true).performClick()

        composeRule.onNodeWithTag("phone-git-row-changes-src/A.kt", useUnmergedTree = true).performTouchInput { longClick() }
        composeRule.waitUntil(5_000) { textShown("Review changes") }
        composeRule.onNodeWithText("Review changes", useUnmergedTree = true).performClick()

        assertEquals(listOf("history", "review:all", "branch", "lines:src/A.kt:worktree", "review:src/A.kt"), calls)
    }

    @Test
    fun withoutTabActions_theHeaderOffersNothingItCannotOpen() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneSourceControlScreen(projectName = "ppm", api = api)
            }
        }
        composeRule.waitUntil(5_000) { shown("phone-git-row-changes-src/A.kt") }
        assertFalse(shown("phone-git-history-button"))
        assertFalse(shown("phone-git-more-button"))
        assertFalse(shown("phone-git-review-button"))
        composeRule.onNodeWithTag("phone-git-refresh-button", useUnmergedTree = true).assertIsDisplayed()
    }
}
