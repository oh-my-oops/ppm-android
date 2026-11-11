package com.hienlh.ppm.app.ui.phone.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import com.hienlh.ppm.app.TextInputFocus
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.AccountChip
import com.hienlh.ppm.design.kit.ActionIconButton
import com.hienlh.ppm.design.kit.ActionPrimaryButton
import com.hienlh.ppm.design.kit.BottomActionCard
import com.hienlh.ppm.design.kit.Chip
import com.hienlh.ppm.design.kit.KitSpec
import com.hienlh.ppm.design.kit.NeedsYouPill
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.vector
import com.hienlh.ppm.protocol.ApprovalRequest
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.state.AttentionNotice
import com.hienlh.ppm.state.NeedsYouState
import com.hienlh.ppm.state.WaitingCardKind
import com.hienlh.ppm.state.resolveNeedsYouState

/**
 * The design's Now/Next/Later priority (`phone/1-chat/index.html`, B10 & B11). The chat no longer
 * offers it: the server queues every message sent while a turn runs, whatever it carries, and the
 * queue stack ([PhoneQueuedMessagesStack]) took its place. Kept only because other phone files
 * still import it.
 */
enum class PhoneMessagePriority(val chipLabel: String, val queueDescription: String, val snackbarTitle: String) {
    NOW(
        chipLabel = "Now",
        queueDescription = "Interrupt — sends right away",
        snackbarTitle = "Next message: Interrupt",
    ),
    NEXT(
        chipLabel = "Next",
        queueDescription = "Queue — sends when this turn ends",
        snackbarTitle = "Next message: Queue",
    ),
    LATER(
        chipLabel = "Later",
        queueDescription = "Later — sends when the chat is idle",
        snackbarTitle = "Next message: Later",
    ),
    ;

    fun next(): PhoneMessagePriority = when (this) {
        NOW -> NEXT
        NEXT -> LATER
        LATER -> NOW
    }
}

/** One message of the server's queue, as the stack above the composer shows it. */
data class PhoneQueuedMessage(
    val id: String,
    /** The typed text alone: a reply or turn references it carries are not shown. */
    val text: String,
    val imageCount: Int = 0,
)

/**
 * Reply or Edit banner state shown as a 52 dp strip above the composer (Phone 1 Section 2 B13).
 */
data class PhoneReplyEditBanner(
    val isEditing: Boolean,
    val title: String = if (isEditing) "Editing message" else "Replying to AI",
    val snippet: String,
)

/**
 * The session-changes chip (Phone 1 F4): [count] files, with their line totals once the list is
 * loaded ([additions]/[deletions] null before), or "reviewed" when every file is marked reviewed.
 */
data class PhoneChangesChipState(
    val count: Int,
    val additions: Int? = null,
    val deletions: Int? = null,
    val allReviewed: Boolean = false,
)

/**
 * Task tracker summary shown as a 52 dp bar under the app bar (Phone 1 Section 3 C6).
 */
data class PhoneTaskProgressSummary(
    val completedCount: Int,
    val totalCount: Int,
    val currentTaskTitle: String,
)

/**
 * Complete Phone 1 · Chat screen scaffold (`designs/ppm-mobile-tablet-redesign/phone/1-chat/index.html`,
 * Requirements 4.1, 4.2, 4.3).
 *
 * Integrates:
 * - Top task tracker bar (52 dp) & background command bar (52 dp)
 * - Empty new-chat welcome or active transcript content
 * - Floating `NeedsYouPill` (Requirement 4.2: shown when [pendingApproval] is non-null and
 *   `!atBottom`, scrolling to the waiting card on tap), `PhoneTurnJumpPill`, and `PhoneClearErrorsPill`
 * - Waiting-card keyboard focus collapse (`needsYouState.hidesComposerOnKeyboard`): when a waiting
 *   card's own text input (`AskUserQuestion` "Other..." or `ppm:db_execute` password field) has
 *   keyboard focus, the bottom composer and context chip row step aside so the card sits directly
 *   above the keyboard
 * - Turn-stop bar, Reply/Edit 52 dp strip, context chip row (52 dp), and `BottomActionCard` composer
 * - Sticky cross-session attention snackbar (`PhoneAttentionSnackbar`, D7)
 */
