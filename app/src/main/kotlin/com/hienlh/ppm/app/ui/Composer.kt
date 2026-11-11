package com.hienlh.ppm.app.ui

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setSelection
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
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
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.chromeTextArea
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.cssSize
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberChromeTextArea
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.ModelOption
import com.hienlh.ppm.protocol.ParsedUserMessage
import com.hienlh.ppm.protocol.PromptCacheState
import com.hienlh.ppm.protocol.ProviderInfo
import com.hienlh.ppm.state.ChatController
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.ChatState
import com.hienlh.ppm.state.Effort
import com.hienlh.ppm.state.ModelCatalog
import com.hienlh.ppm.state.ModelListStatus
import com.hienlh.ppm.state.ModelNames
import com.hienlh.ppm.state.PermissionMode
import com.hienlh.ppm.state.PromptCacheStatus
import com.hienlh.ppm.state.ReconnectStage
import com.hienlh.ppm.state.composerPlaceholder as stateComposerPlaceholder
import com.hienlh.ppm.state.stepHistory
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

/** Chips of the composer row: 11.5 px, with the body's 1.5 line height. */
private val chipText = ppmText(11.5f, lineHeight = 17.25f)

/** Custom semantics properties exposed on `clearAndSetSemantics` composer chips for layout/label tests without splitting the AccessibilityNodeInfo leaf. */
internal val DisplayedChipTextKey = SemanticsPropertyKey<String>("DisplayedChipText")
internal val ShowsChevronKey = SemanticsPropertyKey<Boolean>("ShowsChevron")
internal val ComposerPlaceholderKey = SemanticsPropertyKey<String>("ComposerPlaceholder")

/** `showCancel`: Stop takes Send's place while a turn runs and the box has nothing to send. */
internal fun showsStop(running: Boolean, hasContent: Boolean) = running && !hasContent

/**
 * The box's placeholder (`message-input.tsx`): `"Will send when reconnected..."` while offline,
 * `"Follow-up or Stop..."` while a turn runs, `"Ask anything..."` otherwise.
 */
internal fun composerPlaceholder(running: Boolean, offline: Boolean = false): String =
    stateComposerPlaceholder(if (offline) ReconnectStage.RECONNECTING else ReconnectStage.HIDDEN, running)

/**
 * The message box (`message-input.tsx`): a single `rounded-[18px]` container with optional
 * reply/agent/attachment chips on top, the growing textarea in the middle, and ONE bottom action bar:
 * `[AttachButton, ModeChip, ComposerModelChip (shrinkable), Spacer, PromptCacheChip, MicButton, Send/Stop]`.
 *
 * [narrow] is the chat's `@max-[420px]/chat` — mode and cold prompt-cache chips drop their
 * text labels, and the model chip drops its effort suffix and trailing chevron so the model name stays
 * readable. [offline] switches the placeholder to `"Will send when reconnected..."`.
 */
