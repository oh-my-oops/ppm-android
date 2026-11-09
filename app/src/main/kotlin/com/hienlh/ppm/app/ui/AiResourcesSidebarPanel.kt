package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.client.aiResourcesCreate
import com.hienlh.ppm.client.aiResourcesDelete
import com.hienlh.ppm.client.aiResourcesDuplicate
import com.hienlh.ppm.client.aiResourcesList
import com.hienlh.ppm.client.mcpAddServer
import com.hienlh.ppm.client.mcpDeleteServer
import com.hienlh.ppm.client.mcpImportFromClaude
import com.hienlh.ppm.client.mcpListServers
import com.hienlh.ppm.client.mcpUpdateServer
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.design.vector
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.settings.AiResourceFilter
import com.hienlh.ppm.state.settings.AiResourceItem
import com.hienlh.ppm.state.settings.AiResourcesOverview
import com.hienlh.ppm.state.settings.McpServerRow
import com.hienlh.ppm.state.settings.buildMcpConfigJson
import com.hienlh.ppm.state.settings.duplicateAiResourceTargetScope
import com.hienlh.ppm.state.settings.parseAiResources
import com.hienlh.ppm.state.settings.parseMcpServers
import com.hienlh.ppm.state.settings.validateAiResourceName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Sidebar AI Resources panel (`src/web/components/ai-resources/ai-resources-panel.tsx`):
 * skills, agents, commands, and MCP servers with filter chips, search, and quick create/import.
 */
