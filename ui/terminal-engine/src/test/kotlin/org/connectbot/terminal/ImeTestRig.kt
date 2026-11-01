package org.connectbot.terminal

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.io.ByteArrayOutputStream
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.robolectric.RuntimeEnvironment

/** The erase byte the terminal sends for Backspace, written out so an expected stream reads as typed. */
internal const val DEL = "\u007f"

/**
 * What libvterm writes to the PTY for the two calls [KeyboardHandler] makes, and a one-line shell to
 * read the bytes with. The real emulator's JNI cannot load on the JVM, so its answers are modelled
 * here from `vterm_keyboard_unichar` / `vterm_keyboard_key`: plain text is UTF-8, Ctrl+letter is the
 * control byte (and Ctrl on a character libvterm cannot fold is CSI u, see [character]), Enter is CR and
 * Backspace is DEL. `CommittedImeInstrumentation` asserts the same literals against the real JNI on a
 * device, so the two cannot drift apart unnoticed.
 */
internal class FakePty {
    private val sent = ByteArrayOutputStream()

    /** Every byte written to the PTY so far, as the server would read it. */
    val bytes: ByteArray get() = sent.toByteArray()

    /** [bytes] as the text it spells, with DEL and CR written out. */
    val stream: String get() = bytes.toString(Charsets.UTF_8)

    /** The command line a shell would hold after these bytes: text appends, DEL erases a character. */
    val line: String get() {
        val shell = StringBuilder()
        for (cp in stream.codePoints().toArray()) when (cp) {
            0x7f -> if (shell.isNotEmpty()) shell.setLength(shell.offsetByCodePoints(shell.length, -1))
            else -> shell.appendCodePoint(cp)
        }
        return shell.toString()
    }

    fun clear() = sent.reset()

    /**
     * `vterm_keyboard_unichar`, as its machine code in the shipped libjni_cb_term.so does it: Shift only
     * matters for Space; with no modifier the character is its UTF-8; Ctrl+[ i j m and every character
     * outside a-z except `\ ] ^ _` (and Space without Shift) is CSI u (`ESC [ code ; modifiers+1 u`)
     * unless only Alt is held; the rest is the character with Ctrl folded into it (`and 0x1f`), after an
     * ESC when Alt is held.
     */
    fun character(modifiers: Int, codepoint: Int) {
        val mod = if (codepoint == ' '.code) modifiers else modifiers and SHIFT.inv()
        if (mod == 0) return write(codepoint)
        val csiU = when (codepoint) {
            '['.code, 'i'.code, 'j'.code, 'm'.code -> true
            '\\'.code, ']'.code, '^'.code, '_'.code -> false
            ' '.code -> modifiers and SHIFT != 0
            else -> codepoint !in 'a'.code..'z'.code
        }
        if (csiU && mod and ALT.inv() != 0) {
            sent.write("\u001b[$codepoint;${mod + 1}u".toByteArray(Charsets.UTF_8))
            return
        }
        if (mod and ALT != 0) sent.write(0x1b)
        write(if (mod and CTRL != 0) codepoint and 0x1f else codepoint)
    }

    private fun write(codepoint: Int) = sent.write(String(Character.toChars(codepoint)).toByteArray(Charsets.UTF_8))

    /**
     * `vterm_keyboard_key`, as its machine code and key table in the shipped libjni_cb_term.so do it. Enter,
     * Backspace and Escape are "literal" keys, and so is Tab unless Shift is held: with Shift or Ctrl they are CSI u
     * (`ESC [ literal ; modifiers+1 u`), with Alt alone they have an ESC in front. Tab with Shift is `ESC [ Z`, or
     * `ESC [ 1 ; modifiers+1 Z` with more held. An arrow is `ESC [ letter`, or `ESC [ 1 ; modifiers+1 letter` with
     * a modifier held (application cursor keys are off). Enter is CR only: newline mode is off.
     */
    fun key(modifiers: Int, key: Int) {
        val arrow = when (key) {
            VTermKey.UP -> 'A'
            VTermKey.DOWN -> 'B'
            VTermKey.RIGHT -> 'C'
            VTermKey.LEFT -> 'D'
            else -> null
        }
        if (arrow != null) {
            sent.write((if (modifiers == 0) "\u001b[$arrow" else "\u001b[1;${modifiers + 1}$arrow").toByteArray())
            return
        }
        val literal = when (key) {
            VTermKey.ENTER -> '\r'.code
            VTermKey.BACKSPACE -> 0x7f
            VTermKey.TAB -> '\t'.code
            VTermKey.ESCAPE -> 0x1b
            else -> error("key $key is not modelled")
        }
        if (key == VTermKey.TAB && modifiers and SHIFT != 0) {
            sent.write((if (modifiers == SHIFT) "\u001b[Z" else "\u001b[1;${modifiers + 1}Z").toByteArray())
        } else if (modifiers and (SHIFT or CTRL) != 0) {
            sent.write("\u001b[$literal;${modifiers + 1}u".toByteArray())
        } else {
            if (modifiers and ALT != 0) sent.write(0x1b)
            sent.write(literal)
        }
    }

    companion object {
        const val SHIFT = 1
        const val ALT = 2
        const val CTRL = 4
    }
}

/** The app's sticky Ctrl, as `TerminalModifiers` in TerminalBody is. */
internal class TestModifiers : ModifierManager {
    var ctrl = false
    override fun isCtrlActive() = ctrl
    override fun isAltActive() = false
    override fun isShiftActive() = false
    override fun clearTransients() { ctrl = false }
}

