package com.hienlh.ppm.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hienlh.ppm.design.*
import com.hienlh.ppm.state.media.MediaPlaybackState

@Composable
internal fun MediaButton(icon: ProductIcon, label: String, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    val colors = LocalPpmTokens.current
    Box(Modifier.size(44.dp).background(if (selected) colors.accentWash.color else androidx.compose.ui.graphics.Color.Transparent)
        .semantics { contentDescription = label }
        .ppmClickable(enabled = enabled, onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        PpmIcon(icon, 20.dp, if (!enabled) colors.text3.color else if (selected) colors.accent.color else colors.text2.color)
    }
}

@Composable
internal fun MediaPlayerControls(
    state: MediaPlaybackState, position: Double, video: Boolean, speed: Float, volume: MediaVolume,
    transform: VideoTransform, fullscreen: Boolean,
    onAction: (MediaAction) -> Unit, onVolume: (Float) -> Unit, onSpeed: (Float) -> Unit,
    onTransform: (VideoTransform) -> Unit, onScrub: (Double) -> Unit, onSeek: () -> Unit,
) {
    val colors = LocalPpmTokens.current
    var settings by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val showVolume = maxWidth >= 640.dp
        Row(Modifier.fillMaxWidth().background(colors.panel.color).cssBorder(colors.border.color, sides = CssSides.Top)
            .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MediaButton(if (state.playWhenReady) ProductIcon.Pause else ProductIcon.Play,
                if (state.playWhenReady) "Pause" else "Play", enabled = state.playback != null) { onAction(MediaAction.Play) }
            BasicText(mediaClock(position) + (state.durationSeconds?.let { " / ${mediaClock(it)}" } ?: ""),
                modifier = Modifier.widthIn(min = 72.dp), style = ppmText(12f).copy(color = colors.text2.color, fontFeatureSettings = "tnum"))
            val duration = state.durationSeconds
            if (duration != null && duration > 0) MediaSlider("Seek", mediaSeekBar(position, duration), duration, 0.1,
                Modifier.weight(1f), state.bufferedSeconds, state.playback != null, onScrub, onSeek)
            else Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MediaButton(if (volume.effective == 0f) ProductIcon.VolumeX else if (volume.level < 0.5f) ProductIcon.Volume1 else ProductIcon.Volume2,
                    if (volume.muted) "Unmute" else "Mute") { onAction(MediaAction.Mute) }
                if (showVolume) MediaSlider("Volume", volume.effective.toDouble(), 1.0, 0.05, Modifier.width(80.dp), onChange = { onVolume(it.toFloat()) })
            }
            MediaButton(ProductIcon.Settings, "Playback settings") { settings = !settings }
            if (video) MediaButton(if (fullscreen) ProductIcon.Minimize else ProductIcon.Maximize,
                if (fullscreen) "Exit fullscreen" else "Fullscreen") { onAction(MediaAction.Fullscreen) }
        }
    }
    if (settings) MediaSettings(video, speed, volume, transform, onSpeed, onVolume, onTransform) { settings = false }
}

@Composable
private fun MediaSettings(video: Boolean, speed: Float, volume: MediaVolume, transform: VideoTransform,
                          onSpeed: (Float) -> Unit, onVolume: (Float) -> Unit, onTransform: (VideoTransform) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalPpmTokens.current
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.width(264.dp).heightIn(max = maxHeight * 0.7f).background(colors.panel.color)
                .cssBorder(colors.border.color).cssScrollY(rememberScrollState()).padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("Playback settings", Modifier.weight(1f), style = ppmText(14f, FontWeight.Medium).copy(color = colors.text.color))
                    MediaButton(ProductIcon.X, "Close settings", onClick = onDismiss)
                }
                BasicText("Speed", style = ppmText(12f).copy(color = colors.text2.color))
                mediaSpeeds.chunked(4).forEach { speeds ->
                    Row(Modifier.fillMaxWidth()) { speeds.forEach { rate ->
                        Box(Modifier.weight(1f).height(44.dp).background(if (speed == rate) colors.accentWash.color else colors.panel.color)
                            .ppmClickable(onClickLabel = "Speed ${rate}×", onClick = { onSpeed(rate) }), contentAlignment = Alignment.Center) {
                            BasicText("${rate.toString().removeSuffix(".0")}×", style = ppmText(12f).copy(color = if (speed == rate) colors.accent.color else colors.text.color))
                        }
                    } }
                }
                BasicText("Volume", style = ppmText(12f).copy(color = colors.text2.color))
                MediaSlider("Volume", volume.effective.toDouble(), 1.0, 0.05, Modifier.fillMaxWidth(), onChange = { onVolume(it.toFloat()) })
                if (video) {
                    BasicText("Orientation", style = ppmText(12f).copy(color = colors.text2.color))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        MediaButton(ProductIcon.RotateCcw, "Rotate left") { onTransform(transform.rotate(-1)) }
                        MediaButton(ProductIcon.RotateCw, "Rotate right") { onTransform(transform.rotate(1)) }
                        MediaButton(ProductIcon.FlipHorizontal, "Flip horizontally", selected = transform.flipH) { onTransform(transform.copy(flipH = !transform.flipH)) }
                        MediaButton(ProductIcon.FlipVertical, "Flip vertically", selected = transform.flipV) { onTransform(transform.copy(flipV = !transform.flipV)) }
                    }
                    if (!transform.identity) BasicText("Reset orientation", Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        .ppmClickable(onClick = { onTransform(VideoTransform()) }).padding(vertical = 12.dp), style = ppmText(12f).copy(color = colors.text.color))
                }
                BasicText("Keyboard shortcuts", Modifier.padding(top = 8.dp, bottom = 4.dp), style = ppmText(12f).copy(color = colors.text2.color))
                val shortcuts = listOf("Space / K" to "Play / pause", "← / →" to "Seek 5s", "J / L, Shift+← / →" to "Seek 10s",
                    "↑ / ↓" to "Volume", "M" to "Mute", "< / >" to "Speed") + if (video) listOf("F" to "Fullscreen", "R / Shift+R" to "Rotate") else emptyList()
                shortcuts.forEach { (keys, action) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        BasicText(keys, style = ppmText(11f).copy(color = colors.text3.color))
                        BasicText(action, style = ppmText(11f).copy(color = colors.text2.color))
                    }
                }
            }
        }
    }
}
