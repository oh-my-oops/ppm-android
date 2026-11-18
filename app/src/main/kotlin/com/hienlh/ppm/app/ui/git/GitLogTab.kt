package com.hienlh.ppm.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.client.GitBranch
import com.hienlh.ppm.client.GitCommit
import com.hienlh.ppm.client.GitGraphData
import com.hienlh.ppm.client.gitGraph
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.state.git.GitLogRefBadges
import com.hienlh.ppm.state.git.GitRefBadgeModel
import com.hienlh.ppm.state.git.GitRepoScope
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

private const val GIT_LOG_PAGE_SIZE = 100

/**
 * Commit history tab (`git-log` -> `src/web/components/git/git-log-panel.tsx`).
 */
@Composable
fun GitLogBody(model: AppModel, tab: WorkspaceTab) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    val api = model.api
    val projectName = tab.metaString("projectName")?.takeIf { it.isNotEmpty() } ?: model.selectedProject

    if (projectName == null) {
        Box(
            modifier = Modifier.fillMaxSize().padding(16.dp).testTag("git-log-no-project"),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = "Select a project to view git log",
                style = PpmFont.sans(13, lineHeight = 18, color = tokens.text2.color),
            )
        }
        return
    }

    LaunchedEffect(projectName, api) {
        model.loadGitRepos(projectName)
    }

    val discovery = model.gitDiscovery[projectName]
    val chosenPath = model.gitChosenRepoPath[projectName]
    val repo = remember(discovery, chosenPath) { GitRepoScope.resolveRepo(discovery, chosenPath) }
    val needsPick = remember(discovery, chosenPath) { GitRepoScope.needsPick(discovery, chosenPath) }
    val noRepo = remember(discovery) { GitRepoScope.hasNoRepo(discovery) }

    var data by remember(projectName, repo?.path) { mutableStateOf<GitGraphData?>(null) }
    var loading by remember(projectName, repo?.path) { mutableStateOf(false) }
    var hasMore by remember(projectName, repo?.path) { mutableStateOf(true) }

    suspend fun fetchData(skip: Int = 0, append: Boolean = false) {
        val client = api ?: return
        if (needsPick || noRepo) return
        loading = true
        try {
            val res = client.gitGraph(projectName, max = GIT_LOG_PAGE_SIZE, skip = skip, repo = repo)
            val prev = data
            data = if (append && prev != null) {
                res.copy(commits = prev.commits + res.commits)
            } else {
                res
            }
            hasMore = res.commits.size == GIT_LOG_PAGE_SIZE
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Silent, matching git-log-panel.tsx
        } finally {
            loading = false
        }
    }

    LaunchedEffect(projectName, repo?.path, needsPick, noRepo, api) {
        if (!needsPick && !noRepo && api != null) {
            fetchData()
        }
    }

    LaunchedEffect(projectName, repo?.path, api) {
        model.gitChangedEvents.filter { it == projectName }.collectLatest {
            fetchData()
        }
    }

    if (noRepo) {
        GitNoRepo(onRescan = { scope.launch { model.loadGitRepos(projectName, force = true) } })
        return
    }
    if (needsPick && discovery != null) {
        GitRepoChoice(
            repos = discovery.repos,
            onSelect = { picked -> model.chooseGitRepo(projectName, picked.path) },
        )
        return
    }

    val listState = rememberLazyListState()

    LaunchedEffect(listState, projectName, repo?.path, api) {
        snapshotFlow {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val nearBottom = total > 0 && lastVisible >= total - 10
            listOf(nearBottom, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, data?.commits?.size ?: 0)
        }
            .distinctUntilChanged()
            .filter { it[0] == true }
            .collect {
                val current = data
                if (!loading && hasMore && current != null && current.commits.isNotEmpty()) {
                    fetchData(skip = current.commits.size, append = true)
                }
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("git-log-panel"),
    ) {
        if (repo != null && discovery != null && (discovery.repos.size > 1 || !discovery.rootIsRepo)) {
            GitRepoBar(
                repo = repo,
                repos = discovery.repos,
                onSelect = { picked -> model.chooseGitRepo(projectName, picked.path) },
            )
        }

        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .edge(Edge.Bottom, tokens.border.color)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(LucideIcon.GitCommitHorizontal, size = 16.dp, tint = tokens.text2.color)
            BasicText(
                text = "Git Log",
                style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text.color),
            )
            data?.let { loaded ->
                BasicText(
                    text = "${loaded.commits.size} commits",
                    style = PpmFont.sans(10, lineHeight = 14, color = tokens.text3.color),
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    // A 44 dp touch target on a phone.
                    .size(if (LocalPpmMobile.current) 44.dp else 28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .alpha(if (loading) 0.5f else 1f)
                    .clickable(enabled = !loading) { scope.launch { fetchData() } }
                    .testTag("git-log-refresh"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(
                    if (loading) ProductIcon.Loader2 else ProductIcon.RefreshCw,
                    size = 14.dp,
                    tint = tokens.text3.color,
                )
            }
        }

        val currentData = data
        when {
            currentData == null && loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PpmIcon(ProductIcon.Loader2, size = 20.dp, tint = tokens.accent.color)
                }
            }
            currentData != null && currentData.commits.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BasicText(
                        text = "No commits yet",
                        style = PpmFont.sans(13, lineHeight = 18, color = tokens.text2.color),
                    )
                }
            }
            currentData != null -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("git-log-list"),
                ) {
                    items(currentData.commits, key = { it.hash }) { commit ->
                        CommitRow(
                            commit = commit,
                            branches = currentData.branches,
                            head = currentData.head,
                        )
                    }
                    if (loading) {
                        item(key = "loading-more") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                PpmIcon(ProductIcon.Loader2, size = 16.dp, tint = tokens.text3.color)
                            }
                        }
                    }
                    if (!hasMore && currentData.commits.isNotEmpty()) {
                        item(key = "end-of-history") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                BasicText(
                                    text = "End of history",
                                    style = PpmFont.sans(10, lineHeight = 14, color = tokens.text3.color),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommitRow(
    commit: GitCommit,
    branches: List<GitBranch>,
    head: String,
) {
    val tokens = LocalPpmTokens.current
    val isHead = commit.hash == head
    val badges = remember(commit.hash, commit.refs, branches, head) {
        GitLogRefBadges.buildRefBadges(commit.hash, commit.refs, branches, head)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isHead) Modifier.background(tokens.accent.opacity(0.05).color) else Modifier)
            .edge(Edge.Bottom, tokens.border.opacity(0.5).color)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("git-log-row-${commit.abbreviatedHash}"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Commit dot
        Box(
            modifier = Modifier.padding(top = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isHead) tokens.accent.color else Color.Transparent)
                    .cssBorder(
                        color = if (isHead) tokens.accent.color else tokens.text3.color,
                        shape = CircleShape,
                        width = 2.dp,
                    ),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            if (badges.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (badge in badges) {
                        GitRefBadgeChip(badge)
                    }
                }
            }

            TruncatedText(
                text = commit.subject,
                style = PpmFont.sans(12, lineHeight = 16, color = tokens.text.color),
            )

            Row(
                modifier = Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicText(
                    text = commit.abbreviatedHash,
                    style = PpmFont.mono(10, lineHeight = 14, color = tokens.text3.color),
                )
                TruncatedText(
                    text = commit.authorName,
                    style = PpmFont.sans(10, lineHeight = 14, color = tokens.text3.color),
                    modifier = Modifier.weight(1f, fill = false),
                )
                BasicText(
                    text = GitLogRefBadges.formatRelativeDate(commit.authorDate),
                    style = PpmFont.sans(10, lineHeight = 14, color = tokens.text3.color),
                )
            }
        }
    }
}

