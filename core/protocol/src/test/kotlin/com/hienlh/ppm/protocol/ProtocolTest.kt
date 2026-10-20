package com.hienlh.ppm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Server frames and models read without a server, as `PPMSelfTest/ProtocolTests.swift` reads them. */
class ProtocolTest {
    private fun json(text: String) = WireJson.parse(text)

    private fun frame(text: String) = ChatFrame.from(json(text))

    @Test
    fun `session_state tells an absent field from null`() {
        val absent = (frame("""{"type":"session_state","sessionId":"s","phase":"idle"}""") as ChatFrame.SessionState).state
        val nulls = (frame("""{"type":"session_state","phase":"thinking","pendingApproval":null,"turnStop":null}""") as ChatFrame.SessionState).state
        assertEquals(Reported.NotReported, absent.pendingApproval)
        assertEquals(Reported.NotReported, absent.turnStop)
        assertEquals(Reported.Value(null), nulls.pendingApproval)
        assertEquals(Reported.Value(null), nulls.turnStop)
        assertEquals(SessionPhase.THINKING, nulls.phase)
    }

    @Test
    fun `an unknown frame keeps its streamSeq`() {
        assertEquals(ChatFrame.Event(ChatEvent.Other("team_updated"), 7), frame("""{"type":"team_updated","streamSeq":7,"x":1}"""))
        assertEquals(SessionPhase.THINKING, SessionPhase.fromWire("paused"))
    }

    @Test
    fun `tool_use carries the result the server copied onto it`() {
        val event = ChatEvent.from(
            json("""{"type":"tool_use","tool":"Bash","input":{"command":"ls"},"toolUseId":"t1","result":{"output":"a\nb","isError":false}}"""),
        )
        assertEquals(ToolOutcome("a\nb", isError = false), (event as ChatEvent.ToolUse).result)
    }

    @Test
    fun `done carries the turn's usage, and a subagent's the call it ended`() {
        val done = ChatEvent.from(
            json(
                """{"type":"done","lastMessageUuid":"u","usage":{"model":"claude-sonnet-4-5","inputTokens":1200,"outputTokens":850,
                   "cacheReadTokens":18000,"cacheWriteTokens":52000,"costUsd":0.2841,"cacheHitRate":0.25,"coldStart":true,
                   "coldReason":"idle_timeout","cacheTtlMs":3600000}}""",
            ),
        ) as ChatEvent.Done
        val usage = done.usage!!
        assertEquals(71_200L, usage.prefixTokens)
        assertEquals(listOf(0.2841, 0.25), listOf(usage.costUsd, usage.cacheHitRate))
        assertEquals("idle_timeout", usage.coldReason)
        assertEquals(3_600_000.0, usage.cacheTtlMs)
        assertNull(usage.contextTokens)
        assertNull(usage.compactedAt)
        assertNull(done.parent)
        assertEquals(ChatEvent.Done(parent = "t1"), ChatEvent.from(json("""{"type":"done","parentToolUseId":"t1"}""")))
        // Usage that is not an object is none; a figure left out is zero.
        assertNull((ChatEvent.from(json("""{"type":"done","usage":null}""")) as ChatEvent.Done).usage)
        assertEquals(TurnUsage("", 0, 0, 0, 0, 0.0, 0.0, coldStart = false), TurnUsage.from(json("{}")))
    }

    @Test
    fun `account_retry says why, and which account takes over`() {
        assertEquals(
            ChatEvent.AccountRetry("Rate limited — switching account", "Work"),
            ChatEvent.from(json("""{"type":"account_retry","reason":"Rate limited — switching account","accountId":"a2","accountLabel":"Work"}""")),
        )
        assertEquals(ChatEvent.AccountRetry(null, null), ChatEvent.from(json("""{"type":"account_retry"}""")))
    }

