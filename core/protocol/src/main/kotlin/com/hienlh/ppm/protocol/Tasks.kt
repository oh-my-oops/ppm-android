package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/*
 * PPM Tasks as `/api/tasks` answers them (`shared/ppm-tasks.ts`, `shared/ppm-tasks-api.ts`), read
 * field by field so a field a newer server adds never fails a decode. Projects are named by their
 * PPM name everywhere; ids are the server's UUIDs. Times are ms since the epoch, days `YYYY-MM-DD`.
 */

object TaskConst {
    /** The reserved member id meaning the user ("You"). */
    const val ME = "me"

    /** Most urgent first; the order is the sort order. */
    val URGENCIES = listOf("now", "today", "week", "later")

    /** Most important first; the order is the sort order. */
    val IMPORTANCES = listOf("must", "should", "nice")
    val BASE_GROUPS = listOf("not_started", "active", "waiting", "closed")
    val TYPES = listOf("task", "decision", "reply")
    val LINK_KINDS = listOf("jira", "pr", "session", "url")
    val FIELD_TYPES = listOf("text", "number", "select", "date", "link")

    const val TITLE_MAX = 200
    const val WHY_MAX = 2_000
    const val NOTES_MAX = 16_000
    const val RESULT_MAX = 4_000
    const val TAGS_MAX = 20
    const val TAG_MAX = 64
    const val DECISION_OPTIONS_MAX = 10
    const val DECISION_LABEL_MAX = 300
    const val CHECK_INTERVAL_DEFAULT_MIN = 30
    const val CHECK_INTERVAL_MIN_MIN = 5
    const val CHECK_INTERVAL_MAX_MIN = 240
    const val HISTORY_DETAIL_DAYS = 90
    val AI_ACTOR_KINDS = listOf("manager", "assistant", "chat")

    /** `/ws/global` pushes that concern tasks. */
    val EVENT_TYPES = listOf("tasks:changed", "tasks:project_changed", "tasks:link_states")
}

data class TaskLink(val kind: String, val ref: String, val label: String?) {
    /** How a DELETE names it: `<kind>:<ref>` (`taskLinkId`). */
    val linkId: String get() = "$kind:$ref"

    companion object {
        fun from(json: JsonElement?): TaskLink? {
            val kind = json["kind"].string ?: return null
            val ref = json["ref"].string ?: return null
            return TaskLink(kind, ref, json["label"].string)
        }
    }
}

/** A link's cached live state; [state] is the checker's raw object (Jira status, PR state, chat state). */
data class TaskLinkState(
    val kind: String,
    val ref: String,
    val state: JsonElement?,
    val error: String?,
    val checkedAt: Long?,
    val changedAt: Long?,
) {
    companion object {
        fun from(json: JsonElement?): TaskLinkState? {
            val kind = json["kind"].string ?: return null
            val ref = json["ref"].string ?: return null
            return TaskLinkState(kind, ref, json["state"]?.takeIf { it !is JsonNull }, json["error"].string, json["checkedAt"].long, json["changedAt"].long)
        }
    }
}

data class DecisionOption(val id: String, val label: String)

data class DecisionChosen(val optionId: String, val at: Long, val by: String)

data class TaskDecision(val question: String?, val options: List<DecisionOption>, val chosen: DecisionChosen?) {
    companion object {
        fun from(json: JsonElement?): TaskDecision? {
            if (json.obj == null) return null
            val chosen = json["chosen"]?.let { c ->
                c["optionId"].string?.let { DecisionChosen(it, c["at"].long ?: 0, c["by"].string ?: "") }
            }
            return TaskDecision(
                question = json["question"].string,
                options = json["options"].array.orEmpty().mapNotNull { o ->
                    val id = o["id"].string ?: return@mapNotNull null
                    DecisionOption(id, o["label"].string ?: "")
                },
                chosen = chosen,
            )
        }
    }
}

data class TaskResult(val text: String, val at: Long, val by: String)

/** The last check of a handed-off task's working chat. */
data class TaskCheckSummary(val state: String, val since: Long, val lastProgressAt: Long?) {
    companion object {
        fun from(json: JsonElement?): TaskCheckSummary? {
            val state = json["state"].string ?: return null
            return TaskCheckSummary(state, json["since"].long ?: 0, json["lastProgressAt"].long)
        }
    }
}

