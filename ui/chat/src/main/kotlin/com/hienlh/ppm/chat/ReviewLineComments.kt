package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.ReviewBlocks
import com.hienlh.ppm.state.ReviewComments
import com.hienlh.ppm.state.ReviewLineComment

/**
 * The chat whose changes a review tab shows (`ReviewView.sessionId`), where its line comments are
 * kept on this device, and [send], which puts them in that chat's composer as a chip and clears
 * them (`comments.send`). Line comments are offered only with one.
 */
class ReviewTabComments(val sessionId: String, val store: ReviewComments, val send: () -> Unit)

/** The review tab's comments, for the code pane under it. */
internal val LocalReviewComments = compositionLocalOf<ReviewTabComments?> { null }

/** Where a line comment belongs (`ReviewCommentScope`): this chat and the file on screen. */
internal class ReviewCommentScope(val comments: ReviewTabComments, val path: String)

internal val LocalReviewCommentScope = compositionLocalOf<ReviewCommentScope?> { null }

/** [content] with the file on screen as the place its line comments go, when the tab takes comments. */
@Composable
internal fun ProvideReviewCommentScope(path: String, content: @Composable () -> Unit) {
    val comments = LocalReviewComments.current
    if (comments == null) content() else CompositionLocalProvider(LocalReviewCommentScope provides ReviewCommentScope(comments, path), content = content)
}

/** The comments written so far in [comments]' chat, as they change. */
@Composable
internal fun rememberSessionComments(comments: ReviewTabComments?): List<ReviewLineComment> {
    comments ?: return emptyList()
    val all by comments.store.state.collectAsState()
    return all[comments.sessionId].orEmpty()
}

/**
 * The line numbers as the comment button (`col-span-2 … hover:bg-primary/10`): a tap on a phone, a
 * click on a tablet. Drawn over the numbers, which the rows draw themselves.
 */
@Composable
internal fun ReviewCommentGutter(line: Int, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Box(
        Modifier
            .semantics { contentDescription = "Comment on line $line" }
            .ppmClickable(
                pressedColor = tokens.accent.opacity(0.1).color,
                hoverColor = tokens.accent.opacity(0.1).color,
                onClickLabel = "Comment on this line",
                onClick = onClick,
            ),
    )
}

/** The desktop header's "Send N comments to chat" (`Button variant="default" size="sm"`). */
@Composable
internal fun SendReviewCommentsButton(comments: ReviewTabComments?, modifier: Modifier = Modifier) {
    val count = rememberSessionComments(comments).size
    if (comments == null || count == 0) return
    ReviewButton(
        "Send $count ${if (count == 1) "comment" else "comments"} to chat",
        WebVariant.DEFAULT,
        WebSize.SM,
        modifier = modifier,
        icon = ProductIcon.MessageCircle,
        onClick = comments.send,
    )
}

/**
 * The phone header's comments button (`iconButton relative text-primary`): the message icon with
 * the count in a badge (`absolute top-1 right-1 min-w-4 rounded-full bg-primary px-1 text-[10px]
 * font-semibold leading-4`).
 */
@Composable
internal fun PhoneSendReviewCommentsButton(comments: ReviewTabComments?) {
    val count = rememberSessionComments(comments).size
    if (comments == null || count == 0) return
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(44.dp)
            .semantics { contentDescription = "Send $count comments to chat" }
            .ppmClickable(pressedColor = tokens.surfaceHover.color, shape = shape, onClickLabel = "Send $count comments to chat", onClick = comments.send),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(ProductIcon.MessageCircle, 20.dp, tokens.accent.color)
        BasicText(
            "$count",
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 4.dp, end = 4.dp)
                .widthIn(min = 16.dp)
                .background(tokens.accent.color, CircleShape)
                .padding(horizontal = 4.dp),
            ppmText(10f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.accentFg.color, textAlign = TextAlign.Center),
        )
    }
}

/** The side a row's comment is on: the file now, or the base for a removed line. */
internal val ReviewBlocks.Row.side: ReviewLineComment.Side
    get() = if (n != null) ReviewLineComment.Side.NEW else ReviewLineComment.Side.OLD

/** The number a row's comment names: the file's now, or the base's for a removed line. */
internal val ReviewBlocks.Row.line: Int
    get() = n ?: o ?: 0
