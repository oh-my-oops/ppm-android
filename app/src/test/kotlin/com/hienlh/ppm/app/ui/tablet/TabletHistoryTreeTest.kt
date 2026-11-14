package com.hienlh.ppm.app.ui.tablet

import android.app.Application
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.specs
import com.hienlh.ppm.app.ui.SessionHistoryPanel
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.state.ChatSearch
import com.hienlh.ppm.state.HistoryList
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * The tablet sidebar's Chat History as the web's desktop sidebar draws it: chats under the chat
 * that opened them behind "N sub-chats", a spec chat's progress and task chats, and the list/tree
 * switch in the header.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp")
class TabletHistoryTreeTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()

    @After
    fun stop() {
        server.close()
    }

    private fun s(id: String, title: String, spawnedBy: String? = null, parentTitle: String? = null, minute: Int = 0) =
        SessionInfo(
            id = id,
            providerId = "claude",
            title = title,
            updatedAt = Instant.parse("2026-11-11T07:00:00Z").plusSeconds(minute * 60L),
            spawnedBy = spawnedBy,
            parentTitle = parentTitle,
        )

    /** The specs route answers [specsJson]; everything else (the view-mode pref included) answers nothing. */
    private fun render(sessions: List<SessionInfo>, specsJson: String = "[]", mode: String? = null): AppModel {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val data = if (request.url.encodedPath == "/api/project/ppm/specs") specsJson else "{}"
                return MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{"ok":true,"data":$data}""").build()
            }
        }
        server.start()
        val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        val api = ApiClient(server.url("/"), "secret")
        model.api = api
        model.selectedProject = "ppm"
        model.sessions = sessions
        if (mode != null) model.specs.historyViewMode = mode
        val list = HistoryList(api, "ppm", model.scope)
        val search = ChatSearch(api, "ppm", model.scope)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                SessionHistoryPanel(model, list, search, onNavigate = {})
            }
        }
        composeRule.waitForIdle()
        return model
    }

    private val family = listOf(
        s("c1", "Child one", spawnedBy = "p", minute = 3),
        s("c2", "Child two", spawnedBy = "p", minute = 2),
        s("p", "Parent chat", minute = 1),
        s("solo", "Solo chat", minute = 0),
    )

    @Test
    fun `tree mode folds sub-chats under their creator and opens them indented`() {
        render(family)

        composeRule.onNodeWithContentDescription("Show as a list").assertExists()
        composeRule.onNodeWithText("2 sub-chats", useUnmergedTree = true).assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("Child one", useUnmergedTree = true).fetchSemanticsNodes().size)

        composeRule.onNodeWithTag("history-family-toggle:p").performClick()
        composeRule.waitForIdle()

        val parent = composeRule.onNodeWithText("Parent chat", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val child = composeRule.onNodeWithText("Child one", useUnmergedTree = true).getUnclippedBoundsInRoot()
        composeRule.onNodeWithText("Child two", useUnmergedTree = true).assertExists()
        // `indent(depth)`: 8 + 16 per level, against the top row's `px-2`.
        assertEquals(16f, (child.left - parent.left).value, 0.5f)
        assertEquals(0, composeRule.onAllNodesWithText("Today", useUnmergedTree = true).fetchSemanticsNodes().size)

        composeRule.onNodeWithTag("history-family-toggle:p").performClick()
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("Child one", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `flat mode lists every chat with no toggle, and the header switch turns the tree on`() {
        render(family, mode = "flat")

        composeRule.onNodeWithTag("history-view-toggle").assertContentDescriptionEquals("Show as a tree")
        assertEquals(0, composeRule.onAllNodesWithTag("history-family-toggle:p").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Child one", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Parent chat", useUnmergedTree = true).assertExists()

        composeRule.onNodeWithTag("history-view-toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("history-family-toggle:p").assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("Child one", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `a spec chat shows its progress and opens its task chats, and a missing creator gets a stand-in`() {
        val sessions = listOf(
            s("t1", "Task 1 · Model", spawnedBy = "spec", minute = 5),
            s("t2", "Task 2 · Screen", spawnedBy = "spec", minute = 4),
            s("spec", "Login spec", minute = 3),
            s("x1", "Research", spawnedBy = "gone", parentTitle = "Planner", minute = 2),
        )
        val specs = """[{"slug":"login","title":"Login","current":"execution","tasksDone":1,"tasksTotal":2,"sessionId":"spec",
            "tasks":[{"id":"1","status":"done","sessionId":"t1"},{"id":"2","status":"running","sessionId":"t2"}]}]"""
        render(sessions, specs)

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("history-spec-progress:login", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("1/2 done", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("· 1 running", useUnmergedTree = true).assertExists()
        // The web's spec family has a chevron on the row, not a "N task chats" line.
        assertEquals(0, composeRule.onAllNodesWithText("2 task chats", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithTag("history-task-chat:t1").fetchSemanticsNodes().size)

        composeRule.onNodeWithContentDescription("Show 2 task chats").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("history-task-chat:t1").assertExists()
        composeRule.onNodeWithTag("history-task-chat:t2").assertExists()
        composeRule.onNodeWithContentDescription("Running", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Done", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Hide task chats").assertExists()

        // "Research"'s creator is not in the list: a row stands in for it, and Research sits under it.
        composeRule.onNodeWithTag("history-stand-in:gone").assertExists()
        composeRule.onNodeWithText("Planner", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("1 sub-chat", useUnmergedTree = true).assertExists()
    }
}
