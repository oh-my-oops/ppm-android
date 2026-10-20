package com.hienlh.ppm.chat

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.Compaction
import com.hienlh.ppm.protocol.TaskItem
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Claude's tasks pinned over the transcript (`TaskTracker`): folded until tapped, and gone while
 * there are none or every one is finished. The web blurs what is behind it (`backdrop-blur-sm`);
 * the messages never scroll under it, so that blurs nothing and is left out.
 */
@Composable
internal fun TaskTracker(tasks: List<TaskItem>) {
    if (tasks.isEmpty() || tasks.all { it.finished }) return
    val tokens = LocalPpmTokens.current
    var collapsed by rememberSaveable { mutableStateOf(true) }
    val completed = tasks.count { it.status == TaskItem.Status.COMPLETED }
    val style = ppmText(12f, lineHeight = 16f)
    Column(
        Modifier
            .fillMaxWidth()
            .background(tokens.panel.opacity(0.6).color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .ppmClickable(pressedColor = tokens.panel.color, hoverColor = tokens.panel.color) { collapsed = !collapsed }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(if (collapsed) ProductIcon.ChevronRight else ProductIcon.ChevronDown, 14.dp, tokens.text.color)
            PpmIcon(ProductIcon.ListTodo, 14.dp, tokens.text2.color)
            BasicText("Tasks", style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color))
            BasicText("$completed/${tasks.size} done", style = style.copy(color = tokens.text3.color))
            // `ml-auto`.
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(width = 64.dp, height = 4.dp).clip(CircleShape).background(tokens.border.color)) {
                // `Math.round` of the percentage, as the web sizes it.
                val pct = Math.round(completed.toDouble() / tasks.size * 100) / 100f
                Box(Modifier.fillMaxHeight().fillMaxWidth(pct).background(tokens.success.opacity(0.7).color))
            }
        }
        if (!collapsed) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 192.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (task in tasks) TaskRow(task)
            }
        }
    }
}

/**
 * Where Chrome on Android finds the task glyphs, Geist having none: it walks the system's named
 * families in order and stops at the first that has one — Source Sans Pro for ✓ and ▶, Noto Serif
 * (`serif`) for ○ and ■ (`CSS.getPlatformFontsForNode`). Android's own fallback picks larger ones.
 * Made on first use: the JVM tests load this file without Android's fonts.
 */
private val SourceSansPro by lazy(LazyThreadSafetyMode.NONE) { FontFamily(Typeface.create("source-sans-pro", Typeface.NORMAL)) }

/** One task: its glyph and its subject, struck through once done, the one under way lit. */
@Composable
private fun TaskRow(task: TaskItem) {
    val tokens = LocalPpmTokens.current
    val active = task.status == TaskItem.Status.IN_PROGRESS
    val style = ppmText(12f, lineHeight = 16f)
    val (glyph, glyphColor, glyphFont) = when (task.status) {
        TaskItem.Status.COMPLETED -> Triple("✓", tokens.success, SourceSansPro)
        TaskItem.Status.IN_PROGRESS -> Triple("▶", tokens.warning, SourceSansPro)
        TaskItem.Status.STOPPED -> Triple("■", tokens.text3, FontFamily.Serif)
        TaskItem.Status.PENDING -> Triple("○", tokens.text3, FontFamily.Serif)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (active) tokens.warning.opacity(0.1).color else Color.Transparent, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(glyph, Modifier.padding(top = 2.dp), style.copy(color = glyphColor.color, fontFamily = glyphFont))
        val text = when {
            task.status == TaskItem.Status.COMPLETED -> style.copy(color = tokens.text3.color, textDecoration = TextDecoration.LineThrough)
            active -> ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color)
            else -> style.copy(color = tokens.text2.color)
        }
        if (task.subject.isEmpty()) {
            BasicText("#${task.id} (no title)", Modifier.weight(1f, fill = false), text.copy(color = tokens.text3.color, fontStyle = FontStyle.Italic))
        } else {
            BasicText(task.subject, Modifier.weight(1f, fill = false), text)
        }
    }
}

/**
 * What stands in for the conversation being fetched above the messages (`PreCompactSkeleton`): a
 * spinner line and three pulsing bubbles, in the flow, so scrolling up lands on something the
 * size of what is coming.
 */