@Composable
fun ChatComposer(
    model: AppModel,
    tab: ChatTab,
    chat: ChatController,
    state: ChatState,
    narrow: Boolean,
    fileDrop: ChatFileDrop,
    pickers: ComposerPickers,
    offline: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val reducedMotion = rememberPrefersReducedMotion()
    val running = state.isRunning
    val focus = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
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

    val shape = RoundedCornerShape(18.dp)
    val borderColor by animateColorAsState(
        targetValue = if (fieldFocused) tokens.accentWashBorder.color else tokens.border.color,
        animationSpec = tween(if (reducedMotion) 0 else 150),
        label = "composerBorderColor",
    )
    CssColumn(
        modifier
            .fillMaxWidth()
            .testTag("chat-composer")
            .ppmShadow(tokens.shadowFloat, shape)
            .clip(shape)
            .background(tokens.panel.color)
            // `cursor-text`: the I-beam over the whole box, its buttons back to the arrow.
            .pointerHoverIcon(WebCursor.Text)
            // A tap on the box's padding goes to the text, as the web's container `onClick` does,
            // without turning the container into a `mergeDescendants` button in accessibility.
            .pointerInput(tab, locked) {
                detectTapGestures { if (!locked) tab.requestFocus() }
            }
            .cssBorder(borderColor, shape),
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

        val placeholder = composerPlaceholder(running, offline)
        if (mobile) {
            // `px-3 pb-0 pt-2 text-sm leading-relaxed max-h-20`: 16 px phone input, 80 dp total cap.
            ComposerField(
                tab, focus, quietFocus, focusQuietly, onKey, ::keyboardSend, pasteImage, fileDrop,
                style = ppmText(16f, lineHeight = 16f * 1.625f),
                padding = 0.dp,
                cssStart = 12.dp,
                maxHeight = 72.dp,
                placeholder = placeholder,
                enabled = !locked,
                onFocusChange = { fieldFocused = it },
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
            )
        } else {
            // `px-3.5 pb-1 pt-3 text-sm leading-relaxed max-h-[160px] min-h-12`: 12 dp top (`8 + 4`), 4 dp bottom, 48 dp min, 160 dp max.
            ComposerField(
                tab, focus, quietFocus, focusQuietly, onKey, ::keyboardSend, pasteImage, fileDrop,
                style = ppmText(14f, lineHeight = 14f * 1.625f),
                padding = 4.dp,
                cssStart = 14.dp,
                maxHeight = 152.dp,
                placeholder = placeholder,
                enabled = !locked,
                onFocusChange = { fieldFocused = it },
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp).defaultMinSize(minHeight = 40.dp),
            )
        }

        val mode = tab.permissionMode ?: PermissionMode.FALLBACK
        val name = modelName(model, chat, state)
        val compactBar = narrow || mobile
        val barGap = if (compactBar) 2.dp else 4.dp
        val barPx = if (compactBar) 6.dp else 8.dp
        val barPt = if (mobile) 0.dp else 4.dp
        val barPb = if (mobile) 4.dp else 8.dp
        Row(
            Modifier
                .fillMaxWidth()
                .testTag("composer-action-bar")
                .padding(start = barPx, end = barPx, top = barPt, bottom = barPb),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(barGap),
        ) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(barGap),
            ) {
                AttachButton(mobile = mobile, enabled = !locked, onClick = attach)
                if (!tab.providerPending) {
                    Box {
                        ModeChip(mode, narrow) { modeOpen = !modeOpen }
                        if (modeOpen) ModePopover(mode, onDismiss = { modeOpen = false }, pick = pickMode)
                    }
                }
                if (!tab.providerPending && !tab.firstSendPending) {
                    // `[data-model-slot]` (`shrink` while siblings stay `shrink-0`): the only chip that yields width.
                    Box(Modifier.weight(1f, fill = false).testTag("composer-model-slot")) {
                        ComposerModelChip(
                            name = name,
                            state = state,
                            providerId = chat.providerId,
                            narrow = narrow,
                        ) {
                            modelOpen = if (mobile) true else !modelOpen
                        }
                        if (!mobile && modelOpen && !running) {
                            ModelPopover(model, tab, chat, state, onDismiss = { modelOpen = false })
                        }
                    }
                }
            }
            PromptCacheChip(state.promptCache, narrow = narrow)
            if (micShown) {
                MicButton(tab, mobile = mobile, enabled = !locked, onClick = toggleMic)
            }
            if (showsStop(running, hasContent)) {
                StopButton(mobile = mobile, onClick = chat::cancel)
            } else {
                SendButton(tab, mobile = mobile, enabled = canSend, onClick = ::send)
            }
        }
    }
    if (mobile && modelOpen && !running) {
        ModelSheet(model, tab, chat, state, onDismiss = { modelOpen = false })
    }
}

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
    onFocusChange: (Boolean) -> Unit = {},
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
                .testTag("composer-field")
                .chromeTextArea(area, maxHeight)
                .plainTextSemantics(tab.draft, placeholder, enabled)
                // `disabled:opacity-50`.
                .alpha(if (enabled) 1f else 0.5f)
                .heightIn(max = maxHeight)
                .focusRequester(focus)
                .tracksTextFocus(tab.draft)
                .onFocusChanged {
                    focused = it.isFocused
                    onFocusChange(it.isFocused)
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
                    modifier = Modifier.dropZone(shouldStartDragAndDrop = { true }, target = fileDrop).dropRelay(),
                ) { field() }
            },
        )
    }
}

