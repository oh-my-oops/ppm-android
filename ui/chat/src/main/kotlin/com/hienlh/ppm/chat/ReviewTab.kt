package com.hienlh.ppm.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FileIcon
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.StartTruncatedText
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.chromeScrollbar
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.SessionFileChange
import com.hienlh.ppm.state.BlockState
import com.hienlh.ppm.state.FileOutcome
import com.hienlh.ppm.state.FileReview
import com.hienlh.ppm.state.ReviewFocus
import com.hienlh.ppm.state.ReviewPane
import com.hienlh.ppm.state.ReviewToast
import com.hienlh.ppm.state.SessionChangeList
import com.hienlh.ppm.state.SessionReview
import com.hienlh.ppm.state.SessionReviewModel
import com.hienlh.ppm.state.SessionReviewState
import com.hienlh.ppm.state.SessionTurn
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The Review tab (`session-review-tab.tsx`): every change one chat session made, block by
 * block, each kept or reverted on disk where it sits in its file, every answer undoable. The
 * state lives in [SessionReview]; this draws the tablet's layout (`review-desktop.tsx`) and the
 * pieces the phone's shares with it (`review-parts.tsx`).
 */

/** What a Review tab is given beyond its review (`ReviewView`'s props), on a tablet and a phone alike. */
@Stable
class ReviewTabContext(
    val projectPath: String?,
    val providerId: String?,
    val chatTitle: String?,
    /** Each call's turn, for the turns a block names. */
    val turns: Map<String, SessionTurn>,
    val openChat: () -> Unit,
    val openFile: (path: String) -> Unit,
    /** Open the chat at the call that wrote a block, or at its turn's prompt. */
    val showInChat: (turn: SessionTurn, call: String) -> Unit,
    /** A text field has focus, which the tab leaves it (the web's check that focus was lost). */
    val typing: () -> Boolean,
    /** The tab is the one in front, whose Escape a turn card takes. */
    val ownsKeys: () -> Boolean,
    val sheet: ReviewSheet,
)

/** The code pane's answers, on the review (`reviewView`'s `codeActions`). */
private fun reviewActions(review: SessionReview, showInChat: (SessionTurn, String) -> Unit) = ReviewCodeActions(
    focus = { key -> review.state.value.focused?.let { review.setFocus(ReviewFocus(it.path, key)) } },
    keep = review::keepBlock,
    revert = review::revertBlock,
    reopen = review::reopenBlock,
    undo = { undoId, key -> review.state.value.focused?.let { review.undo(undoId, ReviewFocus(it.path, key)) } },
    showInChat = showInChat,
)

/** `nameAndDir`: the name and folder a path shows, inside the project relative to it. */
internal fun reviewNameAndDir(path: String, projectPath: String?): Pair<String, String> =
    SessionChangeList.splitPath(SessionChangeList.displayPath(path, projectPath))

/** The page's line height, 1.5 of the size, which most of the tab's text inherits. */
private fun pageLine(size: Float) = size * 1.5f

private fun pageText(size: Float, weight: FontWeight = FontWeight.Normal, kind: FontKind = FontKind.SANS) =
    ppmText(size, weight, kind, lineHeight = pageLine(size))

/** Tailwind's `transition-colors`: 150 ms on `cubic-bezier(0.4, 0, 0.2, 1)`. */
private val ColorsEase = tween<Color>(150, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f))

// The tablet's layout

/**
 * The Review tab on a tablet (`ReviewDesktop`, `@container flex h-full flex-col bg-bg`): progress
 * across the top, the files on the left with a dot per block, and the file in focus with its
 * blocks, stepped through with J/K and answered with Y/N on a hardware keyboard. Below 760 dp of
 * its own width the file list folds behind a button: a narrow split is not a phone.
 */