/** A chat's live state as a task shows it (`TaskSessionState`). */
data class TaskSessionState(
    val sessionId: String,
    /** `running`, `waiting_card`, `idle`, `gone` or `unknown`. */
    val state: String,
    val title: String?,
    val providerId: String?,
    val runningSince: Long?,
    val check: TaskCheckSummary?,
) {
    companion object {
        fun from(json: JsonElement?): TaskSessionState? {
            val id = json["sessionId"].string ?: return null
            return TaskSessionState(
                id, json["state"].string ?: "unknown", json["title"].string, json["providerId"].string,
                json["runningSince"].long, TaskCheckSummary.from(json["check"]),
            )
        }
    }
}

/** Who works on a task: [kind] `session`, `manager` or `start_chat` (no [session] then). */
data class TaskWorkingSession(val kind: String, val session: TaskSessionState?) {
    companion object {
        val START_CHAT = TaskWorkingSession("start_chat", null)

        fun from(json: JsonElement?): TaskWorkingSession {
            val kind = json["kind"].string ?: return START_CHAT
            val session = TaskSessionState.from(json["session"])
            return if (kind == "start_chat" || session == null) START_CHAT else TaskWorkingSession(kind, session)
        }
    }
}

/** A task as every list and detail receives it (`TaskDto`). */
data class TaskDto(
    val id: String,
    val projectKey: String,
    val number: Int,
    val title: String,
    val type: String,
    val statusId: String,
    val urgency: String,
    val importance: String,
    val rank: Double,
    val why: String?,
    val nextStep: String?,
    val notes: String?,
    val dueDate: String?,
    val tags: List<String>,
    val assigneeMemberId: String?,
    val waitingOnMemberId: String?,
    val waitingSince: Long?,
    val parentId: String?,
    val workingSessionId: String?,
    val decision: TaskDecision?,
    /** Custom field values: a string, a number or null, kept as the server sent them. */
    val fields: Map<String, JsonElement>,
    val links: List<TaskLink>,
    val result: TaskResult?,
    val handedOffAt: Long?,
    val snoozedUntil: String?,
    val snoozeUntilChange: Boolean,
    val snoozedAt: Long?,
    val resurfacedAt: Long?,
    val revision: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val closedAt: Long?,
    val deletedAt: Long?,
    val baseGroup: String,
    val closedAs: String?,
    val effectiveUrgency: String,
    val urgencyRaised: Boolean,
    val waitingStartedAt: Long?,
    val snoozed: Boolean,
    val resurfacedToday: Boolean,
    val project: String,
    val ref: String,
    val statusName: String,
    val waitingOnName: String?,
    val workingSession: TaskWorkingSession,
    val check: TaskCheckSummary?,
) {
    /** A custom field as text, the way JavaScript's `String(v)` reads it; null when unset. */
    fun fieldText(key: String): String? = fieldValueText(fields[key])

    companion object {
        fun from(json: JsonElement?): TaskDto? {
            val id = json["id"].string ?: return null
            val urgency = json["urgency"].string ?: "later"
            return TaskDto(
                id = id,
                projectKey = json["projectKey"].string ?: "",
                number = json["number"].int ?: 0,
                title = json["title"].string ?: "",
                type = json["type"].string ?: "task",
                statusId = json["statusId"].string ?: "",
                urgency = urgency,
                importance = json["importance"].string ?: "should",
                rank = json["rank"].double ?: 0.0,
                why = json["why"].string,
                nextStep = json["nextStep"].string,
                notes = json["notes"].string,
                dueDate = json["dueDate"].string,
                tags = json["tags"].array.orEmpty().mapNotNull { it.string },
                assigneeMemberId = json["assigneeMemberId"].string,
                waitingOnMemberId = json["waitingOnMemberId"].string,
                waitingSince = json["waitingSince"].long,
                parentId = json["parentId"].string,
                workingSessionId = json["workingSessionId"].string,
                decision = TaskDecision.from(json["decision"]),
                fields = json["fields"].obj.orEmpty().filterValues { it !is JsonNull },
                links = json["links"].array.orEmpty().mapNotNull(TaskLink::from),
                result = json["result"].let { r -> r["text"].string?.let { TaskResult(it, r["at"].long ?: 0, r["by"].string ?: "") } },
                handedOffAt = json["handedOffAt"].long,
                snoozedUntil = json["snoozedUntil"].string,
                snoozeUntilChange = json["snoozeUntilChange"].bool ?: false,
                snoozedAt = json["snoozedAt"].long,
                resurfacedAt = json["resurfacedAt"].long,
                revision = json["revision"].int ?: 0,
                createdAt = json["createdAt"].long ?: 0,
                updatedAt = json["updatedAt"].long ?: 0,
                closedAt = json["closedAt"].long,
                deletedAt = json["deletedAt"].long,
                baseGroup = json["baseGroup"].string ?: "not_started",
                closedAs = json["closedAs"].string,
                effectiveUrgency = json["effectiveUrgency"].string ?: urgency,
                urgencyRaised = json["urgencyRaised"].bool ?: false,
                waitingStartedAt = json["waitingStartedAt"].long,
                snoozed = json["snoozed"].bool ?: false,
                resurfacedToday = json["resurfacedToday"].bool ?: false,
                project = json["project"].string ?: "",
                ref = json["ref"].string ?: "",
                statusName = json["statusName"].string ?: "",
                waitingOnName = json["waitingOnName"].string,
                workingSession = TaskWorkingSession.from(json["workingSession"]),
                check = TaskCheckSummary.from(json["check"]),
            )
        }
    }
}

