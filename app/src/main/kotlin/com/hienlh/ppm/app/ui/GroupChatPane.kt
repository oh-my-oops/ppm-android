package com.hienlh.ppm.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.GroupTab
import com.hienlh.ppm.chat.pulse
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.cssExact
import com.hienlh.ppm.design.cssHeight
import com.hienlh.ppm.design.cssLazyItem
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberCssExact
import com.hienlh.ppm.design.rememberCssLazyStack
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.GroupMessage
import com.hienlh.ppm.protocol.GroupStatus
import com.hienlh.ppm.state.GroupChat
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/** Tailwind's `amber-500`, as Chrome draws its `oklch()` in sRGB. */
internal val AMBER_500 = Rgba(253.54 / 255, 153.53 / 255, 0.0, 1.0)

/** A group tab's body: "No group selected." for a tab that names none, as the web's says. */
@Composable
internal fun GroupChatBody(model: AppModel, tab: WorkspaceTab) {
    if (tab.metaString("groupId").isNullOrEmpty()) {
        val tokens = LocalPpmTokens.current
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            BasicText("No group selected.", style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text3.color))
        }
        return
    }
    model.group(tab.id)?.let { group -> key(group) { GroupChatPane(model, group) } }
}

/**
 * A group's tab (group-chat-tab.tsx): its status with Stop or Resume over the feed, an error
 * under the feed, the message box at the bottom; the roster beside it all on a tablet, in a sheet
 * on a phone. What the tab keeps while hidden is the [GroupTab]'s.
 */
@Composable
private fun GroupChatPane(model: AppModel, group: GroupTab) {
    val chat = group.chat
    val state by chat.state.collectAsState()
    val mobile = LocalPpmMobile.current
    val speakers = remember(state.roster, state.members) { GroupSpeakers.of(state.roster, state.members) }
    var transcriptOf by remember { mutableStateOf<GroupMessage?>(null) }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            GroupHeader(state, onStop = chat::stop, onResume = chat::resume) { group.rosterOpen = !group.rosterOpen }
            GroupFeed(group, state, speakers, mobile, Modifier.weight(1f)) { transcriptOf = it }
            state.error?.takeIf { it.isNotEmpty() }?.let { GroupErrorBar(it) }
            GroupMessageBox(group, mobile)
        }
        if (!mobile && group.rosterOpen) GroupRosterPanel(model, group, state)
    }
    if (mobile && group.rosterOpen) GroupRosterSheet(model, group, state) { group.rosterOpen = false }
    transcriptOf?.let { message -> GroupTranscriptView(model, chat.groupId, message) { transcriptOf = null } }
}

/**
 * `flex h-11 items-center justify-between border-b px-3`: the status after its dot (`size-2`: the
 * accent, pulsing, while replies run; amber while paused), then Stop while replies run or Resume
 * while paused (`size="sm" variant="outline" h-8`, a 14 px icon) and the roster's toggle (`size-8`).
 */
@Composable
private fun GroupHeader(state: GroupChat.State, onStop: () -> Unit, onResume: () -> Unit, onRoster: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val dot = when (state.shownStatus) {
                GroupStatus.ACTIVE -> tokens.accent.opacity(pulse().toDouble())
                GroupStatus.PAUSED -> AMBER_500
                GroupStatus.IDLE -> tokens.text3
            }
            Box(Modifier.size(8.dp).background(dot.color, CircleShape))
            // `capitalize`.
            BasicText(state.shownStatus.wire.replaceFirstChar { it.uppercase() }, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val dark = isSystemInDarkTheme()
            // `has-[>svg]:px-2.5`; the icon's `mr-1` on the button's `gap-1.5`.
            when {
                state.running -> DialogButton(
                    "Stop", primary = false, dark, enabled = true, Modifier,
                    icon = ProductIcon.Square, height = 32.dp, gap = 10.dp, padding = 10.dp, iconSize = 14.dp, onClick = onStop,
                )
                state.status == GroupStatus.PAUSED -> DialogButton(
                    "Resume", primary = false, dark, enabled = true, Modifier,
                    icon = ProductIcon.Play, height = 32.dp, gap = 10.dp, padding = 10.dp, iconSize = 14.dp, onClick = onResume,
                )
            }
            val hover = rememberHover(instant = true)
            val shape = RoundedCornerShape(8.dp)
            Box(
                Modifier
                    .size(32.dp)
                    .clip(shape)
                    .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, fade = false, shape = shape, onClickLabel = "Toggle roster", source = hover.source, onClick = onRoster),
                contentAlignment = Alignment.Center,
            ) { PpmIcon(ProductIcon.Users, 16.dp, tokens.text.mix(tokens.text3, hover.lit.toDouble()).color) }
        }
    }
}

private const val FEED_START = "\u0000start"
private const val FEED_TYPING = "\u0000typing"
private const val FEED_END = "\u0000end"

