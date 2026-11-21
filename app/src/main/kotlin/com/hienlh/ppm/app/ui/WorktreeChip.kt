package com.hienlh.ppm.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.protocol.SessionWorktree
import com.hienlh.ppm.protocol.isAssistantProject

/**
 * The footer chip for a chat's git worktree (`WorktreeChip` in `worktree-chip.tsx`). Before a new
 * Claude chat's first message it is a switch — *Worktree* runs the chat in
 * `<project>/.claude/worktrees/<name>` on a branch of its own, so it never edits the checkout
 * another chat is working in. Once the chat runs in one, it names the branch, and a tap copies the
 * worktree's path.
 */
@Composable
fun WorktreeChip(
    worktree: SessionWorktree?,
    /** A new Claude chat that has sent nothing yet: the switch is offered. */
    canChoose: Boolean,
    wanted: Boolean,
    narrow: Boolean,
    onWantedChange: (Boolean) -> Unit,
    onCopyPath: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    if (worktree != null) {
        FooterChip(
            narrow = narrow,
            modifier = modifier,
            title = "This chat works in its own worktree: ${worktree.path} (branch ${worktree.branch}). Click to copy the path.",
            contentDescription = "Worktree ${worktree.branch}, copy its path",
            testTag = "worktree-chip-on",
            onClick = { onCopyPath(worktree.path) },
        ) {
            PpmIcon(ProductIcon.GitBranch, 14.dp, tokens.accent.color)
            BasicText(
                worktree.branch,
                Modifier.widthIn(max = 160.dp),
                style = LocalFooterTextStyle.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    if (!canChoose) return
    // `HIDE_NARROW`: the icon alone in a narrow chat and on a phone.
    val hideWords = narrow || LocalPpmMobile.current
    FooterChip(
        narrow = narrow,
        modifier = modifier.semantics { toggleableState = ToggleableState(wanted) },
        // The web's `aria-pressed` look: the accent wash while it is on.
        expanded = wanted,
        title = if (wanted) {
            "This chat will run in a new git worktree of its own. Click to work in the project's checkout instead."
        } else {
            "Run this chat in a new git worktree of its own, so it never edits the checkout other chats work in"
        },
        contentDescription = if (wanted) "New worktree" else "Worktree",
        testTag = if (wanted) "worktree-chip-wanted" else "worktree-chip-off",
        onClick = { onWantedChange(!wanted) },
    ) {
        PpmIcon(ProductIcon.GitBranch, 14.dp, if (wanted) tokens.accent.color else tokens.text3.color)
        if (!hideWords) {
            BasicText(if (wanted) "New worktree" else "Worktree", style = LocalFooterTextStyle.current, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * [WorktreeChip] for the chat in [tab], as `chat-tab.tsx` places it: not in minimal mode nor an
 * Assistant session, the switch only on a Claude chat outside a design that has sent nothing yet.
 */
@Composable
fun ChatWorktreeChip(model: AppModel, tab: ChatTab, narrow: Boolean, modifier: Modifier = Modifier) {
    if (model.info?.minimalMode == true || isAssistantProject(tab.project)) return
    val controller = tab.controller ?: return
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val canChoose = tab.providerId == "claude" &&
        !tab.tabId.startsWith("design:") &&
        tab.firstSendPreview == null &&
        state.items.isEmpty() &&
        (tab.session == null || state.hasLoadedHistory)
    WorktreeChip(
        worktree = state.worktree,
        canChoose = canChoose,
        wanted = state.worktreeWanted,
        narrow = narrow,
        onWantedChange = controller::setWorktreeWanted,
        onCopyPath = { path ->
            val copied = runCatching {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Worktree path", path))
            }.isSuccess
            model.toast(
                if (copied) ErrorToastMessage("Worktree path copied", kind = ErrorToastMessage.Kind.SUCCESS) else ErrorToastMessage("Could not copy the path"),
            )
        },
        modifier = modifier,
    )
}
