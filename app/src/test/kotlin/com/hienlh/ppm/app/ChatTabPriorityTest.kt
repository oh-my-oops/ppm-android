package com.hienlh.ppm.app

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.state.MessagePriority
import com.hienlh.ppm.state.PermissionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The priority chip's side of a send (`executeSend`): what the message carries, and the box and chip after it. */
class ChatTabPriorityTest {
    private val tab = ChatTab("t", SessionInfo("s", "mock", "Chat"), "p", "mock")

    @Test
    fun `a message sent during a turn carries the priority the chip shows, Queue until it is pressed`() {
        assertEquals("next", tab.sendOptions(running = true).priority)
        tab.priority = tab.priority.following
        assertEquals("later", tab.sendOptions(running = true).priority)
        tab.priority = tab.priority.following
        assertEquals("now", tab.sendOptions(running = true).priority)
        tab.priority = tab.priority.following
        assertEquals("next", tab.sendOptions(running = true).priority)
    }

    @Test
    fun `a message sent while no turn runs carries none, whatever the chip was left on`() {
        tab.priority = MessagePriority.NOW
        assertNull(tab.sendOptions(running = false).priority)
    }

    @Test
    fun `the mode goes with it, and none is sent until one is picked`() {
        assertNull(tab.sendOptions(running = true).permissionMode)
        tab.permissionMode = PermissionMode.PLAN
        assertEquals("plan", tab.sendOptions(running = true).permissionMode)
        assertEquals("plan", tab.sendOptions(running = false).permissionMode)
    }

    @Test
    fun `a send leaves the box empty and the chip on Queue`() {
        tab.priority = MessagePriority.NOW
        tab.draft.setTextAndPlaceCursorAtEnd("one more thing")
        tab.agent = "reviewer"
        tab.emptyBox()
        assertEquals(MessagePriority.NEXT, tab.priority)
        assertEquals("", tab.draft.text.toString())
        assertNull(tab.agent)
        assertFalse(tab.hasContent)
    }

    @Test
    fun `Send has something to send for text, a file that did not fail or an agent, which is when Stop gives way to it`() {
        assertFalse(tab.hasContent)
        tab.draft.setTextAndPlaceCursorAtEnd("  \n ")
        assertFalse("blanks are nothing", tab.hasContent)
        tab.draft.setTextAndPlaceCursorAtEnd("go")
        assertTrue(tab.hasContent)
        tab.draft.setTextAndPlaceCursorAtEnd("")
        tab.attachments += ComposerAttachment("a", "a.png", isImage = true, status = ComposerAttachment.Status.ERROR)
        assertFalse("a file that failed is nothing", tab.hasContent)
        tab.attachments += ComposerAttachment("b", "b.txt", isImage = false, status = ComposerAttachment.Status.UPLOADING)
        assertTrue("a file still uploading is something", tab.hasContent)
        tab.attachments.clear()
        tab.agent = "reviewer"
        assertTrue(tab.hasContent)
    }
}
