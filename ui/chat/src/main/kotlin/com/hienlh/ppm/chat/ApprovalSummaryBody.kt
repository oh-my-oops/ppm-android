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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ApprovalSummary
import kotlin.math.max

/**
 * The PPM Assistant's name from Settings → PPM Assistant, as an endpoint card's heading names it
 * (`useAssistantDisplayName`): "PPM Assistant" until one is set.
 */
val LocalAssistantName = staticCompositionLocalOf { "PPM Assistant" }

/** Said whenever what is about to run holds a character [revealedText] had to mark (`HIDDEN_CHARACTERS_WARNING`). */
const val HIDDEN_CHARACTERS_WARNING = "Contains invisible or text-direction characters, shown as ⟨U+…⟩. They are part of what runs."

/**
 * A character that draws nothing or reorders its neighbours (`HIDDEN` in
 * `shared/reveal-hidden-characters.ts`): every control but tab and line feed, every format
 * character, the default-ignorables and the line and paragraph separators.
 */
internal fun isHiddenCodePoint(cp: Int): Boolean {
    if (cp == '\t'.code || cp == '\n'.code) return false
    val type = Character.getType(cp)
    return type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() ||
        cp == 0x2028 || cp == 0x2029 || cp == 0x034F || cp == 0x061C || cp == 0x115F || cp == 0x1160 ||
        cp in 0x17B4..0x17B5 || cp in 0x180B..0x180F || cp == 0x3164 || cp in 0xFE00..0xFE0F || cp == 0xFFA0 ||
        cp in 0xE0000..0xE0FFF
}

/** Whether [text] holds any character [revealedText] would mark (`hasHiddenCharacters`). */
fun hasHiddenCharacters(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (isHiddenCodePoint(cp)) return true
        i += Character.charCount(cp)
    }
    return false
}

/**
 * [text] drawn so that what is displayed is what runs (`RevealedText`): a hidden character is
 * shown as a `⟨U+XXXX⟩` marker in [marker]'s style instead of taking effect. Nothing is removed.
 */
fun revealedText(text: String, marker: SpanStyle): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (isHiddenCodePoint(cp)) {
            withStyle(marker) { append("⟨U+" + Integer.toHexString(cp).uppercase().padStart(4, '0') + "⟩") }
        } else {
            appendCodePoint(cp)
        }
        i += Character.charCount(cp)
    }
}

private fun AnnotatedString.Builder.appendCodePoint(cp: Int) = append(String(Character.toChars(cp)))

private val xs = ppmText(12f, lineHeight = 16f)
private val xsMedium = ppmText(12f, FontWeight.Medium, lineHeight = 16f)

/** `rounded border border-warning/50 bg-warning/15 px-2 py-1.5 text-xs font-medium text-warning`, an AlertCircle first. */
@Composable
internal fun ApprovalWarningLine(text: String) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(tokens.warning.opacity(0.15).color, shape)
            .cssBorder(tokens.warning.opacity(0.5).color, shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        PpmIcon(ProductIcon.AlertCircle, 16.dp, tokens.warning.color)
        BasicText(text, Modifier.weight(1f), xsMedium.copy(color = tokens.warning.color))
    }
}

/**
 * A server-built summary (`SummaryBody` in approval-card.tsx): the headline, its facts as a
 * two-column list, the body in full and wrapped, and its warnings. Shared by the PPM Assistant's
 * endpoint cards and a session tool's schedule card. Every value is shown verbatim, hidden
 * characters as markers.
 */
@Composable
fun ApprovalSummaryBody(summary: ApprovalSummary) {
    val tokens = LocalPpmTokens.current
    val marker = SpanStyle(color = tokens.warning.color, background = tokens.warning.opacity(0.2).color, fontFamily = ppmText(12f, kind = FontKind.MONO).fontFamily)
    val hidden = remember(summary) { (listOf(summary.body?.text.orEmpty()) + summary.facts.map { it.value }).any(::hasHiddenCharacters) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(summary.headline, style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text.color))
        if (summary.facts.isNotEmpty()) FactGrid(summary.facts, marker, tokens.text2, tokens.text, tokens.warning)
        summary.body?.let { body ->
            Column(Modifier.testTag("approval-summary-body"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val count = summary.statementCount?.let { n -> " · $n statement${if (n == 1) "" else "s"}" }.orEmpty()
                BasicText(body.label + count, style = xs.copy(color = tokens.text2.color))
                val shape = RoundedCornerShape(4.dp)
                // `max-h-80 overflow-y-auto whitespace-pre-wrap break-words`: wrapped, never cut.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .background(tokens.bgSolid.color, shape)
                        .cssBorder(tokens.border.color, shape)
                        .cssScrollY(),
                ) {
                    SelectionContainer {
                        BasicText(
                            revealedText(body.text, marker),
                            Modifier.padding(8.dp),
                            ppmText(12f, kind = if (body.format == "text") FontKind.SANS else FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color),
                        )
                    }
                }
            }
        }
        if (hidden) ApprovalWarningLine(HIDDEN_CHARACTERS_WARNING)
        summary.warning?.let { ApprovalWarningLine(it) }
    }
}

/**
 * `grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 text-xs`: the labels one column as wide as
 * the widest, the values the rest; a warning fact's value in the warning colour, medium.
 */
@Composable
private fun FactGrid(facts: List<ApprovalSummary.Fact>, marker: SpanStyle, label: Rgba, value: Rgba, warning: Rgba) {
    Layout(
        content = {
            for (f in facts) {
                BasicText(f.label, style = xs.copy(color = label.color))
                BasicText(
                    revealedText(f.value, marker),
                    style = (if (f.warning) xsMedium.copy(color = warning.color) else xs.copy(color = value.color)),
                )
            }
        },
        modifier = Modifier.fillMaxWidth().testTag("approval-summary-facts"),
    ) { measurables, constraints ->
        val gapX = 12.dp.roundToPx()
        val gapY = 4.dp.roundToPx()
        val width = constraints.maxWidth
        // The label column takes what its widest label needs, never more than half the row.
        val labelMax = max(0, (width - gapX) / 2)
        val labels = measurables.filterIndexed { i, _ -> i % 2 == 0 }.map { it.measure(Constraints(maxWidth = labelMax)) }
        val labelW = labels.maxOfOrNull { it.width } ?: 0
        val valueW = max(0, width - labelW - gapX)
        val values = measurables.filterIndexed { i, _ -> i % 2 == 1 }.map { it.measure(Constraints(maxWidth = valueW)) }
        val rows = labels.indices.map { i -> max(labels[i].height, values[i].height) }
        val height = rows.sum() + gapY * max(0, rows.size - 1)
        layout(width, height) {
            var y = 0
            for (i in labels.indices) {
                labels[i].placeRelative(0, y)
                values[i].placeRelative(labelW + gapX, y)
                y += rows[i] + gapY
            }
        }
    }
}
