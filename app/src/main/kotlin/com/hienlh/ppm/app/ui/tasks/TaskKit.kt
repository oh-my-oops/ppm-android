package com.hienlh.ppm.app.ui.tasks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.tasks
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.vector
import com.hienlh.ppm.protocol.TaskConst
import com.hienlh.ppm.protocol.TaskDto
import com.hienlh.ppm.protocol.TaskLink
import com.hienlh.ppm.protocol.TaskLinkState
import com.hienlh.ppm.state.tasks.WaitingDetail
import com.hienlh.ppm.state.tasks.ageShort
import com.hienlh.ppm.state.tasks.dueView
import com.hienlh.ppm.state.tasks.linkStateView
import com.hienlh.ppm.state.tasks.memberInitials
import com.hienlh.ppm.state.tasks.memberTone
import com.hienlh.ppm.state.tasks.sessionChipView
import com.hienlh.ppm.state.tasks.snoozeBadgeText
import com.hienlh.ppm.state.tasks.statusGlyphKind
import com.hienlh.ppm.state.tasks.taskCode
import com.hienlh.ppm.state.tasks.typeLabel
import com.hienlh.ppm.state.tasks.urgencyLabel
import com.hienlh.ppm.protocol.TaskCheckSummary
import com.hienlh.ppm.protocol.TaskWorkingSession

/*
 * The PPM Tasks UI kit (the web's `components/ppm-tasks/kit` folder): the small pieces every task view draws —
 * status glyph, code, due label, importance bars, snooze badge, waiting label, working-session
 * chip, link pill, member avatar, urgency pill, type badge — in the app's own tokens.
 */

internal fun txt(size: Float, color: Color, weight: FontWeight = FontWeight.Normal, lineHeight: Float? = null, mono: Boolean = false): TextStyle =
    ppmText(size, weight, if (mono) FontKind.MONO else FontKind.SANS, lineHeight ?: size * 1.4f).copy(color = color)

@Composable
internal fun T(
    text: String,
    size: Float = 12f,
    color: Color = LocalPpmTokens.current.text.color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    mono: Boolean = false,
) = BasicText(text, modifier, style = txt(size, color, weight, mono = mono), maxLines = maxLines, overflow = TextOverflow.Ellipsis)

/** A task's status as a small glyph drawn by its base group. */
@Composable
internal fun StatusGlyph(baseGroup: String, closedAs: String?, size: Dp = 12.dp, modifier: Modifier = Modifier, name: String? = null) {
    val tokens = LocalPpmTokens.current
    val kind = statusGlyphKind(baseGroup, closedAs)
    Canvas(modifier.size(size).semantics { name?.let { contentDescription = it } }) {
        val w = 1.5.dp.toPx()
        val r = this.size.minDimension / 2 - w / 2
        when (kind) {
            "active" -> {
                drawArc(tokens.accent.color, -90f, 180f, useCenter = true, topLeft = Offset(w / 2, w / 2), size = Size(r * 2, r * 2))
                drawCircle(tokens.accent.color, r, style = Stroke(w))
            }
            "wait" -> drawCircle(tokens.warning.color, r, style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 1.5.dp.toPx()))))
            "done" -> {
                drawCircle(tokens.success.color, r, style = Stroke(w))
                drawCircle(tokens.success.color, r - 2.5.dp.toPx())
            }
            "drop" -> {
                drawCircle(tokens.text3.color, r, style = Stroke(w))
                drawLine(tokens.text3.color, Offset(this.size.width * 0.2f, this.size.height * 0.8f), Offset(this.size.width * 0.8f, this.size.height * 0.2f), w)
            }
            else -> drawCircle(tokens.text3.color, r, style = Stroke(w))
        }
    }
}

/** `#12` (or `ppm/12` outside its project); a tap copies `ppm/12` (`TaskCode`). */
@Composable
internal fun TaskCodeText(model: AppModel, task: TaskDto, viewProject: String?, size: Float = 11.5f) {
    val tokens = LocalPpmTokens.current
    val clipboard = LocalClipboardManager.current
    val full = "${task.project}/${task.number}"
    T(
        taskCode(task, viewProject), size, tokens.text3.color, mono = true,
        modifier = Modifier.testTag("task-code").ppmClickable(onClickLabel = "Copy $full") {
            clipboard.setText(AnnotatedString(full))
            model.tasks.info("Copied $full")
        },
    )
}

