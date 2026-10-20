package com.hienlh.ppm.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hienlh.ppm.app.ui.PpmRoot

class MainActivity : ComponentActivity() {
    private lateinit var model: AppModel
    private lateinit var shortcuts: KeyboardShortcuts

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = (application as PpmApplication).model(DebugLaunch.from(this, intent))
        shortcuts = KeyboardShortcuts(model)
        enableEdgeToEdge()
        setContent { PpmRoot(model) }
        // Recreated after the process went: its link was opened then.
        if (savedInstanceState == null) openLink(intent)
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = shortcuts.dispatch(event) || super.dispatchKeyEvent(event)

    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data += shortcuts.groups()
    }
}
