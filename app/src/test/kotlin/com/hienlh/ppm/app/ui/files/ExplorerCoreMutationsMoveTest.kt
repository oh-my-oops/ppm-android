package com.hienlh.ppm.app.ui.files

import androidx.compose.ui.test.junit4.createComposeRule
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.files.FileMutationOperation
import com.hienlh.ppm.state.files.FileMutationReport
import com.hienlh.ppm.state.files.FileMutations
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tree's move as the core wiring sends it: [rememberExplorerMutationCallbacks]'s `move` takes the row's path and the
 * folder's path with the row's name in it, in that order, and the server is asked to move the first to the second. Drawn
 * against a server that records what it is asked, as the mutations' own test in core:client does for the API calls: the
 * drag's tests stop at the callback, so a source and a destination swapped here would move the folder into the row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExplorerCoreMutationsMoveTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private val reports = mutableListOf<FileMutationReport>()
    private var callbacks: ExplorerMutationCallbacks? = null

    @Before
    fun setUp() {
        server.start()
        val mutations = FileMutations(ApiClient(server.url("/"), "secret"))
        compose.setContent {
            callbacks = rememberExplorerMutationCallbacks(mutations, ProjectInfo("my app", "/home/me/app"), reports::add)
        }
        compose.waitForIdle()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun answer(code: Int, body: String) = server.enqueue(
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build(),
    )

    @Test
    fun `a move asks the server to move the row's path to the folder's, in that order`() {
        answer(200, """{"ok":true,"data":{"moved":{"from":"src/app.kt","to":"lib/app.kt"}}}""")

        runBlocking { callbacks!!.move("src/app.kt", "lib/app.kt") }

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/project/my%20app/files/move", request.target)
        assertEquals(WireJson.parse("""{"source":"src/app.kt","destination":"lib/app.kt"}"""), WireJson.parse(request.body!!.utf8()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a move that went through is reported once, as a move of that row to that place`() {
        answer(200, """{"ok":true,"data":{"moved":{"from":"src/app.kt","to":"lib/app.kt"}}}""")

        runBlocking { callbacks!!.move("src/app.kt", "lib/app.kt") }

        val report = reports.single()
        assertTrue(report.succeeded)
        val item = report.items.single()
        assertEquals(FileMutationOperation.MOVE, item.operation)
        assertEquals("src/app.kt", item.path)
        assertEquals("lib/app.kt", item.destination)
    }

    @Test
    fun `a move the server refuses throws its message and is still reported`() {
        answer(409, """{"ok":false,"error":"Destination already exists"}""")

        val error = runCatching { runBlocking { callbacks!!.move("src/app.kt", "lib/app.kt") } }.exceptionOrNull()

        assertEquals("src/app.kt: Destination already exists", error?.message)
        assertEquals(1, reports.size)
    }
}
