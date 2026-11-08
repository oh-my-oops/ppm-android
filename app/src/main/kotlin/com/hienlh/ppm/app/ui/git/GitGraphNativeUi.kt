package com.hienlh.ppm.app.ui.git

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.SettingsButton
import com.hienlh.ppm.app.ui.SettingsTextInput
import com.hienlh.ppm.client.ChangedFile
import com.hienlh.ppm.client.GitChanges
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.extensions.WebviewPanelUI
import com.hienlh.ppm.state.git.CheckState
import com.hienlh.ppm.state.git.GIT_GRAPH_FIND_MODES
import com.hienlh.ppm.state.git.GRAPH_GRID_X
import com.hienlh.ppm.state.git.GRAPH_MIN_W
import com.hienlh.ppm.state.git.GRAPH_OFFSET_X
import com.hienlh.ppm.state.git.GitChangesView
import com.hienlh.ppm.state.git.GitGraphActionResult
import com.hienlh.ppm.state.git.GitGraphClassifiedRef
import com.hienlh.ppm.state.git.GitGraphCommitDetail
import com.hienlh.ppm.state.git.GitGraphCommitDraft
import com.hienlh.ppm.state.git.GitGraphCommitStat
import com.hienlh.ppm.state.git.GitGraphCommitsPageState
import com.hienlh.ppm.state.git.GitGraphExtMessage
import com.hienlh.ppm.state.git.GitGraphFileChange
import com.hienlh.ppm.state.git.GitGraphIssueRule
import com.hienlh.ppm.state.git.GitGraphLayoutResult
import com.hienlh.ppm.state.git.GitGraphMessageToken
import com.hienlh.ppm.state.git.GitGraphNodeKind
import com.hienlh.ppm.state.git.GitGraphOwnerRepo
import com.hienlh.ppm.state.git.GitGraphPrCreationConfig
import com.hienlh.ppm.state.git.GitGraphRefData
import com.hienlh.ppm.state.git.GitGraphRemote
import com.hienlh.ppm.state.git.GitGraphRepoInfo
import com.hienlh.ppm.state.git.GitGraphRequestTracker
import com.hienlh.ppm.state.git.GitGraphSearchHit
import com.hienlh.ppm.state.git.GitGraphSettings
import com.hienlh.ppm.state.git.GitGraphStash
import com.hienlh.ppm.state.git.GitGraphSubmodule
import com.hienlh.ppm.state.git.GitGraphUserDetails
import com.hienlh.ppm.state.git.GitGraphVertex
import com.hienlh.ppm.state.git.GitGraphWebviewMessages
import com.hienlh.ppm.state.git.GitGraphWorktree
import com.hienlh.ppm.state.git.applyGitGraphCommitsPage
import com.hienlh.ppm.state.git.buildGitGraphDisplayCommits
import com.hienlh.ppm.state.git.buildGitGraphPrUrl
import com.hienlh.ppm.state.git.classifyGitGraphRefs
import com.hienlh.ppm.state.git.computeGitGraphLayout
import com.hienlh.ppm.state.git.formatGitGraphDate
import com.hienlh.ppm.state.git.formatGitGraphLongAgo
import com.hienlh.ppm.state.git.gitGraphAuthorInitials
import com.hienlh.ppm.state.git.gitGraphAvatarGradientIndex
import com.hienlh.ppm.state.git.gitGraphPrTemplate
import com.hienlh.ppm.state.git.parseGitGraphExtMessage
import com.hienlh.ppm.state.git.parseGitGraphStashMessage
import com.hienlh.ppm.state.git.searchLoadedGitGraphCommits
import com.hienlh.ppm.state.git.splitGitGraphCommitBody
import com.hienlh.ppm.state.git.tokenizeGitGraphCommitMessage
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.math.min

private val AVATAR_GRADIENT_PAIRS: List<Pair<Color, Color>> = listOf(
    Color(0xFF667EEA) to Color(0xFF764BA2),
    Color(0xFFF5576C) to Color(0xFFF093FB),
    Color(0xFF4FACFE) to Color(0xFF00C6FF),
    Color(0xFF43E97B) to Color(0xFF38F9D7),
    Color(0xFFFA709A) to Color(0xFFFEE140),
    Color(0xFFA18CD1) to Color(0xFF6A3DE8),
    Color(0xFFFD7043) to Color(0xFFFF8A65),
    Color(0xFF26C6DA) to Color(0xFF0097A7),
    Color(0xFFAB47BC) to Color(0xFF7B1FA2),
    Color(0xFFEF5350) to Color(0xFFB71C1C),
    Color(0xFF1976D2) to Color(0xFF42A5F5),
    Color(0xFF2E7D32) to Color(0xFF66BB6A),
)

private fun laneColorForSlot(slot: Int, accent: Color, subtext: Color): Color = when (slot) {
    0 -> accent
    1 -> Color(0xFFA855F7) // purple
    2 -> Color(0xFF14B8A6) // teal
    3 -> Color(0xFFF97316) // orange
    4 -> Color(0xFFEC4899) // pink
    else -> subtext // 5 = stash
}

