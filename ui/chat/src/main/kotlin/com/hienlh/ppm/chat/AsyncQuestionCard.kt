package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssHeight
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ChatQuestions
import com.hienlh.ppm.protocol.QueuedMessage
import com.hienlh.ppm.protocol.Question
import com.hienlh.ppm.state.ChatItem

/**
 * What a Codex question card reads from its chat (`AsyncQuestionContext`): the messages and the
 * session's [queued] ones, where its reply is found, and [onSend], a way to send one, only while
 * the chat is connected. Sent mid-turn, the reply is steered into the running turn at once (Send
 * now), never left queued until the turn ends: the AI asked so it could act on the answer.
 */
class AsyncQuestions(val items: List<ChatItem>, val queued: List<QueuedMessage>, val onSend: ((String) -> Unit)?)

val LocalAsyncQuestions = compositionLocalOf { AsyncQuestions(emptyList(), emptyList(), null) }

/**
 * Replies given in this process, by question item id (`sentReplies`). Not the card's own state: the
 * card is drawn again whenever the turn's events regroup, and one drawn before the reply reached
 * the conversation reopened the question, so each further tap sent another answer.
 */
private val sentReplies = mutableStateMapOf<String, String>()

/**
 * A question Codex asked without stopping its turn (async-question-card.tsx), answered by an
 * ordinary message naming the call [itemId]. Once that message is sent from here, waits in the
 * queue or is in the chat, or while the chat cannot send one, it is a box saying so instead of a
 * form; the form leaves the keyboard alone.
 */
@Composable
fun AsyncQuestionCard(itemId: String, questions: List<Question>, modifier: Modifier = Modifier) {
    val context = LocalAsyncQuestions.current
    val prefix = ChatQuestions.replyPrefix(itemId)
    val reply = context.items.firstOrNull { it.role == ChatItem.Role.USER && it.text.startsWith(prefix) }?.text
        ?: context.queued.firstOrNull { it.content.startsWith(prefix) }?.content
        ?: sentReplies[itemId]
    val send = context.onSend
    if (reply == null && send != null) {
        fun answer(content: String) {
            sentReplies[itemId] = content
            send(content)
        }
        QuestionCard(
            questions,
            onSubmit = { answer(ChatQuestions.formatReply(itemId, questions, it)) },
            onSkip = { answer(ChatQuestions.formatReply(itemId, questions, null)) },
            modifier = modifier,
            autoFocus = false,
        )
        return
    }
    val tokens = LocalPpmTokens.current
    // `rounded-lg border border-border bg-surface p-3 text-sm whitespace-pre-wrap`: the theme's `rounded-lg` is 12 px.
    val shape = RoundedCornerShape(12.dp)
    CssColumn(
        modifier.fillMaxWidth().background(tokens.panel.color, shape).cssBorder(tokens.border.color, shape).padding(horizontal = 12.dp),
        top = 12.dp,
        bottom = 12.dp,
    ) {
        // Each line its own line box, 20 px apart in fractions of a pixel as Chrome stacks them. A
        // line break ending the text opens no line of its own in front of the block under it.
        val body = (reply?.substring(prefix.length) ?: questions.joinToString("\n") { it.question }).removeSuffix("\n")
        val text = ppmText(14f, lineHeight = 20f).copy(color = tokens.text.color)
        if (body.isNotEmpty()) body.split('\n').forEach { BasicText(it, Modifier.cssLines(20f), text) }
        // `mt-2 text-xs text-text-secondary`.
        Spacer(Modifier.cssHeight(8.dp))
        BasicText(
            if (reply != null) "Response sent" else "Connect to answer this question",
            Modifier.cssLines(16f),
            ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
        )
    }
}