@Composable
fun PhoneChatScreen(
    composerState: TextFieldState,
    onSendMessage: (String) -> Unit,
    onStopTurn: () -> Unit,
    /** Null leaves the pill to the transcript's own waiting-card pill, which knows where the card is. */
    onScrollToWaitingCard: (() -> Unit)?,
    modifier: Modifier = Modifier,
    isNewChat: Boolean = false,
    startingConversation: Boolean = false,
    projectName: String = "",
    recentSessions: List<SessionInfo> = emptyList(),
    onOpenRecentSession: (SessionInfo) -> Unit = {},
    /** Null hides "Quick orientation". */
    onQuickOrientation: (() -> Unit)? = null,
    pendingApproval: ApprovalRequest? = null,
    atBottom: Boolean = true,
    cardKeyboardFocused: Boolean = false,
    streaming: Boolean = false,
    elapsedTurnLabel: String? = null,
    turnTokensLabel: String? = null,
    providerId: String = "claude",
    modelLabel: String = "Sonnet 4.6",
    effortLabel: String = "Medium",
    /** Why the turns do not run at the effort picked; the chip says so, and its sheet says why. */
    effortNotice: PhoneEffortNotice? = null,
    permissionModeId: String = "default",
    accountName: String = "Primary",
    accountUsageText: String? = null,
    specModeEnabled: Boolean = false,
    /** Null hides the Spec chip. */
    onToggleSpecMode: (() -> Unit)? = null,
    changes: PhoneChangesChipState? = null,
    taskProgress: PhoneTaskProgressSummary? = null,
    /** The session's background commands not stopped yet, one bar each (`BackgroundCommandBar`). */
    backgroundCommands: List<PhoneBackgroundCommand> = emptyList(),
    onViewBackgroundOutput: (String) -> Unit = {},
    onStopBackgroundCommand: (String) -> Unit = {},
    viewingTurnNumber: Int? = null,
    onBackToLatestTurn: () -> Unit = {},
    errorCount: Int = 0,
    onClearAllErrors: () -> Unit = {},
    replyEditBanner: PhoneReplyEditBanner? = null,
    onCancelReplyEdit: () -> Unit = {},
    attachments: List<PhoneDraftAttachment> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {},
    /** Null hides Attach (no chat yet to attach to). */
    onOpenAttachSheet: (() -> Unit)? = null,
    voiceRecording: Boolean = false,
    voiceElapsedLabel: String = "0:00",
    /** Null hides the mic (no recogniser on this device). */
    onToggleVoiceRecording: (() -> Unit)? = null,
    onOpenModelEffortSheet: () -> Unit = {},
    /** Null hides the account chip (a provider with no accounts to switch). */
    onOpenAccountSwitcher: (() -> Unit)? = null,
    onOpenSessionChangesSheet: () -> Unit = {},
    onOpenSessionInfoSheet: () -> Unit = {},
    crossSessionNotice: AttentionNotice? = null,
    onGoToCrossSession: () -> Unit = {},
    onDismissCrossSessionNotice: () -> Unit = {},
    /** Messages sent while the AI worked, not read by it yet: the stack above the composer. */
    queuedMessages: List<PhoneQueuedMessage> = emptyList(),
    onSendQueuedNow: () -> Unit = {},
    onEditQueued: (String) -> Unit = {},
    onRemoveQueued: (String) -> Unit = {},
    /** First in the context row: running agents and linked sessions (`ActivityChip`); nothing when there are none. */
    activityChip: @Composable () -> Unit = {},
    /** Floats over the bottom of the transcript, just above the composer: the `/` and `@` pickers. */
    pickerContent: @Composable () -> Unit = {},
    /** Over the composer, under the transcript: the stopped-turn bar, the MCP sign-in bar, the provider gate line. */
    bannersContent: @Composable () -> Unit = {},
    /** The chat's connection chip, first in the context row while the socket is down. */
    connectionChip: @Composable () -> Unit = {},
    sendEnabled: Boolean = true,
    /** What the box does with what the user types: saves the draft and opens the `/` and `@` pickers. */
    composerInputTransformation: InputTransformation? = null,
    transcriptContent: @Composable () -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val needsYouState: NeedsYouState = remember(pendingApproval, atBottom, cardKeyboardFocused) {
        resolveNeedsYouState(
            pendingApproval = pendingApproval,
            atBottom = atBottom,
            cardKeyboardFocused = cardKeyboardFocused,
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar 1: Task tracker strip (52 dp, Phone 1 C6)
            if (taskProgress != null && taskProgress.totalCount > 0) {
                PhoneTaskTrackerBar(progress = taskProgress)
            }

            // Top bar 2: Running background command bar (52 dp, Phone 1 F1)
            backgroundCommands.forEach { command ->
                PhoneBackgroundCommandBar(
                    command = command.command,
                    elapsedLabel = if (command.stopping) "stopping…" else "running",
                    onViewOutput = { onViewBackgroundOutput(command.id) },
                    onStop = if (command.stopping) null else ({ onStopBackgroundCommand(command.id) }),
                )
            }

            // Main transcript or new-chat welcome
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (isNewChat) {
                    PhoneNewChatWelcome(
                        projectName = projectName,
                        sessions = recentSessions,
                        onOpenSession = onOpenRecentSession,
                        onQuickOrientation = onQuickOrientation,
                        startingConversation = startingConversation,
                    )
                } else {
                    transcriptContent()
                }

                // Floating pills above the bottom area (NeedsYouPill, TurnJumpPill, ClearErrorsPill)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (errorCount >= 2) {
                        PhoneClearErrorsPill(
                            errorCount = errorCount,
                            onClearAll = onClearAllErrors,
                        )
                    }
                    if (viewingTurnNumber != null) {
                        PhoneTurnJumpPill(
                            turnNumber = viewingTurnNumber,
                            onBackToLatest = onBackToLatestTurn,
                        )
                    }
                    if (needsYouState.visible && onScrollToWaitingCard != null) {
                        NeedsYouPill(
                            label = needsYouState.label,
                            onClick = onScrollToWaitingCard,
                        )
                    }
                }

                // The `/` and `@` pickers float over the conversation's foot, above the composer.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    pickerContent()
                }
            }

            // Cross-session sticky attention snackbar (Phone 1 D7, 64 dp)
            if (crossSessionNotice != null) {
                PhoneAttentionSnackbar(
                    notice = crossSessionNotice,
                    onGoToSession = onGoToCrossSession,
                    onDismiss = onDismissCrossSessionNotice,
                )
            }

            // When a waiting card's text input (AskUserQuestion "Other..." or db_execute password)
            // has keyboard focus, the composer and context chip row step aside so the card sits
            // directly above the keyboard (Phone 1 D2 & D3 `cardKeyboard`).
            if (!needsYouState.hidesComposerOnKeyboard) {
                bannersContent()
                if (streaming) {
                    PhoneTurnStopBar(
                        elapsedLabel = elapsedTurnLabel,
                        tokensLabel = turnTokensLabel,
                        onStop = onStopTurn,
                    )
                }

                if (replyEditBanner != null) {
                    PhoneReplyEditStrip(
                        banner = replyEditBanner,
                        onCancel = onCancelReplyEdit,
                    )
                }

                // 52 dp horizontal context chip row above the composer
                PhoneContextChipRow(
                    accountName = accountName,
                    accountUsageText = accountUsageText,
                    specModeEnabled = specModeEnabled,
                    changes = changes,
                    connectionChip = connectionChip,
                    activityChip = activityChip,
                    onOpenAccountSwitcher = onOpenAccountSwitcher,
                    onToggleSpecMode = onToggleSpecMode,
                    onOpenSessionChangesSheet = onOpenSessionChangesSheet,
                    onOpenSessionInfoSheet = onOpenSessionInfoSheet,
                )

                // The server's queue, right above the box it was typed in (`QueuedMessagesStack`).
                PhoneQueuedMessagesStack(
                    messages = queuedMessages,
                    onSendNow = onSendQueuedNow,
                    onEdit = onEditQueued,
                    onRemove = onRemoveQueued,
                )

                // Composer inside BottomActionCard (Requirement 4.1, Phone 1 Section 5)
                val draftText = composerState.text.toString()
                val canSend = sendEnabled && (draftText.isNotBlank() || attachments.isNotEmpty())
                val bypass = permissionModeId == "bypassPermissions"
                val placeholder = if (specModeEnabled) "Describe the feature or bug for Spec mode…" else "Message ${modelLabel}…"
                val composerFocus = remember { FocusRequester() }
                var composerFocused by remember { mutableStateOf(false) }
                var openingComposer by remember { mutableStateOf(false) }
                // Claude's app: one row (+, the placeholder, the mic) until the box is used; a tap opens it
                // with the keyboard, and an empty box folds back when it loses focus or the keyboard closes.
                val collapsed = !composerFocused && !openingComposer && draftText.isEmpty() &&
                    attachments.isEmpty() && !voiceRecording
                LaunchedEffect(openingComposer) {
                    if (openingComposer) {
                        composerFocus.requestFocus()
                        openingComposer = false
                    }
                }
                FoldComposerWhenKeyboardCloses(focused = composerFocused, empty = draftText.isEmpty())
                if (collapsed) {
                    BottomActionCard(applyWindowInsets = true) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (onOpenAttachSheet != null) PhoneAttachButton(bypass, onOpenAttachSheet)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(KitSpec.MinTouchTarget)
                                    .ppmClickable(onClickLabel = "Write a message") {
                                        TextInputFocus.asked()
                                        openingComposer = true
                                    }
                                    .testTag("phone-composer-collapsed"),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                BasicText(
                                    placeholder,
                                    style = ppmText(15f, lineHeight = 21f).copy(color = LocalPpmTokens.current.text3.color),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (onToggleVoiceRecording != null) {
                                ActionIconButton(
                                    icon = ProductIcon.Mic.vector,
                                    label = "Voice input",
                                    onClick = onToggleVoiceRecording,
                                )
                            }
                        }
                    }
                } else BottomActionCard(
                    applyWindowInsets = true,
                    topContent = if (attachments.isNotEmpty() || (voiceRecording && onToggleVoiceRecording != null)) {
                        {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                // What the mic hears is written into the box below as it comes.
                                if (voiceRecording && onToggleVoiceRecording != null) {
                                    PhoneVoiceRecordingBar(
                                        elapsedLabel = voiceElapsedLabel,
                                        onStop = onToggleVoiceRecording,
                                    )
                                }
                                PhoneAttachmentTray(
                                    attachments = attachments,
                                    onRemove = onRemoveAttachment,
                                )
                            }
                        }
                    } else {
                        null
                    },
                    chipsContent = {
                        if (onOpenAttachSheet != null) PhoneAttachButton(bypass, onOpenAttachSheet)
                        if (onToggleVoiceRecording != null) {
                            ActionIconButton(
                                icon = ProductIcon.Mic.vector,
                                label = if (voiceRecording) "Stop voice input" else "Voice input",
                                active = voiceRecording,
                                onClick = onToggleVoiceRecording,
                            )
                        }
                        // Model & effort inside the composer, as on the web.
                        Chip(
                            label = modelLabel,
                            providerId = providerId,
                            secondaryLabel = effortLabel.ifEmpty { null },
                            showCaret = true,
                            alert = effortNotice != null,
                            onClick = onOpenModelEffortSheet,
                            modifier = Modifier.semantics {
                                contentDescription = buildString {
                                    append("Model ").append(modelLabel).append(", effort ").append(effortLabel.ifEmpty { "none" })
                                    effortNotice?.let { append(" (").append(it.title).append(")") }
                                }
                            },
                        )
                    },
                    primaryAction = {
                        if (voiceRecording && onToggleVoiceRecording != null) {
                            ActionPrimaryButton(
                                icon = ProductIcon.Square,
                                label = "Stop",
                                destructive = true,
                                onClick = onToggleVoiceRecording,
                            )
                        } else {
                            PhoneSendButton(
                                enabled = canSend,
                                onClick = { onSendMessage(draftText) },
                            )
                        }
                    },
                ) {
                    PpmTextField(
                        state = composerState,
                        placeholder = placeholder,
                        focusRequester = composerFocus,
                        onFocusChanged = { composerFocused = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 28.dp, max = 140.dp)
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        style = ppmText(15f, lineHeight = 21f),
                        // A textarea, as the old composer and the web's: long text wraps and the box grows to 140 dp.
                        lineLimits = TextFieldLineLimits.MultiLine(),
                        inputTransformation = composerInputTransformation,
                    )
                }
            }
        }
    }
}

