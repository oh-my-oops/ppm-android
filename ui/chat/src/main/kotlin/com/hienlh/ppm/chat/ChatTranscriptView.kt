package com.hienlh.ppm.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalImageViewer
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.ApprovalRequest
import com.hienlh.ppm.protocol.ReplyReference
import com.hienlh.ppm.protocol.SessionPhase
import com.hienlh.ppm.state.ChatBlock
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.ChatState
import com.hienlh.ppm.state.MessageReply
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * A chat's messages, the turn streaming in, and what it waits on (`MessageList` in
 * message-list.tsx): kept on the newest while it is at the end, and left where the user put it
 * once they scroll away ([TranscriptScroll]).
 */
@Composable
fun ChatTranscriptView(
    state: ChatState,
    onRespond: (ApprovalRequest, approved: Boolean, data: JsonElement?) -> Unit,
    /** What is above the shown messages: an older page, or the conversation before a compaction. */
    onLoadEarlier: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
    /** Alt+↑ (-1) and Alt+↓ (1), for this chat only while its tab has the keyboard. */
    jumps: Flow<Int> = emptyFlow(),
    /** Under the welcome while the chat has no message: the project's chats. */
    welcome: @Composable () -> Unit = {},
    /** One message out of this view (an error's Dismiss), by id. */
    onDismiss: (String) -> Unit = {},
    /** Every error out of this view. */
    onClearErrors: () -> Unit = {},
    /** A message picked to answer; null leaves Reply out. */
    onReply: ((ReplyReference) -> Unit)? = null,
) {
    Box(modifier.fillMaxSize()) {
        when {
            !state.hasLoadedHistory && state.items.isEmpty() && state.historyError == null ->
                CenteredNotice("Loading messages...", pulsing = true)
            state.items.isEmpty() && state.live.isNullOrEmpty() && !state.isRunning && state.pendingApproval == null ->
                Welcome(welcome)
            else -> TranscriptList(state, onRespond, onLoadEarlier, jumps, onDismiss, onClearErrors, onReply)
        }
        state.historyError?.let { error ->
            HistoryErrorBanner(error, onReload, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        }
    }
}

@Composable
private fun TranscriptList(
    state: ChatState,
    onRespond: (ApprovalRequest, Boolean, JsonElement?) -> Unit,
    onLoadEarlier: () -> Unit,
    jumps: Flow<Int>,
    onDismiss: (String) -> Unit,
    onClearErrors: () -> Unit,
    onReply: ((ReplyReference) -> Unit)?,
) {
    val rows = remember(state.items, state.isRunning) { TranscriptRows.of(state.items, state.isRunning) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val tokens = LocalPpmTokens.current
    val live = state.live
    // Top down, as the web's: the sentinel while there is more above, the skeleton while it loads,
    // the messages oldest first, the turn streaming in, and the approval or the thinking line
    // while there is one.
    val head = (if (state.hasEarlier) 1 else 0) + (if (state.loadingEarlier) 1 else 0)
    val trailing = state.pendingApproval != null || indicatorLabel(state) != null
    val count = head + rows.size + (if (!live.isNullOrEmpty()) 1 else 0) + (if (trailing) 1 else 0)
    // Opened at its end, so the first frame is already the newest, and the sentinel, which loads
    // what is above once it is near the screen, is not passed on the way there.
    val list = rememberLazyListState((count - 1).coerceAtLeast(0), OPEN_AT_END)
    val scroll = rememberTranscriptScroll(list)
    val oldest = rows.firstOrNull()?.item?.id
    SideEffect {
        scroll.keepPlace(Triple(state.hasEarlier, state.loadingEarlier, oldest)) { key ->
            rows.indexOfFirst { it.item.id == key }.let { if (it < 0) -1 else head + it }
        }
    }
    // `topSentinelRef`: what is above loads once the sentinel is near the screen, while nothing
    // loads and nothing has failed. The web starts 400 px early; only what is on screen is measured
    // here, so that is the sentinel on it, or the message under it less than 400 px above it.
    val load by rememberUpdatedState(onLoadEarlier)
    val early = with(density) { 400.dp.toPx() }
    LaunchedEffect(list, state.hasEarlier, state.loadingEarlier, state.earlierError, rows.size) {
        if (!state.hasEarlier || state.loadingEarlier || state.earlierError != null) return@LaunchedEffect
        snapshotFlow { list.nearTop(early) }.collect { near -> if (near) load() }
    }
    // The message a reply card jumped to, lit for 1.4 s (`showMessageAt`).
    var flashing by remember { mutableStateOf<String?>(null) }
    var flashJob by remember { mutableStateOf<Job?>(null) }
    val replies = remember(rows, state.sessionId, state.providerId, head) {
        val found = HashMap<ReplyReference, ChatItem?>()
        ReplyTargets { reply ->
            val target = if (reply in found) found[reply] else MessageReply.resolve(reply, state.items, state.sessionId, state.providerId).also { found[reply] = it }
            val row = target?.let { rows.indexOfFirst { it.item.id == target.id } } ?: -1
            if (target == null || row < 0) return@ReplyTargets null
            val jump: () -> Unit = {
                flashJob?.cancel()
                flashing = target.id
                flashJob = scope.launch {
                    delay(1_400)
                    flashing = null
                }
                scope.launch {
                    scroll.release()
                    list.reveal(head + row, with(density) { 24.dp.toPx() })
                }
            }
            jump
        }
    }
    // The user's messages as items of that list, oldest first.
    val userItems = remember(rows, head) { rows.indices.filter { rows[it].item.role == ChatItem.Role.USER }.map { head + it } }
    val nav by remember(userItems, density) { derivedStateOf { list.messageNav(userItems, density) } }
    // Which cards are open, and the pictures a tap opens the lightbox among: the whole chat's, as
    // the web's transcript has every message mounted, the lazy list only those on screen.
    val openCards = remember { mutableStateMapOf<String, Boolean>() }
    val viewer = LocalImageViewer.current
    val uploads = LocalChatImages.current
    val toolImages = LocalToolImages.current
    val items by rememberUpdatedState(state.items)
    val streaming by rememberUpdatedState(live)
    val gallery = remember(viewer, uploads, toolImages) {
        ChatGallery { self ->
            val images = galleryRefs(items, streaming) { tool -> tool.toolUseId?.let { openCards[it] } ?: true }
                .map { galleryImage(it, uploads, toolImages) }
            val index = images.indexOfFirst { it.key == self.key }
            // The picture tapped is the one already in hand.
            if (index < 0) viewer?.show(listOf(self), 0) else viewer?.show(images.toMutableList().also { it[index] = self }, index)
        }
    }
    CompositionLocalProvider(LocalOpenCards provides openCards, LocalChatGallery provides gallery) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                TaskTracker(state.tasks)
                LazyColumn(Modifier.weight(1f).fillMaxWidth().nestedScroll(scroll.connection), state = list) {
                    if (state.hasEarlier) item(key = SENTINEL) { Spacer(Modifier.fillMaxWidth().height(1.dp)) }
                    if (state.loadingEarlier) item(key = SKELETON) { PreCompactSkeleton() }
                    for (row in rows) {
                        item(key = row.item.id, contentType = row.item.role) {
                            val reply = remember(row, state.sessionId, state.providerId, onReply) { replyAction(row, state, onReply) }
                            val place = Modifier.replyFlash(flashing == row.item.id, tokens.accent).padding(start = 16.dp, end = 16.dp, top = 16.dp)
                            val compaction = row.item.compaction
                            val message: @Composable (Modifier) -> Unit = { modifier ->
                                ChatMessageView(
                                    row.item,
                                    row.actions?.copy(onReply = reply),
                                    modifier,
                                    replies,
                                    onReply = reply.takeIf { row.item.role == ChatItem.Role.USER },
                                ) { onDismiss(row.item.id) }
                            }
                            // Above the summary: the rule marks where the conversation was cut.
                            if (compaction == null) {
                                message(place)
                            } else {
                                Column(place.fillMaxWidth()) {
                                    CompactionDivider(compaction)
                                    message(Modifier)
                                }
                            }
                        }
                    }
                    if (!live.isNullOrEmpty()) {
                        item(key = "live") {
                            AssistantBlocks(live, live = state.isRunning, actions = null, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
                        }
                    }
                    if (trailing) {
                        item(key = "trailing") { Trailing(state, onRespond) }
                    }
                }
            }
            ScrollNavigator(
                nav,
                goUp = { scope.launch { list.toPreviousMessage(userItems, density, scroll::release) } },
                goDown = { scope.launch { list.toNextMessage(userItems, density, scroll::toEnd) } },
                goBottom = { scope.launch { scroll.toEnd() } },
                jumps = jumps,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 12.dp),
            )
            // Both over the top of the chat, the tracker too; the errors button above the other.
            val earlierError = state.earlierError
            if (earlierError != null && !state.loadingEarlier) {
                EarlierErrorPill(earlierError, onLoadEarlier, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            }
            val errors = rows.count { it.item.role == ChatItem.Role.SYSTEM }
            if (errors > 1) ClearErrorsButton(errors, onClearErrors, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        }
    }
}

/** Tailwind's `shadow-md`, both layers. */
private val shadowMd = listOf(
    ThemeShadow(0.0, 4.0, 6.0, -1.0, Rgba(0.0, 0.0, 0.0, 0.1)),
    ThemeShadow(0.0, 2.0, 4.0, -2.0, Rgba(0.0, 0.0, 0.0, 0.1)),
)

/**
 * "Clear all errors (n)" over the top of the transcript while it shows more than one error
 * (`rounded-full bg-error/15 border-error/25 px-3 py-1 text-xs`, `hover:bg-error/25` at once).
 * The web blurs what is behind it too (`backdrop-blur-sm`), which this leaves out.
 */
@Composable
private fun ClearErrorsButton(count: Int, onClick: () -> Unit, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(instant = true)
    // A finger lights it as a mouse over it does.
    val pressed by hover.source.collectIsPressedAsState()
    var pill = modifier
    for (shadow in shadowMd) pill = pill.ppmShadow(shadow, CircleShape)
    Row(
        pill
            .clip(CircleShape)
            .background(tokens.error.opacity(if (pressed || hover.lit > 0f) 0.25 else 0.15).color)
            .ppmClickable(pressedColor = Color.Transparent, source = hover.source, onClick = onClick)
            .cssBorder(tokens.error.opacity(0.25).color, CircleShape)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(ProductIcon.XCircle, 14.dp, tokens.error.color)
        BasicText("Clear all errors ($count)", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color))
    }
}

