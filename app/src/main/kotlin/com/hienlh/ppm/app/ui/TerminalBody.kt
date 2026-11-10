package com.hienlh.ppm.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.LocalRedesign
import com.hienlh.ppm.app.TextInputFocus
import com.hienlh.ppm.app.ui.phone.terminal.PhoneTerminalActionsSheet
import com.hienlh.ppm.app.ui.phone.terminal.PhoneTerminalBottomBarMode
import com.hienlh.ppm.app.ui.phone.terminal.PhoneTerminalExitedBottomBar
import com.hienlh.ppm.app.ui.phone.terminal.PhoneTerminalSelectionActionsBar
import com.hienlh.ppm.app.ui.phone.terminal.PhoneTerminalStatusBar
import com.hienlh.ppm.app.ui.phone.terminal.collectPhoneProjectTerminals
import com.hienlh.ppm.app.ui.phone.terminal.phoneTerminalIndex
import com.hienlh.ppm.app.ui.phone.terminal.resolvePhoneTerminalBottomBarMode
import com.hienlh.ppm.app.ui.phone.terminal.resolvePhoneTerminalTab
import com.hienlh.ppm.app.ui.phone.terminal.shouldFoldPhoneTerminalBarInLandscape
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
import com.hienlh.ppm.state.terminal.TerminalText
import com.hienlh.ppm.state.workspace.DOCK_PANEL_ID
import com.hienlh.ppm.state.workspace.SplitDirection
import com.hienlh.ppm.state.workspace.WorkspaceTab
import com.hienlh.ppm.terminal.TerminalFonts
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.connectbot.terminal.ComposeController
import org.connectbot.terminal.ImeShortcutInputMode
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalImeController
import org.connectbot.terminal.TerminalImeMode

/**
 * Moves a terminal tab from the dock into an editor panel or splits it to the right so two
 * terminals can sit side-by-side (`Tablet 4` Note 73: `"Split terminal uses editor groups"`).
 */
internal fun splitTerminalToEditorRight(
    model: AppModel,
    tabId: String,
    sourcePanelId: String,
) {
    val state = model.layout ?: return
    if (sourcePanelId == DOCK_PANEL_ID) {
        val targetPanelId = state.focusedPanelId.takeIf { it in state.gridPanelIds }
            ?: state.gridPanelIds.firstOrNull()
            ?: return
        val targetTabs = state.panels[targetPanelId]?.tabs.orEmpty()
        if (targetTabs.isEmpty()) {
            model.moveTab(tabId, DOCK_PANEL_ID, targetPanelId)
        } else {
            model.splitPanel(SplitDirection.Right, tabId, DOCK_PANEL_ID, targetPanelId)
        }
    } else {
        model.splitPanel(SplitDirection.Right, tabId, sourcePanelId, sourcePanelId)
    }
}

/**
 * Moves a terminal tab between the dock (`DOCK_PANEL_ID`) and the focused editor grid panel
 * (`Tablet 4` Note 73).
 */
internal fun toggleTerminalDockOrEditor(
    model: AppModel,
    tabId: String,
    sourcePanelId: String,
) {
    val state = model.layout ?: return
    if (sourcePanelId == DOCK_PANEL_ID) {
        val targetPanelId = state.focusedPanelId.takeIf { it in state.gridPanelIds }
            ?: state.gridPanelIds.firstOrNull()
            ?: return
        model.moveTab(tabId, DOCK_PANEL_ID, targetPanelId)
    } else {
        model.moveTab(tabId, sourcePanelId, DOCK_PANEL_ID)
        model.setDockVisible(true)
    }
}