/**
 * The feed (`min-h-0 flex-1 overflow-y-auto`): a spinner 40 px down while it loads, a line in the
 * middle while it is empty, else the messages in a `py-2` column and who is typing under them.
 * Every new message and every change of who types scrolls to the column's end, which sits inside
 * the `py-2`, so the feed stops 8 px short of its bottom, as `scrollIntoView({ block: "end" })` of
 * the web's end marker does — but not on coming back to a tab that has heard of nothing new, which
 * the web's tab never left.
 */
@Composable
private fun GroupFeed(group: GroupTab, state: GroupChat.State, speakers: GroupSpeakers, mobile: Boolean, modifier: Modifier, onViewFull: (GroupMessage) -> Unit) {
    val tokens = LocalPpmTokens.current
    Box(modifier.fillMaxWidth()) {
        when {
            state.loading -> Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                PpmIcon(ProductIcon.Loader2, 20.dp, tokens.accent.color, spinning = true)
            }
            state.messages.isEmpty() -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                BasicText("Send a message to start the group.", style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text3.color, textAlign = TextAlign.Center))
            }
            else -> FeedList(group, state, speakers, mobile, onViewFull)
        }
    }
}

@Composable
private fun FeedList(group: GroupTab, state: GroupChat.State, speakers: GroupSpeakers, mobile: Boolean, onViewFull: (GroupMessage) -> Unit) {
    val list = group.feed
    val typing = state.typing
    // The rows stacked in fractions of a pixel, as Chrome stacks them: rounded a row at a time,
    // 83.5 px rows at 2.625x put each one a pixel lower than the last.
    val stack = rememberCssLazyStack(0.dp)
    LazyColumn(Modifier.fillMaxSize(), state = list) {
        item(key = FEED_START) { Spacer(Modifier.cssLazyItem(stack, 0, last = false).cssHeight(8.dp)) }
        itemsIndexed(state.messages, key = { _, message -> message.id }) { index, message ->
            // Where the press that asked for the message's menu was, in its row.
            var menuAt by remember { mutableStateOf<Offset?>(null) }
            val exact = rememberCssExact()
            Box(Modifier.cssLazyItem(stack, index + 1, last = false).cssExact(exact)) {
                GroupMessageRow(message, speakers, exact, onMenu = if (message.fullSessionRef.isNullOrEmpty()) null else ({ menuAt = it }))
                menuAt?.let { at ->
                    OneItemMenu(mobile, at, "View full transcript", ProductIcon.ScrollText, destructive = false, onDismiss = { menuAt = null }) {
                        menuAt = null
                        onViewFull(message)
                    }
                }
            }
        }
        val after = state.messages.size + 1
        if (typing.isNotEmpty()) item(key = FEED_TYPING) { TypingLine(typing, Modifier.cssLazyItem(stack, after, last = false)) }
        val end = after + if (typing.isEmpty()) 0 else 1
        item(key = FEED_END) { Spacer(Modifier.cssLazyItem(stack, end, last = true).cssHeight(8.dp)) }
    }
    val pad = with(LocalDensity.current) { 8.dp.toPx() }
    val count = state.messages.size + if (typing.isEmpty()) 2 else 3
    LaunchedEffect(state.messages.size, typing) {
        val seen = state.messages.size to typing
        if (group.scrolledFor == seen) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.totalItemsCount }.first { it == count }
        list.scrollToItem(count - 1)
        list.scrollBy(-pad)
        group.scrolledFor = seen
    }
    // A feed at its end stays there when the layout, not a finger, moves the end away: on the first
    // start after a boot the system bars' insets land after that scroll and hid the last message,
    // and the error bar opens under the feed (the web leaves both covered).
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo }.collect { info ->
            // How far the end is below the viewport; at rest the end spacer sits just under it, out of view.
            val last = info.visibleItemsInfo.lastOrNull() ?: return@collect
            if (info.viewportSize.height <= 0) return@collect
            val behind = when (last.index) {
                info.totalItemsCount - 1 -> last.offset + last.size - info.viewportEndOffset
                info.totalItemsCount - 2 -> last.offset + last.size - info.viewportEndOffset + pad.roundToInt()
                else -> Int.MAX_VALUE
            }
            if (group.feedAtEnd && behind > pad + 1 && !list.isScrollInProgress) {
                list.scrollToItem(info.totalItemsCount - 1)
                list.scrollBy(-pad)
            } else {
                group.feedAtEnd = behind <= pad + 1
            }
        }
    }
}

/** `px-3 py-1.5 text-xs italic text-text-subtle`: "Ann is typing…", "Ann, Bo are typing…". */
@Composable
private fun TypingLine(names: List<String>, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    CssColumn(modifier.fillMaxWidth().padding(horizontal = 12.dp), top = 6.dp, bottom = 6.dp) {
        BasicText(
            "${names.joinToString(", ")} ${if (names.size == 1) "is" else "are"} typing…",
            Modifier.cssLines(16f),
            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color, fontStyle = FontStyle.Italic),
        )
    }
}

