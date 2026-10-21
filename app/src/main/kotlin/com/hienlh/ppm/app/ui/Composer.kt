package com.hienlh.ppm.app.ui

import android.net.Uri
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.TextInputFocus
import com.hienlh.ppm.app.attach
import com.hienlh.ppm.app.onEveryTap
import com.hienlh.ppm.app.removeAttachment
import com.hienlh.ppm.app.tracksTextFocus
import com.hienlh.ppm.chat.ReplyCard
import com.hienlh.ppm.chat.sky400
import com.hienlh.ppm.chat.sky500
import com.hienlh.ppm.chat.sky600
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.brightness
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ModelOption
import com.hienlh.ppm.protocol.ParsedUserMessage
import com.hienlh.ppm.protocol.PromptCacheState
import com.hienlh.ppm.state.ChatController
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.ChatState
import com.hienlh.ppm.state.Effort
import com.hienlh.ppm.state.MessagePriority
import com.hienlh.ppm.state.ModelListStatus
import com.hienlh.ppm.state.ModelNames
import com.hienlh.ppm.state.PermissionMode
import com.hienlh.ppm.state.PromptCacheStatus
import com.hienlh.ppm.state.stepHistory
import kotlin.math.floor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

/** Chips of the composer row: 11.5 px, with the body's 1.5 line height. */
private val chipText = ppmText(11.5f, lineHeight = 17.25f)

/**
 * The message box (`message-input.tsx`): the chips that set how the next turn runs over the text
 * and Send, or Stop while the turn runs and nothing is typed. A phone draws the `md:hidden` rows,
 * a tablet the desktop ones. [narrow] is the chat's `@max-[420px]` — the mode and priority chips
 * keep only their icons, and on a tablet the chips stay on one line. The mic is left out until
 * this app has it. [fileDrop] takes what is dropped on the text, where its own field would; the
 * [pickers] open over the box take its keys first.
 */
