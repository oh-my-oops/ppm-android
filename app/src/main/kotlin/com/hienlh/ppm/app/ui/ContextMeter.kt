package com.hienlh.ppm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.ContextUsageView
import com.hienlh.ppm.protocol.formatContextTokens
import com.hienlh.ppm.state.ContextMeterTone
import com.hienlh.ppm.state.contextMeterTone
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/**
 * The chat footer's context meter (`context-meter.tsx`): "Ctx 42%" once a Claude turn has said how
 * full the window is, and a popover that asks the session's live CLI what fills it, with Compact now.
 *
 * The chip's figure is the last turn's (`contextWindowPct`) until the popover reads a fresher one.
 * A session with no live Claude process has no breakdown: the popover says so and still offers
 * Compact now, which starts one.
 */

/** Reads what fills a session's context, or null when no Claude process runs for it. */
typealias ContextUsageLoader = suspend (sessionId: String) -> ContextUsageView?

@Composable
private fun contextPctColor(pct: Int): Color {
    val tokens = LocalPpmTokens.current
    return when (contextMeterTone(pct)) {
        ContextMeterTone.ERROR -> tokens.error.color
        ContextMeterTone.WARNING -> tokens.warning.color
        ContextMeterTone.NORMAL -> tokens.text2.color
    }
}

/** The chip's face: a small ring (not on a phone or a narrow chat), "Ctx" and the figure. */
@Composable
fun ContextMeterFace(pct: Int, narrow: Boolean) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    Row(
        Modifier.testTag("context-meter-face"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!narrow && !mobile) UsageRing(pct = pct, size = 12.dp)
        BasicText(
            "Ctx",
            style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
            maxLines = 1,
            softWrap = false,
        )
        BasicText(
            "$pct%",
            Modifier.testTag("context-meter-pct:" + contextMeterTone(pct).name.lowercase()),
            style = ppmText(11f, FontWeight.Medium, lineHeight = 16f).copy(color = contextPctColor(pct), fontFeatureSettings = "tnum"),
            maxLines = 1,
            softWrap = false,
        )
    }
}

private sealed interface ContextLoad {
    data object Loading : ContextLoad
    data object None : ContextLoad
    data class Ready(val usage: ContextUsageView) : ContextLoad
    data class Failed(val message: String) : ContextLoad
}

/**
 * The popover's body (`ContextMeterPanel`): the figure and its bar, what fills the window largest
 * first, and Compact now ([onCompact] null while a turn runs or the chat is offline).
 */
