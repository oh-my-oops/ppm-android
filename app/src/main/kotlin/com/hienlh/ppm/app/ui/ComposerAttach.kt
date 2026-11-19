package com.hienlh.ppm.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.hienlh.ppm.app.ComposerAttachment
import com.hienlh.ppm.app.onEveryTap
import com.hienlh.ppm.design.ColorsFade
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.GalleryImage
import com.hienlh.ppm.design.LocalImageViewer
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ViewerImage
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.chromeScrollbar
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssSize
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.isJsSpace
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the paperclip opens, as Chrome answers `<input type="file" multiple>` on Android: the
 * system chooser with the photo picker (several pictures at once), the camera, the camcorder and
 * Files. [onPicked] gets what came back.
 */
@Composable
fun rememberAttachPicker(onPicked: (List<Uri>) -> Unit): () -> Unit {
    val context = LocalContext.current
    // Where the camera was told to write: kept across the activity being recreated meanwhile.
    var photoPath by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val photo = photoPath?.let(::File)
        photoPath = null
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        // The camera writes where it was told and may answer with no data at all.
        if (photo != null && photo.length() > 0) {
            onPicked(listOf(Uri.fromFile(photo)))
            return@rememberLauncherForActivityResult
        }
        val data = result.data ?: return@rememberLauncherForActivityResult
        val clip = data.clipData
        val uris = if (clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } else listOfNotNull(data.data)
        if (uris.isNotEmpty()) onPicked(uris)
    }
    return {
        val photo = cameraFile(context)
        photoPath = photo.path
        launcher.launch(attachChooser(context, photo))
    }
}

/**
 * A fresh file for the camera to write, the last one's gone (it was copied into the message box).
 * Named as Chrome names its own (`<ms><random>.jpg`), which is the name the web's chip shows.
 */
private fun cameraFile(context: Context): File {
    val dir = File(context.cacheDir, "camera").apply {
        deleteRecursively()
        mkdirs()
    }
    return File.createTempFile(System.currentTimeMillis().toString(), ".jpg", dir)
}

private fun attachChooser(context: Context, photo: File): Intent {
    val photoUri = FileProvider.getUriForFile(context, "${context.packageName}.images", photo)
    val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        .putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // The chooser passes a grant on only for a URI in the clip.
        .apply { clipData = ClipData.newRawUri("", photoUri) }
    val camcorder = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
    val files = Intent(Intent.ACTION_GET_CONTENT)
        .setType("*/*")
        .addCategory(Intent.CATEGORY_OPENABLE)
        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    val extra = listOfNotNull(photoPickerIntent(), camera, camcorder).filter { it.resolveActivity(context.packageManager) != null }
    return Intent.createChooser(files, null).putExtra(Intent.EXTRA_INITIAL_INTENTS, extra.toTypedArray<Parcelable>())
}

/**
 * The system photo picker, where several pictures are ticked at once (Files picks one per tap
 * unless the user knows to long-press), or null before Android 13.
 */
internal fun photoPickerIntent(): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Intent(MediaStore.ACTION_PICK_IMAGES).putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
    } else {
        null
    }

/**
 * The text menu's Paste with a picture on the clipboard, as the web's `handlePaste` takes it: the
 * picture goes to [onPaste] to be attached, in place of the text Paste would have put in. Autofill
 * is left out, as Chrome offers none for a textarea.
 */
fun Modifier.pastesImages(context: Context, onPaste: (Uri) -> Unit): Modifier {
    val paste: TextContextMenuSession.() -> Unit = {
        close()
        clipboardImage(context)?.let(onPaste)
    }
    return filterTextContextMenuComponents {
        it.key != TextContextMenuKeys.AutofillKey && (it.key != TextContextMenuKeys.PasteKey || !clipboardHoldsImage(context))
    }
        .appendTextContextMenuComponents {
            if (clipboardHoldsImage(context)) item(ImagePaste, context.getString(android.R.string.paste), 0, paste)
        }
}

private object ImagePaste

/** Whether the clipboard holds a picture, read from its description, which raises no "pasted" notice. */
fun clipboardHoldsImage(context: Context): Boolean {
    val description = context.getSystemService(ClipboardManager::class.java)?.primaryClipDescription ?: return false
    return (0 until description.mimeTypeCount).any { description.getMimeType(it).startsWith("image/") }
}

/** The clipboard's first picture: Chrome hands a page that one file and nothing else. */
fun clipboardImage(context: Context): Uri? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    val resolver = context.contentResolver
    return (0 until clip.itemCount).firstNotNullOfOrNull { index ->
        clip.getItemAt(index).uri?.takeIf { runCatching { resolver.getType(it) }.getOrNull()?.startsWith("image/") == true }
    }
}

/**
 * The paperclip (`SQUARE_BUTTON`, `aria-label="Attach file"`): `size-8` on a phone, `size-[34px]`
 * on a tablet, `rounded-[10px] text-text-3 can-hover:hover:bg-surface-hover can-hover:hover:text-text`
 * with a 16 px (`size-4`) icon and `motion-safe:active:scale-[0.97]`.
 */
