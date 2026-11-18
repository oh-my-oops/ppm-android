package com.hienlh.ppm.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hienlh.ppm.app.ui.PpmRoot

open class MainActivity : ComponentActivity() {
    protected lateinit var model: AppModel
    private lateinit var shortcuts: KeyboardShortcuts

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = createModel(application as PpmApplication)
        shortcuts = KeyboardShortcuts(model)
        enableEdgeToEdge()
        setContent { PpmRoot(model, beforeIme = shortcuts::beforeIme) }
        // Recreated after the process went: its link was opened then.
        if (savedInstanceState == null) openLink(intent)
    }

    protected open fun createModel(app: PpmApplication): AppModel = app.model(DebugLaunch.from(this, intent))

    override fun onStart() {
        super.onStart()
        model.windowVisible = true
        model.store.refresh()
        model.becameActive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::model.isInitialized) model.refreshProjectRegistrations()
    }

    override fun onStop() {
        model.windowVisible = false
        super.onStop()
    }

    /** A `ppm://` link while the app runs (the activity is single in its task). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openLink(intent)
    }

    private fun openLink(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.dataString?.let(model::openLink)
    }

    // Chrome's activation-triggering input (TextInputFocus.activated): a finger as it lifts, a
    // mouse as it presses, and a key the page hears, Escape aside.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val mouse = event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
        if (event.actionMasked == if (mouse) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP) TextInputFocus.activate()
        if (event.actionMasked == MotionEvent.ACTION_DOWN && ::model.isInitialized) model.notePresenceInput()
        return super.dispatchTouchEvent(event)
    }

    // A mouse wheel or a trackpad scroll is someone at this device too (`wheel`).
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL && ::model.isInitialized) model.notePresenceInput()
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !event.isSystem && event.keyCode != KeyEvent.KEYCODE_ESCAPE) TextInputFocus.activate()
        if (event.action == KeyEvent.ACTION_DOWN && !event.isSystem && ::model.isInitialized) model.notePresenceInput()
        return shortcuts.dispatch(event, nativeEditor = currentFocus?.onCheckIsTextEditor() == true) || super.dispatchKeyEvent(event)
    }

    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data += shortcuts.groups()
    }
}
