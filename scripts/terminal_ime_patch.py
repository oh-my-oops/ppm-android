"""Guarded additions for CommittedText IME; called AFTER existing engine patches.

Import by path (the main generator uses python -I):
    spec = importlib.util.spec_from_file_location("terminal_ime_patch", script.with_name("terminal_ime_patch.py"))
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    text = module.patch_terminal_ime(filename, text)

Apply to Terminal.kt, ImeInputView.kt, KeyboardHandler.kt and TerminalDispatcher.kt. Register all
four as patch targets, and include this file as a Gradle task input. Unknown filenames pass through.
New Kotlin types live in ui/terminal-engine/src/main and are compiled normally, not generated.
"""

from pathlib import PurePosixPath
from textwrap import dedent


def replace(text, old, new, count=1):
    found = text.count(old)
    if found != count:
        raise RuntimeError(f"CommittedText patch guard: expected {count}, got {found}: {old[:110]!r}")
    return text.replace(old, new)


def terminal(text):
    for name in ("fun Terminal(", "internal fun TerminalWithAccessibility("):
        if text.count(name) != 1:
            raise RuntimeError(f"CommittedText patch guard: expected one {name}")
        start = text.index(name)
        end = text.index("\n) {", start)
        if "imeMode:" in text[start:end]:
            raise RuntimeError("CommittedText patch already applied")
        text = text[:end] + "\n    imeMode: TerminalImeMode = TerminalImeMode.Legacy,\n    onImeControllerAvailable: ((TerminalImeController) -> Unit)? = null," + text[end:]
    text = replace(text, "        onPageGesture = onPageGesture,", "        onPageGesture = onPageGesture,\n        imeMode = imeMode,\n        onImeControllerAvailable = onImeControllerAvailable,")
    text = replace(text, "keyboardHandler.onKeyEvent(event)", "handleTerminalKey(event)", 2)
    marker = "    var imeInputView by remember { mutableStateOf<ImeInputView?>(null) }"
    text = replace(text, marker, marker + "\n" + dedent('''
        var imePreedit by remember(terminalEmulator) { mutableStateOf("") }
        val currentImeMode by rememberUpdatedState(imeMode)
        var requestedImeShortcutMode by remember(terminalEmulator) { mutableStateOf(ImeShortcutInputMode.DISABLED) }
        val imeController = remember(terminalEmulator) {
            object : TerminalImeController {
                override fun finishBeforeExternalInput() { imeInputView?.finishBeforeExternalInput() }
                override fun showKeyboard() { imeInputView?.apply { imeAllowed = true; showIme() } }
                override fun hideKeyboard() { imeInputView?.hideIme() }
            }
        }
        LaunchedEffect(imeController, onImeControllerAvailable) { onImeControllerAvailable?.invoke(imeController) }
        fun handleTerminalKey(event: ComposeKeyEvent): Boolean =
            if (imeMode == TerminalImeMode.CommittedText) {
                imeInputView?.handleRawKeyEvent(event.nativeKeyEvent) ?: keyboardHandler.onKeyEvent(event)
            } else keyboardHandler.onKeyEvent(event)
    '''))
    text = replace(text, "                    ImeInputView(context, keyboardHandler).apply {", """                    ImeInputView(context, keyboardHandler).apply {
                        this.imeMode = currentImeMode
                        onImePreeditChanged = { imePreedit = it }""")
    text = replace(text, "                    view.onPasteRequest = onPasteRequest", """                    view.onPasteRequest = onPasteRequest
                    view.onImePreeditChanged = { imePreedit = it }
                    view.imeMode = imeMode
                    if (imeMode == TerminalImeMode.CommittedText) view.syncShortcutInputMode(requestedImeShortcutMode)""")
    text = replace(text, "            override fun syncImeShortcutInputMode(mode: ImeShortcutInputMode) {", """            override fun syncImeShortcutInputMode(mode: ImeShortcutInputMode) {
                requestedImeShortcutMode = mode""")
    text = replace(text, "                    if (composeMode.isActive && screenState.scrollbackPosition == 0) {", """                    val overlayText = if (imeMode == TerminalImeMode.CommittedText) imePreedit else composeMode.buffer
                    val overlayActive = if (imeMode == TerminalImeMode.CommittedText) overlayText.isNotEmpty() else composeMode.isActive
                    if (overlayActive && screenState.scrollbackPosition == 0) {""")
    return replace(text, "                            buffer = composeMode.buffer,", "                            buffer = overlayText,")


