package com.hienlh.ppm.chat

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CssExact
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.HighlightedCode
import com.hienlh.ppm.design.LocalPpmTheme
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmFormat
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.SyntaxHighlighter
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.cssExact
import com.hienlh.ppm.design.cssHeight
import com.hienlh.ppm.design.cssLazyItem
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.cssMinHeight
import com.hienlh.ppm.design.cssPhase
import com.hienlh.ppm.design.cssPhased
import com.hienlh.ppm.design.cssReport
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberCssExact
import com.hienlh.ppm.design.rememberCssLazyStack
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.SessionFileChange
import com.hienlh.ppm.state.BlockState
import com.hienlh.ppm.state.FileOutcome
import com.hienlh.ppm.state.FileReview
import com.hienlh.ppm.state.PaneItem
import com.hienlh.ppm.state.PaneModel
import com.hienlh.ppm.state.ReviewBlocks
import com.hienlh.ppm.state.ReviewPane
import com.hienlh.ppm.state.SessionReviewModel
import com.hienlh.ppm.state.SessionReviewState
import com.hienlh.ppm.state.SessionTurn
import com.hienlh.ppm.state.SessionTurns
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The file in focus on the Review tab, drawn the way an editor's review draws it
 * (`review-code.tsx`): each change block where it sits in the file, with the unchanged lines
 * between blocks folded away. An open block shows its diff and, on a tablet, its Keep and
 * Revert; a decided one shows only the lines that stay. Each block names the turns that wrote
 * it, and a turn opens to the prompt that asked for it.
 */

/** What the code pane does on the Review tab's behalf (`ReviewCodeActions`). */
class ReviewCodeActions(
    val focus: (key: String) -> Unit,
    val keep: (key: String) -> Unit,
    val revert: (key: String) -> Unit,
    val reopen: (key: String) -> Unit,
    val undo: (undoId: String, key: String) -> Unit,
    /** Show the chat at the call that wrote a block, or at the turn's prompt. */
    val showInChat: (turn: SessionTurn, call: String) -> Unit,
)

/** A phone's bottom sheet, as the app draws one; [content] is told where in its first pixel it starts. */
typealias ReviewSheet = @Composable (onDismiss: () -> Unit, content: @Composable ColumnScope.(top: Dp) -> Unit) -> Unit

/** A turn that wrote a block, and the first of the block's calls it made. */
@Immutable
data class TurnChip(val turn: SessionTurn, val call: String)

/** The turns that wrote a block, oldest first (`blockTurns`). */
internal fun blockTurns(calls: List<String>?, turns: Map<String, SessionTurn>): List<TurnChip> {
    val out = mutableListOf<TurnChip>()
    for (call in calls.orEmpty()) {
        val turn = turns[call] ?: continue
        if (out.none { it.turn === turn }) out += TurnChip(turn, call)
    }
    return out.sortedWith(compareBy(nullsLast()) { it.turn.at })
}

/** The language to ask Shiki for: the file's extension, which Shiki takes as an alias for most (`reviewLanguage`). */
internal fun reviewLanguage(path: String): String? {
    val name = baseName(path).lowercase()
    if (name == "dockerfile" || name.endsWith(".dockerfile")) return "docker"
    if (name == "makefile") return "make"
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(dot + 1) else null
}

internal fun reviewPlural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

private fun baseName(path: String) = path.substring(max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1)

/**
 * What the pane keeps while its file stays the same, as the web keys it by the file's path: the
 * folds opened, and the one turn whose card is open — under its chip on a tablet, in a sheet on
 * a phone.
 */
@Stable
class ReviewCodeState internal constructor() {
    internal val opened = mutableStateMapOf<Int, Int>()
    internal var pop by mutableStateOf<TurnPop?>(null)

    /** The card and the chips, in the root's coordinates: a press anywhere else closes the card. */
    internal val regions = HashMap<String, Rect>()

    /** Closes the turn card; whether one was open (Escape answers this before the tab's own keys). */
    fun closeTurn(): Boolean {
        if (pop == null) return false
        pop = null
        return true
    }

    internal fun toggle(key: String, chip: TurnChip) {
        val current = pop
        pop = if (current?.key == key && current.chip.turn.messageId == chip.turn.messageId) null else TurnPop(key, chip)
    }
}

@Immutable
internal class TurnPop(val key: String, val chip: TurnChip)

@Composable
fun rememberReviewCodeState(path: String): ReviewCodeState = remember(path) { ReviewCodeState() }

/**
 * Closes [state]'s turn card on a press outside it and outside every chip, as the web's
 * capture-phase `pointerdown` does; the tablet's Review tab puts it round the whole tab too.
 */
@Composable
fun Modifier.closesTurnOutside(state: ReviewCodeState, enabled: Boolean = true): Modifier {
    val host = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return onGloballyPositioned { host[0] = it }.pointerInput(state, enabled) {
        if (!enabled) return@pointerInput
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Press || state.pop == null) continue
                val coords = host[0]?.takeIf { it.isAttached } ?: continue
                val at = coords.localToRoot(event.changes.firstOrNull()?.position ?: continue)
                if (state.regions.values.none { it.contains(at) }) state.pop = null
            }
        }
    }
}

/** The pane's sizes on a tablet (`text-[12px] leading-[1.7]`, columns 40/40/18) and a phone (`text-[11px] leading-[1.65]`, 28/28/14). */
private class CodeMetrics(
    val compact: Boolean,
    val size: Float,
    val ratio: Float,
    val gutter: Float,
    val signColumn: Float,
    val numberPad: Float,
    val codePad: Float,
    val marginStart: Float,
    val marginEnd: Float,
    val padTop: Float,
    val padBottom: Float,
    val foldStart: Float,
    val foldEnd: Float,
    val foldHeight: Float,
    val foldButton: Float,
) {
    val line get() = size * ratio

    /** `text-[0.92em]`, on a line box of the same ratio. */
    val numberSize get() = size * 0.92f
    val numberLine get() = numberSize * ratio
    val codeX get() = 2 * gutter + signColumn
}

private val WIDE = CodeMetrics(false, 12f, 1.7f, 40f, 18f, 8f, 14f, 8f, 12f, 10f, 24f, 50f, 14f, 26f, 22f)
private val COMPACT = CodeMetrics(true, 11f, 1.65f, 28f, 14f, 5f, 10f, 6f, 8f, 8f, 12f, 34f, 10f, 44f, 44f)

/** Unchanged lines a fold opens at a time; a fold not much longer opens whole. */
private const val GAP_STEP = 200

/** The block head's `text-xs leading-[1.3]`. */
private const val HEAD_LINE = 12f * 1.3f

private enum class NoteKind { SINCE_REVIEW, VS_HEAD }

private sealed interface CodeEntry {
    val key: String
}

private class NoteEntry(val kind: NoteKind) : CodeEntry {
    override val key get() = "note"
}

private class GapEntry(val gap: PaneItem.Gap) : CodeEntry {
    override val key get() = "gap:${gap.from}"
}

private class BlockEntry(val block: PaneItem.Block) : CodeEntry {
    override val key get() = "block:${block.key}"
}

/**
 * The blocks of [model], [focusKey] the one in focus, with the folds between them (`ReviewCode`),
 * in the scroller `relative overflow-auto bg-bg font-mono [tab-size:4]`, `pt-2.5 pb-6` (`pt-2
 * pb-3`). [compact] is the phone's layout: narrower gutters, and the answers live in the bottom
 * bar. [turns] is each call's turn, for the turns a block names.
 */
