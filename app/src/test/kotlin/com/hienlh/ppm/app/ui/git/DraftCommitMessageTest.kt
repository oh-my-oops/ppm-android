package com.hienlh.ppm.app.ui.git

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.LocalRedesign
import com.hienlh.ppm.app.ui.files.installBlankFileIcons
import com.hienlh.ppm.app.ui.phone.PhoneArea
import com.hienlh.ppm.app.ui.phone.PhoneNav
import com.hienlh.ppm.app.ui.phone.PhoneSourceControlAreaHost
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.GitRepo
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.git.ChangeTotals
import com.hienlh.ppm.state.git.WriteWithAi
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Source Control's "Draft message with AI" (`git-commit-composer.tsx`, `POST /git/commit-message`):
 * asked for the repository in use, the answer put in that repository's shared draft, the box left
 * alone when it changed meanwhile, and a failure said with the server's own words — on the tablet's
 * panel and the phone's Source Control.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1024dp-h768dp-xhdpi")
class DraftCommitMessageTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()
    private val asked = CopyOnWriteArrayList<String>()
    private lateinit var model: AppModel

    /** Released to let `/git/commit-message` answer; open unless a test holds it. */
    @Volatile private var hold = CountDownLatch(0)
    @Volatile private var answer: MockResponse = ok("""{"message":"feat(api): split the router"}""")

    private val api = GitRepo("/w/demo/api", "api", "api")

    @Before
    fun connect() {
        installBlankFileIcons()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/git/repos") -> ok(
                        """{"projectPath":"/w/demo","rootIsRepo":false,"repos":[""" +
                            """{"path":"/w/demo/api","name":"api","relative":"api"},{"path":"/w/demo/web","name":"web","relative":"web"}]}""",
                    )
                    path.endsWith("/git/changes") -> ok(
                        """{"branch":{"head":"main","oid":"1111111","upstream":null,"upstreamGone":false,"hasRemote":false,"ahead":0,"behind":0},""" +
                            """"operation":null,"files":[{"path":"src/app.ts","x":"M","y":".","untracked":false,"conflict":false,""" +
                            """"staged":{"added":3,"removed":1,"blocks":[]}}],"truncated":false,"stashes":0,"lastCommit":null}""",
                    )
                    path.endsWith("/git/commit-draft") -> ok("""{"message":"","updatedAt":0}""")
                    path.endsWith("/git/commit-message") -> {
                        asked += request.target
                        hold.await(10, TimeUnit.SECONDS)
                        answer
                    }
                    else -> ok("{}")
                }
            }
        }
        server.start()
        model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        model.api = ApiClient(server.url("/"), "tok")
        model.selectedProject = "demo"
        model.chooseGitRepo("demo", api.path)
    }

    @After
    fun disconnect() = server.close()

    private fun ok(data: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{"ok":true,"data":$data}""").build()

    private fun failure(code: Int, message: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body("""{"ok":false,"error":"$message"}""").build()

    private fun shown(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun toast(title: String) = model.toasts.firstOrNull { it.message.title == title }

    private fun draft() = model.commitDrafts.message("demo", api)

    private fun tablet() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalRedesign provides true) { GitStatusPanel(model = model) }
            }
        }
        composeRule.waitUntil(5_000) { shown("git-ai-msg-btn") }
    }

    private fun phone() {
        val nav = PhoneNav(initialTabId = PhoneNav.areaTabId(PhoneArea.SOURCE_CONTROL))
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneSourceControlAreaHost(model = model, tab = null, nav = nav)
            }
        }
        composeRule.waitUntil(5_000) { shown("phone-git-ai-chip") }
        composeRule.waitUntil(5_000) { composeRule.onNodeWithTag("phone-git-ai-chip").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() }
    }

    @Test
    fun `tablet - drafts for the repository in use and puts the message in its draft`() {
        tablet()
        composeRule.onNodeWithTag("git-ai-msg-btn", useUnmergedTree = true).assertContentDescriptionEquals("Draft message with AI")
        composeRule.onNodeWithTag("git-ai-msg-btn", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { draft() == "feat(api): split the router" }
        assertEquals("/api/project/demo/git/commit-message?repo=%2Fw%2Fdemo%2Fapi", asked.single())
    }

    @Test
    fun `tablet - the box edited while the AI wrote keeps what was typed`() {
        hold = CountDownLatch(1)
        tablet()
        composeRule.onNodeWithTag("git-ai-msg-btn", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { asked.isNotEmpty() }
        composeRule.runOnIdle { model.commitDrafts.setMessage(null, "demo", api, "fix: typed on another device") }
        hold.countDown()
        composeRule.waitUntil(5_000) { toast(WriteWithAi.DRAFT_NOT_USED_TITLE) != null }
        assertEquals("The box was edited while it was being written.", toast(WriteWithAi.DRAFT_NOT_USED_TITLE)!!.message.description)
        assertEquals("fix: typed on another device", draft())
    }

    @Test
    fun `tablet - a failure is a toast with the server's words, and the box is untouched`() {
        val words = "Gemini cannot draft a commit message. Make Claude or Codex the default provider in Settings."
        answer = failure(409, words)
        tablet()
        composeRule.onNodeWithTag("git-ai-msg-btn", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { toast("Could not draft a commit message") != null }
        assertEquals(words, toast("Could not draft a commit message")!!.message.description)
        assertEquals("", draft())
        assertTrue("no banner: the web says it in a toast", !shown("git-ai-error-banner"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
    fun `phone - drafts for the repository in use and puts the message in its draft`() {
        phone()
        composeRule.onNodeWithTag("phone-git-ai-chip").performClick()
        composeRule.waitUntil(5_000) { draft() == "feat(api): split the router" }
        assertEquals("/api/project/demo/git/commit-message?repo=%2Fw%2Fdemo%2Fapi", asked.single())
    }

    @Test
    @Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
    fun `phone - the box edited while the AI wrote keeps what was typed`() {
        hold = CountDownLatch(1)
        phone()
        composeRule.onNodeWithTag("phone-git-ai-chip").performClick()
        composeRule.waitUntil(5_000) { asked.isNotEmpty() }
        composeRule.runOnIdle { model.commitDrafts.setMessage(null, "demo", api, "fix: typed on another device") }
        hold.countDown()
        composeRule.waitUntil(5_000) { toast(WriteWithAi.DRAFT_NOT_USED_TITLE) != null }
        assertEquals("fix: typed on another device", draft())
    }

    @Test
    @Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
    fun `phone - a default provider that cannot draft says so, not Stage changes first`() {
        val words = "Gemini cannot draft a commit message. Make Claude or Codex the default provider in Settings."
        answer = failure(409, words)
        phone()
        composeRule.onNodeWithTag("phone-git-ai-chip").performClick()
        composeRule.waitUntil(5_000) { shown("phone-git-ai-error") }
        composeRule.onNodeWithText(words).assertIsDisplayed()
        assertEquals("", draft())
    }

    @Test
    fun `tablet - the button is off until something is staged, whatever else changed`() {
        var clicks = 0
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                GitCommitComposer(
                    message = "",
                    onMessageChange = {},
                    totals = ChangeTotals(files = 2, filesStaged = 0, blocks = 2, blocksStaged = 0, conflicts = 0),
                    operation = null,
                    lastCommit = null,
                    syncMode = null,
                    busyAction = null,
                    onCommit = { _, _, _ -> },
                    onUndoLast = {},
                    onWriteWithAi = { clicks++ },
                )
            }
        }
        composeRule.onNodeWithTag("git-ai-msg-btn", useUnmergedTree = true).assertIsNotEnabled().performClick()
        assertEquals(0, clicks)
    }
}
