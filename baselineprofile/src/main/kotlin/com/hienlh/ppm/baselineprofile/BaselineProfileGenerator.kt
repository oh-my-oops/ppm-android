package com.hienlh.ppm.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes the app's Baseline Profile: `ANDROID_SERIAL=<serial> ./gradlew :app:generateBaselineProfile`
 * with the server named on the device ([Target]). Each journey runs until the methods it reaches
 * stop changing, and the plugin keeps all of them in app/src/release/generated/baselineProfiles.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    private val target = Target.read()

    /** A cold start to a chat: the part of the profile that also orders the dex. */
    @Test
    fun startup() = rule.collect(PACKAGE, includeInStartupProfile = true) {
        openChat(target)
    }

    /** The chat scrolled to its first message and back. */
    @Test
    fun scrollChat() = rule.collect(PACKAGE) {
        openChat(target)
        scrollChat()
    }
}
