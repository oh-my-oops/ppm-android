package com.hienlh.ppm.chat

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CssMixedText
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.chromeScrollbar
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.cssExact
import com.hienlh.ppm.design.cssFlex
import com.hienlh.ppm.design.cssHeight
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberCssExact
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.ImageAudit
import com.hienlh.ppm.protocol.ImageStripResult
import com.hienlh.ppm.protocol.SessionDebugInfo
import com.hienlh.ppm.protocol.TurnRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * What the Session debug dialog asks the server for (session-debug-dialog.tsx), handed in by the
 * app so that this module stays off the network.
 */
class SessionDebugSource(
    val info: suspend () -> SessionDebugInfo,
    val turns: suspend () -> List<TurnRecord>,
    val images: suspend () -> ImageAudit,
    val strip: suspend (all: Boolean, includeAttachments: Boolean) -> ImageStripResult,
)

/**
 * The dialog's body (`flex flex-col gap-3`): the session's ids and transcript file with a Copy
 * button, then its token history and its transcript images, each loading on its own, stacked in
 * fractions of a pixel as Chrome stacks them, [top] and [bottom] inside its edges.
 * [renderStats] is read when the facts arrive, as the web gathers its render weight then;
 * [viewport] is the page's (its `vh`, and `sm:` from 640 wide).
 */
@Composable
fun SessionDebugBody(
    source: SessionDebugSource,
    renderStats: () -> List<String>,
    viewport: DpSize,
    modifier: Modifier = Modifier,
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
) {
    val tokens = LocalPpmTokens.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var info by remember(source) { mutableStateOf<String?>(null) }
    var copied by remember(source) { mutableStateOf(false) }
    LaunchedEffect(source) {
        info = try {
            debugInfoText(source.info(), renderStats())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            "Failed to load debug info"
        }
    }
    CssColumn(modifier.fillMaxWidth(), gap = 12.dp, top = top, bottom = bottom) {
        // `flex items-center gap-2 text-sm font-semibold text-foreground`, a `size-4 text-primary` bug.
        Row(Modifier.cssHeight(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PpmIcon(ProductIcon.Bug, 16.dp, tokens.accent.color)
            BasicText("Session debug info", style = ppmText(14f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color))
        }
        // `max-h-[35vh] overflow-y-auto whitespace-pre-wrap break-all rounded-md border border-border
        // bg-surface p-2.5 font-mono text-[11px] leading-relaxed text-text-secondary select-text`.
        val shape = RoundedCornerShape(8.dp)
        val scroll = rememberScrollState()
        CssColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = viewport.height * 0.35f)
                .chromeScrollbar(scroll, vertical = true, tokens.border.color, border = 1.dp)
                .clip(shape)
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, shape)
                .verticalScroll(scroll)
                .padding(horizontal = 10.dp),
            top = 10.dp,
            bottom = 10.dp,
        ) {
            Mono(info ?: "Loading...", tokens.text2, Modifier.cssLines(17.875f), breakAll = true, style = ppmText(11f, kind = FontKind.MONO, lineHeight = 17.875f))
        }
        // `Button size="sm" className="w-full"`, disabled until there is something to copy.
        val text = info
        ReviewButton(
            if (copied) "Copied!" else "Copy",
            WebVariant.DEFAULT,
            WebSize.SM,
            Modifier.fillMaxWidth().cssHeight(32.dp),
            icon = if (copied) ProductIcon.ClipboardCheck else ProductIcon.Copy,
            iconSize = 14.dp,
            enabled = text != null,
        ) {
            if (text == null) return@ReviewButton
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Session debug info", text)))
                copied = true
                delay(1_500)
                copied = false
            }
        }
        Section { TurnUsageHistory(source, viewport) }
        Section { TranscriptImages(source, viewport) }
    }
}

/** `border-t border-border pt-3`. */
@Composable
private fun Section(content: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    CssColumn(Modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = CssSides.Top), top = 12.dp, content = content)
}

