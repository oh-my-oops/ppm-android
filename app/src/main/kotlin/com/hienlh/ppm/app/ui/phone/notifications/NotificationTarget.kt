package com.hienlh.ppm.app.ui.phone.notifications

import com.hienlh.ppm.client.FeedRow

/** What tapping a notification row opens. */
internal sealed interface NotificationTarget {
    /** The chat the notification is about. */
    data class Chat(val sessionId: String) : NotificationTarget

    /**
     * The schedule it is about, selected in the Schedules window (`openSchedules({ select })`):
     * a script's run summary or `ctx.notify` names no chat, only its schedule.
     */
    data class Schedule(val id: Int) : NotificationTarget

    /** Neither: its project. */
    data object Project : NotificationTarget
}

/** A row's target: its chat when it names one, else its schedule, else its project. */
internal fun notificationTarget(row: FeedRow): NotificationTarget {
    row.sessionId?.takeIf { it.isNotBlank() }?.let { return NotificationTarget.Chat(it) }
    row.scheduleId?.trim()?.toIntOrNull()?.let { return NotificationTarget.Schedule(it) }
    return NotificationTarget.Project
}

/** The action sheet's open row, named after what it opens. */
internal fun notificationOpenLabel(row: FeedRow): String = when (notificationTarget(row)) {
    is NotificationTarget.Chat -> "Open chat"
    is NotificationTarget.Schedule -> "Open schedule"
    NotificationTarget.Project -> "Open project"
}
