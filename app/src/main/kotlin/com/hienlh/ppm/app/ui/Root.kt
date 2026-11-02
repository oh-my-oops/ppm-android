package com.hienlh.ppm.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
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
import com.hienlh.ppm.design.PpmThemeProvider
import kotlinx.coroutines.launch

/**
 * Everything the activity shows: the connect screen until there is a server, then the phone
 * layout under 768 dp — the web's `md` breakpoint — and the desktop one from there, with the
 * dialogs and the toasts over it. [beforeIme] sees each key going to a field in it before the IME
 * does, which may type the key itself.
 */
@Composable
fun PpmRoot(model: AppModel, beforeIme: (android.view.KeyEvent) -> Unit = {}) {
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
    // The version chip's minute check, which the web runs with the app shell (status bar and drawer).
    val upgrade = model.upgrade
    LaunchedEffect(upgrade, lifecycle) {
        if (upgrade != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { upgrade.watch() }
    }

    val focusManager = LocalFocusManager.current
    val toaster = remember { ToasterState() }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // A tap outside the text field leaves it, as a press outside an input does in a browser:
            // the keyboard goes, shortcuts work.
            .leavesTextOnTap(focusManager)
            .onPreInterceptKeyBeforeSoftKeyboard {
                beforeIme(it.nativeKeyEvent)
                false
            }
            .toasterTaps(toaster)
            // Test tags as resource ids, which UI Automator can find (baselineprofile/).
            .semantics { testTagsAsResourceId = true },
    ) {
        val mobile = maxWidth < 768.dp
        // The layout follows the window, not the device: a rotation or a split screen can cross it.
        SideEffect { model.setWindowWide(!mobile) }
        val viewer = remember(model) { ImageViewer { images, index -> model.lightbox = LightboxRequest(images, index) } }
        CompositionLocalProvider(LocalConnectionLost provides model.connectionLost, LocalImageViewer provides viewer, LocalIconFramework provides model.iconFramework) {
            PpmThemeProvider(theme, mobile) {
                // Chrome's own word on a download, which the web leaves to the browser.
                val download = rememberImageDownload { saved ->
                    model.toast(ErrorToastMessage("File downloaded", "${saved.name} (${downloadSize(saved.size)})", ErrorToastMessage.Action("Open", saved.open), kind = ErrorToastMessage.Kind.PLAIN))
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
                model.projectActions?.let { project -> key(model.api, project.name) { com.hienlh.ppm.app.ui.projects.ProjectActionsDialog(model, project) } }
                if (model.addingProject) key(model.api) { com.hienlh.ppm.app.ui.projects.AddProjectDialog(model) }
                Dialogs(model)
                model.explorerSheetPath?.let { com.hienlh.ppm.app.ui.osexplorer.HostExplorerSheet(model, it) }
                model.agentSheet?.let { AgentSessionSheet(model, it) }
                model.paletteQuery?.let { CommandPalette(model, it, model::closePalette) }
                model.backgroundOutput?.let { BackgroundOutputPanel(model, it) }
                // A new sheet for each sign-in, as the web mounts a new body per server.
                model.mcpSignIn?.let { key(it) { McpSignInPanel(model, it) } }
                model.lightbox?.let { request ->
                    ImageLightbox(request, onClose = { model.lightbox = null }, onDownload = download, onCopy = { copyImage(context, clipboard, it) })
                }
                if (model.connectionLost) ConnectionLostOverlay(model)
                Toaster(model.toasts, toaster, mobile, narrow = maxWidth <= 600.dp, dark = model.theme.isDark, onGone = model::forgetToast)
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
