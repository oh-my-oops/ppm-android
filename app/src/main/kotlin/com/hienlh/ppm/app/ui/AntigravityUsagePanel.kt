package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.client.antigravityAccounts
import com.hienlh.ppm.client.antigravityUsages
import com.hienlh.ppm.client.setAntigravityAccountStatus
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.AccountUsage
import com.hienlh.ppm.protocol.AccountUsageEntry
import com.hienlh.ppm.protocol.AntigravityAccount
import com.hienlh.ppm.protocol.UsageInfo
import com.hienlh.ppm.state.AntigravityAccountsData
import com.hienlh.ppm.state.antigravityUnselectableReason
import com.hienlh.ppm.state.currentAntigravityUsage
import com.hienlh.ppm.state.formatLastUpdated
import com.hienlh.ppm.state.isAntigravityUsageStale
import com.hienlh.ppm.state.isoMillis
import com.hienlh.ppm.state.switchRefusal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** `STALE_HINT` in `antigravity-usage-panel.tsx`. */
internal const val ANTIGRAVITY_STALE_HINT =
    "Neither agy nor the Antigravity app has answered since, so the bars still show that reading. Check that agy is installed and signed in."

/** The Antigravity accounts as the panel reads and switches them: `/api/antigravity-accounts` routes. */
private class ApiAntigravityAccounts(private val model: AppModel) : AntigravityAccountsData.Source {
    private val api get() = model.api ?: throw IllegalStateException("Not connected")

    override suspend fun accounts() = api.antigravityAccounts()

    override suspend fun usages() = api.antigravityUsages()

    override suspend fun setStatus(id: String, status: String) {
        api.setAntigravityAccountStatus(id, status)
    }
}

/**
 * The Antigravity usage panel (`antigravity-usage-panel.tsx`): managed accounts and per-account
 * quota bars, read on open and every two minutes, with fallback to the ambient Antigravity Desktop
 * session reading when no managed accounts are returned.
 */
@Composable
fun AntigravityUsagePanel(
    model: AppModel,
    usage: UsageInfo,
    loading: Boolean,
    lastFetchedAt: String?,
    selectedAccountId: String?,
    onSelectAccount: (suspend (id: String, label: String?) -> String?)?,
    onReload: (suspend () -> Unit)?,
    onClose: () -> Unit,
) {
    val data = remember { AntigravityAccountsData(ApiAntigravityAccounts(model)) }
    LaunchedEffect(data) {
        while (true) {
            launch { data.load() }
            delay(AntigravityAccountsData.POLL_MILLIS)
        }
    }
    AntigravityUsagePanelContent(
        data = data,
        usage = usage,
        loading = loading,
        lastFetchedAt = lastFetchedAt,
        selectedAccountId = selectedAccountId,
        onManageAccounts = { model.openSettings("accounts") },
        onSetOverlay = { model.screenOverlay = it },
        onClearOverlay = { if (model.screenOverlay === it) model.screenOverlay = null },
        onSelectAccount = onSelectAccount,
        onReload = onReload,
        onClose = onClose,
    )
}

