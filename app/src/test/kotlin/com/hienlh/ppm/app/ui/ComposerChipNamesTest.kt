package com.hienlh.ppm.app.ui

import com.hienlh.ppm.state.PermissionMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** The words a chip's `title` and `aria-label` carry in message-input.tsx and model-thinking-selector.tsx. */
class ComposerChipNamesTest {
    @Test
    fun `the mode chip names the mode it shows, in every mode`() {
        assertEquals("Permission mode: Ask before edits", modeChipName(PermissionMode.DEFAULT))
        assertEquals("Permission mode: Edit automatically", modeChipName(PermissionMode.ACCEPT_EDITS))
        assertEquals("Permission mode: Plan mode", modeChipName(PermissionMode.PLAN))
        assertEquals("Permission mode: Bypass permissions", modeChipName(PermissionMode.BYPASS_PERMISSIONS))
    }

    @Test
    fun `the model chip's tooltip is its text, until a turn runs and it says why a press does nothing`() {
        assertEquals("Opus 4.7 · High", modelChipTitle(running = false, text = "Opus 4.7 · High"))
        assertEquals("Can't change while running", modelChipTitle(running = true, text = "Opus 4.7 · High"))
    }

    @Test
    fun `the model chip's name carries the effort as the server knows it and says when thinking is on`() {
        assertEquals("Model Opus 4.7, effort high", modelChipName("Opus 4.7", "high", thinking = false))
        // The chip's text says "Extra"; the name keeps the SDK's value.
        assertEquals("Model Sonnet, effort xhigh, thinking on", modelChipName("Sonnet", "xhigh", thinking = true))
    }
}
