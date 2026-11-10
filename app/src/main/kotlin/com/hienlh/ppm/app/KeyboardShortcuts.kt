package com.hienlh.ppm.app

import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import com.hienlh.ppm.app.ui.editor.SharedCompareStore
import com.hienlh.ppm.chat.TurnTrayEscape
import com.hienlh.ppm.state.DOCK_BACKTICK
import com.hienlh.ppm.state.DoubleShift
import com.hienlh.ppm.state.KEY_ACTIONS
import com.hienlh.ppm.state.KeyCombo
import com.hienlh.ppm.state.KeyPress
import com.hienlh.ppm.state.editor.CompareSelection
import com.hienlh.ppm.state.windows.WindowVisualState
import com.hienlh.ppm.state.windows.frontWindowId
import com.hienlh.ppm.state.workspace.closesItsTabs

/**
 * Stack of topmost tablet layers that `Escape` closes in order (`Foundation` Section 2b, Requirement 16.3):
 * palette -> popovers/menus/sheets -> slide-over sidebar -> floating window.
 */
object TabletEscapeStack {
    private val handlers = mutableListOf<() -> Boolean>()

    fun register(handler: () -> Boolean): () -> Unit {
        handlers.add(handler)
        return { handlers.remove(handler) }
    }

    fun dispatch(): Boolean {
        for (i in handlers.lastIndex downTo 0) {
            if (handlers[i]()) return true
        }
        return false
    }

    internal fun clearForTest() {
        handlers.clear()
    }
}

/**
 * Bridge for tablet shell overlays triggered by global hardware shortcuts (`Ctrl+/` shortcuts sheet,
 * `Ctrl+Tab` / `Ctrl+Shift+Tab` All Tabs switcher).
 */
object TabletShortcutBridge {
    var onToggleShortcutsSheet: (() -> Boolean)? = null
    var onOpenTabSwitcher: ((forward: Boolean) -> Boolean)? = null
}

/**
 * The web's window-wide keyboard shortcuts (use-global-keybindings.ts) for a hardware keyboard:
 * [dispatch] sees each key before any view does. The actions this app has are wired; new file,
 * Source Control, search, Problems, compare and extension keys come with their features. In a
 * text field only the terminal panel toggle and the chat jumps run, and nothing runs while an IME
 * composes.
 */
class KeyboardShortcuts(private val model: AppModel) {
    private val doubleShift = DoubleShift()

    /** The focused field's text at the last Shift down or up, to see what an IME typed in between. */
    private var shiftText: String? = null

    /** Keys whose press ran a shortcut: their release is kept from the views too. */
    private val taken = mutableSetOf<Int>()

    fun dispatch(event: KeyEvent, nativeEditor: Boolean = false): Boolean {
        DragModifierKeys.track(event)
        val code = event.keyCode
        val isShift = code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT
        when (event.action) {
            KeyEvent.ACTION_DOWN -> Unit
            KeyEvent.ACTION_UP -> {
                val otherModifiers = event.isCtrlPressed || event.isMetaPressed || event.isAltPressed
                if (isShift) noteTyping()
                if (isShift && doubleShift.shiftUp(event.eventTime, otherModifiers, TextInputFocus.composing)) model.openPalette()
                return taken.remove(code)
            }
            else -> return false
        }
        if (isShift) noteTyping()
        doubleShift.keyDown(isShift, event.isShiftPressed)
        if (isShift || TextInputFocus.composing) return false
        val key = webKey(event) ?: return false
        val press = KeyPress(key, event.isCtrlPressed, event.isMetaPressed, event.isAltPressed, event.isShiftPressed)
        val ran = if (TextInputFocus.focused || nativeEditor) inTextField(press) else anywhere(press)
        if (ran) taken += code
        return ran
    }

    /**
     * A key going down before an IME showing its keyboard takes it: Gboard types a hardware
     * keyboard's letters itself, Telex too, and [dispatch] never sees them, where a browser still
     * fires a `keydown` ("Process") for each. One between two Shifts is typing, not a double tap:
     * capitals typed fast opened the palette, the field's text not yet showing the letters.
     */
    fun beforeIme(event: KeyEvent) {
        val code = event.keyCode
        if (event.action != KeyEvent.ACTION_DOWN || code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT) return
        doubleShift.keyDown(isShift = false, shiftHeld = event.isShiftPressed)
    }

