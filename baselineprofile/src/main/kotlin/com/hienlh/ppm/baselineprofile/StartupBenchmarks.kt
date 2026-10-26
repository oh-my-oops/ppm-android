package com.hienlh.ppm.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A cold start from the launcher to the first frame, with and without the Baseline Profile:
 * `ANDROID_SERIAL=<serial> ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark`,
 * with the server named on the device ([Target]).
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    private val target = Target.read()
    private var signedIn = false

    @Test
    fun startupWithoutProfile() = startup(CompilationMode.None())

    @Test
    fun startupWithProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = {
            // Signed in, with the chat as the tab the app comes back to; the start measured is
            // the one after, from the launcher.
            if (!signedIn) {
                openChat(target)
                signedIn = true
            }
            pressHome()
        },
    ) {
        startActivityAndWait()
    }
}
