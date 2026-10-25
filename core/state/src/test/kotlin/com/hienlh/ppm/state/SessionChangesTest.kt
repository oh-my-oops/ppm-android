package com.hienlh.ppm.state

import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.protocol.SessionAnswer
import com.hienlh.ppm.protocol.SessionAnswerFile
import com.hienlh.ppm.protocol.SessionAnswerResult
import com.hienlh.ppm.protocol.SessionBlockSummary
import com.hienlh.ppm.protocol.SessionFileAnswer
import com.hienlh.ppm.protocol.SessionFileChange
import com.hienlh.ppm.protocol.TurnRevertFile
import com.hienlh.ppm.protocol.TurnRevertResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A server's side of a session's changes, scripted. */
private class FakeChanges(var files: List<SessionFileChange>) : SessionChangesBackend {
    val asked = mutableListOf<List<String>>()
    val marked = mutableListOf<Pair<List<String>, Boolean>>()
    val answers = mutableListOf<Pair<SessionAnswer, List<SessionAnswerFile>>>()
    val reverts = mutableListOf<Pair<List<String>, List<Pair<String, String>>?>>()
    val undone = mutableListOf<String>()
    var holdMark: CompletableDeferred<Unit>? = null
    var holdList: CompletableDeferred<Unit>? = null
    var answerResult: SessionAnswerResult = SessionAnswerResult(emptyList())
    var revertResults = ArrayDeque<TurnRevertResult>()
    var undoResult = SessionAnswerResult(emptyList())
    var fail: Exception? = null
    var count = 0
    var counted = 0

    override suspend fun list(paths: List<String>): List<SessionFileChange> {
        asked += paths
        // The answer as it stood when asked, however long it takes to arrive.
        val answer = files
        holdList?.await()
        return answer
    }

    override suspend fun count(): Int {
        counted++
        return count
    }

    override suspend fun setReviewed(files: List<SessionFileChange>, reviewed: Boolean) {
        holdMark?.await()
        marked += files.map { it.path } to reviewed
    }

    override suspend fun answer(answer: SessionAnswer, files: List<SessionAnswerFile>): SessionAnswerResult {
        fail?.let { throw it }
        answers += answer to files
        return answerResult
    }

    override suspend fun revertTurn(calls: List<String>, apply: List<Pair<String, String>>?): TurnRevertResult {
        fail?.let { throw it }
        reverts += calls to apply
        return revertResults.removeFirst()
    }

