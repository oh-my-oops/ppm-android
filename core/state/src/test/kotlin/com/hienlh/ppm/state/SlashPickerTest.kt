package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.FuzzySearch
import com.hienlh.ppm.protocol.SlashItem
import com.hienlh.ppm.protocol.SlashItemsPayload
import com.hienlh.ppm.protocol.SlashTrigger
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The web's own answers (`slash-trigger.ts`, `fuzzy-search.ts`), printed by the Apple port's
 * `apple/scripts/vectors/slash.ts` into `vectors/slash.json`.
 */
class SlashPickerTest {
    private val vectors = WireJson.parse(javaClass.getResource("/vectors/slash.json")!!.readText())

    private fun strings(value: JsonElement?) = value.array!!.map { it.string!! }

    @Test
    fun `the picker opens on the web's slash, and a pick replaces what it does`() {
        for (vector in vectors["trigger"].array!!) {
            val text = vector["text"].string!!
            assertEquals(text, vector["query"].string, SlashTrigger.queryBefore(text))
            assertEquals(text, vector["replaced"].string, SlashTrigger.replaceQuery(text, "/help"))
            assertEquals(text, vector["sigil"].string, SlashTrigger.replaceQuery(text, "\$imagegen"))
            assertEquals(text, vector["stripped"].string, SlashTrigger.stripQuery(text))
        }
    }

    @Test
    fun `the search scores and ranks as the web's`() {
        for (vector in vectors["distances"].array!!) {
            assertEquals(vector.toString(), vector["distance"].int, FuzzySearch.levenshtein(vector["a"].string!!, vector["b"].string!!))
        }
        for (vector in vectors["scores"].array!!) {
            val expected = (vector["score"] as? JsonObject)?.let { FuzzySearch.Score(it["rank"].int!!, it["distance"].int!!) }
            assertEquals(vector.toString(), expected, FuzzySearch.score(vector["query"].string!!, vector["candidate"].string!!))
        }
        val items = vectors["items"].array!!.map { SlashItem("skill", it["name"].string!!, it["description"].string!!) }
        for (vector in vectors["searches"].array!!) {
            val names = FuzzySearch.search(items, vector["query"].string!!, recentNames = strings(vector["recentNames"])).map { it.name }
            assertEquals(vector.toString(), strings(vector["names"]), names)
        }
        val limited = vectors["limited"]
        assertEquals(strings(limited["names"]), FuzzySearch.search(items, limited["query"].string!!, limited["limit"].int!!).map { it.name })
    }

    @Test
    fun `with nothing typed, the recently picked lead in the order picked`() {
        val items = listOf("help", "clear", "cost", "model").map { SlashItem("builtin", it) }
        val none = SlashPickerList(items, "", emptyList())
        assertEquals(listOf("help", "clear", "cost", "model"), none.items.map { it.name })
        assertEquals(0, none.recentCount)
        val recent = SlashPickerList(items, "", listOf("model", "gone", "clear"))
        assertEquals(listOf("model", "clear", "help", "cost"), recent.items.map { it.name })
        assertEquals(2, recent.recentCount)
        assertEquals(0, SlashPickerList(items, "c", listOf("model")).recentCount)
        assertEquals(3, recent.step(0, -1))
        assertEquals(0, recent.step(3, 1))
        assertEquals(2, recent.step(1, 1))
        assertEquals(0, SlashPickerList(emptyList(), "", emptyList()).step(0, 1))
    }

    @Test
    fun `an item is read from the server's answer, and labelled as the picker labels it`() {
        val payload = SlashItemsPayload.from(
            WireJson.parse(
                """
                {"items":[{"type":"skill","name":"imagegen","description":"Make pictures","invokeSigil":"${'$'}","displayName":"Image Gen"},
                          {"type":"builtin","name":"clear","description":"Start a new chat tab","argumentHint":"[title]","handler":"client","aliases":["new"],"scope":"bundled"},
                          {"type":"agent","name":"planner","description":"","scope":"user"},
                          {"type":"command","name":"deploy","scope":"project"},
                          {"name":"no type"}],
                 "recentNames":["clear"]}
                """,
            ),
        )!!
        assertEquals(listOf("\$imagegen", "/clear", "/planner", "/deploy"), payload.items.map { it.invocation })
        assertEquals(listOf("Codex", "PPM", "global", "command"), payload.items.map { it.sourceLabel })
        assertEquals(listOf("new"), payload.items[1].aliases)
        assertEquals("client", payload.items[1].handler)
        assertEquals("[title]", payload.items[1].argumentHint)
        assertEquals("Image Gen", payload.items[0].displayName)
        assertEquals(listOf("clear"), payload.recentNames)
        assertNull(SlashItemsPayload.from(JsonNull))
    }

