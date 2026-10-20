package com.hienlh.ppm.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.tracksTextFocus
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.brightness
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ModelOption
import com.hienlh.ppm.protocol.PromptCacheState
import com.hienlh.ppm.protocol.TurnOptions
import com.hienlh.ppm.state.ChatController
import com.hienlh.ppm.state.ChatState
import com.hienlh.ppm.state.Effort
import com.hienlh.ppm.state.MessagePriority
import com.hienlh.ppm.state.ModelListStatus
import com.hienlh.ppm.state.ModelNames
import com.hienlh.ppm.state.PermissionMode
import com.hienlh.ppm.state.PromptCacheStatus
import kotlinx.coroutines.delay

/** Chips of the composer row: 11.5 px, with the body's 1.5 line height. */
private val chipText = ppmText(11.5f, lineHeight = 17.25f)

/**
 * The message box as a phone draws it (`message-input.tsx`, its `md:hidden` rows): the chips
 * that set how the next turn runs over the text and Send, or Stop while the turn runs and nothing
 * is typed. [narrow] is the chat's `@max-[420px]` — the mode and priority chips keep only their
 * icons. Attachments and the mic are left out until this app has them.
 */
@Composable
fun MobileComposer(model: AppModel, tab: ChatTab, chat: ChatController, state: ChatState, narrow: Boolean) {
    val tokens = LocalPpmTokens.current
    val running = state.isRunning
    val focus = remember { FocusRequester() }
    var modeOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    // The model can't change mid-turn: its sheet closes as the turn starts.
    LaunchedEffect(running) { if (running) modelOpen = false }

    fun send() {
        val text = tab.draft.text.toString().trim()
        val now = chat.state.value
        if (text.isEmpty() || !now.canSend) return
        // The priority goes only with a message sent during a turn, and is Queue again after it.
        chat.send(text, TurnOptions(permissionMode = tab.permissionMode?.value, priority = if (now.isRunning) tab.priority.value else null))
        tab.draft.clearText()
        tab.priority = MessagePriority.DEFAULT
    }

    val shape = RoundedCornerShape(tokens.rad.dp)
    Column(
        Modifier
            .padding(8.dp)
            .fillMaxWidth()
            .ppmShadow(tokens.shadowFloat, shape)
            .clip(shape)
            .background(tokens.panel.color)
            .border(1.dp, tokens.border.color, shape)
            // A tap on the box's padding goes to the text, as the web's container click does.
            .clickable(remember { MutableInteractionSource() }, indication = null) { focus.requestFocus() }
            .padding(1.dp),
    ) {
        // `flex flex-wrap items-center gap-1 px-2 pt-2 relative`: the mode panel opens over this row.
        Box(Modifier.fillMaxWidth()) {
            ChipsRow(model, tab, chat, state, narrow, onMode = { modeOpen = !modeOpen }, onModel = { modelOpen = true })
            if (modeOpen) {
                ModePopover(tab.permissionMode ?: PermissionMode.FALLBACK, onDismiss = { modeOpen = false }) { mode ->
                    tab.permissionMode = mode
                    modeOpen = false
                }
            }
        }
        // `flex items-end gap-1 px-2 py-2`.
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val inputStyle = ppmText(16f, lineHeight = INPUT_LINE).copy(color = tokens.text.color)
            BasicTextField(
                state = tab.draft,
                modifier = Modifier
                    .weight(1f)
                    // `max-h-20`, its `py-1.5` included.
                    .heightIn(max = 80.dp)
                    .focusRequester(focus)
                    .tracksTextFocus(tab.draft)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            // A keyboard's Return sends and Shift+Return breaks the line, as on the web.
                            event.key == Key.Enter && !event.isShiftPressed -> {
                                send()
                                true
                            }
                            event.key == Key.Tab && event.isShiftPressed -> {
                                tab.permissionMode = (tab.permissionMode ?: PermissionMode.FALLBACK).next
                                true
                            }
                            else -> false
                        }
                    },
                textStyle = inputStyle,
                cursorBrush = SolidColor(tokens.accent.color),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                onKeyboardAction = { send() },
                lineLimits = TextFieldLineLimits.MultiLine(),
                decorator = { field ->
                    Box(Modifier.padding(vertical = 6.dp)) {
                        if (tab.draft.text.isEmpty()) {
                            BasicText(
                                if (running) "Follow-up..." else "Ask anything...",
                                style = inputStyle.copy(color = tokens.text3.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        field()
                    }
                },
            )
            val hasContent = tab.draft.text.isNotBlank()
            if (running && !hasContent) {
                SquareButton(ProductIcon.Square, 14.dp, tokens.error, Color.White, hoverFill = tokens.error.opacity(0.8), label = "Stop") { chat.cancel() }
            } else {
                SquareButton(
                    ProductIcon.ArrowUp, 16.dp, tokens.accent, tokens.accentFg.color, hoverFill = tokens.accent.opacity(0.9),
                    label = "Send", enabled = hasContent && state.canSend, onClick = ::send,
                )
            }
        }
    }
    if (modelOpen && !running) {
        ModelSheet(model, chat, state, onDismiss = { modelOpen = false })
    }
}

