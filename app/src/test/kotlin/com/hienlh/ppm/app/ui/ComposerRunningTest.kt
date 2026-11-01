package com.hienlh.ppm.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the message box shows while a turn runs: `showCancel` and the textarea's placeholder in message-input.tsx. */
class ComposerRunningTest {
    @Test
    fun `Stop takes Send's place only while a turn runs and the box has nothing to send`() {
        assertTrue(showsStop(running = true, hasContent = false))
        assertFalse(showsStop(running = true, hasContent = true))
        assertFalse(showsStop(running = false, hasContent = false))
        assertFalse(showsStop(running = false, hasContent = true))
    }

    @Test
    fun `the placeholder asks for a follow-up while a turn runs, a phone's without the Stop a tablet's names`() {
        assertEquals("Ask anything...", composerPlaceholder(running = false, mobile = true))
        assertEquals("Ask anything...", composerPlaceholder(running = false, mobile = false))
        assertEquals("Follow-up...", composerPlaceholder(running = true, mobile = true))
        assertEquals("Follow-up or Stop...", composerPlaceholder(running = true, mobile = false))
    }
}
