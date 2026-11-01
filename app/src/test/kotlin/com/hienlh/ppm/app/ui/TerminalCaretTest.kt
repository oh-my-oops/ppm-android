package com.hienlh.ppm.app.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The cursor the keyboard's pan follows is the last one the engine reported, as xterm's textarea stays where the cursor last was. */
class TerminalCaretTest {
    private val first = Rect(0f, 100f, 8f, 120f)
    private val second = Rect(8f, 120f, 16f, 140f)

    @Test
    fun `there is no cursor before the engine has reported one`() {
        assertNull(TerminalCaret().cell)
    }

    @Test
    fun `the cursor is where the engine last said it was`() {
        val caret = TerminalCaret()
        caret.cursorMoved(first)
        caret.cursorMoved(second)
        assertEquals(second, caret.cell)
    }

    @Test
    fun `a cursor the engine has hidden, or scrolled out of the view, stays where it was`() {
        val caret = TerminalCaret()
        caret.cursorMoved(first)
        caret.cursorMoved(null)
        assertEquals(first, caret.cell)
        caret.cursorMoved(second)
        caret.cursorMoved(null)
        caret.cursorMoved(null)
        assertEquals(second, caret.cell)
    }
}
