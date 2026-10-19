package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ChatClientMessage
import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.ChatFrame
import com.hienlh.ppm.protocol.HistoryPage
import com.hienlh.ppm.protocol.SessionPhase
import com.hienlh.ppm.protocol.ToolOutcome
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun json(text: String): JsonElement = WireJson.parse(text)

internal fun frame(text: String): ChatFrame = ChatFrame.from(json(text))

/** The chat's rules, frames in and effects out (`PPMSelfTest/ProtocolTests.swift`). */
class TranscriptTest {
    private fun msg(id: String, role: String, content: String): JsonElement = buildJsonObject {
        put("id", id)
        put("role", role)
        put("content", content)
        put("timestamp", "2026-10-19T08:00:00Z")
    }

    private fun page(messages: List<JsonElement>, start: Int = 0): HistoryPage = HistoryPage.from(
        buildJsonObject {
            put("messages", JsonArray(messages))
            put("start", start)
            put("total", start + messages.size)
        },
    )!!

    private val resync = listOf(ChatEffect.Send(ChatClientMessage.Resync))
    private val reload = listOf(ChatEffect.ReloadHistory)

    @Test
    fun `text deltas join, done folds the turn into the messages`() {
        val chat = ChatTranscript()
        chat.handle(frame("""{"type":"phase_changed","phase":"thinking"}"""))
        chat.handle(frame("""{"type":"text","content":"Hel","streamSeq":1}"""))
        chat.handle(frame("""{"type":"text","content":"lo","streamSeq":2}"""))
        assertEquals(listOf(ChatBlock.Text("Hello")), chat.live?.blocks)
        chat.handle(frame("""{"type":"done","sessionId":"s","streamSeq":3}"""))
        assertNull("live turn still open", chat.live)
        assertEquals(listOf("Hello"), chat.items.map { it.text })
        assertEquals(emptyList<ChatEffect>(), chat.handle(frame("""{"type":"phase_changed","phase":"idle"}""")))
    }

    @Test
    fun `a result pairs with its call by id, or the last open call without one`() {
        val turn = TurnBuilder(live = true)
        turn.add(ChatEvent.ToolUse("Read", json("""{"file_path":"a.ts"}"""), "t1"))
        turn.add(ChatEvent.ToolUse("Bash", json("""{"command":"ls"}"""), "t2"))
        turn.add(ChatEvent.ToolResult(ToolOutcome("A", isError = false), "t1"))
        turn.add(ChatEvent.ToolUse("Read", json("""{"file_path":"b.ts"}"""), null))
        turn.add(ChatEvent.ToolResult(ToolOutcome("B", isError = true), null))
        assertEquals(listOf("A", null, "B"), turn.blocks.map { (it as? ChatBlock.Tool)?.tool?.result?.output })
    }

    @Test
    fun `a subagent's calls count as steps of its card`() {
        val turn = TurnBuilder(live = true)
        turn.add(ChatEvent.ToolUse("Agent", json("""{"description":"Explore"}"""), "a1"))
        turn.add(ChatEvent.ToolUse("Grep", json("""{"pattern":"foo"}"""), "g1", parent = "a1"))
        turn.add(ChatEvent.Text("inner", parent = "a1"))
        turn.add(ChatEvent.ToolUse("Read", json("""{"file_path":"x"}"""), "g2", parent = "nowhere"))
        assertEquals(turn.blocks.toString(), 1, turn.blocks.size)
        val card = (turn.blocks.first() as ChatBlock.Tool).tool
        assertEquals(1, card.steps)
        assertEquals("Grep foo", card.lastStep)
    }

    @Test
    fun `a card says what the call does in one line`() {
        val use = ChatEvent.from(json("""{"type":"tool_use","tool":"Bash","input":{"command":"ls"},"toolUseId":"t1"}""")) as ChatEvent.ToolUse
        assertEquals("ls", ToolSummary.detail(use.name, use.input))
        // The first line with anything on it, as it is.
        assertEquals("  git status", ToolSummary.detail("Bash", json("""{"command":"\n\n  git status\nmore"}""")))
        assertEquals("ppm-db · db_query", ToolSummary.displayName("mcp__ppm-db__db_query"))
        assertEquals("3 items", ToolSummary.detail("TodoWrite", json("""{"todos":[1,2,3]}""")))
        assertEquals("b", ToolSummary.detail("Custom", json("""{"z":"","b":"b","a":1}""")))
    }

