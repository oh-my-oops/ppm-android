package com.hienlh.ppm.app.ui.shell

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.DragAndDropPermissions
import android.view.DragEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hienlh.ppm.app.BuildConfig
import com.hienlh.ppm.app.ui.PrimaryButton
import com.hienlh.ppm.app.ui.SecondaryButton
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.shell.ClassifiedExternalDrop
import com.hienlh.ppm.state.shell.ExternalDropItem
import com.hienlh.ppm.state.shell.FoldablePosture
import com.hienlh.ppm.state.shell.FoldableSplitDecision
import com.hienlh.ppm.state.shell.HingeBounds
import com.hienlh.ppm.state.shell.OnboardingEvidence
import com.hienlh.ppm.state.shell.OnboardingFamiliarity
import com.hienlh.ppm.state.shell.OnboardingGoalId
import com.hienlh.ppm.state.shell.OnboardingState
import com.hienlh.ppm.state.shell.OnboardingStepDef
import com.hienlh.ppm.state.shell.PointerRegionKind
import com.hienlh.ppm.state.shell.PreviousExitCrashInfo
import com.hienlh.ppm.state.shell.classifyExternalDrop
import com.hienlh.ppm.state.shell.computeFoldableSplit
import com.hienlh.ppm.state.shell.formatTurnCompleteAnnouncement

// ===========================================================================
// 13a.2 · Mouse / Trackpad / Stylus Pointer Hover Icon & Cross-App Drag-Drop
// ===========================================================================

@Composable
fun Modifier.ppmPointerRegion(kind: PointerRegionKind): Modifier {
    val icon = when (kind) {
        PointerRegionKind.DEFAULT -> PointerIcon.Default
        PointerRegionKind.CLICKABLE_HAND -> PointerIcon.Hand
        PointerRegionKind.TEXT_IBEAM -> PointerIcon.Text
        PointerRegionKind.CROSSHAIR -> PointerIcon.Crosshair
        else -> PointerIcon(kind.androidPointerType)
    }
    return this.pointerHoverIcon(icon)
}

fun extractAndroidDragDrop(
    dragEvent: DragEvent,
    isCrossApp: Boolean = dragEvent.localState == null,
): ClassifiedExternalDrop {
    val desc = dragEvent.clipDescription
    val mimeTypes = buildList {
        if (desc != null) {
            for (i in 0 until desc.mimeTypeCount) {
                add(desc.getMimeType(i))
            }
        }
    }
    val clip = dragEvent.clipData
    val items = buildList {
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                val item = clip.getItemAt(i)
                add(
                    ExternalDropItem(
                        uriString = item.uri?.toString(),
                        mimeType = mimeTypes.firstOrNull(),
                        text = item.text?.toString(),
                    ),
                )
            }
        }
    }
    return classifyExternalDrop(mimeTypes, items, isCrossApp = isCrossApp)
}

fun requestAndroidDragPermissions(
    activity: Activity?,
    dragEvent: DragEvent,
): DragAndDropPermissions? = runCatching {
    activity?.requestDragAndDropPermissions(dragEvent)
}.getOrNull()

// ===========================================================================
// 13a.3 · Foldable Posture Container (Book & Tabletop Split around Hinge)
// ===========================================================================

@Composable
fun FoldablePostureLayout(
    hinge: HingeBounds?,
    windowWidthPx: Int,
    windowHeightPx: Int,
    modifier: Modifier = Modifier,
    primaryPane: @Composable () -> Unit,
    secondaryPane: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val split: FoldableSplitDecision = remember(hinge, windowWidthPx, windowHeightPx) {
        computeFoldableSplit(hinge, windowWidthPx, windowHeightPx)
    }
    when (split.posture) {
        FoldablePosture.FLAT -> {
            Box(modifier.fillMaxSize().testTag("foldable-posture-flat")) {
                primaryPane()
            }
        }
        FoldablePosture.BOOK -> {
            val leftWeight = split.bookLeftFraction.coerceIn(0.25f, 0.75f)
            val rightWeight = (1f - leftWeight).coerceAtLeast(0.25f)
            val gapDp = with(density) { split.hingeGapPx.toDp() }
            Row(modifier.fillMaxSize().testTag("foldable-posture-book")) {
                Box(Modifier.weight(leftWeight).fillMaxHeight().testTag("foldable-book-left")) {
                    primaryPane()
                }
                if (split.hingeGapPx > 0) {
                    Spacer(Modifier.width(gapDp).fillMaxHeight().testTag("foldable-hinge-gap"))
                }
                Box(Modifier.weight(rightWeight).fillMaxHeight().testTag("foldable-book-right")) {
                    secondaryPane()
                }
            }
        }
        FoldablePosture.TABLETOP -> {
            val topDp = with(density) { split.tabletopTopHeightPx.toDp() }
            val gapDp = with(density) { split.hingeGapPx.toDp() }
            Column(modifier.fillMaxSize().testTag("foldable-posture-tabletop")) {
                Box(Modifier.fillMaxWidth().height(topDp).testTag("foldable-tabletop-top")) {
                    primaryPane()
                }
                if (split.hingeGapPx > 0) {
                    Spacer(Modifier.fillMaxWidth().height(gapDp).testTag("foldable-hinge-gap"))
                }
                Box(Modifier.fillMaxWidth().weight(1f).testTag("foldable-tabletop-bottom")) {
                    secondaryPane()
                }
            }
        }
    }
}

