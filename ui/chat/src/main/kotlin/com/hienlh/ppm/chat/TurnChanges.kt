package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.hienlh.ppm.design.ClampedText
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CssExact
import com.hienlh.ppm.design.CssMixedText
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FileIcon
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmFormat
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.StartTruncatedText
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.chromeScrollbar
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderInset
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.cssFlex
import com.hienlh.ppm.design.cssHeight
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.cssMinHeight
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberCssExact
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.TurnRevertFile
import com.hienlh.ppm.protocol.TurnRevertResult
import com.hienlh.ppm.state.EditReview
import com.hienlh.ppm.state.EditReviewState
import com.hienlh.ppm.state.FileEditFragment
import com.hienlh.ppm.state.RevertTurnFlow
import com.hienlh.ppm.state.SessionChangeList
import com.hienlh.ppm.state.SessionChanges
import com.hienlh.ppm.state.SessionTurn
import com.hienlh.ppm.state.SessionTurns
import com.hienlh.ppm.state.TurnFileChange
import com.hienlh.ppm.state.TurnReview
import com.hienlh.ppm.state.TurnReviewLabel
import com.hienlh.ppm.state.TurnReviewLocal
import com.hienlh.ppm.state.TurnReviewModel
import com.hienlh.ppm.state.TurnReviewNotice
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The chat's half of the session review, for each turn's change pill (`SessionChangesContext`):
 * the session's list a tray answers against, the session's Review tab, and a phone's sheet.
 */
class TurnChangesHost(
    /** Null while the chat has no session: a tray then shows the edits and nothing else. */
    val changes: SessionChanges?,
    /** The session's Review tab, on a file when one is named. */
    val openReview: (path: String?) -> Unit,
    /** A phone's bottom sheet, `max-h-[90%]`; [top] is where in its first pixel its content starts. */
    val sheet: @Composable (onDismiss: () -> Unit, content: @Composable ColumnScope.(top: Dp) -> Unit) -> Unit,
    /** The chat's tab is the one in front (`ownsGlobalShortcut`): its tray hears Escape. */
    val ownsKeys: () -> Boolean = { true },
)

/**
 * Escape for the tray as the web hears it, on the whole window (`activeTrayId`): the tray opened
 * last takes it while its tab is the one in front and no text field has focus, wherever focus is
 * — a tap here gives focus to the page, not to the button tapped.
 */
object TurnTrayEscape {
    private var claim: Claim? = null

    internal class Claim(val owns: () -> Boolean, val escape: () -> Unit)

    /** For the app's window-wide keys, with no text field focused: whether a tray took the key. */
    fun dispatch(): Boolean {
        val claim = claim?.takeIf { it.owns() } ?: return false
        claim.escape()
        return true
    }

    internal fun take(claim: Claim) {
        this.claim = claim
    }

    internal fun release(claim: Claim) {
        if (this.claim === claim) this.claim = null
    }
}

/** The chat's host; none outside a chat, where an answer's action bar has no pill. */
val LocalTurnChanges = staticCompositionLocalOf<TurnChangesHost?> { null }

/** The page's line height of 1.5, which the tray and the sheet keep: `text-[13px]` is 13 on 19.5. */
private fun pageText(size: Float, weight: FontWeight = FontWeight.Normal, kind: FontKind = FontKind.SANS) =
    ppmText(size, weight, kind, lineHeight = size * 1.5f)

/** `text-xs`: 12 on 16, and a size set inside it keeps the ratio of 4/3. */
private fun xsText(size: Float = 12f, weight: FontWeight = FontWeight.Normal, kind: FontKind = FontKind.SANS) =
    ppmText(size, weight, kind, lineHeight = size * 4f / 3f)

/** One CSS border's width as Chrome snaps it, in dp. */
@Composable
private fun borderDp(): Dp = with(LocalDensity.current) { cssBorderPx(1.dp).toDp() }

/** One turn's review as the pill, the tray and the sheet read it (`TurnReview`). */
@Stable
internal class TurnReviewView(
    val model: TurnReviewModel?,
    val reviews: Map<String, EditReview>,
    val local: TurnReviewLocal,
    val openReview: (String?) -> Unit,
) {
    val summary = TurnReview.summary(reviews)

    /** False with no session list to answer against: the tray shows the edits and nothing else. */
    val enabled: Boolean get() = model != null && summary.known > 0
    val busy: Boolean get() = local.busy
    val flow: RevertTurnFlow? get() = local.flow
}

@Composable
private fun rememberTurnReview(host: TurnChangesHost, actions: MessageActions): TurnReviewView {
    val changes = host.changes
    val model = remember(changes, actions.key, actions.changes, actions.turn) { changes?.turnReview(actions.key, actions.changes, actions.turn) }
    val files by remember(changes) { changes?.files ?: MutableStateFlow(emptyList()) }.collectAsState()
    val local by remember(model) { model?.local ?: MutableStateFlow(TurnReviewLocal()) }.collectAsState()
    return remember(model, files, local, host) { TurnReviewView(model, model?.reviews(files).orEmpty(), local, host.openReview) }
}

/**
 * The action bar under a turn's last answer with the turn's change pill appended, and what the
 * pill opens: the tray under the bar on a tablet, a sheet on a phone (`TurnChangeRollup`).
 */
@Composable
internal fun TurnChangeRollup(actions: MessageActions, modifier: Modifier = Modifier) {
    val host = LocalTurnChanges.current
    val changes = actions.changes
    if (host == null || changes.isEmpty()) {
        MessageActionBar(actions.timestamp, actions.copyText, modifier, actions.onReply)
        return
    }
    val mobile = LocalPpmMobile.current
    var open by rememberSaveable { mutableStateOf(false) }
    val review = rememberTurnReview(host, actions)
    val (added, removed) = remember(changes) { TurnReview.totals(changes) }
    val close = {
        review.model?.cancelRevert()
        open = false
    }
    MessageActionBar(actions.timestamp, actions.copyText, modifier, actions.onReply) {
        TurnChangePill(changes.size, added, removed, TurnReview.label(review.summary), open, mobile) {
            if (open) {
                close()
            } else {
                // The session's list is worked out only when someone looks; opening a turn's changes is that.
                host.changes?.refresh()
                open = true
            }
        }
    }
    if (!open) return
    val jump: (String) -> Unit = { ref ->
        close()
        actions.onJump?.invoke(ref)
    }
    if (mobile) {
        host.sheet(close) { top -> TurnChangeSheetContent(top, changes, actions.turn, review, close, jump) }
    } else {
        // `gap-2` under the bar, then the tray's own `mt-0.5`.
        TurnChangeTray(changes, actions.turn, review, jump, close, Modifier.padding(top = 10.dp))
    }
}

