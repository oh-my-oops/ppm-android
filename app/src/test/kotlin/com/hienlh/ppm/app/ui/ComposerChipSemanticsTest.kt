package com.hienlh.ppm.app.ui

import android.app.Application
import android.content.ComponentName
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.state.PermissionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A composer chip as the web's `<button aria-label disabled>` is to a screen reader: one node with
 * the name, a button's role, its press and, for the model chip while a turn runs, disabled.
 *
 * Read the way a service reads it, through the compose view's `AccessibilityNodeProvider`, because
 * Compose's own semantics tree is right either way: it merges a clickable with its text and its
 * description into one node. The provider is what splits that into the clickable (pressed and
 * disabled, unnamed), the name as a child that is neither, and the text as another child, which is
 * what uiautomator showed on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerChipSemanticsTest {
    private val compose = createComposeRule()

    // The compose rule starts a `ComponentActivity`, which this module's manifest does not declare,
    // and starts it before a `@Before` runs.
    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(
            TestRule { base, _ ->
                object : Statement() {
                    override fun evaluate() {
                        val app = ApplicationProvider.getApplicationContext<Application>()
                        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                        base.evaluate()
                    }
                }
            },
        )
        .around(compose)

    /** A node as a service is given it, and what is under it. */
    private class Node(val info: AccessibilityNodeInfo, val children: List<Node>) {
        val name get() = info.contentDescription?.toString()
        val role get() = info.className?.toString()
    }

    /** What a service reads below the root, in order. */
    private fun screen(): List<Node> {
        val provider = (compose.onRoot().fetchSemanticsNode().root as View).accessibilityNodeProvider
        val childId = AccessibilityNodeInfo::class.java.getMethod("getChildId", Int::class.javaPrimitiveType)
        fun read(id: Int): Node {
            val info = provider.createAccessibilityNodeInfo(id)!!
            // The id of a child holds its virtual view's id in the upper half.
            return Node(info, (0 until info.childCount).map { read((childId.invoke(info, it) as Long ushr 32).toInt()) })
        }
        return read(AccessibilityNodeProvider.HOST_VIEW_ID).children
    }

    private fun assertOneButton(chip: Node, name: String) {
        assertEquals(name, chip.name)
        assertEquals("android.widget.Button", chip.role)
        assertTrue("clickable", chip.info.isClickable)
        // The name is not on a child of the node that presses, and the text is not a node of its own.
        assertTrue("children: ${chip.children.map { it.name ?: it.info.text }}", chip.children.isEmpty())
    }

    @Test
    fun `an idle model chip is one named button that a tap and an accessibility click both press`() {
        var presses = 0
        compose.setContent { ModelChip("Opus 4.7 · High", NAME, thinking = true, disabled = false) { presses++ } }
        compose.waitForIdle()

        val chip = screen().single()
        assertOneButton(chip, NAME)
        assertTrue("enabled", chip.info.isEnabled)

        compose.onNodeWithContentDescription(NAME).performClick()
        compose.onNodeWithContentDescription(NAME).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(2, presses)
    }

    @Test
    fun `while a turn runs the model chip is that same one node, disabled, and neither a tap nor an accessibility click presses it`() {
        var presses = 0
        compose.setContent { ModelChip("Opus 4.7 · High", NAME, thinking = false, disabled = true) { presses++ } }
        compose.waitForIdle()

        val chip = screen().single()
        assertOneButton(chip, NAME)
        assertFalse("enabled", chip.info.isEnabled)

        compose.onNodeWithContentDescription(NAME).performClick()
        compose.onNodeWithContentDescription(NAME).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(0, presses)
    }

    @Test
    fun `the mode chip, which the priority and provider chips are drawn as, is one named button with its label or without`() {
        var presses = 0
        compose.setContent {
            Column {
                // A phone draws the icon alone; a tablet the label too.
                ModeChip(PermissionMode.PLAN, narrow = true) { presses++ }
                ModeChip(PermissionMode.DEFAULT, narrow = false) { presses++ }
            }
        }
        compose.waitForIdle()

        val chips = screen()
        assertEquals(2, chips.size)
        assertOneButton(chips[0], modeChipName(PermissionMode.PLAN))
        assertOneButton(chips[1], modeChipName(PermissionMode.DEFAULT))
        assertTrue(chips.all { it.info.isEnabled })

        compose.onNodeWithContentDescription(modeChipName(PermissionMode.PLAN)).performClick()
        compose.onNodeWithContentDescription(modeChipName(PermissionMode.DEFAULT)).performClick()
        assertEquals(2, presses)
    }

    private companion object {
        val NAME = modelChipName("Opus 4.7", "high", thinking = true)
    }
}
