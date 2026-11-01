package com.hienlh.ppm.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.ClampedText
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CssRow
import com.hienlh.ppm.design.CssText
import com.hienlh.ppm.design.CursorText
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.GalleryImage
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.TextCursorRegion
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.ViewerImage
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.chromeScrollbar
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderInset
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.cssIcon
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.ChatReply
import com.hienlh.ppm.protocol.ParsedUserMessage
import com.hienlh.ppm.protocol.ReplyReference
import com.hienlh.ppm.protocol.VersionGroup
import java.time.Instant
import kotlin.math.ceil

/**
 * Where a reply card's message is (`replyAvailable` / `onJumpToReply`): a jump that shows it, or
 * null when this chat does not hold it.
 */
fun interface ReplyTargets {
    fun jump(reply: ReplyReference): (() -> Unit)?

    companion object {
        val None = ReplyTargets { null }
    }
}

/** The images sent with a chat's messages, read with the server's credentials (`useAuthBlob`). */
interface ChatImages {
    /** The picture, for its thumbnail; null when it will not load. */
    suspend fun load(path: String): ImageBitmap?

    /** Its file, for the lightbox; null when it will not load. */
    suspend fun read(path: String): ViewerImage?
}

/** How this chat reads its images. None, and every thumbnail shows its broken-image icon. */
val LocalChatImages = staticCompositionLocalOf<ChatImages?> { null }

/** Tailwind's `shadow-sm`. */
private val shadowSm = listOf(
    ThemeShadow(0.0, 1.0, 3.0, 0.0, Rgba(0.0, 0.0, 0.0, 0.1)),
    ThemeShadow(0.0, 1.0, 2.0, -1.0, Rgba(0.0, 0.0, 0.0, 0.1)),
)

/** `text-sm`'s line ratio, which a child that sets only a size (`text-[11px]`) keeps. */
private const val SM_LINE = 20f / 14f

/** `text-xs`'s, for what sits inside a `text-xs` box. */
private const val XS_LINE = 16f / 12f

/** Tailwind's `sky-500`, `sky-600` and `sky-400`, as Chrome draws their `oklch()` in sRGB. */
val sky500 = Rgba(0.0, 165.69 / 255, 244.07 / 255, 1.0)
val sky600 = Rgba(0.0, 132.13 / 255, 209.05 / 255, 1.0)
val sky400 = Rgba(0.0, 187.79 / 255, 1.0, 1.0)

/** `IMAGE_EXTS` in message-media.tsx. */
private val IMAGE_EXTENSIONS = setOf(".png", ".jpg", ".jpeg", ".gif", ".webp")

internal fun isImagePath(path: String): Boolean {
    val dot = path.lastIndexOf('.')
    return dot >= 0 && path.substring(dot).lowercase() in IMAGE_EXTENSIONS
}

/** `basename` in lib/utils.ts, which splits at either slash. */
private fun fileName(path: String): String = path.split('/', '\\').last()

/**
 * Edit, Fork and the versions on a user's message (`onEdit`, `onFork`, `isEditing`,
 * `versionGroup`, `onNavigateVersion`, `versionNavDisabled`). Edit and Fork are null while a turn
 * runs, which leaves them out, as the web does; [onSwitchVersion] is null where the chat has no
 * versions to go to.
 */
class UserMessageEdits(
    val onEdit: (() -> Unit)?,
    val onFork: (() -> Unit)?,
    /** This is the message the next send replaces. */
    val editing: Boolean,
    /** The versions of this message, when it has any. */
    val versions: VersionGroup?,
    val onSwitchVersion: ((String) -> Unit)?,
    val versionsDisabled: Boolean,
)

/**
 * The user's message (`message-user-bubble.tsx`): what the composer and the runtime wrapped
 * around it taken apart into a reply card, badges and chips, then the text, two lines until
 * "Show more", and its versions when it was edited. What the runtime put there on its own (a
 * task's result, a command's caveat) is drawn faint and full width, with no action bar.
 */
