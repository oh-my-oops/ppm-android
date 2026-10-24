package com.hienlh.ppm.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import java.text.BreakIterator
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderInset
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.AccountInfo
import com.hienlh.ppm.protocol.AccountUsageEntry
import com.hienlh.ppm.protocol.LimitBucket
import com.hienlh.ppm.state.UsageTone
import com.hienlh.ppm.state.formatExpiry
import com.hienlh.ppm.state.formatLastUpdated
import com.hienlh.ppm.state.formatResetTime
import com.hienlh.ppm.state.isExpired
import com.hienlh.ppm.state.isoMillis
import com.hienlh.ppm.state.tokenStatus
import com.hienlh.ppm.state.usagePercent

/** Where a card sits (`layout`): a card in the sideways strip, or a cell of the fullscreen grid. */
enum class AccountCardLayout { Strip, Grid }

/** A figure's colour: `pctColor` / `barColor`, or `text-text-subtle` where there is none. */
fun ThemeTokens.tone(tone: UsageTone): Rgba = when (tone) {
    UsageTone.Success -> success
    UsageTone.Warning -> warning
    UsageTone.Error -> error
    UsageTone.Subtle -> text3
}

/** `duration-500` on Tailwind's easing: how a card's flash comes and goes. */
private val FlashFade = tween<Color>(500, easing = FastOutSlowInEasing)

