package com.hienlh.ppm.app.ui.files

import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.state.files.FileTreeNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The menus of the project tree as lists: the rows of `tree-node-context-menu.tsx` and of the blank-space menu in `file-tree.tsx`,
 * in their groups, each with the icon the web gives it and a divider above the first row of every group after the first. A row's
 * menu is built from the real [ExplorerTransfers.actionsForNode] and [explorerOpenActions], as the tree builds it. Each row is shown
 * as `— label [icon] (destructive)`, the dash standing for the divider above it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExplorerMenuActionsTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val transferSession = ExplorerTransferSession(scope)
    private lateinit var transfers: ExplorerTransfers

    private val folder = FileTreeNode("main", "src/main", isDirectory = true, isIgnored = false)
    private val file = FileTreeNode("app.kt", "src/app.kt", isDirectory = false, isIgnored = false)

    /** What the rows did when pressed, in order. */
    private val events = mutableListOf<String>()

    @Before
    fun transfers() {
        var clipboard: Clipboard? = null
        compose.setContent { clipboard = LocalClipboard.current }
        compose.waitForIdle()
        transfers = ExplorerTransfers(
            ApiClient("http://localhost/".toHttpUrl(), "secret"), ProjectInfo("p", "/work/p"), transferSession,
            ApplicationProvider.getApplicationContext(), null, clipboard!!, scope,
            choose = {}, cancelChoice = {}, onUploadFinished = { _, _ -> },
        )
    }

    @After
    fun close() {
        transferSession.close()
        scope.cancel()
    }

    private fun writes(canPaste: Boolean = true) = ExplorerNodeWrites(
        canPaste,
        create = { events += "create directory=$it" },
        cut = { events += "cut" },
        copy = { events += "copy" },
        paste = { events += "paste" },
        rename = { events += "rename" },
        delete = { events += "delete" },
    )

    private fun open(node: FileTreeNode, explorer: Boolean = true, terminal: Boolean = true) = explorerOpenActions(
        node,
        if (explorer) { path -> events += "explorer $path" } else null,
        if (terminal) { path -> events += "terminal $path" } else null,
    )

    private fun menu(node: FileTreeNode, writes: ExplorerNodeWrites?, open: List<ExplorerTreeAction> = open(node)) =
        explorerNodeActions(node, writes, open, transfers.actionsForNode(node))

    private fun List<ExplorerTreeAction>.shown() = map { action ->
        buildString {
            if (action.separatorBefore) append("— ")
            append(action.label)
            action.icon?.let { append(" [").append(it).append("]") }
            if (action.destructive) append(" (destructive)")
        }
    }

    @Test
    fun `a folder's menu is the web's rows in its groups, with an icon on Open in File Explorer, Open in Terminal and Download only`() {
        assertEquals(
            listOf(
                "New File", "New Folder",
                "— Open in File Explorer [${ProductIcon.FolderOpen}]", "Open in Terminal [${ProductIcon.Terminal}]",
                "— Cut", "Copy", "Paste",
                "— Rename", "Delete (destructive)",
                "— Copy Path", "Copy Full Path",
                "— Download as Zip [${ProductIcon.Download}]",
            ),
            menu(folder, writes()).shown(),
        )
    }

    @Test
    fun `a folder with nothing to paste has no Paste, and the groups round it do not move`() {
        assertEquals(
            listOf(
                "New File", "New Folder",
                "— Open in File Explorer [${ProductIcon.FolderOpen}]", "Open in Terminal [${ProductIcon.Terminal}]",
                "— Cut", "Copy",
                "— Rename", "Delete (destructive)",
                "— Copy Path", "Copy Full Path",
                "— Download as Zip [${ProductIcon.Download}]",
            ),
            menu(folder, writes(canPaste = false)).shown(),
        )
    }

    @Test
    fun `a file's menu has no New rows, no Open in rows and no Paste even with something to paste`() {
        assertEquals(
            listOf("Cut", "Copy", "— Rename", "Delete (destructive)", "— Copy Path", "Copy Full Path", "— Download [${ProductIcon.Download}]"),
            menu(file, writes(canPaste = true)).shown(),
        )
    }

    @Test
    fun `a tree that cannot change files offers only the rows that read, and the first of them has no divider above it`() {
        assertEquals(
            listOf(
                "Open in File Explorer [${ProductIcon.FolderOpen}]", "Open in Terminal [${ProductIcon.Terminal}]",
                "— Copy Path", "Copy Full Path", "— Download as Zip [${ProductIcon.Download}]",
            ),
            menu(folder, writes = null).shown(),
        )
        assertEquals(
            listOf("Copy Path", "Copy Full Path", "— Download [${ProductIcon.Download}]"),
            menu(file, writes = null).shown(),
        )
    }

    @Test
    fun `while a transfer runs Download goes and takes its divider with it`() {
        transferSession.choose(file)

        assertEquals(
            listOf("Cut", "Copy", "— Rename", "Delete (destructive)", "— Copy Path", "Copy Full Path"),
            menu(file, writes()).shown(),
        )
    }

    @Test
    fun `a folder whose owner gave no way to open it elsewhere has no group for it and no divider where it would be`() {
        assertEquals(
            listOf(
                "New File", "New Folder", "— Cut", "Copy", "Paste", "— Rename", "Delete (destructive)",
                "— Copy Path", "Copy Full Path", "— Download as Zip [${ProductIcon.Download}]",
            ),
            menu(folder, writes(), open(folder, explorer = false, terminal = false)).shown(),
        )
        assertEquals(
            listOf("New File", "New Folder", "— Open in Terminal [${ProductIcon.Terminal}]", "— Cut"),
            menu(folder, writes(), open(folder, explorer = false)).shown().take(4),
        )
    }

    @Test
    fun `only Delete is destructive`() {
        val rows = menu(folder, writes())
        assertEquals(listOf("Delete"), rows.filter { it.destructive }.map { it.label })
    }

    @Test
    fun `each row runs its own handler, and New Folder asks for a folder`() {
        val transferLabels = transfers.actionsForNode(folder).map { it.label }.toSet()
        for (action in menu(folder, writes()).filterNot { it.label in transferLabels }) action.onClick()

        assertEquals(
            listOf(
                "create directory=false", "create directory=true", "explorer src/main", "terminal src/main",
                "cut", "copy", "paste", "rename", "delete",
            ),
            events,
        )
    }

    @Test
    fun `a divider stands between two rows exactly when they are of different groups, whatever the gates leave`() {
        fun group(label: String) = when (label) {
            "New File", "New Folder" -> 0
            "Open in File Explorer", "Open in Terminal" -> 1
            "Cut", "Copy", "Paste" -> 2
            "Rename", "Delete" -> 3
            "Copy Path", "Copy Full Path" -> 4
            else -> 5
        }
        var checked = 0
        for (node in listOf(folder, file)) for (writes in listOf(null, writes(canPaste = true), writes(canPaste = false))) {
            for (busy in listOf(false, true)) for (explorer in listOf(false, true)) for (terminal in listOf(false, true)) {
                transferSession.cancel()
                if (busy) transferSession.choose(node)
                val rows = menu(node, writes, open(node, explorer, terminal))
                assertTrue("$node $writes $busy $explorer $terminal", rows.isNotEmpty())
                for ((index, row) in rows.withIndex()) {
                    val expected = index > 0 && group(row.label) != group(rows[index - 1].label)
                    assertEquals("the divider above ${row.label}", expected, row.separatorBefore)
                }
                checked++
            }
        }
        assertEquals(2 * 3 * 2 * 2 * 2, checked)
    }

    @Test
    fun `the blank space offers New File and New Folder and, under a divider, Refresh, each with its icon`() {
        assertEquals(
            listOf("New File [${ProductIcon.FilePlus}]", "New Folder [${ProductIcon.FolderPlus}]", "— Refresh [${ProductIcon.RefreshCw}]"),
            explorerRootActions(canCreate = true, onCreate = { events += "create directory=$it" }, onRefresh = { events += "refresh" }).shown(),
        )
    }

    @Test
    fun `the blank space of a tree that cannot create is Refresh alone, with no divider above it`() {
        assertEquals(
            listOf("Refresh [${ProductIcon.RefreshCw}]"),
            explorerRootActions(canCreate = false, onCreate = { events += "create directory=$it" }, onRefresh = { events += "refresh" }).shown(),
        )
    }

    @Test
    fun `the blank space has no Paste and no Open in Terminal`() {
        val labels = explorerRootActions(canCreate = true, onCreate = {}, onRefresh = {}).map { it.label }
        assertEquals(emptyList<String>(), labels.filter { it == "Paste" || it == "Open in Terminal" })
    }

    @Test
    fun `the blank space's rows run their own handlers`() {
        for (action in explorerRootActions(canCreate = true, onCreate = { events += "create directory=$it" }, onRefresh = { events += "refresh" })) action.onClick()

        assertEquals(listOf("create directory=false", "create directory=true", "refresh"), events)
    }

    @Test
    fun `explorerMenuGroups drops empty groups, sets the divider on each group's first row and none on the first of all`() {
        fun row(label: String, separatorBefore: Boolean = false) = ExplorerTreeAction(label, separatorBefore = separatorBefore) {}

        val rows = explorerMenuGroups(
            emptyList(),
            listOf(row("a", separatorBefore = true), row("b")),
            emptyList(),
            listOf(row("c"), row("d", separatorBefore = true)),
            listOf(row("e")),
        )

        assertEquals(listOf("a", "b", "— c", "— d", "— e"), rows.shown())
    }
}
