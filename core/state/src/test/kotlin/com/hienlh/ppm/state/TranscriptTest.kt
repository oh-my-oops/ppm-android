package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ChatClientMessage
import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.ChatFrame
import com.hienlh.ppm.protocol.ChatReply
import com.hienlh.ppm.protocol.HistoryMessage
import com.hienlh.ppm.protocol.HistoryPage
import com.hienlh.ppm.protocol.ReplyReference
import com.hienlh.ppm.protocol.SessionPhase
import com.hienlh.ppm.protocol.TabOpenRequest
import com.hienlh.ppm.protocol.ToolOutcome
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.get
import java.time.Instant
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
    fun `a streamed answer keeps the time it began and the SDK id done names`() {
        val chat = ChatTranscript()
        chat.handle(frame("""{"type":"text","content":"Hi","streamSeq":1}"""), now = 1_000)
        chat.handle(frame("""{"type":"text","content":"!","streamSeq":2}"""), now = 4_000)
        chat.handle(frame("""{"type":"done","sessionId":"s","lastMessageUuid":"uuid-9","streamSeq":3}"""), now = 9_000)
        val answer = chat.items.single()
        assertEquals(Instant.ofEpochMilli(1_000), answer.timestamp)
        assertEquals("uuid-9", answer.sdkUuid)
    }

    @Test
    fun `a retry sits between the text around it, and only the turn's own end keeps its usage`() {
        val chat = ChatTranscript()
        chat.handle(frame("""{"type":"phase_changed","phase":"thinking"}"""))
        chat.handle(frame("""{"type":"text","content":"Hel","streamSeq":1}"""))
        chat.handle(frame("""{"type":"account_retry","reason":"Usage limit reached — switching account","accountLabel":"Work","streamSeq":2}"""))
        chat.handle(frame("""{"type":"account_retry","streamSeq":3}"""))
        chat.handle(frame("""{"type":"text","content":"lo","streamSeq":4}"""))
        assertEquals(
            listOf(
                ChatBlock.Text("Hel"),
                ChatBlock.Retry("Usage limit reached — switching account", "Work"),
                ChatBlock.Retry("Auth failed", "another account"),
                ChatBlock.Text("lo"),
            ),
            chat.live?.blocks,
        )
        chat.handle(frame("""{"type":"done","parentToolUseId":"t1","usage":{"inputTokens":5},"streamSeq":5}"""))
        assertNotNull("a subagent's end closed the turn", chat.live)
        chat.handle(frame("""{"type":"done","usage":{"model":"m","inputTokens":5,"cacheReadTokens":95},"streamSeq":6}"""))
        val answer = chat.items.single()
        assertEquals(100L, answer.usage?.prefixTokens)
        // What Copy takes is the answer, without the notice.
        assertEquals("Hello", answer.answerText)
    }

    @Test
    fun `the wait is the server's count, and PPM's own status stands until the account is known`() {
        val chat = ChatTranscript()
        chat.handle(frame("""{"type":"phase_changed","phase":"connecting"}"""))
        assertEquals(0, chat.connectingElapsed)
        chat.handle(frame("""{"type":"phase_changed","phase":"connecting","elapsed":10}"""))
        assertEquals(10, chat.connectingElapsed)
        chat.handle(frame("""{"type":"status_update","phase":"refreshing","message":"Checking token for Work...","accountLabel":"Work","streamSeq":1}"""))
        assertEquals("Checking token for Work... (Work)", chat.statusMessage)
        chat.handle(frame("""{"type":"account_info","accountId":"a","accountLabel":"Work","streamSeq":2}"""))
        assertNull(chat.statusMessage)
        chat.handle(frame("""{"type":"phase_changed","phase":"thinking"}"""))
        assertEquals(0, chat.connectingElapsed)
        chat.handle(frame("""{"type":"status_update","phase":"retrying","message":"Retrying (1/3)...","accountLabel":"","streamSeq":3}"""))
        assertEquals("Retrying (1/3)...", chat.statusMessage)
        chat.handle(frame("""{"type":"text","content":"Hi","streamSeq":4}"""))
        chat.handle(frame("""{"type":"done","parentToolUseId":"t","streamSeq":5}"""))
        assertEquals("Retrying (1/3)...", chat.statusMessage)
        chat.handle(frame("""{"type":"done","streamSeq":6}"""))
        assertNull(chat.statusMessage)
        chat.handle(frame("""{"type":"status_update","phase":"routing","message":"Selecting account...","streamSeq":7}"""))
        chat.handle(frame("""{"type":"phase_changed","phase":"idle"}"""))
        assertNull(chat.statusMessage)
        // Nothing of either lands in the answer.
        assertEquals(listOf(ChatBlock.Text("Hi")), chat.items.single().blocks)
    }

    @Test
    fun `a result pairs with its call by id, or else with the first card still without one`() {
        val turn = TurnBuilder(live = true)
        turn.add(ChatEvent.ToolUse("Read", json("""{"file_path":"a.ts"}"""), "t1"))
        turn.add(ChatEvent.ToolUse("Bash", json("""{"command":"ls"}"""), "t2"))
        turn.add(ChatEvent.ToolResult(ToolOutcome("A", isError = false), "t1"))
        turn.add(ChatEvent.ToolUse("Read", json("""{"file_path":"b.ts"}"""), null))
        turn.add(ChatEvent.ToolResult(ToolOutcome("B", isError = true), null))
        turn.add(ChatEvent.ToolResult(ToolOutcome("C", isError = false), "unknown"))
        assertEquals(listOf("A", "B", "C"), turn.blocks.map { (it as? ChatBlock.Tool)?.tool?.result?.output })
    }

    @Test
    fun `an exit code and an answered request come through as the server sends them`() {
        val result = ChatEvent.from(json("""{"type":"tool_result","output":"x","isError":true,"exitCode":128,"toolUseId":"t"}""")) as ChatEvent.ToolResult
        assertEquals(128, result.outcome.exitCode)
        val turn = TurnBuilder(live = true)
        turn.add(ChatEvent.from(json("""{"type":"approval_request","requestId":"r","tool":"Bash","input":{"command":"rm x"}}""")))
        assertNull((turn.blocks.single() as ChatBlock.Tool).tool.approved)
        // A replay sends it again once it was answered.
        turn.add(ChatEvent.from(json("""{"type":"approval_request","requestId":"r","tool":"Bash","input":{"command":"rm x"},"approved":false}""")))
        assertEquals(false, (turn.blocks.single() as ChatBlock.Tool).tool.approved)
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
    fun `a step counts once however often it is sent, and a nested agent's steps add up`() {
        val turn = TurnBuilder(live = true)
        turn.add(ChatEvent.ToolUse("Agent", json("""{"description":"Plan"}"""), "a1"))
        turn.add(ChatEvent.ToolUse("Read", json("""{"file_path":"/x/y.ts"}"""), "s1", parent = "a1"))
        turn.add(ChatEvent.ToolUse("Read", json("""{"file_path":"/x/y.ts"}"""), "s1", parent = "a1"))
        turn.add(ChatEvent.ToolUse("Task", json("""{"name":" scout ","description":"Look around"}"""), "a2", parent = "a1"))
        turn.add(ChatEvent.ToolUse("Bash", json("""{"command":"ls"}"""), "s2", parent = "a2"))
        turn.add(ChatEvent.ToolUse("Glob", json("""{"pattern":"*.kt"}"""), "s3", parent = "a2"))
        val card = (turn.blocks.single() as ChatBlock.Tool).tool
        // Read and the nested Task on the card itself, Bash and Glob inside the Task.
        assertEquals(4, card.steps)
        assertEquals("scout · Look around", card.lastStep)
    }

    @Test
    fun `a history card counts the steps it carries`() {
        val use = ChatEvent.from(
            json(
                """{"type":"tool_use","tool":"Agent","toolUseId":"a1","input":{"description":"Review"},"children":[
                {"type":"text","content":"Looking"},
                {"type":"tool_use","tool":"Grep","toolUseId":"c1","input":{"pattern":"TODO"}},
                {"type":"tool_result","toolUseId":"c1","output":"none"},
                {"type":"tool_use","tool":"Agent","toolUseId":"c2","input":{"prompt":"Check tests"},"children":[
                  {"type":"tool_use","tool":"WebFetch","input":{"url":"https://example.com"}}
                ]}
                ]}""",
            ),
        )
        val history = TurnBuilder(live = false)
        history.add(use)
        val card = (history.blocks.single() as ChatBlock.Tool).tool
        assertEquals(3, card.steps)
        assertEquals("Check tests", card.lastStep)
        assertEquals("Fetch https://example.com", describeStep("WebFetch", json("""{"url":"https://example.com"}""")))
        assertEquals("Edit b.ts", describeStep("Edit", json("""{"notebook_path":"","file_path":"C:\\a\\b.ts"}""")))
        assertEquals("Bash", describeStep("Bash", json("""{"description":"","command":""}""")))
    }

    @Test
    fun `a backgrounded agent ends when the server says so, on whichever turn holds its card`() {
        val chat = ChatTranscript()
        chat.handle(frame("""{"type":"phase_changed","phase":"thinking"}"""))
        chat.handle(frame("""{"type":"tool_use","tool":"Agent","input":{"description":"Watch"},"toolUseId":"a1","streamSeq":1}"""))
        chat.handle(frame("""{"type":"tool_result","output":"Async agent launched successfully.","toolUseId":"a1","streamSeq":2}"""))
        chat.handle(frame("""{"type":"done","streamSeq":3}"""))
        chat.handle(frame("""{"type":"phase_changed","phase":"idle"}"""))
        // It works on after its turn: its calls count on that turn's card.
        chat.handle(frame("""{"type":"tool_use","tool":"Read","input":{"file_path":"/a.ts"},"toolUseId":"c1","parentToolUseId":"a1","streamSeq":4}"""))
        chat.handle(frame("""{"type":"text","content":"read it","parentToolUseId":"a1","streamSeq":5}"""))
        assertNull(chat.live)
        fun card() = chat.items.last().blocks.filterIsInstance<ChatBlock.Tool>().single().tool
        assertEquals(1, card().steps)
        assertEquals("Read a.ts", card().lastStep)
        assertNull(card().bgStatus)
        chat.handle(frame("""{"type":"subagent_status","toolUseId":"a1","status":"completed","streamSeq":6}"""))
        assertEquals("completed", card().bgStatus)
        // History stamps it on the call.
        val use = ChatEvent.from(json("""{"type":"tool_use","tool":"Agent","input":{},"toolUseId":"a2","bgStatus":"failed"}""")) as ChatEvent.ToolUse
        assertEquals("failed", use.bgStatus)
    }

    @Test
    fun `a subagent's events are kept on its card for the session window, its error too`() {
        val chat = ChatTranscript()
        chat.handle(frame("""{"type":"phase_changed","phase":"thinking"}"""))
        chat.handle(frame("""{"type":"tool_use","tool":"Agent","input":{"description":"Watch"},"toolUseId":"a1","streamSeq":1}"""))
        chat.handle(frame("""{"type":"text","content":"Reading","parentToolUseId":"a1","streamSeq":2}"""))
        chat.handle(frame("""{"type":"error","message":"Tool crashed","parentToolUseId":"a1","streamSeq":3}"""))
        // Not a line of the turn: the card holds it.
        assertEquals(1, chat.live!!.blocks.size)
        fun card() = chat.items.last().blocks.filterIsInstance<ChatBlock.Tool>().single().tool
        chat.handle(frame("""{"type":"tool_result","output":"Async agent launched successfully.","toolUseId":"a1","streamSeq":4}"""))
        chat.handle(frame("""{"type":"done","streamSeq":5}"""))
        assertEquals(listOf(ChatEvent.Text("Reading", "a1"), ChatEvent.Error("Tool crashed", "a1")), card().children!!.events)
        // After its turn, on that turn's card.
        chat.handle(frame("""{"type":"tool_use","tool":"Edit","input":{"file_path":"/a.ts"},"toolUseId":"e1","parentToolUseId":"a1","streamSeq":6}"""))
        chat.handle(frame("""{"type":"text","content":"edited","parentToolUseId":"a1","streamSeq":7}"""))
        assertEquals(listOf("e1"), card().children!!.kept.map { (it.event as ChatEvent.ToolUse).toolUseId })
        assertEquals(4, card().children!!.events.size)
        assertEquals(1, chat.items.last().blocks.size)
    }

    @Test
    fun `a team found and its inbox are handed to the controller, a replayed one too`() {
        val chat = ChatTranscript()
        assertEquals(listOf(ChatEffect.TeamDetected("s1")), chat.handle(frame("""{"type":"team_detected","teamName":"s1","streamSeq":1}""")))
        val inbox = chat.handle(
            frame("""{"type":"team_inbox","teamName":"s1","agent":"tester","messages":[{"from":"team-lead","text":"go","timestamp":"2026-10-25T08:00:00Z"}]}"""),
        ).single() as ChatEffect.TeamMessages
        assertEquals(listOf("go"), inbox.messages.map { it.text })
        chat.handle(frame("""{"type":"session_state","phase":"streaming","pendingApproval":null}"""))
        val replay = chat.handle(frame("""{"type":"turn_events","userMessage":"go","streamSeq":3,"events":[{"type":"team_detected","teamName":"s1","streamSeq":3}]}"""))
        assertTrue(ChatEffect.TeamDetected("s1") in replay)
        // Nothing of the turn: the snapshot's own question, and no answer.
        assertEquals(listOf("go"), chat.items.map { it.text })
        assertNull(chat.live)
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
        // No resync asked for: only the end of the turn, for whoever is not looking at it.
        assertEquals(listOf(ChatEffect.Attention(ChatAttention.Done)), chat.handle(frame("""{"type":"done","streamSeq":961}""")))
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
        // A subagent's question goes the same way, and is no step.
        chat.handle(frame("""{"type":"tool_use","tool":"AskUserQuestion","input":{"questions":[]},"toolUseId":"q2","parentToolUseId":"nowhere"}"""))
        chat.handle(frame("""{"type":"tool_result","output":"answered","toolUseId":"q2"}"""))
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
    fun `a send is shown at once, and a refusal takes it back for the composer`() {
        val chat = ChatTranscript()
        val reply = ReplyReference("s1", "mock", "m1", null, ReplyReference.Role.ASSISTANT, "2026-10-01T10:00:00.000Z", "earlier", truncated = false)
        val message = chat.send("hello", replyTo = reply)
        assertEquals("hello", message.content)
        assertEquals(reply, message.replyTo)
        assertEquals(SessionPhase.INITIALIZING, chat.phase)
        assertEquals(listOf(message.clientMessageId), chat.items.map { it.id })
        // Shown as the server will store it: the quote travels inside the text.
        assertEquals(ChatReply.encode("hello", reply), chat.items.single().text)
        chat.handle(ChatFrame.MessageRejected(message.clientMessageId, "Nope"))
        assertTrue(chat.items.isEmpty())
        assertEquals(RejectedSend("hello", reply, "Nope"), chat.rejected)
        assertEquals(SessionPhase.IDLE, chat.phase)
        chat.takeRejected()
        assertNull(chat.rejected)
    }

    @Test
    fun `a refusal without an id finds the latest send of the same text and reply`() {
        val chat = ChatTranscript()
        chat.send("same", now = 1)
        val second = chat.send("same", now = 2)
        chat.handle(frame("""{"type":"message_rejected","content":"same","message":"Invalid reply reference for this session"}"""))
        assertEquals(RejectedSend("same", null, "Invalid reply reference for this session"), chat.rejected)
        assertEquals(1, chat.items.size)
        assertTrue(chat.items.none { it.id == second.clientMessageId })
    }

    @Test
    fun `a refusal of nothing this device sent hands nothing back`() {
        val chat = ChatTranscript()
        chat.handle(ChatFrame.MessageRejected("someone-else", "Nope"))
        assertNull(chat.rejected)
    }

    @Test
    fun `a user_message from another device shows once`() {
        val chat = ChatTranscript()
        chat.handle(frame("""{"type":"user_message","content":"from phone"}"""))
        chat.handle(frame("""{"type":"user_message","content":"from phone"}"""))
        assertEquals(listOf("from phone"), chat.items.map { it.text })
    }

    @Test
    fun `an error out of a turn is a message with its text, once in a row`() {
        val chat = ChatTranscript()
        chat.handle(frame("""{"type":"error","message":"Provider is offline"}"""))
        chat.handle(frame("""{"type":"error","message":"Provider is offline"}"""))
        assertEquals(listOf(ChatItem.Role.SYSTEM), chat.items.map { it.role })
        assertEquals("Provider is offline", chat.items.single().text)
        chat.handle(frame("""{"type":"error","message":"Still offline"}"""))
        assertEquals(listOf("Provider is offline", "Still offline"), chat.items.map { it.text })
    }

    @Test
    fun `dismiss takes one message out, clear every error and nothing else`() {
        val chat = ChatTranscript()
        chat.applyHistory(page(listOf(msg("u", "user", "hi"), msg("s", "system", "Old error"))), chat.activity)
        chat.handle(frame("""{"type":"error","message":"New error"}"""))
        chat.handle(frame("""{"type":"error","message":"Newer error"}"""))
        chat.dismissMessage(chat.items.last().id)
        assertEquals(listOf("hi", "Old error", "New error"), chat.items.map { it.text })
        chat.clearErrors()
        assertEquals(listOf("u"), chat.items.map { it.id })
    }

    @Test
    fun `tab_open goes to the controller to open, and changes nothing on screen`() {
        val chat = ChatTranscript()
        val effects = chat.handle(frame("""{"type":"tab_open","requestId":"q1","tool":"open_file","filePath":"a.ts","projectName":"p"}"""))
        assertEquals(listOf(ChatEffect.OpenTab(TabOpenRequest.File("q1", "open_file", "a.ts", "p"))), effects)
        assertEquals(emptyList<ChatItem>(), chat.items)
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

    private fun summary(id: String, path: String) =
        msg(id, "user", "This session is being continued from a previous conversation. Read the full transcript at: $path")

    private fun history(vararg messages: JsonElement) = messages.mapNotNull(HistoryMessage::from)

    @Test
    fun `a compact summary's earlier conversation shows above it, and so does that one's`() {
        val chat = ChatTranscript()
        chat.applyHistory(page(listOf(summary("s2", "/t/b.jsonl"), msg("u3", "user", "after"))), chat.activity)
        assertEquals("/t/b.jsonl", chat.unexpandedCompact?.transcriptPath)
        assertEquals("s2", chat.unexpandedCompact?.id)

        // The segment before s2 starts with the summary before it.
        chat.expand("s2", "/t/b.jsonl", history(summary("s1", "/t/a.jsonl"), msg("u2", "user", "middle")))
        val prefix = PreCompact.idPrefix("/t/b.jsonl")
        assertEquals(listOf("${prefix}s1", "${prefix}u2", "s2", "u3"), chat.shown.map { it.id })
        assertEquals("${prefix}s1", chat.unexpandedCompact?.id)
        assertEquals("s1", PreCompact.rawId("${prefix}s1"))
        // What arrives from the socket is not part of a segment.
        assertEquals(listOf("s2", "u3"), chat.items.map { it.id })

        chat.expand("${prefix}s1", "/t/a.jsonl", history(msg("u1", "user", "first")))
        assertEquals(listOf("${PreCompact.idPrefix("/t/a.jsonl")}u1", "${prefix}s1", "${prefix}u2", "s2", "u3"), chat.shown.map { it.id })
        assertNull(chat.unexpandedCompact)
    }

    @Test
    fun `an empty earlier conversation counts as loaded`() {
        val chat = ChatTranscript()
        chat.applyHistory(page(listOf(summary("s", "/t/a.jsonl"))), chat.activity)
        chat.expand("s", "/t/a.jsonl", emptyList())
        assertEquals(listOf("s"), chat.shown.map { it.id })
        assertNull(chat.unexpandedCompact)
    }

    @Test
    fun `segment ids carry the web's prefix, and only the first one comes off`() {
        assertEquals("pc-4b8f8z-", PreCompact.idPrefix("/home/u/.claude/projects/-home-u-app/0b6c.jsonl"))
        assertEquals("pc-el4ils-", PreCompact.idPrefix("/tmp/a.jsonl"))
        assertEquals("pc-twst0s-", PreCompact.idPrefix("é/ü.jsonl"))
        assertEquals("0b6c-11-22", PreCompact.rawId("pc-abc-0b6c-11-22"))
        assertEquals("0b6c-11-22", PreCompact.rawId("0b6c-11-22"))
        assertEquals("/x/y.jsonl", PreCompact.transcriptPath("READ THE FULL TRANSCRIPT AT:\n  /x/y.jsonl."))
        assertNull(PreCompact.transcriptPath("read the full transcript at: /x/y.json"))
    }

    @Test
    fun `an older page leaves out what is already shown`() {
        val chat = ChatTranscript()
        chat.applyHistory(page(listOf(msg("b", "user", "two"), msg("c", "user", "three")), start = 2), chat.activity)
        chat.prependHistory(page(listOf(msg("a", "user", "one"), msg("b", "user", "two"))), before = 2)
        assertEquals(listOf("a", "b", "c"), chat.items.map { it.id })
        assertEquals(0, chat.historyStart)
    }

    @Test
    fun `task tool calls are counted per message, a streamed one once`() {
        val chat = ChatTranscript()
        val assistant = json(
            """
            {"id":"a","role":"assistant","content":"","events":[
              {"type":"tool_use","tool":"TaskCreate","input":{},"toolUseId":"t1"},
              {"type":"tool_use","tool":"Bash","input":{},"toolUseId":"t2"},
              {"type":"tool_use","tool":"TaskUpdate","input":{},"toolUseId":"t3","parentToolUseId":"x"}]}
            """,
        )
        chat.applyHistory(page(listOf(assistant)), chat.activity)
        assertEquals(2, chat.items.single().taskCalls)

        chat.handle(frame("""{"type":"phase_changed","phase":"thinking"}"""))
        chat.handle(frame("""{"type":"tool_use","tool":"TaskStop","input":{},"toolUseId":"t4","streamSeq":1}"""))
        chat.handle(frame("""{"type":"tool_use","tool":"TaskStop","input":{"taskId":"1"},"toolUseId":"t4","streamSeq":2}"""))
        assertEquals(1, chat.live?.taskCalls)
        chat.handle(frame("""{"type":"done","sessionId":"s","streamSeq":3}"""))
        assertEquals(1, chat.items.last().taskCalls)
    }
}
