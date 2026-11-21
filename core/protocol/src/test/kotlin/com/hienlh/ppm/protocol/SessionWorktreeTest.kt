package com.hienlh.ppm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

/** A chat in a git worktree of its own on the wire (`src/shared/session-worktree.ts`, `session_worktree`). */
class SessionWorktreeTest {
    private fun frame(text: String) = ChatFrame.from(WireJson.parse(text))

    @Test
    fun `session_worktree names the worktree, and one without a path is no worktree`() {
        val wt = SessionWorktree("chat-261121-0930-00ff", "/p/.claude/worktrees/chat-261121-0930-00ff", "worktree-chat-261121-0930-00ff")
        assertEquals(
            ChatFrame.WorktreeChanged(wt),
            frame("""{"type":"session_worktree","sessionId":"s","worktree":{"name":"${wt.name}","path":"${wt.path}","branch":"${wt.branch}","extra":1}}"""),
        )
        assertTrue(frame("""{"type":"session_worktree","sessionId":"s","worktree":{"name":"x"}}""") is ChatFrame.Event)
        assertTrue(frame("""{"type":"session_worktree","sessionId":"s"}""") is ChatFrame.Event)
        assertNull(SessionWorktree.from(WireJson.parse("""{"path":3}""")))
    }

    @Test
    fun `a message carries the worktree name only when one is asked for`() {
        val plain = ChatClientMessage.Message("id", "hi").json
        assertFalse(plain.containsKey("worktree"))
        val asked = ChatClientMessage.Message("id", "hi", worktree = "chat-261121-0930-00ff").json
        assertEquals("chat-261121-0930-00ff", asked["worktree"].string)
    }

    @Test
    fun `a fresh name is stamped with when it was made and passes the server's check`() {
        val name = SessionWorktree.newName(LocalDateTime.of(2026, 11, 21, 9, 5), Random(1))
        assertTrue(name, Regex("^chat-261121-0905-[0-9a-f]{4}$").matches(name))
        assertTrue(SessionWorktree.NAME_RE.matches(name))
        assertFalse(SessionWorktree.NAME_RE.matches("Chat"))
        assertFalse(SessionWorktree.NAME_RE.matches("-chat"))
        assertFalse(SessionWorktree.NAME_RE.matches("a".repeat(41)))
    }
}
