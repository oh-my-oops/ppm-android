package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.osexplorer.HostCollision
import com.hienlh.ppm.state.osexplorer.HostCollisionChoice
import com.hienlh.ppm.state.osexplorer.HostTransferPrompts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private data class CollisionPrompt(val collision: HostCollision, val reply: CompletableDeferred<Pair<HostCollisionChoice, Boolean>>)
private data class DeletePrompt(val paths: List<String>, val noTrash: Boolean, val reply: CompletableDeferred<Boolean>)

/**
 * The two questions a transfer can ask and the dialogs that ask them: a name that is taken (Skip, Keep both or Replace, for this item
 * or for the rest of the batch) and a Replace on a host with no Trash (a permanent delete to confirm). Every surface that moves
 * files holds one, so a collision looks and answers the same wherever the drop landed: the OS Explorer window's commands and the
 * project tree's drops (the web's `useDropTransfer` renders the very dialogs the window does). [Dialogs] has to be composed
 * somewhere inside the surface.
 */
internal class HostTransferPromptHost {
    private var collision by mutableStateOf<CollisionPrompt?>(null)
    private var deletion by mutableStateOf<DeletePrompt?>(null)
    private val promptLock = Mutex()
    private var attached = false

    fun attach() { attached = true }

    /** The surface is gone: what waits for an answer is skipped or refused, and nothing asks again. */
    fun detach() {
        attached = false
        collision?.reply?.complete(HostCollisionChoice.SKIP to false)
        deletion?.reply?.complete(false)
    }

    /** The prompts of one batch: "Apply to all" belongs to the batch that was asked, never to the next. */
    fun prompts(): HostTransferPrompts {
        var all: HostCollisionChoice? = null
        return HostTransferPrompts(resolveCollision = { item ->
            promptLock.withLock {
                if (!attached) return@withLock HostCollisionChoice.SKIP
                all ?: run {
                val prompt = CollisionPrompt(item, CompletableDeferred())
                collision = prompt
                try { prompt.reply.await().also { if (it.second) all = it.first }.first }
                finally { if (collision === prompt) collision = null }
                }
            }
        }, confirmPermanentOverwrite = { confirmDelete(listOf(it.destination), true) })
    }

    suspend fun confirmDelete(paths: List<String>, noTrash: Boolean): Boolean = promptLock.withLock {
        if (!attached) return@withLock false
        val prompt = DeletePrompt(paths, noTrash, CompletableDeferred())
        deletion = prompt
        try { prompt.reply.await() } finally { if (deletion === prompt) deletion = null }
    }

    @Composable
    fun Dialogs() {
        collision?.let { prompt ->
            var applyAll by remember(prompt) { mutableStateOf(false) }
            fun answer(choice: HostCollisionChoice) { prompt.reply.complete(choice to applyAll) }
            HostExplorerDialog("A file named “${prompt.collision.name}” already exists",
                "Replace moves the existing item to Trash. Keep both saves the copy with a new name.", { answer(HostCollisionChoice.SKIP) }) {
                BasicText(prompt.collision.destination, style = ppmText(12f).copy(color = LocalPpmTokens.current.text2.color))
                BasicText((if (applyAll) "☑" else "☐") + " Apply to all", Modifier.ppmClickable { applyAll = !applyAll }.padding(vertical = 8.dp),
                    style = ppmText(14f).copy(color = LocalPpmTokens.current.text.color))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HostDialogButton("Skip") { answer(HostCollisionChoice.SKIP) }
                    HostDialogButton("Keep both") { answer(HostCollisionChoice.KEEP_BOTH) }
                    HostDialogButton("Replace") { answer(HostCollisionChoice.REPLACE) }
                }
            }
        }
        deletion?.let { prompt ->
            HostExplorerDialog(if (prompt.noTrash) "This host has no Trash" else "Delete permanently?",
                "These items will be permanently deleted. This cannot be undone.", { prompt.reply.complete(false) }) {
                BasicText(prompt.paths.take(10).joinToString("\n") { it.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\') } +
                    if (prompt.paths.size > 10) "\n…and ${prompt.paths.size - 10} more" else "",
                    style = ppmText(14f).copy(color = LocalPpmTokens.current.text2.color))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HostDialogButton("Cancel") { prompt.reply.complete(false) }
                    HostDialogButton("Delete permanently", danger = true) { prompt.reply.complete(true) }
                }
            }
        }
    }
}