// Pill

/**
 * The pill appended to the action bar (`TurnChangePill`), after a 1 px rule: a dot, "N files",
 * the lines added and removed, and where the turn's edits stand in the review. Borderless, a
 * 44 px target on a phone (`px-3 min-h-11`), `px-[9px] py-[5px]` on a tablet; lit while open.
 */
@Composable
private fun TurnChangePill(count: Int, added: Int, removed: Int, status: TurnReviewLabel?, open: Boolean, mobile: Boolean, onToggle: () -> Unit) {
    val tokens = LocalPpmTokens.current
    // `mx-1.5 h-3.5 w-px bg-border-soft`.
    Box(Modifier.padding(horizontal = 6.dp).size(1.dp, 14.dp).background(tokens.borderSoft.color))
    val label = SessionChangeList.fileCount(count)
    val hover = rememberHover()
    val pressed by hover.source.collectIsPressedAsState()
    val lit = if (open || pressed) 1.0 else hover.lit.toDouble()
    val color = tokens.text.mix(tokens.text2, lit).color
    // `font-mono text-[11.5px]` inside the bar's `text-xs`.
    val mono = xsText(11.5f, kind = FontKind.MONO)
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (open) tokens.accentWash.color else Color.Transparent)
            .ppmClickable(
                pressedColor = tokens.panel.color,
                hoverColor = tokens.panel.color,
                shape = CircleShape,
                onClickLabel = "$label changed this turn, $added added, $removed removed${status?.let { ", ${it.text}" }.orEmpty()}",
                source = hover.source,
                onClick = onToggle,
            )
            .then(if (mobile) Modifier.heightIn(min = 44.dp).padding(horizontal = 12.dp) else Modifier.padding(horizontal = 9.dp, vertical = 5.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(tokens.accent.color))
        BasicText(label, style = mono.copy(color = color), maxLines = 1, softWrap = false)
        ChangeCounts(added, removed, mono)
        if (status != null) {
            val tone = when (status.tone) {
                TurnReviewLabel.Tone.TODO -> tokens.warning
                TurnReviewLabel.Tone.KEPT -> tokens.success
                TurnReviewLabel.Tone.REVERTED -> tokens.text2
            }.color
            // `inline-flex items-center gap-[5px] border-l border-border-soft pl-2 font-sans`.
            Row(
                Modifier.cssBorder(tokens.borderSoft.color, sides = CssSides.Start).padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (status.tone == TurnReviewLabel.Tone.TODO) Box(Modifier.size(6.dp).clip(CircleShape).background(tone))
                if (status.tone == TurnReviewLabel.Tone.KEPT) PpmIcon(ProductIcon.Check, 12.dp, tone)
                BasicText(status.text, style = xsText(11.5f).copy(color = tone), maxLines = 1, softWrap = false)
            }
        }
    }
}

// Buttons

private enum class ButtonKind { GHOST, PRIMARY, DESTRUCTIVE }

/**
 * The web's `Button` at `size="sm"` (`h-8 gap-1.5 rounded-md px-3`, `px-2.5` around an icon) or
 * `"xs"` (`h-6 gap-1 px-2 text-xs`, `px-1.5` around an icon), `text-sm font-medium`. A ghost is
 * [color] (the text's by default), on `bg-accent/50` with the text's colour under a mouse unless
 * [keepColor]; the primary is the accent; the destructive the error colour, at 60% in the dark.
 */
