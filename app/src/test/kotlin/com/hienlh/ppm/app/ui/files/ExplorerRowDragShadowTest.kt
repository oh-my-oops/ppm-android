package com.hienlh.ppm.app.ui.files

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.withComposeActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * What the window is told about the image of a drag that a row starts: the browser holds its ghost 12 px from its corner
 * (`setDragImage(ghost, 12, 12)`), where Compose's own drag source holds the touch point at the middle of the image. The
 * window hears it in `performDrag`, as the touch point inside the shadow; three pixels to the dp, so the dp is told apart from the
 * pixel. The row is 200 dp wide: the middle of a row's image would be 300 px in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "xxhdpi")
class ExplorerRowDragShadowTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private var host: View? = null

    /** The flags each drag was started with. */
    private val flags = mutableListOf<Int>()

    private fun show(drag: ExplorerRowDrag) {
        compose.setContent {
            host = LocalView.current
            Box(Modifier.size(200.dp, 40.dp).testTag("row").explorerRowDragSource(drag, enabled = true))
        }
        compose.waitForIdle()
    }

    /**
     * Each touch point a drag was started with, as `performDrag` of the window's session got it (`thumbCenterX`, `thumbCenterY`: the
     * floats after the touch's own position). The session is the framework's, wrapped to see the call and then pass it on.
     */
    private fun touchPointsOfDrags(): List<Pair<Float, Float>> {
        val attach = ReflectionHelpers.getField<Any>(ReflectionHelpers.callInstanceMethod<Any>(host!!, "getViewRootImpl"), "mAttachInfo")
        val session = ReflectionHelpers.getField<Any>(attach, "mSession")
        val seen = mutableListOf<Pair<Float, Float>>()
        val spy = Proxy.newProxyInstance(session.javaClass.classLoader, session.javaClass.interfaces, InvocationHandler { _, method, args ->
            if (method.name == "performDrag") {
                seen += (args[6] as Float) to (args[7] as Float)
                flags += args[1] as Int
            }
            try { method.invoke(session, *(args ?: emptyArray())) } catch (error: InvocationTargetException) { throw error.targetException }
        })
        ReflectionHelpers.setField(attach, "mSession", spy)
        return seen
    }

    private fun dragWithMouse() {
        compose.onNodeWithTag("row").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, 40f))
            moveBy(Offset(0f, 40f))
        }
        compose.waitForIdle()
    }

    @Test fun `the pointer is held 12 dp in from the corner of the chip, which has the shadow's margin round it`() {
        show(ExplorerRowDrag("p", "src/app.kt", false))
        val seen = touchPointsOfDrags()
        dragWithMouse()
        // 10 dp of margin for the shadow, then the 12 dp of `setDragImage(ghost, 12, 12)`, at 3 px to the dp.
        assertEquals(listOf(66f to 66f), seen)
    }

    @Test fun `the drag is the window's own, with no flag that would hand its data to other apps`() {
        show(ExplorerRowDrag("p", "src/app.kt", false))
        touchPointsOfDrags()
        dragWithMouse()
        assertEquals(listOf(0), flags)
    }

    @Test fun `the pointer is held the same distance in whatever the drag carries`() {
        show(ExplorerRowDrag("p", "src/app.kt", false, listOf("src/app.kt", "docs", "README.md")))
        val seen = touchPointsOfDrags()
        dragWithMouse()
        assertEquals(listOf(66f to 66f), seen)
    }
}
