package com.hienlh.ppm.state.tasks

import com.hienlh.ppm.protocol.TaskDto

/*
 * Which chat buttons a task view shows (`task-chat-buttons.ts`). "Open chat" opens the chat the
 * task was handed to; "Ask manager" opens the project's manager chat about the task, whichever
 * chat the task was handed to.
 *
 * A task that was not handed off is worked on by the manager chat itself, so "Open chat" would open
 * the very chat "Ask manager" opens: such a task shows "Ask manager" alone. A project with no
 * manager chat shows no "Ask manager" (and nothing creates one from here).
 */

/** A chat to open: its session and provider. */
data class TaskChatRef(val sessionId: String, val providerId: String?)

/** A project's manager chat as the manager list names it. */
data class TaskManagerLike(val projectName: String, val sessionId: String, val providerId: String?)

/**
 * The project's manager chat: from the manager chat list, else from the task's own working session
 * (`kind: "manager"`, the server's answer when no chat was handed the task) while the list loads.
 */
fun taskManagerChat(task: TaskDto, managers: List<TaskManagerLike>?): TaskChatRef? {
    managers?.firstOrNull { it.projectName == task.project }?.let { return TaskChatRef(it.sessionId, it.providerId) }
    val w = task.workingSession
    val s = w.session
    return if (w.kind == "manager" && s != null) TaskChatRef(s.sessionId, s.providerId) else null
}

data class TaskChatButtons(
    /** The chat the task was handed to, when it was handed to a chat other than the manager chat. */
    val openChat: TaskChatRef?,
    /** The project's manager chat, when the project has one. */
    val askManager: TaskChatRef?,
) {
    /** Both are shown: in a narrow footer they drop their icons and keep their whole words. */
    val crowded: Boolean get() = openChat != null && askManager != null
}

fun taskChatButtons(task: TaskDto, managers: List<TaskManagerLike>?): TaskChatButtons {
    val askManager = taskManagerChat(task, managers)
    val w = task.workingSession
    val s = w.session
    val handed = w.kind == "session" && s != null && s.sessionId != askManager?.sessionId
    return TaskChatButtons(if (handed) TaskChatRef(s!!.sessionId, s.providerId) else null, askManager)
}

/**
 * Whether the working-session block's chat is the manager chat: a task handed to the manager
 * chat by id is worked on by the manager all the same.
 */
fun workingIsManager(task: TaskDto, managerSessionId: String?): Boolean {
    val s = task.workingSession.session
    return task.workingSession.kind == "manager" || (managerSessionId != null && s?.sessionId == managerSessionId)
}
