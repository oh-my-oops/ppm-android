package com.hienlh.ppm.chat

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ProviderLogo
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.TurnLinkTarget
import com.hienlh.ppm.protocol.TurnLinks
import com.hienlh.ppm.protocol.generated.TurnReference
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.ComposerTurnChip
import com.hienlh.ppm.state.HistoryWindowController
import com.hienlh.ppm.state.TurnIdResolver
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What a chat's transcript needs for turn links: to build and copy one, and to open its history
 * window on a turn the live list has not loaded. Made by the app per chat tab.
 */
class ChatTurnTools(
    val projectName: String,
    /** The server's origin, which every turn link starts with. */
    val origin: String,
    /** The session's turn index asked by a message's record uuid (`GET …/turns?resolve=`); null when it does not know. */
    val resolveTurnId: suspend (sessionId: String, providerId: String, messageUuid: String) -> String?,
    /** The chat's history window (`useHistoryWindow`). */
    val history: HistoryWindowController,
    /** Says something went wrong, as the web's `toast.error`. */
    val toast: (String) -> Unit,
)

/** The chat Copy turn link works in: its tools, and the session as the transcript last had it. */
internal class TurnLinkContext(
    val tools: ChatTurnTools,
    val sessionId: String,
    val providerId: String,
    val bindings: Map<String, String>,
)

internal val LocalTurnLinkContext = compositionLocalOf<TurnLinkContext?> { null }

/** The message whose action bar is being drawn, when it is a prompt a turn link can name. */
internal val LocalTurnLinkItem = staticCompositionLocalOf<ChatItem?> { null }

private sealed interface CopyTurnState {
    data object Idle : CopyTurnState
    data object Waiting : CopyTurnState
    data class Ready(val turnId: String) : CopyTurnState
    data object Copied : CopyTurnState
}

/**
 * Copy turn link, in a user message's action bar (`CopyTurnLinkButton`): the message is where a
 * turn starts. A message whose turn is not known yet waits — a second tap stops waiting — and once
 * the id comes the button says the link is ready; the next tap copies it, so the clipboard is
 * written by a tap and not minutes later over whatever was copied meanwhile.
 */
@Composable
internal fun CopyTurnLinkButton() {
    val context = LocalTurnLinkContext.current ?: return
    val item = LocalTurnLinkItem.current ?: return
    if (context.providerId != "claude" && context.providerId != "codex") return
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val currentItem by rememberUpdatedState(item)
    val currentContext by rememberUpdatedState(context)
    var state by remember { mutableStateOf<CopyTurnState>(CopyTurnState.Idle) }
    var waiting by remember { mutableStateOf<Job?>(null) }

    fun copy(turnId: String) {
        val ctx = currentContext
        val link = TurnLinks.build(ctx.tools.origin, TurnLinkTarget(ctx.tools.projectName, ctx.providerId, ctx.sessionId, turnId))
        scope.launch {
            val ok = runCatching { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("turn link", link))) }.isSuccess
            if (!ok) {
                ctx.tools.toast("Could not copy the turn link")
                if (state !is CopyTurnState.Ready) state = CopyTurnState.Idle
                return@launch
            }
            state = CopyTurnState.Copied
            delay(1_500)
            if (state == CopyTurnState.Copied) state = CopyTurnState.Idle
        }
    }

    val onClick = click@{
        if (state == CopyTurnState.Waiting) {
            waiting?.cancel()
            waiting = null
            state = CopyTurnState.Idle
            return@click
        }
        val known = (state as? CopyTurnState.Ready)?.turnId ?: TurnIdResolver.known(currentItem, currentContext.bindings)
        if (known != null) {
            copy(known)
            return@click
        }
        state = CopyTurnState.Waiting
        waiting = scope.launch {
            val ctx = currentContext
            val turnId = TurnIdResolver.resolve(
                current = { currentItem },
                bindings = { currentContext.bindings },
                byUuid = { uuid -> ctx.tools.resolveTurnId(ctx.sessionId, ctx.providerId, uuid) },
            )
            waiting = null
            state = if (turnId != null) CopyTurnState.Ready(turnId) else CopyTurnState.Idle
            if (turnId == null) ctx.tools.toast("Couldn't get this turn's id yet")
        }
    }
    val tokens = LocalPpmTokens.current
    when (val s = state) {
        CopyTurnState.Idle -> ActionButton(ProductIcon.Link, "Copy turn link", "Copy a link to this turn, to paste into another chat", touchTarget = true, iconOnlyOnMobile = true, onClick = onClick)
        CopyTurnState.Waiting -> ActionButton(ProductIcon.Loader2, "Getting link…", "Waiting for this turn to start. Tap to stop waiting", touchTarget = true, spinning = true, iconOnlyOnMobile = true, onClick = onClick)
        is CopyTurnState.Ready -> ActionButton(ProductIcon.Link, "Link ready", "The turn link is ready. Tap to copy it", touchTarget = true, iconTint = tokens.accent.color, iconOnlyOnMobile = true, onClick = onClick)
        CopyTurnState.Copied -> ActionButton(ProductIcon.Check, "Copied", "Turn link copied", touchTarget = true, iconOnlyOnMobile = true, onClick = onClick)
    }
}

