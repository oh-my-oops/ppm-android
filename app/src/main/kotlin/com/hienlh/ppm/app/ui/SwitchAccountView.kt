package com.hienlh.ppm.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.state.MANY_SWITCH_ACCOUNTS
import com.hienlh.ppm.state.SwitchAccount
import com.hienlh.ppm.state.formatResetLine
import com.hienlh.ppm.state.matchesAccountQuery
import com.hienlh.ppm.state.orderSwitchAccounts
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Exposes `"error"` (when window >= 100%) or `"muted"` on each account reset line for tests. */
val AccountResetToneKey = SemanticsPropertyKey<String>("AccountResetTone")

/**
 * Drill-in "Switch account" view inside the chat's ··· menu (`SwitchAccountView` in
 * `switch-account-view.tsx`).
 */
@Composable
fun SwitchAccountView(
    accounts: List<SwitchAccount>,
    loading: Boolean,
    currentId: String?,
    providerName: String,
    onBack: () -> Unit,
    onPick: (suspend (id: String, label: String) -> String?)?,
    onPicked: () -> Unit,
    onReauth: ((SwitchAccount) -> Unit)? = null,
    onAddAccount: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val reducedMotion = rememberPrefersReducedMotion()
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var foldOpen by remember { mutableStateOf(false) }
    var pickingId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchFocused by remember { mutableStateOf(false) }

    val many = accounts.size >= MANY_SWITCH_ACCOUNTS
    val ordered = remember(accounts, currentId) { orderSwitchAccounts(accounts, currentId) }
    val shownUsable = remember(ordered.usable, query) { ordered.usable.filter { matchesAccountQuery(it, query) } }
    val shownOut = remember(ordered.outOfUsage, query) { ordered.outOfUsage.filter { matchesAccountQuery(it, query) } }
    val outOpen = foldOpen || query.trim().isNotEmpty()

    fun pick(account: SwitchAccount) {
        val refusal = account.refusal
        if (refusal != null) {
            if (account.signedOut && onReauth != null) {
                onReauth(account)
            } else {
                error = "Cannot switch to ${account.label} — ${refusal.lowercase()}."
            }
            return
        }
        if (account.id == currentId) {
            onPicked()
            return
        }
        if (onPick == null) {
            error = "The account can be changed once this chat has finished starting."
            return
        }
        if (pickingId != null) return
        pickingId = account.id
        error = null
        scope.launch {
            try {
                val refused = onPick(account.id, account.label)
                if (refused != null) {
                    error = refused
                } else {
                    if (!reducedMotion) delay(260L)
                    onPicked()
                }
            } finally {
                pickingId = null
            }
        }
    }

    Column(modifier.fillMaxWidth().testTag("switch-account-view")) {
        FooterPopoverHeader {
            val backSize = if (mobile) 44.dp else 28.dp
            val backShape = RoundedCornerShape(6.dp)
            Box(
                Modifier
                    .size(backSize)
                    .clip(backShape)
                    .semantics { contentDescription = "Back" }
                    .ppmClickable(
                        pressedColor = tokens.surfaceHover.color,
                        hoverColor = tokens.surfaceHover.color,
                        shape = backShape,
                        role = Role.Button,
                        onClick = onBack,
                    )
                    .testTag("switch-account-back"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.ChevronLeft, if (mobile) 16.dp else 14.dp, tokens.text3.color)
            }
            BasicText(
                "Switch account",
                style = ppmText(if (mobile) 14f else 13f, FontWeight.SemiBold, lineHeight = if (mobile) 20f else 18f).copy(
                    color = tokens.text.color,
                ),
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.weight(1f))
            BasicText(
                if (many) "${accounts.size} accounts · $providerName" else "for this chat",
                style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                maxLines = 1,
                softWrap = false,
            )
        }

        error?.let { err ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom)
                    .background(tokens.error.opacity(0.05).color)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Assertive }
                    .testTag("switch-account-alert"),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicText(
                    err,
                    Modifier.weight(1f),
                    style = ppmText(if (mobile) 12f else 11f, lineHeight = 16f).copy(color = tokens.error.color),
                )
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .semantics { contentDescription = "Dismiss" }
                        .ppmClickable(
                            pressedColor = tokens.error.opacity(0.10).color,
                            hoverColor = tokens.error.opacity(0.10).color,
                            shape = RoundedCornerShape(4.dp),
                            role = Role.Button,
                            onClick = { error = null },
                        )
                        .testTag("switch-account-alert-dismiss"),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.X, 12.dp, tokens.error.color)
                }
            }
        }

        if (many) {
            val searchShape = RoundedCornerShape(8.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp)
                    .height(if (mobile) 44.dp else 32.dp)
                    .clip(searchShape)
                    .background(tokens.panel.color)
                    .cssBorder(
                        if (searchFocused) tokens.accentWashBorder.color else tokens.border.color,
                        searchShape,
                    )
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PpmIcon(ProductIcon.Search, 14.dp, tokens.text3.color)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = ppmText(if (mobile) 16f else 12f, lineHeight = if (mobile) 22f else 16f).copy(
                        color = tokens.text.color,
                    ),
                    cursorBrush = SolidColor(tokens.accent.color),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { searchFocused = it.isFocused }
                        .semantics { contentDescription = "Find an account" }
                        .testTag("switch-account-search"),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                BasicText(
                                    "Find an account",
                                    style = ppmText(if (mobile) 16f else 12f, lineHeight = if (mobile) 22f else 16f).copy(
                                        color = tokens.text3.color,
                                    ),
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
        }

        val listScroll = rememberScrollState()
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    when {
                        mobile -> Modifier
                        many -> Modifier.heightIn(max = 300.dp).padding(start = 4.dp, end = 4.dp, bottom = 4.dp)
                        else -> Modifier
                    },
                )
                .verticalScroll(listScroll),
        ) {
            when {
                loading && accounts.isEmpty() -> {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PpmIcon(ProductIcon.Loader2, 14.dp, tokens.text3.color, spinning = true)
                        BasicText(
                            "Loading accounts…",
                            style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
                        )
                    }
                }
                accounts.isEmpty() -> {
                    BasicText(
                        "No $providerName accounts yet. Add one to choose which serves this chat.",
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
                        style = ppmText(12f, lineHeight = 18f).copy(
                            color = tokens.text3.color,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
                else -> {
                    if (many && shownUsable.isNotEmpty()) {
                        BasicText(
                            "CURRENT, THEN MOST HEADROOM",
                            Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
                            style = ppmText(10f, FontWeight.SemiBold, lineHeight = 14f).copy(
                                color = tokens.text3.color,
                                letterSpacing = 0.5.sp,
                            ),
                        )
                    }
                    Column(
                        Modifier.fillMaxWidth().padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (account in shownUsable) {
                            SwitchAccountCard(
                                account = account,
                                active = account.id == currentId,
                                picking = pickingId == account.id,
                                onPick = { pick(account) },
                            )
                        }
                        if (!many) {
                            for (account in ordered.outOfUsage) {
                                SwitchAccountCard(
                                    account = account,
                                    active = account.id == currentId,
                                    picking = pickingId == account.id,
                                    onPick = { pick(account) },
                                )
                            }
                        }
                    }

                    if (many && shownOut.isNotEmpty()) {
                        val chevronTurn by animateFloatAsState(
                            targetValue = if (outOpen) 180f else 0f,
                            animationSpec = tween(if (reducedMotion) 0 else 150),
                            label = "outOfUsageChevron",
                        )
                        val foldShape = RoundedCornerShape(6.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = if (mobile) 44.dp else 34.dp)
                                .clip(foldShape)
                                .ppmClickable(
                                    pressedColor = tokens.surfaceHover.color,
                                    hoverColor = tokens.surfaceHover.color,
                                    shape = foldShape,
                                    role = Role.Button,
                                    onClick = { foldOpen = !foldOpen },
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .semantics { stateDescription = if (outOpen) "expanded" else "collapsed" }
                                .testTag("out-of-usage-toggle"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PpmIcon(ProductIcon.Lock, 16.dp, tokens.text3.color)
                            BasicText(
                                "Out of usage",
                                style = ppmText(if (mobile) 14f else 13f, lineHeight = if (mobile) 20f else 18f).copy(
                                    color = tokens.text.color,
                                ),
                            )
                            Spacer(Modifier.weight(1f))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                BasicText(
                                    "${shownOut.size}",
                                    style = ppmText(11f, lineHeight = 15f).copy(
                                        color = tokens.text3.color,
                                        fontFeatureSettings = "tnum",
                                    ),
                                )
                                PpmIcon(ProductIcon.ChevronDown, 12.dp, tokens.text3.color, Modifier.rotate(chevronTurn))
                            }
                        }
                        FoldGroup(open = outOpen) {
                            Column(
                                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                for (account in shownOut) {
                                    SwitchAccountCard(
                                        account = account,
                                        active = account.id == currentId,
                                        picking = pickingId == account.id,
                                        onPick = { pick(account) },
                                    )
                                }
                            }
                        }
                    }

                    if (many && shownUsable.isEmpty() && shownOut.isEmpty()) {
                        BasicText(
                            "No account matches.",
                            Modifier.fillMaxWidth().padding(8.dp),
                            style = ppmText(12f, lineHeight = 18f).copy(
                                color = tokens.text3.color,
                                textAlign = TextAlign.Center,
                            ),
                        )
                    }
                }
            }
        }

        FooterPopoverFooter {
            FooterButton(
                onClick = onAddAccount,
                testTag = "switch-account-add",
            ) {
                PpmIcon(ProductIcon.Plus, if (mobile) 16.dp else 14.dp, LocalFooterContentColor.current)
                BasicText("Add account", style = LocalFooterTextStyle.current, maxLines = 1, softWrap = false)
            }
            FooterButton(
                onClick = onManage,
                pushEnd = true,
                testTag = "switch-account-manage",
            ) {
                PpmIcon(ProductIcon.Settings, if (mobile) 16.dp else 14.dp, LocalFooterContentColor.current)
                BasicText("Manage in Settings", style = LocalFooterTextStyle.current, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun SwitchAccountCard(
    account: SwitchAccount,
    active: Boolean,
    picking: Boolean,
    onPick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val reducedMotion = rememberPrefersReducedMotion()
    val blocked = account.refusal != null
    val shape = RoundedCornerShape(10.dp)

    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val canScale = !(blocked && !account.signedOut) && !reducedMotion
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && canScale) 0.97f else 1f,
        animationSpec = tween(if (reducedMotion) 0 else 150),
        label = "accountCardScale",
    )

    val bg = if (active) tokens.accentWash.color else tokens.panel.color
    val border = when {
        active -> tokens.accentWashBorder.color
        !mobile && hovered -> tokens.border.color
        else -> tokens.borderSoft.color
    }
    val title = when {
        account.signedOut -> "${account.label} — sign in again"
        account.refusal != null -> "${account.label} — ${account.refusal}"
        else -> account.label
    }

    Column(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = if (mobile) 44.dp else 0.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(shape)
            .background(bg)
            .cssBorder(border, shape)
            .webTitle(title)
            .semantics {
                selected = active
                stateDescription = when {
                    blocked && !account.signedOut -> "disabled"
                    active -> "current"
                    else -> ""
                }
            }
            .hoverable(interactions)
            .clickable(
                interactionSource = interactions,
                indication = null,
                role = Role.Button,
                onClick = onPick,
            )
            .padding(10.dp)
            .testTag("account-row:${account.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TruncatedText(
                AnnotatedString(account.label),
                ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(
                    color = if (blocked) tokens.text3.color else tokens.text.color,
                ),
                Modifier.weight(1f, fill = false),
            )
            account.plan?.let { plan ->
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(tokens.panel2.color)
                        .cssBorder(tokens.borderSoft.color, CircleShape)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    BasicText(
                        plan,
                        style = ppmText(10f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text2.color),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (picking) {
                val spinAngle = if (!reducedMotion) {
                    val transition = rememberInfiniteTransition(label = "accountPicking")
                    val angle by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
                        label = "accountPickingAngle",
                    )
                    angle
                } else {
                    0f
                }
                PpmIcon(ProductIcon.Loader2, 14.dp, tokens.accent.color, Modifier.rotate(spinAngle))
            } else if (active) {
                PpmIcon(ProductIcon.CheckCircle2, 14.dp, tokens.accent.color, Modifier.testTag("account-active:${account.id}"))
            } else {
                Spacer(Modifier.size(14.dp))
            }
        }

        account.refusal?.let { refusal ->
            BasicText(
                refusal,
                Modifier.padding(top = 0.dp),
                style = ppmText(11f, lineHeight = 15f).copy(
                    color = tokens.error.color,
                    textDecoration = if (account.signedOut) TextDecoration.Underline else TextDecoration.None,
                ),
                maxLines = 1,
                softWrap = false,
            )
        }

        if (!account.signedOut) {
            val now = rememberResetClockMillis()
            val zone = LocalResetZone.current
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                AccountCardMeter(
                    accountId = account.id,
                    label = "5h",
                    pct = account.fiveHourPct,
                    resetsAt = account.fiveHourResetsAt,
                    dim = blocked,
                    now = now,
                    zone = zone,
                    modifier = Modifier.weight(1f),
                )
                AccountCardMeter(
                    accountId = account.id,
                    label = "Week",
                    pct = account.weekPct,
                    resetsAt = account.weekResetsAt,
                    dim = blocked,
                    now = now,
                    zone = zone,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AccountCardMeter(
    accountId: String,
    label: String,
    pct: Int?,
    resetsAt: String?,
    dim: Boolean,
    now: Long,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val reset = formatResetLine(resetsAt, now, zone)
    val isFull = (pct ?: 0) >= 100
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText(
                label,
                Modifier.width(30.dp),
                style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                maxLines = 1,
                softWrap = false,
            )
            UsageTrack(
                pct = pct,
                modifier = Modifier
                    .weight(1f)
                    .testTag("account-bar:$accountId:$label"),
            )
            BasicText(
                if (pct != null) "$pct%" else "--%",
                Modifier.width(32.dp),
                style = ppmText(11f, lineHeight = 15f).copy(
                    color = if (dim) tokens.text3.color else tokens.text2.color,
                    textAlign = TextAlign.End,
                    fontFeatureSettings = "tnum",
                ),
                maxLines = 1,
                softWrap = false,
            )
        }
        if (reset != null) {
            BasicText(
                reset,
                Modifier
                    .fillMaxWidth()
                    .testTag("account-reset:$accountId:$label")
                    .semantics {
                        this[AccountResetToneKey] = if (isFull) "error" else "muted"
                    },
                style = ppmText(
                    10.5f,
                    if (isFull) FontWeight.Medium else FontWeight.Normal,
                    lineHeight = 15f,
                ).copy(
                    color = if (isFull) tokens.error.color else tokens.text3.color,
                    fontFeatureSettings = "tnum",
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            )
        }
    }
}
