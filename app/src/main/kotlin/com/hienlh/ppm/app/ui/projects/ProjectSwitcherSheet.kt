package com.hienlh.ppm.app.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.BottomSheet
import com.hienlh.ppm.app.ui.PROJECT_SORTS
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.app.ui.ProjectAvatar
import com.hienlh.ppm.app.ui.unreadSessions
import com.hienlh.ppm.chat.RelativeTime
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.GitRepo
import com.hienlh.ppm.client.GitRepos
import com.hienlh.ppm.client.ServerProfile
import com.hienlh.ppm.client.fsMkdir
import com.hienlh.ppm.client.gitRepos
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.cssScrollbar
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.SessionUnread
import com.hienlh.ppm.state.git.GitRepoScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Project switcher (`Phone 2 · P1–P8` and `Tablet 2 · Section 5`):
 * - Server bar at the top when multiple servers are saved, probing each saved server's health
 *   and allowing one-tap server switching or `"Add server"`
 * - Search field (`"Search projects…"`) + 3-segment sort bar (`Recent`, `Priority`, `Name`)
 * - Project rows with avatar, unread urgency dot, git branch chip, relative last-opened time,
 *   inline rename, and long-press / `"⋯"` actions
 * - Git states: `P7 · Not a git repository` (`"Initialize repository"`) and `P8 · Multiple git
 *   repositories inside one project` (nested repository selector)
 * - Footer with `+ Add Project` and `Settings` + server version chip.
 */
@Composable
fun ProjectSwitcherSheet(
    model: AppModel,
    onDismiss: () -> Unit,
    asFlyoutCard: Boolean = !LocalPpmMobile.current,
    modifier: Modifier = Modifier,
) {
    if (model.projectActions != null) return
    val tokens = LocalPpmTokens.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    if (asFlyoutCard) {
        val shape = RoundedCornerShape(14.dp)
        Box(
            modifier
                .width(340.dp)
                .heightIn(max = 680.dp)
                .ppmShadow(tokens.shadowPanel, shape)
                .clip(shape)
                .background(tokens.panel2.over(tokens.bgSolid).color)
                .cssBorder(tokens.border.color, shape),
        ) {
            ProjectSwitcherBody(model = model, onDismiss = onDismiss, isSheet = false)
        }
    } else {
        BottomSheet(
            onDismiss = onDismiss,
            modifier = modifier.heightIn(max = screenHeight * 0.86f),
            background = tokens.bgSolid.color,
        ) {
            ProjectSwitcherBody(model = model, onDismiss = onDismiss, isSheet = true)
        }
    }
}