/** The due label: a calendar and "Overdue 2d", red when due tomorrow or earlier. */
@Composable
internal fun DueLabel(due: String?, today: String, iconless: Boolean = false) {
    val view = dueView(due, today) ?: return
    val tokens = LocalPpmTokens.current
    val color = if (view.soon) tokens.error.color else tokens.text2.color
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (!iconless) PpmIcon(TaskIcons.Calendar, 12.dp, color)
        T(view.label, 11.5f, color, weight = if (view.soon) FontWeight.Medium else FontWeight.Normal)
    }
}

/** Must / Should / Nice as three, two or one lit bars. */
@Composable
internal fun ImportanceBars(importance: String) {
    val tokens = LocalPpmTokens.current
    val lit = when (importance) { "must" -> 3; "should" -> 2; else -> 1 }
    Row(Modifier.height(12.dp).semantics { contentDescription = com.hienlh.ppm.state.tasks.importanceLabel(importance) }, horizontalArrangement = Arrangement.spacedBy(1.5.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 0 until 3) {
            Box(
                Modifier.width(3.dp).height((5 + i * 3).dp).clip(RoundedCornerShape(1.dp))
                    .background(if (i < lit) (if (importance == "must") tokens.error.color else tokens.text2.color) else tokens.borderSoft.color),
            )
        }
    }
}

/** "Until Thu 20 Nov" with a moon while snoozed, "Back today" on the day it came back. */
@Composable
internal fun SnoozeBadge(task: TaskDto, today: String, compact: Boolean = false) {
    val text = snoozeBadgeText(task, today, compact) ?: return
    val tokens = LocalPpmTokens.current
    val color = if (task.snoozed) tokens.text3.color else tokens.warning.color
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        PpmIcon(ProductIcon.Moon, 12.dp, color)
        T(text, 11.5f, color, weight = if (task.snoozed) FontWeight.Normal else FontWeight.Medium)
    }
}

/** "Waiting on you · Today", "Waiting on Việt · 2d": the user's in the accent with a raised hand. */
@Composable
internal fun WaitingLabel(detail: WaitingDetail, today: String, now: Long = System.currentTimeMillis()) {
    val tokens = LocalPpmTokens.current
    val color = if (detail.mine) tokens.accent.color else tokens.warning.color
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.testTag("task-waiting")) {
        if (detail.mine) PpmIcon(ProductIcon.Hand, 12.dp, color)
        T(detail.label, 11.5f, color, weight = if (detail.mine) FontWeight.Medium else FontWeight.Normal)
        when {
            detail.dueDate != null -> {
                T("·", 11.5f, tokens.text3.color)
                DueLabel(detail.dueDate, today, iconless = true)
            }
            detail.since != null -> {
                val since = detail.since!!
                T("·", 11.5f, tokens.text3.color)
                T(ageShort(since, now), 11f, tokens.text3.color, mono = true)
            }
        }
    }
}

private fun sessionTone(tokens: ThemeTokens, tone: String): Color = when (tone) {
    "run", "mgr" -> tokens.success.color
    "need", "stalled" -> tokens.error.color
    "finished" -> tokens.warning.color
    "none", "gone" -> tokens.text3.color
    else -> tokens.text2.color
}

