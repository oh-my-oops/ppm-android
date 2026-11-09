package com.hienlh.ppm.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Base64
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollbar
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.FileEntry
import com.hienlh.ppm.protocol.SlashItem
import com.hienlh.ppm.state.FilePickerList
import com.hienlh.ppm.state.SlashItemsStore
import com.hienlh.ppm.state.SlashKindFilter
import com.hienlh.ppm.state.SlashPickerList
import com.hienlh.ppm.state.slashScopeLabel
import com.hienlh.ppm.state.splitFilePath
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

private val PickerPopEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/** Whether the picker floats above the composer (`chat-tab.tsx`) or sits in flow (`Settings`). */
enum class PickerVariant(val id: String) {
    FLOATING("floating"),
    STATIC("static"),
}

val SlashDescMaxLinesKey = SemanticsPropertyKey<Int>("SlashDescMaxLines")
val SlashTileKindKey = SemanticsPropertyKey<String>("SlashTileKind")
val StartEllipsisShownKey = SemanticsPropertyKey<String>("StartEllipsisShown")

/**
 * What the composer's open picker lists (`SlashCommandPicker`'s `displayItems`, `FilePicker`'s
 * `filtered`), and the keys it takes. A picker with nothing matching anywhere is not shown, while
 * an empty filtered tab in `/` stays open (`anyMatch == true`) and absorbs pick/navigation keys.
 */
@Stable
class ComposerPickers(
    /** The slash picker's rows while it is shown. */
    val slash: SlashPickerList?,
    /** The file picker's rows while it is shown. */
    val files: FilePickerList?,
    private val model: AppModel,
    private val tab: ChatTab,
) {
    val isShowing: Boolean
        get() = slash != null || files != null

    /**
     * The shown picker's keydown, heard before the box's (`handleKeyDown`): ↑/↓ go round its rows,
     * Return and Tab pick the highlighted one, Escape closes it — with any modifier, as on the web.
     */
    fun key(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || (slash == null && files == null)) return false
        if (event.key == Key.Escape) {
            tab.closePickers()
            return true
        }
        if (slash != null && slash.items.isEmpty()) {
            // A tab with nothing in it: the keys that would move or pick do nothing rather than send.
            return when (event.key) {
                Key.DirectionUp, Key.DirectionDown, Key.Enter, Key.NumPadEnter, Key.Tab -> true
                else -> false
            }
        }
        when (event.key) {
            Key.DirectionUp, Key.DirectionDown -> {
                val delta = if (event.key == Key.DirectionUp) -1 else 1
                if (slash != null) tab.slashSelection = slash.step(tab.slashSelection, delta)
                else files?.let { tab.fileSelection = it.step(tab.fileSelection, delta) }
            }
            Key.Enter, Key.NumPadEnter, Key.Tab -> pickSelected()
            else -> return false
        }
        return true
    }

    /** The keyboard's Send, which Chrome hands the page as a Return: it picks while a picker is shown. */
    fun send(): Boolean {
        if (slash == null && files == null) return false
        if (slash == null || slash.items.isNotEmpty()) pickSelected()
        return true
    }

    /** A row tapped or clicked: like Chrome's button, it keeps focus in the composer. */
    fun pick(item: SlashItem) = model.pickSlash(tab, item, raiseKeyboard = true)

    fun pick(entry: FileEntry) = tab.pickFile(entry.path, raiseKeyboard = true)

    // A row past the end of a list that shrank under it picks nothing, and the key is still taken.
    private fun pickSelected() {
        if (slash != null) {
            slash.items.getOrNull(tab.slashSelection)?.let { model.pickSlash(tab, it, raiseKeyboard = false) }
        } else {
            files?.items?.getOrNull(tab.fileSelection)?.let { tab.pickFile(it.path, raiseKeyboard = false) }
        }
    }
}

/**
 * The pickers of [tab]'s box. The slash list is read when the box shows, every half hour while it
 * does and as the picker opens (`loadSlashItems`); the file list is kept current while its picker
 * is open (`openIndexReader`), and a project too long to send is searched on the server as the
 * query changes (`useRemoteFileSearch`).
 */