@Composable
fun ChatComposer(model: AppModel, tab: ChatTab, chat: ChatController, state: ChatState, narrow: Boolean, fileDrop: ChatFileDrop, pickers: ComposerPickers) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val running = state.isRunning
    val focus = remember { FocusRequester() }
    var modeOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    // The model can't change mid-turn: its sheet closes as the turn starts.
    LaunchedEffect(running) { if (running) modelOpen = false }
    // A press landing on the box while it has no focus: the focus a long press gives it brings no
    // keyboard, as Chrome's brings none. A tap still raises it, and so does anything below.
    val quietFocus = remember { mutableStateOf(false) }
    // The box asked for focus brings the keyboard, even one Back put away: the web's button took the
    // focus, so Chrome raises it for the textarea. A paste leaves it as it was.
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    LaunchedEffect(tab) {
        snapshotFlow { tab.focusRequests }.drop(1).collect {
            // A box focused quietly has no input session yet: dropping the flag starts one. Not with
            // the keyboard up, where a tap left the flag behind: a new session drops the keyboard
            // and brings it back.
            val keyboardUp = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (tab.raisesKeyboard && !keyboardUp) quietFocus.value = false
            focus.requestFocus()
            if (tab.raisesKeyboard) keyboard?.show()
        }
    }
    // A draft that loaded is focused at its end (`initialValue`), on the desktop layout only: on a
    // phone the web focuses that layout's textarea, which is hidden there. The keyboard comes with
    // it as Chrome brings it for a `focus()`, once the app has been touched since it started.
    val focusQuietly = remember { mutableStateOf(false) }
    LaunchedEffect(tab, mobile) {
        snapshotFlow { tab.draftFocus }.filter { it }.collect {
            tab.draftFocus = false
            if (mobile) return@collect
            if (TextInputFocus.activated) {
                tab.requestFocus()
            } else if (TextInputFocus.field !== tab.draft) {
                quietFocus.value = true
                focusQuietly.value = true
            }
        }
    }

    // The send lives on the tab, so one queued behind an upload still goes with this box off screen.
    fun send() = tab.submit()

    // The keyboard's Send is the page's Return, which a picker on screen takes.
    fun keyboardSend() {
        if (!pickers.send()) send()
    }
    val context = LocalContext.current
    val attach = rememberAttachPicker { uris -> model.attach(context, tab, uris) }
    // The box pasted into kept its focus, so the keyboard stays as it was.
    val pasteImage: (Uri) -> Unit = { uri -> model.attach(context, tab, listOf(uri), raiseKeyboard = false) }

    /**
     * ↑/↓ through this chat's past messages, as a shell does: only from an empty box (so ↑ still
     * moves the caret in a draft), back down to the empty box. A recalled message comes back as
     * typed, its agent as the chip, and drops a reply being written.
     */
    fun recall(up: Boolean): Boolean {
        val browsing = tab.historyIndex >= 0
        if (!browsing && (!up || tab.draft.text.isNotEmpty() || tab.agent != null)) return false
        val history = chat.state.value.items.filter { it.role == ChatItem.Role.USER && it.text.isNotBlank() }.map { it.text }
        val step = stepHistory(history, tab.historyIndex, if (up) 1 else -1) ?: return false
        tab.historyIndex = step.index
        val (agent, text) = ParsedUserMessage.composerDraft(step.text)
        tab.agent = agent
        tab.draft.setTextAndPlaceCursorAtEnd(text)
        if (tab.replyTo != null) tab.selectReply(null) else focus.requestFocus()
        tab.edited()
        return true
    }

    val onKey: (KeyEvent) -> Boolean = { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else if (pickers.key(event)) {
            true
        } else {
            when {
                // A keyboard's Return sends and Shift+Return breaks the line, as on the web.
                event.key == Key.Enter && !event.isShiftPressed -> {
                    send()
                    true
                }
                // Bare arrows only: Alt+↑/↓ jumps through the transcript.
                (event.key == Key.DirectionUp || event.key == Key.DirectionDown) &&
                    !event.isAltPressed && !event.isCtrlPressed && !event.isMetaPressed && !event.isShiftPressed ->
                    recall(event.key == Key.DirectionUp)
                event.key == Key.Tab && event.isShiftPressed -> {
                    tab.permissionMode = (tab.permissionMode ?: PermissionMode.FALLBACK).next
                    true
                }
                // Ctrl+V, or the keyboard's own Paste, with a picture on the clipboard: as the menu's Paste.
                (event.key == Key.Paste || event.key == Key.V && event.isCtrlPressed && !event.isShiftPressed && !event.isAltPressed) &&
                    clipboardHoldsImage(context) ->
                    clipboardImage(context)?.let(pasteImage) != null
                else -> false
            }
        }
    }
    val hasContent = tab.hasContent
    val pickMode: (PermissionMode) -> Unit = { mode ->
        tab.permissionMode = mode
        modeOpen = false
    }

    val shape = RoundedCornerShape(tokens.rad.dp)
    Column(
        Modifier
            // `p-2 md:p-3`.
            .padding(if (mobile) 8.dp else 12.dp)
            .fillMaxWidth()
            .ppmShadow(tokens.shadowFloat, shape)
            .clip(shape)
            .background(tokens.panel.color)
            // `cursor-text`: the I-beam over the whole box, its buttons back to the arrow.
            .pointerHoverIcon(WebCursor.Text)
            // A tap on the box's padding goes to the text, as the web's container click does.
            .clickable(remember { MutableInteractionSource() }, indication = null) { tab.requestFocus() }
            .cssBorder(tokens.border.color, shape),
    ) {
        // `px-2 md:px-4 pt-2`, and the card's own `mb-2`.
        val inset = if (mobile) 8.dp else 16.dp
        tab.replyTo?.let { reply ->
            // Its buttons do not stop their clicks, so the box's own gives the text the focus back.
            ReplyCard(reply, onJump = null, Modifier.padding(start = inset, end = inset, top = 8.dp, bottom = 8.dp).onEveryTap { tab.requestFocus() }, preview = true) {
                tab.selectReply(null)
            }
        }
        tab.agent?.let { agent ->
            AgentChip(agent, Modifier.padding(start = inset, end = inset, top = 8.dp)) {
                tab.agent = null
                tab.requestFocus()
            }
        }
        AttachmentChips(tab.attachments, inset) { id -> model.removeAttachment(tab, id) }
        val mode = tab.permissionMode ?: PermissionMode.FALLBACK
        if (mobile) {
            // `flex flex-wrap items-center gap-1 px-2 pt-2 relative`: the mode panel opens over this row.
            Box(Modifier.fillMaxWidth()) {
                ChipsRow(model, tab, chat, state, narrow, onMode = { modeOpen = !modeOpen }, onModel = { modelOpen = true })
                if (modeOpen) ModePopover(mode, onDismiss = { modeOpen = false }, pick = pickMode)
            }
            // `flex items-end gap-1 px-2 py-2`.
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AttachButton(mobile = true, onClick = attach)
                // `text-sm py-1.5 max-h-20`, the `py-1.5` inside the 80 px; a phone's inputs are 16 px.
                ComposerField(
                    tab, focus, quietFocus, focusQuietly, onKey, ::keyboardSend, pasteImage, fileDrop,
                    style = ppmText(16f, lineHeight = INPUT_LINE),
                    padding = 6.dp,
                    maxHeight = 80.dp,
                    placeholder = if (running) "Follow-up..." else "Ask anything...",
                    modifier = Modifier.weight(1f),
                )
                if (running && !hasContent) {
                    SquareButton(ProductIcon.Square, 14.dp, tokens.error, Color.White, hoverFill = tokens.error.opacity(0.8), label = "Stop") { chat.cancel() }
                } else {
                    SendButton(tab, 16.dp, label = "Send", enabled = hasContent && state.canSend, onClick = ::send)
                }
            }
        } else {
            DesktopChipsRow(
                model, tab, chat, state, narrow,
                modeOpen = modeOpen,
                onMode = { modeOpen = !modeOpen },
                onModeDismiss = { modeOpen = false },
                pickMode = pickMode,
                modelOpen = modelOpen && !running,
                onModel = { modelOpen = !modelOpen },
                onModelDismiss = { modelOpen = false },
            )
            // `flex items-end gap-2 px-2.5 py-2`.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AttachButton(mobile = false, onClick = attach)
                // `text-sm leading-relaxed py-2 max-h-[90px]`, the `py-2` inside the 90 px.
                ComposerField(
                    tab, focus, quietFocus, focusQuietly, onKey, ::keyboardSend, pasteImage, fileDrop,
                    style = ppmText(14f, lineHeight = 14f * 1.625f),
                    padding = 8.dp,
                    maxHeight = 90.dp,
                    placeholder = if (running) "Follow-up or Stop..." else "Ask anything...",
                    modifier = Modifier.weight(1f),
                )
                if (running && !hasContent) {
                    SquareButton(ProductIcon.Square, 16.dp, tokens.error, Color.White, hoverFill = tokens.error.opacity(0.8), label = "Stop response") { chat.cancel() }
                } else {
                    SendButton(tab, 17.dp, label = "Send message", enabled = hasContent && state.canSend, onClick = ::send)
                }
            }
        }
    }
    if (mobile && modelOpen && !running) {
        ModelSheet(model, chat, state, onDismiss = { modelOpen = false })
    }
}