/** A row a reply card jumped to: `bg-primary/10 ring-1 ring-primary/40`, the ring outside it. */
private fun Modifier.replyFlash(on: Boolean, accent: Rgba): Modifier = if (!on) this else drawBehind {
    drawRect(accent.opacity(0.1).color)
    val ring = 1.dp.toPx()
    drawRect(accent.opacity(0.4).color, Offset(-ring / 2, -ring / 2), Size(size.width + ring, size.height + ring), style = Stroke(ring))
}

/** The keys of what sits above the messages while it is there: the 1 px sentinel, then the skeleton. */
private const val SENTINEL = "earlier"
private const val SKELETON = "earlier-skeleton"
private val HEAD_KEYS = setOf(SENTINEL, SKELETON)

/** Where the list starts before it is measured: far past its last item, which it then clamps to its end. */
private const val OPEN_AT_END = 100_000_000

/** `STICK_TO_BOTTOM_OFFSET_PX`: this near the end, moving down takes hold of it again. */
private val NEAR_END = 70.dp

/**
 * The transcript's scrolling as message-list.tsx has it: `useStickToBottom` with `initial` and
 * `resize` both "instant", over a list with `[overflow-anchor:none]`. The list is laid out top
 * down, so whatever grows moves only what is under it. While the chat holds its end it follows
 * the end, a frame later, as the web's ResizeObserver does, and stops 1 px short of the last
 * pixel, as `targetScrollTop` does. Moving it up lets go; coming within 70 px of the end on the
 * way down, or the content shrinking there, takes hold again.
 */
