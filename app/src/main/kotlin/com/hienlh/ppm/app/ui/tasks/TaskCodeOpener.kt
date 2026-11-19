package com.hienlh.ppm.app.ui.tasks

import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.TaskTarget
import com.hienlh.ppm.app.tasks
import com.hienlh.ppm.chat.TaskCodeOpener

/**
 * The app's answer to a PPM tool card's task button (`TaskCodeButton`): the task its code names,
 * looked up on the server, in its quick view. A bare `#12` is the chat's own project's task; with no
 * project (the Assistant) a code several projects share is not opened at random.
 */
fun taskCodeOpener(model: AppModel) = TaskCodeOpener { code, chatProject ->
    val api = model.api ?: return@TaskCodeOpener
    val found = lookupTasksByCode(api, code, chatProject).orEmpty()
    when {
        found.size > 1 -> model.toast(ErrorToastMessage("Several projects have a task $code"))
        found.isEmpty() -> model.toast(ErrorToastMessage("No task $code found"))
        else -> {
            val task = found.single()
            model.tasks.peek = TaskTarget(task.id, task.project, task.title, task.number)
        }
    }
}
