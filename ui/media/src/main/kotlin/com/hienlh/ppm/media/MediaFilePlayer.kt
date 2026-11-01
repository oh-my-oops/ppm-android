package com.hienlh.ppm.media

import android.view.KeyEvent as AndroidKey
import android.view.TextureView
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.*
import com.hienlh.ppm.state.media.*
import kotlinx.coroutines.delay

/** One visible file owner. No media is opened until visible AND the host lifecycle is started. */
@Composable
fun MediaFilePlayer(
    api: ApiClient,
    source: MediaSource,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onDownload: (() -> Unit)? = null,
) {
    key(api, source) { MediaOwner(api, source, modifier, visible, onDownload) }
}

@Composable
private fun MediaOwner(api: ApiClient, source: MediaSource, modifier: Modifier, visible: Boolean, onDownload: (() -> Unit)?) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller = remember { MediaPlaybackController(api, scope).apply { setSource(source) } }
    val native = remember { NativeMediaPlayer(context, api, controller, scope) }
    val state by controller.state.collectAsState()
    var speed by remember { mutableFloatStateOf(1f) }
    var volume by remember { mutableStateOf(MediaVolume()) }
    var transform by remember { mutableStateOf(VideoTransform()) }
    var fullscreen by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Double?>(null) }
    var resumeAfterScrub by remember { mutableStateOf(false) }
    val video = source.kind == MediaKind.VIDEO
    LaunchedEffect(state.mode) { transform = VideoTransform() }

    val visibilityOwner = remember { object : MediaVisibilityOwner {
        override fun foreground(value: Boolean) {
            if (!value) native.suspendPlayback()
            controller.setForeground(value)
        }
        override fun show() = controller.show()
        override fun hide() { native.suspendPlayback(); controller.hide() }
        override fun close() { native.close(); controller.close() }
    } }
    MediaVisibility(visibilityOwner, lifecycle, visible)
    LaunchedEffect(controller) { controller.state.collect { native.update(it) } }
    SideEffect { native.settings(speed, volume.effective) }
    // Leaving while dragging must not turn a temporary native scrub pause into the next tab's state.
    LaunchedEffect(state.active) {
        if (!state.active) {
            if (resumeAfterScrub) controller.setPlayWhenReady(true)
            resumeAfterScrub = false; scrub = null
        }
    }
    val action: (MediaAction) -> Unit = { command ->
        when (command) {
            MediaAction.Play -> {
                val current = controller.state.value
                if (!current.playWhenReady && current.durationSeconds?.let { current.positionSeconds >= it - 0.05 } == true) native.seek(0.0)
                controller.setPlayWhenReady(!current.playWhenReady)
            }
            MediaAction.Mute -> volume = volume.copy(muted = !volume.muted)
            MediaAction.Fullscreen -> if (video) fullscreen = !fullscreen
            is MediaAction.Seek -> native.seek(controller.state.value.positionSeconds + command.seconds)
            is MediaAction.Volume -> volume = volume.by(command.delta)
            is MediaAction.Speed -> speed = stepMediaSpeed(speed, command.direction)
            is MediaAction.Rotate -> if (video) transform = transform.rotate(command.direction)
        }
    }
    val onScrub: (Double) -> Unit = { position ->
        if (scrub == null && state.mode == MediaMode.NATIVE) {
            resumeAfterScrub = controller.state.value.playWhenReady
            controller.setPlayWhenReady(false)
            native.update(controller.state.value)
        }
        scrub = position
        if (state.mode == MediaMode.NATIVE) native.seek(position)
    }
    val onSeek: () -> Unit = {
        scrub?.let(native::seek)
        scrub = null
        if (resumeAfterScrub) controller.setPlayWhenReady(true)
        resumeAfterScrub = false
    }
    val content: @Composable (Modifier) -> Unit = { area ->
        MediaContent(source, native, state, area, video, speed, volume, transform, fullscreen, scrub,
            action, { volume = volume.slider(it) }, { speed = it }, { transform = it }, onScrub, onSeek,
            { controller.retry() }, onDownload)
    }
    Box(modifier) {
        if (!fullscreen) content(Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize().background(Color.Black))
    }
    if (fullscreen) Dialog(onDismissRequest = { fullscreen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.let {
                if (Build.VERSION.SDK_INT >= 30) {
                    it.setDecorFitsSystemWindows(false)
                    it.insetsController?.apply {
                        systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(WindowInsets.Type.systemBars())
                    }
                } else {
                    @Suppress("DEPRECATION")
                    it.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                }
            }
            onDispose { }
        }
        content(Modifier.fillMaxSize())
    }
}

