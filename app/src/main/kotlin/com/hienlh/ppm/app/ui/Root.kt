package com.hienlh.ppm.app.ui

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.LightboxRequest
import com.hienlh.ppm.app.leavesTextOnTap
import com.hienlh.ppm.app.ui.tablet.TabletShell
import com.hienlh.ppm.design.ImageViewer
import com.hienlh.ppm.design.LocalIconFramework
import com.hienlh.ppm.design.LocalImageViewer
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
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

    // The browser's Back (`useUrlSync`): the tab shown before. Registered before the shells', so
    // the drawer, a sheet or Settings' own pages close first; under the connection-lost overlay
    // nothing moves, and Back leaves the app.
    BackHandler(enabled = model.canGoBack && !model.connectionLost, onBack = model::goBack)

    // `useServerReload`, while the app is in front.
    val api = model.api
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(api, lifecycle) {
        if (api != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.watchHealth(api) }
    }

    val focusManager = LocalFocusManager.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // A tap outside the text field leaves it, as a press outside an input does in a browser:
            // the keyboard goes, shortcuts work.
            .leavesTextOnTap(focusManager),
    ) {
        val mobile = maxWidth < 768.dp
        // The layout follows the window, not the device: a rotation or a split screen can cross it.
        SideEffect { model.setWindowWide(!mobile) }
        val viewer = remember(model) { ImageViewer { images, index -> model.lightbox = LightboxRequest(images, index) } }
        CompositionLocalProvider(LocalConnectionLost provides model.connectionLost, LocalImageViewer provides viewer, LocalIconFramework provides model.iconFramework) {
            PpmThemeProvider(theme, mobile) {
                // Chrome's own word on a download, which the web leaves to the browser.
                val download = rememberImageDownload { saved ->
                    model.toast = ErrorToastMessage("File downloaded", "${saved.name} (${downloadSize(saved.size)})", ErrorToastMessage.Action("Open", saved.open), kind = ErrorToastMessage.Kind.PLAIN)
                }
                val context = LocalContext.current
                val clipboard = LocalClipboard.current
                // `backdrop-blur-sm` under the connection-lost overlay, the lightbox and the background
                // output panel: CSS's blur(8px) is a sigma of 8, which Android's blur reaches at a radius
                // of 13 (sigma = 0.577 radius + 0.5).
                val blurred = model.connectionLost || model.lightbox != null || model.backgroundOutput != null
                Box(Modifier.fillMaxSize().then(if (blurred) Modifier.blur(13.dp) else Modifier)) {
                    when {
                        !model.hasServer -> ConnectScreen(model, signIn = false)
                        mobile -> PhoneShell(model)
                        else -> TabletShell(model)
                    }
                    model.screenOverlay?.let { overlay ->
                        // The viewport `fixed inset-0` covers: the app's own area, inside the system bars.
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) { overlay() }
                    }
                }
                if (model.showSignIn) ConnectScreen(model, signIn = true, onCancel = { model.showSignIn = false })
                if (model.addingServer) ConnectScreen(model, signIn = false, onCancel = { model.addingServer = false })
                Dialogs(model)
                model.agentSheet?.let { AgentSessionSheet(model, it) }
                model.paletteQuery?.let { CommandPalette(model, it, model::closePalette) }
                model.backgroundOutput?.let { BackgroundOutputPanel(model, it) }
                // A new sheet for each sign-in, as the web mounts a new body per server.
                model.mcpSignIn?.let { key(it) { McpSignInPanel(model, it) } }
                model.lightbox?.let { request ->
                    ImageLightbox(request, onClose = { model.lightbox = null }, onDownload = download, onCopy = { copyImage(context, clipboard, it) })
                }
                if (model.connectionLost) ConnectionLostOverlay(model)
                ErrorToast(model.toast, mobile, narrow = maxWidth <= 600.dp, dark = model.theme.isDark) { model.toast = null }
            }
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
 * The web's error toast (sonner with its close button): the title, the description under it and
 * the action at the end, on the surface colour, for four seconds or until closed. `top-center` on
 * a phone — 16 px from three edges up to 600 px wide, else 356 px wide and 24 px down — and
 * `bottom-left`, 24 px in, on the desktop layout.
 */
@Composable
private fun ErrorToast(message: ErrorToastMessage?, mobile: Boolean, narrow: Boolean, dark: Boolean, onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    // Kept while it slides out, after the message itself is cleared.
    var shown by remember { mutableStateOf(message) }
    if (message != null) shown = message
    // sonner holds the four seconds while a mouse is over the toast, and runs on from where they
    // stopped once it leaves.
    val overToast = remember { MutableInteractionSource() }
    val closeHover = rememberHover(instant = true)
    // Both read on every pass: behind `||` the second would leave the composition while the
    // body is hovered, and come back too late to see the close button's Enter.
    val overBody by overToast.collectIsHoveredAsState()
    val overClose by closeHover.source.collectIsHoveredAsState()
    val held = overBody || overClose
    var left by remember(message) { mutableLongStateOf(4_000L) }
    LaunchedEffect(message, held) {
        if (message == null || held) return@LaunchedEffect
        val start = SystemClock.uptimeMillis()
        try {
            delay(left)
            onDismiss()
        } finally {
            left -= SystemClock.uptimeMillis() - start
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
            // `--border-radius: var(--radius)`, and PPM defines no `--radius`: sonner's corners
            // compute to 0. The web's quirk, kept.
            val shape = RectangleShape
            // `--normal-bg`, the popover colour: `--panel-2`.
            val surface = tokens.panel2
            // The icons PPM gives sonner (components/ui/sonner.tsx).
            val icon = when (shown?.kind) {
                ErrorToastMessage.Kind.INFO -> ProductIcon.InfoIcon
                ErrorToastMessage.Kind.SUCCESS -> ProductIcon.CircleCheckIcon
                ErrorToastMessage.Kind.PLAIN -> null
                else -> ProductIcon.OctagonXIcon
            }
            Box(if (mobile && narrow) Modifier.fillMaxWidth() else Modifier.width(356.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .ppmShadow(TOAST_SHADOW, shape)
                        .clip(shape)
                        .background(surface.color)
                        .hoverable(overToast)
                        .cssBorder(tokens.border.color, shape)
                        // sonner's `padding: 16px`, less the 3 px the icon's `margin-left: -3px` takes back.
                        .padding(start = if (icon != null) 13.dp else 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // `[data-icon]`: 16 px and `margin-right: 4px`, its svg `margin-left: -1px`.
                    if (icon != null) {
                        Box(Modifier.padding(end = 4.dp).size(16.dp)) {
                            PpmIcon(icon, 16.dp, tokens.text.color, Modifier.offset(x = (-1).dp))
                        }
                    }
                    // `[data-content]`, `gap: 2px`: the title at 500 on a 1.5 line, the description at 400
                    // on 1.4, in sonner's own grey.
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        BasicText(shown?.title.orEmpty(), style = sonnerText(13f, FontWeight.Medium, lineHeight = 19.5f).copy(color = tokens.text.color))
                        shown?.description?.let {
                            BasicText(it, style = sonnerText(13f, FontWeight.Normal, lineHeight = 18.2f).copy(color = (if (dark) SONNER_DESCRIPTION_DARK else SONNER_DESCRIPTION).color))
                        }
                    }
                    // `[data-button]`, `margin-left: auto`: 24 tall, the toast's colours swapped. It closes
                    // the toast, then does what it says.
                    shown?.action?.let { action ->
                        Box(
                            Modifier
                                .height(24.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(tokens.text.color)
                                .pointerHoverIcon(WebCursor.Pointer)
                                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                                    onDismiss()
                                    action.run()
                                }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(action.label, style = sonnerText(12f, FontWeight.Medium, lineHeight = 18f).copy(color = surface.color))
                        }
                    }
                }
                // `closeButton`: a 20 px circle on the top-left corner, sonner's own colours under a
                // mouse (and a finger), faded over its 200 ms `ease`, and its `cursor: pointer`. On a
                // phone it takes taps 12 px around it, a 44 px target (globals.css).
                val close = SonnerClose.of(dark, tokens.border, tokens.text)
                val lit by animateFloatAsState(closeHover.lit, tween(200, easing = SONNER_EASE), label = "close")
                val pressed by closeHover.source.collectIsPressedAsState()
                val reach = if (mobile) 12.dp else 0.dp
                Box(
                    Modifier
                        .offset((-6).dp - reach, (-6).dp - reach)
                        .pointerHoverIcon(WebCursor.Pointer)
                        .hoverable(closeHover.source)
                        .clickable(closeHover.source, indication = null, onClickLabel = "Close toast", role = Role.Button, onClick = onDismiss)
                        .padding(reach)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background((if (pressed) close.hoverFill else close.hoverFill.mix(surface, lit.toDouble())).color)
                        .cssBorder(close.hoverBorder.mix(close.border, lit.toDouble()).color, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { PpmIcon(ProductIcon.X, 12.dp, close.icon.color) }
            }
        }
    }
}

/**
 * sonner's close button: on a light toaster its own greys (`--gray4` ring, `--gray12` glyph,
 * `--gray2` / `--gray5` under a mouse); on a dark one the border and text PPM passes it, and its
 * own `--normal-bg-hover` / `--normal-border-hover`, which PPM does not.
 */
private class SonnerClose(val border: Rgba, val icon: Rgba, val hoverFill: Rgba, val hoverBorder: Rgba) {
    companion object {
        fun of(dark: Boolean, border: Rgba, text: Rgba) =
            if (dark) SonnerClose(border, text, grey(0.12), grey(0.25)) else SonnerClose(grey(0.93), grey(0.09), grey(0.973), grey(0.909))

        private fun grey(lightness: Double) = Rgba(lightness, lightness, lightness, 1.0)
    }
}

/** CSS `ease`. */
private val SONNER_EASE = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/**
 * sonner sets its own `font-family: ui-sans-serif, system-ui, …` on the toaster, so the web's
 * toasts are not in Geist: Chrome on Android draws them in the system font.
 */
private fun sonnerText(size: Float, weight: FontWeight, lineHeight: Float) =
    ppmText(size, weight, lineHeight = lineHeight).copy(fontFamily = FontFamily.SansSerif)

/** `[data-description]`: `#3f3f3f`, and `hsl(0, 0%, 91%)` on a dark toaster. */
private val SONNER_DESCRIPTION = Rgba(63 / 255.0, 63 / 255.0, 63 / 255.0, 1.0)
private val SONNER_DESCRIPTION_DARK = Rgba(0.91, 0.91, 0.91, 1.0)

/** sonner's `0 4px 12px rgba(0, 0, 0, .1)`. */
private val TOAST_SHADOW = ThemeShadow(0.0, 4.0, 12.0, 0.0, Rgba(0.0, 0.0, 0.0, 0.1))