/**
 * 52 dp task progress bar pinned under the app bar (Phone 1 Section 3 C6).
 */
@Composable
fun PhoneTaskTrackerBar(
    progress: PhoneTaskProgressSummary,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PpmIcon(ProductIcon.ListChecks, 16.dp, tokens.accent.color)
        BasicText(
            text = "Tasks ${progress.completedCount}/${progress.totalCount} done",
            style = ppmText(12.5f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
        )
        BasicText(
            text = progress.currentTaskTitle,
            modifier = Modifier.weight(1f),
            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Turn-stop bar shown above the context chip row while a turn is streaming (Phone 1 Section 6 F2).
 */
@Composable
fun PhoneTurnStopBar(
    elapsedLabel: String?,
    tokensLabel: String?,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(tokens.panel.color)
            .cssBorder(tokens.borderSoft.color, sides = CssSides.Top)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PpmIcon(ProductIcon.Loader2, 15.dp, tokens.accent.color, spinning = true)
        BasicText(
            text = buildString {
                append("Generating")
                if (!elapsedLabel.isNullOrEmpty()) {
                    append(" · ")
                    append(elapsedLabel)
                }
                if (!tokensLabel.isNullOrEmpty()) {
                    append(" · ")
                    append(tokensLabel)
                }
            },
            modifier = Modifier.weight(1f),
            style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text2.color),
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tokens.error.opacity(0.14).color)
                .cssBorder(tokens.error.opacity(0.40).color, RoundedCornerShape(10.dp))
                .ppmClickable(
                    shape = RoundedCornerShape(10.dp),
                    onClickLabel = "Stop generating",
                    onClick = onStop,
                )
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PpmIcon(ProductIcon.Square, 12.dp, tokens.error.color)
                BasicText(
                    text = "Stop",
                    style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.error.color),
                )
            }
        }
    }
}