@Composable
private fun MediaContent(
    source: MediaSource, native: NativeMediaPlayer, state: MediaPlaybackState, modifier: Modifier,
    video: Boolean, speed: Float, volume: MediaVolume, transform: VideoTransform, fullscreen: Boolean, scrub: Double?,
    onAction: (MediaAction) -> Unit, onVolume: (Float) -> Unit, onSpeed: (Float) -> Unit, onTransform: (VideoTransform) -> Unit,
    onScrub: (Double) -> Unit, onSeek: () -> Unit, onRetry: () -> Unit, onDownload: (() -> Unit)?,
) {
    val colors = LocalPpmTokens.current
    val focus = remember { FocusRequester() }
    val action by rememberUpdatedState(onAction)
    var waiting by remember { mutableStateOf(false) }
    LaunchedEffect(state.waiting) { waiting = false; if (state.waiting) { delay(300); waiting = true } }
    Column(modifier.background(if (video) Color.Black else colors.panel.color).clipToBounds()
        .focusRequester(focus).onKeyEvent { event ->
            val command = keyAction(event)
            if (command == null || event.type != KeyEventType.KeyDown) false else { action(command); true }
        }.focusable()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
            if (video) {
                val (w, h) = transform.fittedSize(maxWidth.value, maxHeight.value, native.aspect)
                AndroidView(factory = { TextureView(it).apply { native.attach(this) } },
                    modifier = Modifier.requiredSize(w.dp, h.dp).graphicsLayer {
                        rotationZ = transform.rotation.toFloat()
                        scaleX = if (transform.flipH) -1f else 1f
                        scaleY = if (transform.flipV) -1f else 1f
                    }, update = { native.attach(it) }, onRelease = { native.detach(it) })
                Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures(onTap = { focus.requestFocus(); action(MediaAction.Play) },
                        onDoubleTap = { focus.requestFocus(); action(MediaAction.Fullscreen) })
                })
            } else Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PpmIcon(ProductIcon.Music, 48.dp, colors.text3.color)
                BasicText(native.title ?: source.path.substringAfterLast('/').substringAfterLast('\\'), style = ppmText(14f).copy(color = colors.text.color))
                native.artist?.let { BasicText(it, style = ppmText(12f).copy(color = colors.text2.color)) }
            }
            if (waiting && state.error == null) PpmIcon(ProductIcon.Loader2, 28.dp, if (video) Color.White else colors.text2.color, spinning = true)
            if (state.error != null) Column(Modifier.widthIn(max = 480.dp).background(colors.panel.color).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(state.error!!.message, style = ppmText(14f).copy(color = colors.text.color))
                state.probe?.let { probe ->
                    val description = listOfNotNull(probe.container, probe.video?.codec, probe.audio?.codec).joinToString(" · ")
                    if (description.isNotEmpty()) BasicText(description, style = ppmText(12f).copy(color = colors.text2.color))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MediaButton(ProductIcon.RotateCw, "Retry", onClick = onRetry)
                    if (onDownload != null) MediaButton(ProductIcon.Download, "Download", onClick = onDownload)
                }
            }
        }
        MediaPlayerControls(state, scrub ?: state.positionSeconds, video, speed, volume, transform, fullscreen,
            onAction, onVolume, onSpeed, onTransform, onScrub, onSeek)
    }
}

private fun keyAction(event: KeyEvent): MediaAction? {
    val native = event.nativeKeyEvent
    val key = when (native.keyCode) {
        AndroidKey.KEYCODE_DPAD_LEFT -> "ArrowLeft"
        AndroidKey.KEYCODE_DPAD_RIGHT -> "ArrowRight"
        AndroidKey.KEYCODE_DPAD_UP -> "ArrowUp"
        AndroidKey.KEYCODE_DPAD_DOWN -> "ArrowDown"
        else -> native.unicodeChar.takeIf { it != 0 }?.toChar()?.toString() ?: ""
    }
    return mediaShortcut(key, native.isShiftPressed, native.isCtrlPressed || native.isAltPressed || native.isMetaPressed)
}
