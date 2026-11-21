package com.hienlh.ppm.app.ui.phone.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.openAssistant
import com.hienlh.ppm.app.ui.projectBackground
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.isAssistantProject
import com.hienlh.ppm.state.UnreadBellGroup
import com.hienlh.ppm.state.UnreadBellRow
import com.hienlh.ppm.state.UnreadKind

/** The Assistant's heading in the bell (`ASSISTANT_TAB_TITLE`). */
private const val ASSISTANT_HEADING = "PPM Assistant"

/**
 * The bell's body (`notification-bell-popover.tsx`): a heading per project — its colour dot and
 * name, the Assistant's sessions under "PPM Assistant" — then one row per unread session, tinted by
 * what it was left on. [mobile] gives the rows a 44 dp touch target.
 */
@Composable
internal fun UnreadSessionGroups(
    groups: List<UnreadBellGroup>,
    projects: List<ProjectInfo>,
    mobile: Boolean,
    onOpen: (sessionId: String, projectName: String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        for (group in groups) {
            val index = projects.indexOfFirst { it.name == group.project }
            val dot = if (index >= 0) projectBackground(projects[index].color, index) else SolidColor(tokens.text3.color)
            val heading = if (isAssistantProject(group.project)) ASSISTANT_HEADING else group.project
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (mobile) 16.dp else 14.dp, vertical = if (mobile) 6.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                BasicText(
                    heading.uppercase(),
                    style = ppmText(if (mobile) 11f else 10.5f, FontWeight.Medium, lineHeight = 14f)
                        .copy(color = tokens.text3.color, letterSpacing = 0.05.em),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            for (row in group.rows) {
                UnreadSessionRow(row, mobile, tokens) { onOpen(row.sessionId, group.project) }
            }
        }
    }
}

@Composable
private fun UnreadSessionRow(row: UnreadBellRow, mobile: Boolean, tokens: ThemeTokens, onClick: () -> Unit) {
    val (tint, icon) = when (row.kind) {
        UnreadKind.APPROVAL -> tokens.error to ProductIcon.ShieldAlert
        UnreadKind.QUESTION -> tokens.warning to ProductIcon.CircleHelp
        UnreadKind.DONE -> tokens.accent to ProductIcon.CheckCircle2
        UnreadKind.OTHER -> tokens.error to ProductIcon.Bell
    }
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("unread-session-${row.sessionId}")
            .background(tint.opacity(0.1).color)
            .ppmClickable(
                pressedColor = tokens.surfaceHover.color,
                hoverColor = tokens.surfaceHover.color,
                onClickLabel = row.title,
                onClick = onClick,
            )
            .heightIn(min = if (mobile) 44.dp else 34.dp)
            .padding(horizontal = if (mobile) 16.dp else 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.semantics { contentDescription = row.kind.label }) {
            PpmIcon(icon, if (mobile) 16.dp else 14.dp, tint.color)
        }
        BasicText(
            row.title,
            Modifier.weight(1f),
            style = ppmText(if (mobile) 14f else 12.5f, FontWeight.Normal, lineHeight = if (mobile) 18f else 16f)
                .copy(color = tokens.text.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A row's tap (`handleGoToSession`): an Assistant session opens in the Assistant, any other in its
 * project's chat, and either is then read. A session filed under a project the server does not
 * have opens nothing and stays unread, so the caller keeps the list open ([false]).
 */
internal fun openBellSession(
    sessionId: String,
    projectName: String,
    openAssistant: (String) -> Unit,
    openChat: (String, String) -> Boolean,
    markRead: (String) -> Unit,
): Boolean {
    if (isAssistantProject(projectName)) {
        openAssistant(sessionId)
    } else if (!openChat(sessionId, projectName)) {
        return false
    }
    markRead(sessionId)
    return true
}

/** [openBellSession] against the app. */
internal fun AppModel.openUnreadBellSession(sessionId: String, projectName: String): Boolean =
    openBellSession(
        sessionId,
        projectName,
        openAssistant = { openAssistant(sessionId = it) },
        openChat = ::openUnreadSession,
        markRead = { events?.markRead(it) },
    )
