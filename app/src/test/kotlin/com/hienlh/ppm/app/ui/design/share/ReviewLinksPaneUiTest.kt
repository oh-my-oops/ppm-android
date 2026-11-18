package com.hienlh.ppm.app.ui.design.share

import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.settings.ReviewLinksPane
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings → Remote Access → Review links against a scripted PPM, on a tablet and on a phone. */
abstract class ReviewLinksPaneUiTestBase(private val mobile: Boolean) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val fake = DesignShareFakeServer().start()
    private val toasts = mutableListOf<ErrorToastMessage>()
    private var openedGeneral = 0

    @After
    fun tearDown() = fake.close()

    private fun show() {
        val client = ApiClient(fake.server.url("/"), "test-token")
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                ) {
                    ReviewLinksPane(api = client, onToast = { toasts += it }, onOpenUrl = {}, onOpenGeneral = { openedGeneral++ })
                }
            }
        }
    }

    private fun waitTag(tag: String) = composeRule.waitUntil(5_000) {
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitText(text: String) = composeRule.waitUntil(5_000) {
        composeRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun tag(tag: String): SemanticsNodeInteraction = composeRule.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()

    @Test
    fun theSwitchTurnsTheReviewAddressOn() {
        show()
        waitTag("review-links-switch-card")
        waitText("Off.")
        tag("review-links-switch").performClick()
        composeRule.waitUntil(5_000) { fake.sentTo("POST", "/api/share-host/enabled").isNotEmpty() }
        assertEquals("""{"enabled":true}""", fake.sentTo("POST", "/api/share-host/enabled").single().body)
        waitText("On. Review links open at https://review.trycloudflare.com")
    }

    @Test
    fun aDomainIsSetUpThroughTheZoneAndHostname() {
        show()
        waitTag("review-links-setup-named")
        tag("review-links-setup-named").performClick()
        waitTag("review-setup-hostname")
        tag("review-setup-prefix").performTextReplacement("review")
        tag("review-links-submit").performClick()
        composeRule.waitUntil(5_000) { fake.sentTo("POST", "/api/share-host/setup").isNotEmpty() }
        val body = fake.sentTo("POST", "/api/share-host/setup").single().body
        assertTrue(body, body.contains("\"mode\":\"named\"") && body.contains("\"hostname\":\"review.example.com\""))
        // Back to the options, the domain now in use.
        waitTag("review-links-option-named")
        waitText("IN USE")
    }

    @Test
    fun anUnusableSignInAsksBeforeSigningInAgain() {
        fake.certState = "invalid"
        show()
        waitTag("review-links-setup-named")
        tag("review-links-setup-named").performClick()
        waitTag("review-setup-relogin")
        assertTrue(fake.sentTo("POST", "/api/tunnel/named/login").isEmpty())
    }

    @Test
    fun switchingBackToATemporaryAddressIsConfirmed() {
        fake.shareHostMode = "named"
        show()
        waitTag("review-links-switch-quick")
        tag("review-links-switch-quick").performClick()
        waitTag("review-links-switch-confirm")
        composeRule.onNodeWithTag("review-links-switch-confirm", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { fake.sentTo("POST", "/api/share-host/quick").isNotEmpty() }
        composeRule.waitUntil(5_000) { toasts.any { it.title == "Switched to a temporary address" } }
    }

    @Test
    fun withThePasswordOffTheDomainCannotBeSetUp() {
        fake.authEnabled = false
        show()
        waitTag("review-links-auth-off")
        assertEquals(0, composeRule.onAllNodesWithTag("review-links-setup-named", useUnmergedTree = true).fetchSemanticsNodes().size)
        composeRule.onAllNodesWithText("Open General", useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1024dp-h768dp")
class ReviewLinksPaneTabletUiTest : ReviewLinksPaneUiTestBase(mobile = false)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ReviewLinksPanePhoneUiTest : ReviewLinksPaneUiTestBase(mobile = true)
