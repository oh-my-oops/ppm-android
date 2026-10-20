package com.hienlh.ppm.chat

import androidx.compose.ui.unit.IntSize
import com.hienlh.ppm.state.ToolBlock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The image a Read card previews, laid out as image-preview-geometry.ts lays it out. */
class ToolImagePreviewTest {
    private fun call(name: String, path: String, requestId: String? = null) =
        ToolBlock("t1", name, buildJsonObject { put("file_path", path) }, requestId = requestId)

    @Test
    fun `only an absolute path to a picture is previewed, for Read and ImageGen calls`() {
        assertEquals("/tmp/ppm-e4b/a1.png", imageReadPath(call("Read", "/tmp/ppm-e4b/a1.png")))
        assertEquals("/tmp/Shot.PNG", imageReadPath(call("Read", "/tmp/Shot.PNG")))
        assertEquals("C:\\out\\x.webp", imageReadPath(call("ImageGen", "C:\\out\\x.webp")))
        assertNull(imageReadPath(call("Read", "docs/a.png")))
        // SVG is text to Read, and the raw route will not serve it.
        assertNull(imageReadPath(call("Read", "/tmp/logo.svg")))
        assertNull(imageReadPath(call("Read", "/tmp/notes.md")))
        assertNull(imageReadPath(call("Write", "/tmp/a.png")))
        assertNull(imageReadPath(call("Read", "/tmp/a.png", requestId = "r1")))
    }

    @Test
    fun `the tile follows the picture within the clamp`() {
        assertEquals(PreviewLayout(band = false, aspect = 1000.0 / 440, fit = ImageFit.COVER), previewLayout(IntSize(1000, 440), TILE_WIDTH))
        assertEquals(PreviewLayout(band = true, aspect = 3.0, fit = ImageFit.CONTAIN), previewLayout(IntSize(3000, 1000), TILE_WIDTH))
        assertEquals(PreviewLayout(band = false, aspect = 100.0 / 44, fit = ImageFit.NATIVE), previewLayout(IntSize(100, 44), TILE_WIDTH))
        assertEquals(PreviewLayout(band = false, aspect = TILE_MIN_RATIO, fit = ImageFit.CONTAIN), previewLayout(IntSize(400, 2000), TILE_WIDTH))
        // Narrower than the tile but taller than its clamped box: fitted, not shown 1:1.
        assertEquals(PreviewLayout(band = false, aspect = TILE_MIN_RATIO, fit = ImageFit.CONTAIN), previewLayout(IntSize(60, 300), TILE_WIDTH))
    }

    @Test
    fun `sizes and dimensions read as the web prints them`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1 KB", formatBytes(1024))
        assertEquals("2 KB", formatBytes(1536))
        assertEquals("40 KB", formatBytes(40_960))
        assertEquals("1.5 MB", formatBytes(1_572_864))
        assertEquals("1.3 MB", formatBytes(1_310_720))
        assertEquals("1000 × 440 px", dimensionsLabel(IntSize(1000, 440), ImageFit.COVER))
        assertEquals("100 × 44 px · shown 1:1", dimensionsLabel(IntSize(100, 44), ImageFit.NATIVE))
    }

    @Test
    fun `a preview is decoded small but never under 1024 px on its longest side`() {
        assertEquals(1, previewSampleSize(IntSize(1000, 440)))
        assertEquals(1, previewSampleSize(IntSize(2047, 100)))
        assertEquals(2, previewSampleSize(IntSize(2048, 1000)))
        assertEquals(4, previewSampleSize(IntSize(4096, 3000)))
    }

    @Test
    fun `a path is drawn as a right-to-left box draws it`() {
        // The leading slash goes to the right of the left-to-right run, as Chrome shows it.
        assertEquals("\u202Dtmp/ppm-e4b/a1.png/", rtlVisualOrder("/tmp/ppm-e4b/a1.png"))
        assertEquals("\u202DC:\\shots\\a.png", rtlVisualOrder("C:\\shots\\a.png"))
        assertEquals("\u202D/", rtlVisualOrder("/"))
    }
}
