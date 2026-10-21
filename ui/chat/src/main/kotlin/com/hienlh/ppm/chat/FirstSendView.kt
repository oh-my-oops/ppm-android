package com.hienlh.ppm.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmText
import java.time.Instant

/**
 * A new chat's first message while its session is made and connected (chat-tab.tsx's
 * `firstSendPending`), in place of the transcript: `p-4` in a scroll of its own, the message as
 * it was sent, then a `size-4` spinner and "Starting conversation..." (`gap-2 pt-3 text-sm
 * text-text-secondary`).
 */
@Composable
fun FirstSendView(content: String, timestamp: Instant, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        UserBubble(content, timestamp, ReplyTargets.None)
        Row(
            Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text2.color, spinning = true)
            BasicText("Starting conversation...", style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color))
        }
    }
}
