package com.hienlh.ppm.state.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSessionStateTest {

    @Test
    fun `piece buffer handles 50k lines with fast line lookup bounded slices and exact undo`() {
        val raw = buildString(50_000 * 105) {
            for (i in 1..50_000) {
                append("// line $i: editor performance fixture ".padEnd(104, 'x'))
                if (i < 50_000) append('\n')
            }
        }
        val state = EditorSessionState(initialText = raw, language = "typescript")
        assertEquals(50_000, state.buffer.lineCount)
        assertTrue(state.buffer.length > 5_000_000)

        // O(log N) line lookup in the middle of 50k lines
        val midOffset = state.buffer.lineColToOffset(25_000, 10)
        val pos = state.buffer.offsetToLineCol(midOffset)
        assertEquals(LineCol(25_000, 10), pos)

        // Select-all on 5 MB buffer -> bounded getSelectedText() never exceeds MAX_IME_SLICE_CHARS
        state.selectAll()
        val selectedSlice = state.getSelectedText()
        assertNotNull(selectedSlice)
        assertEquals(MAX_IME_SLICE_CHARS, selectedSlice!!.length)

        // Replace all 5 MB with "X" and undo -> exact match on length and sample lines
        state.commitText("X", 1, nowMs = 1_000L)
        assertEquals("X", state.buffer.toString())
        assertTrue(state.undo())
        assertEquals(raw.length, state.buffer.length)
        assertEquals(50_000, state.buffer.lineCount)
        assertEquals(raw.substring(0, 104), state.buffer.line(0))
        assertEquals(raw.substring(raw.length - 104), state.buffer.line(49_999))
    }

    @Test
    fun `surrogate boundaries are preserved in IME slices and deleteSurroundingTextInCodePoints`() {
        // 🚀 is U+1F680 (2 UTF-16 code units: \uD83D\uDE80)
        val state = EditorSessionState(initialText = "ab🚀cd")
        // Attempt to place selection right in the middle of the surrogate pair (index 3)
        state.setSelection(3, 3)
        // Clamped to index 2 (high surrogate)
        assertEquals(2, state.selectionStart)

        // Place cursor after 🚀 (index 4) and ask for 1 UTF-16 char before cursor:
        // must clamp to include both surrogates ("🚀") instead of an orphaned low surrogate
        state.setSelection(4, 4)
        assertEquals("🚀", state.getTextBeforeCursor(1))

        // Delete 1 code point before cursor -> removes the whole 🚀 surrogate pair
        state.deleteSurroundingTextInCodePoints(1, 0, nowMs = 2_000L)
        assertEquals("abcd", state.buffer.toString())
    }

    @Test
    fun `Vietnamese Telex composition via setComposingText and commitText produces exact phrase`() {
        val state = EditorSessionState(initialText = "")
        state.setComposingText("t", 1, nowMs = 100L)
        state.setComposingText("ti", 1, nowMs = 150L)
        state.setComposingText("tie", 1, nowMs = 200L)
        state.setComposingText("tiê", 1, nowMs = 250L)
        state.setComposingText("tiến", 1, nowMs = 300L)
        state.setComposingText("tiếng", 1, nowMs = 350L)
        assertTrue(state.hasComposition)
        assertEquals(0, state.composingStart)
        assertEquals(5, state.composingEnd)

        state.commitText("tiếng ", 1, nowMs = 400L)
        assertFalse(state.hasComposition)

        state.setComposingText("v", 1, nowMs = 500L)
        state.setComposingText("vi", 1, nowMs = 550L)
        state.setComposingText("việ", 1, nowMs = 600L)
        state.setComposingText("việt", 1, nowMs = 650L)
        state.commitText("việt", 1, nowMs = 700L)

        assertEquals("tiếng việt", state.buffer.toString())
        assertEquals(10, state.selectionStart)
    }

    @Test
    fun `undo coalesces fast single-char typing and separates paused edits`() {
        val state = EditorSessionState(initialText = "")
        state.commitText("a", 1, nowMs = 1_000L)
        state.commitText("b", 1, nowMs = 1_100L)
        state.commitText("c", 1, nowMs = 1_200L)

        // Pause > 400ms
        state.commitText("d", 1, nowMs = 2_000L)
        assertEquals("abcd", state.buffer.toString())

        // First undo reverts "d"
        assertTrue(state.undo())
        assertEquals("abc", state.buffer.toString())

        // Second undo reverts coalesced "abc"
        assertTrue(state.undo())
        assertEquals("", state.buffer.toString())

        // Redo restores "abc" then "d"
        assertTrue(state.redo())
        assertEquals("abc", state.buffer.toString())
        assertTrue(state.redo())
        assertEquals("abcd", state.buffer.toString())
    }

    @Test
    fun `autoPair smartNewline toggleComment and regex replaceAll with capture groups work`() {
        val state = EditorSessionState(initialText = "function demo", language = "typescript")
        state.setSelection(state.buffer.length)

        // Type '(' -> inserts "()" and places cursor inside
        state.typeChar('(')
        assertEquals("function demo()", state.buffer.toString())
        assertEquals(14, state.selectionStart)

        // Type ')' -> skips over closing ')'
        state.typeChar(')')
        assertEquals(15, state.selectionStart)

        state.commitText(" ", 1)
        state.typeChar('{')
        assertEquals("function demo() {}", state.buffer.toString())

        // Smart newline inside "{|}" -> indents body and places closing '}' on next line
        state.insertNewlineWithIndent()
        assertEquals("function demo() {\n    \n}", state.buffer.toString())

        // Bracket match on closing '}' finds opening '{'
        val match = state.findMatchingBracket(state.buffer.length)
        assertNotNull(match)
        assertEquals(16, match!!.openOffset)

        // Toggle line comment on line 0
        state.goToLine(1, 1)
        state.toggleLineComment()
        assertTrue(state.buffer.line(0).startsWith("// "))
        state.toggleLineComment()
        assertFalse(state.buffer.line(0).startsWith("// "))

        // Regex replaceAll with $1 and $2 capture groups (fixes web $1 whole-match bug)
        val regexState = EditorSessionState("const foo = 10;\nconst bar = 20;", language = "typescript")
        val count = regexState.replaceAll(
            query = "const (\\w+) = (\\d+);",
            replacement = "let $1: number = $2;",
            isRegex = true,
        )
        assertEquals(2, count)
        assertEquals("let foo: number = 10;\nlet bar: number = 20;", regexState.buffer.toString())
    }

    @Test
    fun `EditorTabDocumentState tracks dirty state reloads clean buffer and protects dirty edits on disk conflict`() {
        val doc = EditorTabDocumentState("val a = 1\n", language = "kotlin")
        assertFalse(doc.isDirty)
        assertEquals("Main.kt", doc.formatTabTitle("Main.kt"))

        // Clean buffer reloads when disk changes
        assertTrue(doc.onExternalDiskContent("val a = 2\n"))
        assertEquals("val a = 2\n", doc.session.buffer.toString())
        assertFalse(doc.isDirty)

        // User edits buffer -> dirty
        doc.session.setSelection(doc.session.buffer.length)
        doc.session.commitText("val b = 3\n", 1)
        assertTrue(doc.isDirty)
        assertEquals("Main.kt ●", doc.formatTabTitle("Main.kt"))

        // Conflicting disk change while dirty -> preserves user buffer and flags conflict
        assertFalse(doc.onExternalDiskContent("val a = 999\n"))
        assertTrue(doc.externalChangeConflict)
        assertTrue(doc.session.buffer.toString().contains("val b = 3"))

        // Saving clears dirty & conflict
        doc.markSaved()
        assertFalse(doc.isDirty)
        assertFalse(doc.externalChangeConflict)

        val snippet = buildAiEditorContextSnippet("src/Main.kt", "kotlin", 10, 12, "val x = 1\nval y = 2")
        assertTrue(snippet.startsWith("src/Main.kt:10-12\n```kotlin\n"))
    }

    @Test
    fun `C8 preserves CRLF and CR line endings on load edit and save`() {
        val doc = EditorTabDocumentState("a\r\nb", language = "typescript")
        assertEquals("\r\n", doc.session.buffer.eol)
        assertFalse(doc.isDirty)

        doc.session.setSelection(1, 1)
        doc.session.commitText("1", 1)
        assertTrue(doc.isDirty)
        assertEquals("a1\nb", doc.session.buffer.toString())
        assertEquals("a1\r\nb", doc.textToSave())

        doc.markSaved()
        assertFalse(doc.isDirty)
        assertFalse(doc.onExternalDiskContent("a1\r\nb"))

        val crDoc = EditorTabDocumentState("x\ry", language = "plaintext")
        assertEquals("\r", crDoc.session.buffer.eol)
        crDoc.session.setSelection(1, 1)
        crDoc.session.commitText("2", 1)
        assertEquals("x2\ry", crDoc.textToSave())
    }
}
