package com.hienlh.ppm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.extensions.ExtensionIcons
import com.hienlh.ppm.app.ui.extensions.ExtensionWebviewBody
import com.hienlh.ppm.app.ui.files.FileBody
import com.hienlh.ppm.app.ui.git.BranchReviewBody
import com.hienlh.ppm.app.ui.git.GitDiffBody
import com.hienlh.ppm.app.ui.git.GitLogBody
import com.hienlh.ppm.app.ui.git.GitReviewBody
import com.hienlh.ppm.design.FileIcon
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ProviderLogo
import com.hienlh.ppm.design.ProviderLogos
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.vector
import com.hienlh.ppm.state.workspace.WorkspaceTab
import com.hienlh.ppm.state.workspace.safePreviewUrl
import com.hienlh.ppm.web.WebPreviewTab
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor
import kotlin.math.max

/**
 * What a tab shows. The layout is the web's, so it can hold every kind of tab the web opens;
 * the kinds this app cannot show yet say so rather than disappear, since closing them here
 * closes them on every device.
 */
@Composable
fun TabBody(model: AppModel, tab: WorkspaceTab) {
    when (tab.type) {
        "settings" -> SettingsTab(model)
        "system-monitor" -> com.hienlh.ppm.app.ui.sysmon.SystemMonitorTabBody(model, tab)
        "logs" -> com.hienlh.ppm.app.ui.logs.LogsTabBody(model, tab)
        "remote-desktop" -> com.hienlh.ppm.app.ui.remotedesktop.RemoteDesktopTab(model, tab.id)
        "android" -> com.hienlh.ppm.app.ui.androiddevice.AndroidTabBody(model, tab)
        "chat" -> model.chat(tab.id)?.let { chat -> key(chat) { ChatPane(model, chat) } }
        "session-review" -> SessionReviewBody(model, tab)
        "web-preview" -> WebPreviewBody(model, tab)
        "group" -> GroupChatBody(model, tab)
        "terminal" -> TerminalBody(model, tab)
        "editor" -> FileBody(model, tab)
        "ai-resource" -> com.hienlh.ppm.app.ui.editor.AiResourceEditorBody(model, tab)
        "problems" -> com.hienlh.ppm.app.ui.editor.ProblemsTabBody(model, tab)
        "conflict-editor" -> com.hienlh.ppm.app.ui.editor.ConflictEditorTabBody(model, tab)
        "database" -> com.hienlh.ppm.app.ui.database.DatabaseTableTabBody(model, tab)
        "db-connection" -> com.hienlh.ppm.app.ui.database.DbConnectionFormTabBody(model, tab)
        "db-sql" -> com.hienlh.ppm.app.ui.database.DbSqlObjectTabBody(model, tab)
        "db-query" -> com.hienlh.ppm.app.ui.database.DatabaseQueryTabBody(model, tab)
        "git-review" -> GitReviewBody(model, tab)
        "branch-review" -> BranchReviewBody(model, tab)
        "git-log" -> GitLogBody(model, tab)
        "git-diff" -> GitDiffBody(model, tab)
        "extension" -> ExtensionWebviewBody(model, tab)
        else -> {
            TabPlaceholder(tabTypeIcon(tab.type), tab.title, "This tab is not in the Android app yet.")
        }
    }
}

/** A web server on the host in a tab (`WebPreviewTab`), the address it moves to kept in the tab as `currentUrl`. */
@Composable
private fun WebPreviewBody(model: AppModel, tab: WorkspaceTab) {
    val app = model.api?.baseUrl
    WebPreviewTab(
        requestedUrl = safePreviewUrl(tab.metaString("url"), app),
        savedUrl = safePreviewUrl(tab.metaString("currentUrl"), app),
        via = tab.metaString("via"),
        aiOpenAt = (tab.metadata["aiOpenAt"] as? JsonPrimitive)?.content.orEmpty(),
        appUrl = app,
        onNavigate = { url -> model.keepPreviewUrl(tab.id, url) },
        onCopyFailed = { model.toast(ErrorToastMessage("Failed to copy URL")) },
    )
}

@Composable
internal fun TabPlaceholder(icon: ImageVector, title: String, detail: String) {
    val tokens = LocalPpmTokens.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PpmIcon(icon, 32.dp, tokens.text3.color)
        BasicText(
            title,
            Modifier.padding(top = 12.dp).widthIn(max = 320.dp),
            style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color, textAlign = TextAlign.Center),
            maxLines = 2,
        )
        BasicText(
            detail,
            Modifier.padding(top = 4.dp).widthIn(max = 320.dp),
            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color, textAlign = TextAlign.Center),
        )
    }
}

