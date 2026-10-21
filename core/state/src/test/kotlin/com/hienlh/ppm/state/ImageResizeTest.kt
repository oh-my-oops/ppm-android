package com.hienlh.ppm.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Case for case with `image-limits.test.ts`, `image-animation.test.ts` and `image-resize.test.ts`. */
class ImageResizeTest {
    @Test
    fun `leaves an image that is already under the cap alone`() {
        assertNull(fitWithin(1024, 768))
        assertNull(fitWithin(MAX_IMAGE_DIMENSION - 1, 10))
    }

    @Test
    fun `scales an image measuring exactly the cap`() {
        assertEquals(MAX_IMAGE_DIMENSION - 1, fitWithin(MAX_IMAGE_DIMENSION, 1000)!!.width)
    }

    @Test
    fun `brings the longest side strictly under the cap`() {
        for ((w, h) in listOf(4000 to 3000, 3000 to 4000, 2500 to 2500, 8000 to 100)) {
            val out = fitWithin(w, h)!!
            assertTrue(maxOf(out.width, out.height) < MAX_IMAGE_DIMENSION)
        }
    }

    @Test
    fun `keeps the aspect ratio within a pixel`() {
        val out = fitWithin(4000, 3000)!!
        assertTrue(abs(out.width.toDouble() / out.height - 4000.0 / 3000) < 0.01)
    }

    @Test
    fun `scales the taller side when the image is portrait`() {
        val out = fitWithin(1000, 5000)!!
        assertEquals(MAX_IMAGE_DIMENSION - 1, out.height)
        assertTrue(out.width < out.height)
    }

    @Test
    fun `a square lands square`() {
        assertEquals(PixelSize(MAX_IMAGE_DIMENSION - 1, MAX_IMAGE_DIMENSION - 1), fitWithin(5000, 5000))
    }

    @Test
    fun `never rounds a side down to zero`() {
        assertTrue(fitWithin(20000, 3)!!.height >= 1)
    }

    @Test
    fun `honours a custom cap`() {
        val out = fitWithin(4000, 2000, 1000)!!
        assertEquals(999, maxOf(out.width, out.height))
    }

    @Test
    fun `refuses nonsense dimensions rather than inventing them`() {
        assertNull(fitWithin(0, 100))
        assertNull(fitWithin(-5, 100))
    }

    @Test
    fun `the attachment target sits below the API ceiling`() {
        assertTrue(ATTACHMENT_MAX_DIMENSION < MAX_IMAGE_DIMENSION)
        assertNull(fitWithin(ATTACHMENT_MAX_DIMENSION, 900, ATTACHMENT_MAX_DIMENSION + 1))
    }

    @Test
    fun `encodeType keeps what a canvas re-encodes and makes the rest png`() {
        assertEquals("image/jpeg", encodeType("image/jpeg"))
        assertEquals("image/webp", encodeType("image/webp"))
        assertEquals("image/png", encodeType("image/gif"))
        assertEquals("image/png", encodeType("image/bmp"))
        assertEquals("image/png", encodeType("image/png"))
    }

    @Test
    fun `a HEIC photo is re-encoded as JPEG`() {
        assertEquals("image/jpeg", encodeType("image/heic"))
        assertEquals("image/jpeg", encodeType("image/heif"))
    }

    private fun bytes(vararg parts: String) = parts.joinToString("").toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `animation markers`() {
        assertTrue(hasAnimationMarker("image/gif", bytes("GIF89a")))
        assertTrue(hasAnimationMarker("image/webp", bytes("RIFF....WEBPVP8XANIM")))
        assertFalse(hasAnimationMarker("image/webp", bytes("RIFF....WEBPVP8 ")))
        assertTrue(hasAnimationMarker("image/png", bytes("\u0089PNG\r\n\u001a\n....IHDR....acTL")))
        assertFalse(hasAnimationMarker("image/png", bytes("\u0089PNG\r\n\u001a\n....IHDR....IDAT")))
        assertFalse(hasAnimationMarker("image/jpeg", bytes("ÿØÿ")))
        assertFalse(hasAnimationMarker("image/png", ByteArray(0)))
    }

    @Test
    fun `an image that cannot be measured goes by path`() {
        assertEquals(ResizePlan.AsIs, planResize("image/png", null, animated = false))
    }

    @Test
    fun `an SVG has no pixels to measure`() {
        assertEquals(ResizePlan.AsIs, planResize("image/svg+xml", PixelSize(4000, 3000), animated = false))
    }

    @Test
    fun `scales an oversized image`() {
        val plan = planResize("image/png", PixelSize(4000, 3000), animated = false)
        assertTrue(plan is ResizePlan.Encode)
        val to = (plan as ResizePlan.Encode).to
        assertNotNull(to)
        assertTrue(maxOf(to!!.width, to.height) <= ATTACHMENT_MAX_DIMENSION)
        assertEquals("image/png", plan.type)
    }

    @Test
    fun `leaves a small image of an accepted type alone, but marks it sendable`() {
        assertEquals(ResizePlan.Inlineable, planResize("image/png", PixelSize(800, 600), animated = false))
    }

    @Test
    fun `a small image of a type the socket refuses stays path-only`() {
        assertEquals(ResizePlan.AsIs, planResize("image/bmp", PixelSize(800, 600), animated = false))
    }

    @Test
    fun `an image exactly at the target is not re-encoded`() {
        assertEquals(ResizePlan.Inlineable, planResize("image/png", PixelSize(ATTACHMENT_MAX_DIMENSION, 900), animated = false))
    }

    @Test
    fun `an animation is never scaled`() {
        assertEquals(ResizePlan.Inlineable, planResize("image/gif", PixelSize(1800, 900), animated = true))
        assertEquals(ResizePlan.AsIs, planResize("image/gif", PixelSize(MAX_IMAGE_DIMENSION, 900), animated = true))
    }

    @Test
    fun `a HEIC photo becomes JPEG at any size`() {
        assertEquals(ResizePlan.Encode(null, "image/jpeg"), planResize("image/heic", PixelSize(800, 600), animated = false))
        val big = planResize("image/heic", PixelSize(4032, 3024), animated = false) as ResizePlan.Encode
        assertEquals("image/jpeg", big.type)
        assertEquals(ATTACHMENT_MAX_DIMENSION, big.to!!.width)
    }
}
