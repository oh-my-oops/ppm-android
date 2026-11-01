package com.hienlh.ppm.app.ui.files

import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.state.files.FileTreeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The tree's context-menu entries that open a folder somewhere else: a File Explorer window on it, a terminal in it. */
class ExplorerOpenActionsTest {
    private val folder = FileTreeNode("main", "src/main", isDirectory = true, isIgnored = false)
    private val file = FileTreeNode("app.kt", "src/app.kt", isDirectory = false, isIgnored = false)

    /** What was asked, in order, each with the project-relative path it was given. */
    private val asked = mutableListOf<String>()
    private val explorer: (String) -> Unit = { asked += "explorer $it" }
    private val terminal: (String) -> Unit = { asked += "terminal $it" }

    private fun labels(node: FileTreeNode, openExplorer: ((String) -> Unit)? = explorer, openTerminal: ((String) -> Unit)? = terminal) =
        explorerOpenActions(node, openExplorer, openTerminal).map { it.label }

    @Test
    fun `a folder offers a File Explorer window on it before a terminal in it, as the web's menu does`() {
        val actions = explorerOpenActions(folder, explorer, terminal)

        assertEquals(listOf("Open in File Explorer", "Open in Terminal"), actions.map { it.label })
        assertEquals(listOf(ProductIcon.FolderOpen, ProductIcon.Terminal), actions.map { it.icon })
        assertFalse(actions.any { it.destructive })
    }

    @Test
    fun `each entry hands the folder's project-relative path to its own opener and to no other`() {
        val actions = explorerOpenActions(folder, explorer, terminal)

        actions[0].onClick()
        assertEquals(listOf("explorer src/main"), asked)
        actions[1].onClick()
        assertEquals(listOf("explorer src/main", "terminal src/main"), asked)
    }

    @Test
    fun `a file offers neither`() {
        assertEquals(emptyList<String>(), labels(file))
    }

    @Test
    fun `an entry whose owner gave no way to open it is not offered, and the other still is`() {
        assertEquals(listOf("Open in Terminal"), labels(folder, openExplorer = null))
        assertEquals(listOf("Open in File Explorer"), labels(folder, openTerminal = null))
        assertEquals(emptyList<String>(), labels(folder, null, null))
    }
}