@Composable
private fun WebButton(
    label: String,
    kind: ButtonKind,
    modifier: Modifier = Modifier,
    icon: (@Composable (Color) -> Unit)? = null,
    xs: Boolean = false,
    height: Dp = if (xs) 24.dp else 32.dp,
    color: Rgba? = null,
    keepColor: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    // `dark:` is the device's dark mode on the web, not PPM's theme.
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(8.dp)
    val hover = rememberHover(enabled = enabled)
    val pressed by hover.source.collectIsPressedAsState()
    val lit = if (pressed && enabled) 1.0 else hover.lit.toDouble()
    val (fill, content) = when (kind) {
        ButtonKind.GHOST -> {
            val base = color ?: tokens.text
            Color.Transparent to (if (keepColor) base else tokens.text.mix(base, lit)).color
        }
        ButtonKind.PRIMARY -> tokens.accent.opacity(1 - 0.1 * lit).color to tokens.accentFg.color
        ButtonKind.DESTRUCTIVE -> tokens.error.opacity(if (dark) 0.6 + 0.3 * lit else 1 - 0.1 * lit).color to Color.White
    }
    val hoverFill = if (kind == ButtonKind.GHOST) tokens.panel2.opacity(if (dark) 0.5 else 1.0).color else Color.Transparent
    Row(
        modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(fill)
            .ppmClickable(enabled = enabled, pressedColor = hoverFill, hoverColor = hoverFill, shape = shape, onClickLabel = label, source = hover.source, onClick = onClick)
            .padding(horizontal = if (icon != null) (if (xs) 6.dp else 10.dp) else (if (xs) 8.dp else 12.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (xs) 4.dp else 6.dp, Alignment.CenterHorizontally),
    ) {
        icon?.invoke(content)
        BasicText(label, style = (if (xs) xsText(weight = FontWeight.Medium) else ppmText(14f, FontWeight.Medium, lineHeight = 20f)).copy(color = content), maxLines = 1, softWrap = false)
    }
}

/** An icon of [size] in the colour a button hands it. */
private fun icon(icon: ProductIcon, size: Dp): @Composable (Color) -> Unit = { PpmIcon(icon, size, it) }

/** A square icon button (`inline-grid place-items-center text-text-subtle hover:bg-surface hover:text-text-primary`). */
@Composable
private fun SquareIconButton(icon: ProductIcon, iconSize: Dp, size: Dp, radius: Dp, label: String, title: String? = null, hoverFill: Rgba? = null, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover()
    val pressed by hover.source.collectIsPressedAsState()
    val lit = if (pressed) 1.0 else hover.lit.toDouble()
    val fill = (hoverFill ?: tokens.panel).color
    Box(
        Modifier
            .size(size)
            .then(if (title != null) Modifier.webTitle(title) else Modifier)
            .ppmClickable(pressedColor = fill, hoverColor = fill, shape = RoundedCornerShape(radius), onClickLabel = label, source = hover.source, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, iconSize, tokens.text.mix(tokens.text3, lit).color)
    }
}

// Shared parts

/**
 * `rounded-lg border border-border-soft`, dashed once the edit is answered as Blink dashes a
 * rounded border: one closed path down the middle of the border, from the start of the top side
 * clockwise, three widths on and about two off, the gap stretched so a whole number of dashes
 * goes round (`StrokeData::SelectBestDashGap`). The pattern runs on through the corners.
 */
internal fun Modifier.editCardBorder(color: Color, radius: Dp, dashed: Boolean): Modifier =
    // The dashes are drawn over the whole box, then the border's room is taken inside it.
    if (!dashed) cssBorder(color, RoundedCornerShape(radius)) else drawWithCache {
        val w = cssBorderPx(1.dp).toFloat()
        val half = w / 2
        val r = radius.toPx() - half
        val right = size.width - half
        val bottom = size.height - half
        val path = Path().apply {
            moveTo(half + r, half)
            lineTo(right - r, half)
            arcTo(Rect(right - 2 * r, half, right, half + 2 * r), -90f, 90f, false)
            lineTo(right, bottom - r)
            arcTo(Rect(right - 2 * r, bottom - 2 * r, right, bottom), 0f, 90f, false)
            lineTo(half + r, bottom)
            arcTo(Rect(half, bottom - 2 * r, half + 2 * r, bottom), 90f, 90f, false)
            lineTo(half, half + r)
            arcTo(Rect(half, half, half + 2 * r, half + 2 * r), 180f, 90f, false)
            close()
        }
        val intervals = chromeDashes(PathMeasure().apply { setPath(path, false) }.length, w)
        val stroke = Stroke(w, pathEffect = intervals?.let { PathEffect.dashPathEffect(it) })
        onDrawBehind { drawPath(path, color, style = stroke) }
    }.cssBorderInset(all = 1.dp)

/** The dash and gap Blink strokes a closed path [length] px long with, [width] wide; null for no room to dash. */
internal fun chromeDashes(length: Float, width: Float): FloatArray? {
    val dash = width * if (width >= 3) 2 else 3
    val gap = width * if (width >= 3) 1 else 2
    if (length <= dash * 2) return null
    val pair = 2 * dash + 2 * gap
    if (length <= pair) return floatArrayOf(dash * length / pair, gap * length / pair)
    val fewer = floor(length / (dash + gap))
    val shorter = (length - fewer * dash) / fewer
    val wider = (length - (fewer + 1) * dash) / (fewer + 1)
    return floatArrayOf(dash, if (wider <= 0 || abs(shorter - gap) < abs(wider - gap)) shorter else wider)
}

/**
 * Revert and Keep for one edit (`Answers`): `h-[26px] rounded-[7px] px-2.5 text-xs` in the
 * tray's head row, or full-width `h-11 rounded-[10px] px-3.5 text-sm` under the lines on a phone.
 */
@Composable
private fun Answers(review: EditReview, mobile: Boolean, busy: Boolean, onKeep: () -> Unit, onRevert: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    @Composable
    fun answer(label: String, title: String, keep: Boolean, onClick: () -> Unit) {
        val shape = RoundedCornerShape(if (mobile) 10.dp else 7.dp)
        val hover = rememberHover(enabled = !busy)
        val pressed by hover.source.collectIsPressedAsState()
        val lit = if (pressed && !busy) 1.0 else hover.lit.toDouble()
        val content = if (keep) tokens.accentFg else tokens.text.mix(tokens.error, 1 - lit)
        Row(
            modifier
                .height(if (mobile) 44.dp else 26.dp)
                .alpha(if (busy) 0.5f else 1f)
                .webTitle(title)
                .clip(shape)
                .background((if (keep) tokens.accent.opacity(1 - 0.1 * lit) else tokens.bgSolid).color)
                .ppmClickable(enabled = !busy, pressedColor = Color.Transparent, shape = shape, onClickLabel = label, source = hover.source, onClick = onClick)
                .cssBorder(if (keep) Color.Transparent else tokens.border.mix(tokens.error.opacity(0.5), 1 - lit).color, shape)
                .padding(horizontal = if (mobile) 14.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            PpmIcon(if (keep) ProductIcon.Check else ProductIcon.RotateCcw, if (mobile) 20.dp else 14.dp, content.color)
            BasicText(label, style = (if (mobile) ppmText(14f, FontWeight.Medium, lineHeight = 20f) else xsText(weight = FontWeight.Medium)).copy(color = content.color), maxLines = 1, softWrap = false)
        }
    }
    answer("Revert", if (review.keys.size > 1) "Put back the ${review.keys.size} blocks this edit wrote" else "Put these lines back", keep = false, onRevert)
    answer("Keep", "Keep this change", keep = true, onKeep)
}

/** A small text button in an edit's head row (`rounded-[5px] px-1.5 font-medium text-text-2 hover:bg-surface-hover hover:text-text`). */
@Composable
private fun EditHeadButton(icon: ProductIcon, label: String, title: String, mobile: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(5.dp)
    val hover = rememberHover(enabled = enabled)
    val pressed by hover.source.collectIsPressedAsState()
    val lit = if (pressed && enabled) 1.0 else hover.lit.toDouble()
    val color = tokens.text.mix(tokens.text2, lit).color
    Row(
        Modifier
            .then(if (mobile) Modifier.heightIn(min = 44.dp) else Modifier.height(24.dp))
            .alpha(if (enabled) 1f else 0.5f)
            .webTitle(title)
            .ppmClickable(enabled = enabled, pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color, shape = shape, onClickLabel = label, source = hover.source, onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PpmIcon(icon, 14.dp, color)
        BasicText(label, style = pageText(11.5f, FontWeight.Medium).copy(color = color), maxLines = 1, softWrap = false)
    }
}

/** One edit (`EditCard`): its place, a way to its tool card, its answers, and its lines while it is open. */
@Composable
private fun EditCard(change: TurnFileChange, edit: FileEditFragment, index: Int, view: TurnReviewView, mobile: Boolean, onJump: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    val key = TurnReview.editKey(change.filePath, edit)
    val review = view.reviews[key]
    val state = review?.state
    val decided = state == EditReviewState.KEPT || state == EditReviewState.REVERTED
    val canChange = if (state == EditReviewState.KEPT) review.keys.isNotEmpty() else state == EditReviewState.REVERTED && review.undoId != null
    val model = view.model
    // `py-1` as one length: two paddings of 10.5 px rounded apiece would make the head a pixel taller.
    val padBelow = with(LocalDensity.current) { (8.dp.roundToPx() - 4.dp.roundToPx()).toDp() }
    // A phone's 44 px button with `py-1` around it and the border under it outgrows `min-h-[52px]`:
    // the head is then 52.76 px, which counts as that only if the column knows it before rounding.
    val tall = mobile && (edit.editRef != null || (decided && canChange && model != null))
    val headMin = when {
        tall -> 52.dp + if (decided) 0.dp else borderDp()
        mobile -> 52.dp
        else -> 36.dp
    }
    CssColumn(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.bgSolid.color)
            .editCardBorder(tokens.borderSoft.color, 12.dp, dashed = decided),
    ) {
        // `flex flex-wrap items-center gap-x-2 gap-y-1.5 py-1 pr-1.5 pl-2.5 text-[11.5px] text-text-subtle`,
        // its `min-h` the border's too (`border-box`).
        FlowRow(
            Modifier
                .fillMaxWidth()
                .cssMinHeight(headMin)
                .then(if (decided) Modifier else Modifier.cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom))
                .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = padBelow),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            val text = pageText(11.5f)
            BasicText(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = tokens.text2.color)) { append("Edit ${index + 1}") }
                    append(" of ${change.edits.size}")
                },
                style = text.copy(color = tokens.text3.color),
                maxLines = 1,
                softWrap = false,
            )
            edit.editRef?.let { ref ->
                EditHeadButton(ProductIcon.ArrowUp, if (mobile) "Chat" else "Show in chat", "Scroll the chat to the tool card that made this edit", mobile) { onJump(ref) }
            }
            Spacer(Modifier.weight(1f))
            if (state == EditReviewState.OPEN && !mobile && model != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Answers(review, mobile = false, busy = view.busy, onKeep = { model.keep(key) }, onRevert = { model.revert(key) })
                }
            }
            if (decided) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val kept = state == EditReviewState.KEPT
                    val tone = (if (kept) tokens.accent else tokens.error).color
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PpmIcon(if (kept) ProductIcon.Check else ProductIcon.RotateCcw, 14.dp, tone)
                        BasicText(if (kept) "Kept" else "Reverted", style = text.copy(color = tone, fontWeight = FontWeight.SemiBold), maxLines = 1, softWrap = false)
                    }
                    if (canChange && model != null) {
                        EditHeadButton(
                            ProductIcon.Undo2, "Change",
                            if (kept) "Open this edit again" else "Put back what the agent wrote here",
                            mobile, enabled = !view.busy,
                        ) { model.change(key) }
                    }
                }
            }
        }
        if (!decided) {
            CssColumn(Modifier.fillMaxWidth().padding(horizontal = 4.dp), top = 4.dp, bottom = 4.dp) {
                EditDiffView(edit.oldStr, edit.newStr, lineHeight = 16.5f)
            }
        }
        // On a phone the two answers sit under the lines, where the thumb is after reading them.
        if (state == EditReviewState.OPEN && mobile && model != null) {
            Row(
                Modifier.cssHeight(60.dp + borderDp()).fillMaxWidth().cssBorder(tokens.borderSoft.color, sides = CssSides.Top).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Answers(review, mobile = true, busy = view.busy, onKeep = { model.keep(key) }, onRevert = { model.revert(key) }, Modifier.weight(1f))
            }
        }
    }
}

