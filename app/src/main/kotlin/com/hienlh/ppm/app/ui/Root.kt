package com.hienlh.ppm.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.FocusAnchor
import com.hienlh.ppm.app.FocusStartAnchor
import com.hienlh.ppm.app.LightboxRequest
import com.hienlh.ppm.app.LocalRedesign
import com.hienlh.ppm.app.leavesTextOnTap
import com.hienlh.ppm.app.tasks
import com.hienlh.ppm.app.ui.extensions.ExtensionOverlays
import com.hienlh.ppm.app.ui.phone.PhoneShell
import com.hienlh.ppm.app.ui.tablet.TabletShell
import com.hienlh.ppm.chat.ChatSessionOpener
import com.hienlh.ppm.chat.LocalChatSessionOpener
import com.hienlh.ppm.chat.LocalPpmApprovals
import com.hienlh.ppm.chat.ServerPpmApprovals
import com.hienlh.ppm.client.designListSystems
import com.hienlh.ppm.design.ImageViewer
import com.hienlh.ppm.design.LocalIconFramework
import com.hienlh.ppm.design.LocalImageViewer
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import kotlinx.coroutines.launch

/**
 * Everything the activity shows: the connect screen until there is a server, then the phone
 * layout under 600 dp ([SizeClass.Phone]) and the tablet layout from 600 dp up ([SizeClass.Tablet]),
 * with the dialogs and the toasts over it. [beforeIme] sees each key going to a field in it before
 * the IME does, which may type the key itself.
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
    val tabScrollStore = rememberTabScrollStore()
    val context = LocalContext.current
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
        val sizeClass = SizeClass.of(maxWidth, maxHeight)
        val mobile = sizeClass == SizeClass.Phone
        // The layout follows the window, not the device: a rotation, split screen or foldable unfold can cross it.
        SideEffect { model.setWindowWide(sizeClass == SizeClass.Tablet) }
        val viewer = remember(model) { ImageViewer { images, index -> model.lightbox = LightboxRequest(images, index) } }
        CompositionLocalProvider(
            LocalConnectionLost provides model.connectionLost,
            LocalImageViewer provides viewer,
            LocalIconFramework provides model.iconFramework,
            LocalSizeClass provides sizeClass,
            LocalRedesign provides true,
            LocalTabScrollStore provides tabScrollStore,
            // PPM's own approvals are answered over HTTP, and a from-session bubble or turn chip opens its session.
            LocalPpmApprovals provides model.api?.let(::ServerPpmApprovals),
            // An endpoint approval card is headed with the Assistant's own name.
            com.hienlh.ppm.chat.LocalAssistantName provides assistantDisplayName(model),
            LocalChatSessionOpener provides remember(model) {
                ChatSessionOpener { target ->
                    val turnId = target.turnId
                    if (turnId != null) model.openTurnRef(target.projectName, target.providerId, target.sessionId, turnId, target.title)
                    else model.openLinkedSession(target.projectName, target.sessionId, target.providerId, target.title)
                }
            },
            // A task chip on a sent message shows the task's quick view.
            com.hienlh.ppm.chat.LocalTaskRefOpener provides remember(model) { com.hienlh.ppm.app.ui.tasks.taskRefOpener(model) },
            // A PPM tool card's task button shows the task its code names.
            com.hienlh.ppm.chat.LocalTaskCodeOpener provides remember(model) { com.hienlh.ppm.app.ui.tasks.taskCodeOpener(model) },
        ) {
            PpmThemeProvider(theme, mobile) {
                // Chrome's own word on a download, which the web leaves to the browser.
                val download = rememberImageDownload { saved ->
                    model.toast(ErrorToastMessage("File downloaded", "${saved.name} (${downloadSize(saved.size)})", ErrorToastMessage.Action("Open", saved.open), kind = ErrorToastMessage.Kind.PLAIN))
                }
                val clipboard = LocalClipboard.current
                // `backdrop-blur-sm` under the connection-lost overlay, the lightbox and the background
                // output panel: CSS's blur(8px) is a sigma of 8, which Android's blur reaches at a radius
                // of 13 (sigma = 0.577 radius + 0.5).
                val blurred = model.connectionLost || model.lightbox != null || model.backgroundOutput != null
                Box(Modifier.fillMaxSize().then(if (blurred) Modifier.blur(13.dp) else Modifier)) {
                    when {
                        !model.hasServer -> ConnectScreen(model, signIn = false)
                        sizeClass == SizeClass.Phone -> PhoneShell(model)
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
                model.tasks.peek?.let { key(it.id) { com.hienlh.ppm.app.ui.tasks.TaskPeekHost(model, it) } }
                com.hienlh.ppm.app.ui.tasks.TaskConfirmHost(model)
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
                // A sealed vault value the user unlocked on a card, for a minute after the card left.
                com.hienlh.ppm.chat.VaultShownValueHost(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars))
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

/**
 * The project's avatar (`ProjectAvatar`): its uploaded image, cropped to fill — nothing while it
 * loads —, else its colour — a picked hex or a palette gradient, else the palette by position —
 * with initials that tell it from the others; a circle, or the 7 px squircle the desktop sidebar
 * uses ([square]). An image that will not load gives the colour and initials, as on the web.
 */
