package com.hienlh.ppm.app.ui

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.ppmText
import java.util.Locale

data class MoreMenuTeamInfo(
    val unread: Int,
    val onOpen: () -> Unit,
)

data class MoreMenuSessionActions(
    val hasUnread: Boolean,
    val onToggleUnread: () -> Unit,
    val onOpenDebug: () -> Unit,
    val onShowLogs: () -> Unit,
)

/**
 * The chat's ··· menu, main view (`ChatMoreMenu` in `chat-more-menu.tsx`): account meters,
 * Switch account, AI settings, Team activity, Reload messages, and folded session actions.
 */
@Composable
fun ChatMoreMenu(
    who: String? = null,
    fiveHourPct: Int? = null,
    weekPct: Int? = null,
    fiveHourResetsAt: String? = null,
    weekResetsAt: String? = null,
    sessionCostUsd: Double? = null,
    accountCount: Int? = null,
    onSwitchAccount: (() -> Unit)? = null,
    providerId: String? = null,
    onOpenAISettings: () -> Unit,
    team: MoreMenuTeamInfo? = null,
    isConnected: Boolean = true,
    onReload: (() -> Unit)? = null,
    session: MoreMenuSessionActions? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val reducedMotion = rememberPrefersReducedMotion()
    var moreOpen by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .testTag("chat-more-menu"),
    ) {
        if (who != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (mobile) 16.dp else 8.dp,
                        end = if (mobile) 16.dp else 8.dp,
                        top = 8.dp,
                        bottom = 10.dp,
                    ),
            ) {
                TruncatedText(
                    AnnotatedString(who),
                    ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                    Modifier.padding(bottom = 2.dp),
                )
                UsageMeter("5 hours", fiveHourPct, resetsAt = fiveHourResetsAt)
                UsageMeter("Week", weekPct, resetsAt = weekResetsAt)
                if (sessionCostUsd != null) {
                    BasicText(
                        "This session $${String.format(Locale.US, "%.4f", sessionCostUsd)}",
                        Modifier.padding(top = 6.dp),
                        style = ppmText(11f, lineHeight = 15f).copy(
                            color = tokens.text3.color,
                            fontFeatureSettings = "tnum",
                        ),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            MoreMenuSeparator()
        }

        Column(Modifier.fillMaxWidth().padding(4.dp)) {
            if (onSwitchAccount != null) {
                MoreMenuRow(
                    icon = { PpmIcon(ProductIcon.Users, 16.dp, tokens.text3.color) },
                    label = "Switch account",
                    testTag = "more-switch-account",
                    onClick = onSwitchAccount,
                    hint = {
                        if (accountCount != null) {
                            BasicText(
                                "$accountCount account${if (accountCount == 1) "" else "s"}",
                                style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        PpmIcon(ProductIcon.ChevronRight, 12.dp, tokens.text3.color)
                    },
                )
            }

            MoreMenuRow(
                icon = { PpmIcon(ProductIcon.Settings2, 16.dp, tokens.text3.color) },
                label = "AI settings",
                testTag = "more-ai-settings",
                onClick = onOpenAISettings,
                hint = if (!providerId.isNullOrEmpty() && providerId != "mock") {
                    {
                        ProviderBadge(providerId, size = 14.dp)
                        BasicText(
                            providerId.replaceFirstChar { it.titlecase(Locale.US) },
                            style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                } else {
                    null
                },
            )

            if (team != null) {
                MoreMenuRow(
                    icon = { PpmIcon(ProductIcon.Users, 16.dp, tokens.text3.color) },
                    label = "Team activity",
                    testTag = "more-team-activity",
                    onClick = team.onOpen,
                    hint = if (team.unread > 0) {
                        {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(tokens.accent.color, CircleShape)
                                    .semantics { contentDescription = "${team.unread} unread" }
                                    .testTag("more-team-unread"),
                            )
                        }
                    } else {
                        null
                    },
                )
            }

            if (onReload != null) {
                MoreMenuSeparator()
                MoreMenuRow(
                    icon = { PpmIcon(ProductIcon.RefreshCw, 16.dp, tokens.text3.color) },
                    label = "Reload messages",
                    testTag = "more-reload",
                    onClick = onReload,
                    hint = {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background((if (isConnected) tokens.success else tokens.error).color, CircleShape),
                        )
                        BasicText(
                            if (isConnected) "Connected" else "Disconnected",
                            style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                            maxLines = 1,
                            softWrap = false,
                        )
                    },
                )
            }

            if (session != null) {
                val chevronTurn by animateFloatAsState(
                    targetValue = if (moreOpen) 180f else 0f,
                    animationSpec = tween(if (reducedMotion) 0 else 150),
                    label = "moreMenuChevron",
                )
                MoreMenuRow(
                    icon = { PpmIcon(ProductIcon.MoreHorizontal, 16.dp, tokens.text3.color) },
                    label = "More",
                    expanded = moreOpen,
                    testTag = "more-session-fold",
                    onClick = { moreOpen = !moreOpen },
                    hint = {
                        BasicText(
                            "Unread, debug, logs",
                            style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                            maxLines = 1,
                            softWrap = false,
                        )
                        PpmIcon(ProductIcon.ChevronDown, 12.dp, tokens.text3.color, Modifier.rotate(chevronTurn))
                    },
                )
                FoldGroup(open = moreOpen) {
                    Column(Modifier.fillMaxWidth().padding(start = 12.dp)) {
                        MoreMenuRow(
                            icon = {
                                if (session.hasUnread) {
                                    PpmIcon(ProductIcon.BellOff, 16.dp, tokens.warning.color)
                                } else {
                                    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                                        Box(Modifier.size(8.dp).background(tokens.accent.color, CircleShape))
                                    }
                                }
                            },
                            label = if (session.hasUnread) "Mark as read" else "Mark as unread",
                            testTag = "more-toggle-unread",
                            onClick = session.onToggleUnread,
                        )
                        MoreMenuRow(
                            icon = { PpmIcon(ProductIcon.Bug, 16.dp, tokens.text3.color) },
                            label = "Session debug info",
                            testTag = "more-debug",
                            onClick = session.onOpenDebug,
                        )
                        MoreMenuRow(
                            icon = { PpmIcon(ProductIcon.ScrollText, 16.dp, tokens.text3.color) },
                            label = "Show logs for this chat",
                            testTag = "more-logs",
                            onClick = session.onShowLogs,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreMenuSeparator() {
    val tokens = LocalPpmTokens.current
    Box(
        Modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(tokens.borderSoft.color),
    )
}

@Composable
private fun MoreMenuRow(
    icon: @Composable () -> Unit,
    label: String,
    testTag: String,
    onClick: () -> Unit,
    expanded: Boolean? = null,
    hint: (@Composable RowScope.() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val reducedMotion = rememberPrefersReducedMotion()
    val shape = RoundedCornerShape(6.dp)
    val minHeight = if (mobile) 44.dp else 34.dp
    val padX = if (mobile) 12.dp else 8.dp
    val fontSize = if (mobile) 14f else 13f
    val lineHeight = if (mobile) 20f else 18f

    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val lit = pressed || (!mobile && hovered)
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) 0.97f else 1f,
        animationSpec = tween(if (reducedMotion) 0 else 150),
        label = "moreMenuRowScale",
    )

    var mod = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = minHeight)
        .graphicsLayer {
            scaleX = pressScale
            scaleY = pressScale
        }
        .clip(shape)
        .background(if (lit) tokens.surfaceHover.color else Color.Transparent)
        .hoverable(interactions)
        .clickable(
            interactionSource = interactions,
            indication = null,
            role = Role.Button,
            onClick = onClick,
        )
        .padding(horizontal = padX, vertical = 7.dp)
        .testTag(testTag)
    if (expanded != null) {
        mod = mod.semantics { stateDescription = if (expanded) "expanded" else "collapsed" }
    }

    Row(
        mod,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        icon()
        TruncatedText(
            AnnotatedString(label),
            ppmText(fontSize, lineHeight = lineHeight).copy(color = tokens.text.color),
            Modifier.weight(1f),
        )
        if (hint != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                content = hint,
            )
        }
    }
}
