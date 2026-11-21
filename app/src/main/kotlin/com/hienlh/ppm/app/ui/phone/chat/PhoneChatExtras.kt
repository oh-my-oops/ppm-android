package com.hienlh.ppm.app.ui.phone.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.ui.ContextMeterChip
import com.hienlh.ppm.client.contextUsage
import com.hienlh.ppm.design.kit.KitSpec
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.TurnOptions
import com.hienlh.ppm.state.ChatController
import com.hienlh.ppm.state.ChatState

/**
 * The context meter in the phone's context row (`ContextMeterFace` + `ContextMeterPanel`), which
 * has no footer cluster: "Ctx 42%" once a Claude turn has reported it, its breakdown in a sheet,
 * and Compact now while the chat is connected and idle.
 */
@Composable
internal fun PhoneContextMeterChip(model: AppModel, tab: ChatTab, chat: ChatController?, state: ChatState) {
    ContextMeterChip(
        sessionId = tab.session?.id ?: state.sessionId,
        providerId = tab.providerId,
        contextPct = state.contextWindowPct,
        load = { id -> model.api?.contextUsage(tab.project, id) ?: error("Not connected") },
        onCompact = if (chat != null && state.connected && !state.isRunning) {
            { chat.send("/compact", TurnOptions(permissionMode = tab.permissionMode?.value)) }
        } else {
            null
        },
    )
}

/**
 * Claude's suggested next prompt as a tap (`prompt-suggestion-chip`), a pill over the composer with
 * a sparkle and the suggestion on one line; a 44 dp target around its 30 dp face.
 */
@Composable
internal fun PhonePromptSuggestionChip(suggestion: String, onAccept: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(KitSpec.MinTouchTarget)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier
                .height(30.dp)
                .clip(shape)
                .background(tokens.panel2.color)
                .cssBorder(tokens.border.color, shape)
                .ppmClickable(pressedColor = tokens.surfaceHover.color, shape = shape, onClickLabel = "Use this suggestion", onClick = onAccept)
                .padding(horizontal = 12.dp)
                .testTag("prompt-suggestion-chip"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(ProductIcon.Sparkles, 14.dp, tokens.text2.color)
            TruncatedText(AnnotatedString(suggestion), ppmText(13f, lineHeight = 18f).copy(color = tokens.text2.color))
        }
    }
}
