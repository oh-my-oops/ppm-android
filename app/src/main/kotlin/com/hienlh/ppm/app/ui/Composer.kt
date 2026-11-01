package com.hienlh.ppm.app.ui

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setSelection
import androidx.compose.ui.semantics.textSelectionRange
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.ui.unit.sp
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
import com.hienlh.ppm.chat.textareaBreakMark
import com.hienlh.ppm.chat.textareaClipboard
import com.hienlh.ppm.design.ChromeTextAreaBox
import com.hienlh.ppm.design.CssAlign
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CssExact
import com.hienlh.ppm.design.CssRow
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
import com.hienlh.ppm.design.chromeTextArea
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.cssExact
import com.hienlh.ppm.design.cssFlex
import com.hienlh.ppm.design.cssIcon
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.cssShrink
import com.hienlh.ppm.design.cssSize
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberChromeTextArea
import com.hienlh.ppm.design.rememberCssExact
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.ModelOption
import com.hienlh.ppm.protocol.ParsedUserMessage
import com.hienlh.ppm.protocol.PromptCacheState
import com.hienlh.ppm.protocol.ProviderInfo
import com.hienlh.ppm.state.ChatController
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.ChatState
import com.hienlh.ppm.state.Effort
import com.hienlh.ppm.state.MessagePriority
import com.hienlh.ppm.state.ModelCatalog
import com.hienlh.ppm.state.ModelListStatus
import com.hienlh.ppm.state.ModelNames
import com.hienlh.ppm.state.PermissionMode
import com.hienlh.ppm.state.PromptCacheStatus
import com.hienlh.ppm.state.stepHistory
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

/** Chips of the composer row: 11.5 px, with the body's 1.5 line height. */
private val chipText = ppmText(11.5f, lineHeight = 17.25f)

/** `showCancel`: Stop takes Send's place while a turn runs and the box has nothing to send. */
internal fun showsStop(running: Boolean, hasContent: Boolean) = running && !hasContent

/** The box's placeholder: a turn running asks for a follow-up, and a tablet's adds that Stop is there. */
internal fun composerPlaceholder(running: Boolean, mobile: Boolean) = when {
    !running -> "Ask anything..."
    mobile -> "Follow-up..."
    else -> "Follow-up or Stop..."
}

