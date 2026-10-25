package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.chat.DiscMarker
import com.hienlh.ppm.chat.MarkdownStyle
import com.hienlh.ppm.client.codexAccounts
import com.hienlh.ppm.client.codexUsages
import com.hienlh.ppm.client.setCodexAccountStatus
import com.hienlh.ppm.client.setCodexDailyGuard
import com.hienlh.ppm.client.spendCodexResetCredit
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmFormat
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.AccountUsage
import com.hienlh.ppm.protocol.AccountUsageEntry
import com.hienlh.ppm.protocol.CodexAccount
import com.hienlh.ppm.protocol.LimitBucket
import com.hienlh.ppm.protocol.ResetCredits
import com.hienlh.ppm.protocol.UsageInfo
import com.hienlh.ppm.state.CodexAccountsData
import com.hienlh.ppm.state.canUseResetCredit
import com.hienlh.ppm.state.codexPlanLabel
import com.hienlh.ppm.state.codexUnselectableReason
import com.hienlh.ppm.state.dailyGuardState
import com.hienlh.ppm.state.isoMillis
import com.hienlh.ppm.state.resetCreditOutcome
import com.hienlh.ppm.state.switchRefusal
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** `CODEX_SIGNED_OUT_HINT`: why a signed-out account's card is dimmed though its bars look fine. */
private const val CODEX_SIGNED_OUT_HINT =
    "Codex rejected this account's login (revoked or expired), so no turn can run on it and it is skipped. " +
        "The usage shown is the last reading. Click to sign in again — the account keeps its settings and chats."

/** The Codex accounts as the panel reads and switches them: the server's codex-accounts routes. */
private class ApiCodexAccounts(private val model: AppModel) : CodexAccountsData.Source {
    private val api get() = model.api ?: throw IllegalStateException("Not connected")

    override suspend fun accounts() = api.codexAccounts()

    override suspend fun usages() = api.codexUsages()

    override suspend fun setStatus(id: String, status: String) = api.setCodexAccountStatus(id, status)

    override suspend fun setDailyGuard(id: String, enabled: Boolean) = api.setCodexDailyGuard(id, enabled)
}

/**
 * The Codex usage panel (codex-usage-panel.tsx): the Claude panel's frame and cards, the accounts
 * from the Codex routes, read on opening and every two minutes. A weekly-only account gets its
 * Daily guard, one at a limit with a free reset left its "Use reset". With no managed account, the
 * default `~/.codex` login this chat runs on. [onReload] has the server read every account live
 * before the panel reads them again; [onSelectAccount] as in [ClaudeUsagePanel].
 */
@Composable
fun CodexUsagePanel(
    model: AppModel,
    usage: UsageInfo,
    selectedAccountId: String?,
    onSelectAccount: (suspend (id: String, label: String?) -> String?)?,
    onReload: (suspend () -> Unit)?,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val data = remember { CodexAccountsData(ApiCodexAccounts(model)) }
    // `setInterval`: on the clock, whether or not the last read is back.
    LaunchedEffect(data) {
        while (true) {
            launch { data.load() }
            delay(CodexAccountsData.POLL_MILLIS)
        }
    }
    val state by data.state.collectAsState()
    var selectingId by remember { mutableStateOf<String?>(null) }
    var panelError by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()
    val reload: (() -> Unit)? = onReload?.let { refresh -> { scope.launch { data.reload(refresh) } } }

    fun select(id: String) {
        val pick = onSelectAccount ?: return
        val account = state.accounts.firstOrNull { it.id == id } ?: return
        // Refused here rather than left inert, so the press is answered; the server checks again.
        codexUnselectableReason(account, state.usages[id], System.currentTimeMillis())?.let {
            panelError = switchRefusal(it)
            return
        }
        selectingId = id
        panelError = null
        scope.launch {
            try {
                panelError = pick(id, account.label)
            } finally {
                selectingId = null
            }
        }
    }

    fun change(patch: suspend () -> String?) {
        panelError = null
        scope.launch { panelError = patch() }
    }

    UsagePanelShell(
        model,
        title = "Codex Usage",
        meta = null,
        onClose = onClose,
        onReload = reload,
        reloading = state.loading,
        error = panelError,
        onDismissError = { panelError = null },
        cardCount = state.accounts.size,
        fallback = { AmbientCodexLogin(usage, loading = state.loading, now) },
        footer = null,
    ) { layout ->
        for (account in state.accounts) {
            key(account.id) {
                val u = state.usages[account.id] ?: UsageInfo()
                val guard = if (u.session == null) dailyGuardState(u.weekly, now) else null
                AccountCard(
                    // `isOAuth` carries the kind: a ChatGPT sign-in or a static API key.
                    AccountUsageEntry(account.id, account.label, account.status, account.type != "apiKey", AccountUsage(session = u.session, weekly = u.weekly)),
                    // What serves this chat, not what the rotation would pick next.
                    isActive = account.id == (selectedAccountId ?: usage.activeAccountId),
                    accountInfo = null,
                    layout = layout,
                    onToggle = { id, status -> change { data.toggle(id, status) } },
                    toggling = state.togglingId == account.id,
                    onSelect = if (onSelectAccount != null) ::select else null,
                    unselectableReason = codexUnselectableReason(account, u, now),
                    selecting = selectingId == account.id,
                    planLabel = codexPlanLabel(account.planType),
                    reauthHint = if (account.signedOut) CODEX_SIGNED_OUT_HINT else null,
                    // Only a ChatGPT sign-in is renewed in place, and that is Settings' until 12b.
                    onReauth = if (account.type == "chatgpt") { _ -> model.openSettings() } else null,
                    dailyGuard = guard?.let { CardDailyGuard(account.dailyGuardEnabled, it) },
                    onDailyGuardToggle = guard?.let { { change { data.toggleDailyGuard(account.id, account.dailyGuardEnabled) } } },
                    dailyGuardToggling = state.togglingId == account.id,
                    resetCredits = u.resetCredits,
                    resetCreditAction = {
                        CodexResetCreditButton(model, account, u) { message ->
                            panelError = message
                            reload?.invoke()
                        }
                    },
                )
            }
        }
    }
}

