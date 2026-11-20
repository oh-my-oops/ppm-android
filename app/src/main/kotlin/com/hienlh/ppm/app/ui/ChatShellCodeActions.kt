package com.hienlh.ppm.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.chat.ShellCodeActions
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.ShellRunResult
import com.hienlh.ppm.client.runShellCommand
import com.hienlh.ppm.protocol.isAssistantProject

/**
 * What a chat's shell code blocks may do (`canRun`): nothing in minimal mode, without a project,
 * or in the PPM Assistant's chat (`assistantContent`); otherwise Run and Run in terminal.
 */
internal fun shellCodeActions(
    project: String?,
    minimalMode: Boolean,
    runInTerminal: (String) -> Unit,
    run: suspend (String) -> ShellRunResult,
): ShellCodeActions? =
    if (minimalMode || project.isNullOrEmpty() || isAssistantProject(project)) null else ShellCodeActions(runInTerminal, run)

/** [shellCodeActions] for a chat of [project], as ChatPane and the phone's chat provide it. */
@Composable
internal fun rememberShellCodeActions(model: AppModel, project: String): ShellCodeActions? {
    val minimal = model.info?.minimalMode == true
    return remember(model, project, minimal) {
        shellCodeActions(
            project,
            minimal,
            runInTerminal = { command -> model.runInTerminal(command, project) },
            run = { command -> (model.api ?: throw PpmError.Unreachable("Not connected.")).runShellCommand(project, command) },
        )
    }
}