def ime_input(text):
    marker = "    internal var imeAllowed = true"
    text = replace(text, marker, dedent('''
        /** Full IME editing is separate from legacy line-buffered ComposeMode. */
        var imeMode: TerminalImeMode = TerminalImeMode.Legacy
            set(value) {
                if (field == value) return
                committedConnection?.closeConnection()
                committedConnection = null
                detached = false
                field = value
                if (windowToken != null) onRestartInput(this)
            }
        var onImePreeditChanged: (String) -> Unit = {}
        private var committedConnection: CommittedImeConnection? = null
        fun finishBeforeExternalInput() { committedConnection?.finishBeforeExternalInput() }

        /**
         * The IME was last told this view is no editor at all: no connection and `TYPE_NULL`, which is how Chrome
         * describes a textarea that lost the focus. The editor ends with the view's focus ([onFocusChanged]), but
         * Android leaves the IME attached to a view whose focus a tap on nothing took, because it starts a new
         * connection only for a view that gains the focus when another one is served. The IME then went on typing
         * into an editor that no longer writes anywhere, and a key pressed with nothing focused (one hardware
         * letter was enough) left Gboard composing a word that the next editor inherited: soft taps were added to
         * it, no byte reached the shell, and only a key that restarts the IME brought typing back.
         */
        private var detached = false
        private var detachPending = false

        /** How [detachEditor] is posted; the tests run it themselves, as no looper does for a view with no window. */
        internal var postDetach: (Runnable) -> Unit = { post(it) }

        // Posted, not run as the focus goes: it may be going to another view, which makes the IME start a
        // connection for that one, and `restartInput` does nothing for a view the IME no longer serves.
        private val detachEditor = Runnable {
            detachPending = false
            if (imeMode == TerminalImeMode.CommittedText && !hasFocus()) {
                detached = true
                restartInputSoon()
            }
        }

        /** The focus is back: the IME has to be told to ask for a new editor, as it will not by itself. */
        private fun restartIfEditorEnded() {
            if (imeMode != TerminalImeMode.CommittedText) return
            val ended = detached || committedConnection?.isActive == false
            detached = false
            if (!ended) return
            restartInputSoon()
            // The same editor description does not make the IME drop a word it still composes, and
            // `InputMethodManager` drops a report equal to the last it sent. A caret that moved and came back,
            // with no composing region, is one the IME did not expect: it ends the word.
            onUpdateSelection(this, 1, 1, -1, -1)
            onUpdateSelection(this, 0, 0, -1, -1)
        }

    ''') + marker)
    marker = "        val useFullEditor = isComposeModeActive && shortcutInputMode != ImeShortcutInputMode.TYPE_NULL"
    text = replace(text, marker, dedent('''
        if (imeMode == TerminalImeMode.CommittedText) {
            if (detached) {
                outAttrs.inputType = EditorInfo.TYPE_NULL
                return null
            }
            activeConnection = null
            val raw = shortcutInputMode != ImeShortcutInputMode.DISABLED
            // The same editor Chrome describes for xterm.js's textarea: no keyboard autocorrects it.
            outAttrs.inputType = if (raw) EditorInfo.TYPE_NULL else TerminalEditorInfo.inputType()
            outAttrs.imeOptions = TerminalEditorInfo.IME_OPTIONS
            // Creation can be speculative: Android may keep the preceding handle served.
            // Reuse its editor, and let closing a handle retire only that handle.
            val editor = committedConnection?.takeIf { it.isActive } ?: CommittedImeConnection(
                this,
                onCommit = { text, modifiers -> keyboardHandler.onCommittedText(text, applyModifiers = modifiers) },
                onKey = { keyboardHandler.onKeyEvent(ComposeKeyEvent(it)) },
                onDelete = { keyboardHandler.onImeDeleteBackward() },
                onPreedit = { onImePreeditChanged(it) },
                onSelection = { start, end, ca, cb -> onUpdateSelection(this, start, end, ca, cb) },
                onExtractedText = { token, extracted -> inputMethodManager.updateExtractedText(this, token, extracted) },
                onPaste = { onPasteRequest?.invoke() },
                onInvalidate = { finishShortcutInput(restartWhenStillActive = true) },
                rawMode = raw,
                hasShortcutModifiers = { keyboardHandler.hasTerminalShortcutModifiers() },
            ).also { committedConnection = it }
            outAttrs.initialSelStart = editor.selectionStart
            outAttrs.initialSelEnd = editor.selectionEnd
            return TerminalImeConnectionHandle(this, editor)
        }
    ''') + marker)
    marker = "    fun handleRawKeyEvent(event: KeyEvent): Boolean {"
    text = replace(text, marker, '''    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        // Gboard's Vietnamese IME can transliterate physical letters too (terminal -> tẻminal).
        // Keep unmodified hardware typing immediate. App modifier shortcuts retain their
        // existing dispatch route; software IME keys still use InputConnection.
        if (imeMode == TerminalImeMode.CommittedText && event.action == KeyEvent.ACTION_DOWN &&
            event.flags and KeyEvent.FLAG_SOFT_KEYBOARD == 0 && !event.isSystem &&
            !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed &&
            !KeyEvent.isModifierKey(keyCode) && handleRawKeyEvent(event)) return true
        return super.onKeyPreIme(keyCode, event)
    }

''' + marker)
    text = replace(text, marker, marker + '''
        if (imeMode == TerminalImeMode.CommittedText) {
            val connection = committedConnection?.takeIf { it.isActive }
            if (connection != null) return connection.handleRawKeyEvent(event)
            // IME restart is asynchronous; hardware input must not wait for it.
            if (shortcutInputMode == ImeShortcutInputMode.DISABLED) return keyboardHandler.onKeyEvent(ComposeKeyEvent(event))
        }''')
    text = replace(text, "            isComposeModeActive &&\n            keyboardHandler.hasTerminalShortcutModifiers()", "            (isComposeModeActive || imeMode == TerminalImeMode.CommittedText) &&\n            keyboardHandler.hasTerminalShortcutModifiers()")
    text = replace(text, "        resetImeBuffer()\n        shortcutInputMode = nextMode", "        committedConnection?.flushPending()\n        resetImeBuffer()\n        shortcutInputMode = nextMode")
    text = replace(text, "    fun resetImeBuffer() {", "    fun resetImeBuffer() {\n        committedConnection?.closeConnection()\n        committedConnection = null")
    text = replace(text, "        if (!gainFocus) {", "        if (gainFocus) {\n            detachPending = false\n            removeCallbacks(detachEditor)\n            restartIfEditorEnded()\n        }\n        if (!gainFocus) {\n            committedConnection?.closeConnection()\n            if (imeMode == TerminalImeMode.CommittedText) {\n                detachPending = true\n                postDetach(detachEditor)\n            }")
    text = replace(
        text,
        "            restoreOnWindowFocus = hasFocus() && lastImeVisible && imeAllowed",
        "            restoreOnWindowFocus = imeMode != TerminalImeMode.CommittedText && hasFocus() && lastImeVisible && imeAllowed\n            if (imeMode == TerminalImeMode.CommittedText) pendingShow = false",
    )
    text = replace(
        text,
        "        if (isAttachedToWindow && hasFocus()) onHideKeyboard(this)",
        "        if (hasFocus() || detachPending) onHideKeyboard(this)",
    )
    # No editor is a null connection, which the platform's own signature allows.
    text = replace(text, "    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {", "    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {")
    return replace(
        text,
        "    override fun onDetachedFromWindow() {\n        hideIme()",
        "    override fun onDetachedFromWindow() {\n        removeCallbacks(detachEditor)\n        committedConnection?.closeConnection()\n        if (detachPending) {\n            detached = true\n            restartInputSoon()\n        }\n        hideIme()\n        detachPending = false",
    )


