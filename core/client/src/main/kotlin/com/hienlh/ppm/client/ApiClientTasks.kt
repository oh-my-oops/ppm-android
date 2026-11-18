package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.NotifyManagerResult
import com.hienlh.ppm.protocol.TaskDetail
import com.hienlh.ppm.protocol.TaskHistoryItem
import com.hienlh.ppm.protocol.TaskLink
import com.hienlh.ppm.protocol.TaskListResponse
import com.hienlh.ppm.protocol.TaskLiveParts
import com.hienlh.ppm.protocol.TaskLinkState
import com.hienlh.ppm.protocol.TaskProjectSettings
import com.hienlh.ppm.protocol.TaskProjectSummary
import com.hienlh.ppm.protocol.TaskWorkingSession
import com.hienlh.ppm.protocol.TaskWriteResult
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * PPM Tasks over HTTP (`/api/tasks`, routes/ppm-tasks*.ts and task-reminders.ts) as the web's
 * `ppm-tasks-client.ts` calls it. A write names the revision it read; a stale one answers 409 with
 * code `stale`, which [ApiClient.call] throws as a [PpmError.Server] — see [isStaleTask].
 */

/** A list query (`TaskListQuery`): a project name or `all`, and the filters the server applies. */
data class TaskListQuery(
    val project: String,
    val groups: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val person: String? = null,
    val tag: String? = null,
    val q: String? = null,
    val closed: Boolean = false,
    val snoozed: Boolean = false,
    val session: String? = null,
) {
    /** The query string `taskListPath` builds, in its order. */
    fun params(): List<Pair<String, String>> = buildList {
        add("project" to project)
        if (groups.isNotEmpty()) add("group" to groups.joinToString(","))
        if (types.isNotEmpty()) add("type" to types.joinToString(","))
        person?.takeIf { it.isNotEmpty() }?.let { add("person" to it) }
        tag?.takeIf { it.isNotEmpty() }?.let { add("tag" to it) }
        q?.trim()?.takeIf { it.isNotEmpty() }?.let { add("q" to it) }
        if (closed) add("closed" to "1")
        if (snoozed) add("snoozed" to "1")
        session?.takeIf { it.isNotEmpty() }?.let { add("session" to it) }
    }
}

/** The write was refused because the task changed since it was read: re-read, never retry blindly. */
val Throwable.isStaleTask: Boolean get() = this is PpmError.Server && code == "stale"

/** A decision with no working chat and no manager chat: offer Start chat. */
val Throwable.isNoTaskSession: Boolean get() = this is PpmError.Server && code == "no_session"

/** The project has tasks switched off. */
val Throwable.isTasksOff: Boolean get() = this is PpmError.Server && code == "tasks_off"

private const val TASKS = "/api/tasks"

private fun taskPath(id: String) = "$TASKS/${ApiClient.escape(id)}"

private fun write(json: JsonElement): TaskWriteResult = TaskWriteResult.from(json) ?: throw PpmError.BadResponse("Unexpected task.")

/** [body] with `revision` added, as every task write sends it. */
private fun withRevision(body: JsonObject, revision: Int): JsonObject = JsonObject(body + ("revision" to kotlinx.serialization.json.JsonPrimitive(revision)))

suspend fun ApiClient.tasksList(query: TaskListQuery): TaskListResponse = TaskListResponse.from(call("GET", TASKS, query.params()))

suspend fun ApiClient.taskDetail(id: String): TaskDetail = TaskDetail.from(call("GET", taskPath(id))) ?: throw PpmError.BadResponse("Unexpected task.")

/** `POST /api/tasks`: [fields] is the patch (`title`, `type`, `urgency`…) and may carry `decision`. */
suspend fun ApiClient.taskCreate(project: String, fields: JsonObject): TaskWriteResult =
    write(call("POST", TASKS, body = JsonObject(mapOf("project" to kotlinx.serialization.json.JsonPrimitive(project)) + fields)))

/** `PATCH /api/tasks/:id`: [patch] is a `TaskPatch` (null clears a field; `fields` merges, `links` replaces). */
suspend fun ApiClient.taskPatch(id: String, revision: Int, patch: JsonObject): TaskWriteResult =
    write(call("PATCH", taskPath(id), body = withRevision(patch, revision)))

suspend fun ApiClient.taskClose(id: String, revision: Int, closedAs: String? = null, statusId: String? = null): TaskWriteResult =
    write(
        call(
            "POST", "${taskPath(id)}/close",
            body = buildJsonObject {
                closedAs?.let { put("closedAs", it) }
                statusId?.let { put("statusId", it) }
                put("revision", revision)
            },
        ),
    )