/**
 * No managed account: the default `~/.codex` login this chat runs on, as a card with this chat's
 * reading, and where accounts are added. "Loading…" while the first read is out.
 */
@Composable
private fun AmbientCodexLogin(usage: UsageInfo, loading: Boolean, now: Long) {
    val tokens = LocalPpmTokens.current
    val subtle = tokens.text3.color
    if (loading) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PpmIcon(ProductIcon.Loader2, 12.dp, subtle, spinning = true)
            BasicText("Loading…", style = ppmText(12f, lineHeight = 16f).copy(color = subtle))
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // `AccountCardShell`, not dense: `rounded-md border border-border/50 p-3 space-y-2`.
        val shape = RoundedCornerShape(8.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape).cssBorder(tokens.border.opacity(0.5).color, shape).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(
                    usage.activeAccountLabel?.takeIf { it.isNotEmpty() } ?: "Default login",
                    Modifier.weight(1f),
                    style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
                // `uppercase tracking-wide border border-border rounded px-1`.
                val tag = RoundedCornerShape(4.dp)
                BasicText(
                    "~/.CODEX",
                    Modifier.clip(tag).cssBorder(tokens.border.color, tag).padding(horizontal = 4.dp),
                    style = ppmText(10f, lineHeight = 15f).copy(color = subtle, letterSpacing = 0.25.sp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccountBucketRow("5-Hour Session", usage.session, now)
                AccountBucketRow("Weekly", usage.weekly, now)
                if (usage.session == null && usage.weekly == null) {
                    BasicText("No usage data yet", style = ppmText(12f, lineHeight = 16f).copy(color = subtle))
                }
            }
        }
        BasicText(
            buildAnnotatedString {
                append("Using your default ")
                // `<code>`: the monospace face at the same size, ligatures off.
                withStyle(SpanStyle(fontFamily = PpmFonts.mono, fontFeatureSettings = "\"liga\" 0, \"calt\" 0")) { append("~/.codex") }
                append(" login. Add managed accounts in Settings → AI Provider → Codex.")
            },
            style = ppmText(11f, lineHeight = 16.5f).copy(color = subtle),
        )
    }
}

/**
 * "Use reset" (codex-reset-credit-button.tsx): spends one of Codex's free rate-limit resets, and
 * is there only once a limit is reached, since a reset moves the weekly reset date and, spent
 * early, throws away what was left. Asks first, saying so: a bottom sheet on a phone, a dialog
 * otherwise. [onDone] gets what happened, or why it was refused.
 */
@Composable
private fun CodexResetCreditButton(model: AppModel, account: CodexAccount, usage: UsageInfo, onDone: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    if (!canUseResetCredit(usage)) return
    val credits = usage.resetCredits ?: return
    val mobile = LocalPpmMobile.current
    // `dark:` is the device's dark mode on the web, not PPM's theme.
    val dark = isSystemInDarkTheme()

    fun spend() {
        busy = true
        scope.launch {
            try {
                val api = model.api ?: throw IllegalStateException("Not connected")
                onDone(resetCreditOutcome(api.spendCodexResetCredit(account.id), account.label))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onDone(e.message?.takeIf { it.isNotEmpty() } ?: "Could not use the reset")
            } finally {
                busy = false
                confirming = false
            }
        }
    }

    UseResetButton(mobile, dark, enabled = !busy) { confirming = true }
    if (!confirming) return
    val body: @Composable () -> Unit = {
        ResetConfirmBody(account.label, credits, usage.weekly, mobile, dark, busy, onKeep = { confirming = false }, onUse = ::spend)
    }
    if (mobile) {
        BottomSheet(onDismiss = { confirming = false }, dismissible = !busy) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                BasicText(
                    "Use a free reset",
                    Modifier.padding(bottom = 12.dp),
                    style = ppmText(16f, FontWeight.SemiBold, lineHeight = 24f).copy(color = tokens.text.color),
                )
                body()
            }
        }
    } else {
        // `sm:max-w-sm`, and the class's `text-sm` brings its 20 px line, which tailwind-merge lets
        // replace the title's own `leading-none`.
        WebDialog(
            "Use a free reset",
            onDismiss = { if (!busy) confirming = false },
            widest = 384.dp,
            titleStyle = ppmText(14f, FontWeight.SemiBold, lineHeight = 20f),
            content = body,
        )
    }
}