/** `text-sm`'s 1.25 rem ratio, carried to the 16 px a phone's inputs are set at. */
private const val INPUT_LINE = 16f * 1.25f / 0.875f

/**
 * The textarea: `rows={1}` growing with its text (`field-sizing: content`) up to [maxHeight], its
 * [padding] above and below inside that; [send] from the keyboard's action key, [pasteImage] for
 * a picture pasted from the text menu. While [quietFocus] holds, gaining focus starts no input
 * session, so no keyboard: set by a touch landing on the unfocused box, dropped as it loses focus.
 * [focusQuietly] focuses it that way.
 */
@Composable
private fun ComposerField(
    tab: ChatTab,
    focus: FocusRequester,
    quietFocus: MutableState<Boolean>,
    focusQuietly: MutableState<Boolean>,
    onKey: (KeyEvent) -> Boolean,
    send: () -> Unit,
    pasteImage: (Uri) -> Unit,
    fileDrop: ChatFileDrop,
    style: TextStyle,
    padding: Dp,
    maxHeight: Dp,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val inputStyle = style.copy(color = tokens.text.color)
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    // From the composition that gave the field the options [quietFocus] sets, and on the value it
    // composed: a focus asked of a field still holding the old ones brings the keyboard, and an
    // effect started late would read the flag set since.
    val focusNow = focusQuietly.value
    LaunchedEffect(focusNow) {
        if (!focusNow) return@LaunchedEffect
        focus.requestFocus()
        focusQuietly.value = false
    }
    BasicTextField(
        state = tab.draft,
        modifier = modifier
            .heightIn(max = maxHeight)
            .focusRequester(focus)
            .tracksTextFocus(tab.draft)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) quietFocus.value = false
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val finger = event.changes.any { it.type == PointerType.Touch || it.type == PointerType.Stylus }
                        if (event.type == PointerEventType.Press && finger && !focused) quietFocus.value = true
                    }
                }
            }
            .pastesImages(context, pasteImage)
            .onPreviewKeyEvent(onKey)
            // An arrow the text has no line for stays in the box, as a textarea keeps it: the
            // caret goes to the start or the end (Blink's, but on Windows), where Compose would
            // send focus on to whatever is above or below.
            .onKeyEvent { event ->
                val arrow = event.key == Key.DirectionUp || event.key == Key.DirectionDown
                if (!arrow || event.isAltPressed || event.isCtrlPressed || event.isMetaPressed) return@onKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    val end = if (event.key == Key.DirectionUp) 0 else tab.draft.text.length
                    tab.draft.edit { selection = if (event.isShiftPressed) TextRange(selection.start, end) else TextRange(end) }
                }
                true
            },
        // Typing turns a recalled message back into a draft, saves it and opens or closes the
        // pickers. Only what the user does runs this, not the recall's or a pick's own writes; the
        // IME runs it for a moved caret too, which the web's `onChange` never hears of, so only a
        // changed text counts.
        inputTransformation = remember(tab) {
            InputTransformation {
                if (!asCharSequence().contentEquals(originalText)) {
                    tab.historyIndex = -1
                    tab.updatePickers(asCharSequence(), selection.min)
                    tab.edited(asCharSequence())
                }
            }
        },
        textStyle = inputStyle,
        cursorBrush = SolidColor(tokens.accent.color),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Send,
            showKeyboardOnFocus = !quietFocus.value,
        ),
        onKeyboardAction = { send() },
        lineLimits = TextFieldLineLimits.MultiLine(),
        decorator = { field ->
            // Inside the field, so the drop lands here rather than in the field's own target.
            Box(Modifier.dragAndDropTarget(shouldStartDragAndDrop = { true }, target = fileDrop).padding(vertical = padding)) {
                if (tab.draft.text.isEmpty()) {
                    BasicText(placeholder, style = inputStyle.copy(color = tokens.text3.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                field()
            }
        },
    )
}

/**
 * The subagent picked for the next message: `rounded-md border-sky-500/30 bg-sky-500/10 px-2 py-1
 * text-xs font-medium`, sky-600 or, under a dark system theme (`dark:` follows the OS, not PPM's
 * theme), sky-400; the cross `rounded-sm p-0.5 hover:bg-sky-500/20` takes it off.
 */
@Composable
private fun AgentChip(agent: String, modifier: Modifier = Modifier, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val color = (if (isSystemInDarkTheme()) sky400 else sky600).color
    // The chip stands on its icon's bottom edge, which is the border, `py-1` and half of what
    // the 16 px row leaves around the 14 px icon above its own bottom.
    val room = strutRoomUnder(belowPx = with(LocalDensity.current) { cssBorderPx(1.dp) + 5.dp.toPx() })
    Row(Modifier.fillMaxWidth().then(modifier).padding(bottom = room)) {
        Row(
            Modifier
                .clip(shape)
                .background(sky500.opacity(0.1).color)
                .cssBorder(sky500.opacity(0.3).color, shape)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(ProductIcon.Bot, 14.dp, color)
            BasicText(agent, style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = color))
            val cross = RoundedCornerShape(6.dp)
            Box(
                Modifier
                    .clip(cross)
                    .pointerHoverIcon(WebCursor.Default)
                    .ppmClickable(pressedColor = sky500.opacity(0.2).color, hoverColor = sky500.opacity(0.2).color, shape = cross, onClickLabel = "Remove $agent agent", onClick = onRemove)
                    .padding(2.dp),
            ) {
                PpmIcon(ProductIcon.X, 12.dp, color)
            }
        }
    }
}

