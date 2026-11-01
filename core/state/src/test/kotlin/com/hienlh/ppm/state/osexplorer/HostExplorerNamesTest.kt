package com.hienlh.ppm.state.osexplorer

import com.hienlh.ppm.state.files.FileMutationItem
import com.hienlh.ppm.state.files.FileMutationOperation
import com.hienlh.ppm.state.files.FileMutationOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/** `commitInline` (use-explorer-actions.ts) and the toasts of `createEntry` and `renameEntry` (explorer-actions-mutate.ts). */
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
}
