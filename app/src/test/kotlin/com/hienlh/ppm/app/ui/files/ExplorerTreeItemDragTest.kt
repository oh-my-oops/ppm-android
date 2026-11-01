package com.hienlh.ppm.app.ui.files

import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.protocol.FileDirectoryEntry
import com.hienlh.ppm.protocol.FileDirectoryResult
import com.hienlh.ppm.state.files.FileTreeBackend
import com.hienlh.ppm.state.files.FileTreeNode
import com.hienlh.ppm.state.files.FileTreeRow
import com.hienlh.ppm.state.files.ProjectFileTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowWindowManagerGlobal

/**
 * What the tree's own item makes of a row that is dragged by a mouse: the row it names, the project it says it is from, and
 * whether it is a source at all. [ExplorerTreeRowDragTest] has the row's gestures; this has the item that builds the drag
 * and decides whether the tree may start one. A file row, because a folder's chevron is a vector icon this module's unit
 * tests cannot draw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExplorerTreeItemDragTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var tree: ProjectFileTree
    private var host: View? = null
    private val node = FileTreeNode("app.kt", "src/app.kt", isDirectory = false, isIgnored = false)

    private fun callbacks() = ExplorerMutationCallbacks(
        "p", create = { _, _, _ -> }, rename = { _, _ -> }, delete = {}, cut = {}, copy = {}, paste = {}, move = { _, _ -> },
    )

    private fun show(mutations: ExplorerMutationCallbacks?, busy: Boolean = false, selected: Set<String> = emptySet(), root: String? = null) {
        installBlankFileIcons()
        tree = ProjectFileTree(
            object : FileTreeBackend {
                override suspend fun list(project: String, path: String) =
                    if (path.isEmpty()) listOf(FileDirectoryEntry("src", isDirectory = true)) else listOf(FileDirectoryEntry("app.kt", isDirectory = false))
                override suspend fun listBatch(project: String, paths: List<String>): List<FileDirectoryResult> = error("Unexpected batch")
            }, scope, idleMillis = 60_000,
        )
        runBlocking { tree.open("p")?.join() }
        val session = ExplorerMutationSession("p", scope)
        compose.setContent {
            host = LocalView.current
            ExplorerTreeItem(
                FileTreeRow(node, depth = 1), tree, tree.state.value, listOf(node.path), selected,
                onOpenFile = {}, onOpenTerminal = null, onOpenExplorer = null, actionsForNode = { emptyList() },
                mutations = mutations, session = session, busy = busy, onCreate = { _, _ -> }, dragOver = ExplorerDragOver(), projectRoot = root,
            )
        }
        compose.waitForIdle()
        ShadowWindowManagerGlobal.clearLastDragClipData()
    }

    @After fun close() {
        if (::tree.isInitialized) tree.close()
        scope.cancel()
    }

    private fun dragTheRow() {
        compose.onNodeWithText("app.kt").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, 40f))
            moveBy(Offset(0f, 40f))
            release()
        }
        compose.waitForIdle()
    }

    private fun clip() = ShadowWindowManagerGlobal.getLastDragClipData()

    /** What the drag says to every drop target it passes: the framework keeps it with the window that started the drag. */
    private fun localState(): Any? {
        val root = host!!.rootView.parent
        return root.javaClass.getDeclaredField("mLocalDragState").apply { isAccessible = true }.get(root)
    }

    @Test fun `a file row of a tree that can write is dragged as its own path, which is not a folder's`() {
        show(callbacks())
        dragTheRow()
        val clip = clip()!!
        assertEquals(TREE_PATH_MIME, clip.description.getMimeType(0))
        assertEquals("src/app.kt", clip.getItemAt(0).text)
    }

    @Test fun `the drag names the project of the tree and the row's path, and says it is a file`() {
        show(callbacks())
        dragTheRow()
        assertEquals(ExplorerRowDrag("p", "src/app.kt", isDirectory = false), localState())
    }

    private fun payload() = clip()!!.getItemAt(1).text.toString()

    @Test fun `a row of the project carries its path under the project folder`() {
        show(callbacks(), root = "/home/me/p")
        dragTheRow()
        assertEquals("""{"paths":["/home/me/p/src/app.kt"],"origin":"tree","projectName":"p"}""", payload())
        assertEquals("src/app.kt", clip()!!.getItemAt(0).text)
    }

    @Test fun `a row that is one of several selected carries the whole selection, in the order it was selected`() {
        val selection = linkedSetOf("docs", node.path, "README.md")
        show(callbacks(), selected = selection, root = "/home/me/p")
        dragTheRow()
        assertEquals(
            """{"paths":["/home/me/p/docs","/home/me/p/src/app.kt","/home/me/p/README.md"],"origin":"tree","projectName":"p"}""",
            payload(),
        )
        assertEquals("the legacy value is still the row dragged, which the chat's drop and a row's move read", "src/app.kt", clip()!!.getItemAt(0).text)
        assertEquals(listOf("docs", "src/app.kt", "README.md"), (localState() as ExplorerRowDrag).paths)
    }

    @Test fun `a row that is not among the selected carries itself alone`() {
        show(callbacks(), selected = setOf("docs", "README.md"), root = "/home/me/p")
        dragTheRow()
        assertEquals("""{"paths":["/home/me/p/src/app.kt"],"origin":"tree","projectName":"p"}""", payload())
    }

    @Test fun `a row that is the only one selected carries itself alone`() {
        show(callbacks(), selected = setOf(node.path), root = "/home/me/p")
        dragTheRow()
        assertEquals("""{"paths":["/home/me/p/src/app.kt"],"origin":"tree","projectName":"p"}""", payload())
    }

    @Test fun `a tree that does not know the project folder carries the paths as they are, as the web does`() {
        show(callbacks(), selected = linkedSetOf(node.path, "docs"))
        dragTheRow()
        assertEquals("""{"paths":["src/app.kt","docs"],"origin":"tree","projectName":"p"}""", payload())
    }

    @Test fun `a tree with nothing to write with is not a drag source`() {
        show(mutations = null)
        dragTheRow()
        assertNull(clip())
    }

    @Test fun `a tree that is busy is not a drag source`() {
        show(callbacks(), busy = true)
        dragTheRow()
        assertNull(clip())
    }
}