/** The turn's start in local time (`turnRefTime`); empty when the stored time cannot be read. */
fun turnRefTime(iso: String, zone: ZoneId = ZoneId.systemDefault()): String = try {
    TURN_TIME.format(Instant.parse(iso).atZone(zone))
} catch (_: Exception) {
    ""
}

private val TURN_TIME = DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", Locale.US)

private val CHIP_SHAPE = RoundedCornerShape(6.dp)

/** A composer chip is this wide in every state, so a chip that loads does not move the row. */
private val COMPOSER_CHIP_WIDTH = 288.dp

@Composable
private fun chipHeight() = if (LocalPpmMobile.current) 44.dp else 28.dp

/**
 * One turn of another session as a chip (`TurnRefChipView`): the provider's logo, the session's
 * title, "Turn N", and the project when it is not the one being read. A tap opens the session at
 * that turn; a long press shows the turn's prompt and time in a sheet. [trailing] is the
 * composer's remove button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TurnRefChipView(
    ref: TurnReference,
    currentProject: String?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val opener = LocalChatSessionOpener.current
    val showProject = currentProject != null && ref.projectName != currentProject
    var previewing by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val xs = ppmText(12f, lineHeight = 16f)
    val open = { opener?.open(ChatSessionTarget(ref.projectName, ref.providerId, ref.sessionId, ref.title, ref.turnId)) }
    Row(
        modifier
            .height(chipHeight())
            .widthIn(max = 288.dp)
            .clip(CHIP_SHAPE)
            .background(tokens.accent.opacity(if (pressed) 0.15 else 0.1).color)
            .cssBorder(tokens.accent.opacity(0.3).color, CHIP_SHAPE)
            .testTag("turn-chip:${ref.turnId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f, fill = false)
                .fillMaxHeight()
                .combinedClickable(
                    interactionSource = source,
                    indication = null,
                    role = Role.Button,
                    onLongClickLabel = "Preview turn",
                    onLongClick = { previewing = true },
                    onClick = { open() },
                )
                .webTitle(listOfNotNull(ref.prompt.takeIf { it.isNotEmpty() }, turnRefTime(ref.at).takeIf { it.isNotEmpty() }).joinToString("\n"))
                .semantics { contentDescription = "Turn ${ref.n.toLong()} of ${ref.title}" }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ProviderLogo(ref.providerId, 16.dp, tokens.text.color)
            BasicText(ref.title, Modifier.weight(1f, fill = false), xs.copy(color = tokens.text.color, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText("Turn ${ref.n.toLong()}", style = xs.copy(color = tokens.text3.color), maxLines = 1)
            if (showProject) {
                BasicText("· ${ref.projectName}", Modifier.widthIn(max = 96.dp), xs.copy(color = tokens.text3.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
    if (previewing) {
        TurnRefPreviewDialog(ref, showProject, onOpen = { open() }) { previewing = false }
    }
}

/** The composer's remove button on a chip (`Remove turn reference`). */
@Composable
private fun RemoveChipButton(onRemove: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val size = chipHeight()
    Box(
        Modifier
            .size(size)
            .clip(CHIP_SHAPE)
            .ppmClickable(pressedColor = tokens.surfaceHover.color, shape = CHIP_SHAPE, onClickLabel = "Remove turn reference", onClick = onRemove)
            .webTitle("Remove")
            .semantics { contentDescription = "Remove turn reference" },
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(ProductIcon.X, 14.dp, tokens.text3.color)
    }
}

