package com.hienlh.ppm.state

import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.ChatFrame
import com.hienlh.ppm.protocol.LiveBackgroundTask
import com.hienlh.ppm.protocol.SessionPhase
import com.hienlh.ppm.protocol.SessionStateFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The chat state behind the context meter, the background tasks tab, the suggested prompt and
 * `/btw` (`use-chat.ts`, `chat-tab.tsx`, `side-question-panel.tsx`, `activity-chip.tsx`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatFooterExtrasTest {
    private fun ChatTranscript.phase(p: SessionPhase) = handle(ChatFrame.PhaseChanged(p))

    private fun done(pct: Int?) = ChatFrame.Event(ChatEvent.Done(contextWindowPct = pct), null)

    @Test
    fun `the context figure is the one a running turn's done reported, and a later done without one keeps it`() {
        val chat = ChatTranscript("claude")
        assertNull(chat.contextWindowPct)
        chat.phase(SessionPhase.STREAMING)
        chat.handle(done(42))
        assertEquals(42, chat.contextWindowPct)
        // A second done for the same turn finds the phase idle: never read (`if (phaseRef.current === "idle") break`).
        chat.phase(SessionPhase.IDLE)
        chat.handle(done(90))
        assertEquals(42, chat.contextWindowPct)
        chat.phase(SessionPhase.THINKING)
        chat.handle(done(null))
        assertEquals(42, chat.contextWindowPct)
        chat.phase(SessionPhase.STREAMING)
        chat.handle(ChatFrame.Event(ChatEvent.Done(parent = "toolu_1", contextWindowPct = 7), null))
        assertEquals(42, chat.contextWindowPct)
    }

    @Test
    fun `the background tasks are the server's last list`() {
        val chat = ChatTranscript("claude")
        val a = LiveBackgroundTask("b1", "local_bash", "bun dev")
        chat.handle(ChatFrame.BackgroundTasks(listOf(a)))
        assertEquals(listOf(a), chat.liveTasks)
        chat.handle(ChatFrame.BackgroundTasks(emptyList()))
        assertEquals(emptyList<LiveBackgroundTask>(), chat.liveTasks)
    }

    @Test
    fun `a suggestion is kept while idle and goes when a turn starts or a message is sent`() {
        val chat = ChatTranscript("claude")
        chat.handle(ChatFrame.PromptSuggestion("Run the tests"))
        assertEquals("Run the tests", chat.promptSuggestion)
        chat.phase(SessionPhase.THINKING)
        assertNull(chat.promptSuggestion)
        // One arriving mid-turn is not shown.
        chat.handle(ChatFrame.PromptSuggestion("Late"))
        assertNull(chat.promptSuggestion)
        chat.phase(SessionPhase.IDLE)
        chat.handle(ChatFrame.PromptSuggestion("Commit it"))
        chat.send("something else")
        assertNull(chat.promptSuggestion)
        chat.handle(ChatFrame.SessionState(SessionStateFrame(phase = SessionPhase.IDLE)))
        chat.handle(ChatFrame.PromptSuggestion("Again"))
        chat.handle(ChatFrame.SessionState(SessionStateFrame(phase = SessionPhase.STREAMING)))
        assertNull(chat.promptSuggestion)
    }

    @Test
    fun `the composer offers a suggestion only on an empty, idle, online Claude box with no agent`() {
        fun offer(
            suggestion: String? = "Run it",
            provider: String? = "claude",
            running: Boolean = false,
            offline: Boolean = false,
            disabled: Boolean = false,
            text: String = "",
            agent: Boolean = false,
        ) = offeredPromptSuggestion(suggestion, provider, running, offline, disabled, text, agent)
        assertEquals("Run it", offer())
        assertNull(offer(suggestion = null))
        assertNull(offer(suggestion = ""))
        assertNull(offer(provider = "codex"))
        assertNull(offer(running = true))
        assertNull(offer(offline = true))
        assertNull(offer(disabled = true))
        assertNull(offer(text = "a"))
        assertNull(offer(agent = true))
    }

    @Test
    fun `btw is read off the message, and refused with the web's words`() {
        assertEquals("where is it?", SideQuestionSend.questionOf("  /btw   where is it?  "))
        assertEquals("two\nlines", SideQuestionSend.questionOf("/btw two\nlines"))
        assertEquals("", SideQuestionSend.questionOf("/btw"))
        assertNull(SideQuestionSend.questionOf("/btwx hi"))
        assertNull(SideQuestionSend.questionOf("say /btw hi"))

        assertEquals(SideQuestionSend.NotOne, SideQuestionSend.of("hello", "s1", "claude", false))
        assertEquals(SideQuestionSend.Refused("Ask something after /btw"), SideQuestionSend.of("/btw  ", "s1", "claude", false))
        val needsClaude = SideQuestionSend.Refused("/btw needs a Claude chat that has already started")
        assertEquals(needsClaude, SideQuestionSend.of("/btw q", null, "claude", false))
        assertEquals(needsClaude, SideQuestionSend.of("/btw q", "s1", "codex", false))
        assertEquals(
            SideQuestionSend.Refused("A side question takes only text: remove the reply, chips and attachments first"),
            SideQuestionSend.of("/btw q", "s1", "claude", true),
        )
        assertEquals(SideQuestionSend.Ask("q"), SideQuestionSend.of("/btw q", "s1", "claude", false))
    }

    @Test
    fun `a side question is asked, answered, failed or closed, and a newer one wins`() = runTest {
        val questions = SideQuestions(this)
        val first = CompletableDeferred<String?>()
        questions.ask("tab", "s1", "one?") { first.await() }
        runCurrent()
        assertEquals(SideQuestionState.Asking("one?"), questions.states.value["tab"])
        // Asked again before the answer: the first answer is dropped.
        val second = CompletableDeferred<String?>()
        questions.ask("tab", "s1", "two?") { second.await() }
        first.complete("old")
        runCurrent()
        assertEquals(SideQuestionState.Asking("two?"), questions.states.value["tab"])
        second.complete("new")
        runCurrent()
        assertEquals(SideQuestionState.Answered("two?", "new"), questions.states.value["tab"])

        questions.ask("tab", "s1", "three?") { throw PpmError.Server(409, "This chat's Claude process is not running.") }
        runCurrent()
        assertEquals(SideQuestionState.Failed("three?", "This chat's Claude process is not running."), questions.states.value["tab"])

        val pending = CompletableDeferred<String?>()
        questions.ask("tab", "s1", "four?") { pending.await() }
        questions.close("tab")
        pending.complete("late")
        runCurrent()
        assertNull(questions.states.value["tab"])
    }

    @Test
    fun `a side question goes when its tab shows another session`() = runTest {
        val questions = SideQuestions(this)
        questions.ask("tab", "s1", "q") { "a" }
        runCurrent()
        questions.follow("tab", "s1")
        assertEquals(SideQuestionState.Answered("q", "a"), questions.states.value["tab"])
        questions.follow("tab", "s2")
        assertNull(questions.states.value["tab"])
    }

    @Test
    fun `the meter's colour and the activity popover's tabs`() {
        assertEquals(ContextMeterTone.NORMAL, contextMeterTone(74))
        assertEquals(ContextMeterTone.WARNING, contextMeterTone(75))
        assertEquals(ContextMeterTone.WARNING, contextMeterTone(89))
        assertEquals(ContextMeterTone.ERROR, contextMeterTone(90))

        assertEquals(ActivityPopoverTab.SESSIONS, activityOpeningTab(needsYou = true, hasAgents = true, hasTasks = true))
        assertEquals(ActivityPopoverTab.AGENTS, activityOpeningTab(needsYou = false, hasAgents = true, hasTasks = true))
        assertEquals(ActivityPopoverTab.TASKS, activityOpeningTab(needsYou = false, hasAgents = false, hasTasks = true))
        assertEquals(ActivityPopoverTab.SESSIONS, activityOpeningTab(needsYou = false, hasAgents = false, hasTasks = false))

        assertEquals(ActivityPopoverTab.TASKS, activityShownTab(ActivityPopoverTab.TASKS, hasAgents = false, hasTasks = true))
        // The last task ended with its tab open: Sessions.
        assertEquals(ActivityPopoverTab.SESSIONS, activityShownTab(ActivityPopoverTab.TASKS, hasAgents = true, hasTasks = false))
        assertEquals(ActivityPopoverTab.SESSIONS, activityShownTab(ActivityPopoverTab.AGENTS, hasAgents = false, hasTasks = true))
    }
}
