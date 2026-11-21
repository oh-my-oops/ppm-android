package com.hienlh.ppm.design

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.max

/** An in-place selection over ChromeText blocks, apart from any layout ([ChromeSelectionModel]). */
class ChromeSelectionModelTest {
    @Test
    fun `a press on a word selects the word`() {
        val text = "Answer text to copy"
        assertEquals(TextRange(7, 11), ChromeSelectionModel.wordAt(text, 8))
        assertEquals(TextRange(0, 6), ChromeSelectionModel.wordAt(text, 0))
        // Just after a word (on the space that ends it): that word.
        assertEquals(TextRange(7, 11), ChromeSelectionModel.wordAt(text, 11))
        assertEquals(TextRange(15, 19), ChromeSelectionModel.wordAt(text, 19))
    }

    @Test
    fun `an inline code's edges and punctuation are not part of the word`() {
        // How the markdown lays out `code`: a private-use edge on either side.
        val text = "run npm_test, then"
        assertEquals(TextRange(5, 13), ChromeSelectionModel.wordAt(text, 7))
        // On the comma, off any word: the comma alone.
        assertEquals(TextRange(14, 15), ChromeSelectionModel.wordAt(text, 14))
        assertEquals(TextRange(0), ChromeSelectionModel.wordAt("", 0))
    }

    @Test
    fun `a word in another script, and digits, select whole`() {
        assertEquals(TextRange(5, 8), ChromeSelectionModel.wordAt("Chọn chữ này đi", 6))
        assertEquals(TextRange(6, 10), ChromeSelectionModel.wordAt("port: 8081 now", 7))
    }

    @Test
    fun `a selection over blocks gives each block its part`() {
        val start = ChromeAnchor(0, 7)
        val end = ChromeAnchor(2, 4)
        assertEquals(ChromeSelectionModel.pack(7, 12), ChromeSelectionModel.rangeIn(0, 12, start, end))
        assertEquals(ChromeSelectionModel.pack(0, 30), ChromeSelectionModel.rangeIn(1, 30, start, end))
        assertEquals(ChromeSelectionModel.pack(0, 4), ChromeSelectionModel.rangeIn(2, 9, start, end))
        assertEquals(ChromeSelectionModel.NONE, ChromeSelectionModel.rangeIn(3, 9, start, end))
        // A selection ending at a block's very start takes nothing of it.
        assertEquals(ChromeSelectionModel.NONE, ChromeSelectionModel.rangeIn(1, 30, start, ChromeAnchor(1, 0)))
        val packed = ChromeSelectionModel.pack(3, 70_000)
        assertEquals(3, ChromeSelectionModel.packedStart(packed))
        assertEquals(70_000, ChromeSelectionModel.packedEnd(packed))
    }

    @Test
    fun `extending from one paragraph into the next copies both parts, a newline between`() {
        val blocks = listOf("Alpha beta gamma", "Second paragraph here", "A list item")
        // A word in the first, extended into the second.
        assertEquals("beta gamma\nSecond", ChromeSelectionModel.plainText(blocks, ChromeAnchor(0, 6), ChromeAnchor(1, 6)))
        // Over a whole block in the middle.
        assertEquals("gamma\nSecond paragraph here\nA list", ChromeSelectionModel.plainText(blocks, ChromeAnchor(0, 11), ChromeAnchor(2, 6)))
        // Within one block.
        assertEquals("beta", ChromeSelectionModel.plainText(blocks, ChromeAnchor(0, 6), ChromeAnchor(0, 10)))
        // Nothing, either way round.
        assertEquals("", ChromeSelectionModel.plainText(blocks, ChromeAnchor(1, 3), ChromeAnchor(1, 3)))
        assertEquals("", ChromeSelectionModel.plainText(blocks, ChromeAnchor(2, 0), ChromeAnchor(1, 3)))
    }

