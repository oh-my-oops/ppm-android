package com.hienlh.ppm.app

import android.view.KeyEvent

/**
 * Whether Ctrl or Alt is down, for a drop to read: the web copies instead of moving when either is (`resolveDropOperation`, read
 * again at the drop, because the user may change their mind mid-drag) off the drop event, and Android's `DragEvent` carries no
 * modifier state. So it is kept from the key events the window sees ([KeyboardShortcuts.dispatch], which sees each one before any
 * view) and from the press that starts a drag. Every key event carries the meta state as it stands, so a key-up that went to another
 * window corrects itself with the next key.
 */
internal object DragModifierKeys {
    private var ctrl = false
    private var alt = false

    /** The web's `ctrlKey || altKey`: Alt is macOS's own copy key. */
    val copy: Boolean get() = ctrl || alt

    fun update(ctrl: Boolean, alt: Boolean) {
        this.ctrl = ctrl
        this.alt = alt
    }

    fun track(event: KeyEvent) = update(ctrl = event.isCtrlPressed, alt = event.isAltPressed)
}
