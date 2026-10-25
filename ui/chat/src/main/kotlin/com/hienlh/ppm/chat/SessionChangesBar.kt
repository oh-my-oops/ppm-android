package com.hienlh.ppm.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CssGridRow
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FileIcon
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.StartTruncatedText
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.chromeScrollbar
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.cssHeight
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.SessionFileChange
import com.hienlh.ppm.state.SessionChangeList

/** `text-xs`: 12 on 16. */
private val xsText = ppmText(12f, lineHeight = 16f)

/** `text-[11px]` under `text-xs`'s line height of 4/3. */
private const val XS_11_LINE = 44f / 3

/** `text-[11px]` under the page's line height of 1.5. */
private val pageSmallText = ppmText(11f, lineHeight = 16.5f)

/** What the list leaves out of a session run by [providerId] (`shellChangesHint`). */
fun shellChangesHint(providerId: String?): String =
    if (providerId == "claude") {
        "Shell commands are followed through git: a file outside a repository, or one git ignores, is not listed. Source Control shows everything on disk."
    } else {
        "Files changed by shell commands are not listed — Source Control shows everything on disk."
    }

/** One CSS border's height as Chrome snaps it, in dp. */
@Composable
private fun borderDp(): Dp = with(LocalDensity.current) { cssBorderPx(1.dp).toDp() }

/** The +added −removed pair the tray, the changes bar and the Review tab all show (`ChangeCounts`); ×N for an edit made N times. */
@Composable
fun ChangeCounts(added: Int, removed: Int, style: TextStyle, modifier: Modifier = Modifier, editCount: Int? = null) {
    val tokens = LocalPpmTokens.current
    // `tabular-nums`.
    val numbers = style.copy(fontFeatureSettings = "tnum")
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        BasicText("+$added", style = numbers.copy(color = tokens.success.color), maxLines = 1, softWrap = false)
        BasicText("−$removed", style = numbers.copy(color = tokens.error.color), maxLines = 1, softWrap = false)
        if (editCount != null && editCount > 1) BasicText("×$editCount", style = numbers.copy(color = tokens.text3.color), maxLines = 1, softWrap = false)
    }
}

/** A file's counts, or why it has none: `binary`, `too large`. */
@Composable
fun SessionChangeCounts(file: SessionFileChange, style: TextStyle, modifier: Modifier = Modifier) {
    val additions = file.additions
    val deletions = file.deletions
    if (additions == null || deletions == null) {
        val why = if (file.binary) "binary" else if (file.tooLarge) "too large" else ""
        BasicText(why, modifier, style.copy(color = LocalPpmTokens.current.text3.color), maxLines = 1, softWrap = false)
    } else {
        ChangeCounts(additions, deletions, style, modifier)
    }
}

/** The box a Reviewed checkbox draws (`ReviewedBox`): `size-4 rounded border`, filled with a tick once checked. */
@Composable
fun ReviewedBox(checked: Boolean, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    // `transition-colors`: the fill and the border fade, the tick does not.
    val fill = colorsFade(checked).toDouble()
    Box(
        modifier
            .size(16.dp)
            .clip(shape)
            .background(tokens.accent.opacity(fill).color)
            .cssBorder(tokens.accent.mix(tokens.text3, fill).color, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) PpmIcon(ProductIcon.Check, 12.dp, tokens.accentFg.color)
    }
}

/** Source Control's letter, colour and name for each state. */
private fun statusOf(status: SessionFileChange.Status): Triple<String, String, Boolean?> = when (status) {
    SessionFileChange.Status.ADDED -> Triple("A", "Added", true)
    SessionFileChange.Status.MODIFIED -> Triple("M", "Modified", null)
    SessionFileChange.Status.DELETED -> Triple("D", "Deleted", false)
}

