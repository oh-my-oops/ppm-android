package com.hienlh.ppm.app.ui.logs

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.phone.PhoneLogsAreaHost
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.logs.DeviceLogBuffer
import com.hienlh.ppm.state.logs.LogEntry
import com.hienlh.ppm.state.logs.LogIssuesResult
import com.hienlh.ppm.state.logs.LogQueryResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** "This device" in place of "Browser": this app's own lines, read on the device, on the tablet and the phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeviceLogsUiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val model by lazy { AppModel(ApplicationProvider.getApplicationContext<Context>()) }

    private fun deviceLog() = DeviceLogBuffer().apply {
        add("info", "PPM", "PPM app 0.9.3 started on Android 17")
        add("error", "toast", "Could not save the logs")
    }

    @Test
    @Config(sdk = [34], qualifiers = "sw800dp-w1280dp-h800dp")
    fun `tablet - This device replaces Browser and shows the app's own lines, live`() {
        val device = deviceLog()
        val server = LogQueryResult(
            entries = listOf(LogEntry(id = "e1", ts = System.currentTimeMillis(), lv = "info", src = "server", tag = "srv", msg = "Server ready")),
        )
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                LogsAppBody(model = model, initialResult = server, initialIssues = LogIssuesResult(), deviceLog = device)
            }
        }

        assertEquals(0, composeRule.onAllNodesWithTag("logs-source-browser").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("logs-source-device").assertTextContains("This device", substring = true)
        composeRule.onNodeWithText("Server ready", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag("logs-source-device").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("PPM app 0.9.3 started on Android 17", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Could not save the logs", substring = true).assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("Server ready", substring = true).fetchSemanticsNodes().size)

        device.add("warn", "PPM", "lost the connection to http://ppm.local:8080")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("lost the connection to http://ppm.local:8080", substring = true).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w412dp-h1200dp-xxhdpi")
    fun `phone - This device replaces Browser and shows the app's own lines with no server`() {
        val device = deviceLog()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneLogsAreaHost(model = model, deviceLog = device)
            }
        }

        assertEquals(0, composeRule.onAllNodesWithTag("phone-logs-source-browser").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("phone-logs-source-device").assertTextContains("This device", substring = true)

        composeRule.onNodeWithTag("phone-logs-source-device").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("PPM app 0.9.3 started on Android 17", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Could not save the logs", substring = true).assertIsDisplayed()

        device.add("warn", "PPM", "lost the connection to http://ppm.local:8080")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("lost the connection to http://ppm.local:8080", substring = true).assertIsDisplayed()
    }
}
