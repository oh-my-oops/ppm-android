package com.hienlh.ppm.app.ui.editor

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.GitRepo
import com.hienlh.ppm.client.GitRepos
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * The editor's change bars against the file in git's index (`use-gutter-changes.ts`): read on
 * open, drawn beside the line numbers, and asked again every few seconds naming the blob held.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class EditorGutterChangesTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private fun json(data: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{"ok":true,"data":$data}""").build()

    private fun serve(indexBase: (RecordedRequest) -> String): Pair<MockWebServer, MutableList<String>> {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request.target
                return if (request.target.contains("/git/index-base")) json(indexBase(request)) else json("{}")
            }
        }
        server.start()
        return server to seen
    }

    /** [switchedOn]: Settings → Appearance's "Changed Lines in the Gutter", off until the tour asks. */
    private fun model(server: MockWebServer, repos: GitRepos?, switchedOn: Boolean = true): AppModel =
        AppModel(ApplicationProvider.getApplicationContext<Application>()).apply {
            api = ApiClient(server.url("/"), "secret")
            if (repos != null) gitDiscovery["app"] = repos
            if (switchedOn) setGutterChangesPref(true)
        }

    private val rootRepo = GitRepos(rootIsRepo = true, repos = listOf(GitRepo("/w/app", "app", ".")), root = "/w/app")

    @Test
    fun `marks modified, added and deleted lines on a phone, then re-asks naming the blob it holds`() {
        val (server, seen) = serve { request ->
            if (request.target.contains("known=")) """{"state":"same","oid":"b1"}"""
            else """{"state":"ok","oid":"b1","content":"a\nb\nc\nd\ne\nf\n"}"""
        }
        try {
            val model = model(server, rootRepo)
            composeRule.setContent {
                var wrap by remember { mutableStateOf(true) }
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                    EditableFileCodeView(
                        model = model,
                        project = "app",
                        path = "src/notes.txt",
                        initialContent = "a\nB\nc\nnew\nd\nf\n",
                        initialLanguage = "plaintext",
                        wrap = wrap,
                        onToggleWrap = { wrap = !wrap },
                    )
                }
            }
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("editor-gutter-modified-2", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onAllNodesWithTag("editor-gutter-added-4", useUnmergedTree = true).assertCountEquals(1)
            // `e` was deleted after `d` (line 5).
            composeRule.onAllNodesWithTag("editor-gutter-deleted-5", useUnmergedTree = true).assertCountEquals(1)
            assertEquals("/api/project/app/git/index-base?path=src%2Fnotes.txt", seen.first { it.contains("index-base") })

            composeRule.mainClock.advanceTimeBy(GUTTER_POLL_MS + 100)
            composeRule.waitUntil(5_000) { seen.any { it.contains("index-base") && it.contains("known=b1") } }
            composeRule.waitForIdle()
            // `same` keeps the bars that were drawn.
            composeRule.onAllNodesWithTag("editor-gutter-modified-2", useUnmergedTree = true).assertCountEquals(1)
        } finally {
            server.close()
        }
    }

    @Test
    fun `an untracked file gets no bars, and nothing is asked before the file's repository is known`() {
        val (server, seen) = serve { """{"state":"none","reason":"untracked"}""" }
        try {
            val model = model(server, repos = null)
            composeRule.setContent {
                var wrap by remember { mutableStateOf(true) }
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                    EditableFileCodeView(
                        model = model,
                        project = "app",
                        path = "new.txt",
                        initialContent = "x\ny\n",
                        initialLanguage = "plaintext",
                        wrap = wrap,
                        onToggleWrap = { wrap = !wrap },
                    )
                }
            }
            composeRule.waitForIdle()
            assertTrue("asked before the repository was known: $seen", seen.none { it.contains("index-base") })

            composeRule.runOnIdle { model.gitDiscovery["app"] = rootRepo }
            composeRule.waitForIdle()
            val ok = runCatching { composeRule.waitUntil(5_000) { seen.any { it.contains("index-base") } } }.isSuccess
            assertTrue("never asked once the repository was known: $seen", ok)
            composeRule.waitForIdle()
            composeRule.onAllNodesWithTag("editor-gutter-added-1", useUnmergedTree = true).assertCountEquals(0)
        } finally {
            server.close()
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1024dp-h768dp-xhdpi")
    fun `switched off a tablet editor asks git nothing, switched on it draws the bars`() {
        val (server, seen) = serve { """{"state":"ok","oid":"b1","content":"a\nb\n"}""" }
        try {
            val model = model(server, rootRepo, switchedOn = false)
            assertFalse("off until the tour's question is answered", model.gutterChanges)
            composeRule.setContent {
                var wrap by remember { mutableStateOf(true) }
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                    EditableFileCodeView(
                        model = model,
                        project = "app",
                        path = "notes.txt",
                        initialContent = "a\nB\n",
                        initialLanguage = "plaintext",
                        wrap = wrap,
                        onToggleWrap = { wrap = !wrap },
                    )
                }
            }
            composeRule.waitForIdle()
            composeRule.mainClock.advanceTimeBy(GUTTER_POLL_MS + 100)
            composeRule.waitForIdle()
            assertTrue("asked git while switched off: $seen", seen.none { it.contains("index-base") })
            composeRule.onAllNodesWithTag("editor-gutter-modified-2", useUnmergedTree = true).assertCountEquals(0)

            composeRule.runOnIdle { model.setGutterChangesPref(true) }
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("editor-gutter-modified-2", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(seen.any { it.contains("index-base") })
        } finally {
            server.close()
        }
    }
}
