package com.hienlh.ppm.app.ui.phone.tools

import android.app.Application
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.tunnels.PortForwardingPanel
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.tunnels.NamedTunnelStatus
import com.hienlh.ppm.state.tunnels.TailscaleAvailability
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Port Forwarding panel's Set up / Manage on a phone: Settings → Remote Access opens there now. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h844dp-xxhdpi")
class PortForwardingSetupPhoneTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun `a phone's setup rows open Settings, Remote Access`() {
        val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PortForwardingPanel(
                    model = model,
                    initialTunnels = emptyList(),
                    initialTailscale = TailscaleAvailability(available = false, reason = "Tailscale is not installed"),
                    initialCloudflare = NamedTunnelStatus(mode = "quick"),
                )
            }
        }
        assertEquals(0, composeRule.onAllNodesWithText("on a tablet", substring = true).fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("tunnel-setup-tailscale").assertTextContains("Set up")
        composeRule.onNodeWithTag("tunnel-setup-tailscale").performClick()
        assertEquals("remote-access", model.requestedSettingsCategory)
    }
}