@Composable
fun AttachButton(mobile: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val reducedMotion = rememberPrefersReducedMotion()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val lit = if (!mobile && hovered) colorsFade(true).toDouble() else 0.0
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled && !reducedMotion) 0.97f else 1f,
        animationSpec = tween(if (reducedMotion) 0 else 150),
        label = "attachButtonScale",
    )
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .cssSize(if (mobile) 32.dp else 34.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            // `disabled:opacity-50`.
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(if (!mobile && hovered) tokens.surfaceHover.color else Color.Transparent)
            .pointerHoverIcon(WebCursor.Default)
            .webTitle("Attach file")
            .hoverable(source)
            .clickable(source, indication = null, enabled = enabled, onClickLabel = "Attach file", onClick = onClick)
            .semantics { contentDescription = "Attach file" },
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(ProductIcon.Paperclip, 16.dp, tokens.text.mix(tokens.text3, lit).color)
    }
}

/**
 * The files waiting in the box (`AttachmentChips`): `px-2 md:px-4 pt-2`, chips `gap-1.5` with
 * `gap-y-5` between wrapped rows — a touch screen's pointer is coarse, and the web keeps a
 * finger's target off the row below. A chip with a picture opens it in the lightbox, the others
 * waiting in the box beside it; one with text (terminal output, a selection) opens that text under
 * the row, and a second tap closes it. [onTextTap] is what such a tap, and one on the text, does
 * beyond that: the click bubbles on to the box's own `onClick`, which gives the message field the focus.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttachmentChips(attachments: List<ComposerAttachment>, inset: Dp, onRemove: (String) -> Unit, onTextTap: () -> Unit = {}) {
    if (attachments.isEmpty()) return
    var expandedId by remember { mutableStateOf<String?>(null) }
    val viewer = LocalImageViewer.current
    fun preview(attachment: ComposerAttachment) {
        val pictures = attachments.filter { it.previewable }
        val index = pictures.indexOfFirst { it.id == attachment.id }
        if (viewer == null || index < 0) return
        viewer.show(
            pictures.map { picture ->
                GalleryImage("composer:${picture.id}", picture.name) {
                    // The copy in the box now: a reduced one may have taken the picked one's place.
                    val current = attachments.firstOrNull { it.id == picture.id } ?: return@GalleryImage null
                    val file = current.file ?: return@GalleryImage null
                    withContext(Dispatchers.IO) { runCatching { ViewerImage(current.name, file.readBytes(), current.mediaType) }.getOrNull() }
                }
            },
            index,
        )
    }
    // The chip whose text is open, while it is still in the box.
    val opened = attachments.firstOrNull { it.id == expandedId }?.textContent?.takeIf { it.isNotEmpty() }
    Column(Modifier.fillMaxWidth().padding(start = inset, end = inset, top = 8.dp)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            for (attachment in attachments) {
                // A flex line's items stretch to its tallest, so a chip without a thumbnail is as tall as one with.
                AttachmentChip(
                    attachment, Modifier.fillMaxRowHeight(), expanded = attachment.id == expandedId,
                    onBody = {
                        when (chipBodyAction(attachment.textContent, attachment.previewable)) {
                            ChipBodyAction.EXPAND -> {
                                expandedId = if (expandedId == attachment.id) null else attachment.id
                                onTextTap()
                            }
                            ChipBodyAction.PREVIEW -> preview(attachment)
                            ChipBodyAction.NONE -> {}
                        }
                    },
                    onRemove = {
                        onRemove(attachment.id)
                        if (expandedId == attachment.id) expandedId = null
                    },
                )
            }
        }
        opened?.let { TextAttachmentPreview(stripCodeFence(it), onTextTap) }
    }
}

/** A picture this device can show: the web's `previewUrl`. */
private val ComposerAttachment.previewable get() = thumbnail != null && file != null

/** What a tap on a chip's body does. */
internal enum class ChipBodyAction { EXPAND, PREVIEW, NONE }

/**
 * `chipBodyAction`: a chip with text opens that text, one with a picture the picture, any other
 * nothing. The text is asked first, as the web asks it: a picture never has any.
 */
internal fun chipBodyAction(textContent: String?, previewable: Boolean): ChipBodyAction = when {
    !textContent.isNullOrEmpty() -> ChipBodyAction.EXPAND
    previewable -> ChipBodyAction.PREVIEW
    else -> ChipBodyAction.NONE
}

private val CODE_FENCE = Regex("```\\w*\n([\\s\\S]*?)\n```")

/** `stripCodeFence`: the text without the Markdown fence around all of it, as the opened chip shows it. */
internal fun stripCodeFence(text: String): String {
    val trimmed = text.trim(::isJsSpace)
    return CODE_FENCE.matchEntire(trimmed)?.groupValues?.get(1) ?: trimmed
}

/**
 * The text of an opened chip, under the row: `<pre class="mt-1.5 max-h-40 overflow-auto rounded-md
 * border border-border bg-background p-2 text-xs text-text-primary font-mono whitespace-pre-wrap
 * break-words">`.
 */
