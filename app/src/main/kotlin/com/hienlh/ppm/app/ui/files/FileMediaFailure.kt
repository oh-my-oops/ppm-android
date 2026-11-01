package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.ShadowXs
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssHeight
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.state.media.AUDIO_FAILURE_LINE
import com.hienlh.ppm.state.media.MediaFailure
import com.hienlh.ppm.state.media.UNSUPPORTED_VIDEO_HINT

/**
 * What a media tab shows in place of its player when the file cannot play, as the web draws it
 * (audio-preview.tsx, video-preview.tsx): no controls and no Retry, only the way out the web gives.
 * [onDownload] is null while a download is already running.
 */
@Composable
internal fun MediaFailureView(failure: MediaFailure, onDownload: (() -> Unit)?) {
    val modifier = Modifier.fillMaxSize().background(LocalPpmTokens.current.bgSolid.color)
    when (failure) {
        MediaFailure.Audio -> FileNoteView(FileNote.Warning(AUDIO_FAILURE_LINE), modifier)
        is MediaFailure.UnsupportedVideo -> UnsupportedVideo(failure, onDownload, modifier)
    }
}

/**
 * `flex flex-col items-center justify-center gap-3 p-6 text-center text-text-secondary`: the warning
 * icon, what could not be played, how to get ffmpeg (`max-w-sm`), and the download.
 */
@Composable
private fun UnsupportedVideo(failure: MediaFailure.UnsupportedVideo, onDownload: (() -> Unit)?, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val center = TextAlign.Center
    Column(modifier.padding(24.dp), Arrangement.spacedBy(12.dp, Alignment.CenterVertically), Alignment.CenterHorizontally) {
        PpmIcon(ProductIcon.FileWarning, 40.dp, tokens.text3.color)
        BasicText(failure.line, style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color, textAlign = center))
        BasicText(
            buildAnnotatedString {
                for (part in UNSUPPORTED_VIDEO_HINT) {
                    // `<code>`: the monospace face at the same size, ligatures off.
                    if (part.code) withStyle(SpanStyle(fontFamily = PpmFonts.mono, fontFeatureSettings = "\"liga\" 0, \"calt\" 0")) { append(part.text) }
                    else append(part.text)
                }
            },
            Modifier.widthIn(max = 384.dp),
            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color, textAlign = center),
        )
        DownloadFileButton(onDownload)
    }
}

/**
 * `Button asChild variant="outline" size="sm" className="h-11 px-4"` around an `<a>` holding a 16 px
 * icon with `mr-2` and the label. `has-[>svg]:px-2.5` outranks the `px-4`, so 10 each side, and
 * `gap-1.5` plus the icon's margin is 14 before the label. The link takes its colour from the box
 * around it (text-secondary) and `hover:text-accent-foreground` is the text colour; the light
 * variant names no border colour, so its border follows that, and `dark:` is the device's dark mode.
 * The web's link is never disabled; here it is while a download runs ([onDownload] null).
 */
@Composable
private fun DownloadFileButton(onDownload: (() -> Unit)?) {
    val tokens = LocalPpmTokens.current
    val dark = isSystemInDarkTheme()
    val enabled = onDownload != null
    val shape = RoundedCornerShape(8.dp)
    val hover = rememberHover(enabled = enabled)
    val lit = hover.lit.toDouble()
    val ink = tokens.text.mix(tokens.text2, lit)
    val fill = if (dark) tokens.border.opacity(0.3 + 0.2 * lit) else tokens.panel2.mix(tokens.bgSolid, lit)
    Row(
        Modifier
            .cssHeight(44.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .ppmShadow(ShadowXs, shape)
            .clip(shape)
            .background(fill.color)
            .ppmClickable(enabled = enabled, pressedColor = Color.Transparent, source = hover.source) { onDownload?.invoke() }
            .cssBorder((if (dark) tokens.border else ink).color, shape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PpmIcon(ProductIcon.Download, 16.dp, ink.color)
        BasicText("Download file", style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = ink.color))
    }
}