/**
 * A file's group of edits (`FileGroup`): its icon and name, its folder losing its start when it
 * does not fit, a Sub-agent badge when one made any of them, and its lines; then each edit.
 */
@Composable
private fun FileGroup(change: TurnFileChange, view: TurnReviewView, mobile: Boolean, onJump: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    val (base, dir) = SessionChangeList.splitPath(change.filePath)
    CssColumn(Modifier.fillMaxWidth(), gap = 6.dp) {
        // `flex min-h-10 items-center gap-2 pt-1.5 text-[13px]`.
        FileGroupHead(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).webTitle(change.filePath).padding(top = 6.dp),
            icon = { FileIcon(base, size = 16.dp) },
            name = { BasicText(base, style = pageText(13f, FontWeight.SemiBold).copy(color = tokens.text.color), maxLines = 1, softWrap = false) },
            dir = if (dir.isEmpty()) null else ({ m -> StartTruncatedText(dir, xsText().copy(color = tokens.text3.color), m) }),
            badge = if (!change.viaSubagent) null else ({ SubagentBadge() }),
            counts = { ChangeCounts(change.linesAdded, change.linesRemoved, pageText(11f, kind = FontKind.MONO)) },
        )
        change.edits.forEachIndexed { index, edit ->
            key(edit.editRef ?: "${edit.toolUseId}:$index") { EditCard(change, edit, index, view, mobile, onJump) }
        }
    }
}

/**
 * The head of a file's group as CSS flex lays it out: the icon, the name and the badge at their
 * width, the folder taking what is left up to its own width and shrinking first, then the lines
 * flush right (`flex-1` before them), all `gap-2` apart and centred on the row.
 */
