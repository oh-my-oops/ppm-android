package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ChatEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The `bash_output` rules of use-chat.ts, and the text its `StreamingBashOutput` shows. */
class BashPartialTest {
    /** What the web shows of [content]. */
    private fun webShows(content: String) = content.split("\n").takeLast(BashPartial.LINES).joinToString("\n")

    @Test
    fun `the last lines are the web's split, slice and join`() {
        for (text in listOf("", "a", "a\n", "\n", "\n\n\n", "x".repeat(10))) assertEquals(webShows(text), BashPartial.lastLines(text))
        for (n in listOf(1, 198, 199, 200, 201, 450)) {
            val lines = (1..n).joinToString("\n") { "line $it" }
            assertEquals(webShows(lines), BashPartial.lastLines(lines))
            assertEquals(webShows("$lines\n"), BashPartial.lastLines("$lines\n"))
        }
    }

    @Test
    fun `keeping only the lines shown shows what keeping the web's 500,000 characters shows`() {
        val random = Random(7)
        repeat(40) {
            var web: String? = null
            var app: BashPartial? = null
            repeat(60) {
                // Short lines, long lines, no newline at all, and deltas past the cap.
                val delta = buildString {
                    repeat(random.nextInt(0, 30)) {
                        append("x".repeat(random.nextInt(0, if (random.nextInt(10) == 0) 60_000 else 80)))
                        if (random.nextInt(4) != 0) append('\n')
                    }
                }
                web = web?.let { (it + delta).let { all -> if (all.length > BashPartial.MAX_CHARS) all.takeLast(BashPartial.MAX_CHARS) else all } } ?: delta
                app = app?.plus(delta, 0) ?: BashPartial.of(delta, 0)
                assertEquals(webShows(web!!), app!!.tail)
            }
        }
    }

    @Test
    fun `a first delta past the cap is kept whole, as the web keeps it`() {
        val big = "y".repeat(BashPartial.MAX_CHARS + 10)
        assertEquals(big, BashPartial.of(big, 1).tail)
        assertEquals(BashPartial.MAX_CHARS, BashPartial.of(big, 1).plus("z", 1).tail.length)
    }

    private fun running(): ChatTranscript = ChatTranscript().apply {
        handle(frame("""{"type":"phase_changed","phase":"thinking"}"""))
        handle(frame("""{"type":"tool_use","tool":"Bash","input":{"command":"make"},"toolUseId":"b1","streamSeq":1}"""))
    }

    @Test
    fun `a running command's output adds up, with the server's line count`() {
        val chat = running()
        chat.handle(frame("""{"type":"bash_output","toolUseId":"b1","content":"one\n","lineCount":1}"""))
        chat.handle(frame("""{"type":"bash_output","toolUseId":"b1","content":"two\n","lineCount":2}"""))
        assertEquals(mapOf("b1" to BashPartial("one\ntwo\n", 2)), chat.bashOutput)
        // Live only: it is not counted as a frame of the turn.
        assertEquals(1, chat.streamSeq)
    }

    @Test
    fun `its result, the end of the turn and Stop each drop it`() {
        val chat = running()
        chat.handle(frame("""{"type":"tool_use","tool":"PowerShell","input":{"command":"dir"},"toolUseId":"p1","streamSeq":2}"""))
        chat.handle(frame("""{"type":"bash_output","toolUseId":"b1","content":"a","lineCount":1}"""))
        chat.handle(frame("""{"type":"bash_output","toolUseId":"p1","content":"b","lineCount":1}"""))
        chat.handle(frame("""{"type":"tool_result","output":"a","toolUseId":"b1","streamSeq":3}"""))
        assertEquals(setOf("p1"), chat.bashOutput.keys)
        chat.handle(frame("""{"type":"done","streamSeq":4}"""))
        assertEquals(emptyMap<String, BashPartial>(), chat.bashOutput)

        val stopped = running()
        stopped.handle(frame("""{"type":"bash_output","toolUseId":"b1","content":"a","lineCount":1}"""))
        stopped.cancelled()
        assertEquals(emptyMap<String, BashPartial>(), stopped.bashOutput)
    }

    @Test
    fun `nothing is kept that no running card of the turn would show`() {
        val chat = running()
        // A backgrounded command prints on after its result.
        chat.handle(frame("""{"type":"tool_use","tool":"Bash","input":{"command":"serve","run_in_background":true},"toolUseId":"b2","streamSeq":2}"""))
        chat.handle(frame("""{"type":"tool_result","output":"Command running in background","toolUseId":"b2","streamSeq":3}"""))
        chat.handle(frame("""{"type":"bash_output","toolUseId":"b2","content":"listening","lineCount":1}"""))
        // A subagent's command, whose card is a step of the agent's.
        chat.handle(frame("""{"type":"tool_use","tool":"Agent","input":{"description":"Build"},"toolUseId":"a1","streamSeq":4}"""))
        chat.handle(frame("""{"type":"tool_use","tool":"Bash","input":{"command":"ls"},"toolUseId":"b3","parentToolUseId":"a1","streamSeq":5}"""))
        chat.handle(frame("""{"type":"bash_output","toolUseId":"b3","content":"x","lineCount":1}"""))
        // Another tool, and a call of no turn.
        chat.handle(frame("""{"type":"bash_output","toolUseId":"a1","content":"x","lineCount":1}"""))
        chat.handle(frame("""{"type":"bash_output","toolUseId":"nope","content":"x","lineCount":1}"""))
        assertEquals(emptyMap<String, BashPartial>(), chat.bashOutput)
        assertTrue(ChatEvent.from(json("""{"type":"bash_output","content":"x","lineCount":1}""")) is ChatEvent.Other)
    }
}