/**
 * The message box (`message-input.tsx`): the chips that set how the next turn runs over the text
 * and Send, or Stop while the turn runs and nothing is typed. A phone draws the `md:hidden` rows,
 * a tablet the desktop ones. [narrow] is the chat's `@max-[420px]` — the mode and priority chips
 * keep only their icons, and on a tablet the chips stay on one line. [fileDrop] takes what is
 * dropped on the text, where its own field would; the [pickers] open over the box take its keys
 * first.
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
    // A new chat's box, or one handed text to edit, takes the focus 100 ms after it first shows
    // (`autoFocus`): the box of the layout on screen, so a phone's too.
    LaunchedEffect(tab) {
        if (!tab.focusOnShow) return@LaunchedEffect
        tab.focusOnShow = false
        delay(100)
        if (TextInputFocus.activated) {
            tab.requestFocus()
        } else if (TextInputFocus.field !== tab.draft) {
            quietFocus.value = true
            focusQuietly.value = true
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

    // The mic, for the engine Settings → Voice picked; asked for the microphone the first time.
    val engine = model.voiceEngine
    val micShown = model.voice.supported(engine)
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> model.voice.permitted(granted) }
    val toggleMic = { model.voice.toggle(tab, engine) { askMic.launch(Manifest.permission.RECORD_AUDIO) } }
    // Mod+Shift+V and the palette reach the chat in front, which answers only with a mic to show.
    val shortcutMic by rememberUpdatedState(if (micShown) toggleMic else null)
    LaunchedEffect(tab) { model.voiceToggles.filter { it == tab.tabId }.collect { shortcutMic?.invoke() } }

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
                    tab.pickMode((tab.permissionMode ?: PermissionMode.FALLBACK).next)
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
        tab.pickMode(mode)
        modeOpen = false
    }
    // Send asks no connection, as on the web: a new chat's first message makes the session, and
    // what is sent while a chat reconnects goes out once it is back.
    val canSend = hasContent && !tab.firstSendPending
    // `disabled` while the first message goes: the text, the paperclip and the mic at half strength.
    val locked = tab.firstSendPending

    val shape = RoundedCornerShape(tokens.rad.dp)
    // `p-2 md:p-3` around the box, and its rows, at their exact heights: what is above the box
    // starts where they put it.
    val pad = if (mobile) 8.dp else 12.dp
    CssColumn(Modifier.fillMaxWidth().padding(horizontal = pad), top = pad, bottom = pad) {
    CssColumn(
        Modifier
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
        AttachmentChips(tab.attachments, inset, onRemove = { id -> model.removeAttachment(tab, id) }, onTextTap = { tab.requestFocus() })
        val mode = tab.permissionMode ?: PermissionMode.FALLBACK
        if (mobile) {
            // `flex flex-wrap items-center gap-1 px-2 pt-2 relative`: the mode panel opens over this row.
            val chips = rememberCssExact()
            Box(Modifier.fillMaxWidth().cssExact(chips)) {
                ChipsRow(model, tab, chat, state, narrow, chips, onMode = { modeOpen = !modeOpen }, onModel = { modelOpen = true })
                if (modeOpen && !tab.providerPending) ModePopover(mode, onDismiss = { modeOpen = false }, pick = pickMode)
            }
            // `flex items-end gap-1 px-2 py-2`.
            CssRow(Modifier.fillMaxWidth(), gap = 4.dp, start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp, align = CssAlign.End) {
                AttachButton(mobile = true, enabled = !locked, onClick = attach)
                // `text-sm py-1.5 max-h-20`, the `py-1.5` inside the 80 px; a phone's inputs are 16 px.
                ComposerField(
                    tab, focus, quietFocus, focusQuietly, onKey, ::keyboardSend, pasteImage, fileDrop,
                    style = ppmText(16f, lineHeight = INPUT_LINE),
                    padding = 6.dp,
                    // `px-2`, the 32 px attach button and `gap-1`.
                    cssStart = 44.dp,
                    maxHeight = 80.dp,
                    placeholder = composerPlaceholder(running, mobile = true),
                    enabled = !locked,
                    modifier = Modifier.cssFlex(),
                )
                if (micShown) MicButton(tab, mobile = true, enabled = !locked, onClick = toggleMic)
                if (showsStop(running, hasContent)) {
                    SquareButton(ProductIcon.Square, 14.dp, tokens.error, Color.White, hoverFill = tokens.error.opacity(0.8), label = "Stop") { chat.cancel() }
                } else {
                    SendButton(tab, 16.dp, label = "Send", enabled = canSend, onClick = ::send)
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
            CssRow(Modifier.fillMaxWidth(), gap = 8.dp, start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp, align = CssAlign.End) {
                AttachButton(mobile = false, enabled = !locked, onClick = attach)
                // `text-sm leading-relaxed py-2 max-h-[90px]`, the `py-2` inside the 90 px.
                ComposerField(
                    tab, focus, quietFocus, focusQuietly, onKey, ::keyboardSend, pasteImage, fileDrop,
                    style = ppmText(14f, lineHeight = 14f * 1.625f),
                    padding = 8.dp,
                    // `px-2.5`, the 34 px attach button and `gap-2`.
                    cssStart = 52.dp,
                    maxHeight = 90.dp,
                    placeholder = composerPlaceholder(running, mobile = false),
                    enabled = !locked,
                    modifier = Modifier.cssFlex(),
                )
                if (micShown) MicButton(tab, mobile = false, enabled = !locked, onClick = toggleMic)
                if (showsStop(running, hasContent)) {
                    SquareButton(ProductIcon.Square, 16.dp, tokens.error, Color.White, hoverFill = tokens.error.opacity(0.8), label = "Stop response") { chat.cancel() }
                } else {
                    SendButton(tab, 17.dp, label = "Send message", enabled = canSend, onClick = ::send)
                }
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
 * [padding] above and below inside that, [cssStart] the CSS length before it in its row (for the
 * caret); [send] from the keyboard's action key, [pasteImage] for
 * a picture pasted from the text menu. While [quietFocus] holds, gaining focus starts no input
 * session, so no keyboard: set by a touch landing on the unfocused box, dropped as it loses focus.
 * [focusQuietly] focuses it that way. [onKey] hears a key first, and a Tab it leaves alone moves the
 * focus on ([tabMovesFocus]).
 */
