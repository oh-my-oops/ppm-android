package com.hienlh.ppm.app.ui.phone.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every Settings section opens on a phone (the web's narrow Settings: list, then the pane), and
 * what the phone pages used to send to a tablet is one their own now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp-xxhdpi")
class PhoneSettingsEverySectionTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private lateinit var model: AppModel

    @Before
    fun setUp() {
        // No server: each pane draws what it can before it has read anything.
        model = AppModel(ApplicationProvider.getApplicationContext<Application>())
    }

    private fun shown(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun textShown(text: String) =
        composeRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `each section the tablet hosts whole opens as a phone page`() {
        var route by mutableStateOf<PhoneSettingsRoute>(PhoneSettingsRoute.Home)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneSettingsScreen(model = model, initialRoute = route)
            }
        }
        val hosted = listOf(
            PhoneSettingsSection.Tools, PhoneSettingsSection.Mcp, PhoneSettingsSection.AiResources, PhoneSettingsSection.Assistant,
            PhoneSettingsSection.Vault, PhoneSettingsSection.Design, PhoneSettingsSection.Voice, PhoneSettingsSection.Shortcuts,
            PhoneSettingsSection.RemoteAccess, PhoneSettingsSection.Cloud, PhoneSettingsSection.Proxy, PhoneSettingsSection.Extensions,
            PhoneSettingsSection.Jira, PhoneSettingsSection.Files, PhoneSettingsSection.LanguageServers,
            PhoneSettingsSection.DatabaseDrivers, PhoneSettingsSection.RemoteDesktop,
        )
        for (section in hosted) {
            route = PhoneSettingsRoute.FullPage(section)
            composeRule.waitForIdle()
            assertTrue("Expected ${section.id}'s page", shown("phone-settings-${section.id}-page"))
            assertTrue(textShown(section.title))
        }
        assertEquals(0, composeRule.onAllNodesWithText("on a tablet", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `a link into Settings opens its page even while Settings is showing, once`() {
        var route by mutableStateOf<PhoneSettingsRoute>(PhoneSettingsRoute.Home)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneSettingsScreen(model = model, initialRoute = route)
            }
        }
        assertTrue(shown("phone-settings-home-list"))
        model.requestedSettingsCategory = "remote-access"
        route = resolvePhoneSettingsRoute("remote-access")
        composeRule.waitForIdle()
        assertTrue(shown("phone-settings-remote-access-page"))
        // Followed, so a later link to the same page is a change again.
        assertNull(model.requestedSettingsCategory)
        // The link going away (the request cleared) leaves the page where it is.
        route = PhoneSettingsRoute.Home
        composeRule.waitForIdle()
        assertTrue(shown("phone-settings-remote-access-page"))
    }

    @Test
    fun `Accounts and AI Provider open the tablet's whole pane one level down, and Back returns`() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneSettingsScreen(model = model, initialRoute = PhoneSettingsRoute.FullPage(PhoneSettingsSection.Accounts))
            }
        }
        composeRule.onNodeWithTag("phone-accounts-all-settings").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(shown("phone-settings-accounts-all-page"))
        assertTrue(textShown(PHONE_ACCOUNTS_ALL_TITLE))
        // The tablet's provider tabs, where rotation, backup and token tests live.
        assertTrue(shown("accounts-tab-codex"))
        composeRule.onNodeWithTag("phone-settings-back").performClick()
        composeRule.waitForIdle()
        assertTrue(shown("phone-accounts-all-settings"))
    }

    @Test
    fun `About carries General's device name, password and Learn PPM, and Theme the rest of Appearance`() {
        var route by mutableStateOf<PhoneSettingsRoute>(PhoneSettingsRoute.FullPage(PhoneSettingsSection.About))
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneSettingsScreen(model = model, initialRoute = route)
            }
        }
        assertTrue(shown("phone-about-general"))
        assertTrue(textShown("Device Name"))
        assertTrue(textShown("Change Password"))
        assertTrue(shown("settings-device-name-input"))

        route = PhoneSettingsRoute.FullPage(PhoneSettingsSection.Theme)
        composeRule.waitForIdle()
        assertTrue(shown("phone-theme-extras"))
        assertTrue(textShown("Custom Themes"))
        assertEquals(0, composeRule.onAllNodesWithText("on a tablet", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
    }
}
