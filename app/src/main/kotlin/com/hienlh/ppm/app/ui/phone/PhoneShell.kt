package com.hienlh.ppm.app.ui.phone

import com.hienlh.ppm.design.kit.SheetScrimLayerHost
import com.hienlh.ppm.design.kit.SheetScrimLayer
import com.hienlh.ppm.design.kit.LocalSheetScrimLayer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.app.tasks
import com.hienlh.ppm.app.openAssistant
import com.hienlh.ppm.app.ui.phone.notifications.NotificationsSheet
import com.hienlh.ppm.app.ui.phone.onboarding.PhoneOnboardingController
import com.hienlh.ppm.app.ui.phone.onboarding.PhoneOnboardingHost
import com.hienlh.ppm.app.ui.phone.onboarding.rememberPhoneOnboardingController
import com.hienlh.ppm.app.ui.phone.specs.PhoneSpecStartSheet
import com.hienlh.ppm.app.specs
import com.hienlh.ppm.app.ui.projects.ProjectSwitcherSheet
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.kit.AppBar
import com.hienlh.ppm.design.kit.AppBarAction
import com.hienlh.ppm.design.kit.AppBarConnectionState
import com.hienlh.ppm.design.kit.BottomActionCard
import com.hienlh.ppm.design.kit.DrawerNavItem
import com.hienlh.ppm.design.kit.DrawerProjectInfo
import com.hienlh.ppm.design.kit.DrawerToolTileItem
import com.hienlh.ppm.design.kit.KitSpec
import com.hienlh.ppm.design.kit.NewTabOption
import com.hienlh.ppm.design.kit.PhoneDrawer
import com.hienlh.ppm.design.kit.TabCardItem
import com.hienlh.ppm.design.kit.TabDotKind
import com.hienlh.ppm.design.kit.TabSwitcherSheet
import com.hienlh.ppm.design.kit.RowAction
import com.hienlh.ppm.design.kit.RowActionsSheet
import com.hienlh.ppm.design.themeBackground
import com.hienlh.ppm.design.vector
import com.hienlh.ppm.state.workspace.WorkspaceTab
import com.hienlh.ppm.state.workspace.tabSessionId

/**
 * Redesigned phone shell (`designs/ppm-mobile-tablet-redesign/foundation/index.html`,
 * Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 3.1, 3.2, 3.3).
 *
 * Structure:
 * - Top: [AppBar] with `☰` (or Back arrow on pushed pages), tappable tab title (opens
 *   [TabSwitcherSheet]), and at most 2 right actions ([KitSpec.APP_BAR_MAX_ACTIONS]).
 * - Middle: active phone area or [WorkspaceTab] content, routing tablet-only tabs to
 *   [OpenOnTablet] while preserving them untouched in `WorkspaceStore`.
 * - Bottom: optional [BottomActionCard] when a screen provides a primary action — never a
 *   5-tab bottom bar.
 * - Left overlay: [PhoneDrawer] with project switcher, phone workspace screens, 2×2 tool tiles,
 *   Notifications, and Settings.
 * - Bottom sheets: [TabSwitcherSheet], [ProjectSwitcherSheet], [NotificationsSheet], and
 *   Cloud & Share ([PhoneCloudShareAreaHost]).
 * - First-launch onboarding: [PhoneOnboardingHost] controlled by [rememberPhoneOnboardingController].
 */