@Composable
internal fun UserBubble(
    content: String,
    timestamp: Instant?,
    replies: ReplyTargets,
    modifier: Modifier = Modifier,
    onReply: (() -> Unit)? = null,
    edits: UserMessageEdits? = null,
) {
    val tokens = LocalPpmTokens.current
    val parsed = remember(content) { ParsedUserMessage.parse(content) }
    val system = parsed.isSystemContext
    val rad = tokens.rad.dp
    val shape = bubbleShape(system, rad)
    val uriHandler = LocalUriHandler.current
    val linked = if (system || parsed.text.isEmpty()) null else remember(parsed.text, tokens, uriHandler) { linkedUserText(parsed.text, tokens, uriHandler) }
    // How wide the text is exactly: the bubble around it is that and its padding, as Chrome sizes it.
    val textWidth = if (linked == null) Float.NaN else rememberExactTextWidth(linked, BUBBLE_TEXT)
    // `flex flex-col gap-1`, stacked in Chrome's fractions: 4 px is 10.5 at 2.625x.
    CssColumn(modifier.fillMaxWidth(), gap = 4.dp, alignEnd = !system) {
        // As wide as what it holds, as a box in a flex column is, up to 80%: the text's exact width
        // while it is the widest thing in it (measured, it is laid out to its next pixel).
        var bubble = if (system) {
            Modifier.fillMaxWidth()
        } else {
            Modifier.fitWidthAtEnd(0.8f) { measured ->
                val border = cssBorderPx(1.dp)
                if (textWidth.isNaN() || measured > ceil(textWidth).toInt() + 2 * (border + 12.dp.roundToPx())) Float.NaN
                else textWidth + 2 * (border + 12.dp.toPx())
            }
        }
        for (shadow in shadowSm) bubble = bubble.ppmShadow(shadow, shape)
        // `ring-2 ring-primary/60 border-primary/40` on the message an edit replaces, faded in and
        // out as `transition-all` fades them: the ring grows from nothing, over the shadow.
        val editing = colorsFade(edits?.editing == true)
        if (editing > 0f) bubble = bubble.ring(2.dp * editing, tokens.accent.opacity(0.6 * editing).color, shape) { bubbleShape(system, rad, it) }
        val fill = if (system) tokens.panel.opacity(0.4) else tokens.accentWash
        val stroke = (if (system) tokens.border.opacity(0.4) else tokens.accentWashBorder).let { tokens.accent.opacity(0.4).mix(it, editing.toDouble()) }
        CssColumn(bubble.background(fill.color, shape).cssBorder(stroke.color, shape).padding(horizontal = 12.dp), top = 8.dp, bottom = 8.dp) {
            parsed.replyTo?.let { ReplyCard(it, replies.jump(it), Modifier.padding(bottom = 8.dp)) }
            if (parsed.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (tag in parsed.tags) {
                        if (tag.name == "task-notification") TaskNotificationBadge(tag) else SystemTagBadge(tag)
                    }
                }
            }
            parsed.agent?.let { agent ->
                // `dark:` is the device's dark mode on the web, not PPM's theme.
                val color = if (isSystemInDarkTheme()) sky400 else sky600
                LabelChip(agent, sky500.opacity(0.15), sky500.opacity(0.25), color) { PpmIcon(ProductIcon.Bot, 12.dp, it) }
            }
            parsed.command?.let { command ->
                LabelChip(command.name, tokens.accent.opacity(0.15), tokens.accent.opacity(0.2), tokens.accent) { PpmIcon(LucideIcon.Slash, 12.dp, it) }
            }
            parsed.idePath?.let { path ->
                CssColumn(bottom = 4.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BasicText("Opened in IDE:", style = ppmText(11f, lineHeight = 11f * SM_LINE).copy(color = tokens.text3.color))
                        FilePathChip(path, lineHeight = 10f * SM_LINE)
                    }
                }
            }
            if (parsed.files.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (path in parsed.files) if (isImagePath(path)) ImageChip(path) else FileChip(path)
                }
            }
            if (parsed.terminalBlocks.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (block in parsed.terminalBlocks) TerminalBlockPreview(block)
                }
            }
            if (parsed.text.isNotEmpty()) BubbleText(parsed.text, system, linked)
            val versions = edits?.versions
            val switch = edits?.onSwitchVersion
            if (!system && versions != null && switch != null) VersionSwitcher(versions, edits.versionsDisabled, switch)
        }
        if (!system) {
            MessageActionBar(timestamp, remember(content) { ChatReply.decode(content).content }, onReply = onReply, alignEnd = true) {
                edits?.onEdit?.let { ActionButton(ProductIcon.Pencil, "Edit", "Edit this message (continue in the same tab)", onClick = it) }
                edits?.onFork?.let { ActionButton(ProductIcon.RotateCcw, "Fork", "Retry from this message (fork into a new tab)", onClick = it) }
            }
        }
    }
}

