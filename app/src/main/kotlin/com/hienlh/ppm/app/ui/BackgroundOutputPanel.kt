package com.hienlh.ppm.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.BackgroundOutputRequest
import com.hienlh.ppm.chat.BackgroundOutputCard
import com.hienlh.ppm.client.fsRead
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmShadow

/**
 * The background output panel (`background-output-panel.tsx`): a command's output over the whole
 * app, read again every 1.5 s while it runs and followed to its end. A sheet on the bottom edge
 * on a phone, at most 80% of the screen; a card in the middle on the desktop layout, 90% of the
 * width up to 768 dp and at most 75% of the height. Esc, Back, × or a tap outside closes it.
 */
@Composable
fun BackgroundOutputPanel(model: AppModel, request: BackgroundOutputRequest) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    // Followed through the chat that lists it, so the header changes as the command does.
    val listed = model.chat(request.tabId)?.controller?.state?.collectAsState()?.value?.backgroundShells
    val shell = listed?.firstOrNull { it.shellId == request.shellId }
    val api = model.api
    val path = shell?.outputPath.orEmpty()
    val status = shell?.status
    // Once no chat lists it any more, the box keeps what it last read, as the web's does.
    LaunchedEffect(path, status, api) {
        if (path.isEmpty() || api == null) return@LaunchedEffect
        model.backgroundOutputShown.follow(path, running = status == "running") { api.fsRead(it) }
    }
    val shown by model.backgroundOutputShown.shown.collectAsState()
    val close = model::closeBackgroundOutput
    // Under the connection-lost overlay, as the web's z-[100] is: hidden while it shows, still reading.
    if (LocalConnectionLost.current) return

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) { window?.setDimAmount(0f) }
        // `animate-in fade-in duration-150`, the backdrop and the panel together.
        val fade = remember { Animatable(0f) }
        LaunchedEffect(Unit) { fade.animateTo(1f, tween(150, easing = CssEase)) }
        // The web hears Escape on `document`, wherever focus is.
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = fade.value }
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        close()
                        true
                    } else {
                        false
                    }
                },
        ) {
            // `bg-black/60`; what is under it is blurred by the root (`backdrop-blur-sm`).
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = close),
            )
            val card: @Composable (Modifier, Dp) -> Unit = { modifier, bottom -> BackgroundOutputCard(shell, shown.text, close, modifier, bottom) }
            if (mobile) {
                // `items-end w-full max-h-[80vh] rounded-t-xl`, on the bottom edge and under the
                // navigation bar as Chrome draws it, but with its last line kept above that bar. A
                // `vh` is Chrome's large viewport, everything under the status bar (869⅓ on a 924 dp phone).
                BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
                    val navigation = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                    card(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .heightIn(max = maxHeight * 0.8f)
                            .ppmShadow(SHADOW_2XL, shape)
                            .clip(shape)
                            .background(tokens.panel.color)
                            .cssBorder(tokens.border.color, shape),
                        navigation,
                    )
                }
            } else {
                // `md:items-center md:max-w-3xl md:w-[90vw] md:max-h-[75vh] md:rounded-xl`, centred
                // between the system bars. A `vh` is Chrome's large viewport, everything under the
                // status bar (776 on an 800 dp tablet, whose page is 680 under Chrome's toolbar).
                BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
                    val vh = maxHeight
                    val vw = maxWidth
                    val shape = RoundedCornerShape(12.dp)
                    Box(Modifier.fillMaxSize().navigationBarsPadding()) {
                        card(
                            Modifier
                                .align(Alignment.Center)
                                .width(minOf(vw * 0.9f, 768.dp))
                                .heightIn(max = vh * 0.75f)
                                .ppmShadow(SHADOW_2XL, shape)
                                .clip(shape)
                                .background(tokens.panel.color)
                                .cssBorder(tokens.border.color, shape),
                            0.dp,
                        )
                    }
                }
            }
        }
    }
}
