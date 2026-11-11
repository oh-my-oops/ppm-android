package com.hienlh.ppm.app.ui.phone.settings

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Max Turns and the leftover-process minutes on the phone's AI Provider page (`ai-settings-section.tsx`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h1400dp-xxhdpi")
class PhoneAiLimitsTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()
    private val puts = mutableListOf<String>()
    @Volatile private var maxTurns = 100
    @Volatile private var leftover: Int? = null
    private lateinit var model: AppModel

    private fun settingsJson(): String {
        val leftoverField = leftover?.let { ""","leftover_processes_idle_minutes":$it""" }.orEmpty()
        return """{"ok":true,"data":{"default_provider":"claude","providers":{"claude":{"type":"agent-sdk","max_turns":$maxTurns},"codex":{"type":"codex-cli"}}$leftoverField}}"""
    }

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val body = when {
                    path == "/api/settings/ai" && request.method == "PUT" -> {
                        val sent = request.body?.utf8().orEmpty()
                        synchronized(puts) { puts += sent }
                        Regex(""""max_turns":(\d+)""").find(sent)?.let { maxTurns = it.groupValues[1].toInt() }
                        Regex(""""leftover_processes_idle_minutes":(\d+)""").find(sent)?.let { leftover = it.groupValues[1].toInt() }
                        settingsJson()
                    }
                    path == "/api/settings/ai" -> settingsJson()
                    path.endsWith("/models") || path.endsWith("/providers/status") -> """{"ok":true,"data":[]}"""
                    else -> """{"ok":true,"data":{}}"""
                }
                return MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
            }
        }
        server.start()
        model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        model.api = ApiClient(server.url("/"), "t")
    }

    @After
    fun stop() = server.close()

    private fun shown(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun fieldText(tag: String) =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text
    private fun sent() = synchronized(puts) { puts.toList() }

    private fun enter(tag: String, text: String) {
        composeRule.onNodeWithTag(tag).performTextReplacement(text)
        composeRule.onNodeWithTag(tag).performImeAction()
        composeRule.waitForIdle()
    }

    @Test
    fun `typed limits are brought into range and saved, an empty field shows the saved value again`() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) { PhoneAiProviderPage(model = model, onBack = {}) }
        }
        composeRule.waitUntil(5_000) { shown("phone-ai-max-turns") && shown("phone-ai-leftover-idle") }
        assertEquals("100", fieldText("phone-ai-max-turns"))
        assertEquals("a server that never set it reads as 0, log only", "0", fieldText("phone-ai-leftover-idle"))
        assertFalse(shown("phone-ai-max-turns-warning"))

        enter("phone-ai-max-turns", "5000")
        composeRule.waitUntil(5_000) { sent().size == 1 }
        assertEquals("""{"providers":{"claude":{"max_turns":1000}}}""", sent().single())
        assertEquals("1000", fieldText("phone-ai-max-turns"))
        composeRule.waitUntil(2_000) { shown("phone-ai-max-turns-warning") }

        enter("phone-ai-max-turns", "")
        assertEquals("1000", fieldText("phone-ai-max-turns"))
        enter("phone-ai-max-turns", "1000")
        assertEquals("nothing new is saved", 1, sent().size)

        enter("phone-ai-leftover-idle", "2000")
        composeRule.waitUntil(5_000) { sent().size == 2 }
        assertEquals("""{"leftover_processes_idle_minutes":1440}""", sent()[1])
        assertEquals("1440", fieldText("phone-ai-leftover-idle"))

        // Max Turns belongs to the SDK providers; the leftover minutes to every provider.
        composeRule.onNodeWithTag("phone-ai-provider-tab-codex").performClick()
        composeRule.waitForIdle()
        assertFalse(shown("phone-ai-max-turns"))
        assertTrue(shown("phone-ai-leftover-idle"))
    }

    @Test
    fun `numbers are read as the web's number field reads them`() {
        assertEquals(1000, phoneClampedNumber("5000", 0, 1000))
        assertEquals(0, phoneClampedNumber("-3", 0, 1000))
        assertEquals(42, phoneClampedNumber("42", 0, 1000))
        assertNull(phoneClampedNumber("", 0, 1000))
        assertNull(phoneClampedNumber("-", 0, 1000))
        assertEquals(0, phoneLeftoverIdleMinutes(null))
        assertEquals(0, phoneLeftoverIdleMinutes(5000))
        assertEquals(30, phoneLeftoverIdleMinutes(30))
        assertTrue(phoneMaxTurnsCostWarning(0) && phoneMaxTurnsCostWarning(501) && !phoneMaxTurnsCostWarning(500))
    }
}
