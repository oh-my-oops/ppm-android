package com.hienlh.ppm.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.TextInputFocus
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTheme
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.terminal.MobileKey
import com.hienlh.ppm.state.workspace.WorkspaceTab
import com.hienlh.ppm.state.terminal.TerminalText
import com.hienlh.ppm.terminal.TerminalFonts
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.ComposeController
import org.connectbot.terminal.ImeShortcutInputMode
import org.connectbot.terminal.TerminalImeController
import org.connectbot.terminal.TerminalImeMode
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.Terminal

/** The shell owns its parser outside composition; this view only owns input focus/selection. */
@Composable
fun TerminalBody(model: AppModel, tab: WorkspaceTab) {
    val session = remember(model, tab.id) { model.terminal(tab) } ?: return
    val state by session.controller.state.collectAsState()
    val tokens = LocalPpmTokens.current
    val theme = LocalPpmTheme.current
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val face = remember(context) { TerminalFonts.typeface(context) }
    val emulator = session.emulator
    var selection by remember(emulator) { mutableStateOf<SelectionController?>(null) }
    var inputControl by remember(emulator) { mutableStateOf<ComposeController?>(null) }
    var imeController by remember(emulator) { mutableStateOf<TerminalImeController?>(null) }
    var selecting by remember(emulator) { mutableStateOf(false) }
    var keyboardRequested by remember { mutableStateOf(false) }
    var links by remember { mutableStateOf<List<String>?>(null) }
    var oscLink by remember { mutableStateOf<String?>(null) }
    val modifiers = remember(emulator) { TerminalModifiers() }
    val focus = remember { FocusRequester() }
    val visible = LocalWindowContentVisible.current
    val inputReady = visible && state.connected && state.shellReady && !state.exited && state.error == null
    val focusManager = LocalFocusManager.current
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val cursor = remember(emulator) { arrayOfNulls<Rect>(1) }
    SideEffect { session.theme(theme) }
    DisposableEffect(session, visible) {
        session.controller.setVisible(visible)
        session.controller.start()
        onDispose { session.controller.setVisible(false); TextInputFocus.releaseNative(session) }
    }
    LaunchedEffect(tab.metaString("pendingCommand")) {
        tab.metaString("pendingCommand")?.takeIf { it.isNotEmpty() }?.let {
            session.pendingCommand = it
            model.terminalCommandTaken(tab.id)
        }
    }
    LaunchedEffect(state.shellReady, state.connected, session.pendingCommand) {
        val command = session.pendingCommand
        if (command != null && state.shellReady && state.connected) {
            // End the soft keyboard's editor first: a Telex revision must not rewrite into the command's line.
            imeController?.finishBeforeExternalInput()
            if (session.send(command)) {
                session.pendingCommand = null
                // Like the web, type it without Enter; the user decides when to execute it.
                runCatching { focus.requestFocus() }
            }
        }
    }
    LaunchedEffect(inputReady, keyboardRequested, emulator) {
        if (inputReady && keyboardRequested && !selecting) runCatching { focus.requestFocus() }
    }
    // Rich IME composition stays independent of hardware typing. Sticky Ctrl asks the IME
    // for raw key events for the next key, then restores Unicode composition immediately.
    LaunchedEffect(inputControl, modifiers.ctrl) {
        inputControl?.syncImeShortcutInputMode(if (modifiers.ctrl) ImeShortcutInputMode.TYPE_NULL else ImeShortcutInputMode.DISABLED)
    }
    fun copy() {
        val selected = selection?.getSelectionText().orEmpty()
        val text = selected.ifEmpty { session.lastOutput() }
        if (text.isBlank()) model.toast(ErrorToastMessage("Nothing to copy", kind = ErrorToastMessage.Kind.PLAIN, durationMillis = TERMINAL_TOAST_MS)) else {
            // The clipboard service refuses a very large output; the web answers that "Could not copy".
            val copied = runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text)) }.isSuccess
            model.toast(
                if (copied) ErrorToastMessage(if (selected.isNotEmpty()) "Selection copied" else "Last output copied", kind = ErrorToastMessage.Kind.SUCCESS, durationMillis = TERMINAL_TOAST_MS)
                else ErrorToastMessage("Could not copy", durationMillis = TERMINAL_TOAST_MS),
            )
        }
    }
    // The web's focusTerminal(): a key of the row keeps the terminal focused, so the soft keyboard stays up
    // (and comes up when the terminal had lost the focus), rather than taking it as a tap elsewhere does.
    fun focusTerminal() {
        TextInputFocus.asked()
        keyboardRequested = true
        runCatching { focus.requestFocus() }
    }
    // As handlePaste: an empty clipboard does nothing at all, and what was pasted leaves the terminal focused.
    fun paste() {
        if (inputReady) clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() }?.let {
            imeController?.finishBeforeExternalInput()
            emulator.pasteText(it)
            focusTerminal()
        }
    }
    fun chat() {
        val draft = TerminalText.chatDraft(selection = selection?.getSelectionText().orEmpty(), command = session.lastCommand(), output = session.lastOutput()) ?: return
        model.terminalToChat(draft.text, draft.label, session.project)
        model.toast(ErrorToastMessage("Sent to chat", kind = ErrorToastMessage.Kind.SUCCESS, durationMillis = TERMINAL_TOAST_MS))
    }
    fun openLink(value: String) = openInBrowser(context, value) {
        model.toast(ErrorToastMessage("No browser is available to open this link"))
    }
    Column(Modifier.fillMaxSize().background(tokens.bgSolid.color)) {
        Row(
            Modifier.fillMaxWidth().background(tokens.panel.color).cssBorder(tokens.border.color, sides = CssSides.Bottom).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val color = when { state.exited -> tokens.error; state.connected -> tokens.success; state.reconnecting -> tokens.warning; else -> tokens.error }
            Box(Modifier.size(8.dp).background(color.color, CircleShape))
            BasicText(when { state.exited -> "Process exited"; state.connected -> "Connected"; state.reconnecting -> "Reconnecting..."; else -> "Disconnected" }, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
            if (state.exited) TerminalAction("Restart", ProductIcon.RotateCcw, compact = true) { session.restart() }
            Spacer(Modifier.weight(1f))
            TerminalAction("Chat", ProductIcon.MessageSquare, compact = true, action = ::chat)
            state.sessionId?.let {
                BasicText(it, Modifier.weight(1f, fill = false), style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text3.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        key(emulator) {
            Terminal(
                terminalEmulator = emulator,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(4.dp)
                    .onGloballyPositioned { placed[0] = it }
                    .onFocusChanged { focusState ->
                        // A tap on nothing blurs the terminal as it does xterm's textarea: the keyboard goes with the
                        // focus, and the next tap on the terminal asks for it again like the first one did.
                        if (focusState.hasFocus) TextInputFocus.takeNative(session, focusManager, onBlur = {
                            keyboardRequested = false
                            imeController?.hideKeyboard()
                        }) { page ->
                            val field = placed[0]
                            val caret = cursor[0]
                            if (field == null || caret == null || !field.isAttached || !page.isAttached || field.findRootCoordinates() != page.findRootCoordinates()) null
                            else Rect(page.localPositionOf(field, caret.topLeft), page.localPositionOf(field, caret.bottomRight))
                        } else TextInputFocus.releaseNative(session)
                    },
                typeface = face, initialFontSize = 13.sp,
                backgroundColor = tokens.bgSolid.color, foregroundColor = tokens.text.color,
                selectionBackgroundColor = tokens.accentWash.color, selectionForegroundColor = tokens.text.color,
                cursorColor = tokens.accent.color, cursorAccent = tokens.bgSolid.color, minimumContrastRatio = 4.5,
                keyboardEnabled = inputReady && !selecting, showSoftKeyboard = keyboardRequested && !selecting, focusRequester = focus,
                imeMode = TerminalImeMode.CommittedText,
                onComposeControllerAvailable = { inputControl = it },
                onImeControllerAvailable = { imeController = it },
                // Every tap asks, as the web's touchend does, except in select mode, which raises nothing (it has no touchend).
                onTerminalTap = { if (!selecting) { TextInputFocus.asked(); keyboardRequested = true } },
                onCursorBoundsChanged = { cursor[0] = it },
                modifierManager = modifiers,
                onSelectionControllerAvailable = { selection = it },
                // Select mode: a one-finger drag selects from where it came down (useTerminalTouchSelection) and does not scroll.
                touchSelect = selecting,
                selectionActions = { selected ->
                    TerminalText.selectionContext(selected.getSelectionText())?.let { context ->
                        FlowRow(
                            Modifier.shadow(4.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp))
                                .background(tokens.panel.color).cssBorder(tokens.border.color, RoundedCornerShape(6.dp)).padding(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for ((label, newTab) in listOf("Add to current chat" to false, "Add to new chat" to true)) {
                                Row(
                                    Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(4.dp)).background(tokens.panel2.color)
                                        .ppmClickable(onClickLabel = label, pressedColor = tokens.accent.color) {
                                            model.terminalToChat(context.text, context.label, session.project, newTab, asContext = true)
                                            selected.clearSelection()
                                        }.padding(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    PpmIcon(if (newTab) ProductIcon.MessageSquarePlus else ProductIcon.MessageSquare, 16.dp, tokens.text.color)
                                    BasicText(label, style = ppmText(14f).copy(color = tokens.text.color))
                                }
                            }
                        }
                    }
                },
                onPasteRequest = ::paste,
                // A plain address opens at once, as the web's `WebLinksAddon` does; an OSC 8 one asks first, as xterm's own handler does.
                onHyperlinkClick = ::openLink,
                onOsc8HyperlinkClick = { oscLink = it },
            )
        }
        TerminalMobileToolbar(
            ctrlMode = modifiers.ctrl,
            onToggleCtrl = {
                focusTerminal()
                modifiers.ctrl = MobileKey.Ctrl.press(modifiers.ctrl).ctrlArmed
            },
            selectMode = selecting,
            // Like the web's, it selects nothing by itself: the drag does, and a selection outlives the mode.
            onToggleSelect = { selecting = !selecting },
            onKey = { key, repeated ->
                if (!repeated) {
                    focusTerminal()
                    imeController?.finishBeforeExternalInput()
                }
                val press = key.press(modifiers.ctrl)
                modifiers.ctrl = press.ctrlArmed
                press.keystroke?.let(session::sendKey)
            },
            onCopy = ::copy,
            onPaste = ::paste,
            onSendToChat = ::chat,
            onOpenLinks = { links = session.bufferUrls() },
        )
    }
    oscLink?.let { uri -> Osc8LinkConfirm(uri, open = ::openLink, onDismiss = { oscLink = null }) }
    links?.let { urls ->
        TerminalLinksSheet(
            urls = urls,
            scope = model.scope,
            onClose = { links = null },
            open = ::openLink,
            copy = { url -> runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("Link", url)) }.isSuccess },
            toast = model::toast,
            deliver = { url ->
                val api = model.api ?: throw IllegalStateException("Not connected")
                api.call("POST", "/api/loopback/callback", body = buildJsonObject { put("url", url) })
            },
        )
    }
}

/** How long terminal-tab.tsx's toasts stay (`duration: 1500`). */
private const val TERMINAL_TOAST_MS = 1_500L

private class TerminalModifiers : ModifierManager {
    var ctrl by mutableStateOf(false)
    override fun isCtrlActive() = ctrl
    override fun isAltActive() = false
    override fun isShiftActive() = false
    override fun clearTransients() { ctrl = false }
}

@Composable
private fun TerminalAction(label: String, icon: ProductIcon? = null, compact: Boolean = false, action: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.heightIn(min = if (compact) 20.dp else 44.dp)
            .then(if (compact) Modifier else Modifier.width(44.dp))
            .clip(RoundedCornerShape(4.dp))
            .background(tokens.panel2.color)
            .ppmClickable(onClickLabel = label, pressedColor = tokens.accent.color, onClick = action)
            .padding(horizontal = if (compact) 6.dp else 0.dp, vertical = if (compact) 2.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        val color = tokens.text.color
        if (icon != null) PpmIcon(icon, if (compact) 10.dp else 16.dp, color)
        if (icon == null || compact) BasicText(label, Modifier.then(if (icon != null) Modifier.padding(start = 4.dp) else Modifier), style = ppmText(12f, kind = if (icon == null) FontKind.MONO else FontKind.SANS, lineHeight = 16f).copy(color = color))
    }
}
