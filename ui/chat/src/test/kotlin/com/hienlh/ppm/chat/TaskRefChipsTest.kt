package com.hienlh.ppm.chat

import com.hienlh.ppm.protocol.TaskReference
import org.junit.Assert.assertEquals
import org.junit.Test

/** A sent message's task chip names its task as the chats do (`taskChipLabel`). */
class TaskRefChipsTest {
    private val ref = TaskReference("t1", "ppm/12", 12, "ppm", "Fix login", "In progress", "now", null, "/project/ppm?openTask=t1")

    @Test
    fun `a task of the chat's own project reads #12, any other project's ppm-slash-12`() {
        assertEquals("#12", taskRefCode(ref, "ppm"))
        assertEquals("ppm/12", taskRefCode(ref, "other"))
        assertEquals("ppm/12", taskRefCode(ref, null))
    }
}
