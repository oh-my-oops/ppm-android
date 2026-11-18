package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.VaultCardAnswers
import com.hienlh.ppm.protocol.VaultTiers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** `/api/vault` (`vault-client.ts`): the routes, what each sends, and how a refusal reads. */
class VaultApiTest {
    private fun withServer(vararg responses: MockResponse, block: suspend (MockWebServer, ApiClient) -> Unit) = runBlocking {
        val server = MockWebServer()
        responses.forEach(server::enqueue)
        server.start()
        try {
            block(server, ApiClient(server.url("/"), "tok"))
        } finally {
            server.close()
        }
    }

    private fun ok(data: String) = MockResponse.Builder().body("""{"ok":true,"data":$data}""").build()

    private fun refused(code: Int, body: String) = MockResponse.Builder().code(code).body(body).build()

    private fun json(text: String?) = Json.parseToJsonElement(text!!)

    @Test
    fun `status and items read the tiers and the names, never a value`() = withServer(
        ok("""{"publicCount":1,"tiers":{"secure":{"enabled":true,"itemCount":2,"lockedUntil":0,"attemptsLeft":5,"settingsOpenUntil":1700000300000},"sealed":{"enabled":false,"itemCount":0,"lockedUntil":0,"attemptsLeft":5,"settingsOpenUntil":0}}}"""),
        ok("""[{"id":"a","tier":"secure","name":"NI","kind":"ni_number","createdAt":1,"updatedAt":2},{"id":"b","tier":"bogus","name":"x","kind":"other"}]"""),
    ) { server, api ->
        val status = api.vaultStatus()
        assertTrue(status.secure.enabled)
        assertEquals(2, status.secure.itemCount)
        assertEquals(1_700_000_300_000, status.secure.settingsOpenUntil)
        assertFalse(status.sealed.enabled)
        assertEquals("/api/vault/status", server.takeRequest().target)
        val items = api.vaultItems()
        assertEquals(listOf("a"), items.map { it.id })
        assertEquals("ni_number", items[0].kind)
    }

    @Test
    fun `an item action carries this app's settings handle and the password only when one was typed`() = withServer(
        ok("""{"value":"AB123456C"}"""),
        ok("""{"value":"plain"}"""),
        ok("{}"),
    ) { server, api ->
        assertEquals("AB123456C", api.vaultReveal("id/1", "correct horse battery"))
        val reveal = server.takeRequest()
        assertEquals("/api/vault/items/id%2F1/reveal", reveal.target)
        val body = json(reveal.body!!.utf8()).jsonObject
        assertEquals(VAULT_SETTINGS_HANDLE, body["handle"]!!.jsonPrimitive.content)
        assertEquals("correct horse battery", body["password"]!!.jsonPrimitive.content)
        assertEquals(48, VAULT_SETTINGS_HANDLE.length)

        api.vaultReveal("p1", "")
        assertFalse("password" in json(server.takeRequest().body!!.utf8()).jsonObject)

        api.vaultMove("a", VaultTiers.SEALED, mapOf("secure" to "", "sealed" to "sealed password!"))
        val move = server.takeRequest()
        assertEquals("/api/vault/items/a/move", move.target)
        assertEquals(json("""{"to":"sealed","passwords":{"sealed":"sealed password!"},"handle":"$VAULT_SETTINGS_HANDLE"}"""), json(move.body!!.utf8()))
    }

    @Test
    fun `every password sent and value received is kept out of the trace`() = withServer(
        ok("{}"),
        ok("""{"value":"TOP-SECRET-VALUE"}"""),
        ok("""{"approved":true,"shown":{"name":"Passport","value":"SEALED-VALUE-1"}}"""),
    ) { _, api ->
        api.vaultEnableTier(VaultTiers.SECURE, "enable password 1")
        api.vaultReveal("x", "reveal password 2")
        val result = api.answerVaultCard("r1", VaultCardAnswers.readSealed("card password 3", giveToAi = false, allowGiveToAi = true))
        assertEquals("SEALED-VALUE-1", result.shownValue)
        assertEquals("Passport", result.shownName)
        val text = "enable password 1 reveal password 2 card password 3 TOP-SECRET-VALUE SEALED-VALUE-1"
        val redacted = TraceRedactor.Global.redact(text)
        for (secret in listOf("enable password 1", "reveal password 2", "card password 3", "TOP-SECRET-VALUE", "SEALED-VALUE-1")) {
            assertFalse("$secret leaked", redacted.contains(secret))
        }
    }

    @Test
    fun `tier routes and the card routes`() = withServer(
        ok("""{"openUntil":1700000300000}"""),
        ok("{}"),
        ok("{}"),
        ok("{}"),
        ok("""{"value":"prefilled"}"""),
        ok("""{"approved":false}"""),
    ) { server, api ->
        assertEquals(1_700_000_300_000, api.vaultUnlockSecure("pw-unlock-1234"))
        assertEquals("/api/vault/tiers/secure/unlock", server.takeRequest().target)
        api.vaultLock(VaultTiers.SECURE)
        val lock = server.takeRequest()
        assertEquals("/api/vault/lock", lock.target)
        assertEquals(json("""{"tier":"secure"}"""), json(lock.body!!.utf8()))
        api.vaultChangePassword(VaultTiers.SEALED, "old-pw-123456789", "new-pw-123456789")
        assertEquals("/api/vault/tiers/sealed/password", server.takeRequest().target)
        api.vaultDeleteTier(VaultTiers.SEALED, "pw-delete-1234")
        assertEquals("/api/vault/tiers/sealed/delete", server.takeRequest().target)
        assertEquals("prefilled", api.vaultCardPrefill("card 1"))
        assertEquals("/api/vault/cards/card%201/prefill", server.takeRequest().target)
        assertFalse(api.answerVaultCard("card 1", VaultCardAnswers.decline()).approved)
        val decline = server.takeRequest()
        assertEquals("POST /api/vault/cards/card%201", "${decline.method} ${decline.target}")
        assertEquals(json("""{"approved":false}"""), json(decline.body!!.utf8()))
    }

    @Test
    fun `a refusal says attempts left, a lock-out counts down, and an ended unlock asks for the password`() = withServer(
        refused(403, """{"ok":false,"error":"Wrong password.","attemptsLeft":1}"""),
        refused(423, """{"ok":false,"error":"Too many wrong passwords.","retryAfterMs":41500}"""),
        refused(423, """{"ok":false,"error":"The Secure tier is locked."}"""),
    ) { _, api ->
        fun failure(block: suspend () -> Unit): VaultFailure = runBlocking {
            try {
                block()
                fail("expected a refusal")
                error("unreachable")
            } catch (e: PpmError.Server) {
                VaultFailure.of(e, now = 1_000)
            }
        }
        val wrong = failure { api.vaultReveal("a", "nope") }
        assertEquals("Wrong password. 1 attempt left.", wrong.text(1_000))
        assertFalse(wrong.needsPassword)

        val locked = failure { api.vaultReveal("a", "nope") }
        assertEquals(42_500L, locked.lockedUntil)
        assertEquals("Too many wrong passwords. Try again in 42 s.", locked.text(1_000))
        assertTrue(locked.lockedOut(1_000))
        assertFalse(locked.needsPassword)

        val ended = failure { api.vaultReveal("a", null) }
        assertTrue(ended.needsPassword)
        assertNull(ended.detail(1_000))
    }

    @Test
    fun `a countdown reads minutes and seconds`() {
        assertEquals("4:12", vaultCountdown(251_200))
        assertEquals("0:00", vaultCountdown(-5))
    }
}
