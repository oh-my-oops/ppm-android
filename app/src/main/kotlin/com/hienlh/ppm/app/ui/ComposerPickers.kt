package com.hienlh.ppm.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.ui.tablet.Edge
import com.hienlh.ppm.app.ui.tablet.edge
import com.hienlh.ppm.chat.sky500
import com.hienlh.ppm.design.ClampedText
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.cssScrollbar
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.FileEntry
import com.hienlh.ppm.protocol.SlashItem
import com.hienlh.ppm.state.FilePickerList
import com.hienlh.ppm.state.SlashItemsStore
import com.hienlh.ppm.state.SlashPickerList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * What the composer's open picker lists (`SlashCommandPicker`'s `displayItems`, `FilePicker`'s
 * `filtered`), and the keys it takes. A picker with nothing to list is not shown, and takes none.
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
    /**
     * The shown picker's keydown, heard before the box's (`handleKeyDown`): ↑/↓ go round its rows,
     * Return and Tab pick the highlighted one, Escape closes it — with any modifier, as on the web.
     */
    fun key(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || (slash == null && files == null)) return false
        when (event.key) {
            Key.DirectionUp, Key.DirectionDown -> {
                val delta = if (event.key == Key.DirectionUp) -1 else 1
                if (slash != null) tab.slashSelection = slash.step(tab.slashSelection, delta) else files?.let { tab.fileSelection = it.step(tab.fileSelection, delta) }
            }
            Key.Enter, Key.NumPadEnter, Key.Tab -> pickSelected()
            Key.Escape -> tab.closePickers()
            else -> return false
        }
        return true
    }

    /** The keyboard's Send, which Chrome hands the page as a Return: it picks while a picker is shown. */
    fun send(): Boolean {
        if (slash == null && files == null) return false
        pickSelected()
        return true
    }

    /** A row tapped or clicked: like Chrome's button, it took the focus, so the keyboard comes back. */
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
    val slash = remember(slashStore, revision, slashQuery, tab.providerId) {
        val payload = slashStore?.payload(tab.project, tab.providerId)
        if (slashQuery == null || payload == null) null else SlashPickerList(payload.items, slashQuery, payload.recentNames).takeIf { it.items.isNotEmpty() }
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

/** The headers' `text-[10px] font-medium uppercase tracking-wider`. */
private fun headerText(color: Color) = ppmText(10f, FontWeight.Medium, lineHeight = 15f).copy(color = color, letterSpacing = 0.05.em)

/**
 * The open picker, in the flow between the toolbar and the message box (chat-tab.tsx):
 * `SlashCommandPicker` or `FilePicker`, never both.
 */
@Composable
fun ComposerPickerList(model: AppModel, tab: ChatTab, pickers: ComposerPickers) {
    pickers.slash?.let { SlashPicker(model, tab, it, pickers) }
    pickers.files?.let { FilePicker(tab, it, pickers) }
}

/**
 * `max-h-52 overflow-y-auto border-b border-border bg-surface` around a `py-1` list. The
 * highlighted row is scrolled to whenever it changes, by the keys, a mouse or a new query, but
 * not as the picker opens: that shows the top, whatever is highlighted.
 */
@Composable
private fun PickerBox(selection: () -> Int, content: LazyListScope.() -> Unit) {
    val tokens = LocalPpmTokens.current
    val list = rememberLazyListState()
    LaunchedEffect(list) { snapshotFlow(selection).drop(1).collect { list.revealNearest(it) } }
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 208.dp).background(tokens.panel.color).edge(Edge.Bottom, tokens.border.color).cssScrollbar(list),
        list,
        PaddingValues(vertical = 4.dp),
        content = content,
    )
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
 * A row (`transition-colors`): highlighted in `bg-primary/10`, its text in the accent; else
 * `hover:bg-surface-hover` under a mouse, which highlights it as well (`onMouseEnter`). A tap
 * highlights it too, as Chrome's compatibility mouse events do, before it picks.
 */
