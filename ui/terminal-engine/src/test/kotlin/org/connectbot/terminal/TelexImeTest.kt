package org.connectbot.terminal

import android.view.KeyEvent
import android.view.inputmethod.TextAttribute
import java.text.Normalizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gboard Telex and CJK input, from the `InputConnection` calls an IME makes to the bytes the server's
 * PTY reads. Everything between is the app's own code (`ImeInputView`, `CommittedImeConnection`,
 * `KeyboardHandler`); only libvterm's output is modelled ([FakePty]).
 *
 * "In step" is the property that keeps a diacritic from being repeated or lost: after every call the
 * IME makes, what the IME believes is before its caret is the end of what the user sees (the shell's
 * line plus the unsent composition). It reads that (`getTextBeforeCursor`) to decide its next Telex
 * revision, so a revision can only ever land on text the terminal really has.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelexImeTest {
    private val rig = ImeRig()
    private val attribute = TextAttribute.Builder().build()

    private fun assertInStep(after: String) {
        val screen = rig.pty.line + rig.preedit
        assertTrue("the IME's context '${rig.imeContext}' is not the end of '$screen' after $after", screen.endsWith(rig.imeContext))
    }

    /** The exact bytes, not only the text they spell. */
    private fun assertSent(expected: String) {
        check(Normalizer.isNormalized(expected, Normalizer.Form.NFC)) { "write the expectation precomposed" }
        assertEquals(expected, rig.pty.stream)
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), rig.pty.bytes)
    }

    // Gboard on API 34 and later: each key is committed at once and a Telex rule revises what was
    // committed with replaceText. Captured from real Gboard on API 37 (the instrumentation test holds
    // the same trace, in lower case); a capital only changes which character is committed first.
    private fun commit(text: String) {
        assertTrue(rig.ime.commitText(text, 1, attribute))
        assertInStep("commit $text")
    }

    private fun replace(start: Int, end: Int, text: String) {
        assertTrue(rig.ime.replaceText(start, end, text, 1, attribute))
        assertInStep("replace $start-$end with $text")
    }

    // Before API 34 there is no replaceText: the same revision is a batch that deletes the tail and
    // commits it again.
    private fun revise(erase: Int, text: String) {
        rig.ime.beginBatchEdit()
        assertTrue(rig.ime.deleteSurroundingText(erase, 0))
        assertTrue(rig.ime.commitText(text, 1))
        rig.ime.endBatchEdit()
        assertInStep("revise $erase to $text")
    }

    private fun compose(text: String) {
        assertTrue(rig.ime.setComposingText(text, 1))
        assertInStep("compose $text")
    }

    @Test fun `Gboard Telex on API 34 types Tiếng Việt`() {
        commit("T"); commit("i"); commit("e"); replace(2, 3, "ê")
        commit("n"); commit("g"); replace(2, 5, "ếng"); commit(" ")
        commit("V"); commit("i"); commit("e"); replace(8, 9, "ê")
        commit("t"); replace(8, 10, "ệt")
        // A key is sent as it is committed, and a revision erases the changed tail and sends it
        // again: Tie, ⌫ ê, ng, ⌫⌫⌫ ếng, space, Vie, ⌫ ê, t, ⌫⌫ ệt.
        assertSent("Tie${DEL}êng${DEL}${DEL}${DEL}ếng Vie${DEL}êt${DEL}${DEL}ệt")
        assertEquals("Tiếng Việt", rig.pty.line)
        assertEquals("Tiếng Việt", rig.imeContext)
    }

    @Test fun `Telex revised by deleting and committing again types the same bytes`() {
        commit("T"); commit("i"); commit("e"); revise(1, "ê")
        commit("n"); commit("g"); revise(3, "ếng"); commit(" ")
        commit("V"); commit("i"); commit("e"); revise(1, "ê")
        commit("t"); revise(2, "ệt")
        assertSent("Tie${DEL}êng${DEL}${DEL}${DEL}ếng Vie${DEL}êt${DEL}${DEL}ệt")
        assertEquals("Tiếng Việt", rig.pty.line)
    }

    @Test fun `Telex revised by recomposing the last letters sends the revision once it is finished`() {
        commit("T"); commit("i"); commit("e")
        assertTrue(rig.ime.setComposingRegion(2, 3))
        assertTrue(rig.ime.setComposingText("ê", 1))
        // Shown as unsent text, not yet in the shell, which still has the e.
        assertEquals("ê", rig.preedit)
        assertEquals("Tie", rig.pty.line)
        assertTrue(rig.ime.finishComposingText())
        assertInStep("finish")
        assertSent("Tie${DEL}ê")
        assertEquals("Tiê", rig.pty.line)
    }

    @Test fun `a keyboard that composes the word sends it once, when it is committed`() {
        for (prefix in listOf("T", "Ti", "Tie", "Tiê", "Tiên", "Tiêng", "Tiếng")) {
            compose(prefix)
            assertEquals("composing is shown, not sent", prefix, rig.preedit)
            assertEquals("", rig.pty.stream)
        }
        commit("Tiếng")
        commit(" ")
        for (prefix in listOf("V", "Vi", "Vie", "Viê", "Viêt", "Việt")) {
            compose(prefix)
            assertEquals(prefix, rig.preedit)
            assertEquals("Tiếng ", rig.pty.stream)
        }
        commit("Việt")
        // No revision on the wire: the keyboard did its Telex in its own composition.
        assertSent("Tiếng Việt")
    }

    @Test fun `finishing a composition twice sends the word once`() {
        compose("Tiếng")
        assertTrue(rig.ime.finishComposingText())
        assertTrue(rig.ime.finishComposingText())
        assertSent("Tiếng")
        commit(" ")
        assertSent("Tiếng ")
    }

    @Test fun `Chinese Pinyin and Japanese conversion send only what was picked`() {
        for (step in listOf("n", "ni", "ni h", "ni hao")) {
            compose(step)
            assertEquals("", rig.pty.stream)
        }
        compose("你好")
        commit("你好")
        assertSent("你好")

        for (step in listOf("に", "にほ", "にほん", "にほんご", "日本語")) compose(step)
        assertEquals("日本語", rig.preedit)
        assertSent("你好")
        assertTrue(rig.ime.finishComposingText())
        assertSent("你好日本語")

        commit("😀")
        assertSent("你好日本語😀")
        assertEquals("你好日本語😀", rig.pty.line)
    }

    @Test fun `cancelling a composition sends nothing`() {
        compose("tiê")
        compose("")
        assertTrue(rig.ime.finishComposingText())
        assertEquals(0, rig.pty.bytes.size)
        assertEquals("", rig.preedit)
    }

    @Test fun `no Enter is sent in the middle of a word`() {
        commit("T"); commit("i"); commit("e"); replace(2, 3, "ê")
        compose("ng")
        assertTrue(rig.ime.finishComposingText())
        commit(" ")
        assertFalse('\r' in rig.pty.stream)
        assertTrue("no key reached the terminal: ${rig.keys}", rig.keys.isEmpty())
    }

    @Test fun `Enter after a word is sent once and a replay of the word is refused`() {
        commit("T"); commit("i"); commit("e"); replace(2, 3, "ê")
        val connection = rig.ime
        assertTrue(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        assertSent("Tie${DEL}ê\r")
        assertEquals(listOf(KeyEvent.KEYCODE_ENTER), rig.keys.map { it.keyCode })
        // The shell is on a new line now, and the IME is told to start over from an empty one. It may
        // still replay the word on the connection it had.
        assertTrue(rig.restarts > 0)
        assertFalse(connection.commitText("Tiê", 1))
        assertSent("Tie${DEL}ê\r")
        assertEquals("", rig.imeContext)
        // Typing on goes after the Enter, with nothing left to revise behind it.
        commit("l"); commit("s")
        assertSent("Tie${DEL}ê\rls")
    }

    @Test fun `Backspace edits the text sent so far and an empty editor leaves it to the terminal`() {
        commit("T"); commit("i"); commit("e"); replace(2, 3, "ê"); commit("n"); commit("g")
        assertTrue(rig.ime.deleteSurroundingText(1, 0))
        assertInStep("backspace")
        assertEquals("Tiên", rig.pty.line)
        assertEquals("Tiên", rig.imeContext)

        rig.view.finishBeforeExternalInput()
        assertEquals("", rig.imeContext)
        assertTrue(rig.ime.deleteSurroundingText(1, 0))
        // Nothing of the old line is the IME's to edit, so this is the terminal's own Backspace key.
        assertEquals(listOf(KeyEvent.KEYCODE_DEL), rig.keys.map { it.keyCode })
        assertSent("Tie${DEL}êng${DEL}${DEL}")
        assertEquals("Tiê", rig.pty.line)
    }

    @Test fun `text typed after an external key is not revised into the line it changed`() {
        commit("t"); commit("i"); commit("e")
        // What the toolbar does before it sends Tab: the shell's line is no longer the IME's.
        rig.view.finishBeforeExternalInput()
        assertEquals("", rig.imeContext)
        commit("e")
        assertSent("tiee")
    }

    @Test fun `the editor is the one Chrome describes for xterm, which Gboard does not autocorrect, and keeps what is typed unlearned`() {
        // The numbers `dumpsys input_method` shows for the web terminal's textarea, plus the app's
        // no-personalised-learning flag (0x01000000) on the options. Chrome adds 0x200000 to the type
        // from Android 17, which this runs below; TerminalEditorInfoTest pins that half.
        assertEquals(0x200a1, rig.info.inputType)
        assertEquals(0x13000001, rig.info.imeOptions)
    }

    @Test fun `physical keys go straight through, in order with a word the soft keyboard was composing`() {
        for ((key, typed) in listOf(KeyEvent.KEYCODE_V to "v", KeyEvent.KEYCODE_I to "vi", KeyEvent.KEYCODE_M to "vim")) {
            assertTrue(rig.hardwareKey(key))
            assertEquals("sent at once, not held for a commit", typed, rig.pty.stream)
        }
        assertTrue(rig.hardwareKey(KeyEvent.KEYCODE_ENTER))
        assertEquals("vim\r", rig.pty.stream)
        rig.pty.clear()

        compose("tiê")
        assertEquals("", rig.pty.stream)
        assertTrue(rig.hardwareKey(KeyEvent.KEYCODE_J))
        assertEquals("the composition is committed first", "tiêj", rig.pty.stream)
    }

    @Test fun `Ctrl C on a physical keyboard is the interrupt byte`() {
        rig.view.handleRawKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON))
        assertEquals("\u0003", rig.pty.stream)
    }
}
