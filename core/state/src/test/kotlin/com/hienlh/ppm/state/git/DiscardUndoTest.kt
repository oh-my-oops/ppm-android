package com.hienlh.ppm.state.git

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.ChangeSide
import com.hienlh.ppm.client.ChangedFile
import com.hienlh.ppm.client.DISCARD_UNDO_CONFLICT_MESSAGE
import com.hienlh.ppm.client.DiscardRecord
import com.hienlh.ppm.client.DiscardUndoResult
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.gitDiscard
import com.hienlh.ppm.client.gitDiscardUndo
import com.hienlh.ppm.client.gitDiscardUndoResult
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the single-file Discard confirmation, 8-second Undo bar, 409 conflict
 * reporting, and >20 MB skipped-snapshot warning (Requirement 9.4, `design.md` Section 2).
 */
class DiscardUndoTest {

    private val modifiedFile = ChangedFile(
        path = "src/server/routes/git.ts",
        staged = null,
        unstaged = ChangeSide(added = 8, removed = 2, blocks = listOf("b0")),
        conflict = false,
        x = ".",
        y = "M",
        untracked = false,
    )

    private val untrackedFile = ChangedFile(
        path = "src/web/new-feature.tsx",
        staged = null,
        unstaged = ChangeSide(added = 24, removed = 0, blocks = emptyList(), whole = "new file"),
        conflict = false,
        x = "?",
        y = "?",
        untracked = true,
    )

    @Test
    fun `requesting discard opens confirmation dialog and cancel closes it without discarding`() {
        val controller = DiscardUndoController()
        val changeRow = GitChangeGroups.from(listOf(modifiedFile)).changes.single()

        val dialog = controller.requestDiscard(changeRow)
        assertNotNull(controller.confirmDialog)
        assertEquals("src/server/routes/git.ts", dialog.targetPath)
        assertEquals("Discard changes to git.ts?", dialog.title)
        assertTrue(dialog.body.contains("You can undo it right after."))

        controller.cancelDiscard()
        assertNull(controller.confirmDialog)
        assertNull(controller.undoBar)
    }

    @Test
    fun `untracked file confirmation dialog explains deletion and undo availability`() {
        val controller = DiscardUndoController()
        val dialog = controller.requestDiscard(untrackedFile)
        assertEquals("Delete new-feature.tsx?", dialog.title)
        assertEquals("Delete file", dialog.confirmLabel)
        assertTrue(dialog.body.contains("You can undo it right after."))
    }

    @Test
    fun `confirming discard starts 8-second undo bar and expires after 8000ms`() = runBlocking {
        val controller = DiscardUndoController()
        controller.requestDiscard(modifiedFile)

        var discardedPaths: List<String>? = null
        val outcome = controller.confirmDiscard { files ->
            discardedPaths = files
            DiscardRecord(
                id = "discard-rec-42",
                createdAt = 1_700_000_000_000L,
                files = files,
                skipped = emptyList(),
            )
        }

        assertEquals(listOf("src/server/routes/git.ts"), discardedPaths)
        assertTrue(outcome is DiscardUndoOutcome.Discarded)
        assertNull(controller.confirmDialog)

        val bar = controller.undoBar
        assertNotNull(bar)
        assertTrue(bar!!.active)
        assertEquals("discard-rec-42", bar.recordId)
        assertEquals("Discarded git.ts", bar.message)
        assertEquals(8_000L, bar.remainingMs)
        assertEquals(8, bar.remainingSeconds)

        // Advance 2.5 seconds -> 6s remaining
        assertFalse(controller.tick(2_500L))
        assertEquals(5_500L, controller.undoBar?.remainingMs)
        assertEquals(6, controller.undoBar?.remainingSeconds)

        // Advance remaining 5.5 seconds -> expires at 8.0s
        assertTrue(controller.tick(5_500L))
        assertNull(controller.undoBar)
    }