def keyboard(text):
    marker = "    fun onCommittedText(text: String) {"
    start = text.index(marker)
    end = text.index("\n    }", start) + len("\n    }")
    old = text[start:end]
    new = replace(old, marker, "    fun onCommittedText(text: String, applyModifiers: Boolean = true) {")
    new = replace(new, "        val modifiers = getModifierMask()", "        val modifiers = if (applyModifiers) getModifierMask() else 0")
    new = replace(new, "        modifierManager?.clearTransients()", "        if (applyModifiers) modifierManager?.clearTransients()")
    text = replace(text, old, new)
    # Ctrl+character as xterm.js writes it (ControlKeys), not as libvterm does: for a key event here and
    # for committed text in dispatchText.
    text = replace(
        text,
        "            dispatchCodePoint(codepoint) { cp -> terminalEmulator.dispatchCharacter(modifiers, cp) }",
        "            dispatchCodePoint(codepoint) { cp -> terminalEmulator.dispatchCharacterLikeXterm(modifiers, cp) }",
    )
    # Enter, Tab, Backspace and Escape with Ctrl, Shift or Alt held as xterm.js writes them, not as the CSI u
    # libvterm writes. The compose-mode branches above this one are the legacy buffering PPM never turns on.
    return replace(
        text,
        "            terminalEmulator.dispatchKey(modifiers, vtermKey)\n",
        "            terminalEmulator.dispatchKeyLikeXterm(modifiers, vtermKey)\n",
    )


def dispatcher(text):
    text = replace(text, "            terminal.dispatchCharacter(modifiers, cp)\n", "            terminal.dispatchCharacterLikeXterm(modifiers, cp)\n")
    # An Enter the soft keyboard commits while the toolbar's Ctrl is armed.
    return replace(text, "            terminal.dispatchKey(modifiers, VTermKey.ENTER)\n", "            terminal.dispatchKeyLikeXterm(modifiers, VTermKey.ENTER)\n")


def patch_terminal_ime(filename, text):
    """Pure, fail-closed transformation; never reads/writes files or downloads anything."""
    patch = {"Terminal.kt": terminal, "ImeInputView.kt": ime_input, "KeyboardHandler.kt": keyboard, "TerminalDispatcher.kt": dispatcher}.get(PurePosixPath(filename).name)
    return patch(text) if patch else text
