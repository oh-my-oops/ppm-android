package com.hienlh.ppm.app

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.hienlh.ppm.protocol.ChatReply
import com.hienlh.ppm.protocol.ReplyReference
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.state.ChatBlock
import com.hienlh.ppm.state.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Edit on a message, as `handleEdit`, `handleCancelEdit` and `handleSwitchVersion` leave the box. */
class ChatTabEditTest {
    private val tab = ChatTab("t", SessionInfo("fork-2", "mock", "Chat"), "p", "mock")

    private val quoted = ReplyReference(
        sessionId = "root-1", providerId = "mock", messageId = "a1", role = ReplyReference.Role.ASSISTANT,
        timestamp = "2026-10-25T08:00:00.000Z", quote = "Earlier answer", truncated = false,
    )

    private fun user(text: String) = ChatItem("u7", ChatItem.Role.USER, listOf(ChatBlock.Text(text)), null, sdkUuid = "sdk-u7")

    @Test
    fun `an edit puts the message's text in place of the box's and picks its reply again in this chat`() {
        tab.draft.setTextAndPlaceCursorAtEnd("half-typed")
        tab.armEdit(user(ChatReply.encode("Fix the test", quoted)), anchor = "sdk-a6")
        assertEquals("Fix the test", tab.draft.text.toString())
        assertEquals(quoted.copy(sessionId = "fork-2"), tab.replyTo)
        assertEquals(EditFork("sdk-a6", "u7"), tab.editFork)
    }

    @Test
    fun `cancelling an edit empties the box and the next send is a new message`() {
        tab.armEdit(user("Fix the test"), anchor = null)
        tab.agent = "reviewer"
        tab.cancelEdit()
        assertNull(tab.editFork)
        assertNull(tab.replyTo)
        assertNull(tab.agent)
        assertEquals("", tab.draft.text.toString())
    }

    @Test
    fun `switching version leaves the edit and the box behind`() {
        tab.armEdit(user(ChatReply.encode("Fix the test", quoted)), anchor = "sdk-a6")
        tab.swapSession(SessionInfo("root-1", "mock", "Chat"), moveDraft = false)
        assertEquals("root-1", tab.session?.id)
        assertNull(tab.editFork)
        assertNull(tab.replyTo)
        assertEquals("", tab.draft.text.toString())
    }
}