@Stable
private class TranscriptScroll(
    private val list: LazyListState,
    private val held: MutableState<Boolean>,
    private val short: Int,
    private val near: Int,
) {
    // The first item on screen and its offset at the last layout, and how far the end was below the screen.
    private var top: Any? = null
    private var topOffset = 0
    private var remaining: Int? = null
    private var head: Any? = null

    /** Scrolling up, by finger, fling or wheel, lets go of the end at once. */
    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (consumed.y > 0f) held.value = false
            return Offset.Zero
        }
    }

    /** `stopScroll`. */
    fun release() {
        held.value = false
    }

    /** `scrollToBottom`. */
    suspend fun toEnd() {
        held.value = true
        list.scrollToEnd(short)
    }

    /** Watches every layout of the list, for as long as it is shown. */
    suspend fun follow() {
        snapshotFlow { list.layoutInfo }.collect { info ->
            val first = info.visibleItemsInfo.firstOrNull()
            // A list with no height is a hidden tab: nothing it says is about the chat.
            if (first == null || info.viewportSize.height <= 0) return@collect
            val last = info.visibleItemsInfo.last()
            // How far the end is below the screen, null while it is past the items on screen.
            val now = if (last.index == info.totalItemsCount - 1) last.offset + last.size - info.viewportEndOffset else null
            val opened = top == null
            // Nothing scrolled, so what moved the end was the content, or the screen, changing size.
            val resized = first.key == top && first.offset == topOffset
            val before = remaining
            top = first.key
            topOffset = first.offset
            remaining = now
            if (held.value) {
                if (opened || now == null || (resized && before != null && now > before && now > short)) list.scrollToEnd(short)
            } else if (now != null && now - short <= near && (before == null || now < before)) {
                held.value = true
            }
        }
    }

    /**
     * `preserveFromBottomRef`: what comes in above the messages — a page, an earlier conversation,
     * the skeleton standing in for it — leaves the view where it was. The list keeps its first item
     * in place by itself; only when that is the sentinel or the skeleton, which stay first, does the
     * message under them take their place. Called with what makes up the [head] before the list
     * measures it.
     */
    fun keepPlace(head: Any, indexOf: (Any) -> Int) {
        val before = this.head
        this.head = head
        if (before == null || before == head) return
        val visible = list.layoutInfo.visibleItemsInfo
        if (visible.firstOrNull()?.key !in HEAD_KEYS) return
        val kept = visible.firstOrNull { it.key !in HEAD_KEYS } ?: return
        val index = indexOf(kept.key)
        if (index >= 0) list.requestScrollToItem(index, -kept.offset)
    }
}