/**
 * One account as the chat's usage panel shows it (account-card.tsx, dense): its name and badges,
 * the Use button and the on/off switch beside them, its windows, and a footer of the facts worth
 * acting on — when the figures were read and when the sign-in ends. An expired account, or one
 * whose sign-in was refused, is dimmed; the expired one also loses its switch.
 *
 * The badges' explanations are the web's tooltips, which a tap never opens there: they wait for
 * the mouse hover pass. "Expired" and "Sign in again" lead to Settings until it can sign an
 * account in again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountCard(
    entry: AccountUsageEntry,
    isActive: Boolean,
    accountInfo: AccountInfo?,
    layout: AccountCardLayout,
    modifier: Modifier = Modifier,
    flash: Boolean = false,
    onToggle: ((id: String, status: String) -> Unit)? = null,
    toggling: Boolean = false,
    onSelect: ((id: String) -> Unit)? = null,
    unselectableReason: String? = null,
    selecting: Boolean = false,
    planLabel: String? = null,
    reauthHint: String? = null,
    onReauth: ((id: String) -> Unit)? = null,
    footerLead: @Composable RowScope.() -> Unit = {},
    below: (@Composable () -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val now = System.currentTimeMillis()
    val usage = entry.usage
    val status = accountInfo?.status ?: entry.accountStatus
    val isExpired = accountInfo?.isExpired(now) == true
    val ts = tokenStatus(accountInfo, now)
    val needsReauth = accountInfo?.reauthRequired == true || reauthHint != null
    val grantExpiresAtMs = accountInfo?.grantExpiresAt?.takeIf { it != 0L }?.let { it * 1000 }
    val grantEndingSoon = grantExpiresAtMs != null && grantExpiresAtMs - now < 5 * 86_400_000L
    val showTokenStatus = accountInfo != null &&
        (ts.label == "temp" || ts.label == "unknown" || (ts.label == "expired" && !isExpired && !needsReauth))
    val refusedReason = when {
        isExpired -> "Token expired — sign in again"
        needsReauth -> "Sign in again to use this account"
        else -> unselectableReason
    }
    val shape = RoundedCornerShape(8.dp)
    // Tailwind writes `/10` and `/40` after `/5` and `/30`, so a flash outranks being active.
    val fill by animateColorAsState(
        when {
            flash -> tokens.accent.opacity(0.1).color
            isActive -> tokens.accent.opacity(0.05).color
            else -> Color.Transparent
        },
        FlashFade,
        label = "card fill",
    )
    val edge by animateColorAsState(
        when {
            flash -> tokens.accent.opacity(0.4).color
            isActive -> tokens.accent.opacity(0.3).color
            else -> tokens.border.opacity(0.5).color
        },
        FlashFade,
        label = "card edge",
    )
    val sized = when (layout) {
        // `shrink-0 snap-start min-w-[300px]`: as wide as its widest row, never narrower.
        AccountCardLayout.Strip -> Modifier.widthIn(min = 300.dp).width(IntrinsicSize.Max)
        AccountCardLayout.Grid -> Modifier
    }
    CardColumn(
        layout == AccountCardLayout.Grid,
        modifier
            .then(sized)
            .alpha(if (isExpired || needsReauth) 0.5f else 1f)
            .clip(shape)
            .background(fill)
            .cssBorder(edge, shape)
            // `overflow-hidden` cuts at the padding box, inside the border and its curve.
            .clip(RoundedCornerShape(8.dp - with(LocalDensity.current) { cssBorderPx(1.dp).toDp() }))
            .padding(10.dp),
    ) {
        val gap = if (layout == AccountCardLayout.Grid) Modifier.padding(bottom = 6.dp) else Modifier
        CardHeaderRow(gap.fillMaxWidth().height(20.dp)) {
            // `flex-1`: in the strip the card is as wide as the whole name, so it is never cut there.
            CardName(entry.accountLabel ?: entry.accountId.take(8), ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color))
            val badge = ppmText(10f, FontWeight.Medium, lineHeight = 15f)
            // `AccountHint`s with nothing to do: `cursor-help`, for the tooltip behind them.
            val hint = Modifier.pointerHoverIcon(WebCursor.Help)
            if (isActive) BasicText("Active", hint, style = badge.copy(color = tokens.accent.color))
            if (onSelect != null && !isActive) UseButton(selecting, refused = refusedReason != null) { onSelect(entry.accountId) }
            if (isExpired) {
                ReauthChip("Expired", onReauth?.let { { it(entry.accountId) } })
            } else if (needsReauth) {
                ReauthChip("Sign in again", onReauth?.let { { it(entry.accountId) } })
            }
            if (!entry.isOAuth && !isExpired) BasicText("API key", hint, style = ppmText(10f, lineHeight = 15f).copy(color = tokens.text3.color))
            planLabel?.let {
                // `uppercase tracking-wide`: 0.025em.
                BasicText(it.uppercase(), hint, style = ppmText(10f, lineHeight = 15f).copy(color = tokens.text3.color, letterSpacing = 0.25.sp))
            }
            // `flex items-center gap-0.5 shrink-0`, there even when empty, so its gap still counts.
            Box {
                if (!isExpired && onToggle != null) {
                    PpmSwitch(
                        checked = status != "disabled",
                        enabled = !toggling && status != "cooldown",
                        onChange = { onToggle(entry.accountId, status) },
                    )
                }
            }
        }
        if (usage.hasBuckets) {
            Column(gap, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccountBucketRow("5-Hour Session", usage.session, now)
                AccountBucketRow("Weekly", usage.weekly, now)
                AccountBucketRow("Weekly (Opus)", usage.weeklyOpus, now)
                AccountBucketRow("Weekly (Sonnet)", usage.weeklySonnet, now)
                for (scoped in usage.weeklyScoped) AccountBucketRow("Weekly (${scoped.label})", scoped, now)
            }
        } else {
            BasicText(
                if (entry.isOAuth) "No usage data yet" else "Usage tracking not available for API keys",
                gap,
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
            )
        }
        below?.let { Box(gap) { it() } }
        // Always there, even empty: `space-y` still puts its gap above it. `gap-2` both ways, so
        // the facts that wrap in a narrow grid cell sit 8 apart.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            footerLead()
            val fact = ppmText(10f, lineHeight = 15f)
            usage.lastFetchedAt?.let(::isoMillis)?.let { read ->
                FooterFact(ProductIcon.RefreshCw, formatLastUpdated(read, now).orEmpty(), tokens.text3, fact)
            }
            if (grantExpiresAtMs != null && !needsReauth) {
                FooterFact(ProductIcon.KeyRound, "sign-in ${formatExpiry(grantExpiresAtMs, now)}", if (grantEndingSoon) tokens.warning else tokens.text3, fact)
            }
            if (showTokenStatus) BasicText("© ${ts.label}", Modifier.pointerHoverIcon(WebCursor.Help), style = fact.copy(color = tokens.tone(ts.tone).color))
        }
    }
}

/** A footer fact: a 12 px icon and its words, `inline-flex items-center gap-1`. */
@Composable
private fun FooterFact(icon: ProductIcon, text: String, color: Rgba, style: TextStyle) {
    Row(Modifier.pointerHoverIcon(WebCursor.Help), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PpmIcon(icon, 12.dp, color.color)
        BasicText(text, style = style.copy(color = color.color), maxLines = 1, softWrap = false)
    }
}

/**
 * "Use" (`px-1.5 py-0.5 rounded text-[10px] font-medium`): in the secondary colour, or the subtle
 * one when the account cannot take the chat, where pressing it says why. "Switching…" while the
 * switch is out.
 */
