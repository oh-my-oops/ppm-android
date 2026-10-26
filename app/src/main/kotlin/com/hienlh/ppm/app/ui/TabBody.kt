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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
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

/**
 * What a tab shows. The layout is the web's, so it can hold every kind of tab the web opens;
 * the kinds this app cannot show yet say so rather than disappear, since closing them here
 * closes them on every device.
 */
@Composable
fun TabBody(model: AppModel, tab: WorkspaceTab) {
    when (tab.type) {
        "settings" -> SettingsTab(model)
        "chat" -> model.chat(tab.id)?.let { chat -> key(chat) { ChatPane(model, chat) } }
        "session-review" -> SessionReviewBody(model, tab)
        "web-preview" -> WebPreviewBody(model, tab)
        else -> if (isHtmlEditorTab(tab)) {
            HtmlEditorBody(model, tab)
        } else {
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
        onCopyFailed = { model.toast = ErrorToastMessage("Failed to copy URL") },
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

/** `getTabIcon`: a chat by its provider's logo (Claude when it has none yet), anything else by its kind. */
@Composable
fun TabIcon(tab: WorkspaceTab, tint: Rgba) {
    val provider = tab.metaString("providerId") ?: "claude"
    if (tab.type == "chat" && ProviderLogos.has(provider)) {
        ProviderLogo(provider, 16.dp, tint.color)
    } else {
        PpmIcon(tabTypeIcon(tab.type), 16.dp, tint.color)
    }
}

/**
 * `TAB_TYPE_ICONS`, product icons and the two the web keeps on lucide. A file tab is drawn with
 * its file's icon on the web; until this app has those, with the type's.
 */
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
    else -> ProductIcon.Puzzle.vector
}