/**
 * One file in the session's change list (`SessionChangeRow`), shared by the changes bar and the
 * Review tab: icon, name over its folder, counts and status letter, then — with
 * [onToggleReviewed] — a Reviewed checkbox of its own beside the row. [dense] is the 36px desktop
 * row; otherwise the 52px touch row.
 */
@Composable
fun SessionChangeRow(
    file: SessionFileChange,
    projectPath: String?,
    dense: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onToggleReviewed: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val (base, dir) = SessionChangeList.splitPath(SessionChangeList.displayPath(file.path, projectPath))
    val deleted = file.status == SessionFileChange.Status.DELETED
    // Its two lines, or one, between `py-1`/`py-1.5`, and never under `min-h-9`/`min-h-[52px]`.
    val nameLine = if (dense) 16f else 19.5f
    val dirLine = if (dense) XS_11_LINE else 16.5f
    val content = nameLine + (if (dir.isNotEmpty()) dirLine else 0f)
    val height = if (dense) maxOf(36f, content + 8f) else maxOf(52f, content + 12f)
    val countsStyle = ppmText(11f, kind = FontKind.MONO, lineHeight = dirLine)
    Row(modifier.fillMaxWidth().cssHeight(height.dp + borderDp()).cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom)) {
        val accent = tokens.accent.color
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .webTitle(file.path)
                .then(
                    // `bg-surface shadow-[inset_2px_0_0_var(--accent)]`.
                    if (selected) Modifier.background(tokens.panel.color).drawBehind { drawRect(accent, size = Size(2.dp.toPx(), size.height)) } else Modifier,
                )
                .ppmClickable(pressedColor = tokens.panel.color, hoverColor = tokens.panel.color, onClickLabel = file.path, onClick = onClick)
                .padding(horizontal = if (dense) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FileIcon(base, size = 16.dp)
            CssColumn(Modifier.weight(1f)) {
                val nameStyle = ppmText(if (dense) 12f else 13f, lineHeight = nameLine).copy(
                    color = if (deleted || file.reviewed) tokens.text2.color else tokens.text.color,
                    textDecoration = if (deleted) TextDecoration.LineThrough else null,
                )
                TruncatedText(AnnotatedString(base), nameStyle, Modifier.cssLines(nameLine))
                if (dir.isNotEmpty()) {
                    StartTruncatedText(dir, ppmText(11f, lineHeight = dirLine).copy(color = tokens.text3.color), Modifier.fillMaxWidth().cssLines(dirLine))
                }
            }
            SessionChangeCounts(file, countsStyle)
            val (glyph, label, tone) = statusOf(file.status)
            val color = when (tone) {
                true -> tokens.success
                false -> tokens.error
                null -> tokens.warning
            }
            // `text-center`, centred as a box centres it: `TextAlign.Center` evens the line's width
            // first and set the letter a pixel right of Chrome's.
            Box(Modifier.width(12.dp).webTitle(label), contentAlignment = Alignment.Center) {
                BasicText(glyph, style = countsStyle.copy(color = color.color, fontWeight = FontWeight.SemiBold), maxLines = 1, softWrap = false)
            }
        }
        if (onToggleReviewed != null) {
            val reviewed = file.reviewed
            Box(
                Modifier
                    .width(if (dense) 32.dp else 44.dp)
                    .fillMaxHeight()
                    .webTitle(if (reviewed) "Reviewed — click to bring it back to the list" else "Mark reviewed")
                    .ppmClickable(pressedColor = tokens.panel.color, hoverColor = tokens.panel.color, role = Role.Checkbox, onClickLabel = "Mark $base reviewed", onClick = onToggleReviewed),
                contentAlignment = Alignment.Center,
            ) {
                ReviewedBox(reviewed)
            }
        }
    }
}