@Composable
fun AiResourcesSidebarPanel(model: AppModel, onNavigate: () -> Unit = {}) {
    val tokens = LocalPpmTokens.current
    val api = model.api
    val scope = rememberCoroutineScope()
    val activeProject = model.currentProject
    val projectPath = activeProject?.path.orEmpty()

    var result by remember { mutableStateOf<AiResourcesOverview?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var mcpServers by remember { mutableStateOf<List<McpServerRow>>(emptyList()) }

    val searchState = remember { TextFieldState() }
    val search = searchState.text.toString()
    var filter by remember { mutableStateOf(AiResourceFilter.ALL) }
    var newMenuOpen by remember { mutableStateOf(false) }

    var createType by remember { mutableStateOf<String?>(null) }
    var dupSource by remember { mutableStateOf<AiResourceItem?>(null) }
    var delTarget by remember { mutableStateOf<AiResourceItem?>(null) }
    var mcpDialogOpen by remember { mutableStateOf(false) }
    var editingMcp by remember { mutableStateOf<McpServerRow?>(null) }
    var delMcp by remember { mutableStateOf<McpServerRow?>(null) }
    var importingMcp by remember { mutableStateOf(false) }

    suspend fun reloadResources() {
        val client = api ?: return
        loading = true
        try {
            result = parseAiResources(client.aiResourcesList(projectPath))
            error = null
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            error = t.message ?: "Failed to load AI resources"
        } finally {
            loading = false
        }
    }

    suspend fun reloadMcp() {
        val client = api ?: return
        try {
            mcpServers = parseMcpServers(client.mcpListServers())
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
        }
    }

    LaunchedEffect(api, projectPath) {
        if (api != null) {
            reloadResources()
            reloadMcp()
        } else {
            loading = false
        }
    }

    val activeFilePath = model.layout?.activeGridTab
        ?.takeIf { it.type == "ai-resource" }
        ?.metadata?.get("filePath")?.string

    val showFile = filter != AiResourceFilter.MCP
    val showMcp = filter == AiResourceFilter.ALL || filter == AiResourceFilter.MCP
    val q = search.trim().lowercase()

    val groups = remember(result, filter, q, showFile) {
        val ov = result
        if (ov == null || !showFile) emptyList()
        else listOf("skill", "agent", "command")
            .filter { type -> filter == AiResourceFilter.ALL || filter.id == type }
            .mapNotNull { type ->
                val items = ov.items.filter { item ->
                    item.type == type && (q.isEmpty() || item.name.lowercase().contains(q) || item.description.lowercase().contains(q))
                }
                if (items.isEmpty()) null else type to items
            }
    }

    val mcpFiltered = remember(mcpServers, q, showMcp) {
        if (!showMcp) emptyList()
        else if (q.isEmpty()) mcpServers
        else mcpServers.filter { s -> s.name.lowercase().contains(q) || mcpServerPreview(s).lowercase().contains(q) }
    }

    val nothingToShow = groups.isEmpty() && (!showMcp || mcpFiltered.isEmpty()) && filter != AiResourceFilter.MCP

    fun openResource(item: AiResourceItem) {
        model.openAiResourceTab(item, projectPath)
        onNavigate()
    }

    fun handleImportMcp() {
        val client = api ?: return
        importingMcp = true
        scope.launch {
            try {
                val res = client.mcpImportFromClaude()
                val imported = res["imported"].int ?: 0
                val skipped = res["skipped"].int ?: 0
                model.toast(ErrorToastMessage("Imported $imported, skipped $skipped", kind = ErrorToastMessage.Kind.INFO))
                reloadMcp()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                model.toast(ErrorToastMessage(t.message ?: "Import failed"))
            } finally {
                importingMcp = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // SidebarHeader (`px-2 py-2 border-b border-border gap-1`)
        Row(
            Modifier
                .fillMaxWidth()
                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PpmIcon(ProductIcon.Sparkles, 16.dp, tokens.accent.color)
            BasicText(
                "AI RESOURCES",
                Modifier.weight(1f),
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text2.color, letterSpacing = 0.025.em),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val refreshHover = rememberHover()
            Box(
                Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .ppmClickable(
                        pressedColor = tokens.panel2.color,
                        hoverColor = tokens.panel2.color,
                        shape = RoundedCornerShape(4.dp),
                        onClickLabel = "Refresh",
                        source = refreshHover.source,
                    ) {
                        scope.launch { reloadResources() }
                        scope.launch { reloadMcp() }
                    },
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(
                    ProductIcon.RefreshCw,
                    14.dp,
                    tokens.text.mix(tokens.text3, refreshHover.lit.toDouble()).color,
                    spinning = loading,
                )
            }
            Box {
                val newShape = RoundedCornerShape(4.dp)
                Row(
                    Modifier
                        .clip(newShape)
                        .background(tokens.accent.color)
                        .ppmClickable(
                            pressedColor = tokens.accent.opacity(0.9).color,
                            hoverColor = tokens.accent.opacity(0.9).color,
                            shape = newShape,
                            onClickLabel = "New resource",
                        ) { newMenuOpen = !newMenuOpen }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PpmIcon(ProductIcon.Plus, 12.dp, tokens.accentFg.color)
                    BasicText("New", style = ppmText(11f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.accentFg.color))
                }
                if (newMenuOpen) {
                    DropdownBelowEnd(onDismiss = { newMenuOpen = false }, minWidth = 176.dp) {
                        MenuItem("New Skill", onClick = { newMenuOpen = false; dupSource = null; createType = "skill" }, icon = { MenuIcon(LucideIcon.Hexagon.vector) }, iconEnd = 0.dp)
                        MenuItem("New Agent", onClick = { newMenuOpen = false; dupSource = null; createType = "agent" }, icon = { MenuIcon(ProductIcon.Bot) }, iconEnd = 0.dp)
                        MenuItem("New Command", onClick = { newMenuOpen = false; dupSource = null; createType = "command" }, icon = { MenuIcon(ProductIcon.SquareTerminal) }, iconEnd = 0.dp)
                        MenuSeparator()
                        MenuItem("New MCP Server", onClick = { newMenuOpen = false; editingMcp = null; mcpDialogOpen = true }, icon = { MenuIcon(ProductIcon.Plug) }, iconEnd = 0.dp)
                        MenuItem("Import MCP…", onClick = { newMenuOpen = false; handleImportMcp() }, icon = { MenuIcon(ProductIcon.Download) }, iconEnd = 0.dp)
                    }
                }
            }
        }

        // Search (`px-2 pt-2`)
        Box(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp)) {
            AiResourceSearchInput(searchState)
        }

        // Type filter chips (`flex gap-1 px-2 py-2`)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (f in AiResourceFilter.entries) {
                val active = filter == f
                val chipShape = CircleShape
                val border = if (active) tokens.accent.opacity(0.4).color else tokens.border.color
                val bg = if (active) tokens.accent.opacity(0.1).color else Color.Transparent
                val fg = if (active) tokens.accent.color else tokens.text3.color
                Box(
                    Modifier
                        .clip(chipShape)
                        .background(bg)
                        .ppmClickable(
                            pressedColor = if (active) bg else tokens.panel2.color,
                            hoverColor = if (active) bg else tokens.panel2.color,
                            shape = chipShape,
                        ) { filter = f }
                        .cssBorder(border, chipShape)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                ) {
                    BasicText(f.label, style = ppmText(11f, lineHeight = 15f).copy(color = fg), maxLines = 1)
                }
            }
        }

        // List (`flex-1 min-h-0 overflow-y-auto`)
        Column(Modifier.weight(1f).fillMaxWidth().cssScrollY()) {
            if (showMcp) {
                McpSignInList(
                    model = model,
                    modifier = Modifier.fillMaxWidth(),
                    project = activeProject?.name,
                    search = search,
                    showWhenEmpty = false,
                )
            }
            when {
                error != null -> {
                    BasicText(
                        error!!,
                        Modifier.fillMaxWidth().padding(16.dp),
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color, textAlign = TextAlign.Center),
                    )
                }
                loading && result == null -> {
                    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(6) {
                            Box(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(4.dp)).background(tokens.panel2.color))
                        }
                    }
                }
                nothingToShow -> {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PpmIcon(ProductIcon.FolderOpen, 32.dp, tokens.text3.opacity(0.4).color)
                        BasicText(
                            if (search.isNotBlank()) "No matching resources" else "No skills, agents, or commands found",
                            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color, textAlign = TextAlign.Center),
                        )
                        if (activeProject == null && search.isBlank()) {
                            BasicText(
                                "Open a project to include project-scoped skills.",
                                style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.opacity(0.7).color, textAlign = TextAlign.Center),
                            )
                        }
                    }
                }
                else -> {
                    for ((type, items) in groups) {
                        Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                            AiGroupHeader(aiTypeLabel(type), items.size)
                            for (item in items) {
                                AiResourceRow(
                                    item = item,
                                    active = activeFilePath == item.filePath,
                                    onOpen = { openResource(item) },
                                    onDuplicate = { dupSource = item },
                                    onDelete = { delTarget = item },
                                )
                            }
                        }
                    }
                    if (showMcp) {
                        AiMcpGroup(
                            servers = mcpFiltered,
                            showActions = filter == AiResourceFilter.MCP,
                            importing = importingMcp,
                            onAdd = { editingMcp = null; mcpDialogOpen = true },
                            onImport = ::handleImportMcp,
                            onEdit = { s -> editingMcp = s; mcpDialogOpen = true },
                            onDelete = { s -> delMcp = s },
                        )
                    }
                }
            }
        }

        // Status footer (`flex items-center gap-2 border-t border-border px-2 py-1 text-[10px] text-text-subtle`)
        val stats = result?.stats
        if (stats != null) {
            val footStyle = ppmText(10f, lineHeight = 15f).copy(color = tokens.text3.color)
            Row(
                Modifier
                    .fillMaxWidth()
                    .cssBorder(tokens.border.color, sides = CssSides.Top)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicText("${stats.active} active", style = footStyle)
                BasicText("·", style = footStyle)
                BasicText("${stats.project} project", style = footStyle)
                BasicText("·", style = footStyle)
                BasicText("${stats.shadowed} shadowed", style = footStyle)
            }
        }
    }

    // Create / Duplicate dialog
    if (createType != null || dupSource != null) {
        val dup = dupSource
        AiCreateOrDuplicateDialog(
            mode = if (dup != null) "duplicate" else "create",
            source = dup,
            initialType = dup?.type ?: createType ?: "skill",
            hasProject = activeProject != null,
            projectPath = projectPath,
            onDismiss = { createType = null; dupSource = null },
            onSubmit = { type, targetScope, name, setErr, setBusy ->
                val client = api ?: return@AiCreateOrDuplicateDialog
                setBusy(true)
                scope.launch {
                    try {
                        val res = if (dup != null) {
                            client.aiResourcesDuplicate(
                                path = dup.filePath,
                                type = dup.type,
                                scope = targetScope,
                                name = name,
                                projectPath = projectPath,
                            )
                        } else {
                            client.aiResourcesCreate(
                                type = type,
                                scope = targetScope,
                                name = name,
                                projectPath = projectPath,
                            )
                        }
                        val createdPath = res["filePath"].string ?: res["path"].string
                        createType = null
                        dupSource = null
                        reloadResources()
                        if (!createdPath.isNullOrBlank()) {
                            val opened = result?.items?.firstOrNull { it.filePath == createdPath }
                            if (opened != null) openResource(opened)
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        setErr(t.message ?: "Operation failed")
                    } finally {
                        setBusy(false)
                    }
                }
            },
        )
    }

    delTarget?.let { item ->
        ConfirmDialog(
            title = "Delete '${item.name}'?",
            message = if (item.type == "skill") "This deletes the skill folder (${item.filePath})." else "This permanently deletes ${item.filePath}.",
            confirm = "Delete",
            onConfirm = {
                val client = api ?: return@ConfirmDialog
                scope.launch {
                    try {
                        client.aiResourcesDelete(item.filePath, item.type, projectPath)
                        delTarget = null
                        reloadResources()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        model.toast(ErrorToastMessage(t.message ?: "Delete failed"))
                    }
                }
            },
            onDismiss = { delTarget = null },
        )
    }

    if (mcpDialogOpen) {
        val editing = editingMcp
        Dialog(onDismissRequest = { mcpDialogOpen = false; editingMcp = null }) {
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(16.dp),
            ) {
                McpServerFormContent(
                    initial = editing,
                    onCancel = { mcpDialogOpen = false; editingMcp = null },
                    onSave = { name, transport, command, argsText, url, kvPairs ->
                        val client = api ?: return@McpServerFormContent
                        val cfg = buildMcpConfigJson(
                            transport = transport,
                            command = command,
                            argsText = argsText,
                            url = url,
                            kvPairs = kvPairs,
                        )
                        scope.launch {
                            try {
                                if (editing != null) client.mcpUpdateServer(editing.name, cfg)
                                else client.mcpAddServer(name.trim(), cfg)
                                mcpDialogOpen = false
                                editingMcp = null
                                reloadMcp()
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (t: Throwable) {
                                model.toast(ErrorToastMessage(t.message ?: "Failed to save MCP server"))
                            }
                        }
                    },
                )
            }
        }
    }

    delMcp?.let { server ->
        ConfirmDialog(
            title = "Delete '${server.name}'?",
            message = "Remove MCP server '${server.name}' from PPM?",
            confirm = "Delete",
            onConfirm = {
                val client = api ?: return@ConfirmDialog
                scope.launch {
                    try {
                        client.mcpDeleteServer(server.name)
                        delMcp = null
                        reloadMcp()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        model.toast(ErrorToastMessage(t.message ?: "Delete failed"))
                    }
                }
            },
            onDismiss = { delMcp = null },
        )
    }
}

