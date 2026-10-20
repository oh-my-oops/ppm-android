package com.hienlh.ppm.state

/** One customizable shortcut (`KEY_ACTIONS` in keybindings-store.ts). */
data class KeyAction(
    val id: String,
    val label: String,
    /** `general`, `tabs` or `projects`. */
    val category: String,
    /** `Mod+B`; empty when the action has no default. */
    val defaultKey: String,
    val locked: Boolean = false,
    val note: String? = null,
)

/** The web's catalog, in its order. */
val KEY_ACTIONS: List<KeyAction> = listOf(
    KeyAction("command-palette", "Command Palette", "general", "F1", note = "Shift+Shift also opens (not customizable)"),
    KeyAction("toggle-sidebar", "Toggle Sidebar", "general", "Mod+B"),
    KeyAction(
        "toggle-dock", "Toggle Terminal Panel", "general", "Mod+'",
        note = "Opens/closes the terminal panel; auto-opens a terminal when empty. Fires even when a terminal is focused. Ctrl+` also toggles it on desktop (fixed).",
    ),
    KeyAction("save-prevent", "Prevent Save Dialog", "general", "Mod+S", locked = true, note = "Always active — prevents browser save"),
    KeyAction("next-tab", "Next Tab", "tabs", "Alt+]"),
    KeyAction("prev-tab", "Previous Tab", "tabs", "Alt+["),
    KeyAction("new-file", "New File", "tabs", "Mod+N"),
    KeyAction("open-chat", "Open Chat", "tabs", "Mod+L"),
    KeyAction(
        "open-terminal", "Open Terminal Tab", "tabs", "",
        note = "No default shortcut — Mod+' now toggles the terminal panel; still available via the command palette",
    ),
    KeyAction("open-settings", "Open Settings", "tabs", "Mod+,"),
    KeyAction("open-git-status", "Git Status (sidebar)", "tabs", "Mod+Shift+E"),
    KeyAction("open-search", "Search Files (sidebar)", "tabs", "Mod+Shift+F"),
    KeyAction("open-problems", "Problems", "tabs", "Mod+Shift+M", note = "Errors and warnings across the open files, in the panel — VS Code's shortcut"),
    KeyAction("voice-input", "Voice Input", "general", "Mod+Shift+V", note = "Toggle speech-to-text in chat"),
    KeyAction("chat-nav-prev", "Previous Chat Message", "general", "Alt+ArrowUp", note = "Jump to your previous message in the visible chat transcript"),
    KeyAction("chat-nav-next", "Next Chat Message", "general", "Alt+ArrowDown", note = "Jump to your next message in the visible chat transcript"),
    KeyAction("compare-files", "Compare Files...", "general", "Mod+Alt+D", note = "Open file-compare picker (seeds active file as A)"),
) + (1..9).map { KeyAction("switch-project-$it", "Switch to Project $it", "projects", "Mod+$it") }

private val DEFAULT_KEYS = KEY_ACTIONS.associate { it.id to it.defaultKey }

/** The shortcut an action has unless one is set for it; empty for none. */
fun defaultKey(actionId: String): String = DEFAULT_KEYS[actionId].orEmpty()

/** A combo as the command palette writes it (`formatShortcut`), off a Mac: `Mod+L` is `Ctrl+L`. */
fun formatShortcut(combo: String): String =
    combo.replace("Mod+", "Ctrl+").replace("Meta+", "⌘")

/** A key going down as the web's handler sees it: `KeyboardEvent.key` and the four modifiers. */
data class KeyPress(
    val key: String,
    val ctrl: Boolean = false,
    val meta: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
)

/** `parseCombo`: `Mod` is Ctrl, as on the web anywhere but a Mac. */
data class KeyCombo(val ctrl: Boolean, val meta: Boolean, val alt: Boolean, val shift: Boolean, val key: String) {
    /** `eventMatchesCombo`: every modifier exactly, the key case-blind. */
    fun matches(press: KeyPress): Boolean =
        press.ctrl == ctrl && press.meta == meta && press.alt == alt && press.shift == shift && press.key.lowercase() == key

    companion object {
        fun parse(combo: String): KeyCombo {
            var ctrl = false
            var meta = false
            var alt = false
            var shift = false
            var key = ""
            for (part in combo.split("+")) {
                when (val p = part.trim()) {
                    "Mod", "Ctrl" -> ctrl = true
                    "Meta", "Cmd" -> meta = true
                    "Alt" -> alt = true
                    "Shift" -> shift = true
                    else -> key = p.lowercase()
                }
            }
            return KeyCombo(ctrl, meta, alt, shift, key)
        }
    }
}

/** VS Code's Ctrl+`, a second way to toggle the terminal panel, on the desktop layout only (`matchesDockBacktick`). */
val DOCK_BACKTICK: KeyCombo = KeyCombo.parse("Ctrl+`")

/**
 * The shortcuts in force (keybindings-store.ts): the server's overrides (`/api/settings/keybindings`,
 * shared by every device) over the defaults. An override of "" leaves the action without one.
 */
class KeyBindings(val overrides: Map<String, String> = emptyMap()) {
    private val combos: Map<String, KeyCombo> = KEY_ACTIONS.mapNotNull { action ->
        val combo = overrides[action.id] ?: action.defaultKey
        if (combo.isEmpty()) null else action.id to KeyCombo.parse(combo)
    }.toMap()

    /** `getBinding`. */
    fun binding(actionId: String): String = overrides[actionId] ?: defaultKey(actionId)

    /** `matchesEvent`. */
    fun matches(press: KeyPress, actionId: String): Boolean = combos[actionId]?.matches(press) == true
}

/**
 * Shift pressed and let go on its own twice within 400 ms opens the command palette, and cannot
 * be changed (use-global-keybindings.ts). Shift held for another key (a capital letter) does not
 * count, nor does any key typed between the two.
 */
class DoubleShift {
    private var lastShiftUp = 0L
    private var shiftAlone = false

    /** A key went down: [isShift] for Shift itself, [shiftHeld] for any other key typed with it. */
    fun keyDown(isShift: Boolean, shiftHeld: Boolean) {
        if (isShift) {
            shiftAlone = true
            return
        }
        if (shiftHeld) shiftAlone = false
        lastShiftUp = 0
    }

    /** Shift came up at [at] ms: true when that completes a double tap. */
    fun shiftUp(at: Long, otherModifiers: Boolean, composing: Boolean): Boolean {
        if (!shiftAlone || otherModifiers || composing) return false
        if (at - lastShiftUp < 400) {
            lastShiftUp = 0
            return true
        }
        lastShiftUp = at
        return false
    }
}
