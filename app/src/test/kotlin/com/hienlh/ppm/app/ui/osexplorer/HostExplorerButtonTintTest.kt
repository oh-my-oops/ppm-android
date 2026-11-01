package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.protocol.HostDirectory
import com.hienlh.ppm.protocol.HostPlace
import com.hienlh.ppm.state.osexplorer.ExplorerView
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The colours of the toolbar's buttons as they are painted: the toolbar's own are `text-text-subtle` at rest and `bg-accent-wash
 * text-primary` for the one in force (`toolbarButtonClass`, and the view buttons in `explorer-toolbar.tsx`), the other explorer buttons
 * `text-text-2` and a fixed wash of the accent. Only pixels tell a colour from the one beside it, so this reads them (native graphics,
 * 3 pixels to the dp), in the light theme, whose wash (0.10) is not the 0.12 the other buttons use.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h400dp-xxhdpi")
class HostExplorerButtonTintTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val tokens = BuiltInThemes.auroraLight.tokens
    private val ground = tokens.panel2.color

    private fun buttons() = compose.setContent {
        CompositionLocalProvider(LocalPpmTokens provides tokens) {
            Box(Modifier.background(ground)) {
                Row {
                    HostExplorerButton(ProductIcon.ArrowLeft, "plain, at rest") {}
                    HostExplorerButton(ProductIcon.ArrowLeft, "subtle, at rest", subtle = true) {}
                    HostExplorerButton(ProductIcon.ArrowLeft, "plain, in force", active = true) {}
                    HostExplorerButton(ProductIcon.ArrowLeft, "subtle, in force", active = true, subtle = true) {}
                }
            }
        }
    }

    private fun distance(a: Color, b: Color) = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

    /** The button's own fill, off its icon and clear of its rounded corners, and the colour of its icon's strongest pixel. */
    private fun painted(description: String): Pair<Color, Color> {
        val image = compose.onNodeWithContentDescription(description).captureToImage().toPixelMap()
        val fill = image[6, image.height / 2]
        var ink = fill
        for (y in 18 until 66) for (x in 18 until 66) if (distance(image[x, y], fill) > distance(ink, fill)) ink = image[x, y]
        return fill to ink
    }

    @Test
    fun `a toolbar button rests in the subtle text colour and not the second text colour a plain explorer button has`() {
        buttons()
        assertTrue("the two text colours differ", distance(tokens.text2.color, tokens.text3.color) > 0.1f)
        assertEquals(0f, distance(painted("subtle, at rest").second, tokens.text3.color), 0.02f)
        assertEquals(0f, distance(painted("plain, at rest").second, tokens.text2.color), 0.02f)
        assertEquals("at rest it has no fill of its own", 0f, distance(painted("subtle, at rest").first, ground), 0.004f)
    }

    @Test
    fun `the toolbar button in force wears the theme's accent wash under the accent, where the plain one has a fixed 12 percent`() {
        buttons()
        val wash = tokens.accentWash.color.compositeOver(ground)
        val fixed = tokens.accent.color.copy(alpha = .12f).compositeOver(ground)
        assertTrue("the two washes differ enough to tell apart", distance(wash, fixed) > 0.012f)
        val subtle = painted("subtle, in force")
        assertEquals(0f, distance(subtle.first, wash), 0.006f)
        assertEquals(0f, distance(subtle.second, tokens.accent.color), 0.03f)
        val plain = painted("plain, in force")
        assertEquals(0f, distance(plain.first, fixed), 0.006f)
        assertEquals(0f, distance(plain.second, tokens.accent.color), 0.03f)
    }

    @Test
    fun `every button of the real toolbar is the subtle one, and the view in force wears the accent wash`() {
        val session = explorerSessionOn(
            HostDirectory(emptyList(), "/home/me", "/home", listOf(HostPlace("me", "/home/me")), "/", truncated = false),
        )
        try {
            session.preferences.update(session.preferences.state.value.copy(view = ExplorerView.ICONS))
            compose.setContent {
                CompositionLocalProvider(LocalPpmTokens provides tokens) {
                    val state by session.state.collectAsState()
                    val preferences by session.preferences.state.collectAsState()
                    Box(Modifier.width(1000.dp)) { HostExplorerToolbar(session, state, preferences, canUpload = true) {} }
                }
            }
            assertTrue("the two text colours differ", distance(tokens.text2.color, tokens.text3.color) > 0.1f)
            for (description in listOf("Refresh", "Up one level", "Edit path", "List view", "Column view", "Sort", "Upload", "Show hidden files")) {
                val (fill, ink) = painted(description)
                // The pencil sits inside the path box, which is the panel's colour and not the bar's.
                val behind = if (description == "Edit path") tokens.panel.color else ground
                assertEquals("$description rests in text-subtle", 0f, distance(ink, tokens.text3.color), 0.03f)
                assertEquals("$description has no fill at rest", 0f, distance(fill, behind), 0.004f)
            }
            val (fill, ink) = painted("Icons view")
            assertEquals("the view in force is washed", 0f, distance(fill, tokens.accentWash.color.compositeOver(ground)), 0.006f)
            assertEquals("and in the accent", 0f, distance(ink, tokens.accent.color), 0.03f)
        } finally {
            session.close()
        }
    }
}
