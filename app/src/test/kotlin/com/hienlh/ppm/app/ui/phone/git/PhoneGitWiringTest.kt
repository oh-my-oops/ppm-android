package com.hienlh.ppm.app.ui.phone.git

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.phone.PhoneArea
import com.hienlh.ppm.app.ui.phone.PhoneNav
import com.hienlh.ppm.app.ui.phone.PhoneSourceControlAreaHost
import com.hienlh.ppm.app.ui.phone.phoneGitRefreshEvents
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.GitBranchState
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.git.GitChangeGroups
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The phone Source Control and git-diff tab against a server that answers as PPM does: the diff tab loads its lines,
 * the panel refetches on the app's git events and feeds the app's change cache, keeps the shared commit draft, and
 * stages single hunks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
class PhoneGitWiringTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()
    private val requests = mutableListOf<Recorded>()
    private lateinit var model: AppModel
    private lateinit var api: ApiClient

    @Volatile private var branchHead: String? = "feat/x"

    private data class Recorded(val method: String, val path: String, val query: String?, val body: String)

    @Before
    fun connect() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                synchronized(requests) {
                    requests += Recorded(request.method ?: "GET", path, request.url.encodedQuery, request.body?.utf8().orEmpty())
                }
                return when {
                    path.endsWith("/git/repos") ->
                        ok("""{"projectPath":"/work/ppm","rootIsRepo":true,"repos":[{"path":"/work/ppm","name":"ppm","relative":"."}]}""")
                    path.endsWith("/git/changes") -> {
                        val head = branchHead?.let { "\"$it\"" } ?: "null"
                        ok(
                            """{"branch":{"head":$head,"oid":null,"upstream":null,"upstreamGone":false,"ahead":0,"behind":0,"hasRemote":false},""" +
                                """"operation":null,"files":[{"path":"src/A.kt","x":".","y":"M","untracked":false,"conflict":false,"staged":null,""" +
                                """"unstaged":{"added":1,"removed":0,"blocks":[{"id":"h1","index":0,"oldStart":1,"oldLines":1,"newStart":1,"newLines":2,"added":1,"removed":0}]}}],""" +
                                """"stashes":0,"lastCommit":null,"truncated":false}""",
                        )
                    }
                    path.endsWith("/git/commit-draft") && request.method == "GET" -> ok("""{"message":"Draft from the web","updatedAt":1}""")
                    path.endsWith("/git/hunks") ->
                        ok(
                            """{"path":"src/A.kt","binary":false,"hunks":[{"id":"h1","index":0,"oldStart":1,"oldLines":1,"newStart":1,"newLines":2,""" +
                                """"lines":[{"type":" ","content":"val keep = 1"},{"type":"+","content":"val added = 2"}]}]}""",
                        )
                    path.endsWith("/git/file-full-diff") -> ok("""{"original":"val keep = 1\n","modified":"val keep = 1\nval fromServer = 2\n","binary":false}""")
                    path.endsWith("/files/compare") -> ok("""{"original":"left only\n","modified":"right only\n"}""")
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

    private fun diffTab(vararg meta: Pair<String, String>) = WorkspaceTab(
        id = "git-diff:test",
        type = "git-diff",
        title = "A.kt",
        projectId = "ppm",
        metadata = buildJsonObject { meta.forEach { (k, v) -> put(k, JsonPrimitive(v)) } },
    )

    @Test
    fun gitDiffTab_forAFile_rendersTheLinesTheServerDiffed() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneGitDiffTabHost(model = model, tab = diffTab("filePath" to "src/A.kt", "projectName" to "ppm"), onOpenFile = { _, _ -> })
            }
        }
        composeRule.waitUntil(5_000) { textShown("val fromServer = 2") }
        val request = recorded("GET", "/git/file-full-diff").single()
        assertEquals("/api/project/ppm/git/file-full-diff", request.path)
        assertTrue(request.query.orEmpty(), request.query.orEmpty().contains("file=src%2FA.kt") || request.query.orEmpty().contains("file=src/A.kt"))
        assertFalse("The repository is the project root, so no repo= is sent", request.query.orEmpty().contains("repo="))
        assertTrue(textShown("Working Tree"))
    }

    @Test
    fun gitDiffTab_comparingTwoFiles_rendersTheServersCompare() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneGitDiffTabHost(model = model, tab = diffTab("file1" to "a/left.txt", "file2" to "b/right.txt", "projectName" to "ppm"), onOpenFile = { _, _ -> })
            }
        }
        composeRule.waitUntil(5_000) { textShown("right only") && textShown("left only") }
        assertEquals(1, recorded("GET", "/files/compare").size)
    }

    @Test
    fun unifiedDiffText_buildsGitStyleHunks() {
        val text = unifiedDiffText("a\nb\nc\n", "a\nB\nc\n")
        assertTrue(text, text.startsWith("@@ -1,3 +1,3 @@\n"))
        assertTrue(text, text.contains("-b\n") && text.contains("+B\n") && text.contains(" a\n"))
        assertEquals("", unifiedDiffText("same\n", "same\n"))
    }

    @Test
    fun sourceControl_feedsTheAppsCache_keepsTheSharedDraft_refetchesOnGitEvents_andStagesOneHunk() {
        val nav = PhoneNav(initialTabId = PhoneNav.areaTabId(PhoneArea.SOURCE_CONTROL))
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneSourceControlAreaHost(model = model, tab = null, nav = nav)
            }
        }
        // Loaded changes go to the app (drawer badge, tree letters), as the old panel's reads do.
        composeRule.waitUntil(5_000) { model.cachedGitChanges("ppm") != null }
        assertEquals("src/A.kt", model.cachedGitChanges("ppm")?.files?.single()?.path)
        assertEquals(1, model.gitChangeCount("ppm"))

        // The commit message is the repository's shared draft.
        composeRule.waitUntil(5_000) { textShown("Draft from the web") }
        composeRule.onNodeWithTag("phone-git-commit-input").performTextInput(" more")
        // The draft is sent 300 ms after the last keystroke, on the app's main-thread scope.
        composeRule.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            recorded("PUT", "/git/commit-draft").isNotEmpty()
        }
        val draft = recorded("PUT", "/git/commit-draft").last().body
        assertTrue(draft, draft.contains("Draft from the web") && draft.contains(" more") && draft.contains("\"message\""))

        // A git event of this project (a file changed, PPM wrote, the app bar's Refresh status) reads the changes again.
        val reads = recorded("GET", "/git/changes").size
        composeRule.runOnIdle { model.refreshGitStatus("ppm") }
        composeRule.waitUntil(5_000) { recorded("GET", "/git/changes").size > reads }

        // Open the file, choose By hunk, stage the one hunk.
        composeRule.onNodeWithTag("phone-git-row-changes-src/A.kt", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { shown("phone-git-diff-hunk-header-0") }
        assertFalse("Whole file mode shows no per-hunk action", shown("phone-git-diff-hunk-action-0"))
        composeRule.onNodeWithTag("phone-git-diff-by-hunk-chip", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { shown("phone-git-diff-hunk-action-0") }
        assertTrue(textShown("Stage hunk"))
        composeRule.onNodeWithTag("phone-git-diff-hunk-action-0", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { recorded("POST", "/git/stage-hunks").isNotEmpty() }
        val stage = recorded("POST", "/git/stage-hunks").single()
        assertEquals("/api/project/ppm/git/stage-hunks", stage.path)
        assertTrue(stage.body, stage.body.contains("\"path\":\"src/A.kt\"") && stage.body.contains("\"id\":\"h1\""))
        assertFalse("A whole hunk sends no line selection", stage.body.contains("\"lines\""))
    }

    @Test
    fun sourceControl_refetchesWhenItsRefreshEventsFire() {
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val loaded = mutableListOf<String>()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneSourceControlScreen(
                    projectName = "ppm",
                    api = api,
                    refreshEvents = events,
                    onChangesLoaded = { _, changes -> loaded += changes.files.map { it.path } },
                )
            }
        }
        composeRule.waitUntil(5_000) { loaded.isNotEmpty() }
        val reads = recorded("GET", "/git/changes").size
        composeRule.runOnIdle { events.tryEmit(Unit) }
        composeRule.waitUntil(5_000) { recorded("GET", "/git/changes").size > reads }
        composeRule.waitUntil(5_000) { loaded.size >= 2 }
    }

    @Test
    fun sourceControl_neverInventsABranchName() {
        branchHead = null
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneSourceControlScreen(projectName = "ppm", api = api)
            }
        }
        composeRule.waitUntil(5_000) { shown("phone-git-row-changes-src/A.kt") }
        assertFalse(composeRule.onAllNodesWithText("main", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())

        assertNull(phoneGitBranchName(GitBranchState(head = null, oid = null, upstream = null, upstreamGone = false, ahead = 0, behind = 0)))
        assertEquals("1234567 (detached)", phoneGitBranchName(GitBranchState(head = null, oid = "1234567890", upstream = null, upstreamGone = false, ahead = 0, behind = 0)))
        val subline = formatPhoneGitSubline(
            GitBranchState(head = null, oid = null, upstream = null, upstreamGone = false, ahead = 0, behind = 0),
            GitChangeGroups.from(emptyList<com.hienlh.ppm.client.ChangedFile>()),
        )
        assertFalse(subline, subline.contains("main"))
    }

    @Test
    fun refreshEvents_followThisProjectsGitEvents_only() = runBlocking {
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(3_000) { phoneGitRefreshEvents(model, "ppm").first() }
        }
        delay(100)
        model.refreshGitStatus("other")
        delay(100)
        assertFalse("Another project's event is not this panel's", first.isCompleted)
        model.refreshGitStatus("ppm")
        first.await()
    }
}
