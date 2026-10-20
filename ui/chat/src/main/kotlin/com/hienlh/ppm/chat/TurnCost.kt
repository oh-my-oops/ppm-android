package com.hienlh.ppm.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.ColorsFade
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.TurnUsage

/** How much of a turn's prefix was paid for twice (`assessTurnCost` in shared/turn-usage.ts). */
internal data class TurnCostVerdict(val level: Level, val wastedTokens: Long, val reason: String) {
    enum class Level { OK, WARN, BAD }
}

internal object TurnCost {
    /** A smaller prefix is cheap however it is billed. */
    private const val PREFIX_WARN_TOKENS = 20_000
    private const val HIT_RATE_BAD = 0.5
    private const val HIT_RATE_WARN = 0.9

    /** Billing weights against a fresh input token, only to compare a turn with itself. */
    private const val CACHE_READ_WEIGHT = 0.1
    private const val CACHE_WRITE_WEIGHT = 1.25

    private val COLD_REASON_TEXT = mapOf(
        "cache_expired" to "the session's prompt cache had already lapsed, so PPM released its subprocess",
        "idle_timeout" to "the session sat idle with no tab open long enough for PPM to release its subprocess",
        "warm_idle_cap" to "too many idle sessions were holding a subprocess, so this one was released early",
        "tab_closed" to "PPM shut the session's subprocess down when the last tab disconnected",
        "set_model" to "the model was changed, which restarts the session",
        "stream_ended" to "the session's subprocess had already exited",
        "resume" to "the session was resumed on a new subprocess",
    )

    /** Only a turn that re-sent an existing transcript, a big enough one, is worth a word. */
    fun assess(usage: TurnUsage): TurnCostVerdict {
        val wasted = usage.inputTokens + usage.cacheWriteTokens
        if (!usage.coldStart || usage.prefixTokens < PREFIX_WARN_TOKENS) return TurnCostVerdict(TurnCostVerdict.Level.OK, wasted, "")
        val level = when {
            usage.cacheHitRate < HIT_RATE_BAD -> TurnCostVerdict.Level.BAD
            usage.cacheHitRate < HIT_RATE_WARN -> TurnCostVerdict.Level.WARN
            else -> return TurnCostVerdict(TurnCostVerdict.Level.OK, wasted, "")
        }
        val cause = usage.coldReason?.takeIf { it.isNotEmpty() }?.let { COLD_REASON_TEXT[it] ?: "the session restarted ($it)" }
        val reason = if (cause != null) {
            "${fmtTokens(wasted)} of this session's transcript was re-sent uncached because $cause."
        } else {
            "${fmtTokens(wasted)} of this session's transcript was re-sent uncached."
        }
        return TurnCostVerdict(level, wasted, reason)
    }

    /** How many times the prefix cost what it would have fully cached (`prefixCostMultiplier`). */
    fun multiplier(usage: TurnUsage): Double {
        val prefix = usage.prefixTokens
        if (prefix == 0L) return 1.0
        val actual = usage.inputTokens + usage.cacheWriteTokens * CACHE_WRITE_WEIGHT + usage.cacheReadTokens * CACHE_READ_WEIGHT
        val floor = prefix * CACHE_READ_WEIGHT
        return if (floor > 0) maxOf(1.0, actual / floor) else 1.0
    }
}

/**
 * The notice on a turn that cost more than it had to (`TurnCostWarning`): a warning-tinted box
 * when under half the prefix came from the cache, a plain one under nine tenths, nothing
 * otherwise. Its line opens the split, the reason and what restarts a session.
 */
@Composable
internal fun TurnCostWarning(usage: TurnUsage, modifier: Modifier = Modifier) {
    val verdict = remember(usage) { TurnCost.assess(usage) }
    if (verdict.level == TurnCostVerdict.Level.OK) return
    val tokens = LocalPpmTokens.current
    var open by remember { mutableStateOf(false) }
    val bad = verdict.level == TurnCostVerdict.Level.BAD
    val hitPct = Math.round(usage.cacheHitRate * 100)
    val shape = RoundedCornerShape(8.dp)
    val body = ppmText(11f, lineHeight = 16.5f)
    Column(
        modifier
            .fillMaxWidth()
            .background((if (bad) tokens.warning.opacity(0.1) else tokens.panel).color, shape)
            .cssBorder((if (bad) tokens.warning.opacity(0.4) else tokens.border).color, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        val hover = rememberHover()
        val color = tokens.text.mix(tokens.text2, hover.lit.toDouble()).color
        val turned by animateFloatAsState(if (open) 180f else 0f, ColorsFade, label = "chevron")
        Row(
            Modifier
                .fillMaxWidth()
                .ppmClickable(pressedColor = Color.Transparent, source = hover.source) { open = !open }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            PpmIcon(ProductIcon.TriangleAlert, 14.dp, (if (bad) tokens.warning else tokens.text3).color, Modifier.padding(top = 1.dp))
            val line = remember(usage) {
                buildAnnotatedString {
                    append("Costly turn — $hitPct% of a ${fmtTokens(usage.prefixTokens)}-token transcript came from cache, so it cost about ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(fixed(TurnCost.multiplier(usage), 1) + "x") }
                    append(" a fully cached turn.")
                }
            }
            BasicText(line, Modifier.weight(1f), style = body.copy(color = color))
            PpmIcon(ProductIcon.ChevronDown, 14.dp, color, Modifier.padding(top = 1.dp).rotate(turned))
        }
        if (open) {
            Column(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .cssBorder(tokens.border.color, sides = CssSides.Top)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (verdict.reason.isNotEmpty()) BasicText(verdict.reason, style = body.copy(color = tokens.text2.color))
                val rows = listOf(
                    "From cache" to "${fmtTokens(usage.cacheReadTokens)} ($hitPct%)",
                    "Written to cache" to fmtTokens(usage.cacheWriteTokens),
                    "Fresh input" to fmtTokens(usage.inputTokens),
                    "Output" to fmtTokens(usage.outputTokens),
                    "Model" to usage.model.ifEmpty { "unknown" },
                    "Cost" to "$" + fixed(usage.costUsd, 4),
                )
                // `grid-cols-2 gap-x-3 gap-y-1`, each cell `truncate`.
                val cell = ppmText(10.5f, kind = FontKind.MONO, lineHeight = 15.75f)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((label, value) in rows) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            BasicText(label, Modifier.weight(1f), cell.copy(color = tokens.text3.color), overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1)
                            BasicText(
                                value,
                                Modifier.weight(1f),
                                cell.copy(color = tokens.text2.color, textAlign = TextAlign.End),
                                overflow = TextOverflow.Ellipsis,
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                    }
                }
                BasicText(
                    "A turn stays cached while the session keeps running. Closing the last tab of an idle session, switching model, " +
                        "or a retry all restart it, and the next turn re-sends the transcript at full price.",
                    style = body.copy(color = tokens.text3.color),
                )
            }
        }
    }
}
