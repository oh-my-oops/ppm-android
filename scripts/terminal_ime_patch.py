"""Guarded additions for CommittedText IME; called AFTER existing engine patches.

Import by path (the main generator uses python -I):
    spec = importlib.util.spec_from_file_location("terminal_ime_patch", script.with_name("terminal_ime_patch.py"))
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    text = module.patch_terminal_ime(filename, text)

Apply to Terminal.kt, ImeInputView.kt and KeyboardHandler.kt. Register all three as patch
targets, and include this file as a Gradle task input. Unknown filenames pass through.
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
            TerminalImeController { imeInputView?.finishBeforeExternalInput() }
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
                field = value
                if (windowToken != null) onRestartInput(this)
            }
        var onImePreeditChanged: (String) -> Unit = {}
        private var committedConnection: CommittedImeConnection? = null
        fun finishBeforeExternalInput() { committedConnection?.finishBeforeExternalInput() }

    ''') + marker)
    marker = "        val useFullEditor = isComposeModeActive && shortcutInputMode != ImeShortcutInputMode.TYPE_NULL"
    text = replace(text, marker, dedent('''
        if (imeMode == TerminalImeMode.CommittedText) {
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
    text = replace(text, "        if (!gainFocus) {", "        if (!gainFocus) {\n            committedConnection?.closeConnection()")
    return replace(text, "    override fun onDetachedFromWindow() {", "    override fun onDetachedFromWindow() {\n        committedConnection?.closeConnection()")


def keyboard(text):
    marker = "    fun onCommittedText(text: String) {"
    start = text.index(marker)
    end = text.index("\n    }", start) + len("\n    }")
    old = text[start:end]
    new = replace(old, marker, "    fun onCommittedText(text: String, applyModifiers: Boolean = true) {")
    new = replace(new, "        val modifiers = getModifierMask()", "        val modifiers = if (applyModifiers) getModifierMask() else 0")
    new = replace(new, "        modifierManager?.clearTransients()", "        if (applyModifiers) modifierManager?.clearTransients()")
    return replace(text, old, new)


def patch_terminal_ime(filename, text):
    """Pure, fail-closed transformation; never reads/writes files or downloads anything."""
    patch = {"Terminal.kt": terminal, "ImeInputView.kt": ime_input, "KeyboardHandler.kt": keyboard}.get(PurePosixPath(filename).name)
    return patch(text) if patch else text