    @Test
    fun `a gap asks for one resync, not one per frame`() {
        val chat = ChatTranscript()
        chat.handle(frame("""{"type":"phase_changed","phase":"thinking"}"""))
        assertEquals(emptyList<ChatEffect>(), chat.handle(frame("""{"type":"text","content":"a","streamSeq":1}""")))
        assertEquals(resync, chat.handle(frame("""{"type":"text","content":"c","streamSeq":3}""")))
        assertEquals(emptyList<ChatEffect>(), chat.handle(frame("""{"type":"text","content":"d","streamSeq":4}""")))
        assertEquals(emptyList<ChatEffect>(), chat.handle(frame("""{"type":"ping","streamSeq":9}""")))
    }

    @Test
    fun `a snapshot replaces both the streamed and the history copy of the turn`() {
        val chat = ChatTranscript()
        chat.applyHistory(
            page(listOf(msg("u0", "user", "old question"), msg("a0", "assistant", "old answer"), msg("u1", "user", "go"), msg("a1", "assistant", "partial"))),
            chat.activity,
        )
        chat.handle(frame("""{"type":"session_state","phase":"streaming","pendingApproval":null}"""))
        chat.handle(
            frame(
                """
                {"type":"turn_events","userMessage":"go","streamSeq":12,"events":[
                  {"type":"text","content":"par","streamSeq":10},{"type":"text","content":"tial and more","streamSeq":12}]}
                """,
            ),
        )
        assertEquals(listOf("old question", "old answer", "go"), chat.items.map { it.text })
        assertEquals(listOf(ChatBlock.Text("partial and more")), chat.live?.blocks)
        assertEquals(12, chat.streamSeq)
        assertEquals(emptyList<ChatEffect>(), chat.handle(frame("""{"type":"text","content":"!","streamSeq":13}""")))
        assertEquals(listOf(ChatBlock.Text("partial and more!")), chat.live?.blocks)
        // A second snapshot (an answered resync) rebuilds the same turn, not a copy of it.
        chat.handle(frame("""{"type":"turn_events","userMessage":"go","streamSeq":13,"events":[{"type":"text","content":"partial and more!","streamSeq":13}]}"""))
        assertEquals(listOf("old question", "old answer", "go"), chat.items.map { it.text })
        assertEquals(listOf(ChatBlock.Text("partial and more!")), chat.live?.blocks)
    }

    @Test
    fun `the first ping on an idle session sets the count without a resync`() {
        val chat = ChatTranscript()
        chat.applyHistory(page(emptyList()), chat.activity)
        chat.socketOpened()
        chat.handle(frame("""{"type":"session_state","phase":"idle","pendingApproval":null}"""))
        assertEquals(emptyList<ChatEffect>(), chat.handle(frame("""{"type":"ping","streamSeq":40}""")))
        assertEquals(emptyList<ChatEffect>(), chat.handle(frame("""{"type":"ping","streamSeq":40}""")))
        assertEquals(emptyList<ChatEffect>(), chat.handle(frame("""{"type":"text","content":"x","streamSeq":41}""")))
    }

    @Test
    fun `a resync answered with idle settles the count, no resync every ping`() {
        val chat = ChatTranscript()
        val opened = 1_000_000L
        chat.applyHistory(page(emptyList()), chat.activity, opened)
        chat.socketOpened()
        chat.handle(frame("""{"type":"ping","streamSeq":3}"""), opened)
        // Frames went missing: a later ping counts past what arrived.
        assertEquals(resync, chat.handle(frame("""{"type":"ping","streamSeq":20}"""), opened))
        val later = opened + 60_000
        assertEquals(reload, chat.handle(frame("""{"type":"session_state","phase":"idle"}"""), later))
        assertEquals(emptyList<ChatEffect>(), chat.handle(frame("""{"type":"ping","streamSeq":20}"""), later))
    }

