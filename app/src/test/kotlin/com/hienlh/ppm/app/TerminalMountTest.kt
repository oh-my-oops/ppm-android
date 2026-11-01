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
 * had run out. A shell that is attached stays so while its tab is behind another, in another panel or in another project, as the
 * web's keep-alive holds it, and goes with its tab.
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
    fun `a collapsed phone dock's terminal is attached when the workspace loads, and a terminal behind it is not`() {
        model.syncTerminals(
            state(
                listOf(panel("p1", editor("editor:x"))),
                dock = panel("d", terminal("terminal:1"), terminal("terminal:2")),
                dockVisible = false,
            ),
        )
        assertEquals(listOf("shell-of-terminal:1"), attached(1))
        assertNoMoreThan(1)
        // Attached, not shown: it says nothing of a size or anything else to the shell.
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
    fun `the front terminal of every panel is attached, those of a project seen earlier included`() {
        model.syncTerminals(
            state(
                listOf(panel("p1", terminal("terminal:1"), editor("editor:x")), panel("p2", editor("editor:y"), terminal("terminal:2"))),
                kept = mapOf("other" to listOf(panel("x", terminal("terminal:3", "other"), terminal("terminal:4", "other")))),
            ),
        )
        assertEquals(listOf("shell-of-terminal:1", "shell-of-terminal:3"), attached(2))
        assertNoMoreThan(2)
    }

    @Test
    fun `a terminal that has not yet been in front is not attached until it is`() {
        val behind = terminal("terminal:1")
        model.syncTerminals(state(listOf(panel("p1", editor("editor:x"), behind))))
        assertNoMoreThan(0)
        assertNull(model.terminalOf("terminal:1"))
        // It comes to the front.
        model.syncTerminals(state(listOf(panel("p1", editor("editor:x"), behind, front = behind))))
        assertEquals(listOf("shell-of-terminal:1"), attached(1))
        assertNotNull(model.terminalOf("terminal:1"))
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
