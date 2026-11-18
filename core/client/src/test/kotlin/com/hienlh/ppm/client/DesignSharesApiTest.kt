package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** `api-design-shares.ts` and `api-share-host.ts`: paths, bodies and the tolerant reads. */
class DesignSharesApiTest {
    private fun ok(data: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json")
        .body("""{"ok":true,"data":$data}""").build()

    private fun fail(error: String, code: Int) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json")
        .body("""{"ok":false,"error":"$error","code":"limit"}""").build()

    private fun withServer(vararg responses: MockResponse, block: suspend (MockWebServer, ApiClient) -> Unit) = runBlocking {
        val server = MockWebServer()
        responses.forEach(server::enqueue)
        server.start()
        try { block(server, ApiClient(server.url("/"), "secret")) } finally { server.close() }
    }

    private val link = """{"id":"l1","label":"Client","snapshotId":"s1","snapshotCreatedAt":"2026-11-18T08:00:00.000Z",
        "createdAt":1000,"updatedAt":2000,"expiresAt":null,"revokedAt":null,"status":"active","path":"/s/abc","future":1}"""

    @Test fun listsCreatesAndUpdatesLinks() = withServer(
        ok("""{"links":[$link,{"label":"no id"}]}"""),
        ok("{}"),
        ok("""{"link":$link}""", 201),
        ok("""{"link":$link,"changedFiles":{"added":["a.css"],"removed":[],"changed":["index.html"],"truncated":true}}"""),
        ok("""{"link":$link}"""),
    ) { server, api ->
        val links = api.designShares("my app", "hero v2")
        assertEquals(1, links.size)
        val first = links.single()
        assertEquals("Client", first.label)
        assertNull(first.expiresAt)
        assertEquals("/s/abc", first.path)
        assertEquals("2026-11-18T08:00:00.000Z", first.snapshotCreatedAt)
        val get = server.takeRequest()
        assertEquals("/api/project/my%20app/designs/hero%20v2/shares", get.target)
        assertEquals("Bearer secret", get.headers["Authorization"])

        assertTrue(api.designShares("p", "d").isEmpty())
        server.takeRequest()

        api.designShareCreate("p", "d", label = null, expiresAt = null, version = "current")
        val create = server.takeRequest()
        assertEquals("POST", create.method)
        val body = Json.parseToJsonElement(create.body!!.utf8()).jsonObject
        assertEquals(JsonNull, body["expiresAt"])
        assertEquals("current", body["version"]!!.toString().trim('"'))
        assertFalse("label" in body)

        val repinned = api.designShareUpdate("p", "d", "l/1", DesignSharePatch(repin = true))
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/api/project/p/designs/d/shares/l%2F1", patch.target)
        assertEquals("""{"repin":true}""", patch.body!!.utf8())
        assertEquals(listOf("a.css"), repinned.changedFiles!!.added)
        assertTrue(repinned.changedFiles!!.truncated)
        assertEquals(2, repinned.changedFiles!!.count)

        val renamed = api.designShareUpdate("p", "d", "l1", DesignSharePatch(label = "x", expiry = DesignSharePatch.Expiry(null)))
        assertEquals("""{"label":"x","expiresAt":null}""", server.takeRequest().body!!.utf8())
        assertNull(renamed.changedFiles)
    }

    @Test fun changesDeletesAddressAndVersions() = withServer(
        ok("""{"changedFiles":null}"""),
        ok("""{"changedFiles":{"added":[],"removed":["old.png"],"changed":[],"truncated":false}}"""),
        ok("""{"deleted":3}"""),
        ok("""{"deleted":"l1"}"""),
        ok("""{"kind":"quick","url":"https://x.trycloudflare.com","warning":null,"via":"share-host","enabled":false,"authEnabled":true}"""),
        ok("""[{"id":"s2","reason":"turn","createdAt":"2026-11-18T09:00:00Z"},{"reason":"manual"}]"""),
        fail("This design already has 20 share links", 409),
    ) { server, api ->
        assertNull(api.designShareChanges("p", "d", "l1"))
        assertEquals("/api/project/p/designs/d/shares/l1/changes", server.takeRequest().target)
        assertEquals(listOf("old.png"), api.designShareChanges("p", "d", "l1")!!.removed)
        server.takeRequest()
        assertEquals(3, api.designShareDeleteComments("p", "d", "l1"))
        val purge = server.takeRequest()
        assertEquals("DELETE", purge.method)
        assertEquals("/api/project/p/designs/d/shares/l1/comments", purge.target)
        api.designShareDelete("p", "d", "l1")
        assertEquals("/api/project/p/designs/d/shares/l1", server.takeRequest().target)
        val address = api.designShareAddress()
        assertEquals("quick", address.kind)
        assertEquals("share-host", address.via)
        assertEquals("/api/design-shares/address", server.takeRequest().target)
        val versions = api.designShareVersions("p", "d")
        assertEquals(listOf(DesignShareVersion("s2", "2026-11-18T09:00:00Z", "turn")), versions)
        assertEquals("/api/project/p/designs/d/history", server.takeRequest().target)
        try {
            api.designShareCreate("p", "d", "L", 5L, "s2")
            fail("a refused create throws")
        } catch (error: PpmError.Server) {
            assertEquals("This design already has 20 share links", error.message)
            assertEquals("limit", error.code)
        }
    }

    @Test fun shareHostRoutes() = withServer(
        ok("""{"enabled":true,"mode":"shared","hostname":"share.example.com","tunnelName":"ppm","canApply":true,"supervised":true,
            "running":true,"connected":false,"url":"https://share.example.com","warning":"reconnecting",
            "ppmTunnel":{"named":true,"hostname":"ppm.example.com","enabled":false},"certState":"ok","authEnabled":true}"""),
        ok("""{"zone":"example.com","proposedHostname":"ppm.example.com"}"""),
        ok("""{"applied":true}"""),
        ok("""{"applied":false,"message":"Run ppm restart"}"""),
        ok("""{"hostname":"share.example.com","tunnelName":"ppm-share-x","applied":{"applied":true}}"""),
    ) { server, api ->
        val status = api.shareHostStatus()
        assertEquals("shared", status.mode)
        assertTrue(status.ppmTunnel.named)
        assertEquals("ppm.example.com", status.ppmTunnel.hostname)
        assertEquals("ok", status.certState)
        assertFalse(status.connected)
        assertEquals("/api/share-host/status", server.takeRequest().target)
        assertEquals("example.com", api.shareHostZone().zone)
        server.takeRequest()
        assertTrue(api.shareHostSetEnabled(true).applied)
        val enable = server.takeRequest()
        assertEquals("/api/share-host/enabled", enable.target)
        assertEquals("""{"enabled":true}""", enable.body!!.utf8())
        val quick = api.shareHostUseTemporary()
        assertFalse(quick.applied)
        assertEquals("Run ppm restart", quick.message)
        assertEquals("/api/share-host/quick", server.takeRequest().target)
        val setup = api.shareHostSetup("named", "share.example.com")
        assertEquals("ppm-share-x", setup.tunnelName)
        assertTrue(setup.applied.applied)
        assertEquals("""{"mode":"named","hostname":"share.example.com"}""", server.takeRequest().body!!.utf8())
    }
}
