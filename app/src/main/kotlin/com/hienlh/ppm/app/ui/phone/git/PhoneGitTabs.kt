package com.hienlh.ppm.app.ui.phone.git

import com.hienlh.ppm.state.git.GIT_INSIGHTS_VIEW_TYPES
import com.hienlh.ppm.state.workspace.WorkspaceTab

/**
 * The git tabs a phone shows full screen in its Source Control area, each with the same body the
 * tablet gives it (`TabBody`): resolving a conflict, Review changes (the working tree's and a
 * chat session's), Branch review and the plain commit list the Git Graph falls back to.
 */
val PHONE_GIT_TAB_TYPES: Set<String> = setOf(
    "conflict-editor",
    "git-review",
    "session-review",
    "branch-review",
    "git-log",
)

/**
 * True for an extension tab holding the Git Graph or one of its own panels (blame, file history,
 * compare, interactive rebase, reflog). The app draws those natively (`ExtensionWebviewBody`), so a
 * phone can show them like any other git tab; other extension panels are not this area's.
 * The tab keeps the view's slug, i.e. the view type without its `.view` suffix.
 */
fun isGitGraphExtensionTab(tab: WorkspaceTab): Boolean {
    if (tab.type != "extension") return false
    val viewType = tab.metaString("viewType") ?: return false
    return viewType == "git-graph" || viewType == "git-graph.view" || viewType in GIT_INSIGHTS_VIEW_TYPES
}

/** True when the phone's Source Control area shows [tab] through its tab body rather than its own list. */
fun isPhoneGitTabBody(tab: WorkspaceTab): Boolean = tab.type in PHONE_GIT_TAB_TYPES || isGitGraphExtensionTab(tab)

/**
 * Where the phone's Source Control hands a file or the repository over to a git tab, as the web's
 * panel does (`git-status-panel.tsx`): Review changes (the Changes header's Review, a row's
 * "Review changes"), the conflict editor (a conflict row, "Resolve conflict"), the line picker
 * ("Stage lines…" / "Unstage lines…", `scope` `"worktree"` or `"index"`), the History button
 * (Git Graph when the extension offers it, else the commit list) and "Review branch…".
 * A `null` hides the entry.
 */
data class PhoneGitTabActions(
    val onOpenReview: ((com.hienlh.ppm.state.git.FileChange?) -> Unit)? = null,
    val onResolveConflict: ((com.hienlh.ppm.state.git.FileChange) -> Unit)? = null,
    val onPickLines: ((com.hienlh.ppm.state.git.FileChange, String) -> Unit)? = null,
    val onOpenHistory: (() -> Unit)? = null,
    val historyLabel: String = "View Git Log",
    val onOpenBranchReview: (() -> Unit)? = null,
)

/** A question the phone's Source Control asks before a git write (Mark resolved over markers, Abort a merge). */
data class PhoneGitConfirm(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val destructive: Boolean,
    val run: () -> Unit,
)
