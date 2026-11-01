package com.hienlh.ppm.app.ui.files

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.protocol.FileDirectoryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real project tree on a phone, its menus pressed: which row opens which sheet, what each row of it does (the handler it
 * reaches, the paths it is given, the prompt it opens) and which rows a tree that may not change files leaves out. The phone,
 * because its sheet is the one window this module's Robolectric can make; a tablet's popup is the same rows
 * ([ExplorerTreeMenuRowsTest] draws them). The tree is the shared one: docs (shut), src (open: util, app.kt, main.kt), README.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h1800dp")
internal class ExplorerTreeMenuWiringTest : ExplorerTreeDragTestBase() {
    /** What the rows did, in order: `cut [src/app.kt]`, `paste docs`, `terminal docs`, `copy-path src/app.kt`. */
    private val events = mutableListOf<String>()

    /** A refresh asks for the root and the open folders in one batch. */
    override suspend fun batchListing(paths: List<String>) = paths.map { FileDirectoryResult(it, listing.getValue(it)) }

    private fun recording(canPaste: Boolean = true, busy: Boolean = false) = ExplorerMutationCallbacks(
        "p", create = { _, _, _ -> }, rename = { _, _ -> }, delete = {},
        cut = { events += "cut $it" }, copy = { events += "copy $it" }, paste = { events += "paste $it" }, move = { _, _ -> },
        canPaste = canPaste, busy = busy,
    )

    private fun phone(mutations: ExplorerMutationCallbacks? = recording()) = show(
        mutations, listOf("src"),
        content = { tree, commands ->
            ExplorerTree(
                tree, onOpenFile = {}, mutations = commands,
                onOpenTerminal = { events += "terminal $it" }, onOpenExplorer = { events += "explorer $it" },
                actionsForNode = { node -> listOf(ExplorerTreeAction("Copy Path") { events += "copy-path ${node.path}" }) },
            )
        },
        around = { content -> CompositionLocalProvider(LocalPpmMobile provides true) { content() } },
    )

    /** A press of the mouse's other button on the row, which the row's own menu gesture takes for a menu. */
    private fun menuOn(label: String) {
        compose.onNodeWithText(label).performMouseInput { rightClick() }
        compose.waitForIdle()
    }

    private fun menuOnBlank() {
        val at = blank()
        compose.onRoot().performMouseInput { rightClick(Offset(at.x, at.y)) }
        compose.waitForIdle()
    }

    private fun press(label: String) {
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    /** The rows of the sheet that is up, top to bottom (its backdrop is pressable too, and has no text). */
    private fun sheet(): List<String> = compose.onAllNodes(hasClickAction() and hasAnyAncestor(isDialog())).fetchSemanticsNodes()
        .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }

    @Test fun `a file's sheet is Cut, Copy, Rename and Delete and then what the owner adds, under no title`() {
        phone()
        menuOn("app.kt")

        assertEquals(listOf("Cut", "Copy", "Rename", "Delete", "Copy Path"), sheet())
        compose.onAllNodesWithText("app.kt").assertCountEquals(1)
    }

    @Test fun `a folder's sheet is the web's rows in its order`() {
        phone()
        menuOn("docs")

        assertEquals(
            listOf("New File", "New Folder", "Open in File Explorer", "Open in Terminal", "Cut", "Copy", "Paste", "Rename", "Delete", "Copy Path"),
            sheet(),
        )
    }

    @Test fun `a row in the selection cuts and copies the whole selection, and one outside it only itself`() {
        phone()
        tree.setSelectedFiles(listOf("src/app.kt", "src/main.kt"))
        compose.waitForIdle()

        menuOn("app.kt")
        press("Cut")
        menuOn("README.md")
        press("Copy")

        assertEquals(listOf("cut [src/app.kt, src/main.kt]", "copy [README.md]"), events)
    }

    @Test fun `a row closes the sheet and then runs`() {
        phone()

        menuOn("app.kt")
        press("Copy")

        assertEquals(emptyList<String>(), sheet())
        assertEquals(listOf("copy [src/app.kt]"), events)
    }