@Composable
internal fun PreCompactSkeleton() {
    val tokens = LocalPpmTokens.current
    val alpha = pulse()
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            PpmIcon(ProductIcon.Loader2, 12.dp, tokens.text2.color, spinning = true)
            BasicText("Loading previous conversation…", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
        }
        for (i in 0..2) {
            val mine = i % 2 == 0
            Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.TopEnd else Alignment.TopStart) {
                Column(
                    Modifier
                        .fillMaxWidth(if (mine) 0.5f else 0.75f)
                        .alpha(alpha)
                        .background(tokens.panel.color, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SkeletonLine(1f)
                    SkeletonLine(5f / 6)
                    if (!mine) SkeletonLine(2f / 3)
                }
            }
        }
    }
}

@Composable
private fun SkeletonLine(width: Float) {
    Box(Modifier.fillMaxWidth(width).height(12.dp).background(LocalPpmTokens.current.border.color, RoundedCornerShape(4.dp)))
}

/**
 * Loading what is above failed: why, and Retry (`compactLoadError`), over the top of the chat.
 * The web's hover swaps the surface for the translucent `bg-surface-hover`, at once, and a long
 * reason wraps centred, as a `<button>` centres its text.
 */
@Composable
internal fun EarlierErrorPill(message: String, retry: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(instant = true)
    val pressed by hover.source.collectIsPressedAsState()
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier
            .heightIn(min = if (LocalPpmMobile.current) 44.dp else 0.dp)
            .background(if (pressed || hover.lit > 0f) tokens.surfaceHover.color else tokens.panel.color, shape)
            .ppmClickable(pressedColor = Color.Transparent, source = hover.source, onClick = retry)
            .cssBorder(tokens.border.color, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color)
        PpmIcon(ProductIcon.AlertCircle, 14.dp, tokens.error.color)
        BasicText("Could not load previous conversation: $message", Modifier.weight(1f, fill = false), style.copy(textAlign = TextAlign.Center))
        BasicText("Retry", style = style.copy(textDecoration = TextDecoration.Underline))
    }
}

/**
 * The rule above a compact summary, saying what that compaction saved (`CompactionDivider`); its
 * `title` says how it ran, what it took the context from and to, and how long it took.
 */
@Composable
internal fun CompactionDivider(compaction: Compaction) {
    val tokens = LocalPpmTokens.current
    // `text-[11px]` under preflight's `line-height: 1.5`.
    val style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text3.color, fontFeatureSettings = "tnum")
    Row(
        Modifier.fillMaxWidth().webTitle(remember(compaction) { compactionDetail(compaction) }).padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(tokens.border.color))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PpmIcon(ProductIcon.Layers, 14.dp, tokens.text3.color)
            BasicText("Compacted session · saved ${fmtTokens(compaction.savedTokens)} tokens", style = style)
        }
        Box(Modifier.weight(1f).height(1.dp).background(tokens.border.color))
    }
}

/** The divider's `title`. */
internal fun compactionDetail(compaction: Compaction): String = listOfNotNull(
    if (compaction.manual) "Ran with /compact" else "Ran automatically on a full context window",
    "${fmtTokens(compaction.preTokens)} → ${fmtTokens(compaction.postTokens)} tokens",
    compaction.durationMs?.let { "took ${compactionDuration(it)}" },
).joinToString(" · ")

/** Coarse on purpose, as the web's: a compaction is never sub-second. */
private fun compactionDuration(ms: Long): String {
    val seconds = Math.round(ms / 1000.0)
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}

/** `fmtTokens` (turn-usage.ts): 950, 1.2k, 12k, 1.2M, rounded as `toFixed` rounds and with a point whatever the locale. */
internal fun fmtTokens(n: Long): String = when {
    n >= 1_000_000 -> fixed(n / 1_000_000.0, 1) + "M"
    n >= 1_000 -> fixed(n / 1_000.0, if (n >= 10_000) 0 else 1) + "k"
    else -> n.toString()
}

/** `toFixed`: the double's exact value, a half rounded up. */
private fun fixed(value: Double, digits: Int): String = BigDecimal(value).setScale(digits, RoundingMode.HALF_UP).toPlainString()
