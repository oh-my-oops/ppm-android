package com.hienlh.ppm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ClaudeLimitResetGrant
import com.hienlh.ppm.protocol.ClaudeLimitResets
import com.hienlh.ppm.protocol.UsageInfo
import com.hienlh.ppm.state.CODEX_RESET_DETAIL
import com.hienlh.ppm.state.CODEX_RESET_NOT_AT_LIMIT
import com.hienlh.ppm.state.CodexCredit
import com.hienlh.ppm.state.ResetLine
import com.hienlh.ppm.state.claudeGrantDescription
import com.hienlh.ppm.state.claudeResetBlockedReason
import com.hienlh.ppm.state.claudeResetCardLines
import com.hienlh.ppm.state.claudeResetConfirmLeft
import com.hienlh.ppm.state.claudeResetEarlyWarning
import com.hienlh.ppm.state.claudeResetGrantsLeft
import com.hienlh.ppm.state.claudeResetKindName
import com.hienlh.ppm.state.claudeResetsLeft
import com.hienlh.ppm.state.codexCreditList
import com.hienlh.ppm.state.codexResetCardLines
import com.hienlh.ppm.state.resetLineExpiry
import com.hienlh.ppm.state.resetRowExpiry
import com.hienlh.ppm.state.resetWhen
import com.hienlh.ppm.state.usageLimitReached
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/*
 * Free limit resets on an account card (`account-resets.tsx`, `claude-limit-resets-button.tsx`,
 * `codex-reset-credit-button.tsx`): the card lists them with their expiries and spends nothing; its
 * "Use" opens a sheet where one is picked, confirmed and spent. Bottom sheet on a phone, centred
 * dialog elsewhere, like every other confirmation in the accounts pane.
 */

/**
 * The card's "Free resets" section (`ResetsSection`): how many, and one line per reset (or kind)
 * with its expiry and the days left, amber within a week. Always drawn, "None right now"
 * included, so cards side by side line up. "Use" (accent while one can be spent now) opens the sheet.
 */
@Composable
internal fun ResetsSection(count: Int, lines: List<ResetLine>, usable: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val dash = tokens.border.opacity(0.6).color
    Column(
        modifier
            .fillMaxWidth()
            // `border-t border-dashed border-border/60 pt-2`
            .drawBehind {
                val w = 1.dp.toPx()
                drawLine(dash, Offset(0f, w / 2), Offset(size.width, w / 2), w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * w, 3 * w)))
            }
            .padding(top = 8.dp)
            .testTag("resets-section"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText(
                "FREE RESETS · $count",
                style = ppmText(10f, FontWeight.SemiBold, lineHeight = 15f).copy(color = tokens.text3.color, letterSpacing = 0.25.sp),
            )
            if (count > 0) {
                val shape = RoundedCornerShape(4.dp)
                Row(
                    Modifier
                        .offset(x = 4.dp)
                        .then(if (mobile) Modifier.heightIn(min = 44.dp) else Modifier)
                        .clip(shape)
                        .ppmClickable(shape = shape, onClick = onOpen)
                        .padding(horizontal = 4.dp)
                        .testTag("resets-use"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val color = if (usable) tokens.accent.color else tokens.text2.color
                    BasicText("Use", style = ppmText(11f, FontWeight.Medium, lineHeight = 16f).copy(color = color))
                    PpmIcon(ProductIcon.ChevronRight, 12.dp, color)
                }
            }
        }
        if (lines.isEmpty()) {
            BasicText("None right now", style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color))
        } else {
            for (line in lines) ResetLineRow(line)
        }
    }
}

/** `title ×N` with the count in the subtle colour. */
private fun countedTitle(title: String, count: Int, subtle: androidx.compose.ui.graphics.Color, normalWeight: Boolean = false): AnnotatedString =
    buildAnnotatedString {
        append(title)
        if (count > 1) withStyle(SpanStyle(color = subtle, fontWeight = if (normalWeight) FontWeight.Normal else null)) { append(" ×$count") }
    }

@Composable
private fun ResetLineRow(line: ResetLine) {
    val tokens = LocalPpmTokens.current
    val expiry = resetLineExpiry(line.expiresAt)
    val dots = tokens.border.color
    val style = ppmText(11f, lineHeight = 16f)
    Row(Modifier.fillMaxWidth().testTag("reset-line"), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(countedTitle(line.title, line.count, tokens.text3.color), style = style.copy(color = tokens.text2.color), maxLines = 1)
        // `min-w-3 flex-1 border-b border-dotted border-border`: the leader between the name and the date.
        Box(
            Modifier
                .weight(1f)
                .widthIn(min = 12.dp)
                .heightIn(min = 12.dp)
                .drawBehind {
                    val w = 1.dp.toPx()
                    val y = size.height - w / 2 - 3.dp.toPx()
                    drawLine(dots, Offset(0f, y), Offset(size.width, y), w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w, w)))
                },
        )
        BasicText(
            expiry.text,
            style = style.copy(color = (if (expiry.soon) tokens.warning else tokens.text3).color, fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
    }
}