/** The row that hides or shows the files marked reviewed (`ReviewedToggle`). */
@Composable
fun ReviewedToggle(count: Int, shown: Boolean, dense: Boolean, onToggle: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val style = if (dense) pageSmallText else xsText
    Row(
        Modifier
            .fillMaxWidth()
            // `min-h-8`/`min-h-11` with the border inside it (`border-box`).
            .cssHeight(if (dense) 32.dp else 44.dp)
            .cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom)
            .ppmClickable(pressedColor = tokens.panel.color, hoverColor = tokens.panel.color, onClickLabel = if (shown) "Hide reviewed" else "Show reviewed", onClick = onToggle)
            .padding(horizontal = if (dense) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(if (shown) ProductIcon.EyeOff else ProductIcon.Eye, 14.dp, tokens.text2.color)
        BasicText("$count reviewed", Modifier.weight(1f), style.copy(color = tokens.text2.color), maxLines = 1, softWrap = false)
        BasicText(if (shown) "Hide" else "Show", style = style.copy(color = tokens.text3.color), maxLines = 1, softWrap = false)
    }
}

/**
 * Every file this chat session has changed, pinned above the composer (`SessionChangesBar`):
 * "N files changed +X −Y", which opens into the list — inline on a tablet, a bottom sheet on a
 * phone, drawn by [sheet] — and a Review button for the session's Review tab. A file marked
 * reviewed leaves the list and the totals until the agent changes it again, behind one
 * "N reviewed" row; with every file reviewed the bar is that one line. Nothing while no file
 * changed. [narrow] is the chat's own `@max-[420px]`.
 */
