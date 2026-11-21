package com.hienlh.ppm.app.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Settings → AI Provider's "Always allowed in a project": listed, each removable, hidden with none. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SavedApprovalsUiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()

    @After
    fun tearDown() = server.close()

    private fun ok(data: String) = MockResponse.Builder().body("""{"ok":true,"data":$data}""").build()

    private val two = """[
        {"projectPath":"/p/a","key":"bash:git status","label":"`git status` commands","savedAt":"2026-11-20T09:00:00.000Z"},
        {"projectPath":"/p/b","key":"bash-exact:ls | wc -l","label":"this exact command","savedAt":"2026-11-20T09:01:00.000Z"}
    ]"""

    private fun show(mobile: Boolean) {
        server.start()
        val api = ApiClient(server.url("/"), "tok")
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) { SavedApprovals(api) }
        }
    }

    private fun listsAndRemoves(mobile: Boolean) {
        server.enqueue(ok(two))
        server.enqueue(ok("""{"removed":true}"""))
        show(mobile)
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Always allowed in a project (2)").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("`git status` commands").assertExists()
        composeRule.onNodeWithText("/p/a").assertExists()
        // A rule for one exact command shows the command.
        composeRule.onNodeWithText("ls | wc -l").assertExists()
        composeRule.onNodeWithContentDescription("Remove `git status` commands").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Always allowed in a project (1)").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("/api/settings/approval-rules", server.takeRequest(1, TimeUnit.SECONDS)!!.target)
        val delete = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("DELETE", delete.method)
        assertEquals("""{"projectPath":"/p/a","key":"bash:git status"}""", delete.body!!.utf8())
    }

    @Test fun `a phone lists the saved rules and removes one`() = listsAndRemoves(mobile = true)

    @Test fun `a tablet lists the saved rules and removes one`() = listsAndRemoves(mobile = false)

    @Test
    fun `a server without the route shows nothing`() {
        server.enqueue(MockResponse.Builder().code(404).body("""{"ok":false,"error":"Not found"}""").build())
        show(mobile = true)
        composeRule.waitUntil(5_000) { server.requestCount == 1 }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Always allowed in a project", substring = true).assertCountEquals(0)
    }
}
