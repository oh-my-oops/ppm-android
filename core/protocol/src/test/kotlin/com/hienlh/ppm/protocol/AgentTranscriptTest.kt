package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The agent session window's traffic over `/ws/global`, and the team events of a chat. */
class AgentTranscriptTest {
    private fun parse(text: String) = AgentTranscriptFrame.from(WireJson.parse(text))

    @Test
    fun `a page keeps every step that has an event and a key, and the cursor to resume from`() {
        val page = parse(
            """
            {"type":"agent-transcript:events","subId":"ats-1","cursor":{"main":120},"more":true,"available":true,"running":true,"events":[
              {"ev":{"type":"text","content":"Looking"},"ts":1761379200000.0,"k":"a:1"},
              {"ev":{"type":"tool_use","tool":"Read","toolUseId":"c1","input":{"file_path":"/a.ts"}},"ts":1761379201000,"k":"a:2","replace":true},
              {"ev":{"type":"text","content":"no key"},"ts":1},
              {"ev":"not an event","ts":2,"k":"a:3"}
            ]}
            """,
        ) as AgentTranscriptFrame.Page
        assertEquals("ats-1", page.subId)
        assertEquals(listOf("a:1", "a:2"), page.events.map { it.key })
        assertEquals(ChatEvent.Text("Looking"), page.events[0].event)
        assertEquals(1761379200000L, page.events[0].ts)
        assertEquals(listOf(false, true), page.events.map { it.replace })
        assertEquals(WireJson.parse("""{"main":120}"""), page.cursor)
        assertTrue(page.more && page.available && page.running && !page.reset)
        // A page with none of the flags is a plain one.
        val bare = parse("""{"type":"agent-transcript:events","subId":"ats-1","events":[]}""") as AgentTranscriptFrame.Page
        assertEquals(AgentTranscriptFrame.Page("ats-1", emptyList(), JsonObject(emptyMap())), bare)
    }

    @Test
    fun `an error carries its code, and activity lists whoever can be named`() {
        assertEquals(AgentTranscriptFrame.Error("ats-2", "not_found"), parse("""{"type":"agent-transcript:error","subId":"ats-2","code":"not_found"}"""))
        val activity = parse(
            """
            {"type":"agent-activity","subId":"aa-1","running":[
              {"cardId":"toolu_1","memberName":"tester","lastWriteAt":1761379200000,"lastStep":"Bash: bun test"},
              {"memberName":"reviewer","lastWriteAt":1761379100000},
              {"cardId":"","memberName":"","lastWriteAt":1}
            ]}
            """,
        )
        assertEquals(
            AgentTranscriptFrame.Activity(
                "aa-1",
                listOf(
                    AgentRunningEntry("toolu_1", "tester", 1761379200000L, "Bash: bun test"),
                    AgentRunningEntry(null, "reviewer", 1761379100000L, null),
                ),
            ),
            activity,
        )
        assertNull(parse("""{"type":"agent-transcript:events","events":[]}"""))
        assertNull(parse("""{"type":"files:index-changed","subId":"x"}"""))
    }

    @Test
    fun `what the client sends names the source, and the cursor only when it has one`() {
        assertEquals(
            WireJson.parse(
                """{"type":"agent-transcript:subscribe","subId":"ats-1","projectName":"demo","providerId":"claude","sessionId":"s1","source":{"kind":"card","cardId":"toolu_1"}}""",
            ),
            WireJson.parse(AgentTranscriptMessages.subscribe("ats-1", "demo", "claude", "s1", AgentSource.Card("toolu_1"), null)),
        )
        val cursor = WireJson.parse("""{"main":120}""") as JsonObject
        assertEquals(
            WireJson.parse(
                """{"type":"agent-transcript:subscribe","subId":"ats-1","projectName":"demo","providerId":"codex","sessionId":"s1","source":{"kind":"member","teamName":"t","memberName":"tester"},"cursor":{"main":120}}""",
            ),
            WireJson.parse(AgentTranscriptMessages.subscribe("ats-1", "demo", "codex", "s1", AgentSource.Member("t", "tester"), cursor)),
        )
        assertEquals(WireJson.parse("""{"type":"agent-transcript:unsubscribe","subId":"ats-1"}"""), WireJson.parse(AgentTranscriptMessages.unsubscribe("ats-1")))
        assertEquals(
            WireJson.parse("""{"type":"agent-activity:subscribe","subId":"aa-1","projectName":"demo","providerId":"claude","sessionId":"s1"}"""),
            WireJson.parse(AgentTranscriptMessages.activitySubscribe("aa-1", "demo", "claude", "s1")),
        )
        assertEquals(WireJson.parse("""{"type":"agent-activity:unsubscribe","subId":"aa-1"}"""), WireJson.parse(AgentTranscriptMessages.activityUnsubscribe("aa-1")))
    }

