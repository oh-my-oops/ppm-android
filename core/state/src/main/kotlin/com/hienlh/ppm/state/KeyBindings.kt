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
