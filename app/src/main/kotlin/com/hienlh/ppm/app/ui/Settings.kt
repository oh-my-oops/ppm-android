package com.hienlh.ppm.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.tablet.Edge
import com.hienlh.ppm.app.ui.tablet.edge
import com.hienlh.ppm.client.ServerProfile
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.PpmTheme
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.windows.WindowRuntimeState
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Settings as a tab: on a phone the category list over the pane, a row opening its pane and
 * Back returning to the list. Only the categories this app has are listed — Appearance's theme,
 * the servers it connects to, which the web has no need of, and Voice Input.
 */
@Composable
fun SettingsTab(model: AppModel) {
    val requested = model.requestedSettingsCategory
    val initial = requested?.let(SettingsCategory::resolveOrDefault)
    SettingsBody(model, initialCategory = initial, backReturnsToList = true)
}

/**
 * Settings in a floating window (settings-window-content.tsx): the open category rides on the
 * window's payload, so the window comes back on the pane it was left on.
 */
@Composable
fun SettingsWindowContent(model: AppModel, win: WindowRuntimeState) {
    val raw = win.payload["category"].string ?: model.requestedSettingsCategory
    val category = raw?.let(SettingsCategory::resolveOrDefault)
    SettingsBody(model, initialCategory = category, onCategoryChange = { next ->
        model.windows?.setPayload(win.id, buildJsonObject { put("category", next.id) })
    })
}

/**
 * `settings-body.tsx`, laid out by its own width as the web's `@container` does: from 640 dp the
 * categories sit in a rail beside the pane; narrower, the list covers the pane until a category
 * is picked. The pane stays mounted either way, so picking a category never rebuilds it.
 * [initialCategory] opens straight on its pane; [backReturnsToList] lets Android's Back bring the
 * list back over it.
 */
@Composable
internal fun SettingsBody(
    model: AppModel,
    initialCategory: SettingsCategory? = null,
    backReturnsToList: Boolean = false,
    onCategoryChange: (SettingsCategory) -> Unit = {},
) {
    val minimalMode = model.info?.minimalMode == true
    var category by rememberSaveable(initialCategory) {
        mutableStateOf(initialCategory ?: SettingsCategory.General)
    }
    var showList by rememberSaveable(initialCategory) { mutableStateOf(initialCategory == null) }
    val select: (SettingsCategory) -> Unit = {
        category = it
        model.requestedSettingsCategory = null
        // Also cleared from the rail: should the body narrow later, a stale list would cover the pane just picked.
        showList = false
        onCategoryChange(it)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val split = maxWidth >= SPLIT_WIDTH
        BackHandler(enabled = backReturnsToList && !split && !showList) { showList = true }
        Row(Modifier.fillMaxSize()) {
            if (split) SettingsRail(category, minimalMode, select)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                SettingsPane(model, category, split, onBack = { showList = true })
                if (showList && !split) SettingsIndex(minimalMode, select)
            }
        }
    }
}

/** `@[640px]`: the body's width from which the rail shows. */
private val SPLIT_WIDTH = 640.dp

