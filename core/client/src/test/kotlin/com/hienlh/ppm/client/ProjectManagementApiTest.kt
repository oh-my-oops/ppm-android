package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ProjectManagementApiTest {
    private fun response(data: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body("{\"ok\":true,\"data\":$data}").build()
    @Test fun `project registration cloning and suggestions preserve host paths and auth`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(response("""{"name":"work","path":"C:\\work"}"""))
        server.enqueue(response("""[{"name":"work","path":"C:\\work"}]"""))
        server.enqueue(response("""{"dir":"~/Projects"}"""))
        server.enqueue(response("""{"path":"/tmp/repo","project":{"name":"repo","path":"/tmp/repo"}}"""))
        server.start()
        try {
            val api = ApiClient(server.url("/ppm"), "test-token")
            assertEquals("work", api.addProject("C:\\work").name)
            val add = server.takeRequest(); assertEquals("/ppm/api/projects", add.target)
            assertEquals("Bearer test-token", add.headers["Authorization"])
            assertEquals("""{"path":"C:\\work"}""", add.body!!.utf8())
            assertEquals("C:\\work", api.suggestProjectDirectories("a & b", "~").single().path)
            assertEquals("/ppm/api/projects/suggest-dirs?q=a%20%26%20b&path=~", server.takeRequest().target)
            assertEquals("~/Projects", api.lastCloneDirectory()); server.takeRequest()
            assertEquals("repo", api.cloneProject("ssh://host/repo.git", "~/Projects", "local").project.name)
            val clone = server.takeRequest(); assertEquals("POST", clone.method)
            assertEquals("""{"url":"ssh://host/repo.git","targetDir":"~/Projects","name":"local"}""", clone.body!!.utf8())
        } finally { server.close() }
    }
    @Test fun `rename color reset and remove encode names and never delete host paths`() = runBlocking {
        val server = MockWebServer()
        repeat(2) { server.enqueue(response("""{"name":"new","path":"/host/work"}""")) }
        server.enqueue(response("""{"removed":"new"}""")); server.start()
        try {
            val api = ApiClient(server.url("/"), "test-token")
            api.renameProject("a / b", "new")
            val rename = server.takeRequest(); assertEquals("/api/projects/a%20%2F%20b", rename.target)
            assertEquals("""{"name":"new"}""", rename.body!!.utf8())
            api.setProjectColor("new", null); assertEquals("""{"color":null}""", server.takeRequest().body!!.utf8())
            api.removeProject("new"); val remove = server.takeRequest()
            assertEquals("DELETE", remove.method); assertEquals("/api/projects/new", remove.target)
        } finally { server.close() }
    }
    @Test fun `avatar upload is bounded webp multipart and removal returns updated project`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(response("""{"name":"p","path":"/p","image":"image.webp"}"""))
        server.enqueue(response("""{"name":"p","path":"/p"}""")); server.start()
        try {
            val api = ApiClient(server.url("/"), "test-token")
            assertEquals("image.webp", api.setProjectImage("p", "fake-webp".toByteArray()).image)
            val request = server.takeRequest(); assertEquals("/api/projects/p/image", request.target)
            val body = request.body!!.utf8(); assertTrue(body.contains("name=\"file\"; filename=\"avatar.webp\""))
            assertTrue(body.contains("Content-Type: image/webp"))
            assertNull(api.removeProjectImage("p").image); assertEquals("DELETE", server.takeRequest().method)
            try { api.setProjectImage("p", ByteArray(2_000_001)); fail("oversized upload") } catch (_: IllegalArgumentException) { }
            assertEquals(2, server.requestCount)
        } finally { server.close() }
    }
}
