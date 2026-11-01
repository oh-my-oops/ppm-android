package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.ShadowXs
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.WebCursor
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
 * What a media tab shows when the file cannot play, as the web draws it (audio-preview.tsx,
 * video-preview.tsx, video-player.tsx). In place of the player there are no controls and no Retry,
 * only the way out the web gives; a transcode that stopped is drawn over the video's area, with a
 * Retry. [onDownload] is null while a download is already running.
 */
@Composable
internal fun MediaFailureView(failure: MediaFailure, onDownload: (() -> Unit)?, onRetry: () -> Unit) {
    val screen = Modifier.fillMaxSize().background(LocalPpmTokens.current.bgSolid.color)
    when (failure) {
        MediaFailure.Audio -> FileNoteView(FileNote.Warning(AUDIO_FAILURE_LINE), screen)
        is MediaFailure.UnsupportedVideo -> UnsupportedVideo(failure, onDownload, screen)
        is MediaFailure.TranscodeStopped -> TranscodeStopped(failure, onRetry)
    }
}

/**
 * `absolute inset-0 flex flex-col items-center justify-center gap-3 bg-black/85 text-text-secondary p-4
 * text-center` over the video's own area, the controls under it: what stopped, and a Retry. A click or a
 * double click on it goes no further, so it neither plays or pauses the video nor takes it fullscreen.
 */
@Composable
private fun TranscodeStopped(failure: MediaFailure.TranscodeStopped, onRetry: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .pointerInput(Unit) { detectTapGestures(onTap = {}, onDoubleTap = {}) }
            .padding(16.dp),
        Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        Alignment.CenterHorizontally,
    ) {
        BasicText(failure.message, style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color, textAlign = TextAlign.Center))
        // `<Button variant="outline" size="sm" className="h-11 px-4">Retry</Button>`: a button, which shows the arrow, and no icon to take 6 off the padding.
        OutlineButton(enabled = true, horizontalPadding = 16.dp, gap = 0.dp, link = false, onClick = onRetry) { ink ->
            BasicText("Retry", style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = ink))
        }
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
 * `gap-1.5` plus the icon's margin is 14 before the label.
 */
@Composable
private fun DownloadFileButton(onDownload: (() -> Unit)?) {
    OutlineButton(enabled = onDownload != null, horizontalPadding = 10.dp, gap = 14.dp, link = true, onClick = { onDownload?.invoke() }) { ink ->
        PpmIcon(ProductIcon.Download, 16.dp, ink)
        BasicText("Download file", style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = ink))
    }
}

/**
 * `Button variant="outline" size="sm"` at `h-11`: `bg-background` in a 1 border, `hover:bg-accent` and
 * `hover:text-accent-foreground`. Its text is the colour of the box around it (text-secondary), so the
 * light variant, which names no border colour, has a border that follows the text; `dark:` is the
 * device's dark mode, with `dark:border-input` and `dark:bg-input/30` (`/50` under a mouse). [link] is
 * an `<a>`, which shows the hand, where a `<button>` shows the arrow. The web's is never disabled;
 * here the download is while it runs ([enabled] false).
 */
@Composable
private fun OutlineButton(
    enabled: Boolean,
    horizontalPadding: Dp,
    gap: Dp,
    link: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.(ink: Color) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val dark = isSystemInDarkTheme()
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
            .then(if (link) Modifier.pointerHoverIcon(WebCursor.Pointer) else Modifier)
            .ppmClickable(enabled = enabled, pressedColor = Color.Transparent, source = hover.source, onClick = onClick)
            .cssBorder((if (dark) tokens.border else ink).color, shape)
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        content(ink.color)
    }
}