/**
 * What an accessibility service reads of the field and does to its caret, in the text the user typed.
 */
private fun Modifier.plainTextSemantics(state: TextFieldState, placeholder: String, enabled: Boolean): Modifier = semantics {
    this[ComposerPlaceholderKey] = placeholder
    if (state.text.isEmpty() && placeholder.isNotEmpty()) {
        contentDescription = placeholder
    }
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

@Composable
private fun strutRoomUnder(belowPx: Float): Dp {
    val density = LocalDensity.current.density
    val (up, down) = PpmFonts.chromeAscentDescent(16f * density, PpmFonts.SANS_ASCENT, PpmFonts.SANS_DESCENT)
    val leading = 24f * density - up - down
    val strut = down + leading - floor(leading / 2)
    return ((strut - belowPx) / density).coerceAtLeast(0f).dp
}

/** The model chip's name: the catalog's label, else one made from the id, else "Model". */
@Composable
private fun modelName(model: AppModel, chat: ChatController, state: ChatState): String {
    val catalog = model.models
    val entries = catalog?.entries?.collectAsState()?.value
    return remember(entries, state.model, chat.providerId) {
        catalog?.displayName(state.model, chat.providerId)
            ?: ModelNames.effectiveModel(state.model, chat.providerId)?.let(ModelNames::displayName)
            ?: "Model"
    }
}

/**
 * A chip as the web's `<button aria-label=[name] disabled>` is to a screen reader: one node with the
 * name, a button's role, its press ([action]) and, while [enabled] is false, disabled.
 */
private fun Modifier.chipSemantics(
    name: String,
    enabled: Boolean = true,
    tag: String? = null,
    extra: SemanticsPropertyReceiver.() -> Unit = {},
    action: () -> Unit,
): Modifier =
    clearAndSetSemantics {
        contentDescription = name
        role = Role.Button
        if (tag != null) testTag = tag
        extra()
        onClick {
            if (enabled) action()
            enabled
        }
        if (!enabled) disabled()
    }

/**
 * `BAR_CHIP` (`message-input.tsx`): borderless ghost pill `h-[30px]` (`max-md:h-7` = 28 dp on phone),
 * `rounded-full px-[9px] gap-1.5` (`@max-[420px]/chat` and `max-md`: `px-1.5` = 6 dp, `gap-1` = 4 dp),
 * `text-[11.5px] text-text-2 can-hover:hover:bg-surface-hover can-hover:hover:text-text` and
 * `motion-safe:active:scale-[0.97]`. On narrow or phone layouts (`narrow || mobile`) only the 16 px
 * icon is drawn.
 */
@Composable
private fun BarChip(
    icon: ProductIcon,
    label: String,
    name: String,
    narrow: Boolean,
    modifier: Modifier = Modifier,
    tag: String? = null,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val reducedMotion = rememberPrefersReducedMotion()
    val compact = narrow || mobile
    val showLabel = !compact
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val lit = if (!mobile && hovered) colorsFade(true).toDouble() else 0.0
    val textColor = tokens.text.mix(tokens.text2, lit).color
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) 0.97f else 1f,
        animationSpec = tween(if (reducedMotion) 0 else 150),
        label = "barChipScale",
    )
    Row(
        modifier
            .chipSemantics(
                name = name,
                tag = tag,
                extra = { this[DisplayedChipTextKey] = if (showLabel) label else "" },
                action = onClick,
            )
            .height(if (mobile) 28.dp else 30.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(CircleShape)
            .background(if (!mobile && hovered) tokens.surfaceHover.color else Color.Transparent)
            .pointerHoverIcon(WebCursor.Default)
            .hoverable(source)
            .clickable(source, indication = null, onClick = onClick)
            .padding(horizontal = if (compact) 6.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
    ) {
        PpmIcon(icon, 16.dp, textColor)
        if (showLabel) {
            BasicText(
                label,
                style = chipText.copy(color = textColor),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/**
 * `ModeChip` (`message-input.tsx`): shows the one-word [PermissionMode.shortLabel] ("Ask",
 * "Auto-edit", "Plan", "Bypass") on wide tablet, or icon-only when [narrow] or on phone.
 * Its `title` and `aria-label` keep the full [PermissionMode.label].
 */
@Composable
internal fun ModeChip(mode: PermissionMode, narrow: Boolean, onClick: () -> Unit) {
    val name = modeChipName(mode)
    BarChip(
        icon = mode.icon,
        label = mode.shortLabel,
        name = name,
        narrow = narrow,
        modifier = Modifier.webTitle(name),
        tag = "composer-mode-chip",
        onClick = onClick,
    )
}

/** `ModeChip`'s `title` and `aria-label`. */
internal fun modeChipName(mode: PermissionMode) = "Permission mode: ${mode.label}"

/**
 * The chip of `ModelThinkingSelector` (`model-thinking-selector.tsx`): borderless ghost pill carrying
 * the provider's logo, the bounded model name (plus `" · <Effort>"` on wide tablet), optional `Brain`
 * icon when thinking is on, and trailing `ChevronDown` on wide tablet.
 */
@Composable
internal fun ComposerModelChip(
    name: String,
    state: ChatState,
    providerId: String = "claude",
    narrow: Boolean = false,
    onClick: () -> Unit,
) {
    val isAntigravity = providerId == "antigravity"
    val effort = state.effort ?: ModelNames.suffixEffort(state.model, providerId) ?: Effort.DEFAULT.value
    val showThinking = !isAntigravity && state.thinking
    val text = ModelNames.chipLabel(name, effort)
    val running = state.isRunning
    ModelChip(
        text = text,
        name = modelChipName(name, effort, showThinking),
        thinking = showThinking,
        disabled = running,
        modifier = Modifier.webTitle(modelChipTitle(running, text, providerId)),
        providerId = providerId,
        narrow = narrow,
        modelDisplay = name,
        tag = "composer-model-chip",
        onClick = onClick,
    )
}

/**
 * The model chip's `title` (`model-thinking-selector.tsx`): `"Can't change while running"` when
 * [running], else `"<Provider> · <text>"` when [providerId] is given, else [text].
 */
internal fun modelChipTitle(running: Boolean, text: String, providerId: String? = null): String {
    if (running) return "Can't change while running"
    val provider = providerId?.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() }
    return if (provider != null) "$provider · $text" else text
}

/**
 * The model chip's `aria-label`: the model, the effort by the value the server knows it by
 * ("xhigh", where the chip says "Extra") and, when it is on, thinking.
 */
internal fun modelChipName(model: String, effort: String, thinking: Boolean) =
    "Model $model, effort $effort${if (thinking) ", thinking on" else ""}"

/**
 * `ModelThinkingSelector`'s trigger button (`data-model-chip`):
 * `inline-flex h-[30px] min-w-0 max-w-full items-center gap-1.5 whitespace-nowrap rounded-full px-[9px]
 * text-[11.5px] text-primary @max-[420px]/chat:gap-1 @max-[420px]/chat:px-1.5 max-md:gap-1 max-md:px-1.5
 * can-hover:hover:bg-surface-hover motion-safe:active:scale-[0.97]`.
 *
 * On `narrow || mobile`, the effort suffix (`" · High"`) and trailing `ChevronDown` are hidden and
 * the model name caps at `110.dp` (`180.dp` on wide tablet), matching `model-thinking-selector.tsx`
 * lines 214–221.
 */
@Composable
internal fun ModelChip(
    text: String,
    name: String,
    thinking: Boolean,
    disabled: Boolean,
    modifier: Modifier = Modifier,
    providerId: String = "claude",
    narrow: Boolean = false,
    modelDisplay: String = text.substringBefore(" · "),
    labelMax: Dp = Dp.Unspecified,
    tag: String? = null,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val reducedMotion = rememberPrefersReducedMotion()
    val compact = narrow || mobile
    val shownText = if (compact) modelDisplay else text
    val effectiveMax = when {
        labelMax != Dp.Unspecified -> labelMax
        compact -> 110.dp
        else -> 180.dp
    }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !disabled && !reducedMotion) 0.97f else 1f,
        animationSpec = tween(if (reducedMotion) 0 else 150),
        label = "modelChipScale",
    )
    Row(
        modifier
            .chipSemantics(
                name = name,
                enabled = !disabled,
                tag = tag,
                extra = {
                    this[DisplayedChipTextKey] = shownText
                    this[ShowsChevronKey] = !compact
                },
                action = onClick,
            )
            .height(30.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(CircleShape)
            .background(if (!mobile && hovered && !disabled) tokens.surfaceHover.color else Color.Transparent)
            // `disabled:cursor-default`: the arrow either way.
            .pointerHoverIcon(WebCursor.Default)
            .hoverable(source)
            .clickable(source, indication = null, enabled = !disabled, onClick = onClick)
            .padding(horizontal = if (compact) 6.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
    ) {
        ProviderBadge(providerId, 16.dp)
        BasicText(
            shownText,
            Modifier.weight(1f, fill = false).widthIn(max = effectiveMax),
            style = chipText.copy(color = tokens.accent.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (thinking) PpmIcon(ProductIcon.Brain, 12.dp, tokens.accent.color)
        if (!compact) PpmIcon(ProductIcon.ChevronDown, 12.dp, tokens.text3.color)
    }
}

/**
 * Cold-only prompt cache indicator (`prompt-cache-chip.tsx`): a warm or unmeasured cache renders
 * nothing; once cold, draws a borderless `h-[30px]` red clock + tabular idle duration (`"1h 51m"`),
 * hiding the text on `narrow || mobile`.
 */
@Composable
internal fun PromptCacheChip(cache: PromptCacheState?, narrow: Boolean = false) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(cache) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val status = PromptCacheStatus.of(cache, now.toDouble()) as? PromptCacheStatus.Cold ?: return
    val label = status.label ?: return
    val detail = status.detail ?: label
    Row(
        Modifier
            .height(30.dp)
            .testTag("prompt-cache-cold")
            .webTitle(detail)
            .semantics { contentDescription = detail }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PpmIcon(ProductIcon.Clock, 16.dp, tokens.error.color)
        if (!narrow && !mobile) {
            BasicText(
                label,
                style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.error.color, fontFeatureSettings = "tnum"),
                maxLines = 1,
            )
        }
    }
}

/**
 * The mic (`SQUARE_BUTTON`, `message-input.tsx`), labelled `"Start voice input"`,
 * `"Stop voice input"` or `"Transcribing"`: `size-[34px]` on tablet, `size-8` on phone, `rounded-[10px]`,
 * `size-4` (16 dp) icon, `can-hover:hover:bg-surface-hover can-hover:hover:text-text` when idle,
 * `!bg-error !text-white animate-pulse` when listening, and `motion-safe:active:scale-[0.97]`.
 */
@Composable
private fun MicButton(tab: ChatTab, mobile: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val reducedMotion = rememberPrefersReducedMotion()
    val listening = tab.voiceListening
    val transcribing = tab.voiceTranscribing
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val lit = if (!mobile && hovered) colorsFade(true).toDouble() else 0.0
    val open = colorsFade(listening).toDouble()
    val pulse = if (listening && !reducedMotion) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 1f,
            targetValue = 0.5f,
            animationSpec = infiniteRepeatable(tween(1_000, easing = PULSE_EASING), RepeatMode.Reverse),
            label = "pulse",
        ).value
    } else {
        1f
    }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled && !transcribing && !reducedMotion) 0.97f else 1f,
        animationSpec = tween(if (reducedMotion) 0 else 150),
        label = "micButtonScale",
    )
    val idleFill = if (!mobile && hovered) tokens.surfaceHover else tokens.surfaceHover.opacity(0.0)
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
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(shape)
            .background(tokens.error.mix(idleFill, open).color)
            .pointerHoverIcon(WebCursor.Default)
            .webTitle(label)
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
        PpmIcon(icon, 16.dp, WHITE.mix(idleIcon, open).color, spinning = transcribing)
    }
}

