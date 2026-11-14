package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.chat.RelativeTime
import com.hienlh.ppm.client.SpecSummary
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFormat
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SessionUnread
import com.hienlh.ppm.state.SpecBarCell
import com.hienlh.ppm.state.SpecFamily
import java.time.Instant

/** Left padding of a row [depth] levels under a top-level chat (`indent` in session-history-list.tsx). */
private fun indent(depth: Int): Dp = (8 + depth * 16).dp

/**
 * One family of the tablet sidebar's Chat History tree, as the web's desktop sidebar draws it
 * (`renderRoot`): the top chat (or a stand-in for a creator not in the list), then [lines]'
 * sub-chats behind "N sub-chats", each level 16 px further in. A spec's chat carries the spec's
 * progress under its title and a chevron at its end, and its task chats sit in a block lined up
 * under its title (`renderSpecFamily`), so the spec's own toggle line is not drawn as a line here.
 */
@Composable
internal fun SidebarHistoryFamily(
    model: AppModel,
    project: String?,
    lines: List<HistoryLine>,
    unread: Map<String, SessionUnread>,
    running: Set<String>,
    onToggle: (HistoryLine.Toggle) -> Unit,
    onShowAll: (parentId: String) -> Unit,
    onOpen: (SessionInfo) -> Unit,
    onOpenStandIn: () -> Unit,
) {
    val head = lines.first() as HistoryLine.Chat
    val spec = head.spec
    val taskToggle = lines.firstOrNull { it is HistoryLine.Toggle && it.taskChats } as HistoryLine.Toggle?
    val rest = lines.drop(1).filter { it != taskToggle }
    val top = head.session

    @Composable
    fun line(line: HistoryLine, inSpec: Boolean) {
        // Inside the spec's block `renderChildren` starts one level up.
        fun start(depth: Int) = indent(if (inSpec) depth - 1 else depth)
        when (line) {
            is HistoryLine.Toggle -> SidebarFamilyToggle(line, start(line.depth)) { onToggle(line) }
            is HistoryLine.More -> SidebarShowMore(line) { onShowAll(line.parentId) }
            is HistoryLine.Chat -> {
                val entry = unread[line.session.id]
                if (line.task) {
                    SidebarTaskRow(model, line.session, line.taskCell, line.session.id in running, entry?.type, entry != null) { onOpen(line.session) }
                } else {
                    SidebarSessionRow(model, line.session, entry?.type, entry != null, start = start(line.depth)) { onOpen(line.session) }
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        val entry = unread[top.id]
        // Under its creator the mark says nothing the tree does not.
        val badge: (@Composable () -> Unit)? = project?.let { p -> { SpawnedSessionBadge(model, top, p) } }
        when {
            head.standIn -> SidebarStandInRow(top, onOpenStandIn)
            spec != null && taskToggle != null -> SidebarSessionRow(
                model,
                top,
                entry?.type,
                entry != null,
                badge = badge,
                meta = { SidebarSpecMeta(spec, taskToggle.unread, top.updatedAt) },
                trailing = { SidebarTaskChatsChevron(taskToggle) { onToggle(taskToggle) } },
            ) { onOpen(top) }
            else -> SidebarSessionRow(model, top, entry?.type, entry != null, badge = badge) { onOpen(top) }
        }
        if (spec == null || taskToggle == null) {
            for (l in rest) line(l, inSpec = false)
        } else if (rest.isNotEmpty()) {
            // `pt-0.5 pb-1.5 pr-1`, `paddingLeft: SPEC_KIDS_INDENT`.
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 4.dp, top = 2.dp, bottom = 6.dp)) {
                for (l in rest) line(l, inSpec = true)
            }
        }
    }
}

/**
 * `renderChildren`'s toggle: `py-1 pr-2 gap-1 text-[11px] text-text-subtle`, a 12 px chevron, then
 * "N sub-chats", "· k running" in the info colour and "· u unread" in semibold; under a mouse
 * `hover:bg-surface-elevated hover:text-text-secondary`.
 */
@Composable
private fun SidebarFamilyToggle(line: HistoryLine.Toggle, start: Dp, onToggle: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover()
    val ink = tokens.text2.mix(tokens.text3, hover.lit.toDouble()).color
    val label = "" + line.count + " sub-chat" + (if (line.count == 1) "" else "s")
    Row(
        Modifier
            .fillMaxWidth()
            .ppmClickable(
                pressedColor = tokens.panel2.color,
                hoverColor = tokens.panel2.color,
                source = hover.source,
                onClickLabel = if (line.open) "Hide $label" else "Show $label",
                onClick = onToggle,
            )
            .semantics { stateDescription = if (line.open) "Expanded" else "Collapsed" }
            .testTag("history-family-toggle:" + line.parentId)
            .padding(start = start, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PpmIcon(if (line.open) ProductIcon.ChevronDown else ProductIcon.ChevronRight, 12.dp, ink)
        val style = ppmText(11f, lineHeight = 16.5f)
        BasicText(label, style = style.copy(color = ink), maxLines = 1)
        if (line.running > 0) BasicText("· " + line.running + " running", style = style.copy(color = tokens.info.color), maxLines = 1)
        if (line.unread > 0) {
            BasicText("· " + line.unread + " unread", style = ppmText(11f, FontWeight.SemiBold, lineHeight = 16.5f).copy(color = tokens.text.color), maxLines = 1)
        }
    }
}

/**
 * The spec chat's line under its title: the bar (`mt-1.5 h-1 gap-0.5 rounded-full`, a cell per
 * required task), then `mt-1` the counts at 11 px with the time pushed to the end.
 */
@Composable
private fun SidebarSpecMeta(spec: SpecSummary, unread: Int, updatedAt: Instant?) {
    val tokens = LocalPpmTokens.current
    val p = SpecFamily.progress(spec)
    Column(Modifier.fillMaxWidth().testTag("history-spec-progress:" + spec.slug)) {
        Row(
            Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(999.dp)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (cell in SpecFamily.bar(spec)) {
                val color = when (cell) {
                    SpecBarCell.DONE -> tokens.success.color
                    SpecBarCell.RUNNING -> tokens.info.color
                    SpecBarCell.FAILED -> tokens.error.color
                    SpecBarCell.TODO -> tokens.border.color
                }
                Box(Modifier.weight(1f).fillMaxHeight().background(color))
            }
        }
        Row(Modifier.padding(top = 4.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val style = ppmText(11f, lineHeight = 16.5f)
            BasicText("" + p.done + "/" + p.total + " done", style = style.copy(color = tokens.text2.color, fontFeatureSettings = "tnum"), maxLines = 1)
            if (p.running > 0) BasicText("· " + p.running + " running", style = style.copy(color = tokens.info.color), maxLines = 1)
            if (p.failed > 0) BasicText("· " + p.failed + " failed", style = style.copy(color = tokens.error.color), maxLines = 1)
            if (unread > 0) BasicText("· $unread unread", style = ppmText(11f, FontWeight.SemiBold, lineHeight = 16.5f).copy(color = tokens.text.color), maxLines = 1)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                updatedAt?.let { RelativeTime(it, style.copy(color = tokens.text3.color)) }
            }
        }
    }
}

/** The spec row's `size-6 self-start rounded` chevron, which opens and closes its task chats. */
@Composable
private fun RowScope.SidebarTaskChatsChevron(line: HistoryLine.Toggle, onToggle: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(instant = true)
    val label = if (line.open) "Hide task chats" else "Show " + line.count + " task chat" + (if (line.count == 1) "" else "s")
    Box(
        Modifier
            .align(Alignment.Top)
            .size(24.dp)
            .clip(RoundedCornerShape(4.dp))
            .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, fade = false, source = hover.source, onClickLabel = label, onClick = onToggle)
            .semantics {
                contentDescription = label
                stateDescription = if (line.open) "Expanded" else "Collapsed"
            }
            .testTag("history-family-toggle:" + line.parentId),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(if (line.open) ProductIcon.ChevronDown else ProductIcon.ChevronRight, 14.dp, tokens.text2.mix(tokens.text3, hover.lit.toDouble()).color)
    }
}

/**
 * A task chat under its spec (`renderTaskRow`): `h-[26px] rounded-md pl-1.5 pr-2 gap-2 text-xs`,
 * the task's state, the title, the time. Unread: tinted and semibold; a done task's in the subtle colour.
 */
@Composable
private fun SidebarTaskRow(
    model: AppModel,
    session: SessionInfo,
    cell: SpecBarCell?,
    running: Boolean,
    unreadType: String?,
    isUnread: Boolean,
    open: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var renaming by remember { mutableStateOf(false) }
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 26.dp)
                .clip(shape)
                .background(if (isUnread) tokens.notification(unreadType).opacity(0.1).color else Color.Transparent)
                .pressable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, enabled = !renaming, onLongClick = { menuAt = it }, onClick = open)
                .padding(start = 6.dp, end = 8.dp)
                .testTag("history-task-chat:" + session.id),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                running || cell == SpecBarCell.RUNNING ->
                    PpmIcon(ProductIcon.Loader2, 14.dp, tokens.info.color, Modifier.semantics { contentDescription = "Running" }, spinning = true)
                cell == SpecBarCell.FAILED -> PpmIcon(ProductIcon.CircleX, 14.dp, tokens.error.color, Modifier.semantics { contentDescription = "Failed" })
                cell == SpecBarCell.DONE -> PpmIcon(ProductIcon.Check, 14.dp, tokens.success.color, Modifier.semantics { contentDescription = "Done" })
                else -> Box(Modifier.size(14.dp))
            }
            if (renaming) {
                InlineRename(model, session, RenameField.HistoryList, Modifier.weight(1f)) { renaming = false }
            } else {
                val color = when {
                    isUnread -> tokens.text
                    cell == SpecBarCell.DONE -> tokens.text3
                    else -> tokens.text2
                }
                BasicText(
                    PpmFormat.collapsedWhitespace(session.title).ifEmpty { "Untitled" },
                    Modifier.weight(1f),
                    style = ppmText(12f, if (isUnread) FontWeight.SemiBold else FontWeight.Normal, lineHeight = 16f).copy(color = color.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                session.updatedAt?.let { RelativeTime(it, ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text3.color)) }
            }
        }
        menuAt?.let { at ->
            SessionContextMenu(model, session, at, onDismiss = { menuAt = null }, onRename = { renaming = true }, onDelete = { model.confirmDeleteSession = session })
        }
    }
}

/** The spec's task chats past the first few: `h-[26px] pl-1.5 text-[11px] text-text-subtle hover:text-text-secondary`. */
@Composable
private fun SidebarShowMore(line: HistoryLine.More, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover()
    val label = "Show " + line.hidden + " more"
    Box(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .ppmClickable(pressedColor = Color.Transparent, source = hover.source, onClickLabel = label, onClick = onClick)
            .padding(start = 6.dp)
            .testTag("history-family-more:" + line.parentId),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(label, style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text2.mix(tokens.text3, hover.lit.toDouble()).color), maxLines = 1)
    }
}

/**
 * A family whose creator is not in the list (a later page, another tag): `px-2 py-1.5 gap-2
 * text-text-secondary`, the info-coloured branch and the creator's name at 13 px.
 */
@Composable
private fun SidebarStandInRow(session: SessionInfo, onOpen: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, onClickLabel = "Open " + session.title, onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("history-stand-in:" + session.id),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(ProductIcon.GitBranch, 14.dp, tokens.info.color)
        BasicText(
            session.title,
            style = ppmText(13f, FontWeight.Medium, lineHeight = 19.5f).copy(color = tokens.text2.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
