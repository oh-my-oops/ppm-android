package com.hienlh.ppm.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
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
    val dark = model.theme.isDark

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
        WebDialog("Use a free reset", onDismiss = { if (!busy) confirming = false }, content = body)
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

/**
 * The web's `Button` at its default size with `min-h-11`: 44 tall, `rounded-md text-sm
 * font-medium`, either `default` (the accent, `px-3` around its icon, `gap-1.5`) or `outline`
 * (`px-4`), half-faded while a reset is out.
 */
@Composable
private fun DialogButton(
    label: String,
    primary: Boolean,
    dark: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    icon: ProductIcon? = null,
    spinning: Boolean = false,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val hover = rememberHover(enabled = enabled)
    val content = if (primary) tokens.accentFg.color else tokens.text.color
    Row(
        modifier
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .then(if (primary) Modifier else Modifier.ppmShadow(SHADOW_XS, shape))
            .clip(shape)
            .background(if (primary) tokens.accent.opacity(1 - 0.1 * hover.lit).color else outlineFill(tokens, dark, hover.lit.toDouble()))
            .ppmClickable(
                enabled = enabled,
                pressedColor = if (primary) Color.Black.copy(alpha = 0.1f) else tokens.panel2.color,
                shape = shape,
                source = hover.source,
                onClick = onClick,
            )
            .then(if (primary) Modifier else Modifier.cssBorder(outlineBorder(tokens, dark), shape))
            .padding(horizontal = if (icon != null) 12.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) PpmIcon(icon, 16.dp, content, spinning = spinning)
        BasicText(label, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = content), maxLines = 1, softWrap = false)
    }
}

/** CSS's `ease`, what tw-animate-css enters with. */
private val CssEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/**
 * The web's `Dialog` around `DialogContent className="sm:max-w-sm"` (shadcn): a 50% black
 * backdrop, a card at most 384 wide and 16 in from each side, `rounded-lg border bg-background
 * p-6 shadow-lg`, the title (`text-sm leading-none font-semibold`, clear of the close cross) 16
 * over [content]. It fades in and grows from 95%; it goes at once, as the web unmounts it.
 */
@Composable
private fun WebDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    if (closedByConnectionLost(onDismiss)) return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The platform's own dim would fade on its own clock; the backdrop is drawn here.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) { window?.setDimAmount(0f) }
        val backdrop = remember { Animatable(0f) }
        val card = remember { Animatable(0f) }
        LaunchedEffect(Unit) { launch { backdrop.animateTo(1f, tween(150, easing = CssEase)) }; card.animateTo(1f, tween(200, easing = CssEase)) }
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f * backdrop.value))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .width(minOf(maxWidth - 32.dp, 384.dp))
                    .graphicsLayer {
                        alpha = card.value
                        scaleX = 0.95f + 0.05f * card.value
                        scaleY = scaleX
                    }
                    .then(ShadowLg.fold(Modifier as Modifier) { m, s -> m.ppmShadow(s, shape) })
                    .clip(shape)
                    .background(tokens.bgSolid.color)
                    // A tap on the card stays on it rather than reaching the backdrop.
                    .pointerInput(Unit) { detectTapGestures {} }
                    .cssBorder(tokens.border.color, shape),
            ) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    BasicText(title, Modifier.padding(end = 32.dp), style = ppmText(14f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text.color))
                    content()
                }
                // `absolute top-4 right-4 opacity-70 hover:opacity-100`, a 16 px cross.
                val hover = rememberHover()
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 16.dp, end = 16.dp)
                        .alpha(0.7f + 0.3f * hover.lit)
                        .pointerHoverIcon(WebCursor.Pointer)
                        .ppmClickable(pressedColor = Color.Transparent, source = hover.source, onClickLabel = "Close", onClick = onDismiss),
                ) {
                    PpmIcon(ProductIcon.X, 16.dp, tokens.text.color)
                }
            }
        }
    }
}
