package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.ApiClientFeed
import com.hienlh.ppm.client.FeedKind
import com.hienlh.ppm.client.FeedRow
import com.hienlh.ppm.client.FeedUnsupported
import com.hienlh.ppm.client.feed
import com.hienlh.ppm.protocol.SessionUnread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class NotificationFeedTest {
    private fun withServer(block: suspend CoroutineScope.(MockWebServer, ApiClient) -> Unit) = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            block(server, ApiClient(server.url("/"), "test-token"))
        } finally {
            server.close()
        }
    }

    private suspend fun MockWebServer.nextRequest(): RecordedRequest = withContext(Dispatchers.IO) {
        checkNotNull(takeRequest(3, TimeUnit.SECONDS)) { "Timed out waiting for HTTP request" }
    }

    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(3_000) {
        while (!condition()) delay(10)
    }

    @Test
    fun `loads feed rows including schedule runs and groups by project`() = withServer { server, api ->
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {
                      "ok": true,
                      "data": {
                        "rows": [
                          {
                            "id": 101,
                            "kind": "approval",
                            "project": "ppm",
                            "sessionId": "s-1",
                            "scheduleId": null,
                            "title": "Fix mobile drawer",
                            "body": "Bash: rm -rf dist/web && bun run build:web",
                            "createdAt": 1700000120000,
                            "read": false
                          },
                          {
                            "id": 100,
                            "kind": "schedule",
                            "project": "ppm",
                            "sessionId": null,
                            "scheduleId": "sched-backup",
                            "title": "Nightly backup failed",
                            "body": "Exit 2 · db-backup at 03:00",
                            "createdAt": 1700000060000,
                            "read": false
                          },
                          {
                            "id": 99,
                            "kind": "done",
                            "project": "ppm-Cloud",
                            "sessionId": "s-2",
                            "scheduleId": null,
                            "title": "Deploy worker edge rules",
                            "body": "All 14 routes deployed.",
                            "createdAt": 1700000000000,
                            "read": true
                          }
                        ],
                        "hasMore": true
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val events = GlobalEvents(api, this)
        val feed = events.feed
        try {
            feed.refresh(limit = 20)
            val req = server.nextRequest()
            assertEquals("GET", req.method)
            assertEquals("/api/notifications/feed", req.url.encodedPath)
            assertEquals("20", req.url.queryParameter("limit"))

            assertTrue(feed.supported.value)
            assertTrue(feed.hasMore.value)
            assertEquals(3, feed.rows.value.size)

            val first = feed.rows.value[0]
            assertEquals(101L, first.id)
            assertEquals(FeedKind.APPROVAL, first.kind)
            assertEquals("ppm", first.project)
            assertEquals("s-1", first.sessionId)
            assertFalse(first.read)

            val schedule = feed.rows.value[1]
            assertEquals(100L, schedule.id)
            assertEquals(FeedKind.SCHEDULE, schedule.kind)
            assertEquals("sched-backup", schedule.scheduleId)
            assertEquals("Nightly backup failed", schedule.title)
            assertEquals("Exit 2 · db-backup at 03:00", schedule.body)
            assertFalse(schedule.read)

            val done = feed.rows.value[2]
            assertEquals(99L, done.id)
            assertEquals(FeedKind.DONE, done.kind)
            assertTrue(done.read)

            val groups = groupFeedRowsByProject(feed.rows.value)
            assertEquals(2, groups.size)
            assertEquals("ppm", groups[0].project)
            assertEquals(listOf(101L, 100L), groups[0].rows.map { it.id })
            assertEquals("ppm-Cloud", groups[1].project)
            assertEquals(listOf(99L), groups[1].rows.map { it.id })
        } finally {
            events.stop()
        }
    }

    @Test
    fun `marks single notification read and unread and marks all read`() = withServer { server, api ->
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {
                      "ok": true,
                      "data": {
                        "rows": [
                          {
                            "id": 7,
                            "kind": "question",
                            "project": "ppm",
                            "sessionId": "s-q",
                            "scheduleId": null,
                            "title": "Ask about schema",
                            "body": "Which migration number?",
                            "createdAt": 1700000000000,
                            "read": false
                          }
                        ],
                        "hasMore": false
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {
                      "ok": true,
                      "data": {
                        "id": 7,
                        "kind": "question",
                        "project": "ppm",
                        "sessionId": "s-q",
                        "scheduleId": null,
                        "title": "Ask about schema",
                        "body": "Which migration number?",
                        "createdAt": 1700000000000,
                        "read": true
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {
                      "ok": true,
                      "data": {
                        "id": 7,
                        "kind": "question",
                        "project": "ppm",
                        "sessionId": "s-q",
                        "scheduleId": null,
                        "title": "Ask about schema",
                        "body": "Which migration number?",
                        "createdAt": 1700000000000,
                        "read": false
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .body("""{"ok": true, "data": {"readAll": true}}""")
                .build(),
        )

        val events = GlobalEvents(api, this)
        val feed = events.feed
        try {
            feed.refresh()
            server.nextRequest()
            assertFalse(feed.rows.value.single().read)

            val readRow = feed.setReadAwait(7L, true)
            val readReq = server.nextRequest()
            assertEquals("POST", readReq.method)
            assertEquals("/api/notifications/feed/7/read", readReq.url.encodedPath)
            assertNotNull(readRow)
            assertTrue(feed.rows.value.single().read)

            val unreadRow = feed.setReadAwait(7L, false)
            val unreadReq = server.nextRequest()
            assertEquals("POST", unreadReq.method)
            assertEquals("/api/notifications/feed/7/unread", unreadReq.url.encodedPath)
            assertNotNull(unreadRow)
            assertFalse(feed.rows.value.single().read)

            feed.readAllAwait()
            val readAllReq = server.nextRequest()
            assertEquals("POST", readAllReq.method)
            assertEquals("/api/notifications/feed/read-all", readAllReq.url.encodedPath)
            assertTrue(feed.rows.value.single().read)
        } finally {
            events.stop()
        }
    }

    @Test
    fun `refreshes when notifications feed_changed frame arrives on GlobalEvents`() = withServer { server, api ->
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {
                      "ok": true,
                      "data": {
                        "rows": [
                          {
                            "id": 12,
                            "kind": "done",
                            "project": "ppm",
                            "sessionId": "s-12",
                            "scheduleId": null,
                            "title": "Synced from tablet",
                            "body": "Completed",
                            "createdAt": 1700000200000,
                            "read": true
                          }
                        ],
                        "hasMore": false
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val events = GlobalEvents(api, this)
        try {
            events.handle(json("""{"type":"notifications:feed_changed"}"""))
            val req = server.nextRequest()
            assertEquals("/api/notifications/feed", req.url.encodedPath)
            waitFor { events.feed.rows.value.size == 1 }
            assertEquals(12L, events.feed.rows.value.single().id)
            assertTrue(events.feed.rows.value.single().read)
        } finally {
            events.stop()
        }
    }

    @Test
    fun `falls back to GlobalEvents unread and clearAll when server returns 404 FeedUnsupported`() = withServer { server, api ->
        server.enqueue(
            MockResponse.Builder()
                .code(404)
                .body("""{"ok": false, "error": "Not found"}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(404)
                .body("""{"ok": false, "error": "Not found"}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .body("""{"ok": true}""")
                .build(),
        )

        val events = GlobalEvents(api, this)
        try {
            // Seed two unread sessions via GlobalEvents frames
            events.handle(
                json(
                    """
                    {
                      "type": "session:unread_changed",
                      "sessionId": "sess-1",
                      "unreadCount": 1,
                      "unreadType": "approval",
                      "projectName": "ppm",
                      "sessionTitle": "Fix auth flow"
                    }
                    """.trimIndent(),
                ),
            )
            events.handle(
                json(
                    """
                    {
                      "type": "session:unread_changed",
                      "sessionId": "sess-2",
                      "unreadCount": 1,
                      "unreadType": "done",
                      "projectName": "ppm-Cloud",
                      "sessionTitle": "Deploy worker"
                    }
                    """.trimIndent(),
                ),
            )

            // Direct client call throws FeedUnsupported on 404
            var caughtUnsupported = false
            try {
                ApiClientFeed(api).feed()
            } catch (_: FeedUnsupported) {
                caughtUnsupported = true
            }
            assertTrue("Expected FeedUnsupported on HTTP 404", caughtUnsupported)
            server.nextRequest()

            // NotificationFeed catches FeedUnsupported, sets supported = false, and populates rows from unread
            events.feed.refresh()
            server.nextRequest()

            assertFalse(events.feed.supported.value)
            assertEquals(2, events.feed.rows.value.size)
            assertEquals(
                listOf("sess-1" to FeedKind.APPROVAL, "sess-2" to FeedKind.DONE),
                events.feed.rows.value.map { it.sessionId to it.kind },
            )
            assertEquals(
                listOf("Fix auth flow", "Deploy worker"),
                events.feed.rows.value.map { it.title },
            )

            // New unread broadcast while in fallback mode updates feed.rows immediately
            events.handle(
                json(
                    """
                    {
                      "type": "session:unread_changed",
                      "sessionId": "sess-3",
                      "unreadCount": 1,
                      "unreadType": "question",
                      "projectName": "ppm",
                      "sessionTitle": "Need input"
                    }
                    """.trimIndent(),
                ),
            )
            assertEquals(3, events.feed.rows.value.size)
            assertEquals(
                SessionUnread("question", 1, "ppm", "Need input"),
                events.unread.value["sess-3"],
            )

            // readAll() in fallback mode delegates to GlobalEvents.clearAll() (/api/chat/sessions/read)
            events.feed.readAllAwait()
            val clearReq = server.nextRequest()
            assertEquals("POST", clearReq.method)
            assertEquals("/api/chat/sessions/read", clearReq.url.encodedPath)
            assertTrue(events.unread.value.isEmpty())
            assertTrue(events.feed.rows.value.isEmpty())
        } finally {
            events.stop()
        }
    }
}