/**
 * A terminal's soft-keyboard side as the app builds it: the real [ImeInputView] in
 * [TerminalImeMode.CommittedText] and the real [KeyboardHandler], over an emulator mock that turns
 * every dispatch into the bytes libvterm would write. Each test drives it as an IME would, through
 * the `InputConnection` the view hands out.
 */
internal class ImeRig {
    val pty = FakePty()
    val modifiers = TestModifiers()

    /** Key events that reached the keyboard handler, before it turned them into bytes. */
    val keys = mutableListOf<KeyEvent>()

    /** What the terminal draws as unsent text after each IME call. */
    var preedit = ""
        private set

    /** How often the view asked the IME to start over, which makes the IME ask for a new connection. */
    var restarts = 0
        private set
    private var restartPending = true

    /** What the view told the IME about the editor's selection, each as start, end, composing start, composing end. */
    val reports = mutableListOf<List<Int>>()

    /** What the view posted to end its editor: no looper runs it for a view with no window, [settle] does. */
    private var pendingDetach: Runnable? = null

    private var lastInfo = EditorInfo()

    /** What the view told the IME about its editor the last time the IME connected. */
    val info: EditorInfo get() { imeOrNull; return lastInfo }
    private val emulator: TerminalEmulatorImpl = mock(TerminalEmulatorImpl::class.java).also {
        doAnswer { pty.character(it.getArgument(0), it.getArgument(1)) }.`when`(it).dispatchCharacter(anyInt(), anyInt())
        doAnswer { pty.key(it.getArgument(0), it.getArgument(1)) }.`when`(it).dispatchKey(anyInt(), anyInt())
    }
    val keyboard = KeyboardHandler(emulator, modifiers, onInterceptKey = { keys += it.nativeKeyEvent; false }).also {
        // A US keyboard's keys, each its own character: the machine's key maps are not what is under test.
        it.unicodeCharLookup = { _, keyCode, meta ->
            val shifted = meta and KeyEvent.META_SHIFT_ON != 0
            val capital = shifted || meta and KeyEvent.META_CAPS_LOCK_ON != 0
            val symbol = SYMBOLS[keyCode]
            when {
                keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                    val letter = 'a' + (keyCode - KeyEvent.KEYCODE_A)
                    (if (capital) letter.uppercaseChar() else letter).code
                }
                keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    val digit = keyCode - KeyEvent.KEYCODE_0
                    if (shifted) SHIFTED_DIGITS[digit].code else '0'.code + digit
                }
                symbol != null -> (if (shifted) symbol.second else symbol.first).code
                else -> 0
            }
        }
    }
    val view = ImeInputView(
        RuntimeEnvironment.getApplication(), keyboard,
        onUpdateSelection = { _, start, end, from, to -> reports += listOf(start, end, from, to) },
        onRestartInput = { restarts++; restartPending = true },
    ).also {
        it.imeMode = TerminalImeMode.CommittedText
        it.onImePreeditChanged = { text -> preedit = text }
        it.postDetach = { post -> pendingDetach = post }
    }
    private var current: InputConnection? = null

    /**
     * The IME's connection, null when the view told it there is no editor. A view that asked for a restart gets a
     * new one on the IME's next call, as `InputMethodManager.restartInput` makes the IME reconnect; otherwise the
     * IME keeps its own.
     */
    val imeOrNull: InputConnection? get() {
        if (restartPending || current == null) {
            restartPending = false
            lastInfo = EditorInfo()
            current = view.onCreateInputConnection(lastInfo)
        }
        return current
    }

    /** The IME's connection, for a test that expects one. */
    val ime: InputConnection get() = imeOrNull!!

    /** Lets what the view posted run, as the main looper does once the focus has had its chance to move on. */
    fun settle() {
        pendingDetach?.also { pendingDetach = null }?.run()
    }

    /** The IME's idea of the text before the caret — what Gboard reads to decide a Telex revision. */
    val imeContext: String get() = ime.getTextBeforeCursor(Int.MAX_VALUE, 0).toString()

    /** A key as it arrives from a physical keyboard: not flagged as the soft keyboard's. */
    fun hardwareKey(keyCode: Int, meta: Int = 0): Boolean {
        val event = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, meta)
        return view.onKeyPreIme(keyCode, event)
    }

    /** A key pressed with Ctrl held on a physical keyboard, which the view routes to the terminal's key path. */
    fun ctrlKey(keyCode: Int, meta: Int = 0): Boolean =
        view.handleRawKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, meta or KeyEvent.META_CTRL_ON))

    /** A key as the terminal's key path receives it, with exactly the modifiers of [meta] held. */
    fun rawKey(keyCode: Int, meta: Int = 0): Boolean =
        view.handleRawKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
}

/** A US keyboard's symbol keys, unshifted and shifted. */
private val SYMBOLS = mapOf(
    KeyEvent.KEYCODE_SPACE to (' ' to ' '),
    KeyEvent.KEYCODE_MINUS to ('-' to '_'),
    KeyEvent.KEYCODE_LEFT_BRACKET to ('[' to '{'),
    KeyEvent.KEYCODE_RIGHT_BRACKET to (']' to '}'),
    KeyEvent.KEYCODE_BACKSLASH to ('\\' to '|'),
    KeyEvent.KEYCODE_SLASH to ('/' to '?'),
    KeyEvent.KEYCODE_SEMICOLON to (';' to ':'),
)

/** What Shift makes of the digit keys 0 to 9. */
private const val SHIFTED_DIGITS = ")!@#$%^&*("
