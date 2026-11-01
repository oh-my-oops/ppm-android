package com.hienlh.ppm.app.ui.files

import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.withComposeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowWindowManagerGlobal
import org.robolectric.util.ReflectionHelpers

/**
 * Who may start a drag of a row, with the real gestures: only a mouse's primary button, once it has moved past the touch
 * slop, gets a clip out of it. The row is alone in the window here, because a long press or a right click on a tree row
 * opens its menu, a popup these unit tests cannot create.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExplorerRowDragSourceTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private var host: View? = null

    /** What the source gave Android as the drag's local state, which the framework hands back with every event of the drag. */
    private fun startedLocalState(): Any? =
        ReflectionHelpers.getField<Any?>(ReflectionHelpers.callInstanceMethod<Any>(host!!, "getViewRootImpl"), "mLocalDragState")

    private fun show(drag: ExplorerRowDrag = ExplorerRowDrag("p", "src/app.kt", false), enabled: Boolean = true) {
        compose.setContent {
            host = LocalView.current
            Box(Modifier.size(200.dp, 40.dp).testTag("row").explorerRowDragSource(drag, enabled))
        }
        compose.waitForIdle()
        // Compose drags the row's own picture and only has it once the row has been drawn, which these tests' window never does by itself.
        compose.runOnUiThread { host!!.draw(Canvas()) }
        ShadowWindowManagerGlobal.clearLastDragClipData()
    }

    private fun dragWithMouse(button: MouseButton = MouseButton.Primary) {
        compose.onNodeWithTag("row").performMouseInput {
            moveTo(center)
            press(button)
            moveBy(Offset(0f, 40f))
            moveBy(Offset(0f, 40f))
        }
        compose.waitForIdle()
    }

    @Test fun `a mouse dragged from a file row starts a drag carrying its path`() {
        show()
        dragWithMouse()
        val clip = ShadowWindowManagerGlobal.getLastDragClipData()
        assertNotNull("a drag was started", clip)
        assertEquals("the web's legacy single-path type", listOf("application/x-ppm-path"), (0 until clip!!.description.mimeTypeCount).map { clip.description.getMimeType(it) })
        assertEquals("src/app.kt", clip.getItemAt(0).text)
        assertEquals("the drag's local state, which the tree's drop target reads", ExplorerRowDrag("p", "src/app.kt", false), startedLocalState())
    }

    @Test fun `a folder's path in the drag ends in a slash`() {
        show(ExplorerRowDrag("p", "src/util", isDirectory = true))
        dragWithMouse()
        assertEquals("src/util/", ShadowWindowManagerGlobal.getLastDragClipData()!!.getItemAt(0).text)
        assertEquals(ExplorerRowDrag("p", "src/util", isDirectory = true), startedLocalState())
    }

    @Test fun `a mouse's other buttons start nothing`() {
        show()
        dragWithMouse(MouseButton.Secondary)
        assertNull(ShadowWindowManagerGlobal.getLastDragClipData())
        dragWithMouse(MouseButton.Tertiary)
        assertNull(ShadowWindowManagerGlobal.getLastDragClipData())
    }

    @Test fun `a click that does not move starts nothing`() {
        show()
        compose.onNodeWithTag("row").performMouseInput {
            moveTo(center)
            press()
            release()
        }
        compose.waitForIdle()
        assertNull(ShadowWindowManagerGlobal.getLastDragClipData())
    }

    @Test fun `a mouse starts a drag only once it has moved past the touch slop`() {
        show()
        compose.onNodeWithTag("row").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, viewConfiguration.touchSlop / 2))
        }
        compose.waitForIdle()
        assertNull("half the slop is still a click", ShadowWindowManagerGlobal.getLastDragClipData())
        compose.onNodeWithTag("row").performMouseInput { moveBy(Offset(0f, viewConfiguration.touchSlop)) }
        compose.waitForIdle()
        assertNotNull("past it is a drag", ShadowWindowManagerGlobal.getLastDragClipData())
    }

    @Test fun `a button that was let go drags nothing when the mouse moves on`() {
        show()
        compose.onNodeWithTag("row").performMouseInput {
            moveTo(center)
            press()
            release()
            moveBy(Offset(0f, 40f))
            moveBy(Offset(0f, 40f))
        }
        compose.waitForIdle()
        assertNull(ShadowWindowManagerGlobal.getLastDragClipData())
    }

    @Test fun `a finger starts nothing, however long it holds or far it goes`() {
        show()
        compose.onNodeWithTag("row").performTouchInput {
            down(center)
            advanceEventTime(1_000)
            moveBy(Offset(0f, 60f))
            moveBy(Offset(0f, 60f))
            up()
        }
        compose.waitForIdle()
        assertNull(ShadowWindowManagerGlobal.getLastDragClipData())
    }

    @Test fun `a row that may not be dragged starts nothing`() {
        show(enabled = false)
        dragWithMouse()
        assertNull(ShadowWindowManagerGlobal.getLastDragClipData())
    }
}
