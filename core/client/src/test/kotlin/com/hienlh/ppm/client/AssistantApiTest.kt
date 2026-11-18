package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.InlineImage
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The PPM Assistant's requests (`assistant-cards-api.ts`, `use-manager-chats.ts`, the main session). */
class AssistantApiTest {
    private fun <T> withServer(vararg responses: MockResponse, block: suspend (MockWebServer, ApiClient) -> T): T = runBlocking {
        val server = MockWebServer()
        responses.forEach(server::enqueue)
        server.start()
        try {
            block(server, ApiClient(server.url("/"), "t"))
        } finally {
            server.close()
        }
    }

    private fun ok(data: String) = MockResponse.Builder().body("""{"ok":true,"data":$data}""").build()

    private val card = """{"id":"c1","kind":"needs_decision","projectName":"web","projectKey":"k","sessionId":"m1","title":"Ship it?",
        |"body":"b","payload":{"draft":{"to":"m1","text":"go","sha256":"abc"},"options":[{"id":"a","label":"A"}]},"status":"open",
        |"answer":null,"provenance":"Written by the web manager AI","okOffered":true,"createdAt":5,"updatedAt":6}""".trimMargin()

    @Test
    fun `cards are paged by time and filtered by project, waiting cards and counts read whole`() = withServer(
        ok("""{"cards":[$card],"nextBefore":4}"""),
        ok("""{"cards":[$card]}"""),
        ok("""{"total":3,"byProject":{"web":2,"api":1}}"""),
    ) { server, api ->
        val page = api.assistantCards(before = 10, project = "web")
        assertEquals("GET /api/assistant/cards?before=10&limit=30&project=web", server.takeRequest().let { "${it.method} ${it.target}" })
        assertEquals(4L, page.nextBefore)
        val c = page.cards.single()
        assertEquals("Ship it?", c.title)
        assertEquals("abc", c.payload.draft?.sha256)
        assertEquals("A", c.payload.options.single().label)
        assertTrue(c.isWaiting)
        assertEquals(1, api.assistantOpenCards().size)
        assertEquals("GET /api/assistant/cards/open", server.takeRequest().let { "${it.method} ${it.target}" })
        val waiting = api.assistantWaiting()
        assertEquals(3, waiting.total)
        assertEquals(2, waiting.byProject["web"])
    }

    @Test
    fun `OK, choose and reply send the device and what the card showed`() = withServer(
        ok("""{"card":$card,"to":"manager","projectName":"web"}"""),
        ok("""{"card":$card,"to":"manager"}"""),
        ok("""{"card":$card,"to":"assistant"}"""),
    ) { server, api ->
        val press = api.okAssistantCard("c1", "abc")
        val okReq = server.takeRequest()
        assertEquals("POST /api/assistant/cards/c1/ok", "${okReq.method} ${okReq.target}")
        val okBody = WireJson.parse(okReq.body!!.utf8())
        assertEquals("abc", okBody["draftSha256"].string)
        assertEquals(ChatClientId.value, okBody["deviceId"].string)
        assertEquals("manager", press.to)
        assertEquals("web", press.projectName)

        api.chooseAssistantCardOption("c1", "a")
        assertEquals("a", WireJson.parse(server.takeRequest().body!!.utf8())["optionId"].string)

        api.replyToAssistantCard("c1", "hello", force = true, images = listOf(InlineImage("QUJD", "image/png")), confirmAge = true)
        val reply = server.takeRequest()
        assertEquals("POST /api/assistant/cards/c1/reply", "${reply.method} ${reply.target}")
        val body = WireJson.parse(reply.body!!.utf8())
        assertEquals("hello", body["text"].string)
        assertEquals("true", body["force"].toString())
        assertEquals("true", body["confirmAge"].toString())
        assertEquals("image/png", body["images"]?.let { (it as kotlinx.serialization.json.JsonArray)[0]["mediaType"].string })
    }