/** Tailwind's `--animate-pulse` timing. */
private val PULSE_EASING = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)

/** `text-white`. */
private val WHITE = Rgba(1.0, 1.0, 1.0, 1.0)

/**
 * Primary action button (`Send` / `Stop`, `message-input.tsx` lines 1179–1197):
 * `size-9 rounded-[11px]` on tablet, `max-md:size-8 max-md:rounded-[10px]` on phone, `size-4` (16 dp)
 * icon, `shadow-[var(--shadow-float)]`, `motion-safe:active:scale-[0.97]`; 30% flat when disabled.
 */
@Composable
private fun ActionSquareButton(
    icon: ProductIcon,
    mobile: Boolean,
    fill: Rgba,
    tint: Color,
    hoverFill: Rgba,
    ariaLabel: String,
    title: String,
    enabled: Boolean = true,
    spinning: Boolean = false,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val reducedMotion = rememberPrefersReducedMotion()
    val shape = RoundedCornerShape(if (mobile) 10.dp else 11.dp)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val fade = colorsFade(hovered)
    val lit = if (pressed && enabled) 1.0 else fade.toDouble()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled && !reducedMotion) 0.97f else 1f,
        animationSpec = tween(if (reducedMotion) 0 else 150),
        label = "actionSquareScale",
    )
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.3f)
            .cssSize(if (mobile) 32.dp else 36.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .then(if (enabled) Modifier.ppmShadow(tokens.shadowFloat, shape) else Modifier)
            .clip(shape)
            .background(hoverFill.mix(fill, lit).color)
            .pointerHoverIcon(if (enabled) WebCursor.Default else WebCursor.NotAllowed)
            .webTitle(title)
            .hoverable(source)
            .clickable(source, indication = null, enabled = enabled, onClickLabel = ariaLabel, onClick = onClick)
            .semantics { contentDescription = ariaLabel },
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, 16.dp, tint, spinning = spinning)
    }
}

