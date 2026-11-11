package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

/** `PATCH /api/accounts/:id {label}`, as `account-rename-dialog.tsx` saves a name. */
class AccountRenameApiTest {
    private fun json(body: String, status: Int = 200) =
        MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build()

    private fun <T> withServer(block: suspend (MockWebServer, ApiClient) -> T): T = runBlocking {
        val server = MockWebServer()
        server.start()
        try { block(server, ApiClient(server.url("/"), "t")) } finally { server.close() }
    }

    @Test fun `a rename patches the account with only its label`() = withServer { server, api ->
        server.enqueue(json("""{"ok":true,"data":{"id":"a b","label":"Work"}}"""))
        api.renameAccount("a b", "Work")
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("PATCH", request.method)
        assertEquals("/api/accounts/a%20b", request.url.encodedPath)
        assertEquals("""{"label":"Work"}""", request.body?.utf8())
    }

    @Test fun `the server's refusal reaches the caller`() = withServer { server, api ->
        server.enqueue(json("""{"ok":false,"error":"Label is too long"}""", 400))
        try {
            api.renameAccount("acc", "x")
            fail("expected a refusal")
        } catch (error: PpmError.Server) {
            assertEquals("Label is too long", error.message)
        }
    }
}
