package com.hienlh.ppm.protocol

import com.hienlh.ppm.protocol.generated.TurnReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Turn links, turn-reference blocks and the wire fields that carry turns (`turn-link.test.ts`, `chat-turn-ref.test.ts`). */
class TurnLinksTest {
    private val sid = "0f8e2a4c-1111-4000-8000-000000000001"
    private val tid = "7a1b9c3d-2222-4000-8000-000000000002"
    private val projects = setOf("ppm", "my project", "a/b")
    private val isProject: (String) -> Boolean = { it in projects }

    private fun ref(project: String = "ppm", turnId: String = tid, n: Double = 3.0) =
        TurnReference(1, project, "claude", sid, turnId, n, "Fix the login", "why does it fail", "2026-11-18T08:00:00.000Z")

    @Test
    fun `builds a link that parses back to the same turn`() {
        val link = TurnLinks.build("http://localhost:8080/", TurnLinkTarget("my project", "codex", sid, "u:$tid"))
        assertEquals("http://localhost:8080/project/my%20project/chat/codex/$sid?turn=u%3A$tid", link)
        assertEquals(listOf(TurnLinkTarget("my project", "codex", sid, "u:$tid")), TurnLinks.parse(link, isProject).links.map { it.target })
    }

    @Test
    fun `ignores the origin, with or without a trailing slash`() {
        for (origin in listOf("http://localhost:8081", "https://abc-def.trycloudflare.com", "http://[::1]:3210", "https://box.tail1234.ts.net")) {
            for (slash in listOf("", "/")) {
                val links = TurnLinks.parse("$origin/project/ppm/chat/claude/$sid$slash?turn=$tid", isProject).links
                assertEquals(listOf(TurnLinkTarget("ppm", "claude", sid, tid)), links.map { it.target })
            }
        }
    }

    @Test
    fun `takes several links in order without repeats, and leaves the rest of the text`() {
        val a = "https://x.test/project/a%2Fb/chat/claude/$sid?turn=$tid"
        val b = "https://y.test/project/ppm/chat/codex/$sid?other=1&turn=$tid#frag"
        val scan = TurnLinks.parse("first $a\nsecond ($b). again $a", isProject)
        assertEquals(listOf("a/b" to "claude", "ppm" to "codex"), scan.links.map { it.target.projectName to it.target.providerId })
        assertEquals("first \nsecond (). again ", scan.rest)
    }

    @Test
    fun `a chat link with no turn, an unknown project or a bad id stays text`() {
        val bad = listOf(
            "http://h/project/ppm/chat/claude/$sid",
            "http://h/project/other/chat/claude/$sid?turn=$tid",
            "http://h/project/ppm/chat/gemini/$sid?turn=$tid",
            "http://h/project/ppm/chat/claude/short?turn=$tid",
            "http://h/project/ppm/chat/claude/$sid?turn=no",
        )
        for (text in bad) {
            val scan = TurnLinks.parse("see $text ok", isProject)
            assertTrue(text, scan.links.isEmpty())
            assertEquals("see $text ok", scan.rest)
        }
    }

    @Test
    fun `a paste keeps what is not a link and the links there was no room for`() {
        val a = "http://h/project/ppm/chat/claude/$sid?turn=$tid"
        val b = "http://h/project/ppm/chat/codex/$sid?turn=$tid"
        // Room for every link: only the words stay.
        assertEquals("look at  please", TurnLinks.pasteWithout("look at $a please", isProject) { emptyList() })
        // A paste of links only inserts nothing.
        assertEquals("", TurnLinks.pasteWithout("  $a\n", isProject) { emptyList() })
        // No room for the codex one: it stays in the text.
        assertEquals(" and $b", TurnLinks.pasteWithout("$a and $b", isProject) { links -> links.filter { it.providerId == "codex" } })
        // No link taken at all, or none in the text: the paste goes in as it is.
        assertNull(TurnLinks.pasteWithout("$a", isProject) { it })
        assertNull(TurnLinks.pasteWithout("plain words", isProject) { emptyList() })
    }