@Composable
fun ReviewCode(
    file: SessionFileChange,
    model: PaneModel,
    focusKey: String?,
    compact: Boolean,
    turns: Map<String, SessionTurn>,
    actions: ReviewCodeActions,
    state: ReviewCodeState,
    sheet: ReviewSheet,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val density = LocalDensity.current
    val m = if (compact) COMPACT else WIDE
    val act by rememberUpdatedState(actions)
    val language = remember(file.path) { reviewLanguage(file.path) }
    val note = when {
        file.sinceReview -> NoteKind.SINCE_REVIEW
        file.baseline == "head" -> NoteKind.VS_HEAD
        else -> null
    }
    val entries = remember(model.items, note) {
        buildList {
            if (note != null) add(NoteEntry(note))
            for (item in model.items) add(if (item is PaneItem.Block) BlockEntry(item) else GapEntry(item as PaneItem.Gap))
        }
    }
    val chips = remember(model.items, turns) {
        model.items.filterIsInstance<PaneItem.Block>().associate { it.key to blockTurns(it.calls, turns) }
    }
    val list = rememberLazyListState()
    // Every item is `my-1.5` or folds to it: margins of 6, 2 or 0 collapse to 6 between any two.
    val stack = rememberCssLazyStack(6.dp)
    val pop = state.pop
    val tracking = pop != null && !compact

    fun lead(entry: CodeEntry): Float = when (entry) {
        is NoteEntry -> 0f
        is BlockEntry -> 6f
        is GapEntry -> if ((state.opened[entry.gap.from] ?: 0) > 0) 0f else 2f
    }

    fun trail(entry: CodeEntry): Float = when (entry) {
        is NoteEntry, is BlockEntry -> 6f
        is GapEntry -> if (entry.gap.to - entry.gap.from - (state.opened[entry.gap.from] ?: 0) > 0) 2f else 0f
    }

    // Bring the block in focus into view unless it already is: its top 12 px under the pane's
    // and its bottom (or as much of it as the pane less 40 px holds) inside.
    LaunchedEffect(focusKey, model.total) {
        val key = focusKey ?: return@LaunchedEffect
        val index = entries.indexOfFirst { it is BlockEntry && it.block.key == key }
        if (index < 0) return@LaunchedEffect
        withFrameNanos { }
        val px = density.density
        val info = list.layoutInfo
        val view = info.viewportSize.height
        val lead = if (index == 0) (m.padTop + 6f) * px else 0f
        val margin = 12f * px
        val seen = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (seen != null) {
            val tail = if (index == entries.lastIndex) (trail(entries[index]) + m.padBottom) * px else 6f * px
            val cardTop = seen.offset + lead
            val bottom = cardTop + min(seen.size - lead - tail, view - 40f * px)
            if (cardTop - margin >= 0 && bottom <= view) return@LaunchedEffect
        }
        val offset = (lead - margin).roundToInt()
        list.scrollToItem(index, max(0, offset))
        if (offset < 0) list.scrollBy(offset.toFloat())
    }

    Box(modifier.fillMaxSize().background(tokens.bgSolid.color).closesTurnOutside(state, enabled = !compact)) {
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
                val first = index == 0
                val last = index == entries.lastIndex
                val raised = entry is BlockEntry && tracking && pop?.key == entry.block.key
                CssColumn(
                    Modifier
                        .then(if (raised) Modifier.zIndex(1f) else Modifier)
                        .cssLazyItem(stack, index, last)
                        .fillMaxWidth(),
                    top = (if (first) m.padTop + lead(entry) else 0f).dp,
                    bottom = (if (last) trail(entry) + m.padBottom else 0f).dp,
                ) {
                    when (entry) {
                        is NoteEntry -> ReviewNote(entry.kind, baseName(file.path), m)
                        is GapEntry -> {
                            val gap = entry.gap
                            ReviewGap(gap, model, state.opened[gap.from] ?: 0, m, language) { state.opened[gap.from] = it }
                        }
                        is BlockEntry -> ReviewBlock(
                            item = entry.block,
                            total = model.total,
                            focused = entry.block.key == focusKey,
                            m = m,
                            language = language,
                            chips = chips[entry.block.key].orEmpty(),
                            openTurn = if (!compact && pop?.key == entry.block.key) pop.chip else null,
                            tracking = tracking,
                            state = state,
                            act = act,
                        )
                    }
                }
            }
        }
    }
    if (compact && pop != null) {
        sheet({ state.pop = null }) { _ ->
            TurnCard(pop.chip, compact = true, onClose = { state.pop = null }, act = act)
        }
    }
}

// Notes

/** The note over a file reviewed before, or compared with the last commit (`Note`). */
@Composable
private fun ReviewNote(kind: NoteKind, name: String, m: CodeMetrics) {
    val tokens = LocalPpmTokens.current
    val since = kind == NoteKind.SINCE_REVIEW
    val tint = if (since) tokens.accent2 else tokens.warning
    val text = remember(since, name, tokens) {
        buildAnnotatedString {
            if (since) {
                append("You reviewed this file before; only what changed after that is open again.")
            } else {
                append("This chat kept no copy of ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = tokens.text.color)) { append(name) }
                append(" from before it changed it, so these blocks compare with the last commit.")
            }
        }
    }
    val box = rememberCssExact()
    val words = rememberCssExact()
    // `mb-1.5 flex items-center gap-2 rounded-lg px-3 py-2 font-sans text-xs leading-[1.45]`, `mx-3 ml-2` (`mx-2 ml-1.5`).
    Box(
        Modifier
            .padding(start = m.marginStart.dp, end = m.marginEnd.dp)
            .cssExact(box)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(tint.opacity(0.09).color),
    ) {
        CssColumn(top = 8.dp, bottom = 8.dp, exact = box) {
            Row(Modifier.padding(horizontal = 12.dp).cssExact(words), verticalAlignment = Alignment.CenterVertically) {
                PpmIcon(if (since) ProductIcon.History else ProductIcon.GitBranch, 14.dp, tint.color)
                Spacer(Modifier.width(8.dp))
                CssColumn(exact = words) {
                    BasicText(text, Modifier.cssLines(12f * 1.45f), ppmText(12f, lineHeight = 12f * 1.45f).copy(color = tokens.text2.color))
                }
            }
        }
    }
}

// Folds

/** Folded unchanged lines, opened [GAP_STEP] at a time from the top (`Gap`). */
@Composable
private fun ReviewGap(gap: PaneItem.Gap, model: PaneModel, shown: Int, m: CodeMetrics, language: String?, onOpen: (Int) -> Unit) {
    val rows = remember(gap.from, gap.to, shown, model) {
        (gap.from until min(gap.from + shown, gap.to)).map { i ->
            ReviewBlocks.Row(ReviewBlocks.Kind.CONTEXT, model.lines.getOrElse(i) { "" }, model.baseLine.getOrElse(i) { 0 }.takeIf { it > 0 }, i + 1)
        }
    }
    val left = gap.to - gap.from - shown
    val next = if (left <= GAP_STEP * 3 / 2) left else GAP_STEP
    if (rows.isNotEmpty()) {
        // Inset by a block's margins plus its 1 px border, so a line number keeps its column
        // across the card's edge.
        ReviewRows(rows, BlockState.KEPT, m, language, plain = true, modifier = Modifier.padding(start = (m.marginStart + 1).dp, end = (m.marginEnd + 1).dp))
    }
    if (left > 0) {
        // Under opened lines the bar's `my-0.5` is the only margin between them.
        if (rows.isNotEmpty()) {
            CssColumn(top = 2.dp) { FoldBar(left, shown, next, m) { onOpen(shown + next) } }
        } else {
            FoldBar(left, shown, next, m) { onOpen(shown + next) }
        }
    }
}

/** `repeating-linear-gradient(90deg, border 0 4px, transparent 4px 8px)` on a 1 px line through the middle. */
private fun Modifier.dashedRule(color: Color): Modifier = drawBehind {
    val dash = 4.dp.toPx()
    val top = (size.height / 2 - 0.5.dp.toPx()).roundToInt().toFloat()
    val bottom = max(top + 1, (size.height / 2 + 0.5.dp.toPx()).roundToInt().toFloat())
    var x = 0f
    while (x < size.width) {
        val start = x.roundToInt().toFloat()
        val end = min(x + dash, size.width).roundToInt().toFloat()
        if (end > start) drawRect(color, Offset(start, top), Size(end - start, bottom - top))
        x += 2 * dash
    }
}

/**
 * `my-0.5 flex items-center gap-2 font-sans text-[11.5px] font-medium text-text-subtle`, `h-[26px]
 * pl-[50px]` (`min-h-11 pl-[34px]`): a dashed rule, the button, and a rule to the end.
 */
@Composable
private fun FoldBar(left: Int, shown: Int, next: Int, m: CodeMetrics, onOpen: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(instant = true)
    val pressed by hover.source.collectIsPressedAsState()
    val color = (if (pressed || hover.lit > 0f) tokens.text else tokens.text3).color
    val label = reviewPlural(left, if (shown > 0) "more unchanged line" else "unchanged line")
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (m.compact) Modifier.cssMinHeight(m.foldHeight.dp) else Modifier.cssHeight(m.foldHeight.dp))
            .padding(start = m.foldStart.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(12.dp).height(m.foldHeight.dp).dashedRule(tokens.border.color))
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier
                .height(m.foldButton.dp)
                .then(if (next < left) Modifier.webTitle("Show the next $next") else Modifier)
                .ppmClickable(
                    pressedColor = tokens.surfaceHover.color,
                    hoverColor = tokens.surfaceHover.color,
                    fade = false,
                    shape = RoundedCornerShape(5.dp),
                    onClickLabel = label,
                    source = hover.source,
                    onClick = onOpen,
                )
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(ProductIcon.ChevronsUpDown, 14.dp, color)
            BasicText(label, style = ppmText(11.5f, FontWeight.Medium, lineHeight = 11.5f * m.ratio).copy(color = color), maxLines = 1, softWrap = false)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).padding(end = m.foldEnd.dp).height(m.foldHeight.dp).dashedRule(tokens.border.color))
    }
}

