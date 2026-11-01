package com.hienlh.ppm.app.ui

import com.hienlh.ppm.protocol.FileEntry
import com.hienlh.ppm.state.files.FileTreeNode
import com.hienlh.ppm.state.files.FileTreeState
import com.hienlh.ppm.state.workspace.SourceLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What command-palette.tsx searches and counts: the query's path and line, the host's folders, the
 * ranking of actions and files together, and the numbers on the filter chips.
 */
class PaletteSearchTest {
    private fun action(label: String, words: String = "") = PaletteItem(PaletteGroup.ACTION, label, "$label $words", action = {})

    private fun file(path: String, ignored: Boolean = false) = fileItem(FileEntry(path, path.substringAfterLast('/'), isIgnored = ignored))

    private fun hostFile(path: String) = filesystemItem(path)

    private fun List<PaletteItem>.paths() = map { it.keywords }

    @Test
    fun `a query ends in the line it names, and a drive letter is not one`() {
        PaletteQuery("src/app.ts:160-172").let {
            assertEquals("src/app.ts", it.path)
            assertEquals(SourceLine(160, 172), it.line)
        }
        PaletteQuery("app.ts:120").let {
            assertEquals("app.ts", it.path)
            assertEquals(SourceLine(120), it.line)
        }
        PaletteQuery("app.ts#L12-L14").let {
            assertEquals("app.ts", it.path)
            assertEquals(SourceLine(12, 14), it.line)
        }
        PaletteQuery("D:/repo/src/PaymentForm.tsx:160").let {
            assertEquals("D:/repo/src/PaymentForm.tsx", it.path)
            assertEquals(SourceLine(160), it.line)
        }
        PaletteQuery("plain").let {
            assertEquals("plain", it.path)
            assertNull(it.line)
        }
    }

    @Test
    fun `a line that cannot be leaves the whole text as the path rather than opening line 1`() {
        for (text in listOf("app.ts:0", "app.ts:9-3")) {
            val query = PaletteQuery(text)
            assertEquals(text, query.path)
            assertNull(text, query.line)
        }
    }

    @Test
    fun `a path query starts at the root, the home folder or a drive`() {
        for (query in listOf("/", "/etc", "~/", "~/Projects/ppm", "~\\", "C:\\Users", "c:/x", "D:/repo")) assertTrue(query, isPathQuery(query))
        for (query in listOf("", "~", "src/", "./src", "../x", "C:", "C:x", "a/b")) assertFalse(query, isPathQuery(query))
    }

    @Test
    fun `the folder the host lists is the path up to its last separator, with or without the rest of a name`() {
        assertEquals("~/Projects/", listedDirectory("~/Projects/"))
        assertEquals("~/Projects/", listedDirectory("~/Projects/pp"))
        assertEquals("/", listedDirectory("/"))
        // A name directly under the root is all there is to list.
        assertEquals("/etc", listedDirectory("/etc"))
        assertEquals("D:/repo/src/", listedDirectory("D:/repo/src/PaymentForm.tsx"))
        assertEquals("C:\\Users\\me\\", listedDirectory("C:\\Users\\me\\x.txt"))
        assertEquals("C:\\Users\\", listedDirectory("C:\\Users\\"))
    }

    @Test
    fun `a file the host lists is named by its last segment, whichever separator ends it`() {
        val posix = hostFile("/home/me/Projects/ppm/README.md")
        assertEquals("README.md", posix.label)
        assertEquals("/home/me/Projects/ppm/README.md", posix.hint)
        assertEquals("/home/me/Projects/ppm/README.md", posix.keywords)
        assertEquals(PaletteGroup.FILESYSTEM, posix.group)
        assertEquals("Pay.tsx", hostFile("C:\\repo\\src\\Pay.tsx").label)
    }

    @Test
    fun `a project file shows its name with its path beside it, and keeps the flag for an ignored one`() {
        val plain = file("src/app.ts")
        assertEquals(PaletteGroup.FILE, plain.group)
        assertEquals("app.ts", plain.label)
        assertEquals("src/app.ts", plain.hint)
        assertFalse(plain.ignored)
        assertTrue(file(".env", ignored = true).ignored)
    }

    @Test
    fun `the exact file ranks above a longer one that only starts the same way`() {
        val index = PaletteSearchIndex(
            listOf(
                file("docs/journals/implementation-260419"),
                file("plans/reports/docs-manager-260419"),
                file("docs/readme-notes.md"),
                file("README.md"),
            ),
        )
        assertEquals(listOf("README.md", "readme-notes.md"), index.rank("readme").map { it.label })
        // The longer one still matches, spelled out letter by letter, and comes after.
        assertEquals(listOf("README.md", "readme-notes.md"), index.rank("README.md").map { it.label })
    }

