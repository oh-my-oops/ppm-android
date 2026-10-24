package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.AgentEnvelope
import com.hienlh.ppm.protocol.AgentRunningEntry
import com.hienlh.ppm.protocol.AgentSource
import com.hienlh.ppm.protocol.AgentTranscriptFrame
import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** An agent's session followed over `/ws/global`, and who runs in a session. */
class AgentStreamTest {
    /** `/ws/global` as the streams see it: open or not, and the listeners they registered. */
    private class FakeChannel : GlobalChannel {
        var open = true
        val sent = mutableListOf<String>()
        val ready = LinkedHashSet<() -> Unit>()
        val frames = LinkedHashSet<(AgentTranscriptFrame) -> Unit>()

        override fun sendIfOpen(text: String): Boolean {
            if (open) sent += text
            return open
        }

        override fun onGlobalReady(listener: () -> Unit): () -> Unit {
            ready += listener
            return { ready -= listener }
        }

        override fun onAgentFrame(listener: (AgentTranscriptFrame) -> Unit): () -> Unit {
            frames += listener
            return { frames -= listener }
        }

        fun reconnect() = ready.toList().forEach { it() }

        fun push(frame: AgentTranscriptFrame) = frames.toList().forEach { it(frame) }

        fun sentJson() = sent.map { WireJson.parse(it) }
    }

    private fun text(text: String) = ChatEvent.Text(text)

    private fun envelope(key: String, ts: Long, text: String, replace: Boolean = false) = AgentEnvelope(text(text), ts, key, replace)

    private fun page(subId: String, vararg events: AgentEnvelope, cursor: String = "{}", reset: Boolean = false, available: Boolean = true, running: Boolean = false) =
        AgentTranscriptFrame.Page(subId, events.toList(), WireJson.parse(cursor) as JsonObject, reset = reset, available = available, running = running)

    @Test
    fun `a page adds new keys where their time sorts, skips keys it has, and replaces only when told`() {
        var held = applyAgentPage(emptyList(), page("s", envelope("a", 10, "one"), envelope("c", 30, "three")))
        held = applyAgentPage(held, page("s", envelope("b", 20, "two"), envelope("a", 10, "one again")))
        assertEquals(listOf("one", "two", "three"), held.map { (it.event as ChatEvent.Text).text })
        held = applyAgentPage(held, page("s", envelope("b", 20, "two, finished", replace = true)))
        assertEquals(listOf("one", "two, finished", "three"), held.map { (it.event as ChatEvent.Text).text })
        // A rewritten history starts again from nothing.
        held = applyAgentPage(held, page("s", envelope("z", 5, "fresh"), reset = true))
        assertEquals(listOf("z"), held.map { it.key })
    }

    @Test
    fun `a session holds its newest steps only`() {
        val many = (1..MAX_AGENT_STREAM_ENTRIES + 5).map { envelope("k$it", it.toLong(), "step $it") }
        val held = applyAgentPage(emptyList(), page("s", *many.toTypedArray()))
        assertEquals(MAX_AGENT_STREAM_ENTRIES, held.size)
        assertEquals("k6", held.first().key)
        assertEquals("k${MAX_AGENT_STREAM_ENTRIES + 5}", held.last().key)
    }

    @Test
    fun `a stream subscribes at start and again on every reconnect, resuming from the last cursor`() {
        val channel = FakeChannel()
        val stream = AgentSessionStream(channel, "demo", "claude", "s1", AgentSource.Card("toolu_1"), Random(1))
        assertEquals(AgentSessionStream.State(), stream.state.value)
        stream.start()
        val first = channel.sentJson().single()
        assertEquals("agent-transcript:subscribe", first["type"].string)
        val subId = first["subId"].string!!
        assertTrue(subId.matches(Regex("ats-[0-9a-z]{8}")))
        assertEquals(null, first["cursor"])

        // Someone else's page changes nothing.
        channel.push(page("ats-other", envelope("x", 1, "not mine")))
        assertTrue(stream.state.value.loading)

        channel.push(page(subId, envelope("a", 1, "Looking"), cursor = """{"main":40}""", running = true))
        with(stream.state.value) {
            assertEquals(listOf(text("Looking")), events)
            assertTrue(available && running)
            assertFalse(loading)
        }

        // A dropped socket sends nothing; the reconnect subscribes again from where it was.
        channel.open = false
        channel.reconnect()
        channel.open = true
        channel.reconnect()
        val again = channel.sentJson().last()
        assertEquals(subId, again["subId"].string)
        assertEquals(WireJson.parse("""{"main":40}"""), again["cursor"])
        assertEquals(2, channel.sent.size)

        stream.stop()
        assertEquals(WireJson.parse("""{"type":"agent-transcript:unsubscribe","subId":"$subId"}"""), channel.sentJson().last())
        assertTrue(channel.ready.isEmpty() && channel.frames.isEmpty())
    }

    @Test
    fun `with no transcript the window keeps what the card knew, and an error says why`() {
        val channel = FakeChannel()
        val stream = AgentSessionStream(channel, "demo", "claude", "s1", AgentSource.Member("t", "tester"), Random(2))
        stream.start()
        val subId = channel.sentJson().single()["subId"].string!!
        val fallback = listOf(text("from the card"))
        channel.push(page(subId, available = false))
        assertEquals(fallback, stream.state.value.shown(fallback))
        channel.push(page(subId, envelope("a", 1, "from disk")))
        assertEquals(listOf(text("from disk")), stream.state.value.shown(fallback))
        channel.push(AgentTranscriptFrame.Error(subId, "forbidden"))
        with(stream.state.value) {
            assertEquals("forbidden", error)
            assertFalse(available || loading)
            assertEquals(fallback, shown(fallback))
        }
    }

    @Test
    fun `who runs is the whole list of each push for this watch`() {
        val channel = FakeChannel()
        val watch = AgentActivityWatch(channel, "demo", "codex", "s1", Random(3))
        watch.start()
        val subscribe = channel.sentJson().single()
        assertEquals(WireJson.parse("""{"type":"agent-activity:subscribe","subId":"${subscribe["subId"].string}","projectName":"demo","providerId":"codex","sessionId":"s1"}"""), subscribe)
        val subId = subscribe["subId"].string!!
        assertTrue(subId.matches(Regex("aa-[0-9a-z]{8}")))
        val tester = AgentRunningEntry("toolu_1", "tester", 1, "Bash: bun test")
        channel.push(AgentTranscriptFrame.Activity(subId, listOf(tester)))
        channel.push(AgentTranscriptFrame.Activity("aa-other", emptyList()))
        assertEquals(listOf(tester), watch.running.value)
        channel.push(AgentTranscriptFrame.Activity(subId, emptyList()))
        assertEquals(emptyList<AgentRunningEntry>(), watch.running.value)
        channel.reconnect()
        assertEquals(channel.sent[0], channel.sent[1])
        watch.stop()
        assertEquals(WireJson.parse("""{"type":"agent-activity:unsubscribe","subId":"$subId"}"""), channel.sentJson().last())
    }
}
