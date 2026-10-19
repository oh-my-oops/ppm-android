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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ProviderLogo
import com.hienlh.ppm.design.ProviderLogos
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.workspace.WorkspaceTab
import com.hienlh.ppm.state.workspace.tabSessionId
import kotlinx.coroutines.launch

/**
 * What a tab shows. The layout is the web's, so it can hold every kind of tab the web opens;
 * the kinds this app cannot show yet say so rather than disappear, since closing them here
 * closes them on every device.
 */
@Composable
fun TabBody(model: AppModel, tab: WorkspaceTab) {
    when (tab.type) {
        "settings" -> SettingsTab(model)
        "chat" -> {
            val chat = model.chat(tab.id)
            when {
                chat != null -> key(chat) { ChatPane(model, chat) }
                tabSessionId(tab) == null -> UnstartedChat(model, tab)
            }
        }
        else -> TabPlaceholder(tabTypeIcon(tab.type), tab.title, "This tab is not in the Android app yet.")
    }
}

/** A chat tab the web opened before its first message: it gets a session once asked to. */
@Composable
private fun UnstartedChat(model: AppModel, tab: WorkspaceTab) {
    TabPlaceholder(ProductIcon.MessageSquare, tab.title.ifEmpty { "New Chat" }, "This chat has no messages yet.") {
        PrimaryButton("Start chat", Modifier.padding(top = 16.dp), height = 36.dp) {
            model.scope.launch { model.startChat(tab.id) }
        }
    }
}

@Composable
private fun TabPlaceholder(icon: ProductIcon, title: String, detail: String, action: @Composable () -> Unit = {}) {
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
        action()
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
 * `TAB_TYPE_ICONS`. A file tab is drawn with its file's icon on the web; until this app has those,
 * with the type's. `FileDiff` and `GitCommitHorizontal` are not among this app's icons yet.
 */
fun tabTypeIcon(type: String): ProductIcon = when (type) {
    "terminal" -> ProductIcon.Terminal
    "chat" -> ProductIcon.MessageSquare
    "editor" -> ProductIcon.FileCode
    "database" -> ProductIcon.Table
    "db-structure" -> ProductIcon.FolderTree
    "db-sql" -> ProductIcon.Code
    "db-query" -> ProductIcon.SquareTerminal
    "db-impexp" -> ProductIcon.ArrowLeftRight
    "db-connection" -> ProductIcon.Plug
    "sqlite" -> ProductIcon.Database
    "git-diff", "branch-review", "session-review", "git-review", "conflict-editor" -> ProductIcon.File
    "settings", "system-monitor" -> ProductIcon.Settings
    "git-log" -> ProductIcon.GitBranch
    "ai-resource" -> ProductIcon.Sparkles
    "group" -> ProductIcon.Users
    "problems" -> ProductIcon.CircleX
    "design" -> ProductIcon.Palette
    "android" -> ProductIcon.Smartphone
    "web-preview" -> ProductIcon.Globe
    "logs" -> ProductIcon.ScrollText
    else -> ProductIcon.Puzzle
}