@Composable
internal fun ProjectSwitcherBody(
    model: AppModel,
    onDismiss: () -> Unit,
    isSheet: Boolean,
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    val query = rememberTextFieldState()
    val listState = rememberLazyListState()
    val searchFocus = remember { FocusRequester() }
    val servers by model.store.servers.collectAsState()
    val selectedServerId by model.store.selectedId.collectAsState()
    val unread = model.unreadSessions()

    var serverMenuOpen by remember { mutableStateOf(false) }
    val serverHealth = remember { mutableStateMapOf<String, Boolean>() }

    // Probe health of saved servers when multiple servers exist (`Tablet 2 · PS3`)
    LaunchedEffect(servers) {
        if (servers.size > 1) {
            for (srv in servers) {
                launch {
                    val reachable = runCatching {
                        val url = ApiClient.normalizedServerUrl(srv.url) ?: return@runCatching false
                        ApiClient(url, model.store.token(srv).orEmpty()).health()
                        true
                    }.getOrDefault(false)
                    serverHealth[srv.id] = reachable
                }
            }
        }
    }

    // Track git repositories state for the active project (`P7` / `P8`)
    val activeProjectName = model.selectedProject
    var activeGitRepos by remember(activeProjectName, model.api) { mutableStateOf<GitRepos?>(null) }
    var selectedSubRepoPath by remember(activeProjectName) { mutableStateOf<String?>(null) }
    var initializingGit by remember { mutableStateOf(false) }

    LaunchedEffect(activeProjectName, model.api) {
        val api = model.api ?: return@LaunchedEffect
        val proj = activeProjectName ?: return@LaunchedEffect
        activeGitRepos = runCatching { api.gitRepos(proj) }.getOrNull()
        selectedSubRepoPath = activeGitRepos?.resolved?.path
    }

    val q = query.text.toString().trim().lowercase()
    val shown = model.sortedProjects.filter {
        q.isEmpty() || it.name.lowercase().contains(q) || it.path.lowercase().contains(q)
    }
    var highlightIdx by remember(q, model.projectSortMode) { mutableIntStateOf(0) }

    Column(Modifier.fillMaxWidth()) {
        // Sheet header (phone)
        if (isSheet) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    "Projects",
                    Modifier.weight(1f),
                    style = ppmText(15f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color),
                )
                if (q.isEmpty()) {
                    BasicText(
                        "${model.projects.size}",
                        Modifier.padding(end = 8.dp),
                        style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text3.color),
                    )
                } else {
                    BasicText(
                        "${shown.size} of ${model.projects.size}",
                        Modifier.padding(end = 8.dp),
                        style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text3.color),
                    )
                }
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .ppmClickable(pressedColor = tokens.panel2.color, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.X, 16.dp, tokens.text2.color)
                }
            }
        }

        // Server bar at top when multiple servers are saved (`Phone 2 · S3`, `Tablet 2 · PS3`)
        if (servers.size > 1) {
            ServerSwitcherHeaderBar(
                servers = servers,
                selectedServerId = selectedServerId,
                currentServerName = model.serverName,
                connectionLost = model.connectionLost,
                serverHealth = serverHealth,
                expanded = serverMenuOpen,
                onToggleExpanded = { serverMenuOpen = !serverMenuOpen },
                onSelectServer = { srv ->
                    serverMenuOpen = false
                    onDismiss()
                    model.switchServer(srv)
                },
                onAddServer = {
                    serverMenuOpen = false
                    onDismiss()
                    model.addingServer = true
                },
            )
        }

        // Search input
        Row(
            Modifier
                .fillMaxWidth()
                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(ProductIcon.Search, 15.dp, tokens.text3.color)
            PpmTextField(
                state = query,
                placeholder = "Search projects…",
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> {
                                highlightIdx = (highlightIdx + 1).coerceAtMost((shown.size - 1).coerceAtLeast(0))
                                true
                            }
                            Key.DirectionUp -> {
                                highlightIdx = (highlightIdx - 1).coerceAtLeast(0)
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                val target = shown.getOrNull(highlightIdx) ?: shown.firstOrNull()
                                if (target != null) {
                                    onDismiss()
                                    scope.launch { model.openProject(target.name) }
                                }
                                true
                            }
                            Key.Escape -> {
                                onDismiss()
                                true
                            }
                            else -> false
                        }
                    },
                style = ppmText(13f, lineHeight = 18f),
                focusRequester = searchFocus,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
            )
            if (query.text.isNotEmpty()) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .ppmClickable(pressedColor = tokens.panel2.color) { query.setTextAndPlaceCursorAtEnd("") },
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.X, 13.dp, tokens.text3.color)
                }
            }
        }

        // Sort bar (`Recent`, `Priority`, `Name`)
        Row(
            Modifier
                .fillMaxWidth()
                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for ((mode, label, icon) in PROJECT_SORTS) {
                val active = model.projectSortMode == mode
                val tint = if (active) tokens.accent.color else tokens.text3.color
                val shape = RoundedCornerShape(8.dp)
                Row(
                    Modifier
                        .weight(1f)
                        .clip(shape)
                        .background(if (active) tokens.accent.opacity(0.12).color else Color.Transparent)
                        .ppmClickable(pressedColor = tokens.panel2.color) { model.pickProjectSort(mode) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PpmIcon(icon, 13.dp, tint)
                    BasicText(
                        label,
                        style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tint),
                    )
                }
            }
        }

        // Project list
        val maxListHeight = LocalConfiguration.current.screenHeightDp.dp * 0.52f
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(max = maxListHeight)
                .cssScrollbar(listState),
            state = listState,
        ) {
            items(shown, key = { it.name }) { project ->
                val isSelected = project.name == model.selectedProject
                val canReorder = model.projectSortMode == "priority" && q.isEmpty()
                ProjectSwitcherRow(
                    model = model,
                    project = project,
                    selected = isSelected,
                    unread = unread,
                    canReorder = canReorder,
                    onOpen = {
                        if (model.phoneProjectRename != project.name) {
                            onDismiss()
                            scope.launch { model.openProject(project.name) }
                        }
                    },
                    onOpenActions = {
                        model.projectActionsCanReorder = canReorder
                        model.projectActions = project
                    },
                )
            }
            if (shown.isEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BasicText(
                            if (model.projects.isEmpty()) "No projects registered yet" else "No projects match \"${query.text}\"",
                            style = ppmText(13.5f, FontWeight.Medium, lineHeight = 18f).copy(
                                color = tokens.text2.color,
                                textAlign = TextAlign.Center,
                            ),
                        )
                        BasicText(
                            if (model.projects.isEmpty()) "Add a folder on the server or clone a Git repository." else "Try a different project name or path.",
                            style = ppmText(12f, lineHeight = 16f).copy(
                                color = tokens.text3.color,
                                textAlign = TextAlign.Center,
                            ),
                        )
                    }
                }
            }
        }

        // Git repository status section (`P7 · Not a git repository` or `P8 · Multiple git repositories`)
        val currentProj = model.currentProject
        val reposState = activeGitRepos
        if (currentProj != null && reposState != null) {
            if (!reposState.rootIsRepo && reposState.repos.isEmpty()) {
                NoGitRepoBanner(
                    projectName = currentProj.name,
                    initializing = initializingGit,
                    onInitGit = {
                        val api = model.api ?: return@NoGitRepoBanner
                        if (initializingGit) return@NoGitRepoBanner
                        initializingGit = true
                        scope.launch {
                            try {
                                api.fsMkdir(currentProj.path, gitInit = true)
                                activeGitRepos = runCatching { api.gitRepos(currentProj.name) }.getOrNull()
                                model.toast(ErrorToastMessage("Initialized Git repository", kind = ErrorToastMessage.Kind.SUCCESS))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (e: Exception) {
                                model.toast(ErrorToastMessage(e.message ?: "Could not initialize Git repository"))
                            } finally {
                                initializingGit = false
                            }
                        }
                    },
                )
            } else if (GitRepoScope.needsPick(reposState, null)) {
                MultiGitRepoPickerBar(
                    repos = reposState.repos,
                    selectedPath = selectedSubRepoPath ?: reposState.repos.firstOrNull()?.path,
                    onSelectRepo = { repo -> selectedSubRepoPath = repo.path },
                )
            }
        }

        // Add Project action row
        Row(
            Modifier
                .fillMaxWidth()
                .cssBorder(tokens.border.color, sides = CssSides.Top)
                .ppmClickable(pressedColor = tokens.panel2.color) {
                    onDismiss()
                    model.addingProject = true
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PpmIcon(ProductIcon.Plus, 18.dp, tokens.accent.color)
            BasicText(
                "Add Project",
                style = ppmText(13.5f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.accent.color),
            )
        }

        // Settings + Server version footer
        if (isSheet) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .cssBorder(tokens.border.color, sides = CssSides.Top)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .ppmClickable(pressedColor = Color.Transparent) {
                            onDismiss()
                            model.openSettings()
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PpmIcon(ProductIcon.Settings, 16.dp, tokens.text2.color)
                    BasicText(
                        "Settings",
                        style = ppmText(13.5f, lineHeight = 18f).copy(color = tokens.text2.color),
                    )
                }
                model.info?.version?.let { ver ->
                    BasicText(
                        "v$ver",
                        style = ppmText(11.5f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text3.color),
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerSwitcherHeaderBar(
    servers: List<ServerProfile>,
    selectedServerId: String?,
    currentServerName: String,
    connectionLost: Boolean,
    serverHealth: Map<String, Boolean>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSelectServer: (ServerProfile) -> Unit,
    onAddServer: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .ppmClickable(pressedColor = tokens.panel2.color, onClick = onToggleExpanded)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val dotColor = if (connectionLost) tokens.warning.color else tokens.success.color
            Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
            PpmIcon(ProductIcon.Monitor, 14.dp, tokens.text2.color)
            BasicText(
                currentServerName,
                Modifier.weight(1f),
                style = ppmText(12.5f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                "${servers.size} servers",
                style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text3.color),
            )
            PpmIcon(
                if (expanded) ProductIcon.ChevronUp else ProductIcon.ChevronDown,
                14.dp,
                tokens.text3.color,
            )
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .cssBorder(tokens.borderSoft.color, sides = CssSides.Top)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (srv in servers) {
                    val isCurrent = srv.id == selectedServerId
                    val healthy = if (isCurrent) !connectionLost else (serverHealth[srv.id] ?: true)
                    val shape = RoundedCornerShape(8.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(if (isCurrent) tokens.accent.opacity(0.1).color else Color.Transparent)
                            .ppmClickable(pressedColor = tokens.panel2.color) { onSelectServer(srv) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (healthy) tokens.success.color else tokens.warning.color),
                        )
                        Column(Modifier.weight(1f)) {
                            BasicText(
                                srv.name,
                                style = ppmText(12.5f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            BasicText(
                                srv.url,
                                style = ppmText(11f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text3.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (isCurrent) {
                            PpmIcon(ProductIcon.Check, 14.dp, tokens.accent.color)
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .ppmClickable(pressedColor = tokens.panel2.color, onClick = onAddServer)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PpmIcon(ProductIcon.Plus, 14.dp, tokens.accent.color)
                    BasicText(
                        "Add another server…",
                        style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.accent.color),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectSwitcherRow(
    model: AppModel,
    project: ProjectInfo,
    selected: Boolean,
    unread: Map<String, SessionUnread>,
    canReorder: Boolean,
    onOpen: () -> Unit,
    onOpenActions: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val branch = model.gitBranches[project.name]?.branch
    val urgentKind = model.urgentUnread(project.name, unread)
    val openedAt = model.projectOpenedAt[project.name]

    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) tokens.accent.opacity(0.08).color else Color.Transparent)
            .pointerInput(project.name, canReorder) {
                detectTapGestures(
                    onTap = { onOpen() },
                    onLongPress = { onOpenActions() },
                )
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (canReorder) {
            PpmIcon(ProductIcon.GripVertical, 14.dp, tokens.text3.color)
        }
        Box {
            ProjectAvatar(model, project, 36.dp, square = true)
            if (urgentKind != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(2.dp, (-2).dp)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(tokens.notification(urgentKind).color)
                        .cssBorder(tokens.bgSolid.color, CircleShape, width = 2.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (model.phoneProjectRename == project.name) {
                InlineProjectRename(model, project)
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BasicText(
                        project.name,
                        Modifier.weight(1f, fill = false),
                        style = ppmText(
                            13.5f,
                            if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            lineHeight = 18f,
                        ).copy(color = tokens.text.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!branch.isNullOrBlank()) {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(tokens.panel2.color)
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            PpmIcon(ProductIcon.GitBranch, 10.dp, tokens.text3.color)
                            BasicText(
                                branch,
                                style = ppmText(10.5f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text2.color),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            BasicText(
                project.path,
                style = ppmText(11.5f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text3.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (openedAt != null && !selected) {
            RelativeTime(
                Instant.ofEpochMilli(openedAt),
                ppmText(10.5f, lineHeight = 14f).copy(color = tokens.text3.color),
            )
        }
        if (selected) {
            PpmIcon(ProductIcon.Check, 16.dp, tokens.accent.color)
        }
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .ppmClickable(pressedColor = tokens.panel2.color, onClick = onOpenActions),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.MoreVertical, 14.dp, tokens.text3.color)
        }
    }
}

/**
 * `P7 · Not a git repository`: shown when the active project has no `.git` directory.
 */
@Composable
fun NoGitRepoBanner(
    projectName: String,
    initializing: Boolean,
    onInitGit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier
            .fillMaxWidth()
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Top)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PpmIcon(LucideIcon.FolderGit2, 16.dp, tokens.text3.color)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BasicText(
                "$projectName is not a Git repository",
                style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
            )
            BasicText(
                "Initialize Git to track changes and branches",
                style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
            )
        }
        val btnShape = RoundedCornerShape(8.dp)
        Box(
            Modifier
                .height(30.dp)
                .clip(btnShape)
                .background(tokens.accent.opacity(0.14).color)
                .cssBorder(tokens.accent.opacity(0.35).color, btnShape)
                .ppmClickable(enabled = !initializing, pressedColor = tokens.accent.opacity(0.22).color, onClick = onInitGit)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                if (initializing) "Initializing…" else "Initialize repository",
                style = ppmText(11.5f, FontWeight.SemiBold, lineHeight = 15f).copy(color = tokens.accent.color),
            )
        }
    }
}

/**
 * `P8 · Multiple git repositories inside one project`: shown when a workspace contains multiple nested git repositories.
 */
@Composable
fun MultiGitRepoPickerBar(
    repos: List<GitRepo>,
    selectedPath: String?,
    onSelectRepo: (GitRepo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    Column(
        modifier
            .fillMaxWidth()
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Top)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(LucideIcon.FolderGit2, 14.dp, tokens.text2.color)
            BasicText(
                "Repositories in workspace (${repos.size})",
                style = ppmText(11.5f, FontWeight.SemiBold, lineHeight = 15f).copy(color = tokens.text2.color),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (repo in repos) {
                val active = repo.path == selectedPath
                val pillShape = RoundedCornerShape(6.dp)
                Row(
                    Modifier
                        .clip(pillShape)
                        .background(if (active) tokens.accent.opacity(0.14).color else tokens.panel2.color)
                        .cssBorder(if (active) tokens.accent.color else tokens.border.color, pillShape)
                        .ppmClickable(pressedColor = tokens.panel.color) { onSelectRepo(repo) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PpmIcon(ProductIcon.GitBranch, 11.dp, if (active) tokens.accent.color else tokens.text3.color)
                    BasicText(
                        repo.relative.ifBlank { repo.name },
                        style = ppmText(11f, kind = FontKind.MONO, lineHeight = 14f).copy(
                            color = if (active) tokens.accent.color else tokens.text2.color,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
