package com.hienlh.ppm.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.withComposeActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where a swipe down closes a sheet (`useSwipeToDismiss`): anywhere on the panel, or, once its content
 * stops the touches (`mobile-explorer-sheet.tsx`, while a name is being typed), on the handle alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class BottomSheetSwipeTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private var dismissed = 0

    private fun show(swipeFromContent: Boolean) = compose.setContent {
        BottomSheet(onDismiss = { dismissed++ }, swipeFromContent = swipeFromContent) {
            Box(Modifier.fillMaxWidth().height(300.dp).testTag("content"))
        }
    }

    /** Down from the middle of the node by [by] px, in 100 ms: far and fast enough to be a dismissal. */
    private fun swipeDownFrom(tag: String, by: Float = 200f) {
        compose.onNodeWithTag(tag).performTouchInput { swipe(center, center + Offset(0f, by), durationMillis = 100) }
        compose.waitForIdle()
    }

    @Test
    fun `a swipe down over the content closes the sheet`() {
        show(swipeFromContent = true)
        swipeDownFrom("content")
        assertEquals(1, dismissed)
    }

    @Test
    fun `a swipe down on the handle closes it as well`() {
        show(swipeFromContent = true)
        swipeDownFrom(SHEET_HANDLE_TAG)
        assertEquals(1, dismissed)
    }

    @Test
    fun `a short swipe is not a dismissal, and the sheet springs back`() {
        show(swipeFromContent = true)
        swipeDownFrom("content", by = 10f)
        assertEquals(0, dismissed)
    }

    @Test
    fun `with the content's touches stopped a swipe over it leaves the sheet where it is`() {
        show(swipeFromContent = false)
        swipeDownFrom("content")
        assertEquals(0, dismissed)
    }

    @Test
    fun `with the content's touches stopped the handle still closes it`() {
        show(swipeFromContent = false)
        swipeDownFrom(SHEET_HANDLE_TAG)
        assertEquals(1, dismissed)
    }
}
