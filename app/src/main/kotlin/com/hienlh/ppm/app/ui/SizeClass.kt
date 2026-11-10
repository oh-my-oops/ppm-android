package com.hienlh.ppm.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Window size class that chooses between the phone shell and the tablet shell.
 *
 * Per Foundation (Section 5 & Note 34):
 * - Under [BREAKPOINT_DP] (600 dp) width is [Phone]; from 600 dp up is [Tablet].
 * - When window height is also given and is compact (under [COMPACT_HEIGHT_DP] = 480 dp, e.g. a
 *   phone rotated to landscape 915 x 412), the window stays on [Phone] so a 32 dp tab bar + 24 dp
 *   status bar + 220 dp dock do not crush the viewport.
 */
sealed interface SizeClass {
    data object Phone : SizeClass
    data object Tablet : SizeClass

    val isPhone: Boolean get() = this == Phone
    val isTablet: Boolean get() = this == Tablet

    companion object {
        const val BREAKPOINT_DP: Int = 600
        const val EXPANDED_BREAKPOINT_DP: Int = 840
        const val COMPACT_HEIGHT_DP: Int = 480

        /** Selects [Phone] below 600 dp and [Tablet] from 600 dp up. */
        fun of(widthDp: Int): SizeClass = if (widthDp < BREAKPOINT_DP) Phone else Tablet

        /** Selects [Phone] below 600 dp and [Tablet] from 600 dp up. */
        fun of(widthDp: Float): SizeClass = if (widthDp < BREAKPOINT_DP) Phone else Tablet

        /** Selects [Phone] below 600 dp and [Tablet] from 600 dp up. */
        fun of(width: Dp): SizeClass = if (width < BREAKPOINT_DP.dp) Phone else Tablet

        /**
         * Selects [Phone] when width is below 600 dp or height is compact (below 480 dp), and
         * [Tablet] otherwise.
         */
        fun of(widthDp: Int, heightDp: Int): SizeClass =
            if (widthDp < BREAKPOINT_DP || heightDp < COMPACT_HEIGHT_DP) Phone else Tablet

        /**
         * Selects [Phone] when width is below 600 dp or height is compact (below 480 dp), and
         * [Tablet] otherwise.
         */
        fun of(width: Dp, height: Dp): SizeClass =
            if (width < BREAKPOINT_DP.dp || height < COMPACT_HEIGHT_DP.dp) Phone else Tablet

        /** Whether the tablet window is wide enough (>= 840 dp) to dock the 260 dp sidebar inline. */
        fun isExpandedTablet(widthDp: Int): Boolean = widthDp >= EXPANDED_BREAKPOINT_DP

        /** Whether the tablet window is wide enough (>= 840 dp) to dock the 260 dp sidebar inline. */
        fun isExpandedTablet(width: Dp): Boolean = width >= EXPANDED_BREAKPOINT_DP.dp
    }
}

/** Current window [SizeClass] provided by [PpmRoot]. */
val LocalSizeClass = staticCompositionLocalOf<SizeClass> { SizeClass.Phone }

/**
 * Holds scroll positions keyed by tab id above the [PhoneShell] / [TabletShell] branch in [PpmRoot]
 * so crossing the 600 dp breakpoint (rotation, split-screen, foldable unfold) preserves the scroll
 * position of the active screen (Requirement 1.2).
 */
@Stable
class TabScrollStore(
    initialOffsets: Map<String, Int> = emptyMap(),
    initialLazyPositions: Map<String, Pair<Int, Int>> = emptyMap(),
) {
    private val offsets = initialOffsets.toMutableMap()
    private val lazyPositions = initialLazyPositions.toMutableMap()

    fun scrollOffset(tabId: String): Int = offsets[tabId] ?: 0

    fun saveScrollOffset(tabId: String, offset: Int) {
        if (tabId.isNotEmpty()) offsets[tabId] = offset.coerceAtLeast(0)
    }

    fun lazyPosition(tabId: String): Pair<Int, Int> = lazyPositions[tabId] ?: (0 to 0)

    fun saveLazyPosition(tabId: String, firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
        if (tabId.isNotEmpty()) {
            lazyPositions[tabId] = firstVisibleItemIndex.coerceAtLeast(0) to firstVisibleItemScrollOffset.coerceAtLeast(0)
        }
    }

    fun snapshotOffsets(): Map<String, Int> = offsets.toMap()

    fun snapshotLazyPositions(): Map<String, Pair<Int, Int>> = lazyPositions.toMap()

    companion object {
        val Saver: Saver<TabScrollStore, ArrayList<String>> = Saver(
            save = { store ->
                val list = ArrayList<String>()
                for ((k, v) in store.offsets) {
                    list.add("s:$k=$v")
                }
                for ((k, v) in store.lazyPositions) {
                    list.add("l:$k=${v.first},${v.second}")
                }
                list
            },
            restore = { saved ->
                val offsets = mutableMapOf<String, Int>()
                val lazy = mutableMapOf<String, Pair<Int, Int>>()
                for (entry in saved) {
                    when {
                        entry.startsWith("s:") -> {
                            val body = entry.substring(2)
                            val eq = body.lastIndexOf('=')
                            if (eq > 0) {
                                val key = body.substring(0, eq)
                                val value = body.substring(eq + 1).toIntOrNull() ?: 0
                                offsets[key] = value
                            }
                        }
                        entry.startsWith("l:") -> {
                            val body = entry.substring(2)
                            val eq = body.lastIndexOf('=')
                            if (eq > 0) {
                                val key = body.substring(0, eq)
                                val parts = body.substring(eq + 1).split(',')
                                if (parts.size == 2) {
                                    val idx = parts[0].toIntOrNull() ?: 0
                                    val off = parts[1].toIntOrNull() ?: 0
                                    lazy[key] = idx to off
                                }
                            }
                        }
                    }
                }
                TabScrollStore(offsets, lazy)
            },
        )
    }
}

val LocalTabScrollStore = staticCompositionLocalOf { TabScrollStore() }

@Composable
fun rememberTabScrollStore(): TabScrollStore =
    rememberSaveable(saver = TabScrollStore.Saver) { TabScrollStore() }

/**
 * Remembers a [ScrollState] keyed by [tabId] that survives crossing the 600 dp [SizeClass]
 * boundary as well as activity recreation.
 */
@Composable
fun rememberTabScrollState(tabId: String): ScrollState {
    val store = LocalTabScrollStore.current
    val state = rememberSaveable(tabId, saver = ScrollState.Saver) {
        ScrollState(initial = store.scrollOffset(tabId))
    }
    DisposableEffect(store, tabId, state) {
        onDispose {
            store.saveScrollOffset(tabId, state.value)
        }
    }
    return state
}

/**
 * Remembers a [LazyListState] keyed by [tabId] that survives crossing the 600 dp [SizeClass]
 * boundary as well as activity recreation.
 */
@Composable
fun rememberTabLazyListState(tabId: String): LazyListState {
    val store = LocalTabScrollStore.current
    val initial = store.lazyPosition(tabId)
    val state = rememberSaveable(tabId, saver = LazyListState.Saver) {
        LazyListState(
            firstVisibleItemIndex = initial.first,
            firstVisibleItemScrollOffset = initial.second,
        )
    }
    DisposableEffect(store, tabId, state) {
        onDispose {
            store.saveLazyPosition(tabId, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
        }
    }
    return state
}
