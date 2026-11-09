package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.LinkedSessionUnlinks
import com.hienlh.ppm.protocol.SessionLink
import com.hienlh.ppm.protocol.SessionLinkRelation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `unlinkedSessions` in `/api/settings/ui-prefs` (`isUnlinkMap`) and session-link routes (`session-collab-client.ts`). */
class UiPrefsUnlinksTest {
    @Test
    fun `isUnlinkMap accepts valid maps and refuses malformed shapes`() {
        assertTrue(LinkedSessionUnlinks.isUnlinkMap(Json.parseToJsonElement("{}")))
        assertTrue(LinkedSessionUnlinks.isUnlinkMap(Json.parseToJsonElement("""{"chat":{"a":1}}""")))
        assertFalse(LinkedSessionUnlinks.isUnlinkMap(null))
        assertFalse(LinkedSessionUnlinks.isUnlinkMap(JsonNull))
        assertFalse(LinkedSessionUnlinks.isUnlinkMap(Json.parseToJsonElement("[]")))
        assertFalse(LinkedSessionUnlinks.isUnlinkMap(Json.parseToJsonElement("""{"chat":["a"]}""")))
        assertFalse(LinkedSessionUnlinks.isUnlinkMap(Json.parseToJsonElement("""{"chat":{"a":"1"}}""")))
        assertFalse(
            LinkedSessionUnlinks.isUnlinkMap(
                JsonObject(mapOf("chat" to JsonObject(mapOf("a" to JsonPrimitive(Double.NaN))))),
            ),
        )
        val tooManyPerChat = JsonObject(
            mapOf(
                "chat" to JsonObject(
                    (0..LinkedSessionUnlinks.MAX_UNLINKS_PER_CHAT).associate { "s$it" to JsonPrimitive(it) },
                ),
            ),
        )
        assertFalse(LinkedSessionUnlinks.isUnlinkMap(tooManyPerChat))
        val tooManyChats = JsonObject(
            (0..LinkedSessionUnlinks.MAX_UNLINK_CHATS).associate { "c$it" to JsonObject(mapOf("x" to JsonPrimitive(1))) },
        )
        assertFalse(LinkedSessionUnlinks.isUnlinkMap(tooManyChats))
    }

    @Test
    fun `UiPrefs round-trips unlinkedSessions and drops malformed unlinkedSessions`() {
        val map = mapOf(
            "chat-1" to mapOf("peer-a" to 1_762_000_000_000L, "peer-b" to 0L),
            "chat-2" to mapOf("child-c" to 500L),
        )
        val prefs = UiPrefs(unlinkedSessions = map)
        val encoded = prefs.toJson().toString()
        assertEquals(prefs, UiPrefs.from(Json.parseToJsonElement(encoded)))

        val bad = UiPrefs.from(Json.parseToJsonElement("""{"unlinkedSessions":{"chat":["wrong"]}}"""))
        assertEquals(emptyMap<String, Map<String, Long>>(), bad.unlinkedSessions)
    }

    @Test
    fun `sessionLinks filters unknown relations and stopChildSessions posts to stop-children`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder().body(
                """{"ok":true,"data":[
                    {"sessionId":"parent-1","relations":["parent","unknown","parent"]},
                    {"sessionId":"child-2","relations":["child"]},
                    {"sessionId":"","relations":["messaged"]},
                    {"sessionId":"bad-3","relations":["bogus"]}
                ]}""",
            ).build(),
        )
        server.enqueue(MockResponse.Builder().body("""{"ok":true,"data":{}}""").build())
        server.start()
        try {
            val api = ApiClient(server.url("/"), "tok")
            val links = api.sessionLinks("my app", "s/1")
            assertEquals(
                listOf(
                    SessionLink("parent-1", listOf(SessionLinkRelation.PARENT)),
                    SessionLink("child-2", listOf(SessionLinkRelation.CHILD)),
                ),
                links,
            )
            val linksReq = server.takeRequest()
            assertEquals("GET", linksReq.method)
            assertEquals("/api/project/my%20app/chat/sessions/s%2F1/links", linksReq.target)

            api.stopChildSessions("my app", "s/1")
            val stopReq = server.takeRequest()
            assertEquals("POST", stopReq.method)
            assertEquals("/api/project/my%20app/chat/sessions/s%2F1/stop-children", stopReq.target)
        } finally {
            server.close()
        }
    }
}