/**
 * The card's "Use reset" (`Button size="sm" variant="outline"`, `gap-1 px-2 text-[11px]
 * border-primary/40 text-primary`): 44 tall on a phone (`min-h-11`), 28 otherwise (`md:h-7`), the
 * icon's `has-[>svg]:px-2.5` outranking the `px-2`. In dark mode `dark:border-input` outranks the
 * accent border.
 */
@Composable
private fun UseResetButton(mobile: Boolean, dark: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val hover = rememberHover(enabled = enabled)
    val content = tokens.text.mix(tokens.accent, hover.lit.toDouble()).color
    Row(
        Modifier
            .height(if (mobile) 44.dp else 28.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .ppmShadow(SHADOW_XS, shape)
            .clip(shape)
            .background(outlineFill(tokens, dark, hover.lit.toDouble()))
            .pointerHoverIcon(WebCursor.Pointer)
            .ppmClickable(enabled = enabled, pressedColor = tokens.panel2.color, shape = shape, source = hover.source, onClick = onClick)
            .cssBorder(if (dark) tokens.border.color else tokens.accent.opacity(0.4).color, shape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        PpmIcon(ProductIcon.RotateCcw, 14.dp, content)
        BasicText("Use reset", style = ppmText(11f, FontWeight.Medium, lineHeight = 16.5f).copy(color = content), maxLines = 1, softWrap = false)
    }
}

/**
 * What a reset does, before it is spent (`space-y-3`): the question, the three facts as a disc list
 * (`text-xs text-text-secondary space-y-1.5 list-disc pl-4`), then "Keep it" and "Use reset" —
 * stacked with "Use reset" on top on a phone (`flex-col-reverse`), side by side on the right
 * otherwise.
 */
@Composable
private fun ResetConfirmBody(
    label: String,
    credits: ResetCredits,
    weekly: LimitBucket?,
    mobile: Boolean,
    dark: Boolean,
    busy: Boolean,
    onKeep: () -> Unit,
    onUse: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    fun local(iso: String?) = iso?.let(::isoMillis)?.let { PpmFormat.mediumDateShortTime(Instant.ofEpochMilli(it)) }
    val weeklyNow = local(weekly?.resetsAt?.takeIf { it.isNotEmpty() })
    val expires = local(credits.nextExpiresAt)
    val n = credits.available
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText(
            buildAnnotatedString {
                append("Use 1 of ")
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(label) }
                append("'s $n free reset${if (n == 1) "" else "s"}?")
            },
            style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text.color),
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ResetFact("${credits.title ?: "Full reset"}: the 5-hour and weekly usage go back to 0% now.")
            ResetFact(
                "The weekly reset date moves: the next one is about 7 days after you continue using Codex" +
                    "${weeklyNow?.let { ", not $it as now" }.orEmpty()}. You do not also get that one.",
            )
            expires?.let { ResetFact("This spends the reset that expires first ($it).") }
        }
        val keep: @Composable (Modifier) -> Unit = { DialogButton("Keep it", primary = false, dark, enabled = !busy, it, onClick = onKeep) }
        val use: @Composable (Modifier) -> Unit = {
            DialogButton("Use reset", primary = true, dark, enabled = !busy, it, icon = if (busy) ProductIcon.Loader2 else ProductIcon.RotateCcw, spinning = busy, onClick = onUse)
        }
        if (mobile) {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                use(Modifier.fillMaxWidth())
                keep(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                keep(Modifier)
                use(Modifier)
            }
        }
    }
}

/** One fact of the confirmation: Chrome's disc in the list's 16 px, the words beside it. */
@Composable
private fun ResetFact(text: String) {
    val tokens = LocalPpmTokens.current
    // A 16 px line to the pixel: 16f / 12f lands a hair over, and Compose rounds a line up.
    val item = MarkdownStyle(12f, 1.3333333f, color = tokens.text2)
    Row {
        DiscMarker(item, tokens.text2.color, Modifier.alignByBaseline())
        BasicText(text, Modifier.alignByBaseline().weight(1f), style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
    }
}