@Composable
internal fun GitRefBadgeChip(badge: GitRefBadgeModel) {
    val tokens = LocalPpmTokens.current
    val baseColor = Color(badge.colorHex)
    val shape = RoundedCornerShape(6.dp)
    val icon = when {
        badge.isTag -> ProductIcon.Tag
        badge.syncState == "synced" -> ProductIcon.Cloud
        badge.syncState == "ahead" -> ProductIcon.ArrowUp
        badge.syncState == "behind" -> ProductIcon.ArrowDown
        badge.syncState == "remote-only" -> ProductIcon.Cloud
        else -> ProductIcon.CloudOff
    }

    Row(
        modifier = Modifier
            .widthIn(max = 220.dp)
            .alpha(if (badge.syncState == "remote-only") 0.7f else 1f)
            .clip(shape)
            .background(baseColor.copy(alpha = 0.1f))
            .cssBorder(
                color = baseColor,
                shape = shape,
                width = if (badge.isHead) 1.5.dp else 1.dp,
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("git-ref-badge-${badge.name}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PpmIcon(icon, size = 12.dp, tint = baseColor)
        TruncatedText(
            text = badge.name,
            style = PpmFont.sans(11, FontWeight.Medium, lineHeight = 14, color = tokens.text.color),
        )
    }
}