@Composable
fun ProjectAvatar(model: AppModel, project: ProjectInfo, size: Dp, square: Boolean = false) {
    val names = model.projects.map { it.name }
    val index = names.indexOf(project.name).coerceAtLeast(0)
    val shape = if (square) RoundedCornerShape(7.dp) else CircleShape
    if (project.image != null) {
        var asked by remember(project.name, project.image) { mutableStateOf(false) }
        val image by produceState<ImageBitmap?>(null, project.name, project.image) {
            value = model.projectImage(project)
            asked = true
        }
        image?.let {
            Image(it, project.name, Modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
            return
        }
        if (!asked) {
            Box(Modifier.size(size))
            return
        }
    }
    Box(
        Modifier.size(size).clip(shape).background(projectBackground(project.color, index)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            projectInitials(project.name, names),
            style = ppmText(if (size <= 24.dp) 10f else 11f, FontWeight.Bold, lineHeight = if (size <= 24.dp) 15f else 16.5f).copy(color = Color.White),
            maxLines = 1,
        )
    }
}

/** `project-palette.ts`. */
internal val PROJECT_PALETTE = listOf(
    "linear-gradient(135deg, #667eea, #764ba2)",
    "linear-gradient(135deg, #f5576c, #f093fb)",
    "linear-gradient(135deg, #4facfe, #00c6ff)",
    "linear-gradient(135deg, #43e97b, #38f9d7)",
    "linear-gradient(135deg, #fa709a, #fee140)",
    "linear-gradient(135deg, #a18cd1, #6a3de8)",
    "linear-gradient(135deg, #fd7043, #ff8a65)",
    "linear-gradient(135deg, #26c6da, #0097a7)",
    "linear-gradient(135deg, #ab47bc, #7b1fa2)",
    "linear-gradient(135deg, #ef5350, #b71c1c)",
    "linear-gradient(135deg, #1976d2, #42a5f5)",
    "linear-gradient(135deg, #2e7d32, #66bb6a)",
)

private val hexColor = Regex("#[0-9a-fA-F]{3,8}")

/** `resolveProjectColor`: a project's own colour, else the palette's by its position, as a CSS `background`. */
internal fun projectBackground(color: String?, index: Int): Brush = projectFill(color ?: PROJECT_PALETTE[index % PROJECT_PALETTE.size])

/** A CSS `background` of one hex or a 135° two-stop gradient, which runs corner to corner. */
private fun projectFill(color: String): Brush {
    val stops = hexColor.findAll(color).mapNotNull { Rgba.hex(it.value)?.color }.toList()
    return when {
        stops.size >= 2 -> Brush.linearGradient(stops, start = Offset.Zero, end = Offset.Infinite)
        stops.size == 1 -> SolidColor(stops.first())
        else -> SolidColor(Color(0xFF4F86C6))
    }
}

/** `getProjectInitials`: one letter while it is unique, two when that is, else the position. */
internal fun projectInitials(name: String, all: List<String>): String {
    fun words(n: String) = n.split(Regex("[-_.\\s]+")).filter { it.isNotEmpty() }
    fun first(n: String) = (words(n).firstOrNull()?.firstOrNull() ?: n.firstOrNull())?.uppercaseChar()?.toString() ?: ""
    fun two(n: String): String {
        val w = words(n)
        val f = first(n)
        return if (w.size > 1) f + (w[1].firstOrNull()?.uppercaseChar()?.toString() ?: "") else f
    }
    val mine = first(name).ifEmpty { "?" }
    val others = all.filter { it != name }
    if (others.none { first(it) == mine }) return mine
    val pair = two(name)
    if (others.none { two(it) == pair }) return pair
    val index = all.indexOf(name)
    return if (index >= 0) "${index + 1}" else "?"
}

/** project-sort.ts's `SORT_OPTIONS`, shared by the project sheet and the desktop flyout. */
internal val PROJECT_SORTS = listOf(
    Triple("recent", "Recent", ProductIcon.Clock),
    Triple("priority", "Priority", ProductIcon.ArrowDownUp),
    Triple("name", "Name", ProductIcon.ArrowDownAZ),
)

/** `tab-typing-dot`: three 2 px dots hopping 1.5 px in turn, a second each. */
@Composable
internal fun TypingDots(color: Rgba, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(1.5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (index in 0 until 3) {
            val lift by transition.animateFloat(
                0f, 0f,
                infiniteRepeatable(
                    keyframes {
                        durationMillis = 1_000
                        0f at 0 using EaseInOut
                        -1.5f at 300 using EaseInOut
                        0f at 600 using EaseInOut
                    },
                    initialStartOffset = StartOffset(index * 150),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "dot$index",
            )
            Box(Modifier.graphicsLayer { translationY = lift * density }.size(2.dp).clip(CircleShape).background(color.color))
        }
    }
}