/**
 * 52 dp Reply / Edit strip above the composer (`Replying to AI` / `Editing message`, Phone 1 B13).
 */
@Composable
fun PhoneReplyEditStrip(
    banner: PhoneReplyEditBanner,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(tokens.panel.color)
            .cssBorder(tokens.borderSoft.color, sides = CssSides.Top)
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PpmIcon(
            icon = if (banner.isEditing) ProductIcon.Pencil else ProductIcon.ArrowLeft,
            size = 16.dp,
            tint = tokens.accent.color,
        )
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = banner.title,
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 15f).copy(color = tokens.accent.color),
                maxLines = 1,
            )
            BasicText(
                text = banner.snippet,
                style = ppmText(12f, lineHeight = 15f).copy(color = tokens.text2.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(KitSpec.MinTouchTarget)
                .clip(CircleShape)
                .ppmClickable(
                    shape = CircleShape,
                    onClickLabel = "Cancel ${banner.title}",
                    onClick = onCancel,
                )
                .semantics { contentDescription = "Cancel ${banner.title}" },
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.X, 16.dp, tokens.text2.color)
        }
    }
}

/**
 * 52 dp horizontally scrollable context chip row above the composer (Phone 1 Sections 1, 5, 6 & 9).
 */
@Composable
fun PhoneContextChipRow(
    accountName: String,
    accountUsageText: String?,
    specModeEnabled: Boolean,
    changes: PhoneChangesChipState?,
    onOpenAccountSwitcher: (() -> Unit)?,
    onToggleSpecMode: (() -> Unit)?,
    onOpenSessionChangesSheet: () -> Unit,
    onOpenSessionInfoSheet: () -> Unit,
    modifier: Modifier = Modifier,
    connectionChip: @Composable () -> Unit = {},
    activityChip: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        connectionChip()
        activityChip()

        // Account switcher chip
        if (onOpenAccountSwitcher != null) {
            AccountChip(
                name = accountName,
                usageText = accountUsageText,
                onClick = onOpenAccountSwitcher,
            )
        }

        // Spec mode toggle chip (Section 9 I1: `✦ Spec`)
        if (onToggleSpecMode != null) {
            Chip(
                label = "✦ Spec",
                selected = specModeEnabled,
                onClick = onToggleSpecMode,
            )
        }

        // Session changes pill when files changed (Section 6 F4)
        if (changes != null && changes.count > 0) {
            val noun = if (changes.allReviewed) "reviewed" else "file${if (changes.count == 1) "" else "s"}"
            Chip(
                label = "${changes.count} $noun",
                secondaryLabel = if (changes.additions != null && changes.deletions != null && !changes.allReviewed) {
                    "+${changes.additions} −${changes.deletions}"
                } else {
                    null
                },
                icon = if (changes.allReviewed) ProductIcon.CheckCircle2.vector else ProductIcon.FileCode.vector,
                onClick = onOpenSessionChangesSheet,
            )
        }

        // Session info chip (Section 8 H2)
        Chip(
            label = "Info",
            icon = ProductIcon.Info,
            onClick = onOpenSessionInfoSheet,
        )
    }
}

