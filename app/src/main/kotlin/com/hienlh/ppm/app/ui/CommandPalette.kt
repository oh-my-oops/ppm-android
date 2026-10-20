package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.tablet.Edge
import com.hienlh.ppm.app.ui.tablet.edge
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.state.formatShortcut
import com.hienlh.ppm.state.scoreFileSearchFast
import kotlinx.coroutines.launch

/** At most this many matches are drawn (`MAX_RESULTS`). */
private const val MAX_RESULTS = 100

/** Tailwind's `shadow-2xl`. */
private val SHADOW_2XL = ThemeShadow(0.0, 25.0, 50.0, -12.0, Rgba(0.0, 0.0, 0.0, 0.25))

/** One entry (`CommandItem`): what it is called, the words that also find it, its shortcut, what it does. */
private class PaletteCommand(
    val label: String,
    val icon: ProductIcon,
    val keywords: String,
    val shortcut: String?,
    val action: () -> Unit,
) {
    /** The web searches [keywords] alone, so typing "settings" never finds Settings; the label is searched here too. */
    val searchText = "$label $keywords".lowercase()
}

/**
 * The command palette (command-palette.tsx): a search over the app's actions, Enter on no match
 * asking AI in a new chat. On the desktop layout a 448 dp card a fifth of the way down; on a
 * phone a sheet on the bottom edge, at most 80% of the screen. ↑ ↓ pick, Enter runs, Esc or a
 * tap outside closes. Files, folders, database tables and the actions of features this app does
 * not have yet join with those features.
 */
@Composable
fun CommandPalette(model: AppModel, initialQuery: String, onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val query = rememberTextFieldState(initialQuery)
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    var selected by remember { mutableIntStateOf(0) }
    val bindings = model.keyBindings
    val commands = remember(model, bindings) {
        fun run(action: () -> Unit): () -> Unit = {
            onDismiss()
            action()
        }
        listOf(
            PaletteCommand("New AI Chat", ProductIcon.MessageSquare, "ai assistant claude", formatShortcut(bindings.binding("open-chat")), run { model.scope.launch { model.newChat() } }),
            PaletteCommand("Settings", ProductIcon.Settings, "config preferences theme", formatShortcut(bindings.binding("open-settings")), run { model.openSettings() }),
        )
    }
    val text = query.text.toString()
    val shown = remember(text, commands) {
        if (text.isBlank()) return@remember commands
        val q = text.lowercase().replace(LEADING_DOT_SLASH, "")
        commands.mapNotNull { command -> scoreFileSearchFast(q, command.searchText, command.searchText, command.label.length, 1)?.let { command to it } }
            .sortedBy { it.second }
            .take(MAX_RESULTS)
            .map { it.first }
    }
    LaunchedEffect(shown.size) { selected = selected.coerceAtMost(maxOf(shown.size - 1, 0)) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // `scrollIntoView({ block: "nearest" })`.
    LaunchedEffect(selected) {
        val info = list.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == selected }
        when {
            item == null || item.offset < info.viewportStartOffset -> list.scrollToItem(selected)
            item.offset + item.size > info.viewportEndOffset -> list.scrollBy((item.offset + item.size - info.viewportEndOffset).toFloat())
        }
    }
    fun askAi() {
        val message = text.trim()
        if (message.isEmpty()) return
        onDismiss()
        model.scope.launch { model.newChat(draft = message) }
    }
    fun enter() {
        val command = shown.getOrNull(selected)
        if (command != null) command.action() else askAi()
    }

    if (closedByConnectionLost(onDismiss)) return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) { window?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
            val card: @Composable (Modifier, Boolean) -> Unit = { modifier, showEsc ->
                Column(modifier) {
                    Row(
                        Modifier.fillMaxWidth().edge(Edge.Bottom, tokens.border.color).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PpmIcon(ProductIcon.Search, 16.dp, tokens.text3.color)
                        PpmTextField(
                            query,
                            // The web's adds "(type / or ~/ for filesystem)"; that comes with the files.
                            "Search actions...",
                            Modifier.weight(1f).onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val count = shown.size
                                when (event.key) {
                                    Key.DirectionDown -> if (count > 0) selected = (selected + 1) % count
                                    Key.DirectionUp -> if (count > 0) selected = (selected - 1 + count) % count
                                    Key.Enter, Key.NumPadEnter -> enter()
                                    Key.Escape -> onDismiss()
                                    else -> return@onPreviewKeyEvent false
                                }
                                true
                            },
                            desktopStyle = ppmText(14f, lineHeight = 20f),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            onKeyboardAction = { enter() },
                            focusRequester = focus,
                        )
                        if (showEsc) Kbd("ESC", horizontal = 6)
                    }
                    if (shown.isEmpty()) {
                        if (text.isNotBlank()) AskAiRow(text.trim(), ::askAi) else EmptyLine("No results")
                    } else {
                        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 288.dp), list, PaddingValues(vertical = 4.dp)) {
                            itemsIndexed(shown) { index, command -> CommandRow(command, index == selected) }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().edge(Edge.Top, tokens.border.color).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    ) {
                        val hint = ppmText(10f, lineHeight = 15f).copy(color = tokens.text3.color)
                        BasicText("Press", style = hint)
                        Kbd("Shift", horizontal = 4)
                        Kbd("Shift", horizontal = 4)
                        BasicText("to open this palette", style = hint)
                    }
                }
            }
            if (mobile) {
                // On the bottom edge, its background under the navigation bar, and above the keyboard.
                BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
                    val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                    card(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .heightIn(max = maxHeight * 0.8f)
                            .ppmShadow(SHADOW_2XL, shape)
                            .clip(shape)
                            .background(tokens.bgSolid.color)
                            .border(1.dp, tokens.border.color, shape)
                            .navigationBarsPadding()
                            .imePadding(),
                        maxWidth >= 640.dp,
                    )
                }
            } else {
                // `md:pt-[20vh]`, kept where it is when the keyboard comes up: the list gives way instead.
                BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
                    val top = maxHeight * 0.2f
                    val keyboard = WindowInsets.ime.exclude(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding()
                    val shape = RoundedCornerShape(12.dp)
                    card(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = top)
                            .widthIn(max = 448.dp)
                            .fillMaxWidth()
                            .heightIn(max = (maxHeight - top - keyboard).coerceAtLeast(120.dp))
                            .ppmShadow(SHADOW_2XL, shape)
                            .clip(shape)
                            .background(tokens.bgSolid.color)
                            .border(1.dp, tokens.border.color, shape),
                        true,
                    )
                }
            }
        }
    }
}