@Composable
private fun UseButton(selecting: Boolean, refused: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    val hover = rememberHover(enabled = !selecting)
    val rest = if (refused) tokens.text3 else tokens.text2
    val lit = if (refused) tokens.error else tokens.text
    val hoverFill = if (refused) tokens.error.opacity(0.1) else tokens.panel2
    Box(
        Modifier
            .clip(shape)
            .pointerHoverIcon(if (selecting) WebCursor.Wait else WebCursor.Pointer)
            .ppmClickable(
                enabled = !selecting,
                pressedColor = hoverFill.color,
                hoverColor = hoverFill.color,
                shape = shape,
                source = hover.source,
            ) { onClick() }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        BasicText(
            if (selecting) "Switching…" else "Use",
            style = ppmText(10f, FontWeight.Medium, lineHeight = 15f).copy(color = lit.mix(rest, hover.lit.toDouble()).color),
        )
    }
}

/**
 * "Expired" / "Sign in again" (`AccountHint` with an action): red, dotted underline 2 px below,
 * `px-1 -mx-1` so its hover fill reaches past the word without moving it.
 */
@Composable
private fun ReauthChip(text: String, onClick: (() -> Unit)?) {
    val tokens = LocalPpmTokens.current
    val style = ppmText(10f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.error.color)
    if (onClick == null) {
        BasicText(text, Modifier.pointerHoverIcon(WebCursor.Help), style = style)
        return
    }
    val shape = RoundedCornerShape(4.dp)
    val hover = rememberHover()
    Box(
        Modifier
            .offsetOutward(4.dp)
            .clip(shape)
            .pointerHoverIcon(WebCursor.Pointer)
            .ppmClickable(pressedColor = tokens.error.opacity(0.1).color, hoverColor = tokens.error.opacity(0.1).color, shape = shape, source = hover.source, onClick = onClick)
            .padding(horizontal = 4.dp),
    ) {
        DottedUnderlineText(text, style, tokens.error.color)
    }
}

/**
 * A usage bar (`AccountUsageBar`): the window's name with "↻ resets in" on the right, then the
 * 8 px track filled to the figure and the figure itself, 40 px wide.
 */
@Composable
fun AccountUsageBar(label: String, pct: Int?, reset: String?) {
    val tokens = LocalPpmTokens.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ShrinkBetweenRow {
            BasicText(label, style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color))
            reset?.let { BasicText("↻ $it", style = ppmText(10f, lineHeight = 15f).copy(color = tokens.text3.color)) }
        }
        Row(Modifier.fillMaxWidth().height(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val color = pct?.let { tokens.tone(UsageTone.of(it)) } ?: tokens.text3
            BarTrack(pct, color, Modifier.weight(1f))
            BasicText(
                pct?.let { "$it%" } ?: "—",
                Modifier.width(40.dp),
                style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = color.color, textAlign = TextAlign.End, fontFeatureSettings = "tnum"),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * The card's rows: `space-y-1.5` in the strip, and in the grid `flex flex-col justify-evenly` in
 * a cell that owns the height, each row at its own height and the room left shared around them.
 * Rows that no longer fit are centred, as Chrome falls `space-evenly` back to `center`, so the
 * card's `overflow-hidden` cuts both ends where a Column would squeeze the last rows to nothing.
 */
@Composable
private fun CardColumn(grid: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    if (!grid) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
        return
    }
    Layout(content, modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)) }
        val sizes = IntArray(placeables.size) { placeables[it].height }
        val positions = IntArray(sizes.size)
        val height = constraints.maxHeight
        with(if (sizes.sum() > height) Arrangement.Center else Arrangement.SpaceEvenly) { arrange(height, sizes, positions) }
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { i, placeable -> placeable.placeRelative(0, positions[i]) }
        }
    }
}

/**
 * `flex items-center gap-2` with the name `flex-1 min-w-0` and everything after it `shrink-0`:
 * the name takes whatever is left, down to nothing, and the rest keep their widths even past the
 * card's edge, where its `overflow-hidden` cuts them. A Row would squeeze the last one instead.
 */
@Composable
private fun CardHeaderRow(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier, CardHeader)
}