@Composable
fun rememberComposerPickers(model: AppModel, tab: ChatTab): ComposerPickers {
    val slashStore = model.slashItems
    // Read again for a provider picked, or resolved, before the session: the list is the provider's.
    LaunchedEffect(tab, slashStore, tab.providerId, tab.providerPending) {
        if (slashStore == null) return@LaunchedEffect
        launch { snapshotFlow { tab.slashQuery != null }.drop(1).filter { it }.collect { model.loadSlashItems(tab) } }
        while (true) {
            model.loadSlashItems(tab)
            delay(SlashItemsStore.LIFETIME_MS)
        }
    }
    val revision = slashStore?.revision?.collectAsState()?.value
    val slashQuery = tab.slashQuery
    val slashKind = tab.slashKind
    val slash = remember(slashStore, revision, slashQuery, slashKind, tab.providerId) {
        val payload = slashStore?.payload(tab.project, tab.providerId)
        if (slashQuery == null || payload == null) {
            null
        } else {
            SlashPickerList(payload.items, slashQuery, payload.recentNames, slashKind).takeIf { it.anyMatch }
        }
    }

    val fileStore = model.fileIndex
    val fileQuery = tab.fileQuery
    val fileOpen = fileQuery != null
    DisposableEffect(fileStore, fileOpen) {
        val close = if (fileOpen) fileStore?.openReader(tab.project) else null
        onDispose { close?.invoke() }
    }
    val index = fileStore?.state?.collectAsState()?.value
    val searchRemote = fileOpen && index?.remote == true
    var remote by remember(tab) { mutableStateOf(emptyList<FileEntry>()) }
    LaunchedEffect(fileStore, fileQuery, searchRemote, index?.revision) {
        if (fileStore == null || fileQuery == null || !searchRemote) {
            remote = emptyList()
            return@LaunchedEffect
        }
        // A failed search keeps what is shown.
        fileStore.searchRemote(fileQuery)?.let { remote = it }
    }
    val files = remember(index, remote, fileQuery) {
        if (fileQuery == null || index == null) {
            null
        } else {
            val entries = if (index.remote) remote else if (index.project == tab.project) index.entries else emptyList()
            FilePickerList(entries, fileQuery).takeIf { it.items.isNotEmpty() }
        }
    }
    return remember(slash, files, tab) { ComposerPickers(slash, files, model, tab) }
}

/** Tailwind's `emerald-500`, as Chrome draws its `oklch()` in sRGB. */
internal val EMERALD_500 = Rgba(0.0, 188.39 / 255, 124.77 / 255, 1.0)

/** The section headers' `text-[10px] font-medium uppercase tracking-wider`. */
private fun sectionHeaderStyle(color: Color) = ppmText(10f, FontWeight.Medium, lineHeight = 15f).copy(color = color, letterSpacing = 0.05.em)

/**
 * `HighlightMatch` (`composer-picker-parts.tsx`): [text] (preceded by optional [prefix] such as `/`)
 * with the first case-insensitive substring occurrence of [query] styled in [matchColor] semibold
 * and no background. Fuzzy non-substring matches remain unmarked.
 */
fun highlightMatchAnnotated(
    text: String,
    query: String,
    matchColor: Color,
    prefix: String = "",
): AnnotatedString {
    val at = if (query.isNotEmpty()) text.indexOf(query, ignoreCase = true) else -1
    if (at < 0) return AnnotatedString(prefix + text)
    val start = prefix.length + at
    val end = start + query.length
    return buildAnnotatedString {
        append(prefix)
        append(text)
        addStyle(SpanStyle(color = matchColor, fontWeight = FontWeight.SemiBold), start, end)
        addStringAnnotation(tag = "mark", annotation = text.substring(at, at + query.length), start = start, end = end)
    }
}

/**
 * `StartEllipsis` (`start-ellipsis.tsx`): ellipsizes from the start (`…suffix`) when [text] exceeds
 * the available width so the distinguishing end of a directory survives while keeping left-to-right
 * character order and preserving [highlightMatchAnnotated] spans inside the surviving suffix.
 */