    @Test
    fun `one window per session source, and Codex and Antigravity have hubs of their own besides Claude`() {
        assertEquals("s1:toolu_1", agentSessionKey("s1", AgentSource.Card("toolu_1")))
        assertEquals("s1:t:tester", agentSessionKey("s1", AgentSource.Member("t", "tester")))
        assertEquals("codex", agentTranscriptProvider("codex"))
        assertEquals("antigravity", agentTranscriptProvider("antigravity"))
        assertEquals("claude", agentTranscriptProvider("mock"))
        assertEquals("claude", agentTranscriptProvider(null))
    }

    @Test
    fun `a chat's team events, an unnamed team being none`() {
        assertEquals(ChatEvent.TeamDetected("s1"), ChatEvent.from(WireJson.parse("""{"type":"team_detected","teamName":"s1"}""")))
        assertEquals(ChatEvent.Other("team_detected"), ChatEvent.from(WireJson.parse("""{"type":"team_detected","teamName":""}""")))
        assertEquals(ChatEvent.Other("team_updated"), ChatEvent.from(WireJson.parse("""{"type":"team_updated","teamName":"s1","team":{}}""")))
        val inbox = ChatEvent.from(
            WireJson.parse(
                """
                {"type":"team_inbox","teamName":"s1","agent":"tester","messages":[
                  {"from":"team-lead","text":"Run the suite","timestamp":"2026-10-25T08:00:00.000Z","summary":"Run tests","to":"tester","parsedType":"message","color":"blue"},
                  "junk"
                ]}
                """,
            ),
        ) as ChatEvent.TeamInbox
        assertEquals("tester", inbox.agent)
        val message = inbox.messages.single()
        assertEquals(TeamMessage("team-lead", "tester", "Run the suite", "2026-10-25T08:00:00.000Z", "Run tests", "message", "blue"), message)
        assertEquals(Instant.parse("2026-10-25T08:00:00Z"), message.time)
        assertEquals(ChatEvent.Other("team_inbox"), ChatEvent.from(WireJson.parse("""{"type":"team_inbox","teamName":"s1","agent":"a"}""")))
    }

    @Test
    fun `a team's activity, members read from their transcripts and the replies they sent`() {
        val feed = TeamActivityFeed.from(
            WireJson.parse(
                """
                {"members":[
                  {"name":"tester","workState":"working","agentType":"ak-engineer:tester","startedAt":"2026-10-25T08:00:00Z","lastEventAt":"2026-10-25T08:05:00Z",
                   "lastTool":"Bash","lastToolArg":"bun test","sizeBytes":20480,"toolUseId":"toolu_1"},
                  {"workState":"paused"}
                 ],
                 "outbound":[{"from":"tester","to":"team-lead","text":"All green","timestamp":"2026-10-25T08:06:00Z"}]}
                """,
            ),
        )
        val member = feed.members.single()
        assertEquals("tester", member.name)
        assertTrue(member.isWorking)
        assertEquals(Instant.parse("2026-10-25T08:00:00Z"), member.startedAt)
        assertEquals(20480L, member.sizeBytes)
        assertEquals(OutboundTeamMessage("tester", "team-lead", "All green", "2026-10-25T08:06:00Z"), feed.outbound.single())
        assertEquals(TeamActivityFeed(emptyList(), emptyList()), TeamActivityFeed.from(null))
    }
}
