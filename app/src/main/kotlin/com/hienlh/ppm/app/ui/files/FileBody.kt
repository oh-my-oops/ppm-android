package com.hienlh.ppm.app.ui.files

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.HtmlEditorBody
import com.hienlh.ppm.app.ui.MenuBox
import com.hienlh.ppm.app.ui.MenuItem
import com.hienlh.ppm.app.ui.TabPlaceholder
import com.hienlh.ppm.app.ui.editor.DeviceLspPreference
import com.hienlh.ppm.app.ui.editor.LspStatusChip
import com.hienlh.ppm.app.ui.editor.SharedBlamePreference
import com.hienlh.ppm.app.ui.editor.SharedProjectLspRegistry
import com.hienlh.ppm.app.ui.isHtmlEditorTab
import com.hienlh.ppm.client.downloadProjectFile
import com.hienlh.ppm.client.listProjectDirectory
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.vector
import com.hienlh.ppm.protocol.FileDirectoryEntry
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.files.FileDocumentClassifier
import com.hienlh.ppm.state.files.FileDocumentState
import com.hienlh.ppm.state.files.FileDocumentViewer
import com.hienlh.ppm.state.files.FileTreeNode
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException

/** The first native file-viewer slice. HTML keeps its existing preview and toolbar. */
@Composable
fun FileBody(model: AppModel, tab: WorkspaceTab) {
    if (isHtmlEditorTab(tab)) HtmlEditorBody(model, tab)
    else key(tab.id) { DocumentBody(model, tab, forceCode = false) }
}

/**
 * Source-only view. HTML owns the toolbar by default; standalone callers can opt into one.
 * A caller that shows the tab's file itself (the HTML tab reads it before it draws anything, in
 * either of its views) passes `ownsController = false`: `show` and `hide` are not counted, so a
 * view that hid the file as it left would hide it from the caller too.
 */
@Composable
fun ReadOnlyFileBody(model: AppModel, tab: WorkspaceTab, showToolbar: Boolean = false, ownsController: Boolean = true) {
    key(tab.id) { DocumentBody(model, tab, forceCode = true, showToolbar = showToolbar, ownsController = ownsController) }
}

/**
 * Whether the tab's own toolbar (the file's path, Wrap, Refresh) sits above [viewer]'s view. The web's editor returns an
 * image, a PDF and a document as their previews alone (code-editor.tsx), so none of them has it, not even for the line a
 * preview says when it cannot show its file; the PDF has a row of its own, with the path and Open in new tab.
 */
internal fun fileHasToolbar(viewer: FileDocumentViewer): Boolean =
    viewer != FileDocumentViewer.IMAGE && viewer != FileDocumentViewer.PDF && viewer != FileDocumentViewer.DOCX

/** Empty inline content is still a buffer; an untitled buffer never reads a metadata path. */
internal fun fileBufferContent(tab: WorkspaceTab): String? = tab.metadata["inlineContent"].string
    ?: if (tab.metadata["isUntitled"].bool == true) tab.metadata["unsavedContent"].string.orEmpty() else null

