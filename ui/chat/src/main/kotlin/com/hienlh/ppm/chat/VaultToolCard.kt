package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.TextCursorRegion
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.VaultKinds
import com.hienlh.ppm.protocol.VaultTiers

/**
 * The transcript card for the AI's vault tools (`vault-tool-card.tsx`). A secure or sealed read is
 * drawn as a count ("Read 2 vault items") and its output is never put on screen — live or rebuilt
 * from history — and a proposal shows its name, kind and suggested tier, never the value the AI
 * may have sent.
 */
@Composable
internal fun VaultToolCard(call: VaultToolCall, toolUseId: String?, output: String?, isError: Boolean, done: Boolean, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val openCards = LocalOpenCards.current
    val id = toolUseId?.takeIf { openCards != null }
    var ownOpen by remember { mutableStateOf(false) }
    val expanded = if (id != null) openCards?.get(id) ?: false else ownOpen
    val setExpanded: (Boolean) -> Unit = { open -> if (id != null) openCards?.put(id, open) else ownOpen = open }
    // An error never carries a value (the server words it), so only a successful read is checked.
    val hidden = remember(output, isError) { if (output != null && !isError) vaultLockedReadCount(output) else null }
    val shownOutput = remember(output, hidden) { if (output != null && hidden == null) tabToolResultText(output) else null }
    val shape = RoundedCornerShape(11.dp)
    Column(
        modifier
            .fillMaxWidth()
            .testTag("vault-tool-card")
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (mobile) 44.dp else 0.dp)
                .ppmClickable(pressedColor = tokens.panel2.opacity(0.4).color, hoverColor = tokens.panel2.opacity(0.4).color) { setExpanded(!expanded) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ToolChip(ToolChipStyle(ProductIcon.Lock, tokens.warning.opacity(0.15), tokens.warning))
            TruncatedText(
                buildAnnotatedString { append(vaultToolSummary(call, hidden)) },
                ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                Modifier.weight(1f),
            )
            StatusIcon(if (isError) Status.FAILED else if (done) Status.DONE else Status.RUNNING)
            PpmIcon(if (expanded) ProductIcon.ChevronDown else ProductIcon.ChevronRight, 12.dp, tokens.text3.color)
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val line = ppmText(12f, lineHeight = 16f)
                TextCursorRegion {
                    if (call is VaultToolCall.Read && call.purpose.isNotEmpty()) {
                        BasicText(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = tokens.text3.color)) { append("Purpose: ") }
                                append(call.purpose)
                            },
                            style = line.copy(color = tokens.text2.color),
                        )
                    }
                    if (call is VaultToolCall.Propose) {
                        BasicText(
                            buildAnnotatedString {
                                append(call.name.ifEmpty { "Unnamed" })
                                call.kind?.let { append(" · ${VaultKinds.label(it)}") }
                                call.tier?.let { append(" · suggested tier: ${VaultTiers.label(it)}") }
                                if (call.hasValue) withStyle(SpanStyle(color = tokens.text3.color)) { append(" · with a value (not shown)") }
                            },
                            style = line.copy(color = tokens.text2.color),
                        )
                    }
                    if (hidden != null) BasicText("The values went to the AI and are not shown here.", style = line.copy(color = tokens.text3.color))
                    if (!shownOutput.isNullOrEmpty()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .background(tokens.bgSolid.color, RoundedCornerShape(4.dp))
                                .cssBorder(tokens.border.color, RoundedCornerShape(4.dp))
                                .cssScrollY()
                                .padding(8.dp),
                        ) { Mono(shownOutput, tokens.text2) }
                    }
                }
            }
        }
    }
}
