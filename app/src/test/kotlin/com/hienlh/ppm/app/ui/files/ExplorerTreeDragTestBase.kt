package com.hienlh.ppm.app.ui.files

import android.view.DragEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.protocol.FileDirectoryEntry
import com.hienlh.ppm.protocol.FileDirectoryResult
import com.hienlh.ppm.state.files.FileTreeBackend
import com.hienlh.ppm.state.files.ProjectFileTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.rules.RuleChain
import org.robolectric.shadows.DragEventBuilder

/**
 * The real project tree in a window, and a row of it dragged over it as Android delivers a drag (started, entered, a location
 * per move, a drop, ended). The tree is
 *
 *     docs/            shut, holds guide.md
 *     src/             open: util/ (shut, holds x.kt), app.kt, main.kt
 *     README.md
 */
internal abstract class ExplorerTreeDragTestBase {
    protected val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    protected lateinit var tree: ProjectFileTree
    private var view: View? = null

    /** What the tree's `move` callback was asked for, as source to destination. */
    protected val moves = mutableListOf<Pair<String, String>>()

    protected fun dir(name: String) = FileDirectoryEntry(name, isDirectory = true)
    protected fun file(name: String) = FileDirectoryEntry(name, isDirectory = false)

    protected val listing = mutableMapOf(
        "" to listOf(dir("docs"), dir("src"), file("README.md")),
        "docs" to listOf(file("guide.md")),
        "src" to listOf(dir("util"), file("app.kt"), file("main.kt")),
        "src/util" to listOf(file("x.kt")),
    )

    protected fun callbacks(project: String = "p") = ExplorerMutationCallbacks(
        project, create = { _, _, _ -> }, rename = { _, _ -> }, delete = {}, cut = {}, copy = {}, paste = {},
        move = { source, destination -> moves += source to destination },
    )

    protected fun show(
        mutations: ExplorerMutationCallbacks? = callbacks(), expand: List<String> = listOf("src"),
        around: @Composable (@Composable () -> Unit) -> Unit = { it() },
    ) {
        installBlankFileIcons()
        tree = ProjectFileTree(
            object : FileTreeBackend {
                override suspend fun list(project: String, path: String) = listing.getValue(path)
                override suspend fun listBatch(project: String, paths: List<String>): List<FileDirectoryResult> = error("Unexpected batch")
            }, scope, idleMillis = 60_000,
        )
        runBlocking { tree.open("p")?.join() }
        expand.forEach { tree.setExpanded(it, true) }
        compose.setContent {
            view = LocalView.current
            around { ExplorerTree(tree, onOpenFile = {}, mutations = mutations) }
        }
        compose.waitForIdle()
    }

    @After fun closeTree() {
        if (::tree.isInitialized) tree.close()
        scope.cancel()
    }

    protected fun expanded() = tree.state.value.expandedPaths

    protected fun center(text: String): Offset = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.center

    /** A point of the list under its last row. */
    protected fun blank(): Offset = compose.onRoot().fetchSemanticsNode().boundsInRoot.let { Offset(it.center.x, it.bottom - 4f) }

    protected fun row(path: String, isDirectory: Boolean = false) = ExplorerRowDrag("p", path, isDirectory)

    protected fun dispatch(action: Int, drag: ExplorerRowDrag, at: Offset = Offset.Zero): Boolean {
        var result = false
        compose.runOnUiThread {
            val clip = explorerDragClip(drag)
            result = view!!.dispatchDragEvent(
                DragEventBuilder.newBuilder().setAction(action).setX(at.x).setY(at.y).setLocalState(drag)
                    .setClipDescription(clip.description).setClipData(clip).build(),
            )
        }
        // A write to state reaches what watches it (a `snapshotFlow`) on the main looper, which a clock held by hand does not run.
        flushStateWrites()
        return result
    }

    /** Android's order for a drag that begins in this window; answers whether the tree took part. */
    protected fun begin(drag: ExplorerRowDrag): Boolean {
        val accepted = dispatch(DragEvent.ACTION_DRAG_STARTED, drag)
        if (accepted) dispatch(DragEvent.ACTION_DRAG_ENTERED, drag)
        return accepted
    }

    protected fun over(drag: ExplorerRowDrag, at: Offset) {
        dispatch(DragEvent.ACTION_DRAG_LOCATION, drag, at)
        compose.waitForIdle()
    }

    /** Lets go at [at]: whether the tree took the drop. */
    protected fun drop(drag: ExplorerRowDrag, at: Offset): Boolean {
        val taken = dispatch(DragEvent.ACTION_DROP, drag, at)
        dispatch(DragEvent.ACTION_DRAG_ENDED, drag)
        compose.waitForIdle()
        return taken
    }

    protected fun advance(ms: Long) = compose.mainClock.advanceTimeBy(ms)

    /** A location with the frames after it run, because the effects that watch it only see it on a frame. */
    protected fun hover(drag: ExplorerRowDrag, at: Offset) {
        dispatch(DragEvent.ACTION_DRAG_LOCATION, drag, at)
        compose.mainClock.advanceTimeByFrame()
    }
}