    /**
     * Typing that comes with no key event at all, the on-screen keyboard's own keys say, shows
     * only in the field's text, where Chrome fires a `keydown` for it all the same: what the field
     * gained since the last Shift is typing too.
     */
    private fun noteTyping() {
        val text = TextInputFocus.text
        if (text != shiftText) doubleShift.typed()
        shiftText = text
    }

    /** What this app runs, for the system's list of shortcuts (Meta + /), with the keys set in Settings. */
    fun groups(): List<KeyboardShortcutGroup> {
        val builtInItems = shortcutActionIds(model.wide, model.projects.size).mapNotNull { id ->
            val label = KEY_ACTIONS.first { it.id == id }.label
            model.keyBindings.binding(id).takeIf { it.isNotEmpty() }?.let { shortcutInfo(label, KeyCombo.parse(it)) }
        }
        val contribs = model.extensions?.contributions?.value
        val extItems = contribs?.keybindings.orEmpty().mapNotNull { kb ->
            val raw = model.keyBindings.binding("ext:${kb.command}").ifEmpty { kb.key }.trim()
            if (raw.isEmpty()) return@mapNotNull null
            val label = contribs?.commands?.firstOrNull { it.command == kb.command }?.title ?: kb.command
            shortcutInfo(label, KeyCombo.parse(raw))
        }
        return listOf(KeyboardShortcutGroup("PPM", builtInItems + extItems))
    }

    private fun matches(press: KeyPress, actionId: String) = model.keyBindings.matches(press, actionId)

    /** The keys a text field has no use for. */
    private fun inTextField(press: KeyPress): Boolean = toggleDock(press) || jumpInChat(press)

    /** In the web's order, so that a key set for two actions runs the same one. */
    private fun anywhere(press: KeyPress): Boolean {
        when {
            // Topmost layer Escape: change tray -> tablet overlays -> front floating window.
            press == KeyPress("Escape") && (TurnTrayEscape.dispatch() || TabletEscapeStack.dispatch() || closeFrontFloatingWindow()) -> Unit
            // The web's stops the browser's save dialog; there is none here, but no later action takes the key.
            matches(press, "save-prevent") -> Unit
            matches(press, "command-palette") -> model.openPalette()
            matches(press, "toggle-sidebar") -> model.toggleSidebar()
            toggleDock(press) -> Unit
            matches(press, "next-tab") -> model.cycleTab(forward = true)
            matches(press, "prev-tab") -> model.cycleTab(forward = false)
            press == KeyPress("Tab", ctrl = true) -> {
                if (TabletShortcutBridge.onOpenTabSwitcher?.invoke(true) != true) model.cycleTab(forward = true)
            }
            press == KeyPress("Tab", ctrl = true, shift = true) -> {
                if (TabletShortcutBridge.onOpenTabSwitcher?.invoke(false) != true) model.cycleTab(forward = false)
            }
            matches(press, "open-chat") -> model.newChat()
            matches(press, "new-file") -> model.openNewFile()
            (press.key.equals("t", ignoreCase = true) && (press.ctrl || press.meta) && !press.alt && !press.shift) -> model.openPalette()
            (press.key.equals("w", ignoreCase = true) && (press.ctrl || press.meta) && !press.alt && !press.shift) -> {
                if (!closeActiveFocusedTab()) return false
            }
            (press.key == "/" && (press.ctrl || press.meta) && !press.alt && !press.shift) -> {
                if (TabletShortcutBridge.onToggleShortcutsSheet?.invoke() != true) model.openSettings("keybindings")
            }
            // A terminal tab in the focused panel (the first on a phone), as the web opens it; toggle-dock shows the terminal panel instead.
            matches(press, "open-terminal") -> model.openTerminal(inDock = false)
            matches(press, "open-settings") -> model.openSettings()
            matches(press, "open-git-status") -> model.showSidebarSection("git")
            matches(press, "open-search") -> model.showSidebarSection("search")
            matches(press, "open-problems") -> model.openProblems()
            matches(press, "compare-files") -> openCompareFiles()
            (press.key.equals("z", ignoreCase = true) && press.alt && !press.ctrl && !press.meta && !press.shift) -> {
                model.setWordWrapPref(!(if (model.wide) model.wordWrap else model.mobileWordWrap))
            }
            matches(press, "voice-input") -> model.toggleVoiceInput()
            jumpInChat(press) -> Unit
            else -> {
                val projIdx = (1..9).firstOrNull { matches(press, "switch-project-$it") }
                if (projIdx != null) {
                    model.openProjectAt(projIdx)
                } else if (!dispatchExtensionKeybinding(press)) {
                    return false
                }
            }
        }
        return true
    }