/** The bubble's corners, or a context message's, each [grow] wider: a spread shadow's, as CSS rounds one. */
private fun bubbleShape(system: Boolean, rad: Dp, grow: Dp = 0.dp): Shape =
    if (system) RoundedCornerShape(12.dp + grow) else RoundedCornerShape(topStart = rad + grow, topEnd = rad + grow, bottomEnd = 4.dp + grow, bottomStart = rad + grow)

/**
 * Tailwind's `ring-[width]`: a band [width] wide around the border edge (a spread shadow with no
 * blur), drawn over the shadows before it and only outside the box, its corners [grown] by the
 * band as CSS grows a spread shadow's radii.
 */
private fun Modifier.ring(width: Dp, color: Color, shape: Shape, grown: (Dp) -> Shape): Modifier = drawBehind {
    val spread = width.toPx()
    val outer = Path().apply {
        addOutline(grown(width).createOutline(Size(size.width + 2 * spread, size.height + 2 * spread), layoutDirection, this@drawBehind))
        translate(Offset(-spread, -spread))
    }
    val inner = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
    clipPath(inner, ClipOp.Difference) { drawPath(outer, color) }
}

/**
 * `‹ n/m ›` under a message that has edited versions (`VersionSwitcher`): `mt-1 gap-1 text-xs
 * text-text-subtle tabular-nums`, each chevron a `p-0.5` button. One with no version that way, or
 * any while a turn runs, is at 30%. Nothing for a message with one version.
 */
@Composable
private fun VersionSwitcher(group: VersionGroup, disabled: Boolean, onSwitch: (String) -> Unit) {
    if (group.ids.size < 2) return
    val tokens = LocalPpmTokens.current
    val index = group.currentIndex
    // 18 px tall, 47.25 at 2.625x, and 4 px under the text, both kept exact.
    CssColumn(top = 4.dp) {
        CssRow(gap = 4.dp) {
            VersionStep(ProductIcon.ChevronLeft, "Previous version", enabled = !disabled && index > 0) { group.ids.getOrNull(index - 1)?.let(onSwitch) }
            CssText("${index + 1}/${group.ids.size}", ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color, fontFeatureSettings = "tnum"))
            VersionStep(ProductIcon.ChevronRight, "Next version", enabled = !disabled && index < group.ids.lastIndex) { group.ids.getOrNull(index + 1)?.let(onSwitch) }
        }
    }
}

/**
 * One of the switcher's chevrons: `rounded p-0.5 hover:text-text-primary disabled:opacity-30`,
 * lit at once (no `transition-colors`), while pressed too, and under a mouse even when disabled,
 * as CSS's `:hover` still matches it. Tailwind 4 gives a button the arrow, enabled or not.
 */
@Composable
private fun VersionStep(icon: ProductIcon, label: String, enabled: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(instant = true)
    val pressed by hover.source.collectIsPressedAsState()
    val color = (if (hover.lit > 0f || (pressed && enabled)) tokens.text else tokens.text3).color
    CssRow(
        Modifier
            .alpha(if (enabled) 1f else 0.3f)
            .clip(RoundedCornerShape(4.dp))
            .semantics { contentDescription = label }
            .ppmClickable(enabled = enabled, pressedColor = Color.Transparent, source = hover.source, hoverWhileDisabled = true, onClick = onClick),
        start = 2.dp,
        end = 2.dp,
        top = 2.dp,
        bottom = 2.dp,
    ) {
        PpmIcon(icon, 14.dp, color, Modifier.cssIcon(14.dp))
    }
}

/** The bubble's `text-sm` (14 px on 20) text. */
private val BUBBLE_TEXT = ppmText(14f, lineHeight = 20f)

