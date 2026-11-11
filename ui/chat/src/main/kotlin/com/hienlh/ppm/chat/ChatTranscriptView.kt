package com.hienlh.ppm.chat

import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
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
import com.hienlh.ppm.design.cssHeight
import com.hienlh.ppm.design.cssLazyItem
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.cssScrollbar
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberCssLazyStack
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.ApprovalRequest
import com.hienlh.ppm.protocol.ReplyReference
import com.hienlh.ppm.protocol.SessionPhase
import com.hienlh.ppm.protocol.VersionGroup
import com.hienlh.ppm.state.ChatBlock
import com.hienlh.ppm.state.ChatJumpRequest
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.ChatState
import com.hienlh.ppm.state.MessageReply
import com.hienlh.ppm.state.MessageVersions
import com.hienlh.ppm.state.PromptCacheStatus
import com.hienlh.ppm.state.SessionTurn
import com.hienlh.ppm.state.SessionTurns
import com.hienlh.ppm.state.SessionTurnsMemo
import com.hienlh.ppm.state.TurnFileChange
import com.hienlh.ppm.state.TurnFileChanges
import com.hienlh.ppm.state.monotonicMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * Fork, Edit and the versions on a chat's user messages (`onFork`, `onEdit`, `editingMsgId`,
 * `onNavigateVersion`, `versionNavDisabled`). Fork and Edit are handed the message and the one it
 * continues from, and are null where they are left out.
 */
class ChatEdits(
    val onFork: ((ChatItem, String?) -> Unit)?,
    val onEdit: ((ChatItem, String?) -> Unit)?,
    /** The message the next send replaces, ringed. */
    val editingId: String?,
    val onSwitchVersion: (String) -> Unit,
    /** Every switcher greyed out, as while a turn runs. */
    val versionsDisabled: Boolean,
)