@Composable
private fun DocumentBody(model: AppModel, tab: WorkspaceTab, forceCode: Boolean, showToolbar: Boolean = true, ownsController: Boolean = true) {
    val path = tab.metaString("filePath")
    val buffer = fileBufferContent(tab)
    val viewer = if (buffer != null || forceCode) FileDocumentViewer.TEXT else FileDocumentClassifier.classify(path.orEmpty())
    if (viewer == FileDocumentViewer.VIDEO || viewer == FileDocumentViewer.AUDIO) {
        FileMediaView(model, tab)
        return
    }
    if (viewer == FileDocumentViewer.SQLITE) {
        com.hienlh.ppm.app.ui.database.SqliteViewerTabBody(model, tab)
        return
    }
    if (viewer !in setOf(FileDocumentViewer.TEXT, FileDocumentViewer.MARKDOWN, FileDocumentViewer.CSV, FileDocumentViewer.IMAGE, FileDocumentViewer.PDF, FileDocumentViewer.DOCX)) {
        TabPlaceholder(ProductIcon.FileCode.vector, tab.title, "This file viewer is not in the Android app yet.")
        return
    }
    val project = tab.projectId ?: tab.metaString("projectName")
    val controller = if (buffer == null) remember(model, model.api, tab.id, path, project) { model.fileDocument(tab) } else null
    if (buffer == null && controller == null) {
        FileNoteView(unreadableFileNote(path), Modifier.fillMaxSize().background(LocalPpmTokens.current.bgSolid.color))
        return
    }
    val visible = com.hienlh.ppm.app.ui.LocalWindowContentVisible.current
    if (controller != null && ownsController) DisposableEffect(controller, forceCode, visible) {
        if (visible) controller.show(readAsText = forceCode) else controller.hide()
        onDispose { controller.hide() }
    }
    val state = controller?.state?.collectAsState()?.value ?: FileDocumentState(project, path.orEmpty(), content = buffer, stale = false)
    val colors = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val line = (tab.metadata["lineNumber"] as? JsonPrimitive)?.longOrNull
    val end = (tab.metadata["endLine"] as? JsonPrimitive)?.longOrNull
    val reveal = (tab.metadata["revealAt"] as? JsonPrimitive)?.content
    val aiStamp = (tab.metadata["aiOpenAt"] as? JsonPrimitive)?.content
    val aiView = tab.metaString("aiView")
    var preview by remember(path) { mutableStateOf(viewer == FileDocumentViewer.MARKDOWN && aiView != "code" && line == null) }
    var csvTable by remember(path) { mutableStateOf(true) }
    var wrap by remember { mutableStateOf(mobile) }
    // The web's Refresh button spins, disabled, while the reload it asked for runs; a reload the watcher asked for shows nothing.
    var refreshing by remember(controller) { mutableStateOf(false) }
    LaunchedEffect(refreshing, controller) {
        if (refreshing && controller != null) {
            controller.state.first { !it.loading }
            refreshing = false
        }
    }
    LaunchedEffect(aiStamp, reveal) {
        if (aiView == "code" || (line != null && reveal != null)) preview = false
        else if (aiView == "preview") preview = true
    }
    // Until there is a file to show, or when it cannot be, the web shows one centred line, with no toolbar or path around it.
    val note = fileNote(viewer, state)
    if (note != null) {
        FileNoteView(note, Modifier.fillMaxSize().background(colors.bgSolid.color))
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = model.api
    val isUntitled = tab.metadata["isUntitled"].bool == true
    val inlineContent = tab.metadata["inlineContent"].string
    val isExternalFile = !path.isNullOrBlank() && (path.startsWith("/") || Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(path))
    val isProjectFile = !isUntitled && inlineContent == null && !path.isNullOrBlank() && !project.isNullOrBlank()
    val isRelativeProjectFile = isProjectFile && !isExternalFile
    val metaLang = tab.metaString("inlineLanguage") ?: tab.metaString("language")
    var localLangOverride by remember(tab.id, metaLang) { mutableStateOf(metaLang) }
    val effectiveHighlightLanguage = fileCodeLanguage(path, localLangOverride)
    val pickerLanguageId = remember(path, localLangOverride) { webMonacoPickerId(path, localLangOverride) }
    val onPickLanguage: (String) -> Unit = { picked ->
        localLangOverride = picked
        model.patchTabMetadata(tab.id, "language" to JsonPrimitive(picked))
    }

    val minimalMode = model.info?.minimalMode == true
    val lspEnabled by DeviceLspPreference.enabled.collectAsState()
    val lspServable = !minimalMode && isRelativeProjectFile
    val lspBridge = remember(api, project, lspServable, lspEnabled) {
        if (api != null && lspServable && lspEnabled && project != null) {
            SharedProjectLspRegistry.getOrCreate(api, project)
        } else {
            null
        }
    }
    val lspStatus = if (lspBridge != null && path != null) {
        lspBridge.statusFlow(path).collectAsState().value
    } else {
        null
    }
    val lspDiagnostics = if (lspBridge != null && path != null) {
        lspBridge.diagnosticsFlow(path).collectAsState().value
    } else {
        emptyList()
    }
    val blameEnabled by SharedBlamePreference.enabled.collectAsState()
    val canBlame = !minimalMode && isRelativeProjectFile

    val downloadLauncher = rememberLauncherForActivityResult(CreateExplorerDocument()) { uri ->
        if (uri == null || api == null || project == null || path.isNullOrBlank()) return@rememberLauncherForActivityResult
        val fileName = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(uri, "w")
                        ?: throw IOException("Cannot open the chosen destination.")
                    out.use { api.downloadProjectFile(project, path, false, it) {} }
                }
                model.toast(ErrorToastMessage("Saved $fileName.", kind = ErrorToastMessage.Kind.INFO))
            } catch (ce: CancellationException) {
                removeDownload(context, uri)
                throw ce
            } catch (err: Exception) {
                removeDownload(context, uri)
                model.toast(ErrorToastMessage("Download failed", err.message))
            }
        }
    }

    Column(Modifier.fillMaxSize().background(colors.bgSolid.color)) {
        // Desktop Breadcrumb + Toolbar bar (`src/web/components/editor/code-editor.tsx:874-926`, `hidden md:flex h-7`)
        if (!mobile && showToolbar && fileHasToolbar(viewer)) {
            if (isProjectFile && path != null && project != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .background(colors.bgSolid.color)
                        .cssBorder(colors.border.color, sides = CssSides.Bottom),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EditorBreadcrumbBar(
                        model = model,
                        tabId = tab.id,
                        projectName = project,
                        filePath = path,
                        isExternal = isExternalFile,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    if (lspServable) {
                        LspStatusChip(
                            enabled = lspEnabled,
                            onToggle = { DeviceLspPreference.setEnabled(it) },
                            status = lspStatus,
                            diagnostics = lspDiagnostics,
                            onInstall = if (lspBridge != null) {
                                { serverId -> lspBridge.installServer(serverId) }
                            } else null,
                            onOpenProblems = { model.openProblems() },
                        )
                    }
                    EditorLanguagePicker(
                        value = pickerLanguageId,
                        onChange = onPickLanguage,
                    )
                    EditorDesktopToolbar(
                        viewer = viewer,
                        preview = preview,
                        onPreviewChange = { preview = it },
                        csvTable = csvTable,
                        onCsvTableChange = { csvTable = it },
                        wordWrap = wrap,
                        onToggleWordWrap = { wrap = !wrap },
                        inlineBlame = blameEnabled,
                        onToggleInlineBlame = if (canBlame) ({ SharedBlamePreference.setEnabled(!blameEnabled) }) else null,
                        onRefresh = if (controller != null) ({
                            refreshing = true
                            controller.refresh()
                        }) else null,
                        refreshing = refreshing,
                        onDownload = if (isRelativeProjectFile && api != null) ({
                            val name = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
                            val node = FileTreeNode(name, path, isDirectory = false, isIgnored = false)
                            runCatching {
                                downloadLauncher.launch(explorerDownloadName(node) to explorerDownloadMime(node))
                            }.onFailure { err ->
                                model.toast(ErrorToastMessage(err.message ?: "The system file picker is unavailable."))
                            }
                        }) else null,
                    )
                }
            } else if (inlineContent == null && (isUntitled || (!path.isNullOrBlank() && project.isNullOrBlank()))) {
                val untitledNum = (tab.metadata["untitledNumber"] as? JsonPrimitive)?.intOrNull ?: 1
                val barTitle = if (isUntitled) {
                    "Untitled-$untitledNum"
                } else {
                    path?.substringAfterLast('/')?.substringAfterLast('\\')?.ifBlank { "Untitled" } ?: "Untitled"
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .background(colors.bgSolid.color)
                        .cssBorder(colors.border.color, sides = CssSides.Bottom)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        barTitle,
                        Modifier.weight(1f),
                        style = ppmText(12f, lineHeight = 16f).copy(color = colors.text2.color),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    EditorLanguagePicker(
                        value = pickerLanguageId,
                        onChange = onPickLanguage,
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                viewer == FileDocumentViewer.IMAGE && state.raw != null -> FileImageView(state.raw!!, path.orEmpty())
                viewer == FileDocumentViewer.PDF && state.raw != null -> FilePdfView(model, state.raw!!, path.orEmpty())
                state.content != null -> {
                    if (viewer == FileDocumentViewer.DOCX) key(state.revision) { FileDocxView(model, state.content!!) }
                    else if (viewer == FileDocumentViewer.CSV && csvTable) FileCsvView(state.content!!, wrap)
                    else if (viewer == FileDocumentViewer.MARKDOWN && preview) FileMarkdownView(model, project, state.content!!)
                    else if (!ownsController || tab.metadata["readOnly"].bool == true) {
                        FileCodeView(
                            state.content!!,
                            effectiveHighlightLanguage,
                            wrap, line, end, reveal,
                        )
                    } else {
                        com.hienlh.ppm.app.ui.editor.EditableFileCodeView(
                            model = model,
                            project = project,
                            path = path,
                            initialContent = state.content!!,
                            initialLanguage = effectiveHighlightLanguage,
                            wrap = wrap,
                            onToggleWrap = { wrap = !wrap },
                            lineNumber = line,
                            endLine = end,
                            revealAt = reveal,
                            isUntitled = isUntitled,
                            readOnly = buffer != null && !isUntitled,
                            useWebHeader = true,
                            onDirtyChange = { isDirty -> controller?.setDirty(isDirty) },
                        )
                    }
                }
            }
        }
    }
}

/** Monaco language IDs offered in `src/web/components/editor/editor-language-picker.tsx`. */
private val WEB_EDITOR_LANGUAGES: List<Pair<String, String>> = listOf(
    "plaintext" to "Plain Text",
    "javascript" to "JavaScript",
    "typescript" to "TypeScript",
    "sql" to "SQL",
    "json" to "JSON",
    "xml" to "XML",
    "html" to "HTML",
    "css" to "CSS",
    "python" to "Python",
    "markdown" to "Markdown",
    "yaml" to "YAML",
    "shell" to "Shell",
    "dotenv" to "Dotenv",
)

private fun webMonacoPickerId(path: String?, override: String?): String {
    if (!override.isNullOrBlank()) {
        val norm = when (override.lowercase()) {
            "md", "mdx" -> "markdown"
            "text", "txt" -> "plaintext"
            "sh", "bash" -> "shell"
            "yml" -> "yaml"
            else -> override.lowercase()
        }
        if (WEB_EDITOR_LANGUAGES.any { it.first == norm }) return norm
    }
    val name = path.orEmpty().substringAfterLast('/').substringAfterLast('\\')
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "js", "jsx" -> "javascript"
        "ts", "tsx" -> "typescript"
        "py" -> "python"
        "html", "htm" -> "html"
        "css" -> "css"
        "json" -> "json"
        "xml" -> "xml"
        "md", "mdx" -> "markdown"
        "yaml", "yml" -> "yaml"
        "sh", "bash" -> "shell"
        "sql" -> "sql"
        else -> if (name.lowercase() == ".env" || name.lowercase().startsWith(".env.")) "dotenv" else "plaintext"
    }
}