    @Test
    fun `client messages`() {
        assertEquals(
            json("""{"type":"message","clientMessageId":"c1","content":"hi"}"""),
            ChatClientMessage.Message("c1", "hi").json,
        )
        assertEquals(
            json("""{"type":"approval_response","requestId":"r","approved":true,"data":{"Q?":"A"}}"""),
            ChatClientMessage.ApprovalResponse("r", approved = true, data = json("""{"Q?":"A"}""")).json,
        )
        assertNull(ChatClientMessage.ApprovalResponse("r", approved = false, data = null).json["data"])
    }

    @Test
    fun `printed as the web prints a tool's input`() {
        assertEquals("{\n  \"command\": \"rm -rf /tmp/test\"\n}", json("""{"command":"rm -rf /tmp/test"}""").prettyString)
        // Keys stay in the order they arrived, as `JSON.stringify` keeps them.
        assertEquals(
            "{\n  \"b\": [\n    1,\n    2.5,\n    true,\n    null\n  ],\n  \"a\": {},\n  \"c\": [],\n  \"d\": \"x\\\"y\\n/z\"\n}",
            json("""{"b":[1,2.5,true,null],"a":{},"c":[],"d":"x\"y\n/z"}""").prettyString,
        )
    }

    @Test
    fun `numbers print as JavaScript prints them`() {
        assertEquals("[\n  1e+21,\n  100,\n  0.000001,\n  1e-7,\n  -0.5\n]", json("[1e21,100.0,0.000001,1e-7,-0.5]").prettyString)
    }

    @Test
    fun `the marks of a snippet become runs, anything else stays text`() {
        val runs = ChatSearchHit.runs("a <mark>b</mark> c <b>d</b> <mark>e")
        assertEquals(listOf("a ", "b", " c <b>d</b> <mark>e"), runs.map { it.text })
        assertEquals(listOf(false, true, false), runs.map { it.marked })
        assertEquals(listOf(true), ChatSearchHit.runs("<mark>x</mark>").map { it.marked })
    }

    @Test
    fun `a search page reads its hits and how far the index has got`() {
        val page = ChatSearchPage.from(
            json(
                """
                {"results":[
                  {"sessionId":"a","providerId":"codex","title":"Fix it","snippet":"…the <mark>bug</mark>…","matchedIn":"content"},
                  {"sessionId":"b","title":null,"snippet":"Bug list","matchedIn":"title"},
                  {"providerId":"claude"}],
                 "indexing":{"total":5,"indexed":2,"running":true}}
                """,
            ),
        )
        assertEquals(listOf("a", "b"), page.hits.map { it.sessionId })
        assertEquals(listOf(true, false), page.hits.map { it.matchedInContent })
        assertEquals(listOf("codex", "claude"), page.hits.map { it.providerId })
        assertNull(page.hits[1].title)
        assertEquals(listOf(2, 5), listOf(page.indexed, page.total))
        assertTrue(page.indexing)
    }

    @Test
    fun `the unread list keeps sessions with something to read`() {
        val list = SessionUnread.list(
            json(
                """
                [{"sessionId":"a","unreadCount":2,"unreadType":"approval_request","projectName":"app","sessionTitle":"T"},
                 {"sessionId":"b","unreadCount":0,"unreadType":"done","projectName":"app"},
                 {"sessionId":"c","unreadCount":1,"unreadType":null,"projectName":null,"sessionTitle":null},
                 {"unreadCount":1}]
                """,
            ),
        )
        assertEquals(2, list.size)
        assertEquals(SessionUnread("approval_request", 2, "app", "T"), list["a"])
        assertEquals(SessionUnread("done", 1, "", null), list["c"])
    }

    @Test
    fun `whole numbers only, and dates in either form`() {
        assertEquals(3L, json("3.0").long)
        assertNull(json("2.5").long)
        assertNull(json("\"3\"").long)
        assertEquals(java.time.Instant.parse("2026-10-19T08:00:00Z"), WireDate.parse(json("1792396800000")))
        assertEquals(java.time.Instant.parse("2026-10-19T08:00:00Z"), WireDate.parse(json("\"2026-10-19T08:00:00.000Z\"")))
        assertEquals(java.time.Instant.parse("2026-10-19T08:00:00Z"), WireDate.parse(json("\"2026-10-19T15:00:00+07:00\"")))
    }
}
