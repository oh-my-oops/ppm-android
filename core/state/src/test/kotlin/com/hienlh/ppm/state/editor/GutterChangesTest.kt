package com.hienlh.ppm.state.editor

import com.hienlh.ppm.state.editor.GutterChanges.Change
import com.hienlh.ppm.state.editor.GutterChanges.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The editor's change bars, as computed: which lines of the buffer are added, modified, or sit
 * next to deleted lines, against the file in the index (`tests/unit/web/gutter-changes.test.ts`).
 */
class GutterChangesTest {
    private fun lines(text: String) = GutterChanges.splitBaseLines(text)
    private fun changes(base: String, now: String) = GutterChanges.compute(lines(base), lines(now))

    @Test
    fun `splits as Monaco does, a final newline leaving an empty last line`() {
        assertEquals(listOf("a", "b", ""), lines("a\nb\n"))
        assertEquals(listOf("a", "b"), lines("a\r\nb"))
        assertEquals(listOf("a", "b"), lines("a\rb"))
        assertEquals(listOf(""), lines(""))
    }

    @Test
    fun `drops a byte-order mark, which Monaco keeps out of the model`() {
        assertEquals(listOf("a", "b"), lines("﻿a\nb"))
    }

    @Test
    fun `reports nothing for an unchanged buffer`() {
        assertEquals(emptyList<Change>(), changes("a\nb\nc\n", "a\nb\nc\n"))
    }

    @Test
    fun `marks added lines`() {
        assertEquals(listOf(Change(Kind.ADDED, 2, 3)), changes("a\nb\n", "a\nx\ny\nb\n"))
    }

    @Test
    fun `marks lines appended at the end`() {
        assertEquals(listOf(Change(Kind.ADDED, 2, 2)), changes("a\n", "a\nb\n"))
    }

    @Test
    fun `marks a changed line as modified`() {
        assertEquals(listOf(Change(Kind.MODIFIED, 2, 2)), changes("a\nb\nc\n", "a\nB\nc\n"))
    }

    @Test
    fun `marks only the new range of a modification that shrank or grew`() {
        assertEquals(listOf(Change(Kind.MODIFIED, 2, 2)), changes("a\nb\nc\nd\n", "a\nX\nd\n"))
        assertEquals(listOf(Change(Kind.MODIFIED, 2, 4)), changes("a\nb\nd\n", "a\nX\nY\nZ\nd\n"))
    }

    @Test
    fun `puts a deletion on the line the deleted lines followed`() {
        assertEquals(listOf(Change(Kind.DELETED, 1, 1)), changes("a\nb\nc\nd\n", "a\nd\n"))
    }

    @Test
    fun `reports a deletion at the top of the file as line 0`() {
        assertEquals(listOf(Change(Kind.DELETED, 0, 0)), changes("a\nb\nc\n", "c\n"))
    }

    @Test
    fun `keeps separate changes separate`() {
        assertEquals(
            listOf(Change(Kind.MODIFIED, 2, 2), Change(Kind.ADDED, 5, 5), Change(Kind.DELETED, 7, 7)),
            changes("1\n2\n3\n4\n5\n6\n7\n8\n", "1\nX\n3\n4\nnew\n5\n6\n8\n"),
        )
    }

    @Test
    fun `ignores line endings, so a CRLF base matches the same text with LF`() {
        assertEquals(emptyList<Change>(), changes("a\r\nb\r\n", "a\nb\n"))
    }

    @Test
    fun `matches the screenshot case - one line edited, one added`() {
        val base = listOf(
            "export function resolveEntryMode(",
            "  argv: string[],",
            "): \"supervise\" | \"search\" | \"cli\" {",
            "  if (argv.includes(\"__search__\")) return \"search\";",
            "  return \"cli\";",
            "}",
        ).joinToString("\n")
        val now = listOf(
            "export function resolveEntryMode(",
            "  argv: string[],",
            "): \"supervise\" | \"search\" | \"memory\" | \"cli\" {",
            "  if (argv.includes(\"__search__\")) return \"search\";",
            "  if (argv.includes(\"__memory__\")) return \"memory\";",
            "  return \"cli\";",
            "}",
        ).joinToString("\n")
        assertEquals(listOf(Change(Kind.MODIFIED, 3, 3), Change(Kind.ADDED, 5, 5)), changes(base, now))
    }

    @Test
    fun `row marks put bars on their lines and a deletion's wedge on the boundary it sat on`() {
        val marks = GutterChanges.rowMarks(
            listOf(Change(Kind.MODIFIED, 2, 3), Change(Kind.DELETED, 5, 5), Change(Kind.DELETED, 0, 0), Change(Kind.ADDED, 9, 12)),
            lineCount = 10,
        )
        assertEquals(GutterChanges.RowMark(bar = Kind.MODIFIED), marks[1])
        assertEquals(GutterChanges.RowMark(bar = Kind.MODIFIED), marks[2])
        assertEquals(GutterChanges.RowMark(deletedBelow = true), marks[4])
        assertEquals(GutterChanges.RowMark(deletedAbove = true), marks[0])
        // Clamped to the buffer, as Monaco clamps a decoration's range.
        assertEquals(GutterChanges.RowMark(bar = Kind.ADDED), marks[9])
        assertEquals(setOf(0, 1, 2, 4, 8, 9), marks.keys)
    }

    @Test
    fun `reports a middle it cannot diff in time as one modified range, never as unchecked lines`() {
        // Two unrelated stretches share nothing, and a budget of 0 cannot finish. The answer
        // covers exactly the differing middle.
        val base = listOf("same-head") + List(3000) { "old $it" } + "same-tail"
        val now = listOf("same-head") + List(2000) { "new $it" } + "same-tail"
        assertEquals(listOf(Change(Kind.MODIFIED, 2, 2001)), GutterChanges.compute(base, now, work = 0))
    }
}
