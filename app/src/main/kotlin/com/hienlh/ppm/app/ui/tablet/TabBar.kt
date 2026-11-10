package com.hienlh.ppm.app.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.LocalRedesign
import com.hienlh.ppm.app.ui.ContextMenuPopup
import com.hienlh.ppm.app.ui.IconLine
import com.hienlh.ppm.app.ui.MenuIcon
import com.hienlh.ppm.app.ui.MenuItem
import com.hienlh.ppm.app.ui.MenuSeparator
import com.hienlh.ppm.app.ui.SetTagMenuItem
import com.hienlh.ppm.app.ui.TabIcon
import com.hienlh.ppm.app.ui.TypingDots
import com.hienlh.ppm.app.ui.pressable
import com.hienlh.ppm.app.ui.runningSessions
import com.hienlh.ppm.app.ui.tabTitle
import com.hienlh.ppm.app.ui.unreadSessions
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderInset
import com.hienlh.ppm.design.kit.HoverTooltip
import com.hienlh.ppm.design.kit.kitFocusRing
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.SessionUnread
import com.hienlh.ppm.state.workspace.NON_POPPABLE_TAB_TYPES
import com.hienlh.ppm.state.workspace.SplitDirection
import com.hienlh.ppm.state.workspace.WorkspaceTab
import com.hienlh.ppm.state.workspace.tabSessionId
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** `TYPE_PRIORITY` in use-tab-overflow.ts: the most urgent unread wins an arrow's dot. */
private val UNREAD_PRIORITY = mapOf("done" to 0, "question" to 1, "approval_request" to 2)

private val WRAP_ROW_HEIGHT = 44.dp

/**
 * A panel's tab bar (tab-bar.tsx, default style): 48 dp with its bottom border, the tabs scrolling
 * sideways with the All Tabs and "+" buttons held at their end, and an arrow on each side with more
 * tabs past it — dotted when one of those has an unread chat. A tab added to the bar, and the active
 * one when the bar first shows, is scrolled into view. With "Wrap Tabs" from the bar's own menu or
 * the All Tabs popup, the tabs take up to 3 rows of 44 dp before scrolling vertically.
 */