/** `text-sm`'s 1.25 rem ratio, carried to the 16 px a phone's inputs are set at. */
private const val INPUT_LINE = 16f * 1.25f / 0.875f

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipsRow(model: AppModel, tab: ChatTab, chat: ChatController, state: ChatState, narrow: Boolean, onMode: () -> Unit, onModel: () -> Unit) {
    val running = state.isRunning
    val catalog = model.models
    val entries = catalog?.entries?.collectAsState()?.value
    val name = remember(entries, state.model, chat.providerId) {
        catalog?.displayName(state.model, chat.providerId) ?: state.model?.let(ModelNames::displayName) ?: "Model"
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val mode = tab.permissionMode ?: PermissionMode.FALLBACK
        NeutralChip(mode.icon, if (narrow) null else mode.label, narrow, labelMax = 100.dp, onClick = onMode)
        ModelChip(ModelNames.chipLabel(name, state.effort ?: Effort.DEFAULT.value), state.thinking, disabled = running, onClick = onModel)
        if (running) {
            NeutralChip(tab.priority.icon, if (narrow) null else tab.priority.label, narrow) { tab.priority = tab.priority.following }
        }
        PromptCacheChip(state.promptCache)
    }
}

/**
 * `px-[9px] py-1 rounded-full text-[11.5px] text-text-2 bg-panel-2 border border-border-soft`,
 * `hover:text-text-primary hover:border-border` faded in: the mode and the priority. [label] null
 * draws the icon alone, at `px-[7px]`.
 */