    @Test
    fun `tapping Undo within 8 seconds calls discard undo with record id and restores file`() = runBlocking {
        val controller = DiscardUndoController()
        controller.requestDiscard(modifiedFile)
        controller.confirmDiscard { files ->
            DiscardRecord(
                id = "discard-rec-99",
                createdAt = 1_700_000_000_000L,
                files = files,
                skipped = emptyList(),
            )
        }

        controller.tick(3_000L)
        assertEquals(5, controller.undoBar?.remainingSeconds)

        var undoCalledWithId: String? = null
        val undoOutcome = controller.undo { recordId ->
            undoCalledWithId = recordId
        }

        assertEquals("discard-rec-99", undoCalledWithId)
        assertNull(controller.undoBar)
        assertEquals(
            DiscardUndoOutcome.Restored(
                recordId = "discard-rec-99",
                paths = listOf("src/server/routes/git.ts"),
            ),
            undoOutcome,
        )
        assertNull(controller.errorMessage)
    }

    @Test
    fun `HTTP 409 from discard undo reports The file changed since so Undo would overwrite it`() = runBlocking {
        val controller = DiscardUndoController()
        controller.requestDiscard(modifiedFile)
        controller.confirmDiscard { files ->
            DiscardRecord(
                id = "discard-rec-409",
                createdAt = 1_700_000_000_000L,
                files = files,
                skipped = emptyList(),
            )
        }

        val outcome = controller.undo { _ ->
            throw PpmError.Server(
                status = 409,
                serverMessage = DISCARD_UNDO_CONFLICT_MESSAGE,
            )
        }

        assertNull(controller.undoBar)
        assertEquals(
            "The file changed since, so Undo would overwrite it",
            controller.errorMessage,
        )
        assertEquals(
            DiscardUndoOutcome.Conflict(
                recordId = "discard-rec-409",
                message = "The file changed since, so Undo would overwrite it",
            ),
            outcome,
        )
    }

    @Test
    fun `file over 20 MB skipped by journal warns user and does not show Undo bar`() = runBlocking {
        val controller = DiscardUndoController()
        val hugeFile = modifiedFile.copy(path = "assets/huge-video.mp4")
        controller.requestDiscard(hugeFile)

        val outcome = controller.confirmDiscard { _ ->
            DiscardRecord(
                id = "discard-rec-skip",
                createdAt = 1_700_000_000_000L,
                files = emptyList(),
                skipped = listOf("assets/huge-video.mp4"),
            )
        }

        assertNull(controller.undoBar)
        assertTrue(outcome is DiscardUndoOutcome.SkippedTooLarge)
        assertEquals(
            "Discarded huge-video.mp4 (too large to keep an undo snapshot)",
            controller.warningMessage,
        )
    }

    @Test
    fun `ApiClient gitDiscard and gitDiscardUndo over HTTP handle 200 restore and 409 conflict`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"ok":true,"data":{"undo":{"id":"srv-undo-1","createdAt":1700000000,"paths":["src/server/routes/git.ts"],"skipped":[]}}}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(409)
                .body("""{"ok":false,"error":"File src/server/routes/git.ts has changed since it was discarded"}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(409)
                .body("""{"ok":false,"error":"File src/server/routes/git.ts has changed since it was discarded"}""")
                .build(),
        )
        server.start()
        try {
            val api = ApiClient(server.url("/"), "token")
            val controller = DiscardUndoController()
            controller.requestDiscard(modifiedFile)

            val discardOutcome = controller.confirmDiscard { files ->
                api.gitDiscard("ppm", files)
            }
            assertTrue(discardOutcome is DiscardUndoOutcome.Discarded)
            assertEquals("srv-undo-1", controller.undoBar?.recordId)

            val undoOutcome = controller.undo { recordId ->
                api.gitDiscardUndo("ppm", recordId)
            }
            assertEquals(
                DiscardUndoOutcome.Conflict(
                    recordId = "srv-undo-1",
                    message = "The file changed since, so Undo would overwrite it",
                ),
                undoOutcome,
            )
            assertEquals("The file changed since, so Undo would overwrite it", controller.errorMessage)

            val directResult = api.gitDiscardUndoResult("ppm", "srv-undo-1")
            assertTrue(directResult is DiscardUndoResult.Conflict)
            assertEquals(
                "The file changed since, so Undo would overwrite it",
                (directResult as DiscardUndoResult.Conflict).message,
            )

            assertEquals("/api/project/ppm/git/discard", server.takeRequest().target)
            assertEquals("/api/project/ppm/git/discard/undo", server.takeRequest().target)
            assertEquals("/api/project/ppm/git/discard/undo", server.takeRequest().target)
        } finally {
            server.close()
        }
    }
}
