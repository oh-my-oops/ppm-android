package com.hienlh.ppm.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ApprovalRequest
import com.hienlh.ppm.protocol.SessionPhase
import com.hienlh.ppm.state.ChatBlock
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.ChatState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
    /** Alt+↑ (-1) and Alt+↓ (1), for this chat only while its tab has the keyboard. */
    jumps: Flow<Int> = emptyFlow(),
    /** Under the welcome while the chat has no message: the project's chats. */
    welcome: @Composable () -> Unit = {},
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
                Welcome(welcome)
            else -> TranscriptList(state, turnStartedAt, onRespond, onLoadOlder, jumps)
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
    jumps: Flow<Int>,
) {
    val rows = remember(state.items, state.isRunning) { TranscriptRows.of(state.items, state.isRunning) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val live = state.live
    // Laid out bottom up: index 0 is the newest thing on screen.
    val tail = 1 + if (!live.isNullOrEmpty()) 1 else 0
    // The user's messages as items of that list, oldest first.
    val userItems = remember(rows, tail) { rows.indices.filter { rows[it].item.role == ChatItem.Role.USER }.map { tail + rows.lastIndex - it } }
    val nav by remember(userItems, density) { derivedStateOf { list.messageNav(userItems, density) } }
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
        ScrollNavigator(
            nav,
            goUp = { scope.launch { list.toPreviousMessage(userItems, density) } },
            goDown = { scope.launch { list.toNextMessage(userItems, density) } },
            goBottom = { scope.launch { list.scrollToItem(0) } },
            jumps = jumps,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 12.dp),
        )
    }
}

/** Puts item [index] of a bottom-up list at the top of the screen, as `scrollTo({ top })` does: its top, if it is taller. */
private suspend fun LazyListState.scrollToTop(index: Int) {
    scrollToItem(index)
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    scrollBy((item.size - layoutInfo.viewportSize.height).toFloat())
}

/**
 * Where each of [items] (oldest first) starts against the top of the screen, as `ChatScrollNav`
 * measures its rows: px below it, negative above it, infinite off screen. A bottom-up list counts
 * an item's offset from the bottom edge.
 */
private fun LazyListState.topsOf(items: List<Int>): List<Float> {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return items.map { Float.POSITIVE_INFINITY }
    val newest = visible.minOf { it.index }
    val oldest = visible.maxOf { it.index }
    return items.map { index ->
        when {
            index > oldest -> Float.NEGATIVE_INFINITY
            index < newest -> Float.POSITIVE_INFINITY
            else -> visible.first { it.index == index }.let { (info.viewportEndOffset - it.offset - it.size).toFloat() }
        }
    }
}

/** What the navigator shows (`NavState`): read off the scroll position, so a swipe moves it as a jump does. */
private data class MessageNav(val hasAbove: Boolean, val atBottom: Boolean, val position: Int, val total: Int)

private fun LazyListState.messageNav(items: List<Int>, density: Density): MessageNav = with(density) {
    val tops = topsOf(items)
    MessageNav(
        hasAbove = tops.any { it < -4.dp.toPx() },
        atBottom = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset < 8.dp.toPx(),
        // 1-based: the last of the user's messages at or above the top of the screen.
        position = tops.count { it <= 12.dp.toPx() },
        total = tops.size,
    )
}

/** `goUp`: the last of the user's messages that starts above the screen, put at its top. */
private suspend fun LazyListState.toPreviousMessage(items: List<Int>, density: Density) {
    val tops = topsOf(items)
    val slack = with(density) { 4.dp.toPx() }
    val target = tops.indices.lastOrNull { tops[it] < -slack } ?: return
    scrollToTop(items[target])
}

/** `goDown`: the first that starts below the top of the screen, else the end of the chat. */
private suspend fun LazyListState.toNextMessage(items: List<Int>, density: Density) {
    val tops = topsOf(items)
    val slack = with(density) { 4.dp.toPx() }
    val target = tops.indices.firstOrNull { tops[it] > slack }
    if (target != null) scrollToTop(items[target]) else scrollToItem(0)
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
            .border(1.dp, tokens.border.color, shape)
            // `hover:bg-surface-hover`, at once.
            .ppmClickable(pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color, fade = false, onClick = retry)
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
                    .border(1.dp, tokens.border.color, shape)
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val rowHeight = if (mobile) 36.dp else 30.dp
                NavRow(ProductIcon.ChevronUp, 15, rowHeight, enabled = nav.hasAbove, label = "Jump to previous message") {
                    goUp()
                    jumped()
                }
                BasicText(
                    if (nav.total == 0) "0/0" else "${nav.position.coerceAtLeast(1)}/${nav.total}",
                    Modifier.padding(vertical = 1.dp),
                    ppmText(10f, FontWeight.Medium, FontKind.MONO).copy(color = tokens.text3.color),
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
                        .border(1.dp, tokens.border.color, CircleShape),
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