private object CardHeader : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val gap = 8.dp.roundToPx()
        val height = constraints.maxHeight
        val rest = measurables.drop(1).map { it.measure(Constraints(maxHeight = height)) }
        val nameWidth = (constraints.maxWidth - rest.sumOf { it.width + gap }).coerceAtLeast(0)
        val name = measurables.first().measure(Constraints(maxWidth = nameWidth, maxHeight = height))
        return layout(constraints.maxWidth, height) {
            name.placeRelative(0, (height - name.height) / 2)
            var x = nameWidth + gap
            for (placeable in rest) {
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width + gap
            }
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        measurables.drop(1).sumOf { it.minIntrinsicWidth(height) + 8.dp.roundToPx() }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        measurables.first().maxIntrinsicWidth(height) + measurables.drop(1).sumOf { it.maxIntrinsicWidth(height) + 8.dp.roundToPx() }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        measurables.maxOf { it.minIntrinsicHeight(Constraints.Infinity) }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        measurables.maxOf { it.maxIntrinsicHeight(Constraints.Infinity) }
}

/**
 * The account's name, `truncate`d as Chrome truncates: an ellipsis where it stops fitting, but
 * never in place of the first letter, which css-overflow-3 clips rather than ellipses — so a cell
 * too narrow for "W…" still shows "W…", cut at its edge, where Android alone would show "…".
 */
@Composable
private fun CardName(name: String, style: TextStyle) {
    val measurer = rememberTextMeasurer()
    val least = remember(name, style, measurer) {
        val first = BreakIterator.getCharacterInstance().run {
            setText(name)
            next().coerceAtLeast(0)
        }
        // Apart, as Android and Chrome both add the ellipsis: not kerned against the letter.
        measurer.measure(name.take(first), style, softWrap = false, maxLines = 1).size.width +
            measurer.measure("…", style, softWrap = false, maxLines = 1).size.width
    }
    BasicText(
        name,
        Modifier.clipToBounds().layout { measurable, constraints ->
            val room = constraints.maxWidth
            val placeable = measurable.measure(if (room < least) constraints.copy(minWidth = least, maxWidth = least) else constraints)
            layout(minOf(placeable.width, room), placeable.height) { placeable.placeRelative(0, 0) }
        },
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
    )
}

/**
 * `flex items-center justify-between` around a window's name and its reset time, both free to
 * shrink: CSS takes what does not fit out of each in proportion to its natural width, never below
 * its longest word, and each wraps in what it keeps — "5-Hour / Session" beside "↻ 2h / 10m" in a
 * narrow grid cell. A Row would give the second its whole width first.
 */
@Composable
private fun ShrinkBetweenRow(content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxWidth(), ShrinkBetween)
}

private object ShrinkBetween : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val sizes = widths(measurables, constraints.maxWidth)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else sizes.sum()
        val placeables = measurables.mapIndexed { i, measurable -> measurable.measure(Constraints.fixedWidth(sizes[i])) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        return layout(width, height) {
            var x = 0
            placeables.forEachIndexed { i, placeable ->
                // The last at the far end, unless the ones before it already reach past that.
                if (i > 0 && i == placeables.lastIndex) x = maxOf(x, width - placeable.width)
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width
            }
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        measurables.sumOf { it.minIntrinsicWidth(height) }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        measurables.sumOf { it.maxIntrinsicWidth(height) }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        maxIntrinsicHeight(measurables, width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        val sizes = widths(measurables, width)
        return measurables.indices.maxOfOrNull { measurables[it].maxIntrinsicHeight(sizes[it]) } ?: 0
    }

    /**
     * Flex shrinking with `flex-shrink: 1` and `min-width: auto`: the overflow taken from each
     * item in proportion to its natural width, any item pushed below its longest word frozen
     * there and the rest shared out again. Edges rounded rather than widths, so they add up.
     */
    private fun widths(measurables: List<IntrinsicMeasurable>, room: Int): List<Int> {
        val basis = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        if (basis.sum() <= room) return basis
        val floor = measurables.map { it.minIntrinsicWidth(Constraints.Infinity) }
        val sizes = basis.map { it.toDouble() }.toMutableList()
        val frozen = BooleanArray(basis.size)
        do {
            val free = room - basis.indices.sumOf { if (frozen[it]) sizes[it] else basis[it].toDouble() }
            val share = basis.indices.filterNot { frozen[it] }.sumOf { basis[it].toDouble() }
            var violated = false
            for (i in basis.indices) {
                if (frozen[i]) continue
                sizes[i] = basis[i] + free * basis[i] / share
                if (sizes[i] < floor[i]) {
                    sizes[i] = floor[i].toDouble()
                    frozen[i] = true
                    violated = true
                }
            }
        } while (violated)
        var edge = 0.0
        var previous = 0
        return sizes.map { size ->
            edge += size
            val at = edge.roundToInt()
            (at - previous).also { previous = at }
        }
    }
}

/** `flex-1 h-2 rounded-full bg-border overflow-hidden`, filled to [pct] (at most all of it). */
@Composable
private fun BarTrack(pct: Int?, color: Rgba, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    Box(modifier.height(8.dp).clip(CircleShape).background(tokens.border.color)) {
        if (pct != null) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(pct.coerceIn(0, 100) / 100f).clip(CircleShape).background(color.color))
        }
    }
}

