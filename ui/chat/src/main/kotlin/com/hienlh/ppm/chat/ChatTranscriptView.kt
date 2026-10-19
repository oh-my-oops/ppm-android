package com.hienlh.ppm.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ApprovalRequest
import com.hienlh.ppm.protocol.SessionPhase
import com.hienlh.ppm.state.ChatBlock
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.ChatState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * A chat's messages, the turn streaming in, and what it waits on — kept on the newest
 * (`MessageList` in message-list.tsx). The list is laid out from the bottom, so a growing turn
 * stays in view and older pages arriving above move nothing.
 */
@Composable
fun ChatTranscriptView(
    state: ChatState,
    onRespond: (ApprovalRequest, approved: Boolean, data: JsonElement?) -> Unit,
    onLoadOlder: suspend () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // When the running turn started, for the wait counter before anything streams.
    var turnStartedAt by remember { mutableStateOf<Instant?>(null) }
    LaunchedEffect(state.isRunning) {
        turnStartedAt = if (state.isRunning) turnStartedAt ?: Instant.now() else null
    }
    Box(modifier.fillMaxSize()) {
        when {
            !state.hasLoadedHistory && state.items.isEmpty() && state.historyError == null ->
                CenteredNotice("Loading messages...", pulsing = true)
            state.items.isEmpty() && state.live.isNullOrEmpty() && !state.isRunning && state.pendingApproval == null ->
                CenteredNotice("Send a message to start a new conversation", pulsing = false)
            else -> TranscriptList(state, turnStartedAt, onRespond, onLoadOlder)
        }
        state.historyError?.let { error ->
            HistoryErrorBanner(error, onReload, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        }
    }
}

@Composable
private fun TranscriptList(
    state: ChatState,
    turnStartedAt: Instant?,
    onRespond: (ApprovalRequest, Boolean, JsonElement?) -> Unit,
    onLoadOlder: suspend () -> Unit,
) {
    val rows = remember(state.items, state.isRunning) { TranscriptRows.of(state.items, state.isRunning) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atBottom by remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 80 } }
    val live = state.live
    // Laid out bottom up: index 0 is the newest thing on screen.
    val tail = 1 + if (!live.isNullOrEmpty()) 1 else 0
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true, verticalArrangement = Arrangement.Top) {
            item(key = "trailing") { Trailing(state, turnStartedAt, onRespond) }
            if (!live.isNullOrEmpty()) {
                item(key = "live") {
                    AssistantBlocks(live, live = state.isRunning, actions = null, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
                }
            }
            for (index in rows.indices.reversed()) {
                val row = rows[index]
                item(key = row.item.id, contentType = row.item.role) {
                    ChatMessageView(row.item, row.actions, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
                }
            }
            if (state.historyStart > 0) {
                item(key = "older") { OlderMessagesRow(state.loadingOlder, state.historyStart, onLoadOlder) }
            }
        }
        val userRows = rows.indices.filter { rows[it].item.role == ChatItem.Role.USER }
        ScrollNavigator(
            count = userRows.size,
            atBottom = atBottom,
            jump = { position ->
                scope.launch {
                    if (position == null) list.animateScrollToItem(0) else list.scrollToTop(tail + rows.lastIndex - userRows[position])
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 12.dp),
        )
    }
}

/** Puts item [index] of a bottom-up list at the top of the screen, as `scrollIntoView` does. */
private suspend fun LazyListState.scrollToTop(index: Int) {
    scrollToItem(index)
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val room = layoutInfo.viewportSize.height - item.size
    if (room > 0) scrollBy(-room.toFloat())
}

/** The approval or question the AI waits on, and the thinking line. */
@Composable
private fun Trailing(state: ChatState, turnStartedAt: Instant?, onRespond: (ApprovalRequest, Boolean, JsonElement?) -> Unit) {
    val indicator = indicatorLabel(state)
    val request = state.pendingApproval
    if (request == null && indicator == null) {
        Spacer(Modifier.height(16.dp))
        return
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (request != null) {
            key(request.requestId) {
                PendingApprovalCard(request, { approved, data -> onRespond(request, approved, data) })
            }
        }
        if (indicator != null) {
            ThinkingIndicator(
                indicator,
                waitingSince = if (state.live.isNullOrEmpty()) turnStartedAt else null,
                connecting = state.phase == SessionPhase.CONNECTING,
            )
        }
    }
}

/** Shown while nothing streams: before the first token, and while a tool's result is read. */
internal fun indicatorLabel(state: ChatState): String? {
    if (!state.isRunning) return null
    if (state.compacting) return "Compacting messages..."
    val live = state.live
    val waiting = live.isNullOrEmpty()
    val afterTool = (live?.lastOrNull() as? ChatBlock.Tool)?.tool?.result != null
    if (!waiting && !afterTool) return null
    return when (state.phase) {
        SessionPhase.INITIALIZING -> "Initializing"
        SessionPhase.CONNECTING -> "Connecting"
        SessionPhase.THINKING -> "Thinking"
        else -> "Processing"
    }
}

/** Each message with whether it ends a turn, and that turn's text for Copy. */
internal object TranscriptRows {
    data class Row(val item: ChatItem, val actions: MessageActions?)

    fun of(items: List<ChatItem>, running: Boolean): List<Row> {
        val rows = ArrayList<Row>(items.size)
        val turnTexts = mutableListOf<String>()
        items.forEachIndexed { index, item ->
            if (item.role == ChatItem.Role.USER) turnTexts.clear()
            if (item.role == ChatItem.Role.ASSISTANT && item.text.isNotEmpty()) turnTexts += item.text
            val next = items.getOrNull(index + 1)
            // The running turn's actions wait until it ends.
            val endsTurn = item.role == ChatItem.Role.ASSISTANT && next?.role != ChatItem.Role.ASSISTANT && !(running && next == null)
            rows += Row(item, if (endsTurn) MessageActions(item.timestamp, turnTexts.joinToString("\n\n")) else null)
        }
        return rows
    }
}

/** The empty states: a bot and one line. */
@Composable
private fun CenteredNotice(text: String, pulsing: Boolean) {
    val tokens = LocalPpmTokens.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        var faded by remember { mutableStateOf(false) }
        if (pulsing) {
            LaunchedEffect(Unit) {
                while (true) {
                    delay(1_000)
                    faded = !faded
                }
            }
        }
        val alpha by animateFloatAsState(if (faded) 0.5f else 1f, tween(1_000), label = "pulse")
        PpmIcon(ProductIcon.Bot, 40.dp, tokens.text3.color, Modifier.alpha(alpha))
        BasicText(text, Modifier.padding(top = 12.dp), ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color))
    }
}