/** The facts as the web lists them, one a line, the render weight after them. */
internal fun debugInfoText(data: SessionDebugInfo, renderStats: List<String>): String {
    val weight = data.jsonlSizeBytes?.let { size -> " (${fmtMB(size)}${data.jsonlLines?.let { ", $it records" } ?: ""})" } ?: ""
    return (
        listOfNotNull(
            "PPM Session: ${data.ppmSessionId}",
            "SDK Session: ${data.sdkSessionId}",
            if (!data.jsonlPath.isNullOrEmpty()) "JSONL: ${data.jsonlPath}$weight" else "JSONL: not found",
            data.projectPath.takeIf { it.isNotEmpty() }?.let { "Project: $it" },
        ) + renderStats.filter { it.isNotEmpty() }
        ).joinToString("\n")
}

private fun fmtMB(bytes: Long) = "${fixed(bytes / 1_048_576.0, 2)} MB"

/** `fmtSize`: megabytes to one decimal, else whole kilobytes, never under 1. */
internal fun fmtSize(bytes: Long): String =
    if (bytes >= 1_048_576) "${fixed(bytes / 1_048_576.0, 1)} MB" else "${maxOf(1L, jsRound(bytes / 1024.0))} KB"

/** `Math.round`: a half goes up, towards positive infinity. */
private fun jsRound(value: Double): Long = floor(value + 0.5).toLong()

/** `String.prototype.slice` with both ends inside the string. */
private fun String.jsSlice(start: Int, end: Int): String = substring(minOf(start, length), minOf(end, length))

private val small = ppmText(11f, lineHeight = 16.5f)
private val heading = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f)

/** `text-[11px]` (its lines 16.5 apart, the page's `leading-normal`) in [color]. */
@Composable
private fun Small(text: String, color: Rgba, modifier: Modifier = Modifier) =
    BasicText(text, modifier.fillMaxWidth().cssLines(16.5f), style = small.copy(color = color.color))

/** `h3 text-xs font-semibold text-foreground`. */
@Composable
private fun Heading(text: String) = BasicText(text, Modifier.cssLines(16f), style = heading.copy(color = LocalPpmTokens.current.text.color))

/**
 * Per-turn token history (`TurnUsageHistory`): a session that suddenly got expensive is read by
 * comparing its turns, the cheap ones reading their prefix from cache.
 */
