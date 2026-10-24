package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.TeamActivityFeed
import com.hienlh.ppm.protocol.TeamMemberActivity
import com.hienlh.ppm.protocol.TeamMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** A chat's agent team: the teams found, their inbox, and the members polled. */
class TeamActivityTest {
    private fun message(from: String, at: String, text: String = "hi") = TeamMessage(from, "tester", text, at)

    @Test
    fun `teams are kept in the order they were found, and live messages count as unread`() {
        var team = TeamActivity()
        assertEquals(null, team.primary)
        team = team.withTeam("s1").withTeam("other").withTeam("s1")
        assertEquals(listOf("s1", "other"), team.names)
        assertEquals("s1", team.primary)
        team = team.received(listOf(message("lead", "2026-10-25T08:00:01Z"), message("lead", "2026-10-25T08:00:02Z")))
        assertEquals(2, team.unread)
        team = team.read()
        assertEquals(0, team.unread)
        assertTrue(team.read() === team)
    }

    @Test
    fun `history merges in time order without what is held, and is not news`() {
        var team = TeamActivity().withTeam("s1").received(listOf(message("lead", "2026-10-25T08:00:05Z", "live")))
        team = team.merged(
            listOf(
                message("lead", "2026-10-25T08:00:05Z", "live, from disk"),
                message("tester", "2026-10-25T08:00:01Z", "older"),
                message("lead", "2026-10-25T08:00:09Z", "newer"),
            ),
        )
        assertEquals(listOf("older", "live", "newer"), team.messages.map { it.text })
        assertEquals(1, team.unread)
    }

    @Test
    fun `a chat holds its newest five hundred messages`() {
        val many = (0 until MAX_TEAM_MESSAGES + 20).map { message("lead", "2026-10-25T08:%02d:%02dZ".format(it / 60, it % 60), "m$it") }
        val live = TeamActivity().received(many)
        assertEquals(MAX_TEAM_MESSAGES, live.messages.size)
        assertEquals("m20", live.messages.first().text)
        assertEquals(MAX_TEAM_MESSAGES + 20, live.unread)
        assertEquals("m20", TeamActivity().merged(many).messages.first().text)
    }

    @Test
    fun `members are read at once and every five seconds, and a failed read keeps the last answer`() = runTest {
        var calls = 0
        val seen = mutableListOf<TeamActivityFeed>()
        val tester = TeamMemberActivity("tester", "working")
        val poll = launch {
            pollTeamActivity(
                fetch = {
                    calls++
                    if (calls == 2) error("mid-write")
                    TeamActivityFeed(listOf(tester), emptyList())
                },
                onFeed = { seen += it },
            )
        }
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(TEAM_ACTIVITY_POLL_MILLIS)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(1, seen.size)
        advanceTimeBy(TEAM_ACTIVITY_POLL_MILLIS)
        runCurrent()
        assertEquals(3, calls)
        assertEquals(2, seen.size)
        poll.cancel()
    }

    @Test
    fun `a reopened chat finds the team named after its session, with its inbox`() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                requests += target
                val body = when {
                    target.contains("/messages") -> """{"messages":[],"start":0,"total":0}"""
                    target == "/api/teams" -> """[{"name":"other"},{"name":"s1","implicit":true,"members":[]}]"""
                    target == "/api/teams/s1" -> """{"name":"s1","messages":[{"from":"tester","to":"team-lead","text":"done","timestamp":"2026-10-25T08:00:00Z"}]}"""
                    else -> return MockResponse.Builder().code(404).body("""{"ok":false,"error":"nope"}""").build()
                }
                return MockResponse.Builder().body("""{"ok":true,"data":$body}""").build()
            }
        }
        server.start()
        try {
            val chat = ChatController(ApiClient(server.url("/"), "t"), "p", SessionInfo(id = "s1", providerId = "mock", title = ""), this, providerId = "mock")
            chat.start()
            withTimeout(5_000) {
                while (chat.state.value.team.messages.isEmpty()) delay(20)
            }
            with(chat.state.value.team) {
                assertEquals(listOf("s1"), names)
                assertEquals(listOf("done"), messages.map { it.text })
                assertEquals(0, unread)
            }
            assertTrue(requests.toString(), requests.none { it.startsWith("/api/teams/other") })
            chat.markTeamRead()
            chat.stop()
        } finally {
            withContext(Dispatchers.IO) { server.close() }
        }
    }
}