/** A chat's [TranscriptScroll], holding its end as it opens, and after the activity is recreated if it did then. */
@Composable
private fun rememberTranscriptScroll(list: LazyListState): TranscriptScroll {
    val density = LocalDensity.current
    val held = rememberSaveable { mutableStateOf(true) }
    val scroll = remember(list, density) { with(density) { TranscriptScroll(list, held, 1.dp.roundToPx(), NEAR_END.roundToPx()) } }
    LaunchedEffect(scroll) { scroll.follow() }
    return scroll
}

/** The sentinel on screen, or the message under it less than [early] px above the top of it. */
private fun LazyListState.nearTop(early: Float): Boolean {
    val info = layoutInfo
    val first = info.visibleItemsInfo.firstOrNull() ?: return false
    // A list with no height is a hidden tab.
    if (info.viewportSize.height <= 0) return false
    return first.index == 0 || (first.index == 1 && -first.offset <= early)
}

/** To the end of the list, [short] px before its last pixel. */
private suspend fun LazyListState.scrollToEnd(short: Int) {
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    if (layoutInfo.visibleItemsInfo.lastOrNull()?.index != last) scrollToItem(last)
    val end = layoutInfo.visibleItemsInfo.lastOrNull()?.takeIf { it.index == last } ?: return
    val remaining = end.offset + end.size - layoutInfo.viewportEndOffset
    if (remaining != short) scrollBy((remaining - short).toFloat())
}

