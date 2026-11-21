package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.chat.MarkdownContent
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.state.SideQuestionState

/**
 * `/btw`'s answer above the composer (`SideQuestionPanel`): the question, Claude's answer from what
 * the chat already knows, and a close. Neither joins the conversation.
 */
@Composable
fun SideQuestionPanel(state: SideQuestionState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    val body = ppmText(14f, lineHeight = 14f * 1.625f)
    Column(
        modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .ppmShadow(tokens.shadowFloat, shape)
            .clip(shape)
            .background(tokens.panel2.over(tokens.bgSolid).color)
            .cssBorder(tokens.border.color, shape)
            .semantics { paneTitle = "Side question" }
            .testTag("side-question-panel"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(ProductIcon.MessageCircle, 16.dp, tokens.text3.color, Modifier.padding(top = 2.dp))
            Column(Modifier.weight(1f)) {
                BasicText(
                    "BY THE WAY · NOT ADDED TO THE CHAT",
                    style = ppmText(11f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text3.color, letterSpacing = 0.275.sp),
                    maxLines = 1,
                    softWrap = false,
                )
                BasicText(state.question, style = body.copy(color = tokens.text2.color), modifier = Modifier.testTag("side-question-text"))
            }
            val closeShape = RoundedCornerShape(6.dp)
            Box(
                Modifier
                    .size(24.dp)
                    .clip(closeShape)
                    .webTitle("Close")
                    .ppmClickable(pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color, shape = closeShape, onClick = onClose)
                    .semantics { contentDescription = "Close side question" }
                    .testTag("side-question-close"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.X, 14.dp, tokens.text3.color)
            }
        }
        val maxBody = (LocalConfiguration.current.screenHeightDp * 0.4f).dp
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxBody)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            when (state) {
                is SideQuestionState.Asking -> Row(
                    Modifier.testTag("side-question-asking"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PpmIcon(ProductIcon.Loader2, 14.dp, tokens.text3.color, spinning = true)
                    BasicText("Asking Claude…", style = body.copy(color = tokens.text3.color))
                }
                is SideQuestionState.Answered -> {
                    val answer = state.answer
                    if (!answer.isNullOrEmpty()) {
                        MarkdownContent(answer, Modifier.testTag("side-question-answer"))
                    } else {
                        BasicText("Claude gave no answer.", style = body.copy(color = tokens.text3.color))
                    }
                }
                is SideQuestionState.Failed -> BasicText(
                    state.error,
                    Modifier.testTag("side-question-error"),
                    style = body.copy(color = tokens.error.color),
                )
            }
        }
    }
}

/**
 * [tab]'s side question, when it has one; closed when the tab shows another session, as the web
 * closes it when its `sessionId` changes.
 */
@Composable
fun ChatSideQuestion(model: AppModel, tab: ChatTab, sessionId: String?, modifier: Modifier = Modifier) {
    val states by model.sideQuestions.states.collectAsState()
    LaunchedEffect(tab.tabId, sessionId) { model.sideQuestions.follow(tab.tabId, sessionId) }
    val state = states[tab.tabId] ?: return
    SideQuestionPanel(state, onClose = { model.sideQuestions.close(tab.tabId) }, modifier)
}
