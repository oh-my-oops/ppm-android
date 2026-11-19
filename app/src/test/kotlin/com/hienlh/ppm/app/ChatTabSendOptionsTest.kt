package com.hienlh.ppm.app

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.ChatClientMessage
import com.hienlh.ppm.state.PermissionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a send carries (`executeSend`) and the box after it. A message carries no priority (Queue and
 * Send now are the only two ways a message sent during a turn goes), and the frame never names one.
 */
class ChatTabSendOptionsTest {
    private val tab = ChatTab("t", SessionInfo("s", "mock", "Chat"), "p", "mock")

    @Test
    fun `a message carries no priority, during a turn or not`() {
        val frame = ChatClientMessage.Message("c1", "hi", tab.sendOptions(), queued = true).json
        assertFalse("priority" in frame)
        assertEquals("true", frame["queued"].toString())
    }

    @Test
    fun `the mode goes with it, and none is sent until one is picked`() {
        assertNull(tab.sendOptions().permissionMode)
        tab.permissionMode = PermissionMode.PLAN
        assertEquals("plan", tab.sendOptions().permissionMode)
    }

    @Test
    fun `a send leaves the box empty`() {
        tab.draft.setTextAndPlaceCursorAtEnd("one more thing")
        tab.agent = "reviewer"
        tab.emptyBox()
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