@Composable
fun StartEllipsisText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxW = constraints.maxWidth
        val shown = remember(text, style, maxW) {
            if (text.isEmpty() || maxW == Constraints.Infinity) {
                text
            } else if (measurer.measure(text, style, maxLines = 1, softWrap = false).size.width <= maxW) {
                text
            } else {
                var lo = 1
                var hi = text.length
                var best: AnnotatedString? = null
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    val candidate = buildAnnotatedString {
                        append("…")
                        append(text.subSequence(mid, text.length))
                    }
                    if (measurer.measure(candidate, style, maxLines = 1, softWrap = false).size.width <= maxW) {
                        best = candidate
                        hi = mid - 1
                    } else {
                        lo = mid + 1
                    }
                }
                best ?: AnnotatedString("…")
            }
        }
        BasicText(
            shown,
            modifier = Modifier
                .testTag("start-ellipsis")
                .semantics { this[StartEllipsisShownKey] = shown.text },
            style = style,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * The open picker (`SlashCommandPicker` or `FilePicker`, never both).
 * By default floats above the composer (`PickerVariant.FLOATING`).
 */
@Composable
fun ComposerPickerList(
    model: AppModel,
    tab: ChatTab,
    pickers: ComposerPickers,
    narrow: Boolean = false,
    variant: PickerVariant = PickerVariant.FLOATING,
    modifier: Modifier = Modifier,
) {
    pickers.slash?.let { SlashPicker(model, tab, it, pickers, narrow, variant, modifier) }
    pickers.files?.let { FilePicker(tab, it, pickers, narrow, variant, modifier) }
}

/**
 * `PickerPanel` (`composer-picker-parts.tsx`):
 * - `FLOATING`: `popover-solid footer-pop-in max-h-[min(320px,55vh)] origin-bottom rounded-xl border border-border shadow-[var(--shadow-float)]`
 * - `STATIC`: `max-h-[340px] border-b border-border bg-surface`
 */
@Composable
internal fun PickerPanel(
    variant: PickerVariant = PickerVariant.FLOATING,
    label: String,
    header: @Composable () -> Unit,
    footer: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val reducedMotion = rememberPrefersReducedMotion()
    val animateIn = variant == PickerVariant.FLOATING && !reducedMotion
    val popProgress = remember(animateIn) { Animatable(if (animateIn) 0f else 1f) }
    LaunchedEffect(animateIn) {
        if (animateIn) {
            popProgress.animateTo(1f, tween(160, easing = PickerPopEasing))
        } else {
            popProgress.snapTo(1f)
        }
    }

    val taggedModifier = modifier
        .fillMaxWidth()
        .testTag("composer-picker")
        .semantics { stateDescription = variant.id }

    val panelModifier = if (variant == PickerVariant.FLOATING) {
        val screenHeight = LocalConfiguration.current.screenHeightDp.dp
        val maxHeight = minOf(320.dp, screenHeight * 0.55f)
        val shape = RoundedCornerShape(12.dp)
        val p = popProgress.value
        taggedModifier
            .heightIn(max = maxHeight)
            .graphicsLayer {
                alpha = p
                translationY = (1f - p) * 6.dp.toPx()
                val s = 0.98f + 0.02f * p
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
            .ppmShadow(tokens.shadowFloat, shape)
            .clip(shape)
            .background(tokens.panel2.over(tokens.bgSolid).color)
            .cssBorder(tokens.border.color, shape)
    } else {
        taggedModifier
            .heightIn(max = 340.dp)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
    }

    Column(
        panelModifier
            .focusProperties { canFocus = false }
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        header()
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .testTag("composer-picker-listbox")
                .semantics { contentDescription = label },
        ) {
            content()
        }
        footer?.invoke()
    }
}

/** `PickerHeader` (`composer-picker-parts.tsx`): `min-h-10 gap-2 border-b border-border-soft py-1.5 pr-1.5 pl-2.5 max-md:py-2`. */
@Composable
internal fun PickerHeader(
    verticalPadding: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val vPad = if (!verticalPadding) 0.dp else if (mobile) 8.dp else 6.dp
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = if (mobile) 44.dp else 40.dp)
            .cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom)
            .padding(start = 10.dp, end = 6.dp, top = vPad, bottom = vPad),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * `PickerKeyHints` (`composer-picker-parts.tsx`):
 * `↑↓ move · ↵ insert · Tab complete · Esc close`, hidden when narrow (`<= 420dp`) or touch/mobile.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PickerKeyHints(narrow: Boolean) {
    if (narrow || LocalPpmMobile.current) return
    val tokens = LocalPpmTokens.current
    FlowRow(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 30.dp)
            .cssBorder(tokens.borderSoft.color, sides = CssSides.Top)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("picker-key-hints"),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        KeyHintItem(keys = listOf("↑", "↓"), label = "move")
        KeyHintItem(keys = listOf("↵"), label = "insert")
        KeyHintItem(keys = listOf("Tab"), label = "complete")
        KeyHintItem(keys = listOf("Esc"), label = "close")
    }
}

