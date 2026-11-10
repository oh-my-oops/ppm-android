package com.hienlh.ppm.app.ui.phone.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.PpmSwitch
import com.hienlh.ppm.app.ui.SettingsPasswordInput
import com.hienlh.ppm.app.ui.SettingsTextInput
import com.hienlh.ppm.app.ui.phone.OpenOnTabletNotice
import com.hienlh.ppm.client.accountUsages
import com.hienlh.ppm.client.accounts
import com.hienlh.ppm.client.accountsAddManual
import com.hienlh.ppm.client.accountsDelete
import com.hienlh.ppm.client.accountsOAuthExchange
import com.hienlh.ppm.client.accountsOAuthUrl
import com.hienlh.ppm.client.activeAccount
import com.hienlh.ppm.client.antigravityAccountsOverview
import com.hienlh.ppm.client.antigravityDeleteAccount
import com.hienlh.ppm.client.antigravityStartLogin
import com.hienlh.ppm.client.antigravitySubmitLoginCode
import com.hienlh.ppm.client.antigravityUsages
import com.hienlh.ppm.client.codexAccounts
import com.hienlh.ppm.client.codexAddApiKey
import com.hienlh.ppm.client.codexDeleteAccount
import com.hienlh.ppm.client.codexStartBrowserLogin
import com.hienlh.ppm.client.codexStartDeviceLogin
import com.hienlh.ppm.client.codexSubmitBrowserCallback
import com.hienlh.ppm.client.codexUsages
import com.hienlh.ppm.client.setAccountStatus
import com.hienlh.ppm.client.setAntigravityAccountStatus
import com.hienlh.ppm.client.setCodexAccountStatus
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.BottomActionCard
import com.hienlh.ppm.design.kit.ListRow
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.AntigravityAccount
import com.hienlh.ppm.protocol.UsageInfo
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.state.codexPlanLabel
import com.hienlh.ppm.state.currentAntigravityUsage
import com.hienlh.ppm.state.formatResetTime
import com.hienlh.ppm.state.settings.AntigravityLoginFlow
import com.hienlh.ppm.state.settings.CodexBrowserLoginFlow
import com.hienlh.ppm.state.settings.CodexDeviceLoginFlow
import com.hienlh.ppm.state.settings.OAuthCodeFlowStart
import com.hienlh.ppm.state.settings.parseAntigravityLoginStart
import com.hienlh.ppm.state.settings.parseCodexBrowserLogin
import com.hienlh.ppm.state.settings.parseCodexDeviceLogin
import com.hienlh.ppm.state.settings.parseOAuthCodeFlowStart
import com.hienlh.ppm.state.usagePercent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class PhoneUsageMeterItem(
    val label: String,
    val percent: Int,
    val resetHint: String? = null,
)

data class PhoneAccountCardData(
    val id: String,
    val provider: String,
    val label: String,
    val subtitle: String,
    val planLabel: String? = null,
    val status: String,
    val isActive: Boolean = false,
    val needsSignIn: Boolean = false,
    val meters: List<PhoneUsageMeterItem> = emptyList(),
) {
    val isEnabled: Boolean
        get() = status != "disabled"
}