@Composable
private fun FileGroupHead(
    modifier: Modifier,
    icon: @Composable () -> Unit,
    name: @Composable () -> Unit,
    dir: (@Composable (Modifier) -> Unit)?,
    badge: (@Composable () -> Unit)?,
    counts: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.Layout(
        content = {
            icon()
            name()
            if (dir != null) dir(Modifier)
            if (badge != null) badge()
            counts()
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val dirIndex = if (dir != null) 2 else -1
        val fixed = measurables.mapIndexed { i, m -> if (i == dirIndex) null else m.measure(loose) }
        // The `flex-1` before the lines is an item too: a gap on either side of it.
        val gaps = gap * measurables.size
        val used = fixed.sumOf { it?.width ?: 0 } + gaps
        val dirPlaceable = if (dirIndex >= 0) {
            val wanted = measurables[dirIndex].maxIntrinsicWidth(constraints.maxHeight)
            val width = wanted.coerceAtMost((constraints.maxWidth - used).coerceAtLeast(0))
            measurables[dirIndex].measure(loose.copy(minWidth = width, maxWidth = width))
        } else {
            null
        }
        val placeables = fixed.mapIndexed { i, p -> p ?: dirPlaceable!! }
        val height = maxOf(constraints.minHeight, placeables.maxOf { it.height })
        layout(constraints.maxWidth, height) {
            var x = 0
            placeables.forEachIndexed { i, p ->
                // The lines sit at the end, the `flex-1` before them taking the rest.
                val at = if (i == placeables.lastIndex) constraints.maxWidth - p.width else x
                p.place(at, (height - p.height) / 2)
                x += p.width + gap
            }
        }
    }
}

/** "Sub-agent" (`rounded px-[5px] text-[10px] font-semibold leading-[15px] text-info`, on the info colour at 14%). */
@Composable
private fun SubagentBadge() {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.webTitle("Changed by a sub-agent").clip(RoundedCornerShape(4.dp)).background(tokens.info.opacity(0.14).color).padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        PpmIcon(ProductIcon.Bot, 12.dp, tokens.info.color)
        BasicText("Sub-agent", style = ppmText(10f, FontWeight.SemiBold, lineHeight = 15f).copy(color = tokens.info.color), maxLines = 1, softWrap = false)
    }
}

/** What an answer did (`NoticeLine`): what went wrong, or what it reverted, with Undo. */
@Composable
private fun NoticeLine(notice: TurnReviewNotice, mobile: Boolean, busy: Boolean, onUndo: (String) -> Unit, onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .cssMinHeight(if (mobile) 48.dp else 36.dp)
            .background(tokens.panel2.color)
            .cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom)
            .padding(start = 14.dp, end = if (mobile) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(notice.text, Modifier.weight(1f), style = (if (mobile) ppmText(14f, lineHeight = 20f) else xsText()).copy(color = tokens.text.color))
        notice.undoId?.let { undoId ->
            WebButton("Undo", ButtonKind.GHOST, xs = !mobile, height = if (mobile) 44.dp else 24.dp, color = tokens.accent, keepColor = true, enabled = !busy) { onUndo(undoId) }
        }
        SquareIconButton(ProductIcon.X, 14.dp, if (mobile) 44.dp else 24.dp, 8.dp, "Dismiss", hoverFill = tokens.surfaceHover, onClick = onDismiss)
    }
}

/** "1 change", "file removed", "file put back". */
private fun fileWhat(f: TurnRevertFile): String = when (f.action) {
    TurnRevertFile.Action.DELETE -> "file removed"
    TurnRevertFile.Action.RESTORE -> "file put back"
    else -> TurnReview.plural(f.changes, "change")
}

/** Who changed a skipped line since: a turn of this chat, or someone outside it. */
private fun laterLabel(by: List<String>, byCall: Map<String, SessionTurn>): String {
    val turns = mutableListOf<SessionTurn>()
    var outside = false
    for (call in by) {
        val turn = if (call.isNotEmpty()) byCall[call] else null
        if (turn != null && turn !in turns) turns += turn else if (turn == null) outside = true
    }
    val names = turns.map(SessionTurns::label).toMutableList()
    if (outside || names.isEmpty()) names += if (by.isEmpty()) "a later change" else "a change outside this chat"
    return names.joinToString(" and ")
}

