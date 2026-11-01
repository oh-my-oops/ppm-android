package com.hienlh.ppm.app.ui.files

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class FileCodeLinesTest {
    @Test fun `CRLF lone CR and final newline keep editor row counts and UTF16 offsets`() {
        val lines = FileCodeLines("first\r\n\rthird😀\n")
        assertEquals("first\n\nthird😀\n", lines.text)
        assertEquals(listOf("first", "", "third😀", ""), List(lines.count, lines::line))
        assertArrayEquals(intArrayOf(0, 6, 7, 15), lines.starts)
        assertEquals(1, FileCodeLines("").count)
        assertEquals("", FileCodeLines("").line(0))
    }

    @Test fun `multiline token spans are clipped to all rows without coloring newline or empty rows`() {
        val text = "a/*\n\ncomment\n*/z"
        val lines = FileCodeLines(text)
        val comment = SpanStyle(color = Color.Green, fontStyle = FontStyle.Italic)
        val last = SpanStyle(color = Color.Red)
        val tokens = AnnotatedString(text, listOf(
            AnnotatedString.Range(comment, 1, text.length - 1),
            AnnotatedString.Range(last, text.length - 1, text.length),
        ))
        val rows = lines.tokenRows(tokens)!!
        assertEquals(listOf("a/*", "", "comment", "*/z"), rows.map { it.text })
        assertEquals(listOf(AnnotatedString.Range(comment, 1, 3)), rows[0].spanStyles)
        assertTrue(rows[1].spanStyles.isEmpty())
        assertEquals(listOf(AnnotatedString.Range(comment, 0, 7)), rows[2].spanStyles)
        assertEquals(listOf(AnnotatedString.Range(comment, 0, 2), AnnotatedString.Range(last, 2, 3)), rows[3].spanStyles)
    }

    @Test fun `stale highlighting cannot paint a different source even at the same length`() {
        val lines = FileCodeLines("new\ntext")
        assertNull(lines.tokenRows(AnnotatedString("old\ntext")))
        assertEquals("new", fileCodeDisplayLine("new", AnnotatedString("old")).text)
    }

    @Test fun `tabs advance to four column stops and carry token offsets through expansion`() {
        val text = "a\tb\tend"
        val style = SpanStyle(color = Color.Blue)
        val result = fileCodeDisplayLine(text, AnnotatedString(text, listOf(AnnotatedString.Range(style, 2, 5))))
        assertEquals("a   b   end", result.text)
        assertEquals(listOf(AnnotatedString.Range(style, 4, 9)), result.spanStyles)
        assertEquals(11, FileCodeLines(text).maxColumns)
    }

    @Test fun `wide codepoints combining marks and surrogate pairs keep tab stops consistent`() {
        for ((source, expanded) in listOf("中\tx" to "中  x", "😀\tx" to "😀  x", "e\u0301\tx" to "e\u0301   x")) {
            assertEquals(expanded, fileCodeDisplayLine(source, null).text)
            assertEquals(5, FileCodeLines(source).maxColumns)
        }
    }

    @Test fun `reveal rejects invalid starts and clamps ranges without integer overflow`() {
        assertNull(fileCodeReveal(null, null, 30))
        assertNull(fileCodeReveal(0, 3, 30))
        assertNull(fileCodeReveal(-1, null, 30))
        assertNull(fileCodeReveal(1, null, 0))
        assertEquals(4..8, fileCodeReveal(5, 9, 30))
        assertEquals(4..4, fileCodeReveal(5, 2, 30))
        assertEquals(4..29, fileCodeReveal(5, Long.MAX_VALUE, 30))
        assertEquals(29..29, fileCodeReveal(Long.MAX_VALUE, Long.MAX_VALUE, 30))
        assertEquals(0..0, fileCodeReveal(1, null, 1))
    }

    @Test fun `large documents index virtual rows without dropping trailing blank lines`() {
        val lines = FileCodeLines((1..100_000).joinToString("\n") + "\n")
        assertEquals(100_001, lines.count)
        assertEquals("50001", lines.line(50_000))
        assertEquals("100000", lines.line(99_999))
        assertEquals("", lines.line(100_000))
        assertEquals(6, lines.maxColumns)
    }

    @Test fun `language aliases honor explicit inline language and dotenv suffix rules`() {
        assertEquals("kotlin", fileCodeLanguage("C:\\src\\Test.KT"))
        assertEquals("python", fileCodeLanguage("script.py"))
        assertEquals("json", fileCodeLanguage("script.py", "json"))
        assertEquals("dotenv", fileCodeLanguage("/app/.env.test.example"))
        assertEquals("md", fileCodeLanguage(".env.example.md"))
        assertEquals("dockerfile", fileCodeLanguage("Dockerfile"))
        assertEquals("text", fileCodeLanguage(null))
    }

    @Test fun `inline and untitled content bypass disk even with a file path and empty content`() {
        fun tab(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = WorkspaceTab("editor:a", "editor", "a", "app",
            buildJsonObject { put("filePath", "a.txt"); block() })
        assertEquals("", fileBufferContent(tab { put("inlineContent", "") }))
        assertEquals("inline", fileBufferContent(tab { put("inlineContent", "inline"); put("isUntitled", true); put("unsavedContent", "draft") }))
        assertEquals("draft", fileBufferContent(tab { put("isUntitled", true); put("unsavedContent", "draft") }))
        assertEquals("", fileBufferContent(tab { put("isUntitled", true) }))
        assertNull(fileBufferContent(tab { put("inlineContent", JsonNull) }))
        assertNull(fileBufferContent(tab { put("isUntitled", "true") }))
    }
}