/** `showMessageAt`: item [index] scrolled smoothly to [gap] px below the top of the screen. */
private suspend fun LazyListState.reveal(index: Int, gap: Float) {
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) scrollToItem(index)
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    animateScrollBy(item.offset - gap)
}

/**
 * Where each of [items] (oldest first) starts against the top of the screen, as `ChatScrollNav`
 * measures its rows: px below it, negative above it, infinite off screen.
 */
private fun LazyListState.topsOf(items: List<Int>): List<Float> {
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return items.map { Float.POSITIVE_INFINITY }
    val first = visible.first().index
    val last = visible.last().index
    return items.map { index ->
        when {
            index < first -> Float.NEGATIVE_INFINITY
            index > last -> Float.POSITIVE_INFINITY
            else -> visible.first { it.index == index }.offset.toFloat()
        }
    }
}

/** What the navigator shows (`NavState`): read off the scroll position, so a swipe moves it as a jump does. */
private data class MessageNav(val hasAbove: Boolean, val atBottom: Boolean, val position: Int, val total: Int)

private fun LazyListState.messageNav(items: List<Int>, density: Density): MessageNav = with(density) {
    val tops = topsOf(items)
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()
    val toEnd = if (last != null && last.index == info.totalItemsCount - 1) last.offset + last.size - info.viewportEndOffset else Int.MAX_VALUE
    MessageNav(
        hasAbove = tops.any { it < -4.dp.toPx() },
        atBottom = toEnd < 8.dp.toPx(),
        // 1-based: the last of the user's messages at or above the top of the screen.
        position = tops.count { it <= 12.dp.toPx() },
        total = tops.size,
    )
}

/** `goUp`: the last of the user's messages that starts above the screen, put at its top; moving up lets go of the end. */
private suspend fun LazyListState.toPreviousMessage(items: List<Int>, density: Density, release: () -> Unit) {
    val tops = topsOf(items)
    val slack = with(density) { 4.dp.toPx() }
    val target = tops.indices.lastOrNull { tops[it] < -slack } ?: return
    release()
    scrollToItem(items[target])
}

/** `goDown`: the first that starts below the top of the screen, else the end of the chat. */
private suspend fun LazyListState.toNextMessage(items: List<Int>, density: Density, toEnd: suspend () -> Unit) {
    val tops = topsOf(items)
    val slack = with(density) { 4.dp.toPx() }
    val target = tops.indices.firstOrNull { tops[it] > slack }
    if (target != null) scrollToItem(items[target]) else toEnd()
}

/** The approval or question the AI waits on, and the thinking line: shown only while there is one (`hasTrailing`). */
@Composable
private fun Trailing(state: ChatState, onRespond: (ApprovalRequest, Boolean, JsonElement?) -> Unit) {
    val indicator = indicatorLabel(state)
    val request = state.pendingApproval
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (request != null) {
            key(request.requestId) {
                PendingApprovalCard(request, { approved, data -> onRespond(request, approved, data) })
            }
        }
        if (indicator != null) {
            ThinkingIndicator(
                indicator,
                elapsed = state.connectingElapsed,
                waiting = state.live.isNullOrEmpty(),
                connecting = state.phase == SessionPhase.CONNECTING,
            )
        }
    }
}

/** Shown while nothing streams — before the first token, while a tool's result is read — and over text while PPM says what it is doing. */
internal fun indicatorLabel(state: ChatState): String? {
    if (!state.isRunning) return null
    val status = if (state.compacting) "Compacting messages..." else state.statusMessage
    val live = state.live
    val waiting = live.isNullOrEmpty()
    val afterTool = (live?.lastOrNull() as? ChatBlock.Tool)?.tool?.result != null
    if (status == null && !waiting && !afterTool) return null
    return status ?: when (state.phase) {
        SessionPhase.INITIALIZING -> "Initializing"
        SessionPhase.CONNECTING -> "Connecting"
        SessionPhase.THINKING -> "Thinking"
        else -> "Processing"
    }
}