/** A tab's title as its tab bar shows it: a chat's comes from the session list, which carries a rename made anywhere. */
fun tabTitle(model: AppModel, tab: WorkspaceTab): String {
    val chat = model.chat(tab.id) ?: return tab.title.ifEmpty { if (tab.type == "chat") "New Chat" else tab.type }
    return model.title(chat, chat.controller?.state?.value?.title)
}

/** The tab types whose title names a file rather than a kind of panel (`FILE_TAB_TYPES`). */
private val FILE_TAB_TYPES = setOf("editor", "git-diff", "conflict-editor")

/** The text of the block the web wraps a tab's icon in: [font] px on lines [line] px tall. */
data class IconLine(val font: Float, val line: Float)

/**
 * `getTabIcon`: a tab holding a file by that file's icon, a chat by its provider's logo (Claude
 * when it has none yet), anything else by its kind.
 *
 * A file's icon is an inline-block, so where the web wraps the icon in a block of its own (the
 * tab bar's and the tab switcher's `span.relative`) it sits on the baseline of a line of that
 * block's text, [line], and the wrapper reaches as far under it as that text does: in the tab
 * bar's 12/16 at 2x, 2.25 px above where a glyph, which is a block there, is centred.
 */
@Composable
fun TabIcon(tab: WorkspaceTab, tint: Rgba, size: Dp = 16.dp, line: IconLine? = null) {
    if (tab.type in FILE_TAB_TYPES) {
        val path = tab.metaString("filePath")?.takeIf { it.isNotEmpty() } ?: tab.title
        if (path.isNotEmpty()) {
            FileIcon(path, if (line == null) Modifier else Modifier.onLine(line, size), size = size)
            return
        }
    }
    val provider = tab.metaString("providerId") ?: "claude"
    if (tab.type == "chat" && ProviderLogos.has(provider)) {
        ProviderLogo(provider, size, tint.color)
    } else if (tab.type == "extension") {
        PpmIcon(ExtensionIcons.viewTypeIcon(null, tab.metaString("viewType")), size, tint.color)
    } else {
        PpmIcon(tabTypeIcon(tab.type), size, tint.color)
    }
}

/** Room above and below an inline-block [size] tall on the baseline of a line of [line]'s text. */
@Composable
private fun Modifier.onLine(line: IconLine, size: Dp): Modifier {
    val density = LocalDensity.current.density
    val font = line.font * density
    val height = line.line * density
    val (up, down) = PpmFonts.chromeAscentDescent(font, PpmFonts.SANS_ASCENT, PpmFonts.SANS_DESCENT)
    val strutAbove = (up + floor((height - up - down) / 2)) / density
    val below = PpmFonts.chromeBelowBaseline(font, height, PpmFonts.SANS_ASCENT, PpmFonts.SANS_DESCENT) / density
    return padding(top = (max(strutAbove, size.value) - size.value).dp, bottom = below.dp)
}

/** `TAB_TYPE_ICONS`, product icons and the two the web keeps on lucide. */
fun tabTypeIcon(type: String): ImageVector = when (type) {
    "terminal" -> ProductIcon.Terminal.vector
    "chat" -> ProductIcon.MessageSquare.vector
    "editor" -> ProductIcon.FileCode.vector
    "database" -> ProductIcon.Table.vector
    "db-structure" -> ProductIcon.FolderTree.vector
    "db-sql" -> ProductIcon.Code.vector
    "db-query" -> ProductIcon.SquareTerminal.vector
    "db-impexp" -> ProductIcon.ArrowLeftRight.vector
    "db-connection" -> ProductIcon.Plug.vector
    "sqlite" -> ProductIcon.Database.vector
    "git-diff", "branch-review", "session-review", "git-review", "conflict-editor" -> LucideIcon.FileDiff.vector
    "settings", "system-monitor" -> ProductIcon.Settings.vector
    "git-log" -> LucideIcon.GitCommitHorizontal.vector
    "ai-resource" -> ProductIcon.Sparkles.vector
    "group" -> ProductIcon.Users.vector
    "problems" -> ProductIcon.CircleX.vector
    "design" -> ProductIcon.Palette.vector
    "android" -> ProductIcon.Smartphone.vector
    "web-preview" -> ProductIcon.Globe.vector
    "logs" -> ProductIcon.ScrollText.vector
    "remote-desktop" -> ProductIcon.Monitor.vector
    else -> ProductIcon.Puzzle.vector
}
