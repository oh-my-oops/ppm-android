package com.hienlh.ppm.chat

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FileIcon
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.cssFlex
import com.hienlh.ppm.design.cssHeight
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.SessionFileChange
import com.hienlh.ppm.state.BlockState
import com.hienlh.ppm.state.FileReview
import com.hienlh.ppm.state.ReviewPane
import com.hienlh.ppm.state.SessionReview
import com.hienlh.ppm.state.SessionReviewState
import kotlin.math.roundToInt

/*
 * The Review tab on a phone (`review-phone.tsx`): one block at a time, its two answers under the
 * thumb, the file list in a sheet. The blocks carry no buttons of their own here; the bottom bar
 * answers the one in focus, and a decided one is opened again from the same place.
 */

private enum class PhoneSheet { FILES, ACTIONS, CONFIRM }

/** The page's line height of 1.5, which the tab's text inherits where it names none. */
private fun pageText(size: Float, weight: FontWeight = FontWeight.Normal) = ppmText(size, weight, lineHeight = size * 1.5f)

@Composable
fun ReviewPhone(review: SessionReview, ctx: ReviewTabContext, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val state by review.state.collectAsState()
    val focused = state.focused
    val progress = state.progress
    val decided = progress.total - progress.open
    var sheet by remember { mutableStateOf<PhoneSheet?>(null) }
    val close = { sheet = null }
    val latest by rememberUpdatedState(ctx)
    val actions = remember(review) { reviewActions(review) { turn, call -> latest.showInChat(turn, call) } }
    val code = rememberReviewCodeState((state.pane as? ReviewPane.Blocks)?.review?.path.orEmpty())
    val undoToast = { state.toast?.let { toast -> toast.undoId?.let { review.undo(it, toast.focus) } }; Unit }

    // `flex h-full flex-col overflow-hidden bg-bg`, its rows stacked at their fractional heights.
    CssColumn(modifier.fillMaxSize().background(tokens.bgSolid.color).clipToBounds()) {
        PhoneHeader(decided, progress.total) { sheet = PhoneSheet.FILES }
        ProgressLine(decided, progress.total)
        state.error?.let { ReviewErrorStrip(it) }
        if (focused != null) {
            PhoneFileHeader(state, focused, ctx) { sheet = PhoneSheet.ACTIONS }
            Box(Modifier.cssFlex().fillMaxWidth().fillMaxHeight()) {
                ReviewPaneBody(
                    state = state,
                    compact = true,
                    turns = ctx.turns,
                    actions = actions,
                    code = code,
                    sheet = ctx.sheet,
                    onRevertFile = { sheet = PhoneSheet.CONFIRM },
                    onKeepFile = review::keepFile,
                    onChange = if (state.canChange) review::changeFocused else null,
                    modifier = Modifier.fillMaxSize(),
                )
                state.toast?.let { ReviewToastSlot(it, mobile = true, onUndo = undoToast) }
            }
            PhoneBottomBar(state, review) { sheet = PhoneSheet.CONFIRM }
        } else {
            Box(Modifier.cssFlex().fillMaxWidth().fillMaxHeight()) {
                ReviewEmpty(
                    loading = state.files == null || (progress.open > 0 && state.focus == null),
                    files = state.reviews.size,
                    total = progress.total,
                    kept = progress.kept,
                    mobile = true,
                    onChat = ctx.openChat,
                )
                state.toast?.let { ReviewToastSlot(it, mobile = true, onUndo = undoToast) }
            }
        }
    }

    // `max-h-[85%]` sheets: the files, and the file's actions with its revert confirmed in the same sheet.
    when (sheet) {
        PhoneSheet.FILES -> ctx.listSheet(close) { _ ->
            SheetHead("Files changed in this chat", "$decided of ${progress.total} blocks decided · ${progress.open} left", onClose = close)
            Column(Modifier.weight(1f, fill = false).cssScrollY()) {
                ReviewFileList(state.reviews, focused?.path, mobile = true, projectPath = ctx.projectPath) { path ->
                    review.focusFile(path)
                    close()
                }
            }
            if (progress.open > 0) {
                // `flex shrink-0 gap-2 border-t border-border-soft px-4 pt-2.5`.
                Row(Modifier.fillMaxWidth().cssBorder(tokens.borderSoft.color, sides = CssSides.Top).padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
                    BarButton("Keep all remaining", ProductIcon.ListChecks, BarTone.PLAIN, Modifier.weight(1f)) {
                        close()
                        review.keepAll()
                    }
                }
            }
        }
        PhoneSheet.ACTIONS, PhoneSheet.CONFIRM -> if (focused != null) {
            ctx.listSheet(close) { _ ->
                if (sheet == PhoneSheet.CONFIRM) {
                    ConfirmRevert(focused, ctx, review, close)
                } else {
                    FileActions(focused, ctx, review, close) { sheet = PhoneSheet.CONFIRM }
                }
            }
        }
        null -> Unit
    }
}

