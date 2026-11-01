package org.connectbot.terminal

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.TextAttribute
import java.io.ByteArrayOutputStream

/** Runs against Android's real Editable/BaseInputConnection and the pinned JNI, without opening PPM. */
class CommittedImeInstrumentation : Instrumentation() {
    private val passed = mutableListOf<String>()

    /** What the real library did where the check only reports it, printed after the passes and not counted with them. */
    private val notes = mutableListOf<String>()

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = runCatching { connectionChecks(); nativeChecks(); replayChecks() }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", passed.joinToString("\n", postfix = "\n") + notes.joinToString("\n", postfix = "\n") +
                if (result.isSuccess) "PASS: ${passed.size} committed-IME checks\n" else result.exceptionOrNull()!!.stackTraceToString())
        })
    }

    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    private fun case(name: String, action: () -> Unit) {
        main(action)
        passed += "PASS: $name"
    }

    private inner class Probe {
        val commits = mutableListOf<Pair<String, Boolean>>()
        val keys = mutableListOf<KeyEvent>()
        val events = mutableListOf<String>()
        val preedits = mutableListOf<String>()
        val selections = mutableListOf<List<Int>>()
        val extracted = mutableListOf<Pair<Int, ExtractedText>>()
        var projected = ""
        var deletes = 0
        val connection = CommittedImeConnection(
            View(targetContext),
            onCommit = { text, modifiers -> commits += text to modifiers; events += "text:$text"; projected += text },
            onKey = { keys += it; events += "key:${it.keyCode}"; true },
            onDelete = { deletes++; projected = projected.substring(0, projected.offsetByCodePoints(projected.length, -1)) },
            onPreedit = { preedits += it },
            onSelection = { a, b, ca, cb -> selections += listOf(a, b, ca, cb) },
            onExtractedText = { token, text -> extracted += token to text },
            onInvalidate = { events += "restart" },
            onPaste = { events += "paste" },
        )
    }

    private fun connectionChecks() {
        case("Telex revisions remain local until the final Vietnamese commit") {
            val p = Probe()
            for (text in listOf("t", "ti", "tie", "tiê", "tiên", "tiến", "tiếng")) {
                check(p.connection.setComposingText(text, 1))
                check(p.commits.isEmpty() && p.keys.isEmpty())
                check(p.connection.preedit == text)
            }
            p.connection.commitText("tiếng", 1)
            check(p.commits == listOf("tiếng" to false))
            check(p.connection.preedit.isEmpty())
            p.connection.finishComposingText()
            check(p.commits.size == 1)
        }
        case("CJK replacements and supplementary characters produce no speculative deletes") {
            val p = Probe()
            for (text in listOf("n", "ni", "你", "你好", "您好😀")) p.connection.setComposingText(text, 1)
            check(p.commits.isEmpty() && p.keys.isEmpty())
            p.connection.commitText("您好😀", 1)
            check(p.commits == listOf("您好😀" to false))
        }
        case("finish finalizes once and two independent identical commits both arrive") {
            val p = Probe()
            p.connection.setComposingText("日本語", 1)
            p.connection.finishComposingText()
            p.connection.finishComposingText()
            p.connection.commitText("你", 1)
            p.connection.commitText("你", 1)
            check(p.commits.map { it.first } == listOf("日本語", "你", "你"))
        }
        case("cancelled candidates and empty commits emit nothing") {
            val p = Probe()
            p.connection.setComposingText("cancel", 1)
            p.connection.setComposingText("", 1)
            p.connection.finishComposingText()
            p.connection.commitText("", 1)
            check(p.commits.isEmpty() && p.keys.isEmpty())
        }
        case("composing region replacement preserves its unsent prefix and suffix") {
            val p = Probe()
            p.connection.setComposingText("abcd", 1)
            p.connection.setComposingRegion(1, 3)
            p.connection.commitText("道😀", 1)
            check(p.commits == listOf("a道😀d" to false))
            check(p.keys.isEmpty())
        }
        case("UTF-16 and code-point deletion keep emoji intact and stay local") {
            val p = Probe()
            p.connection.setComposingText("A😀B", 1)
            p.connection.setComposingRegion(0, 0)
            p.connection.setSelection(3, 3)
            p.connection.deleteSurroundingText(1, 0)
            check(p.connection.preedit == "AB")
            p.connection.setSelection(1, 1)
            p.connection.deleteSurroundingTextInCodePoints(0, 1)
            check(p.connection.preedit == "A")
            p.connection.setComposingText("😀😁", 1)
            p.connection.setComposingRegion(0, 0)
            p.connection.setSelection(5, 5)
            p.connection.deleteSurroundingTextInCodePoints(1, 0)
            check(p.connection.preedit == "A😀")
            check(p.commits.isEmpty() && p.keys.isEmpty())
            p.connection.finishComposingText()
            check(p.commits == listOf("A😀" to false))
        }
        case("surrounding deletion excludes the composing span and never spills into terminal history") {
            val p = Probe()
            p.connection.setComposingText("你好", 1)
            p.connection.deleteSurroundingText(10, 10)
            check(p.connection.preedit == "你好")
            check(p.keys.isEmpty())
            check(!p.connection.deleteSurroundingText(Int.MAX_VALUE, 0))
        }
        case("empty-context erases are bounded terminal keys and zero-zero is a no-op") {
            val p = Probe()
            p.connection.deleteSurroundingText(0, 0)
            check(p.keys.isEmpty())
            p.connection.deleteSurroundingTextInCodePoints(1, 1)
            check(p.keys.map { it.keyCode } == listOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL))
            check(!p.connection.deleteSurroundingText(-1, 0))
            check(!p.connection.deleteSurroundingText(0, Int.MAX_VALUE))
            check(p.keys.size == 2)
        }
        case("invalid surrogate input and selection cannot corrupt pending Unicode") {
            val p = Probe()
            p.connection.setComposingText("😀", 1)
            check(!p.connection.setSelection(1, 1))
            check(!p.connection.setComposingRegion(1, 2))
            check(!p.connection.commitText("\uD83D", 1))
            check(!p.connection.setComposingText("\uDE00", 1))
            check(p.connection.preedit == "😀")
            p.connection.finishComposingText()
            check(p.commits == listOf("😀" to false))
        }
        case("nested batches publish one immutable extracted-text and selection update") {
            val p = Probe()
            val request = ExtractedTextRequest().apply { token = 7 }
            val original = p.connection.getExtractedText(request, InputConnection.GET_EXTRACTED_TEXT_MONITOR)!!
            p.connection.beginBatchEdit(); p.connection.beginBatchEdit()
            p.connection.setComposingText("a", 1)
            p.connection.setComposingText("á😀", 1)
            check(p.selections.isEmpty() && p.preedits.isEmpty())
            check(p.connection.endBatchEdit())
            check(p.selections.isEmpty())
            check(!p.connection.endBatchEdit())
            check(p.selections == listOf(listOf(3, 3, 0, 3)))
            check(p.preedits == listOf("á😀"))
            check(p.extracted.single().first == 7 && p.extracted.single().second.text.toString() == "á😀")
            check(original.text.toString().isEmpty())
        }
        case("raw Enter flushes preedit first and invalidates late replay on the old connection") {
            val p = Probe()
            p.connection.setComposingText("你", 1)
            p.connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            check(p.events == listOf("text:你", "key:${KeyEvent.KEYCODE_ENTER}", "restart"))
            check(!p.connection.commitText("你", 1))
            check(!p.connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
            check(p.commits.size == 1 && p.keys.size == 1)
        }
        case("modifier-only keys preserve preedit and a physical shortcut follows an unmodified flush") {
            val p = Probe()
            p.connection.setComposingText("tiếng", 1)
            p.connection.handleRawKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT))
            check(p.commits.isEmpty() && p.connection.preedit == "tiếng")
            p.connection.handleRawKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON))
            check(p.commits == listOf("tiếng" to false))
            check(p.events == listOf("key:${KeyEvent.KEYCODE_CTRL_LEFT}", "text:tiếng", "key:${KeyEvent.KEYCODE_C}", "restart"))
        }
        case("editor actions flush before one Enter and ordinary commits do not invent Enter") {
            val p = Probe()
            p.connection.commitText("plain", 1)
            check(p.keys.isEmpty())
            p.connection.setComposingText("候補", 1)
            p.connection.performEditorAction(EditorInfo.IME_ACTION_DONE)
            check(p.events == listOf("text:plain", "text:候補", "key:${KeyEvent.KEYCODE_ENTER}", "restart"))
        }
        case("closing discards unsent text and rejects every late mutating callback") {
            val p = Probe()
            p.connection.setComposingText("old terminal", 1)
            p.connection.closeConnection(); p.connection.closeConnection()
            check(!p.connection.commitText("late", 1))
            check(!p.connection.finishComposingText())
            check(!p.connection.deleteSurroundingText(1, 0))
            check(!p.connection.setComposingText("late", 1))
            check(!p.connection.setSelection(0, 0))
            check(!p.connection.beginBatchEdit())
            check(p.commits.isEmpty() && p.keys.isEmpty() && p.connection.preedit.isEmpty())
        }
        if (Build.VERSION.SDK_INT >= 34) case("API-34 replaceText edits only locally owned text and rejects stale history offsets") {
            val p = Probe()
            p.connection.setComposingText("abc", 1)
            p.connection.replaceText(1, 2, "道😀", 1, null)
            check(p.commits == listOf("a道😀c" to false))
            p.connection.finishBeforeExternalInput()
            check(!p.connection.replaceText(0, 3, "stale", 1, null))
            check(p.commits.size == 1 && p.keys.isEmpty())
        }
        if (Build.VERSION.SDK_INT >= 34) case("real Gboard Telex trace with TextAttribute replaces only this editor's sent text") {
            val p = Probe()
            val attr = TextAttribute.Builder().build()
            fun commit(s: String) { check(p.connection.commitText(s, 1, attr)) }
            fun replace(a: Int, b: Int, s: String) { check(p.connection.replaceText(a, b, s, 1, attr)) }
            commit("t"); commit("i"); commit("e"); replace(2, 3, "ê")
            commit("n"); commit("g"); replace(2, 5, "ếng"); commit(" ")
            commit("v"); commit("i"); commit("e"); replace(8, 9, "ê")
            commit("t"); replace(8, 10, "ệt"); commit(" ")
            check(p.projected == "tiếng việt ") { p.projected }
            check(p.deletes == 7 && p.connection.preedit.isEmpty())
            check(p.connection.getTextBeforeCursor(100, 0).toString() == p.projected)
            check(!p.connection.replaceText(0, 1000, "outside", 1, attr))
            p.connection.finishComposingText()
            check(p.projected == "tiếng việt " && p.deletes == 7)
        }
        case("speculative handles share the editor and closing one cannot close its successor") {
            val p = Probe()
            val first = TerminalImeConnectionHandle(View(targetContext), p.connection)
            val speculative = TerminalImeConnectionHandle(View(targetContext), p.connection)
            first.commitText("ti", 1)
            check(speculative.getTextBeforeCursor(100, 0).toString() == "ti")
            speculative.closeConnection()
            check(!speculative.commitText("late", 1))
            first.commitText("ếng", 1)
            val next = TerminalImeConnectionHandle(View(targetContext), p.connection)
            first.closeConnection()
            next.commitText(" việt", 1)
            check(p.projected == "tiếng việt")
            p.connection.closeConnection()
            check(!next.commitText("old owner", 1))
        }
        case("context paste flushes preedit and retires both history and late replay") {
            val p = Probe()
            p.connection.commitText("prefix", 1)
            p.connection.setComposingText("你", 1)
            p.connection.performContextMenuAction(android.R.id.paste)
            check(p.events == listOf("text:prefix", "text:你", "paste", "restart"))
            check(!p.connection.commitText("你", 1))
            check(p.connection.getTextBeforeCursor(100, 0).toString().isEmpty())
        }
        case("hardware navigation retires committed history even without a composing span") {
            val p = Probe()
            p.connection.commitText("recent", 1)
            p.connection.handleRawKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
            check(!p.connection.commitText("replay", 1))
            check(p.projected == "recent" && p.deletes == 0)
            check(p.events.last() == "restart")
        }
        case("owned history is bounded and overflow rejects old offsets and callbacks") {
            val p = Probe()
            p.connection.commitText("x".repeat(TerminalImeHistory.LIMIT), 1)
            check(p.connection.getTextBeforeCursor(Int.MAX_VALUE, 0)?.length == TerminalImeHistory.LIMIT)
            p.connection.commitText("y", 1)
            check(!p.connection.isActive)
            check(p.connection.getTextBeforeCursor(100, 0).toString().isEmpty())
            check(!p.connection.deleteSurroundingText(100, 0))
            check(p.projected == "x".repeat(TerminalImeHistory.LIMIT) + "y" && p.deletes == 0)
        }
    }

    /** Connection -> existing KeyboardHandler -> QueuedTerminal -> real JNI -> emitted UTF-8 bytes. */
    private fun nativeChecks() {
        val bytes = ByteArrayOutputStream()
        val modifiers = object : ModifierManager {
            var ctrl = false
            override fun isCtrlActive() = ctrl
            override fun isAltActive() = false
            override fun isShiftActive() = false
            override fun clearTransients() { ctrl = false }
        }
        lateinit var emulator: TerminalEmulatorImpl
        lateinit var keyboard: KeyboardHandler
        lateinit var view: ImeInputView
        lateinit var connection: InputConnection
        fun drain(): String { emulator.commands.call { }; waitForIdleSync(); return bytes.toByteArray().toString(Charsets.UTF_8) }
        fun create(): InputConnection = view.onCreateInputConnection(EditorInfo())
        main {
            emulator = TerminalEmulatorFactory.create(onKeyboardInput = bytes::write) as TerminalEmulatorImpl
            keyboard = KeyboardHandler(QueuedTerminal(emulator), modifiers)
            view = ImeInputView(targetContext, keyboard, onRestartInput = {})
            view.imeMode = TerminalImeMode.CommittedText
            val info = EditorInfo()
            connection = view.onCreateInputConnection(info)
            check(connection is TerminalImeConnectionHandle)
            // Chrome's numbers for xterm.js's textarea (dumpsys input_method); 0x200000 is its Android 17 flag.
            // The options carry one more, 0x01000000 (no personalised learning), which Chrome omits.
            val chromeType = if (Build.VERSION.SDK_INT >= 37) 0x2200a1 else 0x200a1
            check(info.inputType == chromeType) { "inputType 0x${info.inputType.toString(16)}, Chrome's is 0x${chromeType.toString(16)}" }
            check(info.imeOptions == 0x13000001) { "imeOptions 0x${info.imeOptions.toString(16)}, expected Chrome's 0x12000001 plus 0x01000000" }
            check(keyboard.composeMode == null)
        }
        try {
            main { connection.setComposingText("tie", 1); connection.setComposingText("tiếng", 1) }
            check(drain().isEmpty())
            main { connection.commitText("tiếng😀中文", 1) }
            check(drain() == "tiếng😀中文")
            passed += "PASS: real JNI receives final Vietnamese/CJK/emoji UTF-8 without speculative bytes"

            main {
                bytes.reset()
                for (code in listOf(KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L))
                    check(view.onKeyPreIme(code, KeyEvent(KeyEvent.ACTION_DOWN, code)))
            }
            check(drain() == "ijkl") { "Hardware input buffered or wrong: ${drain().toList()}" }
            main { view.handleRawKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON)) }
            check(drain() == "ijkl\u0003")
            passed += "PASS: physical vim keys and Ctrl+C emit immediately without Enter"

            // The toolbar's keys, as NativeTerminal.sendKey hands them to the emulator (EngineKeyTest models these bytes).
            main {
                bytes.reset()
                emulator.dispatchKey(0, VTermKey.TAB)
                emulator.dispatchKey(0, VTermKey.ESCAPE)
                emulator.dispatchCharacter(4, 'c'.code)
                for (key in listOf(VTermKey.UP, VTermKey.DOWN, VTermKey.RIGHT, VTermKey.LEFT)) emulator.dispatchKey(0, key)
                emulator.dispatchKey(4, VTermKey.LEFT)
            }
            check(drain() == "\t\u001b\u0003\u001b[A\u001b[B\u001b[C\u001b[D\u001b[1;5D") { "Toolbar keys at a prompt: ${drain().toList()}" }
            main {
                bytes.reset()
                emulator.writeInput("\u001b[?1h".toByteArray())
                for (key in listOf(VTermKey.UP, VTermKey.DOWN, VTermKey.RIGHT, VTermKey.LEFT)) emulator.dispatchKey(0, key)
                emulator.dispatchKey(4, VTermKey.UP)
                emulator.writeInput("\u001b[?1l".toByteArray())
            }
            check(drain() == "\u001bOA\u001bOB\u001bOC\u001bOD\u001b[1;5A") { "Toolbar arrows in application cursor mode: ${drain().toList()}" }
            passed += "PASS: toolbar keys through real libvterm: Tab, Esc, ^C, arrows in both cursor modes, Ctrl+arrow"

            // Ctrl+character as xterm.js writes it (ControlKeys; CtrlTypingImeTest models these bytes), through the
            // real handler and libvterm, which alone writes CSI u for [ i j m, capitals, digits and the slash.
            main {
                bytes.reset()
                for (text in listOf("[", "i", "j", "m", "C", "@", "3", "8", "/", "\\", "]", "^", "_", " ", "1")) {
                    modifiers.ctrl = true
                    keyboard.onCommittedText(text)
                }
            }
            check(drain() == "\u001b\t\n\r\u0003\u0000\u001b\u007f\u001f\u001c\u001d\u001e\u001f\u0000") { "Sticky Ctrl on text: ${drain().toList()}" }
            main {
                bytes.reset()
                for ((code, meta) in listOf(
                    KeyEvent.KEYCODE_LEFT_BRACKET to 0, KeyEvent.KEYCODE_I to 0, KeyEvent.KEYCODE_M to 0,
                    KeyEvent.KEYCODE_C to KeyEvent.META_CAPS_LOCK_ON, KeyEvent.KEYCODE_6 to KeyEvent.META_SHIFT_ON,
                    KeyEvent.KEYCODE_A to KeyEvent.META_SHIFT_ON,
                )) view.handleRawKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta or KeyEvent.META_CTRL_ON))
            }
            check(drain() == "\u001b\t\r\u0003\u001e") { "Physical Ctrl keys: ${drain().toList()}" }
            passed += "PASS: Ctrl+character as xterm.js writes it through real libvterm: [ i j m, capitals, digits, / and ^, nothing for Ctrl+Shift+letter"

            // Enter, Tab, Backspace and Escape with every combination of Shift, Alt and Ctrl, as xterm.js's Keyboard.ts
            // writes them (SpecialKeysImeTest models these bytes), through the real handler and libvterm. The control
            // first: libvterm itself writes CSI u for Ctrl and Shift (and an ESC before Tab for Alt), which is why the
            // handler does not hand these keys over as they are.
            main {
                bytes.reset()
                for ((mods, key) in listOf(
                    4 to VTermKey.ENTER, 4 to VTermKey.TAB, 4 to VTermKey.BACKSPACE, 4 to VTermKey.ESCAPE,
                    1 to VTermKey.BACKSPACE, 2 to VTermKey.TAB,
                )) emulator.dispatchKey(mods, key)
            }
            check(drain() == "\u001b[13;5u\u001b[9;5u\u001b[127;5u\u001b[27;5u\u001b[127;2u\u001b\t") { "libvterm's own Ctrl/Shift/Alt keys: ${drain().toList()}" }
            for ((name, code) in listOf(
                "Enter" to KeyEvent.KEYCODE_ENTER, "Tab" to KeyEvent.KEYCODE_TAB,
                "Backspace" to KeyEvent.KEYCODE_DEL, "Escape" to KeyEvent.KEYCODE_ESCAPE,
            )) for (mask in 0..7) {
                val shift = mask and 1 != 0
                val alt = mask and 2 != 0
                val ctrl = mask and 4 != 0
                val expected = when (name) {
                    "Enter" -> if (alt) "\u001b\r" else "\r"
                    "Tab" -> if (shift) "\u001b[Z" else "\t"
                    "Backspace" -> (if (alt) "\u001b" else "") + (if (ctrl) "\b" else "\u007f")
                    else -> if (alt) "\u001b\u001b" else "\u001b"
                }
                main {
                    bytes.reset()
                    view.handleRawKeyEvent(KeyEvent(
                        0, 0, KeyEvent.ACTION_DOWN, code, 0,
                        (if (shift) KeyEvent.META_SHIFT_ON else 0) or
                            (if (alt) KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON else 0) or
                            (if (ctrl) KeyEvent.META_CTRL_ON else 0),
                    ))
                }
                check(drain() == expected) { "$name shift=$shift alt=$alt ctrl=$ctrl: ${drain().toList()}, xterm.js sends ${expected.toList()}" }
            }
            main {
                bytes.reset()
                modifiers.ctrl = true
                keyboard.onCommittedText("\n")
            }
            check(drain() == "\r") { "Enter committed with Ctrl armed: ${drain().toList()}" }
            passed += "PASS: Enter, Tab, Backspace, Escape with Shift/Alt/Ctrl as xterm.js writes them through real libvterm (which writes CSI u for Ctrl and Shift itself); sticky Ctrl + Enter text is CR"

            main {
                bytes.reset()
                connection = create()
                connection.setComposingText("tiếng", 1)
                modifiers.ctrl = true
                view.syncShortcutInputMode(ImeShortcutInputMode.TYPE_NULL)
                check(modifiers.ctrl) { "Flushing preedit consumed the newly armed Ctrl" }
                check(!connection.commitText("stale", 1))
                val shortcutInfo = EditorInfo()
                connection = view.onCreateInputConnection(shortcutInfo)
                check(shortcutInfo.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL)
                connection.commitText("c", 1)
                check(!modifiers.ctrl)
            }
            check(drain() == "tiếng\u0003") { "Sticky Ctrl emitted ${drain().toList()}" }
            main {
                view.syncShortcutInputMode(ImeShortcutInputMode.DISABLED)
                connection = create()
                check(connection is TerminalImeConnectionHandle)
                connection.setComposingText("好", 1)
                connection.finishComposingText()
            }
            check(drain() == "tiếng\u0003好")
            passed += "PASS: arm sticky Ctrl mid-composition, preserve preedit, send ^C, restore Unicode editor"

            main {
                bytes.reset()
                connection.setComposingText("你", 1)
                view.handleRawKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                check(!connection.commitText("你", 1))
                // Hardware must remain usable during the asynchronous editor restart.
                view.handleRawKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_J))
            }
            check(drain() == "你\rj") { "Enter ordering or replay: ${drain().toList()}" }
            passed += "PASS: native output orders preedit then Enter, rejects replay, permits hardware during restart"

            if (Build.VERSION.SDK_INT >= 34) {
                main {
                    bytes.reset()
                    connection = create()
                    // The second create may be discarded by Android; the first must stay live.
                    val discarded = create()
                    connection.commitText("t", 1)
                    connection.commitText("i", 1)
                    connection.commitText("e", 1)
                    discarded.closeConnection()
                    connection.replaceText(2, 3, "ê", 1, null)
                    connection.commitText("n", 1)
                    connection.commitText("g", 1)
                    connection.replaceText(2, 5, "ếng", 1, null)
                    connection.commitText(" ", 1)
                }
                check(drain() == "tie\u007fêng\u007f\u007f\u007fếng ") { "Telex JNI bytes: ${drain().toList()}" }
                main {
                    view.finishBeforeExternalInput()
                    check(!connection.commitText("late", 1))
                    check(!connection.replaceText(0, 1, "late", 1, null))
                    connection = create()
                    connection.commitText("new", 1)
                    view.resetImeBuffer()
                    check(!connection.commitText("old owner", 1))
                }
                check(drain().endsWith("ếng new"))
                passed += "PASS: actual view reuses speculative editors, emits native Telex rewrites, rejects owner-boundary replay"
            }

            main {
                bytes.reset()
                view = ImeInputView(targetContext, keyboard, onRestartInput = {})
                val info = EditorInfo()
                connection = view.onCreateInputConnection(info)
                check(connection !is CommittedImeConnection)
                check(info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL)
                val legacy = ComposeMode().also { it.activate() }
                keyboard.composeMode = legacy
                view.isComposeModeActive = true
                connection = create()
                view.handleRawKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_J))
                check(legacy.buffer == "j")
            }
            check(drain().isEmpty())
            main { view.handleRawKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)) }
            check(drain() == "j\r")
            passed += "PASS: default TYPE_NULL and explicitly activated legacy hardware buffering unchanged"
        } finally {
            main { connection.closeConnection() }
            emulator.close()
        }
    }

    /** What reached the PTY and the clipboard when [text] went into a fresh real emulator, as a replay or as live output. */
    private fun through(text: String, replay: Boolean): Pair<String, List<String>> {
        val bytes = ByteArrayOutputStream()
        val copied = mutableListOf<String>()
        lateinit var emulator: TerminalEmulatorImpl
        main { emulator = TerminalEmulatorFactory.create(onKeyboardInput = bytes::write, onClipboardCopy = { copied += it }) as TerminalEmulatorImpl }
        try {
            main { if (replay) emulator.writeReplay(text.toByteArray()) else emulator.writeInput(text.toByteArray()) }
            emulator.commands.call { }
            waitForIdleSync()
            return bytes.toByteArray().toString(Charsets.UTF_8) to copied.toList()
        } finally {
            emulator.close()
        }
    }

    private fun show(text: String) = text.replace("\u001b", "ESC").replace("\u0007", "BEL")

    /**
     * A replay is shown and never answered. Vim leaves its queries in a session's buffer, and the replay a server sends
     * when the app picks the shell up again carries them to a prompt: answered, they are text that Enter runs. Each
     * query goes through the real libvterm twice, as a replay (nothing may reach the PTY, no clipboard write) and as
     * live output (the answer a tablet's libvterm was seen to give). What libvterm says to colour queries and the
     * focus mode is reported, not asserted: the web answers both on a replay, and its symbols show no code that would.
     */
    private fun replayChecks() {
        // What a fresh emulator's libvterm answers live: the cursor position, DA2, the pixel and cell size (the
        // engine's own words, from its image store) and DECRQM ?12, which reports the cursor blink and is either
        // state however the library starts, so both answers count.
        val seen = listOf(
            "\u001b[6n" to listOf("\u001b[1;1R"),
            "\u001b[>c" to listOf("\u001b[>0;100;0c"),
            "\u001b[14t" to listOf("\u001b[4;384;640t"),
            "\u001b[16t" to listOf("\u001b[6;16;8t"),
            "\u001b[?12\$p" to listOf("\u001b[?12;1\$y", "\u001b[?12;2\$y"),
        )
        val unverified = listOf(
            "\u001b[c", "\u001b[5n", "\u001b[?6n", "\u001b[18t", "\u001b[>q", "\u001b[?u", "\u001b[?1004h", "\u001b[?1004l",
            "\u001b]10;?\u0007", "\u001b]11;?\u0007", "\u001b]10;?\u001b\\", "\u001b]11;?\u001b\\", "\u001b]4;1;?\u0007",
            "\u001bP\$qm\u001b\\", "\u001b[4\$p",
        )
        val queries = seen.map { it.first } + unverified
        // The queries as a vim session leaves them, one replay.
        val session = queries.joinToString("text ", prefix = "prompt$ ")
        val write = "\u001b]52;c;aGVsbG8=\u0007"

        // The replay checks come first and wait for nothing else: they are the point, and a live answer that is not
        // what was expected must not keep them from running.
        for (query in queries) {
            val replayed = through(query, replay = true).first
            check(replayed.isEmpty()) { "A replayed ${show(query)} was answered: ${show(replayed)}" }
        }
        passed += "PASS: each of ${queries.size} queries replayed through real libvterm, one at a time (cursor position, DA1/DA2, DSR, pixel and cell size, DECRQM, DECRQSS, OSC 4/10/11, the focus mode): 0 bytes to the PTY"
        val replayedSession = through(session, replay = true).first
        check(replayedSession.isEmpty()) { "A replayed session was answered: ${show(replayedSession)}" }
        passed += "PASS: the whole session of queries replayed in one go: 0 bytes to the PTY"
        val replayedCopy = through(write, replay = true).second
        check(replayedCopy.isEmpty()) { "A replayed OSC 52 wrote the clipboard: $replayedCopy" }
        passed += "PASS: a replayed OSC 52 clipboard write is not performed"

        // Live, last: what shows the checks above are not empty because nothing is ever answered. What the
        // library says to what it is not seen to answer is only reported.
        val mismatches = mutableListOf<String>()
        val answers = seen.associate { (query, accepted) ->
            val answer = through(query, replay = false).first
            if (answer !in accepted) mismatches += "live ${show(query)} was answered ${show(answer)}, expected one of ${accepted.map { show(it) }}"
            query to answer
        }
        for (query in unverified) notes += "INFO: live ${show(query)} -> ${show(through(query, replay = false).first)}"
        val liveCopy = through(write, replay = false).second
        notes += "INFO: live OSC 52 wrote the clipboard: $liveCopy" + if (liveCopy.isEmpty()) " (the replay check above proves nothing for it)" else ""
        val liveSession = through(session, replay = false).first
        notes += "INFO: the whole session live: ${liveSession.length} bytes"
        var at = 0
        for ((query, answer) in answers) {
            val found = if (answer.isEmpty()) -1 else liveSession.indexOf(answer, at)
            if (found < 0) mismatches += "the live session lost the answer to ${show(query)}: ${show(liveSession)}" else at = found + answer.length
        }
        check(mismatches.isEmpty()) { mismatches.joinToString("\n") }
        passed += "PASS: the same queries live are answered, in order: cursor position, DA2, pixel size, cell size and DECRQM"
    }
}
