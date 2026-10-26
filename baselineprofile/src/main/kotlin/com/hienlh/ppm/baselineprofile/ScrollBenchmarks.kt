package com.hienlh.ppm.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The frames of opening a chat cold and scrolling it, with and without the Baseline Profile: their
 * CPU time is where interpreted code shows, on an emulator too, where software rendering hides it
 * from the start's first frame. Run like [StartupBenchmarks].
 */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    private val target = Target.read()

    @Test
    fun scrollWithoutProfile() = scroll(CompilationMode.None())

    @Test
    fun scrollWithProfile() = scroll(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun scroll(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 5,
    ) {
        openChat(target)
        scrollChat()
    }
}
