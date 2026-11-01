package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.HtmlEditorBody
import com.hienlh.ppm.app.ui.TabPlaceholder
import com.hienlh.ppm.app.ui.isHtmlEditorTab
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.vector
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.files.FileDocumentClassifier
import com.hienlh.ppm.state.files.FileDocumentState
import com.hienlh.ppm.state.files.FileDocumentViewer
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

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
    val code = viewer !in setOf(FileDocumentViewer.IMAGE, FileDocumentViewer.PDF, FileDocumentViewer.DOCX) && (viewer != FileDocumentViewer.MARKDOWN || !preview)
    // Until there is a file to show, or when it cannot be, the web shows one centred line, with no toolbar or path around it.
    val note = fileNote(viewer, state)
    if (note != null) {
        FileNoteView(note, Modifier.fillMaxSize().background(colors.bgSolid.color))
        return
    }
    Column(Modifier.fillMaxSize().background(colors.bgSolid.color)) {
        if (showToolbar && fileHasToolbar(viewer)) Row(
            Modifier.fillMaxWidth().heightIn(min = if (mobile) 48.dp else 32.dp)
                .cssBorder(colors.border.color, sides = CssSides.Bottom).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(path ?: tab.title, Modifier.weight(1f).padding(end = 8.dp),
                style = ppmText(12f, lineHeight = 16f).copy(color = colors.text2.color),
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            if (viewer == FileDocumentViewer.MARKDOWN) {
                FileAction(ProductIcon.Code, "Code", active = !preview) { preview = false }
                FileAction(ProductIcon.Eye, "Preview", active = preview) { preview = true }
            }
            if (viewer == FileDocumentViewer.CSV) {
                FileAction(ProductIcon.Table, "Table", active = csvTable) { csvTable = true }
                FileAction(ProductIcon.Code, "Raw", active = !csvTable) { csvTable = false }
            }
            if (code) FileAction(ProductIcon.WrapText, "Wrap lines", active = wrap) { wrap = !wrap }
            if (controller != null) FileAction(ProductIcon.RefreshCw, "Refresh file", enabled = !refreshing, spinning = refreshing) {
                refreshing = true
                controller.refresh()
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
                    else FileCodeView(state.content!!,
                        fileCodeLanguage(path, tab.metaString("inlineLanguage") ?: tab.metaString("language")),
                        wrap, line, end, reveal)
                }
            }
        }
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
