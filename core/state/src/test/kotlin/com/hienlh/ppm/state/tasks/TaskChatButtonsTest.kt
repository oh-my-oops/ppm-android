package com.hienlh.ppm.state.tasks

import com.hienlh.ppm.protocol.TaskSessionState
import com.hienlh.ppm.protocol.TaskWorkingSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which chat buttons a task view shows (`task-chat-buttons.test.ts`): Open chat for a chat the task
 * was handed to, Ask manager whenever the project has a manager chat, never both for one chat.
 */
class TaskChatButtonsTest {
    private fun session(id: String, provider: String? = "claude") = TaskSessionState(id, "idle", null, provider, null, null)
    private fun working(kind: String, id: String? = null, provider: String? = "claude") =
        if (id == null) TaskWorkingSession.START_CHAT else TaskWorkingSession(kind, session(id, provider))
    private val managers = listOf(TaskManagerLike("ppm", "mgr-ppm", "claude"), TaskManagerLike("pex", "mgr-pex", "codex"))

    @Test
    fun `a task handed to a child chat shows both`() {
        val b = taskChatButtons(task(project = "ppm", workingSession = working("session", "child-1")), managers)
        assertEquals(TaskChatButtons(TaskChatRef("child-1", "claude"), TaskChatRef("mgr-ppm", "claude")), b)
        assertTrue(b.crowded)
    }

    @Test
    fun `a task the manager works on shows Ask manager alone`() {
        val b = taskChatButtons(task(project = "ppm", workingSession = working("manager", "mgr-ppm")), managers)
        assertNull(b.openChat)
        assertEquals(TaskChatRef("mgr-ppm", "claude"), b.askManager)
        assertFalse(b.crowded)
    }

    @Test
    fun `a task handed to the manager chat by id is not handed off either`() {
        val t = task(project = "ppm", workingSession = working("session", "mgr-ppm"))
        val b = taskChatButtons(t, managers)
        assertNull(b.openChat)
        assertEquals("mgr-ppm", b.askManager?.sessionId)
        assertTrue(workingIsManager(t, "mgr-ppm"))
        assertFalse(workingIsManager(t, null))
    }

    @Test
    fun `a project with no manager chat shows no Ask manager`() {
        assertEquals(
            TaskChatButtons(TaskChatRef("child-9", "claude"), null),
            taskChatButtons(task(project = "gamma", workingSession = working("session", "child-9")), managers),
        )
        assertEquals(TaskChatButtons(null, null), taskChatButtons(task(project = "gamma"), managers))
    }

    @Test
    fun `the manager's provider comes from the manager list`() {
        assertEquals(TaskChatRef("mgr-pex", "codex"), taskChatButtons(task(project = "pex", workingSession = working("session", "child-2")), managers).askManager)
    }

    @Test
    fun `the manager falls back to the task's own working session while the list loads`() {
        val t = task(project = "ppm", workingSession = working("manager", "mgr-ppm", provider = null))
        assertEquals(TaskChatRef("mgr-ppm", null), taskManagerChat(t, null))
        assertEquals(TaskChatButtons(null, TaskChatRef("mgr-ppm", null)), taskChatButtons(t, null))
        assertNull(taskManagerChat(task(project = "ppm", workingSession = working("session", "child-1")), null))
        assertNull(taskManagerChat(task(project = "ppm"), emptyList()))
    }
}