private fun copyGraphText(context: Context, label: String, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

private fun openGraphExternalUrl(context: Context, url: String) {
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

/**
 * Native Compose Git Graph (`git-graph.view`, Phase 08c), replacing the extension webview
 * while speaking the exact `WebviewToExt` / `ExtToWebview` protocol over `/ws/extensions`.
 */
@Composable
fun GitGraphNativePanelBody(
    model: AppModel,
    tab: WorkspaceTab,
    panel: WebviewPanelUI,
    modifier: Modifier = Modifier,
) {
    val ext = model.extensions
    var repoInfo by remember(panel.id) { mutableStateOf(GitGraphRepoInfo()) }
    var pageState by remember(panel.id) { mutableStateOf(GitGraphCommitsPageState(loading = true)) }
    var commitDetails by remember(panel.id) { mutableStateOf<GitGraphCommitDetail?>(null) }
    var searchResults by remember(panel.id) { mutableStateOf<GitGraphExtMessage.LoadSearchResults?>(null) }
    var settings by remember(panel.id) { mutableStateOf(GitGraphSettings()) }
    var userDetails by remember(panel.id) { mutableStateOf(GitGraphUserDetails()) }
    var ownerRepo by remember(panel.id) { mutableStateOf(GitGraphOwnerRepo()) }
    var worktrees by remember(panel.id) { mutableStateOf<List<GitGraphWorktree>>(emptyList()) }
    var submodules by remember(panel.id) { mutableStateOf<List<GitGraphSubmodule>>(emptyList()) }
    var changes by remember(panel.id) { mutableStateOf<GitChanges?>(null) }
    var draft by remember(panel.id) { mutableStateOf(GitGraphCommitDraft()) }
    var toastMessage by remember(panel.id) { mutableStateOf<String?>(null) }
    var toastUndo by remember(panel.id) { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    val tracker = remember(panel.id) { GitGraphRequestTracker() }

    fun sendToExt(msg: JsonObject) {
        ext?.sendWebviewMessage(panel.id, msg)
    }

    // Send `{ command: "ready" }` once when the panel binds
    LaunchedEffect(panel.id, ext) {
        sendToExt(GitGraphWebviewMessages.ready())
    }

    // Auto-fetch timer (`10s`..`300s`) when `settings.autoFetchInterval > 0`
    LaunchedEffect(panel.id, settings.autoFetchInterval, repoInfo.remotes.size) {
        val intervalSec = settings.autoFetchInterval
        if (intervalSec <= 0 || repoInfo.remotes.isEmpty()) return@LaunchedEffect
        val delayMs = max(intervalSec, 10) * 1000L
        while (true) {
            delay(delayMs)
            val reqId = tracker.register("fetch", null)
            sendToExt(GitGraphWebviewMessages.sync("fetch", reqId))
        }
    }

    // Collect `ExtToWebview` messages for this panel
    LaunchedEffect(panel.id, ext) {
        val client = ext ?: return@LaunchedEffect
        client.webviewMessages.collect { (targetPanelId, rawJson) ->
            if (targetPanelId != panel.id) return@collect
            when (val msg = parseGitGraphExtMessage(rawJson)) {
                is GitGraphExtMessage.LoadRepoInfo -> {
                    repoInfo = msg.data
                }
                is GitGraphExtMessage.LoadCommits -> {
                    pageState = applyGitGraphCommitsPage(pageState, msg, settings.maxCommits)
                }
                is GitGraphExtMessage.LoadCommitStats -> {
                    pageState = pageState.copy(stats = pageState.stats + msg.data)
                }
                is GitGraphExtMessage.CommitDetails -> {
                    commitDetails = msg.data
                }
                is GitGraphExtMessage.LoadSearchResults -> {
                    searchResults = msg
                }
                is GitGraphExtMessage.LoadSettings -> {
                    settings = msg.data
                }
                is GitGraphExtMessage.LoadUserDetails -> {
                    userDetails = msg.data
                }
                is GitGraphExtMessage.LoadOwnerRepo -> {
                    ownerRepo = msg.data
                }
                is GitGraphExtMessage.LoadWorktrees -> {
                    worktrees = msg.data
                }
                is GitGraphExtMessage.LoadStashes -> {
                    repoInfo = repoInfo.copy(stashes = msg.data)
                }
                is GitGraphExtMessage.LoadSubmodules -> {
                    submodules = msg.data
                }
                is GitGraphExtMessage.LoadChanges -> {
                    changes = msg.data
                }
                is GitGraphExtMessage.LoadDraft -> {
                    draft = msg.data
                }
                is GitGraphExtMessage.ActionResult -> {
                    when (val out = tracker.take(msg.action, msg.reqId)) {
                        is GitGraphRequestTracker.Outcome.Taken -> {
                            out.callback?.invoke(msg.result)
                        }
                        GitGraphRequestTracker.Outcome.Unknown -> {
                            if (!msg.result.ok) {
                                toastMessage = msg.result.error ?: "Git action failed"
                            }
                        }
                    }
                    // Refresh 08a Source Control after any git write (`§8c.1`)
                    model.refreshGitStatus()
                }
                is GitGraphExtMessage.ErrorMessage -> {
                    val failedAction = msg.failed
                    if (failedAction != null) {
                        val out = tracker.take(failedAction, msg.reqId)
                        if (out is GitGraphRequestTracker.Outcome.Taken) {
                            out.callback?.invoke(GitGraphActionResult(ok = false, error = msg.message))
                            return@collect
                        }
                    }
                    pageState = pageState.copy(loading = false)
                    toastMessage = msg.message
                }
                null -> Unit
            }
        }
    }

    GitGraphNativeContent(
        repoInfo = repoInfo,
        pageState = pageState,
        commitDetails = commitDetails,
        searchResults = searchResults,
        settings = settings,
        userDetails = userDetails,
        ownerRepo = ownerRepo,
        worktrees = worktrees,
        submodules = submodules,
        changes = changes,
        draft = draft,
        toastMessage = toastMessage,
        toastUndo = toastUndo,
        onDismissToast = {
            toastMessage = null
            toastUndo = null
        },
        onShowToast = { msg, undo ->
            toastMessage = msg
            toastUndo = undo
        },
        onSendMessage = { msg -> sendToExt(msg) },
        onSendTrackedAction = { action, buildMsg, onDone ->
            val reqId = tracker.register(action) { res ->
                onDone(res)
                model.refreshGitStatus()
            }
            sendToExt(buildMsg(reqId))
        },
        onClearSearchResults = { searchResults = null },
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GitGraphNativeContent(
    repoInfo: GitGraphRepoInfo,
    pageState: GitGraphCommitsPageState,
    commitDetails: GitGraphCommitDetail? = null,
    searchResults: GitGraphExtMessage.LoadSearchResults? = null,
    settings: GitGraphSettings = GitGraphSettings(),
    userDetails: GitGraphUserDetails = GitGraphUserDetails(),
    ownerRepo: GitGraphOwnerRepo = GitGraphOwnerRepo(),
    worktrees: List<GitGraphWorktree> = emptyList(),
    submodules: List<GitGraphSubmodule> = emptyList(),
    changes: GitChanges? = null,
    draft: GitGraphCommitDraft = GitGraphCommitDraft(),
    toastMessage: String? = null,
    toastUndo: Pair<String, () -> Unit>? = null,
    onDismissToast: () -> Unit = {},
    onShowToast: (String, Pair<String, () -> Unit>?) -> Unit = { _, _ -> },
    onSendMessage: (JsonObject) -> Unit = {},
    onSendTrackedAction: (
        action: String,
        buildMsg: (reqId: Int) -> JsonObject,
        onDone: (GitGraphActionResult) -> Unit,
    ) -> Unit = { _, buildMsg, _ -> onSendMessage(buildMsg(1)) },
    onClearSearchResults: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val context = LocalContext.current

    val changedFiles = changes?.files.orEmpty()
    val displayCommits = remember(
        pageState.commits,
        repoInfo.head,
        changedFiles,
        settings.showStashes,
        repoInfo.stashes,
    ) {
        buildGitGraphDisplayCommits(
            commits = pageState.commits,
            headHash = repoInfo.head,
            changedFiles = changedFiles,
            showStashes = settings.showStashes,
            stashes = repoInfo.stashes,
        )
    }

    val graphLayout = remember(displayCommits, repoInfo.head) {
        computeGitGraphLayout(displayCommits, repoInfo.head)
    }

    // Selection & Inspector state
    var selectedCommitHash by remember { mutableStateOf<String?>(null) }
    var inspectorOpen by remember { mutableStateOf(!mobile) }
    var fallbackCommit by remember { mutableStateOf<GitGraphVertex?>(null) }

    // Auto-select WIP or HEAD once when commits arrive on tablet
    var autoSelected by remember { mutableStateOf(false) }
    LaunchedEffect(pageState.commitsLoaded, displayCommits.size, mobile) {
        if (!autoSelected && pageState.commitsLoaded && displayCommits.isNotEmpty()) {
            autoSelected = true
            if (!mobile && selectedCommitHash == null) {
                val target = if (displayCommits.first().isWip) "uncommitted" else repoInfo.head.ifEmpty { displayCommits.first().hash }
                selectedCommitHash = target
                inspectorOpen = true
                if (target != "uncommitted") {
                    onSendMessage(GitGraphWebviewMessages.requestCommitDetails(target))
                }
            }
        }
    }

    fun selectCommit(hash: String, fallback: GitGraphVertex? = null, openInsp: Boolean = true) {
        selectedCommitHash = hash
        fallbackCommit = fallback
        if (openInsp) inspectorOpen = true
        if (hash == "uncommitted") return
        val isStash = repoInfo.stashes.any { it.hash == hash }
        if (isStash) {
            onSendMessage(GitGraphWebviewMessages.requestStashDetails(hash))
        } else {
            onSendMessage(GitGraphWebviewMessages.requestCommitDetails(hash))
        }
    }

    // Graph column width & horizontal pan state
    var customGraphWidthDp by remember { mutableStateOf<Float?>(null) }
    var graphPanXDp by remember { mutableFloatStateOf(0f) }
    val effectiveGraphColWidthDp = (customGraphWidthDp ?: graphLayout.naturalWidthDp).coerceIn(GRAPH_MIN_W, 280f)
    val maxGraphPanDp = max(0f, graphLayout.naturalWidthDp - effectiveGraphColWidthDp)

    // Find bar state
    var findOpen by remember { mutableStateOf(false) }
    var findMode by remember { mutableStateOf("loaded") }
    var findQuery by remember { mutableStateOf("") }
    var findMatchIndex by remember { mutableIntStateOf(0) }
    val loadedMatches = remember(displayCommits, findMode, findQuery) {
        if (findMode == "loaded") searchLoadedGitGraphCommits(displayCommits, findQuery) else emptyList()
    }

    // Menus & Dialogs state
    var showScopeMenu by remember { mutableStateOf(false) }
    var scopeFilterText by remember { mutableStateOf("") }
    var showStashesMenu by remember { mutableStateOf(false) }
    var showWorktreesMenu by remember { mutableStateOf(false) }
    var showSubmodulesMenu by remember { mutableStateOf(false) }
    var showViewMenu by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showAddWorktreeDialog by remember { mutableStateOf(false) }

    // Context menus for commit, ref pill, or drag-and-drop ref onto commit
    var activeCommitMenu by remember { mutableStateOf<GitGraphVertex?>(null) }
    var activeRefMenu by remember { mutableStateOf<Pair<GitGraphClassifiedRef, GitGraphVertex>?>(null) }
    var armedDragRef by remember { mutableStateOf<GitGraphClassifiedRef?>(null) }
    var activeDropMenu by remember { mutableStateOf<Pair<GitGraphClassifiedRef, GitGraphVertex>?>(null) }
    var activeFileMenu by remember { mutableStateOf<Triple<String, String, String?>?>(null) } // (path, hash, parent)

    // Generic native dialog state (`showDialog` replacement)
    var activeDialog by remember { mutableStateOf<GitGraphDialogSpec?>(null) }

    // WIP draft state
    var localDraftText by remember(draft.message) { mutableStateOf(draft.message) }
    var showCommitSplitMenu by remember { mutableStateOf(false) }

    fun runGitAction(
        action: String,
        args: JsonObject,
        successToast: String,
        failureTitle: String = "Git action failed",
    ) {
        onSendTrackedAction(
            action,
            { reqId -> GitGraphWebviewMessages.gitAction(action, args, reqId) },
            { res ->
                if (res.ok) {
                    onShowToast(successToast, null)
                } else {
                    onShowToast("$failureTitle: ${res.error.orEmpty()}", null)
                }
            },
        )
    }

    fun runSyncAction(action: String) {
        onSendTrackedAction(
            action,
            { reqId -> GitGraphWebviewMessages.sync(action, reqId) },
            { res ->
                if (res.ok) {
                    onShowToast("Completed $action", null)
                } else {
                    onShowToast("$action failed: ${res.error.orEmpty()}", null)
                }
            },
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("git-graph-native-root"),
    ) {
        val panelWidthDp = maxWidth
        val sideBySideInspector = !mobile && panelWidthDp > 900.dp

        Column(modifier = Modifier.fillMaxSize()) {
            // ── 1. Operation Banner (`git-graph-op-banner`) ──
            val op = changes?.operation
            if (op != null) {
                val conflictCount = changedFiles.count { it.conflict }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.warning.opacity(0.14).color)
                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("git-graph-op-banner"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        BasicText(
                            "${op.kind.replaceFirstChar { it.uppercase() }} in progress",
                            style = ppmText(12f, FontWeight.SemiBold).copy(color = tokens.text.color),
                        )
                        BasicText(
                            if (conflictCount > 0) "$conflictCount conflict(s) left to resolve" else "All conflicts resolved — continue when ready",
                            style = ppmText(11f).copy(color = tokens.text2.color),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (conflictCount > 0) {
                            SettingsButton(
                                label = "Resolve",
                                modifier = Modifier.testTag("git-graph-op-resolve-btn"),
                                onClick = {
                                    val firstConflict = changedFiles.firstOrNull { it.conflict }
                                    if (conflictCount == 1 && firstConflict != null) {
                                        onSendMessage(GitGraphWebviewMessages.openConflictFile(firstConflict.path))
                                    } else {
                                        selectCommit("uncommitted")
                                    }
                                },
                            )
                        }
                        SettingsButton(
                            label = "Abort",
                            modifier = Modifier.testTag("git-graph-op-abort-btn"),
                            onClick = {
                                onSendTrackedAction(
                                    "operation:abort",
                                    { reqId -> GitGraphWebviewMessages.operation("abort", reqId) },
                                    { res ->
                                        if (!res.ok) {
                                            onShowToast(res.error ?: "Abort failed", null)
                                        }
                                    },
                                )
                            },
                        )
                        if (op.kind == "rebase") {
                            SettingsButton(
                                label = "Skip",
                                modifier = Modifier.testTag("git-graph-op-skip-btn"),
                                onClick = {
                                    runGitAction("rebaseSkip", buildJsonObject {}, "Skipped commit")
                                },
                            )
                        }
                        SettingsButton(
                            label = "Continue",
                            primary = conflictCount == 0,
                            modifier = Modifier.testTag("git-graph-op-continue-btn"),
                            onClick = {
                                if (conflictCount == 0) {
                                    onSendTrackedAction(
                                        "operation:continue",
                                        { reqId -> GitGraphWebviewMessages.operation("continue", reqId) },
                                        { res ->
                                            onShowToast(if (res.ok) "Continued ${op.kind}" else (res.error ?: "Continue failed"), null)
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            }

            // ── 2. Top Toolbar (`git-graph-toolbar`) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("git-graph-toolbar"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val scopeLabel = if (pageState.scope == "all") "All branches" else pageState.scope
                SettingsButton(
                    label = "⎇ $scopeLabel ▾",
                    modifier = Modifier.testTag("git-graph-scope-btn"),
                    onClick = { showScopeMenu = !showScopeMenu },
                )

                val branchStatus = changes?.branch
                val ahead = branchStatus?.ahead ?: 0
                val behind = branchStatus?.behind ?: 0
                if (!mobile) {
                    SettingsButton(
                        label = "Fetch",
                        modifier = Modifier.testTag("git-graph-fetch-btn"),
                        onClick = { runSyncAction("fetch") },
                    )
                    SettingsButton(
                        label = if (behind > 0) "Pull ↓$behind" else "Pull",
                        modifier = Modifier.testTag("git-graph-pull-btn"),
                        onClick = { runSyncAction("pull") },
                    )
                    val isPublish = branchStatus != null && (branchStatus.upstream == null || branchStatus.upstreamGone)
                    SettingsButton(
                        label = when {
                            isPublish -> "Publish"
                            ahead > 0 -> "Push ↑$ahead"
                            else -> "Push"
                        },
                        primary = ahead > 0 || isPublish,
                        modifier = Modifier.testTag("git-graph-push-btn"),
                        onClick = {
                            val act = when {
                                isPublish -> "publish"
                                ahead > 0 && behind > 0 -> "sync"
                                else -> "push"
                            }
                            runSyncAction(act)
                        },
                    )
                } else {
                    SettingsButton(
                        label = when {
                            ahead > 0 && behind > 0 -> "Sync ↓$behind ↑$ahead"
                            behind > 0 -> "Pull ↓$behind"
                            ahead > 0 -> "Push ↑$ahead"
                            else -> "Fetch"
                        },
                        primary = ahead > 0 || behind > 0,
                        modifier = Modifier.testTag("git-graph-sync-mobile-btn"),
                        onClick = {
                            val act = when {
                                ahead > 0 && behind > 0 -> "sync"
                                behind > 0 -> "pull"
                                ahead > 0 -> "push"
                                else -> "fetch"
                            }
                            runSyncAction(act)
                        },
                    )
                }

                SettingsButton(
                    label = if (repoInfo.stashes.isNotEmpty()) "Stashes (${repoInfo.stashes.size})" else "Stashes",
                    modifier = Modifier.testTag("git-graph-stashes-btn"),
                    onClick = { showStashesMenu = !showStashesMenu },
                )
                SettingsButton(
                    label = if (worktrees.size > 1) "Worktrees (${worktrees.size})" else "Worktrees",
                    modifier = Modifier.testTag("git-graph-worktrees-btn"),
                    onClick = {
                        onSendMessage(GitGraphWebviewMessages.requestWorktrees())
                        showWorktreesMenu = !showWorktreesMenu
                    },
                )
                if (submodules.isNotEmpty()) {
                    SettingsButton(
                        label = "Submodules (${submodules.size})",
                        modifier = Modifier.testTag("git-graph-submodules-btn"),
                        onClick = { showSubmodulesMenu = !showSubmodulesMenu },
                    )
                }
                SettingsButton(
                    label = "Find",
                    primary = findOpen,
                    modifier = Modifier.testTag("git-graph-find-btn"),
                    onClick = { findOpen = !findOpen },
                )
                SettingsButton(
                    label = "View ▾",
                    modifier = Modifier.testTag("git-graph-view-btn"),
                    onClick = { showViewMenu = !showViewMenu },
                )
                SettingsButton(
                    label = if (inspectorOpen) "Hide Inspector" else "Inspector",
                    primary = inspectorOpen,
                    modifier = Modifier.testTag("git-graph-inspector-toggle-btn"),
                    onClick = { inspectorOpen = !inspectorOpen },
                )
            }

            // Armed Drag-to-Commit Banner (when user picks "Drag onto commit…" on a branch pill)
            val draggingRef = armedDragRef
            if (draggingRef != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.accent.opacity(0.14).color)
                        .cssBorder(tokens.accent.color, sides = CssSides.Bottom)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .testTag("git-graph-drag-banner"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        "Tap any commit row to drop branch '${draggingRef.name}' (Merge / Rebase / Compare)",
                        style = ppmText(11f, FontWeight.Medium).copy(color = tokens.accent.color),
                    )
                    SettingsButton(
                        label = "Cancel",
                        modifier = Modifier.testTag("git-graph-drag-cancel-btn"),
                        onClick = { armedDragRef = null },
                    )
                }
            }

            // ── 3. Find Bar (`git-graph-find-bar`) ──
            if (findOpen) {
                val curModeInfo = GIT_GRAPH_FIND_MODES.firstOrNull { it.id == findMode } ?: GIT_GRAPH_FIND_MODES.first()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel2.color)
                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("git-graph-find-bar"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (m in GIT_GRAPH_FIND_MODES) {
                        SettingsButton(
                            label = m.chip,
                            primary = findMode == m.id,
                            modifier = Modifier.testTag("git-graph-find-mode-${m.id}"),
                            onClick = {
                                findMode = m.id
                                onClearSearchResults()
                            },
                        )
                    }
                    SettingsTextInput(
                        value = findQuery,
                        onValueChange = {
                            findQuery = it
                            findMatchIndex = 0
                        },
                        placeholder = curModeInfo.placeholder,
                        modifier = Modifier.width(200.dp).testTag("git-graph-find-input"),
                    )
                    if (findMode == "loaded") {
                        val countLabel = if (findQuery.isBlank()) "" else "${loadedMatches.size} found"
                        BasicText(
                            countLabel,
                            style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text2.color),
                            modifier = Modifier.testTag("git-graph-find-count"),
                        )
                        SettingsButton(
                            label = "↑",
                            modifier = Modifier.testTag("git-graph-find-prev-btn"),
                            onClick = {
                                if (loadedMatches.isNotEmpty()) {
                                    findMatchIndex = (findMatchIndex - 1 + loadedMatches.size) % loadedMatches.size
                                    selectCommit(loadedMatches[findMatchIndex])
                                }
                            },
                        )
                        SettingsButton(
                            label = "↓",
                            modifier = Modifier.testTag("git-graph-find-next-btn"),
                            onClick = {
                                if (loadedMatches.isNotEmpty()) {
                                    findMatchIndex = (findMatchIndex + 1) % loadedMatches.size
                                    selectCommit(loadedMatches[findMatchIndex])
                                }
                            },
                        )
                    } else {
                        SettingsButton(
                            label = "Search history",
                            primary = findQuery.isNotBlank(),
                            modifier = Modifier.testTag("git-graph-find-run-btn"),
                            onClick = {
                                if (findQuery.isNotBlank()) {
                                    onSendMessage(GitGraphWebviewMessages.searchCommits(findMode, findQuery.trim()))
                                }
                            },
                        )
                    }
                    SettingsButton(
                        label = "×",
                        modifier = Modifier.testTag("git-graph-find-close-btn"),
                        onClick = {
                            findQuery = ""
                            findOpen = false
                            onClearSearchResults()
                        },
                    )
                }
            }

            // History Search Results Panel (`git-graph-search-results`)
            if (searchResults != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("git-graph-search-results"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            "${searchResults.hits.size} commit(s) across all history",
                            style = ppmText(11f, FontWeight.SemiBold).copy(color = tokens.text2.color),
                        )
                        SettingsButton(label = "Close", onClick = onClearSearchResults)
                    }
                    for (hit in searchResults.hits) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onClearSearchResults()
                                    val fallback = GitGraphVertex(
                                        hash = hit.hash,
                                        parents = emptyList(),
                                        author = hit.author,
                                        authorEmail = hit.authorEmail,
                                        authorDate = hit.authorDate,
                                        committer = hit.author,
                                        committerEmail = hit.authorEmail,
                                        commitDate = hit.authorDate,
                                        refs = emptyList(),
                                        message = hit.subject,
                                    )
                                    selectCommit(hit.hash, fallback = fallback)
                                }
                                .padding(vertical = 4.dp, horizontal = 6.dp)
                                .testTag("git-graph-search-hit-${hit.hash}"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GitGraphAuthorAvatar(hit.author, hit.authorEmail, sizeDp = 18.dp)
                            BasicText(
                                hit.subject,
                                style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            BasicText(
                                hit.hash.take(7),
                                style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                            )
                        }
                    }
                }
            }

            // ── 4. Main Body: Commit Graph List + Inspector ──
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Left: Commit Graph Table
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag("git-graph-list-pane"),
                ) {
                    // Column Header Row (Tablet)
                    if (!mobile) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(tokens.panel2.color)
                                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("git-graph-table-header"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.width(effectiveGraphColWidthDp.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BasicText("Graph", style = ppmText(10f, FontWeight.SemiBold).copy(color = tokens.text3.color))
                                Box(
                                    modifier = Modifier
                                        .width(8.dp)
                                        .height(14.dp)
                                        .background(tokens.border.color)
                                        .pointerInput(Unit) {
                                            detectHorizontalDragGestures { _, dragAmount ->
                                                val next = (customGraphWidthDp ?: effectiveGraphColWidthDp) + dragAmount / density
                                                customGraphWidthDp = next.coerceIn(GRAPH_MIN_W, 280f)
                                            }
                                        }
                                        .testTag("git-graph-col-resize-handle"),
                                )
                            }
                            BasicText(
                                "Description",
                                style = ppmText(10f, FontWeight.SemiBold).copy(color = tokens.text3.color),
                                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                            )
                            if (settings.colChanges) {
                                BasicText(
                                    "Changes",
                                    style = ppmText(10f, FontWeight.SemiBold).copy(color = tokens.text3.color),
                                    modifier = Modifier.width(76.dp),
                                )
                            }
                            if (settings.colAuthor) {
                                BasicText(
                                    "Author",
                                    style = ppmText(10f, FontWeight.SemiBold).copy(color = tokens.text3.color),
                                    modifier = Modifier.width(120.dp),
                                )
                            }
                            if (settings.colDate) {
                                BasicText(
                                    "Date",
                                    style = ppmText(10f, FontWeight.SemiBold).copy(color = tokens.text3.color),
                                    modifier = Modifier.width(72.dp),
                                )
                            }
                            if (settings.colHash) {
                                BasicText(
                                    "Commit",
                                    style = ppmText(10f, FontWeight.SemiBold).copy(color = tokens.text3.color),
                                    modifier = Modifier.width(64.dp),
                                )
                            }
                        }
                    }

                    val rowHeightDp = if (mobile) 56.dp else 36.dp
                    val headAhead = if (changes?.branch?.head == repoInfo.currentBranch) (changes.branch.ahead) else 0
                    val loadedMatchSet = remember(loadedMatches) { loadedMatches.toHashSet() }

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("git-graph-commit-list"),
                    ) {
                        itemsIndexed(
                            items = displayCommits,
                            key = { _, item -> item.hash },
                        ) { rowIndex, commit ->
                            val isSelected = selectedCommitHash == commit.hash
                            val isDimmed = findMode == "loaded" && findQuery.isNotBlank() && !commit.isWip && commit.hash !in loadedMatchSet
                            val rowBg = when {
                                isSelected -> tokens.accent.opacity(0.14).color
                                commit.isWip -> tokens.panel.color
                                else -> tokens.bgSolid.color
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(rowHeightDp)
                                    .background(rowBg)
                                    .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                                    .combinedClickable(
                                        onClick = {
                                            val dragged = armedDragRef
                                            if (dragged != null && !commit.isWip && !commit.isStash) {
                                                activeDropMenu = dragged to commit
                                                armedDragRef = null
                                            } else {
                                                selectCommit(commit.hash)
                                            }
                                        },
                                        onLongClick = {
                                            activeCommitMenu = commit
                                        },
                                    )
                                    .padding(end = 8.dp)
                                    .testTag("git-graph-row-${commit.hash}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 1. Native Canvas Graph Column (`git-graph-canvas-<hash>`)
                                Box(
                                    modifier = Modifier
                                        .width(effectiveGraphColWidthDp.dp)
                                        .fillMaxHeight()
                                        .clipToBounds()
                                        .pointerInput(maxGraphPanDp) {
                                            if (maxGraphPanDp > 0f) {
                                                detectHorizontalDragGestures { _, dragAmount ->
                                                    graphPanXDp = (graphPanXDp - dragAmount / density).coerceIn(0f, maxGraphPanDp)
                                                }
                                            }
                                        }
                                        .testTag("git-graph-canvas-${commit.hash}"),
                                ) {
                                    val accentColor = tokens.accent.color
                                    val subtextColor = tokens.text3.color
                                    val bgColor = tokens.bgSolid.color
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        drawGitGraphRowSlice(
                                            layout = graphLayout,
                                            rowIndex = rowIndex,
                                            graphStyle = settings.graphStyle,
                                            panXDp = graphPanXDp,
                                            accentColor = accentColor,
                                            subtextColor = subtextColor,
                                            bgColor = bgColor,
                                        )
                                    }
                                }

                                // 2. Message + Ref Pills Column
                                val classifiedRefs = remember(
                                    commit.refs,
                                    repoInfo.remotes,
                                    settings.showTags,
                                    settings.showRemoteBranches,
                                    headAhead,
                                ) {
                                    classifyGitGraphRefs(
                                        refs = commit.refs,
                                        remotes = repoInfo.remotes,
                                        showTags = settings.showTags,
                                        showRemoteBranches = settings.showRemoteBranches,
                                        headAheadCount = headAhead,
                                    )
                                }
                                val laneSlot = graphLayout.commitLaneSlot(commit) ?: 0
                                val pillColor = laneColorForSlot(laneSlot, tokens.accent.color, tokens.text3.color)

                                Row(
                                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    for (ref in classifiedRefs) {
                                        Box(
                                            modifier = Modifier
                                                .background(
                                                    if (ref.type == "head") pillColor.copy(alpha = 0.22f) else tokens.panel2.color,
                                                    RoundedCornerShape(4.dp),
                                                )
                                                .cssBorder(pillColor)
                                                .combinedClickable(
                                                    onClick = { activeRefMenu = ref to commit },
                                                    onLongClick = {
                                                        if (ref.type != "tag" && ref.type != "stash") {
                                                            armedDragRef = ref
                                                        } else {
                                                            activeRefMenu = ref to commit
                                                        }
                                                    },
                                                )
                                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                                .testTag("git-graph-ref-pill-${ref.name}"),
                                        ) {
                                            val suffix = buildString {
                                                if (ref.synced) append(" ☁")
                                                if (ref.aheadCount > 0) append(" ↑${ref.aheadCount}")
                                            }
                                            BasicText(
                                                "${ref.name}$suffix",
                                                style = ppmText(
                                                    10f,
                                                    if (ref.type == "head") FontWeight.Bold else FontWeight.Medium,
                                                    FontKind.MONO,
                                                ).copy(color = tokens.text.color),
                                            )
                                        }
                                    }

                                    val subject = commit.message.lineSequence().firstOrNull().orEmpty()
                                    BasicText(
                                        if (commit.isWip) "Uncommitted changes (${changedFiles.size} file(s))" else subject,
                                        style = ppmText(
                                            12f,
                                            if (commit.isWip || commit.hash == repoInfo.head) FontWeight.SemiBold else FontWeight.Normal,
                                        ).copy(
                                            color = if (isDimmed) tokens.text3.color else tokens.text.color,
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )

                                    if (commit.isWip) {
                                        SettingsButton(
                                            label = "Review",
                                            modifier = Modifier.testTag("git-graph-wip-review-btn"),
                                            onClick = { onSendMessage(GitGraphWebviewMessages.openReview()) },
                                        )
                                    }
                                }

                                // 3. Optional Columns on Tablet (`colChanges`, `colAuthor`, `colDate`, `colHash`)
                                if (!mobile) {
                                    if (settings.colChanges) {
                                        Box(modifier = Modifier.width(76.dp)) {
                                            val stat = pageState.stats[commit.hash]
                                            when {
                                                commit.isWip -> {
                                                    BasicText(
                                                        "${changedFiles.size} files",
                                                        style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.accent.color),
                                                    )
                                                }
                                                commit.parents.size > 1 || commit.isStash -> {
                                                    BasicText("—", style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.text3.color))
                                                }
                                                stat != null -> {
                                                    BasicText(
                                                        "+${stat.insertions} −${stat.deletions}",
                                                        style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.success.color),
                                                        modifier = Modifier.testTag("git-graph-stat-${commit.hash}"),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (settings.colAuthor) {
                                        Row(
                                            modifier = Modifier.width(120.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            if (!commit.isWip) {
                                                GitGraphAuthorAvatar(commit.author, commit.authorEmail, sizeDp = 16.dp)
                                                BasicText(
                                                    commit.author,
                                                    style = ppmText(11f).copy(color = tokens.text2.color),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                    if (settings.colDate) {
                                        BasicText(
                                            if (commit.isWip) "now" else formatGitGraphDate(commit.commitDate, settings.dateFormat),
                                            style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                                            modifier = Modifier.width(72.dp),
                                        )
                                    }
                                    if (settings.colHash) {
                                        BasicText(
                                            if (commit.isWip || commit.isStash) "" else commit.hash.take(7),
                                            style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                                            modifier = Modifier.width(64.dp),
                                        )
                                    }
                                }

                                // Context menu button (`⋮`) on every row
                                SettingsButton(
                                    label = "⋮",
                                    modifier = Modifier.testTag("git-graph-row-menu-btn-${commit.hash}"),
                                    onClick = { activeCommitMenu = commit },
                                )
                            }
                        }

                        if (pageState.hasMore) {
                            item(key = "__load_more__") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    SettingsButton(
                                        label = "Load more commits (${pageState.commits.size} loaded)",
                                        modifier = Modifier.testTag("git-graph-load-more-btn"),
                                        onClick = {
                                            onSendMessage(
                                                GitGraphWebviewMessages.requestCommits(
                                                    branch = pageState.scope,
                                                    maxCommits = settings.maxCommits,
                                                    skip = pageState.commits.size,
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // Bottom Status Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(tokens.panel.color)
                            .cssBorder(tokens.border.color, sides = CssSides.Top)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag("git-graph-status-bar"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            "${pageState.commits.size} commit(s) · ${repoInfo.branches.size} branch(es) · ${repoInfo.tags.size} tag(s)",
                            style = ppmText(11f).copy(color = tokens.text3.color),
                        )
                        if (toastMessage != null) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.testTag("git-graph-toast"),
                            ) {
                                BasicText(toastMessage, style = ppmText(11f, FontWeight.Medium).copy(color = tokens.accent.color))
                                val undo = toastUndo
                                if (undo != null) {
                                    SettingsButton(
                                        label = undo.first,
                                        primary = true,
                                        modifier = Modifier.testTag("git-graph-toast-undo-btn"),
                                        onClick = {
                                            undo.second()
                                            onDismissToast()
                                        },
                                    )
                                }
                                SettingsButton(label = "×", onClick = onDismissToast)
                            }
                        }
                    }
                }

                // Right: Side-by-side Inspector on wide tablets (`> 900.dp`)
                if (inspectorOpen && sideBySideInspector) {
                    Box(
                        modifier = Modifier
                            .width(380.dp)
                            .fillMaxHeight()
                            .background(tokens.panel.color)
                            .cssBorder(tokens.border.color, sides = CssSides.Start)
                            .testTag("git-graph-inspector"),
                    ) {
                        GitGraphInspectorPane(
                            selectedHash = selectedCommitHash,
                            displayCommits = displayCommits,
                            fallbackCommit = fallbackCommit,
                            commitDetails = commitDetails,
                            repoInfo = repoInfo,
                            settings = settings,
                            changes = changes,
                            localDraftText = localDraftText,
                            onDraftChange = {
                                localDraftText = it
                                onSendMessage(GitGraphWebviewMessages.saveDraft(it))
                            },
                            showCommitSplitMenu = showCommitSplitMenu,
                            onToggleCommitSplitMenu = { showCommitSplitMenu = !showCommitSplitMenu },
                            onClose = { inspectorOpen = false },
                            onSelectCommit = { selectCommit(it) },
                            onOpenCommitMenu = { activeCommitMenu = it },
                            onOpenFileMenu = { path, h, p -> activeFileMenu = Triple(path, h, p) },
                            onSendMessage = onSendMessage,
                            onSendTrackedAction = onSendTrackedAction,
                            onRunGitAction = ::runGitAction,
                            onShowToast = onShowToast,
                            onOpenDialog = { activeDialog = it },
                        )
                    }
                }
            }
        }

        // Overlay / Bottom-Sheet Inspector when `inspectorOpen && !sideBySideInspector`
        if (inspectorOpen && !sideBySideInspector) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = if (mobile) Alignment.BottomCenter else Alignment.CenterEnd,
            ) {
                Box(
                    modifier = (if (mobile) {
                        Modifier.fillMaxWidth().heightIn(max = 520.dp)
                    } else {
                        Modifier.width(380.dp).fillMaxHeight()
                    })
                        .background(tokens.panel.color, if (mobile) RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp) else RoundedCornerShape(0.dp))
                        .cssBorder(tokens.border.color)
                        .testTag("git-graph-inspector"),
                ) {
                    GitGraphInspectorPane(
                        selectedHash = selectedCommitHash,
                        displayCommits = displayCommits,
                        fallbackCommit = fallbackCommit,
                        commitDetails = commitDetails,
                        repoInfo = repoInfo,
                        settings = settings,
                        changes = changes,
                        localDraftText = localDraftText,
                        onDraftChange = {
                            localDraftText = it
                            onSendMessage(GitGraphWebviewMessages.saveDraft(it))
                        },
                        showCommitSplitMenu = showCommitSplitMenu,
                        onToggleCommitSplitMenu = { showCommitSplitMenu = !showCommitSplitMenu },
                        onClose = { inspectorOpen = false },
                        onSelectCommit = { selectCommit(it) },
                        onOpenCommitMenu = { activeCommitMenu = it },
                        onOpenFileMenu = { path, h, p -> activeFileMenu = Triple(path, h, p) },
                        onSendMessage = onSendMessage,
                        onSendTrackedAction = onSendTrackedAction,
                        onRunGitAction = ::runGitAction,
                        onShowToast = onShowToast,
                        onOpenDialog = { activeDialog = it },
                    )
                }
            }
        }

        // ── 5. Scope / Branch Filter Menu (`git-graph-scope-menu`) ──
        if (showScopeMenu) {
            GitGraphModalOverlay(testTag = "git-graph-scope-menu", onDismiss = { showScopeMenu = false }) {
                BasicText("Filter Branch & Visibility", style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color))
                SettingsTextInput(
                    value = scopeFilterText,
                    onValueChange = { scopeFilterText = it },
                    placeholder = "Filter branches...",
                    modifier = Modifier.fillMaxWidth().testTag("git-graph-scope-filter-input"),
                )
                SettingsButton(
                    label = "${if (pageState.scope == "all") "✓ " else ""}All branches",
                    primary = pageState.scope == "all",
                    modifier = Modifier.fillMaxWidth().testTag("git-graph-scope-option-all"),
                    onClick = {
                        showScopeMenu = false
                        onSendMessage(GitGraphWebviewMessages.requestCommits(branch = null, maxCommits = settings.maxCommits))
                    },
                )
                val q = scopeFilterText.trim().lowercase()
                for (b in repoInfo.branches.filter { q.isEmpty() || it.name.lowercase().contains(q) }) {
                    SettingsButton(
                        label = "${if (pageState.scope == b.name) "✓ " else ""}${b.name}${if (b.name == repoInfo.currentBranch) " (checked out)" else ""}",
                        primary = pageState.scope == b.name,
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-scope-option-${b.name}"),
                        onClick = {
                            showScopeMenu = false
                            onSendMessage(GitGraphWebviewMessages.requestCommits(branch = b.name, maxCommits = settings.maxCommits))
                        },
                    )
                }
                BasicText("SHOW IN GRAPH", style = ppmText(10f, FontWeight.SemiBold).copy(color = tokens.text3.color))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsButton(
                        label = "${if (settings.showRemoteBranches) "☑" else "☐"} Remotes",
                        primary = settings.showRemoteBranches,
                        modifier = Modifier.testTag("git-graph-toggle-remotes"),
                        onClick = {
                            onSendMessage(GitGraphWebviewMessages.updateSetting("showRemoteBranches", JsonPrimitive(!settings.showRemoteBranches)))
                        },
                    )
                    SettingsButton(
                        label = "${if (settings.showTags) "☑" else "☐"} Tags",
                        primary = settings.showTags,
                        modifier = Modifier.testTag("git-graph-toggle-tags"),
                        onClick = {
                            onSendMessage(GitGraphWebviewMessages.updateSetting("showTags", JsonPrimitive(!settings.showTags)))
                        },
                    )
                    SettingsButton(
                        label = "${if (settings.showStashes) "☑" else "☐"} Stashes",
                        primary = settings.showStashes,
                        modifier = Modifier.testTag("git-graph-toggle-stashes"),
                        onClick = {
                            onSendMessage(GitGraphWebviewMessages.updateSetting("showStashes", JsonPrimitive(!settings.showStashes)))
                        },
                    )
                }
            }
        }

        // ── 6. Stashes Menu (`git-graph-stashes-menu`) ──
        if (showStashesMenu) {
            GitGraphModalOverlay(testTag = "git-graph-stashes-menu", onDismiss = { showStashesMenu = false }) {
                BasicText("Stashes (${repoInfo.stashes.size})", style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color))
                if (repoInfo.stashes.isEmpty()) {
                    BasicText("No stashes", style = ppmText(12f).copy(color = tokens.text3.color))
                }
                for (s in repoInfo.stashes) {
                    val (_, msgText) = parseGitGraphStashMessage(s.message)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            BasicText("stash@{${s.index}}: $msgText", style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            SettingsButton(
                                label = "Apply",
                                modifier = Modifier.testTag("git-graph-stash-apply-${s.index}"),
                                onClick = {
                                    showStashesMenu = false
                                    onSendTrackedAction(
                                        "stashAction",
                                        { reqId -> GitGraphWebviewMessages.stashAction("apply", s.index, s.hash, reqId) },
                                        { res -> onShowToast(if (res.ok) "Stash applied" else (res.error ?: "Apply failed"), null) },
                                    )
                                },
                            )
                            SettingsButton(
                                label = "Pop",
                                modifier = Modifier.testTag("git-graph-stash-pop-${s.index}"),
                                onClick = {
                                    showStashesMenu = false
                                    onSendTrackedAction(
                                        "stashAction",
                                        { reqId -> GitGraphWebviewMessages.stashAction("pop", s.index, s.hash, reqId) },
                                        { res -> onShowToast(if (res.ok) "Stash popped" else (res.error ?: "Pop failed"), null) },
                                    )
                                },
                            )
                            SettingsButton(
                                label = "Drop",
                                modifier = Modifier.testTag("git-graph-stash-drop-${s.index}"),
                                onClick = {
                                    showStashesMenu = false
                                    activeDialog = GitGraphDialogSpec(
                                        title = "Drop stash@{${s.index}}?",
                                        message = "The stash is deleted for good.",
                                        confirmLabel = "Drop stash",
                                        onConfirm = { _, _ ->
                                            onSendTrackedAction(
                                                "stashAction",
                                                { reqId -> GitGraphWebviewMessages.stashAction("drop", s.index, s.hash, reqId) },
                                                { res -> onShowToast(if (res.ok) "Stash dropped" else (res.error ?: "Drop failed"), null) },
                                            )
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
                SettingsButton(
                    label = "+ Stash changes…",
                    primary = changedFiles.isNotEmpty(),
                    modifier = Modifier.testTag("git-graph-stash-create-btn"),
                    onClick = {
                        showStashesMenu = false
                        activeDialog = GitGraphDialogSpec(
                            title = "Stash changes",
                            message = "Puts every change aside in a new stash.",
                            inputPlaceholder = "Message (optional)",
                            checkboxLabel = "Include untracked files",
                            checkboxDefault = true,
                            confirmLabel = "Stash",
                            onConfirm = { msgVal, includeUntracked ->
                                onSendTrackedAction(
                                    "stash",
                                    { reqId -> GitGraphWebviewMessages.stash(msgVal.trim().ifEmpty { null }, includeUntracked, reqId) },
                                    { res -> onShowToast(if (res.ok) "Stashed changes" else (res.error ?: "Could not stash"), null) },
                                )
                            },
                        )
                    },
                )
            }
        }

        // ── 7. Worktrees Menu (`git-graph-worktrees-menu`) & Add Worktree Dialog ──
        if (showWorktreesMenu) {
            GitGraphModalOverlay(testTag = "git-graph-worktrees-menu", onDismiss = { showWorktreesMenu = false }) {
                BasicText("Worktrees (${worktrees.size})", style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color))
                for (wt in worktrees) {
                    val isCurrent = wt.path == repoInfo.path
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            BasicText(wt.path, style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text.color))
                            BasicText(
                                "${wt.branch.ifEmpty { "detached" }}${if (isCurrent) " · this one" else ""}",
                                style = ppmText(10f).copy(color = tokens.text3.color),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (!isCurrent) {
                                SettingsButton(
                                    label = "Open",
                                    modifier = Modifier.testTag("git-graph-wt-open-${wt.branch}"),
                                    onClick = {
                                        showWorktreesMenu = false
                                        onSendMessage(GitGraphWebviewMessages.openWorktree(wt.path))
                                    },
                                )
                            }
                            SettingsButton(
                                label = "Copy",
                                onClick = {
                                    copyGraphText(context, "Worktree path", wt.path)
                                    onShowToast("Copied path", null)
                                },
                            )
                            if (!wt.isMain && !isCurrent) {
                                SettingsButton(
                                    label = "Remove",
                                    modifier = Modifier.testTag("git-graph-wt-remove-${wt.branch}"),
                                    onClick = {
                                        showWorktreesMenu = false
                                        onSendTrackedAction(
                                            "removeWorktree",
                                            { reqId -> GitGraphWebviewMessages.removeWorktree(wt.path, force = false, reqId) },
                                            { res -> onShowToast(if (res.ok) "Removed worktree" else (res.error ?: "Remove failed"), null) },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsButton(
                        label = "+ Add worktree…",
                        primary = true,
                        modifier = Modifier.testTag("git-graph-wt-add-btn"),
                        onClick = {
                            showWorktreesMenu = false
                            showAddWorktreeDialog = true
                        },
                    )
                    SettingsButton(
                        label = "Prune stale",
                        modifier = Modifier.testTag("git-graph-wt-prune-btn"),
                        onClick = {
                            showWorktreesMenu = false
                            onSendTrackedAction(
                                "pruneWorktrees",
                                { reqId -> GitGraphWebviewMessages.pruneWorktrees(reqId) },
                                { res -> onShowToast(if (res.ok) "Pruned stale worktrees" else (res.error ?: "Prune failed"), null) },
                            )
                        },
                    )
                }
            }
        }

        if (showAddWorktreeDialog) {
            var wtPath by remember { mutableStateOf("${repoInfo.path.trimEnd('/')}-worktree") }
            var wtBranch by remember { mutableStateOf("") }
            var wtNewBranch by remember { mutableStateOf(true) }
            GitGraphModalOverlay(testTag = "git-graph-add-worktree-dialog", onDismiss = { showAddWorktreeDialog = false }) {
                BasicText("Add worktree", style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color))
                SettingsTextInput(
                    value = wtPath,
                    onValueChange = { wtPath = it },
                    placeholder = "Folder for the worktree",
                    modifier = Modifier.fillMaxWidth().testTag("git-graph-wt-path-input"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsButton(
                        label = "New branch",
                        primary = wtNewBranch,
                        onClick = { wtNewBranch = true },
                    )
                    SettingsButton(
                        label = "Existing branch",
                        primary = !wtNewBranch,
                        onClick = { wtNewBranch = false },
                    )
                }
                SettingsTextInput(
                    value = wtBranch,
                    onValueChange = { wtBranch = it },
                    placeholder = "Branch name",
                    modifier = Modifier.fillMaxWidth().testTag("git-graph-wt-branch-input"),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SettingsButton(label = "Cancel", onClick = { showAddWorktreeDialog = false })
                    Spacer(Modifier.width(6.dp))
                    SettingsButton(
                        label = "Add worktree",
                        primary = wtPath.isNotBlank(),
                        modifier = Modifier.testTag("git-graph-wt-submit-btn"),
                        onClick = {
                            if (wtPath.isNotBlank()) {
                                showAddWorktreeDialog = false
                                onSendTrackedAction(
                                    "addWorktree",
                                    { reqId ->
                                        GitGraphWebviewMessages.addWorktree(
                                            path = wtPath.trim(),
                                            branch = if (!wtNewBranch) wtBranch.trim() else null,
                                            newBranch = if (wtNewBranch) wtBranch.trim() else null,
                                            reqId = reqId,
                                        )
                                    },
                                    { res -> onShowToast(if (res.ok) "Added worktree" else (res.error ?: "Add worktree failed"), null) },
                                )
                            }
                        },
                    )
                }
            }
        }

        // ── 8. Submodules Menu (`git-graph-submodules-menu`) ──
        if (showSubmodulesMenu) {
            GitGraphModalOverlay(testTag = "git-graph-submodules-menu", onDismiss = { showSubmodulesMenu = false }) {
                BasicText("Submodules (${submodules.size})", style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color))
                for (sm in submodules) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText("${sm.path} (${sm.state})", style = ppmText(12f, kind = FontKind.MONO).copy(color = tokens.text.color))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            SettingsButton(
                                label = "Update",
                                modifier = Modifier.testTag("git-graph-sm-update-${sm.path}"),
                                onClick = {
                                    showSubmodulesMenu = false
                                    onSendTrackedAction(
                                        "updateSubmodule",
                                        { reqId -> GitGraphWebviewMessages.updateSubmodule(sm.path, reqId) },
                                        { res -> onShowToast(if (res.ok) "Updated ${sm.path}" else (res.error ?: "Update failed"), null) },
                                    )
                                },
                            )
                            SettingsButton(
                                label = "Open",
                                onClick = {
                                    showSubmodulesMenu = false
                                    onSendMessage(GitGraphWebviewMessages.openSubmodule(sm.path))
                                },
                            )
                        }
                    }
                }
            }
        }

        // ── 9. View Menu (`git-graph-view-menu`) ──
        if (showViewMenu) {
            GitGraphModalOverlay(testTag = "git-graph-view-menu", onDismiss = { showViewMenu = false }) {
                BasicText("View & Columns", style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color))
                if (!mobile) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((key, label, active) in listOf(
                            Triple("colChanges", "Changes", settings.colChanges),
                            Triple("colAuthor", "Author", settings.colAuthor),
                            Triple("colDate", "Date", settings.colDate),
                            Triple("colHash", "Hash", settings.colHash),
                        )) {
                            SettingsButton(
                                label = "${if (active) "☑" else "☐"} $label",
                                primary = active,
                                modifier = Modifier.testTag("git-graph-col-toggle-$key"),
                                onClick = {
                                    onSendMessage(GitGraphWebviewMessages.updateSetting(key, JsonPrimitive(!active)))
                                },
                            )
                        }
                    }
                }
                SettingsButton(
                    label = "Refresh",
                    modifier = Modifier.fillMaxWidth().testTag("git-graph-view-refresh-btn"),
                    onClick = {
                        showViewMenu = false
                        onSendMessage(GitGraphWebviewMessages.requestRepoInfo())
                        onSendMessage(GitGraphWebviewMessages.requestCommits(branch = pageState.scope, maxCommits = settings.maxCommits))
                        onSendMessage(GitGraphWebviewMessages.requestChanges())
                    },
                )
                SettingsButton(
                    label = "Reflog",
                    modifier = Modifier.fillMaxWidth().testTag("git-graph-view-reflog-btn"),
                    onClick = {
                        showViewMenu = false
                        onSendMessage(GitGraphWebviewMessages.openReflog())
                    },
                )
                SettingsButton(
                    label = "Git Graph settings…",
                    modifier = Modifier.fillMaxWidth().testTag("git-graph-view-settings-btn"),
                    onClick = {
                        showViewMenu = false
                        onSendMessage(GitGraphWebviewMessages.requestSettings())
                        onSendMessage(GitGraphWebviewMessages.requestUserDetails())
                        showSettingsSheet = true
                    },
                )
            }
        }

        // ── 10. Commit Row Context Menu (`git-graph-commit-menu`) ──
        val menuCommit = activeCommitMenu
        if (menuCommit != null) {
            val shortHash = menuCommit.hash.take(7)
            val isHead = menuCommit.hash == repoInfo.head
            GitGraphModalOverlay(testTag = "git-graph-commit-menu", onDismiss = { activeCommitMenu = null }) {
                BasicText(
                    if (menuCommit.isWip) "Uncommitted changes" else if (menuCommit.isStash) "Stash $shortHash" else "Commit $shortHash",
                    style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color),
                )
                if (menuCommit.isWip) {
                    SettingsButton(
                        label = "Stash all changes",
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-wip-menu-stash"),
                        onClick = {
                            activeCommitMenu = null
                            onSendTrackedAction(
                                "stash",
                                { reqId -> GitGraphWebviewMessages.stash(null, true, reqId) },
                                { res -> onShowToast(if (res.ok) "Stashed changes" else (res.error ?: "Stash failed"), null) },
                            )
                        },
                    )
                    SettingsButton(
                        label = "Review changes",
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-wip-menu-review"),
                        onClick = {
                            activeCommitMenu = null
                            onSendMessage(GitGraphWebviewMessages.openReview())
                        },
                    )
                    SettingsButton(
                        label = "Discard all changes…",
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-wip-menu-discard-all"),
                        onClick = {
                            activeCommitMenu = null
                            val paths = changedFiles.map { it.path }
                            if (paths.isNotEmpty()) {
                                onSendTrackedAction(
                                    "discardFiles",
                                    { reqId -> GitGraphWebviewMessages.discardFiles(paths, reqId) },
                                    { res -> onShowToast(if (res.ok) "Discarded changes" else (res.error ?: "Discard failed"), null) },
                                )
                            }
                        },
                    )
                } else if (menuCommit.isStash) {
                    val stashIdx = menuCommit.stashIndex ?: 0
                    SettingsButton(
                        label = "Apply",
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-stash-menu-apply"),
                        onClick = {
                            activeCommitMenu = null
                            onSendTrackedAction(
                                "stashAction",
                                { reqId -> GitGraphWebviewMessages.stashAction("apply", stashIdx, menuCommit.hash, reqId) },
                                { res -> onShowToast(if (res.ok) "Stash applied" else (res.error ?: "Apply failed"), null) },
                            )
                        },
                    )
                    SettingsButton(
                        label = "Pop",
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-stash-menu-pop"),
                        onClick = {
                            activeCommitMenu = null
                            onSendTrackedAction(
                                "stashAction",
                                { reqId -> GitGraphWebviewMessages.stashAction("pop", stashIdx, menuCommit.hash, reqId) },
                                { res -> onShowToast(if (res.ok) "Stash popped" else (res.error ?: "Pop failed"), null) },
                            )
                        },
                    )
                } else {
                    SettingsButton(
                        label = "Checkout",
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-menu-checkout"),
                        onClick = {
                            activeCommitMenu = null
                            if (!isHead) {
                                runGitAction("checkout", buildJsonObject { put("target", menuCommit.hash) }, "Checked out $shortHash")
                            }
                        },
                    )
                    SettingsButton(
                        label = "Create branch here…",
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-menu-create-branch"),
                        onClick = {
                            activeCommitMenu = null
                            activeDialog = GitGraphDialogSpec(
                                title = "Create a branch at $shortHash",
                                inputPlaceholder = "Branch name",
                                checkboxLabel = "Check it out",
                                checkboxDefault = true,
                                confirmLabel = "Create branch",
                                onConfirm = { name, checkout ->
                                    if (name.isNotBlank()) {
                                        runGitAction(
                                            "createBranch",
                                            buildJsonObject {
                                                put("name", name.trim())
                                                put("startPoint", menuCommit.hash)
                                                put("force", false)
                                            },
                                            "Created ${name.trim()}",
                                        )
                                        if (checkout) {
                                            runGitAction("checkout", buildJsonObject { put("target", name.trim()) }, "Switched to ${name.trim()}")
                                        }
                                    }
                                },
                            )
                        },
                    )
                    SettingsButton(
                        label = "Cherry-pick",
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-menu-cherry-pick"),
                        onClick = {
                            activeCommitMenu = null
                            if (!isHead) {
                                runGitAction("cherryPick", buildJsonObject { put("hash", menuCommit.hash) }, "Cherry-picked $shortHash")
                            }
                        },
                    )
                    SettingsButton(
                        label = "Revert…",
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-menu-revert"),
                        onClick = {
                            activeCommitMenu = null
                            activeDialog = GitGraphDialogSpec(
                                title = "Revert $shortHash?",
                                message = "Makes a new commit that undoes this commit.",
                                confirmLabel = "Revert",
                                onConfirm = { _, _ ->
                                    runGitAction("revert", buildJsonObject { put("hash", menuCommit.hash) }, "Reverted $shortHash")
                                },
                            )
                        },
                    )
                    SettingsButton(
                        label = "Create tag…",
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-menu-create-tag"),
                        onClick = {
                            activeCommitMenu = null
                            activeDialog = GitGraphDialogSpec(
                                title = "Create a tag at $shortHash",
                                inputPlaceholder = "Tag name",
                                confirmLabel = "Create tag",
                                onConfirm = { tagName, _ ->
                                    if (tagName.isNotBlank()) {
                                        runGitAction(
                                            "createTag",
                                            buildJsonObject {
                                                put("name", tagName.trim())
                                                put("hash", menuCommit.hash)
                                            },
                                            "Tagged $shortHash as ${tagName.trim()}",
                                        )
                                    }
                                },
                            )
                        },
                    )
                    SettingsButton(
                        label = "Compare with HEAD",
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-menu-compare-head"),
                        onClick = {
                            activeCommitMenu = null
                            onSendMessage(GitGraphWebviewMessages.openCompare(ref1 = menuCommit.hash, ref2 = "HEAD"))
                        },
                    )
                    SettingsButton(
                        label = "Copy hash",
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-menu-copy-hash"),
                        onClick = {
                            activeCommitMenu = null
                            copyGraphText(context, "Commit hash", menuCommit.hash)
                            onShowToast("Copied the hash", null)
                        },
                    )
                    SettingsButton(
                        label = "Copy subject",
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-menu-copy-subject"),
                        onClick = {
                            activeCommitMenu = null
                            copyGraphText(context, "Commit subject", menuCommit.message.lineSequence().firstOrNull().orEmpty())
                            onShowToast("Copied the subject", null)
                        },
                    )
                    val rw = repoInfo.remoteWeb
                    if (rw != null) {
                        SettingsButton(
                            label = "Open on ${rw.label}",
                            modifier = Modifier.fillMaxWidth().testTag("git-graph-menu-open-remote-web"),
                            onClick = {
                                activeCommitMenu = null
                                openGraphExternalUrl(context, rw.base + rw.commitPath + menuCommit.hash)
                            },
                        )
                    }
                    val prUrl = buildGitGraphPrUrl(settings.prCreation, repoInfo.currentBranch.ifEmpty { "main" })
                    if (prUrl != null) {
                        SettingsButton(
                            label = "Create pull request",
                            modifier = Modifier.fillMaxWidth().testTag("git-graph-menu-create-pr"),
                            onClick = {
                                activeCommitMenu = null
                                openGraphExternalUrl(context, prUrl)
                            },
                        )
                    }
                    SettingsButton(
                        label = "Reset ${repoInfo.currentBranch.ifEmpty { "HEAD" }} to here…",
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-menu-reset"),
                        onClick = {
                            activeCommitMenu = null
                            activeDialog = GitGraphDialogSpec(
                                title = "Reset ${repoInfo.currentBranch.ifEmpty { "HEAD" }} to $shortHash?",
                                message = "soft keeps changes staged. mixed keeps them unstaged. hard deletes uncommitted changes.",
                                selectOptions = listOf("soft", "mixed", "hard"),
                                selectDefault = "mixed",
                                confirmLabel = "Reset",
                                onConfirm = { mode, _ ->
                                    if (mode == "hard" && changedFiles.isNotEmpty()) {
                                        onShowToast("Hard reset refused while working tree has uncommitted changes", null)
                                    } else {
                                        runGitAction(
                                            "reset",
                                            buildJsonObject {
                                                put("mode", mode)
                                                put("hash", menuCommit.hash)
                                            },
                                            "Reset to $shortHash ($mode)",
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }

        // ── 11. Ref Pill Menu (`git-graph-ref-menu`) ──
        val refMenuTarget = activeRefMenu
        if (refMenuTarget != null) {
            val (ref, commit) = refMenuTarget
            val currentBranch = repoInfo.currentBranch.ifEmpty { "HEAD" }
            GitGraphModalOverlay(testTag = "git-graph-ref-menu", onDismiss = { activeRefMenu = null }) {
                BasicText("${ref.name} (${ref.type})", style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color))
                if (ref.type == "head" || ref.type == "local") {
                    val isHead = ref.type == "head" || ref.name == repoInfo.currentBranch
                    if (!isHead) {
                        SettingsButton(
                            label = "Checkout",
                            modifier = Modifier.fillMaxWidth().testTag("git-graph-ref-checkout"),
                            onClick = {
                                activeRefMenu = null
                                runGitAction("checkout", buildJsonObject { put("target", ref.name) }, "Switched to ${ref.name}")
                            },
                        )
                        SettingsButton(
                            label = "Merge into $currentBranch",
                            modifier = Modifier.fillMaxWidth().testTag("git-graph-ref-merge"),
                            onClick = {
                                activeRefMenu = null
                                runGitAction("merge", buildJsonObject { put("branch", ref.name) }, "Merged ${ref.name}")
                            },
                        )
                        SettingsButton(
                            label = "Rebase $currentBranch onto this",
                            modifier = Modifier.fillMaxWidth().testTag("git-graph-ref-rebase"),
                            onClick = {
                                activeRefMenu = null
                                runGitAction("rebase", buildJsonObject { put("branch", ref.name) }, "Rebased onto ${ref.name}")
                            },
                        )
                    }
                    SettingsButton(
                        label = "Drag onto commit…",
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-ref-arm-drag"),
                        onClick = {
                            activeRefMenu = null
                            armedDragRef = ref
                        },
                    )
                    SettingsButton(
                        label = "Rename…",
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-ref-rename"),
                        onClick = {
                            activeRefMenu = null
                            activeDialog = GitGraphDialogSpec(
                                title = "Rename ${ref.name}",
                                inputPlaceholder = "New name",
                                inputDefault = ref.name,
                                confirmLabel = "Rename",
                                onConfirm = { newName, _ ->
                                    if (newName.isNotBlank() && newName.trim() != ref.name) {
                                        runGitAction(
                                            "renameBranch",
                                            buildJsonObject {
                                                put("oldName", ref.name)
                                                put("newName", newName.trim())
                                            },
                                            "Renamed ${ref.name} to ${newName.trim()}",
                                        )
                                    }
                                },
                            )
                        },
                    )
                    if (!isHead) {
                        SettingsButton(
                            label = "Delete…",
                            modifier = Modifier.fillMaxWidth().testTag("git-graph-ref-delete"),
                            onClick = {
                                activeRefMenu = null
                                runGitAction(
                                    "deleteBranch",
                                    buildJsonObject {
                                        put("name", ref.name)
                                        put("force", false)
                                    },
                                    "Deleted ${ref.name}",
                                )
                            },
                        )
                    }
                } else if (ref.type == "remote") {
                    val prefix = repoInfo.remotes.map { "${it.name}/" }.firstOrNull { ref.name.startsWith(it) }.orEmpty()
                    val remoteName = prefix.removeSuffix("/")
                    val localName = ref.name.removePrefix(prefix)
                    SettingsButton(
                        label = "Check out as local branch ($localName)",
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-ref-checkout-remote"),
                        onClick = {
                            activeRefMenu = null
                            runGitAction("checkout", buildJsonObject { put("target", localName) }, "Switched to $localName")
                        },
                    )
                    SettingsButton(
                        label = "Merge into $currentBranch",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            activeRefMenu = null
                            runGitAction("merge", buildJsonObject { put("branch", ref.name) }, "Merged ${ref.name}")
                        },
                    )
                    if (remoteName.isNotEmpty()) {
                        SettingsButton(
                            label = "Delete from $remoteName…",
                            modifier = Modifier.fillMaxWidth().testTag("git-graph-ref-delete-remote"),
                            onClick = {
                                activeRefMenu = null
                                runGitAction(
                                    "push",
                                    buildJsonObject {
                                        put("remote", remoteName)
                                        put("branch", localName)
                                        put("delete", true)
                                    },
                                    "Deleted $localName from $remoteName",
                                )
                            },
                        )
                    }
                } else if (ref.type == "tag") {
                    SettingsButton(
                        label = "Checkout",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            activeRefMenu = null
                            runGitAction("checkout", buildJsonObject { put("target", ref.name) }, "Checked out ${ref.name}")
                        },
                    )
                    SettingsButton(
                        label = "Delete tag…",
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-ref-delete-tag"),
                        onClick = {
                            activeRefMenu = null
                            runGitAction("deleteTag", buildJsonObject { put("name", ref.name) }, "Deleted tag ${ref.name}")
                        },
                    )
                }
                SettingsButton(
                    label = "Compare with $currentBranch",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        activeRefMenu = null
                        onSendMessage(GitGraphWebviewMessages.openCompare(ref1 = ref.name, ref2 = "HEAD"))
                    },
                )
                SettingsButton(
                    label = "Copy name",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        activeRefMenu = null
                        copyGraphText(context, "Ref name", ref.name)
                        onShowToast("Copied ${ref.name}", null)
                    },
                )
            }
        }

        // ── 12. Drop Ref Onto Commit Menu (`git-graph-drop-menu`) ──
        val dropTarget = activeDropMenu
        if (dropTarget != null) {
            val (ref, commit) = dropTarget
            val short = commit.hash.take(7)
            val currentBranch = repoInfo.currentBranch.ifEmpty { "HEAD" }
            GitGraphModalOverlay(testTag = "git-graph-drop-menu", onDismiss = { activeDropMenu = null }) {
                BasicText("Drop '${ref.name}' onto $short", style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color))
                if (ref.name == repoInfo.currentBranch) {
                    SettingsButton(
                        label = "Rebase ${ref.name} onto $short",
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-drop-rebase"),
                        onClick = {
                            activeDropMenu = null
                            runGitAction("rebase", buildJsonObject { put("branch", commit.hash) }, "Rebased ${ref.name} onto $short")
                        },
                    )
                } else {
                    SettingsButton(
                        label = "Merge ${ref.name} into $currentBranch",
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-drop-merge"),
                        onClick = {
                            activeDropMenu = null
                            runGitAction("merge", buildJsonObject { put("branch", ref.name) }, "Merged ${ref.name} into $currentBranch")
                        },
                    )
                    SettingsButton(
                        label = "Rebase $currentBranch onto ${ref.name}",
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-drop-rebase"),
                        onClick = {
                            activeDropMenu = null
                            runGitAction("rebase", buildJsonObject { put("branch", ref.name) }, "Rebased $currentBranch onto ${ref.name}")
                        },
                    )
                }
                SettingsButton(
                    label = "Compare ${ref.name} with $short",
                    modifier = Modifier.fillMaxWidth().testTag("git-graph-drop-compare"),
                    onClick = {
                        activeDropMenu = null
                        onSendMessage(GitGraphWebviewMessages.openCompare(ref1 = ref.name, ref2 = commit.hash))
                    },
                )
            }
        }

        // ── 13. Inspector File Context Menu (`git-graph-file-menu`) ──
        val fileMenuTarget = activeFileMenu
        if (fileMenuTarget != null) {
            val (filePath, commitHash, parentHash) = fileMenuTarget
            val short = commitHash.take(7)
            GitGraphModalOverlay(testTag = "git-graph-file-menu", onDismiss = { activeFileMenu = null }) {
                BasicText(filePath, style = ppmText(12f, FontWeight.SemiBold, FontKind.MONO).copy(color = tokens.text.color))
                SettingsButton(
                    label = "Open diff",
                    modifier = Modifier.fillMaxWidth().testTag("git-graph-file-menu-diff"),
                    onClick = {
                        activeFileMenu = null
                        onSendMessage(GitGraphWebviewMessages.openDiff(filePath, commitHash, parentHash))
                    },
                )
                SettingsButton(
                    label = "Open file",
                    modifier = Modifier.fillMaxWidth().testTag("git-graph-file-menu-open"),
                    onClick = {
                        activeFileMenu = null
                        onSendMessage(GitGraphWebviewMessages.openFile(filePath))
                    },
                )
                SettingsButton(
                    label = "Blame",
                    modifier = Modifier.fillMaxWidth().testTag("git-graph-file-menu-blame"),
                    onClick = {
                        activeFileMenu = null
                        onSendMessage(GitGraphWebviewMessages.openBlame(filePath))
                    },
                )
                SettingsButton(
                    label = "Blame at $short",
                    modifier = Modifier.fillMaxWidth().testTag("git-graph-file-menu-blame-at"),
                    onClick = {
                        activeFileMenu = null
                        onSendMessage(GitGraphWebviewMessages.openBlame(filePath, commitHash))
                    },
                )
                SettingsButton(
                    label = "File history",
                    modifier = Modifier.fillMaxWidth().testTag("git-graph-file-menu-history"),
                    onClick = {
                        activeFileMenu = null
                        onSendMessage(GitGraphWebviewMessages.openFileHistory(filePath))
                    },
                )
                SettingsButton(
                    label = "Copy path",
                    modifier = Modifier.fillMaxWidth().testTag("git-graph-file-menu-copy"),
                    onClick = {
                        activeFileMenu = null
                        copyGraphText(context, "File path", filePath)
                        onShowToast("Copied the path", null)
                    },
                )
            }
        }

        // ── 14. Git Graph Settings Sheet (`git-graph-settings-sheet`) ──
        if (showSettingsSheet) {
            GitGraphSettingsDialog(
                settings = settings,
                userDetails = userDetails,
                ownerRepo = ownerRepo,
                remotes = repoInfo.remotes,
                onSendMessage = onSendMessage,
                onDismiss = { showSettingsSheet = false },
            )
        }

        // ── 15. Generic Native Confirmation / Input Dialog (`git-graph-dialog`) ──
        val dlg = activeDialog
        if (dlg != null) {
            var textVal by remember(dlg) { mutableStateOf(dlg.inputDefault) }
            var selectVal by remember(dlg) { mutableStateOf(dlg.selectDefault) }
            var checkVal by remember(dlg) { mutableStateOf(dlg.checkboxDefault) }
            GitGraphModalOverlay(testTag = "git-graph-dialog", onDismiss = { activeDialog = null }) {
                BasicText(dlg.title, style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color))
                if (dlg.message.isNotEmpty()) {
                    BasicText(dlg.message, style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.color))
                }
                if (dlg.inputPlaceholder != null) {
                    SettingsTextInput(
                        value = textVal,
                        onValueChange = { textVal = it },
                        placeholder = dlg.inputPlaceholder,
                        modifier = Modifier.fillMaxWidth().testTag("git-graph-dialog-input"),
                    )
                }
                if (dlg.selectOptions.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (opt in dlg.selectOptions) {
                            SettingsButton(
                                label = opt,
                                primary = selectVal == opt,
                                modifier = Modifier.testTag("git-graph-dialog-opt-$opt"),
                                onClick = { selectVal = opt },
                            )
                        }
                    }
                }
                if (dlg.checkboxLabel != null) {
                    SettingsButton(
                        label = "${if (checkVal) "☑" else "☐"} ${dlg.checkboxLabel}",
                        primary = checkVal,
                        modifier = Modifier.testTag("git-graph-dialog-checkbox"),
                        onClick = { checkVal = !checkVal },
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SettingsButton(
                        label = "Cancel",
                        modifier = Modifier.testTag("git-graph-dialog-cancel-btn"),
                        onClick = { activeDialog = null },
                    )
                    Spacer(Modifier.width(6.dp))
                    SettingsButton(
                        label = dlg.confirmLabel,
                        primary = true,
                        modifier = Modifier.testTag("git-graph-dialog-confirm-btn"),
                        onClick = {
                            val chosen = if (dlg.selectOptions.isNotEmpty()) selectVal else textVal
                            activeDialog = null
                            dlg.onConfirm(chosen, checkVal)
                        },
                    )
                }
            }
        }
    }
}

private data class GitGraphDialogSpec(
    val title: String,
    val message: String = "",
    val inputPlaceholder: String? = null,
    val inputDefault: String = "",
    val selectOptions: List<String> = emptyList(),
    val selectDefault: String = "",
    val checkboxLabel: String? = null,
    val checkboxDefault: Boolean = false,
    val confirmLabel: String = "OK",
    val onConfirm: (value: String, checked: Boolean) -> Unit,
)

@Composable
internal fun GitGraphModalOverlay(
    testTag: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(16.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .background(tokens.panel.color, RoundedCornerShape(8.dp))
                .cssBorder(tokens.border.color)
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SettingsButton(label = "Close", onClick = onDismiss)
            }
        }
    }
}

/**
 * Draws the slice of all graph branch lanes and the row's vertex node for [rowIndex]
 * inside the row's `Canvas` (`GBranch.draw` & `GVertex.draw` in `webview-script-graph.ts`).
 */
private fun DrawScope.drawGitGraphRowSlice(
    layout: GitGraphLayoutResult,
    rowIndex: Int,
    graphStyle: String,
    panXDp: Float,
    accentColor: Color,
    subtextColor: Color,
    bgColor: Color,
) {
    val rowH = size.height
    val gridX = GRAPH_GRID_X.dp.toPx()
    val offsetX = (GRAPH_OFFSET_X - panXDp).dp.toPx()
    val offsetY = rowH / 2f
    val rowOriginY = rowIndex * rowH
    val d = rowH * if (graphStyle == "angular") 0.38f else 0.8f
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()), 0f)
    val strokeW = 2.dp.toPx()

    // 1. Draw branch segments that overlap [rowIndex - 1 .. rowIndex + 1]
    for (branch in layout.branches) {
        val laneColor = laneColorForSlot(
            layout.resolvedLaneSlot(branch.colour, branch.isStash),
            accentColor,
            subtextColor,
        )
        for (seg in branch.segments) {
            val minY = min(seg.p1.y, seg.p2.y)
            val maxY = max(seg.p1.y, seg.p2.y)
            if (maxY < rowIndex - 1 || minY > rowIndex + 1) continue

            val x1 = seg.p1.x * gridX + offsetX
            val y1 = seg.p1.y * rowH + offsetY - rowOriginY
            val x2 = seg.p2.x * gridX + offsetX
            val y2 = seg.p2.y * rowH + offsetY - rowOriginY
            val isSolid = seg.isCommitted && !branch.isStash
            val effect = if (isSolid) null else dashEffect

            if (seg.p1.x == seg.p2.x) {
                drawLine(
                    color = laneColor,
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round,
                    pathEffect = effect,
                )
            } else {
                val path = Path().apply {
                    moveTo(x1, y1)
                    if (graphStyle == "angular") {
                        if (seg.lockedFirst) {
                            lineTo(x2, y2 - d)
                        } else {
                            lineTo(x1, y1 + d)
                        }
                        lineTo(x2, y2)
                    } else {
                        cubicTo(x1, y1 + d, x2, y2 - d, x2, y2)
                    }
                }
                drawPath(
                    path = path,
                    color = laneColor,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect),
                )
            }
        }
    }

    // 2. Draw vertex node for `rowIndex`
    val node = layout.vertices.getOrNull(rowIndex) ?: return
    val cx = node.x * gridX + offsetX
    val cy = offsetY
    val laneColor = laneColorForSlot(
        layout.resolvedLaneSlot(node.colour, node.isStash),
        accentColor,
        subtextColor,
    )
    when (node.kind) {
        GitGraphNodeKind.COMMIT -> {
            drawCircle(color = bgColor, radius = 6.5.dp.toPx(), center = Offset(cx, cy))
            drawCircle(color = laneColor, radius = 4.5.dp.toPx(), center = Offset(cx, cy))
        }
        GitGraphNodeKind.MERGE -> {
            drawCircle(color = bgColor, radius = 6.dp.toPx(), center = Offset(cx, cy))
            drawCircle(
                color = laneColor,
                radius = 3.5.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        GitGraphNodeKind.HEAD -> {
            drawCircle(color = bgColor, radius = 7.5.dp.toPx(), center = Offset(cx, cy))
            drawCircle(
                color = laneColor,
                radius = 5.5.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.dp.toPx()),
            )
            drawCircle(color = laneColor, radius = 2.5.dp.toPx(), center = Offset(cx, cy))
        }
        GitGraphNodeKind.WIP -> {
            drawCircle(color = bgColor, radius = 6.5.dp.toPx(), center = Offset(cx, cy))
            drawCircle(
                color = laneColor,
                radius = 4.75.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = dashEffect),
            )
        }
        GitGraphNodeKind.STASH -> {
            val rBg = 7.dp.toPx()
            val rFg = 5.dp.toPx()
            fun diamondPath(r: Float) = Path().apply {
                moveTo(cx, cy - r)
                lineTo(cx + r, cy)
                lineTo(cx, cy + r)
                lineTo(cx - r, cy)
                close()
            }
            drawPath(diamondPath(rBg), color = bgColor)
            drawPath(
                diamondPath(rFg),
                color = laneColor,
                style = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Round),
            )
        }
    }
}