// Blocks

/**
 * One block (`Block`): a card in the file's flow (`my-1.5 rounded-[10px] border border-border-soft
 * bg-bg`), its head naming its place, its turns and, on a tablet, its answers; its rows under it.
 * Dashed once decided; in focus, outlined in the accent with a 3 px wash around it. A tap
 * anywhere but a button puts it in focus. The open turn's card hangs under the head.
 */
@Composable
private fun ReviewBlock(
    item: PaneItem.Block,
    total: Int,
    focused: Boolean,
    m: CodeMetrics,
    language: String?,
    chips: List<TurnChip>,
    openTurn: TurnChip?,
    tracking: Boolean,
    state: ReviewCodeState,
    act: ReviewCodeActions,
) {
    val tokens = LocalPpmTokens.current
    val open = item.state == BlockState.OPEN
    val card = rememberCssExact()
    val head = rememberCssExact()
    val headBottom = remember { FloatArray(1) }
    Layout(
        content = {
            Box(
                Modifier
                    .then(if (focused) Modifier.focusRing(tokens.accentWash.color) else Modifier)
                    .background(tokens.bgSolid.color, RoundedCornerShape(10.dp))
                    .editCardBorder(if (focused) tokens.accent.opacity(0.6).color else tokens.borderSoft.color, 10.dp, dashed = !open)
                    .then(
                        if (focused) {
                            Modifier
                        } else {
                            Modifier
                                .pointerHoverIcon(WebCursor.Pointer)
                                .ppmClickable(pressedColor = Color.Transparent, onClickLabel = "Block ${item.index + 1} of $total") { act.focus(item.key) }
                        },
                    ),
            ) {
                CssColumn(exact = card) {
                    BlockHead(item, total, focused, m.compact, chips, openTurn, tracking, head, headBottom, state, act)
                    BlockBody(item, m, language)
                }
            }
            if (openTurn != null) TurnPopover(openTurn, state, act)
        },
        modifier = Modifier.padding(start = m.marginStart.dp, end = m.marginEnd.dp).cssExact(card),
    ) { measurables, constraints ->
        val placed = measurables[0].measure(constraints)
        val border = cssBorderPx(1.dp)
        // `w-[330px] max-w-[calc(100%-24px)]` of the head's width.
        val popWidth = max(0, min(330.dp.roundToPx(), placed.width - 2 * border - 24.dp.roundToPx()))
        val pop = measurables.getOrNull(1)?.measure(Constraints(minWidth = popWidth, maxWidth = popWidth))
        layout(placed.width, placed.height) {
            placed.place(0, 0)
            // `absolute top-full left-2 mt-1`: under the head's padding box.
            pop?.place(border + 8.dp.roundToPx(), (border + headBottom[0] + 4.dp.toPx()).roundToInt())
        }
    }
}

/** `shadow-[0_0_0_3px_var(--accent-wash)]` round the card's `rounded-[10px]` border box. */
private fun Modifier.focusRing(color: Color): Modifier = drawBehind {
    val band = 3.dp.toPx()
    drawRoundRect(
        color,
        topLeft = Offset(-band / 2, -band / 2),
        size = Size(size.width + band, size.height + band),
        cornerRadius = CornerRadius(10.dp.toPx() + band / 2),
        style = Stroke(band),
    )
}

/** A child of the block's head: its CSS height (a line's, for text) and whether it is the `flex-1` spacer. */
@Immutable
private class HeadItem(val height: Float, val grow: Boolean = false)

/**
 * The block's head (`flex flex-wrap items-center gap-x-2 gap-y-1.5 rounded-t-[9px] py-1 pr-1.5
 * pl-2.5 text-xs leading-[1.3] text-text-subtle`): `min-h-9 border-b` on the panel while open,
 * `min-h-[30px]` once decided.
 */
@Composable
private fun BlockHead(
    item: PaneItem.Block,
    total: Int,
    focused: Boolean,
    compact: Boolean,
    chips: List<TurnChip>,
    openTurn: TurnChip?,
    tracking: Boolean,
    exact: CssExact,
    bottom: FloatArray,
    state: ReviewCodeState,
    act: ReviewCodeActions,
) {
    val tokens = LocalPpmTokens.current
    val open = item.state == BlockState.OPEN
    val kept = item.state == BlockState.KEPT
    val xs = ppmText(12f, lineHeight = HEAD_LINE).copy(color = tokens.text3.color)
    val fill = when {
        !open -> Color.Transparent
        focused -> tokens.accent.mix(tokens.panel, 0.07).color
        else -> tokens.panel.color
    }
    HeadFlow(
        minHeight = if (open) 36f else 30f,
        bottomBorder = open,
        exact = exact,
        bottom = bottom,
        modifier = Modifier.fillMaxWidth().background(fill, RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp)),
    ) {
        if (open) {
            val label = remember(item.index, total, tokens) {
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = tokens.text2.color)) { append("Block ${item.index + 1}") }
                    append(" of $total")
                }
            }
            BasicText(label, Modifier.layoutId(HeadItem(HEAD_LINE)), style = xs)
        } else {
            val tone = (if (kept) tokens.accent else tokens.error).color
            Row(Modifier.layoutId(HeadItem(HEAD_LINE)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PpmIcon(if (kept) ProductIcon.Check else ProductIcon.RotateCcw, 14.dp, tone)
                BasicText(if (kept) "Kept" else "Reverted", style = ppmText(12f, FontWeight.SemiBold, lineHeight = HEAD_LINE).copy(color = tone), maxLines = 1, softWrap = false)
            }
            BasicText("· block ${item.index + 1} of $total", Modifier.layoutId(HeadItem(HEAD_LINE)), style = xs)
        }
        for (chip in chips) {
            TurnChipButton(
                chip,
                expanded = openTurn?.turn == chip.turn,
                tracking = tracking,
                region = "chip:${item.key}:${chip.turn.messageId}",
                state = state,
                modifier = Modifier.layoutId(HeadItem(24f)),
            ) { state.toggle(item.key, chip) }
        }
        Box(Modifier.layoutId(HeadItem(0f, grow = true)))
        if (!compact) {
            if (open) {
                AnswerButton(keep = false, focused = focused, Modifier.layoutId(HeadItem(28f))) { act.revert(item.key) }
                AnswerButton(keep = true, focused = focused, Modifier.layoutId(HeadItem(28f))) { act.keep(item.key) }
            } else {
                ChangeButton(item, act, Modifier.layoutId(HeadItem(24f)))
            }
        }
    }
}

