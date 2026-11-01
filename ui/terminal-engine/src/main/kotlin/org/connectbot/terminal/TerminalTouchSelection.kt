package org.connectbot.terminal

/**
 * Select mode, as the web's `useTerminalTouchSelection` has it: while it is on, a one-finger drag is a selection, as a
 * mouse drag is in xterm, instead of a scroll. The web ignores a second finger (`e.touches.length > 1`), so does this.
 */
internal fun dragSelects(touchSelect: Boolean, singleTouch: Boolean): Boolean = touchSelect && singleTouch

/**
 * The first half of that selection: the cell the finger came down on, as a character selection. What was selected
 * is replaced whatever mode it was in, as xterm's `mousedown` replaces it; the drag then extends this one with
 * [SelectionManager.updateSelection], and lifting the finger ends it with [SelectionManager.endSelection].
 */
internal fun SelectionManager.beginDragSelection(row: Int, col: Int, snapshot: TerminalSnapshot, scrollbackPosition: Int) =
    startSelection(row, col, snapshot.cols, SelectionMode.CHARACTER, snapshot, scrollbackPosition)
