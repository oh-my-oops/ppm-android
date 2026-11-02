package com.hienlh.ppm.app.ui.osexplorer

import android.content.ClipData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.hienlh.ppm.app.DragModifierKeys
import com.hienlh.ppm.app.ui.files.ENTRY_DRAG_MIME
import com.hienlh.ppm.app.ui.files.HostEntryDrag
import com.hienlh.ppm.state.osexplorer.ExplorerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.shadows.ShadowWindowManagerGlobal
import org.robolectric.util.ReflectionHelpers

/**
 * What starts a drag from a row or a tile of a window, with the real gestures (`use-entry-row-dnd.ts`, `use-entry-drag-source.ts`): a
 * mouse's primary button once it has moved past the touch slop, and nothing else; what it carries is the shared payload alone, the absolute
 * paths of what a menu on that row would act on. A phone's window and one with no commands have no drag at all.
 */
internal abstract class HostEntriesViewDragSourceTestBase(protected val view: ExplorerView) : HostEntryDndTestBase() {
    /** The clip the last drag was started with, null if none was. */
    protected fun startedClip(): ClipData? = ShadowWindowManagerGlobal.getLastDragClipData()

    /** What the source gave Android as the drag's local state, which the framework hands back with every event of the drag. */
    protected fun startedLocalState(): Any? =
        ReflectionHelpers.getField<Any?>(ReflectionHelpers.callInstanceMethod<Any>(host!!, "getViewRootImpl"), "mLocalDragState")

    protected fun dragWithMouse(name: String, button: MouseButton = MouseButton.Primary) {
        ShadowWindowManagerGlobal.clearLastDragClipData()
        compose.onNodeWithContentDescription(name).performMouseInput {
            moveTo(center)
            press(button)
            moveBy(Offset(0f, 40f))
            moveBy(Offset(0f, 40f))
            release(button)
        }
        compose.waitForIdle()
    }

    @Test
    fun `a mouse dragged from a row starts a drag that carries the shared payload alone, with its absolute path`() {
        show(view)
        dragWithMouse("a.txt")
        val clip = startedClip()
        assertNotNull("a drag was started", clip)
        assertEquals("no legacy tree type, so the chat lights nothing", listOf(ENTRY_DRAG_MIME), (0 until clip!!.description.mimeTypeCount).map { clip.description.getMimeType(it) })
        assertEquals(1, clip.itemCount)
        assertEquals("""{"paths":["/home/me/a.txt"],"origin":"explorer"}""", clip.getItemAt(0).text.toString())
        assertEquals("the local state, which the drop targets of the window read", HostEntryDrag(listOf("/home/me/a.txt")), startedLocalState())
    }

    @Test
    fun `a folder's row carries the folder's own path, with no slash after it`() {
        show(view)
        dragWithMouse("docs")
        assertEquals("""{"paths":["/home/me/docs"],"origin":"explorer"}""", startedClip()!!.getItemAt(0).text.toString())
    }

    @Test
    fun `the keys down at the press are noted for the drop`() {
        show(view)
        DragModifierKeys.update(ctrl = false, alt = false)
        compose.onNodeWithContentDescription("a.txt").performKeyInput { keyDown(Key.CtrlLeft) }
        dragWithMouse("a.txt")
        assertTrue("Ctrl was down", DragModifierKeys.copy)
        compose.onNodeWithContentDescription("a.txt").performKeyInput { keyUp(Key.CtrlLeft) }
    }

    @Test
    fun `the secondary button starts none, its click being the menu`() {
        show(view)
        dragWithMouse("a.txt", MouseButton.Secondary)
        assertNull(startedClip())
    }

    @Test
    fun `a finger's swipe starts none, which is what scrolls the list`() {
        show(view)
        ShadowWindowManagerGlobal.clearLastDragClipData()
        compose.onNodeWithContentDescription("a.txt").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertNull(startedClip())
    }

    @Test
    fun `a window on a phone has no drag, as the web's has none on touch`() {
        show(view, mobile = true)
        dragWithMouse(if (view == ExplorerView.COLUMNS) "docs" else "a.txt")
        assertNull(startedClip())
    }

    @Test
    fun `a window with no commands has none either`() {
        show(view, withCommands = false)
        dragWithMouse("a.txt")
        assertNull(startedClip())
    }

    @Test
    fun `what a window's row starts is what its own folders take, end to end`() {
        show(view, clockHeld = view == ExplorerView.COLUMNS)
        dragWithMouse("a.txt")
        driver.local = startedLocalState()
        driver.clip = startedClip()!!
        assertTrue(driver.begin())
        hover(center("docs"))
        assertTrue(letGo(center("docs")))
        assertEquals(listOf("move /home/me/a.txt -> /home/me/docs/a.txt"), transfers.toList())
        assertFalse(commands.busy)
    }
}