@Composable
private fun EditorBreadcrumbBar(
    model: AppModel,
    tabId: String,
    projectName: String,
    filePath: String,
    isExternal: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPpmTokens.current
    val density = LocalDensity.current
    val projectRoot = model.projects.firstOrNull { it.name == projectName }?.path.orEmpty().trimStart('/')
    val normFile = filePath.trimStart('/')
    val (prefixParts, relParts) = remember(normFile, projectRoot, isExternal) {
        if (!isExternal && projectRoot.isNotEmpty() && normFile.startsWith("$projectRoot/")) {
            val rel = normFile.removePrefix("$projectRoot/")
            projectRoot.split('/').filter { it.isNotEmpty() } to rel.split('/').filter { it.isNotEmpty() }
        } else {
            emptyList<String>() to normFile.split('/').filter { it.isNotEmpty() }
        }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(relParts, scroll.maxValue) {
        scroll.scrollTo(scroll.maxValue)
    }

    var openSegIdx by remember(filePath) { mutableStateOf<Int?>(null) }
    var siblings by remember { mutableStateOf<List<FileDirectoryEntry>?>(null) }
    val api = model.api

    LaunchedEffect(openSegIdx, api, projectName, relParts) {
        val idx = openSegIdx ?: return@LaunchedEffect
        if (isExternal || api == null) {
            siblings = emptyList()
            return@LaunchedEffect
        }
        siblings = null
        val parentDir = relParts.take(idx).joinToString("/")
        siblings = runCatching { api.listProjectDirectory(projectName, parentDir) }
            .getOrDefault(emptyList())
            .sortedWith(compareBy<FileDirectoryEntry> { !it.isDirectory }.thenBy { it.name })
    }

    Row(
        modifier = modifier.horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        prefixParts.forEachIndexed { i, part ->
            if (i > 0) {
                PpmIcon(ProductIcon.ChevronRight, 12.dp, colors.text2.color, Modifier.padding(horizontal = 2.dp))
            }
            BasicText(
                part,
                Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                style = ppmText(12f, lineHeight = 16f).copy(color = colors.text2.color),
                maxLines = 1,
                softWrap = false,
            )
        }
        relParts.forEachIndexed { i, seg ->
            if (i > 0 || prefixParts.isNotEmpty()) {
                PpmIcon(ProductIcon.ChevronRight, 12.dp, colors.text2.color, Modifier.padding(horizontal = 2.dp))
            }
            val isLast = i == relParts.lastIndex
            Box {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .ppmClickable(
                            enabled = !isExternal,
                            pressedColor = colors.panel.color,
                            hoverColor = colors.panel.color,
                            onClickLabel = seg,
                            onClick = { openSegIdx = if (openSegIdx == i) null else i },
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    BasicText(
                        seg,
                        Modifier.widthIn(max = 120.dp),
                        style = ppmText(
                            12f,
                            if (isLast) FontWeight.Medium else FontWeight.Normal,
                            lineHeight = 16f,
                        ).copy(color = if (isLast) colors.text.color else colors.text2.color),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (openSegIdx == i && !isExternal) {
                    val parentDir = relParts.take(i).joinToString("/")
                    val activePath = relParts.take(i + 1).joinToString("/")
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(0, with(density) { 24.dp.roundToPx() }),
                        onDismissRequest = { openSegIdx = null },
                        properties = PopupProperties(focusable = true),
                    ) {
                        MenuBox(
                            modifier = Modifier
                                .heightIn(max = 300.dp)
                                .verticalScroll(rememberScrollState()),
                            minWidth = 140.dp,
                        ) {
                            val entries = siblings
                            if (entries == null) {
                                MenuItem("Loading…", onClick = {}, enabled = false)
                            } else if (entries.isEmpty()) {
                                MenuItem("Empty", onClick = {}, enabled = false)
                            } else {
                                for (entry in entries) {
                                    val entryPath = if (parentDir.isEmpty()) entry.name else "$parentDir/${entry.name}"
                                    MenuItem(
                                        label = entry.name,
                                        lit = entryPath == activePath,
                                        icon = {
                                            PpmIcon(
                                                if (entry.isDirectory) ProductIcon.Folder else ProductIcon.File,
                                                14.dp,
                                                colors.text2.color,
                                            )
                                        },
                                        iconEnd = 0.dp,
                                        onClick = {
                                            openSegIdx = null
                                            if (!entry.isDirectory) {
                                                model.updateTabTitleAndMetadata(
                                                    tabId = tabId,
                                                    title = entry.name,
                                                    "filePath" to JsonPrimitive(entryPath),
                                                    "projectName" to JsonPrimitive(projectName),
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorLanguagePicker(
    value: String,
    onChange: (String) -> Unit,
) {
    val colors = LocalPpmTokens.current
    val density = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    val label = WEB_EDITOR_LANGUAGES.firstOrNull { it.first == value }?.second ?: "Plain Text"
    val selectShape = RoundedCornerShape(4.dp)

    Row(
        Modifier
            .cssBorder(colors.border.color, sides = CssSides.Start)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PpmIcon(ProductIcon.FileCode, 12.dp, colors.text2.color)
        Box {
            Row(
                Modifier
                    .height(20.dp)
                    .widthIn(max = 120.dp)
                    .clip(selectShape)
                    .cssBorder(colors.border.color, selectShape)
                    .ppmClickable(
                        pressedColor = colors.panel.color,
                        hoverColor = colors.panel.color,
                        onClickLabel = "Select editor language",
                        onClick = { open = !open },
                    )
                    .padding(start = 5.dp, end = 4.dp)
                    .testTag("editor-language-chip"),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    label,
                    style = ppmText(10f, lineHeight = 14f).copy(color = colors.text.color),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                PpmIcon(ProductIcon.ChevronDown, 10.dp, colors.text.color)
            }
            if (open) {
                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(0, with(density) { 22.dp.roundToPx() }),
                    onDismissRequest = { open = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    MenuBox(
                        modifier = Modifier
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                        minWidth = 120.dp,
                    ) {
                        for ((id, name) in WEB_EDITOR_LANGUAGES) {
                            MenuItem(
                                label = name,
                                lit = id == value,
                                onClick = {
                                    open = false
                                    onChange(id)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorDesktopToolbar(
    viewer: FileDocumentViewer,
    preview: Boolean,
    onPreviewChange: (Boolean) -> Unit,
    csvTable: Boolean,
    onCsvTableChange: (Boolean) -> Unit,
    wordWrap: Boolean,
    onToggleWordWrap: () -> Unit,
    inlineBlame: Boolean,
    onToggleInlineBlame: (() -> Unit)?,
    onRefresh: (() -> Unit)?,
    refreshing: Boolean,
    onDownload: (() -> Unit)?,
) {
    Row(
        Modifier.padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (viewer == FileDocumentViewer.MARKDOWN) {
            EditorToolbarButton(ProductIcon.Code, "Edit", active = !preview) { onPreviewChange(false) }
            EditorToolbarButton(ProductIcon.Eye, "Preview", active = preview) { onPreviewChange(true) }
        }
        if (viewer == FileDocumentViewer.CSV) {
            EditorToolbarButton(ProductIcon.Table, "Table", active = csvTable) { onCsvTableChange(true) }
            EditorToolbarButton(ProductIcon.Code, "Raw", active = !csvTable) { onCsvTableChange(false) }
        }
        EditorToolbarButton(ProductIcon.WrapText, "Wrap", description = "Wrap lines", active = wordWrap, onClick = onToggleWordWrap)
        if (onToggleInlineBlame != null) {
            EditorToolbarButton(ProductIcon.UserRound, "Blame", active = inlineBlame, onClick = onToggleInlineBlame)
        }
        if (onRefresh != null) {
            EditorToolbarButton(
                ProductIcon.RefreshCw,
                "Refresh",
                description = "Refresh file",
                active = false,
                enabled = !refreshing,
                spinning = refreshing,
                onClick = onRefresh,
            )
        }
        if (onDownload != null) {
            EditorToolbarButton(ProductIcon.Download, "Download", active = false, onClick = onDownload)
        }
    }
}

@Composable
private fun EditorToolbarButton(
    icon: ProductIcon,
    label: String,
    active: Boolean,
    description: String = label,
    enabled: Boolean = true,
    spinning: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalPpmTokens.current
    val fg = when {
        !enabled -> colors.text3.color
        active -> colors.text.color
        else -> colors.text2.color
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) colors.panel.color else Color.Transparent)
            .semantics { contentDescription = description }
            .ppmClickable(
                enabled = enabled,
                pressedColor = colors.panel.color,
                hoverColor = colors.panel.color,
                onClickLabel = description,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PpmIcon(icon, 12.dp, fg, spinning = spinning)
        BasicText(label, style = ppmText(12f, lineHeight = 16f).copy(color = fg))
    }
}

@Composable
internal fun FileMessage(title: String, detail: String? = null) {
    val colors = LocalPpmTokens.current
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(title, style = ppmText(14f, lineHeight = 20f).copy(color = colors.text2.color))
        if (detail != null) BasicText(detail, Modifier.padding(top = 8.dp), style = ppmText(12f, lineHeight = 16f).copy(color = colors.text3.color))
    }
}

@Composable
internal fun FileAction(
    icon: ProductIcon, description: String, label: String? = null, active: Boolean = false, enabled: Boolean = true,
    spinning: Boolean = false, onClick: () -> Unit,
) {
    val colors = LocalPpmTokens.current
    val side = if (LocalPpmMobile.current) 44.dp else 28.dp
    Row(Modifier.sizeIn(minWidth = side, minHeight = side)
        .background(if (active) colors.panel.color else Color.Transparent)
        .semantics { contentDescription = description }
        .ppmClickable(enabled = enabled, pressedColor = colors.panel.color, hoverColor = colors.panel.color,
            onClickLabel = description, onClick = onClick).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        PpmIcon(icon, 16.dp, if (enabled) colors.text2.color else colors.text3.color, spinning = spinning)
        if (label != null) BasicText(label, style = ppmText(12f, lineHeight = 16f).copy(color = colors.text2.color))
    }
}