/**
 * Voice recording status bar inside the composer card (Phone 1 Section 5 E7).
 */
@Composable
fun PhoneVoiceRecordingBar(
    elapsedLabel: String,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(tokens.error.color),
        )
        BasicText(
            text = elapsedLabel,
            style = ppmText(13f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 17f).copy(color = tokens.text.color),
        )
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val heights = listOf(8.dp, 16.dp, 12.dp, 20.dp, 10.dp, 18.dp, 14.dp, 9.dp)
            heights.forEach { h ->
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(h)
                        .clip(RoundedCornerShape(2.dp))
                        .background(tokens.accent.color),
                )
            }
        }
        BasicText(
            text = "Listening…",
            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
        )
    }
}

/**
 * Sticky 64 dp cross-session attention snackbar when another session needs permission or asks a
 * question (`"{tool} needs permission"` / `"AI has a question"` / `"AI wants to change {conn}"`,
 * Phone 1 Section 4 D7).
 */
@Composable
fun PhoneAttentionSnackbar(
    notice: AttentionNotice,
    onGoToSession: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(14.dp)
    val accent = if (notice.warning) tokens.warning else tokens.accent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .heightIn(min = 64.dp)
            .ppmShadow(tokens.shadowFloat, shape)
            .clip(shape)
            .background(tokens.panel2.color)
            .cssBorder(accent.opacity(0.50).color, shape, width = 1.5.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PpmIcon(
            icon = if (notice.warning) ProductIcon.ShieldAlert else ProductIcon.CircleHelp,
            size = 18.dp,
            tint = accent.color,
        )
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = notice.title,
                style = ppmText(13f, FontWeight.SemiBold, lineHeight = 17f).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                text = notice.description,
                style = ppmText(11.5f, lineHeight = 15f).copy(color = tokens.text2.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.color)
                .ppmClickable(
                    shape = RoundedCornerShape(10.dp),
                    onClickLabel = "Go to session",
                    onClick = onGoToSession,
                )
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = "Go to session",
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f)
                    .copy(color = if (notice.warning) Color.Black else tokens.accentFg.color),
            )
        }
    }
}