enum class SettingsCategory(
    val id: String,
    val label: String,
    val subtitle: String,
    val icon: ProductIcon,
    val group: SettingsGroup,
    val minimalSafe: Boolean = false,
) {
    // Core
    General("general", "General", "Device name, screen wake, password, about", ProductIcon.Settings, SettingsGroup.Core, minimalSafe = true),
    Appearance("appearance", "Appearance", "Theme, tab style, word wrap, explorer skin", ProductIcon.Palette, SettingsGroup.Core, minimalSafe = true),
    Servers("servers", "Servers", "The PPM servers this app connects to", ProductIcon.HardDrive, SettingsGroup.Core, minimalSafe = true),
    Voice("voice", "Voice Input", "Speech to text for the chat box", ProductIcon.Mic, SettingsGroup.Core, minimalSafe = true),
    Shortcuts("shortcuts", "Keyboard Shortcuts", "Hardware keyboard bindings", ProductIcon.Code, SettingsGroup.Core, minimalSafe = true),
    Files("files", "File Filters", "Explorer & search exclude patterns", ProductIcon.FolderTree, SettingsGroup.Core, minimalSafe = false),

    // AI & Accounts
    Ai("ai", "AI Provider", "Default provider, models, effort & budgets", ProductIcon.Sparkles, SettingsGroup.Ai, minimalSafe = true),
    Tools("tools", "Tools", "Built-in PPM tools exposed to AI chats", ProductIcon.Wrench, SettingsGroup.Ai, minimalSafe = true),
    Accounts("accounts", "Accounts", "Claude & Codex accounts, OAuth, rotation", ProductIcon.Users, SettingsGroup.Ai, minimalSafe = true),
    Mcp("mcp", "MCP Servers", "Model Context Protocol servers & sign-in", ProductIcon.Plug, SettingsGroup.Ai, minimalSafe = true),
    AiResources("ai-resources", "AI Resources", "Skills, agents, commands & MCP configs", ProductIcon.Layers, SettingsGroup.Ai, minimalSafe = false),
    PpmBot("ppmbot", "PPMBot", "Telegram bot coordinator, tasks & memory", ProductIcon.BotMessageSquare, SettingsGroup.Ai, minimalSafe = false),
    Design("design", "Design", "Design Canvas apps, instructions & skills", ProductIcon.LayoutGrid, SettingsGroup.Ai, minimalSafe = false),

    // Advanced
    QueryAudit("query-audit", "Query Audit Log", "SQL history retention & size cap", ProductIcon.ScrollText, SettingsGroup.Advanced, minimalSafe = false),
    LanguageServers("language-servers", "Language Servers", "Editor code intelligence", ProductIcon.Code, SettingsGroup.Advanced, minimalSafe = false),
    DatabaseDrivers("database-drivers", "Database Drivers", "On-demand database engines", ProductIcon.Database, SettingsGroup.Advanced, minimalSafe = false),
    RemoteDesktop("remote-desktop", "Remote Desktop", "MediaMTX WebRTC relay", ProductIcon.Monitor, SettingsGroup.Advanced, minimalSafe = false),
    ;

    companion object {
        /** Resolves [raw] by category `id` or falls back to [General]. */
        fun resolveOrDefault(raw: String?): SettingsCategory {
            if (raw.isNullOrBlank()) return General
            return entries.firstOrNull { it.id.equals(raw.trim(), ignoreCase = true) } ?: General
        }
    }
}

/** `SETTINGS_GROUPS`, as far as this app's categories reach: the first has no heading. */
enum class SettingsGroup(val label: String?) {
    Core(null),
    Ai("AI & Accounts"),
    Integrations("Integrations"),
    Advanced("Advanced"),
    ;

    fun categories(minimalMode: Boolean = false): List<SettingsCategory> =
        SettingsCategory.entries.filter { it.group == this && (!minimalMode || it.minimalSafe) }
}

/** A group's heading, `text-[11px] font-medium uppercase tracking-wide text-muted-foreground`. */
@Composable
private fun GroupHeading(label: String, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    BasicText(
        label.uppercase(),
        modifier,
        style = ppmText(11f, FontWeight.Medium, lineHeight = 16.5f).copy(color = tokens.text2.color, letterSpacing = 0.025.em),
    )
}

/**
 * settings-category-rail.tsx: `w-[210px] border-r`, every category at once in `p-2 space-y-4`, a
 * group's rows `space-y-1` under its heading (`px-2 pt-1`).
 */
