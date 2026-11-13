package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.chat.TeamActivityPanel
import com.hienlh.ppm.client.accountUsages
import com.hienlh.ppm.client.accounts
import com.hienlh.ppm.client.activeAccount
import com.hienlh.ppm.client.codexAccounts
import com.hienlh.ppm.client.codexUsages
import com.hienlh.ppm.client.setAccountStatus
import com.hienlh.ppm.client.teamActivity
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.AgentSource
import com.hienlh.ppm.protocol.CodexAccount
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.UsageInfo
import com.hienlh.ppm.state.AccountsData
import com.hienlh.ppm.state.AgentSessionIdentity
import com.hienlh.ppm.state.AgentSessionPayload
import com.hienlh.ppm.state.ChatController
import com.hienlh.ppm.state.ChatState
import com.hienlh.ppm.state.SwitchAccount
import com.hienlh.ppm.state.claudeSwitchAccount
import com.hienlh.ppm.state.codexSwitchAccount
import com.hienlh.ppm.state.resolveFooterUsage
import com.hienlh.ppm.state.shortCodexPlan
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

enum class FooterToolsPanel {
    History,
    More,
    Config,
    Team,
    Usage,
}

private enum class MoreView {
    Main,
    Accounts,
}

private enum class MoreTrigger {
    Ring,
    Dots,
}

@Stable
data class SwitchAccountsState(
    val accounts: List<SwitchAccount> = emptyList(),
    val loading: Boolean = false,
    val reload: () -> Unit = {},
)

private class FooterClaudeAccountsSource(private val model: AppModel) : AccountsData.Source {
    private val api get() = model.api ?: throw IllegalStateException("Not connected")

    override suspend fun usages() = api.accountUsages()
    override suspend fun accounts() = api.accounts()
    override suspend fun active() = api.activeAccount()
    override suspend fun setStatus(id: String, status: String) = api.setAccountStatus(id, status)
}

/**
 * Loads the accounts for "Switch account" while the ··· menu is open (`useSwitchAccounts` in
 * `use-switch-accounts.ts`).
 */
@Composable
fun rememberSwitchAccounts(
    model: AppModel,
    providerId: String?,
    enabled: Boolean,
): SwitchAccountsState {
    val scope = rememberCoroutineScope()
    val isCodex = providerId == "codex"
    val claudeData = remember(model) { AccountsData(scope, FooterClaudeAccountsSource(model)) }
    val claudeState by claudeData.state.collectAsState()

    var codexPair by remember { mutableStateOf<Pair<List<CodexAccount>, Map<String, UsageInfo>>?>(null) }
    var codexReq by remember { mutableIntStateOf(0) }

    suspend fun loadCodex() {
        val api = model.api ?: run {
            codexPair = codexPair ?: (emptyList<CodexAccount>() to emptyMap())
            return
        }
        val req = ++codexReq
        try {
            val (accounts, usages) = coroutineScope {
                val listDeferred = async { api.codexAccounts() }
                val usagesDeferred = async { runCatching { api.codexUsages() }.getOrDefault(emptyMap()) }
                listDeferred.await() to usagesDeferred.await()
            }
            if (req == codexReq) {
                codexPair = accounts to usages
            }
        } catch (_: Exception) {
            if (req == codexReq && codexPair == null) {
                codexPair = emptyList<CodexAccount>() to emptyMap()
            }
        }
    }

    LaunchedEffect(enabled, isCodex) {
        if (!enabled) return@LaunchedEffect
        if (isCodex) {
            loadCodex()
        } else {
            claudeData.reload()
        }
    }

    val clock = LocalResetClock.current
    val accounts = remember(isCodex, codexPair, claudeState.accounts, claudeState.usages) {
        val now = clock()
        if (isCodex) {
            val (list, usages) = codexPair ?: return@remember emptyList()
            list.map { acc ->
                codexSwitchAccount(acc, usages[acc.id], shortCodexPlan(acc.planType), now)
            }
        } else {
            val infoById = claudeState.accounts.associateBy { it.id }
            claudeState.usages.map { entry ->
                claudeSwitchAccount(entry, infoById[entry.accountId], now)
            }
        }
    }

    val reload: () -> Unit = remember(isCodex, claudeData) {
        {
            scope.launch {
                if (isCodex) loadCodex() else claudeData.reload()
            }
        }
    }

    return SwitchAccountsState(
        accounts = accounts,
        loading = if (isCodex) codexPair == null && enabled else claudeState.initialLoading && enabled,
        reload = reload,
    )
}

/**
 * Right-hand pill cluster of the chat footer's context row (`ChatFooterTools` in
 * `chat-footer-tools.tsx`): labelled usage rings + divider, History, and ··· menu.
 */
