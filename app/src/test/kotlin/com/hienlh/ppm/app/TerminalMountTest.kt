package com.hienlh.ppm.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.state.workspace.DOCK_PANEL_ID
import com.hienlh.ppm.state.workspace.DockState
import com.hienlh.ppm.state.workspace.WorkspacePanel
import com.hienlh.ppm.state.workspace.WorkspaceState
import com.hienlh.ppm.state.workspace.WorkspaceTab
import com.hienlh.ppm.state.workspace.windowPanelId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Which shells the app attaches to (`AppModel.syncTerminals`), against a server that counts the sockets it is given. The web mounts the
 * front tab of every panel whether its panel is on screen or not (tab-pool.tsx): a dock that is collapsed on a phone or hidden on a
 * tablet, a minimized window, another project's grid. The app used to make a terminal only when its view was composed, so a shell in
 * any of those was never attached after the app started, and the server ended it once the 30 minutes it keeps a shell with no socket
 * had run out. The web's rule has the same hole one step further: a tab behind another is not mounted until it is first in front, so
 * after a reload it has no socket either, is ended by the same 30 minutes, and the next visit opens a new shell in its place. The app
 * attaches the shell of every terminal tab the panels hold, behind another tab or not. A shell that is attached stays so while its tab
 * is behind another, in another panel or in another project, as the web's keep-alive holds it, and goes with its tab.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalMountTest {
    private class Peer : WebSocketListener() {
        val closing = AtomicInteger()
        val received = CopyOnWriteArrayList<String>()
        override fun onOpen(webSocket: WebSocket, response: Response) {}
        override fun onMessage(webSocket: WebSocket, text: String) { received += text }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closing.incrementAndGet()
            webSocket.close(1000, null)
        }
    }

    private val server = MockWebServer()
    private val peer = Peer()
    private lateinit var model: AppModel

    @Before
    fun setUp() {
        repeat(10) { server.enqueue(MockResponse.Builder().webSocketUpgrade(peer).build()) }
        server.start()
        model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        model.api = ApiClient(server.url("/"), "secret")
    }

    @After
    fun tearDown() {
        // No terminal tab is left, so every shell is ended with its socket.
        model.syncTerminals(state(listOf(panel("p1", editor("editor:end")))))
        server.close()
    }

    /** A shell that says which one it is in its URL, so a socket can be told from another. */
    private fun terminal(id: String, project: String? = "ppm") = WorkspaceTab(
        id, "terminal", "Terminal", project,
        JsonObject(buildMap {
            put("sessionId", JsonPrimitive("shell-of-$id"))
            if (!project.isNullOrEmpty()) put("projectName", JsonPrimitive(project))
        }),
    )

    private fun editor(id: String) = WorkspaceTab(id, "editor", id, "ppm")

    private fun panel(id: String, vararg tabs: WorkspaceTab, front: WorkspaceTab? = tabs.firstOrNull()) =
        WorkspacePanel(id, tabs.toList(), front?.id)

    private fun state(
        vararg grid: List<WorkspacePanel>,
        dock: WorkspacePanel? = null,
        dockVisible: Boolean = false,
        windows: Map<String, WorkspacePanel> = emptyMap(),
        kept: Map<String, List<WorkspacePanel>> = emptyMap(),
    ) = WorkspaceState(
        panels = (grid.toList().flatten() + kept.values.flatten() + listOfNotNull(dock?.copy(id = DOCK_PANEL_ID)) +
            windows.map { (id, panel) -> panel.copy(id = windowPanelId(id)) }).associateBy { it.id },
        grid = grid.map { row -> row.map { it.id } },
        focusedPanelId = grid.first().first().id,
        currentProject = "ppm",
        dock = DockState(visible = dockVisible, height = 30.0),
        projectGrids = kept.mapValues { (_, panels) -> panels.map { listOf(it.id) } },
    )

    /** The shells that have reached the server, once there are [count] of them. */
    private fun attached(count: Int): List<String> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (server.requestCount < count && System.nanoTime() < deadline) Thread.sleep(10)
        return (1..count).map { server.takeRequest(5, TimeUnit.SECONDS)!!.url.pathSegments.last() }.sorted()
    }

    /** Nothing more has come after a moment in which a socket would have: the one test of a shell that is not attached. */
    private fun assertNoMoreThan(count: Int) {
        Thread.sleep(400)
        assertEquals(count, server.requestCount)
    }

    @Test
    fun `a collapsed phone dock with three terminals attaches all three when the workspace loads, the one in front and the two behind it`() {
        val third = terminal("terminal:3")
        model.syncTerminals(
            state(
                listOf(panel("p1", editor("editor:x"))),
                dock = panel("d", terminal("terminal:1"), terminal("terminal:2"), third, front = third),
                dockVisible = false,
            ),
        )
        assertEquals(listOf("shell-of-terminal:1", "shell-of-terminal:2", "shell-of-terminal:3"), attached(3))
        assertNoMoreThan(3)
        // Attached, not shown: not one of them says anything of a size or anything else to its shell.
        assertTrue(peer.received.isEmpty())
    }

    @Test
    fun `a hidden dock's terminal on a tablet is attached too`() {
        model.setWindowWide(true)
        model.syncTerminals(state(listOf(panel("p1", editor("editor:x"))), dock = panel("d", terminal("terminal:1")), dockVisible = false))
        assertEquals(listOf("shell-of-terminal:1"), attached(1))
        assertNoMoreThan(1)
    }

    @Test
    fun `a minimized window's terminal is attached`() {
        model.setWindowWide(true)
        model.syncTerminals(state(listOf(panel("p1", editor("editor:x"))), windows = mapOf("w1" to panel("w", terminal("terminal:1")))))
        assertEquals(listOf("shell-of-terminal:1"), attached(1))
        assertNoMoreThan(1)
    }

    @Test
    fun `every terminal of every panel is attached, in front or behind another tab, in a project seen earlier, a window and the dock`() {
        model.syncTerminals(
            state(
                listOf(
                    panel("p1", terminal("terminal:1"), editor("editor:x"), terminal("terminal:2")),
                    panel("p2", editor("editor:y"), terminal("terminal:3")),
                ),
                dock = panel("d", terminal("terminal:4"), terminal("terminal:5")),
                windows = mapOf("w1" to panel("w", terminal("terminal:6"), terminal("terminal:7"))),
                kept = mapOf("other" to listOf(panel("x", terminal("terminal:8", "other"), terminal("terminal:9", "other")))),
            ),
        )
        assertEquals((1..9).map { "shell-of-terminal:$it" }, attached(9))
        assertNoMoreThan(9)
    }

    @Test
    fun `a terminal behind another tab is attached at load, and coming to the front attaches nothing again`() {
        val behind = terminal("terminal:1")
        model.syncTerminals(state(listOf(panel("p1", editor("editor:x"), behind))))
        assertEquals(listOf("shell-of-terminal:1"), attached(1))
        val first = model.terminalOf("terminal:1")
        assertNotNull(first)
        // It comes to the front: the same shell, and no second socket.
        model.syncTerminals(state(listOf(panel("p1", editor("editor:x"), behind, front = behind))))
        assertSame(first, model.terminalOf("terminal:1"))
        assertNoMoreThan(1)
        assertEquals(0, peer.closing.get())
    }

    @Test
    fun `a terminal of another project in the open project's grid is not attached, as the web's grid does not hold it`() {
        model.syncTerminals(state(listOf(panel("p1", terminal("terminal:1", "other"), editor("editor:x")))))
        assertNoMoreThan(0)
        assertNull(model.terminalOf("terminal:1"))
    }

    @Test
    fun `the same layout synced again attaches nothing twice`() {
        val layout = state(listOf(panel("p1", terminal("terminal:1"), terminal("terminal:2"))), dock = panel("d", terminal("terminal:3")))
        model.syncTerminals(layout)
        assertEquals(listOf("shell-of-terminal:1", "shell-of-terminal:2", "shell-of-terminal:3"), attached(3))
        val shells = (1..3).map { model.terminalOf("terminal:$it") }
        repeat(3) { model.syncTerminals(layout) }
        assertEquals(shells, (1..3).map { model.terminalOf("terminal:$it") })
        assertNoMoreThan(3)
        assertEquals(0, peer.closing.get())
    }

    @Test
    fun `the shell of a terminal behind another tab ends with its tab, and the other shell stays`() {
        val front = terminal("terminal:1")
        val behind = terminal("terminal:2")
        model.syncTerminals(state(listOf(panel("p1", front, behind))))
        assertEquals(listOf("shell-of-terminal:1", "shell-of-terminal:2"), attached(2))
        // The tab behind closes.
        model.syncTerminals(state(listOf(panel("p1", front))))
        assertNull(model.terminalOf("terminal:2"))
        assertNotNull(model.terminalOf("terminal:1"))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (peer.closing.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(1, peer.closing.get())
        // The one that stays is not given a second socket, and it is not the one that was closed.
        assertNoMoreThan(2)
        assertEquals(1, peer.closing.get())
    }

    @Test
    fun `an attached shell stays so, the same one, when its tab goes behind another, to another panel or to another project, and goes with its tab`() {
        val shell = terminal("terminal:1")
        val other = editor("editor:x")
        model.syncTerminals(state(listOf(panel("p1", shell, other))))
        assertEquals(listOf("shell-of-terminal:1"), attached(1))
        val first = model.terminalOf("terminal:1")
        assertNotNull(first)

        // Behind another tab.
        model.syncTerminals(state(listOf(panel("p1", shell, other, front = other))))
        assertSame(first, model.terminalOf("terminal:1"))
        // In a panel of its own beside it, and then in a dock nobody has open.
        model.syncTerminals(state(listOf(panel("p1", other), panel("p2", shell, editor("editor:y"), front = editor("editor:y")))))
        assertSame(first, model.terminalOf("terminal:1"))
        model.syncTerminals(state(listOf(panel("p1", other)), dock = panel("d", shell, front = null), dockVisible = false))
        assertSame(first, model.terminalOf("terminal:1"))
        // In the grid a project switch kept, behind that project's other tab.
        model.syncTerminals(state(listOf(panel("p1", other)), kept = mapOf("other" to listOf(panel("x", shell, editor("editor:z"), front = editor("editor:z"))))))
        assertSame(first, model.terminalOf("terminal:1"))

        // Not one of those moves attached it again or ended it.
        assertNoMoreThan(1)
        assertEquals(0, peer.closing.get())

        // Its tab closes: the shell's socket is closed with it.
        model.syncTerminals(state(listOf(panel("p1", other))))
        assertNull(model.terminalOf("terminal:1"))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (peer.closing.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(1, peer.closing.get())
    }
}
