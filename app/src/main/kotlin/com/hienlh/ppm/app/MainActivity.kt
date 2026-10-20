package com.hienlh.ppm.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hienlh.ppm.app.ui.PpmRoot

class MainActivity : ComponentActivity() {
    private lateinit var shortcuts: KeyboardShortcuts

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = (application as PpmApplication).model(DebugLaunch.from(this, intent))
        shortcuts = KeyboardShortcuts(model)
        enableEdgeToEdge()
        setContent { PpmRoot(model) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = shortcuts.dispatch(event) || super.dispatchKeyEvent(event)

    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data += shortcuts.groups()
    }
}