    @Test
    fun `a chip's reference is built from the link and the server's answer`() {
        val target = TurnLinkTarget("ppm", "claude", sid, tid)
        val meta = WireJson.parse("""{"sessionId":"$sid","n":4,"startedAt":"2026-11-18T08:00:00.000Z","prompt":"first line\nsecond","sessionTitle":"  Login  "}""")
        val made = TurnLinks.refFromMeta(target, meta)!!
        assertEquals("Login", made.title)
        assertEquals("first line", made.prompt)
        assertEquals(4.0, made.n, 0.0)
        // No title: the prompt names it.
        val untitled = TurnLinks.refFromMeta(target, WireJson.parse("""{"n":1,"startedAt":"2026-11-18T08:00:00.000Z","prompt":"hello"}"""))!!
        assertEquals("hello", untitled.title)
        // A malformed answer is no reference.
        assertNull(TurnLinks.refFromMeta(target, WireJson.parse("""{"n":"x"}""")))
    }

    @Test
    fun `a message's turn references survive its block`() {
        val refs = listOf(ref(), ref(project = "my project", turnId = "u:$tid", n = 12.0))
        val encoded = ChatTurnRefs.encode("what changed?", refs, ownProject = "ppm")
        assertTrue(encoded.startsWith("what changed?\n\n<ppm-turnrefs-v1>"))
        val decoded = ChatTurnRefs.decode(encoded)
        assertEquals("what changed?", decoded.content)
        assertEquals(refs, decoded.turnRefs)
        // None: the text as it is.
        assertEquals("plain", ChatTurnRefs.encode("plain", emptyList()))
    }

    @Test
    fun `a message frame carries its turn references`() {
        val message = ChatClientMessage.Message("c1", "hi", turnRefs = listOf(ref()))
        val sent = message.json["turnRefs"] as JsonArray
        assertEquals(1, sent.size)
        val first = sent[0].jsonObject
        assertEquals(listOf("version", "projectName", "providerId", "sessionId", "turnId", "n", "title", "prompt", "at"), first.keys.toList())
        assertEquals("3", first["n"]!!.jsonPrimitive.content)
        // None: no field.
        assertNull(ChatClientMessage.Message("c2", "hi").json["turnRefs"])
    }

    @Test
    fun `turn_started and session_state bind a sent message to its turn`() {
        val started = ChatFrame.from(WireJson.parse("""{"type":"turn_started","turnId":"$tid","clientMessageId":"local-1"}"""))
        assertEquals(ChatFrame.TurnStarted(tid, "local-1"), started)
        val state = (ChatFrame.from(WireJson.parse("""{"type":"session_state","turnBindings":[{"clientMessageId":"local-1","turnId":"$tid"},{"clientMessageId":"x","turnId":"bad id!"}]}""")) as ChatFrame.SessionState).state
        assertEquals(listOf(TurnBinding("local-1", tid)), state.turnBindings)
    }

    @Test
    fun `a rejected message gives its turn references back`() {
        val frame = ChatFrame.from(WireJson.parse("""{"type":"message_rejected","clientMessageId":"c","content":"hi","message":"no","turnRefs":[${ChatTurnRefs.toJson(ref())}]}"""))
        assertEquals(listOf(ref()), (frame as ChatFrame.MessageRejected).turnRefs)
    }

    @Test
    fun `a link says when the two sessions last talked`() {
        val links = SessionLink.parseList(WireJson.parse("""[{"sessionId":"a","relations":["child"],"lastInteractionAt":1763452800000},{"sessionId":"b","relations":["messaged"],"lastInteractionAt":-5},{"sessionId":"c","relations":["parent"]}]"""))
        assertEquals(listOf(1763452800000L, null, null), links.map { it.lastInteractionAt })
    }

    @Test
    fun `a history message carries its turn when the id is one`() {
        val good = HistoryMessage.from(WireJson.parse("""{"id":"m","role":"user","content":"hi","turnId":"$tid","turnN":2}"""))!!
        assertEquals(tid, good.turnId)
        assertEquals(2, good.turnN)
        val bad = HistoryMessage.from(WireJson.parse("""{"id":"m","role":"user","content":"hi","turnId":"no"}"""))!!
        assertNull(bad.turnId)
    }
}