/**
 * How far below an `inline-flex` chip the line it sits on reaches, in a block of the page's 16 px
 * text on 24 px lines: the chip stands on the baseline of its first item, [belowPx] above its
 * bottom, and the line's strut goes further down than that. Chrome rounds the strut in device
 * pixels ([PpmFonts.chromeAscentDescent], the top half-leading floored): 1.1 px under the agent
 * chip at 2.625x, 1 px at 2x.
 */
@Composable
private fun strutRoomUnder(belowPx: Float): Dp {
    val density = LocalDensity.current.density
    val (up, down) = PpmFonts.chromeAscentDescent(16f * density, PpmFonts.SANS_ASCENT, PpmFonts.SANS_DESCENT)
    val leading = 24f * density - up - down
    val strut = down + leading - floor(leading / 2)
    return ((strut - belowPx) / density).coerceAtLeast(0f).dp
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipsRow(model: AppModel, tab: ChatTab, chat: ChatController, state: ChatState, narrow: Boolean, onMode: () -> Unit, onModel: () -> Unit) {
    val running = state.isRunning
    val name = modelName(model, chat, state)
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val mode = tab.permissionMode ?: PermissionMode.FALLBACK
        NeutralChip(mode.icon, if (narrow) null else mode.label, narrow, labelMax = 100.dp, onClick = onMode)
        ModelChip(ModelNames.chipLabel(name, state.effort ?: Effort.DEFAULT.value), state.thinking, disabled = running, onClick = onModel)
        if (running) {
            NeutralChip(tab.priority.icon, if (narrow) null else tab.priority.label, narrow) { tab.priority = tab.priority.following }
        }
        PromptCacheChip(state.promptCache)
    }
}

