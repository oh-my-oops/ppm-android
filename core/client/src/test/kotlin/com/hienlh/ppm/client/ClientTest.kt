package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** The HTTP client's own rules, without a server (`PPMSelfTest/ClientTests.swift`). */
class ClientTest {
    private fun failure(body: String, status: Int, contentType: String = "application/json"): PpmError? = try {
        ApiClient.unwrap(status, contentType, body)
        null
    } catch (error: PpmError) {
        error
    }

    @Test
    fun `the envelope's data, or its error with the code`() {
        assertEquals(1, ApiClient.unwrap(200, "application/json", """{"ok":true,"data":{"a":1}}""")["a"].int)
        assertEquals(
            PpmError.Server(428, "Log in first", "DB_LOGIN_REQUIRED"),
            failure("""{"ok":false,"error":"Log in first","code":"DB_LOGIN_REQUIRED"}""", 428),
        )
        assertEquals(PpmError.Server(400, "Nope"), failure("""{"ok":false,"error":"Nope"}""", 400))
        assertEquals(PpmError.Unauthorized(), failure("""{"ok":false,"error":"x"}""", 401))
    }

    @Test
    fun `a page where JSON was due is an older server, other junk is not`() {
        assertEquals(PpmError.OutdatedServer(), failure("<!doctype html><html></html>", 200, "text/html; charset=utf-8"))
        assertEquals(PpmError.BadResponse("The server answered HTTP 502."), failure("Bad gateway", 502, "text/plain"))
        assertEquals(PpmError.BadResponse("This address answers, but not like a PPM server."), failure("{}", 200))
    }

    @Test
    fun `requests say which app is asking, and a GET has its own timeout`() {
        val api = ApiClient("http://ppm.test:8080".toHttpUrl(), "t")
        val get = api.request("GET", "/api/projects")
        assertEquals("android", get.header("x-ppm-client"))
        assertEquals("Bearer t", get.header("Authorization"))
        assertEquals(PpmHttp.GET_TIMEOUT_SECONDS * 1000, PpmHttp.getClient.readTimeoutMillis.toLong())
        assertTrue("a POST waits as long as the work takes", PpmHttp.client.readTimeoutMillis > PpmHttp.getClient.readTimeoutMillis)
    }

    @Test
    fun `socket URLs escape the project and the password, and keep a base path`() {
        val api = ApiClient("http://host:8080/".toHttpUrl(), "p+w&d/=")
        assertEquals(
            "ws://host:8080/ws/project/my%20app/chat/s-1?providerId=claude&token=p%2Bw%26d%2F%3D",
            api.chatSocketUrl("my app", "s-1", "claude"),
        )
        val behindProxy = ApiClient("https://host/ppm/".toHttpUrl(), "p+w&d/=")
        assertEquals("wss://host/ppm/ws/global?token=p%2Bw%26d%2F%3D", behindProxy.globalSocketUrl())
    }

    @Test
    fun `a typed server address is normalized`() {
        // OkHttp always prints a root path, where Foundation's URL prints none.
        assertEquals("http://192.168.1.5:8080/", ApiClient.normalizedServerUrl(" 192.168.1.5:8080/ ").toString())
        assertEquals("https://x.trycloudflare.com/", ApiClient.normalizedServerUrl("https://x.trycloudflare.com").toString())
        assertNull(ApiClient.normalizedServerUrl("ftp://x"))
        assertNull(ApiClient.normalizedServerUrl("   "))
    }

    @Test
    fun `multipart puts every file under files and keeps the header intact`() {
        val body = multipart(
            listOf(Upload("a\"b\n.png", "image/png", byteArrayOf(1, 2)), Upload("c.txt", "text/plain", "hi".toByteArray())),
            boundary = "B",
        )
        val text = String(body, Charsets.UTF_8)
        assertTrue(text, text.startsWith("--B\r\nContent-Disposition: form-data; name=\"files\"; filename=\"a%22b.png\"\r\nContent-Type: image/png\r\n\r\n"))
        assertTrue(text, text.contains("\r\n--B\r\nContent-Disposition: form-data; name=\"files\"; filename=\"c.txt\"\r\nContent-Type: text/plain\r\n\r\nhi\r\n"))
        assertTrue(text, text.endsWith("--B--\r\n"))
    }

    @Test
    fun `identical GETs at once share one request`() = runBlocking {
        val started = AtomicInteger()
        val first = async {
            InflightGets.value("k") {
                started.incrementAndGet()
                delay(200)
                JsonPrimitive("answer")
            }
        }
        delay(20)
        val second = InflightGets.value("k") {
            started.incrementAndGet()
            JsonPrimitive("second request")
        }
        assertEquals(JsonPrimitive("answer"), first.await())
        assertEquals(JsonPrimitive("answer"), second)
        assertEquals(1, started.get())
        // Settled: the next caller sends its own.
        val third = InflightGets.value("k") {
            started.incrementAndGet()
            JsonPrimitive("fresh")
        }
        assertEquals(JsonPrimitive("fresh"), third)
        assertEquals(2, started.get())
    }

    @Test
    fun `servers are kept with their passwords apart`() {
        val values = HashMap<String, String>()
        val secrets = HashMap<String, String>()
        val keyValues = object : KeyValueStore {
            override fun get(key: String) = values[key]
            override fun put(key: String, value: String?) {
                if (value == null) values.remove(key) else values[key] = value
            }
        }
        val secretStore = object : SecretStore {
            override fun read(account: String) = secrets[account]
            override fun write(account: String, secret: String) {
                secrets[account] = secret
            }
            override fun delete(account: String) {
                secrets.remove(account)
            }
        }
        val store = ServerStore(keyValues, secretStore)
        val home = store.add("Home", "http://10.0.2.2:8080", "secret")
        val work = store.add("Work", "https://work.example", "other")
        assertEquals(work.id, store.selectedId.value)
        assertTrue("a password reached the plain values", values.values.none { it.contains("secret") })

        // What a restarted app reads back.
        val reopened = ServerStore(keyValues, secretStore)
        assertEquals(listOf("Home", "Work"), reopened.servers.value.map { it.name })
        assertEquals(work.id, reopened.selectedId.value)
        assertEquals("secret", reopened.token(home))

        // Log out: the password goes, the server and the selection stay.
        reopened.forgetToken(work)
        assertNull(reopened.token(work))
        assertEquals(listOf("Home", "Work"), reopened.servers.value.map { it.name })
        assertEquals(work.id, reopened.selectedId.value)
        assertEquals("secret", reopened.token(home))

        reopened.remove(work)
        assertEquals(home.id, reopened.selectedId.value)
        assertNull(secrets[work.id])
    }
}