/** A custom field value as text (`String(v)`); null for none. */
fun fieldValueText(value: JsonElement?): String? {
    val p = value as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return if (p.isString) p.content else value.double?.let(::jsNumber) ?: p.content
}

/** One project as the task views list it (`TaskProjectSummary`). */
data class TaskProjectSummary(
    val name: String,
    val projectKey: String,
    val enabled: Boolean,
    val hasManager: Boolean,
    val open: Int,
    val now: Int,
    val snoozed: Int,
) {
    companion object {
        fun from(json: JsonElement?): TaskProjectSummary? {
            val name = json["name"].string ?: return null
            return TaskProjectSummary(
                name, json["projectKey"].string ?: name, json["enabled"].bool ?: false, json["hasManager"].bool ?: false,
                json["open"].int ?: 0, json["now"].int ?: 0, json["snoozed"].int ?: 0,
            )
        }
    }
}

data class TaskListResponse(val tasks: List<TaskDto>, val projects: List<TaskProjectSummary>) {
    companion object {
        fun from(json: JsonElement?) = TaskListResponse(
            json["tasks"].array.orEmpty().mapNotNull(TaskDto::from),
            json["projects"].array.orEmpty().mapNotNull(TaskProjectSummary::from),
        )
    }
}

data class TaskDetail(val task: TaskDto, val children: List<TaskDto>, val linkStates: List<TaskLinkState>, val jiraSite: String?) {
    companion object {
        fun from(json: JsonElement?): TaskDetail? {
            val task = TaskDto.from(json["task"]) ?: return null
            return TaskDetail(
                task,
                json["children"].array.orEmpty().mapNotNull(TaskDto::from),
                json["linkStates"].array.orEmpty().mapNotNull(TaskLinkState::from),
                json["jiraSite"].string,
            )
        }
    }
}

/**
 * Every write's answer: the task as it now stands, whether to ask "Tell the manager now?", and for
 * a decision how the chat took the choice ([delivered] `started`/`inserted`/`queued`, null when
 * recorded but not sent) — for Start chat, [sessionId] is the new chat.
 */
data class TaskWriteResult(
    val task: TaskDto,
    val managerPrompt: Boolean,
    val delivered: String? = null,
    val sessionId: String? = null,
    val deliveryError: String? = null,
) {
    companion object {
        fun from(json: JsonElement?): TaskWriteResult? {
            val task = TaskDto.from(json["task"]) ?: return null
            return TaskWriteResult(task, json["managerPrompt"].bool ?: false, json["delivered"].string, json["sessionId"].string, json["deliveryError"].string)
        }
    }
}

/** `notify-manager`: sent, or why not (`budget`, `nothing_pending`, `no_manager`, `error`). */
data class NotifyManagerResult(val sent: Boolean, val sessionId: String?, val reason: String?, val error: String?)

