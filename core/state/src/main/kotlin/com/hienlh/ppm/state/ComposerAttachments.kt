package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.InlineImage

/**
 * How the composer's attachments become one message: which images ride inline
 * (`image-resize-limits.ts`), which marker each gets (`attachment-marker-split.ts`) and the text
 * that carries them (`buildMessageWithAttachments` in chat-tab.tsx).
 */

/** What of an attachment the message is built from. */
interface SendableAttachment {
    /** Where the server saved it; null when the upload failed. */
    val serverPath: String?

    /** Its picture, when it may be sent inline. */
    val imageData: InlineImage?

    /** Text carried as is (a terminal's output), with nothing uploaded. */
    val textContent: String?
}

/**
 * Ceilings on the payloads one message carries. Past them an image is not refused: it goes by
 * path, as every attachment did before inlining existed.
 */
object InlineImageLimits {
    /** The socket refuses a message carrying more than five. */
    const val MAX_IMAGES = 4

    /** The socket refuses a payload above 7,000,000 base64 characters. */
    const val MAX_BASE64_PER_IMAGE = 5_000_000

    /** Across one message, well under the 16 MiB past which Bun drops the frame. */
    const val MAX_BASE64_TOTAL = 10_000_000
}

/**
 * The payloads that may ride on one message, in order, up to [InlineImageLimits]. One that does
 * not fit is skipped rather than ending the scan, so a large image does not keep out the small
 * ones after it.
 */
fun selectInlineImages(payloads: List<InlineImage?>): List<InlineImage> {
    val picked = mutableListOf<InlineImage>()
    var total = 0
    for (image in payloads) {
        if (image == null) continue
        if (picked.size >= InlineImageLimits.MAX_IMAGES) break
        if (image.data.length > InlineImageLimits.MAX_BASE64_PER_IMAGE) continue
        if (total + image.data.length > InlineImageLimits.MAX_BASE64_TOTAL) continue
        total += image.data.length
        picked += image
    }
    return picked
}

/** The uploaded paths a message names: the images whose pictures go with it, and the rest. */
data class AttachmentMarkers(val inlineImagePaths: List<String>, val pathOnlyPaths: List<String>)

/**
 * Which marker each uploaded attachment gets. An image is announced as included only when its
 * payload is among [inline] — the caps can leave one behind, and saying its contents are in the
 * message would give the model neither the picture nor a reason to open the file.
 */
fun splitAttachmentMarkers(attachments: List<SendableAttachment>, inline: List<InlineImage>): AttachmentMarkers {
    val inlinePaths = mutableListOf<String>()
    val pathOnly = mutableListOf<String>()
    for (attachment in attachments) {
        val path = attachment.serverPath ?: continue
        val image = attachment.imageData
        if (image != null && inline.any { it === image }) inlinePaths += path else pathOnly += path
    }
    return AttachmentMarkers(inlinePaths, pathOnly)
}

/**
 * [content] with its attachments ahead of it: text carried as is, then an inline marker per image
 * sent in the frame, then one marker for every file that goes by path. An image keeps its path even
 * when its picture rides along, because the transcript is what the chat draws its thumbnail from.
 */
fun messageWithAttachments(content: String, attachments: List<SendableAttachment>, inline: List<InlineImage>): String {
    if (attachments.isEmpty()) return content
    val parts = mutableListOf<String>()
    // `if (att.textContent)`: a chip with no text adds no part, however it came to have none.
    attachments.mapNotNullTo(parts) { it.textContent?.takeIf(String::isNotEmpty) }
    val markers = splitAttachmentMarkers(attachments, inline)
    markers.inlineImagePaths.mapTo(parts) { "[Attached image (contents included in this message): $it]" }
    val paths = markers.pathOnlyPaths
    if (paths.isNotEmpty()) {
        parts += if (paths.size == 1) "[Attached file: ${paths[0]}]" else "[Attached files:\n${paths.joinToString("\n")}\n]"
    }
    if (parts.isEmpty()) return content
    return parts.joinToString("\n\n") + "\n\n" + content
}