/** `shrink-0 border-t bg-destructive/10 px-3 py-2 text-xs text-destructive`. */
@Composable
private fun GroupErrorBar(error: String) {
    val tokens = LocalPpmTokens.current
    BasicText(
        error,
        Modifier
            .fillMaxWidth()
            .background(tokens.error.opacity(0.1).color)
            .cssBorder(tokens.border.color, sides = CssSides.Top)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color),
    )
}

/** Set while a keyboard's Shift+Return goes through to the field as the line break it is. */
private class ShiftReturn {
    var on = false
}

/**
 * The message box (`shrink-0 border-t p-2`, its row `flex items-end gap-2`): a textarea a line tall
 * (`rows={1} min-h-[44px] resize-none rounded-md border bg-background px-3 py-2.5 text-sm`) whose
 * text scrolls inside it, and Send (`size-11`), half-faded while there is nothing to send. On a
 * tablet Return sends and Shift+Return breaks the line — the on-screen keyboard's Return too, which
 * Chrome turns into the Enter key; on a phone Return only ever breaks the line, as there.
 */
// `TextFieldBuffer.changes`, to tell a typed line break from a pasted one.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupMessageBox(group: GroupTab, mobile: Boolean) {
    val tokens = LocalPpmTokens.current
    val draft = group.draft
    val scope = rememberCoroutineScope()
    fun send() {
        val content = draft.text.trim().toString()
        if (content.isEmpty()) return
        group.chat.send(content)
        draft.clearText()
    }
    val shiftReturn = remember { ShiftReturn() }
    // The keyboard's Return comes as a line break typed, which the field cannot send from inside its edit.
    val returnSends = remember(mobile) {
        InputTransformation {
            if (mobile || shiftReturn.on) {
                shiftReturn.on = false
                return@InputTransformation
            }
            if (changes.changeCount != 1) return@InputTransformation
            val range = changes.getRange(0)
            if (changes.getOriginalRange(0).collapsed && range.length == 1 && asCharSequence()[range.start] == '\n') {
                revertAllChanges()
                scope.launch { send() }
            }
        }
    }
    var focused by remember { mutableStateOf(false) }
    // 16 px on a phone (`globals.css`), on `text-sm`'s lines either way.
    val font = if (mobile) 16f else 14f
    val line = font * (1.25f / 0.875f)
    // `rows={1}`, `py-2.5` and the borders, which Chrome snaps to whole pixels before it adds them
    // up: 116.5 px at 2.625x, painted 116, where 1 px borders counted whole would make 117.75.
    // `min-h-[44px]` holds it on a tablet.
    val height = with(LocalDensity.current) { max(44.dp.toPx(), (line + 20f).dp.toPx() + 2 * cssBorderPx(1.dp)).toInt().toDp() }
    val shape = RoundedCornerShape(8.dp)
    val ring = tokens.accent.color
    Row(
        Modifier
            .fillMaxWidth()
            .cssBorder(tokens.border.color, sides = CssSides.Top)
            .padding(8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(height)
                // `focus:ring-1 focus:ring-primary`: 1 px outside the border.
                .drawBehind {
                    if (!focused) return@drawBehind
                    val px = 1.dp.toPx()
                    drawRoundRect(ring, Offset(-px / 2, -px / 2), Size(size.width + px, size.height + px), CornerRadius(8.dp.toPx() + px / 2), style = Stroke(px))
                }
                .clip(shape)
                .background(tokens.bgSolid.color)
                .cssBorder(tokens.border.color, shape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            PpmTextField(
                draft,
                "Message the group…",
                Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { event ->
                        val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                        when {
                            mobile || !enter || event.type != KeyEventType.KeyDown -> false
                            event.isShiftPressed -> {
                                shiftReturn.on = true
                                false
                            }
                            else -> {
                                send()
                                true
                            }
                        }
                    },
                style = ppmText(font, lineHeight = line),
                placeholderColor = tokens.text3.color,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                lineLimits = TextFieldLineLimits.MultiLine(),
                inputTransformation = returnSends,
                onFocusChanged = { focused = it },
                cssStart = 12.dp,
            )
        }
        SendButton(enabled = draft.text.isNotBlank(), onClick = ::send)
    }
}

/** `Button size-11 p-0`: the accent, `hover:bg-primary/90`, a 16 px paper plane; half-faded and inert while disabled. */
@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val hover = rememberHover(enabled = enabled)
    Box(
        Modifier
            .size(44.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(tokens.accent.opacity(1 - 0.1 * hover.lit).color)
            .ppmClickable(enabled = enabled, pressedColor = Color.Black.copy(alpha = 0.1f), shape = shape, onClickLabel = "Send", source = hover.source, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PpmIcon(ProductIcon.Send, 16.dp, tokens.accentFg.color) }
}
