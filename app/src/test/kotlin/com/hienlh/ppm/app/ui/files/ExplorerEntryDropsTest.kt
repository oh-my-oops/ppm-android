package com.hienlh.ppm.app.ui.files

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
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.files.FileMutationReport
import com.hienlh.ppm.state.files.FileMutations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What a drop on the tree's blank area does end to end, against a server that answers as asked: the requests the shared transfer
 * sends (a move, or a copy with Ctrl or Alt), the dialogs it asks with when a name is taken or the host has no Trash, the toast
 * for what did not go, and the silence about what did. The project folder is [ROOT].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class ExplorerEntryDropsTest {
    companion object {
        const val ROOT = "/home/me/p"
        const val TAKEN = "A file named “app.kt” already exists"
    }

    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val toasts = CopyOnWriteArrayList<ErrorToastMessage>()
    private val changes = CopyOnWriteArrayList<FileMutationReport>()
    private var copyHeld = false
    private var shown by mutableStateOf(true)
    private lateinit var drops: ExplorerEntryDrops

    @Before fun startServer() = server.start()

    @After fun stopServer() {
        scope.cancel()
        server.close()
    }

    private fun ok(data: String) = MockResponse.Builder().addHeader("Content-Type", "application/json")
        .body("""{"ok":true,"data":""" + data + "}").build()

    private fun failure(code: String, status: Int = 409, hint: String? = null) = MockResponse.Builder().code(status)
        .addHeader("Content-Type", "application/json").body(buildJsonObject {
            put("ok", false); put("error", code); put("code", code)
            if (hint != null) put("hint", hint)
        }.toString()).build()

    private fun transferred(source: String, destination: String) = ok(buildJsonObject { put("source", source); put("destination", destination) }.toString())

    private fun exists(path: String) = ok(buildJsonObject { put("path", path); put("kind", "file") }.toString())

    /** A cross-device move that wrote the destination and could not remove the source. */
    private fun copiedButNotRemoved(source: String, destination: String) = ok(buildJsonObject {
        put("source", source); put("destination", destination); put("crossDevice", true); put("sourceRemoved", false)
    }.toString())

    /** A server on which every name is taken: a stat of any path finds it, and a move or a copy to the name it wanted is refused. */
    private fun everyNameTaken() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.target!!.startsWith("/api/fs/stat")) exists("/taken") else failure("EEXIST")
        }
    }

    private fun trashed(path: String, permanent: Boolean = false) =
        ok(buildJsonObject { put("removed", path); put("permanent", permanent); put("trashed", !permanent) }.toString())

    private fun enqueue(vararg responses: MockResponse) = responses.forEach(server::enqueue)

    private fun show(root: String = ROOT, onChanged: (FileMutationReport) -> Unit = { changes += it }) {
        val api = ApiClient(server.url("/"), "secret")
        drops = explorerEntryDrops(api, FileMutations(api), root, scope, copyHeld = { copyHeld }, toast = { toasts += it }, onChanged = onChanged)
        compose.setContent {
            if (shown) {
                DisposableEffect(drops) {
                    drops.attach()
                    onDispose { drops.detach() }
                }
                drops.Dialogs()
            }
        }
        compose.waitForIdle()
    }

    /** The rows of the project let go on the blank area, as project-relative paths. */
    private fun drop(vararg paths: String): Boolean = drops.dropOnRoot(ExplorerRowDrag("p", paths.first(), false, paths.toList(), drops.root))

    private fun waitUntilDone() = compose.waitUntil(10_000) { !drops.running }

    private fun waitFor(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun isShown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

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

    @Test fun `rows dropped on the blank area are moved into the project folder, with nothing said about it`() {
        enqueue(transferred("$ROOT/src/app.kt", "$ROOT/app.kt"), transferred("$ROOT/docs/guide.md", "$ROOT/guide.md"))
        show()
        assertTrue(drop("src/app.kt", "docs/guide.md"))
        waitUntilDone()
        assertRequest("POST", "/api/fs/move", """{"source":"$ROOT/src/app.kt","destination":"$ROOT/app.kt"}""")
        assertRequest("POST", "/api/fs/move", """{"source":"$ROOT/docs/guide.md","destination":"$ROOT/guide.md"}""")
        assertEquals(emptyList<ErrorToastMessage>(), toasts.toList())
        assertEquals(setOf(ROOT, "$ROOT/src", "$ROOT/docs"), changes.single().affectedDirectories)
    }

    @Test fun `with Ctrl or Alt held they are copied`() {
        copyHeld = true
        enqueue(transferred("$ROOT/src/app.kt", "$ROOT/app.kt"))
        show()
        assertTrue(drop("src/app.kt"))
        waitUntilDone()
        assertRequest("POST", "/api/fs/copy", """{"source":"$ROOT/src/app.kt","destination":"$ROOT/app.kt"}""")
        assertEquals(emptyList<ErrorToastMessage>(), toasts.toList())
    }

    @Test fun `a copy of a row of the folder itself is saved under the next free name`() {
        copyHeld = true
        enqueue(failure("ENOENT", 404), transferred("$ROOT/README.md", "$ROOT/README (2).md"))
        show()
        assertTrue(drop("README.md"))
        waitUntilDone()
        assertRequest("GET", "/api/fs/stat?path=%2Fhome%2Fme%2Fp%2FREADME%20%282%29.md")
        assertRequest("POST", "/api/fs/copy", """{"source":"$ROOT/README.md","destination":"$ROOT/README (2).md"}""")
        assertFalse("no prompt for a name of our own making", isShown("Skip"))
    }

    @Test fun `a name that is taken asks first, and Skip leaves both where they are`() {
        enqueue(failure("EEXIST"))
        show()
        assertTrue(drop("src/app.kt"))
        waitFor(TAKEN)
        assertTrue(isShown("$ROOT/app.kt"))
        assertTrue(isShown("Replace moves the existing item to Trash. Keep both saves the copy with a new name."))
        click("Skip")
        waitUntilDone()
        assertRequest("POST", "/api/fs/move")
        assertEquals("nothing was sent after the answer", 1, server.requestCount)
        assertEquals(emptyList<ErrorToastMessage>(), toasts.toList())
        assertFalse("the dialog is gone", isShown(TAKEN))
    }

    @Test fun `Keep both saves it under the next free name`() {
        enqueue(failure("EEXIST"), failure("ENOENT", 404), transferred("$ROOT/src/app.kt", "$ROOT/app (2).kt"))
        show()
        assertTrue(drop("src/app.kt"))
        waitFor(TAKEN)
        click("Keep both")
        waitUntilDone()
        assertRequest("POST", "/api/fs/move", """{"source":"$ROOT/src/app.kt","destination":"$ROOT/app.kt"}""")
        assertRequest("GET", "/api/fs/stat?path=%2Fhome%2Fme%2Fp%2Fapp%20%282%29.kt")
        assertRequest("POST", "/api/fs/move", """{"source":"$ROOT/src/app.kt","destination":"$ROOT/app (2).kt"}""")
    }

    @Test fun `Replace sends the one that was there to the Trash first`() {
        enqueue(failure("EEXIST"), trashed("$ROOT/app.kt"), transferred("$ROOT/src/app.kt", "$ROOT/app.kt"))
        show()
        assertTrue(drop("src/app.kt"))
        waitFor(TAKEN)
        click("Replace")
        waitUntilDone()
        assertRequest("POST", "/api/fs/move")
        assertRequest("DELETE", "/api/fs/delete", """{"path":"$ROOT/app.kt","permanent":false}""")
        assertRequest("POST", "/api/fs/move", """{"source":"$ROOT/src/app.kt","destination":"$ROOT/app.kt"}""")
        assertEquals(emptyList<ErrorToastMessage>(), toasts.toList())
    }

    @Test fun `Apply to all answers the rest of the drop the same way, and is not kept for the next drop`() {
        enqueue(
            failure("EEXIST"), failure("ENOENT", 404), transferred("$ROOT/src/app.kt", "$ROOT/app (2).kt"),
            failure("EEXIST"), failure("ENOENT", 404), transferred("$ROOT/src/main.kt", "$ROOT/main (2).kt"),
        )
        show()
        assertTrue(drop("src/app.kt", "src/main.kt"))
        waitFor(TAKEN)
        assertTrue(isShown("☐ Apply to all"))
        click("☐ Apply to all")
        assertTrue(isShown("☑ Apply to all"))
        click("Keep both")
        waitUntilDone()
        assertFalse("main.kt was not asked about", isShown("A file named “main.kt” already exists"))
        assertRequest("POST", "/api/fs/move")
        assertRequest("GET", "/api/fs/stat?path=%2Fhome%2Fme%2Fp%2Fapp%20%282%29.kt")
        assertRequest("POST", "/api/fs/move", """{"source":"$ROOT/src/app.kt","destination":"$ROOT/app (2).kt"}""")
        assertRequest("POST", "/api/fs/move", """{"source":"$ROOT/src/main.kt","destination":"$ROOT/main.kt"}""")
        assertRequest("GET", "/api/fs/stat?path=%2Fhome%2Fme%2Fp%2Fmain%20%282%29.kt")
        assertRequest("POST", "/api/fs/move", """{"source":"$ROOT/src/main.kt","destination":"$ROOT/main (2).kt"}""")

        enqueue(failure("EEXIST"))
        assertTrue(drop("src/app.kt"))
        waitFor(TAKEN)
        click("Skip")
        waitUntilDone()
    }

    @Test fun `without Apply to all every item that collides is asked about`() {
        enqueue(failure("EEXIST"), failure("EEXIST"))
        show()
        assertTrue(drop("src/app.kt", "src/main.kt"))
        waitFor(TAKEN)
        click("Skip")
        waitFor("A file named “main.kt” already exists")
        click("Skip")
        waitUntilDone()
        assertEquals(2, server.requestCount)
    }

    @Test fun `closing the question without an answer is a Skip`() {
        enqueue(failure("EEXIST"))
        show()
        assertTrue(drop("src/app.kt"))
        waitFor(TAKEN)
        pressBackInDialog()
        waitUntilDone()
        assertEquals("nothing was sent after the question", 1, server.requestCount)
        assertFalse("the dialog is gone", isShown(TAKEN))
    }

    @Test fun `closing the Trash question without an answer deletes nothing`() {
        enqueue(failure("EEXIST"), failure("NO_TRASH", 400))
        show()
        assertTrue(drop("src/app.kt"))
        waitFor(TAKEN)
        click("Replace")
        waitFor("This host has no Trash")
        pressBackInDialog()
        waitUntilDone()
        assertEquals("the move and the Trash, and no permanent delete", 2, server.requestCount)
    }

    @Test fun `a host with no Trash asks before it overwrites for good, and Cancel leaves the file`() {
        enqueue(failure("EEXIST"), failure("NO_TRASH", 400))
        show()
        assertTrue(drop("src/app.kt"))
        waitFor(TAKEN)
        click("Replace")
        waitFor("This host has no Trash")
        assertTrue(isShown("These items will be permanently deleted. This cannot be undone."))
        assertTrue("the name of what would be deleted", isShown("app.kt"))
        click("Cancel")
        waitUntilDone()
        assertRequest("POST", "/api/fs/move")
        assertRequest("DELETE", "/api/fs/delete", """{"path":"$ROOT/app.kt","permanent":false}""")
        assertEquals("nothing was sent after the answer", 2, server.requestCount)
        assertEquals(emptyList<ErrorToastMessage>(), toasts.toList())
    }

    @Test fun `Delete permanently removes the one that was there, then the drop goes through`() {
        enqueue(failure("EEXIST"), failure("NO_TRASH", 400), trashed("$ROOT/app.kt", permanent = true), transferred("$ROOT/src/app.kt", "$ROOT/app.kt"))
        show()
        assertTrue(drop("src/app.kt"))
        waitFor(TAKEN)
        click("Replace")
        waitFor("This host has no Trash")
        click("Delete permanently")
        waitUntilDone()
        assertRequest("POST", "/api/fs/move")
        assertRequest("DELETE", "/api/fs/delete", """{"path":"$ROOT/app.kt","permanent":false}""")
        assertRequest("DELETE", "/api/fs/delete", """{"path":"$ROOT/app.kt","permanent":true}""")
        assertRequest("POST", "/api/fs/move", """{"source":"$ROOT/src/app.kt","destination":"$ROOT/app.kt"}""")
        assertEquals(emptyList<ErrorToastMessage>(), toasts.toList())
    }

    @Test fun `a row the server refuses is a toast with what the server said, and the others still go`() {
        enqueue(failure("EACCES", 403, "Check host permissions"), transferred("$ROOT/docs/guide.md", "$ROOT/guide.md"))
        show()
        assertTrue(drop("src/app.kt", "docs/guide.md"))
        waitUntilDone()
        val toast = toasts.single()
        assertEquals("Move failed: app.kt", toast.title)
        assertEquals("Check host permissions", toast.description)
        assertEquals(ErrorToastMessage.Kind.ERROR, toast.kind)
        assertRequest("POST", "/api/fs/move")
        assertRequest("POST", "/api/fs/move", """{"source":"$ROOT/docs/guide.md","destination":"$ROOT/guide.md"}""")
        assertEquals("the folders are refreshed all the same", setOf(ROOT, "$ROOT/src", "$ROOT/docs"), changes.single().affectedDirectories)
    }

    @Test fun `a copy the server refuses says Copy failed`() {
        copyHeld = true
        enqueue(failure("EACCES", 403, "Check host permissions"))
        show()
        assertTrue(drop("src/app.kt"))
        waitUntilDone()
        assertEquals("Copy failed: app.kt", toasts.single().title)
    }

    @Test fun `an error of the transfer itself is a toast of its own, and the tree is free after it`() {
        enqueue(transferred("$ROOT/src/app.kt", "$ROOT/app.kt"))
        show(onChanged = { error("the list could not be refreshed") })
        assertTrue(drop("src/app.kt"))
        waitUntilDone()
        val toast = toasts.single()
        assertEquals("File operation failed", toast.title)
        assertEquals("the list could not be refreshed", toast.description)
        assertFalse(drops.running)
    }

    @Test fun `a tree that goes away while a question waits skips the item and is not left busy`() {
        enqueue(failure("EEXIST"))
        show()
        assertTrue(drop("src/app.kt"))
        waitFor(TAKEN)
        compose.runOnUiThread { shown = false }
        compose.waitForIdle()
        waitUntilDone()
        assertFalse(drops.running)
        assertEquals(1, server.requestCount)
        assertEquals(emptyList<ErrorToastMessage>(), toasts.toList())
    }

    @Test fun `a drop that is cancelled while it is sent says nothing, and leaves the tree free`() {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.countDown()
                release.await(5, TimeUnit.SECONDS)
                return transferred("$ROOT/src/app.kt", "$ROOT/app.kt")
            }
        }
        show()
        try {
            assertTrue(drop("src/app.kt"))
            assertTrue(arrived.await(3, TimeUnit.SECONDS))
            assertTrue(drops.running)
            scope.cancel()
            waitUntilDone()
            assertEquals("a cancelled drop is not an error", emptyList<ErrorToastMessage>(), toasts.toList())
            assertTrue("what was changed is still reported", changes.single().cancelled)
        } finally { release.countDown() }
    }

    @Test fun `a project folder on Windows is written to with its own separator`() {
        val root = "C:\\p"
        enqueue(transferred("C:\\p\\src\\app.kt", "C:\\p\\app.kt"))
        show(root)
        assertTrue(drop("src/app.kt"))
        waitUntilDone()
        assertRequest("POST", "/api/fs/move", buildJsonObject { put("source", "C:\\p\\src\\app.kt"); put("destination", "C:\\p\\app.kt") }.toString())
        assertEquals(setOf("C:\\p", "C:\\p\\src"), changes.single().affectedDirectories)
    }

    @Test fun `a Replace whose Trash step is refused writes nothing, and says it could not remove the existing one`() {
        enqueue(failure("EEXIST"), failure("EACCES", 403, "Check host permissions"))
        show()
        assertTrue(drop("src/app.kt"))
        waitFor(TAKEN)
        click("Replace")
        waitUntilDone()
        val toast = toasts.single()
        assertEquals("Could not remove existing app.kt", toast.title)
        assertEquals("Check host permissions", toast.description)
        assertEquals(ErrorToastMessage.Kind.ERROR, toast.kind)
        assertEquals("the move and the Trash, and nothing written over what could not be removed", 2, server.requestCount)
    }

    @Test fun `a Replace that removed the existing one and could not write the replacement says that as its title`() {
        enqueue(failure("EEXIST"), trashed("$ROOT/app.kt"), failure("EACCES", 403, "Check host permissions"))
        show()
        assertTrue(drop("src/app.kt"))
        waitFor(TAKEN)
        click("Replace")
        waitUntilDone()
        val toast = toasts.single()
        assertEquals("app.kt was removed but the replacement could not be written", toast.title)
        assertEquals("what the server said, with nothing glued in front of it", "Check host permissions", toast.description)
        assertEquals(ErrorToastMessage.Kind.ERROR, toast.kind)
    }

    @Test fun `on a host with no Trash, a delete for good that is refused is the same toast as a Trash that is`() {
        enqueue(failure("EEXIST"), failure("NO_TRASH", 400), failure("EACCES", 403, "Check host permissions"))
        show()
        assertTrue(drop("src/app.kt"))
        waitFor(TAKEN)
        click("Replace")
        waitFor("This host has no Trash")
        click("Delete permanently")
        waitUntilDone()
        assertEquals("Could not remove existing app.kt", toasts.single().title)
        assertEquals("Check host permissions", toasts.single().description)
        assertEquals("the move, the Trash, the delete for good, and no write", 3, server.requestCount)
    }

    @Test fun `Keep both with no name left to give is a Move failed toast that says so`() {
        everyNameTaken()
        show()
        assertTrue(drop("src/app.kt"))
        waitFor(TAKEN)
        click("Keep both")
        waitUntilDone()
        assertEquals("Move failed: app.kt", toasts.single().title)
        assertEquals("No free name available", toasts.single().description)
    }

    @Test fun `a copy into the folder it is in, with no name left to give, is a Copy failed toast that says so`() {
        copyHeld = true
        everyNameTaken()
        show()
        assertTrue(drop("README.md"))
        waitUntilDone()
        assertEquals("Copy failed: README.md", toasts.single().title)
        assertEquals("No free name available", toasts.single().description)
    }

    @Test fun `a move that copied the row but could not remove it is reported, which the web calls a success`() {
        enqueue(copiedButNotRemoved("$ROOT/src/app.kt", "$ROOT/app.kt"))
        show()
        assertTrue(drop("src/app.kt"))
        waitUntilDone()
        val toast = toasts.single()
        assertEquals("Move failed: app.kt", toast.title)
        assertEquals("Copied to $ROOT/app.kt, but the source was not removed.", toast.description)
        assertEquals(ErrorToastMessage.Kind.ERROR, toast.kind)
    }

    @Test fun `every row that fails has its own toast, in the order they were dropped, and what went is not announced`() {
        enqueue(
            failure("EACCES", 403, "Check host permissions"), failure("ENOSPC", 507, "Disk full"),
            transferred("$ROOT/docs/guide.md", "$ROOT/guide.md"),
        )
        show()
        assertTrue(drop("src/app.kt", "src/main.kt", "docs/guide.md"))
        waitUntilDone()
        assertEquals(listOf("Move failed: app.kt", "Move failed: main.kt"), toasts.map { it.title })
        assertEquals(listOf("Check host permissions", "Disk full"), toasts.map { it.description })
        assertEquals("nothing says that guide.md went, as the web's tree does not", listOf(ErrorToastMessage.Kind.ERROR, ErrorToastMessage.Kind.ERROR), toasts.map { it.kind })
    }
}