@Composable
fun ReviewDesktop(review: SessionReview, ctx: ReviewTabContext, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val state by review.state.collectAsState()
    val focused = state.focused
    var confirm by remember { mutableStateOf(false) }
    var filesOpen by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(ctx)
    val actions = remember(review) { reviewActions(review) { turn, call -> latest.showInChat(turn, call) } }
    val code = rememberReviewCodeState((state.pane as? ReviewPane.Blocks)?.review?.path.orEmpty())
    val focus = remember { FocusRequester() }
    val overlay = remember { ReviewOverlay() }

    // The keys stay with the tab: when it opens from the chat's bar, and when an answer takes away
    // the button that was pressed. Never away from somewhere to type.
    LaunchedEffect(state.focus?.path, state.focus?.key, state.pane::class) {
        withFrameNanos { }
        if (!latest.typing()) runCatching { focus.requestFocus() }
    }
    LaunchedEffect(focused?.path) { confirm = false }
    // An open turn card takes Escape before the tab's own keys, as the web's capture listener does.
    val cardOpen = code.pop != null
    DisposableEffect(cardOpen) {
        if (!cardOpen) return@DisposableEffect onDispose { }
        val claim = TurnTrayEscape.Claim({ latest.ownsKeys() }) { code.closeTurn() }
        TurnTrayEscape.take(claim)
        onDispose { TurnTrayEscape.release(claim) }
    }

    fun onKey(type: KeyEventType, key: Key, modified: Boolean): Boolean {
        if (type != KeyEventType.KeyDown || modified) return false
        when {
            key == Key.J || key == Key.K -> review.step(if (key == Key.J) 1 else -1)
            key == Key.Y && state.focusState == BlockState.OPEN -> review.keepFocused()
            key == Key.N && state.focusState == BlockState.OPEN -> if (!review.revertFocused()) confirm = true
            key == Key.Escape && (confirm || filesOpen) -> {
                confirm = false
                filesOpen = false
            }
            else -> return false
        }
        return true
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .clipToBounds()
            .onGloballyPositioned { overlay.root = it }
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { onKey(it.type, it.key, it.isCtrlPressed || it.isMetaPressed || it.isAltPressed) }
            .pointerInput(Unit) {
                // A press in the tab leaves its keys with it, as Chrome's focus stays in the tab on
                // the button pressed; the app gives a tap's focus to the page, after which this
                // runs. Never away from somewhere to type.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                    } while (event.changes.any { it.pressed })
                    if (!latest.typing()) runCatching { focus.requestFocus() }
                }
            }
            .closesTurnOutside(code)
            .pointerInput(confirm) {
                if (!confirm) return@pointerInput
                // `mousedown` outside the Revert button and its popover closes the popover.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Press) continue
                        val at = event.changes.firstOrNull()?.position ?: continue
                        if (overlay.revert?.contains(at) != true && overlay.popover?.contains(at) != true) confirm = false
                    }
                }
            },
    ) {
        val wide = maxWidth >= 760.dp
        val drawer = minOf(288.dp, maxWidth * 0.85f)
        val pick = { path: String ->
            review.focusFile(path)
            filesOpen = false
        }
        Column(Modifier.fillMaxSize()) {
            ReviewHeader(
                state = state,
                ctx = ctx,
                wide = wide,
                filesOpen = filesOpen,
                onFiles = { filesOpen = !filesOpen },
                onKeepAll = review::keepAll,
                onReload = review::reload,
            )
            state.error?.let { error ->
                // `bg-destructive/10 px-3 py-1.5 text-xs text-destructive`.
                BasicText(
                    error,
                    Modifier.fillMaxWidth().background(tokens.error.opacity(0.1).color).padding(horizontal = 12.dp, vertical = 6.dp),
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxSize()) {
                    if (wide) {
                        FileAside(
                            state.reviews,
                            focused?.path,
                            ctx,
                            pick,
                            Modifier.width(288.dp).fillMaxHeight().background(tokens.panel.color).cssBorder(tokens.borderSoft.color, sides = CssSides.End),
                        )
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (focused != null) {
                            FileHeader(focused, ctx)
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                ReviewPaneBody(
                                    state = state,
                                    compact = false,
                                    turns = ctx.turns,
                                    actions = actions,
                                    code = code,
                                    sheet = ctx.sheet,
                                    onRevertFile = { confirm = true },
                                    onKeepFile = review::keepFile,
                                    onChange = if (state.canChange) review::changeFocused else null,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                state.toast?.let { toast -> ToastSlot(toast, mobile = false) { toast.undoId?.let { review.undo(it, toast.focus) } } }
                            }
                            BottomBar(
                                state = state,
                                review = review,
                                ctx = ctx,
                                confirm = confirm,
                                onConfirm = { confirm = it },
                                overlay = overlay,
                            )
                        } else {
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                ReviewEmpty(
                                    loading = state.files == null || (state.progress.open > 0 && state.focus == null),
                                    files = state.reviews.size,
                                    total = state.progress.total,
                                    kept = state.progress.kept,
                                    mobile = false,
                                    onChat = ctx.openChat,
                                )
                                state.toast?.let { toast -> ToastSlot(toast, mobile = false) { toast.undoId?.let { review.undo(it, toast.focus) } } }
                            }
                        }
                    }
                }
                if (!wide && filesOpen) {
                    // `absolute inset-0 z-20 bg-black/30`, and the list over it.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.3f))
                            .pointerInput(Unit) { awaitPointerEventScope { while (true) if (awaitPointerEvent().type == PointerEventType.Release) filesOpen = false } },
                    )
                    // `w-[min(288px,85%)] border-r border-border bg-panel shadow-float`.
                    val shape = RoundedCornerShape(0.dp)
                    FileAside(
                        state.reviews,
                        focused?.path,
                        ctx,
                        pick,
                        Modifier
                            .width(drawer)
                            .fillMaxHeight()
                            .ppmShadow(tokens.shadowFloat, shape)
                            .background(tokens.panel.color)
                            .cssBorder(tokens.border.color, sides = CssSides.End),
                    )
                }
            }
        }
        if (confirm && focused != null && !focused.gone) {
            RevertConfirm(focused, ctx, overlay, onCancel = { confirm = false }) {
                confirm = false
                review.revertFile()
            }
        }
    }
}

/**
 * Where the Revert button's wrapper and its popover are in the tab, so a press elsewhere closes
 * the popover and the popover sits over the button.
 */
@Stable
private class ReviewOverlay {
    var root: LayoutCoordinates? = null
    var revert by mutableStateOf<Rect?>(null)
    var popover: Rect? = null

    fun boundsOf(child: LayoutCoordinates): Rect? {
        val host = root?.takeIf { it.isAttached } ?: return null
        return if (child.isAttached) host.localBoundingBoxOf(child, clipBounds = false) else null
    }
}

/**
 * `relative flex shrink-0 flex-wrap items-center gap-x-[18px] gap-y-2 border-b border-border-soft
 * bg-panel py-2.5 pr-3 pl-4`: the title and the link back to the chat, the progress, and the
 * tab's buttons.
 */
@Composable
private fun ReviewHeader(
    state: SessionReviewState,
    ctx: ReviewTabContext,
    wide: Boolean,
    filesOpen: Boolean,
    onFiles: () -> Unit,
    onKeepAll: () -> Unit,
    onReload: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    CssWrapRow(
        gapX = 18.dp,
        gapY = 8.dp,
        start = 16.dp,
        end = 12.dp,
        top = 10.dp,
        bottom = 10.dp,
        borderBottom = tokens.borderSoft.color,
        modifier = Modifier.fillMaxWidth().background(tokens.panel.color),
    ) {
        TitleBlock(ctx, Modifier.layoutId(WrapItem(shrink = true)))
        if (state.progress.total > 0) {
            ReviewProgressView(state.reviews, state.progress.total, state.progress.open, ctx.projectPath, Modifier.layoutId(WrapItem(shrink = true)))
        }
        Box(Modifier.layoutId(WrapItem(grow = true)))
        Row(Modifier.layoutId(WrapItem(height = 32f)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!wide) {
                ReviewButton(
                    reviewPlural(state.reviews.size, "file"),
                    WebVariant.GHOST,
                    WebSize.SM,
                    icon = ProductIcon.FolderTree,
                    content = tokens.text2,
                    modifier = Modifier.webTitle(if (filesOpen) "Hide the files" else "Show the files"),
                    onClick = onFiles,
                )
            }
            if (state.progress.open > 0) {
                ReviewButton("Keep all remaining", WebVariant.GHOST, WebSize.SM, icon = ProductIcon.ListChecks, content = tokens.text2, onClick = onKeepAll)
            }
            ToolButton(ProductIcon.RefreshCw, "Reload", spinning = state.loading, onClick = onReload)
        }
    }
}