/**
 * `flex h-12 shrink-0 items-center gap-1 border-b border-border-soft bg-panel pr-1 pl-3.5`: the
 * title, how many blocks are decided, and the files.
 */
@Composable
private fun PhoneHeader(decided: Int, total: Int, onFiles: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .cssHeight(48.dp)
            .fillMaxWidth()
            .background(tokens.panel.color)
            .cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TruncatedText(AnnotatedString("Review changes"), pageText(15f, FontWeight.SemiBold).copy(color = tokens.text.color), Modifier.weight(1f))
        if (total > 0) {
            // `mr-0.5 text-xs text-text-2`, the count in `font-semibold text-text`.
            val count = remember(decided, total, tokens) {
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = tokens.text.color)) { append("$decided") }
                    append("/$total blocks")
                }
            }
            BasicText(count, Modifier.padding(end = 2.dp), style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color), maxLines = 1, softWrap = false)
        }
        PhoneIconButton(ProductIcon.FolderTree, "All files", onFiles)
    }
}

/** `h-[3px] bg-[text 10%]`, filled in the accent as far as the blocks are decided, its width eased. */
@Composable
private fun ProgressLine(decided: Int, total: Int) {
    val tokens = LocalPpmTokens.current
    val fraction by animateFloatAsState(
        if (total > 0) decided.toFloat() / total else 0f,
        tween(150, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
        label = "decided",
    )
    val accent = tokens.accent.color
    Box(
        Modifier
            .cssHeight(3.dp)
            .fillMaxWidth()
            .background(tokens.text.opacity(0.1).color)
            .drawBehind { drawRect(accent, size = Size((size.width * fraction).roundToInt().toFloat(), size.height)) },
    )
}

/**
 * The file in focus (`PhoneFileHeader`, `flex min-h-[52px] items-center gap-2 border-b
 * border-border-soft bg-panel py-1 pr-1 pl-3.5`): its name over where the block is and its folder,
 * and its actions.
 */
@Composable
private fun PhoneFileHeader(state: SessionReviewState, r: FileReview, ctx: ReviewTabContext, onActions: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val (base, dir) = reviewNameAndDir(r.path, ctx.projectPath)
    val deleted = r.file.status == SessionFileChange.Status.DELETED
    val place = state.place
    val where = when {
        state.pane is ReviewPane.Whole -> "Whole file"
        place != null -> "Block ${place.first + 1} of ${place.second}"
        else -> ""
    }
    val sub = listOf(where, dir).filter { it.isNotEmpty() }.joinToString(" · ")
    // The 44 px button or the two lines, whichever is taller, `py-1` and the border: never under
    // `min-h-[52px]`, which counts the border, and 52.76 px where Rows would round it to 53.33.
    val height = with(LocalDensity.current) {
        val lines = 20.sp.toDp() + if (sub.isNotEmpty()) 17.25.sp.toDp() else 0.dp
        maxOf(44.dp, lines) + 8.dp + cssBorderPx(1.dp).toDp()
    }
    Row(
        Modifier
            .cssHeight(height)
            .fillMaxWidth()
            .background(tokens.panel.color)
            .cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FileIcon(base, size = 16.dp)
        Column(Modifier.weight(1f)) {
            val name = ppmText(14f, FontWeight.SemiBold, lineHeight = 20f).copy(
                color = (if (deleted) tokens.text2 else tokens.text).color,
                textDecoration = if (deleted) TextDecoration.LineThrough else null,
            )
            TruncatedText(AnnotatedString(base), name, Modifier.fillMaxWidth())
            // An empty `<small>` has no line box.
            if (sub.isNotEmpty()) TruncatedText(AnnotatedString(sub), pageText(11.5f).copy(color = tokens.text3.color), Modifier.fillMaxWidth())
        }
        PhoneIconButton(ProductIcon.MoreVertical, "File actions", onActions)
    }
}

/**
 * `flex shrink-0 items-center gap-2 border-t border-border-soft bg-panel px-2.5 py-2`: the step
 * buttons either side of the block's two answers, or of Change once it is decided.
 */
@Composable
private fun PhoneBottomBar(state: SessionReviewState, review: SessionReview, onConfirm: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val height = with(LocalDensity.current) { 44.dp + 16.dp + cssBorderPx(1.dp).toDp() }
    Row(
        Modifier
            .cssHeight(height)
            .fillMaxWidth()
            .background(tokens.panel.color)
            .cssBorder(tokens.borderSoft.color, sides = CssSides.Top)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BarButton(null, ProductIcon.ChevronLeft, BarTone.PLAIN, label = "Previous block") { review.step(-1) }
        if (state.focusState == BlockState.OPEN) {
            BarButton("Revert", ProductIcon.RotateCcw, BarTone.PLAIN, Modifier.weight(1f)) { if (!review.revertFocused()) onConfirm() }
            BarButton("Keep", ProductIcon.Check, BarTone.PRIMARY, Modifier.weight(1f), onClick = review::keepFocused)
        } else {
            BarButton("Change", ProductIcon.Undo2, BarTone.PLAIN, Modifier.weight(1f), enabled = state.canChange, onClick = review::changeFocused)
        }
        BarButton(null, ProductIcon.ChevronRight, BarTone.PLAIN, label = "Next block") { review.step(1) }
    }
}

private enum class BarTone { PLAIN, PRIMARY, DANGER }

/**
 * `barButton`: `inline-flex h-11 min-w-11 items-center justify-center gap-2 rounded-[10px] border
 * px-3 text-sm font-medium active:opacity-80 disabled:opacity-50`, on `bg-panel-2` with a border, on
 * the primary colour, or on the error colour darkened by 15% for a revert.
 */
@Composable
private fun BarButton(
    text: String?,
    icon: ProductIcon?,
    tone: BarTone,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = text,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val (fill, border, ink) = when (tone) {
        BarTone.PLAIN -> Triple(tokens.panel2.color, tokens.border.color, tokens.text.color)
        BarTone.PRIMARY -> Triple(tokens.accent.color, Color.Transparent, tokens.accentFg.color)
        BarTone.DANGER -> Triple(tokens.error.mix(Rgba(0.0, 0.0, 0.0, 1.0), 0.85).color, Color.Transparent, Color.White)
    }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .height(44.dp)
            .widthIn(min = 44.dp)
            .graphicsLayer {
                alpha = when {
                    !enabled -> 0.5f
                    pressed -> 0.8f
                    else -> 1f
                }
            }
            .clip(shape)
            .background(fill)
            .cssBorder(border, shape)
            .ppmClickable(enabled = enabled, pressedColor = Color.Transparent, shape = shape, onClickLabel = label, source = source, onClick = onClick)
            .padding(horizontal = if (text == null) 0.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        icon?.let { PpmIcon(it, 20.dp, ink) }
        text?.let { BasicText(it, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = ink), maxLines = 1, softWrap = false) }
    }
}

