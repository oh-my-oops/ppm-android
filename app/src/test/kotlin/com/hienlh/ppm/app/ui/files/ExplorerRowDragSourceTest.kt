package com.hienlh.ppm.app.ui.files

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.DragModifierKeys
import com.hienlh.ppm.app.withComposeActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @After fun keysUp() = DragModifierKeys.update(ctrl = false, alt = false)

    /** What the source gave Android as the drag's local state, which the framework hands back with every event of the drag. */
    private fun startedLocalState(): Any? =
        ReflectionHelpers.getField<Any?>(ReflectionHelpers.callInstanceMethod<Any>(host!!, "getViewRootImpl"), "mLocalDragState")

    private fun show(drag: ExplorerRowDrag = ExplorerRowDrag("p", "src/app.kt", false), enabled: Boolean = true) {
        compose.setContent {
            host = LocalView.current
            Box(Modifier.size(200.dp, 40.dp).testTag("row").explorerRowDragSource(drag, enabled))
        }
        compose.waitForIdle()
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
        assertEquals(
            "the web's legacy single-path type, then its shared payload",
            listOf("application/x-ppm-path", "application/x-ppm-paths"), (0 until clip!!.description.mimeTypeCount).map { clip.description.getMimeType(it) },
        )
        assertEquals("the legacy value first, which the chat's drop reads", "src/app.kt", clip.getItemAt(0).text)
        assertEquals("the drag's local state, which the tree's drop target reads", ExplorerRowDrag("p", "src/app.kt", false), startedLocalState())
    }

    @Test fun `the shared payload is the whole drag, the web's JSON with absolute paths`() {
        show(ExplorerRowDrag("p", "src/app.kt", false, listOf("src/app.kt", "docs"), root = "/home/me/p"))
        dragWithMouse()
        val clip = ShadowWindowManagerGlobal.getLastDragClipData()!!
        assertEquals("src/app.kt", clip.getItemAt(0).text)
        assertEquals(
            """{"paths":["/home/me/p/src/app.kt","/home/me/p/docs"],"origin":"tree","projectName":"p"}""",
            clip.getItemAt(1).text.toString(),
        )
        assertEquals(2, clip.itemCount)
    }

    @Test fun `a folder's path in the drag ends in a slash, in the legacy value and not in the payload`() {
        show(ExplorerRowDrag("p", "src/util", isDirectory = true, root = "/home/me/p"))
        dragWithMouse()
        val clip = ShadowWindowManagerGlobal.getLastDragClipData()!!
        assertEquals("src/util/", clip.getItemAt(0).text)
        assertEquals("""{"paths":["/home/me/p/src/util"],"origin":"tree","projectName":"p"}""", clip.getItemAt(1).text.toString())
        assertEquals(ExplorerRowDrag("p", "src/util", isDirectory = true, root = "/home/me/p"), startedLocalState())
    }

    @Test fun `the keys down at the press are noted for the drop`() {
        show()
        DragModifierKeys.update(ctrl = false, alt = false)
        compose.onNodeWithTag("row").performKeyInput { keyDown(Key.CtrlLeft) }
        dragWithMouse()
        assertTrue("Ctrl was down", DragModifierKeys.copy)
        compose.onNodeWithTag("row").performKeyInput { keyUp(Key.CtrlLeft) }
    }

    @Test fun `Alt down at the press is a copy as well, and Shift is not`() {
        show()
        DragModifierKeys.update(ctrl = false, alt = false)
        compose.onNodeWithTag("row").performKeyInput { keyDown(Key.ShiftLeft) }
        dragWithMouse()
        assertFalse("Shift alone", DragModifierKeys.copy)
        compose.onNodeWithTag("row").performKeyInput { keyUp(Key.ShiftLeft) }
        compose.onNodeWithTag("row").performMouseInput { release() }

        compose.onNodeWithTag("row").performKeyInput { keyDown(Key.AltLeft) }
        dragWithMouse()
        assertTrue("Alt was down", DragModifierKeys.copy)
        compose.onNodeWithTag("row").performKeyInput { keyUp(Key.AltLeft) }
    }

    @Test fun `a press with no key down clears what an earlier one left`() {
        show()
        DragModifierKeys.update(ctrl = true, alt = true)
        dragWithMouse()
        assertFalse("neither was down", DragModifierKeys.copy)
    }

    @Test fun `the drag that starts is the row's as it is now, not as it was when the gesture was set up`() {
        var drag by mutableStateOf(ExplorerRowDrag("p", "src/app.kt", false))
        compose.setContent {
            host = LocalView.current
            Box(Modifier.size(200.dp, 40.dp).testTag("row").explorerRowDragSource(drag, true))
        }
        compose.waitForIdle()
        // The gesture is already running by the time the row is touched for a drag: the first pointer event starts it.
        compose.onNodeWithTag("row").performMouseInput { moveTo(center) }
        compose.waitForIdle()
        // A selection changes what the row drags without making the row a new one.
        val now =ExplorerRowDrag("p", "src/main.kt", false, listOf("src/main.kt", "docs"), "/home/me/p")
        drag = now
        compose.waitForIdle()
        ShadowWindowManagerGlobal.clearLastDragClipData()
        dragWithMouse()
        val clip = ShadowWindowManagerGlobal.getLastDragClipData()!!
        assertEquals("src/main.kt", clip.getItemAt(0).text)
        assertEquals("""{"paths":["/home/me/p/src/main.kt","/home/me/p/docs"],"origin":"tree","projectName":"p"}""", clip.getItemAt(1).text.toString())
        assertEquals(now, startedLocalState())
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