/**
 * Chrome's `flex flex-wrap items-center` with `gap-x-2 gap-y-1.5` inside `py-1 pr-1.5 pl-2.5`, at
 * least [minHeight] tall with the border under it: items go on a line until the next one does
 * not fit, the `flex-1` one takes what its line has left, lines share what the minimum height
 * leaves (`align-content: normal` stretches them), and each item sits in the middle of its line.
 * Each child's [HeadItem] height is what Chrome lays it out at, where Compose rounds it.
 * [bottom] gets the bottom of the head's padding box, from the top of its first pixel.
 */
@Composable
private fun HeadFlow(
    minHeight: Float,
    bottomBorder: Boolean,
    exact: CssExact,
    bottom: FloatArray,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val borderColor = LocalPpmTokens.current.borderSoft.color
    Layout(
        content,
        modifier
            .cssPhased(exact)
            .then(
                if (bottomBorder) {
                    Modifier.drawBehind {
                        val w = cssBorderPx(1.dp).toFloat()
                        drawRect(borderColor, Offset(0f, size.height - w), Size(size.width, w))
                    }
                } else {
                    Modifier
                },
            ),
    ) { measurables, constraints ->
        val phase = exact.cssPhase()
        val border = if (bottomBorder) cssBorderPx(1.dp) else 0
        val start = 10.dp.toPx()
        val end = 6.dp.toPx()
        val padY = 4.dp.toPx()
        val gapX = 8.dp.toPx()
        val gapY = 6.dp.toPx()
        val inner = max(0f, constraints.maxWidth - start - end)
        val items = measurables.map { it.layoutId as? HeadItem }
        val placeables = measurables.mapIndexed { i, child ->
            if (items[i]?.grow == true) child.measure(Constraints.fixed(0, 0)) else child.measure(Constraints(maxWidth = inner.toInt()))
        }
        // Each item's exact height: a text's lines at its line height, a box's set height.
        val tall = FloatArray(placeables.size) { i ->
            val h = (items[i]?.height ?: 0f).dp.toPx()
            if (h <= 0f) placeables[i].height.toFloat() else max(1, (placeables[i].height / ceil(h)).roundToInt()) * h
        }
        val lines = mutableListOf<IntRange>()
        var from = 0
        var used = 0f
        for (i in placeables.indices) {
            val w = placeables[i].width.toFloat()
            if (i > from && used + gapX + w > inner + 0.01f) {
                lines += from until i
                from = i
                used = w
            } else {
                used += (if (i > from) gapX else 0f) + w
            }
        }
        if (from < placeables.size) lines += from until placeables.size
        val lineHeights = lines.map { line -> line.maxOfOrNull { tall[it] } ?: 0f }
        val contentHeight = lineHeights.sum() + gapY * max(0, lines.size - 1)
        val least = minHeight.dp.toPx() - 2 * padY - border
        val extra = if (lines.isEmpty()) 0f else max(0f, least - contentHeight) / lines.size
        val total = 2 * padY + max(contentHeight, least) + border
        val height = (phase + total).roundToInt()
        exact.cssReport(total, height)
        bottom[0] = phase + total - border
        layout(constraints.maxWidth, height) {
            var y = phase + padY
            lines.forEachIndexed { n, line ->
                val lineHeight = lineHeights[n] + extra
                val free = inner - (line.sumOf { placeables[it].width } + gapX * (line.count() - 1))
                var x = start
                for ((k, i) in line.withIndex()) {
                    if (k > 0) x += gapX
                    val p = placeables[i]
                    p.place(x.roundToInt(), (y + (lineHeight - tall[i]) / 2).roundToInt())
                    x += if (items[i]?.grow == true) max(0f, free) else p.width.toFloat()
                }
                y += lineHeight + gapY
            }
        }
    }
}

/**
 * A turn that wrote the block (`h-6 rounded-md border px-[7px] text-[11.5px] text-text-2`): its
 * number and time, its card when tapped. While a card is open every chip says where it is, as
 * a press on any chip leaves the card to the chip's own click.
 */
@Composable
private fun TurnChipButton(
    chip: TurnChip,
    expanded: Boolean,
    tracking: Boolean,
    region: String,
    state: ReviewCodeState,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(instant = true)
    val pressed by hover.source.collectIsPressedAsState()
    val lit = expanded || pressed || hover.lit > 0f
    val shape = RoundedCornerShape(6.dp)
    val label = SessionTurns.label(chip.turn)
    val time = chip.turn.at?.let { PpmFormat.turnTime(it) }.orEmpty()
    DisposableEffect(region, tracking) { onDispose { state.regions.remove(region) } }
    Row(
        modifier
            .height(24.dp)
            .then(if (tracking) Modifier.onGloballyPositioned { state.regions[region] = it.boundsInRoot() } else Modifier)
            .webTitle(chip.turn.prompt)
            .clip(shape)
            .background((if (lit) tokens.panel2 else tokens.bgSolid).color)
            .ppmClickable(pressedColor = Color.Transparent, shape = shape, onClickLabel = label, source = hover.source, onClick = onClick)
            .cssBorder((if (lit) tokens.border else tokens.borderSoft).color, shape)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val color = (if (lit) tokens.text else tokens.text2).color
        PpmIcon(ProductIcon.MessageCircle, 12.dp, color)
        BasicText(label, style = ppmText(11.5f, FontWeight.SemiBold, lineHeight = 11.5f * 1.3f).copy(color = tokens.text.color), maxLines = 1, softWrap = false)
        if (time.isNotEmpty()) BasicText("· $time", style = ppmText(11.5f, lineHeight = 11.5f * 1.3f).copy(color = color), maxLines = 1, softWrap = false)
    }
}

/**
 * Revert (`border-border bg-bg text-text`, the error colour under a mouse) or Keep (the accent),
 * `h-7 rounded-[7px] border pl-2.5 text-[12.5px] font-medium transition-colors`, `pr-1.5` and
 * its key while the block is in focus.
 */