@Composable
fun SessionChangesBar(
    files: List<SessionFileChange>,
    /**
     * Whether [files] is the server's answer. The list is worked out only when opened, so until
     * then the bar counts the [unloadedCount] files the transcript names, with no line totals.
     */
    loaded: Boolean,
    loading: Boolean,
    unloadedCount: Int,
    projectPath: String?,
    providerId: String?,
    narrow: Boolean,
    onReview: (String?) -> Unit,
    /** The list was opened: worth asking the server again, the disk may have moved. */
    onOpen: () -> Unit,
    /** Ask the server again on demand: the list never refreshes on its own. */
    onRefresh: (() -> Unit)?,
    onSetReviewed: ((List<SessionFileChange>, Boolean) -> Unit)?,
    sheet: @Composable (onDismiss: () -> Unit, content: @Composable ColumnScope.(top: Dp) -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var showReviewed by remember { mutableStateOf(false) }
    if (if (loaded) files.isEmpty() && !(loading && open) else unloadedCount == 0) return
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val (pending, reviewed) = SessionChangeList.splitReviewed(files)
    val allReviewed = loaded && files.isNotEmpty() && pending.isEmpty()
    // Nothing else to show once every file is reviewed.
    val reviewedShown = showReviewed || allReviewed
    val (added, removed) = SessionChangeList.totals(pending)
    val toggle = {
        if (!open) onOpen()
        open = !open
    }
    val review = { path: String? ->
        open = false
        // The Review tab follows the agent through this list, so opening it is opening the list.
        if (!loaded) onOpen()
        onReview(path)
    }
    val markAll: () -> Unit = {
        open = false
        onSetReviewed?.invoke(pending, true)
    }
    val rows = @Composable { dense: Boolean ->
        ChangeRows(loading, pending, reviewed, reviewedShown, showToggle = reviewed.isNotEmpty() && !allReviewed, showReviewed, dense, projectPath, review, onSetReviewed) {
            showReviewed = !showReviewed
        }
    }

    // `shrink-0 border-t border-border bg-surface-elevated/60`.
    Column(
        Modifier.fillMaxWidth().background(tokens.panel2.opacity(0.6).color).cssBorder(tokens.border.color, sides = CssSides.Top),
    ) {
        // `flex items-center gap-1 px-1.5`.
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val label = when {
                !loaded -> "${SessionChangeList.fileCount(unloadedCount)} changed"
                allReviewed -> "All ${SessionChangeList.fileCount(reviewed.size)} reviewed"
                else -> "${SessionChangeList.fileCount(pending.size)} changed"
            }
            // `flex min-h-11 min-w-0 flex-1 items-center gap-2 rounded px-1 text-xs md:min-h-8`.
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = if (mobile) 44.dp else 32.dp)
                    .ppmClickable(pressedColor = tokens.panel.color, hoverColor = tokens.panel.color, shape = RoundedCornerShape(4.dp), onClickLabel = label, onClick = toggle)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // `transition-transform`, a quarter turn while the inline list is open.
                val turn by animateFloatAsState(if (open && !mobile) 90f else 0f, tween(150), label = "chevron")
                PpmIcon(ProductIcon.ChevronRight, 14.dp, tokens.text3.color, Modifier.rotate(turn))
                if (allReviewed) PpmIcon(ProductIcon.CheckCircle2, 14.dp, tokens.success.color)
                TruncatedText(
                    AnnotatedString(label),
                    ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = if (allReviewed) tokens.text2.color else tokens.text.color),
                    Modifier.weight(1f, fill = false),
                )
                if (loaded && !allReviewed) ChangeCounts(added, removed, ppmText(11f, kind = FontKind.MONO, lineHeight = XS_11_LINE))
                if (!allReviewed && reviewed.isNotEmpty() && !narrow) {
                    BasicText("· ${reviewed.size} reviewed", style = ppmText(11f, lineHeight = XS_11_LINE).copy(color = tokens.text3.color), maxLines = 1, softWrap = false)
                }
            }
            if (onRefresh != null) RefreshButton(mobile, loading, onRefresh)
            if (!mobile && loaded && !allReviewed && onSetReviewed != null) MarkAllButton(narrow, markAll)
            if (!allReviewed) ReviewButton(mobile) { review(null) }
        }

        if (!mobile && open) {
            // `max-h-56 overflow-y-auto border-t border-border-soft`; Escape closes it.
            val scroll = rememberScrollState()
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 224.dp)
                    .cssBorder(tokens.borderSoft.color, sides = CssSides.Top)
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                            open = false
                            true
                        } else {
                            false
                        }
                    },
            ) {
                CssColumn(Modifier.fillMaxWidth().chromeScrollbar(scroll, vertical = true, tokens.border.color).verticalScroll(scroll)) {
                    rows(true)
                    // `px-2.5 py-1.5 text-[11px] text-text-subtle`.
                    CssColumn(Modifier.fillMaxWidth().padding(horizontal = 10.dp), top = 6.dp, bottom = 6.dp) {
                        BasicText(shellChangesHint(providerId), Modifier.cssLines(16.5f), pageSmallText.copy(color = tokens.text3.color))
                    }
                }
            }
        }
    }

    if (mobile && open) {
        sheet({ open = false }) { top ->
            ChangesSheetContent(
                top, loaded, allReviewed, added, removed, if (loaded) pending.size else unloadedCount, providerId,
                onClose = { open = false },
                onMarkAll = if (loaded && onSetReviewed != null) markAll else null,
                onReviewAll = { review(null) },
                rows = { rows(false) },
            )
        }
    }
}

/** The files still to review, then the row that shows or hides the reviewed ones, and those while shown. */
@Composable
private fun ChangeRows(
    loading: Boolean,
    pending: List<SessionFileChange>,
    reviewed: List<SessionFileChange>,
    reviewedShown: Boolean,
    showToggle: Boolean,
    showReviewed: Boolean,
    dense: Boolean,
    projectPath: String?,
    onReview: (String?) -> Unit,
    onSetReviewed: ((List<SessionFileChange>, Boolean) -> Unit)?,
    onToggleShown: () -> Unit,
) {
    @Composable
    fun row(file: SessionFileChange) = key(file.path) {
        SessionChangeRow(
            file, projectPath, dense,
            onClick = { onReview(file.path) },
            onToggleReviewed = onSetReviewed?.let { set -> { set(listOf(file), !file.reviewed) } },
        )
    }
    if (loading && pending.isEmpty() && reviewed.isEmpty()) {
        val tokens = LocalPpmTokens.current
        // `text-text-subtle`, `px-2.5 py-1.5 text-[11px]` in the list, `px-4 py-3 text-xs` in the sheet.
        CssColumn(Modifier.fillMaxWidth().padding(horizontal = if (dense) 10.dp else 16.dp), top = if (dense) 6.dp else 12.dp, bottom = if (dense) 6.dp else 12.dp) {
            BasicText("Working out what changed…", Modifier.cssLines(if (dense) 16.5f else 16f), (if (dense) pageSmallText else xsText).copy(color = tokens.text3.color))
        }
    }
    pending.forEach { row(it) }
    if (showToggle) ReviewedToggle(reviewed.size, showReviewed, dense, onToggleShown)
    if (reviewedShown) reviewed.forEach { row(it) }
}

