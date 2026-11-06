package org.connectbot.terminal

import android.os.Looper
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the emulator answers when a program asks for its foreground, background or cursor colour (`OSC 10 / 11 / 12 ; ?`) or whether the
 * alternate screen is on (`CSI ? 47 / 1047 / 1049 $ p`). On the web, xterm.js answers the theme's colours in 16-bit `rgb:rrrr/gggg/bbbb`
 * form ended by `ESC \` and answers all three alternate-screen mode requests from the buffer in use (`1` on the alternate screen, `2` on
 * the main one); libvterm says nothing to the colour queries and answers `0` ("not recognised") to `47` and `1049`, so programs such as
 * vim, bat and delta cannot tell a light theme from a dark one or whether the alternate screen is active.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalQueriesTest {
    private val esc = "\u001b"
    private val bel = "\u0007"
    private val st = "$esc\\"

    // Aurora Light: text #0f1a33, bg-solid #f3f7ff, accent #2f6bff.
    private val auroraLightFg = Color(0xFF0F1A33)
    private val auroraLightBg = Color(0xFFF3F7FF)
    private val auroraLightCursor = Color(0xFF2F6BFF)

    // Aurora Dark: text #eaeefb, bg-solid #0a0e17, accent #5b8cff.
    private val auroraDarkFg = Color(0xFFEAEEFB)
    private val auroraDarkBg = Color(0xFF0A0E17)
    private val auroraDarkCursor = Color(0xFF5B8CFF)

    private val pty = ByteArrayOutputStream()
    private val vterm = FakeVterm(24, 80)
    private val emulator = TerminalEmulatorImpl(
        looper = Looper.getMainLooper(),
        initialRows = 24,
        initialCols = 80,
        defaultForeground = auroraLightFg,
        defaultBackground = auroraLightBg,
        defaultCursorColor = auroraLightCursor,
        onKeyboardInput = pty::write,
        nativeFactory = vterm::attach,
    ).also {
        it.applyColorScheme(IntArray(16), auroraLightFg.toArgb(), auroraLightBg.toArgb(), auroraLightCursor.toArgb())
    }

    private fun write(text: String) {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        emulator.writeInput(bytes, 0, bytes.size)
    }

    private fun writeDirect(text: String) {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        val buffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
        emulator.writeInput(buffer, bytes.size)
    }

    private fun takeAnswers(): String {
        shadowOf(Looper.getMainLooper()).idle()
        val out = pty.toString(Charsets.ISO_8859_1)
        pty.reset()
        return out
    }

    @Test fun `each special colour query is answered in xterm's 16-bit rgb format whether ended by BEL or ESC backslash`() {
        for (terminator in listOf(bel, st)) {
            write("$esc]10;?$terminator")
            assertEquals("$esc]10;rgb:0f0f/1a1a/3333$st", takeAnswers())

            write("$esc]11;?$terminator")
            assertEquals("$esc]11;rgb:f3f3/f7f7/ffff$st", takeAnswers())

            write("$esc]12;?$terminator")
            assertEquals("$esc]12;rgb:2f2f/6b6b/ffff$st", takeAnswers())
        }
    }

    @Test fun `several colour queries stacked in one OSC are answered in slot order up to cursor colour`() {
        write("$esc]10;?;?$bel")
        assertEquals(
            "$esc]10;rgb:0f0f/1a1a/3333$st$esc]11;rgb:f3f3/f7f7/ffff$st",
            takeAnswers(),
        )

        write("$esc]10;?;?;?;?$st")
        assertEquals(
            "$esc]10;rgb:0f0f/1a1a/3333$st$esc]11;rgb:f3f3/f7f7/ffff$st$esc]12;rgb:2f2f/6b6b/ffff$st",
            takeAnswers(),
        )

        write("$esc]11;?;?;?$bel")
        assertEquals(
            "$esc]11;rgb:f3f3/f7f7/ffff$st$esc]12;rgb:2f2f/6b6b/ffff$st",
            takeAnswers(),
        )

        // A non-query slot still advances the special-colour offset, as xterm.js's `_setOrReportSpecialColor` does.
        write("$esc]10;#112233;?$bel")
        assertEquals("$esc]11;rgb:f3f3/f7f7/ffff$st", takeAnswers())
    }

    @Test fun `setting a colour or asking for an indexed palette entry is not answered`() {
        write("$esc]10;#112233$bel$esc]11;rgb:00/00/00$st$esc]12;#abcdef$bel$esc]4;0;?$bel")
        assertEquals("", takeAnswers())
    }

    @Test fun `a query split across writes is answered once when its terminator arrives`() {
        val stream = "$esc]10;?$bel$esc]11;?$st$esc[?1049\$p"
        val expected = "$esc]10;rgb:0f0f/1a1a/3333$st$esc]11;rgb:f3f3/f7f7/ffff$st$esc[?1049;2\$y"

        for (split in 1 until stream.length) {
            write(stream.substring(0, split))
            val firstHalf = takeAnswers()
            write(stream.substring(split))
            val secondHalf = takeAnswers()
            assertEquals("split at $split", expected, firstHalf + secondHalf)
        }

        // And one byte at a time.
        for (ch in stream) write(ch.toString())
        assertEquals(expected, takeAnswers())
    }

    @Test fun `nothing is answered during writeReplay`() {
        val replay = "prompt $esc[6n$esc]10;?$bel$esc]11;?$st$esc]12;?$bel$esc[?1049\$p$esc[?1047\$p$esc[?47\$p"
        emulator.writeReplay(replay.toByteArray(Charsets.ISO_8859_1))
        assertEquals("", takeAnswers())

        // Live queries right after the replay are answered as usual.
        write("$esc]11;?$bel$esc[?1049\$p")
        assertEquals("$esc]11;rgb:f3f3/f7f7/ffff$st$esc[?1049;2\$y", takeAnswers())
    }

    @Test fun `answers arrive in the order the queries were written, interleaved with libvterm's own answers`() {
        val combined = "$esc[3;5H$esc[6n$esc]11;?$bel$esc[?1049\$p$esc[>c$esc]10;?$st$esc[?12\$p"
        val expected = "$esc[3;5R" +
            "$esc]11;rgb:f3f3/f7f7/ffff$st" +
            "$esc[?1049;2\$y" +
            "$esc[>0;100;0c" +
            "$esc]10;rgb:0f0f/1a1a/3333$st" +
            "$esc[?12;1\$y"

        write(combined)
        assertEquals(expected, takeAnswers())

        writeDirect(combined)
        assertEquals(expected, takeAnswers())
    }

    @Test fun `DECRQM 1049, 1047 and 47 answer 2 on the main screen and 1 on the alternate screen`() {
        write("$esc[?1049\$p$esc[?1047\$p$esc[?47\$p")
        assertEquals("$esc[?1049;2\$y$esc[?1047;2\$y$esc[?47;2\$y", takeAnswers())

        write("$esc[?1049h$esc[?1049\$p$esc[?1047\$p$esc[?47\$p")
        assertEquals("$esc[?1049;1\$y$esc[?1047;1\$y$esc[?47;1\$y", takeAnswers())

        write("$esc[?1049l$esc[?1049\$p$esc[?1047\$p$esc[?47\$p")
        assertEquals("$esc[?1049;2\$y$esc[?1047;2\$y$esc[?47;2\$y", takeAnswers())

        // Entering and leaving with ?1047h / ?1047l switches the same alternate buffer.
        write("$esc[?1047h$esc[?1049\$p$esc[?1047\$p$esc[?47\$p$esc[?1047l$esc[?1049\$p")
        assertEquals("$esc[?1049;1\$y$esc[?1047;1\$y$esc[?47;1\$y$esc[?1049;2\$y", takeAnswers())
    }

    @Test fun `changing a running terminal's theme updates the colours answered afterwards`() {
        write("$esc]10;?$bel$esc]11;?$bel$esc]12;?$bel")
        assertEquals(
            "$esc]10;rgb:0f0f/1a1a/3333$st$esc]11;rgb:f3f3/f7f7/ffff$st$esc]12;rgb:2f2f/6b6b/ffff$st",
            takeAnswers(),
        )

        emulator.applyColorScheme(IntArray(16), auroraDarkFg.toArgb(), auroraDarkBg.toArgb(), auroraDarkCursor.toArgb())
        write("$esc]10;?$bel$esc]11;?$bel$esc]12;?$bel")
        assertEquals(
            "$esc]10;rgb:eaea/eeee/fbfb$st$esc]11;rgb:0a0a/0e0e/1717$st$esc]12;rgb:5b5b/8c8c/ffff$st",
            takeAnswers(),
        )
    }
}