@Composable
private fun SettingsRail(active: SettingsCategory, minimalMode: Boolean, onSelect: (SettingsCategory) -> Unit) {
    val tokens = LocalPpmTokens.current
    Column(
        Modifier
            .width(210.dp)
            .fillMaxHeight()
            .edge(Edge.Right, tokens.border.color)
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        for (group in SettingsGroup.entries) {
            val cats = group.categories(minimalMode)
            if (cats.isEmpty()) continue
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                group.label?.let { GroupHeading(it, Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp)) }
                for (category in cats) {
                    val on = category == active
                    val shape = RoundedCornerShape(8.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .then(if (on) Modifier.background(tokens.panel2.color) else Modifier)
                            .pointerHoverIcon(WebCursor.Pointer)
                            .ppmClickable(
                                pressedColor = tokens.panel2.color,
                                hoverColor = if (on) Color.Transparent else tokens.panel2.opacity(0.5).color,
                                shape = shape,
                            ) { onSelect(category) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PpmIcon(category.icon, 16.dp, (if (on) tokens.text else tokens.text2).color)
                        BasicText(category.label, style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** `absolute inset-0 bg-background`: "Settings", then a row per category in `p-3 space-y-5`, each group under its heading (`px-1 pb-1`). */
@Composable
private fun SettingsIndex(minimalMode: Boolean, onSelect: (SettingsCategory) -> Unit) {
    val tokens = LocalPpmTokens.current
    Column(Modifier.fillMaxSize().background(tokens.bgSolid.color)) {
        BasicText(
            "Settings",
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            style = ppmText(14f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color),
        )
        Separator()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            for (group in SettingsGroup.entries) {
                val cats = group.categories(minimalMode)
                if (cats.isEmpty()) continue
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    group.label?.let { GroupHeading(it, Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp)) }
                    for (category in cats) {
                        val shape = RoundedCornerShape(12.dp)
                        val hover = rememberHover(instant = true)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .pointerHoverIcon(WebCursor.Pointer)
                                .ppmClickable(
                                    pressedColor = tokens.panel2.color,
                                    hoverColor = tokens.panel2.opacity(0.5).color,
                                    shape = shape,
                                    source = hover.source,
                                ) { onSelect(category) }
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(tokens.panel2.mix(tokens.panel, hover.lit.toDouble()).color),
                                contentAlignment = Alignment.Center,
                            ) { PpmIcon(category.icon, 16.dp, tokens.text2.color) }
                            Column(Modifier.weight(1f)) {
                                BasicText(category.label, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                BasicText(category.subtitle, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            PpmIcon(ProductIcon.ChevronRight, 16.dp, tokens.text2.color)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The pane: the category's icon and name (`px-2 py-2.5`, `px-4` beside the rail), then its
 * content in `p-4 pb-8`. Back to the list leads the row when there is no rail.
 */
@Composable
private fun SettingsPane(model: AppModel, category: SettingsCategory, split: Boolean, onBack: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (split) 16.dp else 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!split) {
                // The `ghost` button: `hover:bg-accent`, `dark:hover:bg-accent/50`, `cursor-pointer`.
                val ghost = ghostHover(tokens, isSystemInDarkTheme())
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .pointerHoverIcon(WebCursor.Pointer)
                        .ppmClickable(pressedColor = ghost, hoverColor = ghost, onClickLabel = "Back to settings list", onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { PpmIcon(ProductIcon.ArrowLeft, 16.dp, tokens.text.color) }
            }
            PpmIcon(category.icon, 16.dp, tokens.text2.color)
            BasicText(category.label, style = ppmText(14f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Separator()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp)) {
            when (category) {
                SettingsCategory.General -> GeneralSection(model)
                SettingsCategory.Appearance -> AppearanceSection(model)
                SettingsCategory.Servers -> ServersSection(model)
                SettingsCategory.Voice -> VoiceSection(model)
                SettingsCategory.Shortcuts -> KeyboardShortcutsSection(model)
                SettingsCategory.Files -> FilesSettingsSection(model)
                SettingsCategory.Ai -> AiProviderSettingsSection(model)
                SettingsCategory.Tools -> ToolsSettingsSection(model)
                SettingsCategory.Accounts -> AccountsSettingsSection(model)
                SettingsCategory.Mcp -> McpSettingsSection(model)
                SettingsCategory.AiResources -> AiResourcesSettingsSection(model)
                SettingsCategory.PpmBot -> PpmBotSettingsSection(model)
                SettingsCategory.Design -> DesignSettingsSection(model)
                SettingsCategory.QueryAudit -> QueryAuditSection(model)
                SettingsCategory.LanguageServers -> LanguageServersSection(model)
                SettingsCategory.DatabaseDrivers -> DatabaseDriversSection(model)
                SettingsCategory.RemoteDesktop -> RemoteDesktopSettingsSection(model)
            }
        }
    }
}

@Composable
internal fun Separator() {
    val tokens = LocalPpmTokens.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))
}

// Appearance

/** The theme section of `appearance-settings-section.tsx`: the six themes, then Light, Dark or System, plus custom themes, wrap, tab style & explorer skin. */
@Composable
private fun AppearanceSection(model: AppModel) {
    val tokens = LocalPpmTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText("Theme", style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color))
            val active = if (model.themeSelection.style == "custom") "" else model.theme.id
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in BuiltInThemes.all.chunked(2)) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (theme in row) {
                            ThemeCard(theme, theme.id == active, Modifier.weight(1f).fillMaxHeight()) { model.scope.launch { model.pickTheme(theme) } }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((mode, label, icon) in THEME_MODES) {
                    ModeButton(label, icon, model.themeSelection.mode == mode, isSystemInDarkTheme(), Modifier.weight(1f)) {
                        model.scope.launch { model.pickThemeMode(mode) }
                    }
                }
            }
        }
        Separator()
        AppearanceExtrasSection(model)
    }
}

/** `THEME_MODE_OPTIONS`. */
private val THEME_MODES = listOf(
    Triple("light", "Light", ProductIcon.Sun),
    Triple("dark", "Dark", ProductIcon.Moon),
    Triple("system", "System", ProductIcon.Monitor),
)

/**
 * `rounded-lg border p-2 min-h-11`: the swatch, the name and its mode; the active one ringed with
 * a check, any other `hover:bg-accent/40`. The 1 px border takes room, as CSS borders do, so the
 * padding is 9.
 */
@Composable
private fun ThemeCard(theme: PpmTheme, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .heightIn(min = 44.dp)
            .then(if (active) Modifier.ring(3.dp, tokens.accentWash.color, 12.dp) else Modifier)
            .clip(shape)
            .ppmClickable(
                pressedColor = tokens.panel2.opacity(0.4).color,
                hoverColor = if (active) Color.Transparent else tokens.panel2.opacity(0.4).color,
                shape = shape,
                onClick = onClick,
            )
            .cssBorder((if (active) tokens.accent else tokens.border).color, shape)
            .padding(8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeSwatch(theme)
            Column(Modifier.weight(1f)) {
                BasicText(
                    theme.name.replace(Regex(" (Dark|Light)$"), ""),
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(if (theme.isDark) "Dark" else "Light", style = ppmText(10f, lineHeight = 15f).copy(color = tokens.text3.color))
            }
        }
        // `absolute top-1 right-1`, from the padding box.
        if (active) PpmIcon(ProductIcon.Check, 14.dp, tokens.accent.color, Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp))
    }
}

/** `ThemeSwatch`: three 12x24 strips in a bordered, rounded box. */
@Composable
internal fun ThemeSwatch(theme: PpmTheme) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.clip(shape).cssBorder(tokens.border.color, shape)) {
        for (color in theme.swatch) Box(Modifier.width(12.dp).height(24.dp).background(color.color))
    }
}

/**
 * The web's `Button` in `default` (picked, with `ring-2 ring-primary`) or `outline`, `h-9 gap-2`.
 * The outline's `border` names no colour outside dark mode (`dark:border-input`), so in a light
 * theme it is Tailwind 4's default, the text colour. A mouse fades in `default`'s
 * `hover:bg-primary/90`, or the outline's own hover fill, over a `cursor-pointer`.
 */
@Composable
private fun ModeButton(label: String, icon: ProductIcon, on: Boolean, dark: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val content = if (on) tokens.accentFg else tokens.text
    val hover = rememberHover()
    Row(
        modifier
            .height(36.dp)
            .then(if (on) Modifier.ring(2.dp, tokens.accent.color, 8.dp) else Modifier.ppmShadow(SHADOW_XS, shape))
            .clip(shape)
            .background(
                if (on) tokens.accent.opacity(1 - 0.1 * hover.lit).color else outlineFill(tokens, dark, hover.lit.toDouble()),
            )
            .pointerHoverIcon(WebCursor.Pointer)
            .ppmClickable(
                pressedColor = if (on) Color.Black.copy(alpha = 0.1f) else tokens.panel2.color,
                shape = shape,
                source = hover.source,
                onClick = onClick,
            )
            .then(if (on) Modifier else Modifier.cssBorder(outlineBorder(tokens, dark), shape))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        PpmIcon(icon, 16.dp, content.color)
        BasicText(label, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = content.color), maxLines = 1)
    }
}