/** `iconButton`: `inline-grid size-11 place-items-center rounded-[10px] text-text-2 active:bg-surface-hover`. */
@Composable
private fun PhoneIconButton(icon: ProductIcon, label: String, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(44.dp).ppmClickable(pressedColor = tokens.surfaceHover.color, shape = shape, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, 20.dp, tokens.text2.color)
    }
}

/**
 * A sheet's head (`SheetHead`, `flex items-center gap-2.5 pt-0.5 pr-1.5 pb-2 pl-4`): its title,
 * with an icon before it and a line under it when given, and Close.
 */
@Composable
private fun SheetHead(title: String, sub: String? = null, icon: (@Composable () -> Unit)? = null, onClose: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                icon?.invoke()
                TruncatedText(AnnotatedString(title), pageText(15f, FontWeight.SemiBold).copy(color = tokens.text.color), Modifier.weight(1f, fill = false))
            }
            sub?.let { BasicText(it, Modifier.padding(top = 2.dp), style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color)) }
        }
        PhoneIconButton(ProductIcon.X, "Close", onClose)
    }
}

/** `flex min-h-12 w-full items-center gap-3 px-4 text-left text-sm active:bg-surface-hover`, in the error colour for a revert. */
@Composable
private fun ActionRow(icon: ProductIcon, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val ink = (if (destructive) tokens.error else tokens.text).color
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .ppmClickable(pressedColor = tokens.surfaceHover.color, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PpmIcon(icon, 20.dp, ink)
        BasicText(label, style = ppmText(14f, lineHeight = 20f).copy(color = ink))
    }
}