/** The confirmation's list (`RevertTurnSummary`): what goes back, and what stays because something changed it since. */
@Composable
private fun RevertTurnSummary(preview: TurnRevertResult, mobile: Boolean, byCall: Map<String, SessionTurn>) {
    val tokens = LocalPpmTokens.current
    val files = preview.files.filter { it.action != TurnRevertFile.Action.NONE }
    val skipped = preview.files.flatMap { f -> f.skipped.map { f to it } }
    val failed = preview.files.filter { it.error != null }
    val body = if (mobile) pageText(14f) else pageText(12.5f)
    if (files.isEmpty() && skipped.isEmpty() && failed.isEmpty()) {
        BasicText("Nothing this turn wrote is left to put back.", Modifier.cssLines(if (mobile) 21f else 18.75f), body.copy(color = tokens.text2.color))
        return
    }
    val count = files.size + skipped.size + failed.size
    var at = 0
    // Each row's text column lends the row its exact height: a name over a folder is 31.2 px.
    @Composable
    fun row(warn: Boolean, content: @Composable RowScope.(lines: CssExact) -> Unit) {
        val last = ++at == count
        val lines = rememberCssExact()
        Row(
            Modifier
                .fillMaxWidth()
                .cssMinHeight(if (mobile) 52.dp else 36.dp, lend = lines)
                .then(if (warn) Modifier.background(tokens.warning.opacity(0.09).color) else Modifier)
                .then(if (last) Modifier else Modifier.cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) { content(lines) }
    }
    val shape = RoundedCornerShape(12.dp)
    CssColumn(Modifier.fillMaxWidth().clip(shape).cssBorder(tokens.borderSoft.color, shape)) {
        for (f in files) {
            key("f:${f.path}") {
                row(warn = false) { lines ->
                    val (base, dir) = SessionChangeList.splitPath(f.path)
                    FileIcon(base, size = 16.dp)
                    // `leading-[1.3]`.
                    val size = if (mobile) 14f else 12.5f
                    CssColumn(Modifier.weight(1f), exact = lines) {
                        TruncatedText(AnnotatedString(base), ppmText(size, FontWeight.Medium, lineHeight = size * 1.3f).copy(color = tokens.text.color), Modifier.cssLines(size * 1.3f))
                        // The folder gives way from its start; what happens to the file is never cut.
                        val small = ppmText(11.5f, lineHeight = 11.5f * 1.3f).copy(color = tokens.text3.color)
                        Row(Modifier.cssLines(11.5f * 1.3f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (dir.isNotEmpty()) StartTruncatedText(dir, small, Modifier.weight(1f, fill = false))
                            BasicText("${if (dir.isNotEmpty()) "· " else ""}${fileWhat(f)}", style = small, maxLines = 1, softWrap = false)
                        }
                    }
                    ChangeCounts(f.added, f.removed, ppmText(11f, kind = FontKind.MONO, lineHeight = 11f * 1.3f))
                }
            }
        }
        skipped.forEachIndexed { i, (f, s) ->
            key("s:${f.path}:$i") {
                row(warn = true) { lines ->
                    PpmIcon(ProductIcon.TriangleAlert, 16.dp, tokens.warning.color)
                    val size = if (mobile) 14f else 12.5f
                    CssColumn(Modifier.weight(1f), exact = lines) {
                        BasicText(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = tokens.text.color)) { append(SessionChangeList.splitPath(f.path).first) }
                                append(" line ${s.line} stays as it is — ${laterLabel(s.by, byCall)} changed it again.")
                            },
                            Modifier.cssLines(size * 1.4f),
                            ppmText(size, lineHeight = size * 1.4f).copy(color = tokens.text2.color),
                        )
                    }
                }
            }
        }
        for (f in failed) {
            key("e:${f.path}") {
                row(warn = true) { lines ->
                    PpmIcon(ProductIcon.TriangleAlert, 16.dp, tokens.error.color)
                    val size = if (mobile) 14f else 12.5f
                    CssColumn(Modifier.weight(1f), exact = lines) {
                        BasicText(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = tokens.text.color)) { append(SessionChangeList.splitPath(f.path).first) }
                                append(": ${f.error}")
                            },
                            Modifier.cssLines(size * 1.4f),
                            ppmText(size, lineHeight = size * 1.4f).copy(color = tokens.text2.color),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The confirmation's own lines around the summary (`RevertTurnBody`), shared by the popover and the
 * sheet, stacked as Chrome stacks them: a 21 px line at 2.625x is 55.125 px, which Compose rounds up.
 */
@Composable
private fun RevertTurnBody(flow: RevertTurnFlow, mobile: Boolean, byCall: Map<String, SessionTurn>, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val size = if (mobile) 14f else 12.5f
    val body = pageText(size)
    val lines = Modifier.cssLines(size * 1.5f)
    val preview = flow.preview
    CssColumn(modifier) {
        if (preview == null) {
            BasicText("Working out what this turn wrote…", lines, body.copy(color = tokens.text2.color))
        } else {
            val files = flow.reverted
            val added = files.sumOf { it.added }
            val removed = files.sumOf { it.removed }
            val skips = preview.files.any { it.skipped.isNotEmpty() }
            if (flow.moved) {
                BasicText("A file changed since — this is what reverting does now.", lines, body.copy(color = tokens.warning.color, fontWeight = FontWeight.Medium))
                Spacer(Modifier.cssHeight(8.dp))
            }
            if (files.isNotEmpty()) {
                val mono = SpanStyle(fontFamily = PpmFonts.mono)
                CssMixedText(
                    buildAnnotatedString {
                        append("Puts back what this answer wrote, on disk: ${TurnReview.plural(files.sumOf { maxOf(it.changes, 1) }, "change")} in ${TurnReview.plural(files.size, "file")}, ")
                        withStyle(mono.copy(color = tokens.success.color)) { append("+$added") }
                        append(" ")
                        withStyle(mono.copy(color = tokens.error.color)) { append("−$removed") }
                        append(" undone.")
                        if (!skips) append(" No later turn changed these lines.")
                    },
                    body.copy(color = tokens.text2.color),
                    lineHeight = size * 1.5f,
                )
                Spacer(Modifier.cssHeight(12.dp))
            }
            RevertTurnSummary(preview, mobile, byCall)
            Spacer(Modifier.cssHeight(12.dp))
            BasicText(
                "The chat itself stays as it is. You can undo it right after.",
                Modifier.cssLines(if (mobile) 19.5f else 16f),
                (if (mobile) pageText(13f) else xsText()).copy(color = tokens.text3.color),
            )
        }
    }
}

// Tray

/** The confirmation's shadow: the theme's float shadow and `0 18px 40px -16px rgba(0,0,0,.6)` under it. */
private val revertShadow = ThemeShadow(0.0, 18.0, 40.0, -16.0, Rgba(0.0, 0.0, 0.0, 0.6))

/**
 * The desktop tray (`TurnChangeTray`): an inline, non-modal panel under the action bar holding
 * every edit the turn made, grouped by file, each with its lines and — once the session's list
 * can say which blocks it wrote — Keep and Revert. The head keeps the whole turn, reverts it from
 * a confirmation the server works out first, or opens the Review tab. Escape cancels the
 * confirmation, then closes the tray.
 */
@Composable
private fun TurnChangeTray(changes: List<TurnFileChange>, turn: SessionTurn?, view: TurnReviewView, onJump: (String) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val model = view.model
    val flow = view.flow
    val byCall = LocalTurnsByCall.current
    val shape = RoundedCornerShape(12.dp)
    val (added, removed) = remember(changes) { TurnReview.totals(changes) }
    // Escape cancels the confirmation, then closes the tray.
    val host = LocalTurnChanges.current
    val escape by rememberUpdatedState { if (flow != null) model?.cancelRevert() else onClose() }
    DisposableEffect(host) {
        val claim = TurnTrayEscape.Claim(host?.ownsKeys ?: { true }) { escape() }
        TurnTrayEscape.take(claim)
        onDispose { TurnTrayEscape.release(claim) }
    }
    Column(
        modifier
            .fillMaxWidth()
            .ppmShadow(tokens.shadowFloat, shape)
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, shape),
    ) {
        // `relative flex min-h-12 flex-wrap items-center gap-x-2.5 gap-y-1.5 border-b border-border-soft py-1.5 pr-2 pl-3.5`.
        WrapRow(
            Modifier
                .fillMaxWidth()
                .cssMinHeight(48.dp)
                .cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom)
                .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            gapX = 10.dp,
            gapY = 6.dp,
        ) {
            BasicText("Changed this turn", style = pageText(13f, FontWeight.SemiBold).copy(color = tokens.text.color), maxLines = 1, softWrap = false)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText("${TurnReview.editsInFiles(changes)} ·", style = xsText().copy(color = tokens.text3.color), maxLines = 1, softWrap = false)
                ChangeCounts(added, removed, xsText(11f, kind = FontKind.MONO))
            }
            // `relative ml-auto flex flex-wrap items-center justify-end gap-1.5`.
            Box {
                var groupHeight by remember { mutableIntStateOf(0) }
                FlowRow(
                    Modifier.onSizeChanged { groupHeight = it.height },
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (view.enabled && turn != null && model != null) {
                        WebButton(
                            "Revert turn…", ButtonKind.GHOST, icon = icon(ProductIcon.RotateCcw, 16.dp), color = tokens.text2,
                            enabled = !(view.busy && flow == null),
                        ) { if (flow != null) model.cancelRevert() else model.startRevert() }
                    }
                    if (view.summary.open > 0 && model != null) {
                        WebButton("Keep all", ButtonKind.PRIMARY, icon = icon(ProductIcon.Check, 16.dp), enabled = !view.busy) { model.keepAll() }
                    }
                    if (view.enabled) {
                        WebButton("Review in tab", ButtonKind.GHOST, icon = { PpmIcon(LucideIcon.FileDiff, 16.dp, it) }, color = tokens.accent, keepColor = true) {
                            view.openReview(changes.firstOrNull()?.filePath)
                        }
                    }
                    SquareIconButton(ProductIcon.X, 14.dp, 32.dp, 12.dp, "Close change tray", title = "Close (Esc)", onClick = onClose)
                }
                if (flow != null && turn != null && model != null) {
                    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
                    Popup(
                        alignment = Alignment.TopEnd,
                        offset = IntOffset(0, groupHeight + gap),
                        properties = PopupProperties(focusable = false, clippingEnabled = false),
                    ) {
                        RevertConfirm(turn, flow, byCall, onCancel = model::cancelRevert, onConfirm = model::confirmRevert)
                    }
                }
            }
        }
        view.local.notice?.let { notice ->
            NoticeLine(notice, mobile = false, busy = view.busy, onUndo = { model?.undo(it) }, onDismiss = { model?.dismissNotice() })
        }
        // `max-h-[560px] overflow-y-auto rounded-b-xl px-3 pt-1 pb-3`.
        val scroll = rememberScrollState()
        CssColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .chromeScrollbar(scroll, vertical = true, tokens.border.color)
                .verticalScroll(scroll)
                .padding(horizontal = 12.dp),
            top = 4.dp,
            bottom = 12.dp,
        ) {
            for (change in changes) key(change.filePath) { FileGroup(change, view, mobile = false, onJump) }
        }
    }
}

