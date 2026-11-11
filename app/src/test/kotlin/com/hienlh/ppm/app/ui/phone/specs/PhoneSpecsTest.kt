package com.hienlh.ppm.app.ui.phone.specs

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.app.openSpecs
import com.hienlh.ppm.app.specs
import com.hienlh.ppm.app.ui.phone.PhoneArea
import com.hienlh.ppm.app.ui.phone.PhoneNav
import com.hienlh.ppm.app.ui.phone.PhoneShell
import com.hienlh.ppm.app.ui.phone.buildPhoneAppBarActions
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ServerInfo
import com.hienlh.ppm.state.SpecStartChoice
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Spec mode on the phone against a scripted server: a spec's documents approved and sent back,
 * Run all and Stop, a chat bound to the spec it starts, and the Specs area in the drawer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
class PhoneSpecsTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()

    @After
    fun stop() {
        server.close()
    }

    /** A request as the server saw it: "METHOD /path" and its body. */
    private data class Seen(val line: String, val body: String)

    /** The spec routes of one spec, `login`, whose state moves as the buttons are pressed. */
    private inner class SpecServer(var state: String) : Dispatcher() {
        val seen = CopyOnWriteArrayList<Seen>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            val method = request.method
            seen += Seen("$method $path", request.body?.utf8().orEmpty())
            val base = "/api/project/ppm/specs/login"
            val data = when {
                method == "GET" && path == base -> view()
                method == "POST" && path == "$base/approve/requirements" -> {
                    state = "design"
                    """{"message":"Approved requirements.md.","spec":${view()}}"""
                }
                method == "POST" && path == "$base/request-changes/requirements" -> view()
                method == "POST" && path == "$base/run" -> {
                    state = "running"
                    """{"message":"Run all started.","spec":${view()}}"""
                }
                method == "POST" && path == "$base/stop" -> {
                    state = "execution"
                    """{"message":"Stopped.","spec":${view()}}"""
                }
                else -> "null"
            }
            return MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{"ok":true,"data":$data}""").build()
        }

        private fun phase(status: String, file: String, text: String?, submitted: Boolean = false) =
            """{"status":"$status","file":"specs/login/$file","issues":[],"text":${text?.let { "\"$it\"" } ?: "null"},"submitted":$submitted}"""

        fun view(): String {
            val execution = state == "execution" || state == "running"
            val current = when (state) {
                "requirements" -> "requirements"
                "design" -> "design"
                else -> "execution"
            }
            val req = if (state == "requirements") phase("draft", "requirements.md", "# Login needs\\n1. WHEN a user signs in THE SYSTEM SHALL greet them", true) else phase("approved", "requirements.md", "# Login needs")
            val design = when (state) {
                "requirements" -> phase("missing", "design.md", null)
                "design" -> phase("draft", "design.md", "# Design body", true)
                else -> phase("approved", "design.md", "# Design body")
            }
            val tasks = if (execution) phase("approved", "tasks.md", "- [ ] 1. Model") else phase("missing", "tasks.md", null)
            val taskList = if (execution) {
                """[{"id":"1","title":"Model","optional":false,"status":"todo","done":false,"next":"start","wave":0,"deps":[],"files":[],"requirements":[],"run":null},
                    {"id":"2","title":"Screen","optional":false,"status":"todo","done":false,"next":"wait","blockedReason":"Waits for 1","wave":1,"deps":["1"],"files":[],"requirements":[],"run":null}]"""
            } else {
                "[]"
            }
            val run = if (state == "running") """{"active":true,"startedAt":"x","wave":0}""" else "null"
            return """{"slug":"login","title":"Login flow","kind":"feature","workflow":"requirements-first","checks":"standard",
                "current":"$current","order":["requirements","design","tasks"],
                "phases":{"requirements":$req,"design":$design,"tasks":$tasks},
                "waves":{"waves":[["1"],["2"]],"moved":[],"optional":[],"issues":[]},"tasks":$taskList,
                "suite":null,"run":$run,"runLive":${state == "running"},"sessionId":"chat-1","updatedAt":"2026-11-11T08:00:00.000Z"}"""
        }
    }

    private fun model(dispatcher: Dispatcher): AppModel {
        server.dispatcher = dispatcher
        server.start()
        return AppModel(ApplicationProvider.getApplicationContext<Application>()).apply {
            api = ApiClient(server.url("/"), "secret")
            selectedProject = "ppm"
        }
    }

    private fun showSpec(model: AppModel) {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneSpecScreen(model = model, project = "ppm", slug = "login", onOpenChat = { _, _ -> })
            }
        }
    }

    private fun waitForTag(tag: String) = composeRule.waitUntil(5_000L) {
        composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForRequest(spec: SpecServer, line: String): Seen {
        composeRule.waitUntil(5_000L) { spec.seen.any { it.line == line } }
        return spec.seen.first { it.line == line }
    }

    @Test
    fun `a document is sent back with a note, then approved, and the next one shows`() {
        val spec = SpecServer("requirements")
        val model = model(spec)
        showSpec(model)

        waitForTag("phone-spec-document")
        composeRule.onNodeWithText("Submitted by the AI · waiting for you", useUnmergedTree = true).assertExists()

        composeRule.onNodeWithTag("phone-spec-request-changes").performClick()
        composeRule.onNodeWithTag("phone-spec-note").performTextInput("Use OAuth")
        composeRule.onNodeWithTag("phone-spec-note-send").performClick()
        val changes = waitForRequest(spec, "POST /api/project/ppm/specs/login/request-changes/requirements")
        assertEquals("""{"note":"Use OAuth","continue":true}""", changes.body)
        composeRule.waitUntil(5_000L) { composeRule.onAllNodes(hasTestTag("phone-spec-note")).fetchSemanticsNodes().isEmpty() }

        composeRule.onNodeWithTag("phone-spec-approve").performClick()
        val approve = waitForRequest(spec, "POST /api/project/ppm/specs/login/approve/requirements")
        assertEquals("""{"continue":true}""", approve.body)
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodes(hasText("Design body", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("phone-spec-approve").assertExists()
        assertEquals("Login flow", model.specs.openTitle)
    }

    @Test
    fun `Run all starts the tasks and Stop stops them`() {
        val spec = SpecServer("execution")
        showSpec(model(spec))

        waitForTag("phone-spec-run-all")
        composeRule.onNodeWithTag("phone-spec-task:1").assertExists()
        composeRule.onNodeWithTag("phone-spec-run-all").performClick()
        waitForRequest(spec, "POST /api/project/ppm/specs/login/run")
        waitForTag("phone-spec-stop")
        composeRule.onNodeWithText("Wave 1 of 2", useUnmergedTree = true).assertExists()
        assertEquals(0, composeRule.onAllNodesWithTag("phone-spec-run-all").fetchSemanticsNodes().size)

        composeRule.onNodeWithTag("phone-spec-stop").performClick()
        waitForRequest(spec, "POST /api/project/ppm/specs/login/stop")
        waitForTag("phone-spec-run-all")
        assertEquals(0, composeRule.onAllNodesWithTag("phone-spec-stop").fetchSemanticsNodes().size)
    }

    /** `by-session` answers [bound]; `POST /specs` makes `made`. */
    private inner class BindServer(private val bound: String?) : Dispatcher() {
        val seen = CopyOnWriteArrayList<Seen>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            seen += Seen(request.method + " " + request.url.encodedPath, request.body?.utf8().orEmpty())
            val data = when (request.url.encodedPath) {
                "/api/project/ppm/specs/by-session/chat-1" -> bound?.let { """{"slug":"$it","taskId":null,"providerId":"claude"}""" } ?: "null"
                "/api/project/ppm/specs" -> """{"slug":"made","title":"Add login","kind":"bugfix","workflow":"requirements-first","checks":"thorough","phases":{},"tasks":[]}"""
                else -> "null"
            }
            return MockResponse.Builder().body("""{"ok":true,"data":$data}""").build()
        }
    }

    @Test
    fun `a chat started as a spec gets a new spec bound to it, named by its first line`() {
        val bind = BindServer(bound = null)
        val model = model(bind)
        val slug = runBlocking {
            model.specs.bindSession(model.api!!, "ppm", "chat-1", "Add login\nwith OAuth", SpecStartChoice("bugfix", checks = "thorough"))
        }
        assertEquals("made", slug)
        val create = bind.seen.single { it.line == "POST /api/project/ppm/specs" }
        assertEquals("""{"title":"Add login","kind":"bugfix","workflow":"requirements-first","checks":"thorough","sessionId":"chat-1"}""", create.body)
    }

    @Test
    fun `a chat already bound keeps its spec, and a plain chat makes none`() {
        val bind = BindServer(bound = "existing")
        val model = model(bind)
        runBlocking {
            assertEquals("existing", model.specs.bindSession(model.api!!, "ppm", "chat-1", "x", SpecStartChoice("spec")))
            assertNull(model.specs.bindSession(model.api!!, "ppm", "chat-1", "x", SpecStartChoice("chat")))
        }
        assertFalse(bind.seen.any { it.line.startsWith("POST") })
        assertEquals(1, bind.seen.size)
    }

    @Test
    fun `openSpecs shows the list, not a spec left open`() {
        val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        model.specs.openSlug = "login"
        model.openSpecs()
        assertNull(model.specs.openSlug)
        assertTrue(model.specs.showRequested)
    }

    @Test
    fun `minimal mode offers the chat, files and settings only`() {
        assertEquals(setOf(PhoneArea.CHAT, PhoneArea.PROJECTS, PhoneArea.FILES, PhoneArea.SETTINGS), PhoneArea.entries.filter { PhoneArea.isAvailable(it, minimal = true) }.toSet())
        assertTrue(PhoneArea.entries.all { PhoneArea.isAvailable(it, minimal = false) })
        assertEquals(listOf("chat", "settings", "editor"), listOf("chat", "terminal", "settings", "git-graph", "editor", "database").filter { PhoneArea.isMinimalTabType(it) })
        fun labels(minimal: Boolean) = buildPhoneAppBarActions(
            activeArea = PhoneArea.CHAT,
            syntheticArea = null,
            hasRunningChat = false,
            onOpenChatHistory = {},
            onOpenTerminal = {},
            onNewChat = {},
            onNewTerminal = {},
            onRefreshGit = {},
            onBackToChat = {},
            minimal = minimal,
        ).map { it.label }
        assertEquals(listOf("Chat history", "Open terminal"), labels(false))
        assertEquals(listOf("Chat history"), labels(true))
    }

    private fun shell(minimal: Boolean): PhoneNav {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PrefsStore(context).apply { put("onboarding.dismissed", "1") }
        val model = AppModel(context).apply {
            selectedProject = "ppm"
            if (minimal) info = ServerInfo(version = "1", deviceName = null, tunnelActive = false, minimalMode = true)
        }
        val nav = PhoneNav()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneShell(model = model, nav = nav, prefs = prefs)
            }
        }
        composeRule.runOnIdle { nav.openDrawer() }
        composeRule.waitForIdle()
        return nav
    }

    @Test
    fun `the drawer opens the Specs list`() {
        shell(minimal = false)
        composeRule.onNodeWithText(PhoneArea.SOURCE_CONTROL.title, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText(PhoneArea.SPECS.title, useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-specs-screen").assertExists()
    }

    @Test
    fun `a server in minimal mode leaves Specs, git, the terminal and the database out of the drawer`() {
        shell(minimal = true)
        composeRule.onNodeWithText(PhoneArea.FILES.title, useUnmergedTree = true).assertExists()
        for (area in listOf(PhoneArea.SPECS, PhoneArea.SOURCE_CONTROL, PhoneArea.TERMINAL, PhoneArea.DATABASE)) {
            assertEquals(area.title, 0, composeRule.onAllNodesWithText(area.title, useUnmergedTree = true).fetchSemanticsNodes().size)
        }
    }
}