// ===========================================================================
// 13b.1 · Onboarding "Learn PPM" Guide & Quick Orientation (2 Pages)
// ===========================================================================

@Composable
fun LearnPpmDialog(
    state: OnboardingState,
    onStateChange: (OnboardingState) -> Unit,
    onRefreshEvidence: () -> Unit,
    onInsertSuggestedPrompt: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current

    if (state.quickOrientationPage != null) {
        QuickOrientationDialog(
            page = state.quickOrientationPage!!,
            onNext = { onStateChange(state.nextOrientationPage()) },
            onPrev = { onStateChange(state.prevOrientationPage()) },
            onClose = { onStateChange(state.copy(quickOrientationPage = null)) },
        )
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(min = 320.dp, max = 520.dp)
                .heightIn(max = 620.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, RoundedCornerShape(12.dp))
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                        onStateChange(state.pauseOnEscape())
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .padding(16.dp)
                .testTag("learn-ppm-dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PpmIcon(ProductIcon.Sparkles, 18.dp, tokens.accent.color)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(
                        "Learn PPM",
                        style = ppmText(15f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color),
                    )
                    BasicText(
                        "Interactive onboarding guide · Press Esc to pause",
                        style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text3.color),
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.panel2.color)
                        .clickable { onStateChange(state.openQuickOrientation()) }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                        .testTag("learn-ppm-quick-orientation-btn"),
                ) {
                    BasicText(
                        "Quick Orientation",
                        style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.accent.color),
                    )
                }
            }

            // Familiarity selector
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText(
                    "Experience Level",
                    style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.text2.color),
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (fam in OnboardingFamiliarity.entries) {
                        val active = state.familiarity == fam
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) tokens.accent.opacity(0.16).color else tokens.bgSolid.color)
                                .cssBorder(
                                    if (active) tokens.accent.color else tokens.border.color,
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable { onStateChange(state.copy(familiarity = fam)) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag("learn-ppm-familiarity-${fam.id}"),
                        ) {
                            BasicText(
                                fam.label,
                                style = ppmText(11f, FontWeight.Medium, lineHeight = 14f)
                                    .copy(color = if (active) tokens.accent.color else tokens.text2.color),
                            )
                        }
                    }
                }
            }

            // Goal selector (AI / Explore / Developer)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText(
                    "Choose a Goal",
                    style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.text2.color),
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (goal in OnboardingGoalId.entries) {
                        val active = state.selectedGoal == goal
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) tokens.accent.opacity(0.16).color else tokens.bgSolid.color)
                                .cssBorder(
                                    if (active) tokens.accent.color else tokens.border.color,
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable { onStateChange(state.copy(selectedGoal = goal)) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag("learn-ppm-goal-${goal.id}"),
                        ) {
                            BasicText(
                                goal.title,
                                style = ppmText(11f, FontWeight.Medium, lineHeight = 14f)
                                    .copy(color = if (active) tokens.accent.color else tokens.text2.color),
                            )
                        }
                    }
                }
            }

            // Steps list for selected goal
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (step in state.goalSteps) {
                    val completed = step.id in state.completedSteps
                    OnboardingStepCard(
                        step = step,
                        completed = completed,
                        onToggleComplete = {
                            val nextSet = if (completed) state.completedSteps - step.id else state.completedSteps + step.id
                            onStateChange(state.copy(completedSteps = nextSet))
                        },
                        onInsertPrompt = step.suggestedPrompt?.let { prompt ->
                            { onInsertSuggestedPrompt(prompt) }
                        },
                    )
                }
            }

            // Footer actions
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Check Progress", Modifier.weight(1f), onClick = onRefreshEvidence)
                PrimaryButton("Done", Modifier.weight(1f), onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun OnboardingStepCard(
    step: OnboardingStepDef,
    completed: Boolean,
    onToggleComplete: () -> Unit,
    onInsertPrompt: (() -> Unit)?,
) {
    val tokens = LocalPpmTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(tokens.bgSolid.color)
            .cssBorder(
                if (completed) tokens.success.opacity(0.5).color else tokens.border.color,
                RoundedCornerShape(8.dp),
            )
            .padding(10.dp)
            .testTag("learn-ppm-step-${step.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { onToggleComplete() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (completed) tokens.success.color else tokens.panel2.color),
                contentAlignment = Alignment.Center,
            ) {
                if (completed) {
                    PpmIcon(ProductIcon.Check, 11.dp, Color.White)
                }
            }
            BasicText(
                step.title,
                Modifier.weight(1f),
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
            )
            BasicText(
                if (completed) "Done" else "To do",
                style = ppmText(10f, FontWeight.Medium, lineHeight = 13f)
                    .copy(color = if (completed) tokens.success.color else tokens.text3.color),
            )
        }
        BasicText(
            step.instruction,
            style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.color),
        )
        if (onInsertPrompt != null && step.suggestedPrompt != null) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(tokens.accent.opacity(0.14).color)
                    .clickable(onClick = onInsertPrompt)
                    .padding(horizontal = 8.dp, vertical = 5.dp)
                    .testTag("learn-ppm-insert-prompt-${step.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PpmIcon(ProductIcon.MessageSquare, 12.dp, tokens.accent.color)
                BasicText(
                    "Insert suggested prompt into composer (does not auto-send)",
                    style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.accent.color),
                )
            }
        }
    }
}