    @Test
    fun `a refused reply carries the question and the card as it is now`() = withServer(
        MockResponse.Builder().code(409).body("""{"ok":false,"error":"Stale","data":{"question":"Send anyway?","card":$card}}""").build(),
    ) { _, api ->
        val refusal = CardRefusal.of(runCatching { api.replyToAssistantCard("c1", "x") }.exceptionOrNull()!!)
        assertEquals("Stale", refusal.error)
        assertEquals("Send anyway?", refusal.question)
        assertEquals("c1", refusal.card?.id)
    }

    @Test
    fun `answers, dismissals and rules decisions go to their own routes`() = withServer(
        ok("""{"card":$card}"""), ok("""{"card":$card}"""), ok("""{"card":$card}"""), ok("{}"),
    ) { server, api ->
        api.answerAssistantCard("c1", allow = false)
        assertEquals("""{"decision":"deny"}""", server.takeRequest().body!!.utf8())
        api.answerAssistantQuestions("c1", mapOf("q1" to listOf("Yes")))
        assertEquals("""{"answersById":{"q1":["Yes"]}}""", server.takeRequest().body!!.utf8())
        api.dismissAssistantCard("c1")
        assertEquals("POST /api/assistant/cards/c1/dismiss", server.takeRequest().let { "${it.method} ${it.target}" })
        api.decideRulesChange("my app", approve = true, hash = "h1")
        val rules = server.takeRequest()
        assertEquals("POST /api/assistant/managers/my%20app/rules/decision", "${rules.method} ${rules.target}")
        assertEquals("""{"action":"approve","hash":"h1"}""", rules.body!!.utf8())
    }

    @Test
    fun `the main session, manager chats and the assign check`() = withServer(
        ok("""{"main":{"sessionId":"s1"}}"""),
        ok("""{"main":null}"""),
        ok("true"),
        ok("""{"managers":[{"projectName":"web","projectPath":"/w","sessionId":"m1","providerId":"claude","title":null,"phase":"running","permissionMode":"default","digest":true,"waiting":2,"rulesPending":false}]}"""),
        ok("""{"permissionMode":"bypassPermissions","replaces":{"sessionId":"m0","title":"Old"},"already":false}"""),
        ok("true"),
        ok("true"),
    ) { server, api ->
        assertEquals("claude", api.assistantMainSession()?.providerId)
        server.takeRequest()
        assertNull(api.assistantMainSession())
        server.takeRequest()
        api.setAssistantMainSession("s2")
        assertEquals("""{"sessionId":"s2"}""", server.takeRequest().body!!.utf8())
        val manager = api.managerChats().single()
        assertEquals("running", manager.phase)
        assertEquals(2, manager.waiting)
        server.takeRequest()
        val check = api.checkManagerChat("web", "s9")
        assertEquals("GET /api/assistant/managers/web/check?sessionId=s9", server.takeRequest().let { "${it.method} ${it.target}" })
        assertEquals("Old", check.replacesTitle)
        assertFalse(check.already)
        api.setManagerChat("web", "s9")
        assertEquals("PUT /api/assistant/managers/web", server.takeRequest().let { "${it.method} ${it.target}" })
        api.unsetManagerChat("web")
        assertEquals("DELETE /api/assistant/managers/web", server.takeRequest().let { "${it.method} ${it.target}" })
    }

    @Test
    fun `the chat socket names this app, and only an id of the right shape`() {
        val api = ApiClient("http://host:8080/".toHttpUrl(), "t")
        assertEquals(
            "ws://host:8080/ws/project/__assistant__/chat/s-1?providerId=claude&clientId=abcd-1234&token=t",
            api.chatSocketUrl("__assistant__", "s-1", "claude", "abcd-1234"),
        )
        assertEquals("ws://host:8080/ws/project/p/chat/s?providerId=claude&token=t", api.chatSocketUrl("p", "s", "claude", "bad id!"))
        assertTrue(ChatClientId.valid(ChatClientId.value))
    }
}