/**
 * A result (`px-3 py-2 text-sm transition-colors`): picked in the `accent` surface at 15% and the
 * text colour, else hovered in `surface-elevated`, every change faded.
 */
@Composable
private fun CommandRow(command: PaletteCommand, selected: Boolean) {
    val tokens = LocalPpmTokens.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val picked = colorsFade(selected).toDouble()
    val lit = colorsFade(hovered && !selected).toDouble()
    val color = tokens.text.mix(tokens.text2, picked).color
    Row(
        Modifier
            .fillMaxWidth()
            .background(tokens.panel2.opacity(0.15).mix(tokens.panel2.opacity(lit), picked).color)
            .hoverable(hover)
            .ppmClickable(pressedColor = tokens.panel2.color, onClick = command.action)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PpmIcon(command.icon, 16.dp, color)
        BasicText(
            command.label,
            Modifier.weight(1f),
            style = ppmText(14f, lineHeight = 20f).copy(color = color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        command.shortcut?.takeIf { it.isNotEmpty() }?.let { Kbd(it, horizontal = 6) }
    }
}

/**
 * "Ask AI: …" (`px-3 py-3`), the query in the text colour, cut at 60 characters;
 * `hover:bg-accent/15 hover:text-text-primary` faded in.
 */
@Composable
private fun AskAiRow(query: String, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover()
    val wash = tokens.panel2.opacity(0.15).color
    Row(
        Modifier
            .fillMaxWidth()
            .ppmClickable(pressedColor = wash, hoverColor = wash, source = hover.source, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PpmIcon(ProductIcon.MessageSquare, 16.dp, tokens.accent.color)
        BasicText(
            buildAnnotatedString {
                append("Ask AI: ")
                withStyle(SpanStyle(color = tokens.text.color, fontWeight = FontWeight.Medium)) { append(query.take(60)) }
            },
            style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text.mix(tokens.text2, hover.lit.toDouble()).color),
        )
    }
}

@Composable
private fun EmptyLine(text: String) {
    val tokens = LocalPpmTokens.current
    BasicText(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
        style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text3.color, textAlign = TextAlign.Center),
    )
}

/** `rounded border border-border bg-surface py-0.5 text-[10px] text-text-subtle font-mono`, [horizontal] being its `px`. */
@Composable
private fun Kbd(text: String, horizontal: Int) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    BasicText(
        text,
        Modifier
            .clip(shape)
            .background(tokens.panel.color)
            .border(1.dp, tokens.border.color, shape)
            // A CSS border adds to the box; Compose draws it inside, so the padding takes it in.
            .padding(horizontal = (horizontal + 1).dp, vertical = 3.dp),
        style = ppmText(10f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text3.color),
        maxLines = 1,
    )
}

private val LEADING_DOT_SLASH = Regex("^\\.\\.?/")