@Composable
fun PhoneShell(
    model: AppModel,
    nav: PhoneNav = rememberPhoneNav(model.activeTabId),
    prefs: PrefsStore? = null,
    onboardingController: PhoneOnboardingController? = null,
    overrideOpenTabs: List<WorkspaceTab>? = null,
    overrideActiveTab: WorkspaceTab? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    val resolvedPrefs = remember(prefs, context) {
        prefs ?: PrefsStore(context)
    }
    val resolvedOnboarding = onboardingController
        ?: rememberPhoneOnboardingController(resolvedPrefs)

    // A server in minimal mode offers the chat, its files and Settings only (`--minimal`).
    val minimal = model.info?.minimalMode == true
    val openTabPairs = model.openTabs
    val openTabs = overrideOpenTabs ?: openTabPairs.map { it.first }
    val openTabIds = openTabs.mapTo(LinkedHashSet()) { it.id }
    val activeWorkspaceTab = overrideActiveTab
        ?: openTabs.firstOrNull { it.id == nav.currentTabId }
        ?: model.activeTab
    val activeWorkspaceTabId = activeWorkspaceTab?.id
    // The tab whose long-press menu is open in the tab switcher.
    var tabMenuFor by remember { mutableStateOf<String?>(null) }

    // Synchronize workspace tab changes into PhoneNav while keeping synthetic area views intact.
    LaunchedEffect(openTabIds, activeWorkspaceTabId) {
        nav.syncOpenTabs(openTabIds = openTabIds, activeTabId = activeWorkspaceTabId)
    }

    // A chat that starts as a spec gets its spec once its first message makes the session.
    LaunchedEffect(openTabIds) {
        model.specs.armPending(openTabs)
    }

    // `openSpecs()` / `openSpec()` from anywhere (a chat's Spec chip, a notification): show the Specs area.
    val specsRequested = model.specs.showRequested
    LaunchedEffect(specsRequested) {
        if (specsRequested) {
            model.specs.showRequested = false
            if (!minimal) nav.visitTab(PhoneNav.areaTabId(PhoneArea.SPECS))
        }
    }

    // Handle legacy sidebar/drawer open requests bridged from AppModel.showSidebarSection.
    val drawerRequest = model.mobileDrawerOpenRequest
    LaunchedEffect(drawerRequest) {
        if (drawerRequest != null) {
            model.mobileDrawerOpenRequest = null
            when (drawerRequest) {
                "explorer" -> nav.visitTab(PhoneNav.areaTabId(PhoneArea.FILES))
                "git" -> if (!minimal) nav.visitTab(PhoneNav.areaTabId(PhoneArea.SOURCE_CONTROL))
                "database" -> if (!minimal) nav.visitTab(PhoneNav.areaTabId(PhoneArea.DATABASE))
                "tunnels" -> if (!minimal) nav.visitTab(PhoneNav.areaTabId(PhoneArea.PORT_FORWARDING))
                "specs" -> if (!minimal) nav.visitTab(PhoneNav.areaTabId(PhoneArea.SPECS))
                "tasks" -> if (!minimal) nav.visitTab(PhoneNav.areaTabId(PhoneArea.TASKS))
                "history" -> nav.visitTab(PhoneNav.areaTabId(PhoneArea.CHAT))
                else -> nav.openDrawer()
            }
        }
    }

    // System Back button handling (Requirement 2.5):
    // 1. Top sheet -> 2. Drawer -> 3. Previous tab in history -> 4. Exit.
    BackHandler(enabled = nav.canHandleBack && !model.connectionLost) {
        when (val result = nav.back()) {
            is BackResult.ClosedSheet -> Unit
            BackResult.ClosedDrawer -> Unit
            is BackResult.PoppedTab -> {
                if (PhoneNav.parseAreaTabId(result.tabId) == null) {
                    model.activate(result.tabId)
                }
            }
            BackResult.Exit -> Unit
        }
    }

    val unreadMap = model.events?.unread?.collectAsState()?.value.orEmpty()
    val runningSet = model.events?.running?.collectAsState()?.value.orEmpty()
    val projectUnread = model.unread(model.selectedProject, unreadMap)
    val totalUnreadCount = unreadMap.size
    val hasUnreadAttention = totalUnreadCount > 0 || model.showLogsBadgeDot
    val hasRunningChat = runningSet.isNotEmpty()

    val syntheticArea = PhoneNav.parseAreaTabId(nav.currentTabId)
    val activeArea = syntheticArea ?: activeWorkspaceTab?.let(PhoneArea::fromTab)

    val projectName = model.selectedProject ?: model.serverName
    val currentBranch = model.selectedProject?.let { model.gitBranches[it]?.branch }
    val gitChangesCount = model.gitChangeCount(model.selectedProject)
    val terminalTabCount = openTabs.count { it.type == "terminal" }

    val connectionState = when {
        model.connectionLost -> AppBarConnectionState.Reconnecting
        model.api == null -> AppBarConnectionState.Disconnected
        else -> AppBarConnectionState.Connected
    }
    val connectionLabel = when (connectionState) {
        AppBarConnectionState.Connected -> "Live"
        AppBarConnectionState.Reconnecting -> "Reconnecting…"
        AppBarConnectionState.Disconnected -> "Offline"
        AppBarConnectionState.None -> ""
    }

    val specs = model.specs
    val onOpenSpec = syntheticArea == PhoneArea.SPECS && specs.openSlug != null
    // A spec opened from the list goes back to the list; one a chat opened goes back to the chat.
    val specBack = onOpenSpec && specs.openedFromList
    val titleText = when {
        onOpenSpec -> specs.openTitle ?: "Spec"
        syntheticArea != null -> syntheticArea.title
        activeWorkspaceTab != null -> activeWorkspaceTab.title.ifBlank {
            PhoneArea.fromTab(activeWorkspaceTab)?.title ?: activeWorkspaceTab.type
        }
        else -> projectName
    }

    val activeTabIndex = activeWorkspaceTab?.let { tab ->
        openTabs.indexOfFirst { it.id == tab.id }.takeIf { it >= 0 }?.plus(1)
    }
    val sublineText = buildString {
        append(projectName)
        if (activeTabIndex != null && openTabs.isNotEmpty() && syntheticArea == null) {
            append(" · ")
            append(activeTabIndex)
            append(" of ")
            append(openTabs.size)
            append(if (openTabs.size == 1) " tab" else " tabs")
        }
        if (connectionLabel.isNotEmpty()) {
            append(" · ")
            append(connectionLabel)
        }
    }

    // Foundation Section 1: at most 2 right-hand slots on every phone screen.
    val appBarActions = buildPhoneAppBarActions(
        activeArea = activeArea,
        syntheticArea = syntheticArea,
        hasRunningChat = hasRunningChat,
        runningDotColor = tokens.success.color,
        onOpenChatHistory = { nav.visitTab(PhoneNav.areaTabId(PhoneArea.CHAT)) },
        onOpenTerminal = { focusOrOpenTerminal(model, openTabs, nav) },
        onNewChat = {
            model.newChat()
            model.activeTabId?.let(nav::visitTab)
        },
        onNewTerminal = {
            model.openTerminal(inDock = false)
            model.activeTabId?.let(nav::visitTab)
        },
        onRefreshGit = { model.refreshGitStatus() },
        onBackToChat = { focusOrOpenChat(model, openTabs, nav) },
        minimal = minimal,
    )

    val isPushedPage = syntheticArea != null || activeWorkspaceTab?.type == "settings"

    val sheetScrim = remember { SheetScrimLayer() }
    CompositionLocalProvider(LocalSheetScrimLayer provides sheetScrim) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .themeBackground(tokens.background),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // The app bar's own background runs up behind the status bar, as one header.
            AppBar(
                statusBarsPadding = true,
                title = titleText,
                subline = sublineText,
                connectionState = connectionState,
                hasUnreadAttention = hasUnreadAttention,
                onDrawerClick = if (!specBack && (!isPushedPage || nav.history.isEmpty())) {
                    { nav.openDrawer() }
                } else {
                    null
                },
                onBack = if (specBack) {
                    { specs.closeSpec() }
                } else if (isPushedPage && nav.history.isNotEmpty()) {
                    {
                        val res = nav.back()
                        if (res is BackResult.PoppedTab && PhoneNav.parseAreaTabId(res.tabId) == null) {
                            model.activate(res.tabId)
                        }
                    }
                } else {
                    null
                },
                onTitleClick = { nav.pushSheet(SheetKey.TabSwitcher) },
                actions = appBarActions,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when {
                    syntheticArea != null -> {
                        PhoneWorkspaceAreaBody(
                            model = model,
                            area = syntheticArea,
                            tab = null,
                            nav = nav,
                            showChatHistoryInitially = syntheticArea == PhoneArea.CHAT,
                        )
                    }
                    activeWorkspaceTab != null -> {
                        when (val route = resolvePhoneTabRoute(activeWorkspaceTab)) {
                            is PhoneTabRoute.TabletOnly -> {
                                // Requirement 3.2 & 3.3: show OpenOnTablet without removing the tab from WorkspaceStore.
                                OpenOnTablet(
                                    tab = route.tab,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            is PhoneTabRoute.Area -> {
                                PhoneWorkspaceAreaBody(
                                    model = model,
                                    area = route.area,
                                    tab = route.tab,
                                    nav = nav,
                                )
                            }
                        }
                    }
                    else -> {
                        PhoneChatAreaHost(
                            model = model,
                            tab = null,
                            nav = nav,
                            showHistoryInitially = false,
                        )
                    }
                }
            }

            if (bottomAction != null) {
                BottomActionCard {
                    bottomAction()
                }
            }
        }

        // A sheet opened in the page dims the app bar and the status bar above it too.
        SheetScrimLayerHost(sheetScrim)

        // Left navigation drawer (Requirement 2.2)
        val drawerSubline = buildString {
            if (!currentBranch.isNullOrBlank()) {
                append(currentBranch)
                append(" · ")
            }
            append(if (connectionState == AppBarConnectionState.Connected) "Connected" else connectionLabel)
        }

        PhoneDrawer(
            open = nav.drawerOpen,
            project = DrawerProjectInfo(
                name = projectName,
                subline = drawerSubline,
                serverConnected = connectionState == AppBarConnectionState.Connected,
            ),
            workspaceItems = listOf(
                DrawerNavItem(
                    id = PhoneArea.CHAT.id,
                    label = "Chat History",
                    icon = ProductIcon.MessageSquare,
                    selected = activeArea == PhoneArea.CHAT,
                    countBadge = projectUnread.size.takeIf { it > 0 }?.toString(),
                    badgeAlert = projectUnread.isNotEmpty(),
                    onClick = {
                        nav.closeDrawer()
                        handleDrawerSelection(model, PhoneArea.CHAT.id, openTabs, nav)
                    },
                ),
                DrawerNavItem(
                    id = PhoneArea.TASKS.id,
                    label = PhoneArea.TASKS.title,
                    icon = ProductIcon.ListTodo,
                    selected = activeArea == PhoneArea.TASKS,
                    countBadge = model.tasks.railCount().takeIf { it > 0 }?.toString(),
                    onClick = {
                        nav.closeDrawer()
                        handleDrawerSelection(model, PhoneArea.TASKS.id, openTabs, nav)
                    },
                ),
                DrawerNavItem(
                    id = PhoneArea.SPECS.id,
                    label = PhoneArea.SPECS.title,
                    icon = ProductIcon.ClipboardList,
                    selected = activeArea == PhoneArea.SPECS,
                    onClick = {
                        nav.closeDrawer()
                        handleDrawerSelection(model, PhoneArea.SPECS.id, openTabs, nav)
                    },
                ),
                DrawerNavItem(
                    id = PhoneArea.FILES.id,
                    label = PhoneArea.FILES.title,
                    icon = ProductIcon.Folder,
                    selected = activeArea == PhoneArea.FILES,
                    onClick = {
                        nav.closeDrawer()
                        handleDrawerSelection(model, PhoneArea.FILES.id, openTabs, nav)
                    },
                ),
                DrawerNavItem(
                    id = PhoneArea.SOURCE_CONTROL.id,
                    label = PhoneArea.SOURCE_CONTROL.title,
                    icon = ProductIcon.GitBranch,
                    selected = activeArea == PhoneArea.SOURCE_CONTROL,
                    countBadge = gitChangesCount.takeIf { it > 0 }?.toString(),
                    onClick = {
                        nav.closeDrawer()
                        handleDrawerSelection(model, PhoneArea.SOURCE_CONTROL.id, openTabs, nav)
                    },
                ),
                DrawerNavItem(
                    id = PhoneArea.TERMINAL.id,
                    label = PhoneArea.TERMINAL.title,
                    icon = ProductIcon.TerminalSquare,
                    selected = activeArea == PhoneArea.TERMINAL,
                    countBadge = terminalTabCount.takeIf { it > 0 }?.toString(),
                    onClick = {
                        nav.closeDrawer()
                        handleDrawerSelection(model, PhoneArea.TERMINAL.id, openTabs, nav)
                    },
                ),
                DrawerNavItem(
                    id = PhoneArea.DATABASE.id,
                    label = PhoneArea.DATABASE.title,
                    icon = ProductIcon.Database,
                    selected = activeArea == PhoneArea.DATABASE,
                    onClick = {
                        nav.closeDrawer()
                        handleDrawerSelection(model, PhoneArea.DATABASE.id, openTabs, nav)
                    },
                ),
            ).filter { item -> PhoneArea.fromId(item.id)?.let { PhoneArea.isAvailable(it, minimal) } ?: true },
            toolTiles = listOf(
                DrawerToolTileItem(
                    id = PhoneArea.PORT_FORWARDING.id,
                    title = "Ports",
                    subtitle = "Forwarding",
                    icon = ProductIcon.Plug,
                    onClick = {
                        nav.closeDrawer()
                        handleDrawerSelection(model, PhoneArea.PORT_FORWARDING.id, openTabs, nav)
                    },
                ),
                DrawerToolTileItem(
                    id = PhoneArea.MONITOR.id,
                    title = PhoneArea.MONITOR.title,
                    subtitle = "System",
                    icon = ProductIcon.Activity,
                    onClick = {
                        nav.closeDrawer()
                        handleDrawerSelection(model, PhoneArea.MONITOR.id, openTabs, nav)
                    },
                ),
                DrawerToolTileItem(
                    id = PhoneArea.LOGS.id,
                    title = PhoneArea.LOGS.title,
                    subtitle = if (model.showLogsBadgeDot) "Issues" else "Server",
                    icon = ProductIcon.ScrollText,
                    dotColor = if (model.showLogsBadgeDot) tokens.error.color else null,
                    onClick = {
                        nav.closeDrawer()
                        handleDrawerSelection(model, PhoneArea.LOGS.id, openTabs, nav)
                    },
                ),
                DrawerToolTileItem(
                    id = PhoneArea.CLOUD_AND_SHARE.id,
                    title = PhoneArea.CLOUD_AND_SHARE.title,
                    subtitle = if (!model.namedHostname.isNullOrBlank()) "Live" else "Tunnel",
                    icon = ProductIcon.Cloud,
                    dotColor = if (!model.namedHostname.isNullOrBlank()) tokens.success.color else null,
                    onClick = {
                        nav.closeDrawer()
                        handleDrawerSelection(model, PhoneArea.CLOUD_AND_SHARE.id, openTabs, nav)
                    },
                ),
                com.hienlh.ppm.app.ui.assistant.assistantDrawerTile(model) {
                    nav.closeDrawer()
                    handleDrawerSelection(model, PhoneArea.ASSISTANT.id, openTabs, nav)
                },
                DrawerToolTileItem(
                    id = PhoneArea.SCHEDULES.id,
                    title = PhoneArea.SCHEDULES.title,
                    subtitle = "Runs & logs",
                    icon = ProductIcon.CalendarClock,
                    onClick = {
                        nav.closeDrawer()
                        handleDrawerSelection(model, PhoneArea.SCHEDULES.id, openTabs, nav)
                    },
                ),
            ).filter { tile -> PhoneArea.fromId(tile.id)?.let { PhoneArea.isAvailable(it, minimal) } ?: true },
            onProjectClick = {
                nav.closeDrawer()
                nav.pushSheet(SheetKey.ProjectSwitcher)
            },
            onNotificationsClick = {
                nav.closeDrawer()
                nav.pushSheet(SheetKey.Notifications)
            },
            onSettingsClick = {
                nav.closeDrawer()
                model.openSettings()
                model.activeTabId?.let(nav::visitTab)
            },
            onDismiss = { nav.closeDrawer() },
            unreadNotifications = totalUnreadCount,
            settingsSelected = activeArea == PhoneArea.SETTINGS,
        )

        // Tab switcher bottom sheet (Requirement 2.3)
        val switcherTabs = openTabs.filter { !minimal || PhoneArea.isMinimalTabType(it.type) }
        if (nav.topSheet == SheetKey.TabSwitcher) {
            TabSwitcherSheet(
                tabs = switcherTabs.map { tab ->
                    val sessionId = tabSessionId(tab)
                    val isUnread = sessionId != null && unreadMap.containsKey(sessionId)
                    val isRunning = sessionId != null && runningSet.contains(sessionId)
                    val tabletOnly = isTabletOnlyTab(tab)
                    val dotKind = when {
                        isUnread -> TabDotKind.Attention
                        isRunning -> TabDotKind.Running
                        else -> TabDotKind.None
                    }
                    TabCardItem(
                        id = tab.id,
                        title = tab.title.ifBlank { tab.type },
                        subtitle = when {
                            tabletOnly -> "Opens on a tablet"
                            isRunning -> "Running…"
                            isUnread -> "Needs your attention"
                            else -> PhoneArea.fromTab(tab)?.title ?: tab.type
                        },
                        icon = phoneTabIconVector(tab.type),
                        active = syntheticArea == null && tab.id == activeWorkspaceTabId,
                        dotKind = dotKind,
                        pinned = tab.pinned,
                    )
                },
                onUnpinTab = { tabId -> model.setTabPinned(tabId, false) },
                onLongPressTab = { tabId -> tabMenuFor = tabId },
                onSelectTab = { tabId ->
                    nav.closeSheet(SheetKey.TabSwitcher)
                    nav.visitTab(tabId)
                    model.activate(tabId)
                },
                onCloseTab = { tabId ->
                    model.closeTab(tabId)
                    nav.onTabClosed(tabId, model.activeTabId)
                },
                onDismiss = { nav.closeSheet(SheetKey.TabSwitcher) },
                newTabOptions = listOf(
                    NewTabOption(
                        id = PhoneArea.CHAT.id,
                        label = "Chat",
                        icon = ProductIcon.MessageSquare,
                        onClick = {
                            nav.closeSheet(SheetKey.TabSwitcher)
                            model.newChat()
                            model.activeTabId?.let(nav::visitTab)
                        },
                    ),
                    NewTabOption(
                        id = PhoneArea.TERMINAL.id,
                        label = "Terminal",
                        icon = ProductIcon.TerminalSquare,
                        onClick = {
                            nav.closeSheet(SheetKey.TabSwitcher)
                            model.openTerminal(inDock = false)
                            model.activeTabId?.let(nav::visitTab)
                        },
                    ),
                    NewTabOption(
                        id = PhoneArea.FILES.id,
                        label = "Files",
                        icon = ProductIcon.Folder,
                        onClick = {
                            nav.closeSheet(SheetKey.TabSwitcher)
                            nav.visitTab(PhoneNav.areaTabId(PhoneArea.FILES))
                        },
                    ),
                    NewTabOption(
                        id = PhoneArea.DATABASE.id,
                        label = "Database",
                        icon = ProductIcon.Database,
                        onClick = {
                            nav.closeSheet(SheetKey.TabSwitcher)
                            nav.visitTab(PhoneNav.areaTabId(PhoneArea.DATABASE))
                        },
                    ),
                ).filter { option -> PhoneArea.fromId(option.id)?.let { PhoneArea.isAvailable(it, minimal) } ?: true },
            )
        }

        // A tab's long-press menu: Pin / Unpin and the closes, which leave pinned tabs open (`mobile-nav.tsx`).
        val menuTab = tabMenuFor?.let { id -> switcherTabs.firstOrNull { it.id == id } }
        if (menuTab != null) {
            // The tabs of the menu tab's panel, as the web's menu counts them; a list handed in has no panels.
            val panelId = openTabPairs.firstOrNull { it.first.id == menuTab.id }?.second
            val panelTabs = if (panelId == null) switcherTabs else switcherTabs.filter { tab -> openTabPairs.any { it.first.id == tab.id && it.second == panelId } }
            val minimalShown = { tab: WorkspaceTab -> !minimal || PhoneArea.isMinimalTabType(tab.type) }
            RowActionsSheet(
                title = menuTab.title.ifBlank { menuTab.type },
                actions = phoneTabMenuItems(menuTab, panelTabs).map { item ->
                    RowAction(item.id, item.label, item.icon) {
                        when {
                            item.bulk != null -> model.bulkCloseTabs(menuTab.id, item.bulk, shown = minimalShown)
                            item.id == "close" -> {
                                model.closeTab(menuTab.id)
                                nav.onTabClosed(menuTab.id, model.activeTabId)
                            }
                            else -> model.setTabPinned(menuTab.id, !menuTab.pinned)
                        }
                    }
                },
                onDismiss = { tabMenuFor = null },
            )
        }

        // Project switcher sheet (Task 9)
        if (nav.topSheet == SheetKey.ProjectSwitcher) {
            ProjectSwitcherSheet(
                model = model,
                onDismiss = { nav.closeSheet(SheetKey.ProjectSwitcher) },
                asFlyoutCard = false,
                modifier = Modifier.testTag("project-switcher-sheet"),
            )
        }

        // Notifications sheet (Task 10)
        if (nav.topSheet == SheetKey.Notifications) {
            NotificationsSheet(
                model = model,
                onDismiss = { nav.closeSheet(SheetKey.Notifications) },
                modifier = Modifier.testTag("notifications-sheet"),
            )
        }

        // Cloud & Share sheet (Task 19)
        if (nav.topSheet == SheetKey.CloudShare) {
            PhoneCloudShareAreaHost(
                model = model,
                onDismiss = { nav.closeSheet(SheetKey.CloudShare) },
            )
        }

        // The workflow chooser a chat asked for (`startSpec`).
        val choosingFor = specs.choosingFor
        if (choosingFor != null) {
            BackHandler { specs.choosingFor = null }
            PhoneSpecStartSheet(
                model = model,
                chatTabId = choosingFor,
                onDismiss = { specs.choosingFor = null },
            )
        }

        // First-launch onboarding (Task 9)
        PhoneOnboardingHost(
            model = model,
            controller = resolvedOnboarding,
            onOpenProjectSwitcher = { nav.pushSheet(SheetKey.ProjectSwitcher) },
            onOpenNewChat = {
                model.newChat()
                model.activeTabId?.let(nav::visitTab)
            },
            onInsertDraftPrompt = { prompt ->
                model.newChat(pendingMessage = prompt)
                model.activeTabId?.let(nav::visitTab)
            },
            onOpenFiles = { nav.visitTab(PhoneNav.areaTabId(PhoneArea.FILES)) },
            onOpenTerminal = { focusOrOpenTerminal(model, openTabs, nav) },
            onOpenSourceControl = { nav.visitTab(PhoneNav.areaTabId(PhoneArea.SOURCE_CONTROL)) },
        )
    }
    }
}