@Composable
fun QuickOrientationDialog(
    page: Int,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier
                .widthIn(min = 300.dp, max = 440.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, RoundedCornerShape(12.dp))
                .padding(16.dp)
                .testTag("quick-orientation-dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    if (page == 0) "Welcome to PPM (1/2)" else "AI, Terminal & Tools (2/2)",
                    Modifier.weight(1f),
                    style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                )
                BasicText(
                    "Page ${page + 1} of 2",
                    style = ppmText(11f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text3.color),
                )
            }
            if (page == 0) {
                BasicText(
                    "PPM connects to your self-hosted server and brings your full workspace to Android: Projects, Explorer, Code Editor with LSP, Source Control, and Git Graph.",
                    style = ppmText(12f, lineHeight = 17f).copy(color = tokens.text2.color),
                )
            } else {
                BasicText(
                    "Collaborate with AI in multi-session Chat and Design mode, run shell commands in Terminal, inspect Databases with DBGate grids, and control Remote Desktop.",
                    style = ppmText(12f, lineHeight = 17f).copy(color = tokens.text2.color),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (page > 0) {
                    SecondaryButton("Previous", Modifier.weight(1f), onClick = onPrev)
                } else {
                    SecondaryButton("Skip", Modifier.weight(1f), onClick = onClose)
                }
                PrimaryButton(
                    title = if (page == 0) "Next" else "Finish",
                    modifier = Modifier.weight(1f),
                    onClick = onNext,
                )
            }
        }
    }
}

// ===========================================================================
// 13b.3 · TalkBack Turn Completion Announcer
// ===========================================================================

@Composable
fun AccessibilityTurnAnnouncer(
    isStreaming: Boolean,
    projectName: String?,
    sessionTitle: String?,
    hasPendingApproval: Boolean = false,
) {
    val view = LocalView.current
    var wasStreaming by remember { mutableStateOf(false) }
    LaunchedEffect(isStreaming, hasPendingApproval) {
        if ((wasStreaming && !isStreaming) || hasPendingApproval) {
            val announcement = formatTurnCompleteAnnouncement(
                projectName = projectName,
                sessionTitle = sessionTitle,
                hasPendingApproval = hasPendingApproval,
            )
            view.announceForAccessibility(announcement)
        }
        wasStreaming = isStreaming
    }
}

// ===========================================================================
// 14.4 · ApplicationExitInfo Previous Crash / ANR Inspector & Report Card
// ===========================================================================

fun inspectLatestCrashOrAnrExit(context: Context): PreviousExitCrashInfo? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    return runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        val actionable = exits.firstOrNull { info ->
            info.reason == ApplicationExitInfo.REASON_CRASH ||
                info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                info.reason == ApplicationExitInfo.REASON_ANR
        } ?: return null
        val reasonName = when (actionable.reason) {
            ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "REASON_ANR"
            else -> "REASON_${actionable.reason}"
        }
        val trace = runCatching {
            actionable.traceInputStream?.bufferedReader()?.use { it.readText().take(4_000) }
        }.getOrNull()
        PreviousExitCrashInfo(
            reasonCode = actionable.reason,
            reasonName = reasonName,
            timestampMs = actionable.timestamp,
            processName = actionable.processName.orEmpty(),
            description = actionable.description,
            traceSnippet = trace,
        )
    }.getOrNull()
}

@Composable
fun PreviousExitCrashCard(
    crashInfo: PreviousExitCrashInfo,
    onCopyReport: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val markdown = remember(crashInfo) {
        crashInfo.toBugReportMarkdown(
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(tokens.warning.opacity(0.12).color)
            .cssBorder(tokens.warning.opacity(0.45).color, RoundedCornerShape(10.dp))
            .padding(12.dp)
            .testTag("previous-exit-crash-card")
            .semantics { contentDescription = "Previous session crash report available" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PpmIcon(ProductIcon.AlertTriangle, 16.dp, tokens.warning.color)
            Spacer(Modifier.width(8.dp))
            BasicText(
                "Previous session exited unexpectedly (${crashInfo.reasonName})",
                Modifier.weight(1f),
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
            )
        }
        if (!crashInfo.description.isNullOrBlank()) {
            BasicText(
                crashInfo.description.orEmpty(),
                style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Dismiss", Modifier.weight(1f), onClick = onDismiss)
            PrimaryButton("Copy Crash Report", Modifier.weight(1f)) { onCopyReport(markdown) }
        }
    }
}