@Composable
private fun PickerRow(selected: Boolean, select: () -> Unit, pick: () -> Unit, padding: PaddingValues, content: @Composable (text: Color) -> Unit) {
    val tokens = LocalPpmTokens.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) select() }
    val picked = colorsFade(selected).toDouble()
    val lit = colorsFade(hovered && !selected).toDouble()
    Box(
        Modifier
            .fillMaxWidth()
            .background(tokens.accent.opacity(0.1).mix(tokens.surfaceHover.opacity(lit), picked).color)
            .ppmClickable(source = source) {
                select()
                pick()
            }
            .padding(padding),
    ) {
        content(tokens.accent.mix(tokens.text, picked).color)
    }
}

/**
 * `SlashCommandPicker`: with nothing typed after the `/`, the recently picked under "Recent" (and
 * its Refresh), the rest under "All".
 */
@Composable
private fun SlashPicker(model: AppModel, tab: ChatTab, list: SlashPickerList, pickers: ComposerPickers) {
    PickerBox({ tab.slashSelection }) {
        itemsIndexed(list.items) { index, item ->
            Column {
                if (list.recentCount > 0 && index == 0) RecentHeader(model, tab)
                if (list.recentCount > 0 && index == list.recentCount) AllHeader()
                PickerRow(index == tab.slashSelection, { tab.slashSelection = index }, { pickers.pick(item) }, PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { text ->
                    SlashRow(item, text)
                }
            }
        }
    }
}

/** "Recent" with a clock (`flex items-center justify-between px-3 pt-1 pb-0.5`), Refresh at its end. */
@Composable
private fun RecentHeader(model: AppModel, tab: ChatTab) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            PpmIcon(ProductIcon.Clock, 12.dp, tokens.text3.color)
            BasicText("RECENT", style = headerText(tokens.text3.color))
        }
        // `text-text-subtle hover:text-text-primary p-0.5`: the arrows alone, turning while the server reads skills again.
        val hover = rememberHover()
        Box(
            Modifier
                .pressable(pressedColor = Color.Transparent, source = hover.source) { model.refreshSlashItems(tab) }
                .webTitle("Refresh skill list")
                .semantics { contentDescription = "Refresh skill list" }
                .padding(2.dp),
        ) {
            PpmIcon(ProductIcon.RefreshCw, 12.dp, tokens.text.mix(tokens.text3, hover.lit.toDouble()).color, spinning = tab.slashRefreshing)
        }
    }
}

/**
 * "All" (`px-3 pt-1.5 pb-0.5`): a block, so its line is the page's 16 px text on 24 px lines,
 * the label on that line's baseline as Chrome rounds it in device pixels.
 */
@Composable
private fun AllHeader() {
    val tokens = LocalPpmTokens.current
    val density = LocalDensity.current.density
    val (up, down) = PpmFonts.chromeAscentDescent(16f * density, PpmFonts.SANS_ASCENT, PpmFonts.SANS_DESCENT)
    val baseline = 6f * density + floor((24f * density - up - down) / 2) + up
    Layout({ BasicText("ALL", style = headerText(tokens.text3.color)) }, Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 12.dp)) { measurables, constraints ->
        val label = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) { label.place(0, (baseline - label[FirstBaseline]).roundToInt()) }
    }
}

/** `flex items-start gap-3`: the item's icon (`mt-0.5`), then its title line and up to two lines of description. */
@Composable
private fun SlashRow(item: SlashItem, nameColor: Color) {
    val tokens = LocalPpmTokens.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 2.dp)) { SlashIcon(item) }
        Column(Modifier.weight(1f)) {
            SlashTitle(item, nameColor)
            if (item.description.isNotEmpty()) {
                ClampedText(AnnotatedString(item.description), ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color), maxLines = 2, Modifier.padding(top = 2.dp))
            }
        }
    }
}

