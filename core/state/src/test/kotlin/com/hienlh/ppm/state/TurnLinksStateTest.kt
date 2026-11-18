package com.hienlh.ppm.state

import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.TurnWindowPage
import com.hienlh.ppm.client.TurnWindowQuery
import com.hienlh.ppm.client.WindowTurn
import com.hienlh.ppm.protocol.ChatFrame
import com.hienlh.ppm.protocol.ChatTurnRefs
import com.hienlh.ppm.protocol.HistoryMessage
import com.hienlh.ppm.protocol.TurnLinkTarget
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.generated.TurnReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Composer turn chips, the history window, Copy turn link's resolution and the transcript's turn bindings. */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnLinksStateTest {
    private val sid = "0f8e2a4c-1111-4000-8000-000000000001"

    private fun tid(n: Int) = "7a1b9c3d-2222-4000-8000-00000000000$n"

    private fun target(n: Int, provider: String = "claude") = TurnLinkTarget("ppm", provider, sid, tid(n))

    private fun ref(n: Int, provider: String = "claude") =
        TurnReference(1, "ppm", provider, sid, tid(n), n.toDouble(), "Title", "prompt", "2026-11-18T08:00:00.000Z")

    private fun gone() = PpmError.Server(404, "Turn not found", "not_found")

    // Composer chips

    @Test
    fun `a pasted link loads, then the message may go with its reference`() = runTest {
        val answer = CompletableDeferred<TurnReference?>()
        val chips = ComposerTurnChips(this) { answer.await() }
        var changes = 0
        chips.onChanged = { changes++ }
        assertTrue(chips.addLinks(listOf(target(1))).isEmpty())
        runCurrent()
        assertTrue(chips.chips.value.single() is ComposerTurnChip.Loading)
        assertEquals(ComposerTurnChips.LOADING_REASON, chips.blockedReason)
        answer.complete(ref(1))
        advanceUntilIdle()
        assertEquals(listOf(ref(1)), chips.refs)
        assertNull(chips.blockedReason)
        assertEquals(2, changes)
    }

    @Test
    fun `a turn that is gone or failed holds the send back until removed`() = runTest {
        val chips = ComposerTurnChips(this) { t -> if (t.turnId == tid(1)) throw gone() else throw IllegalStateException("x") }
        chips.addLinks(listOf(target(1), target(2)))
        advanceUntilIdle()
        assertEquals(
            listOf(ComposerTurnChips.GONE_REASON, ComposerTurnChips.FAILED_REASON),
            chips.chips.value.map { (it as ComposerTurnChip.Failed).reason },
        )
        assertEquals(ComposerTurnChips.ERROR_REASON, chips.blockedReason)
        chips.remove(target(1).key)
        chips.remove(target(2).key)
        assertNull(chips.blockedReason)
    }

    @Test
    fun `an answer for a chip removed meanwhile lands nowhere`() = runTest {
        val answer = CompletableDeferred<TurnReference?>()
        val chips = ComposerTurnChips(this) { answer.await() }
        chips.addLinks(listOf(target(1)))
        runCurrent()
        chips.remove(target(1).key)
        answer.complete(ref(1))
        advanceUntilIdle()
        assertTrue(chips.chips.value.isEmpty())
    }

    @Test
    fun `one chip per turn and ten at most, the rest stay text with a toast`() = runTest {
        val chips = ComposerTurnChips(this) { t -> ref(t.turnId.last().digitToInt()) }
        val toasts = mutableListOf<String>()
        chips.onOverflow = { title, _ -> toasts += title }
        chips.addLinks(listOf(target(1), target(1)))
        assertEquals(1, chips.chips.value.size)
        // Already a chip: not overflow, nothing said.
        assertTrue(chips.addLinks(listOf(target(1))).isEmpty())
        val many = (0..9).map { TurnLinkTarget("ppm", "codex", sid, tid(it)) }
        val overflow = chips.addLinks(many)
        assertEquals(ChatTurnRefs.MAX_TURN_REFS, chips.chips.value.size)
        assertEquals(1, overflow.size)
        assertEquals(listOf("A message can reference at most 10 turns"), toasts)
    }

    @Test
    fun `a refused message's chips join the ones pasted since, an edit's replace them`() = runTest {
        val chips = ComposerTurnChips(this) { t -> ref(t.turnId.last().digitToInt()) }
        chips.addLinks(listOf(target(1)))
        advanceUntilIdle()
        chips.merge(listOf(ref(1), ref(2)))
        assertEquals(listOf(ref(1), ref(2)), chips.refs)
        chips.replace(listOf(ref(3)))
        assertEquals(listOf(ref(3)), chips.refs)
        chips.replace(null)
        assertTrue(chips.chips.value.isEmpty())
    }

    // History window

    private fun page(vararg turns: Int, older: Boolean = false, newer: Boolean = false, latest: Int? = null) = TurnWindowPage(
        sessionId = sid,
        turns = turns.map { WindowTurn(tid(it), it, false) },
        messages = turns.flatMap { n ->
            listOf(
                HistoryMessage.from(WireJson.parse("""{"id":"u$n","role":"user","content":"q$n","turnId":"${tid(n)}","turnN":$n}"""))!!,
                HistoryMessage.from(WireJson.parse("""{"id":"a$n","role":"assistant","content":"a$n","turnId":"${tid(n)}","turnN":$n}"""))!!,
            )
        },
        hasOlder = older,
        hasNewer = newer,
        latestTurnId = latest?.let(::tid),
    )

    @Test
    fun `a window opens around the turn and grows at either end`() = runTest {
        val asked = mutableListOf<TurnWindowQuery>()
        val window = HistoryWindowController(this) { q ->
            asked += q
            when (q) {
                is TurnWindowQuery.Around -> page(3, 4, older = true, newer = true, latest = 9)
                is TurnWindowQuery.Before -> page(1, 2, older = false, latest = 9)
                is TurnWindowQuery.After -> page(5, newer = false, latest = 9)
            }
        }
        assertEquals(HistoryWindowController.OpenResult.OPENED, window.open(tid(3)))
        val opened = window.view.value as HistoryView.Window
        assertEquals(tid(3), opened.targetTurnId)
        assertEquals(listOf("u3", "a3", "u4", "a4"), opened.items.map { it.id })
        window.loadOlder()
        advanceUntilIdle()
        window.loadNewer()
        advanceUntilIdle()
        val grown = window.view.value as HistoryView.Window
        assertEquals((1..5).map(::tid), grown.turns.map { it.turnId })
        assertEquals(false, grown.hasOlder)
        assertEquals(false, grown.hasNewer)
        assertEquals(TurnWindowQuery.Before(tid(3), HistoryWindowController.PAGE_TURNS), asked[1])
        assertEquals(TurnWindowQuery.After(tid(4), HistoryWindowController.PAGE_TURNS), asked[2])
        window.exit()
        assertEquals(HistoryView.Live, window.view.value)
    }

    @Test
    fun `a turn the session no longer has is gone, anything else failed`() = runTest {
        val gone = HistoryWindowController(this) { throw gone() }
        assertEquals(HistoryWindowController.OpenResult.GONE, gone.open(tid(1)))
        val failed = HistoryWindowController(this) { throw IllegalStateException("boom") }
        assertEquals(HistoryWindowController.OpenResult.FAILED, failed.open(tid(1)))
        assertEquals(HistoryView.Live, failed.view.value)
    }

    @Test
    fun `an answer for a window left meanwhile does not land`() = runTest {
        val answer = CompletableDeferred<TurnWindowPage>()
        val window = HistoryWindowController(this) { answer.await() }
        val result = async { window.open(tid(1)) }
        runCurrent()
        window.exit()
        answer.complete(page(1))
        assertEquals(HistoryWindowController.OpenResult.STALE, result.await())
        assertEquals(HistoryView.Live, window.view.value)
    }

    @Test
    fun `the window keeps thirty turns, dropping the far end`() {
        val start = HistoryWindowModel.enter(HistoryView.Live, page(*(1..9).toList().toIntArray(), newer = true), tid(1))
        var view: HistoryView = start
        var next = 10
        repeat(4) {
            val turns = (next until next + 7).toList().toIntArray()
            view = HistoryWindowModel.loaded(view, LoadDirection.NEWER, page(*turns, newer = true))
            next += 7
        }
        val window = view as HistoryView.Window
        assertEquals(HistoryWindowModel.MAX_WINDOW_TURNS, window.turns.size)
        assertEquals(tid(37).takeLast(2), window.turns.last().turnId.takeLast(2))
        assertTrue(window.hasOlder)
        assertTrue(window.items.none { it.id == "u1" })
    }

    @Test
    fun `the live list takes over once it holds the window's last turn whole`() {
        val window = HistoryWindowModel.enter(HistoryView.Live, page(2, 3, latest = 3), tid(2))
        fun item(id: String, role: ChatItem.Role, turn: String?) = ChatItem(id, role, listOf(ChatBlock.Text(id)), null, turnId = turn)
        val partial = listOf(item("x", ChatItem.Role.USER, tid(3)), item("y", ChatItem.Role.ASSISTANT, tid(3)))
        // The newest turn, and nothing streams: whole.
        assertTrue(HistoryWindowModel.canMergeToLive(window, partial, liveStreaming = false))
        // Still streaming: not yet.
        assertTrue(!HistoryWindowModel.canMergeToLive(window, partial, liveStreaming = true))
        // A later turn has started: whole even while that one streams.
        assertTrue(HistoryWindowModel.canMergeToLive(window, partial + item("z", ChatItem.Role.USER, tid(4)), liveStreaming = true))
        // The live list does not hold that turn at all.
        assertTrue(!HistoryWindowModel.canMergeToLive(window, listOf(item("q", ChatItem.Role.USER, tid(9))), liveStreaming = false))
    }

    // Copy turn link

    @Test
    fun `a stamped or bound message names its turn at once`() {
        val stamped = ChatItem("m", ChatItem.Role.USER, emptyList(), null, turnId = tid(1))
        assertEquals(tid(1), TurnIdResolver.known(stamped, emptyMap()))
        val sent = ChatItem("local-1", ChatItem.Role.USER, emptyList(), null)
        assertEquals(tid(2), TurnIdResolver.known(sent, mapOf("local-1" to tid(2))))
        assertNull(TurnIdResolver.known(sent, emptyMap()))
    }

    @Test
    fun `an unknown message asks the index, then waits for its turn to start`() = runTest {
        val item = ChatItem("local-1", ChatItem.Role.USER, emptyList(), null, sdkUuid = "uuid")
        assertEquals(tid(5), TurnIdResolver.resolve({ item }, { emptyMap() }, { tid(5) }))
        var bindings = emptyMap<String, String>()
        val waiting = async { TurnIdResolver.resolve({ item }, { bindings }, { throw IllegalStateException("down") }) }
        advanceTimeBy(1_000)
        bindings = mapOf("local-1" to tid(6))
        advanceTimeBy(TurnIdResolver.POLL_MILLIS + 1)
        assertEquals(tid(6), waiting.await())
        // Nothing ever names it: gives up after the wait.
        val never = async { TurnIdResolver.resolve({ item }, { emptyMap() }, { null }) }
        advanceTimeBy(TurnIdResolver.WAIT_MILLIS + 1_000)
        assertNull(never.await())
    }

    // The transcript

    @Test
    fun `turn_started binds the message sent under that id, a late socket gets the bindings replayed`() {
        val chat = ChatTranscript()
        val sent = chat.send("hello", turnRefs = listOf(ref(1)), ownProject = "ppm")
        assertEquals(listOf(ref(1)), sent.turnRefs)
        // Shown as the server stores it: the block travels inside the text.
        assertEquals(listOf(ref(1)), ChatTurnRefs.decode(chat.items.single().text).turnRefs)
        chat.handle(ChatFrame.TurnStarted(tid(1), sent.clientMessageId))
        assertEquals(tid(1), chat.turnBindings[sent.clientMessageId])
        val late = ChatTranscript()
        late.handle(frame("""{"type":"session_state","sessionId":"s","phase":"idle","turnBindings":[{"clientMessageId":"c9","turnId":"${tid(2)}"}]}"""))
        assertEquals(mapOf("c9" to tid(2)), late.turnBindings)
    }

    @Test
    fun `a refused message gives its chips back with its text`() {
        val chat = ChatTranscript()
        val sent = chat.send("hello", turnRefs = listOf(ref(1)))
        chat.handle(ChatFrame.MessageRejected(sent.clientMessageId, "Nope"))
        assertEquals(RejectedSend("hello", null, "Nope", listOf(ref(1))), chat.rejected)
    }

    // Presence

    @Test
    fun `input is sent at most once a minute, and a send the socket could not take waits for it`() {
        var now = 0L
        var open = false
        val sent = mutableListOf<String>()
        val presence = PresenceInput({ frame -> if (open) sent.add(frame) else false }, now = { now })
        presence.input()
        assertTrue(sent.isEmpty())
        open = true
        presence.retryIfPending()
        assertEquals(listOf(PresenceInput.FRAME), sent)
        now = 30_000
        presence.input()
        assertEquals(1, sent.size)
        presence.retryIfPending()
        assertEquals(1, sent.size)
        now = 60_000
        presence.input()
        assertEquals(2, sent.size)
    }

    // Live sync

    @Test
    fun `the read-again broadcasts reach their listeners, malformed ones nobody`() {
        val events = LiveSyncListeners()
        val heard = mutableListOf<String>()
        events.sessionsList.add { project, renamed -> heard += "sessions:$project:${renamed?.title}" }
        events.projects.add { heard += "projects" }
        events.settings.add { section, client -> heard += "settings:$section:$client" }
        events.dbConnections.add { heard += "db" }
        events.extensions.add { heard += "ext" }
        val off = events.designList.add { project -> heard += "design:$project" }
        val frames = listOf(
            """{"type":"sessions:list_changed","projectName":"p","renamed":{"sessionId":"s","title":"New"}}""",
            """{"type":"sessions:list_changed","projectName":"p","renamed":{"sessionId":"s","title":"  "}}""",
            """{"type":"sessions:list_changed"}""",
            """{"type":"projects:changed"}""",
            """{"type":"settings:changed","section":"theme","clientId":"abcdef12-3456"}""",
            """{"type":"settings:changed","section":"ai","clientId":"no"}""",
            """{"type":"settings:changed","section":"secrets"}""",
            """{"type":"db:connections_changed"}""",
            """{"type":"extensions:changed"}""",
            """{"type":"design:list_changed","projectName":"p"}""",
        )
        for (f in frames) events.handle(WireJson.parse(f))
        off()
        events.handle(WireJson.parse("""{"type":"design:list_changed","projectName":"p"}"""))
        assertEquals(
            listOf("sessions:p:New", "sessions:p:null", "projects", "settings:theme:abcdef12-3456", "settings:ai:null", "db", "ext", "design:p"),
            heard,
        )
    }
}