/**
 * `min-w-0`: the `text-sm font-semibold leading-5` title, and under it the link back to the chat
 * (`inline-flex max-w-full items-center gap-1 text-xs text-text-2 underline-offset-[3px]
 * hover:text-text hover:underline`), which sits in a line of the block's own: the page's 16 px on
 * 24, its baseline where the icon's box ends, as Chrome puts an inline flex box on a line.
 */
@Composable
private fun TitleBlock(ctx: ReviewTabContext, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val density = LocalDensity.current
    // The page's strut, 16 px on 24 in device pixels: where its baseline falls in the line.
    val baseline = with(density) {
        val px = 16.sp.toPx()
        val (up, down) = PpmFonts.chromeAscentDescent(px, PpmFonts.SANS_ASCENT, PpmFonts.SANS_DESCENT)
        up + floor((24.dp.toPx() - up - down) / 2)
    }
    Column(modifier) {
        BasicText("Review changes", style = ppmText(14f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color), maxLines = 1, softWrap = false)
        Box(Modifier.height(24.dp)) {
            val hover = rememberHover(instant = true)
            val lit = hover.lit > 0f
            val color = (if (lit) tokens.text else tokens.text2).color
            Row(
                Modifier
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        // The 12 px icon is centred in the 16 px box and its bottom is the box's baseline.
                        val top = (baseline - 14.dp.toPx()).roundToInt()
                        layout(placeable.width, constraints.maxHeight) { placeable.place(0, top) }
                    }
                    .height(16.dp)
                    .webTitle("Back to the chat")
                    .pointerHoverIcon(WebCursor.Pointer)
                    .ppmClickable(pressedColor = Color.Transparent, onClickLabel = "Back to the chat", source = hover.source, onClick = ctx.openChat),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PpmIcon(ProductIcon.MessageCircle, 12.dp, color)
                UnderlinedText(ctx.chatTitle ?: "Chat", ppmText(12f, lineHeight = 16f).copy(color = color), underline = lit, offset = 3.dp)
            }
        }
    }
}

/** `truncate`, with `underline` and its offset when [underline] — the font's own thickness, a tenth of an em. */
@Composable
private fun UnderlinedText(text: String, style: TextStyle, underline: Boolean, offset: Dp) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val color = style.color
    BasicText(
        text,
        Modifier.drawBehind {
            val line = layout ?: return@drawBehind
            if (!underline) return@drawBehind
            val thickness = max(1f, (style.fontSize.toPx() / 20f).roundToInt().toFloat())
            val top = (line.getLineBaseline(0) + offset.toPx()).roundToInt().toFloat()
            drawRect(color, Offset(0f, top), Size(min(line.size.width.toFloat(), size.width), thickness))
        },
        style = style,
        onTextLayout = { layout = it },
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * `inline-grid size-7 shrink-0 place-items-center rounded-md text-text-subtle hover:bg-surface-hover
 * hover:text-text`, at once: no transition.
 */
@Composable
private fun ToolButton(icon: ProductIcon, title: String, spinning: Boolean = false, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(28.dp)
            .webTitle(title)
            .pointerHoverIcon(WebCursor.Pointer)
            .ppmClickable(
                pressedColor = tokens.surfaceHover.color,
                hoverColor = tokens.surfaceHover.color,
                fade = false,
                shape = shape,
                onClickLabel = title,
                source = source,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, 14.dp, (if (hovered || pressed) tokens.text else tokens.text3).color, spinning = spinning)
    }
}

/** Segments are drawn one per block up to this many; past it, one bar in proportion. */
private const val MAX_SEGMENTS = 48

/**
 * One segment per block — kept, reverted or still open — and how many are left (`ReviewProgress`,
 * `flex min-w-0 flex-wrap items-center gap-x-2.5 gap-y-1.5 whitespace-nowrap text-[12.5px]
 * text-text-2`).
 */
@Composable
internal fun ReviewProgressView(reviews: List<FileReview>, total: Int, open: Int, projectPath: String?, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val blocks = reviews.flatMap { r -> r.blocks.mapIndexed { i, b -> Triple(r.path, i, b.state) } }
    val kept = blocks.count { it.third == BlockState.KEPT }
    val reverted = blocks.count { it.third == BlockState.REVERTED }
    val style = pageText(12.5f).copy(color = tokens.text2.color)
    val keptColor = tokens.accent.color
    val revertedColor = tokens.error.opacity(0.7).color
    val openColor = tokens.text.opacity(0.13).color
    CssWrapRow(gapX = 10.dp, gapY = 6.dp, modifier = modifier) {
        if (total <= MAX_SEGMENTS) {
            Row(Modifier.layoutId(WrapItem(height = 6f)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for ((path, index, block) in blocks) {
                    val target = when (block) {
                        BlockState.KEPT -> keptColor
                        BlockState.REVERTED -> revertedColor
                        else -> openColor
                    }
                    val fill by animateColorAsState(target, ColorsEase, label = "segment")
                    Box(
                        Modifier
                            .size(12.dp, 6.dp)
                            .webTitle("${reviewNameAndDir(path, projectPath).first} · block ${index + 1}")
                            .background(fill, RoundedCornerShape(3.dp)),
                    )
                }
            }
        } else {
            // `flex h-1.5 w-40 overflow-hidden rounded-[3px]`, the kept share then the reverted one.
            Box(
                Modifier
                    .layoutId(WrapItem(height = 6f))
                    .size(160.dp, 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(openColor)
                    .drawBehind {
                        val k = size.width * kept / total
                        drawRect(keptColor, size = Size(k, size.height))
                        drawRect(revertedColor, Offset(k, 0f), Size(size.width * reverted / total, size.height))
                    },
            )
        }
        val decided = remember(total, open, tokens) {
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = tokens.text.color)) { append("${total - open}") }
                append(" of ${reviewPlural(total, "block")} decided")
            }
        }
        BasicText(decided, Modifier.layoutId(WrapItem(height = pageLine(12.5f))), style = style, maxLines = 1, softWrap = false)
        if (open > 0) {
            BasicText("· $open left", Modifier.layoutId(WrapItem(height = pageLine(12.5f))), style = style.copy(color = tokens.text3.color), maxLines = 1, softWrap = false)
        } else {
            BasicText("· all done", Modifier.layoutId(WrapItem(height = pageLine(12.5f))), style = style.copy(color = tokens.success.color), maxLines = 1, softWrap = false)
        }
    }
}