    private fun closeFrontFloatingWindow(): Boolean {
        if (!model.wide) return false
        val windows = model.windowState.windows
        val frontId = frontWindowId(windows) ?: return false
        val win = windows[frontId] ?: return false
        if (win.state == WindowVisualState.Minimized || closesItsTabs(win)) return false
        model.closeWindow(win.id)
        return true
    }

    private fun closeActiveFocusedTab(): Boolean {
        val state = model.layout ?: return false
        val panelId = state.focusedPanelId.takeIf { it in state.gridPanelIds } ?: state.gridPanelIds.firstOrNull() ?: return false
        val panel = state.panels[panelId] ?: return false
        val active = panel.tabs.firstOrNull { it.id == panel.activeTabId } ?: return false
        if (!active.closable) return false
        model.closeTab(active.id, panelId)
        return true
    }

    private fun openCompareFiles() {
        val focusedPanel = model.layout?.let { it.panels[it.focusedPanelId] }
        val activeTab = focusedPanel?.tabs?.firstOrNull { it.id == focusedPanel.activeTabId }
        val fp = activeTab?.takeIf { it.type == "editor" }?.let { it.metaString("filePath") ?: it.metaString("path") }
        val proj = activeTab?.metaString("projectName") ?: model.selectedProject
        if (!fp.isNullOrBlank() && !proj.isNullOrBlank()) {
            SharedCompareStore.store.selectForCompare(
                CompareSelection(
                    filePath = fp,
                    projectName = proj,
                    dirtyContent = activeTab.metaString("unsavedContent"),
                ),
            )
        }
        model.comparePickerOpen = true
    }

    /** Extension-contributed keybindings (`use-global-keybindings.ts:301-319`). */
    private fun dispatchExtensionKeybinding(press: KeyPress): Boolean {
        val extKbs = model.extensions?.contributions?.value?.keybindings.orEmpty()
        for (kb in extKbs) {
            val raw = model.keyBindings.binding("ext:${kb.command}").ifEmpty { kb.key }.trim()
            if (raw.isEmpty()) continue
            if (KeyCombo.parse(raw).matches(press)) {
                model.dispatchExtCommand(kb.command)
                return true
            }
        }
        return false
    }

    /** Mod+', and Ctrl+` on the desktop layout: shows or hides the terminal panel. */
    private fun toggleDock(press: KeyPress): Boolean {
        if (!matches(press, "toggle-dock") && !(model.wide && DOCK_BACKTICK.matches(press))) return false
        val visible = model.layout?.dock?.visible == true
        model.setDockVisible(!visible)
        return true
    }

    private fun jumpInChat(press: KeyPress): Boolean {
        val direction = when {
            matches(press, "chat-nav-prev") -> -1
            matches(press, "chat-nav-next") -> 1
            else -> return false
        }
        model.jumpInChat(direction)
        return true
    }
}

/**
 * The actions this app runs that the system's list names, in the web's catalog order
 * (`KEY_ACTIONS`). One with no key set is left out of the list, as "Open Terminal Tab" is until
 * Settings gives it a key: the web ships it without one.
 */
internal fun shortcutActionIds(wide: Boolean, projectCount: Int): List<String> = buildList {
    add("command-palette")
    if (wide) addAll(listOf("toggle-sidebar", "toggle-dock"))
    addAll(
        listOf(
            "next-tab",
            "prev-tab",
            "new-file",
            "open-chat",
            "open-terminal",
            "open-settings",
            "open-git-status",
            "open-search",
            "open-problems",
            "voice-input",
            "chat-nav-prev",
            "chat-nav-next",
            "compare-files",
        ),
    )
    for (n in 1..minOf(9, projectCount)) add("switch-project-$n")
}

