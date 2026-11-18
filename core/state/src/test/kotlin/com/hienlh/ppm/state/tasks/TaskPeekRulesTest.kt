package com.hienlh.ppm.state.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

/** The quick view's and the manager chip's pure rules (`task-peek-compact.ts`, `manager-tasks-popover-list.tsx`). */
class TaskPeekRulesTest {
    @Test
    fun `links past the limit fold into +N in the last place`() {
        assertEquals(listOf(1, 2, 3, 4) to 0, peekLinks(listOf(1, 2, 3, 4), expanded = false))
        assertEquals(listOf(1, 2, 3) to 2, peekLinks(listOf(1, 2, 3, 4, 5), expanded = false))
        assertEquals(listOf(1, 2, 3, 4, 5) to 0, peekLinks(listOf(1, 2, 3, 4, 5), expanded = true))
    }

    @Test
    fun `due presets carry their weekday, chip titles name an empty field`() {
        val p = peekDuePresets("2026-11-18")
        assertEquals(listOf("Today", "Tomorrow", "In a week"), p.map { it.label })
        assertEquals(listOf("2026-11-18", "2026-11-19", "2026-11-25"), p.map { it.ymd })
        assertEquals("Thu 19 Nov", p[1].hint)
        assertEquals("Add due date", peekChipTitle("Due date", null))
        assertEquals("Urgency: Today", peekChipTitle("Urgency", "Today"))
        assertEquals("Tasks: 9 open, 2 now", tasksChipLabel(9, 2))
    }

    @Test
    fun `the chip list cuts at the limit across groups and counts the rest as more`() {
        val tasks = (1..6).map { task(id = "n$it", number = it, urgency = "now") } +
            (7..10).map { task(id = "w$it", number = it, urgency = "week") } +
            task(id = "c", number = 11, urgency = "now", parentId = "n1") +
            task(id = "x", number = 12, baseGroup = "closed")
        val v = chipListView(tasks)
        assertEquals(listOf("now", "week"), v.groups.map { it.first })
        assertEquals(8, v.ids.size)
        assertEquals(2, v.more)
        // Flat: the child is a row of its own.
        assertEquals(3, chipListView(tasks, flat = true).more)
    }
}