/** The working-session chip: who works on the task and how it stands; "Start chat" when nobody does. */
@Composable
internal fun SessionChip(working: TaskWorkingSession, check: TaskCheckSummary?, onClick: (sessionId: String?) -> Unit) {
    val tokens = LocalPpmTokens.current
    // On a phone the chip row's 26 dp, at least 44 dp wide; a touch smaller than 48 dp is still
    // taken by Compose's minimum touch target, so its press stays thumb-sized.
    val phone = LocalPpmMobile.current
    val view = sessionChipView(working, check, System.currentTimeMillis())
    val color = sessionTone(tokens, view.tone)
    val shape = RoundedCornerShape(50)
    val text = if (phone) 12.5f else 11f
    Row(
        Modifier.heightIn(min = if (phone) 26.dp else 20.dp).widthIn(min = if (phone) 44.dp else 0.dp).clip(shape)
            .then(if (view.tone == "none") Modifier.border(1.dp, tokens.border.color, shape) else Modifier.background(tokens.panel2.color, shape))
            .ppmClickable(shape = shape, onClickLabel = view.title) { onClick(view.sessionId) }
            .padding(horizontal = if (phone) 8.dp else 6.dp, vertical = 1.dp)
            .semantics { contentDescription = view.title }
            .testTag("task-session-chip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (view.tone != "none") Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        else PpmIcon(ProductIcon.Plus, 11.dp, color)
        T(view.label, text, if (view.tone == "none") tokens.text2.color else tokens.text.color, modifier = Modifier.weight(1f, fill = false))
        view.detail?.let { T(it, text, color) }
    }
}

internal fun linkIcon(kind: String): ImageVector = when (kind) {
    "jira" -> TaskIcons.Ticket
    "pr" -> TaskIcons.GitPullRequest
    "session" -> ProductIcon.MessageCircle.vector
    else -> ProductIcon.Link.vector
}

private fun linkTone(tokens: ThemeTokens, tone: String): Color = when (tone) {
    "ok" -> tokens.success.color
    "bad" -> tokens.error.color
    "warn" -> tokens.warning.color
    "info" -> tokens.info.color
    "merge" -> tokens.accent2.color
    else -> tokens.text3.color
}

/** A link with its live state: "NX-1 · Done", "r#4 · open · CI failing". */
@Composable
internal fun LinkPill(link: TaskLink, live: TaskLinkState?, onClick: (() -> Unit)?, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val tokens = LocalPpmTokens.current
    val view = linkStateView(link, live)
    val tone = linkTone(tokens, view.tone)
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.heightIn(min = 24.dp).clip(shape).border(1.dp, tokens.borderSoft.color, shape).background(tokens.panel2.color)
            .then(if (onClick != null) Modifier.ppmClickable(shape = shape, onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("task-link-pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PpmIcon(linkIcon(link.kind), 13.dp, tone)
        T(view.key, 11.5f, tokens.text.color, mono = link.kind != "session" && link.kind != "url", modifier = Modifier.weight(1f, fill = false))
        view.state?.let { T(it, 11.5f, tone) }
        trailing?.invoke(this)
    }
}

private fun memberColor(tokens: ThemeTokens, tone: Int): Color = when (tone) {
    1 -> tokens.accent.color
    2 -> tokens.success.color
    3 -> tokens.warning.color
    4 -> tokens.info.color
    else -> tokens.accent2.color
}

/** A member's initials in their tone; the user neutral, the AI a gradient. */
@Composable
internal fun MemberAvatar(id: String, name: String, size: Dp = 18.dp, ai: Boolean = false) {
    val tokens = LocalPpmTokens.current
    val bg = when {
        ai -> Modifier.background(Brush.linearGradient(listOf(tokens.accent.color, tokens.accent2.color)), CircleShape)
        id == TaskConst.ME -> Modifier.background(tokens.panel2.color, CircleShape).border(1.dp, tokens.border.color, CircleShape)
        else -> Modifier.background(memberColor(tokens, memberTone(id)).copy(alpha = 0.22f), CircleShape)
    }
    Box(Modifier.size(size).then(bg).semantics { contentDescription = name }, contentAlignment = Alignment.Center) {
        val fg = if (ai) Color.White else if (id == TaskConst.ME) tokens.text2.color else memberColor(tokens, memberTone(id))
        if (ai) PpmIcon(ProductIcon.Sparkles, size * 0.6f, fg) else T(memberInitials(name), (size.value * 0.42f), fg, weight = FontWeight.SemiBold)
    }
}

internal fun urgencyColor(tokens: ThemeTokens, u: String): Color = when (u) {
    "now" -> tokens.error.color
    "today" -> tokens.warning.color
    "week" -> tokens.accent.color
    else -> tokens.text3.color
}

/** The urgency as a small coloured pill. */
@Composable
internal fun UrgencyPill(urgency: String) {
    val tokens = LocalPpmTokens.current
    val c = urgencyColor(tokens, urgency)
    val shape = RoundedCornerShape(50)
    Box(Modifier.clip(shape).background(c.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 1.dp)) {
        T(urgencyLabel(urgency), 11f, c, weight = FontWeight.Medium)
    }
}

internal fun typeIcon(type: String): ImageVector = when (type) {
    "decision" -> TaskIcons.Scale
    "reply" -> TaskIcons.Reply
    else -> ProductIcon.ListChecks.vector
}

/** A Decision or Reply badge; a plain task shows none unless [always]. */
@Composable
internal fun TypeBadge(type: String, always: Boolean = false) {
    if (type == "task" && !always) return
    val tokens = LocalPpmTokens.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        PpmIcon(typeIcon(type), 12.dp, tokens.accent.color)
        T(typeLabel(type), 11.5f, tokens.accent.color, weight = FontWeight.Medium)
    }
}

/** "while you were away": a change an unattended Assistant turn made. */
@Composable
internal fun UnattendedBadge() {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(50)
    Box(Modifier.clip(shape).background(tokens.warning.color.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        T("while you were away", 10.5f, tokens.warning.color)
    }
}

/**
 * The bordered card every section of a task's page sits in (`TaskCard`): a 40px header with the
 * title, an optional count and what sits at its right, then the body.
 */
@Composable
internal fun TaskSection(
    title: String,
    modifier: Modifier = Modifier,
    count: String? = null,
    icon: ImageVector? = null,
    decision: Boolean = false,
    tag: String? = null,
    aside: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val tone = if (decision) tokens.accent2.color else tokens.text.color
    Column(
        modifier.fillMaxWidth().clip(shape)
            .border(1.dp, if (decision) tokens.accent2.color.copy(alpha = 0.4f) else tokens.borderSoft.color, shape)
            .background(tokens.panel.color)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp)
                .then(if (decision) Modifier.background(tokens.accent2.color.copy(alpha = 0.1f)) else Modifier)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            icon?.let { PpmIcon(it, 14.dp, tone) }
            T(title, 13f, tone, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
            count?.let { CountBadge(it) }
            Box(Modifier.weight(1f))
            aside?.invoke(this)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.borderSoft.color))
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) { content() }
    }
}