    @Test
    fun `a list is kept half an hour, a provider's shared skills serve its other projects, and a pick leads at once`() {
        var clock = 1_000_000L
        val store = SlashItemsStore(CoroutineScope(Job()), clock = { clock })
        val payload = SlashItemsPayload(
            listOf(SlashItem("skill", "mine", scope = "project"), SlashItem("skill", "shared", scope = "user")),
            listOf("a", "b", "c", "d", "e"),
        )
        assertNull(store.payload("p", "claude"))
        store.seed(payload, "p", "claude")
        assertEquals(payload, store.payload("p", "claude"))
        assertEquals(listOf("shared"), store.payload("q", "claude")?.items?.map { it.name })
        assertNull(store.payload("q", "codex"))
        val api = ApiClient("http://127.0.0.1:9".toHttpUrl(), "")
        val revision = store.revision.value
        store.recordUse(SlashItem("skill", "c"), api, "p", "claude")
        assertEquals(listOf("c", "a", "b", "d", "e"), store.payload("p", "claude")?.recentNames)
        assertEquals(revision + 1, store.revision.value)
        store.recordUse(SlashItem("skill", "new"), api, "p", "claude")
        assertEquals(listOf("new", "c", "a", "b", "d"), store.payload("p", "claude")?.recentNames)
        clock += SlashItemsStore.LIFETIME_MS + 1
        // Old, but still what is shown until a read replaces it.
        assertEquals(2, store.payload("p", "claude")?.items?.size)
    }

    @Test
    fun `clear is the app's own, refused while replying, as is anything the app or PPM runs`() {
        val items = listOf(
            SlashItem("builtin", "clear", handler = "client", aliases = listOf("new")),
            SlashItem("builtin", "skills", handler = "ppm", aliases = listOf("sk")),
            SlashItem("builtin", "compact", handler = "sdk"),
        )
        assertEquals(ClientCommand.Clear(null), ClientCommand.of("/clear", items, replying = false))
        assertEquals(ClientCommand.Clear("My topic"), ClientCommand.of("/clear   My topic", items, replying = false))
        assertEquals(ClientCommand.Clear("Other"), ClientCommand.of("/new Other", items, replying = false))
        assertEquals("before the list loads", ClientCommand.Clear(null), ClientCommand.of("/clear", emptyList(), replying = false))
        assertNull(ClientCommand.of("/skills", items, replying = false))
        assertNull(ClientCommand.of("/compact", items, replying = true))
        assertNull(ClientCommand.of("hello /clear", items, replying = true))
        assertEquals(ClientCommand.Refused, ClientCommand.of("/clear", emptyList(), replying = true))
        assertEquals(ClientCommand.Refused, ClientCommand.of("/new", items, replying = true))
        assertEquals(ClientCommand.Refused, ClientCommand.of("/skills", items, replying = true))
        assertNull("a PPM command is matched by its name, not its alias", ClientCommand.of("/sk", items, replying = true))
    }

    private val costList = """{"ok":true,"data":{"items":[{"type":"builtin","name":"cost"}],"recentNames":["cost"]}}"""

    private fun <T> withServer(vararg responses: MockResponse, block: suspend CoroutineScope.(MockWebServer, ApiClient) -> T): T = runBlocking {
        val server = MockWebServer()
        responses.forEach(server::enqueue)
        server.start()
        try {
            block(server, ApiClient(server.url("/"), "t"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `a list is read once in half an hour, once for two asking together, and kept when a later read fails`() = withServer(
        MockResponse.Builder().body(costList).build(),
        MockResponse.Builder().code(500).body("""{"ok":false,"error":"boom"}""").build(),
    ) { server, api ->
        var clock = 0L
        val store = SlashItemsStore(this, clock = { clock })
        listOf(launch { store.load(api, "my app", "claude", "s-1") }, launch { store.load(api, "my app", "claude", "s-1") }).joinAll()
        assertEquals("/api/project/my%20app/chat/slash-items?providerId=claude&sessionId=s-1", server.takeRequest().target)
        assertEquals(listOf("cost"), store.payload("my app", "claude")?.items?.map { it.name })
        store.load(api, "my app", "claude", "s-1")
        assertEquals(1, server.requestCount)
        clock += SlashItemsStore.LIFETIME_MS
        store.load(api, "my app", "claude", null)
        assertEquals("/api/project/my%20app/chat/slash-items?providerId=claude", server.takeRequest().target)
        assertEquals(listOf("cost"), store.payload("my app", "claude")?.items?.map { it.name })
    }

    @Test
    fun `a pick is told to the server, and Refresh has it read skills again before the list is`() = withServer(
        MockResponse.Builder().body(costList).build(),
        MockResponse.Builder().body("""{"ok":true,"data":{"recorded":true}}""").build(),
        MockResponse.Builder().body("""{"ok":true,"data":{"cleared":true}}""").build(),
        MockResponse.Builder().body("""{"ok":true,"data":{"items":[{"type":"builtin","name":"model"}],"recentNames":[]}}""").build(),
    ) { server, api ->
        val store = SlashItemsStore(this)
        store.load(api, "p", "claude", null)
        server.takeRequest()
        store.recordUse(SlashItem("builtin", "cost"), api, "p", "claude")
        // The POST is sent from this thread, which takeRequest then blocks.
        yield()
        val use = server.takeRequest()
        assertEquals("POST /api/project/p/chat/slash-recents", "${use.method} ${use.target}")
        assertEquals("""{"name":"cost","type":"builtin"}""", use.body?.utf8())
        store.refresh(api, "p", "claude", null)
        val clear = server.takeRequest()
        assertEquals("DELETE /api/project/p/chat/slash-items/cache", "${clear.method} ${clear.target}")
        assertEquals("the list is read after Refresh returns", 3, server.requestCount)
        // Sent from this thread too, so waited for without blocking it.
        withTimeout(5_000) { store.revision.first { store.payload("p", "claude")?.items?.firstOrNull()?.name == "model" } }
        assertEquals("GET", server.takeRequest().method)
    }
}
