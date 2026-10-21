package com.hienlh.ppm.app

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.text.TextRange
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SlashItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pickers as `message-input.tsx` opens them and puts a pick in the box. */
class ChatTabPickersTest {
    private val tab = ChatTab("t", SessionInfo("s", "mock", "Chat"), "p", "mock")

    private fun type(text: String, caret: Int = text.length) {
        tab.draft.edit {
            replace(0, length, text)
            selection = TextRange(caret)
        }
        tab.updatePickers(text, caret)
    }

    @Test
    fun `the slash picker opens before the file picker, from what is left of the caret`() {
        type("look at /he")
        assertEquals("he", tab.slashQuery)
        assertNull(tab.fileQuery)
        type("@src /he")
        assertEquals("he", tab.slashQuery)
        assertNull(tab.fileQuery)
        type("look at @src/ma")
        assertNull(tab.slashQuery)
        assertEquals("src/ma", tab.fileQuery)
        type("/he and more", caret = 3)
        assertEquals("he", tab.slashQuery)
        type("plain words")
        assertNull(tab.slashQuery)
        assertNull(tab.fileQuery)
    }

    @Test
    fun `the highlighted row goes back to the first as the query changes, and stays for a picker reopened on nothing`() {
        type("/")
        tab.slashSelection = 3
        type("/c")
        assertEquals(0, tab.slashSelection)
        type("/")
        tab.slashSelection = 2
        tab.closePickers()
        type("/")
        assertEquals("closed, the web's filter is the empty one", 2, tab.slashSelection)
        type("/x")
        tab.slashSelection = 1
        tab.closePickers()
        assertEquals(0, tab.slashSelection)
    }

    @Test
    fun `a command takes the slash query's place with its own sigil, the caret after its space`() {
        type("hello /he world", caret = 9)
        tab.pickSlash(SlashItem("builtin", "help"), raiseKeyboard = false)
        assertEquals("hello /help  world", tab.draft.text.toString())
        assertEquals(TextRange(12), tab.draft.selection)
        assertNull(tab.slashQuery)
        type("/img")
        tab.pickSlash(SlashItem("skill", "imagegen", invokeSigil = "$"), raiseKeyboard = false)
        assertEquals("\$imagegen ", tab.draft.text.toString())
    }

    @Test
    fun `an agent becomes the chip and its query leaves the box`() {
        type("please /pla")
        tab.pickSlash(SlashItem("agent", "planner"), raiseKeyboard = false)
        assertEquals("planner", tab.agent)
        assertEquals("please ", tab.draft.text.toString())
        assertEquals(TextRange(7), tab.draft.selection)
    }

    @Test
    fun `a file replaces the at query left of the caret, else goes at the end`() {
        type("look at @src/ma and more", caret = 15)
        tab.pickFile("src/main.ts", raiseKeyboard = false)
        assertEquals("look at @src/main.ts  and more", tab.draft.text.toString())
        assertEquals(TextRange(21), tab.draft.selection)
        assertNull(tab.fileQuery)
        tab.draft.setTextAndPlaceCursorAtEnd("no trigger")
        tab.pickFile("a.ts", raiseKeyboard = false)
        assertEquals("no trigger@a.ts ", tab.draft.text.toString())
        assertEquals(TextRange(16), tab.draft.selection)
    }
}