@Composable
private fun AnswerButton(keep: Boolean, focused: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(7.dp)
    val hover = rememberHover()
    val pressed by hover.source.collectIsPressedAsState()
    val lit = if (pressed) 1.0 else hover.lit.toDouble()
    val label = if (keep) "Keep" else "Revert"
    val content = if (keep) tokens.accentFg else tokens.error.mix(tokens.text, lit)
    Row(
        modifier
            .height(28.dp)
            .webTitle(if (keep) "Keep this change (Y)" else "Put these lines back (N)")
            .clip(shape)
            .background((if (keep) tokens.accent.opacity(1 - 0.1 * lit) else tokens.bgSolid).color)
            .ppmClickable(pressedColor = Color.Transparent, shape = shape, onClickLabel = label, source = hover.source, onClick = onClick)
            .cssBorder(if (keep) Color.Transparent else tokens.error.opacity(0.5).mix(tokens.border, lit).color, shape)
            .padding(start = 10.dp, end = if (focused) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(if (keep) ProductIcon.Check else ProductIcon.RotateCcw, 14.dp, content.color)
        BasicText(label, style = ppmText(12.5f, FontWeight.Medium, lineHeight = 12.5f * 1.3f).copy(color = content.color), maxLines = 1, softWrap = false)
        if (focused) ReviewKbd(if (keep) "Y" else "N", onAccent = keep)
    }
}

/**
 * A key's cap (`Kbd`): `inline-grid h-4 min-w-4 place-items-center rounded border border-b-2
 * border-border bg-panel px-1 font-mono text-[10px] font-medium leading-none text-text-2`; on the
 * accent `border-white/30 bg-white/15 text-inherit`.
 */
@Composable
internal fun ReviewKbd(label: String, onAccent: Boolean = false, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val fill = if (onAccent) Color.White.copy(alpha = 0.15f) else tokens.panel.color
    val edge = if (onAccent) Color.White.copy(alpha = 0.3f) else tokens.border.color
    val text = if (onAccent) tokens.accentFg.color else tokens.text2.color
    val density = LocalDensity.current
    val thin = with(density) { cssBorderPx(1.dp) }
    val thick = with(density) { cssBorderPx(2.dp) }
    val (thinDp, thickDp) = with(density) { thin.toDp() to thick.toDp() }
    Box(
        modifier
            .height(16.dp)
            .widthIn(min = 16.dp)
            .drawWithCache {
                val r = 4.dp.toPx()
                val outside = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r))) }
                val inside = Path().apply {
                    val top = CornerRadius(max(0f, r - thin))
                    val low = CornerRadius(max(0f, r - thin), max(0f, r - thick))
                    addRoundRect(RoundRect(thin.toFloat(), thin.toFloat(), size.width - thin, size.height - thick, top, top, low, low))
                }
                val ring = Path.combine(PathOperation.Difference, outside, inside)
                onDrawBehind {
                    drawPath(inside, fill)
                    drawPath(ring, edge)
                }
            }
            .padding(start = thinDp + 4.dp, end = thinDp + 4.dp, top = thinDp, bottom = thickDp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = ppmText(10f, FontWeight.Medium, FontKind.MONO, lineHeight = 10f).copy(color = text), maxLines = 1, softWrap = false)
    }
}

/**
 * Open a decided block again (`ChangeButton`, `h-6 gap-1 rounded-[5px] px-1.5 text-[11.5px]
 * font-medium text-text-2`): a kept one is reopened, a reverted one's revert undone.
 */