/** The shell owns its parser outside composition; this view only owns input focus/selection. */
@Composable
fun TerminalBody(model: AppModel, tab: WorkspaceTab) {
    val isPhoneRedesign = LocalSizeClass.current == SizeClass.Phone && LocalRedesign.current
    val gridTabs = model.openTabs.map { it.first }
    val dockTabs = model.dockTabs
    val activeDockTabId = model.layout?.dockPanel?.activeTabId
    val projectTerminals = remember(gridTabs, dockTabs, model.selectedProject) {
        collectPhoneProjectTerminals(gridTabs, dockTabs, model.selectedProject)
    }
    val autoResolvedTab = remember(tab, gridTabs, dockTabs, activeDockTabId, model.selectedProject, isPhoneRedesign) {
        if (isPhoneRedesign) {
            resolvePhoneTerminalTab(
                requestedTab = tab,
                gridTabs = gridTabs,
                dockTabs = dockTabs,
                activeDockTabId = activeDockTabId,
                currentProject = model.selectedProject,
            )
        } else {
            tab
        }
    }
    // User can pick another running terminal from the `⋮` sheet on phone; if a newer terminal or
    // a pendingCommand arrives from the AI afterwards, automatically switch to that newest terminal.
    var userPickedTabId by remember(tab.id) { mutableStateOf<String?>(null) }
    var lastAutoMaxIndex by remember(tab.id) { mutableIntStateOf(phoneTerminalIndex(autoResolvedTab)) }
    val currentMaxIndex = remember(projectTerminals) {
        projectTerminals.maxOfOrNull(::phoneTerminalIndex) ?: 0
    }
    val hasIncomingPendingCommand = !autoResolvedTab.metaString("pendingCommand").isNullOrEmpty()
    LaunchedEffect(currentMaxIndex, hasIncomingPendingCommand, autoResolvedTab.id) {
        if (hasIncomingPendingCommand || currentMaxIndex > lastAutoMaxIndex) {
            userPickedTabId = null
            lastAutoMaxIndex = currentMaxIndex
        }
    }
    val effectiveTab = if (isPhoneRedesign && userPickedTabId != null) {
        projectTerminals.firstOrNull { it.id == userPickedTabId } ?: autoResolvedTab
    } else {
        autoResolvedTab
    }

    val session = remember(model, effectiveTab.id) { model.terminal(effectiveTab) } ?: return
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
    val kb = remember(effectiveTab.id) { TerminalKeyboardState() }
    LaunchedEffect(emulator) { kb.selecting = false }
    var links by remember { mutableStateOf<List<String>?>(null) }
    var oscLink by remember { mutableStateOf<String?>(null) }
    var actionsSheetOpen by remember { mutableStateOf(false) }
    var landscapeBarExpanded by remember { mutableStateOf(false) }
    val modifiers = remember(emulator) { TerminalModifiers() }
    val focus = remember(effectiveTab.id) { FocusRequester() }
    val visible = LocalWindowContentVisible.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val inputReady = visible && state.connected && state.shellReady && !state.exited && state.error == null
    val focusManager = LocalFocusManager.current
    val localKeyRowOverride = LocalTerminalKeyRowOverride.current ?: remember { TerminalKeyRowOverrideState() }
    // One for the session's life, not the engine's: the keyboard's pan is given its lambdas when the terminal takes the focus, which an
    // engine replaced afterwards does not repeat.
    val caret = remember(session) { TerminalCaret() }
    SideEffect { session.theme(theme) }
    DisposableEffect(session, visible) {
        session.setShown(visible)
        session.controller.start()
        onDispose { session.setShown(false); TextInputFocus.releaseNative(session) }
    }
    LaunchedEffect(windowFocused) {
        kb.onWindowFocusChanged(windowFocused)
    }
    LaunchedEffect(visible, state.exited) {
        kb.onTerminalLivenessChanged(
            visible = visible,
            exited = state.exited,
            hideKeyboard = { imeController?.hideKeyboard() },
            releaseFocus = { TextInputFocus.releaseNative(session) },
        )
    }
    LaunchedEffect(effectiveTab.id, effectiveTab.metaString("pendingCommand")) {
        effectiveTab.metaString("pendingCommand")?.takeIf { it.isNotEmpty() }?.let {
            session.pendingCommand = it
            model.terminalCommandTaken(effectiveTab.id)
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
                kb.terminalFocused = true
                kb.keyboardRequested = true
                runCatching { focus.requestFocus() }
                imeController?.showKeyboard()
            }
        }
    }
    LaunchedEffect(inputReady, kb.terminalFocused, kb.keyboardRequested, kb.selecting, emulator, imeController) {
        kb.onReadyStateChanged(
            inputReady = inputReady,
            requestFocus = { runCatching { focus.requestFocus() } },
            showKeyboard = { imeController?.showKeyboard() },
        )
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
        kb.focusFromToolbar(
            askFocus = TextInputFocus::asked,
            requestFocus = { runCatching { focus.requestFocus() } },
            showKeyboard = { imeController?.showKeyboard() },
        )
    }
    // As handlePaste: an empty clipboard does nothing at all, and what was pasted leaves the terminal focused.
    fun paste() {
        val current = emulator ?: return
        if (inputReady) clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() }?.let {
            imeController?.finishBeforeExternalInput()
            current.pasteText(it)
            focusTerminal()
        }
    }
    fun chat() {
        val draft = TerminalText.chatDraft(selection = selection?.getSelectionText().orEmpty(), command = session.lastCommand(), output = session.lastOutput()) ?: return
        model.terminalToChat(draft.text, draft.label, session.project)
        model.toast(ErrorToastMessage("Sent to chat", kind = ErrorToastMessage.Kind.SUCCESS, durationMillis = TERMINAL_TOAST_MS))
    }
    fun restartShell() {
        kb.terminalFocused = false
        kb.keyboardRequested = false
        session.restart()
    }
    fun openLink(value: String) = openInBrowser(context, value) {
        model.toast(ErrorToastMessage("No browser is available to open this link"))
    }

    val cwd = effectiveTab.metaString("cwd")
    val hostPanelId = model.layout?.panelOf(effectiveTab.id)?.id

    CompositionLocalProvider(LocalTerminalKeyRowOverride provides localKeyRowOverride) {
        BoxWithConstraints(Modifier.fillMaxSize().background(tokens.bgSolid.color)) {
            val isLandscape = maxWidth > maxHeight
            val imeUp = kb.imeWasVisible || kb.keyboardRequested
            LaunchedEffect(isLandscape, imeUp) {
                if (!isLandscape || !imeUp) landscapeBarExpanded = false
            }
            val foldedInLandscape = isPhoneRedesign && shouldFoldPhoneTerminalBarInLandscape(
                widthDp = maxWidth.value,
                heightDp = maxHeight.value,
                imeVisible = imeUp,
                manuallyExpanded = landscapeBarExpanded,
            )

            Column(Modifier.fillMaxSize().background(tokens.bgSolid.color)) {
                if (isPhoneRedesign) {
                    PhoneTerminalStatusBar(
                        state = state,
                        cwd = cwd,
                        foldedInLandscape = foldedInLandscape,
                        canFoldInLandscape = isLandscape && imeUp && landscapeBarExpanded,
                        onShowFullBar = { landscapeBarExpanded = true },
                        onFoldBar = { landscapeBarExpanded = false },
                        onOpenActionsSheet = { actionsSheetOpen = true },
                        onRetryConnection = { session.controller.foreground() },
                        onRestart = ::restartShell,
                    )
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(tokens.panel.color)
                            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val color = when {
                            state.exited -> tokens.error
                            state.connected -> tokens.success
                            state.reconnecting -> tokens.warning
                            else -> tokens.error
                        }
                        Box(Modifier.size(8.dp).background(color.color, CircleShape))
                        BasicText(
                            when {
                                state.exited -> "Process exited"
                                state.connected -> "Connected"
                                state.reconnecting -> "Reconnecting..."
                                else -> "Disconnected"
                            },
                            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                        )
                        if (!cwd.isNullOrBlank()) {
                            BasicText(
                                "· $cwd",
                                Modifier.weight(1f, fill = false),
                                style = ppmText(11.5f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text3.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (state.exited) {
                            TerminalAction("Restart", ProductIcon.RotateCcw, compact = true, action = ::restartShell)
                        } else if (state.reconnecting || !state.connected) {
                            TerminalAction("Retry now", ProductIcon.RefreshCw, compact = true) {
                                session.controller.foreground()
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (model.wide && hostPanelId != null) {
                            val inDock = hostPanelId == DOCK_PANEL_ID
                            TerminalAction(
                                label = if (inDock) "Move to Editor" else "Move to Dock",
                                icon = if (inDock) ProductIcon.ArrowUpRight else ProductIcon.PanelBottom,
                                compact = true,
                            ) {
                                toggleTerminalDockOrEditor(model, effectiveTab.id, hostPanelId)
                            }
                            TerminalAction(
                                label = "Split right",
                                icon = ProductIcon.Columns2,
                                compact = true,
                            ) {
                                splitTerminalToEditorRight(model, effectiveTab.id, hostPanelId)
                            }
                        }
                        TerminalAction("Chat", ProductIcon.MessageSquare, compact = true, action = ::chat)
                        state.sessionId?.let {
                            BasicText(
                                it,
                                Modifier.weight(1f, fill = false),
                                style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text3.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                if (emulator == null) {
                    Spacer(Modifier.weight(1f).fillMaxWidth())
                } else {
                    val outputAlpha = if (state.reconnecting) 0.55f else 1f
                    key(emulator) {
                        Terminal(
                            terminalEmulator = emulator,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .alpha(outputAlpha)
                                .padding(4.dp)
                                .pointerInput(session) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            if (event.type == PointerEventType.Press) TextInputFocus.pressed(session)
                                        }
                                    }
                                }
                                .onGloballyPositioned(caret::terminalPlaced)
                                .onFocusChanged { focusState ->
                                    // A tap on nothing blurs the terminal as it does xterm's textarea: the keyboard goes with the
                                    // focus, and the next tap on the terminal asks for it again like the first one did.
                                    if (focusState.hasFocus) {
                                        kb.terminalFocused = true
                                        TextInputFocus.takeNative(session, focusManager, onBlur = {
                                            kb.terminalFocused = false
                                            kb.keyboardRequested = false
                                            imeController?.hideKeyboard()
                                        }, keep = caret::keysIn, bounds = caret::cursorIn)
                                    } else {
                                        if (TextInputFocus.field !== session && inputReady && !kb.selecting && session.emulator === emulator) {
                                            kb.terminalFocused = false
                                            kb.keyboardRequested = false
                                        }
                                        TextInputFocus.releaseNative(session)
                                    }
                                },
                            typeface = face,
                            initialFontSize = 13.sp,
                            backgroundColor = tokens.bgSolid.color,
                            foregroundColor = tokens.text.color,
                            selectionBackgroundColor = tokens.accentWash.color,
                            selectionForegroundColor = tokens.text.color,
                            cursorColor = tokens.accent.color,
                            cursorAccent = tokens.bgSolid.color,
                            minimumContrastRatio = 4.5,
                            keyboardEnabled = kb.keyboardEnabled(inputReady),
                            showSoftKeyboard = kb.showSoftKeyboard(inputReady),
                            focusRequester = focus,
                            imeMode = TerminalImeMode.CommittedText,
                            onComposeControllerAvailable = { inputControl = it },
                            onImeControllerAvailable = { imeController = it },
                            onImeVisibilityChanged = { imeVisible ->
                                kb.onImeVisibilityChanged(imeVisible = imeVisible, inputReady = inputReady)
                            },
                            // Every tap asks, as the web's touchend does, except in select mode, which raises nothing (it has no touchend).
                            onTerminalTap = {
                                kb.onTerminalTap(
                                    askFocus = TextInputFocus::asked,
                                    showKeyboard = { imeController?.showKeyboard() },
                                )
                            },
                            onCursorBoundsChanged = caret::cursorMoved,
                            modifierManager = modifiers,
                            onSelectionControllerAvailable = { selection = it },
                            // Select mode: a one-finger drag selects from where it came down (useTerminalTouchSelection) and does not scroll.
                            touchSelect = kb.selecting,
                            selectionActions = { selected ->
                                TerminalText.selectionContext(selected.getSelectionText())?.let { selContext ->
                                    PhoneTerminalSelectionActionsBar(
                                        onAddToCurrentChat = {
                                            model.terminalToChat(selContext.text, selContext.label, session.project, newTab = false, asContext = true)
                                            selected.clearSelection()
                                        },
                                        onAddToNewChat = {
                                            model.terminalToChat(selContext.text, selContext.label, session.project, newTab = true, asContext = true)
                                            selected.clearSelection()
                                        },
                                        onCopy = {
                                            copy()
                                            selected.clearSelection()
                                        },
                                    )
                                }
                            },
                            onPasteRequest = ::paste,
                            // A plain address opens at once, as the web's `WebLinksAddon` does; an OSC 8 one asks first, as xterm's own handler does.
                            onHyperlinkClick = ::openLink,
                            onOsc8HyperlinkClick = { oscLink = it },
                        )
                    }
                }

                val bottomMode = if (isPhoneRedesign) {
                    resolvePhoneTerminalBottomBarMode(state)
                } else {
                    PhoneTerminalBottomBarMode.KeyRow
                }
                when (bottomMode) {
                    PhoneTerminalBottomBarMode.ExitedRestartBar -> {
                        PhoneTerminalExitedBottomBar(
                            isError = false,
                            cwd = cwd,
                            sessionId = state.sessionId,
                            onRestart = ::restartShell,
                            modifier = Modifier.onGloballyPositioned(caret::keysPlaced),
                        )
                    }
                    PhoneTerminalBottomBarMode.ErrorRestartBar -> {
                        PhoneTerminalExitedBottomBar(
                            isError = true,
                            errorMessage = state.error,
                            cwd = cwd,
                            sessionId = state.sessionId,
                            onRestart = ::restartShell,
                            modifier = Modifier.onGloballyPositioned(caret::keysPlaced),
                        )
                    }
                    PhoneTerminalBottomBarMode.KeyRow -> {
                        TerminalMobileToolbar(
                            ctrlMode = modifiers.ctrl,
                            onToggleCtrl = {
                                focusTerminal()
                                modifiers.ctrl = MobileKey.Ctrl.press(modifiers.ctrl).ctrlArmed
                            },
                            selectMode = kb.selecting,
                            // Like the web's, it selects nothing by itself: the drag does, and a selection outlives the mode.
                            onToggleSelect = {
                                kb.onToggleSelect(hideKeyboard = { imeController?.hideKeyboard() })
                            },
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
                            phoneKeyRowMode = isPhoneRedesign,
                            forceVisible = localKeyRowOverride.forceVisible,
                            modifier = Modifier.onGloballyPositioned(caret::keysPlaced),
                        )
                    }
                }
            }
        }
    }

    if (actionsSheetOpen) {
        val currentSelection = selection?.getSelectionText().orEmpty()
        val detectedUrls = remember(session, actionsSheetOpen) { session.bufferUrls() }
        PhoneTerminalActionsSheet(
            sessionId = state.sessionId,
            cwd = cwd,
            hasSelection = currentSelection.isNotEmpty(),
            selectMode = kb.selecting,
            linkCount = detectedUrls.size,
            exited = state.exited,
            projectTerminals = projectTerminals,
            activeTerminalId = effectiveTab.id,
            onCopy = ::copy,
            onPaste = ::paste,
            onToggleSelect = {
                kb.onToggleSelect(hideKeyboard = { imeController?.hideKeyboard() })
            },
            onOpenLinks = { links = detectedUrls },
            onSendToChat = ::chat,
            onRestart = ::restartShell,
            onSelectTerminal = { chosen ->
                userPickedTabId = chosen.id
            },
            onDismiss = { actionsSheetOpen = false },
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

internal class TerminalKeyboardState {
    var terminalFocused by mutableStateOf(false)
    var keyboardRequested by mutableStateOf(false)
    var imeWasVisible by mutableStateOf(false)
    var selecting by mutableStateOf(false)

    fun keyboardEnabled(inputReady: Boolean): Boolean = inputReady && !selecting

    fun showSoftKeyboard(inputReady: Boolean): Boolean = inputReady && keyboardRequested && !selecting

    fun onWindowFocusChanged(windowFocused: Boolean) {
        if (!windowFocused) keyboardRequested = false
    }

    /**
     * A transient socket reconnect drops `connected` or `shellReady` and sets `state.error` (`inputReady == false`)
     * while the tab stays visible and alive; keep `keyboardRequested` and `terminalFocused` across the reconnect so
     * the soft keyboard returns when `inputReady` comes back. Only hide and clear when the tab is hidden or exited.
     */
    fun onTerminalLivenessChanged(
        visible: Boolean,
        exited: Boolean,
        hideKeyboard: () -> Unit,
        releaseFocus: () -> Unit,
    ) {
        if (!visible || exited) {
            keyboardRequested = false
            hideKeyboard()
        }
        if (exited) {
            terminalFocused = false
            releaseFocus()
        }
    }

    fun onReadyStateChanged(
        inputReady: Boolean,
        requestFocus: () -> Unit,
        showKeyboard: () -> Unit,
    ) {
        if (inputReady && !selecting) {
            if (terminalFocused || keyboardRequested) requestFocus()
            if (keyboardRequested) showKeyboard()
        }
    }

    fun onImeVisibilityChanged(imeVisible: Boolean, inputReady: Boolean) {
        if (imeWasVisible && !imeVisible && inputReady && !selecting) {
            keyboardRequested = false
        }
        imeWasVisible = imeVisible
    }

    fun onTerminalTap(
        askFocus: () -> Unit,
        showKeyboard: () -> Unit,
    ) {
        if (!selecting) {
            askFocus()
            terminalFocused = true
            keyboardRequested = true
            showKeyboard()
        }
    }

    fun onToggleSelect(hideKeyboard: () -> Unit) {
        selecting = !selecting
        if (selecting) {
            keyboardRequested = false
            hideKeyboard()
        }
    }

    fun focusFromToolbar(
        askFocus: () -> Unit,
        requestFocus: () -> Unit,
        showKeyboard: () -> Unit,
    ) {
        if (selecting) return
        askFocus()
        terminalFocused = true
        keyboardRequested = true
        showKeyboard()
        requestFocus()
    }
}

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
        Modifier
            .heightIn(min = if (compact) 20.dp else 44.dp)
            .then(if (compact) Modifier else Modifier.width(44.dp))
            .clip(RoundedCornerShape(4.dp))
            .background(tokens.panel2.color)
            .ppmClickable(onClickLabel = label, pressedColor = tokens.accent.color, onClick = action)
            .padding(horizontal = if (compact) 6.dp else 0.dp, vertical = if (compact) 2.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val color = tokens.text.color
        if (icon != null) PpmIcon(icon, if (compact) 10.dp else 16.dp, color)
        if (icon == null || compact) {
            BasicText(
                label,
                Modifier.then(if (icon != null) Modifier.padding(start = 4.dp) else Modifier),
                style = ppmText(12f, kind = if (icon == null) FontKind.MONO else FontKind.SANS, lineHeight = 16f).copy(color = color),
            )
        }
    }
}
