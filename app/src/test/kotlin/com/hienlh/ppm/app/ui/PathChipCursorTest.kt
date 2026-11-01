package com.hienlh.ppm.app.ui

import android.view.PointerIcon
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.performMouseInput
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.chat.FirstSendView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * A path in a message is a chip (`cursor-pointer`), and the web shows the hand over all of it. A
 * context message's text is a selectable island whose own glyphs show the I-beam, and the chip's
 * name is text inside it, so the hand only reached the chip's padding and its icons. Drawn for real,
 * with a mouse moved over each part of the chip and along the line, reading the cursor the window
 * was asked for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PathChipCursorTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private lateinit var view: View

    // A message with a context tag holds no text the user typed, so a path in it is a chip, in the line.
    private val inline = "<local-command-caveat>c</local-command-caveat>see /tmp/palette-lines.txt and more"

    // The Opened in IDE row above a message: the same chip, outside the selectable text.
    private val ide = "<ide_opened_file>The user opened the file /tmp/palette-lines.txt in the IDE.</ide_opened_file>hello there"

    private fun show(content: String) = compose.setContent {
        view = LocalView.current
        FirstSendView(content, Instant.parse("2026-01-01T00:00:00Z"))
    }

    private fun icon(type: Int): PointerIcon = PointerIcon.getSystemIcon(ApplicationProvider.getApplicationContext(), type)

    private fun cursorAt(node: SemanticsNodeInteraction, x: (width: Float) -> Float): PointerIcon? {
        node.performMouseInput { moveTo(Offset(x(width.toFloat()), height / 2f)) }
        compose.waitForIdle()
        return view.pointerIcon
    }

    private fun chip() = compose.onNode(hasClickAction() and hasText("palette-lines.txt"))

    private fun name() = compose.onNode(hasText("palette-lines.txt") and !hasClickAction(), useUnmergedTree = true)

    @Test
    fun `the hand is over the name of a chip in the line, as it is over its padding and its icons`() {
        show(inline)

        val chip = chip()
        assertEquals("over the name", icon(PointerIcon.TYPE_HAND), cursorAt(name()) { it / 2f })
        assertEquals("over the left edge", icon(PointerIcon.TYPE_HAND), cursorAt(chip) { 1f })
        assertEquals("over the file icon", icon(PointerIcon.TYPE_HAND), cursorAt(chip) { 10f })
        assertEquals("over the link icon", icon(PointerIcon.TYPE_HAND), cursorAt(chip) { it - 9f })
        assertEquals("over the right edge", icon(PointerIcon.TYPE_HAND), cursorAt(chip) { it - 1f })
    }

    @Test
    fun `walking along the line the hand is over the chip from edge to edge and the I-beam over the text on both sides`() {
        show(inline)

        val text = compose.onNode(hasText("see", substring = true) and !hasClickAction(), useUnmergedTree = true)
        val line = text.fetchSemanticsNode().boundsInRoot
        val chip = chip().fetchSemanticsNode().boundsInRoot
        val hand = icon(PointerIcon.TYPE_HAND)
        val beam = icon(PointerIcon.TYPE_TEXT)
        // One pixel at a time: a single jump from the chip to the text after it leaves the hand there on every other try, with the fix and without, until the mouse moves again.
        val pixels = line.width.toInt()
        val seen = (0 until pixels).map { x ->
            when (cursorAt(text) { x + 0.5f }) { hand -> 'h'; beam -> 'i'; null -> '-'; else -> '?' }
        }.joinToString("")
        val expected = (0 until pixels).map { x -> if (x + 0.5f in (chip.left - line.left)..(chip.right - line.left)) 'h' else 'i' }.joinToString("")

        assertEquals("h is the hand, i is the I-beam, one letter for each pixel of the line", expected, seen)
    }

    @Test
    fun `the chip of the Opened in IDE row shows the hand over its name too`() {
        show(ide)

        assertEquals(icon(PointerIcon.TYPE_HAND), cursorAt(name()) { it / 2f })
    }
}