@Composable
private fun ChangeButton(item: PaneItem.Block, act: ReviewCodeActions, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val reverted = item.state == BlockState.REVERTED
    val undoId = item.undoId
    val enabled = !reverted || undoId != null
    val hover = rememberHover(enabled = enabled, instant = true)
    val pressed by hover.source.collectIsPressedAsState()
    val color = (if ((pressed && enabled) || hover.lit > 0f) tokens.text else tokens.text2).color
    Row(
        modifier
            .height(24.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .webTitle(if (reverted) "Put back what the agent wrote here" else "Open this block again")
            .ppmClickable(
                enabled = enabled,
                pressedColor = tokens.surfaceHover.color,
                hoverColor = tokens.surfaceHover.color,
                fade = false,
                shape = RoundedCornerShape(5.dp),
                onClickLabel = "Change",
                source = hover.source,
            ) {
                if (!reverted) act.reopen(item.key) else if (undoId != null) act.undo(undoId, item.key)
            }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PpmIcon(ProductIcon.Undo2, 14.dp, color)
        BasicText("Change", style = ppmText(11.5f, FontWeight.Medium, lineHeight = 11.5f * 1.3f).copy(color = color), maxLines = 1, softWrap = false)
    }
}

/** The block's rows (`overflow-hidden rounded-b-[9px]`), with a 2 px inset edge in the accent once kept, the error colour once reverted. */
@Composable
private fun BlockBody(item: PaneItem.Block, m: CodeMetrics, language: String?) {
    val tokens = LocalPpmTokens.current
    val edge = when (item.state) {
        BlockState.KEPT -> tokens.accent.opacity(0.75).color
        BlockState.REVERTED -> tokens.error.opacity(0.6).color
        BlockState.OPEN -> null
    }
    ReviewRows(
        item.rows,
        item.state,
        m,
        language,
        modifier = Modifier
            .clip(RoundedCornerShape(bottomStart = 9.dp, bottomEnd = 9.dp))
            .then(
                if (edge == null) {
                    Modifier
                } else {
                    // `box-shadow: inset 2px 0 0`: the body less itself moved 2 px right, so the
                    // edge follows the bottom corner's curve.
                    Modifier.drawWithCache {
                        val r = CornerRadius(9.dp.toPx())
                        fun body(dx: Float) = Path().apply {
                            addRoundRect(RoundRect(dx, 0f, size.width + dx, size.height, CornerRadius.Zero, CornerRadius.Zero, r, r))
                        }
                        val band = Path.combine(PathOperation.Difference, body(0f), body(2.dp.toPx()))
                        onDrawBehind { drawPath(band, edge) }
                    }
                },
            ),
    )
}

// Rows

/** One row as drawn: its code with Chrome's line ends marked, and the pieces of it that changed. */
@Immutable
private class DrawnRow(
    val row: ReviewBlocks.Row,
    val code: AnnotatedString,
    /** `[start, end)` in [code] of each piece of the changed span: one per token it crosses, each its own rounded box on the web. */
    val pieces: List<IntRange>,
    val add: Boolean,
    val del: Boolean,
    val old: String,
    val new: String,
)

/**
 * A block's rows (`Rows`): the diff while it is open, the lines that stay once it is answered,
 * on the grid `40px 40px 18px 1fr` (`28px 28px 14px 1fr`). [plain] draws unchanged lines with
 * both numbers, for an opened fold. Each row is its code's lines tall, stacked as Chrome stacks
 * them in fractions of a pixel; the numbers, the sign and the tints are drawn behind the code.
 */
@Composable
private fun ReviewRows(rows: List<ReviewBlocks.Row>, state: BlockState, m: CodeMetrics, language: String?, plain: Boolean = false, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val oldText = remember(rows) { rows.filter { it.kind != ReviewBlocks.Kind.ADDED }.joinToString("\n") { it.text } }
    val newText = remember(rows) { rows.filter { it.kind != ReviewBlocks.Kind.REMOVED }.joinToString("\n") { it.text } }
    val oldTokens = rememberTokenLines(oldText, language)
    val newTokens = if (newText == oldText) oldTokens else rememberTokenLines(newText, language)
    val drawn = remember(rows, state, plain, oldTokens, newTokens) {
        val spans = if (state == BlockState.OPEN) SessionReviewModel.changedSpans(rows) else null
        val keep = SessionReviewModel.shownRows(rows, state).toHashSet()
        var o = 0
        var n = 0
        val out = mutableListOf<DrawnRow>()
        rows.forEachIndexed { at, row ->
            val line = if (row.kind == ReviewBlocks.Kind.REMOVED) oldTokens?.getOrNull(o) else newTokens?.getOrNull(n)
            if (row.kind != ReviewBlocks.Kind.ADDED) o++
            if (row.kind != ReviewBlocks.Kind.REMOVED) n++
            if (row !in keep) return@forEachIndexed
            val (code, pieces) = reviewCodeLine(row.text, line, spans?.getOrNull(at))
            out += DrawnRow(
                row,
                code,
                pieces,
                add = state == BlockState.OPEN && row.kind == ReviewBlocks.Kind.ADDED,
                del = state == BlockState.OPEN && row.kind == ReviewBlocks.Kind.REMOVED,
                old = if (state == BlockState.KEPT && !plain) "" else row.o?.toString().orEmpty(),
                new = if (state == BlockState.REVERTED) "" else row.n?.toString().orEmpty(),
            )
        }
        out
    }
    if (drawn.isEmpty()) {
        // `py-0.5 font-sans text-text-subtle` in the code's column.
        val exact = rememberCssExact()
        Box(modifier.cssExact(exact).fillMaxWidth().padding(start = m.codeX.dp)) {
            CssColumn(top = 2.dp, bottom = 2.dp, exact = exact) {
                BasicText(
                    if (state == BlockState.KEPT) "The file is deleted — nothing of it stays." else "The file is gone again — it did not exist before this chat.",
                    Modifier.cssLines(m.line),
                    ppmText(m.size, lineHeight = m.line).copy(color = tokens.text3.color),
                )
            }
        }
        return
    }
    val decided = state != BlockState.OPEN && !plain
    val exact = rememberCssExact()
    val layouts = remember(drawn) { arrayOfNulls<TextLayoutResult>(drawn.size) }
    val tops = remember(drawn) { IntArray(drawn.size + 1) }
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val numberStyle = ppmText(m.numberSize, kind = FontKind.MONO, lineHeight = m.numberLine).copy(color = tokens.text3.color)
    val plusStyle = ppmText(m.size, kind = FontKind.MONO, lineHeight = m.line).copy(color = tokens.success.color)
    val minusStyle = plusStyle.copy(color = tokens.error.color)
    val codeStyle = ppmText(m.size, kind = FontKind.MONO, lineHeight = m.line).copy(color = tokens.text.color)
    val glyphs = remember(numberStyle, plusStyle, minusStyle) { HashMap<String, TextLayoutResult>() }
    val density = LocalDensity.current
    val (up, down) = remember(m, density) {
        with(density) { PpmFonts.chromeAscentDescent(m.size.sp.toPx(), PpmFonts.MONO_ASCENT, PpmFonts.MONO_DESCENT) }
    }
    val addTint = tokens.success.opacity(0.16).color
    val delTint = tokens.error.opacity(0.16).color
    val addWord = tokens.success.opacity(0.4).color
    val delWord = tokens.error.opacity(0.4).color
    WithoutOnCopy("$BREAK$JOIN") {
        Layout(
            content = {
                drawn.forEachIndexed { i, row ->
                    BasicText(row.code, if (decided) Modifier.alpha(0.78f) else Modifier, codeStyle, onTextLayout = { layouts[i] = it })
                }
            },
            modifier = modifier
                .cssPhased(exact)
                .fillMaxWidth()
                .drawBehind {
                    fun glyph(text: String, style: TextStyle, kind: Char): TextLayoutResult =
                        glyphs.getOrPut("$kind$text") { measurer.measure(text, style, softWrap = false) }
                    val oldEnd = (m.gutter - m.numberPad).dp.toPx()
                    val newEnd = (2 * m.gutter - m.numberPad).dp.toPx()
                    val signCentre = (2 * m.gutter + m.signColumn / 2).dp.toPx()
                    val codeX = m.codeX.dp.toPx().roundToInt().toFloat()
                    drawn.forEachIndexed { i, row ->
                        val top = tops[i].toFloat()
                        val bottom = tops[i + 1].toFloat()
                        if (row.add || row.del) drawRect(if (row.add) addTint else delTint, Offset(0f, top), Size(size.width, bottom - top))
                        if (row.old.isNotEmpty()) glyph(row.old, numberStyle, 'n').let { drawText(it, topLeft = Offset((oldEnd - it.size.width).roundToInt().toFloat(), top)) }
                        if (row.new.isNotEmpty()) glyph(row.new, numberStyle, 'n').let { drawText(it, topLeft = Offset((newEnd - it.size.width).roundToInt().toFloat(), top)) }
                        if (row.add || row.del) {
                            val sign = if (row.add) glyph("+", plusStyle, 'a') else glyph("−", minusStyle, 'd')
                            drawText(sign, topLeft = Offset((signCentre - sign.size.width / 2f).roundToInt().toFloat(), top))
                        }
                        val layout = layouts[i]
                        if (layout != null && row.pieces.isNotEmpty()) {
                            translate(codeX, top) { drawPieces(layout, row.pieces, if (row.add) addWord else delWord, up, down) }
                        }
                    }
                },
        ) { measurables, constraints ->
            val phase = exact.cssPhase()
            val codeX = m.codeX.dp.toPx()
            val width = constraints.maxWidth
            val codeWidth = max(0, (width - codeX - m.codePad.dp.toPx()).toInt())
            val line = m.line.sp.toPx()
            val numberLine = m.numberLine.sp.toPx()
            var at = phase
            val placeables = measurables.mapIndexed { i, child ->
                tops[i] = at.roundToInt()
                val placeable = child.measure(Constraints(maxWidth = codeWidth))
                val row = drawn[i]
                at += if (row.row.text.isEmpty()) {
                    // An empty line makes no line box: the row is as tall as its sign or its number.
                    max(if (row.add || row.del) line else 0f, if (row.old.isNotEmpty() || row.new.isNotEmpty()) numberLine else 0f)
                } else {
                    max(1, layouts[i]?.lineCount ?: (placeable.height / ceil(line)).roundToInt()) * line
                }
                placeable
            }
            tops[drawn.size] = at.roundToInt()
            val height = at.roundToInt()
            exact.cssReport(at - phase, height)
            layout(width, height) {
                placeables.forEachIndexed { i, p -> p.place(codeX.roundToInt(), tops[i]) }
            }
        }
    }
}

/**
 * The changed span's pieces behind the code (`rounded-[2px] bg-diff-*-word`): each an inline box
 * as tall as the font's ascent and descent as Chrome rounds them, cut where the line wraps with
 * the corners only at its two ends (`box-decoration-break: slice`).
 */
private fun DrawScope.drawPieces(layout: TextLayoutResult, pieces: List<IntRange>, color: Color, up: Float, down: Float) {
    val r = CornerRadius(2.dp.toPx())
    val length = layout.layoutInput.text.length
    for (piece in pieces) {
        val start = piece.first
        val end = min(length, piece.last + 1)
        if (end <= start) continue
        val first = layout.getLineForOffset(start)
        val last = layout.getLineForOffset(end - 1)
        for (l in first..last) {
            val from = max(start, layout.getLineStart(l))
            val lineEnd = layout.getLineEnd(l)
            val to = min(end, lineEnd)
            if (to <= from) continue
            val x1 = layout.getHorizontalPosition(from, true)
            val x2 = if (to == lineEnd && l < layout.lineCount - 1) layout.getLineRight(l) else layout.getHorizontalPosition(to, true)
            val baseline = layout.getLineBaseline(l)
            val left = if (l == first) r else CornerRadius.Zero
            val right = if (l == last) r else CornerRadius.Zero
            drawPath(Path().apply { addRoundRect(RoundRect(x1, baseline - up, x2, baseline + down, left, right, right, left)) }, color)
        }
    }
}

/**
 * Each line of [text] as Shiki colours it, in its colours and italics alone (`CodeLine` reads
 * nothing else), or null until it is coloured: from the first frame when it was before.
 */
@Composable
private fun rememberTokenLines(text: String, language: String?): List<AnnotatedString>? {
    val theme = LocalPpmTheme.current.shikiTheme
    val highlighter = SyntaxHighlighter.get(LocalContext.current)
    val ready = remember(text, language, theme) { highlighter.cached(text, language, theme)?.let(::tokenLines) }
    if (ready != null) return ready
    val loaded by produceState<List<AnnotatedString>?>(null, text, language, theme) {
        value = highlighter.highlight(text, language, theme)?.let(::tokenLines)
    }
    return loaded
}

/** [code] cut at its line breaks, each span kept on the line it falls in. */
private fun tokenLines(code: HighlightedCode): List<AnnotatedString> {
    val text = code.text.text
    val starts = mutableListOf(0)
    text.forEachIndexed { i, c -> if (c == '\n') starts += i + 1 }
    val spans = List(starts.size) { mutableListOf<AnnotatedString.Range<SpanStyle>>() }
    for (span in code.text.spanStyles) {
        if (span.end <= span.start) continue
        var line = starts.binarySearch(span.start)
        if (line < 0) line = -line - 2
        val base = starts[line]
        val lineEnd = (starts.getOrNull(line + 1) ?: (text.length + 1)) - 1
        val style = SpanStyle(color = span.item.color, fontStyle = span.item.fontStyle.takeIf { it == FontStyle.Italic })
        spans[line] += AnnotatedString.Range(style, span.start - base, min(span.end, lineEnd) - base)
    }
    return starts.mapIndexed { i, s ->
        val e = (starts.getOrNull(i + 1) ?: (text.length + 1)) - 1
        AnnotatedString(text.substring(s, max(s, e)), spans[i])
    }
}

/**
 * One row's code as `CodeLine` draws it in a `whitespace-pre-wrap [overflow-wrap:anywhere]` box
 * of monospace: [tokens]' colours when they add up to the line, else plain; each tab the spaces
 * to the next stop of four columns; and the marks for Android to end lines where Chrome does
 * ([breakMark]). Also where the [span] that changed lands: a piece for each token it crosses.
 */
internal fun reviewCodeLine(text: String, tokens: AnnotatedString?, span: IntRange?): Pair<AnnotatedString, List<IntRange>> {
    val usable = tokens?.takeIf { it.length == text.length }
    val starts = IntArray(text.length + 1)
    val out = StringBuilder(text.length + 8)
    var column = 0
    for (i in text.indices) {
        breakMark(text, i)?.let { out.append(it) }
        starts[i] = out.length
        val c = text[i]
        if (c == '\t') {
            val n = 4 - column % 4
            repeat(n) { out.append(' ') }
            column += n
        } else {
            out.append(c)
            if (!c.isLowSurrogate()) column++
        }
    }
    starts[text.length] = out.length
    val styles = usable?.spanStyles.orEmpty()
    val code = AnnotatedString(out.toString(), styles.map { AnnotatedString.Range(it.item, starts[it.start], starts[min(it.end, text.length)]) })
    if (span == null || span.isEmpty()) return code to emptyList()
    val from = span.first.coerceIn(0, text.length)
    val to = (span.last + 1).coerceIn(from, text.length)
    // Plain text is one piece, as the web draws it as one token.
    val cuts = (listOf(0, text.length) + styles.flatMap { listOf(it.start, it.end) }).filter { it in 0..text.length }.distinct().sorted()
    val pieces = mutableListOf<IntRange>()
    for (k in 0 until cuts.size - 1) {
        val a = max(from, cuts[k])
        val b = min(to, cuts[k + 1])
        if (b > a) pieces += starts[a] until starts[b]
    }
    return code to pieces
}

// Turns

/** The card a turn chip opens on a tablet: `w-[330px] rounded-xl border border-border bg-panel-2 p-3.5`. */
@Composable
private fun TurnPopover(chip: TurnChip, state: ReviewCodeState, act: ReviewCodeActions) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    DisposableEffect(Unit) { onDispose { state.regions.remove(POP_REGION) } }
    Box(
        Modifier
            .onGloballyPositioned { state.regions[POP_REGION] = it.boundsInRoot() }
            .ppmShadow(POPOVER_SHADOW, shape)
            .clip(shape)
            .background(tokens.panel2.color)
            .cssBorder(tokens.border.color, shape)
            .padding(14.dp),
    ) {
        TurnCard(chip, compact = false, onClose = { state.pop = null }, act = act)
    }
}

