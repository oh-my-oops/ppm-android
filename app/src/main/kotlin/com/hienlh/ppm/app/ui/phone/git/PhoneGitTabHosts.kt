package com.hienlh.ppm.app.ui.phone.git

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.TabBody
import com.hienlh.ppm.app.ui.phone.PhoneNav
import com.hienlh.ppm.client.GitRepo
import com.hienlh.ppm.state.git.FileChange
import com.hienlh.ppm.state.git.GitRepoScope
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive

/** How long the History button waits for the Git Graph extension to answer with its panel before giving up on showing it. */
private const val GIT_GRAPH_OPEN_WAIT_MS = 10_000L

/**
 * A git tab ([isPhoneGitTabBody]) on a phone: the tab body the tablet shows, full screen under the
 * phone's app bar. Each body already has its phone layout (`LocalPpmMobile`): Review changes and
 * Branch review their list-then-file pages, a chat's review `ReviewPhone`, the Git Graph its
 * bottom-sheet inspector, the conflict editor its stacked accept buttons.
 */
@Composable
fun PhoneGitTabBodyHost(model: AppModel, tab: WorkspaceTab) {
    Box(Modifier.fillMaxSize().testTag("phone-git-tab-body")) {
        TabBody(model = model, tab = tab)
    }
}

/**
 * The phone Source Control's way into the git tabs, the same calls the tablet's panel makes
 * (`GitStatusPanel`: `openReview`, `openGraph`, `openBranchReview`). Each opened tab is shown at
 * once ([PhoneNav.visitTab]); the Git Graph's tab is made by the extension when it answers, so
 * that one is shown when it becomes the active tab.
 */
@Composable
fun rememberPhoneGitTabActions(
    model: AppModel,
    project: String,
    repo: GitRepo?,
    nav: PhoneNav,
    onPickLines: (FileChange, String) -> Unit,
): PhoneGitTabActions {
    val scope = rememberCoroutineScope()
    val contributions = model.extensions?.contributions?.collectAsState()?.value
    val gitGraphAvailable = contributions?.commands?.any { it.command == "git-graph.view" } == true
    if (project.isEmpty()) return PhoneGitTabActions()
    // The repository, not the project folder: the graph runs git in whatever path it is handed.
    val gitRoot = repo?.path ?: model.projects.firstOrNull { it.name == project }?.path
    val showActive = { model.activeTabId?.let(nav::visitTab) }
    return PhoneGitTabActions(
        onOpenReview = { change ->
            model.openGitReview(project, change?.path, repo?.takeIf { it.relative != "." }?.path)
            showActive()
        },
        onResolveConflict = { change ->
            model.openGitConflictTab(project, GitRepoScope.projectRelativePath(change.path, repo))
            showActive()
        },
        onPickLines = onPickLines,
        onOpenHistory = {
            if (gitGraphAvailable) {
                val args = if (!gitRoot.isNullOrEmpty()) listOf(JsonPrimitive(gitRoot)) else emptyList()
                model.dispatchExtCommand("git-graph.view", args = args)
                scope.launch {
                    val graph = withTimeoutOrNull(GIT_GRAPH_OPEN_WAIT_MS) {
                        snapshotFlow { model.activeTab }.first { it != null && isGitGraphExtensionTab(it) }
                    }
                    graph?.let { nav.visitTab(it.id) }
                }
            } else {
                model.openGitHistoryTab(project)
                showActive()
            }
        },
        historyLabel = if (gitGraphAvailable) "Open Git Graph" else "View Git Log",
        onOpenBranchReview = {
            model.openBranchReviewTab(project)
            showActive()
        },
    )
}
