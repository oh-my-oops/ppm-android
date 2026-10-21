package com.hienlh.ppm.state

/**
 * What happens to a picked image before it is sent (`image-resize.ts`, `image-limits.ts`,
 * `image-animation.ts`). An image is billed by area on every later turn, and one past the API's
 * ceiling is refused — on that turn and every one after, since the transcript is replayed — so it
 * is reduced before it is uploaded, previewed, embedded or stored.
 */

/** Longest side, in pixels, at which the API starts refusing an image; measuring exactly this is already over. */
const val MAX_IMAGE_DIMENSION = 2000

/** Longest side an attachment is reduced to: under the ceiling, still legible for text. */
const val ATTACHMENT_MAX_DIMENSION = 1600

/** The image types the chat socket takes as an inline payload — and the ones the web previews. */
val INLINE_MEDIA_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

data class PixelSize(val width: Int, val height: Int)

/**
 * The size that brings an image under [max], or null when it already is: the longest side one
 * pixel below it, the other side in proportion and never below one pixel.
 */
fun fitWithin(width: Int, height: Int, max: Int = MAX_IMAGE_DIMENSION): PixelSize? {
    if (width <= 0 || height <= 0) return null
    val longest = maxOf(width, height)
    if (longest < max) return null
    val target = max - 1
    val scale = target.toDouble() / longest
    return PixelSize(
        width = if (width >= height) target else maxOf(1, Math.round(width * scale).toInt()),
        height = if (height > width) target else maxOf(1, Math.round(height * scale).toInt()),
    )
}

/** HEIC / HEIF: what a phone camera often saves, which the API does not take and this app can decode. */
fun isHeif(type: String): Boolean = type == "image/heic" || type == "image/heif"

/**
 * The type an image is re-encoded as: JPEG and WebP keep theirs, anything else becomes PNG — but a
 * HEIC photo becomes JPEG, where the web, which cannot decode one, sends it as a plain file.
 */
fun encodeType(type: String): String = when {
    type == "image/jpeg" || type == "image/webp" -> type
    isHeif(type) -> "image/jpeg"
    else -> "image/png"
}

/** Bytes read from the front of a file to look for animation markers. */
const val ANIMATION_HEADER_BYTES = 4096

/**
 * Whether an image carries more than one frame, read from its container rather than decoded: an
 * animated WebP declares `ANIM`, an animated PNG `acTL`. A GIF is taken as animated — a still one
 * cannot be told from a prefix, and they are rare.
 */
fun hasAnimationMarker(type: String, header: ByteArray): Boolean = when (type) {
    "image/gif" -> true
    "image/webp" -> header.containsAscii("ANIM")
    "image/png", "image/apng" -> header.containsAscii("acTL")
    else -> false
}

private fun ByteArray.containsAscii(needle: String): Boolean {
    val target = needle.toByteArray(Charsets.US_ASCII)
    outer@ for (i in 0..size - target.size) {
        for (j in target.indices) if (this[i + j] != target[j]) continue@outer
        return true
    }
    return false
}

/** What to do with a picked image (`downscaleImage`'s outcome, decided before any pixel is touched). */
sealed interface ResizePlan {
    /** Not measured, or of a type the socket refuses: sent by path only, never inline. */
    data object AsIs : ResizePlan

    /** Small enough and of a type the socket takes: sent inline unchanged. */
    data object Inlineable : ResizePlan

    /** Re-encoded as [type]: at [to], or at its own size when [to] is null (a small HEIC photo). Safe inline. */
    data class Encode(val to: PixelSize?, val type: String) : ResizePlan
}

/**
 * [size] is the image as it is seen (EXIF rotation applied), null when it could not be decoded.
 * An animation is never scaled — that would keep one frame — so it goes inline at its own size
 * while the API still takes it, and by path once it does not.
 */
fun planResize(type: String, size: PixelSize?, animated: Boolean, max: Int = ATTACHMENT_MAX_DIMENSION): ResizePlan {
    if (!type.startsWith("image/") || type == "image/svg+xml") return ResizePlan.AsIs
    if (size == null) return ResizePlan.AsIs
    // `fitWithin`'s bound is exclusive: an image measuring exactly the target is not re-encoded for one pixel.
    val to = fitWithin(size.width, size.height, max + 1)
    if (to != null && animated) {
        return if (maxOf(size.width, size.height) < MAX_IMAGE_DIMENSION && type in INLINE_MEDIA_TYPES) {
            ResizePlan.Inlineable
        } else {
            ResizePlan.AsIs
        }
    }
    return when {
        to != null -> ResizePlan.Encode(to, encodeType(type))
        type in INLINE_MEDIA_TYPES -> ResizePlan.Inlineable
        isHeif(type) -> ResizePlan.Encode(null, encodeType(type))
        else -> ResizePlan.AsIs
    }
}