/**
 * The desktop chips (`flex flex-wrap items-center gap-1.5 px-2.5 pt-2.5`): each panel opens over
 * its own chip, the mode's from `relative shrink-0`, the model's from `relative min-w-0`. On a
 * narrow chat (`@max-[420px]/chat:flex-nowrap`) they stay on one line and the model chip gives way.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopChipsRow(
    model: AppModel,
    tab: ChatTab,
    chat: ChatController,
    state: ChatState,
    narrow: Boolean,
    modeOpen: Boolean,
    onMode: () -> Unit,
    onModeDismiss: () -> Unit,
    pickMode: (PermissionMode) -> Unit,
    modelOpen: Boolean,
    onModel: () -> Unit,
    onModelDismiss: () -> Unit,
) {
    val running = state.isRunning
    val name = modelName(model, chat, state)
    val mode = tab.permissionMode ?: PermissionMode.FALLBACK
    val modeChip = @Composable {
        Box {
            NeutralChip(mode.icon, if (narrow) null else mode.label, narrow, labelMax = 100.dp, onClick = onMode)
            if (modeOpen) ModePopover(mode, onDismiss = onModeDismiss, pick = pickMode)
        }
    }
    val modelChip = @Composable { modifier: Modifier ->
        Box(modifier) {
            ModelChip(ModelNames.chipLabel(name, state.effort ?: Effort.DEFAULT.value), state.thinking, disabled = running, onClick = onModel)
            if (modelOpen) ModelPopover(model, chat, state, onDismiss = onModelDismiss)
        }
    }
    val rest = @Composable {
        if (running) {
            NeutralChip(tab.priority.icon, if (narrow) null else tab.priority.label, narrow) { tab.priority = tab.priority.following }
        }
        PromptCacheChip(state.promptCache)
    }
    val padding = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 10.dp)
    if (narrow) {
        Row(padding, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            modeChip()
            modelChip(Modifier.weight(1f, fill = false))
            rest()
        }
    } else {
        FlowRow(
            padding,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            modeChip()
            modelChip(Modifier)
            rest()
        }
    }
}

/** The model chip's name: the catalog's label, else one made from the id, else "Model". */
@Composable
private fun modelName(model: AppModel, chat: ChatController, state: ChatState): String {
    val catalog = model.models
    val entries = catalog?.entries?.collectAsState()?.value
    return remember(entries, state.model, chat.providerId) {
        catalog?.displayName(state.model, chat.providerId) ?: state.model?.let(ModelNames::displayName) ?: "Model"
    }
}

/**
 * `px-[9px] py-1 rounded-full text-[11.5px] text-text-2 bg-panel-2 border border-border-soft`,
 * `hover:text-text-primary hover:border-border` faded in: the mode and the priority. [label] null
 * draws the icon alone, at `px-[7px]`.
 */
