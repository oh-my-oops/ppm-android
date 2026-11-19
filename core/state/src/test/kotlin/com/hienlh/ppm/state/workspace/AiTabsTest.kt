package com.hienlh.ppm.state.workspace

import com.hienlh.ppm.client.FsStat
import com.hienlh.ppm.protocol.TabOpenRequest
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** ai-tab-placement.test.ts, case by case, then the store opening what it chose. */
class AiTabsTest {
    private val session = "s-1"
    private fun meta(text: String) = WireJson.parse(text) as JsonObject
    private val chat = WorkspaceTab("chat:claude/s-1", "chat", "Chat", null, meta("""{"sessionId":"s-1"}"""), true)
    private fun file(filePath: String, projectName: String = "app") =
        WorkspaceTab("editor:$filePath", "editor", filePath, projectName, JsonObject(mapOf("filePath" to JsonPrimitive(filePath), "projectName" to JsonPrimitive(projectName))), true)

    private fun panel(id: String, tabs: List<WorkspaceTab>, activeTabId: String? = tabs.firstOrNull()?.id) = WorkspacePanel(id, tabs, activeTabId)

    private fun place(
        panels: List<WorkspacePanel>,
        grid: List<List<String>>,
        focusedPanelId: String = grid[0][0],
        mobile: Boolean = false,
    ) = chooseAiTabPlacement(grid, panels.associateBy { it.id }, focusedPanelId, mobile, session) { isTabForFile(it, "report.html", "app") }

    @Test
    fun `one panel - the file opens in the chat's panel and is split out to its right`() {
        assertEquals(AiTabPlacement.Open("p1", split = true), place(listOf(panel("p1", listOf(chat))), listOf(listOf("p1"))))
    }

    @Test
    fun `two panels - the file opens in the one that is not the chat's`() {
        val panels = listOf(panel("p1", listOf(chat)), panel("p2", listOf(file("a.ts"))))
        assertEquals(AiTabPlacement.Open("p2", split = false), place(panels, listOf(listOf("p1", "p2"))))
        // The chat on the right: its left neighbour.
        assertEquals(AiTabPlacement.Open("p2", split = false), place(panels, listOf(listOf("p2", "p1")), focusedPanelId = "p1"))
    }

    @Test
    fun `the right neighbour wins over a panel in another row, and the focused panel over both`() {
        val panels = listOf(panel("p1", listOf(chat)), panel("p2", emptyList()), panel("p3", emptyList()))
        val grid = listOf(listOf("p3"), listOf("p1", "p2"))
        assertEquals(AiTabPlacement.Open("p2", split = false), place(panels, grid, focusedPanelId = "p1"))
        assertEquals(AiTabPlacement.Open("p3", split = false), place(panels, grid, focusedPanelId = "p3"))
    }

    @Test
    fun `a tab already showing the file in another panel is brought to the front`() {
        val panels = listOf(panel("p1", listOf(chat)), panel("p2", listOf(file("a.ts"), file("report.html"))))
        assertEquals(AiTabPlacement.Focus("editor:report.html", "p2"), place(panels, listOf(listOf("p1", "p2"))))
    }

    @Test
    fun `a tab hidden behind the chat is moved out beside it, never shown over it`() {
        val behind = panel("p1", listOf(chat, file("report.html")), chat.id)
        assertEquals(AiTabPlacement.Move("editor:report.html", "p1", null), place(listOf(behind), listOf(listOf("p1"))))
        assertEquals(
            AiTabPlacement.Move("editor:report.html", "p1", "p2"),
            place(listOf(behind, panel("p2", emptyList())), listOf(listOf("p1", "p2"))),
        )
    }

    @Test
    fun `when the chat is not the tab on screen in its panel, the file's tab there is just focused`() {
        val panels = listOf(panel("p1", listOf(chat, file("report.html"), file("a.ts")), "editor:a.ts"))
        assertEquals(AiTabPlacement.Focus("editor:report.html", "p1"), place(panels, listOf(listOf("p1"))))
    }

