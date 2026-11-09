package com.hienlh.ppm.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.chat.ChangeCounts
import com.hienlh.ppm.chat.ReviewedToggle
import com.hienlh.ppm.chat.SessionChangeRow
import com.hienlh.ppm.chat.shellChangesHint
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.SessionFileChange
import com.hienlh.ppm.state.SessionChangeList

/**
 * Every file this chat session has changed, as one chip in the footer's context row
 * (`SessionChangesChip` in `session-changes-chip.tsx`).
 */
@Composable
fun SessionChangesChip(
    files: List<SessionFileChange>,
    loaded: Boolean = true,
    loading: Boolean = false,
    unloadedCount: Int = 0,
    projectPath: String?,
    providerId: String?,
    narrow: Boolean,
    /** True when the chat container is `<= 600dp`, dropping `+added −removed` before dropping words. */
    hideTotals: Boolean = narrow,
    onReview: (String?) -> Unit,
    onOpen: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    onSetReviewed: ((List<SessionFileChange>, Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    var showReviewed by remember { mutableStateOf(false) }

    if (if (loaded) files.isEmpty() && !(loading && open) else unloadedCount == 0) return

    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val reducedMotion = rememberPrefersReducedMotion()
    val hideWords = narrow || mobile
    val (pending, reviewed) = remember(files) { SessionChangeList.splitReviewed(files) }
    val allReviewed = loaded && files.isNotEmpty() && pending.isEmpty()
    val reviewedShown = showReviewed || allReviewed
    val (added, removed) = remember(pending) { SessionChangeList.totals(pending) }
    val count = when {
        !loaded -> unloadedCount
        allReviewed -> reviewed.size
        else -> pending.size
    }
    val heading = if (allReviewed) {
        "All ${SessionChangeList.fileCount(count)} reviewed"
    } else {
        "${SessionChangeList.fileCount(count)} changed"
    }
    val dense = !mobile

    fun review(path: String?) {
        open = false
        if (!loaded) onOpen?.invoke()
        onReview(path)
    }

    val markAll = {
        open = false
        onSetReviewed?.invoke(pending, true)
    }

    FooterChip(
        narrow = narrow,
        modifier = modifier,
        expanded = open,
        title = "$heading — open the list",
        contentDescription = "$heading — open the list",
        testTag = "session-changes-chip",
        onClick = {
            if (!open) onOpen?.invoke()
            open = !open
        },
    ) {
        if (allReviewed) {
            PpmIcon(ProductIcon.CheckCircle2, 14.dp, tokens.success.color)
        } else {
            PpmIcon(ProductIcon.FilePen, 14.dp, LocalFooterContentColor.current)
        }
        BasicText(
            "$count",
            style = LocalFooterTextStyle.current.copy(
                color = tokens.text.color,
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum",
            ),
            maxLines = 1,
            softWrap = false,
        )
        if (!hideWords) {
            BasicText(
                when {
                    allReviewed -> "reviewed"
                    count == 1 -> "file"
                    else -> "files"
                },
                style = LocalFooterTextStyle.current,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (loaded && !allReviewed && !hideTotals && !hideWords) {
            ChangeCounts(
                added = added,
                removed = removed,
                style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f),
            )
        }
    }

    FooterPopover(
        open = open,
        onClose = { open = false },
        label = "Changed in this chat",
        align = FooterPopoverAlign.Left,
    ) {
        FooterPopoverHeader {
            PpmIcon(ProductIcon.FilePen, 14.dp, tokens.text3.color)
            TruncatedText(
                AnnotatedString(heading),
                ppmText(if (mobile) 14f else 13f, FontWeight.SemiBold, lineHeight = if (mobile) 20f else 18f).copy(
                    color = tokens.text.color,
                ),
                Modifier.weight(1f, fill = false),
            )
            if (loaded && !allReviewed) {
                ChangeCounts(
                    added = added,
                    removed = removed,
                    style = ppmText(if (mobile) 12f else 11f, kind = FontKind.MONO, lineHeight = 16f),
                )
            }
            if (!allReviewed && reviewed.isNotEmpty()) {
                Spacer(Modifier.weight(1f))
                BasicText(
                    "${reviewed.size} reviewed",
                    style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        val scroll = rememberScrollState()
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (mobile) Modifier else Modifier.heightIn(max = 224.dp))
                .verticalScroll(scroll),
        ) {
            if (loading && files.isEmpty()) {
                BasicText(
                    "Working out what changed…",
                    Modifier.padding(
                        horizontal = if (dense) 10.dp else 16.dp,
                        vertical = if (dense) 6.dp else 12.dp,
                    ),
                    style = ppmText(if (dense) 11f else 12f, lineHeight = if (dense) 16.5f else 18f).copy(color = tokens.text3.color),
                )
            }
            for (file in pending) {
                SessionChangeRow(
                    file = file,
                    projectPath = projectPath,
                    dense = dense,
                    onClick = { review(file.path) },
                    modifier = Modifier.testTag("session-change-row:${file.path}"),
                    onToggleReviewed = if (onSetReviewed != null) {
                        { onSetReviewed(listOf(file), !file.reviewed) }
                    } else {
                        null
                    },
                )
            }
            if (reviewed.isNotEmpty() && !allReviewed) {
                ReviewedToggle(
                    count = reviewed.size,
                    shown = showReviewed,
                    dense = dense,
                    onToggle = { showReviewed = !showReviewed },
                )
            }
            if (reviewedShown) {
                for (file in reviewed) {
                    SessionChangeRow(
                        file = file,
                        projectPath = projectPath,
                        dense = dense,
                        onClick = { review(file.path) },
                        modifier = Modifier.testTag("session-change-row:${file.path}"),
                        onToggleReviewed = if (onSetReviewed != null) {
                            { onSetReviewed(listOf(file), !file.reviewed) }
                        } else {
                            null
                        },
                    )
                }
            }
            BasicText(
                shellChangesHint(providerId),
                Modifier.padding(
                    horizontal = if (mobile) 16.dp else 10.dp,
                    vertical = 6.dp,
                ),
                style = ppmText(if (mobile) 12f else 11f, lineHeight = if (mobile) 18f else 16.5f).copy(color = tokens.text3.color),
            )
        }

        FooterPopoverFooter {
            if (onRefresh != null) {
                val spinAngle = if (loading && !reducedMotion) {
                    val transition = rememberInfiniteTransition(label = "changesRefresh")
                    val angle by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
                        label = "changesRefreshAngle",
                    )
                    angle
                } else {
                    0f
                }
                FooterButton(
                    onClick = onRefresh,
                    enabled = !loading,
                    title = "Refresh changes",
                    contentDescription = "Refresh changes",
                    testTag = "session-changes-refresh",
                ) {
                    PpmIcon(
                        ProductIcon.RefreshCw,
                        if (mobile) 16.dp else 14.dp,
                        LocalFooterContentColor.current,
                        Modifier.rotate(spinAngle),
                    )
                }
            }
            if (loaded && !allReviewed && onSetReviewed != null) {
                FooterButton(
                    onClick = { markAll() },
                    testTag = "session-changes-mark-all",
                ) {
                    PpmIcon(ProductIcon.ListChecks, if (mobile) 16.dp else 14.dp, LocalFooterContentColor.current)
                    BasicText(
                        "Mark all reviewed",
                        style = LocalFooterTextStyle.current,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            if (!allReviewed) {
                FooterButton(
                    onClick = { review(null) },
                    variant = FooterButtonVariant.Primary,
                    testTag = "session-changes-review",
                ) {
                    PpmIcon(ProductIcon.FilePen, if (mobile) 16.dp else 14.dp, LocalFooterContentColor.current)
                    BasicText(
                        "Review",
                        style = LocalFooterTextStyle.current,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}
