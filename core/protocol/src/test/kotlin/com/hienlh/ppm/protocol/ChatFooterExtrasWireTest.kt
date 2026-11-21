package com.hienlh.ppm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The context meter's figure and breakdown, the CLI's background tasks and Claude's suggested
 * prompt on the wire (web b49accc87 + 3ad423867, 48e2da7bc, 79846f551).
 */
class ChatFooterExtrasWireTest {
    private fun json(text: String) = WireJson.parse(text)

    private fun frame(text: String) = ChatFrame.from(json(text))

    @Test
    fun `done carries how full the context was, rounded, and nothing when it says nothing`() {
        val done = (frame("""{"type":"done","sessionId":"s","contextWindowPct":42.4}""") as ChatFrame.Event).event as ChatEvent.Done
        assertEquals(42, done.contextWindowPct)
        val bare = (frame("""{"type":"done","sessionId":"s"}""") as ChatFrame.Event).event as ChatEvent.Done
        assertNull(bare.contextWindowPct)
        val odd = (frame("""{"type":"done","sessionId":"s","contextWindowPct":"42"}""") as ChatFrame.Event).event as ChatEvent.Done
        assertNull(odd.contextWindowPct)
    }

    @Test
    fun `background tasks are a whole list each time, malformed entries left out`() {
        val tasks = frame(
            """{"type":"background_tasks","sessionId":"s","tasks":[
              {"taskId":"b1","taskType":"local_bash","description":"bun dev"},
              {"taskId":"a1","taskType":"local_agent","description":"review","subagentType":"code-reviewer","parentTaskId":"p"},
              {"description":"no id"}, {"taskId":""}, 3]}""",
        )
        assertEquals(
            ChatFrame.BackgroundTasks(
                listOf(
                    LiveBackgroundTask("b1", "local_bash", "bun dev"),
                    LiveBackgroundTask("a1", "local_agent", "review", "code-reviewer", "p"),
                ),
            ),
            tasks,
        )
        assertEquals(ChatFrame.BackgroundTasks(emptyList()), frame("""{"type":"background_tasks","sessionId":"s"}"""))
        assertEquals(ChatFrame.BackgroundTasks(emptyList()), frame("""{"type":"background_tasks","tasks":"b1"}"""))
        assertEquals(LiveBackgroundTask("x", "task", ""), LiveBackgroundTask.from(json("""{"taskId":"x"}""")))
        assertEquals(json("""{"type":"stop_background_task","taskId":"b1"}"""), ChatClientMessage.StopBackgroundTask("b1").json)
    }

    @Test
    fun `a task's kind reads as a word for its row`() {
        assertEquals("Command", LiveBackgroundTask("t", "local_bash").kindLabel)
        assertEquals("Agent", LiveBackgroundTask("t", "local_agent").kindLabel)
        assertEquals("Agent", LiveBackgroundTask("t", "local_agent", subagentType = "general-purpose").kindLabel)
        assertEquals("Agent · Explore", LiveBackgroundTask("t", "local_agent", subagentType = "Explore").kindLabel)
        assertEquals("Monitor", LiveBackgroundTask("t", "monitor").kindLabel)
        assertEquals("Monitor", LiveBackgroundTask("t", "local_monitor").kindLabel)
        assertEquals("Workflow", LiveBackgroundTask("t", "local_workflow").kindLabel)
        assertEquals("Task", LiveBackgroundTask("t", "dream").kindLabel)
    }

    @Test
    fun `a suggested prompt is its own frame, and one without text is nothing the chat shows`() {
        assertEquals(ChatFrame.PromptSuggestion("Run the tests"), frame("""{"type":"prompt_suggestion","suggestion":"Run the tests"}"""))
        val bare = frame("""{"type":"prompt_suggestion"}""")
        assertTrue(bare is ChatFrame.Event && bare.event is ChatEvent.Other)
    }

    @Test
    fun `the context breakdown keeps the server's order, and no usage is null`() {
        val view = ContextUsageView.from(
            json(
                """{"model":"claude-opus-4-5","percentage":42.6,"totalTokens":84000,"maxTokens":200000,"rows":[
                  {"id":"messages","name":"Messages","tokens":60000,"kind":"used"},
                  {"name":"System tools","tokens":12000.4,"kind":"used"},
                  {"id":"free_space","name":"Free space","tokens":116000,"kind":"free"},
                  {"tokens":3}]}""",
            ),
        )!!
        assertEquals(43, view.percentage)
        assertEquals(84_000L, view.totalTokens)
        assertEquals(200_000L, view.maxTokens)
        assertEquals(
            listOf(
                ContextUsageView.Row("messages", "Messages", 60_000, "used"),
                ContextUsageView.Row("System tools", "System tools", 12_000, "used"),
                ContextUsageView.Row("free_space", "Free space", 116_000, "free"),
            ),
            view.rows,
        )
        assertNull(ContextUsageView.from(json("null")))
        assertEquals(100, ContextUsageView.from(json("""{"percentage":130}"""))!!.percentage)
    }

    @Test
    fun `token counts read as formatTokens prints them`() {
        assertEquals("850", formatContextTokens(850))
        assertEquals("1.0k", formatContextTokens(1_000))
        assertEquals("12.3k", formatContextTokens(12_345))
        assertEquals("100k", formatContextTokens(100_000))
        assertEquals("200k", formatContextTokens(199_600))
        assertEquals("1.2M", formatContextTokens(1_234_567))
        assertEquals("12M", formatContextTokens(12_345_678))
    }
}