    @Test
    fun `a copy outside the chat's panel is preferred over one behind the chat`() {
        val panels = listOf(panel("p1", listOf(chat, file("report.html")), chat.id), panel("p2", listOf(file("report.html"))))
        assertEquals(AiTabPlacement.Focus("editor:report.html", "p2"), place(panels, listOf(listOf("p1", "p2"))))
    }

    @Test
    fun `phone - the first panel, which then shows the tab, and an open tab is reused wherever it is`() {
        val panels = listOf(panel("p1", listOf(chat)), panel("p2", emptyList()))
        assertEquals(AiTabPlacement.Open("p1", split = false), place(panels, listOf(listOf("p1", "p2")), focusedPanelId = "p2", mobile = true))
        val open = listOf(panel("p1", listOf(chat)), panel("p2", listOf(file("report.html"))))
        assertEquals(AiTabPlacement.Focus("editor:report.html", "p2"), place(open, listOf(listOf("p1", "p2")), mobile = true))
    }

    @Test
    fun `a chat in a floating window - the focused grid panel, no split`() {
        val panels = listOf(panel("p1", listOf(file("a.ts"))), panel("p2", emptyList()))
        assertEquals(AiTabPlacement.Open("p2", split = false), place(panels, listOf(listOf("p1", "p2")), focusedPanelId = "p2"))
        assertEquals(AiTabPlacement.Open("p1", split = false), place(panels, listOf(listOf("p1", "p2")), focusedPanelId = "win:1"))
    }

    @Test
    fun `a design tab hosting the session is the chat`() {
        val design = WorkspaceTab("design:x", "design", "Design", null, meta("""{"sessionId":"s-1"}"""), true)
        assertEquals(AiTabPlacement.Open("p1", split = true), place(listOf(panel("p1", listOf(design))), listOf(listOf("p1"))))
    }

    @Test
    fun `no panel on the grid - nowhere to open`() {
        assertNull(place(emptyList(), listOf(listOf("gone"))))
    }

    @Test
    fun `a relative path matches only in the same project`() {
        assertTrue(isTabForFile(file("src/a.ts", "app"), "src/a.ts", "app"))
        assertFalse(isTabForFile(file("src/a.ts", "other"), "src/a.ts", "app"))
    }

    @Test
    fun `an absolute path matches whatever project the tab was opened from`() {
        val tmp = WorkspaceTab("editor:/tmp/r.html", "editor", "r.html", null, meta("""{"filePath":"/tmp/r.html"}"""), true)
        assertTrue(isTabForFile(tmp, "/tmp/r.html", null))
        assertTrue(isTabForFile(file("C:\\x\\r.html", "app"), "C:\\x\\r.html", null))
    }