@Composable
fun ContextMeterPanel(
    sessionId: String,
    open: Boolean,
    lastPct: Int?,
    load: ContextUsageLoader,
    onCompact: (() -> Unit)?,
    onFresh: (Int) -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var state by remember { mutableStateOf<ContextLoad>(ContextLoad.Loading) }
    val currentLoad by rememberUpdatedState(load)
    val currentFresh by rememberUpdatedState(onFresh)
    LaunchedEffect(sessionId, open) {
        if (!open) return@LaunchedEffect
        state = ContextLoad.Loading
        state = try {
            currentLoad(sessionId)?.let { ContextLoad.Ready(it) } ?: ContextLoad.None
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ContextLoad.Failed(e.message ?: e.toString())
        }
        (state as? ContextLoad.Ready)?.let { currentFresh(it.usage.percentage) }
    }
    val pct = (state as? ContextLoad.Ready)?.usage?.percentage ?: lastPct
    val body = ppmText(if (mobile) 13f else 12f, lineHeight = if (mobile) 19.5f else 18f)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .testTag("context-panel:" + state.javaClass.simpleName.lowercase()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                "Context",
                Modifier.weight(1f),
                style = ppmText(if (mobile) 14f else 13f, FontWeight.SemiBold, lineHeight = if (mobile) 20f else 18f).copy(color = tokens.text.color),
                maxLines = 1,
            )
            if (pct != null) {
                BasicText(
                    "$pct% used",
                    style = body.copy(color = contextPctColor(pct), fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        if (pct != null) UsageTrack(pct, Modifier.fillMaxWidth())
        when (val s = state) {
            ContextLoad.Loading -> BasicText("Reading what fills the context…", style = body.copy(color = tokens.text3.color))
            ContextLoad.None -> BasicText(
                "The breakdown appears while this chat's Claude process is running." +
                    if (lastPct != null) " The figure is from the last turn." else "",
                style = body.copy(color = tokens.text3.color),
            )
            is ContextLoad.Failed -> BasicText("Could not read the context: " + s.message, style = body.copy(color = tokens.error.color))
            is ContextLoad.Ready -> {
                val usage = s.usage
                BasicText(
                    formatContextTokens(usage.totalTokens) + " of " + formatContextTokens(usage.maxTokens) + " tokens · " + usage.model,
                    style = body.copy(color = tokens.text3.color, fontFeatureSettings = "tnum"),
                )
                Column(
                    Modifier.semantics { contentDescription = "What fills the context" },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (row in usage.rows) {
                        Row(
                            Modifier.fillMaxWidth().testTag("context-row:" + row.id),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.weight(1f)) {
                                TruncatedText(AnnotatedString(row.name), body.copy(color = if (row.isUsed) tokens.text.color else tokens.text3.color))
                            }
                            BasicText(
                                formatContextTokens(row.tokens),
                                style = body.copy(color = tokens.text2.color, fontFeatureSettings = "tnum"),
                                maxLines = 1,
                                softWrap = false,
                            )
                            BasicText(
                                if (usage.maxTokens > 0) "${(row.tokens * 100.0 / usage.maxTokens).roundToInt()}%" else "",
                                Modifier.width(40.dp),
                                style = body.copy(color = tokens.text3.color, fontFeatureSettings = "tnum", textAlign = TextAlign.End),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            CompactNowButton(onCompact)
        }
    }
}

/** `h-7 rounded-md border px-2.5 text-[12px]`, half strength while a turn runs. */
@Composable
private fun CompactNowButton(onCompact: (() -> Unit)?) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .height(if (mobile) 26.dp else 28.dp)
            .alpha(if (onCompact != null) 1f else 0.5f)
            .clip(shape)
            .cssBorder(tokens.border.color, shape)
            .webTitle(if (onCompact != null) "Summarise the conversation so far to free up context" else "Wait for the current turn to finish")
            .ppmClickable(
                enabled = onCompact != null,
                pressedColor = tokens.surfaceHover.color,
                hoverColor = tokens.surfaceHover.color,
                shape = shape,
                onClick = { onCompact?.invoke() },
            )
            .padding(horizontal = 10.dp)
            .testTag("context-compact"),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            "Compact now",
            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * The meter as a chip of its own, for the phone's context row, which has no footer cluster: the
 * same face, and the same panel in a sheet.
 */
@Composable
fun ContextMeterChip(
    sessionId: String?,
    providerId: String?,
    contextPct: Int?,
    load: ContextUsageLoader,
    onCompact: (() -> Unit)?,
    narrow: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    var fresh by remember(contextPct, sessionId) { mutableStateOf<Int?>(null) }
    val isClaude = providerId.isNullOrEmpty() || providerId == "claude"
    val pct = fresh ?: contextPct
    if (!isClaude || sessionId.isNullOrEmpty() || pct == null) return
    FooterChip(
        narrow = narrow,
        expanded = open,
        title = "Context $pct% used: see what fills it, or compact",
        contentDescription = "Context $pct% used",
        testTag = "context-meter",
        onClick = { open = !open },
    ) {
        ContextMeterFace(pct, narrow)
    }
    FooterPopover(open = open, onClose = { open = false }, label = "Context", align = FooterPopoverAlign.Right) {
        ContextMeterPanel(
            sessionId = sessionId,
            open = open,
            lastPct = contextPct,
            load = load,
            onCompact = onCompact?.let { compact -> { open = false; compact() } },
            onFresh = { fresh = it },
        )
    }
}