@Composable
private fun NeutralChip(icon: ProductIcon, label: String?, narrow: Boolean, labelMax: Dp = Dp.Unspecified, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val fade = colorsFade(hovered)
    val lit = if (pressed) 1.0 else fade.toDouble()
    val color = tokens.text.mix(tokens.text2, lit)
    Row(
        Modifier
            .clip(CircleShape)
            .background(tokens.panel2.color)
            .pointerHoverIcon(WebCursor.Default)
            .hoverable(source)
            .clickable(source, indication = null, onClick = onClick)
            .cssBorder(tokens.border.mix(tokens.borderSoft, lit).color, CircleShape)
            // `px-[9px] py-1` (`px-[7px]` narrow).
            .padding(horizontal = if (narrow) 7.dp else 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // An icon alone still takes the line's height, as the text beside it would.
        Box(Modifier.height(17.25.dp), contentAlignment = Alignment.Center) { PpmIcon(icon, 12.dp, color.color) }
        if (label != null) {
            BasicText(
                label,
                Modifier.widthIn(max = labelMax),
                style = chipText.copy(color = color.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * `text-primary bg-accent-wash border border-accent-wash-border`, sparkles, a brain while
 * thinking; `hover:brightness-110` faded in. 50% while the turn runs, when a mouse over it still
 * brightens it, as CSS `:hover` matches a disabled button.
 */
@Composable
private fun ModelChip(text: String, thinking: Boolean, disabled: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val fade = colorsFade(hovered)
    val lit = if (pressed) 1f else fade
    Row(
        Modifier
            .alpha(if (disabled) 0.5f else 1f)
            .brightness(1f + 0.1f * lit)
            .clip(CircleShape)
            .background(tokens.accentWash.color)
            // `disabled:cursor-default`: the arrow either way.
            .pointerHoverIcon(WebCursor.Default)
            .hoverable(source)
            .clickable(source, indication = null, enabled = !disabled, onClick = onClick)
            .cssBorder(tokens.accentWashBorder.color, CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(ProductIcon.Sparkles, 14.dp, tokens.accent.color)
        BasicText(
            text,
            Modifier.widthIn(max = 140.dp),
            style = chipText.copy(color = tokens.accent.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (thinking) PpmIcon(ProductIcon.Brain, 12.dp, tokens.accent.color)
    }
}

/**
 * How long the prompt cache has left (`PromptCacheChip`): a countdown while warm, red with the
 * time it has sat idle once gone; nothing until PPM has measured the session.
 */
@Composable
private fun PromptCacheChip(cache: PromptCacheState?) {
    val tokens = LocalPpmTokens.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(cache) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val status = PromptCacheStatus.of(cache, now.toDouble())
    val label = status.label ?: return
    val cold = status is PromptCacheStatus.Cold
    val color = if (cold) tokens.error else tokens.text2
    Row(
        Modifier
            .clip(CircleShape)
            .background((if (cold) tokens.error.opacity(0.1) else tokens.panel2).color)
            .cssBorder((if (cold) tokens.error.opacity(0.4) else tokens.borderSoft).color, CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(ProductIcon.Clock, 12.dp, color.color)
        BasicText(label, style = chipText.copy(color = color.color, fontFeatureSettings = "tnum"), maxLines = 1)
    }
}

/**
 * `size-9 rounded-[11px]` with the float shadow, [hoverFill] under a mouse (faded in) or a finger;
 * 30% and flat when disabled, when a mouse over it still lights it.
 */
@Composable
private fun SquareButton(
    icon: ProductIcon,
    iconSize: Dp,
    fill: Rgba,
    tint: Color,
    hoverFill: Rgba,
    label: String,
    enabled: Boolean = true,
    spinning: Boolean = false,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(11.dp)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val fade = colorsFade(hovered)
    val lit = if (pressed && enabled) 1.0 else fade.toDouble()
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.3f)
            .size(36.dp)
            .then(if (enabled) Modifier.ppmShadow(tokens.shadowFloat, shape) else Modifier)
            .clip(shape)
            .background(hoverFill.mix(fill, lit).color)
            // Send's `disabled:cursor-not-allowed`.
            .pointerHoverIcon(if (enabled) WebCursor.Default else WebCursor.NotAllowed)
            .hoverable(source)
            .clickable(source, indication = null, enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, iconSize, tint, spinning = spinning)
    }
}

/**
 * Send, or while a file is still uploading the send waiting on it: a 16 px spinner labelled
 * "Cancel queued send", which a press takes back.
 */
@Composable
private fun SendButton(tab: ChatTab, arrowSize: Dp, label: String, enabled: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val queued = tab.pendingSend
    SquareButton(
        if (queued) ProductIcon.Loader2 else ProductIcon.ArrowUp, if (queued) 16.dp else arrowSize,
        tokens.accent, tokens.accentFg.color, hoverFill = tokens.accent.opacity(0.9),
        label = if (queued) "Cancel queued send" else label, enabled = enabled, spinning = queued,
        onClick = { if (queued) tab.pendingSend = false else onClick() },
    )
}

/**
 * The modes (`ModeSelector`, `w-72 md:w-80`): over what holds it — the chips row on a phone, the
 * chip on a tablet — with its left edge on that one's, 4 px clear of it. A press anywhere else
 * closes it and goes on to what it landed on, as the web's mousedown listener lets it. A row's
 * `hover:bg-surface-elevated` is the popover's own fill on the web, so this lights it in the
 * hover tint instead.
 */
@Composable
private fun ModePopover(current: PermissionMode, onDismiss: () -> Unit, pick: (PermissionMode) -> Unit) {
    val tokens = LocalPpmTokens.current
    val width = if (LocalPpmMobile.current) 288.dp else 320.dp
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    PlacedPopup(remember(gap) { aboveStart(gap) }, onDismiss, passThrough = true) {
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier
                .width(width)
                .ppmShadow(tokens.shadowPanel, shape)
                .clip(shape)
                .background(tokens.panel2.over(tokens.bgSolid).color)
                .cssBorder(tokens.border.color, shape),
        ) {
            Row(
                Modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = CssSides.Bottom).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText("Modes", Modifier.weight(1f), style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color))
                val kbd = RoundedCornerShape(4.dp)
                BasicText(
                    "Shift + Tab",
                    Modifier
                        .clip(kbd)
                        .background(tokens.panel2.color)
                        .cssBorder(tokens.border.color, kbd)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = ppmText(10f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text3.color),
                )
            }
            Column(Modifier.padding(vertical = 4.dp)) {
                for (mode in PermissionMode.entries) {
                    val active = mode == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (active) tokens.panel2.color else Color.Transparent)
                            .ppmClickable(pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color) { pick(mode) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PpmIcon(mode.icon, 16.dp, tokens.text2.color, Modifier.padding(top = 2.dp))
                        Column(Modifier.weight(1f)) {
                            BasicText(mode.label, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color))
                            BasicText(mode.detail, style = ppmText(12f, lineHeight = 16.5f).copy(color = tokens.text3.color))
                        }
                        if (active) PpmIcon(ProductIcon.Check, 16.dp, tokens.accent.color, Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

/** Over the anchor, left edges together, [gap] clear of it (`absolute bottom-full left-0 mb-1`), kept on screen. */
private fun aboveStart(gap: Int) = { anchor: IntRect, window: IntSize, box: IntSize ->
    IntOffset(anchor.left.coerceIn(0, maxOf(0, window.width - box.width)), maxOf(0, anchor.top - gap - box.height))
}

/**
 * Model, effort and thinking in one sheet (`ModelThinkingSelector` on a phone). A pick leaves the
 * sheet open, as on the web.
 */
@Composable
private fun ModelSheet(model: AppModel, chat: ChatController, state: ChatState, onDismiss: () -> Unit) {
    BottomSheet(onDismiss) {
        // `px-1 pb-2`.
        ModelColumns(
            model, chat, state,
            Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.8f),
            Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            onPicked = {},
        )
    }
}

/**
 * The same on a tablet: a popover over the chip (`absolute bottom-full left-0 mb-1 w-80
 * rounded-lg border popover-solid shadow-panel max-h-[70vh] overflow-y-auto`). A model or effort
 * picked closes it, the thinking switch does not; a press anywhere else closes it and goes on.
 */
@Composable
private fun ModelPopover(model: AppModel, chat: ChatController, state: ChatState, onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    PlacedPopup(remember(gap) { aboveStart(gap) }, onDismiss, passThrough = true) {
        val shape = RoundedCornerShape(12.dp)
        ModelColumns(
            model, chat, state,
            Modifier
                .width(320.dp)
                .ppmShadow(tokens.shadowPanel, shape)
                .clip(shape)
                .background(tokens.panel2.over(tokens.bgSolid).color)
                .cssBorder(tokens.border.color, shape)
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.7f),
            Modifier,
            onPicked = onDismiss,
        )
    }
}

/**
 * The picker's content (`flex divide-x divide-border`): the provider's models on the left, the
 * efforts and the thinking switch on the right, scrolling inside [box] past its height; [inner]
 * pads the columns inside the scroll. [onPicked] runs after a model or an effort is picked.
 */
@Composable
private fun ModelColumns(model: AppModel, chat: ChatController, state: ChatState, box: Modifier, inner: Modifier, onPicked: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val catalog = model.models
    val entries = catalog?.entries?.collectAsState()?.value
    LaunchedEffect(catalog, chat.providerId) { catalog?.load(chat.providerId) }
    val models: List<ModelOption> = remember(catalog, entries) { catalog?.models(chat.providerId).orEmpty() }
    val status: ModelListStatus? = remember(catalog, entries) { catalog?.status(chat.providerId) ?: ModelListStatus.LOADING }
    val effort = Effort.of(state.effort) ?: Effort.DEFAULT
    Row(
        box
            .verticalScroll(rememberScrollState())
            .then(inner)
            .height(IntrinsicSize.Min),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            SectionLabel("Model")
            if (status != null) {
                BasicText(
                    status.text,
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                )
            }
            for (option in models) {
                OptionRow(active = option.value == state.model, onClick = {
                    chat.setModel(option.value)
                    onPicked()
                }) {
                    BasicText(
                        ModelNames.shortLabel(option.label),
                        Modifier.weight(1f),
                        style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().cssBorder(tokens.border.color, sides = CssSides.Start).padding(vertical = 4.dp)) {
            SectionLabel("Effort")
            for (option in Effort.entries) {
                OptionRow(active = option == effort, onClick = {
                    chat.setEffort(option)
                    onPicked()
                }) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        BasicText(option.label, style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text.color), maxLines = 1)
                        if (option == Effort.DEFAULT) {
                            BasicText(
                                "Default",
                                Modifier.padding(start = 8.dp),
                                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().cssBorder(tokens.border.color, sides = CssSides.Top))
            ThinkingRow(state.thinking) { chat.setThinking(!state.thinking) }
        }
    }
}

/** `px-3 py-1.5 text-xs font-medium text-text-secondary`. */
@Composable
private fun SectionLabel(text: String) {
    val tokens = LocalPpmTokens.current
    BasicText(
        text,
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color),
    )
}

/**
 * `flex items-center gap-3 px-3 min-h-[44px] text-sm`, lit and checked when chosen. Its
 * `hover:bg-surface-elevated` is the sheet's own fill, so the hover tint lights it instead.
 */
@Composable
private fun OptionRow(active: Boolean, onClick: () -> Unit, label: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(if (active) tokens.panel2.color else Color.Transparent)
            .ppmClickable(pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        label()
        if (active) PpmIcon(ProductIcon.Check, 16.dp, tokens.accent.color)
    }
}

/** `role="switch"`: a 36×20 track, its 16 px knob 2 px in when off and 16 px in when on; lit at once under a mouse. */
@Composable
private fun ThinkingRow(on: Boolean, onToggle: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val knob by animateDpAsState(if (on) 16.dp else 2.dp, tween(150), label = "knob")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .ppmClickable(pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color, fade = false, onClick = onToggle)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PpmIcon(ProductIcon.Brain, 16.dp, tokens.text.color)
        BasicText("Thinking", Modifier.weight(1f), style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text.color))
        Box(Modifier.size(36.dp, 20.dp).clip(CircleShape).background((if (on) tokens.accent else tokens.border).color)) {
            Box(Modifier.offset(x = knob, y = 2.dp).size(16.dp).clip(CircleShape).background(Color.White))
        }
    }
}

val PermissionMode.icon: ProductIcon
    get() = when (this) {
        PermissionMode.DEFAULT -> ProductIcon.Hand
        PermissionMode.ACCEPT_EDITS -> ProductIcon.Code
        PermissionMode.PLAN -> ProductIcon.ClipboardList
        PermissionMode.BYPASS_PERMISSIONS -> ProductIcon.ShieldOff
    }

val MessagePriority.icon: ProductIcon
    get() = when (this) {
        MessagePriority.NOW -> ProductIcon.Zap
        MessagePriority.NEXT -> ProductIcon.ListOrdered
        MessagePriority.LATER -> ProductIcon.Clock
    }
