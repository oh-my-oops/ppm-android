package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.AccountPick
import com.hienlh.ppm.protocol.PickedAccount
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The requests a chat with no session makes before its first message (new-chat-prepare-client.ts, use-chat-prewarm.ts). */
class ChatPrepareTest {
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

    private val prepared = """{"ok":true,"data":{"resolvedProviderId":"claude","providerId":"claude",
        "settings":{"default_provider":"claude","providers":{}},"providers":[{"id":"claude","name":"Claude"}],
        "pickedAccount":{"id":"acc-1","label":"Work"},"usage":null,"draft":null,"tags":null,"slash":null}}"""

    @Test
    fun `a tab that knows its provider names it, one still resolving passes the focused chat's`() = withServer(
        MockResponse.Builder().body(prepared).build(),
        MockResponse.Builder().body(prepared).build(),
    ) { server, api ->
        val result = api.prepareChat("my app", providerId = "claude", focusedProvider = "codex", skipPick = false)
        assertEquals(AccountPick.Picked(PickedAccount("acc-1", "Work")), result.pickedAccount)
        val named = server.takeRequest()
        assertEquals("POST /api/project/my%20app/chat/prepare", "${named.method} ${named.target}")
        assertEquals("""{"providerId":"claude","skipPick":false}""", named.body?.utf8())
        api.prepareChat("my app", providerId = null, focusedProvider = "codex", skipPick = true)
        assertEquals("""{"focusedProvider":"codex","skipPick":true}""", server.takeRequest().body?.utf8())
    }

    @Test
    fun `without a preparation, a new chat reads only the provider part of the AI settings`() = withServer(
        MockResponse.Builder().body(
            """{"ok":true,"data":{"default_provider":"codex","new_chat_provider_mode":"follow-focus","max_turns":0,
                "providers":{"claude":{"type":"agent-sdk","permission_mode":"plan"},"codex":{"type":"cli"}}}}""",
        ).build(),
    ) { server, api ->
        val settings = api.chatPreparationSettings()
        assertEquals("GET /api/settings/ai", server.takeRequest().let { "${it.method} ${it.target}" })
        assertEquals("codex", settings.resolveProvider(null))
        assertEquals("claude", settings.resolveProvider("claude"))
        assertEquals(mapOf("claude" to "plan"), settings.permissionModes)
    }

    @Test
    fun `a prewarm carries only the picks that were made`() = withServer(
        MockResponse.Builder().code(202).body("""{"ok":true,"data":{"accepted":true}}""").build(),
        MockResponse.Builder().code(202).body("""{"ok":true,"data":{"accepted":true}}""").build(),
    ) { server, api ->
        api.prewarmChat("p", "claude", "bypassPermissions", "acc-1", model = "opus", effort = "high", thinking = true)
        val full = server.takeRequest()
        assertEquals("POST /api/project/p/chat/prewarm", "${full.method} ${full.target}")
        assertEquals(
            """{"providerId":"claude","permissionMode":"bypassPermissions","accountId":"acc-1","model":"opus","effort":"high","thinking":true}""",
            full.body?.utf8(),
        )
        api.prewarmChat("p", "claude", null, null)
        assertEquals("""{"providerId":"claude"}""", server.takeRequest().body?.utf8())
    }

    @Test
    fun `an account is picked from its provider's pool for Claude, Codex and Antigravity`() = withServer(
        MockResponse.Builder().body("""{"ok":true,"data":{"id":"acc-2","label":null}}""").build(),
        MockResponse.Builder().body("""{"ok":true,"data":null}""").build(),
        MockResponse.Builder().body("""{"ok":true,"data":{"id":"default","label":"Alice · Pro"}}""").build(),
    ) { server, api ->
        assertEquals(PickedAccount("acc-2", null), api.pickAccount("claude"))
        assertEquals("POST /api/accounts/pick", server.takeRequest().let { "${it.method} ${it.target}" })
        assertNull(api.pickAccount("codex"))
        assertEquals("POST /api/codex-accounts/pick", server.takeRequest().let { "${it.method} ${it.target}" })
        assertEquals(PickedAccount("default", "Alice · Pro"), api.pickAccount("antigravity"))
        assertEquals("POST /api/antigravity-accounts/pick", server.takeRequest().let { "${it.method} ${it.target}" })
        assertNull(api.pickAccount("mock"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a session starts on the account the tab claimed`() = withServer(
        MockResponse.Builder().body("""{"ok":true,"data":{"id":"s1","providerId":"claude","title":"Hello","createdAt":"2026-10-21T12:00:00Z"}}""").build(),
    ) { server, api ->
        api.createSession("p", "claude", title = "Hello", accountId = "acc-1")
        assertEquals("""{"providerId":"claude","title":"Hello","accountId":"acc-1"}""", server.takeRequest().body?.utf8())
    }
}