/**
 * Reusable scaffold for a phone screen that places its primary action inside [BottomActionCard]
 * above the system navigation bar and keyboard (Requirement 2.4).
 */
@Composable
fun PhoneScreenScaffold(
    modifier: Modifier = Modifier,
    bottomAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            content()
        }
        if (bottomAction != null) {
            BottomActionCard {
                bottomAction()
            }
        }
    }
}

internal fun phoneTabIconVector(type: String): ImageVector = when (type) {
    "chat" -> ProductIcon.MessageSquare.vector
    "editor", "file", "files" -> ProductIcon.FileText.vector
    "terminal" -> ProductIcon.TerminalSquare.vector
    "database", "db-structure", "db-sql" -> ProductIcon.Table.vector
    "db-query" -> ProductIcon.FileCode.vector
    "db-impexp" -> ProductIcon.ArrowUpDown.vector
    "db-connection" -> ProductIcon.Plug.vector
    "sqlite" -> ProductIcon.Database.vector
    "git-graph" -> ProductIcon.GitBranch.vector
    "git-diff", "git-review", "branch-review", "session-review" -> LucideIcon.FileDiff.vector
    "git-status", "source-control" -> ProductIcon.GitBranch.vector
    "conflict-editor" -> ProductIcon.GitMerge.vector
    "git-log" -> LucideIcon.GitCommitHorizontal.vector
    "settings" -> ProductIcon.Settings.vector
    "system-monitor", "monitor" -> ProductIcon.Cpu.vector
    "logs" -> ProductIcon.ScrollText.vector
    "schedules" -> ProductIcon.CalendarClock.vector
    "ports", "port-forwarding" -> ProductIcon.Plug.vector
    "tunnels", "cloud-share" -> ProductIcon.Cloud.vector
    "extension" -> ProductIcon.Puzzle.vector
    "group" -> ProductIcon.Users.vector
    "design" -> ProductIcon.Palette.vector
    "problems" -> ProductIcon.AlertTriangle.vector
    "web-preview" -> ProductIcon.Globe.vector
    else -> ProductIcon.FileText.vector
}

