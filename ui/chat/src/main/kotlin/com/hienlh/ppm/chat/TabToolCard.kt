package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssSides
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
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.state.workspace.OPEN_PREVIEW_TOOL

/**
 * The card for the AI's tab tools (`TabToolCard`, tab-tool-card.tsx): what was opened, what the
 * page check found, and an Open button that brings the tab back — from the chat's history, after
 * the tab was closed, or on a device that was not the one the AI opened it on. [output]: the
 * result's raw output, once there is one.
 */
@Composable
internal fun TabToolCard(call: TabToolCall, toolUseId: String?, output: String?, isError: Boolean, done: Boolean, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    // Closed until opened, and kept so while the transcript keeps the card's state, as the other cards are.
    val openCards = LocalOpenCards.current
    val id = toolUseId?.takeIf { openCards != null }
    var ownOpen by remember { mutableStateOf(false) }
    val expanded = if (id != null) openCards?.get(id) ?: false else ownOpen
    val setExpanded: (Boolean) -> Unit = { open -> if (id != null) openCards?.put(id, open) else ownOpen = open }
    val text = remember(output) { output?.let(::tabToolResultText) ?: "" }
    val problems = if (call.tool == OPEN_PREVIEW_TOOL && !isError) remember(text) { previewProblemCount(text) } else null
    val name = basename(call.path)
    val tabs = LocalChatTabs.current
    val shape = RoundedCornerShape(11.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, shape),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    // `max-md:min-h-11`.
                    .heightIn(min = if (mobile) 44.dp else 0.dp)
                    .ppmClickable(pressedColor = tokens.panel2.opacity(0.4).color, hoverColor = tokens.panel2.opacity(0.4).color) { setExpanded(!expanded) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ToolChip(ToolChipStyle(if (call.tool == OPEN_PREVIEW_TOOL) ProductIcon.Eye else ProductIcon.FileCode, tokens.info.opacity(0.15), tokens.info))
                val summary = buildAnnotatedString {
                    append(if (call.tool == OPEN_PREVIEW_TOOL) "Preview" else "Opened")
                    withStyle(SpanStyle(color = tokens.text3.color)) { append(" · $name${call.line?.let { ":$it" } ?: ""}") }
                }
                TruncatedText(summary, ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color), Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (problems != null) {
                        BasicText(
                            if (problems == 0L) "no problems" else "$problems ${if (problems == 1L) "problem" else "problems"}",
                            style = ppmText(10f, lineHeight = 40f / 3).copy(color = (if (problems > 0) tokens.warning else tokens.text3).color),
                        )
                    }
                    StatusIcon(if (isError) Status.FAILED else if (done) Status.DONE else Status.RUNNING)
                    PpmIcon(if (expanded) ProductIcon.ChevronDown else ProductIcon.ChevronRight, 12.dp, tokens.text3.color)
                }
            }
            // `shrink-0 mr-1 px-2.5 h-7 rounded-md max-md:h-11 max-md:min-w-11`, lit `hover:bg-accent-wash`.
            val buttonShape = RoundedCornerShape(6.dp)
            Box(
                Modifier
                    .padding(end = 4.dp)
                    .height(if (mobile) 44.dp else 28.dp)
                    .widthIn(min = if (mobile) 44.dp else 0.dp)
                    .webTitle("Open $name in a tab")
                    .ppmClickable(
                        pressedColor = tokens.accentWash.color,
                        hoverColor = tokens.accentWash.color,
                        shape = buttonShape,
                        onClickLabel = "Open $name in a tab",
                    ) { tabs?.openToolFile(call.tool, call.path, call.line) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("Open", style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.accent.color))
            }
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
                // `select-text`: the I-beam over the glyphs of the path and the text.
                TextCursorRegion {
                    Mono(call.path, tokens.text3, breakAll = true)
                    if (text.isNotEmpty()) {
                        Box(
                            Modifier
                                .padding(top = 6.dp)
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .cssBorder(tokens.border.color, sides = CssSides.Top)
                                .cssScrollY()
                                .padding(top = 6.dp),
                        ) {
                            Mono(text, tokens.text3)
                        }
                    }
                }
            }
        }
    }
}
