package com.hienlh.ppm.app.ui.files

import android.app.Application
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.FileDirectoryResult
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.files.FileMutations
import com.hienlh.ppm.state.files.ProjectFileTree
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

/**
 * The Explorer panel's own wiring of a drop on the tree's blank area, with the real app model against a server that answers as asked:
 * the rows go to the server as absolute paths, a taken name is asked about in a dialog the panel hosts, what did not go is a toast of the
 * app's, and the folders the drop touched are listed again. The tree is the shared one (docs, src, README.md), the project folder
 * [ROOT].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
internal class ExplorerBodyDropsTest : ExplorerTreeDragTestBase() {
    private val server = MockWebServer()
    private lateinit var model: AppModel

    /** A refresh asks for the root and the open folders in one batch. */
    override suspend fun batchListing(paths: List<String>) = paths.map { FileDirectoryResult(it, listing.getValue(it)) }

    @Before fun connect() {
        server.start()
        model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        val api = ApiClient(server.url("/"), "secret")
        model.api = api
        stateOf<String?>("selectedProject").value = "p"
        stateOf<List<ProjectInfo>>("projects").value = listOf(ProjectInfo("p", ROOT))
        stateOf<FileMutations?>("fileMutations").value = FileMutations(api)
    }

    @After fun disconnect() = server.close()

    /** The state behind a property of the model that only the model sets: what a connection to a server would have put there. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> stateOf(property: String): MutableState<T> = ReflectionHelpers.getField(model, property + "\$delegate")

    private fun showPanel() = show(content = { tree, _ ->
        remember(tree) { stateOf<ProjectFileTree?>("fileTree").value = tree }
        ExplorerBody(model)
    })

    private fun ok(data: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{"ok":true,"data":""" + data + "}").build()

    private fun failure(code: String, status: Int = 409, hint: String? = null) = MockResponse.Builder().code(status)
        .addHeader("Content-Type", "application/json").body(buildJsonObject {
            put("ok", false); put("error", code); put("code", code)
            if (hint != null) put("hint", hint)
        }.toString()).build()

    private fun moved(source: String, destination: String) = ok(buildJsonObject { put("source", source); put("destination", destination) }.toString())

    private fun isShown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String) = compose.waitUntil(10_000) { isShown(text) }

    private fun dropAppOnBlank() {
        val drag = row("src/app.kt")
        assertTrue(begin(drag))
        over(drag, blank())
        assertTrue(drop(drag, blank()))
    }

    private val taken = "A file named “app.kt” already exists"

    @Test fun `a drop on the blank area goes to the server as absolute paths, and the folders it touched are listed again`() {
        server.enqueue(moved("$ROOT/src/app.kt", "$ROOT/app.kt"))
        showPanel()
        assertTrue("app.kt starts above README.md, in src", center("app.kt").y < center("README.md").y)
        listing["src"] = listOf(dir("util"), file("main.kt"))
        listing[""] = listOf(dir("docs"), dir("src"), file("README.md"), file("app.kt"))
        dropAppOnBlank()
        compose.waitUntil(10_000) { center("app.kt").y > center("README.md").y }
        val request = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        assertEquals("/api/fs/move", request.target)
        assertEquals(
            WireJson.parse("""{"source":"$ROOT/src/app.kt","destination":"$ROOT/app.kt"}"""),
            WireJson.parse(request.body!!.utf8()),
        )
        assertEquals(emptyList<String>(), model.toasts.map { it.message.title })
    }

    @Test fun `a name that is taken is asked about in a dialog the panel hosts, and Skip sends nothing more`() {
        server.enqueue(failure("EEXIST"))
        showPanel()
        dropAppOnBlank()
        waitFor(taken)
        compose.onNodeWithText("Skip").performClick()
        compose.waitUntil(10_000) { !isShown(taken) }
        assertEquals(1, server.requestCount)
        assertEquals(emptyList<String>(), model.toasts.map { it.message.title })
    }

    @Test fun `a row the server refuses is a toast of the app, with what the server said`() {
        server.enqueue(failure("EACCES", 403, "Check host permissions"))
        showPanel()
        dropAppOnBlank()
        compose.waitUntil(10_000) { model.toasts.isNotEmpty() }
        val toast = model.toasts.single().message
        assertEquals("Move failed: app.kt", toast.title)
        assertEquals("Check host permissions", toast.description)
        assertFalse(isShown(taken))
    }

    @Test fun `a drop on a row moves the one row, as the tree always did, without a transfer`() {
        server.enqueue(ok("""{"moved":{"from":"src/app.kt","to":"docs/app.kt"}}"""))
        showPanel()
        val drag = row("src/app.kt")
        assertTrue(begin(drag))
        over(drag, center("docs"))
        assertTrue(drop(drag, center("docs")))
        compose.waitUntil(10_000) { server.requestCount == 1 }
        val request = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        assertEquals("/api/project/p/files/move", request.target)
        assertEquals(WireJson.parse("""{"source":"src/app.kt","destination":"docs/app.kt"}"""), WireJson.parse(request.body!!.utf8()))
    }
}