/**
 * The text, two lines until "Show more"; then up to half the window, scrolling. A user's text
 * comes [linked] ([linkedUserText]), as the bubble measured it.
 */
@Composable
private fun BubbleText(text: String, system: Boolean, linked: AnnotatedString?) {
    val tokens = LocalPpmTokens.current
    var expanded by remember(text) { mutableStateOf(false) }
    var clamped by remember(text) { mutableStateOf(false) }
    // `max-h-[50vh]` once expanded.
    val expandedLimit = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height / 2).toDp() }
    val style = BUBBLE_TEXT.copy(color = (if (system) tokens.text2 else tokens.text).color)
    val (annotated, inline) = if (system || linked == null) pathChips(text) else linked to emptyMap<String, InlineTextContent>()
    // The bubble is as wide as a user's text exactly, a fraction under the pixels it lays out in.
    val slack = if (system) Modifier else Modifier.exactTextSlack()
    WithoutOnCopy(if (system) "" else userTextMarks(text)) {
        // Counted at its lines, 52.5 px each at 2.625x where Compose makes 53, and at most half the window.
        SelectionContainer(Modifier.cssLines(20f, max = if (expanded) expandedLimit else Dp.Unspecified)) {
            // `select-text`: the I-beam over the glyphs, the arrow after a short line, and the chip's own hand.
            TextCursorRegion {
                if (expanded) {
                    CursorText(annotated, slack.heightIn(max = expandedLimit).cssScrollY(), style, inlineContent = inline)
                } else {
                    ClampedText(annotated, style, maxLines = 2, slack, preWrap = true, inlineContent = inline, onClamped = { clamped = it })
                }
            }
        }
    }
    if (expanded || clamped) {
        // `text-primary/70 hover:text-primary`, or `text-text-subtle hover:text-text-secondary` for context.
        val hover = rememberHover()
        val lit = hover.lit.toDouble()
        val color = (if (system) tokens.text2.mix(tokens.text3, lit) else tokens.accent.mix(tokens.accent.opacity(0.7), lit)).color
        // `mt-1`: 10.5 px at 2.625x, which padding would make 11.
        CssColumn(top = 4.dp) {
            Row(
                Modifier.ppmClickable(pressedColor = Color.Transparent, source = hover.source) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PpmIcon(if (expanded) ProductIcon.ChevronUp else ProductIcon.ChevronDown, 12.dp, color)
                BasicText(if (expanded) "Show less" else "Show more", style = ppmText(12f, lineHeight = 16f).copy(color = color))
            }
        }
    }
}

/** `TextWithFilePaths`: each absolute path of two segments or more drawn as its chip, in the line. */
private val ABSOLUTE_PATH = Regex("""/(?:[\w.\-]+/)+[\w.\-]+""")

@Composable
private fun pathChips(text: String): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val tokens = LocalPpmTokens.current
    return remember(text, density, tokens) {
        val inline = HashMap<String, InlineTextContent>()
        val annotated = buildAnnotatedString {
            var last = 0
            ABSOLUTE_PATH.findAll(text).forEachIndexed { index, match ->
                append(text, last, match.range.first)
                val id = "path-$index"
                appendInlineContent(id, match.value)
                val label = measurer.measure(fileName(match.value), filePathStyle(10f * SM_LINE), maxLines = 1).size.width
                with(density) {
                    // Borders, padding, both icons and both gaps around the name, which stops at 240.
                    val width = 40.dp.toPx() + minOf(label.toFloat(), 240.dp.toPx())
                    val height = (6f + 10f * SM_LINE).dp.toPx()
                    inline[id] = InlineTextContent(Placeholder(width.toSp(), height.toSp(), PlaceholderVerticalAlign.TextCenter)) {
                        FilePathChip(match.value, lineHeight = 10f * SM_LINE, Modifier.fillMaxSize())
                    }
                }
                last = match.range.last + 1
            }
            append(text, last, text.length)
        }
        annotated to inline
    }
}

/** `font-mono text-[10px]`, its line [lineHeight] tall. */
private fun filePathStyle(lineHeight: Float) = ppmText(10f, kind = FontKind.MONO, lineHeight = lineHeight)