/**
 * One reset in the sheet (`ResetRow`): what it is, when it expires, its button or why it has none.
 * [blockedReason] disables the button and says why under it; [blockedSilently] disables it for a
 * reason the sheet already states once for every row.
 */
@Composable
internal fun ResetRow(
    title: AnnotatedString,
    detail: String?,
    expiresAt: String?,
    action: String,
    blockedReason: String?,
    onUse: () -> Unit,
    modifier: Modifier = Modifier,
    blockedSilently: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    val blocked = blockedReason != null || blockedSilently
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .cssBorder(tokens.border.opacity(0.6).color, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                BasicText(title, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color))
                BasicText(resetRowExpiry(expiresAt), style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color))
            }
            SettingsActionButton(action, outline = blocked, enabled = !blocked, onClick = onUse)
        }
        if (detail != null) BasicText(detail, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
        if (blockedReason != null) {
            BasicText("Can't use it now: $blockedReason.", style = ppmText(11f, lineHeight = 16f).copy(color = tokens.warning.color))
        }
    }
}

/** The sheet itself (`ResetsSheet`): a dialog on a tablet, a bottom sheet on a phone. */
@Composable
internal fun ResetsSheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    if (LocalPpmMobile.current) {
        BottomSheet(onDismiss = onClose) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                BasicText(title, Modifier.padding(bottom = 12.dp), style = ppmText(16f, FontWeight.SemiBold, lineHeight = 24f).copy(color = LocalPpmTokens.current.text.color))
                content()
            }
        }
    } else {
        WebDialog(title = title, onDismiss = onClose, widest = 448.dp, titleStyle = ppmText(14f, FontWeight.SemiBold, lineHeight = 14f)) {
            content()
        }
    }
}

/** A confirmation's points (`list-disc pl-4 space-y-1.5 text-xs text-text-secondary`); a warning point is amber. */
@Composable
private fun ConfirmPoints(points: List<Pair<String, Boolean>>) {
    val tokens = LocalPpmTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((text, warning) in points) {
            val color = (if (warning) tokens.warning else tokens.text2).color
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText("•", style = ppmText(12f, lineHeight = 16f).copy(color = color))
                BasicText(text, style = ppmText(12f, lineHeight = 16f).copy(color = color))
            }
        }
    }
}

/** "Keep it" and the spending button: one row ending right on a tablet, stacked with the spend on top on a phone. */
@Composable
private fun ConfirmButtons(spendLabel: String, busy: Boolean, onKeep: () -> Unit, onSpend: () -> Unit) {
    val keep = @Composable { m: Modifier -> SettingsActionButton("Keep it", m, outline = true, enabled = !busy, minHeight = 44.dp, onClick = onKeep) }
    val spend = @Composable { m: Modifier ->
        SettingsActionButton(spendLabel, m.testTag("reset-confirm-spend"), icon = ProductIcon.RotateCcw, busy = busy, enabled = !busy, minHeight = 44.dp, onClick = onSpend)
    }
    if (LocalPpmMobile.current) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            spend(Modifier.fillMaxWidth())
            keep(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            keep(Modifier)
            spend(Modifier)
        }
    }
}

/** "Use <b>label</b>'s …?" — the confirmation's question, the account's name in medium. */
@Composable
private fun ConfirmQuestion(before: String, label: String, after: String) {
    val tokens = LocalPpmTokens.current
    BasicText(
        buildAnnotatedString {
            append(before)
            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(label) }
            append(after)
        },
        style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text.color),
    )
}

/**
 * Claude's free limit resets on an account card (`ClaudeLimitResetsButton`). [spend] claims one
 * grant and answers the line to show; it may throw, whose message is shown instead. [onDone] gets
 * that line, and whether it is a failure.
 */
