package com.hienlh.ppm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hienlh.ppm.app.ui.PpmRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = (application as PpmApplication).model(DebugLaunch.from(this, intent))
        enableEdgeToEdge()
        setContent { PpmRoot(model) }
    }
}