    @Test fun `the sheet's rows have the web's p-2 round them`() {
        phone()
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        menuOn("app.kt")

        val rows = compose.onAllNodes(hasClickAction() and hasAnyAncestor(isDialog())).fetchSemanticsNodes()
            .filter { it.config.getOrNull(SemanticsProperties.Text) != null }
        assertEquals(5, rows.size)
        for (row in rows) {
            assertEquals(8f, row.boundsInRoot.left, 0.5f)
            assertEquals(width - 8f, row.boundsInRoot.right, 0.5f)
        }
    }

    @Test fun `Cut and Copy are told apart`() {
        phone()

        menuOn("app.kt")
        press("Copy")
        menuOn("app.kt")
        press("Cut")

        assertEquals(listOf("copy [src/app.kt]", "cut [src/app.kt]"), events)
    }

    @Test fun `Paste on a folder pastes into it`() {
        phone()

        menuOn("docs")
        press("Paste")

        assertEquals(listOf("paste docs"), events)
    }

    @Test fun `a folder with nothing to paste has no Paste`() {
        phone(recording(canPaste = false))
        menuOn("docs")
        compose.onNodeWithText("Paste").assertDoesNotExist()
    }

    @Test fun `a file has no Paste with something to paste`() {
        phone(recording(canPaste = true))
        menuOn("app.kt")
        compose.onNodeWithText("Paste").assertDoesNotExist()
    }

    @Test fun `New Folder on a folder opens the folder name field inside it, and New File the file name field`() {
        phone()

        menuOn("docs")
        press("New Folder")
        compose.onNodeWithText("folder-name").assertExists()
        assertTrue("the folder is opened for it", "docs" in expanded())

        menuOn("docs")
        press("New File")
        compose.onNodeWithText("filename.ts").assertExists()
    }

    @Test fun `Rename opens the name field on the row`() {
        phone()

        menuOn("app.kt")
        press("Rename")
        compose.onNodeWithContentDescription("Rename app.kt").assertExists()
    }

    @Test fun `Delete asks before deleting the file`() {
        phone()

        menuOn("app.kt")
        press("Delete")

        compose.onNodeWithText("Permanently delete app.kt?").assertExists()
    }

    @Test fun `the Open in rows hand the folder's path to their own openers`() {
        phone()

        menuOn("docs")
        press("Open in File Explorer")
        menuOn("docs")
        press("Open in Terminal")

        assertEquals(listOf("explorer docs", "terminal docs"), events)
    }

    @Test fun `what the owner adds reaches the row it was asked for`() {
        phone()

        menuOn("app.kt")
        press("Copy Path")

        assertEquals(listOf("copy-path src/app.kt"), events)
    }

    @Test fun `a tree with nothing to write with has only the rows that read`() {
        phone(mutations = null)

        menuOn("app.kt")
        assertEquals(listOf("Copy Path"), sheet())
        press("Copy Path")
        menuOn("docs")
        assertEquals(listOf("Open in File Explorer", "Open in Terminal", "Copy Path"), sheet())
    }

    @Test fun `a tree that is busy has only the rows that read`() {
        phone(recording(busy = true))

        menuOn("docs")

        assertEquals(listOf("Open in File Explorer", "Open in Terminal", "Copy Path"), sheet())
    }

    @Test fun `the blank space's sheet is New File, New Folder and Refresh, with no Paste and no Open in Terminal`() {
        phone(recording(canPaste = true))
        menuOnBlank()

        assertEquals(listOf("New File", "New Folder", "Refresh"), sheet())
    }

    @Test fun `New Folder and New File on the blank space ask for a folder and a file`() {
        phone()

        menuOnBlank()
        press("New Folder")
        compose.onNodeWithText("folder-name").assertExists()

        menuOnBlank()
        press("New File")
        compose.onNodeWithText("filename.ts").assertExists()
    }

    @Test fun `Refresh on the blank space reads the project again`() {
        phone()
        listing[""] = listing.getValue("") + file("NEW.md")
        compose.onNodeWithText("NEW.md").assertDoesNotExist()

        menuOnBlank()
        press("Refresh")

        compose.onNodeWithText("NEW.md").assertExists()
    }

    @Test fun `the blank space of a tree that cannot create is Refresh alone`() {
        phone(mutations = null)
        menuOnBlank()

        assertEquals(listOf("Refresh"), sheet())
    }

    @Test fun `the blank space of a tree that is busy is Refresh alone`() {
        phone(recording(busy = true))
        menuOnBlank()

        assertEquals(listOf("Refresh"), sheet())
    }
}
