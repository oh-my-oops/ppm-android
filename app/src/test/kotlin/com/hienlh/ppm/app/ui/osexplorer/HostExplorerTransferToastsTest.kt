package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.ui.test.junit4.createComposeRule
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.files.ExplorerRowDrag
import com.hienlh.ppm.app.ui.files.explorerEntryDrops
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.HostDirectory
import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.protocol.HostPlace
import com.hienlh.ppm.state.files.FileClipboardOperation
import com.hienlh.ppm.state.files.FileMutations
import com.hienlh.ppm.state.osexplorer.HostFileActions
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a window's Paste and its transfers say, which is what the web says: a toast for each item that did not go, in `transfer()`'s
 * words (explorer-actions-clipboard.ts), and for what did, "Moved 2 items" or "Copied 1 item", never "Pasted" (`pasteInto` and
 * `transferInto`). The project tree's blank area reports through the same function, so the same failures read the same in both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w600dp-h400dp")
class HostExplorerTransferToastsTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val toasts = CopyOnWriteArrayList<ErrorToastMessage>()
    private val session = explorerSessionOn(
        HostDirectory(
            listOf(HostFileEntry("old.txt", "/home/me/old.txt", "file", "file", size = 1)),
            "/home/me", "/home", listOf(HostPlace("me", "/home/me")), "/", truncated = false,
        ),
    )
    private lateinit var api: ApiClient
    private lateinit var files: FileMutations
    private lateinit var commands: HostExplorerCommands

    @Before
    fun start() {
        server.start()
        api = ApiClient(server.url("/"), "secret")
        files = FileMutations(api)
        commands = HostExplorerCommands(toasts::add, api, session, HostFileActions(api, files, "/", null), null, scope)
        compose.setContent {}
    }

    @After
    fun stop() {
        scope.cancel()
        server.close()
        session.close()
    }

    private fun ok(data: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{"ok":true,"data":""" + data + "}").build()

    private fun refused(code: String, status: Int = 409, hint: String? = null) = MockResponse.Builder().code(status).addHeader("Content-Type", "application/json")
        .body(buildJsonObject { put("ok", false); put("error", code); put("code", code); if (hint != null) put("hint", hint) }.toString()).build()

    private fun moved(source: String, destination: String) = ok(buildJsonObject { put("source", source); put("destination", destination) }.toString())

    private fun copiedButNotRemoved(source: String, destination: String) = ok(buildJsonObject {
        put("source", source); put("destination", destination); put("crossDevice", true); put("sourceRemoved", false)
    }.toString())

    private fun waitUntilDone() = compose.waitUntil(10_000) { !commands.busy }

    private fun titles() = toasts.map { it.title }

    @Test
    fun `a Paste of a copy says Copied, with the count, and nothing says Pasted`() {
        commands.core.setClipboard(listOf("/src/a.txt", "/src/b.txt"), FileClipboardOperation.COPY)
        server.enqueue(moved("/src/a.txt", "/home/me/a.txt")); server.enqueue(moved("/src/b.txt", "/home/me/b.txt"))
        commands.paste()
        waitUntilDone()
        assertEquals(listOf("Copied 2 items"), titles())
        assertEquals(ErrorToastMessage.Kind.SUCCESS, toasts.single().kind)
        assertNull(toasts.single().description)
    }

    @Test
    fun `a Paste of a cut says Moved, and the clipboard is gone once nothing failed`() {
        commands.core.setClipboard(listOf("/src/a.txt"), FileClipboardOperation.CUT)
        server.enqueue(moved("/src/a.txt", "/home/me/a.txt"))
        commands.paste()
        waitUntilDone()
        assertEquals(listOf("Moved 1 item"), titles())
        assertNull(files.state.value.clipboard)
    }

    @Test
    fun `a Paste where one item fails says so for that item first, then what went, and keeps the cut`() {
        commands.core.setClipboard(listOf("/src/a.txt", "/src/b.txt"), FileClipboardOperation.CUT)
        server.enqueue(moved("/src/a.txt", "/home/me/a.txt")); server.enqueue(refused("EACCES", 403, "Check host permissions"))
        commands.paste()
        waitUntilDone()
        assertEquals(listOf("Move failed: b.txt", "Moved 1 item"), titles())
        assertEquals(listOf("Check host permissions", null), toasts.map { it.description })
        assertEquals(listOf(ErrorToastMessage.Kind.ERROR, ErrorToastMessage.Kind.SUCCESS), toasts.map { it.kind })
        assertNotNull("a failure keeps the cut", files.state.value.clipboard)
    }

    @Test
    fun `a Paste where everything fails says nothing went, and says nothing about what did not`() {
        commands.core.setClipboard(listOf("/src/a.txt"), FileClipboardOperation.COPY)
        server.enqueue(refused("EACCES", 403, "Check host permissions"))
        commands.paste()
        waitUntilDone()
        assertEquals(listOf("Copy failed: a.txt"), titles())
    }

    @Test
    fun `a Paste with nothing on the clipboard says nothing`() {
        commands.paste()
        waitUntilDone()
        assertEquals(emptyList<ErrorToastMessage>(), toasts.toList())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a drop of items that fail is a toast for each of them, not one File operation failed`() {
        server.enqueue(refused("EACCES", 403, "Check host permissions")); server.enqueue(refused("ENOSPC", 507, "Disk full"))
        server.enqueue(moved("/src/c.txt", "/home/me/c.txt"))
        commands.transfer(listOf("/src/a.txt", "/src/b.txt", "/src/c.txt"), "/home/me", copy = false)
        waitUntilDone()
        assertEquals(listOf("Move failed: a.txt", "Move failed: b.txt", "Moved 1 item"), titles())
        assertEquals(listOf("Check host permissions", "Disk full", null), toasts.map { it.description })
    }

    @Test
    fun `a copy that fails says Copy failed`() {
        server.enqueue(refused("EACCES", 403, "Check host permissions"))
        commands.transfer(listOf("/src/a.txt"), "/home/me", copy = true)
        waitUntilDone()
        assertEquals(listOf("Copy failed: a.txt"), titles())
    }

    @Test
    fun `a move that copied but could not remove its source is reported by the window too`() {
        server.enqueue(copiedButNotRemoved("/src/a.txt", "/home/me/a.txt"))
        commands.transfer(listOf("/src/a.txt"), "/home/me", copy = false)
        waitUntilDone()
        assertEquals(listOf("Move failed: a.txt"), titles())
        assertEquals("Copied to /home/me/a.txt, but the source was not removed.", toasts.single().description)
    }

    /**
     * One script of server answers, every item failing so that no success is announced, played to the window's transfer and to the tree's
     * blank area: the toasts are the same, word for word, because one function makes them.
     */
    @Test
    fun `the same failures read the same in the window and on the tree's blank area`() {
        fun script() {
            server.enqueue(refused("EACCES", 403, "Check host permissions"))
            server.enqueue(copiedButNotRemoved("/home/me/src/b.txt", "/home/me/b.txt"))
            server.enqueue(refused("ENOSPC", 507, "Disk full"))
            server.enqueue(refused("EIO", 500))
        }
        script()
        commands.transfer(listOf("/home/me/src/a.txt", "/home/me/src/b.txt", "/home/me/src/c.txt", "/home/me/src/d.txt"), "/home/me", copy = false)
        waitUntilDone()
        val window = toasts.map { Triple(it.title, it.description, it.kind) }
        assertEquals(4, window.size)

        toasts.clear()
        script()
        val treeToasts = CopyOnWriteArrayList<ErrorToastMessage>()
        val drops = explorerEntryDrops(api, files, "/home/me", scope, copyHeld = { false }, toast = { treeToasts += it }, onChanged = {})
        val rows = listOf("src/a.txt", "src/b.txt", "src/c.txt", "src/d.txt")
        compose.runOnUiThread { drops.dropOnRoot(ExplorerRowDrag("p", rows.first(), false, rows, "/home/me")) }
        compose.waitUntil(10_000) { !drops.running }
        assertEquals(window, treeToasts.map { Triple(it.title, it.description, it.kind) })
        assertEquals(
            listOf(
                Triple("Move failed: a.txt", "Check host permissions", ErrorToastMessage.Kind.ERROR),
                Triple("Move failed: b.txt", "Copied to /home/me/b.txt, but the source was not removed.", ErrorToastMessage.Kind.ERROR),
                Triple("Move failed: c.txt", "Disk full", ErrorToastMessage.Kind.ERROR),
                Triple("Move failed: d.txt", "EIO", ErrorToastMessage.Kind.ERROR),
            ),
            window,
        )
    }
}
