package com.hienlh.ppm.chat

import androidx.compose.runtime.staticCompositionLocalOf
import com.hienlh.ppm.design.GalleryImage
import com.hienlh.ppm.design.ViewerImage
import com.hienlh.ppm.protocol.ParsedUserMessage
import com.hienlh.ppm.state.ChatBlock
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.ToolBlock
import com.hienlh.ppm.state.isAgent

/**
 * What a tap on one of a chat's pictures opens the lightbox on (`collectGallery`): every picture
 * the chat shows, [self] among them, or [self] alone when it is not one of them.
 */
internal fun interface ChatGallery {
    fun open(self: GalleryImage)
}

/** How this chat's pictures open full size; none, and a tap on one does nothing. */
internal val LocalChatGallery = staticCompositionLocalOf<ChatGallery?> { null }

/** A picture the chat shows, by where it is; [key] is the lightbox's. */
internal sealed interface GalleryRef {
    val key: String
    val name: String

    /** An image sent with a message (`AuthImageThumbnail`). */
    data class Upload(val path: String) : GalleryRef {
        override val key get() = uploadImageKey(path)
        override val name get() = basename(path)
    }

    /** The image a call read (`ToolImagePreview`). */
    data class ToolRead(val toolUseId: String?, val path: String) : GalleryRef {
        override val key get() = toolImageKey(toolUseId, path)
        override val name get() = basename(path)
    }

    /** A picture an answer's markdown shows (`MdImage`), in the block [scope] names. */
    data class Markdown(val scope: String, val src: String, val alt: String) : GalleryRef {
        override val key get() = markdownImageKey(scope, src)
        override val name get() = alt.ifEmpty { basename(src) }
    }
}

/**
 * An upload's key. The web finds the picture it opens on by its blob URL, one per file for the
 * whole session, so the same file sent twice opens on its first appearance; keyed by path, so does this.
 */
internal fun uploadImageKey(path: String) = "upload:$path"

/** A tool card's: each card reads its own copy, so each is a picture of its own. */
internal fun toolImageKey(toolUseId: String?, path: String) = "tool:${toolUseId.orEmpty()}\u0000$path"

/**
 * `collectGallery` over the chat as the web draws it, which is every message, oldest first: a
 * message's images in its bubble's order, an answer's in the order of its blocks, the turn still
 * streaming in last. A tool card's picture counts only while the card is open ([isOpen]), as a
 * closed card has no `<img>` to collect. An answer's blocks are named as [AssistantBlocks] names
 * them for their markdown's pictures: the message's id, or `live`, and the block's place.
 */
internal fun galleryRefs(items: List<ChatItem>, live: List<ChatBlock>?, isOpen: (ToolBlock) -> Boolean): List<GalleryRef> {
    val refs = mutableListOf<GalleryRef>()
    fun answer(scope: String, blocks: List<ChatBlock>) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is ChatBlock.Text -> for (image in markdownImages(block.text)) {
                    if (!markdownImageBroken(image.url)) refs += GalleryRef.Markdown("$scope/$index", image.url, image.alt)
                }
                is ChatBlock.Tool -> {
                    val tool = block.tool
                    val path = if (tool.isAgent) null else imageReadPath(tool)
                    if (path != null && isOpen(tool)) refs += GalleryRef.ToolRead(tool.toolUseId, path)
                }
                else -> Unit
            }
        }
    }
    for (item in items) {
        when (item.role) {
            ChatItem.Role.USER -> for (path in ParsedUserMessage.parse(item.text).files) if (isImagePath(path)) refs += GalleryRef.Upload(path)
            ChatItem.Role.ASSISTANT -> answer(item.id, item.blocks)
            ChatItem.Role.SYSTEM -> Unit
        }
    }
    live?.let { answer(LIVE_SCOPE, it) }
    return refs
}

/** The scope of the turn still streaming in. */
internal const val LIVE_SCOPE = "live"

/** The pictures [text] shows, in the order the page draws them. */
internal fun markdownImages(text: String): List<InlineMarkdown.Image> {
    if (!text.contains("![")) return emptyList()
    val images = mutableListOf<InlineMarkdown.Image>()
    fun inline(source: String) {
        images += InlineMarkdown.parse(source).images
    }
    fun walk(blocks: List<MarkdownBlock>) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Paragraph -> inline(block.text)
                is MarkdownBlock.Heading -> inline(block.text)
                is MarkdownBlock.ListBlock -> block.items.forEach { walk(it.blocks) }
                is MarkdownBlock.Quote -> walk(block.blocks)
                is MarkdownBlock.Table -> (listOf(block.header) + block.rows).forEach { row -> row.forEach(::inline) }
                is MarkdownBlock.Code, MarkdownBlock.Rule, is MarkdownBlock.Html -> Unit
            }
        }
    }
    walk(Markdown.parse(text))
    return images
}

/** [ref] as the lightbox reads it: from what the chat has shown already when it can. */
internal fun galleryImage(ref: GalleryRef, uploads: ChatImages?, tools: ToolImages?, markdown: MarkdownImages?): GalleryImage = when (ref) {
    is GalleryRef.Upload -> GalleryImage(ref.key, ref.name) { uploads?.read(ref.path) }
    is GalleryRef.ToolRead -> GalleryImage(ref.key, ref.name) {
        val shown = toolImageShown(ref.toolUseId, ref.path)
            ?: (tools?.load(ref.path) as? ToolImage.Loaded)?.also { toolImageKept(ref.toolUseId, ref.path, it) }
        shown?.let { ViewerImage(ref.name, it.bytes, it.type) }
    }
    is GalleryRef.Markdown -> GalleryImage(ref.key, ref.name) {
        val shown = markdownImageShown(ref.src) ?: markdown?.read(ref.src) as? ToolImage.Loaded
        shown?.let { ViewerImage(ref.name, it.bytes, it.type) }
    }
}
