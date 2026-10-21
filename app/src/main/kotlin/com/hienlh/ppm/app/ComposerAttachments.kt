package com.hienlh.ppm.app

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.hienlh.ppm.client.deleteUpload
import com.hienlh.ppm.client.uploadFile
import com.hienlh.ppm.protocol.InlineImage
import com.hienlh.ppm.state.ANIMATION_HEADER_BYTES
import com.hienlh.ppm.state.INLINE_MEDIA_TYPES
import com.hienlh.ppm.state.InlineImageLimits
import com.hienlh.ppm.state.PixelSize
import com.hienlh.ppm.state.ResizePlan
import com.hienlh.ppm.state.SendableAttachment
import com.hienlh.ppm.state.hasAnimationMarker
import com.hienlh.ppm.state.isHeif
import com.hienlh.ppm.state.planResize
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A file in the message box (`ChatAttachment` in message-input.tsx). */
@Immutable
data class ComposerAttachment(
    val id: String,
    val name: String,
    /** A picture the web would preview (`previewUrl`): it gets a thumbnail and opens in the lightbox. */
    val isImage: Boolean,
    val status: Status,
    /** The copy on this device — already reduced, for an image — and its type. */
    val file: File? = null,
    val mediaType: String? = null,
    val thumbnail: ImageBitmap? = null,
    /** The picture has been decoded, or tried: a thumbnail still missing now will not come. */
    val probed: Boolean = false,
    override val serverPath: String? = null,
    override val imageData: InlineImage? = null,
    /** Its size before and after it was reduced, when it was. */
    val resized: Pair<PixelSize, PixelSize>? = null,
    override val textContent: String? = null,
) : SendableAttachment {
    enum class Status { UPLOADING, READY, ERROR }
}

/** Where picked files are copied while they wait in a message box. */
private fun attachmentsDir(context: Context) = File(context.cacheDir, "attachments")

/** Copies from a run that ended with the files still in the box; none of them can come back. */
internal fun clearAttachmentCopies(context: Context) {
    attachmentsDir(context).deleteRecursively()
}

/**
 * `processFiles`: each of [uris] into [tab]'s message box in turn — a chip at once, its thumbnail
 * as soon as the picture decodes, then the picture reduced, the file uploaded and the picture
 * read for sending inline when it may be. Nothing leaves a chip uploading: whatever fails marks
 * it failed instead. [raiseKeyboard] as [ChatTab.requestFocus] takes it.
 */