/**
 * "Mark all reviewed" (`inline-flex min-h-8 gap-1.5 rounded px-2 text-xs text-text-secondary
 * hover:bg-surface hover:text-text-primary`): on a narrow chat the words go and the icon stays.
 */
@Composable
private fun MarkAllButton(narrow: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover()
    val color = tokens.text.mix(tokens.text2, hover.lit.toDouble()).color
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .webTitle("Mark all reviewed")
            .ppmClickable(pressedColor = tokens.panel.color, hoverColor = tokens.panel.color, shape = RoundedCornerShape(4.dp), onClickLabel = "Mark all reviewed", source = hover.source, onClick = onClick)
            .padding(horizontal = if (narrow) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(ProductIcon.ListChecks, 14.dp, color)
        if (!narrow) BasicText("Mark all reviewed", style = xsText.copy(color = color), maxLines = 1, softWrap = false)
    }
}

/**
 * Refresh changes (`inline-flex min-h-11 min-w-11 rounded text-text-secondary hover:bg-surface
 * hover:text-text-primary disabled:opacity-60 md:min-h-8 md:min-w-8`): spinning, and off, while a
 * list is on its way.
 */
@Composable
private fun RefreshButton(mobile: Boolean, loading: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover()
    val side = if (mobile) 44.dp else 32.dp
    Box(
        Modifier
            .alpha(if (loading) 0.6f else 1f)
            .heightIn(min = side)
            .widthIn(min = side)
            .webTitle("Refresh changes")
            .ppmClickable(
                enabled = !loading, pressedColor = tokens.panel.color, hoverColor = tokens.panel.color, shape = RoundedCornerShape(4.dp),
                onClickLabel = "Refresh changes", source = hover.source, hoverWhileDisabled = true, onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(ProductIcon.RefreshCw, 14.dp, tokens.text.mix(tokens.text2, hover.lit.toDouble()).color, spinning = loading)
    }
}

/** Review (`inline-flex min-h-11 gap-1.5 rounded px-3 text-xs font-medium text-primary md:min-h-8 md:px-2`). */
@Composable
private fun ReviewButton(mobile: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .heightIn(min = if (mobile) 44.dp else 32.dp)
            .ppmClickable(pressedColor = tokens.panel.color, hoverColor = tokens.panel.color, shape = RoundedCornerShape(4.dp), onClickLabel = "Review", onClick = onClick)
            .padding(horizontal = if (mobile) 12.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(LucideIcon.FileDiff, 14.dp, tokens.accent.color)
        BasicText("Review", style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.accent.color), maxLines = 1, softWrap = false)
    }
}

/**
 * The phone's sheet (`max-h-[85%] flex-col`): "Changed in this chat" with its totals and a close
 * button, the rows at touch height, and at the bottom, in the thumb's reach, what the list leaves
 * out and the two actions. [top] is where in its first pixel the sheet's content starts.
 */
