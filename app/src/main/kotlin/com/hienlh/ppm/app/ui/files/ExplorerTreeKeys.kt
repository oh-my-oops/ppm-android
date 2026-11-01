package com.hienlh.ppm.app.ui.files

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import com.hienlh.ppm.app.TextInputFocus
import com.hienlh.ppm.state.files.ProjectFileTree

/** Bubble after fields; never turn typing or IME composition into filesystem commands. */
internal fun Modifier.explorerMutationKeys(tree: ProjectFileTree, session: ExplorerMutationSession, callbacks: ExplorerMutationCallbacks?) = onKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown || TextInputFocus.focused || callbacks == null ||
        callbacks.busy || session.state.value.busy || session.state.value.edit != null) return@onKeyEvent false
    val state = tree.state.value
    if (callbacks.project != state.project) return@onKeyEvent false
    if (event.isCtrlPressed || event.isMetaPressed) {
        when {
            event.key == Key.V && callbacks.canPaste -> { session.paste("", callbacks); true }
            (event.key == Key.C || event.key == Key.X) && state.selectedFiles.isNotEmpty() -> {
                session.clipboard(state.selectedFiles, event.key == Key.X, callbacks)
                true
            }
            else -> false
        }
    } else {
        val node = state.focusedPath?.let(state::node) ?: return@onKeyEvent false
        when (event.key) {
            Key.F2 -> { TextInputFocus.asked(); session.rename(node); true }
            Key.Delete -> { session.askDelete(node); true }
            else -> false
        }
    }
}