@Composable
internal fun ComposerField(
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
    cssStart: Dp,
    maxHeight: Dp,
    placeholder: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val inputStyle = style.copy(color = tokens.text.color)
    // Its lines, caret and scroll drawn as Chrome draws a textarea's, wrapped where it wraps them.
    val area = rememberChromeTextArea(tab.draft, inputStyle, padding, cssStart, marks = ::textareaBreakMark)
    val selectionColors = LocalTextSelectionColors.current
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
    CompositionLocalProvider(LocalTextSelectionColors provides area.selectionColors(selectionColors), textareaClipboard()) {
        BasicTextField(
            state = tab.draft,
            modifier = modifier
                .chromeTextArea(area, maxHeight)
                .plainTextSemantics(tab.draft, enabled)
                // `disabled:opacity-50`.
                .alpha(if (enabled) 1f else 0.5f)
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
                // Shift+Tab, and Tab for an open picker, are onKey's, which is asked first; any other
                // Tab moves the focus on, as a textarea's does, instead of typing a tab.
                .tabMovesFocus()
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
            outputTransformation = area.outputTransformation,
            enabled = enabled,
            textStyle = inputStyle.copy(color = Color.Transparent),
            cursorBrush = area.brush,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send,
                showKeyboardOnFocus = !quietFocus.value,
            ),
            onKeyboardAction = { send() },
            lineLimits = TextFieldLineLimits.MultiLine(),
            onTextLayout = area.onTextLayout,
            interactionSource = area.interactions,
            scrollState = area.fieldScroll,
            decorator = { field ->
                // Inside the field, so the drop lands here rather than in the field's own target.
                ChromeTextAreaBox(
                    area,
                    placeholder = placeholder.takeIf { tab.draft.text.isEmpty() },
                    placeholderColor = tokens.text3.color,
                    selectionColor = selectionColors.backgroundColor,
                    scrollbarColor = tokens.border.color,
                    modifier = Modifier.dragAndDropTarget(shouldStartDragAndDrop = { true }, target = fileDrop),
                ) { field() }
            },
        )
    }
}

/**
 * What an accessibility service reads of the field and does to its caret, in the text the user typed. The
 * field's own semantics describe its text as laid out, marks and all (`ChromeTextArea`'s): a service reads
 * "/<U+FEFF>skills" (what uiautomator dumps), is told the caret is where it is in that, and moving it a
 * character at a time stops it on 1, 3, 5, 7, 9. So the text, the caret and the action that sets it are stated
 * together, in the draft's own offsets: the text alone leaves ACTION_SET_SELECTION landing a character off for
 * every mark before it, because the field still reads its offsets in the laid-out text. The field's own check
 * stays: nothing moves while it is disabled, nor to an offset past the text.
 *
 * Line and paragraph traversal stay approximate: they read the line ends from the field's text layout, whose
 * offsets include the marks, and no semantics property can replace that layout. Measured in a 150 dp box over
 * a 53-character draft, three line steps from the start end at 25, 48, 48 and never reach the text's end
 * (25, 25, 25 without this: the caret stays where the first step put it).
 */