/**
 * Each message with whether it ends a turn, that turn's text for Copy, and what its Reply quotes:
 * a user's message itself, and for an answer the turn's last message with text. A turn is the
 * run of answers since anything else.
 */
internal object TranscriptRows {
    data class Row(val item: ChatItem, val actions: MessageActions?, val replyTarget: ChatItem? = null)

    fun of(items: List<ChatItem>, running: Boolean): List<Row> {
        val rows = ArrayList<Row>(items.size)
        val turnTexts = mutableListOf<String>()
        var turnReply: ChatItem? = null
        items.forEachIndexed { index, item ->
            if (item.role != ChatItem.Role.ASSISTANT) {
                turnTexts.clear()
                turnReply = null
            } else if (item.text.isNotEmpty()) {
                turnTexts += item.text
                turnReply = item
            }
            val next = items.getOrNull(index + 1)
            // The running turn's actions wait until it ends.
            val endsTurn = item.role == ChatItem.Role.ASSISTANT && next?.role != ChatItem.Role.ASSISTANT && !(running && next == null)
            rows += when {
                item.role == ChatItem.Role.USER -> Row(item, null, item)
                endsTurn -> Row(item, MessageActions(item.timestamp, turnTexts.joinToString("\n\n")), turnReply)
                else -> Row(item, null)
            }
        }
        return rows
    }
}

/**
 * Reply on [row] (`handleReply`): shown on a user's message only when it can be quoted, on an
 * answer whenever its turn has text, and only in a chat with a session and a provider.
 */
private fun replyAction(row: TranscriptRows.Row, state: ChatState, onReply: ((ReplyReference) -> Unit)?): (() -> Unit)? {
    val target = row.replyTarget ?: return null
    val provider = state.providerId?.takeIf { it.isNotEmpty() } ?: return null
    if (onReply == null || state.sessionId.isEmpty()) return null
    if (row.item.role == ChatItem.Role.USER) {
        val reply = MessageReply.create(target, state.sessionId, provider) ?: return null
        return { onReply(reply) }
    }
    return { MessageReply.create(target, state.sessionId, provider)?.let(onReply) }
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

/**
 * An empty chat (`chat-welcome.tsx`): the robot and "Send a message…" over [below], `gap-6`,
 * centred together. Taller than the pane, they scroll from their top, where the web's
 * `justify-center` would push the top out of reach.
 */
@Composable
private fun Welcome(below: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = viewport),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PpmIcon(ProductIcon.Bot, 40.dp, tokens.text3.color)
                    BasicText(
                        "Send a message to start a new conversation",
                        Modifier.padding(top = 12.dp),
                        ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color),
                    )
                }
                below()
            }
        }
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
            // `hover:bg-surface-hover`, at once.
            .ppmClickable(pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color, fade = false, onClick = retry)
            .cssBorder(tokens.border.color, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color)
        PpmIcon(ProductIcon.AlertCircle, 14.dp, tokens.error.color)
        BasicText("Could not load messages: $message", Modifier.weight(1f, fill = false), style, maxLines = 2, overflow = TextOverflow.Ellipsis)
        BasicText("Retry", style = style.copy(textDecoration = TextDecoration.Underline))
    }
}

/** Folding away after it opens, and after the last jump. */
private const val AUTO_COLLAPSE_MS = 3_200L

/** Shorter, when a shortcut opens it only to show where it went. */
private const val KEYBOARD_PEEK_MS = 1_000L

/** Tailwind's `shadow-lg`. */
private val SHADOW_LG = listOf(
    ThemeShadow(0.0, 10.0, 15.0, -3.0, Rgba(0.0, 0.0, 0.0, 0.1)),
    ThemeShadow(0.0, 4.0, 6.0, -4.0, Rgba(0.0, 0.0, 0.0, 0.1)),
)

/**
 * The bottom-right puck between the user's own messages (`ChatScrollNav`): a tap opens previous ·
 * position · next · latest, which folds away 3.2 s after the last use. Alt+↑ / Alt+↓ jump the
 * same way and show it for a second.
 */
