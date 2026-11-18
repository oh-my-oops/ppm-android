package com.hienlh.ppm.app.ui.chat

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.chat.LocalDesignShots
import com.hienlh.ppm.chat.ToolCardView
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.GalleryImage
import com.hienlh.ppm.design.ImageViewer
import com.hienlh.ppm.design.LocalImageViewer
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ToolOutcome
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.ToolBlock
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/** A `design_screenshot` card: its pictures read by id, a gone one said so, a tap opening the lightbox. */
abstract class DesignShotCardUiTestBase(private val mobile: Boolean) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val kept = "0123456789abcdef0123456789abcdef"
    private val gone = "fedcba9876543210fedcba9876543210"

    private val output = """[{"type":"text","text":"{\"project\":\"web\",\"shots\":[""" +
        """{\"id\":\"$kept\",\"design\":\"landing\",\"file\":\"index.html\",\"label\":\"Hero\"},""" +
        """{\"id\":\"$gone\",\"design\":\"landing\",\"file\":\"variant-2.html\",\"label\":\"\"}]}"},""" +
        """{"type":"text","text":"[image · 120KB]"}]"""

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test
    fun showsEachPictureByIdAndOpensTheLightbox() {
        val asked = mutableListOf<String>()
        val shown = mutableListOf<Pair<List<GalleryImage>, Int>>()
        val bytes = png()
        val tool = ToolBlock(
            toolUseId = "s1",
            name = "mcp__ppm-sessions__design_screenshot",
            input = WireJson.parse("""{"design":"landing"}"""),
            result = ToolOutcome(output, false),
        )
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                CompositionLocalProvider(
                    LocalDesignShots provides { id: String ->
                        asked += id
                        if (id == kept) bytes else throw IllegalStateException("404 Not found")
                    },
                    LocalImageViewer provides ImageViewer { images, index -> shown += images to index },
                ) { ToolCardView(tool, completed = true) }
            }
        }
        composeRule.onNodeWithText("Screenshot").assertExists()
        composeRule.onNodeWithText("· 2 pictures").assertExists()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Picture no longer kept").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("1. index.html · Hero").assertExists()
        composeRule.onNodeWithText("2. variant-2.html").assertExists()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Loading…").fetchSemanticsNodes().isEmpty() }
        assertEquals(setOf(kept, gone), asked.toSet())

        composeRule.onNodeWithTag("design-shot-$kept").performClick()
        assertEquals(1, shown.size)
        assertEquals(0, shown.single().second)
        assertEquals(listOf("index.html · Hero", "variant-2.html"), shown.single().first.map { it.name })
    }

    @Test
    fun aFailedCallShowsWhy() {
        val tool = ToolBlock(
            toolUseId = "s2",
            name = "ppm_sessions:design_screenshot",
            input = WireJson.parse("""{"server":"ppm_sessions","tool":"design_screenshot","arguments":{"design":"landing"}}"""),
            result = ToolOutcome("No screenshot was taken: no browser found", true),
        )
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) { ToolCardView(tool, completed = true) }
        }
        composeRule.onNodeWithText("landing").assertExists()
        composeRule.onNodeWithText("No screenshot was taken: no browser found").assertExists()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1024dp-h768dp")
class DesignShotCardTabletUiTest : DesignShotCardUiTestBase(mobile = false)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class DesignShotCardPhoneUiTest : DesignShotCardUiTestBase(mobile = true)
