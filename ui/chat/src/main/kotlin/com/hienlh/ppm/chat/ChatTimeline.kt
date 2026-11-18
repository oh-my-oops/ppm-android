package com.hienlh.ppm.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.assistant.placeTimelineItems

/** One thing a host draws among a chat's messages by time (`ChatTimelineItem`). */
class ChatTimelineItem(
    val key: String,
    /** Epoch ms; see [placeTimelineItems] for where it lands. */
    val at: Long,
    /** Drawn even when older history is not loaded yet. */
    val keep: Boolean = false,
    val content: @Composable () -> Unit,
)

/** What [ChatTimeline.build] answers for the rows the list draws. */
class ChatTimelineBuild(val items: List<ChatTimelineItem>, val hidden: Set<String> = emptySet())

/**
 * What a host may add to a chat's message list without a list of its own (`ChatTimelineExtension`):
 * items placed among the messages by time (the Assistant's cards), rows it hides (a project
 * filter, a folded turn), and history paging that waits to be asked. A chat without one draws
 * exactly as before.
 */
class ChatTimeline(
    /** Worked out from the chat's messages; called again whenever they change. */
    val build: (rows: List<ChatItem>) -> ChatTimelineBuild,
    /** Older history loads only from "Load earlier", never by scrolling to the top. */
    val manualPaging: Boolean = false,
    /** The host has older items of its own to load. */
    val hasEarlier: Boolean = false,
    /** Called with every "Load earlier", beside the chat's own older page. */
    val onLoadEarlier: () -> Unit = {},
)

const val LOAD_EARLIER_LABEL = "Load earlier"

/** The items before each visible row's index, those after every row, from [built]. */
internal class PlacedTimeline(val before: Map<Int, List<ChatTimelineItem>>, val trailing: List<ChatTimelineItem>)

internal fun placeTimeline(rows: List<ChatItem>, built: ChatTimelineBuild?, olderHidden: Boolean): PlacedTimeline? {
    if (built == null || built.items.isEmpty()) return null
    val placed = placeTimelineItems(rows, built.items, { it.at }, olderHidden) { it.keep }
    return PlacedTimeline(placed.before, placed.trailing)
}

/** "Load earlier" at the top of a list whose paging waits to be asked. */
@Composable
internal fun LoadEarlierButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    Box(modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
        BasicText(
            LOAD_EARLIER_LABEL,
            Modifier
                .testTag("chat-load-earlier")
                .cssBorder(tokens.border.color, RoundedCornerShape(999.dp))
                .ppmClickable(shape = RoundedCornerShape(999.dp), onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text2.color),
        )
    }
}
