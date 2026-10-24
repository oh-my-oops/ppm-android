package com.hienlh.ppm.design

import androidx.compose.ui.input.pointer.PointerIcon

/**
 * The pointers Chrome on Android draws for the CSS cursors the web uses, measured with a mouse on
 * the tablet emulator: Chrome maps each onto one of Android's own system icons, so these are the
 * web's cursors exactly. The arrow every `<button>` gets (`cursor: default`) needs no icon at all,
 * except inside a box that sets another one.
 */
object WebCursor {
    /** `cursor-pointer`. */
    val Pointer = PointerIcon.Hand

    /** `cursor-text`; a text field has it already. */
    val Text = PointerIcon.Text

    /** A `<button>` inside a box with a cursor of its own. */
    val Default = PointerIcon.Default

    /** `cursor-grab`. */
    val Grab = PointerIcon(android.view.PointerIcon.TYPE_GRAB)

    /** `active:cursor-grabbing`, and anything a mouse is dragging. */
    val Grabbing = PointerIcon(android.view.PointerIcon.TYPE_GRABBING)

    /** `cursor-zoom-in`, which Chromium hands Android as its own zoom-in icon. */
    val ZoomIn = PointerIcon(android.view.PointerIcon.TYPE_ZOOM_IN)

    /** `cursor-wait`. */
    val Wait = PointerIcon(android.view.PointerIcon.TYPE_WAIT)

    /** `cursor-help`. */
    val Help = PointerIcon(android.view.PointerIcon.TYPE_HELP)

    /** `cursor-not-allowed`: Chrome's hand with a no-entry sign. */
    val NotAllowed = PointerIcon(android.view.PointerIcon.TYPE_NO_DROP)

    /** `col-resize`, `ew-resize`, `e-resize` and `w-resize`: one double arrow for all four. */
    val ResizeHorizontal = PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW)

    /** `row-resize`, `ns-resize`, `n-resize` and `s-resize`. */
    val ResizeVertical = PointerIcon(android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW)

    /** `nw-resize` and `se-resize`. */
    val ResizeTopLeft = PointerIcon(android.view.PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW)

    /** `ne-resize` and `sw-resize`. */
    val ResizeTopRight = PointerIcon(android.view.PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW)
}