private const val POP_REGION = "pop"

/** `shadow-[0_18px_40px_-16px_rgba(0,0,0,.6)]`. */
private val POPOVER_SHADOW = ThemeShadow(0.0, 18.0, 40.0, -16.0, Rgba(0.0, 0.0, 0.0, 0.6))

/**
 * The prompt behind a turn, with a way to copy it and to see the turn in the chat (`TurnCard`):
 * `text-[12.5px] leading-normal` under its chip, `px-4 pb-1 text-sm` in a phone's sheet.
 */
@Composable
private fun TurnCard(chip: TurnChip, compact: Boolean, onClose: () -> Unit, act: ReviewCodeActions) {
    val tokens = LocalPpmTokens.current
    val turn = chip.turn
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(turn.messageId) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(1500)
        copied = false
    }
    val time = turn.at?.let { PpmFormat.turnTime(it) }.orEmpty()
    // The title inherits the card's line height as a ratio: `leading-normal`, or `text-sm`'s 20/14.
    val ratio = if (compact) 20f / 14f else 1.5f
    val title = if (compact) 15f else 13f
    val prompt = if (compact) 14f else 13f
    Column(if (compact) Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp) else Modifier) {
        // `mb-1.5 flex items-center gap-2 font-semibold text-text`.
        Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PpmIcon(ProductIcon.MessageCircle, if (compact) 20.dp else 16.dp, tokens.text.color)
            BasicText(
                SessionTurns.label(turn) + (if (time.isNotEmpty()) " · $time" else ""),
                style = ppmText(title, FontWeight.SemiBold, lineHeight = title * ratio).copy(color = tokens.text.color),
            )
        }
        // `mb-2.5 overflow-y-auto whitespace-pre-wrap rounded-r-md border-l-2 border-primary bg-[text 5%]
        // px-2.5 py-2 leading-[1.45]`, `max-h-48 text-[13px]` (`max-h-[40vh] text-sm`).
        val quote = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
        val maxHeight = if (compact) (LocalConfiguration.current.screenHeightDp * 0.4f).dp else 192.dp
        val style = ppmText(prompt, lineHeight = prompt * 1.45f)
        Box(
            Modifier
                .padding(bottom = 10.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .clip(quote)
                .background(tokens.text.opacity(0.05).color)
                .cssBorder(tokens.accent.color, width = 2.dp, sides = CssSides.Start)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (turn.prompt.isNotEmpty()) {
                BasicText(chromeBreaks(turn.prompt), style = style.copy(color = tokens.text.color))
            } else {
                BasicText("No text — the prompt was only attachments.", style = style.copy(color = tokens.text3.color))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 6.dp, Alignment.End)) {
            ReviewButton(
                if (copied) "Copied" else "Copy prompt",
                if (compact) WebVariant.OUTLINE else WebVariant.GHOST,
                if (compact) WebSize.LG else WebSize.SM,
                modifier = if (compact) Modifier.weight(1f) else Modifier,
                icon = if (copied) ProductIcon.Check else ProductIcon.Copy,
                height = if (compact) 44.dp else null,
                content = tokens.text2,
            ) {
                scope.launch {
                    runCatching { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("prompt", turn.prompt))) }.onSuccess { copied = true }
                }
            }
            ReviewButton(
                "Show in chat",
                WebVariant.DEFAULT,
                if (compact) WebSize.LG else WebSize.SM,
                modifier = if (compact) Modifier.weight(1f) else Modifier,
                trailingIcon = ProductIcon.ArrowUp,
                height = if (compact) 44.dp else null,
            ) {
                onClose()
                act.showInChat(turn, chip.call)
            }
        }
    }
}

// Buttons

internal enum class WebVariant { DEFAULT, OUTLINE, GHOST }

internal enum class WebSize { XS, SM, LG }