internal fun buildPhoneAppBarActions(
    activeArea: PhoneArea?,
    syntheticArea: PhoneArea?,
    hasRunningChat: Boolean,
    runningDotColor: Color? = null,
    onOpenChatHistory: () -> Unit,
    onOpenTerminal: () -> Unit,
    onNewChat: () -> Unit,
    onNewTerminal: () -> Unit,
    onRefreshGit: () -> Unit,
    onBackToChat: () -> Unit,
    minimal: Boolean = false,
): List<AppBarAction> {
    if (activeArea == PhoneArea.CHAT && syntheticArea == null) {
        return listOfNotNull(
            AppBarAction(
                icon = ProductIcon.History,
                label = "Chat history",
                onClick = onOpenChatHistory,
            ),
            // Minimal mode has no terminal.
            if (minimal) {
                null
            } else {
                AppBarAction(
                    icon = ProductIcon.TerminalSquare,
                    label = "Open terminal",
                    onClick = onOpenTerminal,
                )
            },
        )
    }

    val backToChat = AppBarAction(
        icon = ProductIcon.MessageSquare,
        label = "Back to the chat",
        badgeDotColor = if (hasRunningChat) (runningDotColor ?: Color(0xFF22C55E)) else null,
        onClick = onBackToChat,
    )

    val contextualAction: AppBarAction? = when {
        syntheticArea == PhoneArea.CHAT -> AppBarAction(
            icon = ProductIcon.Plus,
            label = "New chat",
            onClick = onNewChat,
        )
        activeArea == PhoneArea.TERMINAL -> AppBarAction(
            icon = ProductIcon.Plus,
            label = "New terminal",
            onClick = onNewTerminal,
        )
        activeArea == PhoneArea.SOURCE_CONTROL -> AppBarAction(
            icon = ProductIcon.RefreshCw,
            label = "Refresh status",
            onClick = onRefreshGit,
        )
        else -> null
    }

    return listOfNotNull(contextualAction, backToChat).take(KitSpec.APP_BAR_MAX_ACTIONS)
}

