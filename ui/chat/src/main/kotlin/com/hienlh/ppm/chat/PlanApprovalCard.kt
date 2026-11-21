package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.state.ApprovalChoice
import com.hienlh.ppm.state.LineComments
import kotlinx.serialization.json.JsonElement

private val HEADING = Regex("^#{1,6}\\s")

/**
 * The card a plan waits on (`plan-approval-card.tsx`, Claude's ExitPlanMode in plan mode): the
 * plan line by line, each line's number opening a comment under it, and either Approve or Send
 * feedback — the comments, quoted beside their lines, go back to Claude as what to do instead,
 * and it keeps planning. Preview shows the plan rendered.
 */
@Composable
fun PlanApprovalCard(requestId: String, plan: String, respond: (approved: Boolean, data: JsonElement?) -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val lines = remember(plan) { LineComments.planLines(plan) }
    val comments = remember(requestId) { mutableStateMapOf<Int, String>() }
    var editing by remember(requestId) { mutableStateOf<Int?>(null) }
    var overall by remember(requestId) { mutableStateOf("") }
    var preview by remember(requestId) { mutableStateOf(false) }
    val count = comments.values.count { it.isNotBlank() }
    val feedback = LineComments.formatPlanComments(lines, comments.toMap(), overall)
    // `min-h-11 md:min-h-0`.
    val tall = if (LocalPpmMobile.current) Modifier.heightIn(min = 44.dp) else Modifier

    fun setComment(i: Int, body: String) {
        if (body.isNotBlank()) comments[i] = body.trim() else comments.remove(i)
    }

    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Plan approval" }
            .background(tokens.accentWash.color, shape)
            .cssBorder(tokens.accent.opacity(0.4).color, shape, width = 2.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PpmIcon(ProductIcon.ClipboardList, 16.dp, tokens.accent.color)
            BasicText("Plan ready for review", Modifier.weight(1f), ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.accent.color))
            PreviewToggle(preview) { preview = !preview }
        }
        val box = RoundedCornerShape(4.dp)
        val frame = Modifier
            .fillMaxWidth()
            .heightIn(max = 448.dp)
            .clip(box)
            .background(tokens.bgSolid.color)
            .cssBorder(tokens.border.color, box)
        if (preview) {
            Box(frame.cssScrollY().padding(12.dp)) {
                MarkdownContent(plan)
            }
        } else {
            Column(frame.cssScrollY().padding(vertical = 4.dp)) {
                lines.forEachIndexed { i, line ->
                    val commented = comments.containsKey(i)
                    PlanLine(i, line, commented) { editing = i }
                    if (editing == i || commented) {
                        LineCommentBox(
                            body = comments[i].orEmpty(),
                            editing = editing == i,
                            onEdit = { editing = i },
                            onSave = { body -> setComment(i, body); editing = null },
                            onCancel = { editing = null },
                            onDelete = { setComment(i, ""); editing = null },
                            modifier = Modifier.padding(start = 36.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                        )
                    }
                }
            }
        }
        CardTextArea(
            value = overall,
            onValueChange = { overall = it },
            placeholder = "Anything else Claude should change (optional)",
            label = "Feedback on the plan",
            autoFocus = false,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            SolidButton("Approve plan", tokens.success, tall) { respond(true, null) }
            val label = if (feedback.isEmpty()) "Keep planning" else "Send feedback" + if (count > 0) " ($count ${if (count == 1) "comment" else "comments"})" else ""
            EdgeButton(label, tokens.border, tokens.text, tokens.text, tokens.surfaceHover, tall) {
                respond(false, ApprovalChoice.message(feedback))
            }
            if (count > 0) BasicText("Approving ignores the comments.", style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color))
        }
    }
}

/** `inline-flex h-7 items-center gap-1 rounded-md px-2 text-xs text-text-2 hover:bg-surface-hover` (26 px on a phone). */
@Composable
private fun PreviewToggle(preview: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover()
    val shape = RoundedCornerShape(6.dp)
    val label = if (preview) "Comment" else "Preview"
    Row(
        Modifier
            .height(if (LocalPpmMobile.current) 26.dp else 28.dp)
            .clip(shape)
            .ppmClickable(pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color, shape = shape, onClickLabel = label, source = hover.source, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val ink = tokens.text2.mix(tokens.text, hover.lit.toDouble()).color
        PpmIcon(if (preview) ProductIcon.MessageCircle else ProductIcon.Eye, 14.dp, ink)
        BasicText(label, style = ppmText(12f, lineHeight = 16f).copy(color = ink))
    }
}

/**
 * One line of the plan (`grid-cols-[36px_minmax(0,1fr)]`, tinted once it has a comment): its number,
 * the button that opens its comment, then its text at 13 px, a heading's in semibold.
 */
@Composable
private fun PlanLine(index: Int, line: String, commented: Boolean, onComment: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover()
    Row(Modifier.fillMaxWidth().background(if (commented) tokens.accent.opacity(0.05).color else Color.Transparent)) {
        Box(
            Modifier
                .width(36.dp)
                .semantics { contentDescription = "Comment on line ${index + 1}" }
                .ppmClickable(pressedColor = tokens.accent.opacity(0.1).color, hoverColor = tokens.accent.opacity(0.1).color, onClickLabel = "Comment on this line", source = hover.source, onClick = onComment)
                .padding(end = 8.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            if (hover.lit > 0.5f) {
                PpmIcon(ProductIcon.MessageCircle, 12.dp, tokens.accent.color, Modifier.padding(top = 4.dp))
            } else {
                BasicText(
                    "${index + 1}",
                    style = ppmText(11f, lineHeight = 20f).copy(color = tokens.text3.color, textAlign = TextAlign.End),
                )
            }
        }
        BasicText(
            line.ifEmpty { " " },
            Modifier.weight(1f).padding(end = 12.dp),
            ppmText(13f, if (HEADING.containsMatchIn(line)) FontWeight.SemiBold else FontWeight.Normal, lineHeight = 20f).copy(color = tokens.text.color),
        )
    }
}
