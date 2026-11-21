package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.TurnRevertFile
import com.hienlh.ppm.protocol.TurnRevertResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Rewind code" on a prompt (`rewind-code-button.tsx`): the files put back as they were before
 * that message, undoing what its turn and every later one wrote — `revert-turn` with all their
 * calls ([SessionTurns.rewindFrom]), previewed, written only once confirmed, and undoable. The chat
 * itself is left alone. [settle] runs after anything was written (the session's list asked again).
 */
class RewindCode(
    private val backend: SessionChangesBackend,
    private val calls: List<String>,
    private val settle: () -> Unit = {},
) {
    sealed interface Stage {
        data object Loading : Stage
        data class Preview(val preview: TurnRevertResult, val moved: Boolean = false, val busy: Boolean = false) : Stage {
            /** The files the rewind writes. */
            val files: List<TurnRevertFile> get() = preview.files.filter { it.action != TurnRevertFile.Action.NONE }

            /** Nothing written since is left to put back: nothing to confirm. */
            val nothing: Boolean get() = files.isEmpty() && preview.files.all { it.skipped.isEmpty() && it.error == null }
        }
        data class Done(val text: String, val undoId: String? = null, val busy: Boolean = false) : Stage
    }

    private val _stage = MutableStateFlow<Stage?>(null)

    /** Null while the dialog is closed. */
    val stage: StateFlow<Stage?> = _stage.asStateFlow()

    private fun fail(e: Exception) {
        _stage.value = Stage.Done(e.message?.takeIf { it.isNotEmpty() } ?: "The files could not be put back.")
    }

    /** Opens the dialog on what rewinding would put back. */
    suspend fun start() {
        _stage.value = Stage.Loading
        try {
            _stage.value = Stage.Preview(backend.revertTurn(calls, null))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e)
        }
    }

    /**
     * Writes the rewind, each file at the version shown; a file that moved since shows the preview
     * again. True when everything was written, so "Rewind and fork" may fork.
     */
    suspend fun confirm(): Boolean {
        val shown = _stage.value as? Stage.Preview ?: return false
        if (shown.busy) return false
        _stage.value = shown.copy(busy = true)
        try {
            val result = backend.revertTurn(calls, shown.preview.files.map { it.path to it.version })
            if (result.stale) {
                _stage.value = Stage.Preview(TurnRevertResult(result.files), moved = true)
                return false
            }
            val failed = result.files.filter { it.error != null }
            val written = result.files.count { it.action != TurnRevertFile.Action.NONE && it.error == null }
            _stage.value = Stage.Done(
                when {
                    failed.isNotEmpty() -> "${plural(failed.size, "file")} could not be written: ${failed[0].error}"
                    written > 0 -> "Rewound ${plural(written, "file")} to before this message."
                    else -> "Nothing to put back."
                },
                result.undoId,
            )
            return failed.isEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e)
            return false
        } finally {
            settle()
        }
    }

    /** Puts the files back as they were before the rewind. */
    suspend fun undo() {
        val done = _stage.value as? Stage.Done ?: return
        val undoId = done.undoId ?: return
        _stage.value = done.copy(busy = true)
        try {
            val result = backend.undo(undoId)
            _stage.value = Stage.Done(
                if (result.stale) "Nothing was put back: a file changed after the rewind." else "Undone: the files are as they were before the rewind.",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e)
        } finally {
            settle()
        }
    }

    fun close() {
        _stage.value = null
    }

    companion object {
        fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

        /** The dialog's line under its title. */
        fun intro(turns: Int) =
            "Puts the files back as they were before this message, undoing what ${plural(turns, "turn")} wrote. The chat itself stays as it is."
    }
}
