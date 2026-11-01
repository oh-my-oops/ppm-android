package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.state.files.FileTreeNode
import com.hienlh.ppm.state.files.FileTreeRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a row with the drag source on it draws once its content changes after the first frame: the row, as it is now. The source
 * used to be Compose's own `dragAndDropSource(block)`, which records the row's content into a picture the first time it is drawn
 * (for the drag's ghost) and from then on only plays that picture back, so a chevron, a label, a spinner or an icon that arrived
 * after the first frame was never drawn: every row of the project tree stayed as it first looked. Drawn for real, at three
 * pixels to the dp (native graphics). The source draws nothing of its own now (the drag's image is a chip made when the drag starts),
 * and these keep it that way.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class ExplorerRowDragSourceDrawTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private var color by mutableStateOf(Color.Red)
    private var expanded by mutableStateOf(false)

    private fun centre(tag: String): Int {
        val image = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        return image.getPixel(image.width / 2, image.height / 2)
    }

    private fun pixels(tag: String): IntArray {
        val image = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        return IntArray(image.width * image.height).also { image.getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
    }

    @Test
    fun `a row drawn red and then green is green`() {
        compose.setContent {
            Box(Modifier.size(100.dp, 40.dp).testTag("row").explorerRowDragSource(ExplorerRowDrag("p", "a.kt", false), enabled = true).background(color))
        }
        compose.waitForIdle()
        assertEquals("the first frame", Color.Red.toArgb(), centre("row"))

        color = Color.Green
        compose.waitForIdle()

        assertEquals("the frame after the change", Color.Green.toArgb(), centre("row"))
    }

    @Test
    fun `a folder row that opens after its first frame is drawn open`() {
        installBlankFileIcons()
        val node = FileTreeNode("src", "src", isDirectory = true, isIgnored = false)
        compose.setContent {
            Box(Modifier.testTag("row")) {
                ExplorerTreeRow(
                    FileTreeRow(node, depth = 1), expanded = expanded, loading = false, selected = false, focused = false,
                    onClick = { _, _ -> }, onMenu = {},
                    dragSource = Modifier.explorerRowDragSource(ExplorerRowDrag("p", node.path, node.isDirectory), enabled = true),
                )
            }
        }
        compose.waitForIdle()
        val shut = pixels("row")

        expanded = true
        compose.waitForIdle()
        val open = pixels("row")

        // The chevron turns down and the name goes from the secondary colour in the normal weight to the primary in the medium one.
        assertFalse("the row is as it was drawn shut", shut.contentEquals(open))
    }
}
