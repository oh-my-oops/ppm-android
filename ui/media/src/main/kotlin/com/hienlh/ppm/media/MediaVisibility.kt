package com.hienlh.ppm.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

internal interface MediaVisibilityOwner : AutoCloseable {
    fun foreground(value: Boolean)
    fun show()
    fun hide()
}

/** Visibility and activity lifecycle are independent gates, including retained minimized windows. */
@Composable
internal fun MediaVisibility(owner: MediaVisibilityOwner, lifecycle: Lifecycle, visible: Boolean) {
    // Effects dispose in reverse order: unregister and hide before releasing the owner.
    // Replacing only LifecycleOwner must not close the retained player.
    DisposableEffect(owner) { onDispose { owner.close() } }
    DisposableEffect(owner, lifecycle) {
        owner.foreground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) owner.foreground(true)
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) owner.foreground(false)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); owner.foreground(false) }
    }
    DisposableEffect(owner, visible) {
        if (visible) owner.show() else owner.hide()
        onDispose { owner.hide() }
    }
}
