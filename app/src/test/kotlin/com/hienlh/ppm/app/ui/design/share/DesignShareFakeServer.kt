package com.hienlh.ppm.app.ui.design.share

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A PPM that answers the Share dialog's and Review links' routes from state a test sets, and
 * records what was sent. Nothing here reaches Cloudflare: every tunnel and sign-in route is a
 * scripted answer.
 */
class DesignShareFakeServer {
    data class Sent(val method: String, val path: String, val body: String)

    val sent = CopyOnWriteArrayList<Sent>()

    @Volatile var address = """{"kind":"named","url":"https://ppm.example.com","via":"ppm","enabled":true,"authEnabled":true}"""

    @Volatile var links = mutableListOf(link("l1", "Client review"))

    @Volatile var shareHostEnabled = false

    @Volatile var shareHostMode = "quick"

    @Volatile var certState = "ok"

    @Volatile var ppmTunnelNamed = false

    @Volatile var authEnabled = true

    @Volatile var changes = """{"added":["b.png"],"removed":[],"changed":["index.html"],"truncated":false}"""

    private var created = 0

    val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        }
    }

    fun start(): DesignShareFakeServer = apply { server.start() }

    fun close() = server.close()

    fun sentTo(method: String, path: String): List<Sent> = sent.filter { it.method == method && it.path == path }

    private fun ok(data: String) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body("""{"ok":true,"data":$data}""")
        .build()

    private fun status(): String = """{
        "enabled":$shareHostEnabled,"mode":"$shareHostMode","hostname":${if (shareHostMode == "quick") "null" else "\"share.example.com\""},
        "tunnelName":null,"canApply":true,"supervised":true,"running":$shareHostEnabled,"connected":$shareHostEnabled,
        "url":${if (shareHostEnabled) "\"https://review.trycloudflare.com\"" else "null"},"warning":null,
        "ppmTunnel":{"named":$ppmTunnelNamed,"hostname":${if (ppmTunnelNamed) "\"ppm.example.com\"" else "null"},"enabled":true},
        "certState":"$certState","authEnabled":$authEnabled}"""

    private fun answer(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        val body = request.body?.utf8().orEmpty()
        sent += Sent(request.method ?: "", path, body)
        val shares = "/api/project/web/designs/landing/shares"
        return when {
            request.method == "GET" && path == "/api/design-shares/address" -> ok(address)
            request.method == "GET" && path == shares -> ok("""{"links":[${links.joinToString(",")}]}""")
            request.method == "POST" && path == shares -> {
                created++
                val id = "new$created"
                val label = Regex(""""label":"([^"]*)"""").find(body)?.groupValues?.get(1).orEmpty()
                val made = link(id, label)
                links.add(0, made)
                ok("""{"link":$made}""")
            }
            request.method == "GET" && path == "/api/project/web/designs/landing/history" -> ok(
                """[{"id":"snap-old","createdAt":"2026-11-10T08:00:00.000Z","reason":"manual"}]""",
            )
            request.method == "GET" && path.startsWith("$shares/") && path.endsWith("/changes") -> ok("""{"changedFiles":$changes}""")
            request.method == "DELETE" && path.startsWith("$shares/") && path.endsWith("/comments") -> ok("""{"deleted":3}""")
            request.method == "PATCH" && path.startsWith("$shares/") -> {
                val id = path.removePrefix("$shares/")
                val revoked = body.contains("\"revoke\":true")
                val label = Regex(""""label":"([^"]*)"""").find(body)?.groupValues?.get(1)
                val next = link(id, label ?: "Client review", status = if (revoked) "revoked" else "active")
                links = links.map { if (it.contains("\"id\":\"$id\"")) next else it }.toMutableList()
                val changed = if (body.contains("\"repin\":true")) ""","changedFiles":$changes""" else ""
                ok("""{"link":$next$changed}""")
            }
            request.method == "DELETE" && path.startsWith("$shares/") -> {
                val id = path.removePrefix("$shares/")
                links = links.filterNot { it.contains("\"id\":\"$id\"") }.toMutableList()
                ok("{}")
            }
            request.method == "GET" && path == "/api/share-host/status" -> ok(status())
            request.method == "POST" && path == "/api/share-host/enabled" -> {
                shareHostEnabled = body.contains("true")
                ok("""{"applied":true}""")
            }
            request.method == "POST" && path == "/api/share-host/quick" -> {
                shareHostMode = "quick"
                ok("""{"applied":true}""")
            }
            request.method == "GET" && path == "/api/share-host/zone" -> ok("""{"zone":"example.com","proposedHostname":"share.example.com"}""")
            request.method == "POST" && path == "/api/share-host/setup" -> {
                shareHostMode = if (body.contains("\"shared\"")) "shared" else "named"
                ok("""{"hostname":"share.example.com","tunnelName":"ppm-share","applied":{"applied":true}}""")
            }
            else -> MockResponse.Builder().code(404).body("""{"ok":false,"error":"not found"}""").build()
        }
    }

    companion object {
        fun link(id: String, label: String, status: String = "active"): String =
            """{"id":"$id","label":"$label","snapshotId":"s-$id","snapshotCreatedAt":"2026-11-17T08:00:00.000Z",""" +
                """"createdAt":1763366400000,"updatedAt":1763366400000,"expiresAt":null,"revokedAt":null,""" +
                """"status":"$status","path":"/s/$id-code"}"""
    }
}
