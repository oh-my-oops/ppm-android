package com.hienlh.ppm.state.assistant

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.GlobalEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The Assistant's state on this device, fed by HTTP and `assistant:*` events. */
class AssistantStoreTest {
    private fun cardJson(id: String, at: Long, status: String = "open", project: String = "web", kind: String = "needs_decision") =
        """{"id":"$id","kind":"$kind","projectName":"$project","sessionId":"m1","title":"T$id","body":"","payload":{},"status":"$status","okOffered":true,"createdAt":$at,"updatedAt":$at}"""

    private class Routes(val answers: Map<String, String>) : Dispatcher() {
        val asked = CopyOnWriteArrayList<String>()
        override fun dispatch(request: RecordedRequest): MockResponse {
            val target = request.target
            asked += "${request.method} $target"
            val key = answers.keys.firstOrNull { "${request.method} $target".startsWith(it) }
            return key?.let { MockResponse.Builder().body("""{"ok":true,"data":${answers[it]}}""").build() } ?: MockResponse.Builder().code(404).build()
        }
    }

    private fun withStore(answers: Map<String, String>, block: suspend (AssistantStore, Routes) -> Unit) = runBlocking {
        val routes = Routes(answers)
        val server = MockWebServer()
        server.dispatcher = routes
        server.start()
        val job = Job()
        try {
            block(AssistantStore(ApiClient(server.url("/"), "t"), CoroutineScope(coroutineContext + job)), routes)
        } finally {
            job.cancel()
            server.close()
        }
    }

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            delay(10)
        }
    }

    private val base = mapOf(
        "GET /api/assistant/cards/open" to """{"cards":[${cardJson("w1", 2)}]}""",
        "GET /api/assistant/cards" to """{"cards":[${cardJson("c2", 5)},${cardJson("w1", 2)}],"nextBefore":2}""",
        "GET /api/assistant/waiting" to """{"total":1,"byProject":{"web":1}}""",
        "GET /api/assistant/managers" to """{"managers":[{"projectName":"web","projectPath":"/w","sessionId":"m1","providerId":"claude","phase":"idle","waiting":1}]}""",
        "GET /api/assistant/main-session" to """{"main":{"sessionId":"s-main","providerId":"codex"}}""",
        "GET /api/assistant/telegram" to """{"enabled":true,"running":true,"quietWhileInPpm":false,"chats":[]}""",
        "GET /api/assistant/settings" to """{"settings":{"persona":{"name":"Mia"},"default_provider":"claude"}}""",
    )

    @Test
    fun `everything is read once, and events keep it current`() = withStore(base) { store, _ ->
        store.start()
        waitFor("the first read") { store.view.value.let { it.cards != null && it.open != null && it.managers != null && it.mainLoaded && it.name != null } }
        val v = store.view.value
        assertEquals(listOf("c2", "w1"), v.cards!!.map { it.id })
        assertEquals("codex", v.main?.providerId)
        assertEquals("Mia", v.shortName)
        assertTrue(v.isManagerChat("m1"))
        assertFalse(v.isManagerChat("other"))

        // A card answered elsewhere leaves the waiting list and is replaced in the stream.
        store.handleEvent("assistant:card", WireJson.parse("""{"type":"assistant:card","card":${cardJson("w1", 2, status = "answered")}}"""))
        assertTrue(store.view.value.open!!.isEmpty())
        assertEquals("answered", store.view.value.cards!!.first { it.id == "w1" }.status)
        store.handleEvent("assistant:main_session_changed", WireJson.parse("""{"sessionId":null,"providerId":null}"""))
        assertNull(store.view.value.main)
        store.handleEvent("assistant:managers_changed", WireJson.parse("""{"managers":[]}"""))
        assertFalse(store.view.value.isManagerChat("m1"))
        store.handleEvent("assistant:telegram_quiet_changed", WireJson.parse("""{"on":true}"""))
        assertTrue(store.view.value.telegram!!.quietWhileInPpm)
    }

    @Test
    fun `counts that do not match the list read the waiting cards again, and a filter reloads the stream`() = withStore(base) { store, routes ->
        store.start()
        waitFor("the waiting list") { store.view.value.open != null && store.view.value.cards != null }
        val before = routes.asked.count { it == "GET /api/assistant/cards/open" }
        store.handleEvent("assistant:waiting_changed", WireJson.parse("""{"total":1,"byProject":{"web":1}}"""))
        assertEquals(before, routes.asked.count { it == "GET /api/assistant/cards/open" })
        store.handleEvent("assistant:waiting_changed", WireJson.parse("""{"total":3,"byProject":{"web":3}}"""))
        waitFor("a second read") { routes.asked.count { it == "GET /api/assistant/cards/open" } == before + 1 }
        assertEquals(3, store.view.value.waiting.total)

        var kept: String? = "unset"
        val filtered = AssistantStore(ApiClient("http://127.0.0.1:9/".toHttpUrl(), "t"), CoroutineScope(Job()), onFilterChanged = { kept = it })
        filtered.setFilter("web")
        assertEquals("web", kept)
        assertEquals("web", filtered.view.value.filter)
        assertNull(filtered.view.value.cards)
    }

    @Test
    fun `a decision set keeps its order, marks a card answered elsewhere, and sends a pick`() = withStore(
        base + ("POST /api/assistant/cards/w1/ok" to """{"card":${cardJson("w1", 2, status = "answered")},"to":"manager"}"""),
    ) { store, routes ->
        store.start()
        waitFor("the waiting list") { store.view.value.open != null && store.view.value.cards != null }
        store.syncDecisionOrder()
        assertEquals(listOf("w1"), store.view.value.decisions.order)
        val w1 = store.view.value.open!!.single()
        store.pick(w1, DecisionAnswer.Ok(null))
        store.sendOne(w1)
        waitFor("the send") { store.view.value.decisions.done["w1"] != null }
        assertTrue(routes.asked.contains("POST /api/assistant/cards/w1/ok"))
        assertEquals("OK — go ahead", store.view.value.decisions.done["w1"]!!.note)
        assertTrue(store.view.value.open!!.isEmpty())
    }

    @Test
    fun `assistant events reach their listener with their type, and nothing else does`() {
        val events = GlobalEvents(ApiClient("http://127.0.0.1:9/".toHttpUrl(), "t"), CoroutineScope(Job()))
        val seen = mutableListOf<String>()
        val off = events.onAssistantEvent { type, _ -> seen += type }
        events.handle(WireJson.parse("""{"type":"assistant:card","card":{}}"""))
        events.handle(WireJson.parse("""{"type":"memory:changed"}"""))
        off()
        events.handle(WireJson.parse("""{"type":"assistant:waiting_changed"}"""))
        assertEquals(listOf("assistant:card"), seen)
    }

    @Test
    fun `a reply from a card's own bar leaves the Assistant's reply target alone, and a refusal says why`() = withStore(
        base + ("POST /api/assistant/cards/w1/reply" to """{"card":""" + cardJson("w1", 2, status = "answered") + ""","to":"manager"}"""),
    ) { store, routes ->
        store.start()
        waitFor("the waiting list") { store.view.value.open != null }
        val w1 = store.view.value.open!!.single()
        val other = w1.copy(id = "c9")
        store.replyTo(other)
        val sent = store.sendReplyTo(w1, "go on", force = false)
        assertTrue(sent is SendOutcome.Sent)
        assertTrue(routes.asked.contains("POST /api/assistant/cards/w1/reply"))
        assertEquals("c9", store.view.value.replyTarget?.id)
        assertTrue(store.view.value.open!!.isEmpty())
        val refused = store.sendReplyTo(other, "again", force = false)
        assertTrue(refused is SendOutcome.Failed)
        assertEquals("c9", store.view.value.replyTarget?.id)
    }
}