/**
 * The file list beside the pane (`aside … flex-col overflow-y-auto`), its hint under the list
 * (`mt-auto`: at the bottom while the list is short).
 */
@Composable
private fun FileAside(reviews: List<FileReview>, current: String?, ctx: ReviewTabContext, onPick: (String) -> Unit, modifier: Modifier) {
    val scroll = rememberScrollState()
    val tokens = LocalPpmTokens.current
    BoxWithConstraints(modifier) {
        val viewport = constraints.maxHeight
        Layout(
            content = {
                Column { ReviewFileList(reviews, current, mobile = false, projectPath = ctx.projectPath, onPick = onPick) }
                AsideHint(ctx.providerId)
            },
            modifier = Modifier.fillMaxSize().chromeScrollbar(scroll, vertical = true, tokens.border.color).verticalScroll(scroll),
        ) { measurables, constraints ->
            val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
            val list = measurables[0].measure(loose)
            val hint = measurables[1].measure(loose)
            val height = max(list.height + hint.height, viewport)
            layout(constraints.maxWidth, height) {
                list.place(0, 0)
                hint.place(0, height - hint.height)
            }
        }
    }
}

/**
 * `mt-auto border-t border-border-soft px-3 pt-2.5 pb-3 text-[11.5px] leading-normal
 * text-text-subtle`: what the list leaves out, and the keys.
 */
