package com.hienlh.ppm.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ReviewTab
import com.hienlh.ppm.app.TextInputFocus
import com.hienlh.ppm.chat.ReviewDesktop
import com.hienlh.ppm.chat.ReviewPhone
import com.hienlh.ppm.chat.ReviewSheet
import com.hienlh.ppm.chat.ReviewTabComments
import com.hienlh.ppm.chat.ReviewTabContext
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.workspace.WorkspaceTab

/**
 * A session-review tab (`SessionReviewTab`): the session's review once the tab has been shown,
 * or, for a tab that names no session, the line saying so.
 */
@Composable
fun SessionReviewBody(model: AppModel, tab: WorkspaceTab) {
    if (tab.metaString("projectName").isNullOrEmpty() || tab.metaString("sessionId").isNullOrEmpty()) {
        val tokens = LocalPpmTokens.current
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BasicText("No chat session.", style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text3.color))
        }
        return
    }
    model.review(tab.id)?.let { review -> key(review) { ReviewPane(model, review) } }
}

@Composable
private fun ReviewPane(model: AppModel, tab: ReviewTab) {
    val review = tab.review ?: return
    val entries by model.sessionTurns.entries.collectAsState()
    // `max-h-[85%]` of the sheet's frame (`fixed inset-0`), the app's area under the status bar.
    val statusBar = WindowInsets.statusBars.getTop(LocalDensity.current)
    val listSheet: ReviewSheet = remember(statusBar) {
        val sheetMax = Modifier.layout { measurable, constraints ->
            val max = ((constraints.maxHeight - statusBar) * 0.85f).toInt().coerceIn(constraints.minHeight, constraints.maxHeight)
            val placeable = measurable.measure(constraints.copy(maxHeight = max))
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val open: ReviewSheet = { dismiss, content -> BottomSheet(dismiss, sheetMax) { content(LocalSheetContentTop.current) } }
        open
    }
    val sheet: ReviewSheet = remember {
        val open: ReviewSheet = { dismiss, content -> BottomSheet(dismiss) { content(LocalSheetContentTop.current) } }
        open
    }
    val ctx = ReviewTabContext(
        projectPath = model.projects.firstOrNull { it.name == tab.project }?.path,
        providerId = tab.providerId,
        chatTitle = tab.chatTitle,
        turns = entries[tab.sessionId]?.byCall.orEmpty(),
        openChat = { model.openReviewChat(tab) },
        openFile = { model.openFileTab(tab.project, it) },
        showInChat = { turn, call -> model.showInChat(tab, turn.messageId, call) },
        typing = { TextInputFocus.focused },
        ownsKeys = { model.activeTabId == tab.tabId },
        sheet = sheet,
        listSheet = listSheet,
        comments = remember(tab) { ReviewTabComments(tab.sessionId, model.reviewComments) { model.sendReviewComments(tab) } },
    )
    // `useIsMobile`: the phone's layout below the desktop's breakpoint.
    if (model.wide) ReviewDesktop(review, ctx) else ReviewPhone(review, ctx)
}