suspend fun ApiClient.taskReopen(id: String, revision: Int, statusId: String? = null): TaskWriteResult =
    write(call("POST", "${taskPath(id)}/reopen", body = buildJsonObject { statusId?.let { put("statusId", it) }; put("revision", revision) }))

/** Puts the task before [beforeId] or after [afterId] inside its cell. */
suspend fun ApiClient.taskReorder(id: String, revision: Int, beforeId: String? = null, afterId: String? = null): TaskWriteResult =
    write(
        call(
            "POST", "${taskPath(id)}/reorder",
            body = buildJsonObject {
                beforeId?.let { put("beforeId", it) }
                afterId?.let { put("afterId", it) }
                put("revision", revision)
            },
        ),
    )

suspend fun ApiClient.taskDecide(id: String, revision: Int, optionId: String): TaskWriteResult =
    write(call("POST", "${taskPath(id)}/decide", body = buildJsonObject { put("optionId", optionId); put("revision", revision) }))

suspend fun ApiClient.taskStartChat(id: String, revision: Int): TaskWriteResult =
    write(call("POST", "${taskPath(id)}/start-chat", body = buildJsonObject { put("revision", revision) }))

suspend fun ApiClient.tasksNotifyManager(project: String): NotifyManagerResult {
    val json = call("POST", "$TASKS/notify-manager", body = buildJsonObject { put("project", project) })
    return NotifyManagerResult(json["sent"].bool ?: false, json["sessionId"].string, json["reason"].string, json["error"].string)
}

suspend fun ApiClient.taskAddLink(id: String, revision: Int, kind: String, ref: String, label: String? = null): TaskWriteResult =
    write(
        call(
            "POST", "${taskPath(id)}/links",
            body = buildJsonObject {
                put("kind", kind)
                put("ref", ref)
                label?.let { put("label", it) }
                put("revision", revision)
            },
        ),
    )

suspend fun ApiClient.taskRemoveLink(id: String, revision: Int, link: TaskLink): TaskWriteResult =
    write(call("DELETE", "${taskPath(id)}/links/${ApiClient.escape(link.linkId)}", listOf("revision" to revision.toString())))

/** [filter] `all`, `ai` or `status`. */
suspend fun ApiClient.taskHistory(id: String, filter: String = "all"): List<TaskHistoryItem> =
    call("GET", "${taskPath(id)}/history", listOf("filter" to filter)).array.orEmpty().mapNotNull(TaskHistoryItem::from)

suspend fun ApiClient.taskUndo(historyId: Long): TaskWriteResult = write(call("POST", "$TASKS/history/$historyId/undo"))

/** Live link states and working chats of [ids]; tasks not found or switched off are left out. */
suspend fun ApiClient.taskLinkStates(ids: List<String>): Map<String, TaskLiveParts> {
    // One comma-separated parameter; the server decodes it before splitting.
    val json = call("GET", "$TASKS/link-states", listOf("ids" to ids.joinToString(",")))
    return json["tasks"].obj.orEmpty().mapValues { (_, v) ->
        TaskLiveParts(v["linkStates"].array.orEmpty().mapNotNull(TaskLinkState::from), TaskWorkingSession.from(v["workingSession"]))
    }
}

suspend fun ApiClient.taskProjects(): List<TaskProjectSummary> = call("GET", "$TASKS/projects").array.orEmpty().mapNotNull(TaskProjectSummary::from)

suspend fun ApiClient.taskProjectSettings(project: String): TaskProjectSettings =
    TaskProjectSettings.from(call("GET", "$TASKS/projects/${ApiClient.escape(project)}/settings")) ?: throw PpmError.BadResponse("Unexpected task settings.")

/** `PUT …/settings` with a `TaskProjectSettingsUpdate`; applied all or nothing. */
suspend fun ApiClient.saveTaskProjectSettings(project: String, update: JsonObject): TaskProjectSettings =
    TaskProjectSettings.from(call("PUT", "$TASKS/projects/${ApiClient.escape(project)}/settings", body = update))
        ?: throw PpmError.BadResponse("Unexpected task settings.")

/** A one-shot reminder to the manager chat at [at] (`2026-11-17T18:30` on [timezone]'s clock); answers the schedule overview. */
suspend fun ApiClient.taskAddReminder(id: String, at: String, timezone: String, note: String? = null): JsonElement =
    call(
        "POST", "${taskPath(id)}/reminders",
        body = buildJsonObject {
            put("at", at)
            put("timezone", timezone)
            note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
        },
    )

/** The reminders a task owns (`GET /api/schedules?task=<id>`), as the schedules list answers them. */
suspend fun ApiClient.taskReminders(id: String): JsonElement = call("GET", "/api/schedules", listOf("task" to id))
