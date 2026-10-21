package com.hienlh.ppm.app

import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import com.hienlh.ppm.state.DOCK_BACKTICK
import com.hienlh.ppm.state.DoubleShift
import com.hienlh.ppm.state.KEY_ACTIONS
import com.hienlh.ppm.state.KeyCombo
import com.hienlh.ppm.state.KeyPress

/**
 * The web's window-wide keyboard shortcuts (use-global-keybindings.ts) for a hardware keyboard:
 * [dispatch] sees each key before any view does. The actions this app has are wired; new file,
 * terminal, Source Control, search, Problems, compare and extension keys come with their
 * features. In a text field only the terminal panel toggle and the chat jumps run, and nothing
 * runs while an IME composes.
 */
class KeyboardShortcuts(private val model: AppModel) {
    private val doubleShift = DoubleShift()

    /** The focused field's text at the last Shift down or up, to see what an IME typed in between. */
    private var shiftText: String? = null

    /** Keys whose press ran a shortcut: their release is kept from the views too. */
    private val taken = mutableSetOf<Int>()

    fun dispatch(event: KeyEvent): Boolean {
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
        val ran = if (TextInputFocus.focused) inTextField(press) else anywhere(press)
        if (ran) taken += code
        return ran
    }

    /**
     * The letters of a hardware keyboard reach no key event here while an IME shows its keyboard
     * (Gboard types them itself), so a capital is Shift down and up alone: what the field gained
     * since the last Shift is the typing those events would have shown.
     */
    private fun noteTyping() {
        val text = TextInputFocus.text
        if (text != shiftText) doubleShift.typed()
        shiftText = text
    }

    /** What this app runs, for the system's list of shortcuts (Meta + /), with the keys set in Settings. */
    fun groups(): List<KeyboardShortcutGroup> {
        val ids = buildList {
            add("command-palette")
            if (model.wide) addAll(listOf("toggle-sidebar", "toggle-dock"))
            addAll(listOf("next-tab", "prev-tab", "open-chat", "open-settings", "voice-input", "chat-nav-prev", "chat-nav-next"))
            for (n in 1..minOf(9, model.projects.size)) add("switch-project-$n")
        }
        val items = ids.mapNotNull { id ->
            val label = KEY_ACTIONS.first { it.id == id }.label
            model.keyBindings.binding(id).takeIf { it.isNotEmpty() }?.let { shortcutInfo(label, KeyCombo.parse(it)) }
        }
        return listOf(KeyboardShortcutGroup("PPM", items))
    }

    private fun matches(press: KeyPress, actionId: String) = model.keyBindings.matches(press, actionId)

    /** The keys a text field has no use for. */
    private fun inTextField(press: KeyPress): Boolean = toggleDock(press) || jumpInChat(press)

    /** In the web's order, so that a key set for two actions runs the same one. */
    private fun anywhere(press: KeyPress): Boolean {
        when {
            // The web's stops the browser's save dialog; there is none here, but no later action takes the key.
            matches(press, "save-prevent") -> Unit
            matches(press, "command-palette") -> model.openPalette()
            matches(press, "toggle-sidebar") -> model.toggleSidebar()
            toggleDock(press) -> Unit
            matches(press, "next-tab") -> model.cycleTab(forward = true)
            matches(press, "prev-tab") -> model.cycleTab(forward = false)
            matches(press, "open-chat") -> model.newChat()
            matches(press, "open-settings") -> model.openSettings()
            matches(press, "voice-input") -> model.toggleVoiceInput()
            jumpInChat(press) -> Unit
            else -> model.openProjectAt((1..9).firstOrNull { matches(press, "switch-project-$it") } ?: return false)
        }
        return true
    }

    /** Mod+', and Ctrl+` on the desktop layout: shows or hides the terminal panel. */
    private fun toggleDock(press: KeyPress): Boolean {
        if (!matches(press, "toggle-dock") && !(model.wide && DOCK_BACKTICK.matches(press))) return false
        // A phone's panel is a sheet behind the Terminal button, which comes with the terminal.
        if (!model.wide) return false
        val visible = model.layout?.dock?.visible == true
        // The web's opens a terminal in an empty panel; until this app has one, there is nothing to show.
        if (!visible && model.dockTabs.isEmpty()) return false
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
