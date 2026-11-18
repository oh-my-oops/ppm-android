package com.hienlh.ppm.state.tasks

import java.time.LocalDate

/*
 * The pure half of a task's quick view (`task-peek-compact.ts`): what a property chip says, Due's
 * presets, and how many links are drawn before "+N".
 */

/** How many links the quick view draws before it folds the rest into "+N". */
const val PEEK_LINK_LIMIT = 4

/** The links drawn and how many are folded: past [limit], the first `limit - 1` and "+N" in the last place. */
fun <T> peekLinks(links: List<T>, expanded: Boolean, limit: Int = PEEK_LINK_LIMIT): Pair<List<T>, Int> {
    if (expanded || links.size <= limit) return links to 0
    val shown = links.take((limit - 1).coerceAtLeast(0))
    return shown to links.size - shown.size
}

/** A chip's name: "Urgency: Today" for a set field, "Add due date" for an empty one. */
fun peekChipTitle(field: String, value: String?): String = if (!value.isNullOrEmpty()) "$field: $value" else "Add ${field.lowercase()}"

/** One of Due's presets: "Tomorrow" with its day, "Wed 19 Nov". */
data class DuePreset(val label: String, val ymd: String, val hint: String)

private val WEEKDAY = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MONTH = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** Today, Tomorrow and In a week from the user's [todayYmd]. */
fun peekDuePresets(todayYmd: String): List<DuePreset> = listOf("Today" to 0, "Tomorrow" to 1, "In a week" to 7).map { (label, days) ->
    val ymd = addDaysYmd(todayYmd, days)
    val d = runCatching { LocalDate.parse(ymd) }.getOrNull()
    DuePreset(label, ymd, if (d == null) ymd else "${WEEKDAY[d.dayOfWeek.value - 1]} ${d.dayOfMonth} ${MONTH[d.monthValue - 1]}")
}

/** The Tasks chip's accessible name: "Tasks: 9 open, 2 now". */
fun tasksChipLabel(open: Int, now: Int): String = "Tasks: $open open, $now now"

/** The rows the manager chip shows: open top-level tasks in "what to do first" order, cut at [limit]. */
data class ChipListView(val groups: List<Pair<String, List<com.hienlh.ppm.protocol.TaskDto>>>, val more: Int, val ids: List<String>)

const val CHIP_LIST_LIMIT = 8

/** With [flat], a child is a row of its own even when its parent is listed ("Waiting on you"). */
fun chipListView(tasks: List<com.hienlh.ppm.protocol.TaskDto>, limit: Int = CHIP_LIST_LIMIT, flat: Boolean = false): ChipListView {
    val open = tasks.filter { it.baseGroup != "closed" && it.deletedAt == null }
    val groups = mutableListOf<Pair<String, List<com.hienlh.ppm.protocol.TaskDto>>>()
    var shown = 0
    var total = 0
    for (g in groupTasks(if (flat) open.map { it.copy(parentId = null) } else open, emptySet())) {
        val top = g.topTasks
        total += top.size
        val take = top.take((limit - shown).coerceAtLeast(0))
        shown += take.size
        if (take.isNotEmpty()) groups += g.urgency to take
    }
    return ChipListView(groups, total - shown, groups.flatMap { (_, ts) -> ts.map { it.id } })
}