/**
 * The tray head's row (`flex flex-wrap items-center gap-x-2.5 gap-y-1.5`) as CSS wraps it: each
 * child as wide as its content within the row, one that no longer fits starting the next line
 * whole, and the last (`ml-auto`) pushed to the end of whichever line it lands on. Each line's
 * children are centred in it, and the lines in the room the row's minimum height leaves.
 */
@Composable
private fun WrapRow(modifier: Modifier, gapX: Dp, gapY: Dp, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val gx = gapX.toPx()
        val gy = gapY.toPx()
        val placeables = measurables.map { it.measure(Constraints(maxWidth = width)) }
        val starts = ArrayList<Int>()
        var x = 0f
        placeables.forEachIndexed { index, placeable ->
            if (index == 0 || x + gx + placeable.width > width) {
                starts += index
                x = placeable.width.toFloat()
            } else {
                x += gx + placeable.width
            }
        }
        val lines = starts.mapIndexed { line, start -> start until (starts.getOrNull(line + 1) ?: placeables.size) }
        val heights = lines.map { line -> line.maxOfOrNull { placeables[it].height } ?: 0 }
        val total = heights.sum() + gy * (lines.size - 1).coerceAtLeast(0)
        val height = max(constraints.minHeight, total.roundToInt())
        layout(width, height) {
            var y = (height - total) / 2
            lines.forEachIndexed { index, line ->
                var at = 0f
                for (child in line) {
                    val placeable = placeables[child]
                    val left = if (child == placeables.lastIndex) width - placeable.width else at.roundToInt()
                    placeable.place(left, (y + (heights[index] - placeable.height) / 2f).roundToInt())
                    at += placeable.width + gx
                }
                y += heights[index] + gy
            }
        }
    }
}

/**
 * "Revert Turn N?" under the tray's head (`w-[400px] max-w-[calc(100vw-48px)] rounded-xl border
 * bg-panel-2 p-3.5 text-[12.5px] leading-normal`): what reverting does, then Cancel and Revert turn.
 */
@Composable
private fun RevertConfirm(turn: SessionTurn, flow: RevertTurnFlow, byCall: Map<String, SessionTurn>, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    val screen = LocalConfiguration.current.screenWidthDp.dp
    val nothing = flow.preview != null && flow.reverted.isEmpty()
    CssColumn(
        Modifier
            .width(400.dp)
            .widthIn(max = screen - 48.dp)
            .ppmShadow(tokens.shadowFloat, shape)
            .ppmShadow(revertShadow, shape)
            .clip(shape)
            .background(tokens.panel2.color)
            .cssBorder(tokens.border.color, shape)
            .padding(horizontal = 14.dp),
        top = 14.dp,
        bottom = 14.dp,
    ) {
        // `mb-1.5 flex items-center gap-2 text-[13px]`: the line is 19.5 px, the icon 16 beside it.
        Row(Modifier.cssHeight(19.5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PpmIcon(ProductIcon.RotateCcw, 16.dp, tokens.error.color)
            BasicText("Revert ${SessionTurns.label(turn)}?", style = pageText(13f, FontWeight.SemiBold).copy(color = tokens.text.color))
        }
        Spacer(Modifier.cssHeight(6.dp))
        RevertTurnBody(flow, mobile = false, byCall)
        Spacer(Modifier.cssHeight(12.dp))
        Row(Modifier.fillMaxWidth().cssHeight(32.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
            WebButton("Cancel", ButtonKind.GHOST, onClick = onCancel)
            WebButton("Revert turn", ButtonKind.DESTRUCTIVE, icon = icon(ProductIcon.RotateCcw, 16.dp), enabled = !flow.loading && flow.preview != null && !nothing, onClick = onConfirm)
        }
    }
}

// Sheet

/**
 * The sheet's footer buttons (`inline-flex h-11 flex-1 items-center justify-center gap-2
 * rounded-[10px] border px-3.5 text-sm font-medium disabled:opacity-50`): bordered on the panel,
 * or filled.
 */
@Composable
private fun FootButton(label: String, icon: ProductIcon?, fill: Rgba?, content: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background((fill ?: tokens.panel2).color)
            .ppmClickable(enabled = enabled, pressedColor = Color.Transparent, shape = shape, onClickLabel = label, onClick = onClick)
            .cssBorder(if (fill != null) Color.Transparent else tokens.border.color, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) PpmIcon(icon, 20.dp, content)
        BasicText(label, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = content), maxLines = 1, softWrap = false)
    }
}

/**
 * The sheet's head (`SheetHead`, `flex items-center gap-1 pt-0.5 pr-1.5 pb-2 pl-4`): the title
 * (`text-[15px] font-semibold`) with its icon, a line under it (`line-clamp-2 text-xs`), what
 * [extra] adds, and Close.
 */