@Composable
private fun KeyHintItem(keys: List<String>, label: String) {
    val tokens = LocalPpmTokens.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (k in keys) {
            KeyCap(k)
        }
        BasicText(
            " $label",
            style = ppmText(10.5f, lineHeight = 18f).copy(color = tokens.text3.color),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun KeyCap(label: String) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .padding(end = 2.dp)
            .height(16.dp)
            .defaultMinSize(minWidth = 16.dp)
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(tokens.borderSoft.color, shape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = ppmText(10f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.text2.color),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * `scrollIntoView({ block: "nearest" })` on the [index]th item: no further than shows all of it,
 * lined up with the edge it was past, the list's own padding left out of sight as Chrome leaves it.
 */
private suspend fun LazyListState.revealNearest(index: Int) {
    val before = layoutInfo
    if (index !in 0 until before.totalItemsCount) return
    val seen = before.visibleItemsInfo.firstOrNull { it.index == index }
    val below = if (seen != null) seen.offset + seen.size > before.viewportEndOffset else index > (before.visibleItemsInfo.lastOrNull()?.index ?: -1)
    if (seen == null) scrollToItem(index)
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val by = when {
        below -> item.offset + item.size - info.viewportEndOffset
        seen == null || item.offset < info.viewportStartOffset -> item.offset - info.viewportStartOffset
        else -> 0
    }
    if (by != 0) scrollBy(by.toFloat())
}

/**
 * `SlashCommandPicker` (`slash-command-picker.tsx`): kind filter tabs + Refresh in the header,
 * Recent / All groups or fuzzy results in the body, key hints in the footer.
 */
@Composable
private fun SlashPicker(
    model: AppModel,
    tab: ChatTab,
    list: SlashPickerList,
    pickers: ComposerPickers,
    narrow: Boolean,
    variant: PickerVariant,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { tab.slashSelection }.drop(1).collect { listState.revealNearest(it) }
    }

    PickerPanel(
        variant = variant,
        label = "Skills and commands",
        modifier = modifier,
        header = {
            // On mobile, children carry 8dp top/bottom hit area around 28dp pills = 44dp touch target.
            PickerHeader(verticalPadding = !mobile) {
                Row(
                    Modifier
                        .weight(1f, fill = false)
                        .horizontalScroll(rememberScrollState())
                        .semantics { contentDescription = "Filter by kind" },
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (filter in SlashKindFilter.entries) {
                        SlashKindTab(
                            filter = filter,
                            active = list.kind == filter,
                            onClick = { tab.pickSlashKind(filter) },
                        )
                    }
                }
                if (tab.project.isNotEmpty()) {
                    Spacer(Modifier.weight(1f))
                    SlashRefreshButton(
                        refreshing = tab.slashRefreshing,
                        onClick = {
                            if (!tab.slashRefreshing) model.refreshSlashItems(tab)
                        },
                    )
                }
            }
        },
        footer = { PickerKeyHints(narrow) },
    ) {
        if (list.items.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    "No skill or command matches.",
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .cssScrollbar(listState),
                state = listState,
                contentPadding = PaddingValues(4.dp),
            ) {
                itemsIndexed(list.items, key = { i, item -> "$i:${item.type}:${item.name}" }) { index, item ->
                    Column(Modifier.fillMaxWidth()) {
                        if (list.recentCount > 0 && index == 0) {
                            SlashSectionHeader(label = "RECENT", withClock = true)
                        }
                        if (list.recentCount > 0 && index == list.recentCount) {
                            SlashSectionHeader(label = "ALL", withClock = false)
                        }
                        SlashCommandPickerRow(
                            item = item,
                            index = index,
                            selected = index == tab.slashSelection,
                            query = list.query,
                            narrow = narrow,
                            onSelect = { tab.slashSelection = index },
                            onPick = { pickers.pick(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SlashKindTab(
    filter: SlashKindFilter,
    active: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val lit = colorsFade(!mobile && hovered && !active).toDouble()
    val pillHeight = if (mobile) 28.dp else 26.dp
    val hPad = if (mobile) 6.dp else 5.dp
    val pillBg = if (active) tokens.accentWash.color else tokens.surfaceHover.opacity(lit).color
    val textColor = if (active) tokens.accent.color else tokens.text.mix(tokens.text2, lit).color
    val weight = if (active) FontWeight.Medium else FontWeight.Normal

    Box(
        Modifier
            .height(if (mobile) 44.dp else 26.dp)
            .focusProperties { canFocus = false }
            .hoverable(interactions)
            .clickable(
                interactionSource = interactions,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics { selected = active }
            .testTag("slash-kind-tab:${filter.id}"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(pillHeight)
                .clip(RoundedCornerShape(999.dp))
                .background(pillBg)
                .padding(horizontal = hPad),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                filter.label,
                style = ppmText(12f, weight, lineHeight = 16f).copy(color = textColor),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun SlashRefreshButton(
    refreshing: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val lit = colorsFade(!mobile && hovered).toDouble()

    Box(
        Modifier
            .size(if (mobile) 44.dp else 28.dp)
            .focusProperties { canFocus = false }
            .hoverable(interactions)
            .clickable(
                interactionSource = interactions,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .webTitle("Refresh skill list")
            .semantics { contentDescription = "Refresh skill list" }
            .testTag("slash-refresh"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(tokens.surfaceHover.opacity(lit).color),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(
                ProductIcon.RefreshCw,
                14.dp,
                tokens.text.mix(tokens.text3, lit).color,
                spinning = refreshing,
            )
        }
    }
}

@Composable
private fun SlashSectionHeader(label: String, withClock: Boolean) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (withClock) {
            PpmIcon(ProductIcon.Clock, 12.dp, tokens.text3.color)
        }
        BasicText(label, style = sectionHeaderStyle(tokens.text3.color), maxLines = 1, softWrap = false)
    }
}

/**
 * `SlashCommandPickerRow` (`slash-command-picker-row.tsx`):
 * `grid min-h-11 grid-cols-[24px_minmax(0,1fr)_auto] items-center gap-2.5 rounded-lg px-2 py-1.5`.
 */
@Composable
private fun SlashCommandPickerRow(
    item: SlashItem,
    index: Int,
    selected: Boolean,
    query: String,
    narrow: Boolean,
    onSelect: () -> Unit,
    onPick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) onSelect()
    }
    val lit = colorsFade(!mobile && hovered && !selected).toDouble()
    val bg = if (selected) tokens.accentWash.color else tokens.surfaceHover.opacity(lit).color
    val shape = RoundedCornerShape(8.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .clip(shape)
            .background(bg)
            .focusProperties { canFocus = false }
            .hoverable(interactions)
            .clickable(
                interactionSource = interactions,
                indication = null,
                onClick = {
                    onSelect()
                    onPick()
                },
            )
            .semantics { this.selected = selected }
            .testTag("slash-picker-row:$index")
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SlashKindTile(item)
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            SlashNameLine(
                item = item,
                query = query,
                hideHint = narrow || mobile,
            )
            if (item.description.isNotEmpty()) {
                val maxLines = if (selected) 2 else 1
                val descColor = if (selected) tokens.text2.color else tokens.text3.color
                BasicText(
                    item.description,
                    modifier = Modifier
                        .testTag("slash-desc:${item.name}")
                        .semantics { this[SlashDescMaxLinesKey] = maxLines },
                    style = ppmText(12f, lineHeight = 16f).copy(color = descColor),
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val scopeLabel = slashScopeLabel(item)
        if (scopeLabel.isNotEmpty()) {
            val pillShape = RoundedCornerShape(999.dp)
            Box(
                Modifier
                    .align(Alignment.Top)
                    .padding(top = 3.dp)
                    .clip(pillShape)
                    .cssBorder(tokens.borderSoft.color, pillShape)
                    .padding(horizontal = 7.dp, vertical = 1.dp)
                    .testTag("slash-scope:${item.name}"),
            ) {
                BasicText(
                    scopeLabel,
                    style = ppmText(10f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.text3.color),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun SlashNameLine(
    item: SlashItem,
    query: String,
    hideHint: Boolean,
) {
    val tokens = LocalPpmTokens.current
    val invocation = highlightMatchAnnotated(
        text = item.name,
        query = query,
        matchColor = tokens.accent.color,
        prefix = item.invokeSigil ?: "/",
    )
    val display = item.displayName?.takeIf { it.isNotEmpty() && it != item.name }
    val hint = item.argumentHint?.takeIf { it.isNotEmpty() && !hideHint }

    Layout(
        content = {
            TruncatedText(
                invocation,
                ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text.color),
                Modifier.testTag("slash-name:${item.name}"),
            )
            if (display != null) {
                TruncatedText(
                    AnnotatedString(display),
                    ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                )
            }
            if (hint != null) {
                TruncatedText(
                    AnnotatedString(hint),
                    ppmText(11f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text3.color),
                    Modifier.testTag("slash-arg-hint:${item.name}"),
                )
            }
        },
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val natural = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val room = (constraints.maxWidth - gap * (measurables.size - 1)).coerceAtLeast(0)
        val total = natural.sum()
        val widths = if (total <= room || total == 0) {
            natural.map { it.coerceAtMost(room) }
        } else {
            natural.map { (it.toLong() * room / total).toInt() }
        }
        val parts = measurables.mapIndexed { i, m -> m.measure(Constraints(maxWidth = widths[i])) }
        val above = parts.maxOf { it[FirstBaseline] }
        val height = above + parts.maxOf { it.height - it[FirstBaseline] }
        layout(constraints.maxWidth, height) {
            var x = 0
            for (part in parts) {
                part.place(x, above - part[FirstBaseline])
                x += part.width + gap
            }
        }
    }
}

/**
 * `TILE` in `slash-command-picker-row.tsx`:
 * - `skill`: `bg-accent-2/15 text-accent-2` + `Sparkles`
 * - `agent`: `bg-info/15 text-info` + `Bot`
 * - `builtin`: `bg-success/15 text-success` + `Zap`
 * - `command`: `bg-surface-hover text-text-2` + `SquareTerminal`
 * Runtime-supplied `iconDataUri` renders inside the same `24dp` tinted tile.
 */
@Composable
private fun SlashKindTile(item: SlashItem) {
    val tokens = LocalPpmTokens.current
    val (tileBg, iconTint, icon, tileKind) = when (item.type) {
        "skill" -> TileSpec(tokens.accent2.opacity(0.15).color, tokens.accent2.color, ProductIcon.Sparkles, "skill")
        "agent" -> TileSpec(tokens.info.opacity(0.15).color, tokens.info.color, ProductIcon.Bot, "agent")
        "builtin" -> TileSpec(tokens.success.opacity(0.15).color, tokens.success.color, ProductIcon.Zap, "builtin")
        else -> TileSpec(tokens.surfaceHover.color, tokens.text2.color, ProductIcon.SquareTerminal, "command")
    }
    val art = item.iconDataUri?.takeIf { it.isNotEmpty() }
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier
            .size(24.dp)
            .clip(shape)
            .background(tileBg)
            .testTag("slash-tile:${item.name}")
            .semantics { this[SlashTileKindKey] = tileKind },
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            DataUriIcon(art)
        } else {
            PpmIcon(icon, 14.dp, iconTint)
        }
    }
}

private data class TileSpec(
    val bg: Color,
    val fg: Color,
    val icon: ProductIcon,
    val kind: String,
)

/** Decoded once per picture and size: rows come and go as the list scrolls. */
private val decodedIcons = LruCache<Pair<String, Int>, ImageBitmap>(32)

/**
 * A runtime's own artwork (`<img class="size-4 object-contain">` of a data URI), fitted into the
 * box. An SVG is drawn as Chrome draws one in an `<img>`, a document of its own, so its
 * `currentColor` is black whatever the theme. One that does not decode leaves the box empty, as
 * a broken `alt=""` image does.
 */
@Composable
private fun DataUriIcon(uri: String) {
    val px = with(LocalDensity.current) { 16.dp.roundToPx() }
    val bitmap = remember(uri, px) { decodedIcons.get(uri to px) ?: decodeIcon(uri, px)?.also { decodedIcons.put(uri to px, it) } }
    Box(
        Modifier
            .size(16.dp)
            .testTag("slash-data-uri-icon")
            .semantics { stateDescription = uri },
    ) {
        if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

/** The server's `data:<type>;base64,…` (`readIconDataUri`): an SVG drawn at [px] square, or a PNG, WebP or JPEG. */
private fun decodeIcon(uri: String, px: Int): ImageBitmap? {
    val comma = uri.indexOf(',')
    if (!uri.startsWith("data:") || comma < 0 || !uri.substring(0, comma).endsWith(";base64")) return null
    return try {
        val bytes = Base64.decode(uri.substring(comma + 1), Base64.DEFAULT)
        if (uri.startsWith("data:image/svg+xml")) {
            val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            SVG.getFromInputStream(bytes.inputStream()).renderToCanvas(Canvas(bitmap), RenderOptions.create().viewPort(0f, 0f, px.toFloat(), px.toFloat()))
            bitmap.asImageBitmap()
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * `FilePicker` (`file-picker.tsx`): `"Files"` + match count in the header, basename + start-ellipsized
 * directory in each row (wrapping under the basename with 44dp min height on narrow/mobile), and
 * key hints in the footer.
 */
@Composable
private fun FilePicker(
    tab: ChatTab,
    list: FilePickerList,
    pickers: ComposerPickers,
    narrow: Boolean,
    variant: PickerVariant,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val compactRow = narrow || mobile
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { tab.fileSelection }.drop(1).collect { listState.revealNearest(it) }
    }

    val countText = "${list.totalCount} match${if (list.totalCount == 1) "" else "es"}"
    val headerRight = if (list.query.isNotEmpty()) "$countText for “${list.query}”" else countText

    PickerPanel(
        variant = variant,
        label = "Files",
        modifier = modifier,
        header = {
            PickerHeader {
                BasicText(
                    "Files",
                    style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(Modifier.weight(1f))
                BasicText(
                    headerRight,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .testTag("file-picker-count"),
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        footer = { PickerKeyHints(narrow) },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .cssScrollbar(listState),
            state = listState,
            contentPadding = PaddingValues(4.dp),
        ) {
            itemsIndexed(list.items, key = { _, entry -> entry.path }) { index, entry ->
                FilePickerRow(
                    entry = entry,
                    index = index,
                    selected = index == tab.fileSelection,
                    query = list.query,
                    compactRow = compactRow,
                    onSelect = { tab.fileSelection = index },
                    onPick = { pickers.pick(entry) },
                )
            }
        }
    }
}

@Composable
private fun FilePickerRow(
    entry: FileEntry,
    index: Int,
    selected: Boolean,
    query: String,
    compactRow: Boolean,
    onSelect: () -> Unit,
    onPick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) onSelect()
    }
    val lit = colorsFade(!mobile && hovered && !selected).toDouble()
    val bg = if (selected) tokens.accentWash.color else tokens.surfaceHover.opacity(lit).color
    val shape = RoundedCornerShape(8.dp)
    val split = remember(entry.path, entry.isDirectory) { splitFilePath(entry.path, entry.isDirectory) }
    val nameText = remember(split.name, query, tokens.accent.color) {
        highlightMatchAnnotated(split.name, query, tokens.accent.color)
    }
    val dirText = remember(split.dir, query, tokens.accent.color) {
        highlightMatchAnnotated(split.dir, query, tokens.accent.color)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = if (compactRow) 44.dp else 36.dp)
            .clip(shape)
            .background(bg)
            .focusProperties { canFocus = false }
            .hoverable(interactions)
            .clickable(
                interactionSource = interactions,
                indication = null,
                onClick = {
                    onSelect()
                    onPick()
                },
            )
            .webTitle(entry.path)
            .semantics { this.selected = selected }
            .testTag("file-picker-row:$index")
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .testTag("file-icon:$index")
                .semantics { stateDescription = if (entry.isDirectory) "directory" else "file" },
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(
                if (entry.isDirectory) ProductIcon.Folder else ProductIcon.File,
                14.dp,
                (if (entry.isDirectory) tokens.warning else tokens.text3).color,
            )
        }
        if (compactRow) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    nameText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("file-name:$index"),
                    style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (split.dir.isNotEmpty()) {
                    StartEllipsisText(
                        text = dirText,
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("file-dir:$index"),
                    )
                }
            }
        } else {
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    nameText,
                    modifier = Modifier.testTag("file-name:$index"),
                    style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (split.dir.isNotEmpty()) {
                    StartEllipsisText(
                        text = dirText,
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("file-dir:$index"),
                    )
                }
            }
        }
    }
}