@Composable
fun PhoneAccountsPage(
    model: AppModel,
    onBack: () -> Unit,
) {
    val api = model.api
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var activeProvider by remember { mutableStateOf("claude") }
    var claudeCards by remember { mutableStateOf<List<PhoneAccountCardData>>(emptyList()) }
    var codexCards by remember { mutableStateOf<List<PhoneAccountCardData>>(emptyList()) }
    var antigravityCards by remember { mutableStateOf<List<PhoneAccountCardData>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    // Sign-in flow states
    var oauthFlow by remember { mutableStateOf<OAuthCodeFlowStart?>(null) }
    var codexDeviceFlow by remember { mutableStateOf<CodexDeviceLoginFlow?>(null) }
    var codexBrowserFlow by remember { mutableStateOf<CodexBrowserLoginFlow?>(null) }
    var antigravityFlow by remember { mutableStateOf<AntigravityLoginFlow?>(null) }

    suspend fun reloadAll() {
        val client = api ?: return
        loading = true
        try {
            val now = System.currentTimeMillis()
            val accs = runCatching { client.accounts() }.getOrDefault(emptyList())
            val usages = runCatching { client.accountUsages() }.getOrDefault(emptyList())
            val activeId = runCatching { client.activeAccount()?.id }.getOrNull()
            val infoById = accs.associateBy { it.id }
            claudeCards = usages.map { entry ->
                val info = infoById[entry.accountId]
                val sPct = entry.usage.session?.let { usagePercent(it.utilization) } ?: 0
                val wPct = entry.usage.weekly?.let { usagePercent(it.utilization) } ?: 0
                val sReset = formatResetTime(entry.usage.session, now)
                val wReset = formatResetTime(entry.usage.weekly, now)
                PhoneAccountCardData(
                    id = entry.accountId,
                    provider = "claude",
                    label = entry.accountLabel?.takeIf { it.isNotBlank() } ?: info?.label ?: entry.accountId.take(8),
                    subtitle = info?.email ?: if (entry.isOAuth) "OAuth Account" else "API Key",
                    planLabel = if (entry.isOAuth) "Claude Max / Pro" else "API Key",
                    status = entry.accountStatus,
                    isActive = entry.accountId == activeId,
                    needsSignIn = entry.accountStatus == "expired" || entry.accountStatus == "error",
                    meters = listOf(
                        PhoneUsageMeterItem("5-hour session", sPct, sReset),
                        PhoneUsageMeterItem("7-day weekly", wPct, wReset),
                    ),
                )
            }

            val codexAccs = runCatching { client.codexAccounts() }.getOrDefault(emptyList())
            val codexUsageMap = runCatching { client.codexUsages() }.getOrDefault(emptyMap())
            codexCards = codexAccs.mapIndexed { idx, acc ->
                val u = codexUsageMap[acc.id]
                val sPct = u?.session?.let { usagePercent(it.utilization) } ?: 0
                val wPct = u?.weekly?.let { usagePercent(it.utilization) } ?: 0
                PhoneAccountCardData(
                    id = acc.id,
                    provider = "codex",
                    label = acc.label.ifBlank { acc.id.take(8) },
                    subtitle = listOfNotNull(acc.type.ifBlank { null }, codexPlanLabel(acc.planType)).joinToString(" · ").ifBlank { "OpenAI Codex" },
                    planLabel = codexPlanLabel(acc.planType),
                    status = acc.status,
                    isActive = idx == 0 && acc.status == "active",
                    needsSignIn = acc.status == "expired" || acc.status == "error" || acc.signedOut,
                    meters = listOf(
                        PhoneUsageMeterItem("5-hour window", sPct, formatResetTime(u?.session, now)),
                        PhoneUsageMeterItem("7-day window", wPct, formatResetTime(u?.weekly, now)),
                    ),
                )
            }

            val agOverview = runCatching { client.antigravityAccountsOverview() }.getOrNull()
            val agAccs = agOverview?.get("accounts")?.array.orEmpty().mapNotNull(AntigravityAccount::from)
            val agUsageMap = runCatching { client.antigravityUsages() }.getOrDefault(emptyMap())
            antigravityCards = agAccs.mapIndexed { idx, acc ->
                val u = currentAntigravityUsage(agUsageMap[acc.id] ?: UsageInfo(), now)
                val sPct = u.session?.let { usagePercent(it.utilization) } ?: 0
                val wPct = u.weekly?.let { usagePercent(it.utilization) } ?: 0
                val emailStr = acc.email.orEmpty()
                PhoneAccountCardData(
                    id = acc.id,
                    provider = "antigravity",
                    label = acc.label.ifBlank { emailStr.ifBlank { acc.id.take(8) } },
                    subtitle = emailStr.ifBlank { "Google Account" },
                    planLabel = acc.planType,
                    status = acc.status,
                    isActive = idx == 0 && acc.status == "active",
                    needsSignIn = acc.status == "expired" || acc.status == "error",
                    meters = listOf(
                        PhoneUsageMeterItem("Claude Pool", sPct, formatResetTime(u.session, now)),
                        PhoneUsageMeterItem("Gemini Pro", wPct, formatResetTime(u.weekly, now)),
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            isError = true
            statusMsg = t.message ?: "Failed to load accounts"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(api) {
        if (api != null) reloadAll()
    }

    val currentCards = when (activeProvider) {
        "codex" -> codexCards
        "antigravity" -> antigravityCards
        else -> claudeCards
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalPpmTokens.current.bgSolid.color),
    ) {
        PhoneSettingsSubPageHeader(
            title = "Accounts",
            onBack = onBack,
            trailingAction = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .ppmClickable(
                            onClickLabel = "Refresh accounts",
                            onClick = { scope.launch { reloadAll() } },
                        )
                        .testTag("phone-accounts-refresh"),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.RefreshCw, 18.dp, LocalPpmTokens.current.text2.color)
                }
            },
        )

        PhoneAccountsContent(
            activeProvider = activeProvider,
            onSelectProvider = { activeProvider = it },
            cards = currentCards,
            claudeCount = claudeCards.size,
            codexCount = codexCards.size,
            antigravityCount = antigravityCards.size,
            loading = loading,
            statusMsg = statusMsg,
            isError = isError,
            onToggleAccount = { card, enabled ->
                val nextStatus = if (enabled) "active" else "disabled"
                when (card.provider) {
                    "claude" -> claudeCards = claudeCards.map { if (it.id == card.id) it.copy(status = nextStatus) else it }
                    "codex" -> codexCards = codexCards.map { if (it.id == card.id) it.copy(status = nextStatus) else it }
                    "antigravity" -> antigravityCards = antigravityCards.map { if (it.id == card.id) it.copy(status = nextStatus) else it }
                }
                val client = api ?: return@PhoneAccountsContent
                scope.launch {
                    try {
                        when (card.provider) {
                            "claude" -> client.setAccountStatus(card.id, nextStatus)
                            "codex" -> client.setCodexAccountStatus(card.id, nextStatus)
                            "antigravity" -> client.setAntigravityAccountStatus(card.id, nextStatus)
                        }
                        reloadAll()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        isError = true
                        statusMsg = t.message ?: "Failed to update account"
                        reloadAll()
                    }
                }
            },
            onDeleteAccount = { card ->
                val client = api ?: return@PhoneAccountsContent
                scope.launch {
                    try {
                        when (card.provider) {
                            "claude" -> client.accountsDelete(card.id)
                            "codex" -> client.codexDeleteAccount(card.id)
                            "antigravity" -> client.antigravityDeleteAccount(card.id)
                        }
                        reloadAll()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        isError = true
                        statusMsg = t.message ?: "Failed to remove account"
                    }
                }
            },
            onStartSignIn = { provider, method ->
                val client = api ?: return@PhoneAccountsContent
                scope.launch {
                    try {
                        when (provider) {
                            "claude" -> {
                                val started = parseOAuthCodeFlowStart(client.accountsOAuthUrl())
                                if (started != null) {
                                    oauthFlow = started
                                    runCatching { uriHandler.openUri(started.url) }
                                }
                            }
                            "codex" -> {
                                if (method == "browser") {
                                    val started = parseCodexBrowserLogin(client.codexStartBrowserLogin())
                                    if (started != null) {
                                        codexBrowserFlow = started
                                        runCatching { uriHandler.openUri(started.authUrl) }
                                    }
                                } else {
                                    val started = parseCodexDeviceLogin(client.codexStartDeviceLogin())
                                    if (started != null) {
                                        codexDeviceFlow = started
                                        runCatching { uriHandler.openUri(started.verificationUrl) }
                                    }
                                }
                            }
                            "antigravity" -> {
                                val started = parseAntigravityLoginStart(client.antigravityStartLogin())
                                if (started != null) {
                                    antigravityFlow = started
                                    runCatching { uriHandler.openUri(started.authUrl) }
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        isError = true
                        statusMsg = t.message ?: "Failed to start sign-in"
                    }
                }
            },
            onSubmitCodeOrKey = { provider, mode, value, label ->
                val client = api ?: return@PhoneAccountsContent
                scope.launch {
                    try {
                        when (provider) {
                            "claude" -> {
                                if (mode == "oauth") {
                                    val state = oauthFlow?.state ?: ""
                                    client.accountsOAuthExchange(value.trim(), state)
                                    oauthFlow = null
                                } else {
                                    client.accountsAddManual(value.trim(), label?.trim()?.ifEmpty { null })
                                }
                            }
                            "codex" -> {
                                if (mode == "callback") {
                                    val flowId = codexBrowserFlow?.id ?: return@launch
                                    client.codexSubmitBrowserCallback(flowId, value.trim())
                                    codexBrowserFlow = null
                                } else {
                                    client.codexAddApiKey(value.trim(), label?.trim()?.ifEmpty { null })
                                }
                            }
                            "antigravity" -> {
                                val flowId = antigravityFlow?.id ?: return@launch
                                client.antigravitySubmitLoginCode(flowId, value.trim())
                                antigravityFlow = null
                            }
                        }
                        isError = false
                        statusMsg = "Account connected"
                        reloadAll()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        isError = true
                        statusMsg = t.message ?: "Failed to connect account"
                    }
                }
            },
            codexDeviceCode = codexDeviceFlow?.userCode,
            codexVerificationUrl = codexDeviceFlow?.verificationUrl,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun PhoneAccountsContent(
    activeProvider: String,
    onSelectProvider: (String) -> Unit,
    cards: List<PhoneAccountCardData>,
    claudeCount: Int = cards.count { it.provider == "claude" },
    codexCount: Int = cards.count { it.provider == "codex" },
    antigravityCount: Int = cards.count { it.provider == "antigravity" },
    loading: Boolean = false,
    statusMsg: String? = null,
    isError: Boolean = false,
    codexDeviceCode: String? = null,
    codexVerificationUrl: String? = null,
    onToggleAccount: (PhoneAccountCardData, Boolean) -> Unit,
    onDeleteAccount: (PhoneAccountCardData) -> Unit,
    onStartSignIn: (provider: String, method: String) -> Unit = { _, _ -> },
    onSubmitCodeOrKey: (provider: String, mode: String, value: String, label: String?) -> Unit = { _, _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    var menuCard by remember { mutableStateOf<PhoneAccountCardData?>(null) }
    var profileCard by remember { mutableStateOf<PhoneAccountCardData?>(null) }
    var confirmDeleteCard by remember { mutableStateOf<PhoneAccountCardData?>(null) }
    var signInSheetOpen by remember { mutableStateOf(false) }

    // If profile sub-view is open, display it
    val viewingProfile = profileCard
    if (viewingProfile != null) {
        PhoneAccountProfileView(
            card = viewingProfile,
            onBack = { profileCard = null },
            modifier = modifier,
        )
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PhoneSegmentedControl(
                items = listOf(
                    "claude" to "Claude ($claudeCount)",
                    "codex" to "Codex ($codexCount)",
                    "antigravity" to "Antigravity ($antigravityCount)",
                ),
                selectedId = activeProvider,
                onSelect = onSelectProvider,
                tagPrefix = "phone-accounts-provider",
            )

            if (!statusMsg.isNullOrBlank()) {
                BasicText(
                    text = statusMsg,
                    style = ppmText(12f).copy(
                        color = if (isError) tokens.error.color else tokens.success.color,
                    ),
                    modifier = Modifier.testTag("phone-accounts-status"),
                )
            }

            if (cards.isEmpty() && !loading) {
                PhoneSectionCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BasicText(
                            text = "No ${phoneProviderDisplayName(activeProvider)} accounts connected.",
                            style = ppmText(13f, FontWeight.Medium).copy(color = tokens.text.color),
                        )
                        BasicText(
                            text = "Tap + Add account below to sign in or add an API key.",
                            style = ppmText(12f, lineHeight = 17f).copy(color = tokens.text2.color),
                        )
                    }
                }
            }

            for (card in cards) {
                PhoneAccountCard(
                    card = card,
                    onToggle = { enabled -> onToggleAccount(card, enabled) },
                    onOpenSignIn = { signInSheetOpen = true },
                    onOpenMenu = { menuCard = card },
                )
            }

            OpenOnTabletNotice(
                text = "Rotation strategy, encrypted backup/restore and token tests open on a tablet.",
            )
        }

        val activeCount = cards.count { it.isEnabled && !it.needsSignIn }
        BottomActionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(
                        text = "${cards.size} ${if (cards.size == 1) "account" else "accounts"}",
                        style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color),
                    )
                    BasicText(
                        text = "$activeCount active",
                        style = ppmText(11f).copy(color = tokens.text2.color),
                    )
                }
                PhonePillButton(
                    label = "+ Add account",
                    primary = true,
                    onClick = { signInSheetOpen = true },
                    modifier = Modifier.testTag("phone-accounts-add-btn"),
                )
            }
        }
    }

    // Account ⋮ Menu Sheet
    val currentMenuCard = menuCard
    if (currentMenuCard != null) {
        Sheet(
            title = currentMenuCard.label,
            subtitle = currentMenuCard.subtitle,
            onDismiss = { menuCard = null },
            forceBottomSheet = true,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ListRow(
                    title = "View profile",
                    subtitle = "Account details and usage windows",
                    leadingContent = { PpmIcon(ProductIcon.Users, 18.dp, tokens.text2.color) },
                    modifier = Modifier.testTag("phone-account-view-profile-${currentMenuCard.id}"),
                    onClick = {
                        menuCard = null
                        profileCard = currentMenuCard
                    },
                )
                ListRow(
                    title = "Remove account",
                    subtitle = "Disconnect from this PPM server",
                    leadingContent = { PpmIcon(ProductIcon.Trash2, 18.dp, tokens.error.color) },
                    modifier = Modifier.testTag("phone-account-remove-${currentMenuCard.id}"),
                    onClick = {
                        menuCard = null
                        confirmDeleteCard = currentMenuCard
                    },
                )
            }
        }
    }

    // Remove Account Confirmation Sheet
    val targetDelete = confirmDeleteCard
    if (targetDelete != null) {
        Sheet(
            title = "Remove ${targetDelete.label}?",
            subtitle = "This disconnects the account from your PPM server. Active chats switch to the next enabled account.",
            onDismiss = { confirmDeleteCard = null },
            forceBottomSheet = true,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PhonePillButton(
                    label = "Cancel",
                    onClick = { confirmDeleteCard = null },
                    modifier = Modifier.weight(1f),
                )
                PhonePillButton(
                    label = "Remove account",
                    danger = true,
                    onClick = {
                        confirmDeleteCard = null
                        onDeleteAccount(targetDelete)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("phone-account-confirm-remove-${targetDelete.id}"),
                )
            }
        }
    }

    // Sign-in / Add Account Sheet
    if (signInSheetOpen) {
        PhoneAccountSignInSheet(
            provider = activeProvider,
            codexDeviceCode = codexDeviceCode,
            codexVerificationUrl = codexVerificationUrl,
            onDismiss = { signInSheetOpen = false },
            onStartSignIn = { method -> onStartSignIn(activeProvider, method) },
            onSubmit = { mode, value, label ->
                signInSheetOpen = false
                onSubmitCodeOrKey(activeProvider, mode, value, label)
            },
        )
    }
    }
}

