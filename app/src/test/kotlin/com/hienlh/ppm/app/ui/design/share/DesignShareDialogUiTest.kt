package com.hienlh.ppm.app.ui.design.share

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.hienlh.ppm.app.ErrorToastMessage
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

/** The Share for review dialog against a scripted PPM, on a tablet and on a phone. */
abstract class DesignShareDialogUiTestBase(private val mobile: Boolean) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val fake = DesignShareFakeServer().start()
    private val toasts = mutableListOf<ErrorToastMessage>()
    private val opened = mutableListOf<String>()

    @After
    fun tearDown() = fake.close()

    private fun show() {
        val client = ApiClient(fake.server.url("/"), "test-token")
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                DesignShareSheet(
                    api = client,
                    events = null,
                    projectName = "web",
                    slug = "landing",
                    localOrigin = "http://192.168.1.10:3210",
                    onDismiss = {},
                    onToast = { toasts += it },
                    onOpenUrl = {},
                    onOpenSettings = { opened += it },
                )
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

    private fun openAction(linkId: String, action: String) {
        waitTag("share-link-row-$linkId")
        composeRule.onNode(hasTestTag("share-link-more"), useUnmergedTree = true).performScrollTo().performClick()
        waitTag("share-action-$action")
        tag("share-action-$action").performClick()
        waitTag("share-row-panel")
    }

    @Test
    fun theDesignToolbarOpensTheDialog() {
        val client = ApiClient(fake.server.url("/"), "test-token")
        val detail = com.hienlh.ppm.state.design.DesignDetail(
            summary = com.hienlh.ppm.state.design.DesignSummary(slug = "landing", title = "Landing", hasIndex = true),
            files = listOf("index.html"),
        )
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                androidx.compose.foundation.layout.Column {
                    com.hienlh.ppm.app.ui.design.DesignHeaderToolbar(
                        detail = detail,
                        activeVariant = "index.html",
                        onSelectVariant = {},
                        deviceFrame = com.hienlh.ppm.state.design.DesignDeviceFrame.DESKTOP,
                        onSelectDeviceFrame = {},
                        zoom = 1f,
                        onZoomChange = {},
                        layoutOverride = com.hienlh.ppm.state.design.DesignLayoutOverride.AUTO,
                        onSelectLayoutOverride = {},
                        pickerEnabled = false,
                        onTogglePicker = {},
                        transformModeOn = false,
                        onToggleTransform = {},
                        undoCount = 0,
                        isStreaming = false,
                        onUndo = {},
                        openCommentsCount = 0,
                        sidePanel = com.hienlh.ppm.app.ui.design.DesignSidePanel.NONE,
                        onToggleSidePanel = {},
                        onBuildInNewChat = {},
                        onReload = {},
                        shareOpen = open,
                        onShare = { open = true },
                    )
                    if (open) {
                        DesignShareSheet(client, null, "web", "landing", "http://192.168.1.10:3210", { open = false }, { toasts += it }, {}, { opened += it })
                    }
                }
            }
        }
        composeRule.onNodeWithText("Share for review", useUnmergedTree = true).performScrollTo().performClick()
        waitTag("share-dialog")
        waitTag("share-link-row-l1")
    }

    @Test
    fun createsALinkWithItsLabelExpiryAndVersion() {
        show()
        waitTag("share-address-named")
        composeRule.onNodeWithText("https://ppm.example.com", useUnmergedTree = true).performScrollTo()
        waitTag("share-link-row-l1")

        tag("share-new-label").performTextReplacement("Team pass")
        tag("share-new-expiry-thirty_days").performClick()
        // The version picker lists the saved versions after the current one. A phone picks from
        // Chrome's select dialog; the tablet's dropdown popup cannot be drawn under Robolectric
        // (its drawable is not in the test resources), so there the link takes the current version.
        if (mobile) {
            composeRule.onNodeWithText("Current version (saved now)", useUnmergedTree = true).performScrollTo().performClick()
            waitText(", saved")
            composeRule.onAllNodesWithText(", saved", substring = true, useUnmergedTree = true).onLast().performClick()
        }

        composeRule.onNodeWithTag("share-create", useUnmergedTree = true).performClick()
        waitTag("share-link-row-new1")
        val post = fake.sentTo("POST", "/api/project/web/designs/landing/shares").single().body
        assertTrue(post, post.contains("\"label\":\"Team pass\""))
        assertTrue(post, post.contains(if (mobile) "\"version\":\"snap-old\"" else "\"version\":\"current\""))
        val expiresAt = Regex(""""expiresAt":(\d+)""").find(post)?.groupValues?.get(1)?.toLong()
        val days = (expiresAt!! - System.currentTimeMillis()) / 86_400_000.0
        assertTrue("expires in $days days", days in 29.0..31.0)
        composeRule.waitUntil(5_000) { toasts.any { it.title == "Link created" } }
    }

    @Test
    fun repinShowsTheChangedFilesBeforeAndAfter() {
        show()
        openAction("l1", "repin")
        waitTag("share-repin-preview")
        waitText("index.html")
        tag("share-panel-confirm").performClick()
        composeRule.waitUntil(5_000) { fake.sentTo("PATCH", "/api/project/web/designs/landing/shares/l1").isNotEmpty() }
        assertTrue(fake.sentTo("PATCH", "/api/project/web/designs/landing/shares/l1").single().body.contains("\"repin\":true"))
        waitText("Reviewers now see the latest version")
        waitTag("share-changed-files")
        composeRule.onNodeWithText("b.png", substring = true, useUnmergedTree = true).performScrollTo()
    }

    @Test
    fun changesTheExpiryOfALink() {
        show()
        openAction("l1", "expiry")
        tag("share-l1-expiry-one_day").performClick()
        tag("share-panel-confirm").performClick()
        composeRule.waitUntil(5_000) { fake.sentTo("PATCH", "/api/project/web/designs/landing/shares/l1").isNotEmpty() }
        val body = fake.sentTo("PATCH", "/api/project/web/designs/landing/shares/l1").single().body
        val expiresAt = Regex(""""expiresAt":(\d+)""").find(body)?.groupValues?.get(1)?.toLong()
        val days = (expiresAt!! - System.currentTimeMillis()) / 86_400_000.0
        assertTrue("expires in $days days", days in 0.0..2.0)
    }

    @Test
    fun clearsALinksComments() {
        show()
        openAction("l1", "delete_comments")
        tag("share-panel-confirm").performClick()
        composeRule.waitUntil(5_000) { fake.sentTo("DELETE", "/api/project/web/designs/landing/shares/l1/comments").isNotEmpty() }
        composeRule.waitUntil(5_000) { toasts.any { it.title == "The link's comments were deleted" } }
    }

    @Test
    fun revokesALinkAndItsCommentsTogether() {
        show()
        openAction("l1", "revoke")
        tag("share-also-comments").performClick()
        tag("share-panel-confirm").performClick()
        composeRule.waitUntil(5_000) { fake.sentTo("DELETE", "/api/project/web/designs/landing/shares/l1/comments").isNotEmpty() }
        assertTrue(fake.sentTo("PATCH", "/api/project/web/designs/landing/shares/l1").single().body.contains("\"revoke\":true"))
        waitText("Revoked")
    }

    @Test
    fun signInOffPausesSharing() {
        fake.address = """{"kind":"named","url":"https://ppm.example.com","via":"ppm","enabled":true,"authEnabled":false}"""
        fake.authEnabled = false
        show()
        waitTag("share-address-signin-off")
        composeRule.onNodeWithTag("share-create", useUnmergedTree = true).assertIsNotEnabled()
        composeRule.onNode(hasTestTag("share-create") and hasText("Turn on PPM sign-in first", substring = true)).assertExists()
        assertEquals(0, composeRule.onAllNodesWithTag("share-new-link", useUnmergedTree = true).fetchSemanticsNodes().size)
        tag("share-address-open-general").performClick()
        assertEquals(listOf("general"), opened)
    }

    @Test
    fun noPublicAddressOffersToStartOneAndToSetUpAPermanentOne() {
        fake.address = """{"kind":"none","url":null,"via":"ppm","enabled":false,"authEnabled":true}"""
        show()
        waitTag("share-address-none")
        tag("share-address-permanent").performClick()
        assertEquals(listOf("remote-access"), opened)
    }

    @Test
    fun theReviewCardTurnsTheReviewAddressOnAndAsksForATunnel() {
        fake.ppmTunnelNamed = true
        show()
        waitTag("share-review-card")
        tag("share-review-switch").performClick()
        composeRule.waitUntil(5_000) { fake.sentTo("POST", "/api/share-host/enabled").isNotEmpty() }
        assertTrue(fake.sentTo("POST", "/api/share-host/enabled").first().body.contains("true"))
        // Enabled: Use my domain, then PPM's tunnel or one of its own.
        waitTag("share-review-domain")
        tag("share-review-domain").performClick()
        waitTag("share-review-choose")
        tag("share-review-choose-shared").performClick()
        waitTag("review-setup-hostname")
        composeRule.waitUntil(5_000) { fake.sentTo("GET", "/api/share-host/zone").isNotEmpty() }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1024dp-h768dp")
class DesignShareDialogTabletUiTest : DesignShareDialogUiTestBase(mobile = false)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class DesignShareDialogPhoneUiTest : DesignShareDialogUiTestBase(mobile = true)
