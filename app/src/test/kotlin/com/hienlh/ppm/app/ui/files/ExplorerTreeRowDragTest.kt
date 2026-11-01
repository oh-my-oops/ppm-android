package com.hienlh.ppm.app.ui.files

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.state.files.FileTreeNode
import com.hienlh.ppm.state.files.FileTreeRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowWindowManagerGlobal

/**
 * The real tree row with the drag source on it: the gestures the row already had still do what they did, and a mouse drag from
 * it starts a drag and nothing else. A file row, because a folder's chevron is a vector icon, which this module's unit tests
 * cannot draw (what the row draws is [ExplorerRowDragSourceDrawTest]'s, in native graphics).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExplorerTreeRowDragTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private var clicks = 0
    private val menus = mutableListOf<Offset>()

    @Before fun show() {
        installBlankFileIcons()
        val node = FileTreeNode("app.kt", "src/app.kt", isDirectory = false, isIgnored = false)
        compose.setContent {
            ExplorerTreeRow(
                FileTreeRow(node, depth = 1), expanded = false, loading = false, selected = false, focused = false,
                onClick = { _, _ -> clicks++ }, onMenu = { menus += it },
                dragSource = Modifier.explorerRowDragSource(ExplorerRowDrag("p", node.path, node.isDirectory), enabled = true),
            )
        }
        compose.waitForIdle()
        ShadowWindowManagerGlobal.clearLastDragClipData()
    }

    private fun clip() = ShadowWindowManagerGlobal.getLastDragClipData()

    @Test fun `a mouse dragged from the row starts a drag and neither opens the file nor a menu`() {
        compose.onNodeWithText("app.kt").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, 40f))
            moveBy(Offset(0f, 40f))
            release()
        }
        compose.waitForIdle()
        assertEquals("src/app.kt", clip()!!.getItemAt(0).text)
        assertEquals(0, clicks)
        assertEquals(emptyList<Offset>(), menus)
    }

    @Test fun `a mouse click on the row still opens it`() {
        compose.onNodeWithText("app.kt").performMouseInput {
            moveTo(center)
            press()
            release()
        }
        compose.waitForIdle()
        assertEquals(1, clicks)
        assertNull(clip())
    }

    @Test fun `a right click on the row still asks for its menu and starts no drag`() {
        compose.onNodeWithText("app.kt").performMouseInput {
            moveTo(center)
            press(MouseButton.Secondary)
            moveBy(Offset(0f, 40f))
            release(MouseButton.Secondary)
        }
        compose.waitForIdle()
        assertEquals(1, menus.size)
        assertEquals(0, clicks)
        assertNull(clip())
    }

    @Test fun `a tap still opens the row`() {
        compose.onNodeWithText("app.kt").performTouchInput {
            down(center)
            up()
        }
        compose.waitForIdle()
        assertEquals(1, clicks)
        assertNull(clip())
    }

    @Test fun `a long press still asks for the menu, once, and starts no drag`() {
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("app.kt").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithText("app.kt").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(1, menus.size)
        assertEquals(0, clicks)
        assertNull(clip())
    }
}
