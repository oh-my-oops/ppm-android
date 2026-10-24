package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.PromptCacheStatus

/**
 * How long the session has sat idle, once that has stopped being free (`IdleCacheNotice`): at the
 * end of the transcript, above the composer, while no turn runs and nothing waits on an answer.
 * A compaction is said without a figure, as nothing has measured its summary yet; an expiry names
 * the context the next message re-sends when the last turn measured it.
 */
@Composable
internal fun IdleCacheNotice(cold: PromptCacheStatus.Cold) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val line = remember(cold) { idleCacheLine(cold) }
    // `px-4 pt-4 pb-4`, then a `w-fit` box: one sentence, wrapping only where the width runs out.
    Row(
        Modifier
            .padding(16.dp)
            .background(tokens.panel.color, shape)
            .cssBorder(tokens.border.color, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        PpmIcon(ProductIcon.Clock, 14.dp, tokens.text3.color, Modifier.padding(top = 1.dp))
        BasicText(line, style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text2.color))
    }
}

private fun idleCacheLine(cold: PromptCacheStatus.Cold): AnnotatedString = buildAnnotatedString {
    // `tabular-nums` on the figures.
    val figure = SpanStyle(fontFeatureSettings = "tnum")
    val idle = PromptCacheStatus.idle(cold.idleMs)
    if (cold.compacted) {
        append("The conversation was compacted ")
        withStyle(figure) { append(idle) }
        append(" ago, so the prompt cache no longer covers it and your next message will re-cache it.")
        return@buildAnnotatedString
    }
    append("Idle ")
    withStyle(figure) { append(idle) }
    append(". The prompt cache has likely expired, so your next message re-sends ")
    val context = cold.contextTokens
    if (context == null) {
        append("the whole transcript")
    } else {
        append("about ")
        withStyle(figure) { append(PromptCacheStatus.tokens(context)) }
        append(" tokens")
    }
    append(" at full price.")
}