@Composable
private fun TextAttachmentPreview(text: String, onTap: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val scroll = rememberScrollState()
    SelectionContainer(
        Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .heightIn(max = 160.dp)
            .chromeScrollbar(scroll, vertical = true, tokens.border.color, border = 1.dp)
            .clip(shape)
            .background(tokens.bgSolid.color)
            .cssBorder(tokens.border.color, shape)
            .onEveryTap(onTap)
            .verticalScroll(scroll)
            .padding(8.dp),
    ) {
        BasicText(text, style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color))
    }
}

/**
 * `flex items-center gap-1.5 rounded-md border bg-surface px-2 py-1 text-xs text-text-secondary
 * max-w-48`, `hover:border-primary/50` when it opens something, `border-primary/50
 * bg-surface-elevated` while its text is [expanded]: a thumbnail or an icon (a terminal for text),
 * the name, a chevron for text, then a spinner, a red "!", or the size it was reduced to, and the
 * remove button. [onBody] is a tap anywhere on it but the remove button.
 */
@Composable
private fun AttachmentChip(attachment: ComposerAttachment, modifier: Modifier, expanded: Boolean, onBody: () -> Unit, onRemove: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val opens = chipBodyAction(attachment.textContent, attachment.previewable) != ChipBodyAction.NONE
    val hasText = !attachment.textContent.isNullOrEmpty()
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val lit = if (opens) colorsFade(hovered).toDouble() else 0.0
    val text = tokens.text2.color
    val border = tokens.accent.opacity(0.5)
    Row(
        modifier
            .widthIn(max = 192.dp)
            .clip(shape)
            .background((if (expanded) tokens.panel2 else tokens.panel).color)
            .pointerHoverIcon(if (opens) WebCursor.Pointer else WebCursor.Default)
            .hoverable(source)
            .clickable(source, indication = null, enabled = opens, onClick = onBody)
            .cssBorder((if (expanded) border else border.mix(tokens.border, lit)).color, shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (attachment.isImage && (attachment.thumbnail != null || !attachment.probed)) {
            // `size-5 rounded object-cover`: the box is there before the picture is decoded, as the <img> is.
            Box(
                Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .webTitle("Preview ${attachment.name}")
                    .semantics { contentDescription = "Preview ${attachment.name}" },
            ) {
                attachment.thumbnail?.let { Image(it, null, Modifier.size(20.dp), contentScale = ContentScale.Crop) }
            }
        } else {
            // A picture that would not decode has no preview, which the web draws as an image file.
            val icon = when {
                hasText -> ProductIcon.TerminalSquare
                attachment.isImage -> ProductIcon.Image
                else -> ProductIcon.FileText
            }
            PpmIcon(icon, 14.dp, tokens.text3.color)
        }
        BasicText(
            attachment.name,
            Modifier.weight(1f, fill = false),
            style = ppmText(12f, lineHeight = 16f).copy(color = text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (hasText) {
            // `size-3 transition-transform`, turned over while the text is open.
            val turn by animateFloatAsState(if (expanded) 180f else 0f, ColorsFade, label = "chevron")
            PpmIcon(ProductIcon.ChevronDown, 12.dp, tokens.text3.color, Modifier.rotate(turn))
        }
        when {
            attachment.status == ComposerAttachment.Status.UPLOADING ->
                Box(Modifier.height(16.dp), contentAlignment = Alignment.Center) { PpmIcon(ProductIcon.Loader2, 12.dp, tokens.text3.color, spinning = true) }
            attachment.status == ComposerAttachment.Status.ERROR ->
                BasicText("!", Modifier.webTitle("Upload failed"), style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color))
            attachment.resized != null -> {
                val (from, to) = attachment.resized
                // `text-[10px]`, the line height `text-xs` left behind as a ratio.
                BasicText(
                    "${to.width}×${to.height}",
                    Modifier.webTitle("Shrunk from ${from.width}x${from.height} to ${to.width}x${to.height}"),
                    style = ppmText(10f, lineHeight = 10f * 4f / 3f).copy(color = tokens.text3.color),
                    maxLines = 1,
                )
            }
        }
        RemoveButton(attachment.name, text, onRemove)
    }
}

/** `rounded-sm p-0.5 hover:bg-border/50`, a 12 px X in the chip's colour. */
@Composable
private fun RemoveButton(name: String, tint: Color, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val lit = if (pressed) 1f else colorsFade(hovered)
    Box(
        Modifier
            .clip(shape)
            .background(tokens.border.opacity(0.5).color.let { it.copy(alpha = it.alpha * lit) })
            .pointerHoverIcon(WebCursor.Default)
            .hoverable(source)
            .clickable(source, indication = null, onClickLabel = "Remove $name", onClick = onClick)
            .semantics { contentDescription = "Remove $name" }
            .padding(2.dp),
    ) {
        PpmIcon(ProductIcon.X, 12.dp, tint)
    }
}
