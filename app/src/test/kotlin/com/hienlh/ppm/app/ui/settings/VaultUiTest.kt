package com.hienlh.ppm.app.ui.settings

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.phone.settings.PhoneHostedSettingsPage
import com.hienlh.ppm.app.ui.settings.vault.VaultSettingsSection
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.chat.LocalPpmApprovals
import com.hienlh.ppm.chat.PendingApprovalCard
import com.hienlh.ppm.chat.PpmApprovals
import com.hienlh.ppm.chat.ToolCardView
import com.hienlh.ppm.chat.VaultShownValueHost
import com.hienlh.ppm.chat.VaultShownValues
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ApprovalRequest
import com.hienlh.ppm.protocol.SessionCollabAnswer
import com.hienlh.ppm.protocol.ToolOutcome
import com.hienlh.ppm.protocol.VaultCardResult
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.ToolBlock
import kotlinx.serialization.json.JsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings → Vault and the vault's chat cards, on a phone and on a tablet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class VaultUiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @After
    fun clearShown() = VaultShownValues.entries.clear()

    private class RecordingApprovals(private val result: VaultCardResult = VaultCardResult(true, null, null), private val fail: String? = null) : PpmApprovals {
        val vault = mutableListOf<Pair<String, JsonObject>>()

        override suspend fun answerCollab(requestId: String, answer: SessionCollabAnswer) = Unit

        override suspend fun answerDb(requestId: String, approved: Boolean, password: String?) = Unit

        override suspend fun answerVault(requestId: String, answer: JsonObject): VaultCardResult {
            vault += requestId to answer
            if (fail != null) throw PpmError.Server(403, fail)
            return result
        }

        override suspend fun vaultPrefill(requestId: String): String? = "prefilled-value"
    }

    private fun content(mobile: Boolean, approvals: PpmApprovals?, body: @Composable () -> Unit) {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                CompositionLocalProvider(LocalPpmApprovals provides approvals) {
                    body()
                    VaultShownValueHost()
                }
            }
        }
    }

    private fun card(id: String, input: String) = ApprovalRequest(id, "ppm:vault", WireJson.parse(input))

    private val secureCard = card(
        "v1",
        """{"kind":"read-secure","requester":{"sessionTitle":"Taxes"},"purpose":"Fill the tax form","items":[{"id":"a","name":"NI","kind":"ni_number"},{"id":"b","name":"Home","kind":"address"}],"requested":["a"],"attemptsLeft":5,"lockedUntil":0}""",
    )

    @Test
    fun `a secure card sends the ticked items and the password over HTTP, and a wrong one stays on the card (phone)`() {
        val approvals = RecordingApprovals(fail = "Wrong password.")
        val socket = mutableListOf<Boolean>()
        content(mobile = true, approvals = approvals) { PendingApprovalCard(secureCard, { approved, _ -> socket += approved }) }
        composeRule.onNodeWithText("Unlock vault items for the AI").assertExists()
        composeRule.onNodeWithText("Fill the tax form").assertExists()
        composeRule.onNodeWithContentDescription("Approve").assertIsNotEnabled()
        composeRule.onNodeWithTag("vault-item-b").performClick()
        composeRule.onNodeWithText("Open 10 min for this chat").performClick()
        composeRule.onNodeWithText("For 10 minutes this chat's AI reads the ticked items again without asking.").assertExists()
        composeRule.onNodeWithContentDescription("Secure tier password").performTextInput("correct horse")
        composeRule.onNodeWithText("Approve").performClick()
        composeRule.waitForIdle()
        val (id, answer) = approvals.vault.single()
        assertEquals("v1", id)
        assertEquals(WireJson.parse("""{"approved":true,"password":"correct horse","itemIds":["a","b"],"mode":"window","minutes":10}"""), answer)
        composeRule.onNodeWithText("Wrong password.").assertExists()
        assertEquals(emptyList<Boolean>(), socket)
    }

    @Test
    fun `a sealed card shows the value to the user only, for a minute, after the card has gone (tablet)`() {
        val approvals = RecordingApprovals(VaultCardResult(true, "Passport", "P-123-456"))
        content(mobile = false, approvals = approvals) {
            PendingApprovalCard(
                card("v2", """{"kind":"read-sealed","requester":{"assistant":true,"fromTelegram":true},"purpose":"Book a flight","item":{"id":"p","name":"Passport","kind":"passport"},"allowGiveToAi":false}"""),
                { _, _ -> },
            )
        }
        composeRule.onNodeWithText("Sealed vault item").assertExists()
        composeRule.onNodeWithText("This turn came from Telegram, so the value can only be shown to you.").assertExists()
        composeRule.onNodeWithContentDescription("Sealed tier password").performTextInput("sealed password!")
        composeRule.onNodeWithText("Show to me").performClick()
        composeRule.waitForIdle()
        assertEquals(WireJson.parse("""{"approved":true,"password":"sealed password!","giveToAi":false}"""), approvals.vault.single().second)
        composeRule.onNodeWithTag("vault-shown").assertExists()
        composeRule.onNodeWithText("P-123-456").assertExists()
        composeRule.onNodeWithText("Shown to you only. The AI did not get it.").assertExists()
        composeRule.onNodeWithText("Hide").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("vault-shown").assertDoesNotExist()
    }

    @Test
    fun `a proposal leaves the AI's prefilled value unsent and warns on the public tier`() {
        val approvals = RecordingApprovals()
        content(mobile = true, approvals = approvals) {
            PendingApprovalCard(
                card("v3", """{"kind":"propose","requester":{},"name":"Bank PIN","itemKind":"other","suggestedTier":"sealed","hasPrefill":true,"enabled":{"secure":false,"sealed":false}}"""),
                { _, _ -> },
            )
        }
        composeRule.onNodeWithText("Save to the vault").assertExists()
        // Sealed is off, so the card starts on Public, with both warnings.
        composeRule.onNodeWithTag("vault-public-warning").assertExists()
        composeRule.onNodeWithTag("vault-secret-name-warning").assertExists()
        composeRule.onNodeWithText("This value is already in the chat history.").assertExists()
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitForIdle()
        assertEquals(WireJson.parse("""{"approved":true,"tier":"public","name":"Bank PIN","itemKind":"other"}"""), approvals.vault.single().second)
    }

    @Test
    fun `a locked read is drawn as a count and its values are never on screen`() {
        val tool = ToolBlock(
            toolUseId = "t1",
            name = "mcp__ppm-sessions__vault_read",
            input = WireJson.parse("""{"items":["NI"],"purpose":"Fill the form"}"""),
            result = ToolOutcome("Vault items (secure tier):\n- NI: AB123456C", isError = false),
        )
        content(mobile = false, approvals = null) { ToolCardView(tool, completed = true) }
        composeRule.onNodeWithText("Read 1 vault item").performClick()
        composeRule.onNodeWithText("The values went to the AI and are not shown here.").assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("AB123456C", substring = true).fetchSemanticsNodes().size)
    }

    private fun vaultServer(requests: MutableList<String>) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.url.encodedPath}"
                val body = when (request.url.encodedPath) {
                    "/api/vault/status" -> """{"publicCount":1,"tiers":{"secure":{"enabled":true,"itemCount":1,"lockedUntil":0,"attemptsLeft":5,"settingsOpenUntil":0},"sealed":{"enabled":false,"itemCount":0,"lockedUntil":0,"attemptsLeft":5,"settingsOpenUntil":0}}}"""
                    "/api/vault/items" -> """[{"id":"pub1","tier":"public","name":"Home","kind":"address","createdAt":1,"updatedAt":1700000000000},{"id":"sec1","tier":"secure","name":"NI","kind":"ni_number","createdAt":1,"updatedAt":1700000000000}]"""
                    "/api/vault/items/pub1/reveal" -> """{"value":"1 Road"}"""
                    "/api/vault/items/sec1/reveal" -> """{"value":"AB123456C"}"""
                    else -> "{}"
                }
                return MockResponse.Builder().body("""{"ok":true,"data":$body}""").build()
            }
        }
        start()
    }

    private fun settingsTest(mobile: Boolean, check: (MutableList<String>) -> Unit) {
        val requests = mutableListOf<String>()
        val server = vaultServer(requests)
        try {
            val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
            model.api = ApiClient(server.url("/"), "tok")
            composeRule.setContent {
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                    if (mobile) PhoneHostedSettingsPage("Vault", "phone-settings-vault-page", {}) { VaultSettingsSection(model) }
                    else VaultSettingsSection(model)
                }
            }
            composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("1 Road").fetchSemanticsNodes().isNotEmpty() }
            check(requests)
        } finally {
            server.close()
        }
    }

    @Test
    fun `the tablet pane lists public values, keeps secure ones hidden, and Show asks for the password`() = settingsTest(mobile = false) { requests ->
        composeRule.onNodeWithTag("vault-tier-public").assertExists()
        composeRule.onNodeWithText("Unlock for 5 minutes").assertExists()
        // Sealed is off: what it is for, and Turn on.
        composeRule.onNodeWithText("Turn on").assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("AB123456C").fetchSemanticsNodes().size)
        assertTrue("GET /api/vault/items/sec1/reveal" !in requests && "POST /api/vault/items/sec1/reveal" !in requests)
        composeRule.onNodeWithTag("vault-item-sec1").assertExists()
        composeRule.onNodeWithContentDescription("Show").performClick()
        composeRule.onNodeWithText("Show “NI”").assertExists()
        composeRule.onNodeWithContentDescription("Secure tier password").performTextInput("secure password")
        composeRule.onNodeWithText("Show").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("AB123456C").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("POST /api/vault/items/sec1/reveal" in requests)
    }

    @Test
    fun `the phone page hosts the same pane under its header`() = settingsTest(mobile = true) {
        composeRule.onNodeWithTag("phone-settings-vault-page").assertExists()
        composeRule.onNodeWithTag("vault-tier-secure").assertExists()
        composeRule.onNodeWithText("Turn on").performClick()
        composeRule.onNodeWithText("Turn on the Sealed tier").assertExists()
        composeRule.onNodeWithTag("vault-forget-warning", useUnmergedTree = true).assertExists()
    }
}
