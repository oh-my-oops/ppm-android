package com.hienlh.ppm.app.ui.phone.settings

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Renaming a Claude account from the phone's Accounts page (`account-rename-dialog.tsx`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h844dp-xxhdpi")
class PhoneAccountRenameTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()
    private val patches = mutableListOf<String>()
    @Volatile private var label = "Old name"
    private lateinit var model: AppModel

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val (status, body) = when {
                    request.method == "PATCH" && path == "/api/accounts/acc-1" -> {
                        val sent = request.body?.utf8().orEmpty()
                        synchronized(patches) { patches += sent }
                        if (synchronized(patches) { patches.size } == 1) {
                            400 to """{"ok":false,"error":"The server refused that name"}"""
                        } else {
                            label = "Work"
                            200 to """{"ok":true,"data":{}}"""
                        }
                    }
                    path == "/api/accounts" -> 200 to """{"ok":true,"data":[{"id":"acc-1","label":"$label","email":"me@example.com","status":"active"}]}"""
                    path == "/api/accounts/usage" -> 200 to """{"ok":true,"data":[{"accountId":"acc-1","accountLabel":"$label","accountStatus":"active","isOAuth":true,"usage":{}}]}"""
                    path == "/api/accounts/active" -> 200 to """{"ok":true,"data":{"id":"acc-1"}}"""
                    else -> 200 to """{"ok":true,"data":{}}"""
                }
                return MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build()
            }
        }
        server.start()
        model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        model.api = ApiClient(server.url("/"), "t")
    }

    @After
    fun stop() = server.close()

    private fun shown(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun textShown(text: String) = composeRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a Claude account is renamed from its menu, a refusal keeps the sheet open with the reason`() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) { PhoneAccountsPage(model = model, onBack = {}) }
        }
        composeRule.waitUntil(5_000) { shown("phone-account-more-acc-1") }
        composeRule.onNodeWithTag("phone-account-more-acc-1").performClick()
        composeRule.onNodeWithTag("phone-account-rename-acc-1", useUnmergedTree = true).performClick()
        composeRule.waitUntil(2_000) { shown("phone-account-rename-input") }
        assertTrue("the sheet starts on the current name", textShown("Old name"))

        composeRule.onNodeWithTag("phone-account-rename-input").performTextReplacement("  Work  ")
        composeRule.onNodeWithTag("phone-account-rename-save", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { shown("phone-account-rename-error") }
        assertTrue(textShown("The server refused that name"))
        assertTrue("a refused name keeps the sheet open", shown("phone-account-rename-input"))

        composeRule.onNodeWithTag("phone-account-rename-save", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { !shown("phone-account-rename-input") && textShown("Account renamed to \"Work\".") }
        assertEquals(listOf("""{"label":"Work"}""", """{"label":"Work"}"""), synchronized(patches) { patches.toList() })
        composeRule.waitUntil(5_000) { textShown("Work") && !textShown("Old name") }
    }

    @Test
    fun `an account that cannot be renamed offers no Rename`() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneAccountsContent(
                    activeProvider = "codex",
                    onSelectProvider = {},
                    cards = listOf(PhoneAccountCardData(id = "cx", provider = "codex", label = "Codex", subtitle = "", status = "active")),
                    onToggleAccount = { _, _ -> },
                    onDeleteAccount = {},
                    onRenameAccount = { _, _ -> },
                )
            }
        }
        composeRule.onNodeWithTag("phone-account-more-cx").performClick()
        composeRule.waitUntil(2_000) { shown("phone-account-remove-cx") }
        assertFalse(shown("phone-account-rename-cx"))
    }
}