    @Test
    fun `an action is found by its name and its words, and ranks with the files`() {
        val settings = action("Settings", "config preferences theme")
        val terminal = action("New Terminal", "bash shell console")
        val index = PaletteSearchIndex(listOf(terminal, settings, file("docs/settings/index.md"), file("src/settings.ts")))
        // The web searches the words alone; the name is searched here too.
        assertEquals(listOf("Settings", "settings.ts", "index.md"), index.rank("settings").map { it.label })
        assertEquals(listOf("Settings"), index.rank("preferences").map { it.label })
        assertEquals(listOf("New Terminal"), index.rank("bash terminal").map { it.label })
    }

    @Test
    fun `a query of several words is scored word by word, so the second word picks the action`() {
        // The palette's own actions, with the words it gives them.
        val index = PaletteSearchIndex(
            listOf(
                action("New AI Chat", "ai assistant claude"),
                action("New Terminal", "bash shell console"),
                action("Voice Input", "speech microphone dictate voice"),
                action("Settings", "config preferences theme"),
                action("Open File Explorer", "open file explorer finder browse files folders disk drive window"),
            ),
        )
        assertEquals(listOf("New Terminal"), index.rank("new term").map { it.label })
        assertEquals(listOf("New AI Chat"), index.rank("new chat").map { it.label })
        // The first word alone ties them at a prefix match, and the shorter name goes first.
        assertEquals(listOf("New AI Chat", "New Terminal"), index.rank("new").map { it.label }.take(2))
    }

    @Test
    fun `equal scores keep the order given, and a shallower path comes first when nothing else tells them apart`() {
        val given = listOf(file("a/x.ts"), file("b/x.ts"))
        assertEquals(listOf("a/x.ts", "b/x.ts"), PaletteSearchIndex(given).rank("x.ts").paths())
        assertEquals(listOf("b/x.ts", "a/x.ts"), PaletteSearchIndex(given.reversed()).rank("x.ts").paths())
        assertEquals(listOf("x.ts", "a/x.ts"), PaletteSearchIndex(listOf(file("a/x.ts"), file("x.ts"))).rank("x.ts").paths())
    }

    @Test
    fun `a hundred results at most, the best of them`() {
        val files = (0 until 150).map { file("dir/file$it.ts") }
        val found = PaletteSearchIndex(files).rank("file")
        assertEquals(PALETTE_MAX_RESULTS, found.size)
        // Shorter names first: file0 to file9, then file10 to file99; the hundreds are cut.
        assertTrue(found.none { it.label.length > "file99.ts".length })
    }

    @Test
    fun `a leading dot slash is dropped, because the index holds paths without one`() {
        val index = PaletteSearchIndex(listOf(file("src/app.ts"), file("src/lib/util.ts")))
        assertEquals(listOf("src/app.ts"), index.rank("./src/app.ts").paths())
        assertEquals(listOf("src/app.ts"), index.rank("../src/app.ts").paths())
        assertEquals(emptyList<String>(), index.rank("x/src/app.ts").paths())
    }

    @Test
    fun `nothing typed lists the actions alone, and a name or a path lists what it finds`() {
        val actions = listOf(action("Settings"), action("New AI Chat"))
        val index = PaletteSearchIndex(actions + file("src/app.ts"))
        assertSame(actions, paletteResults(index, actions, emptyList(), ""))
        assertSame(actions, paletteResults(index, actions, emptyList(), "   "))
        assertEquals(listOf("app.ts"), paletteResults(index, actions, emptyList(), "app").map { it.label })
    }

    @Test
    fun `the files of a folder are filtered by what follows its last slash, not ranked`() {
        val host = listOf(
            hostFile("/home/me/Projects/zeta.ts"),
            hostFile("/home/me/Projects/b/Payment.tsx"),
            hostFile("/home/me/Projects/alpha.ts"),
        )
        // Nothing after the slash: all of them, as listed.
        assertEquals(host.paths(), filesystemMatches(host, "~/Projects/").paths())
        assertEquals(listOf("/home/me/Projects/b/Payment.tsx"), filesystemMatches(host, "/home/me/Projects/pay").paths())
        // The path counts as well as the name.
        assertEquals(listOf("/home/me/Projects/b/Payment.tsx"), filesystemMatches(host, "/home/me/Projects/b").paths())
        // Not a ranking: alpha.ts starts with the filter, yet what was listed first stays first.
        assertEquals(host.paths(), filesystemMatches(host, "/home/me/Projects/a").paths())
        // A backslash does not end the folder, so a Windows path names no filter.
        assertEquals(host.paths(), filesystemMatches(host, "C:\\Users\\me\\x").paths())
    }