@Composable
private fun TurnUsageHistory(source: SessionDebugSource, viewport: DpSize) {
    val tokens = LocalPpmTokens.current
    var turns by remember(source) { mutableStateOf<List<TurnRecord>?>(null) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    LaunchedEffect(source) {
        try {
            turns = source.turns()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            error = failure.message ?: failure.toString()
        }
    }
    val loaded = turns
    when {
        error != null -> Small(error ?: "", tokens.error)
        loaded == null -> Small("Loading token history...", tokens.text3)
        loaded.isEmpty() -> CssColumn(gap = 8.dp) {
            Heading("Token & cache")
            Small("No turns recorded yet. Send a message and the token split for each turn shows up here.", tokens.text3)
        }
        else -> CssColumn(gap = 8.dp) {
            Heading("Token & cache")
            Small(turnSummary(loaded), tokens.text2)
            TurnTable(loaded, viewport.height * 0.3f)
            val mono = SpanStyle(fontFamily = PpmFonts.mono)
            CssMixedText(
                buildAnnotatedString {
                    append("A high \"Cached\" share is the cheap case. A turn marked ")
                    withStyle(mono) { append("idle_timeout") }
                    append(
                        " paid for the whole transcript again because the session sat idle with no tab open long " +
                            "enough for PPM to release its subprocess. The cache is scoped per account, so a turn on a " +
                            "different account starts cold no matter how the session was resumed.",
                    )
                },
                small.copy(color = tokens.text3.color),
                16.5f,
                Modifier.fillMaxWidth(),
            )
        }
    }
}

/** "Last N turns · C restarted the session · W of transcript re-sent uncached · $T[ · spread over K accounts]". */
internal fun turnSummary(turns: List<TurnRecord>): String {
    val cold = turns.count { it.usage.coldStart }
    val cost = turns.sumOf { it.usage.costUsd }
    val wasted = turns.sumOf { it.usage.uncachedPrefixTokens }
    // More than one account explains cold prefixes on its own: the cache does not follow a session across accounts.
    val accounts = turns.mapNotNull { it.usage.accountId?.takeIf(String::isNotEmpty) }.toSet().size
    return "Last ${turns.size} turn${if (turns.size == 1) "" else "s"} · $cold restarted the session · " +
        "${fmtTokens(wasted)} of transcript re-sent uncached · \$${fixed(cost, 2)}" +
        if (accounts > 1) " · spread over $accounts accounts" else ""
}

private class TurnCell(val text: String, val color: Rgba, val end: Boolean, val title: String? = null)

/** A turn's row: When, Account, Prefix, Cached, Cost, Start. */
private fun turnCells(turn: TurnRecord, tokens: ThemeTokens): List<TurnCell> {
    val usage = turn.usage
    val level = TurnCost.assess(usage).level
    return listOf(
        TurnCell(turn.recordedAt.jsSlice(11, 16).ifEmpty { turn.recordedAt.jsSlice(0, 10) }, tokens.text3, end = false),
        TurnCell(usage.accountLabel ?: usage.accountId?.take(8) ?: "—", tokens.text3, end = false, title = usage.accountLabel ?: usage.accountId ?: ""),
        TurnCell(fmtTokens(usage.prefixTokens), tokens.text2, end = true),
        TurnCell(
            "${jsRound(usage.cacheHitRate * 100)}%",
            when (level) {
                TurnCostVerdict.Level.BAD -> tokens.warning
                TurnCostVerdict.Level.WARN -> tokens.text
                TurnCostVerdict.Level.OK -> tokens.success
            },
            end = true,
        ),
        TurnCell("\$${fixed(usage.costUsd, 3)}", tokens.text2, end = true),
        TurnCell(if (usage.coldStart) usage.coldReason ?: "cold" else "warm", tokens.text3, end = false),
    )
}

private val HEADERS = listOf("When" to false, "Account" to false, "Prefix" to true, "Cached" to true, "Cost" to true, "Start" to false)

/** The Account column's `max-w-24`, which Chrome holds a cell to: 96 px with its padding. */
private const val ACCOUNT = 1
private val ACCOUNT_MAX = 96.dp

/**
 * The turns' table (`max-h-[30vh] overflow-y-auto rounded-md border`, a `w-full font-mono
 * text-[10.5px]` table with a sticky `bg-surface text-text-subtle` head and `border-t` rows of
 * `px-2 py-1` cells). Its columns are laid out as an auto table lays them out: each as wide as its
 * widest cell (the Account one no wider than its `max-w-24`), the room left over shared in
 * proportion to those widths. A table wider than its box scrolls sideways with its head, as the
 * box's overflow does. The rows' borders collapse into one line between rows, which is what the
 * head and each row are stacked with here.
 */
@Composable
private fun TurnTable(turns: List<TurnRecord>, maxHeight: Dp) {
    val tokens = LocalPpmTokens.current
    val style = ppmText(10.5f, kind = FontKind.MONO, lineHeight = 15.75f)
    val rows = remember(turns, tokens) { turns.map { turnCells(it, tokens) } }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val pad = with(density) { 16.dp.toPx() }
    val account = with(density) { ACCOUNT_MAX.toPx() }
    // Each line's own advance, the head's first: an intrinsic width is rounded up to a whole pixel, where Chrome keeps the fraction.
    val advances = remember(rows, measurer, density) {
        (listOf(HEADERS.map { it.first }) + rows.map { row -> row.map { it.text } }).map { texts ->
            FloatArray(texts.size) { measurer.measure(texts[it], style, softWrap = false, maxLines = 1).getLineRight(0) }
        }
    }
    val content = FloatArray(HEADERS.size) { column ->
        val widest = advances.maxOf { it[column] } + pad
        if (column == ACCOUNT) minOf(widest, account) else widest
    }
    // Only an account longer than its `max-w-24` is cut short.
    fun truncated(row: Int, column: Int) = column == ACCOUNT && advances[row + 1][column] + pad > account
    // The line between two rows: one border, collapsed.
    val line = with(density) { cssBorderPx(1.dp).toDp() }
    val shape = RoundedCornerShape(8.dp)
    // The box is as tall as the column in it, which the column around counts exactly.
    val exact = rememberCssExact()
    BoxWithConstraints(Modifier.fillMaxWidth().cssExact(exact)) {
        // The box's border takes its pixels on either side of the table.
        val room = constraints.maxWidth - 2 * with(density) { cssBorderPx(1.dp) }
        val sum = content.sum()
        val widths = if (sum < room) FloatArray(content.size) { content[it] + (room - sum) * content[it] / sum } else content
        val down = rememberScrollState()
        val across = rememberScrollState()
        CssColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                // The box is the web's scroller both ways, its sticky head inside it: the bar down
                // runs the box's whole height, head and all.
                .chromeScrollbar(down, vertical = true, tokens.border.color, across = across, border = 1.dp)
                .chromeScrollbar(across, vertical = false, tokens.border.color, across = down, border = 1.dp)
                .clip(shape)
                .cssBorder(tokens.border.color, shape)
                .then(if (sum > room) Modifier.horizontalScroll(across) else Modifier),
            exact = exact,
        ) {
            TableRow(widths, Modifier.cssHeight(23.75.dp).background(tokens.panel.color)) {
                HEADERS.forEach { (label, end) -> TableCell(label, tokens.text3, end, style) }
            }
            CssColumn(Modifier.cssFlex().verticalScroll(down)) {
                rows.forEachIndexed { index, row ->
                    TableRow(widths, Modifier.cssHeight(23.75.dp + line).cssBorder(tokens.border.color, sides = CssSides.Top)) {
                        row.forEachIndexed { column, cell ->
                            TableCell(cell.text, cell.color, cell.end, style, truncate = truncated(index, column), title = cell.title)
                        }
                    }
                }
            }
        }
    }
}

