package com.hienlh.ppm.app.ui.files

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** PdfRenderer owns its descriptor; at most one page may be open, including while closing. */
internal class PdfPages private constructor(private val file: File, private val renderer: PdfRenderer) : AutoCloseable {
    data class Size(val width: Int, val height: Int)
    val sizes: List<Size> = List(renderer.pageCount) { index -> renderer.openPage(index).use { Size(it.width, it.height) } }
    private var closed = false

    /** Call on IO. Only visible pages have bitmaps; cap each allocation at 16MiB. */
    @Synchronized
    fun render(index: Int, requestedWidth: Int): Bitmap? {
        if (closed) return null
        val size = sizes[index]
        val factor = minOf(requestedWidth.toDouble() / size.width, 4096.0 / maxOf(size.width, size.height),
            sqrt(4_194_304.0 / size.width / size.height))
        val bitmap = Bitmap.createBitmap((size.width * factor).roundToInt().coerceAtLeast(1),
            (size.height * factor).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.WHITE)
            renderer.openPage(index).use { it.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try { renderer.close() } finally { file.delete() }
    }

    companion object {
        /** Worker thread only. Every failed constructor releases both descriptor and cache file. */
        fun open(cache: File, bytes: ByteArray): PdfPages {
            val dir = File(cache, "pdf-render").apply { mkdirs() }
            val file = File.createTempFile("page-", ".pdf", dir)
            var descriptor: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            try {
                file.writeBytes(bytes)
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(descriptor)
                return PdfPages(file, renderer)
            } catch (error: Throwable) {
                if (renderer != null) renderer.close() else descriptor?.close()
                file.delete()
                throw error
            }
        }
    }
}
