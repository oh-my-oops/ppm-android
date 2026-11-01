package org.connectbot.terminal

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the owner of an emulator hears of the first time its view is laid out. `TerminalController` holds what the shell prints until
 * it has been told a size, because the server replays a shell's buffer at once and the emulator wraps it at the width it has then.
 * That wait never ends unless the emulator always reports its first measurement, including a view that comes out at exactly the
 * size the emulator was made with (24 rows of 80 columns, the default `NativeTerminal` starts with): what separates the first
 * report from none is that the emulator had no pixel size before it was measured, and a report is made for a new pixel size too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirstResizeReportTest {
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `a view laid out at the size the emulator was made with is still reported, once`() {
        val reported = mutableListOf<TerminalDimensions>()
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80, onResize = { reported += it })
        try {
            emulator.resize(24, 80)
            idle()
            assertEquals(1, reported.size)
            assertEquals(24, reported.single().rows)
            assertEquals(80, reported.single().columns)
            // The same measurement again says nothing more.
            emulator.resize(24, 80)
            idle()
            assertEquals(1, reported.size)
        } finally {
            emulator.close()
        }
    }
}