private fun handleDrawerSelection(
    model: AppModel,
    itemId: String,
    openTabs: List<WorkspaceTab>,
    nav: PhoneNav,
) {
    when (PhoneArea.fromId(itemId)) {
        PhoneArea.CHAT -> {
            if (itemId == "chat" && nav.currentTabId != PhoneNav.areaTabId(PhoneArea.CHAT)) {
                nav.visitTab(PhoneNav.areaTabId(PhoneArea.CHAT))
            } else {
                focusOrOpenChat(model, openTabs, nav)
            }
        }
        PhoneArea.PROJECTS -> nav.pushSheet(SheetKey.ProjectSwitcher)
        PhoneArea.FILES -> nav.visitTab(PhoneNav.areaTabId(PhoneArea.FILES))
        PhoneArea.SOURCE_CONTROL -> nav.visitTab(PhoneNav.areaTabId(PhoneArea.SOURCE_CONTROL))
        PhoneArea.TERMINAL -> focusOrOpenTerminal(model, openTabs, nav)
        PhoneArea.DATABASE -> nav.visitTab(PhoneNav.areaTabId(PhoneArea.DATABASE))
        PhoneArea.MONITOR -> {
            model.openSystemMonitor()
            model.activeTabId?.let(nav::visitTab)
        }
        PhoneArea.LOGS -> {
            model.openLogs()
            model.activeTabId?.let(nav::visitTab)
        }
        PhoneArea.SCHEDULES -> nav.visitTab(PhoneNav.areaTabId(PhoneArea.SCHEDULES))
        PhoneArea.PORT_FORWARDING -> nav.visitTab(PhoneNav.areaTabId(PhoneArea.PORT_FORWARDING))
        PhoneArea.CLOUD_AND_SHARE -> nav.pushSheet(SheetKey.CloudShare)
        PhoneArea.SPECS -> {
            model.specs.closeSpec()
            nav.visitTab(PhoneNav.areaTabId(PhoneArea.SPECS))
        }
        PhoneArea.TASKS -> nav.visitTab(PhoneNav.areaTabId(PhoneArea.TASKS))
        PhoneArea.SETTINGS -> {
            model.openSettings()
            model.activeTabId?.let(nav::visitTab)
        }
        PhoneArea.ASSISTANT -> {
            model.openAssistant()
            model.activeTabId?.let(nav::visitTab)
        }
        null -> Unit
    }
}

private fun focusOrOpenChat(
    model: AppModel,
    openTabs: List<WorkspaceTab>,
    nav: PhoneNav,
) {
    val existingChat = openTabs.lastOrNull { it.type == "chat" }
    if (existingChat != null) {
        nav.visitTab(existingChat.id)
        model.activate(existingChat.id)
    } else {
        model.newChat()
        model.activeTabId?.let(nav::visitTab)
    }
}

private fun focusOrOpenTerminal(
    model: AppModel,
    openTabs: List<WorkspaceTab>,
    nav: PhoneNav,
) {
    val existingTerminal = openTabs.lastOrNull { it.type == "terminal" }
    if (existingTerminal != null) {
        nav.visitTab(existingTerminal.id)
        model.activate(existingTerminal.id)
    } else {
        model.openTerminal(inDock = false)
        model.activeTabId?.let(nav::visitTab)
    }
}
