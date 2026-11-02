package com.hienlh.ppm.app.ui

import android.content.ClipData
import android.content.ClipDescription
import android.view.DragEvent
import android.view.View
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.robolectric.shadows.DragEventBuilder

/**
 * The drags Android delivers to a composition: started, entered, a location at each move, a drop, ended. [clip] and [local] are what every
 * event of the drag carries (the platform's own `DragEvent` carries them on all of them, though the data only on the drop), and
 * positions are in dp, which is pixels here.
 */
internal class DragDriver(
    private val compose: ComposeTestRule,
    var clip: ClipData = ClipData(ClipDescription("test", arrayOf("application/x-test")), ClipData.Item("test")),
    var local: Any? = "local",
    private val view: () -> View,
) {
    /** Dispatches one event, and whether the view took it. */
    fun send(action: Int, x: Int = 0, y: Int = 0): Boolean {
        var taken = false
        compose.runOnUiThread {
            taken = view().dispatchDragEvent(
                DragEventBuilder.newBuilder().setAction(action).setX(x.toFloat()).setY(y.toFloat()).setLocalState(local)
                    .setClipDescription(clip.description).setClipData(clip).build(),
            )
        }
        compose.waitForIdle()
        return taken
    }

    /** Started and entered at the window's corner, which is where a drag from another window comes in. Whether anything took the drag. */
    fun begin(): Boolean {
        val taken = send(DragEvent.ACTION_DRAG_STARTED)
        send(DragEvent.ACTION_DRAG_ENTERED)
        return taken
    }

    fun move(x: Int, y: Int) {
        send(DragEvent.ACTION_DRAG_LOCATION, x, y)
    }

    /** Whether the drop was taken. */
    fun drop(x: Int, y: Int): Boolean = send(DragEvent.ACTION_DROP, x, y)

    fun end() {
        send(DragEvent.ACTION_DRAG_ENDED)
    }
}
