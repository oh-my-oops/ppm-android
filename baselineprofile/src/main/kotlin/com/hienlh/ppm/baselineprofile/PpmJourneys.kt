package com.hienlh.ppm.baselineprofile

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/** The app, as installed: every build type shares the one id. */
const val PACKAGE = "com.hienlh.ppm"

/**
 * The PPM server a run signs in to and the chat it opens, read from [FILE] on the device:
 * `server=` (as typed on the connect screen, `10.0.2.2:8080` for a server on the emulator's
 * host), `password=`, `project=` and `session=`, a mock session with a long transcript of tool
 * cards, markdown and code (the link names the mock provider, so no AI ever runs). A file, not
 * instrumentation arguments: Gradle prints those and the test results keep them, and the
 * password belongs in neither. Push it before a run and delete it after:
 *
 * ```
 * adb -s <serial> push <file> /data/local/tmp/ppm-baseline-profile.properties
 * ANDROID_SERIAL=<serial> ./gradlew :app:generateBaselineProfile
 * adb -s <serial> shell rm /data/local/tmp/ppm-baseline-profile.properties
 * ```
 */
class Target(val server: String, val password: String, val project: String, val session: String) {
    /** The chat's `ppm://` link, as a notification or another app opens it. */
    val chatLink: Uri get() = Uri.parse("ppm://$server/project/$project/chat/mock/$session")

    companion object {
        const val FILE = "/data/local/tmp/ppm-baseline-profile.properties"

        fun read(): Target {
            // The shell reads it: the test app may not.
            val text = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand("cat $FILE")
            val values = text.lines().mapNotNull { line ->
                line.split("=", limit = 2).takeIf { it.size == 2 }?.let { (key, value) -> key.trim() to value.trim() }
            }.toMap()
            fun value(key: String) = values[key] ?: error("$FILE has no $key= line")
            return Target(value("server"), value("password"), value("project"), value("session"))
        }
    }
}

private val CONNECT = By.text("Unlock your workspace")
private val TRANSCRIPT = By.res("chat-transcript")
private const val TIMEOUT = 30_000L

/**
 * Starts the app cold on [target]'s chat link and waits for the transcript. A fresh install has
 * no server saved, so it refuses the link and opens on the connect screen: this signs in there,
 * then opens the link again.
 */
fun MacrobenchmarkScope.openChat(target: Target) {
    killProcess()
    startActivityAndWait(Intent(Intent.ACTION_VIEW, target.chatLink).setPackage(PACKAGE))
    when (device.waitForEither(CONNECT, TRANSCRIPT)) {
        TRANSCRIPT -> Unit
        CONNECT -> {
            signIn(target)
            device.executeShellCommand("am start -a android.intent.action.VIEW -d ${target.chatLink} $PACKAGE")
            check(device.wait(Until.hasObject(TRANSCRIPT), TIMEOUT)) { "The chat did not open after signing in" }
        }
        else -> error("Neither the chat nor the connect screen showed in ${TIMEOUT / 1000} s")
    }
}

/**
 * Flings the open chat up three times and back down three times, along the middle of the
 * transcript, away from the screen's sides, where a swipe is the system's Back. Swipes, not
 * `UiObject2.fling`: that waits 5 s after each for a scroll event Compose does not send the way it
 * expects.
 */
fun MacrobenchmarkScope.scrollChat() {
    val box = device.findObject(TRANSCRIPT).visibleBounds
    val high = box.top + box.height() / 5
    val low = box.bottom - box.height() / 5
    repeat(3) { fling(box.centerX(), high, low) }
    repeat(3) { fling(box.centerX(), low, high) }
}

/** A finger from [from] to [to] in five 5 ms steps, fast enough to fling, then the fling's time. */
private fun MacrobenchmarkScope.fling(x: Int, from: Int, to: Int) {
    device.swipe(x, from, x, to, 5)
    device.waitForIdle(2_000)
}

/** The server's address and password typed on the connect screen, then Unlock. */
private fun MacrobenchmarkScope.signIn(target: Target) {
    val fields = device.findObjects(By.clazz("android.widget.EditText"))
    check(fields.size == 2) { "The connect screen has ${fields.size} fields, not the address and the password" }
    fields[0].text = target.server
    fields[1].text = target.password
    device.findObject(By.text("Unlock")).click()
    check(device.wait(Until.gone(CONNECT), TIMEOUT)) { "Signing in to ${target.server} failed" }
}

/** Whichever of [first] and [second] shows first, or null when neither does in [TIMEOUT]. */
private fun UiDevice.waitForEither(first: BySelector, second: BySelector): BySelector? {
    val end = SystemClock.uptimeMillis() + TIMEOUT
    while (SystemClock.uptimeMillis() < end) {
        if (hasObject(first)) return first
        if (hasObject(second)) return second
        SystemClock.sleep(200)
    }
    return null
}
