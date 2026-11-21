package com.hienlh.ppm.app.ui

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.client.exportChatMarkdown
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.SessionBookmark
import com.hienlh.ppm.state.ChatViewMode
import com.hienlh.ppm.state.SessionBookmarks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the ··· menu adds for the open chat (`chat-more-menu.tsx`): View (the view mode, cycled as
 * Ctrl+O cycles it), Bookmarks (a drill-in, [bookmarks]) and Export as Markdown. Bookmarks and
 * export are for a Claude or Codex chat with a session only.
 */
data class ChatMoreExtras(
    val viewMode: ChatViewMode,
    val onCycleView: () -> Unit,
    val bookmarks: Bookmarks? = null,
    val onExport: (() -> Unit)? = null,
) {
    /**
     * [count] is the chat's bookmarks; [content] the drill-in, given Back, "jumped" (close the menu)
     * and whether it draws its own Back + title header (a phone's sheet has one already).
     */
    class Bookmarks(
        val count: Int,
        val content: @Composable (onBack: () -> Unit, onJumped: () -> Unit, header: Boolean) -> Unit,
    )
}

/**
 * The open chat's [ChatMoreExtras]. The Bookmarks row waits for the chat's list: a server without
 * bookmarks never answers it, and the row stays out rather than opening on a list that never loads.
 */
@Composable
internal fun rememberChatMoreExtras(model: AppModel, tab: ChatTab): ChatMoreExtras {
    val sessionId = tab.session?.id
    val providerId = tab.providerId
    val project = tab.project
    val canExport = sessionId != null && (providerId == "claude" || providerId == "codex")
    val store = model.bookmarks
    LaunchedEffect(store, project, sessionId, canExport) {
        if (canExport && sessionId != null) store.load(project, sessionId)
    }
    val lists by store.lists.collectAsState()
    val list = sessionId?.let { lists[SessionBookmarks.key(project, it)] }
    val export = rememberMarkdownExport(model)
    return ChatMoreExtras(
        viewMode = model.chatPrefs.viewMode,
        onCycleView = model.chatPrefs::cycleViewMode,
        bookmarks = if (canExport && sessionId != null && list != null) {
            ChatMoreExtras.Bookmarks(list.size) { onBack, onJumped, header ->
                BookmarksView(
                    header = header,
                    list = store.lists.collectAsState().value[SessionBookmarks.key(project, sessionId)],
                    onBack = onBack,
                    onJump = { bookmark ->
                        model.jumpToTurn(sessionId, bookmark.turnId)
                        onJumped()
                    },
                    onRemove = { bookmark -> store.remove(project, sessionId, bookmark.turnId) },
                )
            }
        } else {
            null
        },
        onExport = if (canExport && sessionId != null) {
            { export(project, sessionId, providerId) }
        } else {
            null
        },
    )
}

/**
 * Export as Markdown (`exportChatMarkdown`): "Exporting chat…" while the server writes it, then the
 * file saved in Downloads as the browser saves a download — before Android 10 the system asks
 * where — and "Chat exported", or the server's error.
 */
@Composable
private fun rememberMarkdownExport(model: AppModel): (project: String, sessionId: String, providerId: String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var waiting by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var waitingToast by remember { mutableStateOf<ErrorToastMessage?>(null) }
    fun finish(shown: ErrorToastMessage, next: ErrorToastMessage) {
        if (!model.replaceToast(shown, next)) model.toast(next)
    }
    val create = rememberLauncherForActivityResult(CreateFile()) { uri: Uri? ->
        val file = waiting ?: return@rememberLauncherForActivityResult
        val shown = waitingToast ?: return@rememberLauncherForActivityResult
        waiting = null
        waitingToast = null
        if (uri == null) return@rememberLauncherForActivityResult model.dismissToast(shown)
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(file.second) } != null }.getOrDefault(false)
            }
            finish(shown, if (written) exported(file.first) else ErrorToastMessage("Could not save ${file.first}"))
        }
    }
    return { project, sessionId, providerId ->
        val loading = ErrorToastMessage("Exporting chat…", kind = ErrorToastMessage.Kind.PLAIN, persistent = true)
        model.toast(loading)
        scope.launch {
            val api = model.api
            val result = if (api == null) {
                Result.failure(IllegalStateException("Not connected"))
            } else {
                runCatching { api.exportChatMarkdown(project, sessionId, providerId) }
            }
            result.fold(
                onSuccess = { export ->
                    val bytes = export.markdown.toByteArray()
                    if (Build.VERSION.SDK_INT >= 29) {
                        val uri = withContext(Dispatchers.IO) { saveToDownloads(context, export.fileName, "text/markdown", bytes) }
                        finish(loading, if (uri != null) exported(export.fileName) else ErrorToastMessage("Could not save ${export.fileName}"))
                    } else {
                        waiting = export.fileName to bytes
                        waitingToast = loading
                        create.launch(export.fileName to "text/markdown")
                    }
                },
                onFailure = { e -> finish(loading, ErrorToastMessage(e.message?.takeIf { it.isNotBlank() } ?: "Export failed")) },
            )
        }
    }
}

