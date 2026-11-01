package com.hienlh.ppm.state.osexplorer

import com.hienlh.ppm.state.files.FileMutationItem
import com.hienlh.ppm.state.files.FileMutationOperation
import com.hienlh.ppm.state.files.FileMutationOutcome
import com.hienlh.ppm.state.files.FileTransferFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `commitInline` (use-explorer-actions.ts), the toasts of `createEntry` and `renameEntry` (explorer-actions-mutate.ts), and what a
 * transfer says (`transfer()` in explorer-actions-clipboard.ts, and the line `pasteInto` and a window's `transferInto` add).
 */
class HostExplorerNamesTest {
    @Test
    fun `the name is trimmed before it is checked and before it is sent`() {
        assertEquals(HostNameCommit.Send("notes.txt"), hostNameCommit("  notes.txt\t", null, null))
        assertEquals(HostNameCommit.Send("b.txt"), hostNameCommit(" b.txt ", "a.txt", null))
    }

    @Test
    fun `an empty name, or one of spaces, is refused in the web's words instead of closing the field`() {
        assertEquals(HostNameCommit.Refused("Name cannot be empty"), hostNameCommit("", null, null))
        assertEquals(HostNameCommit.Refused("Name cannot be empty"), hostNameCommit("   ", null, null))
        assertEquals(HostNameCommit.Refused("Name cannot be empty"), hostNameCommit("", "a.txt", null))
    }

    @Test
    fun `a rename to the name the entry has, spaces apart, sends nothing and closes the field`() {
        assertEquals(HostNameCommit.Unchanged, hostNameCommit("a.txt", "a.txt", null))
        assertEquals(HostNameCommit.Unchanged, hostNameCommit(" a.txt ", "a.txt", null))
    }

    @Test
    fun `a new file is never unchanged, and the host's rules refuse a name, Windows ones only on a Windows host`() {
        assertEquals(HostNameCommit.Send("a.txt"), hostNameCommit("a.txt", null, null))
        assertEquals(HostNameCommit.Refused("Name cannot contain path separators"), hostNameCommit("a/b", null, null))
        assertEquals(HostNameCommit.Refused("Reserved name"), hostNameCommit("..", null, null))
        assertEquals(HostNameCommit.Refused("\"con.txt\" is a reserved Windows name"), hostNameCommit("con.txt", null, "win32"))
        assertEquals(HostNameCommit.Send("con.txt"), hostNameCommit("con.txt", null, "linux"))
    }

    private fun failed(operation: FileMutationOperation, error: String?) =
        FileMutationItem(operation, null, "/r/x", null, FileMutationOutcome.FAILED, error)

    @Test
    fun `a failed create or rename is a toast naming what failed over the reason`() {
        assertEquals(HostToast("Could not create file", "File exists"), hostFailureToast(failed(FileMutationOperation.CREATE_FILE, "File exists")))
        assertEquals(HostToast("Could not create folder", "No space left"), hostFailureToast(failed(FileMutationOperation.CREATE_DIRECTORY, "No space left")))
        assertEquals(HostToast("Rename failed", "Permission denied"), hostFailureToast(failed(FileMutationOperation.RENAME, "Permission denied")))
        assertEquals(HostToast("Rename failed", null), hostFailureToast(failed(FileMutationOperation.RENAME, null)))
    }

    private fun transferred(
        operation: FileMutationOperation, path: String, error: String? = "Permission denied", step: FileTransferFailure? = null,
        outcome: FileMutationOutcome = FileMutationOutcome.FAILED,
    ) = FileMutationItem(operation, null, path, "/dst/" + path.substringAfterLast('/'), outcome, error, transferFailure = step)

    @Test
    fun `a move or a copy that failed names the entry and says the reason under it, as transfer() does`() {
        assertEquals(HostToast("Move failed: app.kt", "Permission denied"), hostFailureToast(transferred(FileMutationOperation.MOVE, "/home/me/p/src/app.kt")))
        assertEquals(HostToast("Copy failed: app.kt", "Permission denied"), hostFailureToast(transferred(FileMutationOperation.COPY, "/home/me/p/src/app.kt")))
        assertEquals("the last part of the path, whichever separator the host uses", "Move failed: app.kt",
            hostFailureToast(transferred(FileMutationOperation.MOVE, "C:\\p\\src\\app.kt")).title)
        assertEquals("a folder's trailing separator is not its name", "Copy failed: src",
            hostFailureToast(transferred(FileMutationOperation.COPY, "/home/me/p/src/")).title)
        assertNull("no reason, no description", hostFailureToast(transferred(FileMutationOperation.MOVE, "/a", error = null)).description)
    }

    @Test
    fun `the name in the title is the source's, also when Keep both saved it under another`() {
        val item = FileMutationItem(FileMutationOperation.COPY, null, "/src/a.txt", "/dst/a (2).txt", FileMutationOutcome.FAILED, "No free name available")
        assertEquals(HostToast("Copy failed: a.txt", "No free name available"), hostFailureToast(item))
    }

    @Test
    fun `a Replace that could not remove what was there says so, whichever way it was removed`() {
        val item = transferred(FileMutationOperation.MOVE, "/home/me/p/src/app.kt", "Check host permissions", FileTransferFailure.EXISTING_NOT_REMOVED)
        assertEquals(HostToast("Could not remove existing app.kt", "Check host permissions"), hostFailureToast(item))
        assertEquals("a copy says the same", "Could not remove existing app.kt", hostFailureToast(item.copy(operation = FileMutationOperation.COPY)).title)
    }

    @Test
    fun `a Replace that removed what was there and could not write the replacement says that as its title`() {
        val item = transferred(FileMutationOperation.COPY, "/home/me/p/src/app.kt", "Check host permissions", FileTransferFailure.REPLACEMENT_NOT_WRITTEN)
        assertEquals(HostToast("app.kt was removed but the replacement could not be written", "Check host permissions"), hostFailureToast(item))
        assertEquals("a move says the same", "app.kt was removed but the replacement could not be written",
            hostFailureToast(item.copy(operation = FileMutationOperation.MOVE)).title)
    }

    @Test
    fun `a move that copied but could not remove its source is still the app's own report, not the web's success`() {
        val item = transferred(
            FileMutationOperation.MOVE, "/src/a.txt", "Copied to /dst/a.txt, but the source was not removed.", outcome = FileMutationOutcome.PARTIAL,
        )
        assertEquals(HostToast("Move failed: a.txt", "Copied to /dst/a.txt, but the source was not removed."), hostFailureToast(item))
    }

    private fun done(operation: HostTransferOperation?, succeeded: Int) = HostFileActionResult(succeeded = succeeded, operation = operation)

    @Test
    fun `what went is said once, as Moved or Copied with a count, as pasteInto and transferInto say it`() {
        assertEquals(HostToast("Moved 1 item", null), hostTransferSuccessToast(done(HostTransferOperation.MOVE, 1)))
        assertEquals(HostToast("Moved 2 items", null), hostTransferSuccessToast(done(HostTransferOperation.MOVE, 2)))
        assertEquals(HostToast("Copied 1 item", null), hostTransferSuccessToast(done(HostTransferOperation.COPY, 1)))
        assertEquals(HostToast("Copied 12 items", null), hostTransferSuccessToast(done(HostTransferOperation.COPY, 12)))
    }

    @Test
    fun `nothing is said when nothing went, or when it was not a transfer`() {
        assertNull(hostTransferSuccessToast(done(HostTransferOperation.MOVE, 0)))
        assertNull(hostTransferSuccessToast(done(HostTransferOperation.COPY, 0)))
        assertNull(hostTransferSuccessToast(done(null, 3)))
    }
}
