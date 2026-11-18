package com.hienlh.ppm.state.tasks

import com.hienlh.ppm.protocol.TaskCheckSummary
import com.hienlh.ppm.protocol.TaskDto
import com.hienlh.ppm.protocol.TaskLink
import com.hienlh.ppm.protocol.TaskWorkingSession
import kotlinx.serialization.json.JsonElement

/** A task with every field at a plain default; tests copy what they need. */
fun task(
    id: String = "t1",
    number: Int = 1,
    project: String = "ppm",
    urgency: String = "week",
    effectiveUrgency: String = urgency,
    importance: String = "should",
    rank: Double = 1.0,
    baseGroup: String = "not_started",
    parentId: String? = null,
    snoozed: Boolean = false,
    snoozedUntil: String? = null,
    waitingOnMemberId: String? = null,
    tags: List<String> = emptyList(),
    title: String = "Task $number",
    type: String = "task",
    fields: Map<String, JsonElement> = emptyMap(),
    links: List<TaskLink> = emptyList(),
    workingSessionId: String? = null,
    statusName: String = "To do",
    dueDate: String? = null,
    workingSession: TaskWorkingSession = TaskWorkingSession.START_CHAT,
    check: TaskCheckSummary? = null,
) = TaskDto(
    id = id, projectKey = "k-$project", number = number, title = title, type = type, statusId = "s", urgency = urgency, importance = importance,
    rank = rank, why = null, nextStep = null, notes = null, dueDate = dueDate, tags = tags, assigneeMemberId = null,
    waitingOnMemberId = waitingOnMemberId, waitingSince = null, parentId = parentId, workingSessionId = workingSessionId, decision = null,
    fields = fields, links = links, result = null, handedOffAt = null, snoozedUntil = snoozedUntil, snoozeUntilChange = false, snoozedAt = null,
    resurfacedAt = null, revision = 1, createdAt = 0, updatedAt = 0, closedAt = null, deletedAt = null, baseGroup = baseGroup,
    closedAs = null, effectiveUrgency = effectiveUrgency, urgencyRaised = effectiveUrgency != urgency, waitingStartedAt = null,
    snoozed = snoozed, resurfacedToday = false, project = project, ref = "$project/$number", statusName = statusName,
    waitingOnName = null, workingSession = workingSession, check = check,
)
