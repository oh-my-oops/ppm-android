package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.HostDirectory
import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.protocol.HostPlace
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.files.FileMutations
import com.hienlh.ppm.state.osexplorer.HostFileActions
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The questions a window's own transfers and deletes ask, through the dialogs its commands host: a name that is taken (Skip, Keep both
 * or Replace, for this item or the rest of the batch) and a delete that cannot go to a Trash. The same two dialogs the project tree's
 * drops ask with, held by one owner (`HostTransferPromptHost`), so a window that stops hosting them stops asking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w600dp-h400dp")
class HostExplorerCommandsPromptsTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val toasts = CopyOnWriteArrayList<ErrorToastMessage>()
    private val old = HostFileEntry("old.txt", "/home/me/old.txt", "file", "file", size = 1)
    private val session = explorerSessionOn(
        HostDirectory(listOf(old), "/home/me", "/home", listOf(HostPlace("me", "/home/me")), "/", truncated = false),
    )
    private var shown by mutableStateOf(true)
    private lateinit var commands: HostExplorerCommands

    @Before
    fun start() {
        server.start()
        val api = ApiClient(server.url("/"), "secret")
        commands = HostExplorerCommands(toasts::add, api, session, HostFileActions(api, FileMutations(api), "/", null), null, scope)
        compose.setContent {
            if (shown) {
                DisposableEffect(commands) {
                    commands.attach()
                    onDispose { commands.detach() }
                }
                commands.Dialogs()
            }
        }
        compose.waitForIdle()
    }

    @After
    fun stop() {
        scope.cancel()
        server.close()
        session.close()
    }

    private fun ok(data: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{"ok":true,"data":""" + data + "}").build()

    private fun refused(code: String, status: Int = 409) = MockResponse.Builder().code(status).addHeader("Content-Type", "application/json")
        .body(buildJsonObject { put("ok", false); put("error", code); put("code", code) }.toString()).build()

    private fun moved(source: String, destination: String) = ok(buildJsonObject { put("source", source); put("destination", destination) }.toString())

    private fun waitFor(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun isShown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun waitUntilDone() = compose.waitUntil(10_000) { !commands.busy }

    private fun click(text: String) {
        compose.onNodeWithText(text).performClick()
        compose.waitForIdle()
    }

    /** Back, or a tap outside the box: how a dialog is closed without choosing in it. */
    private fun pressBackInDialog() {
        compose.runOnUiThread { checkNotNull(ShadowDialog.getLatestDialog()).onBackPressed() }
        compose.waitForIdle()
    }

    private fun assertRequest(method: String, target: String, body: String? = null) {
        val request = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)) { "no request, $method $target was expected" }
        assertEquals(method, request.method)
        assertEquals(target, request.target)
        if (body != null) assertEquals(WireJson.parse(body), WireJson.parse(request.body!!.utf8()))
    }

    private val taken = "A file named “a.txt” already exists"

    @Test
    fun `a name that is taken asks in the window's dialog, and Skip leaves the item where it is`() {
        server.enqueue(refused("EEXIST"))
        commands.transfer(listOf("/src/a.txt"), "/home/me", copy = false)
        waitFor(taken)
        assertTrue(isShown("/home/me/a.txt"))
        assertTrue(isShown("Replace moves the existing item to Trash. Keep both saves the copy with a new name."))
        click("Skip")
        waitUntilDone()
        assertEquals(1, server.requestCount)
        assertEquals(emptyList<ErrorToastMessage>(), toasts.toList())
        assertFalse("the dialog is gone", isShown(taken))
    }

    @Test
    fun `Apply to all keeps one answer for the rest of the batch, and the next batch asks again`() {
        server.enqueue(refused("EEXIST"))
        server.enqueue(refused("ENOENT", 404))
        server.enqueue(moved("/src/a.txt", "/home/me/a (2).txt"))
        server.enqueue(refused("EEXIST"))
        server.enqueue(refused("ENOENT", 404))
        server.enqueue(moved("/src/b.txt", "/home/me/b (2).txt"))
        commands.transfer(listOf("/src/a.txt", "/src/b.txt"), "/home/me", copy = true)
        waitFor(taken)
        click("☐ Apply to all")
        click("Keep both")
        waitUntilDone()
        assertFalse("b.txt was not asked about", isShown("A file named “b.txt” already exists"))
        assertRequest("POST", "/api/fs/copy", """{"source":"/src/a.txt","destination":"/home/me/a.txt"}""")
        assertRequest("GET", "/api/fs/stat?path=%2Fhome%2Fme%2Fa%20%282%29.txt")
        assertRequest("POST", "/api/fs/copy", """{"source":"/src/a.txt","destination":"/home/me/a (2).txt"}""")
        assertRequest("POST", "/api/fs/copy", """{"source":"/src/b.txt","destination":"/home/me/b.txt"}""")
        assertRequest("GET", "/api/fs/stat?path=%2Fhome%2Fme%2Fb%20%282%29.txt")
        assertRequest("POST", "/api/fs/copy", """{"source":"/src/b.txt","destination":"/home/me/b (2).txt"}""")
        assertEquals(listOf("Copied 2 items"), toasts.map { it.title })

        server.enqueue(refused("EEXIST"))
        commands.transfer(listOf("/src/a.txt"), "/home/me", copy = true)
        waitFor(taken)
        click("Skip")
        waitUntilDone()
    }

    @Test
    fun `a delete for good asks first, and Cancel sends nothing`() {
        commands.permanentlyDelete(listOf(old))
        waitFor("Delete permanently?")
        assertTrue(isShown("These items will be permanently deleted. This cannot be undone."))
        assertTrue(isShown("old.txt"))
        click("Cancel")
        waitUntilDone()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `Delete permanently sends the delete, and a host with no Trash asks the same way`() {
        server.enqueue(ok("""{"removed":"/home/me/old.txt","permanent":true,"trashed":false}"""))
        commands.permanentlyDelete(listOf(old))
        waitFor("Delete permanently?")
        click("Delete permanently")
        waitUntilDone()
        assertRequest("DELETE", "/api/fs/delete", """{"path":"/home/me/old.txt","permanent":true}""")

        server.enqueue(refused("NO_TRASH", 400))
        server.enqueue(ok("""{"removed":"/home/me/old.txt","permanent":true,"trashed":false}"""))
        commands.trash(listOf(old))
        waitFor("This host has no Trash")
        click("Delete permanently")
        waitUntilDone()
        assertRequest("DELETE", "/api/fs/delete", """{"path":"/home/me/old.txt","permanent":false}""")
        assertRequest("DELETE", "/api/fs/delete", """{"path":"/home/me/old.txt","permanent":true}""")
    }

    @Test
    fun `closing a question without an answer skips the item, or declines the delete`() {
        server.enqueue(refused("EEXIST"))
        commands.transfer(listOf("/src/a.txt"), "/home/me", copy = false)
        waitFor(taken)
        pressBackInDialog()
        waitUntilDone()
        assertEquals(1, server.requestCount)
        assertFalse("the dialog is gone", isShown(taken))

        commands.permanentlyDelete(listOf(old))
        waitFor("Delete permanently?")
        pressBackInDialog()
        waitUntilDone()
        assertEquals("no delete was sent", 1, server.requestCount)
    }

    @Test
    fun `a window that stops hosting the dialogs declines the delete that waits`() {
        commands.permanentlyDelete(listOf(old))
        waitFor("Delete permanently?")
        compose.runOnUiThread { shown = false }
        compose.waitForIdle()
        waitUntilDone()
        assertEquals("nothing was deleted", 0, server.requestCount)
    }

    @Test
    fun `a window that stops hosting the dialogs skips what waits and asks no more`() {
        server.enqueue(refused("EEXIST"))
        commands.transfer(listOf("/src/a.txt"), "/home/me", copy = false)
        waitFor(taken)
        compose.runOnUiThread { shown = false }
        compose.waitForIdle()
        waitUntilDone()
        assertEquals(1, server.requestCount)

        server.enqueue(refused("EEXIST"))
        commands.transfer(listOf("/src/a.txt"), "/home/me", copy = false)
        waitUntilDone()
        assertFalse("nothing to show it in", isShown(taken))
        assertEquals(2, server.requestCount)

        commands.permanentlyDelete(listOf(old))
        waitUntilDone()
        assertEquals("nothing to ask in, so nothing is deleted for good", 2, server.requestCount)
    }
}
