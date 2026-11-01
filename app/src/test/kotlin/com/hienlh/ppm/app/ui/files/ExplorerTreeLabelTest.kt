package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.state.files.FileTreeNode
import com.hienlh.ppm.state.files.FileTreeRow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A name too long for its row loses its end behind the "…" (`truncate`, tree-node.tsx): `long-folder-name-abcdefg…`. The row used
 * to lose its start, as the git and review lists do, which keeps the end of a path. Drawn for real, at three pixels to the dp: the
 * start of the label in a row too narrow for the name is the start of the label in a row that holds the whole of it, both on black.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class ExplorerTreeLabelTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val name = "long-folder-name-abcdefghijklmnop"

    private fun show(directory: Boolean) {
        installBlankFileIcons()
        val node = FileTreeNode(name, name, isDirectory = directory, isIgnored = false)
        compose.setContent {
            Column {
                for ((tag, width) in listOf("narrow" to 150, "wide" to 400)) Box(Modifier.width(width.dp).background(Color.Black).testTag(tag)) {
                    ExplorerTreeRow(FileTreeRow(node, depth = 0), expanded = false, loading = false, selected = false, focused = false, onClick = { _, _ -> }, onMenu = {})
                }
            }
        }
        compose.waitForIdle()
    }

    /** The columns of the row [tag] from [from] dp to [to] dp, as pixels. */
    private fun region(tag: String, from: Int, to: Int): IntArray {
        val image = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val width = (to - from) * 3
        return IntArray(width * image.height).also { image.getPixels(it, 0, width, from * 3, 0, width, image.height) }
    }

    // The label starts 50 dp in (the chevron, the icon's blank space and their gaps) and the "…" of the narrow row is past 130.
    private fun assertCutAtItsEnd() {
        assertTrue(region("narrow", 50, 100).contentEquals(region("wide", 50, 100)))
        assertFalse("the row is too narrow for the whole name", region("narrow", 0, 150).contentEquals(region("wide", 0, 150)))
    }

    @Test
    fun `a folder name too long for its row is cut at its end, so the start of it is drawn as it is when it fits`() {
        show(directory = true)

        assertCutAtItsEnd()
    }

    @Test
    fun `a file name too long for its row is cut at its end too`() {
        show(directory = false)

        assertCutAtItsEnd()
    }
}
