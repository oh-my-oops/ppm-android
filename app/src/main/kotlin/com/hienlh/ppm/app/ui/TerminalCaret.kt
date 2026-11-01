package com.hienlh.ppm.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates

/**
 * Where a terminal's cursor is, and where its row of keys is, for the keyboard's pan ([com.hienlh.ppm.app.TextInputFocus.takeNative]).
 *
 * xterm's textarea stays on the cursor cell for as long as the terminal lives and moves with the cursor, so Chrome has one place to bring into
 * view. The engine says null for a view scrolled back and for a hidden cursor; this goes on with the last cell it was told, as the textarea does.
 * It is one object for the whole life of a session, so what [com.hienlh.ppm.app.TextInputFocus] was given when the terminal took the focus is
 * still right after the engine has been replaced under the view.
 *
 * Both answers read [layout], which goes up whenever the terminal or its keys are laid out again: a pan that follows them is asked again when
 * the terminal moves in the page without the cursor having moved in the terminal (the dock's splitter dragged, a window moved).
 */
internal class TerminalCaret {
    /** The cursor cell in the terminal's own pixels. */
    var cell by mutableStateOf<Rect?>(null)
        private set

    private var layout by mutableIntStateOf(0)
    private var terminal: LayoutCoordinates? = null
    private var keys: LayoutCoordinates? = null

    /** The engine's report: a cell, or null for none just now, which leaves the last one where it is. */
    fun cursorMoved(bounds: Rect?) {
        if (bounds != null) cell = bounds
    }

    fun terminalPlaced(coordinates: LayoutCoordinates) {
        terminal = coordinates
        layout++
    }

    fun keysPlaced(coordinates: LayoutCoordinates) {
        keys = coordinates
        layout++
    }

    /** The cursor cell in [page], null before any was reported, and where the terminal is not in that page (another window's). */
    fun cursorIn(page: LayoutCoordinates): Rect? {
        observeLayout()
        val field = terminal?.takeIf { sameRoot(it, page) } ?: return null
        val cell = cell ?: return null
        return Rect(page.localPositionOf(field, cell.topLeft), page.localPositionOf(field, cell.bottomRight))
    }

    /** The row of keys in [page], null where it is not laid out or not in that page. */
    fun keysIn(page: LayoutCoordinates): Rect? {
        observeLayout()
        val row = keys?.takeIf { sameRoot(it, page) } ?: return null
        return page.localBoundingBoxOf(row, clipBounds = false)
    }

    /** Reading the count is what makes the caller, a `snapshotFlow` in the page's pan, ask again when it changes. */
    private fun observeLayout() {
        @Suppress("UNUSED_EXPRESSION")
        layout
    }

    private fun sameRoot(placed: LayoutCoordinates, page: LayoutCoordinates) =
        placed.isAttached && page.isAttached && placed.findRootCoordinates() == page.findRootCoordinates()
}
