package com.hienlh.ppm.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.AccountUsage
import com.hienlh.ppm.protocol.AccountUsageEntry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings → Accounts lays its grid cards out in a scrolling page, where the card is offered no height at all.
 * The card then takes its rows' own height (CSS `height: auto`) instead of crashing the app on an infinite size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp")
class AccountCardUnboundedHeightTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun aGridCardInAScrollingPage_takesItsRowsHeight() {
        val entry = AccountUsageEntry(
            accountId = "a1",
            accountLabel = "Work account",
            accountStatus = "active",
            isOAuth = true,
            usage = AccountUsage(),
        )
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AccountCard(
                        entry = entry,
                        isActive = true,
                        accountInfo = null,
                        layout = AccountCardLayout.Grid,
                        modifier = Modifier.fillMaxWidth().testTag("grid-card"),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Work account").assertIsDisplayed()
        val bounds = composeRule.onNodeWithTag("grid-card").getBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue("card is as tall as its rows, got $height", height.value in 20f..400f)
    }
}
