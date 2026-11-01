package org.connectbot.terminal

/**
 * What xterm.js writes for Ctrl held with a character (`evaluateKeyboardEvent` in xterm.js's
 * `Keyboard.ts`), which is what the web terminal types and not what libvterm types. libvterm folds Ctrl
 * into the character only for a-z (except i, j and m), `\ ] ^ _` and Space, and writes CSI u for every
 * other character: `ESC[91;5u` for Ctrl+[, `ESC[105;5u` for Ctrl+I, `ESC[67;5u` for Ctrl+C with Caps Lock
 * on or typed as a capital, `ESC[51;5u` for Ctrl+3. A shell prints that as text. (Read from
 * `vterm_keyboard_unichar` in the shipped `libjni_cb_term.so`: its jump table sends `[ijm` to CSI u,
 * `\]^_` to the control code, Space to CSI u only with Shift, and everything else outside a-z to CSI u.)
 */
internal object ControlKeys {
    /** libvterm's modifier bits (`VTermModifier`). */
    const val SHIFT = 1
    const val ALT = 2
    const val CTRL = 4

    /**
     * The control code xterm.js sends for Ctrl+[codepoint], or null when it sends nothing. [shift] is
     * whether a Shift key is held: xterm.js sends nothing for Ctrl+Shift+letter (and so none of
     * Ctrl+Shift+C, which a muscle memory from other terminals means as copy, interrupts anything), and
     * it keeps Shift only for the three symbols that need it, `@` `^` `_`. A soft keyboard cannot say
     * whether Shift is held, so its text is [shift] false: a capital C is as good as a c.
     */
    fun code(codepoint: Int, shift: Boolean = false): Int? = when {
        codepoint == '@'.code -> 0x00 // Ctrl+Shift+2
        codepoint == '^'.code -> 0x1e // Ctrl+Shift+6
        codepoint == '_'.code -> 0x1f // Ctrl+Shift+-
        shift -> null
        codepoint in 'a'.code..'z'.code || codepoint in 'A'.code..'Z'.code -> codepoint and 0x1f
        codepoint == ' '.code -> 0x00
        codepoint in '3'.code..'7'.code -> codepoint - '3'.code + 0x1b // ESC, FS, GS, RS, US
        codepoint == '8'.code -> 0x7f
        codepoint == '/'.code -> 0x1f
        codepoint == '['.code -> 0x1b
        codepoint == '\\'.code -> 0x1c
        codepoint == ']'.code -> 0x1d
        else -> null
    }
}

/**
 * Types [codepoint] with [modifiers] held. Without Ctrl that is libvterm's own encoding. With Ctrl it is
 * [ControlKeys.code] sent as the plain control character (Alt still prefixes ESC, as xterm.js does for
 * Ctrl+Alt+letter), and a combination xterm.js sends nothing for sends nothing, rather than the CSI u
 * libvterm would write.
 */
internal fun TerminalEmulator.dispatchCharacterLikeXterm(modifiers: Int, codepoint: Int) {
    if (modifiers and ControlKeys.CTRL == 0) {
        dispatchCharacter(modifiers, codepoint)
        return
    }
    val code = ControlKeys.code(codepoint, shift = modifiers and ControlKeys.SHIFT != 0) ?: return
    dispatchCharacter(modifiers and ControlKeys.ALT, code)
}