@Composable
private fun AiResourceSearchInput(state: TextFieldState) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    var focused by remember { mutableStateOf(false) }
    val border = if (focused) tokens.accent.opacity(0.5).color else tokens.border.color
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.panel2.color)
            .cssBorder(border, shape),
        contentAlignment = Alignment.CenterStart,
    ) {
        PpmTextField(
            state = state,
            placeholder = "Filter resources…",
            modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
            desktopStyle = ppmText(12f, lineHeight = 16f),
            onFocusChanged = { focused = it },
        )
        Box(Modifier.padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
            PpmIcon(ProductIcon.Search, 14.dp, tokens.text3.color)
        }
    }
}

@Composable
private fun AiGroupHeader(title: String, count: Int) {
    val tokens = LocalPpmTokens.current
    val style = ppmText(10f, FontWeight.SemiBold, lineHeight = 15f).copy(letterSpacing = 0.025.em)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BasicText(title.uppercase(), style = style.copy(color = tokens.text3.color))
        BasicText("$count", style = style.copy(color = tokens.text3.opacity(0.6).color))
    }
}

@Composable
private fun AiResourceRow(
    item: AiResourceItem,
    active: Boolean,
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val accentColor = tokens.accent.color
    val accentWash = tokens.accentWash.color
    Box(Modifier.fillMaxWidth()) {
        menuAt?.let { at ->
            ContextMenuPopup(at, onDismiss = { menuAt = null }) {
                MenuItem(if (item.readOnly) "Open (read-only)" else "Open / Edit", onClick = { menuAt = null; onOpen() })
                MenuItem("Duplicate…", onClick = { menuAt = null; onDuplicate() })
                if (!item.readOnly) {
                    MenuSeparator()
                    MenuItem("Delete", color = tokens.error, highlight = tokens.error.opacity(0.1), onClick = { menuAt = null; onDelete() })
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .alpha(if (item.shadowed) 0.55f else 1f)
                .then(
                    if (active) {
                        Modifier.drawBehind {
                            drawRect(accentWash)
                            drawRect(accentColor, topLeft = Offset.Zero, size = Size(2.dp.toPx(), size.height))
                        }
                    } else {
                        Modifier
                    },
                )
                .pressable(
                    pressedColor = if (active) Color.Transparent else tokens.panel2.color,
                    hoverColor = if (active) Color.Transparent else tokens.panel2.color,
                    onLongClick = { menuAt = it },
                    onClick = onOpen,
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(aiTypeIcon(item.type), 16.dp, tokens.text3.color)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicText(
                        item.name,
                        Modifier.weight(1f, fill = false),
                        style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(
                            color = tokens.text.color,
                            textDecoration = if (item.shadowed) TextDecoration.LineThrough else TextDecoration.None,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.overrides > 0) {
                        val amberBg = Rgba(0.96, 0.62, 0.04, 0.15).color
                        val amberFg = Rgba(0.98, 0.75, 0.14, 1.0).color
                        Box(Modifier.clip(RoundedCornerShape(4.dp)).background(amberBg).padding(horizontal = 4.dp, vertical = 1.dp)) {
                            BasicText("overrides", style = ppmText(9f, FontWeight.Medium, lineHeight = 10f).copy(color = amberFg))
                        }
                    }
                    if (item.shadowed) {
                        BasicText("shadowed", style = ppmText(9f, lineHeight = 11f).copy(color = tokens.text3.color))
                    }
                }
                if (item.description.isNotEmpty()) {
                    BasicText(
                        item.description,
                        style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AiScopeBadge(item.scope)
        }
    }
}

@Composable
private fun AiScopeBadge(scope: String) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    val (fg, border, bg) = when (scope) {
        "project" -> Triple(tokens.accent.color, tokens.accent.opacity(0.3).color, tokens.accent.opacity(0.1).color)
        "user" -> Triple(Rgba(0.204, 0.827, 0.6, 1.0).color, Rgba(0.063, 0.725, 0.506, 0.3).color, Rgba(0.063, 0.725, 0.506, 0.1).color)
        else -> Triple(tokens.text3.color, tokens.border.color, tokens.panel2.color)
    }
    val label = when (scope) {
        "project" -> "PROJECT"
        "user" -> "USER"
        else -> "BUNDLED"
    }
    Box(
        Modifier
            .clip(shape)
            .background(bg)
            .cssBorder(border, shape)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        BasicText(label, style = ppmText(9f, FontWeight.Medium, lineHeight = 10f).copy(color = fg, letterSpacing = 0.025.em))
    }
}

@Composable
private fun AiMcpGroup(
    servers: List<McpServerRow>,
    showActions: Boolean,
    importing: Boolean,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onEdit: (McpServerRow) -> Unit,
    onDelete: (McpServerRow) -> Unit,
) {
    if (servers.isEmpty() && !showActions) return
    val tokens = LocalPpmTokens.current
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        AiGroupHeader("MCP Servers", servers.size)
        for (s in servers) {
            var menuAt by remember { mutableStateOf<Offset?>(null) }
            Box(Modifier.fillMaxWidth()) {
                menuAt?.let { at ->
                    ContextMenuPopup(at, onDismiss = { menuAt = null }) {
                        MenuItem("Edit…", onClick = { menuAt = null; onEdit(s) })
                        MenuSeparator()
                        MenuItem("Delete", color = tokens.error, highlight = tokens.error.opacity(0.1), onClick = { menuAt = null; onDelete(s) })
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressable(
                            pressedColor = tokens.panel2.color,
                            hoverColor = tokens.panel2.color,
                            onLongClick = { menuAt = it },
                            onClick = { onEdit(s) },
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PpmIcon(ProductIcon.Plug, 16.dp, tokens.text3.color)
                    Column(Modifier.weight(1f)) {
                        BasicText(s.name, style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val preview = mcpServerPreview(s)
                        if (preview.isNotEmpty()) {
                            BasicText(preview, style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    val shape = RoundedCornerShape(4.dp)
                    Box(Modifier.clip(shape).background(tokens.panel2.color).cssBorder(tokens.border.color, shape).padding(horizontal = 5.dp, vertical = 2.dp)) {
                        BasicText(s.transport.uppercase(), style = ppmText(9f, FontWeight.Medium, lineHeight = 10f).copy(color = tokens.text3.color, letterSpacing = 0.025.em))
                    }
                }
            }
        }
        if (servers.isEmpty() && showActions) {
            BasicText(
                "No MCP servers configured.",
                Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
            )
        }
        if (showActions) {
            Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val btnShape = RoundedCornerShape(6.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(btnShape)
                        .background(tokens.accent.color)
                        .ppmClickable(pressedColor = tokens.accent.opacity(0.9).color, hoverColor = tokens.accent.opacity(0.9).color, shape = btnShape, onClick = onAdd)
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PpmIcon(ProductIcon.Plus, 14.dp, tokens.accentFg.color)
                    BasicText("Add MCP server", style = ppmText(11f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.accentFg.color))
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .alpha(if (importing) 0.5f else 1f)
                        .clip(btnShape)
                        .ppmClickable(enabled = !importing, pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, shape = btnShape, onClick = onImport)
                        .cssBorder(tokens.border.color, btnShape)
                        .padding(vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PpmIcon(ProductIcon.Download, 14.dp, tokens.text2.color)
                    BasicText(if (importing) "Importing…" else "Import from Claude Code", style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.color))
                }
            }
        }
    }
}

@Composable
private fun AiCreateOrDuplicateDialog(
    mode: String,
    source: AiResourceItem?,
    initialType: String,
    hasProject: Boolean,
    projectPath: String,
    onDismiss: () -> Unit,
    onSubmit: (type: String, scope: String, name: String, setErr: (String?) -> Unit, setBusy: (Boolean) -> Unit) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var type by remember(initialType) { mutableStateOf(initialType) }
    var resScope by remember(source, hasProject) {
        mutableStateOf(
            if (source != null) duplicateAiResourceTargetScope(source.scope, projectPath)
            else if (hasProject) "project" else "user",
        )
    }
    var name by remember(source) { mutableStateOf(if (source != null) "${source.name}-copy" else "") }
    var formErr by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(tokens.bgSolid.color)
                .cssBorder(tokens.border.color, shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingsSectionTitle(if (mode == "duplicate") "Duplicate '${source?.name.orEmpty()}'" else "Create AI Resource")
            if (mode == "create") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in listOf("skill", "agent", "command")) {
                        SettingsButton(label = t, primary = type == t, onClick = { type = t })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (sc in listOf("project", "user")) {
                    SettingsButton(
                        label = "Scope: $sc",
                        primary = resScope == sc,
                        enabled = sc == "user" || hasProject,
                        onClick = { resScope = sc },
                    )
                }
            }
            SettingsTextInput(
                value = name,
                onValueChange = { name = it; formErr = null },
                placeholder = "Resource name (e.g. my-skill)",
                mono = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (formErr != null) {
                BasicText(formErr!!, style = ppmText(11f).copy(color = tokens.error.color))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SettingsButton(label = "Cancel", enabled = !busy, onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                SettingsButton(
                    label = if (busy) "Saving…" else if (mode == "duplicate") "Duplicate" else "Create",
                    primary = true,
                    enabled = !busy,
                    onClick = {
                        val err = validateAiResourceName(name)
                        if (err != null) {
                            formErr = err
                            return@SettingsButton
                        }
                        onSubmit(type, resScope, name.trim(), { formErr = it }, { busy = it })
                    },
                )
            }
        }
    }
}

private fun aiTypeLabel(type: String): String = when (type) {
    "skill" -> "Skills"
    "agent" -> "Agents"
    "command" -> "Commands"
    else -> type
}

private fun aiTypeIcon(type: String): ImageVector = when (type) {
    "skill" -> LucideIcon.Hexagon.vector
    "agent" -> ProductIcon.Bot.vector
    "command" -> ProductIcon.SquareTerminal.vector
    else -> ProductIcon.Sparkles.vector
}

private fun mcpServerPreview(s: McpServerRow): String =
    if (s.transport == "stdio" || s.url.isEmpty()) {
        (listOf(s.command) + s.args).filter { it.isNotBlank() }.joinToString(" ")
    } else {
        s.url
    }