@Composable
internal fun ClaudeLimitResetsButton(
    accountLabel: String,
    resets: ClaudeLimitResets?,
    spend: suspend (ClaudeLimitResetGrant) -> String,
    onDone: (message: String, failed: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ClaudeLimitResetGrant?>(null) }
    var busy by remember { mutableStateOf(false) }

    val grants = claudeResetGrantsLeft(resets)
    if (resets == null || grants.isEmpty()) {
        ResetsSection(0, emptyList(), usable = false, onOpen = {})
        return
    }
    val close = { if (!busy) { open = false; confirm = null } }
    val total = claudeResetsLeft(resets)
    ResetsSection(total, claudeResetCardLines(grants), usable = grants.any { claudeResetBlockedReason(it, resets) == null }, onOpen = { open = true })
    if (!open) return
    val tokens = LocalPpmTokens.current
    val picked = confirm
    ResetsSheet(if (picked != null) "Use a ${claudeResetKindName(picked.kind)}?" else "Limit resets · $total available", close) {
        if (picked == null) {
            Column(Modifier.testTag("claude-limit-resets"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (g in grants) {
                    ResetRow(
                        title = countedTitle(claudeResetKindName(g.kind, capital = true), g.resetsLeft, tokens.text3.color, normalWeight = true),
                        detail = claudeGrantDescription(g),
                        expiresAt = g.endsAt,
                        action = "Reset for free",
                        blockedReason = claudeResetBlockedReason(g, resets),
                        onUse = { confirm = g },
                        modifier = Modifier.testTag("claude-reset-${g.id}"),
                    )
                }
            }
        } else {
            val kind = claudeResetKindName(picked.kind)
            Column(Modifier.testTag("claude-limit-reset-confirm"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConfirmQuestion("Use ", accountLabel, "'s $kind?")
                ConfirmPoints(
                    listOfNotNull(
                        claudeGrantDescription(picked) to false,
                        claudeResetEarlyWarning(picked, resets)?.let { it to true },
                        claudeResetConfirmLeft(picked) to false,
                    ),
                )
                ConfirmButtons(
                    "Yes, use my $kind",
                    busy,
                    onKeep = { confirm = null },
                    onSpend = {
                        busy = true
                        scope.launch {
                            val (message, failed) = spendForMessage { spend(picked) }
                            busy = false
                            open = false
                            confirm = null
                            onDone(message, failed)
                        }
                    },
                )
            }
        }
    }
}

/**
 * Codex's free rate-limit resets on an account card (`CodexResetCreditButton`). A reset is offered
 * only once a limit is reached; the server applies the same rule against a live read.
 */
@Composable
internal fun CodexResetCreditButton(
    accountLabel: String,
    usage: UsageInfo,
    spend: suspend (CodexCredit) -> String,
    onDone: (message: String, failed: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<CodexCredit?>(null) }
    var busy by remember { mutableStateOf(false) }

    val credits = usage.resetCredits
    if (credits == null || credits.available == 0) {
        ResetsSection(0, emptyList(), usable = false, onOpen = {})
        return
    }
    val list = codexCreditList(credits)
    val atLimit = usageLimitReached(usage)
    val close = { if (!busy) { open = false; confirm = null } }
    ResetsSection(credits.available, codexResetCardLines(list), usable = atLimit, onOpen = { open = true })
    if (!open) return
    val tokens = LocalPpmTokens.current
    val picked = confirm
    ResetsSheet(if (picked != null) "Use a free reset" else "Free resets · ${credits.available} available", close) {
        if (picked == null) {
            Column(Modifier.testTag("codex-reset-credits"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!atLimit) BasicText(CODEX_RESET_NOT_AT_LIMIT, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.warning.color))
                list.forEachIndexed { i, c ->
                    ResetRow(
                        title = AnnotatedString(c.title ?: "Full reset"),
                        detail = CODEX_RESET_DETAIL,
                        expiresAt = c.expiresAt,
                        action = "Use reset",
                        blockedReason = null,
                        blockedSilently = !atLimit,
                        onUse = { confirm = c },
                        modifier = Modifier.testTag("codex-reset-${c.id ?: i}"),
                    )
                }
            }
        } else {
            val n = credits.available
            val weeklyNow = usage.weekly?.resetsAt?.takeIf { it.isNotEmpty() }?.let { resetWhen(it) }
            Column(Modifier.testTag("codex-reset-credit-confirm"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConfirmQuestion("Use 1 of ", accountLabel, "'s $n free reset${if (n == 1) "" else "s"}?")
                ConfirmPoints(
                    listOfNotNull(
                        "${picked.title ?: "Full reset"}: the 5-hour and weekly usage go back to 0% now." to false,
                        ("The weekly reset date moves: the next one is about 7 days after you continue using Codex" +
                            "${weeklyNow?.let { ", not $it as now" }.orEmpty()}. You do not also get that one.") to false,
                        picked.expiresAt?.let { resetWhen(it) }?.let { "This reset expires $it." to false },
                    ),
                )
                ConfirmButtons(
                    "Use reset",
                    busy,
                    onKeep = { confirm = null },
                    onSpend = {
                        busy = true
                        scope.launch {
                            val (message, failed) = spendForMessage { spend(picked) }
                            busy = false
                            open = false
                            confirm = null
                            onDone(message, failed)
                        }
                    },
                )
            }
        }
    }
}

/** The line [block] answers, or the error's message ("Could not use the reset" when it has none) and that it failed. */
private suspend fun spendForMessage(block: suspend () -> String): Pair<String, Boolean> = try {
    block() to false
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    (e.message?.takeIf { it.isNotEmpty() } ?: "Could not use the reset") to true
}
