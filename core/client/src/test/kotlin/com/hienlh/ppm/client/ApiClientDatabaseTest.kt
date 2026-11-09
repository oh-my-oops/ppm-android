package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiClientDatabaseTest {
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

    private fun ok(data: String) = MockResponse.Builder()
        .addHeader("Content-Type", "application/json")
        .body("""{"ok":true,"data":$data}""")
        .build()

    @Test
    fun `dbLoginConnection throws DbLoginFailedException when server returns data ok false`() = withServer(
        ok("""{"ok":false,"error":"password authentication failed for user \"postgres\"","details":"FATAL: password authentication failed for user \"postgres\" (SQLSTATE 28P01)"}"""),
        ok("""{"ok":true,"version":"PostgreSQL 16.2","target":"localhost:5432/shop","elapsedMs":14}"""),
    ) { server, api ->
        val failed = try {
            api.dbLoginConnection(id = 3, password = "wrong", user = "postgres")
            null
        } catch (err: DbLoginFailedException) {
            err
        }
        assertNotNull("Expected DbLoginFailedException when data.ok is false", failed)
        assertEquals("Connect failed: password authentication failed for user \"postgres\"", failed!!.message)
        assertTrue(failed.details.contains("SQLSTATE 28P01"))

        val firstReq = server.takeRequest()
        assertEquals("POST /api/db/connections/3/login", "${firstReq.method} ${firstReq.target}")
        assertEquals("""{"password":"wrong","user":"postgres"}""", firstReq.body?.utf8())

        val succeeded = api.dbLoginConnection(id = 3, password = "correct")
        assertEquals("PostgreSQL 16.2", succeeded["version"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content })
    }

    @Test
    fun `dbMoveConnectionGroup uses PUT on the connection resource`() = withServer(
        ok("""{"id":7,"name":"Orders","type":"postgres","groupName":"Prod"}"""),
        ok("""{"id":7,"name":"Orders","type":"postgres","groupName":null}"""),
    ) { server, api ->
        api.dbMoveConnectionGroup(id = 7, groupName = "Prod")
        val first = server.takeRequest()
        assertEquals("PUT /api/db/connections/7", "${first.method} ${first.target}")
        assertEquals("""{"groupName":"Prod"}""", first.body?.utf8())

        api.dbMoveConnectionGroup(id = 7, groupName = null)
        val second = server.takeRequest()
        assertEquals("PUT /api/db/connections/7", "${second.method} ${second.target}")
        assertEquals("""{"groupName":null}""", second.body?.utf8())
    }
}
