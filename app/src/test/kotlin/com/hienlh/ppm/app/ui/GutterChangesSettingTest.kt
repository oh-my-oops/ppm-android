package com.hienlh.ppm.app.ui

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.Collections

/**
 * Settings → Appearance's "Changed Lines in the Gutter" (`appearance-settings-section.tsx`): the
 * `gutterChanges` UI pref, off until the tour's question is answered, saved through
 * `PUT /api/settings/ui-prefs` — which the server takes as the answer to that question.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class GutterChangesSettingTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private fun serve(uiPrefs: String = "{}"): Pair<MockWebServer, MutableList<String>> {
        val puts = Collections.synchronizedList(mutableListOf<String>())
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val data = when {
                    request.target.endsWith("/api/settings/ui-prefs") && request.method == "PUT" -> {
                        puts += request.body?.utf8().orEmpty()
                        "{}"
                    }
                    request.target.endsWith("/api/settings/ui-prefs") -> uiPrefs
                    request.target.contains("/themes") -> "[]"
                    else -> "{}"
                }
                return MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{"ok":true,"data":$data}""").build()
            }
        }
        server.start()
        return server to puts
    }

    private fun model(server: MockWebServer) = AppModel(ApplicationProvider.getApplicationContext<Application>()).apply {
        api = ApiClient(server.url("/"), "secret")
    }

    private fun switchesOnAndSaves(mobile: Boolean) {
        val (server, puts) = serve()
        try {
            val model = model(server)
            assertFalse("off until the tour's question is answered", model.gutterChanges)
            composeRule.setContent {
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { AppearanceExtrasSection(model) }
                }
            }
            composeRule.onNodeWithTag("settings-gutter-changes").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("Changed Lines in the Gutter").assertIsDisplayed()
            composeRule.onNodeWithText("Mark lines that differ from what git has staged. Each open editor checks git every few seconds.")
                .assertIsDisplayed()

            composeRule.onNodeWithTag("settings-gutter-changes").performClick()
            composeRule.waitForIdle()
            assertTrue(model.gutterChanges)
            composeRule.waitUntil(5_000) { puts.any { it.contains("\"gutterChanges\":true") } }

            composeRule.onNodeWithTag("settings-gutter-changes").performClick()
            composeRule.waitForIdle()
            assertFalse(model.gutterChanges)
            composeRule.waitUntil(5_000) { puts.any { it.contains("\"gutterChanges\":false") } }
        } finally {
            server.close()
        }
    }

    @Test
    fun `the switch on a phone turns the bars on and off for every device`() = switchesOnAndSaves(mobile = true)

    @Test
    @Config(sdk = [34], qualifiers = "w1024dp-h768dp-xhdpi")
    fun `the switch on a tablet turns the bars on and off for every device`() = switchesOnAndSaves(mobile = false)

    @Test
    fun `a tour answer saved by the server is read back`() {
        val (server, _) = serve(uiPrefs = """{"gutterChanges":true}""")
        try {
            val model = model(server)
            model.reloadGutterChangesPref()
            composeRule.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                model.gutterChanges
            }
        } finally {
            server.close()
        }
    }
}
