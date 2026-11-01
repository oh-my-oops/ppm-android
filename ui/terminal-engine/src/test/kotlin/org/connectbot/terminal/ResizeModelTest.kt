package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FakeVterm] is only worth anything while it does what libvterm does, and the library cannot load on the JVM. This runs the scripted
 * scenarios of [ResizeDiff] (writes of every kind and resizes, with the alternate screen and the soft wraps in them) through the model
 * and holds the CRC of each trace to [ResizeDiff.GOLDEN], the CRCs the library itself gave when `EngineInstrumentation` traced the same
 * scenarios on a device (`ResizeChecks.verifyResizeModel`). A model that drifts from the library fails here; a library that stops doing
 * what the model does fails there.
 */
class ResizeModelTest {
    private class ModelHost(rec: ResizeDiff.Rec, rows: Int, cols: Int) : ResizeDiff.Host {
        private val vterm = FakeVterm(rows, cols).attach(rec)
        override val native: ScreenNative = vterm
        override val cursor: Pair<Int, Int> get() = vterm.cursor
        override fun close() = Unit
    }

    @Test fun `the model's trace of every scripted scenario is the library's`() {
        val coverage = ResizeDiff.Coverage()
        val actual = ResizeDiff.SEEDS.map { seed -> ResizeDiff.checksum(ResizeDiff.trace(seed, coverage) { rec, rows, cols -> ModelHost(rec, rows, cols) }) }
        assertEquals("The model's traces of ${ResizeDiff.SEEDS}", ResizeDiff.GOLDEN, actual)
        println("scenarios: $coverage")
    }

    @Test fun `the scenarios reach the branches of a resize that the model has to get right`() {
        val coverage = ResizeDiff.Coverage()
        ResizeDiff.SEEDS.forEach { seed -> ResizeDiff.trace(seed, coverage) { rec, rows, cols -> ModelHost(rec, rows, cols) } }
        assertTrue("$coverage", coverage.withPushes >= 25 && coverage.withPops >= 25 && coverage.withWrappedPushes >= 10 && coverage.alternateWithPushes >= 8 && coverage.inColumns >= 25)
    }
}