@Composable
private fun SheetHead(title: String, sub: String?, icon: (@Composable () -> Unit)?, onClose: () -> Unit, extra: @Composable () -> Unit = {}) {
    val tokens = LocalPpmTokens.current
    // As tall as the 44 px buttons or the lines beside them, whichever is taller, counted exactly.
    val lines = rememberCssExact()
    CssColumn(Modifier.fillMaxWidth(), top = 2.dp, bottom = 8.dp) {
        Row(
            Modifier.fillMaxWidth().cssMinHeight(44.dp, lend = lines).padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CssColumn(Modifier.weight(1f), gap = 2.dp, exact = lines) {
                Row(Modifier.cssLines(22.5f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    icon?.invoke()
                    TruncatedText(AnnotatedString(title), pageText(15f, FontWeight.SemiBold).copy(color = tokens.text.color))
                }
                if (!sub.isNullOrEmpty()) {
                    ClampedText(AnnotatedString(sub), xsText().copy(color = tokens.text3.color), maxLines = 2, modifier = Modifier.cssLines(16f))
                }
            }
            extra()
            SquareIconButton(ProductIcon.X, 20.dp, 44.dp, 12.dp, "Close", onClick = onClose)
        }
    }
}

/**
 * The phone's sheet (`TurnChangeSheet`): every edit the turn made, grouped by file, each answered
 * with full-width Keep and Revert under its lines; the footer keeps or reverts the whole turn,
 * and reverting asks first, in the same sheet. The body scrolls inside it.
 */
@Composable
private fun ColumnScope.TurnChangeSheetContent(
    top: Dp,
    changes: List<TurnFileChange>,
    turn: SessionTurn?,
    view: TurnReviewView,
    onClose: () -> Unit,
    onJump: (String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val model = view.model
    val flow = view.flow
    val byCall = LocalTurnsByCall.current
    val summary = view.summary
    // Laid out from where the sheet's content starts inside its first pixel, as Chrome lays it out:
    // the body gives way to the head and the footer, whose paddings are set heights of their own.
    CssColumn(Modifier.fillMaxWidth().weight(1f, fill = false), top = top) {
        if (flow != null && turn != null && model != null) {
            val time = turn.at?.let { PpmFormat.turnTime(it) }.orEmpty()
            SheetHead(
                "Revert ${SessionTurns.label(turn)}?",
                listOf(time, turn.prompt).filter { it.isNotEmpty() }.joinToString(" · "),
                icon = { PpmIcon(ProductIcon.RotateCcw, 20.dp, tokens.error.color) },
                onClose = model::cancelRevert,
            )
            // `min-h-0 flex-1 overflow-y-auto px-4 text-sm leading-normal`.
            val scroll = rememberScrollState()
            RevertTurnBody(flow, mobile = true, byCall, Modifier.fillMaxWidth().cssFlex().cssScrollY(scroll).padding(horizontal = 16.dp))
            val nothing = flow.preview != null && flow.reverted.isEmpty()
            // `flex gap-2 px-4 pt-3 pb-1`.
            Spacer(Modifier.cssHeight(12.dp))
            Row(Modifier.fillMaxWidth().cssHeight(44.dp).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FootButton("Cancel", null, null, tokens.text.color, enabled = true, Modifier.weight(1f), model::cancelRevert)
                FootButton(
                    "Revert turn", ProductIcon.RotateCcw, tokens.error.mix(Rgba(0.0, 0.0, 0.0, 1.0), 0.85), Color.White,
                    enabled = !flow.loading && flow.preview != null && !nothing, Modifier.weight(1f), model::confirmRevert,
                )
            }
            Spacer(Modifier.cssHeight(4.dp))
        } else {
            SheetHead(
                "Changed this turn",
                TurnReview.editsInFiles(changes) + if (summary.kept > 0) " · ${summary.kept} kept" else "",
                icon = null,
                onClose = onClose,
            ) {
                if (view.enabled) {
                    // `inline-flex h-11 shrink-0 items-center gap-1.5 rounded-lg px-2.5 text-sm font-medium text-primary`.
                    Row(
                        Modifier
                            .height(44.dp)
                            .ppmClickable(pressedColor = tokens.panel.color, shape = RoundedCornerShape(12.dp), onClickLabel = "Review") {
                                onClose()
                                view.openReview(changes.firstOrNull()?.filePath)
                            }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PpmIcon(LucideIcon.FileDiff, 16.dp, tokens.accent.color)
                        BasicText("Review", style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.accent.color), maxLines = 1, softWrap = false)
                    }
                }
            }
            view.local.notice?.let { notice ->
                NoticeLine(notice, mobile = true, busy = view.busy, onUndo = { model?.undo(it) }, onDismiss = { model?.dismissNotice() })
            }
            // `min-h-0 flex-1 overflow-y-auto border-t border-border-soft px-3 pb-3`.
            val scroll = rememberScrollState()
            CssColumn(
                Modifier
                    .fillMaxWidth()
                    .cssFlex()
                    .cssBorder(tokens.borderSoft.color, sides = CssSides.Top)
                    .cssScrollY(scroll)
                    .padding(horizontal = 12.dp),
                bottom = 12.dp,
            ) {
                for (change in changes) key(change.filePath) { FileGroup(change, view, mobile = true, onJump) }
            }
            if (view.enabled && model != null && (turn != null || summary.open > 0)) {
                // `flex gap-2 px-3 pt-2 pb-1`.
                Spacer(Modifier.cssHeight(8.dp))
                Row(Modifier.fillMaxWidth().cssHeight(44.dp).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (turn != null) {
                        FootButton("Revert turn…", ProductIcon.RotateCcw, null, tokens.text.color, enabled = !view.busy, Modifier.weight(1f), model::startRevert)
                    }
                    if (summary.open > 0) {
                        FootButton("Keep all ${summary.open}", ProductIcon.Check, tokens.accent, tokens.accentFg.color, enabled = !view.busy, Modifier.weight(1f), model::keepAll)
                    }
                }
                Spacer(Modifier.cssHeight(4.dp))
            }
        }
    }
}

/** Each call's turn in this chat, for naming the turn that changed a line since. */
internal val LocalTurnsByCall = staticCompositionLocalOf<Map<String, SessionTurn>> { emptyMap() }