/**
 * The name with its sigil, the runtime's label for it, its argument hint and where it comes from,
 * on one baseline (`flex items-baseline gap-2`). When they do not fit, the first three shrink in
 * proportion to their widths, each cut with "…", and the source keeps its own at the far end.
 */
@Composable
private fun SlashTitle(item: SlashItem, nameColor: Color) {
    val tokens = LocalPpmTokens.current
    val small = ppmText(12f, lineHeight = 16f)
    Layout(
        content = {
            TruncatedText(AnnotatedString(item.invocation), ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = nameColor))
            item.displayName?.takeIf { it.isNotEmpty() && it != item.name }?.let { TruncatedText(AnnotatedString(it), small.copy(color = tokens.text2.color)) }
            item.argumentHint?.takeIf { it.isNotEmpty() }?.let { TruncatedText(AnnotatedString(it), small.copy(color = tokens.text3.color)) }
            // `capitalize`.
            BasicText(item.sourceLabel.replaceFirstChar { it.titlecase() }, style = small.copy(color = tokens.text3.color), maxLines = 1, softWrap = false)
        },
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val label = measurables.last().measure(Constraints())
        val shrinking = measurables.dropLast(1)
        val natural = shrinking.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val room = (constraints.maxWidth - label.width - gap * shrinking.size).coerceAtLeast(0)
        val total = natural.sum()
        val widths = if (total <= room) natural else natural.map { (it.toLong() * room / total).toInt() }
        val parts = shrinking.mapIndexed { i, m -> m.measure(Constraints(maxWidth = widths[i])) }
        val above = (parts + label).maxOf { it[FirstBaseline] }
        val height = above + (parts + label).maxOf { it.height - it[FirstBaseline] }
        layout(constraints.maxWidth, height) {
            var x = 0
            for (part in parts) {
                part.place(x, above - part[FirstBaseline])
                x += part.width + gap
            }
            label.place(maxOf(x, constraints.maxWidth - label.width), above - label[FirstBaseline])
        }
    }
}

/** The runtime's own artwork, else one by kind: a built-in's bolt, a skill's sparkles, an agent's bot, a command's prompt. */
@Composable
private fun SlashIcon(item: SlashItem) {
    val tokens = LocalPpmTokens.current
    val art = item.iconDataUri?.takeIf { it.isNotEmpty() }
    when {
        art != null -> DataUriIcon(art)
        item.type == "builtin" -> PpmIcon(ProductIcon.Zap, 16.dp, EMERALD_500.color)
        item.type == "skill" -> PpmIcon(ProductIcon.Sparkles, 16.dp, tokens.warning.color)
        item.type == "agent" -> PpmIcon(ProductIcon.Bot, 16.dp, sky500.color)
        else -> PpmIcon(ProductIcon.Terminal, 16.dp, tokens.accent.color)
    }
}

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
    Box(Modifier.size(16.dp)) {
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
 * `FilePicker`: a folder in amber or a file in the accent, its path cut with "…" (`flex
 * items-center gap-2 px-3 py-1.5`). One line always, so 32 tall: as a height rather than two
 * paddings, which round up apart (6 px is 15.75 at 2.625) and leave every row a pixel too tall.
 */
@Composable
private fun FilePicker(tab: ChatTab, list: FilePickerList, pickers: ComposerPickers) {
    val tokens = LocalPpmTokens.current
    PickerBox({ tab.fileSelection }) {
        itemsIndexed(list.items) { index, entry ->
            PickerRow(index == tab.fileSelection, { tab.fileSelection = index }, { pickers.pick(entry) }, PaddingValues(horizontal = 12.dp)) { text ->
                Row(Modifier.height(32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PpmIcon(if (entry.isDirectory) ProductIcon.Folder else ProductIcon.File, 16.dp, (if (entry.isDirectory) tokens.warning else tokens.accent).color)
                    TruncatedText(AnnotatedString(entry.path), ppmText(14f, lineHeight = 20f).copy(color = text), Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}
