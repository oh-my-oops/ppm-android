package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.files.ReadOnlyFileBody
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.design.vector
import com.hienlh.ppm.state.preview.isHtmlFile
import com.hienlh.ppm.state.workspace.WorkspaceTab
import com.hienlh.ppm.web.HtmlPreview
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Whether an editor tab shows an `.html` file as a page (`isHtml` in code-editor.tsx): a file on disk, not a buffer. */
fun isHtmlEditorTab(tab: WorkspaceTab): Boolean {
    if (tab.type != "editor") return false
    val path = tab.metaString("filePath") ?: return false
    val inline = tab.metadata["inlineContent"]
    val untitled = (tab.metadata["isUntitled"] as? JsonPrimitive)?.content == "true"
    return isHtmlFile(path) && !untitled && (inline == null || inline is JsonNull)
}

private enum class HtmlMode { Edit, Preview }

/** The `aiOpenAt` stamp the tab has already acted on; a plain holder, written while composing. */
private class SeenStamp(var value: String?)

/**
 * An `.html` file's editor tab (code-editor.tsx with `isHtml`): the toolbar both views share,
 * and below it either the page preview or native highlighted source.
 *
 * The AI's `open_file` and `open_preview` stamp the tab's `aiOpenAt` and ask for a view with
 * `aiView`: each new stamp switches to that view, and a preview loads the page again. The stamp
 * the tab came with is honoured by the view it starts in.
 */