    @Test
    fun `a folder lists fifty files at most`() {
        val host = (0 until 80).map { hostFile("/data/file$it.log") }
        assertEquals(FILESYSTEM_MAX_RESULTS, filesystemMatches(host, "/data/").size)
        assertEquals(FILESYSTEM_MAX_RESULTS, filesystemMatches(host, "/data/file").size)
        assertEquals(host.take(FILESYSTEM_MAX_RESULTS).paths(), filesystemMatches(host, "/data/").paths())
    }

    @Test
    fun `a path query lists the host's files and nothing of the project`() {
        val actions = listOf(action("Settings", "/ slash"))
        val index = PaletteSearchIndex(actions + file("src/app.ts"))
        val host = listOf(hostFile("/srv/www/index.html"))
        assertEquals(host.paths(), paletteResults(index, actions, host, "/srv/").paths())
        assertEquals(emptyList<String>(), paletteResults(index, actions, emptyList(), "/srv/").paths())
        assertEquals(host.paths(), paletteResults(index, actions, host, "~/").paths())
    }

    @Test
    fun `a chip is offered for each kind that exists, whatever the query finds`() {
        assertEquals(listOf(PaletteGroup.ACTION), availableGroups(hasActions = true, hasFiles = false, hasFilesystem = false))
        assertEquals(listOf(PaletteGroup.ACTION, PaletteGroup.FILE), availableGroups(hasActions = true, hasFiles = true, hasFilesystem = false))
        assertEquals(
            listOf(PaletteGroup.ACTION, PaletteGroup.FILE, PaletteGroup.FILESYSTEM),
            availableGroups(hasActions = true, hasFiles = true, hasFilesystem = true),
        )
        assertEquals(listOf(PaletteGroup.FILE), availableGroups(hasActions = false, hasFiles = true, hasFilesystem = false))
    }

    @Test
    fun `a chip counts what the search found of its kind`() {
        val results = listOf(action("Settings"), file("src/settings.ts"), file("docs/settings.md"), hostFile("/etc/settings.conf"))
        assertEquals(mapOf(PaletteGroup.ACTION to 1, PaletteGroup.FILE to 2, PaletteGroup.FILESYSTEM to 1), groupCounts(results))
        // With nothing typed only the actions are listed: the Files chip reads zero.
        assertEquals(mapOf(PaletteGroup.ACTION to 2), groupCounts(listOf(action("a"), action("b"))))
        assertNull(groupCounts(emptyList())[PaletteGroup.FILE])
    }

    @Test
    fun `a chip that is on narrows the list to its kind, and to none of the others`() {
        val results = listOf(action("Settings"), file("src/settings.ts"), hostFile("/etc/settings.conf"))
        assertSame(results, visibleResults(results, emptySet()))
        assertEquals(listOf("settings.ts"), visibleResults(results, setOf(PaletteGroup.FILE)).map { it.label })
        assertEquals(listOf("Settings", "settings.conf"), visibleResults(results, setOf(PaletteGroup.ACTION, PaletteGroup.FILESYSTEM)).map { it.label })
    }

    @Test
    fun `a chip narrows only what the search kept of its best hundred`() {
        val actions = (0 until PALETTE_MAX_RESULTS).map { PaletteItem(PaletteGroup.ACTION, "m", "match action $it", action = {}) }
        val files = (0 until 20).map { file("deep/match$it.ts") }
        val kept = PaletteSearchIndex(actions + files).rank("match")
        assertEquals(PALETTE_MAX_RESULTS, kept.size)
        assertTrue(kept.all { it.group == PaletteGroup.ACTION })
        // Twenty files match, but the hundred that were kept hold none of them.
        assertEquals(emptyList<PaletteItem>(), visibleResults(kept, setOf(PaletteGroup.FILE)))
    }

    @Test
    fun `until the project's list lands the palette lists the files the explorer has read, depth first`() {
        fun node(path: String, directory: Boolean = false) = FileTreeNode(path.substringAfterLast('/'), path, directory, isIgnored = true)
        val tree = FileTreeState(
            project = "p",
            directories = mapOf(
                "" to listOf(node("a", directory = true), node("z.txt"), node("unread", directory = true)),
                "a" to listOf(node("a/b.ts"), node("a/sub", directory = true), node("a/d.ts")),
                "a/sub" to listOf(node("a/sub/c.ts")),
            ),
        )
        val files = loadedTreeFiles(tree, "p")
        assertEquals(listOf("a/b.ts", "a/sub/c.ts", "a/d.ts", "z.txt"), files.map { it.path })
        assertEquals(listOf("b.ts", "c.ts", "d.ts", "z.txt"), files.map { it.name })
        // As on the web, what the tree read of ignoring is not carried over.
        assertTrue(files.none { it.isIgnored })
        assertEquals(emptyList<FileEntry>(), loadedTreeFiles(tree, "other"))
        assertEquals(emptyList<FileEntry>(), loadedTreeFiles(null, "p"))
    }
}
