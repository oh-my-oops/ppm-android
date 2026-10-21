package com.hienlh.ppm.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lightbox's pan, zoom and turns, checked against what use-image-transform.ts gives. */
class LightboxTransformTest {
    /** The web's tablet: a 1500 × 400 panorama fitted into a 1280 × 588 stage. */
    private val band = StageLayout(1280f, 1280f * 400 / 1500, 1280f, 588f, 1500f)

    @Test
    fun `a picture larger than the stage is fitted, one smaller keeps its own size`() {
        val (w, h) = fittedSize(1500f, 400f, 1280f, 588f)
        assertEquals(1280f, w, 0.01f)
        assertEquals(341.33f, h, 0.01f)
        assertEquals(100f to 44f, fittedSize(100f, 44f, 1280f, 588f))
        val (tw, th) = fittedSize(300f, 1000f, 1280f, 588f)
        assertEquals(176.4f, tw, 0.01f)
        assertEquals(588f, th, 0.01f)
    }

    @Test
    fun `zooming holds the point under the fingers and the pan stays inside the limits`() {
        // The pinch the web was given: 280 px apart to 480, about the middle.
        val zoomed = ViewTransform().zoomedTo(480f / 280f, band)
        assertEquals(171, zoomed.percent)
        assertEquals(0f, zoomed.x, 0.001f)
        // About a point 200 to the right of the middle, that point stays where it was.
        val aside = ViewTransform().zoomedTo(2f, band, originX = 200f)
        assertEquals(-200f, aside.x, 0.001f)
        // 2x is 2560 wide in a 1280 stage: 640 either way, and no further.
        assertEquals(640f, aside.panned(10_000f, 0f, band).x, 0.001f)
        // Never past 0.1x or 20x.
        assertEquals(MAX_SCALE, ViewTransform().zoomedTo(400f, band).scale, 0f)
        assertEquals(MIN_SCALE, ViewTransform().zoomedTo(0.001f, band).scale, 0f)
    }

    @Test
    fun `a fitted picture cannot be panned`() {
        assertEquals(ViewTransform(), ViewTransform().panned(50f, -80f, band))
    }

    @Test
    fun `a quarter turn fits the turned picture, and fit takes it back after zooming`() {
        val turned = ViewTransform().rotated(90, band)
        assertEquals(90, turned.rotation)
        // 341.33 wide once turned is no problem; 1280 tall in 588 is: 588 / 1280.
        assertEquals(588f / 1280f, turned.scale, 0.0001f)
        assertEquals(270, ViewTransform().rotated(-90, band).rotation)
        assertEquals(0, ViewTransform(rotation = 270).rotated(90, band).rotation)
        val back = ViewTransform(scale = 4f, x = 300f).fitted(band)
        assertEquals(1f, back.scale, 0f)
        assertEquals(0f, back.x, 0f)
    }

    @Test
    fun `actual size is a pixel of the file per dp, and a double tap toggles`() {
        assertEquals(1500f / 1280f, ViewTransform().actualSize(band).scale, 0.0001f)
        val tapped = ViewTransform().doubleTapped(band, 100f, 0f)
        assertEquals(DOUBLE_TAP_SCALE, tapped.scale, 0f)
        assertEquals(-150f, tapped.x, 0.001f)
        assertEquals(1f, tapped.doubleTapped(band, 0f, 0f).scale, 0f)
    }

    @Test
    fun `flips and the readout`() {
        val flipped = ViewTransform().flipped(horizontal = true)
        assertTrue(flipped.flipX)
        assertFalse(flipped.flipped(horizontal = true).flipX)
        assertTrue(ViewTransform().flipped(horizontal = false).flipY)
        assertEquals(125, ViewTransform(scale = 1.25f).percent)
    }

    @Test
    fun `a whole notch on one axis is a wheel, anything else a trackpad`() {
        assertTrue(looksLikeMouseWheel(0f, 64f))
        assertTrue(looksLikeMouseWheel(0f, -100f))
        assertFalse(looksLikeMouseWheel(0f, 12f))
        assertFalse(looksLikeMouseWheel(0f, 63.5f))
        assertFalse(looksLikeMouseWheel(4f, 64f))
        assertEquals(0.808f, wheelZoom(64f), 0.001f)
    }
}