@Composable
fun TabBar(model: AppModel, panelId: String, tabs: List<WorkspaceTab>, activeTabId: String?) {
    val tokens = LocalPpmTokens.current
    val scroll = rememberScrollState()
    val wrapScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val unread = model.unreadSessions()
    val running = model.runningSessions()
    val drag = LocalTabDrag.current
    val shellState = LocalTabletShellState.current
    val wrap = model.tabWrap
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var localAllTabsOpen by remember { mutableStateOf(false) }
    val allTabsOpen = if (shellState != null) shellState.tabListPanelId == panelId else localAllTabsOpen
    fun setAllTabsOpen(open: Boolean) {
        if (shellState != null) {
            shellState.tabListPanelId = if (open) panelId else null
        } else {
            localAllTabsOpen = open
        }
    }
    DisposableEffect(drag, panelId) { onDispose { drag?.bars?.remove(panelId) } }
    // Each tab's span in the scrolled row, in pixels, to tell which ones are out of view.
    val spans = remember { mutableStateMapOf<String, IntRange>() }
    var viewport by remember { mutableIntStateOf(0) }
    // From 0, as the web's `prevTabCount`: a bar's first frame brings its active tab into view too.
    var shownCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(tabs.size, activeTabId) {
        val grew = tabs.size > shownCount
        shownCount = tabs.size
        if (!grew || activeTabId == null || model.tabWrap) return@LaunchedEffect
        // The new tab is placed in the frame after the one that added it.
        val span = withTimeoutOrNull(1_000) { snapshotFlow { spans[activeTabId] }.filterNotNull().first() } ?: return@LaunchedEffect
        when {
            span.first < scroll.value -> scroll.animateScrollTo(span.first)
            span.last > scroll.value + viewport -> scroll.animateScrollTo(span.last - viewport)
        }
    }
    val chips: @Composable RowScope.(Modifier) -> Unit = { size ->
        for (tab in tabs) {
            val sessionId = tabSessionId(tab)
            val entry = sessionId?.let { unread[it] }?.takeIf { it.count > 0 }
            TabChip(
                model,
                tab,
                panelId,
                active = tab.id == activeTabId,
                unread = entry,
                streaming = tab.type == "chat" && sessionId != null && sessionId in running,
                modifier = size.onPlaced { spans[tab.id] = it.positionInParent().x.toInt().let { x -> x..x + it.size.width } },
                onSelect = { model.activate(tab.id, panelId) },
                onClose = { model.closeTab(tab.id, panelId) },
            )
        }
        // `w-0.5 h-6 bg-primary rounded-full`.
        if (drag?.insert == BarInsert(panelId, null)) {
            Box(Modifier.align(Alignment.CenterVertically).width(2.dp).height(24.dp).background(tokens.accent.color, CircleShape))
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .then(
                if (wrap) {
                    Modifier.heightIn(min = WRAP_ROW_HEIGHT, max = WRAP_ROW_HEIGHT * 3)
                } else {
                    Modifier.height(BAR_HEIGHT)
                },
            )
            .edge(Edge.Bottom, tokens.border.color)
            .onGloballyPositioned { coordinates -> drag?.bars?.set(panelId, coordinates.boundsInRoot()) }
            // A double tap on the bar itself, not on a tab, opens the command palette.
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { model.openPalette() }) }
            .barMenuGestures { menuAt = it },
    ) {
        if (wrap) {
            // `flex-wrap`: 44 dp rows (up to 3 rows before vertical scroll), each ruled along its bottom in the soft border colour.
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(wrapScroll)
                    .drawBehind {
                        val pitch = WRAP_ROW_HEIGHT.toPx()
                        val px = 1.dp.toPx()
                        var y = pitch - px
                        while (y < size.height) {
                            drawRect(tokens.borderSoft.color, Offset(0f, y), Size(size.width, px))
                            y += pitch
                        }
                    },
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                chips(Modifier.height(WRAP_ROW_HEIGHT))
                AllTabsButton(
                    count = tabs.size,
                    active = allTabsOpen,
                    modifier = Modifier.height(WRAP_ROW_HEIGHT),
                    onClick = { setAllTabsOpen(!allTabsOpen) },
                )
                AddTabButton(model, panelId, Modifier.height(WRAP_ROW_HEIGHT))
            }
        } else {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f, fill = false).fillMaxHeight().onSizeChanged { viewport = it.width }.horizontalScroll(scroll),
                ) { chips(Modifier) }
                AllTabsButton(
                    count = tabs.size,
                    active = allTabsOpen,
                    modifier = Modifier.fillMaxHeight(),
                    onClick = { setAllTabsOpen(!allTabsOpen) },
                )
                AddTabButton(model, panelId, Modifier.fillMaxHeight())
            }
            val hidden = hiddenUnread(tabs, spans, unread, scroll.value, scroll.value + viewport)
            if (scroll.value > 1) {
                ScrollArrow(left = true, dot = hidden.first, Modifier.align(Alignment.CenterStart)) {
                    scope.launch { scroll.animateScrollTo(scroll.value - with(density) { 150.dp.roundToPx() }) }
                }
            }
            if (scroll.value < scroll.maxValue - 1) {
                ScrollArrow(left = false, dot = hidden.second, Modifier.align(Alignment.CenterEnd).padding(end = 80.dp)) {
                    scope.launch { scroll.animateScrollTo(scroll.value + with(density) { 150.dp.roundToPx() }) }
                }
            }
        }
        if (allTabsOpen) {
            AllTabsPopup(
                model = model,
                currentPanelId = panelId,
                onDismiss = { setAllTabsOpen(false) },
            )
        }
        menuAt?.let { at -> BarMenu(model, at) { menuAt = null } }
    }
}

