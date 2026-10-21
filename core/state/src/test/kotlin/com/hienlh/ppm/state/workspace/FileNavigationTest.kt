package com.hienlh.ppm.state.workspace

import com.hienlh.ppm.client.FsStat
import com.hienlh.ppm.client.PpmError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** use-markdown-file-navigation.ts, against a host of a few files, a folder and some links. */
class FileNavigationTest {
    private val root = "/home/u/app"

    /** What the host has: a path's kind, and where a link points. */
    private val host = mapOf(
        "/home/u/app/src/a.ts" to "file",
        "/home/u/app/docs" to "directory",
        "/home/u/notes.md" to "file",
        "/home/u/app/link.ts" to "symlink:src/a.ts",
        "/home/u/app/out.ts" to "symlink:/home/u/notes.md",
        "/home/u/app/loop.ts" to "symlink:loop.ts",
        "/home/u/app/socket" to "socket",
    )
    private val asked = mutableListOf<String>()
    private val stat: suspend (String) -> FsStat = { path ->
        asked += path
        val kind = host[path] ?: throw PpmError.Server(404, "not found")
        val name = path.substringAfterLast('/')
        if (kind.startsWith("symlink:")) FsStat(path, name, "symlink", kind.removePrefix("symlink:")) else FsStat(path, name, kind)
    }

    private suspend fun open(path: String, line: SourceLine? = null, projectRoot: String? = root) =
        resolveFileNavigation(path, line, "app", projectRoot, now = 5000, stat = stat)

    private fun editor(filePath: String, title: String, projectId: String?, vararg extra: Pair<String, Long>) = FileNavigation.Editor(
        NewTab(
            "editor",
            title,
            projectId,
            JsonObject(mapOf("filePath" to JsonPrimitive(filePath), "projectName" to JsonPrimitive("app")) + extra.map { (k, v) -> k to JsonPrimitive(v) }),
        ),
        reveal = extra.isNotEmpty(),
    )

    @Test
    fun `a project file opens by its relative name, at the lines named`() = runTest {
        assertEquals(editor("src/a.ts", "a.ts", "app"), open("src/a.ts"))
        assertEquals(listOf("/home/u/app/src/a.ts"), asked)
        assertEquals(editor("src/a.ts", "a.ts", "app", "lineNumber" to 12L, "revealAt" to 5000L), open("src/a.ts", SourceLine(12)))
        assertEquals(
            editor("src/a.ts", "a.ts", "app", "lineNumber" to 3L, "endLine" to 9L, "revealAt" to 5000L),
            open("/home/u/app/src/a.ts", SourceLine(3, 9)),
        )
    }

    @Test
    fun `a file outside the project is named by its absolute path and belongs to no project`() = runTest {
        assertEquals(editor("/home/u/notes.md", "notes.md", null), open("/home/u/notes.md"))
        // `~` is the host's to resolve: asked as it is written.
        open("~/notes.md")
        assertEquals("~/notes.md", asked.last())
    }

    @Test
    fun `links are followed, relative to the link, and a loop gives up after eight`() = runTest {
        assertEquals(editor("src/a.ts", "a.ts", "app"), open("link.ts"))
        assertEquals(listOf("/home/u/app/link.ts", "/home/u/app/src/a.ts"), asked)
        assertEquals(editor("/home/u/notes.md", "notes.md", null), open("out.ts"))
        asked.clear()
        assertEquals(FileNavigation.Search("loop.ts"), open("loop.ts"))
        assertEquals(9, asked.size)
    }

    @Test
    fun `a folder opens as a folder, and anything else is searched for, its lines with it`() = runTest {
        assertEquals(FileNavigation.Folder("/home/u/app/docs"), open("docs"))
        assertEquals(FileNavigation.Search("socket"), open("socket"))
        assertEquals(FileNavigation.Search("src/missing.ts:4-6"), open("src/missing.ts", SourceLine(4, 6)))
        // With no project folder only an absolute path can be looked up.
        asked.clear()
        assertEquals(FileNavigation.Search("src/a.ts:2"), open("src/a.ts", SourceLine(2), projectRoot = ""))
        assertEquals(emptyList<String>(), asked)
        assertEquals(editor("/home/u/notes.md", "notes.md", null), open("/home/u/notes.md", projectRoot = null))
    }

    @Test
    fun `a location reads back as it was written`() {
        assertEquals("src/app.ts", formatSourceLocation("src/app.ts", null))
        assertEquals("src/app.ts:120", formatSourceLocation("src/app.ts", SourceLine(120)))
        assertEquals("src/app.ts:120-140", formatSourceLocation("src/app.ts", SourceLine(120, 140)))
    }
}