/** A row of cells [widths] px wide, each put on the pixels its exact edges round to. */
@Composable
private fun TableRow(widths: FloatArray, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        var at = 0f
        val lefts = IntArray(measurables.size)
        val placeables = measurables.mapIndexed { index, measurable ->
            val left = at.roundToInt()
            at += widths.getOrElse(index) { 0f }
            lefts[index] = left
            measurable.measure(Constraints.fixed(at.roundToInt() - left, constraints.maxHeight))
        }
        layout(at.roundToInt(), constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable -> placeable.place(lefts[index], 0) }
        }
    }
}

/**
 * `px-2 py-1`, one line. A [truncate]d one (`truncate`) is cut short with an ellipsis; any other is
 * set whole on the edge it is aligned to, since a column as wide as its widest line to the fraction
 * is a cell as wide as the whole pixels its edges round to, which may be a fraction short of it.
 */
@Composable
private fun TableCell(text: String, color: Rgba, end: Boolean, style: TextStyle, truncate: Boolean = false, title: String? = null) {
    Box(
        Modifier
            .fillMaxHeight()
            .then(if (!title.isNullOrEmpty()) Modifier.webTitle(title) else Modifier)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            text,
            Modifier.fillMaxWidth().then(
                if (truncate) Modifier else Modifier.wrapContentWidth(if (end) Alignment.End else Alignment.Start, unbounded = true),
            ),
            style = style.copy(color = color.color, textAlign = if (end) TextAlign.End else TextAlign.Start),
            maxLines = 1,
            softWrap = false,
            overflow = if (truncate) TextOverflow.Ellipsis else TextOverflow.Visible,
        )
    }
}

private enum class StripMode { OVERSIZED, ALL }

/**
 * The image payloads a transcript replays on every turn (`TranscriptImages`), and the buttons that
 * strip them: oversized ones, which the API rejects once a request holds several, or all.
 * Nothing runs on its own: trimming a session's record is the user's call.
 */
