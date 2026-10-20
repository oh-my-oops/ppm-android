package com.hienlh.ppm.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.hienlh.ppm.design.SyntaxHighlighter

/**
 * Holds the [AppModel] for the life of the process, so the chats stay connected while the
 * activity is recreated or the app sits in the background; tells it when the app comes to the
 * front and when it leaves.
 */
class PpmApplication : Application() {
    private var model: AppModel? = null

    /** The model, made on first use with the [launch] the first activity was started with. */
    fun model(launch: DebugLaunch?): AppModel = model ?: AppModel(this, launch).also { model = it }

    override fun onCreate() {
        super.onCreate()
        SyntaxHighlighter.get(this).warm()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val model = model ?: return
                model.windowVisible = true
                model.becameActive()
            }

            override fun onStop(owner: LifecycleOwner) {
                model?.windowVisible = false
            }
        })
    }
}