/** All Tabs list button (`TB1`): `40×48 dp` showing `ProductIcon.Layers` and the panel's tab count. */
@Composable
private fun AllTabsButton(
    count: Int,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val lit = colorsFade(hovered || active)
    val fg = if (active) tokens.accent.color else tokens.text.mix(tokens.text2, lit.toDouble()).color
    HoverTooltip("All tabs", shortcut = "Ctrl+Shift+A") {
        Row(
            modifier
                .widthIn(min = 40.dp)
                .semantics { contentDescription = "All tabs" }
                .kitFocusRing(focused, RoundedCornerShape(6.dp))
                .ppmClickable(
                    pressedColor = tokens.panel2.opacity(0.5).color,
                    hoverColor = tokens.panel2.opacity(0.4).color,
                    onClickLabel = "All tabs",
                    source = source,
                    onClick = onClick,
                )
                .cssBorderInset(top = 2.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            PpmIcon(ProductIcon.Layers, 14.dp, fg)
            BasicText(
                "$count",
                style = ppmText(10f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 14f).copy(color = fg),
                maxLines = 1,
            )
        }
    }
}

/** "+" (`tabAddButtonClass`): the command palette, for this panel; its glyph lit on hover. */
@Composable
private fun AddTabButton(model: AppModel, panelId: String, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val lit = colorsFade(hovered)
    HoverTooltip("New tab", shortcut = "Ctrl+T") {
        Box(
            modifier
                .width(40.dp)
                .kitFocusRing(focused, RoundedCornerShape(6.dp))
                .ppmClickable(pressedColor = Color.Transparent, onClickLabel = "Open command palette", source = source) {
                    model.focusPanel(panelId)
                    model.openPalette()
                }
                // `border-t-2 border-transparent`.
                .cssBorderInset(top = 2.dp),
            contentAlignment = Alignment.Center,
        ) { PpmIcon(ProductIcon.Plus, 16.dp, tokens.text.mix(tokens.text2, lit.toDouble()).color) }
    }
}

/**
 * All Tabs dropdown popup (`TB4`): 360 dp wide card listing all open tabs grouped by panel,
 * with a search filter, "Wrap tabs" toggle, keyboard navigation (`↑/↓/Enter/Escape`), and `×` close buttons.
 */
@Composable
private fun AllTabsPopup(
    model: AppModel,
    currentPanelId: String,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(with(density) { (-8).dp.roundToPx() }, with(density) { BAR_HEIGHT.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        AllTabsPopupCard(model = model, currentPanelId = currentPanelId, onDismiss = onDismiss)
    }
}

@Composable
internal fun AllTabsPopupCard(
    model: AppModel,
    currentPanelId: String,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val unread = model.unreadSessions()
    val running = model.runningSessions()
    val queryState = rememberTextFieldState()
    val focusRequester = remember { FocusRequester() }
    val state = model.layout
    val panelIds = state?.gridPanelIds?.takeIf { it.isNotEmpty() } ?: listOf(currentPanelId)
    val query = queryState.text.toString().trim()

    val flatEntries = remember(state, query, model.sessions) {
        buildList {
            panelIds.forEachIndexed { index, pid ->
                val panelTabs = state?.panels?.get(pid)?.tabs.orEmpty()
                for (tab in panelTabs) {
                    val title = tabTitle(model, tab)
                    if (query.isEmpty() || title.contains(query, ignoreCase = true) || tab.type.contains(query, ignoreCase = true)) {
                        add(Triple(index + 1, pid, tab))
                    }
                }
            }
        }
    }
    var selectedIndex by remember(query, flatEntries.size) { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    run {
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier
                .width(360.dp)
                .heightIn(max = 420.dp)
                .ppmShadow(tokens.shadowFloat, shape)
                .clip(shape)
                .background(tokens.panel2.color)
                .cssBorder(tokens.border.color, shape = shape)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.Escape -> {
                            onDismiss()
                            true
                        }
                        Key.DirectionDown -> {
                            if (flatEntries.isNotEmpty()) {
                                selectedIndex = (selectedIndex + 1) % flatEntries.size
                            }
                            true
                        }
                        Key.DirectionUp -> {
                            if (flatEntries.isNotEmpty()) {
                                selectedIndex = (selectedIndex - 1 + flatEntries.size) % flatEntries.size
                            }
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            flatEntries.getOrNull(selectedIndex)?.let { (_, pid, tab) ->
                                onDismiss()
                                model.activate(tab.id, pid)
                            }
                            true
                        }
                        else -> false
                    }
                },
        ) {
            // Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BasicText(
                        "All Tabs",
                        style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                    )
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(tokens.panel2.color)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        BasicText(
                            "${flatEntries.size}",
                            style = ppmText(10f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text2.color),
                        )
                    }
                }
                val wrapShape = RoundedCornerShape(8.dp)
                Row(
                    Modifier
                        .clip(wrapShape)
                        .background(if (model.tabWrap) tokens.accent.opacity(0.15).color else tokens.panel2.color)
                        .cssBorder(if (model.tabWrap) tokens.accent.opacity(0.4).color else tokens.borderSoft.color, shape = wrapShape)
                        .ppmClickable(onClickLabel = "Wrap tabs") { model.toggleTabWrap() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PpmIcon(ProductIcon.Check, 12.dp, if (model.tabWrap) tokens.accent.color else tokens.text3.color)
                    BasicText(
                        "Wrap tabs",
                        style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(
                            color = if (model.tabWrap) tokens.accent.color else tokens.text2.color,
                        ),
                    )
                }
            }
            // Filter search box
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 8.dp)
                    .height(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.borderSoft.color, shape = RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PpmIcon(ProductIcon.Search, 14.dp, tokens.text3.color)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (queryState.text.isEmpty()) {
                            BasicText(
                                "Filter open tabs…",
                                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                                maxLines = 1,
                            )
                        }
                        BasicTextField(
                            state = queryState,
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            textStyle = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color),
                            lineLimits = TextFieldLineLimits.SingleLine,
                            cursorBrush = SolidColor(tokens.accent.color),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.borderSoft.color))
            // Grouped tab list
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
            ) {
                if (flatEntries.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            "No matching tabs",
                            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                        )
                    }
                } else {
                    var lastPanelId: String? = null
                    flatEntries.forEachIndexed { idx, (panelNumber, pid, tab) ->
                        if (panelIds.size > 1 && pid != lastPanelId) {
                            lastPanelId = pid
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                BasicText(
                                    "PANEL $panelNumber",
                                    style = ppmText(10f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text3.color),
                                )
                                if (state?.focusedPanelId == pid) {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(tokens.accent.opacity(0.15).color)
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                    ) {
                                        BasicText(
                                            "FOCUSED",
                                            style = ppmText(9f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 12f).copy(color = tokens.accent.color),
                                        )
                                    }
                                }
                            }
                        }
                        val isPanelActive = state?.panels?.get(pid)?.activeTabId == tab.id
                        val isHighlighted = idx == selectedIndex
                        val sessionId = tabSessionId(tab)
                        val unreadEntry = sessionId?.let { unread[it] }?.takeIf { it.count > 0 }
                        val isStreaming = tab.type == "chat" && sessionId != null && sessionId in running
                        val title = tabTitle(model, tab)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp)
                                .height(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when {
                                        isHighlighted -> tokens.accent.opacity(0.14).color
                                        isPanelActive -> tokens.panel2.color
                                        else -> Color.Transparent
                                    },
                                )
                                .ppmClickable(
                                    hoverColor = tokens.panel2.opacity(0.6).color,
                                    pressedColor = tokens.panel2.color,
                                    onClickLabel = title,
                                ) {
                                    onDismiss()
                                    model.activate(tab.id, pid)
                                }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box {
                                if (isStreaming) {
                                    PpmIcon(ProductIcon.MessageCircle, 16.dp, tokens.warning.color)
                                    TypingDots(tokens.warning, Modifier.align(Alignment.Center))
                                } else {
                                    val iconTint = if (isPanelActive) tokens.accent else tokens.text2
                                    TabIcon(tab, iconTint, line = IconLine(13f, 18f))
                                    if (unreadEntry != null) {
                                        Box(
                                            Modifier
                                                .align(Alignment.TopEnd)
                                                .offset(3.dp, (-3).dp)
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(tokens.notification(unreadEntry.type).color),
                                        )
                                    }
                                }
                            }
                            BasicText(
                                title,
                                Modifier.weight(1f),
                                style = ppmText(
                                    13f,
                                    if (isPanelActive) FontWeight.SemiBold else FontWeight.Normal,
                                    lineHeight = 18f,
                                ).copy(color = if (isPanelActive) tokens.accent.color else tokens.text.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (tab.closable) {
                                Box(
                                    Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .ppmClickable(
                                            hoverColor = tokens.panel2.color,
                                            pressedColor = tokens.panel2.color,
                                            onClickLabel = "Close $title",
                                        ) {
                                            model.closeTab(tab.id, pid)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    PpmIcon(ProductIcon.X, 14.dp, tokens.text2.color)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The bar's own menu, for a press no tab has taken for its own: a mouse's right button, or a
 * finger held still for the system's long press, as Radix opens the web's on a touch held still.
 */
private fun Modifier.barMenuGestures(onMenu: (Offset) -> Unit): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    val menu by rememberUpdatedState(onMenu)
    pointerInput(Unit) {
        awaitEachGesture {
            val press = awaitPointerEvent()
            val down = press.changes.firstOrNull { it.changedToDown() } ?: return@awaitEachGesture
            if (down.type == PointerType.Mouse) {
                if (press.buttons.isSecondaryPressed) {
                    down.consume()
                    menu(down.position)
                }
                return@awaitEachGesture
            }
            val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var change: PointerInputChange? = down
                while (change != null && change.pressed && !change.isConsumed && (change.position - down.position).getDistance() <= viewConfiguration.touchSlop) {
                    change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                }
            } == null
            if (held) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                menu(down.position)
            }
        }
    }
}

/** `BarContextMenu`: the tabs wrapped onto rows or scrolling, and the command palette. */
@Composable
private fun BarMenu(model: AppModel, at: Offset, onDismiss: () -> Unit) {
    fun pick(action: () -> Unit) {
        onDismiss()
        action()
    }
    ContextMenuPopup(at, onDismiss) {
        MenuItem(if (model.tabWrap) "Scroll Tabs" else "Wrap Tabs", onClick = { pick { model.toggleTabWrap() } })
        MenuSeparator()
        MenuItem("Open Command Palette", onClick = { pick { model.openPalette() } })
    }
}

/** `getHiddenUnreadDirection`: the most urgent unread kind among the tabs wholly past each edge. */
private fun hiddenUnread(
    tabs: List<WorkspaceTab>,
    spans: Map<String, IntRange>,
    unread: Map<String, SessionUnread>,
    viewLeft: Int,
    viewRight: Int,
): Pair<String?, String?> {
    var left: String? = null
    var right: String? = null
    for (tab in tabs) {
        if (tab.type != "chat") continue
        val entry = tabSessionId(tab)?.let { unread[it] }?.takeIf { it.count > 0 } ?: continue
        val span = spans[tab.id] ?: continue
        val priority = UNREAD_PRIORITY[entry.type] ?: 0
        if (span.last <= viewLeft && priority > (left?.let { UNREAD_PRIORITY[it] ?: 0 } ?: -1)) left = entry.type
        if (span.first >= viewRight && priority > (right?.let { UNREAD_PRIORITY[it] ?: 0 } ?: -1)) right = entry.type
    }
    return left to right
}

/** `40×48 dp` over a fade from the background, the chevron dotted for an unread tab out that way. */
@Composable
private fun ScrollArrow(left: Boolean, dot: String?, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val bg = tokens.bgSolid.color
    val fade = if (left) listOf(bg, bg, Color.Transparent) else listOf(Color.Transparent, bg, bg)
    Box(
        modifier
            .width(40.dp)
            .height(BAR_HEIGHT)
            .background(Brush.horizontalGradient(fade))
            .ppmClickable(pressedColor = Color.Transparent, onClickLabel = if (left) "Scroll tabs left" else "Scroll tabs right", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box {
            PpmIcon(if (left) ProductIcon.ChevronLeft else ProductIcon.ChevronRight, 16.dp, tokens.text2.color)
            if (dot != null) {
                Box(
                    Modifier
                        .align(if (left) Alignment.TopEnd else Alignment.TopStart)
                        .offset(if (left) 2.dp else (-2).dp, (-4).dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(tokens.notification(dot).color),
                )
            }
        }
    }
}

/**
 * A tab (draggable-tab.tsx, default style): 48 dp tall, a 2 dp top border in the accent and the
 * raised panel colour when active, a soft 1 dp line on its right. Its icon types while the chat's
 * turn runs and carries the unread dot otherwise; a tag shows as a bar along its left edge.
 * Supports middle-click close, double-click / double-tap inline chat rename, and `Shift+F10` menu.
 */
@Composable
private fun TabChip(
    model: AppModel,
    tab: WorkspaceTab,
    panelId: String,
    active: Boolean,
    unread: SessionUnread?,
    streaming: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val redesign = LocalRedesign.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val lit = colorsFade(hovered && !active)
    val on = colorsFade(active)
    val color = tokens.accent.mix(tokens.text.mix(tokens.text2, lit.toDouble()), on.toDouble())
    val fill = tokens.panel2.mix(tokens.panel.opacity(0.6 * lit), on.toDouble())
    val chat = model.chat(tab.id)
    val sessionId = tabSessionId(tab)
    val session = sessionId?.let { id -> model.sessions.firstOrNull { it.id == id } }
    val tag = session?.tag?.color?.let(Rgba::hex)
    val title = tabTitle(model, tab)
    val drag = LocalTabDrag.current
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var renaming by remember { mutableStateOf(false) }
    val snapshot = rememberGraphicsLayer()
    DisposableEffect(drag, tab.id) { onDispose { drag?.chips?.remove(tab.id) } }

    fun saveInlineRename(newTitle: String) {
        renaming = false
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty() || trimmed == title) return
        model.retitleTab(tab.id, trimmed)
        session?.let { s -> model.scope.launch { model.rename(s, trimmed) } }
    }

    Box(
        modifier
            .fillMaxHeight()
            .then(if (redesign) Modifier.widthIn(min = 108.dp, max = 220.dp) else Modifier)
            .pointerHoverIcon(if (pressed) WebCursor.Grabbing else WebCursor.Grab)
            .onGloballyPositioned { drag?.chips?.set(tab.id, panelId to Rect(it.positionInRoot(), it.size.toSize())) }
            .pointerInput(tab.id, tab.closable) {
                awaitEachGesture {
                    val press = awaitPointerEvent()
                    val down = press.changes.firstOrNull { it.changedToDown() } ?: return@awaitEachGesture
                    if (down.type == PointerType.Mouse && press.buttons.isTertiaryPressed && tab.closable) {
                        down.consume()
                        onClose()
                    }
                }
            }
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.F10 && ev.isShiftPressed) {
                    menuAt = Offset.Zero
                    true
                } else {
                    false
                }
            }
            .then(if (drag == null) Modifier else Modifier.tabGestures(drag, model, { DraggedTab(tab.id, panelId, title, snapshot) }) { menuAt = it }),
    ) {
        // The tab as a mouse drag shows it: everything but the drop mark.
        Box(
            Modifier.fillMaxHeight().drawWithContent {
                snapshot.record { this@drawWithContent.drawContent() }
                drawLayer(snapshot)
            },
        ) {
            Row(
                Modifier
                    .fillMaxHeight()
                    .background(fill.color)
                    .kitFocusRing(focused)
                    // A finger gets the hover's fill while it is down; a mouse has it already.
                    .pressable(
                        pressedColor = if (active || hovered) Color.Transparent else tokens.panel.opacity(0.6).color,
                        source = source,
                        onClick = onSelect,
                    )
                    .pointerInput(tab.id, tab.type) {
                        if (tab.type == "chat") {
                            detectTapGestures(
                                onTap = { onSelect() },
                                onDoubleTap = { renaming = true },
                            )
                        }
                    }
                    // `[border-right:1px_solid_var(--border-soft)]`, and `border-t-2` in the accent while active.
                    .cssBorder(tokens.borderSoft.color, sides = CssSides.End)
                    .cssBorder(tokens.accent.opacity(on.toDouble()).color, width = 2.dp, sides = CssSides.Top)
                    .padding(start = 12.dp, end = if (tab.closable) 6.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box {
                    if (streaming) {
                        PpmIcon(ProductIcon.MessageCircle, 16.dp, tokens.warning.color)
                        TypingDots(tokens.warning, Modifier.align(Alignment.Center))
                    } else {
                        TabIcon(tab, color, line = IconLine(12f, 16f))
                        if (unread != null && (!active || unread.manual)) {
                            Box(Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp).size(8.dp).clip(CircleShape).background(tokens.notification(unread.type).color))
                        }
                    }
                }
                if (renaming) {
                    InlineTabRename(
                        initialTitle = title,
                        onCommit = ::saveInlineRename,
                        onCancel = { renaming = false },
                    )
                } else {
                    BasicText(
                        title,
                        Modifier.widthIn(max = if (redesign) 144.dp else 120.dp),
                        style = ppmText(12f, if (active && redesign) FontWeight.SemiBold else FontWeight.Normal, lineHeight = 16f).copy(color = color.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (tab.closable) {
                    // 28×28 dp touch target around the 18×18 dp visual close pill.
                    Box(
                        Modifier
                            .size(if (redesign) 28.dp else 16.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, onClickLabel = "Close tab", onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) { PpmIcon(ProductIcon.X, 12.dp, color.color) }
                }
            }
            if (tag != null) {
                // `absolute left-0 top-2 bottom-2 w-[3px] rounded-r-full`.
                Box(
                    Modifier.align(Alignment.CenterStart).padding(vertical = 8.dp).width(3.dp).fillMaxHeight().drawBehind {
                        val round = CornerRadius(size.width)
                        drawPath(Path().apply { addRoundRect(RoundRect(size.toRect(), topRight = round, bottomRight = round)) }, tag.color)
                    },
                )
            }
        }
        if (drag?.insert == BarInsert(panelId, tab.id)) {
            // A mouse dropping before this tab: `absolute left-0 top-1 bottom-1 w-0.5 bg-primary rounded-full`.
            Box(Modifier.align(Alignment.CenterStart).padding(vertical = 4.dp).width(2.dp).fillMaxHeight().background(tokens.accent.color, CircleShape))
        }
        menuAt?.let { at ->
            TabMenu(
                model = model,
                tab = tab,
                panelId = panelId,
                at = at,
                onRenameChat = if (tab.type == "chat") ({ renaming = true }) else null,
                onDismiss = { menuAt = null },
            )
        }
    }
}

@Composable
private fun InlineTabRename(
    initialTitle: String,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val state = rememberTextFieldState(initialTitle)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
    Box(
        Modifier
            .widthIn(min = 80.dp, max = 144.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(tokens.bgSolid.color)
            .cssBorder(tokens.accent.color, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            onCommit(state.text.toString())
                            true
                        }
                        Key.Escape -> {
                            onCancel()
                            true
                        }
                        else -> false
                    }
                },
            textStyle = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
            lineLimits = TextFieldLineLimits.SingleLine,
            cursorBrush = SolidColor(tokens.accent.color),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onKeyboardAction = { onCommit(state.text.toString()) },
        )
    }
}

/**
 * A tab's menu (draggable-tab.tsx, with tab-bar.tsx's items for a chat): Open in window where
 * the tablet can float it, Split right / Split down so splitting is reachable by touch without
 * dragging, Rename chat for chat tabs, Mark as unread while the chat has none and Set Tag while
 * the project has tags, then Close, Close Others and Close to the Right.
 */
@Composable
private fun TabMenu(
    model: AppModel,
    tab: WorkspaceTab,
    panelId: String,
    at: Offset,
    onRenameChat: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val sessionId = tabSessionId(tab)
    val unread = sessionId?.let { model.unreadSessions()[it] }?.takeIf { it.count > 0 }
    val session = sessionId?.let { id -> model.sessions.firstOrNull { it.id == id } }
    fun pick(action: () -> Unit) {
        onDismiss()
        action()
    }
    ContextMenuPopup(at, onDismiss) {
        // A tab with a window kind of its own would be shown twice.
        if (model.wide && tab.type !in NON_POPPABLE_TAB_TYPES) {
            MenuItem("Open in window", onClick = { pick { model.popOutTab(tab.id, panelId) } }, icon = { MenuIcon(ProductIcon.ExternalLink) })
            MenuSeparator()
        }
        if (model.wide) {
            MenuItem("Split right", onClick = { pick { model.splitPanel(SplitDirection.Right, tab.id, panelId, panelId) } }, icon = { MenuIcon(ProductIcon.Columns) })
            MenuItem("Split down", onClick = { pick { model.splitPanel(SplitDirection.Down, tab.id, panelId, panelId) } }, icon = { MenuIcon(ProductIcon.PanelBottom) })
            MenuSeparator()
        }
        if (onRenameChat != null) {
            MenuItem("Rename chat…", onClick = { pick { onRenameChat() } }, icon = { MenuIcon(ProductIcon.Pencil) })
            MenuSeparator()
        }
        if (sessionId != null && unread == null) {
            MenuItem("Mark as unread", onClick = { pick { model.markUnread(sessionId, tab.title) } }, icon = { MenuIcon(ProductIcon.Circle, tokens.accent) })
            MenuSeparator()
        }
        if (session != null && model.tags.tags.isNotEmpty()) {
            SetTagMenuItem(model, session, onDismiss)
            MenuSeparator()
        }
        if (tab.closable) MenuItem("Close", onClick = { pick { model.closeTab(tab.id, panelId) } })
        MenuItem("Close Others", onClick = { pick { model.closeOtherTabs(tab.id) } })
        MenuItem("Close to the Right", onClick = { pick { model.closeTabsToRight(tab.id) } })
    }
}