@Composable
fun ChatFooterTools(
    usageInfo: UsageInfo,
    providerId: String?,
    narrow: Boolean,
    pickedAccountId: String? = null,
    pickedAccountLabel: String? = null,
    switcher: SwitchAccountsState = SwitchAccountsState(),
    onMenuOpenedChanged: ((Boolean) -> Unit)? = null,
    onSelectAccount: (suspend (id: String, label: String) -> String?)? = null,
    onReauthAccount: ((SwitchAccount) -> Unit)? = null,
    onAddAccount: () -> Unit = {},
    onManageAccounts: () -> Unit = {},
    /** Show an account's free resets in Settings (`requestAccountFocus` + `openSettings("accounts")`). */
    onOpenAccountResets: ((providerId: String, account: SwitchAccount) -> Unit)? = null,
    isConnected: Boolean = true,
    onReload: (() -> Unit)? = null,
    connectionTrouble: Boolean = false,
    hasTeams: Boolean = false,
    teamUnreadCount: Int = 0,
    onTeamOpen: (() -> Unit)? = null,
    sessionActions: MoreMenuSessionActions? = null,
    historyContent: @Composable (onClose: () -> Unit) -> Unit = {},
    aiSettingsContent: @Composable () -> Unit = {},
    teamContent: @Composable () -> Unit = {},
    antigravityUsageContent: @Composable (onClose: () -> Unit) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var panel by remember { mutableStateOf<FooterToolsPanel?>(null) }
    var moreView by remember { mutableStateOf(MoreView.Main) }
    var moreFrom by remember { mutableStateOf(MoreTrigger.Dots) }

    val isClaude = providerId.isNullOrEmpty() || providerId == "claude"
    val isCodex = providerId == "codex"
    val isAntigravity = providerId == "antigravity"
    val hasAccounts = isClaude || isCodex
    val providerName = if (isCodex) "Codex" else "Claude"

    LaunchedEffect(panel == FooterToolsPanel.More && hasAccounts) {
        onMenuOpenedChanged?.invoke(panel == FooterToolsPanel.More && hasAccounts)
    }

    fun close() {
        panel = null
    }

    fun toggle(next: FooterToolsPanel) {
        panel = if (panel == next) null else next
        moreView = MoreView.Main
    }

    fun openMore(from: MoreTrigger) {
        val prevFrom = moreFrom
        moreFrom = from
        if (panel == FooterToolsPanel.More && prevFrom != from) return
        toggle(FooterToolsPanel.More)
    }

    val nowMs = rememberResetClockMillis()
    val resolved = remember(providerId, usageInfo, pickedAccountId, pickedAccountLabel, switcher.accounts, nowMs) {
        resolveFooterUsage(
            providerId = providerId,
            usageInfo = usageInfo,
            pickedAccountId = pickedAccountId,
            pickedAccountLabel = pickedAccountLabel,
            switcherAccounts = switcher.accounts,
            now = nowMs,
        )
    }

    val usageHiddenForConnection = connectionTrouble && (narrow || mobile)
    val showUsageCluster = (hasAccounts || isAntigravity) && resolved.hasUsage && !usageHiddenForConnection

    FooterCluster(
        narrow = narrow,
        modifier = modifier,
    ) {
        if (showUsageCluster) {
            val ringExpanded = if (isAntigravity) {
                panel == FooterToolsPanel.Usage
            } else {
                panel == FooterToolsPanel.More && moreFrom == MoreTrigger.Ring
            }
            FooterGhostButton(
                narrow = narrow,
                expanded = ringExpanded,
                title = resolved.ringTitle,
                contentDescription = "Usage: ${resolved.ringTitle}",
                testTag = "usage-ring",
                onClick = {
                    if (isAntigravity) toggle(FooterToolsPanel.Usage) else openMore(MoreTrigger.Ring)
                },
            ) {
                Row(
                    Modifier.padding(horizontal = if (narrow || mobile) 0.dp else 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (narrow || mobile) 6.dp else 8.dp),
                ) {
                    UsageWindow(label = "5h", pct = resolved.fiveHourPct, narrow = narrow)
                    UsageWindow(label = "Wk", pct = resolved.weekPct, narrow = narrow)
                }
            }
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .width(1.dp)
                    .height(14.dp)
                    .background(tokens.border.color)
                    .testTag("usage-divider"),
            )
        }

        FooterGhostButton(
            narrow = narrow,
            expanded = panel == FooterToolsPanel.History,
            title = "History",
            contentDescription = "History",
            testTag = "footer-history",
            onClick = { toggle(FooterToolsPanel.History) },
        ) {
            PpmIcon(ProductIcon.History, 14.dp, LocalFooterContentColor.current)
        }

        val dotsExpanded = (panel == FooterToolsPanel.More && moreFrom == MoreTrigger.Dots) ||
            panel == FooterToolsPanel.Config ||
            panel == FooterToolsPanel.Team
        Box {
            FooterGhostButton(
                narrow = narrow,
                expanded = dotsExpanded,
                title = "More",
                contentDescription = "More",
                testTag = "footer-more",
                onClick = { openMore(MoreTrigger.Dots) },
            ) {
                PpmIcon(ProductIcon.MoreHorizontal, 16.dp, LocalFooterContentColor.current)
            }
            if (teamUnreadCount > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-2).dp, y = if (mobile) 2.dp else 2.dp)
                        .size(6.dp)
                        .background(tokens.accent.color, CircleShape)
                        .testTag("footer-more-unread"),
                )
            }
        }
    }

    FooterPopover(
        open = panel == FooterToolsPanel.History,
        onClose = ::close,
        label = "History",
        align = FooterPopoverAlign.Right,
        maxWidth = 420.dp,
    ) {
        FooterPopoverHeader {
            PpmIcon(ProductIcon.History, 14.dp, tokens.text3.color)
            BasicText(
                "History",
                style = ppmText(if (mobile) 14f else 13f, FontWeight.SemiBold, lineHeight = if (mobile) 20f else 18f).copy(
                    color = tokens.text.color,
                ),
                maxLines = 1,
                softWrap = false,
            )
        }
        historyContent(::close)
    }

    FooterPopover(
        open = panel == FooterToolsPanel.More,
        onClose = ::close,
        label = "Chat menu",
        align = FooterPopoverAlign.Right,
    ) {
        if (moreView == MoreView.Accounts) {
            SwitchAccountView(
                accounts = switcher.accounts,
                loading = switcher.loading,
                currentId = resolved.currentId,
                providerName = providerName,
                onBack = { moreView = MoreView.Main },
                onPick = onSelectAccount,
                onPicked = { moreView = MoreView.Main },
                onReauth = onReauthAccount,
                onAddAccount = {
                    close()
                    onAddAccount()
                },
                onManage = {
                    close()
                    onManageAccounts()
                },
                onOpenResets = onOpenAccountResets?.let { open ->
                    { account ->
                        close()
                        open(if (isCodex) "codex" else "claude", account)
                    }
                },
            )
        } else {
            ChatMoreMenu(
                who = if (hasAccounts) resolved.who else null,
                fiveHourPct = resolved.fiveHourPct,
                weekPct = resolved.weekPct,
                fiveHourResetsAt = resolved.fiveHourResetsAt,
                weekResetsAt = resolved.weekResetsAt,
                sessionCostUsd = usageInfo.totalCostUsd,
                accountCount = if (switcher.loading) null else maxOf(switcher.accounts.size, 1),
                onSwitchAccount = if (hasAccounts) {
                    { moreView = MoreView.Accounts }
                } else {
                    null
                },
                providerId = providerId,
                onOpenAISettings = { panel = FooterToolsPanel.Config },
                team = if (hasTeams) {
                    MoreMenuTeamInfo(
                        unread = teamUnreadCount,
                        onOpen = {
                            panel = FooterToolsPanel.Team
                            onTeamOpen?.invoke()
                        },
                    )
                } else {
                    null
                },
                isConnected = isConnected,
                onReload = onReload?.let { reloadFn ->
                    {
                        close()
                        reloadFn()
                    }
                },
                session = sessionActions?.let { s ->
                    MoreMenuSessionActions(
                        hasUnread = s.hasUnread,
                        onToggleUnread = {
                            close()
                            s.onToggleUnread()
                        },
                        onOpenDebug = {
                            close()
                            s.onOpenDebug()
                        },
                        onShowLogs = {
                            close()
                            s.onShowLogs()
                        },
                    )
                },
            )
        }
    }

    FooterPopover(
        open = panel == FooterToolsPanel.Usage && isAntigravity,
        onClose = ::close,
        label = "Antigravity usage",
        align = FooterPopoverAlign.Right,
        maxWidth = 420.dp,
    ) {
        if (isAntigravity) {
            antigravityUsageContent(::close)
        }
    }

    FooterPopover(
        open = panel == FooterToolsPanel.Config,
        onClose = ::close,
        label = "AI settings",
        align = FooterPopoverAlign.Right,
    ) {
        FooterPopoverHeader {
            BasicText(
                "AI settings",
                style = ppmText(if (mobile) 14f else 13f, FontWeight.SemiBold, lineHeight = if (mobile) 20f else 18f).copy(
                    color = tokens.text.color,
                ),
                maxLines = 1,
                softWrap = false,
            )
        }
        val scroll = rememberScrollState()
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (mobile) Modifier else Modifier.heightIn(max = 320.dp))
                .verticalScroll(scroll)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            aiSettingsContent()
        }
    }

    FooterPopover(
        open = panel == FooterToolsPanel.Team && hasTeams,
        onClose = ::close,
        label = "Team activity",
        align = FooterPopoverAlign.Right,
        maxWidth = 420.dp,
    ) {
        if (hasTeams) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                teamContent()
            }
        }
    }
}

