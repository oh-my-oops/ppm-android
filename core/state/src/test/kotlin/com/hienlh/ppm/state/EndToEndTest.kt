package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.Upload
import com.hienlh.ppm.client.createTag
import com.hienlh.ppm.client.deleteSession
import com.hienlh.ppm.client.deleteSessions
import com.hienlh.ppm.client.deleteTag
import com.hienlh.ppm.client.deleteUpload
import com.hienlh.ppm.client.providerModels
import com.hienlh.ppm.client.resetTags
import com.hienlh.ppm.client.setDefaultTag
import com.hienlh.ppm.client.setTag
import com.hienlh.ppm.client.updateTag
import com.hienlh.ppm.client.upload
import com.hienlh.ppm.client.workspace
import com.hienlh.ppm.protocol.ChatSearchHit
import com.hienlh.ppm.protocol.HistoryMessage
import com.hienlh.ppm.protocol.ModelOption
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SessionPhase
import com.hienlh.ppm.protocol.TurnOptions
import com.hienlh.ppm.state.workspace.MemoryValues
import com.hienlh.ppm.state.workspace.NewTab
import com.hienlh.ppm.state.workspace.WorkspaceCache
import com.hienlh.ppm.state.workspace.WorkspaceStore
import com.hienlh.ppm.state.workspace.workspaceRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Against a real server, with the `mock` provider so that no AI is paid for
 * (`PPMSelfTest/EndToEnd.swift`). Runs only when `PPM_E2E_URL`, `PPM_E2E_PASSWORD` and
 * `PPM_E2E_PROJECT` name a scratch server: `android/scripts/scratch-server.sh`.
 */
class EndToEndTest {
    private lateinit var api: ApiClient
    private lateinit var url: HttpUrl
    private lateinit var token: String
    private lateinit var project: String

