package com.hienlh.ppm.app.ui.files

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import org.robolectric.Shadows.shadowOf

/**
 * Makes a write to state from the test thread reach what watches it, a `snapshotFlow` in an effect. The write only
 * reaches it once the apply notifications go out, and left to Compose they go out from a coroutine on the main looper
 * that the test's clock, held by hand, does not run for it: a suite that ran another class's tests first, with the same
 * Robolectric sandbox, left it never running here, and every test that waited on it failed in the full run and passed
 * alone. So they are sent here, then the looper runs what they scheduled.
 */
internal fun flushStateWrites() {
    Snapshot.sendApplyNotifications()
    shadowOf(Looper.getMainLooper()).idle()
}
