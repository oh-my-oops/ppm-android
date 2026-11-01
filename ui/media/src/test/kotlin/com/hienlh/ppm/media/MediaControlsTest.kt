package com.hienlh.ppm.media

import org.junit.Assert.*
import org.junit.Test

class MediaControlsTest {
    @Test fun `quarter turn fits portrait axes without cropping and preserves flips`() {
        val rotated = VideoTransform(flipH = true).rotate(-1)
        assertEquals(270, rotated.rotation)
        assertTrue(rotated.flipH)
        val (width, height) = rotated.fittedSize(400f, 300f, 16f / 9f)
        assertEquals(300f, width, 0.01f)
        assertEquals(168.75f, height, 0.01f)
        assertTrue(height <= 400 && width <= 300)
        assertEquals(VideoTransform(flipH = true), rotated.rotate(1))
        assertEquals(0f to 0f, rotated.fittedSize(0f, 300f, 1f))
    }

    @Test fun `normal fit letterboxes and full rotation restores original geometry`() {
        val original = VideoTransform(flipV = true)
        val cycled = (0..3).fold(original) { t, _ -> t.rotate(1) }
        assertEquals(original, cycled)
        assertEquals(400f to 225f, cycled.fittedSize(400f, 300f, 16f / 9f))
        assertFalse(cycled.identity)
    }

    @Test fun `speed steps exactly follow web menu and clamp unknown rates from one`() {
        assertEquals(listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f), mediaSpeeds)
        mediaSpeeds.zipWithNext().forEach { (a, b) -> assertEquals(b, stepMediaSpeed(a, 1)); assertEquals(a, stepMediaSpeed(b, -1)) }
        assertEquals(3f, stepMediaSpeed(3f, 1))
        assertEquals(0.25f, stepMediaSpeed(0.25f, -1))
        assertEquals(1.25f, stepMediaSpeed(1.7f, 1))
    }

    @Test fun `keyboard has web modifiers and does not steal modified shortcuts`() {
        assertEquals(MediaAction.Seek(-5.0), mediaShortcut("ArrowLeft"))
        assertEquals(MediaAction.Seek(10.0), mediaShortcut("ArrowRight", shift = true))
        assertEquals(MediaAction.Seek(-10.0), mediaShortcut("J"))
        assertEquals(MediaAction.Rotate(-1), mediaShortcut("R"))
        assertEquals(MediaAction.Rotate(-1), mediaShortcut("r", shift = true))
        assertEquals(MediaAction.Speed(-1), mediaShortcut("<"))
        assertEquals(MediaAction.Speed(1), mediaShortcut("."))
        assertEquals(MediaAction.Play, mediaShortcut(" "))
        assertNull(mediaShortcut("k", modified = true))
        assertNull(mediaShortcut("Tab"))
    }

    @Test fun `volume nudges unmute and do not accumulate floating point drift`() {
        var volume = MediaVolume(0.1f, muted = true)
        repeat(9) { volume = volume.by(0.1f) }
        assertEquals(MediaVolume(1f, false), volume)
        assertEquals(1f, volume.by(0.1f).level)
        assertEquals(MediaVolume(0f, true), volume.slider(0f))
        assertEquals(MediaVolume(0f, false), MediaVolume(0f, true).by(-0.1f))
        assertEquals(MediaVolume(0.5f, false), MediaVolume(0.3f, true).slider(0.5f))
    }

    @Test fun `seek slider is already on grid so final pointer commit cannot jump backward`() {
        assertEquals(12.3, mediaSeekBar(12.3999, 100.0), 0.00001)
        assertEquals(99.9, mediaSeekBar(100.3, 99.99), 0.00001)
        assertEquals("1:02:05", mediaClock(3725.9))
        assertEquals("1:05", mediaClock(65.9))
        assertEquals("0:00", mediaClock(Double.NaN))
        assertEquals("0:00", mediaClock(-10.0))
    }

    @Test fun `audio prepares tracks then stops loading until user requests play`() {
        val audio = MetadataPreload(true)
        assertTrue(audio.allowLoading())
        audio.ready = true
        assertFalse(audio.allowLoading())
        audio.requestedPlay = true
        assertTrue(audio.allowLoading())
        audio.requestedPlay = false
        assertFalse(audio.allowLoading())
        audio.ready = false // A seek must be allowed to rebuffer and become ready again.
        assertTrue(audio.allowLoading())
        val video = MetadataPreload(false).apply { ready = true }
        assertTrue(video.allowLoading())
    }
}