@Composable
private fun AsideHint(providerId: String?) {
    val tokens = LocalPpmTokens.current
    val style = pageText(11.5f).copy(color = tokens.text3.color)
    Column(
        Modifier
            .fillMaxWidth()
            .cssBorder(tokens.borderSoft.color, sides = CssSides.Top)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PpmIcon(ProductIcon.Terminal, 12.dp, tokens.text3.color, Modifier.padding(top = 2.dp))
            BasicText("A binary or very large file has no blocks: it is kept or reverted whole. ${shellChangesHint(providerId)}", style = style)
        }
        CssWrapRow(gapX = 12.dp, gapY = 4.dp, modifier = Modifier.padding(top = 6.dp)) {
            for ((keys, label) in listOf(listOf("J", "K") to "move", listOf("Y") to "keep", listOf("N") to "revert")) {
                Row(Modifier.layoutId(WrapItem(height = pageLine(11.5f))), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (key in keys) ReviewKbd(key)
                    BasicText(label, style = style, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/**
 * The file in focus (`FileHeader`, `relative flex min-h-12 shrink-0 flex-wrap items-center
 * gap-x-[9px] gap-y-1.5 border-b border-border-soft py-2 pr-3 pl-4`): icon, name, status letter,
 * folder, counts, and Open.
 */
@Composable
private fun FileHeader(r: FileReview, ctx: ReviewTabContext) {
    val tokens = LocalPpmTokens.current
    val (base, dir) = reviewNameAndDir(r.path, ctx.projectPath)
    val deleted = r.file.status == SessionFileChange.Status.DELETED
    CssWrapRow(
        gapX = 9.dp,
        gapY = 6.dp,
        start = 16.dp,
        end = 12.dp,
        top = 8.dp,
        bottom = 8.dp,
        minHeight = 48.dp,
        borderBottom = tokens.borderSoft.color,
        modifier = Modifier.fillMaxWidth(),
    ) {
        FileIcon(base, Modifier.layoutId(WrapItem(height = 16f)), size = 16.dp)
        BasicText(
            base,
            Modifier.layoutId(WrapItem(height = 20f)),
            style = ppmText(14f, FontWeight.SemiBold, lineHeight = 20f).copy(
                color = (if (deleted) tokens.text2 else tokens.text).color,
                textDecoration = if (deleted) TextDecoration.LineThrough else null,
            ),
            maxLines = 1,
            softWrap = false,
        )
        StatusLetter(r.file.status, Modifier.layoutId(WrapItem(height = 18f)))
        if (dir.isNotEmpty()) {
            Box(Modifier.layoutId(WrapItem(height = 16f, shrink = true, minWidth = 40f)).webTitle(r.path)) {
                StartTruncatedText("$dir/", ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color))
            }
        }
        SessionChangeCounts(r.file, pageText(11f, kind = FontKind.MONO), Modifier.layoutId(WrapItem(height = pageLine(11f))))
        Box(Modifier.layoutId(WrapItem(grow = true)))
        if (!deleted && !r.gone) {
            ReviewButton("Open", WebVariant.GHOST, WebSize.SM, icon = ProductIcon.ExternalLink, content = tokens.text2, modifier = Modifier.layoutId(WrapItem(height = 32f))) {
                ctx.openFile(r.path)
            }
        }
    }
}

/**
 * Under the pane (`BottomBar`, `relative flex shrink-0 flex-wrap items-center gap-x-2.5 gap-y-2
 * border-t border-border-soft bg-panel px-3 py-2 text-[12.5px] text-text-2`): the block stepper,
 * what is left in the file, and the file's answers.
 */
@Composable
private fun BottomBar(
    state: SessionReviewState,
    review: SessionReview,
    ctx: ReviewTabContext,
    confirm: Boolean,
    onConfirm: (Boolean) -> Unit,
    overlay: ReviewOverlay,
) {
    val tokens = LocalPpmTokens.current
    val r = state.focused ?: return
    val (base, _) = reviewNameAndDir(r.path, ctx.projectPath)
    val next = state.nextFile
    val style = pageText(12.5f).copy(color = tokens.text2.color)
    CssWrapRow(
        gapX = 10.dp,
        gapY = 8.dp,
        start = 12.dp,
        end = 12.dp,
        top = 8.dp,
        bottom = 8.dp,
        borderTop = tokens.borderSoft.color,
        modifier = Modifier.fillMaxWidth().background(tokens.panel.color),
    ) {
        // `inline-flex items-center gap-0.5 rounded-lg bg-[text 6%] p-0.5`; PPM's `rounded-lg` is 12 px.
        Row(
            Modifier
                .layoutId(WrapItem(height = 32f))
                .background(tokens.text.opacity(0.06).color, RoundedCornerShape(12.dp))
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ToolButton(ProductIcon.ChevronLeft, "Previous block (K)") { review.step(-1) }
            val place = state.place
            BasicText(
                when {
                    state.pane is ReviewPane.Whole -> "Whole file"
                    place != null -> "Block ${place.first + 1} of ${place.second}"
                    else -> ""
                },
                Modifier.widthIn(min = 92.dp),
                style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color, textAlign = TextAlign.Center),
                maxLines = 1,
                softWrap = false,
            )
            ToolButton(ProductIcon.ChevronRight, "Next block (J)") { review.step(1) }
        }
        val status = remember(r.open, base, tokens) {
            buildAnnotatedString {
                val bold = SpanStyle(fontWeight = FontWeight.SemiBold, color = tokens.text.color)
                if (r.open > 0) {
                    withStyle(bold) { append("${r.open}") }
                    append(" open in $base")
                } else {
                    withStyle(bold) { append(base) }
                    append(" is done")
                }
            }
        }
        TruncatedText(status, style, Modifier.layoutId(WrapItem(height = pageLine(12.5f), shrink = true)))
        Box(Modifier.layoutId(WrapItem(grow = true)))
        if (!r.gone) {
            ReviewButton(
                "Revert file…",
                WebVariant.GHOST,
                WebSize.SM,
                icon = ProductIcon.RotateCcw,
                content = tokens.text2,
                modifier = Modifier.layoutId(WrapItem(height = 32f)).onGloballyPositioned { overlay.revert = overlay.boundsOf(it) },
            ) { onConfirm(!confirm) }
        }
        if (r.open > 0 && !r.gone) {
            ReviewButton("Keep file", WebVariant.OUTLINE, WebSize.SM, icon = ProductIcon.Check, modifier = Modifier.layoutId(WrapItem(height = 32f)), onClick = review::keepFile)
        }
        if (next != null) {
            ReviewButton(
                "Next: ${reviewNameAndDir(next.path, ctx.projectPath).first}",
                if (r.open > 0) WebVariant.GHOST else WebVariant.DEFAULT,
                WebSize.SM,
                trailingIcon = ProductIcon.ArrowRight,
                content = if (r.open > 0) tokens.text2 else null,
                modifier = Modifier.layoutId(WrapItem(height = 32f)),
            ) { review.focusFile(next.path) }
        }
    }
}

/**
 * Reverting the whole file, confirmed first (`absolute right-0 bottom-[calc(100%+8px)] z-40
 * w-[330px] rounded-xl border border-border bg-panel-2 p-3.5 text-[12.5px] leading-normal
 * text-text-2 shadow-float`), over the button that asked.
 */
@Composable
private fun RevertConfirm(r: FileReview, ctx: ReviewTabContext, overlay: ReviewOverlay, onCancel: () -> Unit, onRevert: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val anchor = overlay.revert ?: return
    val (base, _) = reviewNameAndDir(r.path, ctx.projectPath)
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight))
                layout(0, 0) {
                    val x = (anchor.right - placeable.width).roundToInt()
                    val y = (anchor.top - 8.dp.toPx() - placeable.height).roundToInt()
                    overlay.popover = Rect(x.toFloat(), y.toFloat(), (x + placeable.width).toFloat(), (y + placeable.height).toFloat())
                    placeable.place(x, y)
                }
            }
            .width(330.dp)
            .ppmShadow(tokens.shadowFloat, shape)
            .clip(shape)
            .background(tokens.panel2.color)
            .cssBorder(tokens.border.color, shape)
            .padding(14.dp),
    ) {
        Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PpmIcon(ProductIcon.RotateCcw, 14.dp, tokens.text.color)
            BasicText("Revert $base?", style = pageText(13f, FontWeight.SemiBold).copy(color = tokens.text.color))
        }
        BasicText(revertFileText(r.file, base, r.blocks.size, tokens), Modifier.padding(bottom = 12.dp), style = pageText(12.5f).copy(color = tokens.text2.color))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
            ReviewButton("Cancel", WebVariant.GHOST, WebSize.SM, onClick = onCancel)
            ReviewButton("Revert file", WebVariant.DESTRUCTIVE, WebSize.SM, icon = ProductIcon.RotateCcw, onClick = onRevert)
        }
    }
}

/** What reverting a whole file does, in a sentence (`revertFileText`). */
internal fun revertFileText(file: SessionFileChange, base: String, blocks: Int, tokens: ThemeTokens): AnnotatedString = buildAnnotatedString {
    val bold = SpanStyle(fontWeight = FontWeight.SemiBold, color = tokens.text.color)
    fun name() = withStyle(bold) { append(base) }
    when (file.status) {
        SessionFileChange.Status.ADDED -> {
            append("Deletes ")
            name()
            append(" — it did not exist before this chat. You can undo it right after.")
        }
        SessionFileChange.Status.DELETED -> {
            append("Brings ")
            name()
            append(" back")
            file.deletions?.takeIf { it > 0 }?.let { append(" with its ${reviewPlural(it, "line")}") }
            append(". You can undo it right after.")
        }
        else -> {
            append("Puts ")
            name()
            append(
                when {
                    file.sinceReview -> " back to the version you reviewed"
                    file.baseline == "head" -> " back to its last committed version"
                    else -> " back the way it was before this chat"
                },
            )
            if (blocks > 1) append(", all ${reviewPlural(blocks, "block")}")
            val additions = file.additions
            val deletions = file.deletions
            if (additions != null && deletions != null) {
                append(" — ")
                val mono = SpanStyle(fontFamily = PpmFonts.mono, fontSize = 11.sp)
                withStyle(mono.copy(color = tokens.success.color)) { append("+$additions") }
                withStyle(mono) { append(" ") }
                withStyle(mono.copy(color = tokens.error.color)) { append("−$deletions") }
                append(" undone on disk")
            }
            append(". You can undo it right after.")
        }
    }
}