/** Semantics property exposing the bottom scroll fade height (`12.dp` for `.chat-scroll-fade`). */
val ChatScrollFadeKey = SemanticsPropertyKey<Dp>("ChatScrollFade")

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
    /** Optional learn/onboarding buttons shown directly under "Send a message…". */
    welcomeActions: (@Composable () -> Unit)? = null,
    /** One message out of this view (an error's Dismiss), by id. */
    onDismiss: (String) -> Unit = {},
    /** Every error out of this view. */
    onClearErrors: () -> Unit = {},
    /** A message picked to answer; null leaves Reply out. */
    onReply: ((ReplyReference) -> Unit)? = null,
    /** Fork, Edit and the versions on the user's messages; null leaves them all out. */
    edits: ChatEdits? = null,
    /** A place in this chat to show, asked for from outside it (the Review tab's "Show in chat"). */
    jumpRequest: ChatJumpRequest? = null,
    /** [jumpRequest] was shown, or given up on. */
    onJumpTaken: (ChatJumpRequest) -> Unit = {},
    /** `.chat-scroll-fade`: masks the bottom `12.dp` of the scroll viewport so messages fade out over any theme background. */
    bottomFade: Boolean = true,
    /** `[data-chat-list]:has(+ [data-chat-footer])`: lifts the scroll navigator `24.dp` when the footer overlaps the `12.dp` fade band. */
    overlappedByFooter: Boolean = bottomFade,
) {
    Box(modifier.fillMaxSize()) {
        when {
            // A new chat has nothing to load until its first message makes the session.
            state.sessionId != null && !state.hasLoadedHistory && state.items.isEmpty() && state.historyError == null ->
                CenteredNotice("Loading messages...", pulsing = true)
            state.items.isEmpty() && state.live.isNullOrEmpty() && !state.isRunning && state.pendingApproval == null ->
                Welcome(welcome, welcomeActions)
            else -> {
                // A long press on an answer's markdown opens it selectable (SelectText.kt).
                var selecting by remember { mutableStateOf<String?>(null) }
                CompositionLocalProvider(LocalSelectText provides { selecting = it }) {
                    TranscriptList(
                        state, onRespond, onLoadEarlier, jumps, onDismiss, onClearErrors, onReply, edits, jumpRequest, onJumpTaken,
                        bottomFade = bottomFade,
                        overlappedByFooter = overlappedByFooter,
                    )
                }
                selecting?.let { text -> SelectTextDialog(text) { selecting = null } }
            }
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
    edits: ChatEdits?,
    jumpRequest: ChatJumpRequest?,
    onJumpTaken: (ChatJumpRequest) -> Unit,
    bottomFade: Boolean,
    overlappedByFooter: Boolean,
) {
    val rollups = remember { TurnRollups() }
    val rows = remember(state.items, state.isRunning) { TranscriptRows.of(state.items, state.isRunning, rollups) }
    // Each user message's number in the session, which names its versions; a row per item.
    val ordinals = remember(state.items, state.userOrdinalOffset) { MessageVersions.ordinals(state.items, state.userOrdinalOffset) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val tokens = LocalPpmTokens.current
    val live = state.live
    // Top down, as the web's: the sentinel while there is more above, the skeleton while it loads,
    // the messages oldest first, the turn streaming in, the cache gone cold while the chat is
    // idle, and the approval or the thinking line while there is one.
    val head = (if (state.hasEarlier) 1 else 0) + (if (state.loadingEarlier) 1 else 0)
    // Read again once a minute, as `useMinuteClock` reads the time. Read once, here: the list's
    // builder reruns by itself when a state it reads changes, and read live there the notice could
    // join a list whose `keys` were built without it (`index: -1` on a tablet, 03/11).
    val idleCache = produceState(coldCache(state), state.promptCache, state.isRunning, state.pendingApproval) {
        while (true) {
            value = coldCache(state)
            delay(60_000)
        }
    }.value
    // `hasTrailing = !!liveApproval || isStreaming`: the whole turn, so its 16 px padding keeps a
    // running tool card off the bars below even while the thinking line itself shows nothing.
    val trailing = state.pendingApproval != null || state.isRunning
    val count = head + rows.size + (if (!live.isNullOrEmpty()) 1 else 0) + (if (idleCache != null) 1 else 0) + (if (trailing) 1 else 0)
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
    val currentRows by rememberUpdatedState(rows)
    val currentHead by rememberUpdatedState(head)
    val showMessage = remember(list, scroll, density) {
        { id: String, row: Int ->
            flashJob?.cancel()
            flashing = id
            flashJob = scope.launch {
                delay(1_400)
                flashing = null
            }
            scope.launch {
                scroll.release()
                list.reveal(currentHead + row, with(density) { 24.dp.toPx() })
            }
            Unit
        }
    }
    val replies = remember(rows, state.sessionId, state.providerId, head) {
        val found = HashMap<ReplyReference, ChatItem?>()
        ReplyTargets { reply ->
            val target = if (reply in found) found[reply] else MessageReply.resolve(reply, state.items, state.sessionId, state.providerId).also { found[reply] = it }
            val row = target?.let { rows.indexOfFirst { it.item.id == target.id } } ?: -1
            if (target == null || row < 0) return@ReplyTargets null
            val jump: () -> Unit = { showMessage(target.id, row) }
            jump
        }
    }
    // "Show in chat" from a change tray (`jumpToEdit`): the edit inside its card when the card is
    // open, else the card, 60 px under the top of the chat, lit for 1.4 s.
    val anchors = remember { EditAnchors() }
    val jump = remember(anchors, list, scroll, density) {
        { ref: String ->
            val call = ref.replace(Regex("-\\d+$"), "")
            val row = currentRows.indexOfFirst { r -> r.item.blocks.any { (it as? ChatBlock.Tool)?.tool?.toolUseId == call } }
            if (row >= 0) {
                scope.launch {
                    scroll.release()
                    val index = currentHead + row
                    if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
                    withFrameNanos {}
                    val edit = anchors.edits[ref]?.takeIf { it.isAttached }
                    val target = edit ?: anchors.cards[call]?.takeIf { it.isAttached }
                    val viewport = anchors.list?.takeIf { it.isAttached }
                    if (target != null && viewport != null) {
                        val top = viewport.localPositionOf(target, Offset.Zero).y
                        anchors.light(if (edit != null) ref else call, scope)
                        list.animateScrollBy(top - with(density) { 60.dp.toPx() })
                    }
                }
            }
            Unit
        }
    }
    // "Show in chat" from the Review tab (`useChatJumpStore`): the call's card, or the prompt that
    // asked for it when the card is not drawn (a sub-agent's call). Waits for the tab to be laid out
    // and its history to load, since the request is made just before the tab opens.
    val taken by rememberUpdatedState(onJumpTaken)
    LaunchedEffect(jumpRequest) {
        val request = jumpRequest ?: return@LaunchedEffect
        while (true) {
            if (list.layoutInfo.viewportSize.height > 0) {
                val call = request.toolUseId
                if (call != null && currentRows.any { r -> r.item.blocks.any { (it as? ChatBlock.Tool)?.tool?.toolUseId == call } }) {
                    jump("$call-0")
                    return@LaunchedEffect taken(request)
                }
                val row = request.messageId?.let { id -> currentRows.indexOfFirst { it.item.id == id } } ?: -1
                if (row >= 0) {
                    showMessage(currentRows[row].item.id, row)
                    return@LaunchedEffect taken(request)
                }
            }
            if (monotonicMillis() - request.at > ChatJumpRequest.GIVE_UP_MILLIS) return@LaunchedEffect taken(request)
            delay(150)
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
    val markdownImages = LocalMarkdownImages.current
    val items by rememberUpdatedState(state.items)
    val streaming by rememberUpdatedState(live)
    val gallery = remember(viewer, uploads, toolImages, markdownImages) {
        ChatGallery { self ->
            val images = galleryRefs(items, streaming) { tool -> tool.toolUseId?.let { openCards[it] } ?: true }
                .map { galleryImage(it, uploads, toolImages, markdownImages) }
            val index = images.indexOfFirst { it.key == self.key }
            // The picture tapped is the one already in hand.
            if (index < 0) viewer?.show(listOf(self), 0) else viewer?.show(images.toMutableList().also { it[index] = self }, index)
        }
    }
    // Each item where the exact heights above it put it, as Chrome stacks the messages: rounded
    // one at a time, 10.5 px gaps and 52.5 px lines put a message 3 px off the web's on a phone.
    val stack = rememberCssLazyStack(0.dp)
    val streams = !live.isNullOrEmpty()
    val cold = idleCache != null
    val keys = remember(rows, state.hasEarlier, state.loadingEarlier, streams, cold, trailing) {
        buildList {
            if (state.hasEarlier) add(SENTINEL)
            if (state.loadingEarlier) add(SKELETON)
            for (row in rows) add(row.item.id)
            if (streams) add(LIVE)
            if (cold) add(IDLE_CACHE)
            if (trailing) add(TRAILING)
        }
    }
    fun stacked(index: Int) = Modifier.cssLazyItem(stack, keys[index], keys.getOrNull(index - 1), keys.getOrNull(index + 1))
    // A shell call's card opens as its output starts to stream in, once (`isStreamingBash`).
    val streamed = remember { HashSet<String>() }
    LaunchedEffect(state.bashOutput.keys) {
        for (id in state.bashOutput.keys) if (streamed.add(id)) openCards[id] = true
    }
    CompositionLocalProvider(
        LocalOpenCards provides openCards,
        LocalChatGallery provides gallery,
        LocalBashOutput provides state.bashOutput,
        LocalEditAnchors provides anchors,
        LocalTurnsByCall provides rollups.byCall,
    ) {
        val mobile = LocalPpmMobile.current
        var paneWidthPx by remember { mutableIntStateOf(0) }
        val horizPad = with(density) {
            readingColumnHorizontalPadding(paneWidthPx.toDp().value, mobile).dp
        }
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                TaskTracker(state.tasks)
                // The tag is how the Baseline Profile's journeys find the transcript (baselineprofile/).
                val fadeModifier = if (bottomFade) {
                    Modifier
                        .semantics { this[ChatScrollFadeKey] = 12.dp }
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val fadePx = 12.dp.toPx()
                            if (size.height > fadePx) {
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0f to Color.Black,
                                        1f to Color.Transparent,
                                        startY = size.height - fadePx,
                                        endY = size.height,
                                    ),
                                    topLeft = Offset(0f, size.height - fadePx),
                                    size = Size(size.width, fadePx),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                        }
                } else {
                    Modifier
                }
                LazyColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .nestedScroll(scroll.connection)
                        .onGloballyPositioned {
                            anchors.list = it
                            paneWidthPx = it.size.width
                        }
                        .testTag("chat-transcript")
                        .then(fadeModifier)
                        .cssScrollbar(list),
                    state = list,
                    contentPadding = PaddingValues(bottom = if (trailing || !bottomFade) 0.dp else 12.dp),
                ) {
                    // `h-px`: 2.625 px at 2.625x, which pushes every message under it a fraction down.
                    if (state.hasEarlier) item(key = SENTINEL) { Spacer(stacked(0).fillMaxWidth().cssHeight(1.dp)) }
                    if (state.loadingEarlier) item(key = SKELETON) { Box(stacked(head - 1)) { PreCompactSkeleton() } }
                    rows.forEachIndexed { index, row ->
                        item(key = row.item.id, contentType = row.item.role) {
                            val reply = remember(row, state.sessionId, state.providerId, onReply) { replyAction(row, state, onReply) }
                            val userEdits = if (edits != null && row.item.role == ChatItem.Role.USER) {
                                val versions = ordinals.getOrNull(index)?.takeIf { it > 0 }?.let { state.versionMap[it] }
                                val items = state.items
                                val predecessor = state.historyPredecessorId
                                remember(row.item, index, edits, versions, items, predecessor) { userMessageEdits(row.item, index, edits, versions, items, predecessor) }
                            } else {
                                null
                            }
                            val place = stacked(head + index).replyFlash(flashing == row.item.id, tokens.accent).padding(start = horizPad, end = horizPad, top = 16.dp)
                            val compaction = row.item.compaction
                            val message: @Composable (Modifier) -> Unit = { modifier ->
                                ChatMessageView(
                                    row.item,
                                    row.actions?.copy(onReply = reply, onJump = jump),
                                    modifier,
                                    replies,
                                    onReply = reply.takeIf { row.item.role == ChatItem.Role.USER },
                                    edits = userEdits,
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
                        item(key = LIVE) {
                            CompositionLocalProvider(LocalMarkdownImageScope provides LIVE_SCOPE) {
                                AssistantBlocks(live, live = state.isRunning, actions = null, modifier = stacked(head + rows.size).padding(start = horizPad, end = horizPad, top = 16.dp))
                            }
                        }
                    }
                    idleCache?.let { cold ->
                        item(key = IDLE_CACHE) {
                            Box(stacked(keys.indexOf(IDLE_CACHE)).padding(horizontal = (horizPad - 16.dp).coerceAtLeast(0.dp))) {
                                IdleCacheNotice(cold)
                            }
                        }
                    }
                    if (trailing) {
                        item(key = TRAILING) {
                            Box(stacked(keys.lastIndex)) {
                                Trailing(state, onRespond, bottomFade = bottomFade, horizontalPadding = horizPad)
                            }
                        }
                    }
                }
            }
            val hasPendingApproval = state.pendingApproval != null
            if (!hasPendingApproval) {
                ScrollNavigator(
                    nav,
                    goUp = { scope.launch { list.toPreviousMessage(userItems, density, scroll::release) } },
                    goDown = { scope.launch { list.toNextMessage(userItems, density, scroll::toEnd) } },
                    goBottom = { scope.launch { scroll.toEnd() } },
                    jumps = jumps,
                    alwaysOpen = shouldKeepNavigatorOpen(mobile = mobile, totalMessages = nav.total, hasPendingApproval = false),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = if (overlappedByFooter) 24.dp else 12.dp),
                )
            }
            waitingApprovalPillLabel(state.pendingApproval, nav.atBottom)?.let { waitingLabel ->
                WaitingApprovalPill(
                    label = waitingLabel,
                    onClick = { scope.launch { scroll.toEnd() } },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (overlappedByFooter) 24.dp else 12.dp),
                )
            }
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

/** The keys of what comes after the messages: the turn streaming in, the cold cache, the approval or the thinking line. */
private const val LIVE = "live"
private const val IDLE_CACHE = "idle-cache"
private const val TRAILING = "trailing"

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
 *
 * Only on average: `scrollHeight - 1 - clientHeight` is in whole CSS px, each rounded, so the web
 * stops 0 to 2 px short depending on the fraction of the whole transcript's height, the messages
 * above the screen included, which a lazy list never measures. Measured 2.45 px short at 2x, where
 * this stops 2: every message there sat 1 px higher than Chrome's.
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

/** The approval or question the AI waits on, and the thinking line: there while either is, or a turn runs (`hasTrailing`). */
@Composable
private fun Trailing(
    state: ChatState,
    onRespond: (ApprovalRequest, Boolean, JsonElement?) -> Unit,
    bottomFade: Boolean = true,
    horizontalPadding: Dp = 16.dp,
) {
    val indicator = indicatorLabel(state)
    val request = state.pendingApproval
    Column(
        Modifier.fillMaxWidth().padding(start = horizontalPadding, top = 16.dp, end = horizontalPadding, bottom = if (bottomFade) 12.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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

/**
 * The cache gone cold, for [IdleCacheNotice]: only while the chat is idle and nothing waits on an
 * answer, as once a turn starts the question it informs has been answered.
 */
private fun coldCache(state: ChatState): PromptCacheStatus.Cold? {
    if (state.isRunning || state.pendingApproval != null) return null
    return PromptCacheStatus.of(state.promptCache, System.currentTimeMillis().toDouble()) as? PromptCacheStatus.Cold
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

    /** With [rollups], a turn's last answer also carries the files the turn changed and the turn it closes. */
    fun of(items: List<ChatItem>, running: Boolean, rollups: TurnRollups? = null): List<Row> {
        rollups?.start(items)
        val rows = ArrayList<Row>(items.size)
        val turnTexts = mutableListOf<String>()
        var turnReply: ChatItem? = null
        var turnStart = 0
        // The prompt the answers since answer: the nearest user message before them that is one.
        var prompt: SessionTurn? = null
        items.forEachIndexed { index, item ->
            if (item.role != ChatItem.Role.ASSISTANT) {
                turnTexts.clear()
                turnReply = null
                turnStart = index + 1
                if (item.role == ChatItem.Role.USER) rollups?.turn(item.id)?.let { prompt = it }
            } else if (item.text.isNotEmpty()) {
                turnTexts += item.text
                turnReply = item
            }
            val next = items.getOrNull(index + 1)
            // The running turn's actions wait until it ends.
            val endsTurn = item.role == ChatItem.Role.ASSISTANT && next?.role != ChatItem.Role.ASSISTANT && !(running && next == null)
            rows += when {
                item.role == ChatItem.Role.USER -> Row(item, null, item)
                endsTurn -> {
                    val changes = rollups?.changes(items.subList(turnStart, index + 1)).orEmpty()
                    val actions = MessageActions(item.timestamp, turnTexts.joinToString("\n\n"), changes = changes, turn = prompt.takeIf { changes.isNotEmpty() }, key = item.id)
                    Row(item, actions, turnReply)
                }
                else -> Row(item, null)
            }
        }
        return rows
    }
}

/**
 * Each turn's changes and the turn they close, for its change pill (`turnChanges`, `rollupTurns`):
 * worked out again only for a turn whose messages changed, as the rows are built again whenever a
 * message arrives. Also each call's turn, for naming the turn that changed a line since.
 */
internal class TurnRollups {
    private val memo = SessionTurnsMemo()
    private val changes = HashMap<String, Pair<List<ChatItem>, List<TurnFileChange>>>()
    private var turns: Map<String, SessionTurn> = emptyMap()
    var byCall: Map<String, SessionTurn> = emptyMap()
        private set

    fun start(items: List<ChatItem>) {
        val found = memo.of(items)
        turns = found.associateBy { it.messageId }
        byCall = SessionTurns.byCall(found)
        if (changes.size > items.size + 64) changes.keys.retainAll(items.mapTo(HashSet()) { it.id })
    }

    /** The turn [messageId] asked for; null for a user message that is not a prompt. */
    fun turn(messageId: String): SessionTurn? = turns[messageId]

    fun changes(turn: List<ChatItem>): List<TurnFileChange> {
        val key = turn.last().id
        changes[key]?.let { (seen, found) -> if (seen.size == turn.size && seen.indices.all { seen[it] === turn[it] }) return found }
        return TurnFileChanges.of(turn).also { changes[key] = turn.toList() to it }
    }
}

/**
 * Where each tool card and each edit inside one is, for "Show in chat" (`data-tool-ref`,
 * `data-edit-ref`), and the one lit (`data-flash`): a card's call id, or an edit's ref.
 */
@Stable
internal class EditAnchors {
    val cards = HashMap<String, LayoutCoordinates>()
    val edits = HashMap<String, LayoutCoordinates>()
    var list: LayoutCoordinates? = null
    var flash by mutableStateOf<String?>(null)
        private set
    private var clear: Job? = null

    /** [ref] lit for 1.4 s; a second jump ends the first one's light at once. */
    fun light(ref: String, scope: CoroutineScope) {
        clear?.cancel()
        flash = ref
        clear = scope.launch {
            delay(1_400)
            flash = null
        }
    }
}

internal val LocalEditAnchors = staticCompositionLocalOf<EditAnchors?> { null }

/**
 * Names this box for "Show in chat" as the edit [ref] (`data-edit-ref`), and lights it
 * (`[data-flash="edit"]`: the accent at 13%, in and out over 200 ms).
 */
@Composable
internal fun Modifier.editAnchor(ref: String?): Modifier {
    val anchors = LocalEditAnchors.current
    if (anchors == null || ref == null) return this
    DisposableEffect(anchors, ref) { onDispose { anchors.edits.remove(ref) } }
    val lit by animateFloatAsState(if (anchors.flash == ref) 1f else 0f, tween(200, easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)), label = "flash")
    val accent = LocalPpmTokens.current.accent
    return onGloballyPositioned { anchors.edits[ref] = it }.drawBehind { if (lit > 0f) drawRect(accent.opacity(0.13 * lit).color) }
}

/**
 * Edit, Fork and the versions on the user's message at [index] of [items]: both go on from the
 * message shown above it (`prevMsgId`), or from what is above the loaded window.
 */
private fun userMessageEdits(item: ChatItem, index: Int, edits: ChatEdits, versions: VersionGroup?, items: List<ChatItem>, predecessor: String?): UserMessageEdits {
    fun anchor() = MessageVersions.anchor(items, index, predecessor)
    return UserMessageEdits(
        onEdit = edits.onEdit?.let { edit -> { edit(item, anchor()) } },
        onFork = edits.onFork?.let { fork -> { fork(item, anchor()) } },
        editing = item.id == edits.editingId,
        versions = versions,
        onSwitchVersion = edits.onSwitchVersion,
        versionsDisabled = edits.versionsDisabled,
    )
}

/**
 * Reply on [row] (`handleReply`): shown on a user's message only when it can be quoted, on an
 * answer whenever its turn has text, and only in a chat with a session and a provider.
 */
private fun replyAction(row: TranscriptRows.Row, state: ChatState, onReply: ((ReplyReference) -> Unit)?): (() -> Unit)? {
    val target = row.replyTarget ?: return null
    val provider = state.providerId?.takeIf { it.isNotEmpty() } ?: return null
    val sessionId = state.sessionId?.takeIf { it.isNotEmpty() } ?: return null
    if (onReply == null) return null
    if (row.item.role == ChatItem.Role.USER) {
        val reply = MessageReply.create(target, sessionId, provider) ?: return null
        return { onReply(reply) }
    }
    return { MessageReply.create(target, sessionId, provider)?.let(onReply) }
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
private fun Welcome(below: @Composable () -> Unit, actions: (@Composable () -> Unit)? = null) {
    val tokens = LocalPpmTokens.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        Column(Modifier.fillMaxSize().cssScrollY()) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = viewport),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PpmIcon(ProductIcon.Bot, 40.dp, tokens.text3.color)
                    BasicText(
                        "Send a message to start a new conversation",
                        style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color),
                    )
                    actions?.invoke()
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
 * Horizontal padding for the centered 760 dp reading column (`Tablet 1 · Chat` Note 3 /
 * `readingColumn = 760px`): on a tablet (`!mobile`), when the pane is wider than `760 + 32 = 792` dp,
 * messages, cards and notices share a centered 760 dp column while the scroll container, task strip
 * and footer span the full pane.
 */
fun readingColumnHorizontalPadding(paneWidthDp: Float, mobile: Boolean): Float {
    if (mobile || paneWidthDp <= 792f) return 16f
    return ((paneWidthDp - 760f) / 2f).coerceAtLeast(16f)
}

/**
 * Whether the message navigator column (`ScrollNavigator`) stays expanded without folding into a
 * puck (`Tablet 1 · Chat` Note 97 / Req 4.4): on a tablet (`!mobile`) whenever there is at least
 * one user message and no approval/question card is waiting at the bottom.
 */
fun shouldKeepNavigatorOpen(mobile: Boolean, totalMessages: Int, hasPendingApproval: Boolean): Boolean =
    !mobile && totalMessages > 0 && !hasPendingApproval

/**
 * Tooltip / accessibility description for the message navigator (`Tablet 1 · Chat` Note 97:
 * `"Message 7 of 48"`).
 */
fun navigatorTooltipText(position: Int, total: Int): String =
    if (total <= 0) "Message 0 of 0" else "Message ${position.coerceIn(1, total)} of $total"

/**
 * Label for the floating "Needs you" pill shown when the user scrolls away from a waiting
 * approval or question card at the bottom of the transcript (`Tablet 1 · Chat` Section 5.5).
 */
fun waitingApprovalPillLabel(approval: ApprovalRequest?, atBottom: Boolean): String? {
    if (approval == null || atBottom) return null
    return if (approval.tool == "AskUserQuestion") {
        "Waiting for answer"
    } else {
        "Waiting for approval · ${approval.tool}"
    }
}

@Composable
private fun WaitingApprovalPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val shape = CircleShape
    Row(
        SHADOW_LG.fold(modifier.testTag("chat-needs-you-pill")) { m, shadow -> m.ppmShadow(shadow, shape) }
            .clip(shape)
            .background(tokens.warning.color, shape)
            .ppmClickable(pressedColor = Color.Transparent, shape = shape, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(
            label,
            style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = Color(0xFF0A0A0A)),
        )
        PpmIcon(ProductIcon.ChevronDown, 14.dp, Color(0xFF0A0A0A))
    }
}

/**
 * The bottom-right puck between the user's own messages (`ChatScrollNav`): on a phone a tap opens
 * previous · position · next · latest, which folds away 3.2 s after the last use; on a tablet
 * ([alwaysOpen]) the 44 dp navigator column stays open beside the transcript (`Tablet 1 · Chat`
 * Note 97 / Req 4.4). Alt+↑ / Alt+↓ jump the same way.
 */
@Composable
private fun ScrollNavigator(
    nav: MessageNav,
    goUp: () -> Unit,
    goDown: () -> Unit,
    goBottom: () -> Unit,
    jumps: Flow<Int>,
    modifier: Modifier,
    alwaysOpen: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var open by remember { mutableStateOf(false) }
    var collapseMs by remember { mutableLongStateOf(AUTO_COLLAPSE_MS) }
    // Bumped on every jump, so that the fold waits again even where the position stays.
    var jumpTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(open, nav.position, jumpTick, alwaysOpen) {
        if (alwaysOpen || !open) return@LaunchedEffect
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

    val shownOpen = alwaysOpen || open
    Box(modifier) {
        if (shownOpen) {
            val shape = RoundedCornerShape(if (alwaysOpen) 10.dp else 12.dp)
            val tooltip = navigatorTooltipText(nav.position, nav.total)
            Column(
                SHADOW_LG.fold(Modifier.testTag("chat-scroll-navigator") as Modifier) { m, shadow -> m.ppmShadow(shadow, shape) }
                    .background(tokens.panel2.opacity(0.95).color, shape)
                    .cssBorder(tokens.border.color, shape)
                    .padding(3.dp)
                    .semantics { contentDescription = tooltip },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val rowWidth = if (alwaysOpen) 38.dp else 34.dp
                val rowHeight = if (mobile) 36.dp else if (alwaysOpen) 34.dp else 30.dp
                NavRow(ProductIcon.ChevronUp, 15, rowWidth, rowHeight, enabled = nav.hasAbove, label = "Jump to previous message") {
                    goUp()
                    jumped()
                }
                // `text-[10px]` under preflight's `line-height: 1.5`.
                BasicText(
                    if (nav.total == 0) "0/0" else "${nav.position.coerceAtLeast(1)}/${nav.total}",
                    Modifier.padding(vertical = 1.dp).testTag("chat-scroll-navigator-pos"),
                    ppmText(10f, FontWeight.Medium, FontKind.MONO, lineHeight = 15f).copy(color = tokens.text3.color),
                )
                NavRow(ProductIcon.ChevronDown, 15, rowWidth, rowHeight, enabled = !nav.atBottom, label = "Jump to next message") {
                    goDown()
                    jumped()
                }
                NavRow(ProductIcon.ChevronsDown, 13, rowWidth, rowHeight, enabled = !nav.atBottom, label = "Jump to latest") {
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

/** `w-[34px] h-[30px] rounded-[9px]` (36 px tall on a phone, 38×34 on tablet), the surface and the text colour under a pointer. */
@Composable
private fun NavRow(icon: ProductIcon, size: Int, width: Dp, height: Dp, enabled: Boolean, label: String, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val lit = colorsFade(hovered && enabled).toDouble()
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier
            .size(width = width, height = height)
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
