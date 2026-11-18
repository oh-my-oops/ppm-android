package com.hienlh.ppm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The tasks block after a user message (`shared/chat-task-ref.ts`). */
class ChatTaskRefsTest {
    private val ref = TaskReference(
        taskId = "11111111-1111-4111-8111-111111111111", ref = "ppm/12", number = 12, projectName = "ppm",
        title = "Fix <login> & “quotes”", status = "In progress", urgency = "today", due = "2026-11-20",
        link = "https://ppm.example/project/ppm/task/11111111-1111-4111-8111-111111111111",
    )

    @Test
    fun `a block the server writes decodes into chips and leaves the typed text`() {
        val content = ChatTaskRefs.encode("fix it now", listOf(ref))
        val decoded = ChatTaskRefs.decode(content)
        assertEquals("fix it now", decoded.content)
        assertEquals(listOf(ref), decoded.taskRefs)
        val parsed = ParsedUserMessage.parse(content)
        assertEquals("fix it now", parsed.text)
        assertEquals("ppm/12", parsed.taskRefs?.single()?.ref)
    }

    @Test
    fun `the web's own block reads back byte for byte`() {
        // As `encodeTaskRefs` writes it: the readable lines escape <, > and & in the JSON strings.
        val content = ChatTaskRefs.encode("x", listOf(ref.copy(due = null)))
        assertEquals(true, content.contains("Task \"ppm/12\" (task_id 11111111-1111-4111-8111-111111111111): \"Fix \\u003clogin\\u003e \\u0026 “quotes”\""))
        assertEquals(true, content.contains("· urgency Today · due none"))
        assertEquals(listOf(ref.copy(due = null)), ChatTaskRefs.decode(content).taskRefs)
    }

    @Test
    fun `a block whose readable lines were changed stays text`() {
        val content = ChatTaskRefs.encode("x", listOf(ref)).replace("status \"In progress\"", "status \"Done\"")
        val decoded = ChatTaskRefs.decode(content)
        assertNull(decoded.taskRefs)
        assertEquals(content, decoded.content)
        assertNull(ChatTaskRefs.decode("plain").taskRefs)
    }
}