/** `KeyboardEvent.key` for an Android key: the name of one that types nothing, else what it types with Shift alone. */
internal fun webKey(event: KeyEvent): String? {
    NAMED_KEYS[event.keyCode]?.let { return it }
    if (event.keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) return "F${event.keyCode - KeyEvent.KEYCODE_F1 + 1}"
    val char = event.getUnicodeChar(event.metaState and (KeyEvent.META_SHIFT_MASK or KeyEvent.META_CAPS_LOCK_ON))
    if (char == 0 || (char and KeyCharacterMap.COMBINING_ACCENT) != 0) return null
    return String(Character.toChars(char))
}

private val NAMED_KEYS = mapOf(
    KeyEvent.KEYCODE_DPAD_UP to "ArrowUp",
    KeyEvent.KEYCODE_DPAD_DOWN to "ArrowDown",
    KeyEvent.KEYCODE_DPAD_LEFT to "ArrowLeft",
    KeyEvent.KEYCODE_DPAD_RIGHT to "ArrowRight",
    KeyEvent.KEYCODE_ENTER to "Enter",
    KeyEvent.KEYCODE_NUMPAD_ENTER to "Enter",
    KeyEvent.KEYCODE_ESCAPE to "Escape",
    KeyEvent.KEYCODE_TAB to "Tab",
    KeyEvent.KEYCODE_DEL to "Backspace",
    KeyEvent.KEYCODE_FORWARD_DEL to "Delete",
    KeyEvent.KEYCODE_MOVE_HOME to "Home",
    KeyEvent.KEYCODE_MOVE_END to "End",
    KeyEvent.KEYCODE_PAGE_UP to "PageUp",
    KeyEvent.KEYCODE_PAGE_DOWN to "PageDown",
    KeyEvent.KEYCODE_INSERT to "Insert",
)

private val SYMBOL_KEYS = mapOf(
    "," to KeyEvent.KEYCODE_COMMA,
    "." to KeyEvent.KEYCODE_PERIOD,
    "'" to KeyEvent.KEYCODE_APOSTROPHE,
    "`" to KeyEvent.KEYCODE_GRAVE,
    "[" to KeyEvent.KEYCODE_LEFT_BRACKET,
    "]" to KeyEvent.KEYCODE_RIGHT_BRACKET,
    "/" to KeyEvent.KEYCODE_SLASH,
    ";" to KeyEvent.KEYCODE_SEMICOLON,
    "-" to KeyEvent.KEYCODE_MINUS,
    "=" to KeyEvent.KEYCODE_EQUALS,
    "\\" to KeyEvent.KEYCODE_BACKSLASH,
    " " to KeyEvent.KEYCODE_SPACE,
)

/** A combo as the system lists it; null for a key it has no code for. */
private fun shortcutInfo(label: String, combo: KeyCombo): KeyboardShortcutInfo? {
    val key = combo.key
    val code = when {
        key.length == 1 && key[0] in 'a'..'z' -> KeyEvent.KEYCODE_A + (key[0] - 'a')
        key.length == 1 && key[0] in '0'..'9' -> KeyEvent.KEYCODE_0 + (key[0] - '0')
        key.length in 2..3 && key[0] == 'f' && key.drop(1).toIntOrNull() in 1..12 -> KeyEvent.KEYCODE_F1 + key.drop(1).toInt() - 1
        else -> SYMBOL_KEYS[key] ?: NAMED_KEYS.entries.firstOrNull { it.value.lowercase() == key }?.key ?: return null
    }
    val modifiers = (if (combo.ctrl) KeyEvent.META_CTRL_ON else 0) or
        (if (combo.alt) KeyEvent.META_ALT_ON else 0) or
        (if (combo.shift) KeyEvent.META_SHIFT_ON else 0) or
        (if (combo.meta) KeyEvent.META_META_ON else 0)
    return KeyboardShortcutInfo(label, code, modifiers)
}