@Composable
private fun PhoneAccountCard(
    card: PhoneAccountCardData,
    onToggle: (Boolean) -> Unit,
    onOpenSignIn: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(14.dp)
    val borderColor = when {
        card.needsSignIn -> tokens.warning.opacity(0.45).color
        card.isActive -> tokens.accent.opacity(0.55).color
        else -> tokens.borderSoft.color
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(borderColor, shape)
            .padding(14.dp)
            .testTag("phone-account-card-${card.id}"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BasicText(
                        text = card.label,
                        style = ppmText(14f, FontWeight.SemiBold).copy(color = tokens.text.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val badgeText = when {
                        card.needsSignIn -> "Needs sign-in"
                        card.isActive -> "Active"
                        !card.isEnabled -> "Disabled"
                        else -> null
                    }
                    if (badgeText != null) {
                        val badgeColor = when {
                            card.needsSignIn -> tokens.warning
                            card.isActive -> tokens.success
                            else -> tokens.text3
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(badgeColor.opacity(0.14).color)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            BasicText(
                                text = badgeText,
                                style = ppmText(10f, FontWeight.SemiBold).copy(color = badgeColor.color),
                            )
                        }
                    }
                }
                BasicText(
                    text = card.subtitle,
                    style = ppmText(12f).copy(color = tokens.text2.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (card.needsSignIn) {
                PhonePillButton(
                    label = "Sign in",
                    primary = true,
                    onClick = onOpenSignIn,
                    modifier = Modifier.testTag("phone-account-signin-${card.id}"),
                )
            } else {
                Box(
                    modifier = Modifier
                        .ppmClickable(
                            onClickLabel = "Toggle ${card.label}",
                            onClick = { onToggle(!card.isEnabled) },
                        )
                        .testTag("phone-account-toggle-${card.id}"),
                ) {
                    PpmSwitch(
                        checked = card.isEnabled,
                        onChange = { onToggle(!card.isEnabled) },
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .ppmClickable(
                        onClickLabel = "Account actions for ${card.label}",
                        onClick = onOpenMenu,
                    )
                    .testTag("phone-account-more-${card.id}"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.MoreVertical, 18.dp, tokens.text2.color)
            }
        }

        // Stacked vertical usage meters
        if (card.meters.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (meter in card.meters) {
                    val clamped = meter.percent.coerceIn(0, 100)
                    val barColor = when {
                        clamped >= 90 -> tokens.error.color
                        clamped >= 75 -> tokens.warning.color
                        else -> tokens.accent.color
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(
                                text = meter.label,
                                style = ppmText(11f, FontWeight.Medium).copy(color = tokens.text2.color),
                            )
                            val rightText = buildString {
                                append("$clamped%")
                                if (!meter.resetHint.isNullOrBlank()) {
                                    append(" · ")
                                    append(meter.resetHint)
                                }
                            }
                            BasicText(
                                text = rightText,
                                style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(tokens.panel2.color),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction = (clamped / 100f).coerceIn(0f, 1f))
                                    .height(6.dp)
                                    .clip(CircleShape)
                                    .background(barColor),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneAccountProfileView(
    card: PhoneAccountCardData,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color),
    ) {
        PhoneSettingsSubPageHeader(
            title = card.label,
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PhoneSectionCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ProfileFieldRow("Provider", phoneProviderDisplayName(card.provider))
                    ProfileFieldRow("Identity", card.subtitle)
                    if (!card.planLabel.isNullOrBlank()) {
                        ProfileFieldRow("Plan", card.planLabel)
                    }
                    ProfileFieldRow("Status", card.status.replaceFirstChar { it.uppercase() })
                }
            }

            if (card.meters.isNotEmpty()) {
                PhoneSectionCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        BasicText(
                            text = "Current Usage Windows",
                            style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color),
                        )
                        for (m in card.meters) {
                            ProfileFieldRow(
                                label = m.label,
                                value = "${m.percent}%" + (m.resetHint?.let { " ($it)" } ?: ""),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileFieldRow(label: String, value: String) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style = ppmText(12f).copy(color = tokens.text2.color),
        )
        BasicText(
            text = value,
            style = ppmText(12f, FontWeight.Medium, kind = FontKind.MONO).copy(color = tokens.text.color),
        )
    }
}

@Composable
private fun PhoneAccountSignInSheet(
    provider: String,
    codexDeviceCode: String?,
    codexVerificationUrl: String?,
    onDismiss: () -> Unit,
    onStartSignIn: (method: String) -> Unit,
    onSubmit: (mode: String, value: String, label: String?) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var mode by remember(provider) {
        mutableStateOf(
            when (provider) {
                "codex" -> "device"
                else -> "oauth"
            },
        )
    }
    var codeOrKeyInput by remember { mutableStateOf("") }
    var labelInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    Sheet(
        title = "Sign in to ${phoneProviderDisplayName(provider)}",
        onDismiss = onDismiss,
        forceBottomSheet = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (provider) {
                "claude" -> {
                    PhoneSegmentedControl(
                        items = listOf(
                            "oauth" to "Browser OAuth",
                            "key" to "Token / API Key",
                        ),
                        selectedId = mode,
                        onSelect = { mode = it },
                        tagPrefix = "phone-claude-signin-mode",
                    )
                    if (mode == "oauth") {
                        BasicText(
                            text = "1. Open Anthropic sign-in in your browser.\n2. Copy the code#state shown on the callback page and paste it below.",
                            style = ppmText(12f, lineHeight = 17f).copy(color = tokens.text2.color),
                        )
                        PhonePillButton(
                            label = "Open browser",
                            primary = true,
                            onClick = { onStartSignIn("oauth") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("phone-claude-open-browser"),
                        )
                        SettingsTextInput(
                            value = codeOrKeyInput,
                            onValueChange = { codeOrKeyInput = it },
                            placeholder = "Paste authorization code#state",
                            mono = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("phone-claude-code-input"),
                        )
                        PhonePillButton(
                            label = "Exchange code",
                            primary = true,
                            enabled = codeOrKeyInput.isNotBlank(),
                            onClick = { onSubmit("oauth", codeOrKeyInput, null) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("phone-claude-exchange-btn"),
                        )
                    } else {
                        SettingsTextInput(
                            value = labelInput,
                            onValueChange = { labelInput = it },
                            placeholder = "Label (e.g. Work Max)",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SettingsPasswordInput(
                            value = codeOrKeyInput,
                            onValueChange = { codeOrKeyInput = it },
                            placeholder = "sk-ant-...",
                            visible = passwordVisible,
                            onToggleVisible = { passwordVisible = !passwordVisible },
                            modifier = Modifier.fillMaxWidth(),
                            inputTestTag = "phone-claude-key-input",
                        )
                        PhonePillButton(
                            label = "Add account",
                            primary = true,
                            enabled = codeOrKeyInput.isNotBlank(),
                            onClick = { onSubmit("key", codeOrKeyInput, labelInput) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("phone-claude-add-key-submit"),
                        )
                    }
                }

                "codex" -> {
                    PhoneSegmentedControl(
                        items = listOf(
                            "device" to "Device code",
                            "browser" to "Browser",
                            "key" to "API key",
                        ),
                        selectedId = mode,
                        onSelect = { mode = it },
                        tagPrefix = "phone-codex-signin-mode",
                    )
                    when (mode) {
                        "device" -> {
                            BasicText(
                                text = "Start device login to get an 8-character code for auth.openai.com/codex/device.",
                                style = ppmText(12f, lineHeight = 17f).copy(color = tokens.text2.color),
                            )
                            if (!codexDeviceCode.isNullOrBlank()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(tokens.panel2.color)
                                        .padding(12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    BasicText(
                                        text = codexDeviceCode,
                                        style = ppmText(18f, FontWeight.Bold, kind = FontKind.MONO).copy(color = tokens.accent.color),
                                    )
                                }
                            }
                            PhonePillButton(
                                label = if (codexDeviceCode.isNullOrBlank()) "Start device code login" else "Open verification page",
                                primary = true,
                                onClick = { onStartSignIn("device") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        "browser" -> {
                            PhonePillButton(
                                label = "Open browser login",
                                onClick = { onStartSignIn("browser") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            SettingsTextInput(
                                value = codeOrKeyInput,
                                onValueChange = { codeOrKeyInput = it },
                                placeholder = "Paste http://localhost:1455/auth/callback?...",
                                mono = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            PhonePillButton(
                                label = "Submit callback URL",
                                primary = true,
                                enabled = codeOrKeyInput.isNotBlank(),
                                onClick = { onSubmit("callback", codeOrKeyInput, null) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        else -> {
                            SettingsTextInput(
                                value = labelInput,
                                onValueChange = { labelInput = it },
                                placeholder = "Label (optional)",
                                modifier = Modifier.fillMaxWidth(),
                            )
                            SettingsPasswordInput(
                                value = codeOrKeyInput,
                                onValueChange = { codeOrKeyInput = it },
                                placeholder = "sk-...",
                                visible = passwordVisible,
                                onToggleVisible = { passwordVisible = !passwordVisible },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            PhonePillButton(
                                label = "Add API key",
                                primary = true,
                                enabled = codeOrKeyInput.isNotBlank(),
                                onClick = { onSubmit("key", codeOrKeyInput, labelInput) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                else -> {
                    BasicText(
                        text = "Sign in with Google for Antigravity, then paste the callback URL or authorization code if needed.",
                        style = ppmText(12f, lineHeight = 17f).copy(color = tokens.text2.color),
                    )
                    PhonePillButton(
                        label = "Open Google sign-in",
                        primary = true,
                        onClick = { onStartSignIn("google") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SettingsTextInput(
                        value = codeOrKeyInput,
                        onValueChange = { codeOrKeyInput = it },
                        placeholder = "Paste callback URL or code",
                        mono = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PhonePillButton(
                        label = "Complete sign-in",
                        primary = true,
                        enabled = codeOrKeyInput.isNotBlank(),
                        onClick = { onSubmit("code", codeOrKeyInput, null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
