package com.hienlh.ppm.app.ui.tablet

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatSpecBinding
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.LocalRedesign
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.app.openSpecs
import com.hienlh.ppm.app.specs
import com.hienlh.ppm.app.ui.ChatPane
import com.hienlh.ppm.app.ui.ModePanel
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.state.DraftLocalStore
import com.hienlh.ppm.state.PermissionMode
import com.hienlh.ppm.state.SpecStartChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Spec mode on the tablet against a scripted server: the sidebar's Specs, the spec pane inside a
 * chat bound to it (beside a wide chat, above a narrow one, hidden and shown from the composer's
 * chip), the Modes menu's Workflow group and the workflow dialog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h900dp")
class TabletSpecsTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var context: Context
    private lateinit var model: AppModel
    private lateinit var api: ApiClient
    private val session = SessionInfo(id = "s-1", providerId = "claude", title = "Login work")

    /** "METHOD /path" and the body, as the server saw them. */
    private data class Seen(val line: String, val body: String)

    private val seen = CopyOnWriteArrayList<Seen>()

    /** Whether `s-1` is bound to the spec `login`; a POST to `/specs` binds it. */
    @Volatile private var bound = true

    private val loginView = """{"slug":"login","title":"Login flow","kind":"feature","workflow":"requirements-first","checks":"thorough",
        "current":"requirements","order":["requirements","design","tasks"],
        "phases":{"requirements":{"status":"draft","file":"specs/login/requirements.md","issues":[],"text":"# Login needs","submitted":true,"approveBlocked":null},
        "design":{"status":"missing","file":"specs/login/design.md","issues":[],"text":null,"submitted":false,"approveBlocked":null},
        "tasks":{"status":"missing","file":"specs/login/tasks.md","issues":[],"text":null,"submitted":false,"approveBlocked":null}},
        "waves":{"waves":[],"moved":[],"optional":[],"issues":[]},"tasks":[],
        "suite":null,"run":null,"runLive":false,"sessionId":"s-1","updatedAt":"2026-11-11T08:00:00.000Z"}"""

    private val specList = """[
        {"slug":"login","title":"Login flow","kind":"feature","checks":"thorough","current":"requirements","tasksDone":0,"tasksTotal":0,"red":0,"running":0,"waitingForYou":true,"tasks":[],"sessionId":"s-1","updatedAt":"2026-11-11T08:00:00.000Z"},
        {"slug":"cli-made","title":"Made from the CLI","kind":"bugfix","checks":"standard","current":"execution","tasksDone":1,"tasksTotal":3,"red":0,"running":0,"waitingForYou":false,"tasks":[],"sessionId":null,"updatedAt":"2026-11-11T07:00:00.000Z"}
    ]"""

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val method = request.method
                seen += Seen("$method $path", request.body?.utf8().orEmpty())
                val data = when {
                    path.startsWith("/ws/") -> return MockResponse.Builder().code(503).build()
                    path.endsWith("/messages") -> """{"messages":[],"total":0,"hasMore":false}"""
                    path == "/api/project/p/specs/by-session/s-1" -> if (bound) """{"slug":"login","taskId":null,"providerId":"claude"}""" else "null"
                    method == "GET" && path == "/api/project/p/specs" -> specList
                    method == "POST" && path == "/api/project/p/specs" -> {
                        bound = true
                        loginView
                    }
                    path == "/api/project/p/specs/login" -> loginView
                    method == "POST" && path == "/api/project/p/chat/sessions" -> """{"id":"s-new","providerId":"claude","title":"Made from the CLI"}"""
                    method == "POST" && path == "/api/project/p/specs/cli-made/session" -> loginView
                    else -> "{}"
                }
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("""{"ok":true,"data":$data}""").build()
            }
        }
        server.start()
        context = ApplicationProvider.getApplicationContext<Application>()
        model = AppModel(context)
        api = ApiClient(server.url("/"), "secret")
        model.api = api
        model.selectedProject = "p"
        model.projects = listOf(ProjectInfo(name = "p", path = "/repo"))
        model.sessions = listOf(session)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun startedTab(): ChatTab = ChatTab("tab-1", session, "p", "claude").also {
        it.start(api, scope, DraftLocalStore(PrefsStore(context, "tablet-specs-test"), "server"))
    }

    private fun showChat(tab: ChatTab, widthDp: Int, heightDp: Int) {
        compose.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Box(Modifier.size(width = widthDp.dp, height = heightDp.dp)) {
                    ChatPane(model = model, tab = tab)
                }
            }
        }
        compose.waitForIdle()
    }

    /** Waits for [condition], running the main looper the app's own scope (`model.scope`) resumes on. */
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(5_000L) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        condition()
    }

    private fun waitForTag(tag: String) = waitFor {
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForNoTag(tag: String) = waitFor {
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test
    fun `the rail has Specs after Designs, and its panel lists the specs and opens one with no chat in a new chat`() {
        val sections = model.sidebarSections
        assertEquals(sections.indexOf("designs") + 1, sections.indexOf("specs"))
        assertTrue("specs" in DEFAULT_RAIL_SECTIONS)

        compose.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Box(Modifier.size(width = 336.dp, height = 700.dp)) { SpecsSidebarPanel(model) }
            }
        }
        waitForTag("spec-row:login")
        compose.onNodeWithTag("spec-row:cli-made").assertExists()
        compose.onNodeWithText("Made from the CLI", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("· 1/3", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("specs-new").assertExists()

        // A spec `ppm spec new` made has no chat: one is made and bound to it, its pane asked for.
        compose.onNodeWithTag("spec-row:cli-made").performClick()
        waitFor { seen.any { it.line == "POST /api/project/p/specs/cli-made/session" } }
        assertEquals("""{"title":"Made from the CLI"}""", seen.first { it.line == "POST /api/project/p/chat/sessions" }.body)
        assertEquals("""{"sessionId":"s-new"}""", seen.first { it.line == "POST /api/project/p/specs/cli-made/session" }.body)
        assertTrue(model.specs.paneRequested("cli-made"))
    }

    @Test
    fun `a wide chat bound to a spec shows it beside the conversation, and the chip hides and shows it`() {
        val tab = startedTab()
        showChat(tab, widthDp = 1200, heightDp = 800)

        waitForTag("spec-pane-beside")
        waitForTag("spec-document")
        compose.onNodeWithText("Login flow", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("spec-approve").assertExists()
        // Its own chat is the one beside it.
        assertEquals(0, count("spec-open-chat"))
        waitFor { model.specs.bound["tab-1"]?.title == "Login flow" }
        assertEquals(ChatSpecBinding("login", "Login flow", "thorough"), model.specs.bound["tab-1"])

        compose.onNodeWithTag("composer-spec-chip").performClick()
        waitForNoTag("spec-pane-beside")
        compose.onNodeWithTag("chat-footer").assertExists()

        compose.onNodeWithTag("composer-spec-chip").performClick()
        waitForTag("spec-pane-beside")
        waitForTag("spec-close")
        compose.onNodeWithTag("spec-close").performClick()
        waitForNoTag("spec-pane-beside")
    }

    @Test
    fun `a narrow chat puts the spec above the conversation`() {
        val tab = startedTab()
        showChat(tab, widthDp = 700, heightDp = 900)

        waitForTag("spec-pane-above")
        assertEquals(0, count("spec-pane-beside"))
        assertTrue(specPaneBeside(768.dp))
        assertEquals(340.dp, specPaneWidth(700.dp))
        assertEquals(760.dp, specPaneWidth(2000.dp))
    }

    @Test
    fun `a chat with no spec starts one from the Modes menu's Workflow group, and then shows it`() {
        bound = false
        val tab = startedTab()
        // The Modes menu as its popover draws it (a Robolectric window cannot host the popup), beside the chat.
        compose.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Row(Modifier.size(width = 1280.dp, height = 800.dp)) {
                    Box(Modifier.width(940.dp).fillMaxHeight()) { ChatPane(model = model, tab = tab) }
                    ModePanel(PermissionMode.DEFAULT, pick = {}, footer = { SpecWorkflowMenuSection(model, tab, onDone = {}) })
                }
            }
        }
        waitFor { seen.any { it.line == "GET /api/project/p/specs/by-session/s-1" } }
        assertEquals(0, count("composer-spec-chip"))
        assertEquals(0, count("spec-pane-beside"))
        waitForTag("modes-workflow")
        compose.onNodeWithText("Turn this chat into a spec. The AI picks it up with your next message.", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("modes-workflow:bugfix").performClick()

        waitFor { seen.any { it.line == "POST /api/project/p/specs" } }
        assertEquals(
            """{"title":"Login work","kind":"bugfix","workflow":"requirements-first","checks":"standard","sessionId":"s-1"}""",
            seen.first { it.line == "POST /api/project/p/specs" }.body,
        )
        waitForTag("spec-pane-beside")
        compose.onNodeWithTag("composer-spec-chip").assertExists()
        // The request to show it is taken by the chat bound to it.
        waitFor { !model.specs.paneRequested("login") }
    }

    @Test
    fun `a new chat's workflow chip opens the workflow dialog, which picks a workflow`() {
        var clicked = 0
        compose.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Box(Modifier.size(width = 1000.dp, height = 800.dp)) {
                    SpecWorkflowChipView(
                        binding = null,
                        pending = SpecStartChoice("quick", checks = "thorough"),
                        narrow = false,
                        onClick = {
                            clicked++
                            model.specs.choosingFor = "tab-new"
                        },
                    )
                    TabletSpecStartDialog(model)
                }
            }
        }
        compose.onNodeWithText("Quick Spec", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("composer-spec-chip").performClick()
        assertEquals(1, clicked)
        waitForTag("spec-start-sheet")
        compose.onNodeWithTag("spec-workflow:chat").assertExists()
        compose.onNodeWithTag("spec-workflow:bugfix").performClick()
        compose.onNodeWithText("Start Bug Fix", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("spec-start-confirm").performClick()
        compose.waitForIdle()
        assertNull(model.specs.choosingFor)
        waitForNoTag("spec-start-sheet")
    }

    @Test
    fun `a chip shows nothing for a plain chat`() {
        compose.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                SpecWorkflowChipView(binding = null, pending = SpecStartChoice("chat"), narrow = false, onClick = {})
            }
        }
        assertEquals(0, count("composer-spec-chip"))
    }

    @Test
    fun `the shell's rail opens the Specs panel, openSpecs reaches it, and a chat's chooser request shows the dialog`() {
        model.setWindowWide(true)
        compose.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalRedesign provides true) {
                    Box(Modifier.size(1280.dp, 800.dp)) { TabletShell(model = model) }
                }
            }
        }
        compose.onNodeWithContentDescription("Specs").performClick()
        waitForTag("specs-panel")
        waitForTag("spec-row:login")
        assertEquals("specs", model.sidebarSection)

        compose.onNodeWithContentDescription("Explorer").performClick()
        waitForNoTag("specs-panel")
        compose.runOnIdle { model.openSpecs() }
        waitForTag("specs-panel")
        assertTrue(!model.specs.showRequested)

        compose.runOnIdle { model.specs.choosingFor = "tab-new" }
        waitForTag("spec-start-sheet")
        compose.onNodeWithText("Workflow", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a pane request is taken once, and a tab no workspace holds claims no spec`() {
        model.specs.bound["tab-x"] = ChatSpecBinding("login")
        model.specs.setPaneOpen("tab-x", false)
        // No workspace holds tab-x, so nothing claims it: the request is kept for the chat that will.
        assertNull(model.specs.tabShowing("login"))
        model.specs.requestPane("login")
        assertTrue(model.specs.takePaneRequest("login"))
        assertTrue(!model.specs.takePaneRequest("login"))
    }
}
