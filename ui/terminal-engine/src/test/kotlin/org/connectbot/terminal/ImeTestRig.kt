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
 * control byte, Enter is CR and Backspace is DEL. `CommittedImeInstrumentation` asserts the same
 * literals against the real JNI on a device, so the two cannot drift apart unnoticed.
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

    fun character(modifiers: Int, codepoint: Int) {
        when {
            modifiers == 0 -> sent.write(String(Character.toChars(codepoint)).toByteArray(Charsets.UTF_8))
            modifiers == CTRL && codepoint in 'a'.code..'z'.code -> sent.write(codepoint and 0x1f)
            else -> error("modifiers $modifiers on U+${codepoint.toString(16)} is not modelled")
        }
    }

    fun key(modifiers: Int, key: Int) {
        check(modifiers == 0) { "modifiers $modifiers on key $key is not modelled" }
        sent.write(when (key) {
            VTermKey.ENTER -> '\r'.code
            VTermKey.BACKSPACE -> 0x7f
            VTermKey.TAB -> '\t'.code
            VTermKey.ESCAPE -> 0x1b
            else -> error("key $key is not modelled")
        })
    }

    companion object { const val CTRL = 4 }
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

    private var lastInfo = EditorInfo()

    /** What the view told the IME about its editor the last time the IME connected. */
    val info: EditorInfo get() { ime; return lastInfo }
    private val emulator: TerminalEmulatorImpl = mock(TerminalEmulatorImpl::class.java).also {
        doAnswer { pty.character(it.getArgument(0), it.getArgument(1)) }.`when`(it).dispatchCharacter(anyInt(), anyInt())
        doAnswer { pty.key(it.getArgument(0), it.getArgument(1)) }.`when`(it).dispatchKey(anyInt(), anyInt())
    }
    val keyboard = KeyboardHandler(emulator, modifiers, onInterceptKey = { keys += it.nativeKeyEvent; false }).also {
        // A letter key is its own character: the machine's key maps are not what is under test.
        it.unicodeCharLookup = { _, keyCode, meta ->
            if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                val letter = 'a' + (keyCode - KeyEvent.KEYCODE_A)
                (if (meta and KeyEvent.META_SHIFT_ON != 0) letter.uppercaseChar() else letter).code
            } else 0
        }
    }
    val view = ImeInputView(
        RuntimeEnvironment.getApplication(), keyboard,
        onUpdateSelection = { _, _, _, _, _ -> },
        onRestartInput = { restarts++; restartPending = true },
    ).also {
        it.imeMode = TerminalImeMode.CommittedText
        it.onImePreeditChanged = { text -> preedit = text }
    }
    private var current: InputConnection? = null

    /**
     * The IME's connection. A view that asked for a restart gets a new one on the IME's next call, as
     * `InputMethodManager.restartInput` makes the IME reconnect; otherwise the IME keeps its own.
     */
    val ime: InputConnection get() {
        if (restartPending || current == null) {
            restartPending = false
            lastInfo = EditorInfo()
            current = view.onCreateInputConnection(lastInfo)
        }
        return current!!
    }

    /** The IME's idea of the text before the caret — what Gboard reads to decide a Telex revision. */
    val imeContext: String get() = ime.getTextBeforeCursor(Int.MAX_VALUE, 0).toString()

    /** A key as it arrives from a physical keyboard: not flagged as the soft keyboard's. */
    fun hardwareKey(keyCode: Int, meta: Int = 0): Boolean {
        val event = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, meta)
        return view.onKeyPreIme(keyCode, event)
    }
}