/** What can be done with the whole file (`FileActions`): open it, keep it, revert it, or go back to the chat. */
@Composable
private fun ColumnScope.FileActions(r: FileReview, ctx: ReviewTabContext, review: SessionReview, onClose: () -> Unit, onRevert: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val (base, _) = reviewNameAndDir(r.path, ctx.projectPath)
    val deleted = r.file.status == SessionFileChange.Status.DELETED
    SheetHead(base, onClose = onClose)
    Column(
        Modifier
            .weight(1f, fill = false)
            .fillMaxWidth()
            .cssBorder(tokens.borderSoft.color, sides = CssSides.Top)
            .cssScrollY()
            .padding(vertical = 4.dp),
    ) {
        if (!deleted && !r.gone) {
            ActionRow(ProductIcon.ExternalLink, "Open file") {
                onClose()
                ctx.openFile(r.path)
            }
        }
        if (r.open > 0 && !r.gone) {
            ActionRow(ProductIcon.Check, "Keep file") {
                onClose()
                review.keepFile()
            }
        }
        if (!r.gone) ActionRow(ProductIcon.RotateCcw, "Revert file…", destructive = true, onClick = onRevert)
        ActionRow(ProductIcon.MessageCircle, "Back to chat") {
            onClose()
            ctx.openChat()
        }
    }
}

/** Reverting the whole file, confirmed first (`ConfirmRevert`). */
@Composable
private fun ConfirmRevert(r: FileReview, ctx: ReviewTabContext, review: SessionReview, onDone: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val (base, _) = reviewNameAndDir(r.path, ctx.projectPath)
    SheetHead("Revert $base?", icon = { PpmIcon(ProductIcon.RotateCcw, 20.dp, tokens.error.color) }, onClose = onDone)
    // `px-4 pb-1`, stacked as Chrome stacks it: a 21 px line at 2.625x is 55.125 px, which Compose
    // rounds up, and the 11 px counts in their mono face fit inside the line without raising it.
    CssColumn(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp), bottom = 4.dp) {
        BasicText(
            revertFileText(r.file, base, r.blocks.size, tokens),
            Modifier.fillMaxWidth().cssLines(21f),
            style = ppmText(14f, lineHeight = 21f).copy(color = tokens.text2.color),
        )
        Spacer(Modifier.cssHeight(14.dp))
        Row(Modifier.cssHeight(44.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BarButton("Cancel", null, BarTone.PLAIN, Modifier.weight(1f), onClick = onDone)
            BarButton("Revert file", ProductIcon.RotateCcw, BarTone.DANGER, Modifier.weight(1f)) {
                onDone()
                review.revertFile()
            }
        }
    }
}
