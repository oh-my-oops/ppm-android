package com.hienlh.ppm.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.tablet.TabletShell
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Everything the activity shows: the connect screen until there is a server, then the phone
 * layout under 768 dp — the web's `md` breakpoint — and the desktop one from there, with the
 * dialogs and the error toast over it.
 */
@Composable
fun PpmRoot(model: AppModel) {
    val systemDark = isSystemInDarkTheme()
    LaunchedEffect(systemDark) { model.systemIsDark = systemDark }
    val theme = model.theme
    val activity = LocalActivity.current as? ComponentActivity
    LaunchedEffect(theme.isDark, activity) {
        // Bars drawn over the app, their icons light on a dark theme and dark on a light one.
        val style = if (theme.isDark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        }
        activity?.enableEdgeToEdge(style, style)
    }
    val servers by model.store.servers.collectAsState()
    val selectedId by model.store.selectedId.collectAsState()
    LaunchedEffect(selectedId, servers.size) { model.showSelectedServer() }

    // `useWakeLock`: the screen stays lit while any turn runs, in any project, if Settings allows.
    val keepAwake = model.hasServer && model.screenKeptAwake()
    val view = LocalView.current
    DisposableEffect(view, keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = false }
    }

    val focusManager = LocalFocusManager.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // A tap nothing else takes (the transcript, an empty stretch of a panel) leaves the text
            // field, as a click outside an input does in a browser: the keyboard goes, shortcuts work.
            .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) },
    ) {
        val mobile = maxWidth < 768.dp
        // The layout follows the window, not the device: a rotation or a split screen can cross it.
        SideEffect { model.setWindowWide(!mobile) }
        PpmThemeProvider(theme, mobile) {
            when {
                !model.hasServer -> ConnectScreen(model, signIn = false)
                mobile -> PhoneShell(model)
                else -> TabletShell(model)
            }
            if (model.showSignIn) ConnectScreen(model, signIn = true, onCancel = { model.showSignIn = false })
            if (model.addingServer) ConnectScreen(model, signIn = false, onCancel = { model.addingServer = false })
            Dialogs(model)
            model.paletteQuery?.let { CommandPalette(model, it, model::closePalette) }
            ErrorToast(model.actionError, mobile, narrow = maxWidth <= 600.dp) { model.actionError = null }
        }
    }
}

/** The confirmations the web asks with `window.confirm` / `window.prompt`. */
@Composable
private fun Dialogs(model: AppModel) {
    model.confirmDeleteSession?.let { session ->
        ConfirmDialog(
            title = "Delete this session?",
            message = "This cannot be undone.",
            confirm = "Delete",
            onConfirm = { model.scope.launch { model.delete(session) } },
            onDismiss = { model.confirmDeleteSession = null },
        )
    }
    var deleteOlderThan by remember { mutableStateOf<Int?>(null) }
    if (model.askDeleteOld) {
        PromptDialog(
            message = "Delete sessions older than how many days? (pinned sessions are kept)",
            initial = "30",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            onConfirm = { text -> text.trim().toIntOrNull()?.takeIf { it >= 1 }?.let { deleteOlderThan = it } },
            onDismiss = { model.askDeleteOld = false },
        )
    }
    deleteOlderThan?.let { days ->
        ConfirmDialog(
            title = "Delete all unpinned sessions older than $days days?",
            message = "This cannot be undone.",
            confirm = "Delete",
            onConfirm = { model.scope.launch { model.deleteSessions(days) } },
            onDismiss = { deleteOlderThan = null },
        )
    }
}

/**
 * The web's error toast (sonner with its close button): the message on the surface colour, for
 * four seconds or until closed. `top-center` on a phone — 16 px from three edges up to 600 px
 * wide, else 356 px wide and 24 px down — and `bottom-left`, 24 px in, on the desktop layout.
 */
@Composable
private fun ErrorToast(message: String?, mobile: Boolean, narrow: Boolean, onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    // Kept while it slides out, after the message itself is cleared.
    var shown by remember { mutableStateOf(message) }
    if (message != null) shown = message
    LaunchedEffect(message) {
        if (message != null) {
            delay(4_000)
            onDismiss()
        }
    }
    val placement = when {
        !mobile -> Modifier.safeDrawingPadding().padding(24.dp)
        narrow -> Modifier.statusBarsPadding().padding(16.dp)
        else -> Modifier.statusBarsPadding().padding(top = 24.dp)
    }
    // Slid in from the edge it sits on.
    val from = if (mobile) -1 else 1
    Box(Modifier.fillMaxSize().then(placement), contentAlignment = if (mobile) Alignment.TopCenter else Alignment.BottomStart) {
        AnimatedVisibility(message != null, enter = fadeIn() + slideInVertically { from * it }, exit = fadeOut() + slideOutVertically { from * it }) {
            val shape = RoundedCornerShape(8.dp)
            Box(if (mobile && narrow) Modifier.fillMaxWidth() else Modifier.width(356.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .ppmShadow(TOAST_SHADOW, shape)
                        .clip(shape)
                        .background(tokens.panel.over(tokens.bgSolid).color)
                        .border(1.dp, tokens.border.color, shape)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PpmIcon(ProductIcon.AlertCircle, 16.dp, tokens.text.color)
                    BasicText(shown.orEmpty(), Modifier.weight(1f), style = ppmText(13f, lineHeight = 19.5f).copy(color = tokens.text.color))
                }
                // `closeButton`: a 20 px circle on the top-left corner.
                Box(
                    Modifier
                        .offset((-6).dp, (-6).dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(tokens.panel.over(tokens.bgSolid).color)
                        .border(1.dp, tokens.border.color, CircleShape)
                        .ppmClickable(pressedColor = tokens.panel2.color, onClickLabel = "Close toast", onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { PpmIcon(ProductIcon.X, 12.dp, tokens.text.color) }
            }
        }
    }
}

/** sonner's `0 4px 12px rgba(0, 0, 0, .1)`. */
private val TOAST_SHADOW = ThemeShadow(0.0, 4.0, 12.0, 0.0, Rgba(0.0, 0.0, 0.0, 0.1))
