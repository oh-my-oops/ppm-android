package com.hienlh.ppm.app.ui.projects

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Web resize-image.ts: source <=10 MiB, center crop, 128 square, WebP quality 85. */
internal fun projectAvatarWebp(resolver: ContentResolver, uri: Uri): ByteArray {
    require(resolver.getType(uri)?.startsWith("image/") == true) { "Please choose an image file" }
    val limit = 10 * 1024 * 1024
    val bytes = requireNotNull(resolver.openInputStream(uri)) { "Cannot read image" }.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "Image too large (max 10MB)" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    val orientation = runCatching {
        ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Cannot decode image" }
    val cropSide = minOf(bounds.outWidth, bounds.outHeight)
    val options = BitmapFactory.Options().apply {
        inSampleSize = 1
        while (cropSide / (inSampleSize * 2) >= 128) inSampleSize *= 2
    }
    // Decode the crop, not the full panorama: sampling from the longest dimension can
    // destroy the only pixels retained by the web's center crop (e.g. 4096 x 256).
    // Region decoding also avoids allocating the discarded sides of very wide images.
    @Suppress("DEPRECATION")
    val region = runCatching { BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) }.getOrNull()
    val input = if (region != null) {
        try {
            val left = (bounds.outWidth - cropSide) / 2
            val top = (bounds.outHeight - cropSide) / 2
            requireNotNull(region.decodeRegion(Rect(left, top, left + cropSide, top + cropSide), options)) { "Cannot decode image" }
        } finally { region.recycle() }
    } else {
        // GIF and other formats without region support still use their first frame.
        requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "Cannot decode image" }
    }
    val target = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
    try {
        val side = minOf(input.width, input.height)
        val left = (input.width - side) / 2
        val top = (input.height - side) / 2
        Canvas(target).drawBitmap(input, Rect(left, top, left + side, top + side), RectF(0f, 0f, 128f, 128f), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        val oriented = Bitmap.createBitmap(target, 0, 0, 128, 128, matrix, true)
        try {
            return ByteArrayOutputStream().use {
                @Suppress("DEPRECATION")
                check(oriented.compress(Bitmap.CompressFormat.WEBP, 85, it)) { "Encode failed" }
                it.toByteArray()
            }
        } finally { if (oriented !== target) oriented.recycle() }
    } finally { input.recycle(); target.recycle() }
}