/**
 * The web's `Button` (`rounded-md text-sm font-medium transition-all`): `default` in the accent;
 * `outline` (`border bg-background shadow-xs`, in the dark `bg-input/30`; its border the text's
 * colour in the light, Tailwind 4's `currentColor`) or `ghost`, both `hover:bg-accent`; at `xs`
 * (`h-6 gap-1 px-2 text-xs`, a 12 px icon), `sm` (`h-8 gap-1.5 px-3`) or `lg` (`h-10 gap-2
 * px-6`), the padding `px-1.5`, `px-2.5` or `px-4` beside an icon. [content] is the colour an
 * outline or a ghost inherits, [hoverContent] what a mouse turns it to.
 */
@Composable
internal fun ReviewButton(
    label: String,
    variant: WebVariant,
    size: WebSize,
    modifier: Modifier = Modifier,
    icon: ProductIcon? = null,
    trailingIcon: ProductIcon? = null,
    height: Dp? = null,
    content: Rgba? = null,
    hoverContent: Rgba? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val dark = LocalPpmTheme.current.isDark
    val shape = RoundedCornerShape(8.dp)
    val hover = rememberHover(enabled = enabled)
    val pressed by hover.source.collectIsPressedAsState()
    val lit = if (pressed && enabled) 1.0 else hover.lit.toDouble()
    val ink = when (variant) {
        WebVariant.DEFAULT -> tokens.accentFg
        else -> (hoverContent ?: tokens.text).mix(content ?: tokens.text, lit)
    }
    val fill = when (variant) {
        WebVariant.DEFAULT -> tokens.accent.opacity(1 - 0.1 * lit)
        WebVariant.OUTLINE -> if (dark) tokens.border.opacity(0.3 + 0.2 * lit) else tokens.panel2.mix(tokens.bgSolid, lit)
        WebVariant.GHOST -> tokens.panel2.opacity(if (dark) 0.5 * lit else lit)
    }
    val withIcon = icon != null || trailingIcon != null
    val pad = when (size) {
        WebSize.XS -> if (withIcon) 6.dp else 8.dp
        WebSize.SM -> if (withIcon) 10.dp else 12.dp
        WebSize.LG -> if (withIcon) 16.dp else 24.dp
    }
    val iconSize = if (size == WebSize.XS) 12.dp else 16.dp
    val text = if (size == WebSize.XS) ppmText(12f, FontWeight.Medium, lineHeight = 16f) else ppmText(14f, FontWeight.Medium, lineHeight = 20f)
    Row(
        modifier
            .height(height ?: (if (size == WebSize.XS) 24.dp else if (size == WebSize.SM) 32.dp else 40.dp))
            .alpha(if (enabled) 1f else 0.5f)
            .then(if (variant == WebVariant.OUTLINE) Modifier.ppmShadow(SHADOW_XS, shape) else Modifier)
            .clip(shape)
            .background(fill.color)
            .pointerHoverIcon(WebCursor.Pointer)
            .ppmClickable(enabled = enabled, pressedColor = Color.Transparent, shape = shape, onClickLabel = label, source = hover.source, onClick = onClick)
            .then(if (variant == WebVariant.OUTLINE) Modifier.cssBorder((if (dark) tokens.border else ink).color, shape) else Modifier)
            .padding(horizontal = pad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(
            when (size) {
                WebSize.XS -> 4.dp
                WebSize.SM -> 6.dp
                WebSize.LG -> 8.dp
            },
            Alignment.CenterHorizontally,
        ),
    ) {
        icon?.let { PpmIcon(it, iconSize, ink.color) }
        BasicText(label, style = text.copy(color = ink.color), maxLines = 1, softWrap = false)
        trailingIcon?.let { PpmIcon(it, iconSize, ink.color) }
    }
}

/** `shadow-xs`, under an outline button. */
private val SHADOW_XS = ThemeShadow(0.0, 1.0, 2.0, 0.0, Rgba(0.0, 0.0, 0.0, 0.05))

// The pane

/**
 * What goes under the file's head (`PaneBody`): the blocks, a card for a file answered whole, or
 * the wait for its diff. [onChange] opens the decided file again, when it can be.
 */
@Composable
fun ReviewPaneBody(
    state: SessionReviewState,
    compact: Boolean,
    turns: Map<String, SessionTurn>,
    actions: ReviewCodeActions,
    code: ReviewCodeState,
    sheet: ReviewSheet,
    onRevertFile: () -> Unit,
    onKeepFile: () -> Unit,
    onChange: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    when (val pane = state.pane) {
        is ReviewPane.Blocks -> ReviewCode(
            file = pane.review.file,
            model = pane.model,
            focusKey = state.focus?.takeIf { it.path == pane.review.path }?.key,
            compact = compact,
            turns = turns,
            actions = actions,
            state = code,
            sheet = sheet,
            modifier = modifier,
        )
        is ReviewPane.Whole -> WholeFile(pane.review, compact, onRevertFile, onKeepFile, onChange, modifier)
        // `flex flex-1 items-center justify-center px-4 text-center text-sm text-text-subtle`.
        is ReviewPane.Error -> Box(modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            BasicText(pane.message, style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text3.color, textAlign = TextAlign.Center))
        }
        else -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text3.color, spinning = true)
        }
    }
}

/**
 * A file answered whole (`WholeFile`): in `overflow-auto bg-bg p-3`, a card (`rounded-[10px]
 * border border-border-soft bg-panel p-4 text-[13px] text-text-2`, dashed once decided) saying
 * why, and on a tablet its answers or how it ended.
 */
@Composable
private fun WholeFile(review: FileReview, compact: Boolean, onRevertFile: () -> Unit, onKeepFile: () -> Unit, onChange: (() -> Unit)?, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val file = review.file
    val why = when {
        review.gone -> "Reverted: the file is back the way it was."
        file.binary -> "A binary file has no lines to compare, so it is kept or reverted whole."
        file.tooLarge -> "This file is too large to show, so it is kept or reverted whole."
        else -> "This diff is too large to cut into blocks, so the file is kept or reverted whole."
    }
    val outcome = if (review.open == 0) SessionReviewModel.fileOutcome(review) else null
    Box(modifier.fillMaxSize().background(tokens.bgSolid.color).verticalScroll(rememberScrollState()).padding(12.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(tokens.panel.color, RoundedCornerShape(10.dp))
                .editCardBorder(tokens.borderSoft.color, 10.dp, dashed = outcome != null)
                .padding(16.dp),
        ) {
            BasicText(why, style = ppmText(13f, lineHeight = 19.5f).copy(color = tokens.text2.color))
            if (!compact) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (outcome != null) {
                        val tone = when (outcome.tone) {
                            FileOutcome.Tone.KEPT -> tokens.accent
                            FileOutcome.Tone.REVERTED -> tokens.error
                            FileOutcome.Tone.MIXED -> tokens.text3
                        }.color
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PpmIcon(if (outcome.tone == FileOutcome.Tone.REVERTED) ProductIcon.RotateCcw else ProductIcon.Check, 14.dp, tone)
                            BasicText(outcome.label, style = ppmText(13f, FontWeight.SemiBold, lineHeight = 19.5f).copy(color = tone), maxLines = 1, softWrap = false)
                        }
                        if (onChange != null) ReviewButton("Change", WebVariant.GHOST, WebSize.XS, icon = ProductIcon.Undo2, content = tokens.text2, onClick = onChange)
                    } else {
                        ReviewButton("Revert file…", WebVariant.OUTLINE, WebSize.SM, icon = ProductIcon.RotateCcw, hoverContent = tokens.error, onClick = onRevertFile)
                        ReviewButton("Keep file", WebVariant.DEFAULT, WebSize.SM, icon = ProductIcon.Check, onClick = onKeepFile)
                    }
                }
            }
        }
    }
}
