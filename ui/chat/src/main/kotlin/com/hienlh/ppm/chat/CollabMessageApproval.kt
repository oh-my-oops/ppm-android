package com.hienlh.ppm.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ProviderLogo
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.SessionCollabApprovalInput

private val xs = ppmText(12f, lineHeight = 16f)
private val xsMedium = ppmText(12f, FontWeight.Medium, lineHeight = 16f)

/** What the card says of a chat that runs every tool unasked (`BYPASS_NOTE`). */
internal const val BYPASS_NOTE = "That chat runs every tool without asking: whatever it does after this message will not ask you first."

/**
 * The body of the PPM Assistant's ask to message one of the user's chats (`kind: "message"` in
 * `collab-approval-card.tsx`): the chat, its project, the mode the message will run in, and the
 * whole message.
 */
@Composable
internal fun CollabMessageBody(input: SessionCollabApprovalInput.Message) {
    val tokens = LocalPpmTokens.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        ProviderLogo(input.providerId, 16.dp, tokens.text.color)
        BasicText(input.targetTitle, Modifier.widthIn(max = 480.dp), xsMedium.copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(input.projectName, style = xs.copy(color = tokens.text3.color))
    }
    RunsIn(input.permissionMode)
    WholeText("The whole message", input.message)
}

/** The mode a chat will run the message in, called out when it asks nothing (`RunsIn`). */
@Composable
private fun RunsIn(mode: String) {
    val tokens = LocalPpmTokens.current
    BasicText(
        buildAnnotatedString {
            append("Runs in ")
            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(modeLabel(mode)) }
            append(" mode.")
        },
        style = xs.copy(color = tokens.text.color),
    )
    if (mode == "bypassPermissions") {
        BasicText(BYPASS_NOTE, Modifier.testTag("collab-bypass-note"), xsMedium.copy(color = tokens.warning.color))
    }
}