/** History could not be read; Retry reads it again. */
@Composable
private fun HistoryErrorBanner(message: String, retry: () -> Unit, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(tokens.panel.color)
            .border(1.dp, tokens.border.color, shape)
            .ppmClickable(pressedColor = tokens.surfaceHover.color, onClick = retry)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color)
        PpmIcon(ProductIcon.AlertCircle, 14.dp, tokens.error.color)
        BasicText("Could not load messages: $message", Modifier.weight(1f, fill = false), style, maxLines = 2, overflow = TextOverflow.Ellipsis)
        BasicText("Retry", style = style.copy(textDecoration = TextDecoration.Underline))
    }
}

/** Older messages exist above: they load when this row comes into view. */
@Composable
private fun OlderMessagesRow(loading: Boolean, historyStart: Int, load: suspend () -> Unit) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(historyStart) { load() }
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = ppmText(11f).copy(color = tokens.text3.color)
        if (loading) PpmIcon(ProductIcon.Loader2, 12.dp, tokens.text3.color, Modifier.padding(end = 6.dp), spinning = true)
        BasicText(
            if (loading) "Loading..." else "Load earlier messages",
            Modifier.ppmClickable(enabled = !loading, pressedColor = tokens.surfaceHover.color) { scope.launch { load() } },
            style,
        )
    }
}

/**
 * The bottom-right puck that opens previous · position · next · latest between the user's own
 * messages, folding away 3.2 s after the last use (`ChatScrollNav`). [jump] takes the 0-based
 * position among them, or null for the newest message.
 */
@Composable
private fun ScrollNavigator(count: Int, atBottom: Boolean, jump: (Int?) -> Unit, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var open by remember { mutableStateOf(false) }
    // 1-based: the user message last jumped to.
    var position by remember { mutableIntStateOf(0) }
    var collapse by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun scheduleCollapse() {
        collapse?.cancel()
        collapse = scope.launch {
            delay(3_200)
            open = false
        }
    }

    Box(modifier) {
        AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .background(tokens.panel2.opacity(0.95).color, shape)
                    .border(1.dp, tokens.border.color, shape)
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val rowHeight = if (mobile) 36.dp else 30.dp
                NavRow(ProductIcon.ChevronUp, 15, rowHeight, enabled = position > 1, label = "Jump to previous message") {
                    position = (position - 1).coerceAtLeast(1)
                    jump(position - 1)
                    scheduleCollapse()
                }
                BasicText(
                    if (count == 0) "0/0" else "${position.coerceAtLeast(1)}/$count",
                    Modifier.padding(vertical = 1.dp),
                    ppmText(10f, FontWeight.Medium, FontKind.MONO).copy(color = tokens.text3.color),
                )
                NavRow(ProductIcon.ChevronDown, 15, rowHeight, enabled = !atBottom, label = "Jump to next message") {
                    if (position < count) {
                        position++
                        jump(position - 1)
                    } else {
                        jump(null)
                    }
                    scheduleCollapse()
                }
                NavRow(ProductIcon.ChevronsDown, 13, rowHeight, enabled = !atBottom, label = "Jump to latest") {
                    position = count
                    jump(null)
                    scheduleCollapse()
                }
            }
        }
        if (!open) {
            // A 44px target around a 36px puck on a phone, as the web sizes it.
            Box(
                Modifier
                    .size(if (mobile) 44.dp else 30.dp)
                    .ppmClickable(pressedColor = Color.Transparent, shape = CircleShape) {
                        position = count
                        open = true
                        scheduleCollapse()
                    }
                    .semantics { contentDescription = "Navigate messages" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(if (mobile) 36.dp else 30.dp)
                        .background(tokens.panel2.opacity(0.55).color, CircleShape)
                        .border(1.dp, tokens.border.color, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.ChevronsUpDown, 15.dp, tokens.text3.color)
                }
            }
        }
    }
}

@Composable
private fun NavRow(icon: ProductIcon, size: Int, height: Dp, enabled: Boolean, label: String, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Box(
        Modifier
            .size(width = 34.dp, height = height)
            .alpha(if (enabled) 1f else 0.3f)
            .ppmClickable(enabled = enabled, pressedColor = tokens.panel2.color, shape = RoundedCornerShape(9.dp), onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, size.dp, tokens.text2.color)
    }
}
