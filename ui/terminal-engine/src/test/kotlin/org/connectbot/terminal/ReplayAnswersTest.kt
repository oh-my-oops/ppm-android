package org.connectbot.terminal

import android.os.Looper
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.spy
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The replay a server sends when it picks a session up again is shown, never answered. libvterm answers a query
 * synchronously inside the write that carried it, and everything it says reaches the program through
 * `onKeyboardInput`, so [TerminalEmulatorImpl.writeReplay] drops whatever is said while it parses. Vim leaves its
 * queries in the buffer (the cursor position, DA2, the pixel size, a mode); replayed to a shell prompt, the answers
 * are text that Enter runs.
 *
 * The real emulator runs here and only the parse is modelled, because libvterm's JNI cannot load on the JVM:
 * [libvterm] calls back as the shipped library does, from inside the write, with the answers a tablet's libvterm
 * gave for the queries it was seen to answer. `CommittedImeInstrumentation` replays the same bytes through the real
 * JNI on a device, so the model cannot drift from the library unnoticed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplayAnswersTest {
    private val pty = ByteArrayOutputStream()
    private val copied = mutableListOf<String>()
    private val emulator: TerminalEmulatorImpl = spy(
        TerminalEmulatorFactory.create(onKeyboardInput = pty::write, onClipboardCopy = { copied += it }) as TerminalEmulatorImpl,
    )

    /** One thing libvterm can call back with when it reads [query]. */
    private class Ask(val query: String, val answer: String, val call: (TerminalEmulatorImpl) -> Unit)

    private val asks = listOf(
        // Seen on the tablet after vim, in this form.
        ask("\u001b[6n", "\u001b[2;2R") { it.onKeyboardInput("\u001b[2;2R".toByteArray()) },
        ask("\u001b[>c", "\u001b[>0;100;0c") { it.onKeyboardInput("\u001b[>0;100;0c".toByteArray()) },
        ask("\u001b[?12\$p", "\u001b[?12;2\$y") { it.onKeyboardInput("\u001b[?12;2\$y".toByteArray()) },
        // The pixel and cell size are the engine's own words, built from its image store.
        ask("\u001b[14t", "\u001b[4;${store.rows * store.cellHeight};${store.cols * store.cellWidth}t") { it.onImageQuery(14) },
        ask("\u001b[16t", "\u001b[6;${store.cellHeight};${store.cellWidth}t") { it.onImageQuery(16) },
        ask("\u001b[18t", "\u001b[8;${store.rows};${store.cols}t") { it.onImageQuery(18) },
        // The web (xterm.js) answers these two on a replay too. The shipped libvterm answers neither today; they
        // stand in for any answer a parser might give, since the drop is at the callback and not per query.
        ask("\u001b]10;?\u0007", "\u001b]10;rgb:ffff/ffff/ffff\u001b\\") { it.onKeyboardInput("\u001b]10;rgb:ffff/ffff/ffff\u001b\\".toByteArray()) },
        ask("\u001b]11;?\u0007", "\u001b]11;rgb:0000/0000/0000\u001b\\") { it.onKeyboardInput("\u001b]11;rgb:0000/0000/0000\u001b\\".toByteArray()) },
        ask("\u001b[?1004h", "\u001b[O") { it.onKeyboardInput("\u001b[O".toByteArray()) },
    )

    private fun ask(query: String, answer: String, call: (TerminalEmulatorImpl) -> Unit) = Ask(query, answer, call)

    private val store get() = emulator.imageStore

    /** What a vim session leaves in the buffer: every query above, between runs of ordinary text. */
    private val session = asks.joinToString("") { "text " + it.query }

    /** Every answer, in the order it was asked. */
    private val answers = asks.joinToString("") { it.answer }

    private var parses = 0

    /** [TerminalEmulatorImpl.writeInput] with libvterm's behaviour: it calls back as it reads each query. */
    private fun libvterm(beforeParse: () -> Unit = {}) {
        doAnswer { call ->
            parses++
            beforeParse()
            val from = call.getArgument<Int>(1)
            val text = String(call.getArgument<ByteArray>(0), from, call.getArgument<Int>(2), Charsets.UTF_8)
            var at = 0
            while (at < text.length) {
                val ask = asks.firstOrNull { text.startsWith(it.query, at) }
                if (ask == null) at++ else { ask.call(emulator); at += ask.query.length }
            }
            null
        }.`when`(emulator).writeInput(anyBytes(), anyInt(), anyInt())
    }

    /** Mockito's matchers answer null, which Kotlin refuses for a non-null parameter before Mockito sees the call. */
    private fun anyBytes(): ByteArray = any(ByteArray::class.java) ?: ByteArray(0)

    private fun live(text: String) = emulator.writeInput(text.toByteArray(), 0, text.toByteArray().size)

    private fun delivered(): String {
        shadowOf(Looper.getMainLooper()).idle()
        return pty.toString(Charsets.UTF_8)
    }

    @Test fun `a replay is parsed and none of what it asks is answered, colour queries and focus reports included`() {
        libvterm()
        emulator.writeReplay(session.toByteArray())
        assertEquals("the replay reached the parser", 1, parses)
        assertEquals("", delivered())
    }

    @Test fun `the same bytes arriving live are all answered, in the order they were asked`() {
        libvterm()
        live(session)
        assertEquals(1, parses)
        assertEquals(answers, delivered())
    }

    @Test fun `live output is answered again after a replay, and an answer made before it is still delivered`() {
        libvterm()
        live("\u001b[6n")
        // Posted to the main thread but not yet delivered when the replay is read: its answer is not a replay's.
        emulator.writeReplay("\u001b[>c".toByteArray())
        live("\u001b[?12\$p")
        assertEquals("\u001b[2;2R" + "\u001b[?12;2\$y", delivered())
    }

    @Test fun `a replay that fails does not leave the emulator deaf to the next live query`() {
        libvterm { if (parses == 1) error("the parse failed") }
        assertThrows(IllegalStateException::class.java) { emulator.writeReplay("\u001b[6n".toByteArray()) }
        live("\u001b[6n")
        assertEquals("\u001b[2;2R", delivered())
    }

    @Test fun `an OSC 52 clipboard write is performed live and not when it is replayed`() {
        val write = "\u001b]52;c;aGVsbG8=\u0007"
        doAnswer { emulator.onTextFragment(TerminalTextDecoder.CLIPBOARD, 52, "aGVsbG8=".toByteArray(), true, true, 0, 0); null }
            .`when`(emulator).writeInput(anyBytes(), anyInt(), anyInt())
        emulator.writeReplay(write.toByteArray())
        delivered()
        assertEquals("history does not overwrite what was copied since", emptyList<String>(), copied)
        live(write)
        delivered()
        assertEquals(listOf("hello"), copied)
    }
}