    @Test
    fun `untitled buffers, inline viewers and other tab types never match`() {
        fun tab(type: String, extra: String) = WorkspaceTab("x", type, "a.ts", "app", meta("""{"filePath":"a.ts","projectName":"app"$extra}"""), true)
        assertFalse(isTabForFile(tab("editor", ""","isUntitled":true"""), "a.ts", "app"))
        assertFalse(isTabForFile(tab("editor", ""","viewerKey":"k""""), "a.ts", "app"))
        assertFalse(isTabForFile(tab("editor", ""","inlineContent":"""""), "a.ts", "app"))
        assertFalse(isTabForFile(tab("git-diff", ""), "a.ts", "app"))
    }

    // The store opening what the placement chose (open-ai-tab.ts `openAiTab`).

    private class MemoryStorage : LayoutStorage {
        override fun load(project: String): PanelLayout? = null

        override fun save(project: String, layout: PanelLayout) {}
    }

    private var ids = 0
    private var mobile = false
    private var now = 5_000L

    private fun store() = WorkspaceStore(MemoryStorage(), isMobile = { mobile }, now = { now }, randomId = { "r${++ids}" }).apply {
        switchProject("app")
        openTab(NewTab("chat", "Chat", "app", meta("""{"providerId":"claude","sessionId":"s-1"}""")))
    }

    @Test
    fun `a new file opens to the chat's right, at its line as code`() {
        val store = store()
        val id = store.openAiTab(AiTabTarget(OPEN_FILE_TOOL, "src/a.ts", "app", line = 42), session)
        val s = store.state.value
        assertEquals(2, s.gridPanelIds.size)
        val right = s.panels.getValue(s.gridPanelIds[1])
        assertEquals(id, right.activeTabId)
        val tab = right.activeTab!!
        assertEquals("editor:src/a.ts", tab.id)
        assertEquals("a.ts", tab.title)
        assertEquals("app", tab.projectId)
        assertEquals("src/a.ts", tab.metadata["filePath"].string)
        assertEquals("app", tab.metadata["projectName"].string)
        assertEquals("42", tab.metadata["lineNumber"].toString())
        assertEquals("5000", tab.metadata["revealAt"].toString())
        assertEquals("code", tab.metadata["aiView"].string)
        assertEquals("5000", tab.metadata["aiOpenAt"].toString())
        // The chat keeps its panel, on screen.
        assertEquals("chat", s.panels.getValue(s.gridPanelIds[0]).activeTab?.type)
    }

    @Test
    fun `a preview asks for the preview view, and an open tab is updated where it is`() {
        val store = store()
        store.openAiTab(AiTabTarget(OPEN_FILE_TOOL, "out/r.html", "app", line = 3), session)
        val id = store.openAiTab(AiTabTarget(OPEN_PREVIEW_TOOL, "out/r.html", "app"), session)
        val s = store.state.value
        assertEquals(2, s.gridPanelIds.size)
        val tab = s.panels.getValue(s.gridPanelIds[1]).tab(id)!!
        assertEquals("preview", tab.metadata["aiView"].string)
        // The line the first call asked for stays: only a call with a line moves it.
        assertEquals("3", tab.metadata["lineNumber"].toString())
    }

    @Test
    fun `a file outside the project opens with no project`() {
        val store = store()
        val id = store.openAiTab(AiTabTarget(OPEN_FILE_TOOL, "/tmp/notes.md", null), session)
        val tab = store.state.value.panelOf(id)!!.tab(id)!!
        assertNull(tab.projectId)
        assertNull(tab.metadata["projectName"])
        assertNull(tab.metadata["aiView"])
        assertNull(tab.metadata["lineNumber"])
    }

    @Test
    fun `a phone opens it in the one panel, over the chat`() {
        mobile = true
        val store = store()
        val id = store.openAiTab(AiTabTarget(OPEN_FILE_TOOL, "src/a.ts", "app"), session)
        val s = store.state.value
        assertEquals(1, s.gridPanelIds.size)
        assertEquals(id, s.panels.getValue(s.gridPanelIds[0]).activeTabId)
    }

    // Resolving the call's path (`resolveToolCallTarget`).

    private fun stat(kind: String = "file", resolve: (String) -> String = { it }): suspend (String) -> FsStat = { path ->
        FsStat(resolve(path), path.split('/').last(), kind)
    }

    @Test
    fun `a relative path is looked up under the project and kept relative`() = runTest {
        val asked = mutableListOf<String>()
        val target = resolveToolCallTarget(OPEN_FILE_TOOL, " src/a.ts ", 4, "app", "/home/u/app") { asked += it; FsStat(it, "a.ts", "file") }
        assertEquals(listOf("/home/u/app/src/a.ts"), asked)
        assertEquals(AiTabTarget(OPEN_FILE_TOOL, "src/a.ts", "app", 4), target)
    }

    @Test
    fun `a home path the host resolves inside the project becomes relative, outside it absolute`() = runTest {
        val inside = resolveToolCallTarget(OPEN_PREVIEW_TOOL, "~/app/r.html", null, "app", "/home/u/app", stat { "/home/u/app/r.html" })
        assertEquals(AiTabTarget(OPEN_PREVIEW_TOOL, "r.html", "app"), inside)
        val outside = resolveToolCallTarget(OPEN_PREVIEW_TOOL, "~/r.html", null, "app", "/home/u/app", stat { "/home/u/r.html" })
        assertEquals(AiTabTarget(OPEN_PREVIEW_TOOL, "/home/u/r.html", null), outside)
    }

    @Test
    fun `a folder, or a relative path with no project folder, is refused with the reason`() = runTest {
        try {
            resolveToolCallTarget(OPEN_FILE_TOOL, "src", null, "app", "/home/u/app", stat(kind = "directory"))
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("src is a folder", e.message)
        }
        try {
            resolveToolCallTarget(OPEN_FILE_TOOL, "src/a.ts", null, "app", "", stat())
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("this chat has no project folder", e.message)
        }
    }

    @Test
    fun `project paths as file-store joins and splits them`() {
        assertEquals("/a/b/c.ts", absoluteProjectPath("/a/b", "c.ts"))
        assertEquals("/a/b/c.ts", absoluteProjectPath("/a/b/", "c.ts"))
        assertEquals("C:\\p\\src\\a.ts", absoluteProjectPath("C:\\p", "src/a.ts"))
        assertEquals("src/a.ts", relativeProjectPath("C:\\p", "C:\\p\\src\\a.ts"))
        assertEquals("", relativeProjectPath("/a/b/", "/a/b"))
        assertNull(relativeProjectPath("/a/b", "/a/bc/d"))
    }

    // `open_url` (open-ai-tab.ts `openAiUrlTab`, web-preview-url.test.ts)

    private fun url(requestId: String = "q1", url: String = "https://devbox.ts.net:5173/", via: String = "tailscale") =
        TabOpenRequest.Url(requestId, url, 5173, via)

    @Test
    fun `a web server opens in a web-preview tab beside the chat, named as the host knows it`() {
        val store = store()
        val id = store.openAiUrlTab(url(), session, serverHost = "10.0.2.2")
        val s = store.state.value
        assertEquals(2, s.gridPanelIds.size)
        val tab = s.panels.getValue(s.gridPanelIds[1]).tab(id)!!
        assertEquals("web-preview" to "localhost:5173", tab.type to tab.title)
        assertNull(tab.projectId)
        assertEquals(meta("""{"url":"https://devbox.ts.net:5173/","port":5173,"via":"tailscale","aiOpenAt":5000}"""), tab.metadata.withoutStamp())
        assertEquals("chat", s.panels.getValue(s.gridPanelIds[0]).activeTab?.type)
    }

    @Test
    fun `the same address again reuses its tab, and new metadata reloads it from the start`() {
        val store = store()
        val first = store.openAiUrlTab(url(), session, serverHost = "10.0.2.2")
        store.updateTab(first) { it.copy(metadata = it.metadata.merged(mapOf("currentUrl" to JsonPrimitive("https://devbox.ts.net:5173/admin")))) }
        now = 6_000L
        assertEquals(first, store.openAiUrlTab(url(requestId = "q2"), session, serverHost = "10.0.2.2"))
        val s = store.state.value
        assertEquals(2, s.gridPanelIds.size)
        // What the page had navigated to is dropped with the rest.
        assertEquals(
            meta("""{"url":"https://devbox.ts.net:5173/","port":5173,"via":"tailscale","aiOpenAt":6000}"""),
            s.panelOf(first)!!.tab(first)!!.metadata.withoutStamp(),
        )
        // Another address is another tab.
        assertTrue(store.openAiUrlTab(url(url = "https://devbox.ts.net:3000/"), session, serverHost = "10.0.2.2") != first)
    }

    @Test
    fun `the host's own localhost opens only on the host`() {
        val local = url(url = "http://localhost:5173/", via = "local")
        try {
            store().openAiUrlTab(local, session, serverHost = "10.0.2.2")
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("this device is not the machine PPM runs on, so it cannot reach localhost:5173", e.message)
        }
        val onHost = store()
        val id = onHost.openAiUrlTab(local, session, serverHost = "127.0.0.1")
        assertEquals("web-preview", onHost.state.value.panelOf(id)!!.activeTab?.type)
        for (host in listOf("localhost", "127.0.0.1", "127.1.2.3", "::1", "[::1]")) assertTrue(host, browserOnHost(host))
        for (host in listOf("10.0.2.2", "192.168.1.20", "127.0.0", "localhost.example", "ppm.ts.net")) assertFalse(host, browserOnHost(host))
    }

    @Test
    fun `Branch Review opens beside the chat on the pair asked for, and a later call moves the tab already open`() {
        val store = store()
        val review = TabOpenRequest.BranchReview("q1", "app", null, base = "main", head = "feat/x")
        val id = store.openAiBranchReviewTab(review, session)
        val s = store.state.value
        assertEquals(2, s.gridPanelIds.size)
        val tab = s.panels.getValue(s.gridPanelIds[1]).tab(id)!!
        assertEquals(Triple("branch-review", "Branch Review", "app"), Triple(tab.type, tab.title, tab.projectId))
        assertEquals(meta("""{"projectName":"app","base":"main","head":"feat/x","aiOpenAt":5000}"""), tab.metadata.withoutStamp())
        assertEquals("chat", s.panels.getValue(s.gridPanelIds[0]).activeTab?.type)

        // The project's one Branch Review tab takes the new pair, whatever else its metadata holds.
        store.updateTab(id) { it.copy(metadata = it.metadata.merged(mapOf("note" to JsonPrimitive("kept")))) }
        now = 6_000L
        assertEquals(id, store.openAiBranchReviewTab(review.copy(requestId = "q2", base = "develop", head = "feat/y"), session))
        assertEquals(2, store.state.value.gridPanelIds.size)
        assertEquals(
            meta("""{"projectName":"app","base":"develop","head":"feat/y","aiOpenAt":6000,"note":"kept"}"""),
            store.state.value.panelOf(id)!!.tab(id)!!.metadata.withoutStamp(),
        )
    }

    /** The store stamps every tab it shows. */
    private fun JsonObject.withoutStamp() = JsonObject(filterKeys { it != "lastActiveAt" })

    private val app = "https://ppm.tail1234.ts.net".toHttpUrl()

    @Test
    fun `a preview loads a forward's http(s) address, never PPM's own origin or another scheme`() {
        assertEquals("https://devbox.tail1234.ts.net:5173/", safePreviewUrl("https://devbox.tail1234.ts.net:5173/", app))
        assertEquals("https://able-river.trycloudflare.com/", safePreviewUrl("https://able-river.trycloudflare.com", app))
        assertEquals("http://192.168.1.20:5173/", safePreviewUrl("http://192.168.1.20:5173/", app))
        assertNull(safePreviewUrl("javascript:alert(document.domain)", app))
        assertNull(safePreviewUrl("https://ppm.tail1234.ts.net/", app))
        assertNull(safePreviewUrl("https://ppm.tail1234.ts.net/api/settings", app))
        assertNull(safePreviewUrl("data:text/html,<script>1</script>", app))
        assertNull(safePreviewUrl("not a url", app))
        assertNull(safePreviewUrl(null, app))
        // Another port on PPM's host is another origin.
        assertEquals("https://ppm.tail1234.ts.net:5173/", safePreviewUrl("https://ppm.tail1234.ts.net:5173/", app))
    }

    @Test
    fun `a preview is titled as the host knows the server`() {
        assertEquals("localhost:5173", previewTitle(5173, "https://devbox.tail1234.ts.net:5173/"))
        assertEquals("able-river.trycloudflare.com", previewTitle(null, "https://able-river.trycloudflare.com/"))
        assertEquals("devbox:8443", previewTitle(0, "https://devbox:8443/x"))
        assertEquals("[::1]:3000", previewTitle(null, "http://[::1]:3000/"))
        assertEquals("not a url", previewTitle(null, "not a url"))
    }

    @Test
    fun `Branch Review keeps the repository the server named on its own tab, and a later call naming none drops it`() {
        val store = store()
        val review = TabOpenRequest.BranchReview("q1", "app", "/w/app/api", base = "main", head = "feat/x")
        val id = store.openAiBranchReviewTab(review, session)
        assertEquals(
            meta("""{"projectName":"app","base":"main","head":"feat/x","repo":"/w/app/api","aiOpenAt":5000}"""),
            store.state.value.panelOf(id)!!.tab(id)!!.metadata.withoutStamp(),
        )
        now = 6_000L
        store.openAiBranchReviewTab(review.copy(requestId = "q2", repo = null), session)
        assertEquals(
            meta("""{"projectName":"app","base":"main","head":"feat/x","aiOpenAt":6000}"""),
            store.state.value.panelOf(id)!!.tab(id)!!.metadata.withoutStamp(),
        )
    }
}