@Composable
fun HtmlEditorBody(model: AppModel, tab: WorkspaceTab) {
    val api = model.api ?: return
    val filePath = tab.metaString("filePath") ?: return
    val projectName = tab.projectId ?: tab.metaString("projectName")
    val aiView = tab.metaString("aiView")
    val aiOpenAt = (tab.metadata["aiOpenAt"] as? JsonPrimitive)?.content
    var mode by remember { mutableStateOf(if (aiView == "code") HtmlMode.Edit else HtmlMode.Preview) }
    var revision by remember { mutableIntStateOf(0) }
    val seen = remember { SeenStamp(aiOpenAt) }
    if (aiOpenAt != null && aiOpenAt != seen.value) {
        seen.value = aiOpenAt
        when (aiView) {
            "code" -> mode = HtmlMode.Edit
            "preview" -> {
                mode = HtmlMode.Preview
                revision++
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        HtmlPreviewToolbar(
            model = model,
            filePath = filePath,
            projectName = projectName,
            mode = mode,
            onMode = { mode = it },
            onRefresh = {
                mode = HtmlMode.Preview
                revision++
            },
        )
        if (mode == HtmlMode.Preview) {
            HtmlPreview(filePath, projectName, revision, api, model.previews, Modifier.weight(1f))
        } else {
            Box(Modifier.weight(1f)) {
                ReadOnlyFileBody(model, tab)
            }
        }
    }
}

/**
 * `HtmlPreviewToolbar`: one row for both views — the file's breadcrumb from the desktop layout
 * up, Refresh while previewing, the Code / Preview switch and the menu of more actions, which is
 * not in this app yet.
 */
@Composable
private fun HtmlPreviewToolbar(
    model: AppModel,
    filePath: String,
    projectName: String?,
    mode: HtmlMode,
    onMode: (HtmlMode) -> Unit,
    onRefresh: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (mobile) 48.dp else 32.dp)
            .background(tokens.bgSolid.color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (mobile) 8.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!mobile) {
            Box(Modifier.weight(1f).clipToBounds()) {
                if (projectName != null) {
                    Breadcrumb(model, filePath, projectName)
                } else {
                    BasicText(
                        filePath.split('/', '\\').last(),
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (mode == HtmlMode.Preview) HtmlAction(ProductIcon.RefreshCw, 16.dp, null, "Refresh preview", mobile, onClick = onRefresh)
        HtmlAction(ProductIcon.Code, 14.dp, "Code", "Code", mobile, active = mode == HtmlMode.Edit) { onMode(HtmlMode.Edit) }
        HtmlAction(ProductIcon.Eye, 14.dp, "Preview", "Preview", mobile, active = mode == HtmlMode.Preview) { onMode(HtmlMode.Preview) }
        // `ml-auto md:ml-0`: on a phone, with no breadcrumb, the menu keeps to the right edge.
        if (mobile) Spacer(Modifier.weight(1f))
        HtmlAction(ProductIcon.MoreHorizontal, 16.dp, null, "More HTML actions", mobile) {
            model.toast(ErrorToastMessage("More HTML actions are not in the Android app yet.", kind = ErrorToastMessage.Kind.PLAIN))
        }
    }
}

/**
 * `actionClass`: `gap-1.5 rounded px-2 min-h-11 min-w-11 md:min-h-7 md:min-w-7 text-xs
 * text-muted-foreground hover:text-foreground hover:bg-muted`, and `bg-muted text-foreground`
 * for the view that is on. Those are joined without `cn`, so both colours are on the button and
 * the stylesheet's order picks `text-muted-foreground`: the view that is on is only filled.
 */
@Composable
private fun HtmlAction(
    icon: ProductIcon,
    iconSize: Dp,
    label: String?,
    description: String,
    mobile: Boolean,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(instant = true)
    val side = if (mobile) 44.dp else 28.dp
    val color = tokens.text.mix(tokens.text2, hover.lit.toDouble())
    Row(
        Modifier
            .sizeIn(minWidth = side, minHeight = side)
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) tokens.panel.color else androidx.compose.ui.graphics.Color.Transparent)
            .ppmClickable(pressedColor = tokens.panel.color, hoverColor = tokens.panel.color, fade = false, onClickLabel = description, source = hover.source, onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PpmIcon(icon, iconSize, color.color)
        if (label != null) BasicText(label, style = ppmText(12f, lineHeight = 16f).copy(color = color.color), maxLines = 1, softWrap = false)
    }
}

/**
 * `EditorBreadcrumb`, as it reads: the project's own folder when the path is spelled from it,
 * then each folder and the file, the file in `text-foreground font-medium`, scrolled to its end.
 * The web opens each segment's siblings in a menu; here the segments are text.
 */
@Composable
private fun Breadcrumb(model: AppModel, filePath: String, projectName: String) {
    val tokens = LocalPpmTokens.current
    val root = model.projects.firstOrNull { it.name == projectName }?.path.orEmpty().removePrefix("/")
    val path = filePath.removePrefix("/")
    val prefix: List<String>
    val segments: List<String>
    if (root.isNotEmpty() && path.startsWith("$root/")) {
        prefix = root.split('/')
        segments = path.removePrefix("$root/").split('/').filter { it.isNotEmpty() }
    } else if (ABSOLUTE_PATH.containsMatchIn(filePath)) {
        // `EditorBreadcrumbExternal`: a file outside the project, named from its drive's root.
        prefix = emptyList()
        segments = filePath.split('/', '\\').filter { it.isNotEmpty() }
    } else {
        prefix = emptyList()
        segments = path.split('/').filter { it.isNotEmpty() }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(filePath, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    val muted = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color)
    Row(
        Modifier.horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        prefix.forEachIndexed { index, part ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) Chevron()
                BasicText(part, Modifier.padding(horizontal = 4.dp, vertical = 2.dp), style = muted, maxLines = 1, softWrap = false)
            }
        }
        segments.forEachIndexed { index, name ->
            val last = index == segments.lastIndex
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index > 0 || prefix.isNotEmpty()) Chevron()
                BasicText(
                    name,
                    Modifier.widthIn(max = 120.dp).padding(horizontal = 4.dp, vertical = 2.dp),
                    style = if (last) ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color) else muted,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A POSIX path, or a Windows one from its drive. */
private val ABSOLUTE_PATH = Regex("""^(/|[A-Za-z]:[/\\])""")

/** `ChevronRight size-3 text-muted-foreground shrink-0 mx-0.5`. */
@Composable
private fun Chevron() {
    PpmIcon(ProductIcon.ChevronRight, 12.dp, LocalPpmTokens.current.text2.color, Modifier.padding(horizontal = 2.dp))
}