@Composable
internal fun AntigravityUsagePanelContent(
    data: AntigravityAccountsData,
    usage: UsageInfo,
    loading: Boolean,
    lastFetchedAt: String?,
    selectedAccountId: String?,
    now: () -> Long = System::currentTimeMillis,
    onManageAccounts: () -> Unit,
    onSetOverlay: ((@Composable () -> Unit) -> Unit)? = null,
    onClearOverlay: ((@Composable () -> Unit) -> Unit)? = null,
    onSelectAccount: (suspend (id: String, label: String?) -> String?)?,
    onReload: (suspend () -> Unit)?,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by data.state.collectAsState()
    var selectingId by remember { mutableStateOf<String?>(null) }
    val currentNow = now()
    val reload: (() -> Unit)? = onReload?.let { refresh -> { scope.launch { data.reload(refresh) } } }

    fun select(id: String) {
        val pick = onSelectAccount ?: return
        val account = state.accounts.firstOrNull { it.id == id } ?: return
        antigravityUnselectableReason(account, state.usages[id], now())?.let {
            data.setError(switchRefusal(it))
            return
        }
        selectingId = id
        data.setError(null)
        scope.launch {
            try {
                data.setError(pick(id, account.displayLabel))
            } finally {
                selectingId = null
            }
        }
    }

    UsagePanelShell(
        onManageAccounts = onManageAccounts,
        onSetOverlay = onSetOverlay,
        onClearOverlay = onClearOverlay,
        title = "Antigravity Usage",
        meta = lastFetchedAt?.let(::isoMillis)?.let { formatLastUpdated(it, currentNow) },
        onClose = onClose,
        onReload = reload,
        reloading = loading || state.loading,
        error = state.error,
        onDismissError = { data.setError(null) },
        cardCount = state.accounts.size,
        fallback = {
            AmbientAntigravityLogin(
                usage = usage,
                parentLoading = loading,
                loading = state.loading,
                error = state.error,
                now = currentNow,
            )
        },
        footer = null,
    ) { layout ->
        val activeId = selectedAccountId ?: usage.activeAccountId
        for (account in state.accounts) {
            key(account.id) {
                val u = currentAntigravityUsage(state.usages[account.id] ?: UsageInfo(), currentNow)
                val isActive = account.id == activeId || (account.email != null && account.email == activeId)
                AccountCard(
                    entry = AccountUsageEntry(
                        accountId = account.id,
                        accountLabel = account.label,
                        accountStatus = account.status,
                        isOAuth = true,
                        usage = AccountUsage(
                            lastFetchedAt = u.lastFetchedAt,
                            session = u.session,
                            weekly = u.weekly,
                            weeklyScoped = u.weeklyScoped,
                        ),
                    ),
                    isActive = isActive,
                    accountInfo = null,
                    layout = layout,
                    onToggle = { id, status -> scope.launch { data.toggle(id, status) } },
                    toggling = state.togglingId == account.id,
                    onSelect = if (onSelectAccount != null) ::select else null,
                    unselectableReason = antigravityUnselectableReason(account, u, currentNow),
                    selecting = selectingId == account.id,
                    planLabel = account.planType,
                    staleUsageHint = if (isAntigravityUsageStale(u.lastFetchedAt, currentNow)) ANTIGRAVITY_STALE_HINT else null,
                    now = currentNow,
                )
            }
        }
    }
}

/**
 * Fallback card when `accounts` is empty (`antigravity-usage-panel.tsx`): shows the ambient
 * Antigravity Desktop / language-server quota reading.
 */
@Composable
private fun AmbientAntigravityLogin(
    usage: UsageInfo,
    parentLoading: Boolean,
    loading: Boolean,
    error: String?,
    now: Long,
) {
    val tokens = LocalPpmTokens.current
    val subtle = tokens.text3.color
    if (loading) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PpmIcon(ProductIcon.Loader2, 12.dp, subtle, spinning = true)
            BasicText("Loading…", style = ppmText(12f, lineHeight = 16f).copy(color = subtle))
        }
        return
    }
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.accent.opacity(0.05).color)
            .cssBorder(tokens.accent.opacity(0.3).color, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(
                usage.activeAccountLabel?.takeIf { it.isNotEmpty() } ?: "Antigravity Desktop",
                Modifier.weight(1f),
                style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            )
            val accountId = usage.activeAccountId
            if (!accountId.isNullOrEmpty()) {
                val tag = RoundedCornerShape(4.dp)
                BasicText(
                    accountId,
                    Modifier.widthIn(max = 180.dp).clip(tag).cssBorder(tokens.border.color, tag).padding(horizontal = 6.dp, vertical = 2.dp),
                    style = ppmText(10f, lineHeight = 15f).copy(color = subtle),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
            }
        }
        val hasBuckets = usage.session != null || usage.weekly != null || usage.weeklyScoped.isNotEmpty()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AccountBucketRow("Gemini · 5-Hour", usage.session, now)
            AccountBucketRow("Gemini · Weekly", usage.weekly, now)
            for (scoped in usage.weeklyScoped) {
                AccountBucketRow(scoped.label.orEmpty(), scoped, now)
            }
            if (!hasBuckets) {
                val emptyText = when {
                    parentLoading || loading -> "Loading quota from Antigravity Language Server…"
                    error != null -> error
                    else -> "No quota data available"
                }
                BasicText(emptyText, style = ppmText(12f, lineHeight = 16f).copy(color = subtle))
            }
        }
    }
}
