package com.hienlh.ppm.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose effect/lifecycle ordering; transport DELETE and callback epochs have core wire tests. */
@RunWith(AndroidJUnit4::class)
class MediaVisibilityInstrumentation {
    @get:Rule val compose = createComposeRule()

    @Test fun retainedHiddenWindowReleasesAndCannotRestartOnActivityResume() {
        val player = RecordingOwner()
        lateinit var host: TestLifecycle
        var visible by mutableStateOf(false)
        compose.runOnUiThread { host = TestLifecycle(Lifecycle.State.RESUMED) }
        compose.setContent { MediaVisibility(player, host.lifecycle, visible) }
        compose.runOnIdle { assertEquals(0, player.starts); visible = true }
        compose.runOnIdle { assertTrue(player.active); assertEquals(1, player.starts); visible = false }
        compose.runOnIdle { assertFalse(player.active); assertEquals(1, player.releases); host.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { host.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertFalse(player.active); assertEquals(1, player.starts); visible = true }
        compose.runOnIdle { assertTrue(player.active); assertEquals(2, player.starts) }
    }

    @Test fun backgroundGatePreventsInitialReadsAndDisposalRemovesObserver() {
        val player = RecordingOwner()
        lateinit var host: TestLifecycle
        var mounted by mutableStateOf(true)
        compose.runOnUiThread { host = TestLifecycle(Lifecycle.State.CREATED) }
        compose.setContent { if (mounted) MediaVisibility(player, host.lifecycle, true) }
        compose.runOnIdle { assertEquals(0, player.starts); host.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertTrue(player.active); host.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { assertFalse(player.active); assertEquals(1, player.releases); mounted = false }
        compose.runOnIdle { assertEquals(1, player.closes); host.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertFalse(player.active); assertEquals(1, player.starts); assertEquals(0, player.callsAfterClose) }
    }

    @Test fun replacingLifecycleRebindsGateWithoutClosingRetainedPlayer() {
        val player = RecordingOwner()
        lateinit var first: TestLifecycle
        lateinit var second: TestLifecycle
        var changed by mutableStateOf(false)
        compose.runOnUiThread {
            first = TestLifecycle(Lifecycle.State.RESUMED)
            second = TestLifecycle(Lifecycle.State.CREATED)
        }
        compose.setContent { MediaVisibility(player, if (changed) second.lifecycle else first.lifecycle, true) }
        compose.runOnIdle { assertTrue(player.active); changed = true }
        compose.runOnIdle { assertFalse(player.active); assertEquals(0, player.closes); first.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { first.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertFalse(player.active); second.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertTrue(player.active); assertEquals(0, player.closes) }
    }

    private class TestLifecycle(state: Lifecycle.State) : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = state }
        override val lifecycle: Lifecycle get() = registry
    }

    private class RecordingOwner : MediaVisibilityOwner {
        private var visible = false
        private var foreground = false
        var active = false
        var starts = 0
        var releases = 0
        var closes = 0
        var callsAfterClose = 0
        override fun foreground(value: Boolean) { foreground = value; update() }
        override fun show() { visible = true; update() }
        override fun hide() { visible = false; update() }
        override fun close() { visible = false; update(); closes++ }
        private fun update() {
            if (closes > 0) { callsAfterClose++; return }
            val next = visible && foreground
            if (next && !active) starts++
            if (!next && active) releases++
            active = next
        }
    }
}