/**
 * `FilePathChip`: a path by its name, `rounded border-border/50 bg-surface/50 font-mono
 * text-[10px] text-text-secondary`, and a button (`cursor-pointer`) that opens the file in an
 * editor tab. `hover:text-text-primary hover:bg-surface` fades in, and shows while a finger is on
 * it. [lineHeight] is what the box it sits in makes of 10 px.
 */
@Composable
private fun FilePathChip(path: String, lineHeight: Float, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val tabs = LocalChatTabs.current
    val hover = rememberHover()
    val pressed by hover.source.collectIsPressedAsState()
    val lit = if (pressed) 1.0 else hover.lit.toDouble()
    val shape = RoundedCornerShape(4.dp)
    val color = tokens.text.mix(tokens.text2, lit).color
    Row(
        modifier
            .clip(shape)
            .background(tokens.panel.mix(tokens.panel.opacity(0.5), lit).color)
            // In a line of text the name sits inside a selectable container, which gives its glyphs an I-beam: the hand wins.
            .pointerHoverIcon(WebCursor.Pointer, overrideDescendants = true)
            .ppmClickable(pressedColor = Color.Transparent, source = hover.source) { tabs?.openFilePath(path) }
            .cssBorder(tokens.border.opacity(0.5).color, shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PpmIcon(ProductIcon.FileText, 10.dp, color)
        BasicText(
            fileName(path),
            Modifier.widthIn(max = 240.dp),
            style = filePathStyle(lineHeight).copy(color = color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        PpmIcon(ProductIcon.ExternalLink, 8.dp, color.copy(alpha = color.alpha * 0.5f))
    }
}

/** The agent's and the slash command's chip: `rounded-md border px-2 py-0.5 text-xs font-medium`, `mb-0.5` under it. */
@Composable
private fun LabelChip(label: String, fill: Rgba, stroke: Rgba, color: Rgba, icon: @Composable (Color) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .padding(bottom = 2.dp)
            .background(fill.color, shape)
            .cssBorder(stroke.color, shape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon(color.color)
        BasicText(label, style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = color.color))
    }
}

/** An attachment's box: `rounded-md border-border/60 bg-background/40 px-1.5 py-0.5 text-[11px]`. */
@Composable
private fun AttachmentChip(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    // An image's is a button: `hover:bg-surface transition-colors cursor-pointer`, lit while a finger is on it too.
    val press = if (onClick == null) {
        Modifier
    } else {
        Modifier.pointerHoverIcon(WebCursor.Pointer).ppmClickable(pressedColor = tokens.panel.color, hoverColor = tokens.panel.color, onClick = onClick)
    }
    Row(
        modifier
            .clip(shape)
            .background(tokens.bgSolid.opacity(0.4).color)
            .then(press)
            .cssBorder(tokens.border.opacity(0.6).color, shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
private fun AttachmentName(path: String) {
    BasicText(
        fileName(path),
        Modifier.widthIn(max = 128.dp),
        style = ppmText(11f, lineHeight = 11f * SM_LINE).copy(color = LocalPpmTokens.current.text2.color),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A file that is not an image: its icon and name. */
@Composable
private fun FileChip(path: String) {
    AttachmentChip {
        PpmIcon(ProductIcon.FileText, 12.dp, LocalPpmTokens.current.text2.color)
        AttachmentName(path)
    }
}

private sealed interface Thumbnail {
    data object Loading : Thumbnail
    data object Failed : Thumbnail
    data class Loaded(val image: ImageBitmap) : Thumbnail
}

/**
 * `AuthImageThumbnail`: a 16 px picture of the image in place of the icon, a pulsing square while
 * it loads and the image icon if it will not. Once loaded, a tap opens it in the lightbox.
 */
@Composable
private fun ImageChip(path: String) {
    val tokens = LocalPpmTokens.current
    val images = LocalChatImages.current
    val gallery = LocalChatGallery.current
    val thumbnail by produceState<Thumbnail>(Thumbnail.Loading, path, images) {
        value = images?.load(path)?.let(Thumbnail::Loaded) ?: Thumbnail.Failed
    }
    val open: () -> Unit = {
        if (thumbnail is Thumbnail.Loaded && images != null) {
            gallery?.open(GalleryImage(uploadImageKey(path), fileName(path)) { images.read(path) })
        }
    }
    AttachmentChip(onClick = open) {
        val shape = RoundedCornerShape(6.dp)
        when (val shown = thumbnail) {
            is Thumbnail.Loaded -> Image(shown.image, fileName(path), Modifier.size(16.dp).clip(shape), contentScale = ContentScale.Crop)
            Thumbnail.Failed -> PpmIcon(ProductIcon.Image, 12.dp, tokens.text2.color)
            Thumbnail.Loading -> Box(Modifier.size(16.dp).alpha(pulse()).background(tokens.panel.color, shape))
        }
        AttachmentName(path)
    }
}

/** `TerminalBlockPreview`: "Terminal output", and the block under it once opened. */
@Composable
private fun TerminalBlockPreview(content: String) {
    val tokens = LocalPpmTokens.current
    var expanded by remember(content) { mutableStateOf(false) }
    val hover = rememberHover()
    val pressed by hover.source.collectIsPressedAsState()
    // `hover:bg-surface` only while closed; a finger down lights it as a mouse over it does.
    val lit = if (expanded) 0.0 else if (pressed) 1.0 else hover.lit.toDouble()
    val open = colorsFade(expanded).toDouble()
    val shape = RoundedCornerShape(8.dp)
    val fill = tokens.panel2.mix(tokens.panel.mix(tokens.bgSolid.opacity(0.4), lit), open)
    val stroke = tokens.accent.opacity(0.5).mix(tokens.border.opacity(0.6), open)
    val color = tokens.text.mix(tokens.text2, open).color
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Column {
        Row(
            Modifier
                .clip(shape)
                .background(fill.color)
                .ppmClickable(pressedColor = Color.Transparent, source = hover.source) { expanded = !expanded }
                .cssBorder(stroke.color, shape)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(ProductIcon.TerminalSquare, 14.dp, color)
            BasicText("Terminal output", style = ppmText(12f, lineHeight = 16f).copy(color = color))
            PpmIcon(ProductIcon.ChevronDown, 12.dp, color, Modifier.rotate(turn))
        }
        if (expanded) {
            // `mt-1 max-h-40 overflow-auto rounded-md border bg-background p-2 text-xs font-mono`.
            val scroll = rememberScrollState()
            SelectionContainer(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .chromeScrollbar(scroll, vertical = true, tokens.border.color, border = 1.dp)
                    .clip(shape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, shape)
                    .verticalScroll(scroll)
                    .padding(8.dp),
            ) {
                BasicText(content, style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color))
            }
        }
    }
}

/** A badge's pill: `rounded-full border-border/60 bg-surface/50 px-2 py-0.5 text-text-subtle`, lit as `hover:` lights it. */
@Composable
private fun BadgePill(gap: Int, onClick: () -> Unit, content: @Composable (Color, Float) -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover()
    val pressed by hover.source.collectIsPressedAsState()
    val lit = if (pressed) 1.0 else hover.lit.toDouble()
    Row(
        Modifier
            .clip(CircleShape)
            .background(tokens.panel.mix(tokens.panel.opacity(0.5), lit).color)
            .ppmClickable(pressedColor = Color.Transparent, source = hover.source, onClick = onClick)
            .cssBorder(tokens.border.opacity(0.6).color, CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap.dp),
    ) { content(tokens.text2.mix(tokens.text3, lit).color, lit.toFloat()) }
}

/** What an opened badge shows: `mt-1 rounded border-border/40 bg-surface/30 px-2 py-1.5`, scrolling past [maxHeight] when it has one. */
@Composable
private fun BadgeBody(maxHeight: Int?, content: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    val scroll = rememberScrollState()
    Box(
        Modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .then(if (maxHeight != null) Modifier.heightIn(max = maxHeight.dp).chromeScrollbar(scroll, vertical = true, tokens.border.color, border = 1.dp) else Modifier)
            .clip(shape)
            .background(tokens.panel.opacity(0.3).color)
            .cssBorder(tokens.border.opacity(0.4).color, shape)
            .then(if (maxHeight != null) Modifier.verticalScroll(scroll) else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) { content() }
}

@Composable
private fun Chevron(open: Boolean, color: Color) {
    val turn by animateFloatAsState(if (open) 90f else 0f, label = "chevron")
    PpmIcon(ProductIcon.ChevronRight, 10.dp, color, Modifier.rotate(turn))
}

/** `SystemTagBadge`: the tag's label, its text under it once opened. */
@Composable
private fun SystemTagBadge(tag: ParsedUserMessage.SystemTag) {
    val tokens = LocalPpmTokens.current
    var open by remember(tag) { mutableStateOf(false) }
    // A flex item: as wide as the wider of its pill and what it opens to.
    Column(Modifier.width(IntrinsicSize.Max)) {
        BadgePill(gap = 4, onClick = { open = !open }) { color, _ ->
            PpmIcon(ProductIcon.Tag, 10.dp, color)
            BasicText(tag.label, style = ppmText(12f, lineHeight = 16f).copy(color = color))
            Chevron(open, color)
        }
        if (open) {
            BadgeBody(maxHeight = 160) {
                // `text-[11px] leading-relaxed text-text-subtle/80 whitespace-pre-wrap`.
                SelectionContainer {
                    BasicText(tag.content, style = ppmText(11f, lineHeight = 11f * 1.625f).copy(color = tokens.text3.opacity(0.8).color))
                }
            }
        }
    }
}

/** `TaskNotificationBadge`: how the task ended and its summary; opened, the summary, its output file and its result. */
@Composable
private fun TaskNotificationBadge(tag: ParsedUserMessage.SystemTag) {
    val tokens = LocalPpmTokens.current
    var open by remember(tag) { mutableStateOf(false) }
    val summary = remember(tag) { tag.field("summary") }
    val outputFile = remember(tag) { tag.field("output-file") }
    val result = remember(tag) { tag.field("result") }
    val ok = remember(tag) { tag.field("status") == "completed" }
    Column(Modifier.width(IntrinsicSize.Max)) {
        BadgePill(gap = 6, onClick = { open = !open }) { color, _ ->
            if (ok) PpmIcon(ProductIcon.CheckCircle2, 10.dp, tokens.success.color) else PpmIcon(ProductIcon.XCircle, 10.dp, tokens.warning.color)
            BasicText(
                summary ?: "Task notification",
                Modifier.widthIn(max = 320.dp),
                style = ppmText(12f, lineHeight = 16f).copy(color = color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Chevron(open, color)
        }
        if (open) {
            // No `max-h` of its own: only the result in it scrolls.
            BadgeBody(maxHeight = null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    summary?.let { BasicText(it, style = ppmText(11f, lineHeight = 11f * XS_LINE).copy(color = tokens.text2.color)) }
                    outputFile?.let { FilePathChip(it, lineHeight = 10f * XS_LINE) }
                    result?.let { TaskResult(it) }
                }
            }
        }
    }
}

/**
 * The result as markdown, `text-[11px] leading-relaxed text-text-subtle/80 max-h-60`, scrolling
 * past that, its HTML shown as text (`rawHtml={false}`): it is what an agent wrote, not the AI's answer.
 */
@Composable
private fun TaskResult(markdown: String) {
    val tokens = LocalPpmTokens.current
    val style = MarkdownStyle(size = 11f, lineHeightRatio = 1.625f, color = tokens.text3.opacity(0.8))
    Box(Modifier.heightIn(max = 240.dp).cssScrollY()) {
        MarkdownContent(markdown, style = style, rawHtml = false)
    }
}

/**
 * `ReplyCard`: the message this one answers, as it was quoted — "Reply to you / AI", which jumps
 * to it while the chat holds it, two lines of the quote until "Show quote". On a phone both
 * buttons are 44 tall (`min-h-[44px] md:min-h-0`). Over the message box ([preview]) it reads
 * "Replying to", and [onCancel] puts the cancel button at its end, 44 px square on every layout.
 * Its buttons keep the arrow over a box that shows the I-beam.
 */
@Composable
fun ReplyCard(
    reply: ReplyReference,
    onJump: (() -> Unit)?,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
    onCancel: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var expanded by remember(reply) { mutableStateOf(false) }
    val small = ppmText(12f, lineHeight = 16f)
    val minHeight = if (mobile) 44.dp else 0.dp
    Column(
        modifier
            .fillMaxWidth()
            .replyFrame(tokens.panel.opacity(0.6).color, tokens.border.color, tokens.accent.color)
            .cssBorderInset(start = 2.dp, top = 1.dp, end = 1.dp, bottom = 1.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        // `flex items-center justify-between gap-2`.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .heightIn(min = minHeight)
                    .pointerHoverIcon(WebCursor.Default)
                    .then(if (onJump != null) Modifier.ppmClickable(pressedColor = Color.Transparent, onClick = onJump) else Modifier),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicText(
                    (if (preview) "Replying to " else "Reply to ") + if (reply.role == ReplyReference.Role.USER) "you" else "AI",
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = (if (onJump != null) tokens.accent else tokens.text2).color),
                )
            }
            if (onCancel != null) {
                Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                // `hover:bg-surface-hover` names a colour the app does not have: nothing lights.
                Box(
                    Modifier
                        .size(44.dp)
                        .pointerHoverIcon(WebCursor.Default)
                        .ppmClickable(pressedColor = Color.Transparent, onClick = onCancel)
                        .semantics { contentDescription = "Cancel reply" },
                    contentAlignment = Alignment.Center,
                ) { PpmIcon(ProductIcon.XCircle, 16.dp, tokens.text2.color) }
            }
        }
        val quoteStyle = small.copy(color = tokens.text2.color)
        // Lines end where Chrome's do, in a quote with no mark of its own for a copy to keep.
        val marked = BREAK !in reply.quote && JOIN !in reply.quote
        val quote = remember(reply.quote) { if (marked) chromeBreaks(reply.quote) else reply.quote }
        WithoutOnCopy(if (marked) "$BREAK$JOIN" else "") {
            SelectionContainer {
                // `select-text`; over the message box the I-beam is the box's own, everywhere in it.
                TextCursorRegion(enabled = !preview) {
                    if (expanded) {
                        CursorText(quote, Modifier.heightIn(max = 240.dp).cssScrollY(), quoteStyle)
                    } else {
                        ClampedText(AnnotatedString(quote), quoteStyle, maxLines = 2, preWrap = true)
                    }
                }
            }
        }
        val note = small.copy(color = tokens.text3.color)
        if (reply.truncated) BasicText("Quote shortened to 12,000 characters", Modifier.padding(top = 4.dp), note)
        if (onJump == null && !preview) BasicText("Original message unavailable", Modifier.padding(top = 4.dp), note)
        // `text-primary hover:underline`, at once.
        val hover = rememberHover(instant = true)
        Box(
            Modifier
                .heightIn(min = minHeight)
                .pointerHoverIcon(WebCursor.Default)
                .ppmClickable(pressedColor = Color.Transparent, source = hover.source) { expanded = !expanded },
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicText(
                if (expanded) "Show less" else "Show quote",
                style = small.copy(color = tokens.accent.color, textDecoration = if (hover.lit > 0f) TextDecoration.Underline else null),
            )
        }
    }
}

/**
 * `rounded-md border border-border border-l-2 border-l-primary` over its fill: the left side 2 px
 * in the accent, the rest 1 px, the colours meeting where CSS joins them, on the line from each
 * outer corner through the inner one.
 */
private fun Modifier.replyFrame(fill: Color, border: Color, accent: Color): Modifier = drawBehind {
    val radius = 8.dp.toPx()
    val left = cssBorderPx(2.dp).toFloat()
    val side = cssBorderPx(1.dp).toFloat()
    val outer = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius))) }
    val leftCorner = CornerRadius((radius - left).coerceAtLeast(0f), (radius - side).coerceAtLeast(0f))
    val rightCorner = CornerRadius((radius - side).coerceAtLeast(0f))
    val inner = Path().apply {
        addRoundRect(RoundRect(left, side, size.width - side, size.height - side, leftCorner, rightCorner, rightCorner, leftCorner))
    }
    drawPath(outer, fill)
    val ring = Path.combine(PathOperation.Difference, outer, inner)
    drawPath(ring, border)
    val slope = radius * side / left
    val leftShare = Path().apply {
        moveTo(0f, 0f)
        lineTo(radius, slope)
        lineTo(radius, size.height - slope)
        lineTo(0f, size.height)
        close()
    }
    clipPath(leftShare) { drawPath(ring, accent) }
}