@Composable
private fun NeutralChip(icon: ProductIcon, label: String?, narrow: Boolean, labelMax: Dp = Dp.Unspecified, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val fade = colorsFade(hovered)
    val lit = if (pressed) 1.0 else fade.toDouble()
    val color = tokens.text.mix(tokens.text2, lit)
    Row(
        Modifier
            .clip(CircleShape)
            .background(tokens.panel2.color)
            .border(1.dp, tokens.border.mix(tokens.borderSoft, lit).color, CircleShape)
            .hoverable(source)
            .clickable(source, indication = null, onClick = onClick)
            .padding(horizontal = if (narrow) 7.dp else 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // An icon alone still takes the line's height, as the text beside it would.
        Box(Modifier.height(17.25.dp), contentAlignment = Alignment.Center) { PpmIcon(icon, 12.dp, color.color) }
        if (label != null) {
            BasicText(
                label,
                Modifier.widthIn(max = labelMax),
                style = chipText.copy(color = color.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * `text-primary bg-accent-wash border border-accent-wash-border`, sparkles, a brain while
 * thinking; `hover:brightness-110` faded in. 50% while the turn runs, when a mouse over it still
 * brightens it, as CSS `:hover` matches a disabled button.
 */
@Composable
private fun ModelChip(text: String, thinking: Boolean, disabled: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val fade = colorsFade(hovered)
    val lit = if (pressed) 1f else fade
    Row(
        Modifier
            .alpha(if (disabled) 0.5f else 1f)
            .brightness(1f + 0.1f * lit)
            .clip(CircleShape)
            .background(tokens.accentWash.color)
            .border(1.dp, tokens.accentWashBorder.color, CircleShape)
            .hoverable(source)
            .clickable(source, indication = null, enabled = !disabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(ProductIcon.Sparkles, 14.dp, tokens.accent.color)
        BasicText(
            text,
            Modifier.widthIn(max = 140.dp),
            style = chipText.copy(color = tokens.accent.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (thinking) PpmIcon(ProductIcon.Brain, 12.dp, tokens.accent.color)
    }
}

/**
 * How long the prompt cache has left (`PromptCacheChip`): a countdown while warm, red with the
 * time it has sat idle once gone; nothing until PPM has measured the session.
 */
@Composable
private fun PromptCacheChip(cache: PromptCacheState?) {
    val tokens = LocalPpmTokens.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(cache) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val status = PromptCacheStatus.of(cache, now.toDouble())
    val label = status.label ?: return
    val cold = status is PromptCacheStatus.Cold
    val color = if (cold) tokens.error else tokens.text2
    Row(
        Modifier
            .clip(CircleShape)
            .background((if (cold) tokens.error.opacity(0.1) else tokens.panel2).color)
            .border(1.dp, (if (cold) tokens.error.opacity(0.4) else tokens.borderSoft).color, CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(ProductIcon.Clock, 12.dp, color.color)
        BasicText(label, style = chipText.copy(color = color.color, fontFeatureSettings = "tnum"), maxLines = 1)
    }
}

/**
 * `size-9 rounded-[11px]` with the float shadow, [hoverFill] under a mouse (faded in) or a finger;
 * 30% and flat when disabled, when a mouse over it still lights it.
 */
@Composable
private fun SquareButton(
    icon: ProductIcon,
    iconSize: Dp,
    fill: Rgba,
    tint: Color,
    hoverFill: Rgba,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(11.dp)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val fade = colorsFade(hovered)
    val lit = if (pressed && enabled) 1.0 else fade.toDouble()
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.3f)
            .size(36.dp)
            .then(if (enabled) Modifier.ppmShadow(tokens.shadowFloat, shape) else Modifier)
            .clip(shape)
            .background(hoverFill.mix(fill, lit).color)
            .hoverable(source)
            .clickable(source, indication = null, enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, iconSize, tint)
    }
}

/**
 * The modes (`ModeSelector`, `w-72 md:w-80`): over the chips row with its left edge on the row's,
 * 4 px clear of it. A tap anywhere else closes it. A row's `hover:bg-surface-elevated` is the
 * popover's own fill on the web, so this lights it in the hover tint instead.
 */
@Composable
private fun ModePopover(current: PermissionMode, onDismiss: () -> Unit, pick: (PermissionMode) -> Unit) {
    val tokens = LocalPpmTokens.current
    val width = if (LocalPpmMobile.current) 288.dp else 320.dp
    val room = with(LocalDensity.current) { 16.dp.roundToPx() }
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val provider = remember(room, gap) { AboveStart(room, gap) }
    if (closedByConnectionLost(onDismiss)) return
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true, clippingEnabled = false)) {
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier
                .padding(16.dp)
                .width(width)
                .ppmShadow(tokens.shadowPanel, shape)
                .clip(shape)
                .background(tokens.panel2.over(tokens.bgSolid).color)
                .border(1.dp, tokens.border.color, shape),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText("Modes", Modifier.weight(1f), style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color))
                val kbd = RoundedCornerShape(4.dp)
                BasicText(
                    "Shift + Tab",
                    Modifier
                        .clip(kbd)
                        .background(tokens.panel2.color)
                        .border(1.dp, tokens.border.color, kbd)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = ppmText(10f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text3.color),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))
            Column(Modifier.padding(vertical = 4.dp)) {
                for (mode in PermissionMode.entries) {
                    val active = mode == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (active) tokens.panel2.color else Color.Transparent)
                            .ppmClickable(pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color) { pick(mode) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PpmIcon(mode.icon, 16.dp, tokens.text2.color, Modifier.padding(top = 2.dp))
                        Column(Modifier.weight(1f)) {
                            BasicText(mode.label, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color))
                            BasicText(mode.detail, style = ppmText(12f, lineHeight = 16.5f).copy(color = tokens.text3.color))
                        }
                        if (active) PpmIcon(ProductIcon.Check, 16.dp, tokens.accent.color, Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

/** Over the anchor, left edges together, [gap] clear of it; [room] is the shadow's margin inside the popup. */
private class AboveStart(private val room: Int, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val width = popupContentSize.width - 2 * room
        val height = popupContentSize.height - 2 * room
        val x = anchorBounds.left.coerceIn(0, maxOf(0, windowSize.width - width))
        val y = maxOf(0, anchorBounds.top - gap - height)
        return IntOffset(x - room, y - room)
    }
}

/**
 * Model, effort and thinking in one sheet (`ModelThinkingSelector` on a phone): the provider's
 * models on the left, the efforts and the thinking switch on the right. A pick leaves the sheet
 * open, as on the web.
 */
@Composable
private fun ModelSheet(model: AppModel, chat: ChatController, state: ChatState, onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val catalog = model.models
    val entries = catalog?.entries?.collectAsState()?.value
    LaunchedEffect(catalog, chat.providerId) { catalog?.load(chat.providerId) }
    val models: List<ModelOption> = remember(catalog, entries) { catalog?.models(chat.providerId).orEmpty() }
    val status: ModelListStatus? = remember(catalog, entries) { catalog?.status(chat.providerId) ?: ModelListStatus.LOADING }
    val effort = Effort.of(state.effort) ?: Effort.DEFAULT
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.8f
    BottomSheet(onDismiss) {
        // `px-1 pb-2`, then `flex divide-x divide-border`.
        Row(
            Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
                .height(IntrinsicSize.Min),
        ) {
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                SectionLabel("Model")
                if (status != null) {
                    BasicText(
                        status.text,
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                    )
                }
                for (option in models) {
                    OptionRow(active = option.value == state.model, onClick = { chat.setModel(option.value) }) {
                        BasicText(
                            ModelNames.shortLabel(option.label),
                            Modifier.weight(1f),
                            style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text.color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(tokens.border.color))
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                SectionLabel("Effort")
                for (option in Effort.entries) {
                    OptionRow(active = option == effort, onClick = { chat.setEffort(option) }) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            BasicText(option.label, style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text.color), maxLines = 1)
                            if (option == Effort.DEFAULT) {
                                BasicText(
                                    "Default",
                                    Modifier.padding(start = 8.dp),
                                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(tokens.border.color))
                ThinkingRow(state.thinking) { chat.setThinking(!state.thinking) }
            }
        }
    }
}

/** `px-3 py-1.5 text-xs font-medium text-text-secondary`. */
@Composable
private fun SectionLabel(text: String) {
    val tokens = LocalPpmTokens.current
    BasicText(
        text,
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color),
    )
}

/**
 * `flex items-center gap-3 px-3 min-h-[44px] text-sm`, lit and checked when chosen. Its
 * `hover:bg-surface-elevated` is the sheet's own fill, so the hover tint lights it instead.
 */
@Composable
private fun OptionRow(active: Boolean, onClick: () -> Unit, label: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(if (active) tokens.panel2.color else Color.Transparent)
            .ppmClickable(pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        label()
        if (active) PpmIcon(ProductIcon.Check, 16.dp, tokens.accent.color)
    }
}

/** `role="switch"`: a 36×20 track, its 16 px knob 2 px in when off and 16 px in when on; lit at once under a mouse. */
@Composable
private fun ThinkingRow(on: Boolean, onToggle: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val knob by animateDpAsState(if (on) 16.dp else 2.dp, tween(150), label = "knob")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .ppmClickable(pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color, fade = false, onClick = onToggle)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PpmIcon(ProductIcon.Brain, 16.dp, tokens.text.color)
        BasicText("Thinking", Modifier.weight(1f), style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text.color))
        Box(Modifier.size(36.dp, 20.dp).clip(CircleShape).background((if (on) tokens.accent else tokens.border).color)) {
            Box(Modifier.offset(x = knob, y = 2.dp).size(16.dp).clip(CircleShape).background(Color.White))
        }
    }
}

val PermissionMode.icon: ProductIcon
    get() = when (this) {
        PermissionMode.DEFAULT -> ProductIcon.Hand
        PermissionMode.ACCEPT_EDITS -> ProductIcon.Code
        PermissionMode.PLAN -> ProductIcon.ClipboardList
        PermissionMode.BYPASS_PERMISSIONS -> ProductIcon.ShieldOff
    }

val MessagePriority.icon: ProductIcon
    get() = when (this) {
        MessagePriority.NOW -> ProductIcon.Zap
        MessagePriority.NEXT -> ProductIcon.ListOrdered
        MessagePriority.LATER -> ProductIcon.Clock
    }