@Composable
private fun ScrollNavigator(
    nav: MessageNav,
    goUp: () -> Unit,
    goDown: () -> Unit,
    goBottom: () -> Unit,
    jumps: Flow<Int>,
    modifier: Modifier,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var open by remember { mutableStateOf(false) }
    var collapseMs by remember { mutableLongStateOf(AUTO_COLLAPSE_MS) }
    // Bumped on every jump, so that the fold waits again even where the position stays.
    var jumpTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(open, nav.position, jumpTick) {
        if (!open) return@LaunchedEffect
        delay(collapseMs)
        open = false
    }
    fun jumped() {
        collapseMs = AUTO_COLLAPSE_MS
        jumpTick++
    }
    val up by rememberUpdatedState(goUp)
    val down by rememberUpdatedState(goDown)
    LaunchedEffect(jumps) {
        jumps.collect { direction ->
            if (direction < 0) up() else down()
            jumped()
            collapseMs = KEYBOARD_PEEK_MS
            open = true
        }
    }

    Box(modifier) {
        if (open) {
            val shape = RoundedCornerShape(12.dp)
            Column(
                SHADOW_LG.fold(Modifier as Modifier) { m, shadow -> m.ppmShadow(shadow, shape) }
                    .background(tokens.panel2.opacity(0.95).color, shape)
                    .cssBorder(tokens.border.color, shape)
                    .padding(3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val rowHeight = if (mobile) 36.dp else 30.dp
                NavRow(ProductIcon.ChevronUp, 15, rowHeight, enabled = nav.hasAbove, label = "Jump to previous message") {
                    goUp()
                    jumped()
                }
                // `text-[10px]` under preflight's `line-height: 1.5`.
                BasicText(
                    if (nav.total == 0) "0/0" else "${nav.position.coerceAtLeast(1)}/${nav.total}",
                    Modifier.padding(vertical = 1.dp),
                    ppmText(10f, FontWeight.Medium, FontKind.MONO, lineHeight = 15f).copy(color = tokens.text3.color),
                )
                NavRow(ProductIcon.ChevronDown, 15, rowHeight, enabled = !nav.atBottom, label = "Jump to next message") {
                    goDown()
                    jumped()
                }
                NavRow(ProductIcon.ChevronsDown, 13, rowHeight, enabled = !nav.atBottom, label = "Jump to latest") {
                    goBottom()
                    jumped()
                }
            }
        } else {
            val hover = remember { MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            val lit = colorsFade(hovered).toDouble()
            // A 44px target around a 36px puck on a phone, as the web sizes it.
            Box(
                Modifier
                    .size(if (mobile) 44.dp else 30.dp)
                    .hoverable(hover)
                    .ppmClickable(pressedColor = Color.Transparent, shape = CircleShape) {
                        collapseMs = AUTO_COLLAPSE_MS
                        open = true
                    }
                    .semantics { contentDescription = "Navigate messages" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(if (mobile) 36.dp else 30.dp)
                        .background(tokens.panel2.mix(tokens.panel2.opacity(0.55), lit).color, CircleShape)
                        .cssBorder(tokens.border.color, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.ChevronsUpDown, 15.dp, tokens.text.mix(tokens.text3, lit).color)
                }
            }
        }
    }
}

/** `w-[34px] h-[30px] rounded-[9px]` (36 px tall on a phone), the surface and the text colour under a pointer. */
@Composable
private fun NavRow(icon: ProductIcon, size: Int, height: Dp, enabled: Boolean, label: String, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val lit = colorsFade(hovered && enabled).toDouble()
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier
            .size(width = 34.dp, height = height)
            .alpha(if (enabled) 1f else 0.3f)
            .background(tokens.panel2.opacity(lit).color, shape)
            .hoverable(hover, enabled)
            .ppmClickable(enabled = enabled, pressedColor = tokens.panel2.color, shape = shape, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, size.dp, tokens.text.mix(tokens.text2, lit).color)
    }
}