@Composable
internal fun GitGraphAuthorAvatar(
    name: String,
    email: String,
    sizeDp: Dp = 18.dp,
) {
    val initials = remember(name) { gitGraphAuthorInitials(name) }
    val gradIdx = remember(name, email) { gitGraphAvatarGradientIndex(name, email, AVATAR_GRADIENT_PAIRS.size) }
    val (c1, c2) = AVATAR_GRADIENT_PAIRS[gradIdx]
    Box(
        modifier = Modifier
            .size(sizeDp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(c1, c2))),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            initials,
            style = ppmText(if (sizeDp >= 24.dp) 10f else 8f, FontWeight.Bold).copy(color = Color.White),
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 8c.3 Inspector Pane (Commit, Stash, WIP Uncommitted Changes)
// ══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GitGraphInspectorPane(
    selectedHash: String?,
    displayCommits: List<GitGraphVertex>,
    fallbackCommit: GitGraphVertex?,
    commitDetails: GitGraphCommitDetail?,
    repoInfo: GitGraphRepoInfo,
    settings: GitGraphSettings,
    changes: GitChanges?,
    localDraftText: String,
    onDraftChange: (String) -> Unit,
    showCommitSplitMenu: Boolean,
    onToggleCommitSplitMenu: () -> Unit,
    onClose: () -> Unit,
    onSelectCommit: (String) -> Unit,
    onOpenCommitMenu: (GitGraphVertex) -> Unit,
    onOpenFileMenu: (filePath: String, hash: String, parentHash: String?) -> Unit,
    onSendMessage: (JsonObject) -> Unit,
    onSendTrackedAction: (
        action: String,
        buildMsg: (reqId: Int) -> JsonObject,
        onDone: (GitGraphActionResult) -> Unit,
    ) -> Unit,
    onRunGitAction: (String, JsonObject, String, String) -> Unit,
    onShowToast: (String, Pair<String, () -> Unit>?) -> Unit,
    onOpenDialog: (GitGraphDialogSpec) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current

    if (selectedHash == null) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            BasicText("Select a commit to see what it changed", style = ppmText(12f).copy(color = tokens.text3.color))
        }
        return
    }

    // 1. WIP Uncommitted Changes Inspector (`git-graph-inspector-wip`)
    if (selectedHash == "uncommitted") {
        val files = changes?.files.orEmpty()
        val totals = remember(files) { GitChangesView.changeTotals(files) }
        val allState = remember(files) { GitChangesView.allCheckState(files) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
                .testTag("git-graph-inspector-wip"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    "Uncommitted changes (${files.size} file(s))",
                    style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color),
                )
                SettingsButton(label = "×", onClick = onClose)
            }

            SettingsTextInput(
                value = localDraftText,
                onValueChange = onDraftChange,
                placeholder = "Commit message…",
                modifier = Modifier.fillMaxWidth().testTag("git-graph-wip-commit-input"),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val canCommit = totals.commitCount > 0 && localDraftText.isNotBlank() && totals.conflicts == 0
                SettingsButton(
                    label = if (totals.staged > 0) "Commit ${totals.staged} file(s)" else "Commit",
                    primary = canCommit,
                    modifier = Modifier.testTag("git-graph-wip-commit-btn"),
                    onClick = {
                        if (canCommit) {
                            onSendTrackedAction(
                                "commitStaged",
                                { reqId ->
                                    GitGraphWebviewMessages.commitStaged(
                                        message = localDraftText.trim(),
                                        reqId = reqId,
                                    )
                                },
                                { res ->
                                    if (res.ok) {
                                        val committedHash = res.data?.get("hash").string.orEmpty()
                                        onDraftChange("")
                                        onShowToast(
                                            "Committed ${committedHash.take(7)}",
                                            "Undo" to {
                                                onSendTrackedAction(
                                                    "undoCommit",
                                                    { rId -> GitGraphWebviewMessages.undoCommit(committedHash, rId) },
                                                    { _ -> onShowToast("Commit undone", null) },
                                                )
                                            },
                                        )
                                    } else {
                                        onShowToast(res.error ?: "Commit failed", null)
                                    }
                                },
                            )
                        }
                    },
                )
                SettingsButton(
                    label = "▾",
                    modifier = Modifier.testTag("git-graph-wip-commit-split-btn"),
                    onClick = onToggleCommitSplitMenu,
                )
                SettingsButton(
                    label = "${if (allState == CheckState.ALL) "☑ Unstage all" else "☐ Stage all"}",
                    modifier = Modifier.testTag("git-graph-wip-toggle-all-btn"),
                    onClick = {
                        val paths = files.filter { !it.conflict }.map { it.path }
                        if (paths.isNotEmpty()) {
                            val act = if (allState == CheckState.ALL) "unstageFiles" else "stageFiles"
                            onSendTrackedAction(
                                act,
                                { reqId ->
                                    if (allState == CheckState.ALL) {
                                        GitGraphWebviewMessages.unstageFiles(paths, reqId)
                                    } else {
                                        GitGraphWebviewMessages.stageFiles(paths, reqId)
                                    }
                                },
                                { _ -> },
                            )
                        }
                    },
                )
            }

            if (showCommitSplitMenu) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsButton(
                        label = "Commit & Push",
                        modifier = Modifier.testTag("git-graph-wip-commit-push-btn"),
                        onClick = {
                            onToggleCommitSplitMenu()
                            onSendTrackedAction(
                                "commitStaged",
                                { reqId ->
                                    GitGraphWebviewMessages.commitStaged(
                                        message = localDraftText.trim(),
                                        push = true,
                                        reqId = reqId,
                                    )
                                },
                                { res -> onShowToast(if (res.ok) "Committed & pushed" else (res.error ?: "Failed"), null) },
                            )
                        },
                    )
                    SettingsButton(
                        label = "Amend",
                        modifier = Modifier.testTag("git-graph-wip-commit-amend-btn"),
                        onClick = {
                            onToggleCommitSplitMenu()
                            onSendTrackedAction(
                                "commitStaged",
                                { reqId ->
                                    GitGraphWebviewMessages.commitStaged(
                                        message = localDraftText.trim(),
                                        amend = true,
                                        reqId = reqId,
                                    )
                                },
                                { res -> onShowToast(if (res.ok) "Amended commit" else (res.error ?: "Failed"), null) },
                            )
                        },
                    )
                }
            }

            for (file in files) {
                val fState = GitChangesView.fileCheckState(file)
                val letter = GitChangesView.changeLetter(file)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.bgSolid.color)
                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("git-graph-wip-file-${file.path}"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsButton(
                        label = if (fState == CheckState.ALL) "☑" else if (fState == CheckState.SOME) "▣" else "☐",
                        primary = fState != CheckState.NONE,
                        modifier = Modifier.testTag("git-graph-wip-stage-${file.path}"),
                        onClick = {
                            val isAll = fState == CheckState.ALL
                            onSendTrackedAction(
                                if (isAll) "unstageFiles" else "stageFiles",
                                { reqId ->
                                    if (isAll) {
                                        GitGraphWebviewMessages.unstageFiles(listOf(file.path), reqId)
                                    } else {
                                        GitGraphWebviewMessages.stageFiles(listOf(file.path), reqId)
                                    }
                                },
                                { _ -> },
                            )
                        },
                    )
                    BasicText(
                        letter,
                        style = ppmText(10f, FontWeight.Bold, FontKind.MONO).copy(color = tokens.accent.color),
                    )
                    BasicText(
                        file.path,
                        style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text.color),
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                if (file.conflict) {
                                    onSendMessage(GitGraphWebviewMessages.openConflictFile(file.path))
                                } else {
                                    onSendMessage(GitGraphWebviewMessages.openReview(file.path))
                                }
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (file.unstaged != null && !file.conflict) {
                        SettingsButton(
                            label = "Discard",
                            modifier = Modifier.testTag("git-graph-wip-discard-${file.path}"),
                            onClick = {
                                onSendTrackedAction(
                                    "discardFiles",
                                    { reqId -> GitGraphWebviewMessages.discardFiles(listOf(file.path), reqId) },
                                    { res ->
                                        val undoId = res.data?.get("undo")?.obj?.get("id").string
                                        onShowToast(
                                            if (res.ok) "Discarded ${file.path}" else (res.error ?: "Discard failed"),
                                            if (res.ok && !undoId.isNullOrEmpty()) {
                                                "Undo" to {
                                                    onSendTrackedAction(
                                                        "undoDiscard",
                                                        { rId -> GitGraphWebviewMessages.undoDiscard(undoId, rId) },
                                                        { _ -> onShowToast("Restored ${file.path}", null) },
                                                    )
                                                }
                                            } else null,
                                        )
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
        return
    }

    // 2. Stash or Commit Inspector (`git-graph-inspector-commit`)
    val rowCommit = displayCommits.firstOrNull { it.hash == selectedHash } ?: fallbackCommit
    val detail = if (commitDetails?.hash == selectedHash) commitDetails else rowCommit?.let {
        GitGraphCommitDetail(
            hash = it.hash,
            author = it.author,
            authorEmail = it.authorEmail,
            authorDate = it.authorDate,
            committer = it.committer,
            committerEmail = it.committerEmail,
            commitDate = it.commitDate,
            message = it.message,
            parents = it.parents,
            fileChanges = null,
            filesOmitted = 0,
            isPartial = true,
        )
    }

    if (detail == null) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            BasicText("Loading commit $selectedHash…", style = ppmText(12f).copy(color = tokens.text3.color))
        }
        return
    }

    val stash = repoInfo.stashes.firstOrNull { it.hash == detail.hash }
    val isHead = detail.hash == repoInfo.head
    val subject = detail.message.lineSequence().firstOrNull().orEmpty()
    val bodyText = detail.message.substringAfter('\n', "").trim()
    val bodyBlocks = remember(bodyText) { splitGitGraphCommitBody(bodyText) }
    val subjectTokens = remember(subject, settings.issueLinkingRules) {
        tokenizeGitGraphCommitMessage(subject, settings.issueLinkingRules)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
            .testTag("git-graph-inspector-commit"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header: Avatar + Author + Close
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                GitGraphAuthorAvatar(detail.author, detail.authorEmail, sizeDp = 24.dp)
                Column {
                    BasicText(
                        if (stash != null) "stash@{${stash.index}}" else detail.author,
                        style = ppmText(12f, FontWeight.SemiBold).copy(color = tokens.text.color),
                    )
                    BasicText(
                        formatGitGraphLongAgo(detail.authorDate),
                        style = ppmText(10f).copy(color = tokens.text3.color),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (rowCommit != null) {
                    SettingsButton(
                        label = "More ⋯",
                        modifier = Modifier.testTag("git-graph-insp-more-btn"),
                        onClick = { onOpenCommitMenu(rowCommit) },
                    )
                }
                SettingsButton(
                    label = "×",
                    modifier = Modifier.testTag("git-graph-insp-close-btn"),
                    onClick = onClose,
                )
            }
        }

        // Subject + Clickable Issue / Hash links
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (tok in subjectTokens) {
                when (tok) {
                    is GitGraphMessageToken.Plain -> {
                        BasicText(
                            tok.text,
                            style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color),
                        )
                    }
                    is GitGraphMessageToken.ExternalLink -> {
                        BasicText(
                            tok.text,
                            style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.accent.color),
                            modifier = Modifier
                                .clickable { openGraphExternalUrl(context, tok.url) }
                                .testTag("git-graph-insp-issue-link-${tok.text}"),
                        )
                    }
                    is GitGraphMessageToken.IssueHighlight -> {
                        BasicText(
                            tok.text,
                            style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.accent.color),
                        )
                    }
                    is GitGraphMessageToken.CommitHashLink -> {
                        BasicText(
                            tok.hash,
                            style = ppmText(13f, FontWeight.SemiBold, FontKind.MONO).copy(color = tokens.accent.color),
                            modifier = Modifier.clickable { onSelectCommit(tok.hash) },
                        )
                    }
                }
            }
        }

        // Hash + Parents Row
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsButton(
                label = "📋 ${detail.hash.take(8)}",
                modifier = Modifier.testTag("git-graph-insp-copy-hash"),
                onClick = {
                    copyGraphText(context, "Commit hash", detail.hash)
                    onShowToast("Copied ${detail.hash.take(7)}", null)
                },
            )
            if (detail.parents.isNotEmpty()) {
                BasicText("parent(s):", style = ppmText(11f).copy(color = tokens.text3.color))
                for (p in detail.parents) {
                    SettingsButton(
                        label = p.take(7),
                        modifier = Modifier.testTag("git-graph-insp-parent-$p"),
                        onClick = { onSelectCommit(p) },
                    )
                }
            }
        }

        // Quick Action Buttons
        if (stash != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsButton(
                    label = "Apply",
                    modifier = Modifier.testTag("git-graph-insp-stash-apply"),
                    onClick = {
                        onSendTrackedAction(
                            "stashAction",
                            { reqId -> GitGraphWebviewMessages.stashAction("apply", stash.index, stash.hash, reqId) },
                            { res -> onShowToast(if (res.ok) "Stash applied" else (res.error ?: "Failed"), null) },
                        )
                    },
                )
                SettingsButton(
                    label = "Pop",
                    modifier = Modifier.testTag("git-graph-insp-stash-pop"),
                    onClick = {
                        onSendTrackedAction(
                            "stashAction",
                            { reqId -> GitGraphWebviewMessages.stashAction("pop", stash.index, stash.hash, reqId) },
                            { res -> onShowToast(if (res.ok) "Stash popped" else (res.error ?: "Failed"), null) },
                        )
                    },
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsButton(
                    label = "Checkout",
                    primary = !isHead,
                    modifier = Modifier.testTag("git-graph-insp-checkout-btn"),
                    onClick = {
                        if (!isHead) {
                            onRunGitAction(
                                "checkout",
                                buildJsonObject { put("target", detail.hash) },
                                "Checked out ${detail.hash.take(7)}",
                                "Checkout failed",
                            )
                        }
                    },
                )
                SettingsButton(
                    label = "Branch…",
                    modifier = Modifier.testTag("git-graph-insp-branch-btn"),
                    onClick = {
                        onOpenDialog(
                            GitGraphDialogSpec(
                                title = "Create a branch at ${detail.hash.take(7)}",
                                inputPlaceholder = "Branch name",
                                checkboxLabel = "Check it out",
                                checkboxDefault = true,
                                confirmLabel = "Create branch",
                                onConfirm = { name, _ ->
                                    if (name.isNotBlank()) {
                                        onRunGitAction(
                                            "createBranch",
                                            buildJsonObject {
                                                put("name", name.trim())
                                                put("startPoint", detail.hash)
                                                put("force", false)
                                            },
                                            "Created ${name.trim()}",
                                            "Could not create branch",
                                        )
                                    }
                                },
                            ),
                        )
                    },
                )
                SettingsButton(
                    label = "Cherry-pick",
                    modifier = Modifier.testTag("git-graph-insp-cherry-pick-btn"),
                    onClick = {
                        if (!isHead) {
                            onRunGitAction(
                                "cherryPick",
                                buildJsonObject { put("hash", detail.hash) },
                                "Cherry-picked ${detail.hash.take(7)}",
                                "Cherry-pick failed",
                            )
                        }
                    },
                )
            }
        }

        // Body Blocks (`prose` vs `pre`)
        if (bodyBlocks.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (block in bodyBlocks) {
                    BasicText(
                        block.text,
                        style = ppmText(
                            11f,
                            kind = if (block.kind == "pre") FontKind.MONO else FontKind.SANS,
                            lineHeight = 16f,
                        ).copy(color = tokens.text2.color),
                    )
                }
            }
        }

        // Changed Files Section (`git-graph-insp-files`)
        val files = detail.fileChanges
        if (files == null) {
            BasicText("Loading files…", style = ppmText(11f).copy(color = tokens.text3.color))
        } else {
            val totalFiles = files.size + detail.filesOmitted
            BasicText(
                "FILES ($totalFiles)${if (detail.filesOmitted > 0) " [… ${detail.filesOmitted} more omitted]" else ""}",
                style = ppmText(11f, FontWeight.SemiBold).copy(color = tokens.text2.color),
                modifier = Modifier.testTag("git-graph-insp-files-header"),
            )
            for (f in files) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.bgSolid.color)
                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                        .combinedClickable(
                            onClick = {
                                onSendMessage(
                                    GitGraphWebviewMessages.openDiff(
                                        filePath = f.path,
                                        hash = detail.hash,
                                        parentHash = detail.parents.firstOrNull(),
                                    ),
                                )
                            },
                            onLongClick = {
                                onOpenFileMenu(f.path, detail.hash, detail.parents.firstOrNull())
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("git-graph-insp-file-${f.path}"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        f.status,
                        style = ppmText(10f, FontWeight.Bold, FontKind.MONO).copy(color = tokens.accent.color),
                    )
                    BasicText(
                        f.path,
                        style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text.color),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    BasicText(
                        "+${f.additions} −${f.deletions}",
                        style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.success.color),
                    )
                    SettingsButton(
                        label = "↗",
                        modifier = Modifier.testTag("git-graph-insp-open-file-${f.path}"),
                        onClick = { onSendMessage(GitGraphWebviewMessages.openFile(f.path)) },
                    )
                    SettingsButton(
                        label = "⋮",
                        modifier = Modifier.testTag("git-graph-insp-file-menu-btn-${f.path}"),
                        onClick = { onOpenFileMenu(f.path, detail.hash, detail.parents.firstOrNull()) },
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 8c.2 Git Graph Settings Dialog (`git-graph-settings-sheet`)
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun GitGraphSettingsDialog(
    settings: GitGraphSettings,
    userDetails: GitGraphUserDetails,
    ownerRepo: GitGraphOwnerRepo,
    remotes: List<GitGraphRemote>,
    onSendMessage: (JsonObject) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var userName by remember(userDetails.name) { mutableStateOf(userDetails.name) }
    var userEmail by remember(userDetails.email) { mutableStateOf(userDetails.email) }
    var newRemoteName by remember { mutableStateOf("") }
    var newRemoteUrl by remember { mutableStateOf("") }
    var prProvider by remember(settings.prCreation) { mutableStateOf(settings.prCreation?.provider.orEmpty()) }
    var prOwner by remember(settings.prCreation, ownerRepo.owner) {
        mutableStateOf(settings.prCreation?.owner?.ifEmpty { ownerRepo.owner } ?: ownerRepo.owner)
    }
    var prRepo by remember(settings.prCreation, ownerRepo.repo) {
        mutableStateOf(settings.prCreation?.repo?.ifEmpty { ownerRepo.repo } ?: ownerRepo.repo)
    }
    var prTarget by remember(settings.prCreation) {
        mutableStateOf(settings.prCreation?.defaultTargetBranch ?: "main")
    }
    var prTemplate by remember(settings.prCreation) {
        mutableStateOf(settings.prCreation?.urlTemplate ?: gitGraphPrTemplate("github"))
    }

    GitGraphModalOverlay(testTag = "git-graph-settings-sheet", onDismiss = onDismiss) {
        BasicText("Git Graph Settings", style = ppmText(14f, FontWeight.SemiBold).copy(color = tokens.text.color))

        // Graph Style & Date Format & Ordering
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Style:", style = ppmText(11f).copy(color = tokens.text2.color))
            for (st in listOf("rounded", "angular")) {
                SettingsButton(
                    label = st,
                    primary = settings.graphStyle == st,
                    modifier = Modifier.testTag("git-graph-setting-style-$st"),
                    onClick = { onSendMessage(GitGraphWebviewMessages.updateSetting("graphStyle", JsonPrimitive(st))) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Date:", style = ppmText(11f).copy(color = tokens.text2.color))
            for (df in listOf("relative", "absolute", "iso")) {
                SettingsButton(
                    label = df,
                    primary = settings.dateFormat == df,
                    modifier = Modifier.testTag("git-graph-setting-date-$df"),
                    onClick = { onSendMessage(GitGraphWebviewMessages.updateSetting("dateFormat", JsonPrimitive(df))) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Order:", style = ppmText(11f).copy(color = tokens.text2.color))
            for (ord in listOf("topo", "date", "author-date")) {
                SettingsButton(
                    label = ord,
                    primary = settings.commitOrdering == ord,
                    modifier = Modifier.testTag("git-graph-setting-order-$ord"),
                    onClick = { onSendMessage(GitGraphWebviewMessages.updateSetting("commitOrdering", JsonPrimitive(ord))) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingsButton(
                label = "${if (settings.firstParentOnly) "☑" else "☐"} First-parent only",
                primary = settings.firstParentOnly,
                modifier = Modifier.testTag("git-graph-setting-first-parent"),
                onClick = {
                    onSendMessage(GitGraphWebviewMessages.updateSetting("firstParentOnly", JsonPrimitive(!settings.firstParentOnly)))
                },
            )
            for ((sec, lbl) in listOf(0 to "Off", 30 to "30s", 60 to "1m", 300 to "5m")) {
                SettingsButton(
                    label = "Auto-fetch: $lbl",
                    primary = settings.autoFetchInterval == sec,
                    modifier = Modifier.testTag("git-graph-setting-autofetch-$sec"),
                    onClick = {
                        onSendMessage(GitGraphWebviewMessages.updateSetting("autoFetchInterval", JsonPrimitive(sec)))
                    },
                )
            }
        }

        // User Details (`user.name` & `user.email`)
        BasicText("GIT USER DETAILS", style = ppmText(11f, FontWeight.SemiBold).copy(color = tokens.text2.color))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingsTextInput(
                value = userName,
                onValueChange = { userName = it },
                placeholder = "user.name",
                modifier = Modifier.weight(1f).testTag("git-graph-setting-user-name"),
            )
            SettingsTextInput(
                value = userEmail,
                onValueChange = { userEmail = it },
                placeholder = "user.email",
                modifier = Modifier.weight(1f).testTag("git-graph-setting-user-email"),
            )
            SettingsButton(
                label = "Save User",
                primary = true,
                modifier = Modifier.testTag("git-graph-setting-save-user-btn"),
                onClick = {
                    onSendMessage(GitGraphWebviewMessages.updateUserDetails(userName.trim(), userEmail.trim()))
                },
            )
        }

        // Remotes Management
        BasicText("REMOTES (${remotes.size})", style = ppmText(11f, FontWeight.SemiBold).copy(color = tokens.text2.color))
        for (r in remotes) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText("${r.name}: ${r.fetchUrl}", style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text.color))
                SettingsButton(
                    label = "Remove",
                    modifier = Modifier.testTag("git-graph-setting-rm-remote-${r.name}"),
                    onClick = { onSendMessage(GitGraphWebviewMessages.removeRemote(r.name)) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingsTextInput(
                value = newRemoteName,
                onValueChange = { newRemoteName = it },
                placeholder = "Remote name (origin)",
                modifier = Modifier.width(120.dp).testTag("git-graph-setting-new-remote-name"),
            )
            SettingsTextInput(
                value = newRemoteUrl,
                onValueChange = { newRemoteUrl = it },
                placeholder = "Remote URL",
                modifier = Modifier.weight(1f).testTag("git-graph-setting-new-remote-url"),
            )
            SettingsButton(
                label = "+ Add",
                modifier = Modifier.testTag("git-graph-setting-add-remote-btn"),
                onClick = {
                    if (newRemoteName.isNotBlank() && newRemoteUrl.isNotBlank()) {
                        onSendMessage(GitGraphWebviewMessages.addRemote(newRemoteName.trim(), newRemoteUrl.trim()))
                        newRemoteName = ""
                        newRemoteUrl = ""
                    }
                },
            )
        }

        // PR Creation Config
        BasicText("PULL REQUEST CREATION", style = ppmText(11f, FontWeight.SemiBold).copy(color = tokens.text2.color))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (prov in listOf("github", "gitlab", "bitbucket")) {
                SettingsButton(
                    label = prov,
                    primary = prProvider == prov,
                    modifier = Modifier.testTag("git-graph-setting-pr-provider-$prov"),
                    onClick = {
                        prProvider = prov
                        prTemplate = gitGraphPrTemplate(prov)
                        onSendMessage(GitGraphWebviewMessages.requestOwnerRepo())
                    },
                )
            }
            SettingsButton(
                label = "Save PR",
                primary = prProvider.isNotEmpty(),
                modifier = Modifier.testTag("git-graph-setting-save-pr-btn"),
                onClick = {
                    if (prProvider.isNotEmpty()) {
                        val prJson = buildJsonObject {
                            put("provider", prProvider)
                            put("urlTemplate", prTemplate)
                            put("owner", prOwner)
                            put("repo", prRepo)
                            put("defaultTargetBranch", prTarget)
                        }
                        onSendMessage(GitGraphWebviewMessages.updateSetting("prCreation", prJson))
                    }
                },
            )
        }
    }
}