    @Test
    fun `a truncated snapshot stops counting and reloads at idle`() {
        val chat = ChatTranscript()
        chat.handle(frame("""{"type":"session_state","phase":"streaming"}"""))
        chat.handle(frame("""{"type":"turn_events","userMessage":"go","streamSeq":900,"truncated":true,"events":[{"type":"text","content":"tail","streamSeq":5}]}"""))
        assertEquals(emptyList<ChatEffect>(), chat.handle(frame("""{"type":"ping","streamSeq":950}""")))
        assertEquals(emptyList<ChatEffect>(), chat.handle(frame("""{"type":"text","content":"x","streamSeq":960}""")))
        assertEquals(emptyList<ChatEffect>(), chat.handle(frame("""{"type":"done","streamSeq":961}""")))
        assertEquals(reload, chat.handle(frame("""{"type":"phase_changed","phase":"idle"}""")))
    }

    @Test
    fun `idle with no done keeps what streamed and reloads`() {
        val chat = ChatTranscript()
        chat.handle(frame("""{"type":"phase_changed","phase":"streaming"}"""))
        chat.handle(frame("""{"type":"text","content":"half","streamSeq":1}"""))
        assertEquals(reload, chat.handle(frame("""{"type":"phase_changed","phase":"idle"}""")))
        assertNull(chat.live)
        assertEquals(listOf("half"), chat.items.map { it.text })
    }

    @Test
    fun `an approval is set live, not on replay, and cleared by resolved and by session_state null`() {
        val chat = ChatTranscript()
        chat.handle(frame("""{"type":"phase_changed","phase":"thinking"}"""))
        chat.handle(frame("""{"type":"approval_request","requestId":"r1","tool":"Bash","input":{"command":"rm x"},"streamSeq":1}"""))
        assertEquals("r1", chat.pendingApproval?.requestId)
        chat.handle(frame("""{"type":"approval_resolved","requestId":"other"}"""))
        assertEquals("r1", chat.pendingApproval?.requestId)
        chat.handle(frame("""{"type":"approval_resolved","requestId":"r1","approved":true}"""))
        assertNull(chat.pendingApproval)

        chat.handle(frame("""{"type":"turn_events","userMessage":null,"streamSeq":2,"events":[{"type":"approval_request","requestId":"r2","tool":"Bash","input":{}}]}"""))
        assertNull("replay must not raise an approval", chat.pendingApproval)

        chat.handle(
            frame(
                """{"type":"session_state","phase":"thinking","pendingApproval":{"requestId":"r3","tool":"AskUserQuestion","input":{"questions":[{"question":"Which?","options":[{"label":"A"},{"label":"B"}],"multiSelect":false}]}}}""",
            ),
        )
        assertEquals(listOf("A", "B"), chat.pendingApproval?.questions?.first()?.options?.map { it.label })
        chat.handle(frame("""{"type":"session_state","phase":"thinking","model":"x"}"""))
        assertEquals("r3", chat.pendingApproval?.requestId)
        chat.handle(frame("""{"type":"session_state","phase":"thinking","pendingApproval":null}"""))
        assertNull(chat.pendingApproval)
    }

    @Test
    fun `the request is a card in the turn too, once, and a question's answers land on it`() {
        val chat = ChatTranscript()
        chat.handle(frame("""{"type":"phase_changed","phase":"thinking"}"""))
        chat.handle(frame("""{"type":"tool_use","tool":"Read","input":{}}"""))
        chat.handle(frame("""{"type":"approval_request","requestId":"r1","tool":"Bash","input":{"command":"rm x"}}"""))
        chat.handle(frame("""{"type":"approval_request","requestId":"r1","tool":"Bash","input":{"command":"rm x"}}"""))
        // An id-less result belongs to the call, never to a request.
        chat.handle(frame("""{"type":"tool_result","output":"done"}"""))
        chat.handle(frame("""{"type":"approval_request","requestId":"db","tool":"ppm:db_execute","input":{}}"""))
        chat.handle(frame("""{"type":"tool_use","tool":"AskUserQuestion","input":{"questions":[]},"toolUseId":"q"}"""))
        chat.handle(frame("""{"type":"approval_request","requestId":"r2","tool":"AskUserQuestion","input":{"questions":[]}}"""))
        chat.handle(frame("""{"type":"tool_result","output":"answered","toolUseId":"q"}"""))
        chat.handle(frame("""{"type":"approval_resolved","requestId":"r2","approved":true,"answers":{"Which?":"A"}}"""))
        val tools = chat.live?.blocks.orEmpty().mapNotNull { (it as? ChatBlock.Tool)?.tool }
        assertEquals(listOf("Read", "Bash", "AskUserQuestion"), tools.map { it.name })
        assertEquals(listOf(null, "r1", "r2"), tools.map { it.requestId })
        assertEquals(listOf("done", null, null), tools.map { it.result?.output })
        assertEquals(json("""{"Which?":"A"}"""), tools.last().input["answers"])
        // History keeps the question's own call: there is no request in it.
        val history = TurnBuilder(live = false)
        history.add(ChatEvent.ToolUse("AskUserQuestion", json("{}"), "q"))
        assertEquals(1, history.blocks.size)
    }