@Composable
private fun ColumnScope.ChangesSheetContent(
    top: Dp,
    loaded: Boolean,
    allReviewed: Boolean,
    added: Int,
    removed: Int,
    /** What "Review all" counts: the files left to review, or before the list is in, every file changed. */
    reviewCount: Int,
    providerId: String?,
    onClose: () -> Unit,
    onMarkAll: (() -> Unit)?,
    onReviewAll: () -> Unit,
    rows: @Composable () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    // `flex items-center gap-2 border-b border-border-soft pb-1 pl-4 pr-1`.
    CssColumn(Modifier.fillMaxWidth(), top = top) {
        Row(
            Modifier
                .fillMaxWidth()
                .cssHeight(48.dp + borderDp())
                .cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom)
                .padding(start = 16.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TruncatedText(AnnotatedString("Changed in this chat"), ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color), Modifier.weight(1f))
            if (loaded && !allReviewed) ChangeCounts(added, removed, ppmText(12f, kind = FontKind.MONO, lineHeight = 16f))
            // `inline-flex size-11 rounded-lg text-text-subtle hover:bg-surface hover:text-text-primary`.
            val hover = rememberHover()
            Box(
                Modifier
                    .size(44.dp)
                    .ppmClickable(pressedColor = tokens.panel.color, hoverColor = tokens.panel.color, shape = RoundedCornerShape(12.dp), onClickLabel = "Close", source = hover.source, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.X, 16.dp, tokens.text.mix(tokens.text3, hover.lit.toDouble()).color)
            }
        }
    }
    // `min-h-0 flex-1 overflow-y-auto`.
    val scroll = rememberScrollState()
    CssColumn(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(scroll)) { rows() }
    // `shrink-0 space-y-2 border-t border-border-soft px-3 pt-2`.
    CssColumn(Modifier.fillMaxWidth().cssBorder(tokens.borderSoft.color, sides = CssSides.Top).padding(horizontal = 12.dp), gap = 8.dp, top = 8.dp) {
        // `text-xs leading-relaxed`.
        BasicText(shellChangesHint(providerId), Modifier.cssLines(19.5f), ppmText(12f, lineHeight = 19.5f).copy(color = tokens.text3.color))
        if (!allReviewed) {
            // `flex-1` each: the bordered one is its two borders wider.
            CssGridRow(Modifier.fillMaxWidth(), gap = 8.dp, bases = if (onMarkAll != null) listOf(borderDp() * 2, 0.dp) else emptyList()) {
                if (onMarkAll != null) {
                    SheetButton(ProductIcon.ListChecks, null, "Mark all reviewed", primary = false, onClick = onMarkAll)
                }
                SheetButton(null, LucideIcon.FileDiff, "Review all ${SessionChangeList.fileCount(reviewCount)}", primary = true, onClick = onReviewAll)
            }
        }
    }
}

/**
 * The sheet's two actions (`flex min-h-11 flex-1 items-center justify-center gap-2 rounded-lg
 * text-sm font-medium`): bordered on the page's background, or filled with the primary colour.
 */
@Composable
private fun SheetButton(icon: ProductIcon?, lucide: LucideIcon?, label: String, primary: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    val hover = rememberHover()
    // The `hover:` look, which a finger on it shows too.
    val pressed by hover.source.collectIsPressedAsState()
    val lit = if (pressed) 1.0 else hover.lit.toDouble()
    val color = if (primary) tokens.accentFg.color else tokens.text.color
    val background = if (primary) tokens.accent.opacity(0.9).mix(tokens.accent, lit) else tokens.panel.mix(tokens.bgSolid, lit)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(background.color)
            .ppmClickable(pressedColor = Color.Transparent, shape = shape, onClickLabel = label, source = hover.source, onClick = onClick)
            .then(if (primary) Modifier else Modifier.cssBorder(tokens.border.color, shape)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) PpmIcon(icon, 16.dp, color)
        if (lucide != null) PpmIcon(lucide, 16.dp, color)
        BasicText(label, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = color), maxLines = 1, softWrap = false)
    }
}