data class TaskActor(val kind: String, val label: String, val sessionId: String?)

data class TaskHistoryChange(val field: String, val before: JsonElement?, val after: JsonElement?)

data class TaskHistoryItem(
    val id: Long,
    val taskId: String?,
    val at: Long,
    val actor: TaskActor,
    val via: String,
    val unattended: Boolean,
    val kind: String,
    val changes: List<TaskHistoryChange>,
    val reason: String?,
    val undoOf: Long?,
    val undoneBy: Long?,
    val undoable: Boolean,
) {
    companion object {
        fun from(json: JsonElement?): TaskHistoryItem? {
            val id = json["id"].long ?: return null
            val actor = json["actor"]
            return TaskHistoryItem(
                id = id,
                taskId = json["taskId"].string,
                at = json["at"].long ?: 0,
                actor = TaskActor(actor["kind"].string ?: "ppm", actor["label"].string ?: "PPM", actor["sessionId"].string),
                via = json["via"].string ?: "ppm",
                unattended = json["unattended"].bool ?: false,
                kind = json["kind"].string ?: "updated",
                changes = json["changes"].array.orEmpty().mapNotNull { c ->
                    val field = c["field"].string ?: return@mapNotNull null
                    TaskHistoryChange(field, c["before"]?.takeIf { it !is JsonNull }, c["after"]?.takeIf { it !is JsonNull })
                },
                reason = json["reason"].string,
                undoOf = json["undoOf"].long,
                undoneBy = json["undoneBy"].long,
                undoable = json["undoable"].bool ?: false,
            )
        }
    }
}

/** One task's live parts from `GET /api/tasks/link-states`. */
data class TaskLiveParts(val linkStates: List<TaskLinkState>, val workingSession: TaskWorkingSession)

data class TaskStatusDef(val id: String, val name: String, val baseGroup: String, val closedAs: String?, val position: Int) {
    companion object {
        fun from(json: JsonElement?): TaskStatusDef? {
            val id = json["id"].string ?: return null
            return TaskStatusDef(id, json["name"].string ?: id, json["baseGroup"].string ?: "not_started", json["closedAs"].string, json["position"].int ?: 0)
        }
    }
}

data class TaskMember(
    val id: String,
    val name: String,
    val jiraAccountId: String?,
    val githubLogin: String?,
    val slackUserId: String?,
    val position: Int,
    val archivedAt: Long?,
) {
    companion object {
        fun from(json: JsonElement?): TaskMember? {
            val id = json["id"].string ?: return null
            return TaskMember(
                id, json["name"].string ?: id, json["jiraAccountId"].string, json["githubLogin"].string, json["slackUserId"].string,
                json["position"].int ?: 0, json["archivedAt"].long,
            )
        }
    }
}

data class TaskFieldDef(val key: String, val label: String, val type: String, val options: List<String>?, val position: Int) {
    companion object {
        fun from(json: JsonElement?): TaskFieldDef? {
            val key = json["key"].string ?: return null
            return TaskFieldDef(key, json["label"].string ?: key, json["type"].string ?: "text", json["options"].array?.mapNotNull { it.string }, json["position"].int ?: 0)
        }
    }
}

data class TaskProjectSettings(
    val project: TaskProjectSummary,
    /** `user`, `manager_assign`, `migration` or null. */
    val enabledBy: String?,
    val checkIntervalMin: Int,
    val statuses: List<TaskStatusDef>,
    val members: List<TaskMember>,
    val fieldDefs: List<TaskFieldDef>,
) {
    companion object {
        fun from(json: JsonElement?): TaskProjectSettings? {
            val project = TaskProjectSummary.from(json["project"]) ?: return null
            return TaskProjectSettings(
                project,
                json["project"]["enabledBy"].string,
                json["project"]["checkIntervalMin"].int ?: TaskConst.CHECK_INTERVAL_DEFAULT_MIN,
                json["statuses"].array.orEmpty().mapNotNull(TaskStatusDef::from).sortedBy { it.position },
                json["members"].array.orEmpty().mapNotNull(TaskMember::from).sortedBy { it.position },
                json["fieldDefs"].array.orEmpty().mapNotNull(TaskFieldDef::from).sortedBy { it.position },
            )
        }
    }
}
