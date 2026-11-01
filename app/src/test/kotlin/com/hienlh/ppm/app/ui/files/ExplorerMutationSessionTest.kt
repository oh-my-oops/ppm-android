package com.hienlh.ppm.app.ui.files

import com.hienlh.ppm.state.files.FileTreeNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class ExplorerMutationSessionTest {
    private val file = FileTreeNode("app.kt", "src/app.kt", false, false)
    private fun callbacks() = ExplorerMutationCallbacks("p", create = { _, _, _ -> error("unexpected create") },
        rename = { _, _ -> error("unexpected rename") }, delete = { error("unexpected delete") },
        cut = { error("unexpected cut") }, copy = { error("unexpected copy") }, paste = { error("unexpected paste") })

    @Test fun emptyCreateAndUnchangedRenameDoNotWrite() = runBlocking {
        val session = ExplorerMutationSession("p", this)
        try {
            session.create("", false)
            assertNull(session.submit(session.state.value.edit!!, "  ", callbacks()))
            assertNull(session.state.value.edit)
            session.rename(file)
            assertNull(session.submit(session.state.value.edit!!, " app.kt ", callbacks()))
            assertNull(session.state.value.edit)
        } finally { session.close() }
    }

    @Test fun enterAndBlurTogetherSubmitExactlyOnce() = runBlocking {
        val session = ExplorerMutationSession("p", this)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val calls = mutableListOf<Triple<String, String, Boolean>>()
        val actions = callbacks().copy(create = { parent, name, directory ->
            calls += Triple(parent, name, directory)
            started.complete(Unit)
            finish.await()
        })
        try {
            session.create("src", true)
            val edit = session.state.value.edit!!
            val first = session.submit(edit, " New Folder ", actions)!!
            assertNull(session.submit(edit, "New Folder", actions))
            started.await()
            session.cancelEdit()
            assertEquals(edit, session.state.value.edit)
            finish.complete(Unit)
            first.join()
            assertEquals(listOf(Triple("src", "New Folder", true)), calls)
            assertNull(session.state.value.edit)
            assertFalse(session.state.value.busy)
        } finally { session.close() }
    }

    @Test fun renameFailureKeepsEditorForCorrectedRetry() = runBlocking {
        val session = ExplorerMutationSession("p", this)
        val calls = mutableListOf<Pair<String, String>>()
        val actions = callbacks().copy(rename = { path, name ->
            calls += path to name
            if (name == "taken.kt") error("File exists")
        })
        try {
            session.rename(file)
            val edit = session.state.value.edit!!
            session.submit(edit, "taken.kt", actions)!!.join()
            assertEquals(edit, session.state.value.edit)
            assertEquals("File exists", session.state.value.editError)
            session.submit(edit, "next.kt", actions)!!.join()
            assertEquals(listOf("src/app.kt" to "taken.kt", "src/app.kt" to "next.kt"), calls)
            assertNull(session.state.value.edit)
        } finally { session.close() }
    }

    @Test fun replacedPromptAndOtherProjectCannotSubmitOldNames() = runBlocking {
        val session = ExplorerMutationSession("p", this)
        try {
            session.rename(file)
            val old = session.state.value.edit!!
            session.create("", false)
            assertNull(session.submit(old, "renamed.kt", callbacks()))
            assertNull(session.submit(session.state.value.edit!!, "new.txt", callbacks().copy(project = "other")))
            assertNull(session.submit(session.state.value.edit!!, "new.txt", callbacks().copy(busy = true)))
            assertFalse(session.state.value.busy)
        } finally { session.close() }
    }

    @Test fun deleteNeedsConfirmationAndRetainsDialogOnFailure() = runBlocking {
        val session = ExplorerMutationSession("p", this)
        var attempts = 0
        val actions = callbacks().copy(delete = { path ->
            assertEquals(file.path, path)
            if (++attempts == 1) error("Permission denied")
        })
        try {
            session.askDelete(file)
            assertEquals(0, attempts)
            session.cancelDelete()
            assertNull(session.confirmDelete(actions))
            session.askDelete(file)
            session.confirmDelete(actions)!!.join()
            assertEquals(file, session.state.value.deleting)
            assertEquals("Permission denied", session.state.value.deleteError)
            session.confirmDelete(actions)!!.join()
            assertNull(session.state.value.deleting)
            assertEquals(2, attempts)
        } finally { session.close() }
    }

    @Test fun pasteRequiresClipboardAndRetainsPartialFailureMessage() = runBlocking {
        val session = ExplorerMutationSession("p", this)
        val actions = callbacks().copy(canPaste = true, paste = { directory ->
            assertEquals("dest", directory)
            error("1 completed; 1 incomplete. Source could not be removed")
        })
        try {
            assertNull(session.paste("dest", actions.copy(canPaste = false)))
            session.paste("dest", actions)!!.join()
            assertEquals("1 completed; 1 incomplete. Source could not be removed", session.state.value.error)
            assertFalse(session.state.value.pasting)
            assertFalse(session.state.value.busy)
        } finally { session.close() }
    }

    @Test fun clipboardActionsReceiveFullRelativeSelection() = runBlocking {
        val session = ExplorerMutationSession("p", this)
        val calls = mutableListOf<Pair<Boolean, List<String>>>()
        val actions = callbacks().copy(cut = { calls += true to it }, copy = { calls += false to it })
        try {
            session.clipboard(listOf("src", "README.md"), true, actions)
            session.clipboard(listOf("src"), false, actions)
            session.clipboard(emptyList(), false, actions)
            assertEquals(listOf(true to listOf("src", "README.md"), false to listOf("src")), calls)
        } finally { session.close() }
    }

    @Test fun closeRejectsLateNonCancellableCompletionAndFutureCommands() = runBlocking {
        val session = ExplorerMutationSession("p", this)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val actions = callbacks().copy(rename = { _, _ ->
            withContext(NonCancellable) { started.complete(Unit); finish.await() }
        })
        session.rename(file)
        val job = session.submit(session.state.value.edit!!, "next.kt", actions)!!
        started.await()
        session.close()
        finish.complete(Unit)
        job.join()
        session.create("", false)
        assertEquals(ExplorerMutationState(), session.state.value)
    }
}