// Below, `dark` is the device's dark mode: the web's `dark:` is `prefers-color-scheme`, which PPM's theme does not set.

/** The outline button's border: `dark:border-input` (the border colour), else the text colour. */
internal fun outlineBorder(tokens: ThemeTokens, dark: Boolean): Color = (if (dark) tokens.border else tokens.text).color

/**
 * The outline button's fill, [lit] of the way to its hover one: `bg-background` to `bg-accent`,
 * or in dark mode `dark:bg-input/30` to `dark:hover:bg-input/50` (`--input` is the border colour).
 */
internal fun outlineFill(tokens: ThemeTokens, dark: Boolean, lit: Double): Color =
    if (dark) tokens.border.opacity(0.3 + 0.2 * lit).color else tokens.panel2.mix(tokens.bgSolid, lit).color

/** The `ghost` button's hover fill: `hover:bg-accent`, `dark:hover:bg-accent/50`. */
internal fun ghostHover(tokens: ThemeTokens, dark: Boolean): Color = tokens.panel2.opacity(if (dark) 0.5 else 1.0).color

/** `shadow-xs`, under an outline button. */
internal val SHADOW_XS = ThemeShadow(0.0, 1.0, 2.0, 0.0, Rgba(0.0, 0.0, 0.0, 0.05))