/**
 * Session collaboration approval card on phone (Phone 1 Section 4 D4 & D5, Note 83):
 * - For `CREATE_SESSION` / `ELEVATE_SESSION`: 3 full-width 48 dp buttons stacked bottom-up
 *   (`Deny` on top, `Allow for this session` outlined in the middle, `Allow once` solid at the bottom).
 * - For `RATE_LIMIT`: 2 side-by-side 48 dp buttons (`Deny` | `Allow 5 more`).
 */
@Composable
fun PhoneSessionCollabApprovalCard(
    kind: WaitingCardKind,
    title: String,
    details: List<Pair<String, String>>,
    onDeny: () -> Unit,
    onAllowOnce: () -> Unit,
    onAllowForSession: () -> Unit = {},
    onAllowMoreTurns: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(tokens.warning.color, shape, width = 2.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(ProductIcon.Users, 18.dp, tokens.warning.color)
            BasicText(
                text = title,
                style = ppmText(14f, FontWeight.SemiBold, lineHeight = 19f).copy(color = tokens.text.color),
            )
        }

        if (details.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(tokens.panel2.color)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                details.forEach { (key, value) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BasicText(
                            text = key,
                            modifier = Modifier.width(76.dp),
                            style = ppmText(11.5f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text3.color),
                        )
                        BasicText(
                            text = value,
                            modifier = Modifier.weight(1f),
                            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color),
                        )
                    }
                }
            }
        }

        if (kind == WaitingCardKind.RATE_LIMIT) {
            // 2 side-by-side 48 dp buttons: Deny | Allow 5 more
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CollabActionButton(
                    label = "Deny",
                    destructive = true,
                    outlined = true,
                    onClick = onDeny,
                    modifier = Modifier.weight(1f),
                )
                CollabActionButton(
                    label = "Allow 5 more",
                    primary = true,
                    onClick = onAllowMoreTurns,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            // 3 stacked 48 dp buttons bottom-up (Note 83):
            // Top: Deny (destructive outline)
            // Middle: Allow for this session (accent outline)
            // Bottom: Allow once (solid accent primary)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CollabActionButton(
                    label = "Deny",
                    destructive = true,
                    outlined = true,
                    onClick = onDeny,
                    modifier = Modifier.fillMaxWidth(),
                )
                CollabActionButton(
                    label = "Allow for this session",
                    outlined = true,
                    onClick = onAllowForSession,
                    modifier = Modifier.fillMaxWidth(),
                )
                CollabActionButton(
                    label = "Allow once",
                    primary = true,
                    onClick = onAllowOnce,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CollabActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    destructive: Boolean = false,
    outlined: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    val bg = when {
        primary -> tokens.accent.color
        destructive && outlined -> tokens.error.opacity(0.10).color
        else -> tokens.panel2.color
    }
    val border = when {
        primary -> Color.Transparent
        destructive -> tokens.error.opacity(0.45).color
        outlined -> tokens.accentWashBorder.color
        else -> tokens.border.color
    }
    val fg = when {
        primary -> tokens.accentFg.color
        destructive -> tokens.error.color
        else -> tokens.accent.color
    }
    Box(
        modifier = modifier
            .height(KitSpec.MinTouchTarget)
            .clip(shape)
            .background(bg)
            .then(if (!primary) Modifier.cssBorder(border, shape) else Modifier)
            .ppmClickable(
                shape = shape,
                onClickLabel = label,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = ppmText(13.5f, FontWeight.SemiBold, lineHeight = 18f).copy(color = fg),
        )
    }
}

/**
 * Messages sent while the AI was working, shown above the composer until the AI reads them
 * (`queued-messages-stack.tsx`). The list is the server's, so every device shows the same stack.
 * Send now has the AI read the queue at once; Edit puts a message back into the composer; Remove
 * drops it. Nothing at all while the queue is empty.
 */
@Composable
fun PhoneQueuedMessagesStack(
    messages: List<PhoneQueuedMessage>,
    onSendNow: () -> Unit,
    onEdit: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty()) return
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(shape)
            .background(tokens.panel2.color)
            .cssBorder(tokens.borderSoft.color, shape)
            .testTag("phone-queued-messages"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(ProductIcon.Clock, 14.dp, tokens.text3.color)
            BasicText(
                text = "${messages.size} queued · sent when the AI is ready",
                modifier = Modifier.weight(1f),
                style = ppmText(11.5f, lineHeight = 15f).copy(color = tokens.text3.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier
                    .heightIn(min = KitSpec.MinTouchTarget)
                    .clip(RoundedCornerShape(8.dp))
                    .ppmClickable(
                        shape = RoundedCornerShape(8.dp),
                        onClickLabel = "Have the AI read the queue now",
                        onClick = onSendNow,
                    )
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PpmIcon(ProductIcon.Send, 14.dp, tokens.text2.color)
                BasicText(
                    text = "Send now",
                    style = ppmText(11.5f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.text2.color),
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .cssBorder(tokens.borderSoft.color, sides = CssSides.Top)
                .verticalScroll(rememberScrollState()),
        ) {
            messages.forEachIndexed { index, message ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (index > 0) Modifier.cssBorder(tokens.borderSoft.color, sides = CssSides.Top) else Modifier)
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    BasicText(
                        text = message.text.ifEmpty { "(no text)" },
                        modifier = Modifier.weight(1f),
                        style = ppmText(13f, lineHeight = 18f)
                            .copy(color = if (message.text.isEmpty()) tokens.text3.color else tokens.text2.color),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (message.imageCount > 0) {
                        Row(
                            modifier = Modifier.semantics {
                                contentDescription = "${message.imageCount} image${if (message.imageCount == 1) "" else "s"}"
                            },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            PpmIcon(ProductIcon.Image, 14.dp, tokens.text3.color)
                            BasicText(
                                text = "${message.imageCount}",
                                style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                            )
                        }
                    }
                    QueuedRowButton(ProductIcon.Pencil, "Edit queued message") { onEdit(message.id) }
                    QueuedRowButton(ProductIcon.X, "Remove queued message") { onRemove(message.id) }
                }
            }
        }
    }
}

@Composable
private fun QueuedRowButton(icon: ProductIcon, label: String, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Box(
        modifier = Modifier
            .size(KitSpec.MinTouchTarget)
            .clip(RoundedCornerShape(8.dp))
            .ppmClickable(shape = RoundedCornerShape(8.dp), onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, 14.dp, tokens.text3.color)
    }
}

/** Send as Claude's app draws it: a 36 dp circle, the size of + and the mic, in the same 48 dp touch target. */
@Composable
private fun PhoneSendButton(enabled: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Box(
        Modifier
            .size(KitSpec.MinTouchTarget)
            .clip(CircleShape)
            .ppmClickable(enabled = enabled, shape = CircleShape, onClickLabel = "Send message", onClick = onClick)
            .semantics { contentDescription = "Send message" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(KitSpec.ChipVisualHeight)
                .background(if (enabled) tokens.accent.color else tokens.panel2.color, CircleShape)
                .then(if (enabled) Modifier else Modifier.cssBorder(tokens.border.color, CircleShape, width = 1.dp))
                .testTag("phone-send-circle"),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.ArrowUp, 16.dp, if (enabled) tokens.accentFg.color else tokens.text3.color)
        }
    }
}

/** The composer's +: attachments and the permission mode, as Claude's app; a red dot while Bypass is on. */
@Composable
private fun PhoneAttachButton(bypass: Boolean, onClick: () -> Unit) {
    Box {
        ActionIconButton(
            icon = ProductIcon.Plus.vector,
            label = "Attach",
            onClick = onClick,
            modifier = if (bypass) Modifier.semantics { stateDescription = "Bypass permissions on" } else Modifier,
        )
        if (bypass) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
                    .size(8.dp)
                    .background(LocalPpmTokens.current.error.color, CircleShape),
            )
        }
    }
}

/** Closing the keyboard over an empty composer folds it back to one row, as Claude's app does. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoldComposerWhenKeyboardCloses(focused: Boolean, empty: Boolean) {
    val imeVisible = WindowInsets.isImeVisible
    val focusManager = LocalFocusManager.current
    val isFocused by rememberUpdatedState(focused)
    val isEmpty by rememberUpdatedState(empty)
    var keyboardWasUp by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            keyboardWasUp = true
        } else if (keyboardWasUp) {
            keyboardWasUp = false
            if (isFocused && isEmpty) focusManager.clearFocus()
        }
    }
}
