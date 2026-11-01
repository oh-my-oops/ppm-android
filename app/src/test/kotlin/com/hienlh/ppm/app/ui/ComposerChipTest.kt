package com.hienlh.ppm.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The two decisions attachment-chips.tsx keeps in named functions: what a tap does, and what an opened chip shows. */
class ComposerChipTest {
    @Test
    fun `a chip with text opens its text, even when it also has a picture`() {
        assertEquals(ChipBodyAction.EXPAND, chipBodyAction("```bash\nls\n```", previewable = false))
        assertEquals(ChipBodyAction.EXPAND, chipBodyAction("ls", previewable = true))
    }

    @Test
    fun `a chip with only a picture opens it, and any other chip does nothing`() {
        assertEquals(ChipBodyAction.PREVIEW, chipBodyAction(null, previewable = true))
        assertEquals(ChipBodyAction.PREVIEW, chipBodyAction("", previewable = true))
        assertEquals(ChipBodyAction.NONE, chipBodyAction(null, previewable = false))
        assertEquals(ChipBodyAction.NONE, chipBodyAction("", previewable = false))
    }

    @Test
    fun `the terminal output chip opens without its fence, and the selection chip with its heading`() {
        assertEquals("$ bun test\n12 pass", stripCodeFence("```bash\n$ bun test\n12 pass\n```"))
        val selection = "Selected text from the terminal\n```\nls\n```"
        assertEquals(selection, stripCodeFence(selection))
    }

    @Test
    fun `a fence is stripped with or without a language, and only when it wraps all of the text`() {
        assertEquals("x", stripCodeFence("```\nx\n```"))
        assertEquals("a\n\nb", stripCodeFence("```ts\na\n\nb\n```"))
        // The end of the text is what ends the block, so a fence inside it stays.
        assertEquals("a\n```\nmore", stripCodeFence("```bash\na\n```\nmore\n```"))
        assertEquals("plain text", stripCodeFence("plain text"))
        assertEquals("before\n```\nx\n```", stripCodeFence("before\n```\nx\n```"))
    }

    @Test
    fun `the text is trimmed as JavaScript trims it before the fence is looked for`() {
        assertEquals("x", stripCodeFence("\n   ﻿```\nx\n```  \n"))
        assertEquals("  indented", stripCodeFence("```\n  indented\n```"))
        // Not trimmed here: the block's own blank edges are its content.
        assertEquals("\nx\n", stripCodeFence("```\n\nx\n\n```"))
    }

    @Test
    fun `a block with nothing between its fences, or with CRLF line ends, is shown as it is`() {
        assertEquals("```bash\n```", stripCodeFence("```bash\n```"))
        assertEquals("```bash\r\nls\r\n```", stripCodeFence("```bash\r\nls\r\n```"))
    }
}