    /** The app's main thread: every controller is driven from it, as the UI drives them. */
    private val mainThread = Executors.newSingleThreadExecutor { Thread(it, "e2e-main") }
    private val main = mainThread.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)

    @Before
    fun connect() {
        val address = System.getenv("PPM_E2E_URL").orEmpty()
        assumeTrue("PPM_E2E_URL is not set", address.isNotEmpty())
        url = ApiClient.normalizedServerUrl(address) ?: error("PPM_E2E_URL is not a server address: $address")
        token = System.getenv("PPM_E2E_PASSWORD").orEmpty()
        project = System.getenv("PPM_E2E_PROJECT").orEmpty().ifEmpty { "demo" }
        api = ApiClient(url, token)
    }

    @After
    fun close() {
        scope.cancel()
        mainThread.shutdownNow()
    }

    private fun onMain(body: suspend () -> Unit) = runBlocking(main) { body() }

    private suspend fun waitFor(what: String, seconds: Int = 20, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            delay(25)
        }
    }

    /** Asks the server until it answers yes. */
    private suspend fun poll(what: String, seconds: Int = 10, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            delay(100)
        }
    }

    private suspend fun open(session: SessionInfo): ChatController {
        val chat = ChatController(api, project, session, scope)
        chat.start()
        waitFor("the socket") { chat.state.value.canSend }
        return chat
    }

    private suspend fun ChatController.turnEnds(items: Int) =
        waitFor("the turn to end") { !state.value.isRunning && state.value.items.size == items }

    @Test
    fun `info, sign-in, projects`() = onMain {
        assertTrue("no version", api.info().version.isNotEmpty())
        api.checkAuth()
        val projects = api.projects()
        assertTrue("project $project not in ${projects.map { it.name }}", projects.any { it.name == project })
        try {
            ApiClient(url, "$token-wrong").checkAuth()
            fail("a wrong password was accepted")
        } catch (error: PpmError) {
            assertEquals(PpmError.Unauthorized(), error)
        }
    }

    @Test
    fun `a layout one device saves is the one another device opens`() = onMain {
        val file = "notes-${UUID.randomUUID()}.md"
        val phone = WorkspaceCache(MemoryValues(), "w.", api.workspaceRemote(), scope)
        val first = WorkspaceStore(phone, isMobile = { true })
        first.switchProject(project)
        val id = first.openTab(NewTab("editor", file, project, JsonObject(mapOf("filePath" to JsonPrimitive(file)))))
        // A field this app has never heard of rides along.
        first.updateTab(id) { it.copy(extra = JsonObject(mapOf("pinned" to JsonPrimitive(true)))) }
        phone.flush()
        poll("the layout to reach the server") { api.workspace(project)?.layout?.toString()?.contains(file) == true }

        val tablet = WorkspaceCache(MemoryValues(), "w.", api.workspaceRemote(), scope)
        assertTrue("the server's copy was not taken", tablet.hydrate(project))
        val second = WorkspaceStore(tablet, isMobile = { false })
        second.switchProject(project)
        val tab = second.state.value.activeGridTab
        assertEquals(id, tab?.id)
        assertEquals(JsonPrimitive(true), tab?.extra?.get("pinned"))
    }

    @Test
    fun `an upload lands on the server and can be taken back`() = onMain {
        val files = api.upload(project, listOf(Upload("note one.txt", "text/plain", "hello".toByteArray())))
        assertEquals(1, files.size)
        assertEquals("note one.txt", files.first().name)
        assertEquals(5L, files.first().size)
        assertTrue(files.first().path, files.first().path.endsWith("-note_one.txt"))
        api.deleteUpload(project, files.first().path)
    }

    @Test
    fun `a route the server lacks is an error, never data`() = onMain {
        // The web app's page (an older server with a built frontend), a JSON 404 (a newer one),
        // or a plain-text 404 (a checkout with no frontend built).
        try {
            api.call("GET", "/api/no-such-route-for-the-app")
            fail("a missing route answered")
        } catch (_: PpmError.OutdatedServer) {
        } catch (error: PpmError.Server) {
            assertEquals(404, error.status)
        } catch (error: PpmError.BadResponse) {
            assertEquals("The server answered HTTP 404.", error.reason)
        }
    }

    @Test
    fun `a turn streams a tool call and text, then goes idle`() = onMain {
        val chat = open(api.createSession(project, "mock"))
        try {
            chat.send("show me a code example in a file")
            chat.turnEnds(2)
            val answer = chat.state.value.items[1]
            val tools = answer.blocks.mapNotNull { (it as? ChatBlock.Tool)?.tool }
            // The first turn also asks to run Bash: an approval, drawn in the turn as the web does.
            assertEquals(listOf("Read", "Bash"), tools.map { it.name })
            assertTrue("tool result missing", tools.first().result?.output?.contains("Main entry point") == true)
            assertNotNull("the Bash card is not the approval request", tools.last().requestId)
            assertTrue("no code block in ${answer.text}", answer.text.contains("```typescript"))
        } finally {
            chat.stop()
        }
    }

    @Test
    fun `losing the connection mid-turn loses and repeats nothing`() = onMain {
        val chat = open(api.createSession(project, "mock"))
        try {
            // The mock streams a code block on a session's first turn that its stored history
            // leaves out, so that turn cannot be compared; get it out of the way first.
            chat.send("hello")
            chat.turnEnds(2)
            for (round in 1..3) {
                val before = chat.state.value.items.size
                chat.send("tell me more, round $round")
                waitFor("text to stream") {
                    val last = chat.state.value.live?.lastOrNull() as? ChatBlock.Text
                    last != null && last.text.split(' ').size >= 2
                }
                chat.simulateConnectionLoss()
                waitFor("the turn to end") {
                    val state = chat.state.value
                    state.canSend && !state.isRunning && state.items.size == before + 2
                }
            }
            // Give a reload triggered at idle time to land, then compare with what the server kept.
            delay(500)
            val page = api.history(project, chat.sessionId, "mock", limit = 50)
            val shown = chat.state.value.items.filter { it.role == ChatItem.Role.ASSISTANT }.map { it.text }.drop(1)
            val kept = page.messages.filter { it.role == HistoryMessage.Role.ASSISTANT }.map { it.content }.drop(1)
            assertEquals(kept, shown)
            assertEquals(3, shown.size)
            assertEquals(4, chat.state.value.items.count { it.role == ChatItem.Role.USER })
        } finally {
            chat.stop()
        }
    }

    @Test
    fun `an approval request reaches the app`() = onMain {
        val chat = open(api.createSession(project, "mock"))
        try {
            chat.send("please delete the temp folder")
            waitFor("the approval") { chat.state.value.pendingApproval != null }
            assertEquals("Bash", chat.state.value.pendingApproval?.tool)
            waitFor("the turn to end") { !chat.state.value.isRunning }
        } finally {
            chat.stop()
        }
    }

    @Test
    fun `the global socket shows a turn running, then the chat unread until read`() = onMain {
        val events = GlobalEvents(api, scope)
        var readies = 0
        events.onReady = { readies++ }
        events.project = project
        events.start()
        try {
            waitFor("the global socket") { events.isConnected.value }
            assertEquals(1, readies)

            val session = api.createSession(project, "mock")
            val chat = open(session)
            try {
                chat.send("hello")
                waitFor("the turn to show as running") { session.id in events.running.value }
                chat.turnEnds(2)
                waitFor("the turn to show as ended") { session.id !in events.running.value }
                waitFor("the unread mark") { events.unread.value[session.id]?.type == "done" }
                assertEquals(project, events.unread.value[session.id]?.projectName)
                assertNotNull("the server does not have it unread", api.unreadSessions(project)[session.id])

                events.markRead(session.id)
                assertNull("still unread here", events.unread.value[session.id])
                poll("the server to mark it read") { api.unreadSessions(project)[session.id] == null }
            } finally {
                chat.stop()
            }
        } finally {
            events.stop()
        }
    }

    @Test
    fun `a session is tagged, marked unread by hand, and deleted`() = onMain {
        val events = GlobalEvents(api, scope)
        events.project = project
        events.start()
        try {
            waitFor("the global socket") { events.isConnected.value }

            val session = api.createSession(project, "mock")
            val tag = api.tags(project).tags.firstOrNull() ?: error("no tag on the project")
            api.setTag(tag.id, project, session.id)
            var listed = api.sessions(project, limit = 200).sessions.firstOrNull { it.id == session.id }
            assertEquals(tag.id, listed?.tag?.id)
            api.setTag(null, project, session.id)
            listed = api.sessions(project, limit = 200).sessions.firstOrNull { it.id == session.id }
            assertTrue("the tag is still on", listed != null && listed.tag == null)

            events.markUnread(session.id, "by hand")
            assertEquals(true, events.unread.value[session.id]?.manual)
            poll("the server to have it unread") { api.unreadSessions(project)[session.id] != null }
            // The server's broadcast of it has come back by now, and kept it marked by hand.
            delay(300)
            assertEquals(true, events.unread.value[session.id]?.manual)
            events.markRead(session.id)
            poll("the server to have it read") { api.unreadSessions(project)[session.id] == null }

            api.deleteSession(project, session.id, "mock")
            val after = api.sessions(project, limit = 200).sessions
            assertFalse("a deleted session is still listed", after.any { it.id == session.id })
            // Everything here is minutes old: the route answers, and deletes nothing.
            assertEquals(0, api.deleteSessions(project, olderThanDays = 3650).first)
        } finally {
            events.stop()
        }
    }

    @Test
    fun `tags are created, changed, made the default, deleted and reset`() = onMain {
        api.createTag(project, "E2E tag", "#123456")
        var tags = api.tags(project)
        val created = tags.tags.firstOrNull { it.name == "E2E tag" } ?: error("the new tag is missing")
        assertEquals("#123456", created.color)
        api.updateTag(project, created.id, "E2E tag 2", "#abcdef")
        api.setDefaultTag(created.id, project)
        tags = api.tags(project)
        assertEquals("E2E tag 2", tags.tags.firstOrNull { it.id == created.id }?.name)
        assertEquals("#abcdef", tags.tags.firstOrNull { it.id == created.id }?.color)
        assertEquals(created.id, tags.defaultTagId)
        api.setDefaultTag(null, project)
        api.deleteTag(project, created.id)
        tags = api.tags(project)
        assertFalse("the deleted tag is still there", tags.tags.any { it.id == created.id })
        assertNull(tags.defaultTagId)
        api.resetTags(project)
        assertTrue("reset left no Todo tag", api.tags(project).tags.any { it.name == "Todo" })
    }

    @Test
    fun `a chat's History finds a title, the sidebar's search a message`() = onMain {
        val titleWord = "zt" + UUID.randomUUID().toString().take(8)
        val bodyWord = "zb" + UUID.randomUUID().toString().take(8)
        // Named when created: a mock session cannot be renamed, and titles itself from a first
        // message that the server puts the project's shared instructions in front of.
        val session = api.createSession(project, "mock", "search $titleWord")
        val chat = open(session)
        try {
            chat.send("a message with a word in it: $bodyWord")
            chat.turnEnds(2)

            val list = HistoryList(api, project, scope)
            list.query = titleWord
            waitFor("the title search") { list.state.value.searchPage?.map { it.id } == listOf(session.id) }
            assertEquals(listOf(session.id), list.sessions(emptyList()).map { it.id })
            // The server filters a page at a time: whether there is more is its say, not the shared page's.
            assertEquals(list.hasMore(shared = false), list.hasMore(shared = true))
            list.query = ""
            waitFor("the shared page back") { list.state.value.searchPage == null }

            // The server indexes the message only once it is searched for, so the first answer
            // misses it and a later one finds it.
            val search = ChatSearch(api, project, scope)
            search.query = bodyWord
            waitFor("the message search") { search.state.value.page?.hits?.any { it.sessionId == session.id } == true }
            val hit = search.state.value.page?.hits?.firstOrNull { it.sessionId == session.id }
            assertEquals(true, hit?.matchedInContent)
            assertTrue(
                hit?.snippet ?: "no snippet",
                ChatSearchHit.runs(hit?.snippet.orEmpty()).any { it.marked && it.text.lowercase() == bodyWord },
            )
        } finally {
            chat.stop()
        }
    }

    @Test
    fun `model, effort and thinking picked in one chat are the session's`() = onMain {
        val claude = api.providerModels(project, "claude")
        assertTrue("$claude", claude.any { it.value == "claude-opus-5-5" && it.label == "Claude Opus 5.5" })
        assertEquals(emptyList<ModelOption>(), api.providerModels(project, "mock"))

        val session = api.createSession(project, "mock")
        val first = open(session)
        try {
            // The greeting's report: thinking is on until something turns it off.
            assertTrue(first.state.value.thinking)
            first.setEffort(Effort.XHIGH)
            first.setThinking(false)
            first.setModel("claude-opus-5-5")
            // Shown at once, before the server has answered.
            assertEquals("xhigh", first.state.value.effort)
            assertFalse(first.state.value.thinking)

            // The server answers each pick on the first socket; give it a moment to store them.
            delay(1_000)
            val second = open(session)
            try {
                waitFor("another chat to read them") {
                    second.state.value.let { it.effort == "xhigh" && !it.thinking && it.model == "claude-opus-5-5" }
                }
                assertEquals(
                    TurnOptions(permissionMode = "plan", model = "claude-opus-5-5", effort = "xhigh"),
                    second.settings.turnOptions(TurnOptions(permissionMode = "plan")),
                )
            } finally {
                second.stop()
            }

            // A message carries its mode, and the turn still runs.
            first.send("hello there", TurnOptions(permissionMode = "plan"))
            first.turnEnds(2)
        } finally {
            first.stop()
        }
    }

    @Test
    fun `reopening a session shows its history`() = onMain {
        val session = api.createSession(project, "mock")
        val first = open(session)
        first.send("hello there")
        first.turnEnds(2)
        val expected = first.state.value.items.map { it.text }
        first.stop()

        val second = ChatController(api, project, session, scope)
        second.start()
        try {
            waitFor("the history") { second.state.value.items.size == 2 }
            val reloaded = second.state.value.items.map { it.text }
            assertEquals(expected.first(), reloaded.first())
            // The mock keeps its answer without the code block it streamed on a first turn.
            assertTrue("$reloaded vs $expected", reloaded[1].isNotEmpty() && expected[1].startsWith(reloaded[1]))
            assertEquals(SessionPhase.IDLE, second.state.value.phase)
        } finally {
            second.stop()
        }
    }
}