// Pieces the phone shares

/** The status letter (`StatusLetter`, `size-[18px] rounded-[5px] font-mono text-[10.5px] font-semibold leading-none`, its colour at 14% behind it). */
@Composable
internal fun StatusLetter(status: SessionFileChange.Status, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val (glyph, tint, label) = when (status) {
        SessionFileChange.Status.ADDED -> Triple("A", tokens.success, "Added")
        SessionFileChange.Status.MODIFIED -> Triple("M", tokens.warning, "Modified")
        SessionFileChange.Status.DELETED -> Triple("D", tokens.error, "Deleted")
    }
    Box(
        modifier.size(18.dp).webTitle(label).background(tint.opacity(0.14).color, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(glyph, style = ppmText(10.5f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 10.5f).copy(color = tint.color), maxLines = 1, softWrap = false)
    }
}

/** `Tag`: `rounded px-[5px] text-[10px] font-semibold leading-[15px]`, its colour at 14% behind it. */
@Composable
private fun ReviewTag(label: String, tint: Rgba) {
    BasicText(
        label,
        Modifier.background(tint.opacity(0.14).color, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp),
        style = ppmText(10f, FontWeight.SemiBold, lineHeight = 15f).copy(color = tint.color),
        maxLines = 1,
        softWrap = false,
    )
}

/** Dots shown before the rest is counted: a file with more blocks says so in its `k/n`. */
private const val MAX_DOTS = 10

/**
 * One file (`FileRow`): its icon, name over its folder, and a dot per block with how far it got —
 * `min-h-[42px] grid-cols-[16px_1fr_auto] gap-[9px] px-3 py-1` on a tablet, `min-h-14
 * grid-cols-[18px_1fr_auto] gap-3 px-4 py-1.5` on a phone; the file in focus in the accent's wash
 * with a 2 px edge.
 */
@Composable
private fun FileRow(review: FileReview, current: Boolean, mobile: Boolean, projectPath: String?, onPick: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    val (base, dir) = reviewNameAndDir(review.path, projectPath)
    val file = review.file
    val done = review.open == 0
    val outcome = if (done) SessionReviewModel.fileOutcome(review) else null
    val decided = review.blocks.size - review.open
    val accent = tokens.accent.color
    val nameLine = if (mobile) 19f else 17f
    val subLine = if (mobile) 16f else 15f
    val tag = when {
        file.sinceReview -> "New since review" to tokens.accent2
        file.baseline == "head" -> "vs last commit" to tokens.warning
        else -> null
    }
    val hasSub = tag != null || dir.isNotEmpty()
    val outcomeLine = if (outcome != null) pageLine(10.5f) else 10.5f
    val minHeight = if (mobile) 56f else 42f
    val padY = if (mobile) 6f else 4f
    val content = maxOf(if (mobile) 18f else 16f, nameLine + (if (hasSub) subLine else 0f), (if (mobile) 10f else 8f) + 4f + outcomeLine)
    Row(
        Modifier
            .fillMaxWidth()
            .height(maxOf(minHeight, content + 2 * padY).dp)
            .webTitle(review.path)
            .then(
                if (current) {
                    Modifier.background(tokens.accentWash.color).drawBehind { drawRect(accent, size = Size(2.dp.toPx(), size.height)) }
                } else {
                    Modifier
                },
            )
            .ppmClickable(
                pressedColor = if (current) Color.Transparent else tokens.surfaceHover.color,
                hoverColor = if (current) Color.Transparent else tokens.surfaceHover.color,
                onClickLabel = review.path,
            ) { onPick(review.path) }
            .padding(horizontal = if (mobile) 16.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (mobile) 12.dp else 9.dp),
    ) {
        FileIcon(base, size = if (mobile) 18.dp else 16.dp)
        Column(Modifier.weight(1f)) {
            val nameStyle = ppmText(if (mobile) 14f else 13f, if (done) FontWeight.Normal else FontWeight.Medium, lineHeight = nameLine).copy(
                color = (if (done) tokens.text2 else tokens.text).color,
                textDecoration = if (file.status == SessionFileChange.Status.DELETED) TextDecoration.LineThrough else null,
            )
            TruncatedText(AnnotatedString(base), nameStyle, Modifier.fillMaxWidth())
            if (hasSub) {
                Row(Modifier.height(subLine.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tag?.let { (label, tint) -> ReviewTag(label, tint) }
                    if (dir.isNotEmpty()) {
                        StartTruncatedText(dir, ppmText(if (mobile) 12f else 11f, lineHeight = subLine).copy(color = tokens.text3.color), Modifier.weight(1f))
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (block in review.blocks.take(MAX_DOTS)) BlockDot(block.state, if (mobile) 10.dp else 8.dp)
            }
            if (outcome != null) {
                val color = when (outcome.tone) {
                    FileOutcome.Tone.KEPT -> tokens.accent
                    FileOutcome.Tone.REVERTED -> tokens.error
                    FileOutcome.Tone.MIXED -> tokens.text3
                }
                BasicText(
                    outcome.label,
                    style = pageText(10.5f, if (outcome.tone == FileOutcome.Tone.MIXED) FontWeight.Medium else FontWeight.SemiBold).copy(color = color.color),
                    maxLines = 1,
                    softWrap = false,
                )
            } else {
                BasicText(
                    "$decided/${review.blocks.size}",
                    style = ppmText(10.5f, FontWeight.Medium, FontKind.MONO, lineHeight = 10.5f).copy(color = tokens.text3.color),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** `rounded-full border-[1.5px]`: filled once decided, in the accent or the error colour; an empty ring while open. */
@Composable
private fun BlockDot(state: BlockState, size: Dp) {
    val tokens = LocalPpmTokens.current
    val color = when (state) {
        BlockState.KEPT -> tokens.accent.color
        BlockState.REVERTED -> tokens.error.color
        else -> tokens.text3.opacity(0.8).color
    }
    val filled = state != BlockState.OPEN
    Box(
        Modifier.size(size).drawBehind {
            if (filled) {
                drawCircle(color)
            } else {
                val ring = 1.5.dp.toPx()
                drawCircle(color, radius = (this.size.minDimension - ring) / 2, style = Stroke(ring))
            }
        },
    )
}

/** `flex h-8 items-center gap-1.5 px-3 text-[10.5px] font-semibold uppercase tracking-[.07em] text-text-subtle`, with its count. */
@Composable
private fun SectionHead(label: String, count: Int) {
    val tokens = LocalPpmTokens.current
    Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(
            label.uppercase(),
            style = pageText(10.5f, FontWeight.SemiBold).copy(color = tokens.text3.color, letterSpacing = 0.07.em),
            maxLines = 1,
            softWrap = false,
        )
        // `rounded-full bg-surface-hover px-1.5 py-px font-mono text-[10.5px] font-medium leading-[14px] text-text-2`.
        BasicText(
            "$count",
            Modifier.background(tokens.surfaceHover.color, CircleShape).padding(horizontal = 6.dp, vertical = 1.dp),
            style = ppmText(10.5f, FontWeight.Medium, FontKind.MONO, lineHeight = 14f).copy(color = tokens.text2.color),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Files with a block left first, then the finished ones with how they ended (`ReviewFileList`). */
@Composable
internal fun ReviewFileList(reviews: List<FileReview>, current: String?, mobile: Boolean, projectPath: String?, onPick: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    val pending = reviews.filter { it.open > 0 }
    val done = reviews.filter { it.open == 0 }
    SectionHead("To review", pending.size)
    if (pending.isEmpty()) {
        BasicText(
            "Nothing left open.",
            Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
        )
    }
    for (r in pending) FileRow(r, r.path == current, mobile, projectPath, onPick)
    if (done.isNotEmpty()) {
        SectionHead("Done", done.size)
        for (r in done) FileRow(r, r.path == current, mobile, projectPath, onPick)
    }
}

/** The toast where the pane's corner puts it: centred near the bottom on a tablet, across it on a phone. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ToastSlot(toast: ReviewToast, mobile: Boolean, onUndo: () -> Unit) {
    ReviewToastView(
        toast,
        mobile,
        onUndo,
        if (mobile) {
            Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 12.dp).fillMaxWidth()
        } else {
            Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).padding(horizontal = 12.dp)
        },
    )
}

/**
 * What an answer did, with its Undo (`ToastView`): `gap-3 rounded-[10px] border bg-panel-2 py-1.5
 * pr-1.5 pl-3.5 text-[12.5px] shadow-float` on a tablet, `rounded-xl text-[13.5px]` with a 40 px
 * Undo on a phone.
 */
@Composable
internal fun ReviewToastView(toast: ReviewToast, mobile: Boolean, onUndo: () -> Unit, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(if (mobile) 12.dp else 10.dp)
    val size = if (mobile) 13.5f else 12.5f
    val text = remember(toast, tokens) {
        buildAnnotatedString {
            append(toast.lead)
            toast.name?.let { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(it) } }
            toast.tail?.let(::append)
        }
    }
    Row(
        modifier
            .ppmShadow(tokens.shadowFloat, shape)
            .clip(shape)
            .background(tokens.panel2.color)
            .cssBorder(tokens.border.color, shape)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (mobile) 10.dp else 12.dp),
    ) {
        val style = pageText(size).copy(color = tokens.text.color)
        if (mobile) {
            BasicText(text, Modifier.weight(1f), style = style)
        } else {
            TruncatedText(text, style, Modifier.weight(1f, fill = false))
        }
        if (toast.undoId != null) {
            ReviewButton(
                "Undo",
                WebVariant.GHOST,
                if (mobile) WebSize.SM else WebSize.XS,
                height = if (mobile) 40.dp else null,
                content = tokens.accent,
                hoverContent = tokens.accent,
                onClick = onUndo,
            )
        }
    }
}

/** Nothing in focus (`ReviewEmpty`): still loading, nothing changed, or every block decided. */
@Composable
internal fun ReviewEmpty(loading: Boolean, files: Int, total: Int, kept: Int, mobile: Boolean, onChat: () -> Unit) {
    val tokens = LocalPpmTokens.current
    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text3.color, spinning = true)
        }
        return
    }
    // `max-w-[38ch]`: 38 of the paragraph's zeros.
    val measurer = rememberTextMeasurer()
    val body = pageText(13f).copy(color = tokens.text2.color, textAlign = TextAlign.Center)
    val ch = remember(measurer, body) { measurer.measure("0", body).size.width }
    val chDp = with(LocalDensity.current) { (ch * 38).toDp() }
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (files > 0) {
                Box(Modifier.padding(bottom = 14.dp).size(52.dp).background(tokens.success.opacity(0.14).color, CircleShape), contentAlignment = Alignment.Center) {
                    PpmIcon(ProductIcon.CheckCircle2, 20.dp, tokens.success.color)
                }
            }
            BasicText(
                if (files > 0) "All ${reviewPlural(total, "block")} decided" else "No changes yet",
                Modifier.padding(bottom = 6.dp),
                style = pageText(15f, FontWeight.SemiBold).copy(color = tokens.text.color, textAlign = TextAlign.Center),
            )
            BasicText(
                if (files > 0) "$kept kept, ${total - kept} reverted. A block comes back here if the agent changes it again." else "This chat has not changed any file.",
                Modifier.widthIn(max = chDp).padding(bottom = 16.dp),
                style = body,
            )
            ReviewButton("Back to chat", WebVariant.OUTLINE, WebSize.SM, icon = ProductIcon.MessageCircle, height = if (mobile) 44.dp else null, onClick = onChat)
        }
    }
}

// Chrome's flex-wrap

/**
 * Where an item of a [CssWrapRow] goes. [height] is the CSS height Chrome lays it out at, where
 * Compose would round it (0 for what it measures); [grow] is `flex-1`, taking what its line has
 * left; [shrink] lets it go narrower than its content (`min-w-0`, `truncate`), down to [minWidth].
 */
internal data class WrapItem(val height: Float = 0f, val grow: Boolean = false, val shrink: Boolean = false, val minWidth: Float = 0f)

/**
 * Chrome's `flex flex-wrap items-center` with gaps [gapX] and [gapY] inside the padding, at least
 * [minHeight] tall border box: items go on a line while they fit at their content's width, the
 * `flex-1` ones share what a line has left and the shrinkable ones give up what it lacks in
 * proportion to their width, lines share what the minimum height leaves (`align-content: normal`
 * stretches them), and each item sits in the middle of its line. A border on top or below is
 * part of the box, as CSS's `border-box` has it.
 */
@Composable
internal fun CssWrapRow(
    gapX: Dp,
    gapY: Dp,
    modifier: Modifier = Modifier,
    start: Dp = 0.dp,
    end: Dp = 0.dp,
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    minHeight: Dp = 0.dp,
    borderTop: Color? = null,
    borderBottom: Color? = null,
    content: @Composable () -> Unit,
) {
    Layout(
        content,
        modifier.drawBehind {
            val w = cssBorderPx(1.dp).toFloat()
            borderTop?.let { drawRect(it, Offset.Zero, Size(size.width, w)) }
            borderBottom?.let { drawRect(it, Offset(0f, size.height - w), Size(size.width, w)) }
        },
    ) { measurables, constraints ->
        val borders = (if (borderTop != null) cssBorderPx(1.dp) else 0) + (if (borderBottom != null) cssBorderPx(1.dp) else 0)
        val topEdge = if (borderTop != null) cssBorderPx(1.dp) else 0
        val left = start.toPx()
        val right = end.toPx()
        val gx = gapX.toPx()
        val gy = gapY.toPx()
        val items = measurables.map { it.layoutId as? WrapItem ?: WrapItem() }
        val bounded = constraints.hasBoundedWidth
        val inner = if (bounded) max(0f, constraints.maxWidth - left - right) else Float.POSITIVE_INFINITY
        // Each item's width at its content (`flex-basis: auto`); a `flex-1` one starts at nothing.
        val basis = FloatArray(measurables.size) { i -> if (items[i].grow) 0f else measurables[i].maxIntrinsicWidth(Constraints.Infinity).toFloat() }
        val lines = mutableListOf<IntRange>()
        var from = 0
        var used = 0f
        for (i in measurables.indices) {
            if (i > from && used + gx + basis[i] > inner + 0.01f) {
                lines += from until i
                from = i
                used = basis[i]
            } else {
                used += (if (i > from) gx else 0f) + basis[i]
            }
        }
        if (from < measurables.size) lines += from until measurables.size
        val width = FloatArray(measurables.size) { basis[it] }
        for (line in lines) {
            val gaps = gx * (line.count() - 1)
            val free = inner - line.sumOf { basis[it].toDouble() }.toFloat() - gaps
            if (free >= 0f) {
                val growers = line.count { items[it].grow }
                if (growers > 0 && free.isFinite()) for (i in line) if (items[i].grow) width[i] = free / growers
                continue
            }
            // What the line lacks comes off the shrinkable items in proportion to their width,
            // none going under its minimum; one that would is held there and the rest share again.
            val frozen = BooleanArray(measurables.size) { !items[it].shrink }
            while (true) {
                val active = line.filter { !frozen[it] }
                if (active.isEmpty()) break
                val taken = line.sumOf { (if (frozen[it]) width[it] else basis[it]).toDouble() }.toFloat() + gaps
                val lack = inner - taken
                if (lack >= 0f) {
                    for (i in active) width[i] = basis[i]
                    break
                }
                val scaled = active.sumOf { basis[it].toDouble() }.toFloat()
                if (scaled <= 0f) break
                var held = false
                for (i in active) {
                    val target = basis[i] + lack * basis[i] / scaled
                    val least = items[i].minWidth.dp.toPx()
                    if (target < least) {
                        width[i] = least
                        frozen[i] = true
                        held = true
                    } else {
                        width[i] = target
                    }
                }
                if (!held) break
            }
        }
        val placeables = measurables.mapIndexed { i, m ->
            when {
                items[i].grow -> m.measure(Constraints.fixed(max(0, width[i].roundToInt()), 0))
                items[i].shrink && width[i] < basis[i] -> m.measure(Constraints.fixedWidth(max(0, width[i].roundToInt())))
                else -> m.measure(Constraints(maxWidth = if (bounded) max(0, inner.roundToInt()) else Constraints.Infinity))
            }
        }
        val tall = FloatArray(placeables.size) { i ->
            val h = items[i].height
            when {
                items[i].grow -> 0f
                h > 0f -> h.dp.toPx()
                else -> placeables[i].height.toFloat()
            }
        }
        val lineHeights = lines.map { line -> line.maxOfOrNull { tall[it] } ?: 0f }
        val contentHeight = lineHeights.sum() + gy * max(0, lines.size - 1)
        val padY = top.toPx() + bottom.toPx()
        val least = minHeight.toPx() - padY - borders
        val extra = if (lines.isEmpty()) 0f else max(0f, least - contentHeight) / lines.size
        val height = (padY + max(contentHeight, least) + borders).roundToInt()
        val contentWidth = lines.maxOfOrNull { line -> line.sumOf { width[it].toDouble() }.toFloat() + gx * (line.count() - 1) } ?: 0f
        val boxWidth = if (bounded) constraints.maxWidth else constraints.constrainWidth((contentWidth + left + right).roundToInt())
        layout(boxWidth, max(height, constraints.minHeight)) {
            var y = topEdge + top.toPx()
            lines.forEachIndexed { n, line ->
                val lineHeight = lineHeights[n] + extra
                var x = left
                for ((k, i) in line.withIndex()) {
                    if (k > 0) x += gx
                    placeables[i].place(x.roundToInt(), (y + (lineHeight - tall[i]) / 2).roundToInt())
                    x += width[i]
                }
                y += lineHeight + gy
            }
        }
    }
}