private fun exported(fileName: String) = ErrorToastMessage("Chat exported", fileName, kind = ErrorToastMessage.Kind.SUCCESS)

/**
 * The ··· menu's Bookmarks drill-in (`bookmarks-view.tsx`): the chat's bookmarked turns in turn
 * order; a tap jumps to the turn (the history window opens when it is not loaded), × takes the
 * bookmark off.
 */
@Composable
internal fun BookmarksView(
    list: List<SessionBookmark>?,
    onBack: () -> Unit,
    onJump: (SessionBookmark) -> Unit,
    onRemove: (SessionBookmark) -> Unit,
    header: Boolean = true,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    Column(Modifier.fillMaxWidth().testTag("bookmarks-view")) {
        if (header) FooterPopoverHeader {
            val backShape = RoundedCornerShape(6.dp)
            Box(
                Modifier
                    .size(if (mobile) 44.dp else 28.dp)
                    .clip(backShape)
                    .semantics { contentDescription = "Back" }
                    .ppmClickable(
                        pressedColor = tokens.surfaceHover.color,
                        hoverColor = tokens.surfaceHover.color,
                        shape = backShape,
                        role = Role.Button,
                        onClick = onBack,
                    )
                    .testTag("bookmarks-back"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.ChevronLeft, if (mobile) 16.dp else 14.dp, tokens.text3.color)
            }
            BasicText(
                "Bookmarks",
                style = ppmText(if (mobile) 14f else 13f, FontWeight.SemiBold, lineHeight = if (mobile) 20f else 18f).copy(color = tokens.text.color),
                maxLines = 1,
                softWrap = false,
            )
        }
        val note = ppmText(if (mobile) 14f else 12f, lineHeight = if (mobile) 20f else 16f).copy(color = tokens.text3.color)
        when {
            list == null -> BasicText("Loading…", Modifier.padding(horizontal = 12.dp, vertical = 16.dp), style = note)
            list.isEmpty() -> BasicText(
                "No bookmarks yet. Bookmark a turn from the bar under any message you sent.",
                Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                style = note,
            )
            else -> Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (mobile) Modifier else Modifier.heightIn(max = 300.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(4.dp),
            ) {
                for (bookmark in list) BookmarkRow(bookmark, onJump, onRemove)
            }
        }
    }
}

@Composable
private fun BookmarkRow(bookmark: SessionBookmark, onJump: (SessionBookmark) -> Unit, onRemove: (SessionBookmark) -> Unit) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.fillMaxWidth().testTag("bookmark-row:" + bookmark.turnId),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .defaultMinSize(minHeight = if (mobile) 44.dp else 34.dp)
                .clip(shape)
                .ppmClickable(pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color, shape = shape, role = Role.Button) { onJump(bookmark) }
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PpmIcon(ProductIcon.Bookmark, 16.dp, tokens.accent.color)
            TruncatedText(
                AnnotatedString(bookmark.label.ifEmpty { "Turn " + (bookmark.turnN?.toString() ?: "") }),
                ppmText(if (mobile) 14f else 13f, lineHeight = if (mobile) 20f else 18f).copy(color = tokens.text.color),
                Modifier.weight(1f),
            )
            bookmark.turnN?.let { n ->
                BasicText(
                    "Turn $n",
                    style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color, fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        Box(
            Modifier
                .size(if (mobile) 44.dp else 28.dp)
                .clip(shape)
                .webTitle("Remove bookmark")
                .semantics { contentDescription = "Remove bookmark" }
                .ppmClickable(pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color, shape = shape, role = Role.Button) { onRemove(bookmark) }
                .testTag("bookmark-remove:" + bookmark.turnId),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.X, 14.dp, tokens.text3.color)
        }
    }
}
