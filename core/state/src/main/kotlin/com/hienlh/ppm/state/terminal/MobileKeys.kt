package com.hienlh.ppm.state.terminal

import kotlinx.coroutines.delay

/**
 * The keys terminal-mobile-toolbar.tsx puts under the terminal on a touch screen (`MOBILE_KEYS`),
 * what each sends, and what the sticky Ctrl does to the key after it.
 *
 * Three things differ from the web, each a bug there. Ctrl is used up by the next key whichever it
 * is: the web's `sendKey` honours it only for a one-character value, so Tab, Esc and ^C swallowed it
 * and sent nothing, and an arrow ignored it and left it armed. An arrow is encoded by the terminal
 * as a keyboard's would be, because the fixed `ESC [ A` is not read as an arrow by a curses program
 * (htop) once it has turned on application cursor keys, which expects `ESC O A`. And a held arrow
 * repeats ([KeyRepeat]), which the web's row, firing on click only, never did.
 */
enum class MobileKey(val label: String) {
    Tab("Tab"),
    Esc("Esc"),
    Ctrl("Ctrl"),
    Up("↑"),
    Down("↓"),
    Left("←"),
    Right("→"),
    CtrlC("^C"),
    ;

    /** Whether holding the key keeps pressing it: the arrows, as on a keyboard. The rest fire once. */
    val repeats: Boolean get() = this == Up || this == Down || this == Left || this == Right

    /**
     * What pressing this key does while Ctrl is [ctrlArmed] or not. Ctrl alone flips; every other
     * key sends its keystroke and uses the armed Ctrl up, the soft keyboard's next key included
     * (the engine's `ModifierManager` clears it there).
     *
     * Tab, Esc and ^C go as themselves with or without Ctrl: Ctrl+I, Ctrl+[ and Ctrl+C are those very
     * bytes, and the encodings a terminal has for Ctrl+Tab and Ctrl+Esc are ones no shell reads.
     */
    fun press(ctrlArmed: Boolean): MobileKeyPress = when (this) {
        Ctrl -> MobileKeyPress(null, !ctrlArmed)
        Tab -> MobileKeyPress(Keystroke.Named(NamedKey.Tab), false)
        Esc -> MobileKeyPress(Keystroke.Named(NamedKey.Escape), false)
        Up -> MobileKeyPress(Keystroke.Named(NamedKey.Up, ctrlArmed), false)
        Down -> MobileKeyPress(Keystroke.Named(NamedKey.Down, ctrlArmed), false)
        Left -> MobileKeyPress(Keystroke.Named(NamedKey.Left, ctrlArmed), false)
        Right -> MobileKeyPress(Keystroke.Named(NamedKey.Right, ctrlArmed), false)
        CtrlC -> MobileKeyPress(Keystroke.Character('c', ctrl = true), false)
    }
}

/** What one press of a [MobileKey] did: [keystroke] to send (none for Ctrl itself) and whether Ctrl is armed after it. */
data class MobileKeyPress(val keystroke: Keystroke?, val ctrlArmed: Boolean)

/** The keys of the row that have no character of their own. */
enum class NamedKey { Tab, Escape, Up, Down, Left, Right }

/**
 * A key as a keyboard has it, left to the terminal's key encoder to turn into bytes. Going through
 * it, rather than writing bytes to the shell, is what keeps the key in order behind text the soft
 * keyboard has just committed, and what lets an arrow follow the program's cursor-key mode.
 */
sealed interface Keystroke {
    /** Whether Ctrl is held with the key. */
    val ctrl: Boolean

    data class Named(val key: NamedKey, override val ctrl: Boolean = false) : Keystroke

    /** A letter key, [char] being its lowercase. */
    data class Character(val char: Char, override val ctrl: Boolean = false) : Keystroke
}

/**
 * When a finger held on a key presses it again: [timeoutMillis] after it went down, then every
 * [intervalMillis], as the key of a hardware keyboard repeats. Android's own numbers are
 * `ViewConfiguration`'s key-repeat timeout and delay.
 *
 * This is the app's addition: the web's row fires on click and has no repeat.
 */
class KeyRepeat(val timeoutMillis: Long, val intervalMillis: Long) {
    init {
        require(timeoutMillis >= 0 && intervalMillis > 0) { "a repeat needs a delay and a positive interval" }
    }

    /** Calls [fire] once the key has been down [timeoutMillis], and then every [intervalMillis], until the caller cancels it. */
    suspend fun whileHeld(fire: () -> Unit): Nothing {
        delay(timeoutMillis)
        while (true) {
            fire()
            delay(intervalMillis)
        }
    }

    companion object {
        /** AOSP's `ViewConfiguration.KEY_REPEAT_DELAY`, for the Androids that predate asking for it. */
        const val DEFAULT_INTERVAL_MILLIS = 50L
    }
}
