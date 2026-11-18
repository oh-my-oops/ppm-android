package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.HistoryView
import com.hienlh.ppm.state.HistoryWindowController
import com.hienlh.ppm.state.LoadDirection
import kotlinx.coroutines.flow.distinctUntilChanged

/** A request to bring a turn of the window on screen; [nonce] makes the same turn asked twice land twice. */
internal data class WindowLanding(val turnId: String, val nonce: Int)

/**
 * The history window (`chat-history-window.tsx`): a block of a session's turns, scrolled on its
 * own, laid over the live list, which stays underneath, still streaming. Pages come in at either
 * end as the reader nears it. Messages here offer only Copy and Copy turn link: a window's ids
 * are not the live list's, so Reply, Edit and Fork are offered only there.
 */
@Composable
internal fun HistoryWindowOverlay(
    view: HistoryView.Window,
    controller: HistoryWindowController,
    landing: WindowLanding?,
    /** The live list now holds the window's last turn whole (`canMergeToLive`). */
    canMerge: Boolean,
    /** The live chat moved on since the jump: something streamed, or a message came. */
    liveActivity: Boolean,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    /** Hands the reader to the live list at the turn at the top of the window. */
    onMerge: (turnId: String?) -> Unit,
    onBackToLatest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val rows = remember(view.items) { TranscriptRows.of(view.items, running = false) }
    val list = rememberLazyListState()
    // Which turn each row belongs to: a compaction marker goes with the message before it.
    val rowTurns = remember(rows) {
        var current: String? = null
        rows.map { row -> (row.item.turnId ?: current).also { current = it } }
    }
    val currentRowTurns by rememberUpdatedState(rowTurns)
    LaunchedEffect(landing) {
        val target = landing ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.item.role == ChatItem.Role.USER && it.item.turnId == target.turnId }
            .takeIf { it >= 0 } ?: rowTurns.indexOf(target.turnId)
        if (index >= 0) list.scrollToItem(index)
    }
    val topTurn by remember { derivedStateOf { currentRowTurns.getOrNull(list.firstVisibleItemIndex) } }
    val currentView by rememberUpdatedState(view)
    val merge by rememberUpdatedState(canMerge)
    // Near the top: the page above. At the bottom: the live list once it holds what is shown here, else the page below.
    LaunchedEffect(list) {
        snapshotFlow {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            (list.firstVisibleItemIndex == 0) to (info.totalItemsCount > 0 && last >= info.totalItemsCount - 1)
        }.distinctUntilChanged().collect { (atTop, atBottom) ->
            val v = currentView
            if (atTop && v.hasOlder && v.loadError == null) controller.loadOlder()
            if (atBottom) {
                if (merge) onMerge(topTurn) else if (v.hasNewer && v.loadError == null) controller.loadNewer()
            }
        }
    }
    Box(modifier.fillMaxSize().background(tokens.bgSolid.color).testTag("history-window")) {
        LazyColumn(
            Modifier.fillMaxSize().semantics { contentDescription = "Earlier turns" },
            state = list,
            contentPadding = PaddingValues(bottom = 80.dp),
        ) {
            rows.forEachIndexed { index, row ->
                item(key = row.item.id, contentType = row.item.role) {
                    // Copy and Copy turn link only.
                    val actions = row.actions?.let { MessageActions(it.timestamp, it.copyText, key = it.key) }
                    ChatMessageView(
                        row.item,
                        actions,
                        Modifier.padding(start = horizontalPadding, end = horizontalPadding, top = 16.dp),
                    )
                }
            }
            if (view.loadingNewer) {
                item(key = "history-window-newer") { WindowSpinner("Loading later turns", floating = false) }
            }
        }
        if (view.loadingOlder) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) { WindowSpinner("Loading earlier turns", floating = true) }
        }
        view.loadError?.let { (direction, message) ->
            LoadErrorBanner(
                message,
                { controller.retry(direction) },
                if (direction == LoadDirection.OLDER) Modifier.align(Alignment.TopCenter).padding(top = 8.dp) else Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
            )
        }
        val n = topTurn?.let { id -> view.turns.firstOrNull { it.turnId == id }?.n }
        BackToLatestPill(n, liveActivity, onBackToLatest, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp, start = 16.dp, end = 16.dp))
    }
}

/** "Loading earlier turns…" over the top, or "Loading later turns…" under the last row. */
@Composable
private fun WindowSpinner(label: String, floating: Boolean) {
    val tokens = LocalPpmTokens.current
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PpmIcon(ProductIcon.Loader2, 12.dp, tokens.text2.color, spinning = true)
            BasicText("$label…", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
        }
    }
    if (floating) {
        Box(
            Modifier
                .ppmShadow(tokens.shadowFloat, CircleShape)
                .clip(CircleShape)
                .background(tokens.panel.opacity(0.95).color)
                .cssBorder(tokens.border.color, CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) { content() }
    } else {
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) { content() }
    }
}

/** "Could not load more turns: …  Retry", where the page that failed would have gone. */
@Composable
private fun LoadErrorBanner(message: String, onRetry: () -> Unit, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, shape)
            .ppmClickable(shape = shape, onClick = onRetry)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val xs = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color)
        PpmIcon(ProductIcon.AlertCircle, 14.dp, tokens.error.color)
        BasicText("Could not load more turns: $message", Modifier.weight(1f, fill = false), xs, maxLines = 2, overflow = TextOverflow.Ellipsis)
        BasicText("Retry", style = xs.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline))
    }
}

/** "Viewing turn N · Back to latest" (`BackToLatestPill`), with a dot once the live chat moved on. */
@Composable
private fun BackToLatestPill(turnN: Int?, liveActivity: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier
            .ppmShadow(tokens.shadowFloat, CircleShape)
            .clip(CircleShape)
            .background(tokens.panel.opacity(0.95).color)
            .cssBorder(tokens.border.color, CircleShape)
            .ppmClickable(pressedColor = tokens.panel2.color, shape = CircleShape, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp)
            .testTag("back-to-latest"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val sm = ppmText(14f, lineHeight = 20f)
        if (turnN != null) {
            BasicText("Viewing turn $turnN", style = sm.copy(color = tokens.text2.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText("·", style = sm.copy(color = tokens.text3.color))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PpmIcon(ProductIcon.ChevronsDown, 16.dp, tokens.text.color)
            BasicText("Back to latest", style = sm.copy(color = tokens.text.color, fontWeight = FontWeight.Medium), maxLines = 1)
        }
        if (liveActivity) {
            Spacer(Modifier.size(8.dp).clip(CircleShape).background(tokens.accent.color).semantics { contentDescription = "New activity" })
        }
    }
}