    @Test
    fun `a copy leaves out an inline box's placeholder`() {
        // A task list item: the checkbox is a placeholder character before the text.
        val blocks = listOf("� Ship it", "Next")
        val copied = ChromeSelectionModel.plainText(blocks, ChromeAnchor(0, 0), ChromeAnchor(1, 4)) { block ->
            if (block == 0) listOf(TextRange(0, 1)) else emptyList()
        }
        assertEquals(" Ship it\nNext", copied)
    }

    @Test
    fun `a point goes to the block on its level, then the nearest across`() {
        // Two paragraphs, then a table row of two cells.
        val boxes = floatArrayOf(
            0f, 0f, 300f, 40f,
            0f, 56f, 300f, 96f,
            0f, 112f, 140f, 152f,
            160f, 112f, 300f, 152f,
        )
        assertEquals(0, ChromeSelectionModel.blockAt(boxes, 4, 50f, 20f))
        assertEquals(1, ChromeSelectionModel.blockAt(boxes, 4, 50f, 60f))
        // In the gap between the paragraphs, nearer the first.
        assertEquals(0, ChromeSelectionModel.blockAt(boxes, 4, 50f, 45f))
        assertEquals(1, ChromeSelectionModel.blockAt(boxes, 4, 50f, 53f))
        // Beside the table's second cell, right of it.
        assertEquals(3, ChromeSelectionModel.blockAt(boxes, 4, 320f, 130f))
        // Between the cells, nearer the first.
        assertEquals(2, ChromeSelectionModel.blockAt(boxes, 4, 145f, 130f))
        // Above everything and below everything.
        assertEquals(0, ChromeSelectionModel.blockAt(boxes, 4, 50f, -30f))
        assertEquals(2, ChromeSelectionModel.blockAt(boxes, 4, 50f, 400f))
        assertEquals(-1, ChromeSelectionModel.blockAt(boxes, 0, 0f, 0f))
    }

    /**
     * Lines as ChromeText draws them, against where the paragraph Compose laid out has them: 14 px
     * Geist on 20 at 2.625x, the first and third holding an inline code. Compose stacks them 53 px
     * apart; Chrome's lines with code are taller and push the rest down, so near a line's top a
     * point is on a different line than Compose's own boxes would say.
     */
    @Test
    fun `a point is on the line drawn there, not the line Compose laid out there`() {
        val ratio = 1.25f / 0.875f
        val strut = chromeTextExtent(14f * 2.625f, 14f * ratio * 2.625f, ChromeFace.Sans.ascent, ChromeFace.Sans.descent)
        val mono = chromeTextExtent(12f * 2.625f, 12f * ratio * 2.625f, ChromeFace.Mono.ascent, ChromeFace.Mono.descent)
        val code = setOf(0, 2)
        val boxes = chromeLineBoxes(5) { line -> if (line in code) max(strut.first, mono.first) to max(strut.second, mono.second) else strut }
        val edges = IntArray(5) { chromePixel(0f, boxes.tops[it]) }
        // Compose's uniform lines, 53 px each.
        val compose = { y: Float -> (y / 53f).toInt() }
        // Line 4 is drawn from 211 (Chrome's 52.5 px lines gain on Compose's 53); just below that,
        // Compose's own line 3 would still be there, down to 212.
        assertEquals(211, edges[4])
        val y = edges[4] + 0.5f
        assertEquals(4, ChromeSelectionModel.lineAt(edges, y))
        assertEquals(3, compose(y))
        // Every point of every band is on that band's line.
        for (line in 0 until 5) {
            val bottom = if (line + 1 < 5) edges[line + 1] else chromePixel(0f, boxes.height)
            assertEquals(line, ChromeSelectionModel.lineAt(edges, edges[line].toFloat()))
            assertEquals(line, ChromeSelectionModel.lineAt(edges, bottom - 0.5f))
        }
        // Above the first line and below the last: those lines.
        assertEquals(0, ChromeSelectionModel.lineAt(edges, -10f))
        assertEquals(4, ChromeSelectionModel.lineAt(edges, 10_000f))
    }
}
