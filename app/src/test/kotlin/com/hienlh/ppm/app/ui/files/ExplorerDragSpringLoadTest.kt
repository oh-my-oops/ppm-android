package com.hienlh.ppm.app.ui.files

import androidx.compose.ui.test.junit4.createComposeRule
import com.hienlh.ppm.app.withComposeActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The web's `springLoad`, on a clock held by hand: a drag that rests 800 ms on a shut folder asks for it to be opened. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExplorerDragSpringLoadTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val over = ExplorerDragOver()
    private val opened = mutableListOf<String>()
    private val drag = ExplorerRowDrag("p", "README.md", false)

    private fun show() {
        compose.setContent { ExplorerDragSpringLoad(over) { opened += it } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    /** The drag is over [key] from now on; [shut] is whether that is a folder that is shut. */
    private fun comeTo(key: String, shut: Boolean = true) {
        over.site = ExplorerDropSite(drag, key, key, shut)
        // The write reaches the `snapshotFlow` watching it on the main looper, which a clock held by hand does not run.
        flushStateWrites()
    }

    private fun leave() {
        over.site = null
        flushStateWrites()
    }

    /** Exactly [ms] of virtual time: the clock otherwise rounds up to whole frames of 16 ms, and 790 becomes 800. */
    private fun advance(ms: Long) = compose.mainClock.advanceTimeBy(ms, ignoreFrameDuration = true)

    @Test fun `a shut folder is asked to open once the drag has rested 800 ms on it`() {
        show()
        comeTo("docs")
        advance(790)
        assertEquals(emptyList<String>(), opened)
        advance(30)
        assertEquals(listOf("docs"), opened)
    }

    @Test fun `it is asked once, however long the drag stays`() {
        show()
        comeTo("docs")
        advance(5_000)
        assertEquals(listOf("docs"), opened)
    }

    @Test fun `a folder that is open, a file and the blank area are never asked`() {
        show()
        comeTo("src", shut = false)
        advance(2_000)
        comeTo("README.md", shut = false)
        advance(2_000)
        comeTo(ROOT_DROP_KEY, shut = false)
        advance(2_000)
        assertEquals(emptyList<String>(), opened)
    }

    @Test fun `a drag that leaves before the wait is over asks for nothing`() {
        show()
        comeTo("docs")
        advance(500)
        leave()
        advance(2_000)
        assertEquals(emptyList<String>(), opened)
    }

    @Test fun `moving to another shut folder starts the wait again`() {
        show()
        comeTo("docs")
        advance(500)
        comeTo("src/util")
        advance(500)
        assertEquals(emptyList<String>(), opened)
        advance(400)
        assertEquals(listOf("src/util"), opened)
    }

    @Test fun `moving on to a row that is not a shut folder cancels the wait`() {
        show()
        comeTo("docs")
        advance(500)
        comeTo("README.md", shut = false)
        advance(2_000)
        assertEquals(emptyList<String>(), opened)
    }

    @Test fun `the same folder again, a move within its row, does not start the wait over`() {
        show()
        comeTo("docs")
        advance(500)
        comeTo("docs")
        advance(400)
        assertEquals(listOf("docs"), opened)
    }
}