/**
 * Tailwind's `ring-N`: a solid [width] band outside the box, its corners following [radius]. A
 * box-shadow, so nothing is drawn under the box itself.
 */
internal fun Modifier.ring(width: Dp, color: Color, radius: Dp): Modifier = drawBehind {
    val band = width.toPx()
    drawRoundRect(
        color,
        topLeft = Offset(-band / 2, -band / 2),
        size = Size(size.width + band, size.height + band),
        cornerRadius = CornerRadius(radius.toPx() + band / 2),
        style = Stroke(band),
    )
}

// Servers

/**
 * The servers this app knows, the one in use ticked: a tap switches to another, the bin forgets
 * one (and the password saved for it), and Add server asks for a new one.
 */
@Composable
private fun ServersSection(model: AppModel) {
    val tokens = LocalPpmTokens.current
    val servers by model.store.servers.collectAsState()
    val selected by model.store.selectedId.collectAsState()
    var removing by remember { mutableStateOf<ServerProfile?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (model.debug != null) {
            BasicText(
                "This launch is pinned to ${model.debug.url} for debugging; the saved servers are not used.",
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
            )
        }
        // The web has no such list: a server is lit as a theme card is, the bin as a `ghost`
        // button and Add server as an `outline` one.
        val ghost = ghostHover(tokens, isSystemInDarkTheme())
        for (server in servers) {
            val current = server.id == selected
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .ppmClickable(
                        pressedColor = tokens.panel2.color,
                        hoverColor = if (current) Color.Transparent else tokens.panel2.opacity(0.4).color,
                        shape = shape,
                    ) { if (!current) model.store.select(server) }
                    .cssBorder((if (current) tokens.accent else tokens.border).color, shape)
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    BasicText(server.name, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(server.url, style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text2.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (current) PpmIcon(ProductIcon.Check, 16.dp, tokens.accent.color)
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).ppmClickable(pressedColor = ghost, hoverColor = ghost, onClickLabel = "Remove ${server.name}") { removing = server },
                    contentAlignment = Alignment.Center,
                ) { PpmIcon(ProductIcon.Trash2, 16.dp, tokens.text2.color) }
            }
        }
        val shape = RoundedCornerShape(8.dp)
        val hover = rememberHover()
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .ppmShadow(SHADOW_XS, shape)
                .clip(shape)
                .background(outlineFill(tokens, isSystemInDarkTheme(), hover.lit.toDouble()))
                .ppmClickable(pressedColor = tokens.panel2.color, shape = shape, source = hover.source) { model.addingServer = true }
                .cssBorder(outlineBorder(tokens, isSystemInDarkTheme()), shape),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            PpmIcon(ProductIcon.Plus, 16.dp, tokens.text.color)
            BasicText("Add server", style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color))
        }
    }
    removing?.let { server ->
        ConfirmDialog(
            title = "Remove ${server.name}?",
            message = "This device forgets the server and the password saved for it. Nothing on the server changes.",
            confirm = "Remove",
            onConfirm = { model.remove(server) },
            onDismiss = { removing = null },
        )
    }
}
