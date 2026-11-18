package com.hienlh.ppm.app.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings → Storage on a tablet: the overview the phone shows, plus the session trace's limits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class StorageOverviewUiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun `the tablet shows backups and the session trace, and saves the trace's limits`() {
        val puts = mutableListOf<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val trace = if (request.method == "PUT") {
                    puts += request.body!!.utf8()
                    """{"path":"/p/session-trace.db","sizeBytes":1048576,"oldestTs":null,"retention_days":7,"max_size_mb":100}"""
                } else {
                    null
                }
                val body = trace ?: """{"appDb":{"path":"/p/ppm.db","sizeBytes":2048},"backups":{"dir":"/b","dbStem":"ppm","count":3,"totalBytes":4096,"newest":null,"others":[]},
                    "sessionTrace":{"path":"/p/session-trace.db","sizeBytes":1048576,"oldestTs":null,"retention_days":30,"max_size_mb":500}}"""
                return MockResponse.Builder().body("""{"ok":true,"data":$body}""").build()
            }
        }
        server.start()
        try {
            val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
            model.api = ApiClient(server.url("/"), "tok")
            composeRule.setContent {
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) { StorageOverviewSection(model) }
            }
            composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Backups").fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithText("3 · 4.0 KB").assertExists()
            composeRule.onNodeWithText("None yet").assertExists()
            composeRule.onNodeWithText("1.0 MB of 500 MB").assertExists()
            composeRule.onNodeWithTag("session-trace-save").assertIsNotEnabled()
            composeRule.onNodeWithTag("session-trace-size").performTextReplacement("10")
            composeRule.onNodeWithTag("session-trace-save").performClick()
            composeRule.onNodeWithText("Max size must be a whole number of at least 50 MB.").assertExists()
            composeRule.onNodeWithTag("session-trace-days").performTextReplacement("7")
            composeRule.onNodeWithTag("session-trace-size").performTextReplacement("100")
            composeRule.onNodeWithTag("session-trace-save").performClick()
            composeRule.waitUntil(5_000) { puts.isNotEmpty() }
            assertEquals("""{"retention_days":7,"max_size_mb":100}""", puts.single())
            composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("1.0 MB of 100 MB").fetchSemanticsNodes().isNotEmpty() }
        } finally {
            server.close()
        }
    }
}