/** A Claude window as a usage bar (`AccountBucketRow`); nothing when the window is absent. */
@Composable
fun AccountBucketRow(label: String, bucket: LimitBucket?, now: Long) {
    bucket ?: return
    AccountUsageBar(label, usagePercent(bucket.utilization), formatResetTime(bucket, now))
}

/** Tailwind's `shadow-sm` under a switch's track, and the thumb's `shadow-lg`. */
private val TrackShadow = listOf(
    ThemeShadow(0.0, 1.0, 3.0, 0.0, Rgba(0.0, 0.0, 0.0, 0.1)),
    ThemeShadow(0.0, 1.0, 2.0, -1.0, Rgba(0.0, 0.0, 0.0, 0.1)),
)
private val ThumbShadow = listOf(
    ThemeShadow(0.0, 10.0, 15.0, -3.0, Rgba(0.0, 0.0, 0.0, 0.1)),
    ThemeShadow(0.0, 4.0, 6.0, -4.0, Rgba(0.0, 0.0, 0.0, 0.1)),
)

/**
 * The web's Switch (Radix): a 36×20 track in the accent when on and the input colour when off,
 * inside a 2 px transparent border, and a 16 px thumb in the page colour that slides 16 px.
 * Half-faded while it cannot be used.
 */
@Composable
fun PpmSwitch(checked: Boolean, enabled: Boolean = true, onChange: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val track by animateColorAsState(
        if (checked) tokens.accent.color else tokens.border.color,
        tween(150, easing = FastOutSlowInEasing),
        label = "switch track",
    )
    val slide by animateDpAsState(if (checked) 16.dp else 0.dp, tween(150, easing = FastOutSlowInEasing), label = "switch thumb")
    Box(
        TrackShadow.fold(Modifier.size(36.dp, 20.dp).alpha(if (enabled) 1f else 0.5f) as Modifier) { m, s -> m.ppmShadow(s, CircleShape) }
            .clip(CircleShape)
            .background(track)
            .pointerHoverIcon(if (enabled) WebCursor.Pointer else WebCursor.NotAllowed)
            .ppmClickable(enabled = enabled, pressedColor = Color.Transparent, role = Role.Switch, onClick = onChange)
            .cssBorderInset(2.dp, 2.dp, 2.dp, 2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            ThumbShadow.fold(Modifier.offset(x = slide).size(16.dp) as Modifier) { m, s -> m.ppmShadow(s, CircleShape) }
                .clip(CircleShape)
                .background(tokens.bgSolid.color),
        )
    }
}

/**
 * `underline decoration-dotted underline-offset-2`: square dots as thick as a tenth of the text,
 * as long, with gaps as long, starting 2 px under the baseline.
 */
@Composable
private fun DottedUnderlineText(text: String, style: TextStyle, color: Color) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    BasicText(
        text,
        Modifier.drawBehind {
            val line = layout ?: return@drawBehind
            val thickness = (style.fontSize.value / 10f).sp.toPx()
            val dot = thickness.roundToInt().coerceAtLeast(1).toFloat()
            val top = (line.getLineBaseline(0) + 2.dp.toPx()).roundToInt().toFloat()
            var x = 0f
            val right = line.size.width.toFloat()
            while (x < right) {
                drawRect(color, Offset(x, top), Size(minOf(dot, right - x), thickness))
                x += 2 * dot
            }
        },
        style = style,
        onTextLayout = { layout = it },
        maxLines = 1,
        softWrap = false,
    )
}

/** Lays a box out [by] wider on each side than it draws its neighbours' room: `-mx-1` around `px-1`. */
private fun Modifier.offsetOutward(by: Dp): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val extra = by.roundToPx()
        val placeable = measurable.measure(constraints)
        layout((placeable.width - 2 * extra).coerceAtLeast(0), placeable.height) { placeable.place(-extra, 0) }
    },
)