@Composable
private fun TranscriptImages(source: SessionDebugSource, viewport: DpSize) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    var audit by remember(source) { mutableStateOf<ImageAudit?>(null) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var busy by remember(source) { mutableStateOf<StripMode?>(null) }
    var freed by remember(source) { mutableStateOf<ImageStripResult?>(null) }
    LaunchedEffect(source) {
        try {
            audit = source.images()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            error = failure.message ?: failure.toString()
        }
    }
    fun strip(mode: StripMode, includeAttachments: Boolean = false) {
        busy = mode
        error = null
        scope.launch {
            try {
                val result = source.strip(mode == StripMode.ALL, includeAttachments)
                audit = result.remaining
                freed = result
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = failure.message ?: failure.toString()
            } finally {
                busy = null
            }
        }
    }
    val shown = audit
    if (error != null && shown == null) return Small(error ?: "", tokens.error)
    if (shown == null) return Small("Scanning transcript for images...", tokens.text3)
    CssColumn(gap = 8.dp) {
        Heading("Transcript images")
        if (shown.total == 0) {
            Small("No images in this transcript${if (freed != null) " — it is clean." else "."}", tokens.text3)
        } else {
            val attached = if (shown.attachments > 0) " · ${shown.attachments} attached by you" else ""
            Small("${shown.total} image${plural(shown.total)} · ${fmtSize(shown.bytes)} of base64, replayed on every turn$attached.", tokens.text2)
        }
        if (shown.oversized > 0) {
            Notice(
                tokens.warning,
                "${shown.oversized} image${if (shown.oversized == 1) " reaches" else "s reach"} the ${shown.limit}px limit " +
                    "(largest ${shown.largestSide}px). Once a request carries several images the API rejects those " +
                    "outright, which fails the whole turn — removing them loses no context.",
            )
        }
        if (shown.oversizedAttachments > 0) {
            val n = shown.oversizedAttachments
            val one = n == 1
            Notice(
                tokens.error,
                "$n of those ${if (one) "is an image you" else "are images you"} attached yourself, so the buttons below leave " +
                    "${if (one) "it" else "them"} in place. While ${if (one) "it stays" else "they stay"}, every turn in this " +
                    "session keeps failing. Removing ${if (one) "it" else "them"} cannot be undone: an attachment PPM sent keeps " +
                    "a copy on disk with its path still in the message, but one from another client does not.",
            ) {
                ReviewButton(
                    "Remove oversized, attachments included",
                    WebVariant.DESTRUCTIVE,
                    WebSize.SM,
                    Modifier.fillMaxWidth().cssHeight(32.dp),
                    icon = if (busy == StripMode.OVERSIZED) ProductIcon.Loader2 else ProductIcon.ImageOff,
                    iconSize = 14.dp,
                    spinning = busy == StripMode.OVERSIZED,
                    enabled = busy == null,
                ) { strip(StripMode.OVERSIZED, includeAttachments = true) }
            }
        }
        freed?.let { Small("Removed ${it.removed} image${plural(it.removed)}, freed ${fmtSize(it.bytesFreed)}.", tokens.success) }
        error?.let { Small(it, tokens.error) }
        StripButtons(shown, busy, row = viewport.width >= 640.dp) { strip(it) }
        Small(
            "These two buttons cover tool images only, and leave anything you attached alone. A removed tool image is " +
                "replaced by its placeholder text; the chat still shows the file, re-read from disk.",
            tokens.text3,
        )
    }
}

private fun plural(n: Int) = if (n == 1) "" else "s"

/**
 * `flex flex-col gap-1.5 sm:flex-row`, two `flex-1` buttons. Side by side they share the row and
 * are `h-8`; stacked, `flex: 1 1 0%` in a column of no set height sizes each to its content, so
 * the web draws them as tall as their text (and the outline one its borders taller), not 32 px.
 */
