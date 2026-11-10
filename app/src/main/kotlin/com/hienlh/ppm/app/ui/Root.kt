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
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
import com.hienlh.ppm.app.FocusAnchor
import com.hienlh.ppm.app.FocusStartAnchor
import com.hienlh.ppm.app.LightboxRequest
import com.hienlh.ppm.app.leavesTextOnTap
import com.hienlh.ppm.app.ui.extensions.ExtensionOverlays
import com.hienlh.ppm.app.ui.tablet.TabletShell
import com.hienlh.ppm.client.designListSystems
import com.hienlh.ppm.design.ImageViewer
import com.hienlh.ppm.design.LocalIconFramework
import com.hienlh.ppm.design.LocalImageViewer
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
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
    val rootAnchor = remember { FocusAnchor() }
    val toaster = remember { ToasterState() }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // A tap outside the text field leaves it, as a press outside an input does in a browser:
            // the keyboard goes, shortcuts work.
            .leavesTextOnTap(focusManager, rootAnchor)
            .onPreInterceptKeyBeforeSoftKeyboard {
                beforeIme(it.nativeKeyEvent)
                false
            }
            .toasterTaps(toaster)
            // Test tags as resource ids, which UI Automator can find (baselineprofile/).
            .semantics { testTagsAsResourceId = true },
    ) {
        FocusStartAnchor(rootAnchor, parkOnStart = true)
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
                ExtensionOverlays(model)
                model.explorerSheetPath?.let { com.hienlh.ppm.app.ui.osexplorer.HostExplorerSheet(model, it) }
                model.agentSheet?.let { AgentSessionSheet(model, it) }
                model.paletteQuery?.let { CommandPalette(model, it, model::closePalette) }
                if (mobile && model.cloudShareOpen) {
                    BottomSheet(onDismiss = { model.cloudShareOpen = false }) {
                        CloudSharePopover(model, onClose = { model.cloudShareOpen = false }, sheet = true)
                    }
                }
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
    val designProj = model.newDesignProject
    val api = model.api
    if (designProj != null && api != null) {
        var systems by remember(designProj, api) { mutableStateOf(emptyList<com.hienlh.ppm.state.design.DesignSystemSummary>()) }
        LaunchedEffect(designProj, api) {
            val rawSys = runCatching { api.designListSystems(designProj) }.getOrNull()
            val sysArr = rawSys?.get("systems")?.array ?: rawSys?.array.orEmpty()
            systems = sysArr.mapNotNull { com.hienlh.ppm.state.design.DesignSystemSummary.fromJson(it) }
        }
        com.hienlh.ppm.app.ui.design.NewDesignDialog(
            api = api,
            projectName = designProj,
            systems = systems,
            onDismiss = { model.newDesignProject = null },
            onCreated = { created, chosenProvider ->
                model.newDesignProject = null
                model.openDesignTab(
                    slug = created.slug,
                    title = created.title,
                    projectName = designProj,
                    sessionId = created.sessionId,
                    providerId = chosenProvider ?: created.providerId,
                    fresh = true,
                )
            },
        )
    }
    if (model.comparePickerOpen) {
        val project = model.selectedProject
        val allTabs = model.layout?.panels?.values?.flatMap { it.tabs }.orEmpty()
        val openTabs = remember(allTabs) {
            allTabs.mapNotNull { t ->
                val p = t.metaString("filePath") ?: t.metaString("path") ?: return@mapNotNull null
                com.hienlh.ppm.state.editor.CompareCandidate(
                    id = "tab:${t.id}",
                    path = p,
                    label = p.substringAfterLast('/').substringAfterLast('\\'),
                    source = "tab",
                )
            }
        }
        val indexState = model.fileIndex?.state?.collectAsState()?.value
        val indexedPaths = remember(indexState?.entries) {
            indexState?.entries.orEmpty().filter { !it.isDirectory }.map { it.path }
        }
        androidx.compose.ui.window.Dialog(onDismissRequest = { model.comparePickerOpen = false }) {
            com.hienlh.ppm.app.ui.editor.CompareFilesDialogContent(
                initialSelectionA = com.hienlh.ppm.app.ui.editor.SharedCompareStore.store.selection,
                candidates = com.hienlh.ppm.state.editor.buildCompareCandidates(openTabs, indexedPaths),
                onPickPair = { selA, candB ->
                    model.comparePickerOpen = false
                    com.hienlh.ppm.app.ui.editor.SharedCompareStore.store.clearSelection()
                    val proj = selA.projectName.ifEmpty { project.orEmpty() }
                    model.openWorkspaceTab(
                        type = "git-diff",
                        title = com.hienlh.ppm.state.editor.formatCompareTabTitle(selA.filePath, candB.path),
                        projectId = proj.ifEmpty { null },
                        metadata = kotlinx.serialization.json.JsonObject(
                            buildMap {
                                if (proj.isNotEmpty()) put("projectName", kotlinx.serialization.json.JsonPrimitive(proj))
                                put("file1", kotlinx.serialization.json.JsonPrimitive(selA.filePath))
                                put("file2", kotlinx.serialization.json.JsonPrimitive(candB.path))
                            },
                        ),
                    )
                },
                onClose = { model.comparePickerOpen = false },
            )
        }
    }
    val learnMode = model.learnPpmMode
    if (learnMode != null) {
        val context = androidx.compose.ui.platform.LocalContext.current
        var onboardingState by remember(learnMode) {
            val saved = com.hienlh.ppm.app.ui.shell.loadOnboardingStateFromPrefs(context)
            mutableStateOf(if (learnMode) saved.openQuickOrientation() else saved.resumeGuide().copy(quickOrientationPage = null))
        }
        fun updateOnboarding(next: com.hienlh.ppm.state.shell.OnboardingState) {
            val closedOrientation = learnMode && next.quickOrientationPage == null
            onboardingState = next
            com.hienlh.ppm.app.ui.shell.saveOnboardingStateToPrefs(context, next)
            if (closedOrientation) model.learnPpmMode = null
        }
        com.hienlh.ppm.app.ui.shell.LearnPpmDialog(
            state = onboardingState,
            onStateChange = ::updateOnboarding,
            onRefreshEvidence = {
                val openTabs = model.layout?.panels?.values?.flatMap { p -> p.tabs }.orEmpty()
                val ev = com.hienlh.ppm.state.shell.OnboardingEvidence(
                    hasProjectSelected = model.selectedProject != null,
                    chatSessionCount = model.sessions.size,
                    hasOpenedFileTab = openTabs.any { t -> t.type == "editor" || t.type == "file" },
                    hasOpenedTerminalTab = openTabs.any { t -> t.type == "terminal" },
                    hasOpenedGitTab = openTabs.any { t -> t.type == "git-status" || t.type == "git-graph" },
                )
                updateOnboarding(onboardingState.mergeEvidence(ev))
            },
            onInsertSuggestedPrompt = { prompt ->
                val openTabs = model.layout?.panels?.values?.flatMap { p -> p.tabs }.orEmpty()
                val existingChat = model.activeTab?.let { model.chat(it.id) }
                    ?: openTabs.firstNotNullOfOrNull { t -> model.chat(t.id) }
                if (existingChat != null) {
                    val updated = com.hienlh.ppm.state.shell.applySuggestedPromptToDraft(existingChat.draft.text.toString(), prompt)
                    existingChat.draft.setTextAndPlaceCursorAtEnd(updated)
                    model.activate(existingChat.tabId)
                    existingChat.requestFocus()
                } else if (model.selectedProject != null) {
                    model.newChat(pendingMessage = prompt)
                } else {
                    model.terminalToChat(prompt, newTab = true, asContext = false)
                }
                updateOnboarding(onboardingState.markStepCompleted("ai.send"))
                model.learnPpmMode = null
            },
            onDismiss = {
                if (!learnMode) updateOnboarding(onboardingState.pauseOnEscape())
                model.learnPpmMode = null
            },
        )
    }
}
