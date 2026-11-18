package com.hienlh.ppm.app.ui.tasks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.tasks
import com.hienlh.ppm.app.ui.PaletteGroup
import com.hienlh.ppm.app.ui.PaletteItem
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.protocol.TaskDto
import com.hienlh.ppm.state.tasks.parseTaskCodeQuery
import com.hienlh.ppm.state.tasks.taskCode
import kotlinx.coroutines.delay

/** How many tasks a bare `#82` may list when several projects have one. */
private const val MAX_TASK_CODE_RESULTS = 5

/** A task as a palette row: "#82 Fix login", hinted with its project and status (`taskCodeItem`). */
internal fun taskCodeItem(model: AppModel, task: TaskDto, activeProject: String?, onDismiss: () -> Unit): PaletteItem {
    val label = "${taskCode(task, activeProject)} ${task.title}"
    return PaletteItem(
        group = PaletteGroup.ACTION,
        label = label,
        keywords = label,
        hint = "Task · ${task.project} · ${task.statusName}",
        icon = ProductIcon.ListTodo,
        action = {
            onDismiss()
            model.tasks.openTask(task.id, task.project, task.title, task.number, keep = true)
        },
    )
}

/** The rows a task code in [query] finds, and whether the lookup for what is typed now is still out. */
internal class TaskCodeItems(val items: List<PaletteItem>, val pending: Boolean)

/**
 * The palette's task lookup (`useTaskCodeCommands`): typing a code the chats use (`#82`, `ppm/82`,
 * any case) puts that task first, so Enter opens it. While the lookup for what is typed now is
 * out, [TaskCodeItems.pending] holds an Enter back, or `#82` typed and entered at once would fall
 * through to "Ask AI".
 */
@Composable
internal fun rememberTaskCodeItems(model: AppModel, query: String, activeProject: String?, onDismiss: () -> Unit): TaskCodeItems {
    val isCode = parseTaskCodeQuery(query) != null
    var found by remember { mutableStateOf<Pair<String, List<TaskDto>>?>(null) }
    val api = model.api
    LaunchedEffect(isCode, query, activeProject, api) {
        if (!isCode || api == null) return@LaunchedEffect
        delay(120)
        found = query to lookupTasksByCode(api, query, activeProject, anywhere = true).orEmpty()
    }
    if (!isCode) return TaskCodeItems(emptyList(), false)
    val answer = found
    // Only the answer to what is typed now: a stale one would open the wrong task on Enter.
    if (answer?.first != query) return TaskCodeItems(emptyList(), api != null)
    return TaskCodeItems(answer.second.take(MAX_TASK_CODE_RESULTS).map { taskCodeItem(model, it, activeProject, onDismiss) }, false)
}