/** A count in a small grey box ("Now 2", "Links 3"). */
@Composable
internal fun CountBadge(text: String) {
    val tokens = LocalPpmTokens.current
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(tokens.surfaceHover.color).padding(horizontal = 6.dp)) {
        T(text, 11f, tokens.text3.color, mono = true)
    }
}

/** A small text button ("Edit", "Manage", "Add child"). */
@Composable
internal fun LinkButton(label: String, modifier: Modifier = Modifier, icon: ProductIcon? = null, color: Color = LocalPpmTokens.current.accent.color, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier.heightIn(min = 28.dp).clip(shape).ppmClickable(enabled = enabled, shape = shape, onClick = onClick).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon?.let { PpmIcon(it, 13.dp, if (enabled) color else LocalPpmTokens.current.text3.color) }
        T(label, 12f, if (enabled) color else LocalPpmTokens.current.text3.color, weight = FontWeight.Medium)
    }
}

/** A square icon button with its label for screen readers. */
@Composable
internal fun IconBtn(icon: ProductIcon, label: String, modifier: Modifier = Modifier, size: Dp = 32.dp, tint: Color = LocalPpmTokens.current.text3.color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.size(size).clip(shape).ppmClickable(shape = shape, onClickLabel = label, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { PpmIcon(icon, 16.dp, tint) }
}

/** A filter or choice chip: lit when [on]. */
@Composable
internal fun Pill(label: String, on: Boolean, modifier: Modifier = Modifier, count: Int? = null, icon: ProductIcon? = null, strike: Boolean = false, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(50)
    Row(
        modifier.heightIn(min = 26.dp).clip(shape)
            .border(1.dp, if (on) tokens.accentWashBorder.color else tokens.borderSoft.color, shape)
            .background(if (on) tokens.accentWash.color else Color.Transparent)
            .ppmClickable(shape = shape, onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon?.let { PpmIcon(it, 12.dp, if (on) tokens.accent.color else tokens.text2.color) }
        BasicText(
            label,
            style = txt(12f, if (on) tokens.text.color else tokens.text2.color, if (on) FontWeight.Medium else FontWeight.Normal)
                .copy(textDecoration = if (strike) androidx.compose.ui.text.style.TextDecoration.LineThrough else null),
            maxLines = 1,
        )
        count?.let { T("$it", 11f, tokens.text3.color, mono = true) }
    }
}