fun AppModel.attach(context: Context, tab: ChatTab, uris: List<Uri>, raiseKeyboard: Boolean = true) {
    val api = api ?: return
    val resolver = context.contentResolver
    val dir = attachmentsDir(context)
    scope.launch {
        for (uri in uris) {
            val id = UUID.randomUUID().toString()
            val (name, type) = withContext(Dispatchers.IO) { describe(resolver, uri) }
            // `isImageFile`, and the HEIC a phone's camera takes, which goes as a JPEG.
            val isImage = type in INLINE_MEDIA_TYPES || isHeif(type)
            tab.attachments += ComposerAttachment(id, name, isImage, ComposerAttachment.Status.UPLOADING)
            var replaced: File? = null
            val finish: (ComposerAttachment) -> ComposerAttachment = try {
                val copy = withContext(Dispatchers.IO) { copyIn(resolver, uri, name, File(dir, id)) }
                val probe = if (isImage) withContext(Dispatchers.IO) { Images.probe(copy) } else null
                tab.update(id) { it.copy(file = copy, mediaType = type, thumbnail = probe?.bitmap?.asImageBitmap(), probed = true) }
                // Only what the web would preview is reduced; any other file goes as it is, by path.
                val local = if (isImage) withContext(Dispatchers.IO) { reduce(copy, name, type, probe?.size) } else LocalAttachment(name, type, copy, inline = false, resized = null)
                replaced = local.replaces
                val path = try {
                    api.uploadFile(tab.project, local.name, local.type, local.file)?.path
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                val data = if (local.inline) withContext(Dispatchers.IO) { InlineImage(Base64.getEncoder().encodeToString(local.file.readBytes()), local.type) } else null
                val image = data?.takeIf { it.data.length <= InlineImageLimits.MAX_BASE64_PER_IMAGE }
                // A picture still goes inline when its upload failed; anything else has nothing left to send.
                val status = if (path != null || image != null) ComposerAttachment.Status.READY else ComposerAttachment.Status.ERROR
                // The chip keeps the name it was picked under, as the web's does.
                fun(chip: ComposerAttachment) = chip.copy(file = local.file, mediaType = local.type, serverPath = path, imageData = image, resized = local.resized, status = status)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                { it.copy(status = ComposerAttachment.Status.ERROR) }
            }
            // Removed while it was on its way: its upload is left, as the web leaves it, but not the copy here.
            if (!tab.update(id, finish)) withContext(Dispatchers.IO) { File(dir, id).deleteRecursively() }
            replaced?.let { withContext(Dispatchers.IO) { it.delete() } }
            tab.sendIfQueued()
        }
        // The keyboard comes up, as Chrome brings it for the web's textarea on a phone. (On a tablet
        // the web focuses the phone layout's hidden textarea instead, so nothing gets focus there.)
        tab.requestFocus(raiseKeyboard)
    }
}

/** [change] to the attachment [id] while it is still in the box; false once it was taken out. */
private fun ChatTab.update(id: String, change: (ComposerAttachment) -> ComposerAttachment): Boolean {
    val index = attachments.indexOfFirst { it.id == id }
    if (index < 0) return false
    attachments[index] = change(attachments[index])
    return true
}

/** Takes one file out of the box, and its upload off the server: uploads are never swept, since chats point at them for good. */
fun AppModel.removeAttachment(tab: ChatTab, id: String) {
    val attachment = tab.attachments.firstOrNull { it.id == id } ?: return
    tab.attachments.remove(attachment)
    attachment.file?.parentFile?.let { scope.launch(Dispatchers.IO) { it.deleteRecursively() } }
    val api = api
    if (api != null && attachment.serverPath != null) {
        scope.launch {
            try {
                api.deleteUpload(tab.project, attachment.serverPath)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // An orphaned file is not worth telling anyone about.
            }
        }
    }
    tab.sendIfQueued()
}

/** A picked file's name and type, as a browser's `File` has them. */
private fun describe(resolver: ContentResolver, uri: Uri): Pair<String, String> {
    val name = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    val type = resolver.getType(uri)
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"
    return name to type
}

/** A picked file once it is on this device in the form it will be sent. */
private class LocalAttachment(
    val name: String,
    val type: String,
    val file: File,
    /** May go inline: reduced, or small and of a type the socket takes (never `asis`). */
    val inline: Boolean,
    val resized: Pair<PixelSize, PixelSize>?,
    /** The larger original this was reduced from, which nothing needs once the chip has this. */
    val replaces: File? = null,
)

/** The picked file copied into [dir], under its own name. */
private fun copyIn(resolver: ContentResolver, uri: Uri, name: String, dir: File): File {
    dir.mkdirs()
    val file = File(dir, fileName(name))
    (resolver.openInputStream(uri) ?: error("unreadable")).use { input -> file.outputStream().use { input.copyTo(it) } }
    return file
}

/**
 * `downscaleImage` on the [original] copy, [size] being what it decoded to: reduced when it must
 * be. An image that will not decode goes as it is, by path only.
 */
private fun reduce(original: File, name: String, type: String, size: PixelSize?): LocalAttachment {
    val header = original.inputStream().use { it.readNBytesCompat(ANIMATION_HEADER_BYTES) }
    return when (val plan = planResize(type, size, animated = hasAnimationMarker(type, header))) {
        ResizePlan.AsIs -> LocalAttachment(name, type, original, inline = false, resized = null)
        ResizePlan.Inlineable -> LocalAttachment(name, type, original, inline = true, resized = null)
        is ResizePlan.Encode -> {
            // A plan to encode is only made for a picture that was measured.
            val from = checkNotNull(size)
            val target = plan.to ?: from
            val reducedName = if (plan.type == type) name else name.substringBeforeLast('.') + if (plan.type == "image/jpeg") ".jpg" else ".png"
            // Beside the original, which the chip shows until this one takes its place.
            val reduced = File(File(original.parentFile, "reduced").apply { mkdirs() }, fileName(reducedName))
            val encoded = try {
                Images.encode(original, target, plan.type, reduced)
            } catch (_: Exception) {
                false
            }
            if (!encoded) return LocalAttachment(name, type, original, inline = false, resized = null)
            LocalAttachment(reducedName, plan.type, reduced, inline = true, resized = plan.to?.let { from to it }, replaces = original)
        }
    }
}

/** A display name as a file name in this attachment's own directory, never a path out of it. */
private fun fileName(name: String): String = name.replace('/', '_').replace('\\', '_').takeUnless { it.isBlank() || it == "." || it == ".." } ?: "file"

private fun java.io.InputStream.readNBytesCompat(count: Int): ByteArray {
    val buffer = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = read(buffer, read, count - read)
        if (n < 0) break
        read += n
    }
    return buffer.copyOf(read)
}

/** Decoding as Chrome's `createImageBitmap` does it: EXIF rotation applied, so sizes are as the picture is seen. */
private object Images {
    /** Thumbnails are drawn 20 dp wide; this covers that at any density. */
    private const val THUMBNAIL_PX = 96

    class Probe(val size: PixelSize, val bitmap: Bitmap)

    /** The picture's size and a small copy of it; null when it will not decode. */
    fun probe(file: File): Probe? = try {
        thumbnail(file)
    } catch (_: Exception) {
        null
    }

    private fun thumbnail(file: File): Probe? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            var size: PixelSize? = null
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                size = PixelSize(info.size.width, info.size.height)
                val scale = THUMBNAIL_PX.toDouble() / minOf(info.size.width, info.size.height)
                if (scale < 1) decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            return size?.let { Probe(it, bitmap) }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val rotation = rotation(file)
        val size = if (rotation % 180 == 0) PixelSize(bounds.outWidth, bounds.outHeight) else PixelSize(bounds.outHeight, bounds.outWidth)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMBNAIL_PX) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return Probe(size, rotate(bitmap, rotation))
    }

    /** [file] at [size] as [type], quality 0.92, into [out]; false when it would not encode. */
    fun encode(file: File, size: PixelSize, type: String, out: File): Boolean {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                decoder.setTargetSize(size.width, size.height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val rotation = rotation(file)
            val longest = max(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longest / (sample * 2) >= max(size.width, size.height)) sample *= 2
            val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
            val upright = rotate(decoded, rotation)
            Bitmap.createScaledBitmap(upright, size.width, size.height, true)
        }
        val format = when (type) {
            "image/jpeg" -> Bitmap.CompressFormat.JPEG
            "image/webp" -> webp()
            else -> Bitmap.CompressFormat.PNG
        }
        val ok = out.outputStream().use { bitmap.compress(format, 92, it) }
        bitmap.recycle()
        // Some encoders answer with an empty file rather than a failure.
        return ok && out.length() > 0
    }

    /** Lossy WebP, as a canvas encodes it at a quality under 1. */
    @Suppress("DEPRECATION")
    private fun webp() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    private fun rotation(file: File): Int = try {
        when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
    } catch (_: Exception) {
        0
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }
}