@Composable
private fun StopButton(mobile: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    ActionSquareButton(
        icon = ProductIcon.Square,
        mobile = mobile,
        fill = tokens.error,
        tint = Color.White,
        hoverFill = tokens.error.opacity(0.8),
        ariaLabel = "Stop response",
        title = "Stop",
        onClick = onClick,
    )
}

@Composable
private fun SendButton(tab: ChatTab, mobile: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val queued = tab.pendingSend
    ActionSquareButton(
        icon = if (queued) ProductIcon.Loader2 else ProductIcon.ArrowUp,
        mobile = mobile,
        fill = tokens.accent,
        tint = tokens.accentFg.color,
        hoverFill = tokens.accent.opacity(0.9),
        ariaLabel = if (queued) "Cancel queued send" else "Send message",
        title = "Send",
        enabled = enabled,
        spinning = queued,
        onClick = { if (queued) tab.pendingSend = false else onClick() },
    )
}

/**
 * The modes (`ModeSelector`, `w-72 md:w-80`): over the chip with its left edge on the chip's,
 * 4 px clear (`absolute bottom-full left-0 mb-1`).
 */
@Composable
private fun ModePopover(current: PermissionMode, onDismiss: () -> Unit, pick: (PermissionMode) -> Unit) =
    ComposerPopover(onDismiss) { ModePanel(current, pick) }

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

