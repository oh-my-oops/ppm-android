package com.hienlh.ppm.state

import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.protocol.SessionAnswer
import com.hienlh.ppm.protocol.SessionAnswerFile
import com.hienlh.ppm.protocol.SessionAnswerResult
import com.hienlh.ppm.protocol.SessionFileChange
import com.hienlh.ppm.protocol.TurnRevertFile
import com.hienlh.ppm.protocol.TurnRevertResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rewind code: preview, write at the versions shown, a file that moved, undo, and failures. */
class RewindCodeTest {
    private class FakeRewind : SessionChangesBackend {
        val asked = mutableListOf<Pair<List<String>, List<Pair<String, String>>?>>()
        val answers = ArrayDeque<() -> TurnRevertResult>()
        var undoAnswer: () -> SessionAnswerResult = { SessionAnswerResult(emptyList()) }
        val undone = mutableListOf<String>()
        override suspend fun list(paths: List<String>): List<SessionFileChange> = emptyList()
        override suspend fun count() = 0
        override suspend fun setReviewed(files: List<SessionFileChange>, reviewed: Boolean) = Unit
        override suspend fun answer(answer: SessionAnswer, files: List<SessionAnswerFile>) = SessionAnswerResult(emptyList())
        override suspend fun revertTurn(calls: List<String>, apply: List<Pair<String, String>>?): TurnRevertResult {
            asked += calls to apply
            return answers.removeFirst()()
        }
        override suspend fun undo(undoId: String): SessionAnswerResult {
            undone += undoId
            return undoAnswer()
        }
    }

    private fun file(path: String, version: String = "v1", action: TurnRevertFile.Action = TurnRevertFile.Action.EDIT, added: Int = 0, removed: Int = 0, error: String? = null) =
        TurnRevertFile(path, version, action, changes = 1, added = added, removed = removed, error = error)

    @Test
    fun `preview, then write each file at the version shown, then undo`() = runTest {
        val fake = FakeRewind()
        var settled = 0
        val flow = RewindCode(fake, listOf("c1", "c2"), settle = { settled++ })
        fake.answers += { TurnRevertResult(listOf(file("a.ts", "v1", added = 2, removed = 3), file("b.ts", "v9", TurnRevertFile.Action.NONE))) }
        flow.start()
        val preview = flow.stage.value as RewindCode.Stage.Preview
        assertEquals(listOf("a.ts"), preview.files.map { it.path })
        assertFalse(preview.nothing)
        assertEquals(listOf("c1", "c2") to null, fake.asked[0])

        fake.answers += { TurnRevertResult(listOf(file("a.ts", "v2")), undoId = "u1") }
        assertTrue(flow.confirm())
        assertEquals(listOf("a.ts" to "v1", "b.ts" to "v9"), fake.asked[1].second)
        assertEquals(RewindCode.Stage.Done("Rewound 1 file to before this message.", "u1"), flow.stage.value)
        assertEquals(1, settled)

        flow.undo()
        assertEquals(listOf("u1"), fake.undone)
        assertEquals(RewindCode.Stage.Done("Undone: the files are as they were before the rewind."), flow.stage.value)
        assertEquals(2, settled)
        flow.close()
        assertNull(flow.stage.value)
    }

    @Test
    fun `a file that moved shows the preview again, and nothing is forked`() = runTest {
        val fake = FakeRewind()
        val flow = RewindCode(fake, listOf("c1"))
        fake.answers += { TurnRevertResult(listOf(file("a.ts", "v1"))) }
        fake.answers += { TurnRevertResult(listOf(file("a.ts", "v2", added = 1)), stale = true) }
        flow.start()
        assertFalse(flow.confirm())
        val again = flow.stage.value as RewindCode.Stage.Preview
        assertTrue(again.moved)
        assertEquals("v2", again.preview.files.single().version)
    }

    @Test
    fun `nothing left to put back, a file that failed, an undo that came too late, an error`() = runTest {
        val fake = FakeRewind()
        val flow = RewindCode(fake, listOf("c1"))
        fake.answers += { TurnRevertResult(listOf(file("a.ts", action = TurnRevertFile.Action.NONE))) }
        flow.start()
        assertTrue((flow.stage.value as RewindCode.Stage.Preview).nothing)

        fake.answers += { TurnRevertResult(listOf(file("a.ts"))) }
        fake.answers += { TurnRevertResult(listOf(file("a.ts", error = "EACCES"), file("b.ts")), undoId = "u2") }
        flow.start()
        assertFalse(flow.confirm())
        assertEquals("1 file could not be written: EACCES", (flow.stage.value as RewindCode.Stage.Done).text)

        fake.undoAnswer = { SessionAnswerResult(emptyList(), stale = true) }
        flow.undo()
        assertEquals("Nothing was put back: a file changed after the rewind.", (flow.stage.value as RewindCode.Stage.Done).text)

        fake.answers += { throw PpmError.Server(404, "Not found") }
        flow.start()
        assertEquals(RewindCode.Stage.Done("Not found"), flow.stage.value)
    }

    @Test
    fun `the intro counts the turns`() {
        assertEquals(
            "Puts the files back as they were before this message, undoing what 1 turn wrote. The chat itself stays as it is.",
            RewindCode.intro(1),
        )
        assertEquals("3 files", RewindCode.plural(3, "file"))
    }
}
