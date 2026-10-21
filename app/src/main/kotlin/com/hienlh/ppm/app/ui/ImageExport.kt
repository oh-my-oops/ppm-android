package com.hienlh.ppm.app.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.hienlh.ppm.design.ViewerImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/** The type [bytes] are, by their first bytes, for a file served without one. */
internal fun sniffImageType(bytes: ByteArray): String? {
    fun at(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xff) ?: -1
    fun ascii(from: Int, text: String) = text.indices.all { at(from + it) == text[it].code }
    return when {
        at(0) == 0x89 && ascii(1, "PNG") -> "image/png"
        at(0) == 0xff && at(1) == 0xd8 && at(2) == 0xff -> "image/jpeg"
        ascii(0, "GIF8") -> "image/gif"
        ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
        ascii(0, "BM") -> "image/bmp"
        ascii(4, "ftypavif") -> "image/avif"
        at(0) == 0 && at(1) == 0 && at(2) == 1 && at(3) == 0 -> "image/x-icon"
        else -> null
    }
}

private val EXTENSIONS = mapOf(
    "image/png" to "png",
    "image/jpeg" to "jpg",
    "image/gif" to "gif",
    "image/webp" to "webp",
    "image/bmp" to "bmp",
    "image/avif" to "avif",
    "image/x-icon" to "ico",
    "image/vnd.microsoft.icon" to "ico",
)

/**
 * The name a picture is saved under (`<a download={name}>`): Chrome keeps it, and adds the
 * extension of the file's type when it has none ("Band chart" served as PNG → "Band chart.png").
 */
internal fun downloadName(name: String, type: String?): String {
    val base = name.trim().ifEmpty { "image" }.replace(Regex("""[/\\:*?"<>|\u0000-\u001f]"""), "_")
    if (Regex("""\.[A-Za-z0-9]{1,5}$""").containsMatchIn(base)) return base
    val extension = type?.substringBefore(';')?.trim()?.lowercase()?.let(EXTENSIONS::get) ?: return base
    return "$base.$extension"
}

/** A size as Chrome's download message gives it: "6.85 KB", in powers of 1024. */
internal fun downloadSize(bytes: Long): String {
    val units = listOf("KB", "MB", "GB")
    if (bytes < 1024) return "$bytes B"
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.getDefault(), "%.2f %s", value, units[unit])
}

/** [type] for a file: what it was served as, else what its bytes say, else a plain stream. */
private fun typeOf(file: ViewerImage): String = file.type?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() } ?: sniffImageType(file.bytes) ?: "application/octet-stream"

/** Into Downloads, where Chrome puts a download: no permission needed from Android 10. */
private fun saveToDownloads(context: Context, name: String, type: String, bytes: ByteArray): Uri? {
    if (Build.VERSION.SDK_INT < 29) return null
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, type)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
    return runCatching {
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
    }.getOrElse {
        resolver.delete(uri, null, null)
        null
    }
}

/** A file saved: what it was called, its size, and how to open it. */
internal class SavedImage(val name: String, val size: Long, val open: () -> Unit)

/** The system's "Save to" sheet, for Android 9 and older, where Downloads needs a permission. */
private class CreateFile : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data.takeIf { resultCode == Activity.RESULT_OK }
}

/**
 * The lightbox's Download (`triggerDownload`): the file saved in Downloads as Chrome saves it,
 * then [onSaved] to say so. Before Android 10 the system asks where, as saving to Downloads
 * would need a storage permission.
 */
@Composable
internal fun rememberImageDownload(onSaved: (SavedImage) -> Unit): (ViewerImage) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by rememberUpdatedState(onSaved)
    var waiting by remember { mutableStateOf<ViewerImage?>(null) }
    val opener = { uri: Uri, type: String ->
        {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(view)
            } catch (_: ActivityNotFoundException) {
            }
        }
    }
    val create = rememberLauncherForActivityResult(CreateFile()) { uri ->
        val file = waiting ?: return@rememberLauncherForActivityResult
        waiting = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(file.bytes) } != null }.getOrDefault(false)
            }
            if (written) saved(SavedImage(downloadName(file.name, typeOf(file)), file.bytes.size.toLong(), opener(uri, typeOf(file))))
        }
    }
    return { file ->
        val type = typeOf(file)
        val name = downloadName(file.name, type)
        if (Build.VERSION.SDK_INT >= 29) {
            scope.launch {
                val uri = withContext(Dispatchers.IO) { saveToDownloads(context, name, type, file.bytes) }
                if (uri != null) saved(SavedImage(name, file.bytes.size.toLong(), opener(uri, type)))
            }
        } else {
            waiting = file
            create.launch(name to type)
        }
    }
}

/** The authority of the provider that hands copied pictures to the app they are pasted into. */
private fun imagesAuthority(context: Context) = "${context.packageName}.images"

/**
 * The lightbox's "Copy image" (`copyImageToClipboard`): as a PNG, the one type every app takes,
 * repainted when it is something else, as the web repaints it through a canvas. The clipboard
 * holds a file of this app's cache, which the app it is pasted into may read.
 */
internal suspend fun copyImage(context: Context, clipboard: Clipboard, file: ViewerImage): Boolean {
    val uri = withContext(Dispatchers.IO) {
        runCatching {
            val png = if (sniffImageType(file.bytes) == "image/png") {
                file.bytes
            } else {
                val bitmap = BitmapFactory.decodeByteArray(file.bytes, 0, file.bytes.size) ?: return@runCatching null
                ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            }
            val dir = File(context.cacheDir, "clipboard").apply { mkdirs() }
            // One copied picture at a time: the last one copied is the one the clipboard names.
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, downloadName(file.name.substringBeforeLast('.'), "image/png"))
            target.writeBytes(png)
            FileProvider.getUriForFile(context, imagesAuthority(context), target)
        }.getOrNull()
    } ?: return false
    clipboard.setClipEntry(ClipEntry(ClipData.newUri(context.contentResolver, file.name, uri)))
    return true
}