/**
 * Offered inside the model picker (`onProviderChange`, `chat-tab.tsx` line 1555): only before a
 * session exists, outside design tabs, once preparation resolved, and before the first message sends.
 */
internal fun choosesProvider(tab: ChatTab) =
    tab.session == null && !tab.tabId.startsWith("design:") && !tab.providerPending && !tab.firstSendPending

/** What the provider list said when last read on opening (`result`): failed keeps the list in hand. */
private class ProviderRead(val providers: List<ProviderInfo>, val failed: Boolean)

/**
 * `ProviderChoiceRow` (`provider-selector.tsx`): sits at the top of the model picker when starting
 * a new chat. Picking a provider switches `tab.providerId` via `model.newChats.pickProvider` while
 * keeping the model picker open so the user can immediately pick a model on that provider.
 */
@Composable
internal fun ProviderChoiceRow(model: AppModel, tab: ChatTab) {
    val cache = model.newChats.preparationCache
    var read by remember(tab, tab.project) { mutableStateOf<ProviderRead?>(null) }
    LaunchedEffect(tab, tab.project) {
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
    val status = when {
        read?.failed == true -> "Unable to load providers"
        read != null -> "No providers available"
        else -> "Loading providers..."
    }
    ProviderChoiceBar(
        current = tab.providerId,
        providers = providers,
        status = status,
        pick = { id -> model.newChats.pickProvider(tab, id) },
    )
}

/**
 * Pure UI for `ProviderChoiceRow` (`border-b border-border px-1 py-1`, `role="radiogroup"`):
 * `"Provider"` label above wrapping rounded-full pills (`min-h-8 px-2.5 text-xs` on tablet,
 * `max-md:min-h-11 max-md:px-3.5 max-md:text-sm` on phone).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProviderChoiceBar(
    current: String,
    providers: List<ProviderInfo>,
    status: String,
    pick: (String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("provider-choice-row")
            .semantics { contentDescription = "AI provider" }
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        BasicText(
            "Provider",
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color),
        )
        if (providers.isEmpty()) {
            BasicText(
                status,
                Modifier
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
            )
        } else {
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (provider in providers) {
                    val active = provider.id == current
                    val textColor = if (active) tokens.text.color else tokens.text2.color
                    Row(
                        Modifier
                            .defaultMinSize(minHeight = if (mobile) 44.dp else 32.dp)
                            .clip(CircleShape)
                            .background(if (active) tokens.accentWash.color else Color.Transparent)
                            .ppmClickable(
                                pressedColor = tokens.surfaceHover.color,
                                hoverColor = tokens.surfaceHover.color,
                                shape = CircleShape,
                                litOnFocus = true,
                            ) { pick(provider.id) }
                            .cssBorder((if (active) tokens.accentWashBorder else tokens.borderSoft).color, CircleShape)
                            .semantics {
                                role = Role.RadioButton
                                selected = active
                            }
                            .padding(horizontal = if (mobile) 14.dp else 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ProviderBadge(provider.id, if (mobile) 16.dp else 14.dp)
                        BasicText(
                            cssCapitalized(provider.name),
                            style = ppmText(if (mobile) 14f else 12f, lineHeight = if (mobile) 20f else 16f).copy(color = textColor),
                        )
                        if (active) PpmIcon(ProductIcon.Check, 14.dp, tokens.accent.color)
                    }
                }
            }
        }
    }
}

/**
 * A selector popover's [label], "Model" (`px-3 py-2 border-b`), used by `ModelSelector.kt`.
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
        val labelPlaceable = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) { labelPlaceable.place(0, (baseline - labelPlaceable[FirstBaseline]).roundToInt()) }
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
 * Model, effort and thinking in one sheet (`ModelThinkingSelector` on a phone), with optional
 * [ProviderChoiceRow] on top before a session exists. A pick leaves the sheet open, as on the web.
 */
@Composable
private fun ModelSheet(model: AppModel, tab: ChatTab, chat: ChatController, state: ChatState, onDismiss: () -> Unit) {
    val showProvider = choosesProvider(tab)
    BottomSheet(onDismiss) {
        ModelColumns(
            model.models, chat, state,
            Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.8f),
            Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            onPicked = {},
            header = if (showProvider) ({ ProviderChoiceRow(model, tab) }) else null,
        )
    }
}

