package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.ChromeCheckbox
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.state.osexplorer.HostCollision
import com.hienlh.ppm.state.osexplorer.HostCollisionChoice
import com.hienlh.ppm.state.osexplorer.HostTransferPrompts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private data class CollisionPrompt(val collision: HostCollision, val reply: CompletableDeferred<Pair<HostCollisionChoice, Boolean>>)
private data class DeletePrompt(val paths: List<String>, val reply: CompletableDeferred<Boolean>)
private data class OverwritePrompt(val name: String, val reply: CompletableDeferred<Boolean>)

/**
 * The questions a transfer or delete can ask and the three dialogs (`explorer-dialogs.tsx`) that ask them: a name that is taken
 * (Skip, Keep both or Replace, for this item or for the rest of the batch), a permanent delete to confirm, and a Replace on a host
 * with no Trash (a separate permanent-overwrite dialog). Every surface that moves files holds one, so a collision looks and answers
 * the same wherever the drop landed: the OS Explorer window's commands and the project tree's drops (the web's `useDropTransfer`
 * renders the very dialogs the window does). [Dialogs] has to be composed somewhere inside the surface.
 */
internal class HostTransferPromptHost {
    private var collision by mutableStateOf<CollisionPrompt?>(null)
    private var deletion by mutableStateOf<DeletePrompt?>(null)
    private var overwrite by mutableStateOf<OverwritePrompt?>(null)
    private var waiting by mutableIntStateOf(0)
    private val promptLock = Mutex()
    private var attached = false

    fun attach() { attached = true }

    /** The surface is gone: what waits for an answer is skipped or refused, and nothing asks again. */
    fun detach() {
        attached = false
        collision?.reply?.complete(HostCollisionChoice.SKIP to false)
        deletion?.reply?.complete(false)
        overwrite?.reply?.complete(false)
    }

    /** The prompts of one batch: "Apply to all" belongs to the batch that was asked, never to the next. */
    fun prompts(): HostTransferPrompts {
        var all: HostCollisionChoice? = null
        return HostTransferPrompts(resolveCollision = { item ->
            all ?: run {
                var queued = true
                waiting++
                try {
                    promptLock.withLock {
                        queued = false
                        waiting--
                        if (!attached) return@withLock HostCollisionChoice.SKIP
                        all ?: run {
                            val prompt = CollisionPrompt(item, CompletableDeferred())
                            collision = prompt
                            try { prompt.reply.await().also { if (it.second) all = it.first }.first }
                            finally { if (collision === prompt) collision = null }
                        }
                    }
                } finally {
                    if (queued) waiting--
                }
            }
        }, confirmPermanentOverwrite = { confirmPermanentOverwrite(it.name) })
    }

    suspend fun confirmDelete(paths: List<String>): Boolean = promptLock.withLock {
        if (!attached) return@withLock false
        val prompt = DeletePrompt(paths, CompletableDeferred())
        deletion = prompt
        try { prompt.reply.await() } finally { if (deletion === prompt) deletion = null }
    }

    suspend fun confirmPermanentOverwrite(name: String): Boolean = promptLock.withLock {
        if (!attached) return@withLock false
        val prompt = OverwritePrompt(name, CompletableDeferred())
        overwrite = prompt
        try { prompt.reply.await() } finally { if (overwrite === prompt) overwrite = null }
    }

    @Composable
    fun Dialogs() {
        val tokens = LocalPpmTokens.current
        collision?.let { prompt ->
            var applyAll by remember(prompt) { mutableStateOf(false) }
            fun answer(choice: HostCollisionChoice) { prompt.reply.complete(choice to applyAll) }
            val description = if (waiting > 0) "${prompt.collision.name} — and $waiting more" else prompt.collision.name
            HostExplorerDialog("An item with that name already exists", description, { answer(HostCollisionChoice.SKIP) }) {
                BasicText(
                    "Replacing moves the existing item to the Trash first, so it stays recoverable. Keep both adds a numbered suffix.",
                    Modifier.padding(vertical = 4.dp),
                    style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color),
                )
                val remaining = prompt.collision.remaining
                if (remaining > 0) {
                    val hover = rememberHover(instant = true)
                    Row(
                        Modifier
                            .heightIn(min = 44.dp)
                            .hoverable(hover.source)
                            .toggleable(applyAll, hover.source, indication = null, role = Role.Checkbox) { applyAll = it },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ChromeCheckbox(applyAll, Modifier.size(16.dp), hovered = hover.lit > 0f)
                        BasicText(
                            "Apply to all $remaining remaining item${if (remaining == 1) "" else "s"}",
                            style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HostDialogButton("Skip") { answer(HostCollisionChoice.SKIP) }
                    HostDialogButton("Keep both") { answer(HostCollisionChoice.KEEP_BOTH) }
                    HostDialogButton("Replace", danger = true) { answer(HostCollisionChoice.REPLACE) }
                }
            }
        }
        deletion?.let { prompt ->
            HostExplorerDialog(
                "Delete ${prompt.paths.size} item${if (prompt.paths.size == 1) "" else "s"} permanently?",
                "This cannot be undone — the items do not go to the Trash.",
                { prompt.reply.complete(false) },
            ) {
                Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (path in prompt.paths.take(10)) {
                        BasicText(
                            path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\'),
                            style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (prompt.paths.size > 10) {
                        BasicText(
                            "and ${prompt.paths.size - 10} more…",
                            style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text3.color),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HostDialogButton("Cancel") { prompt.reply.complete(false) }
                    HostDialogButton("Delete permanently", danger = true) { prompt.reply.complete(true) }
                }
            }
        }
        overwrite?.let { prompt ->
            HostExplorerDialog("This host has no Trash", prompt.name, { prompt.reply.complete(false) }) {
                BasicText(
                    "Replacing this item cannot go through the Trash on this host. Deleting it permanently cannot be undone.",
                    Modifier.padding(vertical = 4.dp),
                    style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HostDialogButton("Cancel") { prompt.reply.complete(false) }
                    HostDialogButton("Delete permanently", danger = true) { prompt.reply.complete(true) }
                }
            }
        }
    }
}
