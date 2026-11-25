package com.hienlh.ppm.app.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.design.DesignSummary
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DesignSidebarRowTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    // The tablet showed "default" broken into a column of letters ("d/e/f/a/ul/t") and "Page"
    // into "P/a/g/e" beside a title too long for the sidebar (2026-11-25).
    @Test
    fun `a long title is cut short and its badges stay on one line`() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Box(Modifier.width(400.dp)) {
                    DesignSidebarRow(
                        DesignSummary(
                            slug = "improve-ui-ux-review-code-trong-session",
                            title = "improve UI UX review code trong session and the rest of a long title",
                            system = "default",
                        ),
                        isActive = false,
                        onOpen = {},
                        onRename = {},
                        onDelete = {},
                    )
                }
            }
        }
        for (badge in listOf("default", "Page")) {
            val bounds = composeRule.onNodeWithText(badge, useUnmergedTree = true).getUnclippedBoundsInRoot()
            val height = bounds.bottom - bounds.top
            val width = bounds.right - bounds.left
            // One 12px line; a broken badge is several lines tall and narrower than it is tall.
            assertTrue("$badge is $height tall", height <= 13.dp)
            assertTrue("$badge is $width wide", width > height)
        }
    }
}