/**
 * The same on a tablet: a popover over the chip (`absolute bottom-full left-0 mb-1 w-80
 * rounded-lg border popover-solid shadow-panel max-h-[70vh] overflow-y-auto`). A model or effort
 * picked closes it; picking a provider or toggling Thinking leaves it open.
 */
@Composable
private fun ModelPopover(model: AppModel, tab: ChatTab, chat: ChatController, state: ChatState, onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val showProvider = choosesProvider(tab)
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
            header = if (showProvider) ({ ProviderChoiceRow(model, tab) }) else null,
        )
    }
}

/**
 * The picker's content (`flex divide-x divide-border`): optional [header] (`ProviderChoiceRow`)
 * above the provider's models on the left and efforts + thinking switch on the right.
 */
@Composable
internal fun ModelColumns(
    catalog: ModelCatalog?,
    chat: ChatController,
    state: ChatState,
    box: Modifier,
    inner: Modifier,
    onPicked: () -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    val entries = catalog?.entries?.collectAsState()?.value
    LaunchedEffect(catalog, chat.providerId) { catalog?.load(chat.providerId) }
    val models: List<ModelOption> = remember(catalog, entries, chat.providerId) { catalog?.models(chat.providerId).orEmpty() }
    val status: ModelListStatus? = remember(catalog, entries, chat.providerId) { catalog?.status(chat.providerId) ?: ModelListStatus.LOADING }
    val effectiveModel = ModelNames.effectiveModel(state.model, chat.providerId)
    val effectiveEffort = Effort.of(state.effort ?: ModelNames.suffixEffort(state.model, chat.providerId)) ?: Effort.DEFAULT
    ModelPickerColumns(
        models, status, effectiveModel, effectiveEffort, state.thinking, box, inner,
        providerId = chat.providerId,
        header = header,
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
 * What [ModelColumns] draws once it has what it shows, apart from where that comes from: optional
 * [header] above the two-column model + effort/thinking layout.
 */
@Composable
internal fun ModelPickerColumns(
    models: List<ModelOption>, status: ModelListStatus?, model: String?, effort: Effort, thinking: Boolean,
    box: Modifier, inner: Modifier,
    providerId: String = "claude",
    header: (@Composable () -> Unit)? = null,
    onModel: (String) -> Unit, onEffort: (Effort) -> Unit, onThinking: (Boolean) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val isAntigravity = providerId == "antigravity"
    // The tablet's popover scrolls with the web's thumb; the phone's sheet does not scroll on the web.
    val scroll = rememberScrollState()
    Column(
        box
            .then(if (LocalPpmMobile.current) Modifier.verticalScroll(scroll) else Modifier.cssScrollY(scroll))
            .then(inner),
    ) {
        header?.invoke()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
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
                            ModelNames.shortLabel(option.label, providerId),
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
                for (option in Effort.optionsFor(providerId)) {
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
                if (!isAntigravity) {
                    Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().cssBorder(tokens.border.color, sides = CssSides.Top))
                    ThinkingRow(thinking) { onThinking(!thinking) }
                }
            }
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