/**
 * One chip in the composer (`TurnRefChip` with `onRemove`): a skeleton while its turn loads, the
 * reason when it cannot be referenced (it can only be removed), else the turn. The same width in
 * every state, so the row does not move as turns load.
 */
@Composable
fun ComposerTurnChipView(chip: ComposerTurnChip, currentProject: String?, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val box = modifier.widthIn(max = COMPOSER_CHIP_WIDTH).width(COMPOSER_CHIP_WIDTH)
    when (chip) {
        is ComposerTurnChip.Loading -> Row(
            box
                .height(chipHeight())
                .clip(CHIP_SHAPE)
                .background(tokens.panel.opacity(0.6).color)
                .cssBorder(tokens.border.opacity(0.6).color, CHIP_SHAPE)
                .padding(start = 8.dp)
                .semantics { contentDescription = "Loading turn" }
                .testTag("composer-turn-chip-loading"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(tokens.text3.opacity(0.2).color))
            Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(tokens.text3.opacity(0.2).color))
            RemoveChipButton(onRemove)
        }
        is ComposerTurnChip.Failed -> Row(
            box
                .height(chipHeight())
                .clip(CHIP_SHAPE)
                .background(tokens.error.opacity(0.1).color)
                .cssBorder(tokens.error.opacity(0.4).color, CHIP_SHAPE)
                .webTitle(chip.reason)
                .padding(start = 8.dp)
                .testTag("composer-turn-chip-error"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(ProductIcon.TriangleAlert, 14.dp, tokens.error.color)
            BasicText(chip.reason, Modifier.weight(1f), ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
            RemoveChipButton(onRemove)
        }
        is ComposerTurnChip.Ready -> TurnRefChipView(chip.ref, currentProject, box) { RemoveChipButton(onRemove) }
    }
}

/**
 * A turn chip's preview, opened by a long press (`TurnRefPreviewSheet`): the turn's first prompt
 * and when it was asked, and Open session in the thumb zone.
 */
@Composable
fun TurnRefPreviewDialog(ref: TurnReference, showProject: Boolean, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val dismiss by rememberUpdatedState(onDismiss)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .ppmClickable(pressedColor = Color.Transparent, onClick = dismiss)
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .clip(shape)
                    .background(tokens.panel2.color)
                    .cssBorder(tokens.border.color, shape)
                    .ppmClickable(pressedColor = Color.Transparent) {}
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                    .testTag("turn-ref-preview"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val sm = ppmText(14f, lineHeight = 20f)
                val xs = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProviderLogo(ref.providerId, 16.dp, tokens.text.color)
                    BasicText(ref.title, Modifier.weight(1f), sm.copy(color = tokens.text.color, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText("Turn ${ref.n.toLong()}", style = xs)
                }
                if (showProject) BasicText("Project: ${ref.projectName}", style = xs)
                if (ref.prompt.isNotEmpty()) BasicText(ref.prompt, style = sm.copy(color = tokens.text2.color, lineHeight = ppmText(14f, lineHeight = 22.75f).lineHeight))
                turnRefTime(ref.at).takeIf { it.isNotEmpty() }?.let { BasicText(it, style = xs) }
                val button = RoundedCornerShape(6.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(button)
                        .background(tokens.accent.color)
                        .ppmClickable(pressedColor = tokens.accent.opacity(0.85).color, shape = button) {
                            dismiss()
                            onOpen()
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    PpmIcon(ProductIcon.ExternalLink, 16.dp, tokens.accentFg.color)
                    BasicText("Open session", style = sm.copy(color = tokens.accentFg.color, fontWeight = FontWeight.Medium))
                }
            }
        }
    }
}
