package com.hienlh.ppm.app.ui.git

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.files.installBlankFileIcons
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A Branch Review tab the AI's `open_branch_review` opened on a named repository (its `repo`
 * metadata, `branch-review-tab.tsx`): it works in that repository and leaves Source Control's
 * choice alone; picking a repository in the tab is the user's own choice and drops the pin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1024dp-h768dp-xhdpi")
class BranchReviewRepoPinTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()
    private val branchReads = CopyOnWriteArrayList<String>()
    private lateinit var model: AppModel

    @Before
    fun connect() {
        installBlankFileIcons()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/git/repos") -> ok(
                        """{"projectPath":"/w/demo","rootIsRepo":false,"repos":[""" +
                            """{"path":"/w/demo/api","name":"api-svc","relative":"api"},{"path":"/w/demo/web","name":"web-app","relative":"web"}]}""",
                    )
                    path.endsWith("/git/branches") -> {
                        branchReads += request.url.queryParameter("repo").orEmpty()
                        ok("""[{"name":"main","current":true,"remote":false,"commit":"1111111"}]""")
                    }
                    else -> ok("{}")
                }
            }
        }
        server.start()
        model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        model.api = ApiClient(server.url("/"), "tok")
        model.selectedProject = "demo"
    }

    @After
    fun disconnect() = server.close()

    private fun ok(data: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{"ok":true,"data":$data}""").build()

    private fun reviewTab(repo: String?) = WorkspaceTab(
        id = "branch-review:demo",
        type = "branch-review",
        title = "Branch Review",
        projectId = "demo",
        metadata = buildJsonObject {
            put("projectName", JsonPrimitive("demo"))
            if (repo != null) put("repo", JsonPrimitive(repo))
        },
    )

    private fun pinnedReadsItsOwn(mobile: Boolean) {
        model.chooseGitRepo("demo", "/w/demo/api")
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                BranchReviewBody(model = model, tab = reviewTab("/w/demo/web"))
            }
        }
        // The tab reads the repository it was opened on; Source Control's choice stays where it was.
        composeRule.waitUntil(5_000) { branchReads.isNotEmpty() }
        assertEquals("/w/demo/web", branchReads.first())
        assertEquals("/w/demo/api", model.gitChosenRepoPath["demo"])
        composeRule.onNodeWithText("web-app", useUnmergedTree = true).assertExists()
    }

    /**
     * A pin the device cannot use, with no choice of its own yet: the tab asks for a pick, and the
     * pick is the user's — the device's choice moves and the pin is dropped.
     */
    private fun pickDropsThePin(mobile: Boolean) {
        var tab by mutableStateOf(reviewTab("/w/demo/gone"))
        var drops = 0
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                BranchReviewBody(
                    model = model,
                    tab = tab,
                    dropRepoPin = {
                        drops++
                        tab = tab.copy(metadata = JsonObject(tab.metadata - "repo"))
                    },
                )
            }
        }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("web-app", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("web-app", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        assertEquals(1, drops)
        assertNull("the pin is dropped", tab.metaString("repo"))
        assertEquals("/w/demo/web", model.gitChosenRepoPath["demo"])
        composeRule.waitUntil(5_000) { branchReads.contains("/w/demo/web") }
    }

    @Test
    fun `tablet - a pinned Branch Review works in its own repository`() = pinnedReadsItsOwn(mobile = false)

    @Test
    @Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
    fun `phone - a pinned Branch Review works in its own repository`() = pinnedReadsItsOwn(mobile = true)

    @Test
    fun `tablet - a pick in the tab is the user's choice and drops the pin`() = pickDropsThePin(mobile = false)

    @Test
    @Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
    fun `phone - a pick in the tab is the user's choice and drops the pin`() = pickDropsThePin(mobile = true)

    @Test
    fun `a pin no longer among the repositories falls back to the device's choice`() {
        model.chooseGitRepo("demo", "/w/demo/api")
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                BranchReviewBody(model = model, tab = reviewTab("/w/demo/gone"))
            }
        }
        composeRule.waitUntil(5_000) { branchReads.isNotEmpty() }
        assertEquals("/w/demo/api", branchReads.first())
    }
}