/**
 * Stateful binding of [ChatFooterTools] for `ChatPane`.
 */
@Composable
fun ChatPaneFooterTools(
    model: AppModel,
    tab: ChatTab,
    chat: ChatController,
    state: ChatState,
    agentIdentity: AgentSessionIdentity,
    usageInfo: UsageInfo,
    usageLoading: Boolean,
    lastFetchedAt: String?,
    onRefreshUsage: suspend () -> Unit,
    onReadUsageQuietly: suspend () -> Unit,
    pickedAccountId: String?,
    pickedAccountLabel: String?,
    narrow: Boolean,
    connectionTrouble: Boolean,
    modifier: Modifier = Modifier,
) {
    var menuOpenedForAccounts by remember { mutableStateOf(false) }
    var debugOpen by remember { mutableStateOf(false) }

    val switcher = rememberSwitchAccounts(
        model = model,
        providerId = tab.providerId,
        enabled = menuOpenedForAccounts,
    )

    val selectAccount: (suspend (String, String) -> String?)? =
        if (!tab.providerPending && !tab.firstSendPending) {
            { id, label ->
                model.selectAccount(tab, id, label) { onReadUsageQuietly() }.also { err ->
                    if (err == null) switcher.reload()
                }
            }
        } else {
            null
        }
    val selectAccountNullable: (suspend (String, String?) -> String?)? =
        if (!tab.providerPending && !tab.firstSendPending) {
            { id, label -> model.selectAccount(tab, id, label) { onReadUsageQuietly() } }
        } else {
            null
        }

    val session = tab.session
    val unreads = model.unreadSessions()
    val hasUnread = session != null && unreads.containsKey(session.id)

    ChatFooterTools(
        usageInfo = usageInfo,
        providerId = tab.providerId,
        narrow = narrow,
        pickedAccountId = pickedAccountId,
        pickedAccountLabel = pickedAccountLabel,
        switcher = switcher,
        onMenuOpenedChanged = { menuOpenedForAccounts = it },
        onSelectAccount = selectAccount,
        onReauthAccount = { model.openSettings("accounts") },
        onAddAccount = { model.openSettings("accounts") },
        onManageAccounts = { model.openSettings("accounts") },
        onOpenAccountResets = { provider, account ->
            AccountFocus.request(provider, account.id)
            model.openSettings("accounts")
        },
        isConnected = state.connected,
        onReload = chat::reloadChat,
        connectionTrouble = connectionTrouble,
        hasTeams = state.team.hasTeams,
        teamUnreadCount = state.team.unread,
        onTeamOpen = chat::markTeamRead,
        sessionActions = session?.let { currentSession ->
            MoreMenuSessionActions(
                hasUnread = hasUnread,
                onToggleUnread = {
                    if (hasUnread) {
                        model.markRead(currentSession.id)
                    } else {
                        model.markUnread(model.sessions.firstOrNull { it.id == currentSession.id } ?: currentSession)
                    }
                },
                onOpenDebug = { debugOpen = true },
                onShowLogs = { model.openLogs(chat = currentSession.id) },
            )
        },
        historyContent = { close ->
            tab.historyList?.let { list ->
                ChatHistoryPanel(
                    model = model,
                    tab = tab,
                    list = list,
                    onClose = close,
                )
            }
        },
        aiSettingsContent = {
            AiSettingsSection(model)
        },
        teamContent = {
            TeamActivityPanel(
                state.team.names,
                state.team.messages,
                agentIdentity.sessionId.ifEmpty { null },
                fetch = { team -> model.api?.teamActivity(team) ?: error("Not connected") },
                onOpenMember = { team, member ->
                    val source = AgentSource.Member(team, member)
                    model.openAgentSession(
                        AgentSessionPayload(
                            agentIdentity.projectName,
                            agentIdentity.providerId,
                            agentIdentity.sessionId,
                            source,
                            title = "Session — $member",
                        ),
                        null,
                    )
                },
            )
        },
        antigravityUsageContent = { close ->
            AntigravityUsagePanel(
                model = model,
                usage = usageInfo,
                loading = usageLoading,
                lastFetchedAt = lastFetchedAt,
                selectedAccountId = pickedAccountId ?: usageInfo.activeAccountId,
                onSelectAccount = selectAccountNullable,
                onReload = onRefreshUsage,
                onClose = close,
            )
        },
        modifier = modifier,
    )

    if (debugOpen && session != null) {
        val itemCount by rememberUpdatedState(state.items.size + if (state.live != null) 1 else 0)
        SessionDebugDialog(
            model = model,
            project = tab.project,
            sessionId = session.id,
            messageCount = { itemCount },
            onDismiss = { debugOpen = false },
        )
    }
}
