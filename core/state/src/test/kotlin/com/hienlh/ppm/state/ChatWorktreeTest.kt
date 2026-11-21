package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ChatFrame
import com.hienlh.ppm.protocol.SessionWorktree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A new Claude chat that runs in a git worktree of its own (`worktreeWanted` and `session_worktree` in use-chat.ts). */
class ChatWorktreeTest {
    private val wt = SessionWorktree("chat-261121-0930-00ff", "/p/.claude/worktrees/chat-261121-0930-00ff", "worktree-chat-261121-0930-00ff")

    @Test
    fun `nothing asks for a worktree until the switch is on`() {
        val chat = ChatTranscript("claude")
        assertNull(chat.send("hi").worktree)
        assertNull(chat.worktree)
    }

    @Test
    fun `the switch puts a fresh valid name on each message until the worktree is reported`() {
        val chat = ChatTranscript("claude")
        chat.worktreeWanted = true
        val first = chat.send("hi").worktree
        assertTrue(first != null && SessionWorktree.NAME_RE.matches(first))
        assertTrue(first!!.startsWith("chat-"))
        // A queued message before the report still asks; the server honours the first turn's only.
        assertTrue(chat.send("more").worktree != null)

        chat.handle(ChatFrame.WorktreeChanged(wt))
        assertEquals(wt, chat.worktree)
        assertFalse(chat.worktreeWanted)
        assertNull(chat.send("later").worktree)
    }
}