@Composable
private fun StripButtons(audit: ImageAudit, busy: StripMode?, row: Boolean, onStrip: (StripMode) -> Unit) {
    val density = LocalDensity.current
    val border = with(density) { cssBorderPx(1.dp).toDp() }
    val oversized: @Composable (Modifier) -> Unit = { modifier ->
        ReviewButton(
            "Remove oversized" + if (audit.oversized > 0) " (${audit.oversized} · ${fmtSize(audit.oversizedBytes)})" else "",
            WebVariant.OUTLINE,
            WebSize.SM,
            modifier,
            height = if (row) 32.dp else 20.dp + border * 2,
            icon = if (busy == StripMode.OVERSIZED) ProductIcon.Loader2 else ProductIcon.ImageOff,
            iconSize = 14.dp,
            spinning = busy == StripMode.OVERSIZED,
            enabled = busy == null && audit.oversized > 0,
        ) { onStrip(StripMode.OVERSIZED) }
    }
    val all: @Composable (Modifier) -> Unit = { modifier ->
        ReviewButton(
            "Remove all" + if (audit.total > 0) " (${audit.total} · ${fmtSize(audit.bytes)})" else "",
            WebVariant.DESTRUCTIVE,
            WebSize.SM,
            modifier,
            height = if (row) 32.dp else 20.dp,
            icon = if (busy == StripMode.ALL) ProductIcon.Loader2 else ProductIcon.ImageOff,
            iconSize = 14.dp,
            spinning = busy == StripMode.ALL,
            enabled = busy == null && audit.total > 0,
        ) { onStrip(StripMode.ALL) }
    }
    if (row) {
        FlexPair(6.dp, Modifier.cssHeight(32.dp)) {
            oversized(Modifier)
            all(Modifier)
        }
    } else {
        CssColumn(Modifier.fillMaxWidth(), gap = 6.dp) {
            oversized(Modifier.fillMaxWidth().cssHeight(20.dp + border * 2))
            all(Modifier.fillMaxWidth().cssHeight(20.dp))
        }
    }
}

/**
 * `rounded-md border border-{tone}/40 bg-{tone}/10 p-2 text-[11px] text-text-secondary`: a
 * `mt-px size-3.5 shrink-0` triangle in [tone] beside the text (`flex gap-2`), and under them
 * whatever [below] holds (`flex flex-col gap-2`).
 */
@Composable
private fun Notice(tone: Rgba, text: String, below: (@Composable () -> Unit)? = null) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val lines = rememberCssExact()
    CssColumn(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tone.opacity(0.1).color)
            .cssBorder(tone.opacity(0.4).color, shape)
            .padding(horizontal = 8.dp),
        gap = 8.dp,
        top = 8.dp,
        bottom = 8.dp,
    ) {
        Row(Modifier.cssExact(lines), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PpmIcon(ProductIcon.TriangleAlert, 14.dp, tone.color, Modifier.padding(top = 1.dp))
            CssColumn(Modifier.weight(1f), exact = lines) { Small(text, tokens.text2) }
        }
        below?.invoke()
    }
}

/**
 * Two `flex-1` items side by side, sharing the width but never narrower than their content (a
 * flex item's `min-width: auto`): one whose content needs more than its share keeps that, and the
 * other takes what is left.
 */
@Composable
private fun FlexPair(gap: Dp, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.fillMaxWidth()) { measurables, constraints ->
        val space = gap.roundToPx()
        val width = constraints.maxWidth
        val mins = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
        val sizes = IntArray(measurables.size)
        val frozen = BooleanArray(measurables.size)
        var left = width - space * (measurables.size - 1)
        var open = measurables.size
        while (open > 0) {
            val share = left / open
            val over = mins.indices.filter { !frozen[it] && mins[it] > share }
            if (over.isEmpty()) {
                val last = mins.indices.last { !frozen[it] }
                var rest = left
                for (i in mins.indices) if (!frozen[i]) {
                    sizes[i] = if (i == last) rest else share
                    rest -= share
                }
                break
            }
            for (i in over) {
                frozen[i] = true
                sizes[i] = mins[i]
                left -= mins[i]
                open--
            }
        }
        val height = constraints.maxHeight
        val placeables = measurables.mapIndexed { i, it -> it.measure(Constraints.fixed(sizes[i].coerceAtLeast(0), height)) }
        layout(width, height) {
            var x = 0
            for (placeable in placeables) {
                placeable.place(x, 0)
                x += placeable.width + space
            }
        }
    }
}
