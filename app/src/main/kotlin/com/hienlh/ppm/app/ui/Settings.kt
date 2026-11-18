package com.hienlh.ppm.app.ui

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.phone.settings.PhoneSettingsRoute
import com.hienlh.ppm.app.ui.phone.settings.PhoneSettingsScreen
import com.hienlh.ppm.app.ui.phone.settings.resolvePhoneSettingsCategory
import com.hienlh.ppm.app.ui.phone.settings.resolvePhoneSettingsRoute
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
 * Settings as a tab:
 * - On a phone (`< 600.dp`), shows [PhoneSettingsScreen] with the 6 everyday phone sections
 *   as full pages and `"Open on a tablet"` for any other section (Requirements 15.1 & 15.2).
 * - On a tablet (`>= 600.dp`), shows the list–detail layout with every section in the left rail
 *   beside the selected section's detail (Requirement 15.3).
 */
@Composable
fun SettingsTab(model: AppModel) {
    val requested = model.requestedSettingsCategory
    val initial = requested?.let(SettingsCategory::resolveOrDefault)
    SettingsBody(
        model = model,
        initialCategory = initial,
        rawRequestedCategory = requested,
        backReturnsToList = true,
    )
}

/**
 * Settings in a floating window (settings-window-content.tsx): the open category rides on the
 * window's payload, so the window comes back on the pane it was left on.
 */
@Composable
fun SettingsWindowContent(model: AppModel, win: WindowRuntimeState) {
    val raw = win.payload["category"].string ?: model.requestedSettingsCategory
    val category = raw?.let(SettingsCategory::resolveOrDefault)
    SettingsBody(
        model = model,
        initialCategory = category,
        rawRequestedCategory = raw,
        onCategoryChange = { next ->
            model.windows?.setPayload(win.id, buildJsonObject { put("category", next.id) })
        },
    )
}

/**
 * Computes the left category rail width on a tablet (`Tablet 6 · Settings`):
 * - Landscape (`maxWidth >= 840.dp`): `336.dp`
 * - Portrait (`maxWidth < 840.dp`): `300.dp`
 */
internal fun tabletSettingsRailWidth(maxWidth: Dp): Dp =
    if (maxWidth >= TABLET_LANDSCAPE_BREAKPOINT) TABLET_RAIL_LANDSCAPE_WIDTH else TABLET_RAIL_PORTRAIT_WIDTH

internal val TABLET_LANDSCAPE_BREAKPOINT = 840.dp
internal val TABLET_RAIL_LANDSCAPE_WIDTH = 336.dp
internal val TABLET_RAIL_PORTRAIT_WIDTH = 300.dp
internal val TABLET_DETAIL_MAX_WIDTH = 760.dp