    override suspend fun undo(undoId: String): SessionAnswerResult {
        undone += undoId
        return undoResult
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionChangesTest {
    /** Every pending step, background ones included, which `advanceUntilIdle` leaves alone. */
    private fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    private val app = SessionFileChange(
        "/p/app.ts", SessionFileChange.Status.MODIFIED, 3, 1, version = "v1",
        blocks = listOf(SessionBlockSummary("k1", 2, 0, calls = listOf("t1")), SessionBlockSummary("k2", 1, 1, calls = listOf("t2"))),
    )
    private val readme = SessionFileChange("/p/README.md", SessionFileChange.Status.ADDED, 5, 0, version = "v9")

    private fun edit(id: String) = FileEditFragment("a", "b", id, 0, "$id-0", false)
    private val turnChanges = listOf(TurnFileChange("/p/app.ts", FileChangeOp.EDIT, 2, 2, 2, listOf(edit("t1"), edit("t2")), false))
    private val turn = SessionTurn(1, "u1", null, "Make it", listOf("t1", "t2"))
    private val k1 = TurnReview.editKey("/p/app.ts", edit("t1"))
    private val k2 = TurnReview.editKey("/p/app.ts", edit("t2"))

    private fun TestScope.changes(server: FakeChanges) = SessionChanges(server, backgroundScope)

    @Test
    fun `the list is asked for only once opened, and until then the bar counts the transcript's files or the server's`() = runTest {
        val server = FakeChanges(listOf(app)).apply { count = 1 }
        val changes = changes(server)
        changes.update(listOf("/p/app.ts"), streaming = false)
        settle()
        assertEquals(1, server.counted)
        assertEquals(1, changes.unloadedCount.value)
        // A turn names a second file; the count is asked again only once it ends.
        changes.update(listOf("/p/app.ts", "/p/README.md"), streaming = true)
        settle()
        assertEquals(2, changes.unloadedCount.value)
        assertEquals(1, server.counted)
        // A row pushed before anyone asked: the list it would go into is not there yet.
        changes.push(listOf(readme), emptyList())
        assertEquals(emptyList<SessionFileChange>(), changes.files.value)
        // Shell commands wrote files no tool call names.
        server.count = 5
        changes.update(listOf("/p/app.ts", "/p/README.md"), streaming = false)
        settle()
        assertEquals(2, server.counted)
        assertEquals(5, changes.unloadedCount.value)
        assertEquals(0, server.asked.size)
        assertEquals(false, changes.loaded.value)

        changes.refresh()
        assertEquals(true, changes.loading.value)
        settle()
        assertEquals(listOf(listOf("/p/app.ts", "/p/README.md")), server.asked)
        assertEquals(listOf(app), changes.files.value)
        assertEquals(true to false, changes.loaded.value to changes.loading.value)
        // Asked for once: the next turn ends without asking again.
        changes.update(listOf("/p/app.ts", "/p/README.md"), streaming = true)
        changes.update(listOf("/p/app.ts", "/p/README.md"), streaming = false)
        settle()
        assertEquals(1, server.asked.size)
    }

    @Test
    fun `pushed rows are put in place without asking, a new file last, and gone files dropped`() = runTest {
        val server = FakeChanges(listOf(app))
        val changes = changes(server)
        changes.refresh()
        settle()
        val moved = app.copy(additions = 9, version = "v2")
        changes.push(listOf(moved, readme), emptyList())
        assertEquals(listOf(moved, readme), changes.files.value)
        assertEquals("an open Review tab follows the push", listOf(moved, readme), changes.listed.value?.files)
        changes.push(emptyList(), listOf("/p/app.ts"))
        assertEquals(listOf(readme), changes.files.value)
        assertEquals(1, server.asked.size)
    }

    @Test
    fun `a row pushed while the list is on its way is set aside, and the list asked for again once it is in`() = runTest {
        val server = FakeChanges(listOf(app)).apply { holdList = CompletableDeferred() }
        val changes = changes(server)
        changes.refresh()
        runCurrent()
        // The list being answered may already be older than this row.
        changes.push(listOf(readme), emptyList())
        server.files = listOf(app, readme)
        server.holdList!!.complete(Unit)
        settle()
        assertEquals(2, server.asked.size)
        assertEquals(listOf(app, readme), changes.files.value)
    }

    @Test
    fun `a newer ask replaces the one on its way`() = runTest {
        val server = FakeChanges(listOf(app)).apply { holdList = CompletableDeferred() }
        val changes = changes(server)
        changes.refresh()
        runCurrent()
        server.holdList = null
        server.files = listOf(app, readme)
        changes.refresh()
        settle()
        assertEquals(2, server.asked.size)
        assertEquals(listOf(app, readme), changes.files.value)
        assertEquals(false, changes.loading.value)
    }

    @Test
    fun `the list is asked for again when the socket connects, once it was asked for`() = runTest {
        val server = FakeChanges(listOf(app))
        val changes = changes(server)
        changes.reconnected()
        settle()
        assertEquals(0, server.asked.size)
        changes.refresh()
        settle()
        // The server restarted mid-turn: what the agent changed meanwhile was never pushed.
        server.files = listOf(app, readme)
        changes.reconnected()
        settle()
        assertEquals(2, server.asked.size)
        assertEquals(listOf(app, readme), changes.files.value)
    }

    @Test
    fun `a failed request keeps the last list`() = runTest {
        val server = object : SessionChangesBackend by FakeChanges(listOf(app)) {
            var calls = 0
            override suspend fun list(paths: List<String>): List<SessionFileChange> {
                if (calls++ > 0) throw PpmError.Unreachable("down")
                return listOf(app)
            }
        }
        val changes = SessionChanges(server, backgroundScope)
        changes.refresh()
        settle()
        changes.refresh()
        settle()
        assertEquals(listOf(app), changes.files.value)
        assertEquals(false, changes.loading.value)
    }

    @Test
    fun `marking reviewed shows at once, asks nothing while saving, then asks again`() = runTest {
        val server = FakeChanges(listOf(app, readme))
        val changes = changes(server)
        changes.refresh()
        settle()
        server.holdMark = CompletableDeferred()
        changes.setReviewed(listOf(readme), true)
        assertEquals(listOf(false, true), changes.files.value.map { it.reviewed })
        changes.refresh()
        changes.push(listOf(readme), emptyList())
        advanceTimeBy(1_000)
        assertEquals("no list while the marks are saved", 1, server.asked.size)
        assertEquals("a row pushed meanwhile waits for the list", listOf(false, true), changes.files.value.map { it.reviewed })
        server.files = listOf(app, readme.copy(reviewed = true))
        server.holdMark!!.complete(Unit)
        settle()
        assertEquals(listOf(listOf("/p/README.md") to true), server.marked)
        assertEquals(2, server.asked.size)
        assertEquals(listOf(false, true), changes.files.value.map { it.reviewed })
    }

    private fun TestScope.model(server: FakeChanges): Pair<SessionChanges, TurnReviewModel> {
        val changes = changes(server)
        changes.refresh()
        settle()
        return changes to TurnReviewModel(turnChanges, turn, changes, backgroundScope)
    }

    @Test
    fun `keep answers the open blocks of the edit, and asks for the list again`() = runTest {
        val server = FakeChanges(listOf(app))
        val (_, model) = model(server)
        assertEquals(EditReviewState.OPEN, model.reviews()[k1]?.state)
        model.keep(k1)
        settle()
        assertEquals(listOf(SessionAnswer.KEEP to listOf(SessionAnswerFile("/p/app.ts", "v1", listOf("k1")))), server.answers)
        assertEquals(2, server.asked.size)
        assertEquals(false, model.local.value.busy)
    }

    @Test
    fun `a revert names its blocks, marks the edits it reached, and Change undoes it`() = runTest {
        val server = FakeChanges(listOf(app))
        val (_, model) = model(server)
        server.answerResult = SessionAnswerResult(listOf(SessionFileAnswer("/p/app.ts", file = null)), undoId = "u-1")
        server.files = emptyList()
        model.revert(k2)
        settle()
        assertEquals(SessionAnswer.REVERT to listOf(SessionAnswerFile("/p/app.ts", "v1", listOf("k2"))), server.answers.single())
        assertEquals(TurnReviewNotice("Reverted 1 block.", "u-1"), model.local.value.notice)
        assertEquals(mapOf(k2 to "u-1"), model.local.value.reverted)
        assertEquals(EditReview(EditReviewState.REVERTED, keys = emptyList(), undoId = "u-1"), model.reviews()[k2])
        model.change(k2)
        settle()
        assertEquals(listOf("u-1"), server.undone)
        assertNull(model.local.value.notice)
        assertEquals(emptyMap<String, String>(), model.local.value.reverted)
    }

    @Test
    fun `an undo refused because a file moved says nothing was put back`() = runTest {
        val server = FakeChanges(listOf(app))
        val (_, model) = model(server)
        server.undoResult = SessionAnswerResult(emptyList(), stale = true)
        model.undo("u-9")
        settle()
        assertEquals(TurnReviewNotice("Nothing was put back: a file changed after the revert."), model.local.value.notice)
    }

    @Test
    fun `an answer to a file that moved, or that fails, says so`() = runTest {
        val server = FakeChanges(listOf(app))
        val (_, model) = model(server)
        server.answerResult = SessionAnswerResult(listOf(SessionFileAnswer("/p/app.ts", stale = true)))
        model.keepAll()
        settle()
        assertEquals(SessionAnswerFile("/p/app.ts", "v1", listOf("k1", "k2")), server.answers.single().second.single())
        assertEquals("A file changed since this was shown. Look again before answering.", model.local.value.notice?.text)
        server.fail = PpmError.Server(403, "That file is outside the project")
        model.keep(k1)
        settle()
        assertEquals(TurnReviewNotice("That file is outside the project"), model.local.value.notice)
    }

    @Test
    fun `revert turn previews, then reverts what was previewed, and a file that moved shows the newer preview`() = runTest {
        val server = FakeChanges(listOf(app))
        val (_, model) = model(server)
        val first = TurnRevertResult(listOf(TurnRevertFile("/p/app.ts", "v1", TurnRevertFile.Action.EDIT, 2, 2, 1)))
        val moved = TurnRevertResult(listOf(TurnRevertFile("/p/app.ts", "v2", TurnRevertFile.Action.EDIT, 1, 1, 0)), stale = true)
        val done = TurnRevertResult(listOf(TurnRevertFile("/p/app.ts", "v2", TurnRevertFile.Action.EDIT, 1, 1, 0)), undoId = "r-1")
        server.revertResults = ArrayDeque(listOf(first, moved, done))
        model.startRevert()
        assertEquals(RevertTurnFlow(), model.local.value.flow)
        settle()
        assertEquals(RevertTurnFlow(first, loading = false), model.local.value.flow)
        assertEquals("a preview writes nothing, so asks for no list", 1, server.asked.size)
        model.confirmRevert()
        settle()
        assertEquals(listOf("/p/app.ts" to "v1"), server.reverts[1].second)
        assertEquals(RevertTurnFlow(TurnRevertResult(moved.files), loading = false, moved = true), model.local.value.flow)
        model.confirmRevert()
        settle()
        assertEquals(listOf("/p/app.ts" to "v2"), server.reverts[2].second)
        assertEquals(listOf(listOf("t1", "t2")), server.reverts.map { it.first }.distinct())
        assertNull(model.local.value.flow)
        assertEquals(TurnReviewNotice("Reverted this turn's changes.", "r-1"), model.local.value.notice)
        assertEquals(mapOf(k1 to "r-1", k2 to "r-1"), model.local.value.reverted)
    }

    @Test
    fun `a revert that wrote nothing, or failed on a file, says so`() = runTest {
        val server = FakeChanges(listOf(app))
        val (_, model) = model(server)
        val preview = TurnRevertResult(listOf(TurnRevertFile("/p/app.ts", "v1", TurnRevertFile.Action.NONE)))
        server.revertResults = ArrayDeque(
            listOf(
                preview, TurnRevertResult(preview.files),
                preview, TurnRevertResult(listOf(TurnRevertFile("/p/app.ts", "v1", TurnRevertFile.Action.EDIT, error = "EACCES")), undoId = "r-2"),
            ),
        )
        model.startRevert()
        settle()
        model.confirmRevert()
        settle()
        assertEquals(TurnReviewNotice("Nothing to put back."), model.local.value.notice)
        model.startRevert()
        settle()
        model.confirmRevert()
        settle()
        assertEquals(TurnReviewNotice("1 file could not be written: EACCES", "r-2"), model.local.value.notice)
        assertTrue(model.local.value.reverted.isNotEmpty())
    }

    @Test
    fun `a preview that fails closes the confirmation and says why`() = runTest {
        val server = FakeChanges(listOf(app))
        val (_, model) = model(server)
        server.fail = PpmError.Server(500, "No history for this session")
        model.startRevert()
        settle()
        assertNull(model.local.value.flow)
        assertEquals(TurnReviewNotice("No history for this session"), model.local.value.notice)
    }
}
