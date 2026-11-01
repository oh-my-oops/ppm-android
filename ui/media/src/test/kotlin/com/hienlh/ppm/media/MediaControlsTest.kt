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

    @Test fun `a duration is seconds to the nearest millisecond, so a gapless mp3 that is a sample short is the whole second it is`() {
        // What Media3 reported for ffmpeg's 4.000 s tones at 44.1 and 48 kHz (ffprobe and Chrome: 4.000000), and for one with no gapless tag.
        assertEquals(4.0, mediaDurationSeconds(3_999_977)!!, 0.0)
        assertEquals(4.0, mediaDurationSeconds(3_999_979)!!, 0.0)
        assertEquals(4.049, mediaDurationSeconds(4_048_937)!!, 0.0)
        assertEquals(4.6, mediaDurationSeconds(4_600_000)!!, 0.0)
        // To the nearest, not up: a file that really is 0.6 ms short of 4 s is 3.9994 s.
        assertEquals(3.999, mediaDurationSeconds(3_999_400)!!, 0.0)
        assertNull(mediaDurationSeconds(0))
        assertNull(mediaDurationSeconds(-9_223_372_036_854_775_807)) // C.TIME_UNSET
    }

    @Test fun `the clock floors a duration as Chrome's controls do`() {
        assertEquals("0:04", mediaClock(mediaDurationSeconds(3_999_977)!!))
        assertEquals("0:04", mediaClock(mediaDurationSeconds(4_600_000)!!))
        assertEquals("0:04", mediaClock(mediaDurationSeconds(4_048_937)!!))
        assertEquals("0:03", mediaClock(mediaDurationSeconds(3_999_400)!!))
    }

    @Test fun `an ended player is at the end of the file, as an HTML media element is, and else where it is`() {
        assertEquals(4.0, mediaElapsedSeconds(3999, ended = true, duration = 4.0), 0.0)
        assertEquals(3.999, mediaElapsedSeconds(3999, ended = false, duration = 4.0), 0.0)
        assertEquals(3.999, mediaElapsedSeconds(3999, ended = true, duration = null), 0.0)
        assertEquals(0.0, mediaElapsedSeconds(0, ended = false, duration = null), 0.0)
    }
}