private fun Modifier.plainTextSemantics(state: TextFieldState, enabled: Boolean): Modifier = semantics {
    editableText = AnnotatedString(state.text.toString())
    textSelectionRange = state.selection
    setSelection { start, end, _ ->
        val length = state.text.length
        if (!enabled || start !in 0..length || end !in 0..length) return@setSelection false
        state.edit { selection = TextRange(start, end) }
        true
    }
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

@Composable
private fun ChipsRow(model: AppModel, tab: ChatTab, chat: ChatController, state: ChatState, narrow: Boolean, exact: CssExact, onMode: () -> Unit, onModel: () -> Unit) {
    val running = state.isRunning
    val name = modelName(model, chat, state)
    CssRow(Modifier.fillMaxWidth(), gap = 4.dp, start = 8.dp, end = 8.dp, top = 8.dp, wrap = true, exact = exact) {
        val mode = tab.permissionMode ?: PermissionMode.FALLBACK
        // While the provider resolves, nothing to pick a mode or a model for yet; while the first
        // message goes, the model is fixed and a mode picked changes nothing.
        if (!tab.providerPending) ModeChip(mode, narrow, onMode)
        if (choosesProvider(tab)) ProviderChip(model, tab)
        if (!tab.providerPending && !tab.firstSendPending) {
            // `relative min-w-0`: a block around the chip, as tall as the line the chip stands on.
            CssColumn(bottom = chipStrut(14.dp)) {
                ComposerModelChip(name, state, onModel)
            }
        }
        if (running) {
            PriorityChip(tab, narrow)
        }
        PromptCacheChip(state.promptCache)
    }
}

/**
 * How far the line under a chip reaches below it, where the chip is an `inline-flex` button in a
 * block (the model's `relative min-w-0`, the desktop mode's `relative shrink-0`, the provider's
 * `relative`): it stands on its first item's bottom edge, its [icon]'s — the border, `py-1` and,
 * when it is [labelled], half of what its 17.25 px line leaves around the icon above its own
 * bottom. 0.75 px under the model chip at 2x, nothing under a labelled mode chip, 4 under a bare one.
 */
@Composable
private fun chipStrut(icon: Dp, labelled: Boolean = true): Dp {
    val below = with(LocalDensity.current) {
        cssBorderPx(1.dp) + 4.dp.toPx() + if (labelled) max(0f, 17.25f.sp.toPx() - icon.toPx()) / 2 else 0f
    }
    return strutRoomUnder(below)
}

/**
 * The desktop chips (`flex flex-wrap items-center gap-1.5 px-2.5 pt-2.5`): each panel opens over
 * its own chip, the mode's from `relative shrink-0`, the model's from `relative min-w-0`. On a
 * narrow chat (`@max-[420px]/chat:flex-nowrap`) they stay on one line and the model chip gives way.
 */
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
    // As on the phone: no mode while the provider resolves, no model then or while the first message goes.
    CssRow(Modifier.fillMaxWidth(), gap = 6.dp, start = 10.dp, end = 10.dp, top = 10.dp, wrap = !narrow) {
        if (!tab.providerPending) {
            CssColumn(bottom = chipStrut(12.dp, labelled = !narrow)) {
                ModeChip(mode, narrow, onMode)
                if (modeOpen) ModePopover(mode, onDismiss = onModeDismiss, pick = pickMode)
            }
        }
        if (choosesProvider(tab)) ProviderChip(model, tab)
        if (!tab.providerPending && !tab.firstSendPending) {
            // On one line, the chip that gives way.
            CssColumn(Modifier.cssShrink(), bottom = chipStrut(14.dp)) {
                ComposerModelChip(name, state, onModel)
                if (modelOpen) ModelPopover(model, chat, state, onDismiss = onModelDismiss)
            }
        }
        if (running) {
            PriorityChip(tab, narrow)
        }
        PromptCacheChip(state.promptCache)
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
 * A chip as the web's `<button aria-label=[name] disabled>` is to a screen reader: one node with the
 * name, a button's role, its press ([action]) and, while [enabled] is false, disabled. Left to
 * `clickable` and a `contentDescription`, Compose hands the services three nodes: the clickable,
 * which presses and can be disabled but has no name, the name as a child of it that does neither,
 * and the text as a second child. Clearing what lies below leaves the one node holding it all. Put it
 * ahead of the chip's own `clickable`, whose gestures it leaves alone and whose semantics it replaces.
 */
private fun Modifier.chipSemantics(name: String, enabled: Boolean = true, action: () -> Unit): Modifier =
    clearAndSetSemantics {
        contentDescription = name
        role = Role.Button
        onClick {
            if (enabled) action()
            enabled
        }
        if (!enabled) disabled()
    }

/**
 * `px-[9px] py-1 rounded-full text-[11.5px] text-text-2 bg-panel-2 border border-border-soft`,
 * `hover:text-text-primary hover:border-border` faded in: the mode and the priority. [label] null
 * draws the icon alone, at `px-[7px]`; [name] is the chip's `aria-label`.
 */
@Composable
private fun NeutralChip(icon: ProductIcon, label: String?, name: String, narrow: Boolean, modifier: Modifier = Modifier, labelMax: Dp = Dp.Unspecified, onClick: () -> Unit) =
    NeutralChip(label, name, narrow, labelMax, onClick, modifier) { color -> PpmIcon(icon, 12.dp, color, Modifier.cssIcon(12.dp)) }

/** [NeutralChip] with its own [leading] mark, drawn in the chip's text colour. */
@Composable
private fun NeutralChip(label: String?, name: String, narrow: Boolean, labelMax: Dp, onClick: () -> Unit, modifier: Modifier = Modifier, leading: @Composable (Color) -> Unit) {
    val tokens = LocalPpmTokens.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val fade = colorsFade(hovered)
    val lit = if (pressed) 1.0 else fade.toDouble()
    val color = tokens.text.mix(tokens.text2, lit)
    val side = if (narrow) 7.dp else 9.dp
    CssRow(
        modifier
            .chipSemantics(name, action = onClick)
            .clip(CircleShape)
            .background(tokens.panel2.color)
            .pointerHoverIcon(WebCursor.Default)
            .hoverable(source)
            .clickable(source, indication = null, onClick = onClick)
            .cssBorder(tokens.border.mix(tokens.borderSoft, lit).color, CircleShape),
        // `px-[9px] py-1` (`px-[7px]` narrow).
        gap = 6.dp,
        start = side,
        end = side,
        top = 4.dp,
        bottom = 4.dp,
    ) {
        // An icon alone is the chip's whole height (21.5 px on the web): the hidden label leaves no line.
        leading(color.color)
        if (label != null) {
            BasicText(
                label,
                Modifier.cssShrink().widthIn(max = labelMax).cssLines(17.25f),
                style = chipText.copy(color = color.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * `ModeChip`, in both rows: the permission mode the next turn runs under. Its `title`, the mouse's
 * tooltip, and its `aria-label`, the name a screen reader gives it, are the same text.
 */
@Composable
internal fun ModeChip(mode: PermissionMode, narrow: Boolean, onClick: () -> Unit) {
    val name = modeChipName(mode)
    NeutralChip(
        mode.icon,
        if (narrow) null else mode.label,
        name,
        narrow,
        Modifier.webTitle(name),
        labelMax = 100.dp,
        onClick = onClick,
    )
}

/** `ModeChip`'s `title` and `aria-label`. */
internal fun modeChipName(mode: PermissionMode) = "Permission mode: ${mode.label}"

/**
 * The chip of `ModelThinkingSelector`, in both rows: its `title` ([modelChipTitle]) and its
 * `aria-label` ([modelChipName]).
 */
@Composable
private fun ComposerModelChip(name: String, state: ChatState, onClick: () -> Unit) {
    val effort = state.effort ?: Effort.DEFAULT.value
    val text = ModelNames.chipLabel(name, effort)
    val running = state.isRunning
    ModelChip(
        text,
        modelChipName(name, effort, state.thinking),
        state.thinking,
        disabled = running,
        modifier = Modifier.webTitle(modelChipTitle(running, text)),
        onClick = onClick,
    )
}

/** The model chip's `title`: its own text, or while a turn runs why a press does nothing. */
internal fun modelChipTitle(running: Boolean, text: String) = if (running) "Can't change while running" else text

/**
 * The model chip's `aria-label`: the model, the effort by the value the server knows it by
 * ("xhigh", where the chip says "Extra") and, when it is on, thinking.
 */
internal fun modelChipName(model: String, effort: String, thinking: Boolean) =
    "Model $model, effort $effort${if (thinking) ", thinking on" else ""}"

/**
 * `PriorityToggle`, in both rows: how a message sent during the turn is handled, one step on
 * (Queue, Later, Interrupt) with each press. Its `title` is the mouse's tooltip and its
 * `aria-label` the name a screen reader gives it, which a narrow chat's icon alone does not.
 */
@Composable
private fun PriorityChip(tab: ChatTab, narrow: Boolean) {
    val priority = tab.priority
    NeutralChip(
        priority.icon,
        if (narrow) null else priority.label,
        "Message priority: ${priority.label}",
        narrow,
        Modifier.webTitle("Priority: ${priority.label} (click to cycle)"),
    ) { tab.priority = priority.following }
}

/**
 * `text-primary bg-accent-wash border border-accent-wash-border`, sparkles, a brain while
 * thinking; `hover:brightness-110` faded in. 50% while the turn runs, when a mouse over it still
 * brightens it, as CSS `:hover` matches a disabled button. [labelMax] is the name's `max-w-[…]`,
 * [name] the chip's `aria-label`.
 */
@Composable
internal fun ModelChip(text: String, name: String, thinking: Boolean, disabled: Boolean, modifier: Modifier = Modifier, labelMax: Dp = 140.dp, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val fade = colorsFade(hovered)
    val lit = if (pressed) 1f else fade
    CssRow(
        modifier
            .chipSemantics(name, enabled = !disabled, action = onClick)
            .alpha(if (disabled) 0.5f else 1f)
            .brightness(1f + 0.1f * lit)
            .clip(CircleShape)
            .background(tokens.accentWash.color)
            // `disabled:cursor-default`: the arrow either way.
            .pointerHoverIcon(WebCursor.Default)
            .hoverable(source)
            .clickable(source, indication = null, enabled = !disabled, onClick = onClick)
            .cssBorder(tokens.accentWashBorder.color, CircleShape),
        gap = 6.dp,
        start = 9.dp,
        end = 9.dp,
        top = 4.dp,
        bottom = 4.dp,
    ) {
        PpmIcon(ProductIcon.Sparkles, 14.dp, tokens.accent.color, Modifier.cssIcon(14.dp))
        BasicText(
            text,
            Modifier.cssShrink().widthIn(max = labelMax).cssLines(17.25f),
            style = chipText.copy(color = tokens.accent.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (thinking) PpmIcon(ProductIcon.Brain, 12.dp, tokens.accent.color, Modifier.cssIcon(12.dp))
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
    CssRow(
        Modifier
            .clip(CircleShape)
            .background((if (cold) tokens.error.opacity(0.1) else tokens.panel2).color)
            .cssBorder((if (cold) tokens.error.opacity(0.4) else tokens.borderSoft).color, CircleShape),
        gap = 6.dp,
        start = 9.dp,
        end = 9.dp,
        top = 4.dp,
        bottom = 4.dp,
    ) {
        PpmIcon(ProductIcon.Clock, 12.dp, color.color, Modifier.cssIcon(12.dp))
        BasicText(label, Modifier.cssLines(17.25f), style = chipText.copy(color = color.color, fontFeatureSettings = "tnum"), maxLines = 1)
    }
}

/**
 * The mic, labelled "Start voice input", "Stop voice input" or "Transcribing": the paperclip's box
 * and colours while closed (`size-8` and a 16 px icon on a phone, `size-[34px]`, 17 px and
 * `hover:bg-surface-elevated` on a tablet); open, `bg-error text-white animate-pulse` and the mic
 * crossed out; while Whisper works on what it heard, a spinner at half strength taking no presses.
 * The change of look fades as `transition-colors` fades it.
 */
@Composable
private fun MicButton(tab: ChatTab, mobile: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val listening = tab.voiceListening
    val transcribing = tab.voiceTranscribing
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val lit = if (pressed && !transcribing) 1.0 else colorsFade(hovered).toDouble()
    val open = colorsFade(listening).toDouble()
    // `animate-pulse`: down to half and back every 2 s, each way on cubic-bezier(0.4, 0, 0.6, 1).
    val pulse = if (listening) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 1f,
            targetValue = 0.5f,
            animationSpec = infiniteRepeatable(tween(1_000, easing = PULSE_EASING), RepeatMode.Reverse),
            label = "pulse",
        ).value
    } else {
        1f
    }
    val idleFill = if (mobile) tokens.panel2.opacity(0.0) else tokens.panel2.opacity(lit)
    val idleIcon = tokens.text.mix(tokens.text3, lit)
    val label = when {
        transcribing -> "Transcribing"
        listening -> "Stop voice input"
        else -> "Start voice input"
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .alpha(if (transcribing || !enabled) 0.5f else pulse)
            .cssSize(if (mobile) 32.dp else 34.dp)
            .clip(shape)
            .background(tokens.error.mix(idleFill, open).color)
            .pointerHoverIcon(WebCursor.Default)
            .hoverable(source)
            .clickable(source, indication = null, enabled = enabled && !transcribing, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val icon = when {
            transcribing -> ProductIcon.Loader2
            listening -> ProductIcon.MicOff
            else -> ProductIcon.Mic
        }
        PpmIcon(icon, if (mobile) 16.dp else 17.dp, WHITE.mix(idleIcon, open).color, spinning = transcribing)
    }
}

/** Tailwind's `--animate-pulse` timing. */
private val PULSE_EASING = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)

/** `text-white`. */
private val WHITE = Rgba(1.0, 1.0, 1.0, 1.0)

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
            .cssSize(36.dp)
            .then(if (enabled) Modifier.ppmShadow(tokens.shadowFloat, shape) else Modifier)
            .clip(shape)
            .background(hoverFill.mix(fill, lit).color)
            // Send's `disabled:cursor-not-allowed`.
            .pointerHoverIcon(if (enabled) WebCursor.Default else WebCursor.NotAllowed)
            .hoverable(source)
            .clickable(source, indication = null, enabled = enabled, onClickLabel = label, onClick = onClick)
            // The `aria-label`: Stop and Send hold an icon only.
            .semantics { contentDescription = label },
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
private fun ModePopover(current: PermissionMode, onDismiss: () -> Unit, pick: (PermissionMode) -> Unit) =
    ComposerPopover(onDismiss) { ModePanel(current, pick) }

/**
 * The window the mode, provider and model panels open in, over the chip they came from with its left edge on the chip's,
 * 4 px clear (`absolute bottom-full left-0 mb-1`). One definition for the three: it is a focusable window of its own, so
 * the keyboard that moves over a panel's rows stays in it and no key reaches the window behind, and a panel opened
 * some other way would not have that. [PassThroughProperties] says what makes it so.
 */
@Composable
private fun ComposerPopover(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    PlacedPopup(remember(gap) { aboveStart(gap) }, onDismiss, passThrough = true, content)
}

/** What [ModePopover] opens: "Modes" and its key over a row for each mode. */
@Composable
internal fun ModePanel(current: PermissionMode, pick: (PermissionMode) -> Unit) {
    val tokens = LocalPpmTokens.current
    val width = if (LocalPpmMobile.current) 288.dp else 320.dp
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
                        .ppmClickable(pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color, litOnFocus = true) { pick(mode) }
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

/** The provider chip is offered (`onProviderChange`): before the session, its provider resolved, no first message on its way. */
private fun choosesProvider(tab: ChatTab) = tab.session == null && !tab.providerPending && !tab.firstSendPending

/** What the provider list said when last read on opening (`result`): failed keeps the list in hand. */
private class ProviderRead(val providers: List<ProviderInfo>, val failed: Boolean)

/**
 * The provider of a chat with no session yet (`ProviderSelector`): its logo and name on a chip
 * drawn as the mode's (the name `max-w-[80px] truncate capitalize`), over which its providers
 * open. The list is the project's while fresh, read again each time it opens.
 */
@Composable
private fun ProviderChip(model: AppModel, tab: ChatTab) {
    val cache = model.newChats.preparationCache
    var open by remember(tab) { mutableStateOf(false) }
    var read by remember(tab) { mutableStateOf<ProviderRead?>(null) }
    LaunchedEffect(tab, open) {
        if (!open) return@LaunchedEffect
        val api = model.api
        read = try {
            if (api == null) error("No server is open.")
            ProviderRead(cache.providers(tab.project) { api.providers(tab.project) }, failed = false)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ProviderRead(cache.peekProviders(tab.project) ?: emptyList(), failed = true)
        }
    }
    val providers = read?.providers ?: cache.peekProviders(tab.project) ?: emptyList()
    val current = tab.providerId
    val name = providers.firstOrNull { it.id == current }?.name ?: current
    // `relative`: a block around the chip, as tall as the line the chip stands on.
    CssColumn(bottom = chipStrut(14.dp)) {
        NeutralChip(
            cssCapitalized(name),
            providerChipName(name),
            narrow = false,
            labelMax = 80.dp,
            onClick = { open = !open },
        ) { ProviderBadge(current, 14.dp) }
        if (open) {
            val status = when {
                read?.failed == true -> "Unable to load providers"
                read != null -> "No providers available"
                else -> "Loading providers..."
            }
            ProviderPopover(current, providers, status, onDismiss = { open = false }) { id ->
                model.newChats.pickProvider(tab, id)
                open = false
            }
        }
    }
}

/** The provider chip's `aria-label`: the name as the server gives it, not as the chip's `capitalize` draws it. */
internal fun providerChipName(name: String) = "AI Provider: $name"

/**
 * The providers (`w-56 rounded-lg border popover-solid shadow-panel`), over the chip with its left
 * edge on the chip's, 4 px clear: "Provider", then a row each (`px-3 py-2 gap-3`), the chosen one
 * lit and checked; [status] while there is none. A press anywhere else closes it and goes on.
 */
@Composable
private fun ProviderPopover(current: String, providers: List<ProviderInfo>, status: String, onDismiss: () -> Unit, pick: (String) -> Unit) =
    ComposerPopover(onDismiss) { ProviderPanel(current, providers, status, pick) }

/** What [ProviderPopover] opens: "Provider" over a row for each provider, or the [status] while there is none. */
@Composable
internal fun ProviderPanel(current: String, providers: List<ProviderInfo>, status: String, pick: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .width(224.dp)
            .ppmShadow(tokens.shadowPanel, shape)
            .clip(shape)
            .background(tokens.panel2.over(tokens.bgSolid).color)
            .cssBorder(tokens.border.color, shape),
    ) {
        PopoverHeader("Provider")
        Column(Modifier.padding(vertical = 4.dp)) {
            if (providers.isEmpty()) {
                BasicText(
                    status,
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                )
            }
            for (provider in providers) {
                val active = provider.id == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (active) tokens.panel2.color else Color.Transparent)
                        .ppmClickable(pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color, litOnFocus = true) { pick(provider.id) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProviderBadge(provider.id, 16.dp)
                    BasicText(
                        cssCapitalized(provider.name),
                        Modifier.weight(1f),
                        style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color),
                    )
                    if (active) PpmIcon(ProductIcon.Check, 16.dp, tokens.accent.color)
                }
            }
        }
    }
}

/**
 * A selector popover's [label], "Provider" or "Model" (`px-3 py-2 border-b`): a block, so its line
 * is the page's 16 px text on 24 px lines, the label on that line's baseline as Chrome rounds it in
 * device pixels.
 */
@Composable
internal fun PopoverHeader(label: String) {
    val tokens = LocalPpmTokens.current
    val density = LocalDensity.current.density
    val (up, down) = PpmFonts.chromeAscentDescent(16f * density, PpmFonts.SANS_ASCENT, PpmFonts.SANS_DESCENT)
    val baseline = floor((24f * density - up - down) / 2) + up
    Layout(
        { BasicText(label, style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color)) },
        Modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = CssSides.Bottom).padding(horizontal = 12.dp, vertical = 8.dp).height(24.dp),
    ) { measurables, constraints ->
        val label = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) { label.place(0, (baseline - label[FirstBaseline]).roundToInt()) }
    }
}

/** CSS `text-transform: capitalize`: each word's first letter in capitals, the rest as written. */
private fun cssCapitalized(text: String): String {
    val out = StringBuilder(text.length)
    var start = true
    for (c in text) {
        out.append(if (start) c.titlecase() else c.toString())
        start = c.isWhitespace()
    }
    return out.toString()
}

/** Over the anchor, left edges together, [gap] clear of it (`absolute bottom-full left-0 mb-1`), kept on screen. */
internal fun aboveStart(gap: Int) = { anchor: IntRect, window: IntSize, box: IntSize ->
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
            model.models, chat, state,
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
    ComposerPopover(onDismiss) {
        val shape = RoundedCornerShape(12.dp)
        ModelColumns(
            model.models, chat, state,
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
internal fun ModelColumns(catalog: ModelCatalog?, chat: ChatController, state: ChatState, box: Modifier, inner: Modifier, onPicked: () -> Unit) {
    val entries = catalog?.entries?.collectAsState()?.value
    LaunchedEffect(catalog, chat.providerId) { catalog?.load(chat.providerId) }
    val models: List<ModelOption> = remember(catalog, entries) { catalog?.models(chat.providerId).orEmpty() }
    val status: ModelListStatus? = remember(catalog, entries) { catalog?.status(chat.providerId) ?: ModelListStatus.LOADING }
    ModelPickerColumns(
        models, status, state.model, Effort.of(state.effort) ?: Effort.DEFAULT, state.thinking, box, inner,
        onModel = {
            chat.setModel(it)
            onPicked()
        },
        onEffort = {
            chat.setEffort(it)
            onPicked()
        },
        onThinking = { chat.setThinking(it) },
    )
}

/**
 * What [ModelColumns] draws once it has what it shows, apart from where that comes from: [model] and [effort] are the ones
 * chosen, [thinking] whether the switch is on, and [onThinking] is told what it would turn it to.
 */
@Composable
internal fun ModelPickerColumns(
    models: List<ModelOption>, status: ModelListStatus?, model: String?, effort: Effort, thinking: Boolean,
    box: Modifier, inner: Modifier,
    onModel: (String) -> Unit, onEffort: (Effort) -> Unit, onThinking: (Boolean) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    // The tablet's popover scrolls with the web's thumb; the phone's sheet does not scroll on the web.
    val scroll = rememberScrollState()
    Row(
        box
            .then(if (LocalPpmMobile.current) Modifier.verticalScroll(scroll) else Modifier.cssScrollY(scroll))
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
                OptionRow(active = option.value == model, onClick = { onModel(option.value) }) {
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
                OptionRow(active = option == effort, onClick = { onEffort(option) }) {
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
            ThinkingRow(thinking) { onThinking(!thinking) }
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
 * `hover:bg-surface-elevated` is the sheet's own fill, so the hover tint lights it instead. The web's row has no
 * `focus:` colour, which leaves a keyboard with no sign of where it is in this picker, so the hover tint lights the row
 * the focus is on too, as it does in the mode and provider pickers, whose web rows do have `focus:bg-surface-elevated`.
 */
@Composable
private fun OptionRow(active: Boolean, onClick: () -> Unit, label: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(if (active) tokens.panel2.color else Color.Transparent)
            .ppmClickable(pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color, litOnFocus = true, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        label()
        if (active) PpmIcon(ProductIcon.Check, 16.dp, tokens.accent.color)
    }
}

/** `role="switch"`: a 36×20 track, its 16 px knob 2 px in when off and 16 px in when on; lit at once under a mouse, and as [OptionRow] is while the keyboard's focus is on it. */
@Composable
private fun ThinkingRow(on: Boolean, onToggle: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val knob by animateDpAsState(if (on) 16.dp else 2.dp, tween(150), label = "knob")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .ppmClickable(pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color, fade = false, litOnFocus = true, onClick = onToggle)
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