@Composable
internal fun SettingsBody(
    model: AppModel,
    initialCategory: SettingsCategory? = null,
    rawRequestedCategory: String? = null,
    backReturnsToList: Boolean = false,
    onCategoryChange: (SettingsCategory) -> Unit = {},
) {
    val minimalMode = model.info?.minimalMode == true
    var category by rememberSaveable(initialCategory) {
        mutableStateOf(initialCategory ?: SettingsCategory.General)
    }
    val select: (SettingsCategory) -> Unit = {
        category = it
        model.requestedSettingsCategory = null
        onCategoryChange(it)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val isPhone = SizeClass.of(maxWidth) == SizeClass.Phone
        if (isPhone) {
            val initialPhoneRoute = remember(rawRequestedCategory, initialCategory) {
                when {
                    !rawRequestedCategory.isNullOrBlank() -> resolvePhoneSettingsRoute(rawRequestedCategory)
                    initialCategory != null -> resolvePhoneSettingsCategory(initialCategory)
                    else -> PhoneSettingsRoute.Home
                }
            }
            PhoneSettingsScreen(
                model = model,
                initialRoute = initialPhoneRoute,
                onRouteChanged = { route ->
                    if (route is PhoneSettingsRoute.FullPage) {
                        route.section.category?.let(onCategoryChange)
                    } else if (route is PhoneSettingsRoute.TabletOnlyPage) {
                        onCategoryChange(route.category)
                    }
                },
            )
        } else {
            val railWidth = tabletSettingsRailWidth(maxWidth)
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LocalPpmTokens.current.bgSolid.color)
                    .testTag("tablet-settings-split"),
            ) {
                SettingsRail(
                    active = category,
                    minimalMode = minimalMode,
                    railWidth = railWidth,
                    onSelect = select,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    SettingsPane(
                        model = model,
                        category = category,
                        split = true,
                        onBack = {},
                    )
                }
            }
        }
    }
}

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
    Memory("memory", "Memory", "One memory store for every provider, synced", ProductIcon.Brain, SettingsGroup.Ai, minimalSafe = false),
    Accounts("accounts", "Accounts", "Claude, Codex and Antigravity sign-ins, rotation", ProductIcon.Users, SettingsGroup.Ai, minimalSafe = true),
    Mcp("mcp", "MCP Servers", "Model Context Protocol servers & sign-in", ProductIcon.Plug, SettingsGroup.Ai, minimalSafe = true),
    AiResources("ai-resources", "AI Resources", "Skills, agents, commands & MCP configs", ProductIcon.Layers, SettingsGroup.Ai, minimalSafe = false),
    PpmBot("ppmbot", "PPMBot", "Telegram bot coordinator, tasks & memory", ProductIcon.BotMessageSquare, SettingsGroup.Ai, minimalSafe = false),
    Vault("vault", "Vault", "Personal details the AI may read, behind passwords", ProductIcon.Lock, SettingsGroup.Ai, minimalSafe = false),
    Design("design", "Design", "Design Canvas apps, instructions & skills", ProductIcon.LayoutGrid, SettingsGroup.Ai, minimalSafe = false),

    // Integrations
    Notifications("notifications", "Notifications", "Push, Telegram & ntfy alert channels", ProductIcon.Bell, SettingsGroup.Integrations, minimalSafe = true),
    RemoteAccess("remote-access", "Remote Access", "Tailscale, Public Link & Cloudflare tunnel", ProductIcon.Globe, SettingsGroup.Integrations, minimalSafe = true),
    Cloud("cloud", "Cloud & Share", "PPM Cloud account, device link & share slug", ProductIcon.Cloud, SettingsGroup.Integrations, minimalSafe = false),
    Proxy("proxy", "API Proxy", "Anthropic & OpenAI SDK proxy endpoint", ProductIcon.ArrowLeftRight, SettingsGroup.Integrations, minimalSafe = false),
    Extensions("extensions", "Extensions", "Installed extensions & local dev-links", ProductIcon.Puzzle, SettingsGroup.Integrations, minimalSafe = false),
    Jira("jira", "Jira", "Project credentials, JQL watchers & issue debug", ProductIcon.Bug, SettingsGroup.Integrations, minimalSafe = false),
    Schedules("schedules", "Schedules", "Cron prompt & script tasks, logs & templates", ProductIcon.CalendarClock, SettingsGroup.Integrations, minimalSafe = false),

    // Advanced
    QueryAudit("query-audit", "Storage", "Databases, backups and logs on disk", ProductIcon.ScrollText, SettingsGroup.Advanced, minimalSafe = false),
    LanguageServers("language-servers", "Language Servers", "Editor code intelligence", ProductIcon.Code, SettingsGroup.Advanced, minimalSafe = false),
    DatabaseDrivers("database-drivers", "Database Drivers", "On-demand database engines", ProductIcon.Database, SettingsGroup.Advanced, minimalSafe = false),
    RemoteDesktop("remote-desktop", "Remote Desktop", "MediaMTX WebRTC relay", ProductIcon.Monitor, SettingsGroup.Advanced, minimalSafe = false),
    ;

    companion object {
        /** Resolves [raw] by category `id` (or common alias) or returns `null` if unknown. */
        fun resolveOrNull(raw: String?): SettingsCategory? {
            val cleaned = raw?.trim()?.lowercase().orEmpty()
            if (cleaned.isEmpty()) return null
            entries.firstOrNull { it.id.equals(cleaned, ignoreCase = true) }?.let { return it }
            return when (cleaned) {
                "keybindings", "keyboard", "keyboard-shortcuts" -> Shortcuts
                "theme", "themes" -> Appearance
                "storage" -> QueryAudit
                else -> null
            }
        }

        /** Resolves [raw] by category `id` or falls back to [General]. */
        fun resolveOrDefault(raw: String?): SettingsCategory =
            resolveOrNull(raw) ?: General
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
 * Tablet list–detail category rail (`Tablet 6 · Settings`, Requirement 15.3):
 * `336.dp` in landscape (`>= 840.dp`) and `300.dp` in portrait (`< 840.dp`), with a search filter
 * and every category grouped under its heading.
 */
@Composable
internal fun SettingsRail(
    active: SettingsCategory,
    minimalMode: Boolean,
    railWidth: Dp = TABLET_RAIL_LANDSCAPE_WIDTH,
    onSelect: (SettingsCategory) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var searchQuery by remember { mutableStateOf("") }
    val needle = searchQuery.trim().lowercase()

    Column(
        Modifier
            .width(railWidth)
            .fillMaxHeight()
            .background(tokens.panel.color)
            .edge(Edge.Right, tokens.border.color)
            .testTag("tablet-settings-rail"),
    ) {
        // Rail header + Search settings input
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText(
                text = "Settings",
                style = ppmText(16f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color),
            )
            SettingsTextInput(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "Search settings",
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tablet-settings-search"),
            )
        }
        Separator()

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            for (group in SettingsGroup.entries) {
                val cats = group.categories(minimalMode).filter { cat ->
                    needle.isEmpty() ||
                        cat.label.lowercase().contains(needle) ||
                        cat.subtitle.lowercase().contains(needle) ||
                        cat.id.lowercase().contains(needle)
                }
                if (cats.isEmpty()) continue
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    group.label?.let { GroupHeading(it, Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp)) }
                    for (category in cats) {
                        val on = category == active
                        val shape = RoundedCornerShape(10.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .then(
                                    if (on) {
                                        Modifier
                                            .background(tokens.accentWash.color)
                                            .cssBorder(tokens.accent.opacity(0.35).color, shape)
                                    } else {
                                        Modifier
                                    },
                                )
                                .pointerHoverIcon(WebCursor.Pointer)
                                .ppmClickable(
                                    pressedColor = tokens.panel2.color,
                                    hoverColor = if (on) Color.Transparent else tokens.panel2.opacity(0.5).color,
                                    shape = shape,
                                ) { onSelect(category) }
                                .padding(horizontal = 10.dp, vertical = 9.dp)
                                .testTag("tablet-settings-category-${category.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (on) tokens.accent.opacity(0.18).color else tokens.panel2.color),
                                contentAlignment = Alignment.Center,
                            ) {
                                PpmIcon(category.icon, 16.dp, (if (on) tokens.accent else tokens.text2).color)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                BasicText(
                                    text = category.label,
                                    style = ppmText(
                                        size = 13f,
                                        weight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                                        lineHeight = 18f,
                                    ).copy(color = if (on) tokens.accent.color else tokens.text.color),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                BasicText(
                                    text = category.subtitle,
                                    style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Stateless Tablet list–detail Settings scaffold (`Tablet 6 · Settings`, Requirement 15.3)
 * that can be mounted directly in tests or previews.
 */
@Composable
internal fun TabletSettingsLayout(
    activeCategory: SettingsCategory,
    onSelectCategory: (SettingsCategory) -> Unit,
    minimalMode: Boolean = false,
    detailContent: @Composable (SettingsCategory) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalPpmTokens.current.bgSolid.color)
            .testTag("tablet-settings-layout"),
    ) {
        val railWidth = tabletSettingsRailWidth(maxWidth)
        Row(Modifier.fillMaxSize()) {
            SettingsRail(
                active = activeCategory,
                minimalMode = minimalMode,
                railWidth = railWidth,
                onSelect = onSelectCategory,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag("tablet-settings-detail-${activeCategory.id}"),
            ) {
                TabletDetailHeader(category = activeCategory)
                Separator()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = TABLET_DETAIL_MAX_WIDTH),
                    ) {
                        detailContent(activeCategory)
                    }
                }
            }
        }
    }
}

@Composable
private fun TabletDetailHeader(
    category: SettingsCategory,
    split: Boolean = true,
    onBack: () -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = if (split) 20.dp else 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!split) {
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
        PpmIcon(category.icon, 18.dp, tokens.accent.color)
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                category.label,
                style = ppmText(15f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                category.subtitle,
                style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The detail pane on a tablet: 48 dp header bar with category icon, title and subtitle, followed
 * by a centered reading column capped at [TABLET_DETAIL_MAX_WIDTH] (`760.dp`).
 */
@Composable
private fun SettingsPane(model: AppModel, category: SettingsCategory, split: Boolean, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .testTag("tablet-settings-detail-${category.id}"),
    ) {
        TabletDetailHeader(category = category, split = split, onBack = onBack)
        Separator()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = TABLET_DETAIL_MAX_WIDTH),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Changed on another device: the pane reads its values again.
                key(settingsLiveRevision(model, category)) {
                when (category) {
                    SettingsCategory.General -> GeneralSection(model)
                    SettingsCategory.Appearance -> AppearanceSection(model)
                    SettingsCategory.Servers -> ServersSection(model)
                    SettingsCategory.Voice -> VoiceSection(model)
                    SettingsCategory.Shortcuts -> KeyboardShortcutsSection(model)
                    SettingsCategory.Files -> FilesSettingsSection(model)
                    SettingsCategory.Ai -> AiProviderSettingsSection(model)
                    SettingsCategory.Tools -> ToolsSettingsSection(model)
                    SettingsCategory.Memory -> MemorySettingsSection(model)
                    SettingsCategory.Accounts -> AccountsSettingsSection(model)
                    SettingsCategory.Mcp -> McpSettingsSection(model)
                    SettingsCategory.AiResources -> AiResourcesSettingsSection(model)
                    SettingsCategory.PpmBot -> PpmBotSettingsSection(model)
                    SettingsCategory.Vault -> com.hienlh.ppm.app.ui.settings.vault.VaultSettingsSection(model)
                    SettingsCategory.Design -> DesignSettingsSection(model)
                    SettingsCategory.Notifications -> NotificationsSettingsSection(model)
                    SettingsCategory.RemoteAccess -> RemoteAccessSettingsSection(model)
                    SettingsCategory.Cloud -> CloudSettingsSection(model)
                    SettingsCategory.Proxy -> ProxySettingsSection(model)
                    SettingsCategory.Extensions -> ExtensionsSettingsSection(model)
                    SettingsCategory.Jira -> JiraSettingsSection(model)
                    SettingsCategory.Schedules -> SchedulesSettingsSection(model)
                    SettingsCategory.QueryAudit -> {
                        com.hienlh.ppm.app.ui.settings.StorageOverviewSection(model)
                        QueryAuditSection(model)
                    }
                    SettingsCategory.LanguageServers -> LanguageServersSection(model)
                    SettingsCategory.DatabaseDrivers -> DatabaseDriversSection(model)
                    SettingsCategory.RemoteDesktop -> RemoteDesktopSettingsSection(model)
                }
                }
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
    var loggingOut by remember { mutableStateOf<ServerProfile?>(null) }
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
        val current = servers.firstOrNull { it.id == selected }
        if (current != null && model.debug == null) {
            OutlineButton(ProductIcon.LogOut, "Log out") { loggingOut = current }
        }
        OutlineButton(ProductIcon.Plus, "Add server") { model.addingServer = true }
    }
    loggingOut?.let { server ->
        ConfirmDialog(
            title = "Log out of ${server.name}?",
            message = "This device forgets the password saved for this server and asks for it again. The server stays in the list.",
            confirm = "Log out",
            onConfirm = { model.logOut() },
            onDismiss = { loggingOut = null },
        )
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

/** A full-width `outline` button, 36 dp tall. */
@Composable
private fun OutlineButton(icon: ProductIcon, label: String, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val hover = rememberHover()
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .ppmShadow(SHADOW_XS, shape)
            .clip(shape)
            .background(outlineFill(tokens, isSystemInDarkTheme(), hover.lit.toDouble()))
            .ppmClickable(pressedColor = tokens.panel2.color, shape = shape, source = hover.source, onClick = onClick)
            .cssBorder(outlineBorder(tokens, isSystemInDarkTheme()), shape),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        PpmIcon(icon, 16.dp, tokens.text.color)
        BasicText(label, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color))
    }
}