    @Test
    fun `a send is shown at once, and a refusal takes it back and says why`() {
        val chat = ChatTranscript()
        val message = chat.send("hello")
        assertEquals("hello", message.content)
        assertEquals(SessionPhase.INITIALIZING, chat.phase)
        assertEquals(listOf(message.clientMessageId), chat.items.map { it.id })
        chat.handle(ChatFrame.MessageRejected(message.clientMessageId, "Nope"))
        assertTrue(chat.items.isEmpty())
        assertEquals("Nope", chat.notice)
        assertEquals(SessionPhase.IDLE, chat.phase)
    }

    @Test
    fun `a user_message from another device shows once`() {
        val chat = ChatTranscript()
        chat.handle(frame("""{"type":"user_message","content":"from phone"}"""))
        chat.handle(frame("""{"type":"user_message","content":"from phone"}"""))
        assertEquals(listOf("from phone"), chat.items.map { it.text })
    }

    @Test
    fun `tab_open is answered at once`() {
        val chat = ChatTranscript()
        val effects = chat.handle(frame("""{"type":"tab_open","requestId":"q1","tool":"open_file","filePath":"a.ts","projectName":"p"}"""))
        assertEquals(
            listOf(ChatEffect.Send(ChatClientMessage.TabOpenResult("q1", opened = false, error = ChatTranscript.TAB_OPEN_UNSUPPORTED))),
            effects,
        )
    }

    @Test
    fun `a turn stop survives a reload and is cleared by the next turn`() {
        val chat = ChatTranscript()
        chat.handle(frame("""{"type":"phase_changed","phase":"thinking"}"""))
        chat.handle(frame("""{"type":"turn_stop","stop":{"message":"Reached max turns","subtype":"error_max_turns"}}"""))
        chat.handle(frame("""{"type":"done","streamSeq":1}"""))
        chat.handle(frame("""{"type":"phase_changed","phase":"idle"}"""))
        chat.applyHistory(page(listOf(msg("u", "user", "q"))), chat.activity)
        assertEquals("error_max_turns", chat.turnStop?.subtype)
        chat.handle(frame("""{"type":"session_state","phase":"idle"}"""))
        assertEquals("error_max_turns", chat.turnStop?.subtype)
        chat.send("again")
        assertNull(chat.turnStop)
    }

    @Test
    fun `a history page requested before new activity is dropped`() {
        val chat = ChatTranscript()
        val token = chat.activity
        chat.send("new")
        assertFalse(chat.applyHistory(page(listOf(msg("u", "user", "old"))), token))
        assertEquals(listOf("new"), chat.items.map { it.text })
    }

    @Test
    fun `history events become blocks and empty placeholders are skipped`() {
        val chat = ChatTranscript()
        val assistant = json(
            """
            {"id":"a","role":"assistant","content":"x","timestamp":"2026-10-19T08:00:00.000Z","events":[
              {"type":"text","content":"First."},{"type":"text","content":"Second."},
              {"type":"tool_use","tool":"Bash","input":{"command":"ls"},"toolUseId":"t"},
              {"type":"tool_result","output":"ok","toolUseId":"t"}]}
            """,
        )
        chat.applyHistory(page(listOf(assistant, msg("e", "assistant", "")), start = 4), chat.activity)
        assertEquals(1, chat.items.size)
        assertEquals(3, chat.items.first().blocks.size)
        assertNotNull(chat.items.first().timestamp)
        assertEquals(4, chat.historyStart)
    }

    @Test
    fun `session_migrated adopts the new id`() {
        val chat = ChatTranscript()
        assertEquals(
            listOf(ChatEffect.AdoptSessionId("b")),
            chat.handle(frame("""{"type":"session_migrated","oldSessionId":"a","newSessionId":"b","streamSeq":1}""")),
        )
    }
}
