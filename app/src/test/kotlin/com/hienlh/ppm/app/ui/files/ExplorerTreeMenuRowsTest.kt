package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.MenuBox
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ThemeTokens
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A project-tree menu as it is drawn, measured in the web's own units: on a tablet `ContextMenuItem` (`px-2 py-1.5`, a label after an
 * icon 38 dp in, the divider `my-1 h-px`) and on a phone `BottomSheetItem` (`px-3 py-3`, 42 dp in, the same divider). The popup and
 * the sheet are windows this module's Robolectric cannot make, so the rows are drawn alone in a box and a column; drawn for real
 * (native graphics, 3 pixels to the dp), as a row's height is its text's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1800dp-xxhdpi")
class ExplorerTreeMenuRowsTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    /** What happened, in order: `dismiss`, then `run Cut`. */
    private val events = mutableListOf<String>()

    private var density = Density(1f)
    private var tokens: ThemeTokens? = null

    private fun action(label: String, icon: ProductIcon? = null, destructive: Boolean = false, separatorBefore: Boolean = false) =
        ExplorerTreeAction(label, icon, destructive, separatorBefore) { events += "run $label" }

    /** Two groups of the folder menu and the destructive pair: an icon on the first row of the second, a divider above each group. */
    private val menu = listOf(
        action("New File"),
        action("New Folder"),
        action("Open in Terminal", ProductIcon.Terminal, separatorBefore = true),
        action("Cut", separatorBefore = true),
        action("Delete", destructive = true),
    )

    private fun tablet(rows: List<ExplorerTreeAction> = menu) = compose.setContent {
        density = LocalDensity.current
        tokens = LocalPpmTokens.current
        MenuBox(Modifier.testTag("box")) { ExplorerMenuRows(rows) { events += "dismiss" } }
    }

    private fun phone(rows: List<ExplorerTreeAction> = menu) = compose.setContent {
        density = LocalDensity.current
        tokens = LocalPpmTokens.current
        Column(Modifier.testTag("box")) { ExplorerSheetRows(rows) { events += "dismiss" } }
    }

    private fun px(dp: Dp) = with(density) { dp.toPx() }

    private fun top(label: String) = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top
    private fun bottom(label: String) = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.bottom
    private fun height(label: String) = bottom(label) - top(label)

    /** How far in the label's own text starts from the left edge of its row. */
    private fun labelStart(label: String): Float =
        compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left -
            compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.left

    private fun colourOf(label: String): Color {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single().layoutInput.style.color
    }

    @Test
    fun `a tablet row is 32 dp, and a divider puts 9 dp between two rows, none inside a group`() {
        tablet()

        for (label in menu.map { it.label }) assertEquals(label, px(32.dp), height(label), 0.5f)
        assertEquals("inside a group", 0f, top("New Folder") - bottom("New File"), 0.5f)
        assertEquals("a divider is my-1 h-px", px(9.dp), top("Open in Terminal") - bottom("New Folder"), 0.5f)
        assertEquals(px(9.dp), top("Cut") - bottom("Open in Terminal"), 0.5f)
        assertEquals("inside a group", 0f, top("Delete") - bottom("Cut"), 0.5f)
    }

    @Test
    fun `a tablet label after an icon starts 38 dp in, and one without an icon 8 dp in with no gutter kept`() {
        tablet()

        assertEquals(px(38.dp), labelStart("Open in Terminal"), 0.5f)
        for (label in listOf("New File", "New Folder", "Cut", "Delete")) assertEquals(label, px(8.dp), labelStart(label), 0.5f)
    }

    @Test
    fun `a tablet menu's first row has no divider above it, whatever it says`() {
        tablet(listOf(action("First", separatorBefore = true), action("Second")))

        val box = compose.onNodeWithTag("box").fetchSemanticsNode().boundsInRoot
        // The box's own border and p-1 and nothing more.
        assertEquals(px(5.dp), top("First") - box.top, 0.5f)
    }

    @Test
    fun `a tablet row closes the menu and then runs`() {
        tablet()

        compose.onNodeWithText("Cut").performClick()
        compose.onNodeWithText("Open in Terminal").performClick()

        assertEquals(listOf("dismiss", "run Cut", "dismiss", "run Open in Terminal"), events)
    }

    @Test
    fun `on a tablet only Delete is red`() {
        tablet()

        val t = tokens!!
        assertEquals(t.error.color, colourOf("Delete"))
        for (label in listOf("New File", "Open in Terminal", "Cut")) assertEquals(label, t.text.color, colourOf(label))
    }

    @Test
    fun `a phone row is 44 dp, and a divider puts 9 dp between two rows, none inside a group`() {
        phone()

        for (label in menu.map { it.label }) assertEquals(label, px(44.dp), height(label), 0.5f)
        assertEquals("inside a group", 0f, top("New Folder") - bottom("New File"), 0.5f)
        assertEquals("a divider is -mx-1 my-1 h-px", px(9.dp), top("Open in Terminal") - bottom("New Folder"), 0.5f)
        assertEquals(px(9.dp), top("Cut") - bottom("Open in Terminal"), 0.5f)
        assertEquals("inside a group", 0f, top("Delete") - bottom("Cut"), 0.5f)
    }

    @Test
    fun `a phone label after an icon starts 42 dp in, and one without an icon 12 dp in with no gutter kept`() {
        phone()

        assertEquals(px(42.dp), labelStart("Open in Terminal"), 0.5f)
        for (label in listOf("New File", "New Folder", "Cut", "Delete")) assertEquals(label, px(12.dp), labelStart(label), 0.5f)
    }

    @Test
    fun `a phone menu's first row has no divider above it, whatever it says`() {
        phone(listOf(action("First", separatorBefore = true), action("Second")))

        assertEquals(compose.onNodeWithTag("box").fetchSemanticsNode().boundsInRoot.top, top("First"), 0.5f)
    }

    @Test
    fun `a phone row closes the sheet and then runs`() {
        phone()

        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("New Folder").performClick()

        assertEquals(listOf("dismiss", "run Delete", "dismiss", "run New Folder"), events)
    }

    @Test
    fun `on a phone only Delete is red`() {
        phone()

        val t = tokens!!
        assertEquals(t.error.color, colourOf("Delete"))
        for (label in listOf("New File", "Open in Terminal", "Cut")) assertEquals(label, t.text.color, colourOf(label))
    }
}
