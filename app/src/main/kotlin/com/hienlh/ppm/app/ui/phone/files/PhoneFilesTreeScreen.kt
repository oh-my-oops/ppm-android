package com.hienlh.ppm.app.ui.phone.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.files.ExplorerDeleteDialog
import com.hienlh.ppm.app.ui.files.ExplorerMutationSession
import com.hienlh.ppm.app.ui.files.rememberExplorerMutationCallbacks
import com.hienlh.ppm.app.ui.git.gitStatusRgba
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.BottomActionCard
import com.hienlh.ppm.design.kit.Chip
import com.hienlh.ppm.design.kit.KitSpec
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.files.FileTreeNode
import com.hienlh.ppm.state.files.FileTreeRow
import com.hienlh.ppm.state.workspace.absoluteProjectPath
import kotlinx.coroutines.launch

/**
 * Stateful phone Files screen (`Phone 3 · Section 1: F1, F6 & states`, Requirement 8.1).
 *
 * Connects [AppModel.fileTree] to [PhoneFilesTreeContent], wiring file opening, lazy folder
 * expansion, long-press action sheet, and the bottom card (`Go to file…` and `Recent`).
 */
@Composable
fun PhoneFilesTreeScreen(
    model: AppModel,
    onNavigate: () -> Unit = {},
    recentTabIds: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
) {
    val tree = model.fileTree
    val project = model.selectedProject
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (tree == null || project == null) {
        PhoneFilesTreeContent(
            projectName = project.orEmpty(),
            screenState = PhoneFilesScreenState.NoProject,
            rows = emptyList(),
            expandedPaths = emptySet(),
            loadingPaths = emptySet(),
            folderEntryCounts = emptyMap(),
            gitFileStatuses = emptyMap(),
            gitFolderStatuses = emptyMap(),
            activeFilePath = null,
            onRowClick = {},
            onRowLongPress = {},
            onRetry = {},
            onGoToFile = null,
            onRecentFiles = null,
            modifier = modifier,
        )
        return
    }

    DisposableEffect(tree, project) {
        tree.open(project)
        onDispose {
            if (tree.state.value.project == project) {
                tree.reset()
            }
        }
    }

    val treeState by tree.state.collectAsState()
    val activeTab = model.activeTab?.takeIf {
        (it.projectId ?: it.metaString("projectName")) == project
    }
    val activeFilePath = activeTab?.metaString("filePath")
    val gitFileStatuses = model.gitFileStatuses[project].orEmpty()
    val gitFolderStatuses = model.gitFolderStatuses[project].orEmpty()
    val projectRoot = model.currentProject?.path.orEmpty()
    val download = rememberPhoneFileDownload(model)
    val offlineSeconds = rememberOfflineSeconds(model.connectionLost)

    // The old explorer's file operations (ExplorerBody + ExplorerTree): one prompt session over the shared FileMutations.
    val mutations = model.fileMutations
    val info = model.currentProject
    val callbacks = if (mutations != null && info != null) {
        rememberExplorerMutationCallbacks(mutations, info) { report ->
            if (model.fileMutations === mutations) model.filesMutated(report)
        }
    } else {
        null
    }
    val session = remember(tree, project) { ExplorerMutationSession(project, scope) }
    DisposableEffect(session) { onDispose { session.close() } }
    val mutationState by session.state.collectAsState()
    val mutationBusy = mutationState.busy || callbacks?.busy == true
    LaunchedEffect(mutationState.error) {
        val error = mutationState.error ?: return@LaunchedEffect
        model.toast(ErrorToastMessage(error))
        session.clearError()
    }

    var longPressedRow by remember { mutableStateOf<FileTreeRow?>(null) }
    var showOverflowSheet by remember { mutableStateOf(false) }
    var showGoToFile by remember { mutableStateOf(false) }
    var showRecent by remember { mutableStateOf(false) }

    val openTabs = model.openTabs.map { it.first }
    val recentFiles = remember(openTabs, project, recentTabIds) { phoneRecentFileTabs(openTabs, project, recentTabIds) }

    val visibleRows = treeState.visibleRows
    val screenState = when {
        treeState.loading && visibleRows.isEmpty() -> PhoneFilesScreenState.Loading
        treeState.error != null && visibleRows.isEmpty() ->
            PhoneFilesScreenState.Error(detail = treeState.error!!)
        !treeState.loading && visibleRows.isEmpty() -> PhoneFilesScreenState.Empty
        else -> PhoneFilesScreenState.Ready(offlineSeconds = offlineSeconds)
    }

    val folderCounts = remember(treeState.directories) {
        treeState.directories.mapValues { (_, list) -> list.size }
    }

    fun openFile(path: String) {
        model.openFileTab(project, path)
        onNavigate()
    }

    PhoneFilesTreeContent(
        projectName = project,
        screenState = screenState,
        rows = visibleRows,
        expandedPaths = treeState.expandedPaths,
        loadingPaths = treeState.loadingPaths,
        folderEntryCounts = folderCounts,
        gitFileStatuses = gitFileStatuses,
        gitFolderStatuses = gitFolderStatuses,
        activeFilePath = activeFilePath,
        onRowClick = { row ->
            if (row.node.isDirectory) {
                tree.toggleExpand(row.node.path)
            } else {
                openFile(row.node.path)
            }
        },
        onRowLongPress = { row ->
            longPressedRow = row
        },
        onRetry = {
            tree.refresh()
        },
        onGoToFile = { showGoToFile = true },
        onRecentFiles = if (recentFiles.isNotEmpty()) ({ showRecent = true }) else null,
        onMore = { showOverflowSheet = true },
        onSearch = onSearch,
        modifier = modifier,
    )

    val activeLongPress = longPressedRow
    if (activeLongPress != null) {
        val node = activeLongPress.node
        val gitLetter = normalizePhoneGitBadgeLetter(gitFileStatuses[node.path])
        PhoneTreeLongPressSheet(
            node = node,
            gitBadge = gitLetter,
            mutationActions = if (callbacks != null && !mutationBusy) {
                PhoneTreeLongPressAction.mutationActionsFor(node.isDirectory, canPaste = callbacks.canPaste)
            } else {
                emptyList()
            },
            onDismiss = { longPressedRow = null },
            onAction = { action ->
                longPressedRow = null
                when (action) {
                    PhoneTreeLongPressAction.OPEN -> {
                        if (!node.isDirectory) openFile(node.path)
                    }
                    PhoneTreeLongPressAction.OPEN_IN_TERMINAL -> {
                        if (projectRoot.isNotBlank()) {
                            model.openTerminalAt(absoluteProjectPath(projectRoot, node.path), project)
                            onNavigate()
                        }
                    }
                    PhoneTreeLongPressAction.COPY_PATH -> {
                        copyTextToClipboard(context, "Path", node.path)
                        model.toast(ErrorToastMessage("Copied path", kind = ErrorToastMessage.Kind.INFO))
                    }
                    PhoneTreeLongPressAction.COPY_FULL_PATH -> {
                        val full = if (projectRoot.isNotBlank()) {
                            absoluteProjectPath(projectRoot, node.path)
                        } else {
                            node.path
                        }
                        copyTextToClipboard(context, "Full path", full)
                        model.toast(ErrorToastMessage("Copied full path", kind = ErrorToastMessage.Kind.INFO))
                    }
                    PhoneTreeLongPressAction.DOWNLOAD -> download(project, node.path, node.isDirectory)
                    PhoneTreeLongPressAction.NEW_FILE, PhoneTreeLongPressAction.NEW_FOLDER -> {
                        scope.launch { tree.setExpanded(node.path, true) }
                        session.create(node.path, directory = action == PhoneTreeLongPressAction.NEW_FOLDER)
                    }
                    PhoneTreeLongPressAction.RENAME -> session.rename(node)
                    PhoneTreeLongPressAction.DELETE -> session.askDelete(node)
                    PhoneTreeLongPressAction.CUT -> {
                        if (callbacks != null) {
                            session.clipboard(listOf(node.path), cut = true, callbacks = callbacks)
                            model.toast(ErrorToastMessage("Cut ${node.name}", "Long-press a folder and choose Paste to move it there.", kind = ErrorToastMessage.Kind.INFO))
                        }
                    }
                    PhoneTreeLongPressAction.PASTE -> if (callbacks != null) session.paste(node.path, callbacks)
                }
            },
        )
    }

    if (showOverflowSheet) {
        PhoneFilesOverflowSheet(
            extraActions = if (callbacks != null && !mutationBusy) {
                buildList {
                    add(PhoneTreeLongPressAction.NEW_FILE)
                    add(PhoneTreeLongPressAction.NEW_FOLDER)
                    if (callbacks.canPaste) add(PhoneTreeLongPressAction.PASTE)
                }
            } else {
                emptyList()
            },
            onExtraAction = { action ->
                showOverflowSheet = false
                when (action) {
                    PhoneTreeLongPressAction.NEW_FILE -> session.create("", directory = false)
                    PhoneTreeLongPressAction.NEW_FOLDER -> session.create("", directory = true)
                    PhoneTreeLongPressAction.PASTE -> if (callbacks != null) session.paste("", callbacks)
                    else -> Unit
                }
            },
            onDismiss = { showOverflowSheet = false },
            onAction = { action ->
                showOverflowSheet = false
                when (action) {
                    PhoneFilesOverflowAction.REVEAL_ACTIVE_FILE -> {
                        if (!activeFilePath.isNullOrBlank()) {
                            scope.launch {
                                val segments = activeFilePath.split('/').dropLast(1)
                                val accum = StringBuilder()
                                for (seg in segments) {
                                    if (accum.isNotEmpty()) accum.append('/')
                                    accum.append(seg)
                                    tree.setExpanded(accum.toString(), true)
                                }
                                tree.setFocusedPath(activeFilePath)
                            }
                        }
                    }
                    PhoneFilesOverflowAction.COLLAPSE_ALL -> tree.collapseAll()
                    PhoneFilesOverflowAction.REFRESH -> tree.refresh()
                }
            },
        )
    }

    if (showGoToFile) {
        PhoneGoToFileSheet(
            model = model,
            project = project,
            onOpen = { path ->
                showGoToFile = false
                openFile(path)
            },
            onDismiss = { showGoToFile = false },
        )
    }

    if (showRecent) {
        PhoneRecentFilesSheet(
            tabs = recentFiles,
            onOpen = { tab ->
                showRecent = false
                model.activate(tab.id)
                onNavigate()
            },
            onDismiss = { showRecent = false },
        )
    }

    val edit = mutationState.edit
    if (edit != null && callbacks != null) {
        val where = edit.parent.ifEmpty { project }
        PhoneFileNameSheet(
            title = when {
                edit.node != null -> "Rename ${edit.node.name}"
                edit.directory -> "New folder in $where"
                else -> "New file in $where"
            },
            initialName = edit.node?.name.orEmpty(),
            confirmLabel = if (edit.node != null) "Rename" else "Create",
            busy = mutationState.busy,
            error = mutationState.editError,
            onSubmit = { value -> session.submit(edit, value, callbacks) },
            onEdited = session::edited,
            onDismiss = session::cancelEdit,
        )
    }

    val deleting = mutationState.deleting
    if (deleting != null && callbacks != null) {
        ExplorerDeleteDialog(
            node = deleting,
            busy = mutationState.busy,
            error = mutationState.deleteError,
            onDelete = { session.confirmDelete(callbacks) },
            onDismiss = session::cancelDelete,
        )
    }
}

/**
 * Stateless phone Files screen content (`Phone 3 · Section 1`, F1, F6, and all states).
 */
@Composable
fun PhoneFilesTreeContent(
    projectName: String,
    screenState: PhoneFilesScreenState,
    rows: List<FileTreeRow>,
    expandedPaths: Set<String>,
    loadingPaths: Set<String>,
    folderEntryCounts: Map<String, Int>,
    gitFileStatuses: Map<String, String>,
    gitFolderStatuses: Map<String, String>,
    activeFilePath: String?,
    onRowClick: (FileTreeRow) -> Unit,
    onRowLongPress: (FileTreeRow) -> Unit,
    onRetry: () -> Unit,
    onGoToFile: (() -> Unit)?,
    onRecentFiles: (() -> Unit)?,
    modifier: Modifier = Modifier,
    folderChangedCounts: Map<String, Int> = emptyMap(),
    listState: LazyListState = rememberLazyListState(),
    onMore: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current

    // Determine the top visible folder for the sticky breadcrumb bar (Note 1).
    val topFolderPath by remember(rows, listState) {
        derivedStateOf {
            val firstIdx = listState.firstVisibleItemIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
            val row = rows.getOrNull(firstIdx)
            if (row == null) {
                ""
            } else if (row.node.isDirectory) {
                row.node.path
            } else {
                row.node.path.substringBeforeLast('/', "")
            }
        }
    }

    val breadcrumbs = remember(projectName, topFolderPath) {
        buildPhoneTreeBreadcrumbs(projectName = projectName, folderPath = topFolderPath)
    }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("phone-files-screen"),
    ) {
        // Breadcrumb row at the top of the tree (Note 1) whenever a project is selected.
        if (screenState !is PhoneFilesScreenState.NoProject) {
            PhoneTreeBreadcrumbBar(
                crumbs = breadcrumbs,
                onCrumbClick = { crumb ->
                    val targetIdx = if (crumb.relativePath.isEmpty()) {
                        0
                    } else {
                        rows.indexOfFirst { it.node.path == crumb.relativePath }.coerceAtLeast(0)
                    }
                    scope.launch { listState.scrollToItem(targetIdx) }
                },
                onMore = onMore,
                moreLabel = "More: reveal active file, collapse all, refresh",
            )
        }

        // Body content per state (Note 10, 65).
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (screenState) {
                PhoneFilesScreenState.NoProject -> {
                    PhoneFilesEmptyBlock(
                        icon = ProductIcon.FolderTree,
                        title = PhoneFilesScreenState.NoProject.TITLE,
                        subtitle = PhoneFilesScreenState.NoProject.SUBTITLE,
                        testTag = "phone-files-no-project",
                    )
                }
                PhoneFilesScreenState.Loading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("phone-files-loading"),
                    ) {
                        PhoneTreePlaceholderRow(depth = 0, widthDp = 60)
                        PhoneTreePlaceholderRow(depth = 0, widthDp = 88)
                        PhoneTreePlaceholderRow(depth = 0, widthDp = 70)
                        PhoneTreePlaceholderRow(depth = 0, widthDp = 120)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PpmIcon(ProductIcon.Loader2, 14.dp, tokens.text2.color, spinning = true)
                            BasicText(
                                text = PhoneFilesScreenState.Loading.MESSAGE,
                                style = ppmText(13.5f, lineHeight = 18f).copy(color = tokens.text2.color),
                            )
                        }
                    }
                }
                PhoneFilesScreenState.Empty -> {
                    PhoneFilesEmptyBlock(
                        icon = ProductIcon.FolderOpen,
                        title = PhoneFilesScreenState.Empty.TITLE,
                        subtitle = PhoneFilesScreenState.Empty.SUBTITLE,
                        testTag = "phone-files-empty",
                    )
                }
                is PhoneFilesScreenState.Error -> {
                    PhoneFilesEmptyBlock(
                        icon = ProductIcon.AlertCircle,
                        isError = true,
                        title = screenState.title,
                        monoDetail = screenState.detail,
                        retryLabel = "Retry",
                        onRetry = onRetry,
                        testTag = "phone-files-error",
                    )
                }
                is PhoneFilesScreenState.Ready -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (screenState.isOffline) {
                            PhoneFilesBanner(
                                icon = ProductIcon.WifiOff,
                                title = screenState.offlineTitle,
                                subtitle = PhoneFilesScreenState.Ready.OFFLINE_SUBTITLE,
                                isError = true,
                                actionLabel = "Retry",
                                onAction = onRetry,
                                modifier = Modifier.testTag("phone-files-offline-banner"),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .alpha(if (screenState.isOffline) 0.55f else 1f),
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("phone-files-tree-list"),
                                contentPadding = PaddingValues(vertical = 4.dp),
                            ) {
                                items(rows, key = { it.node.path }) { row ->
                                    val path = row.node.path
                                    val isExpanded = path in expandedPaths
                                    val isLoading = path in loadingPaths
                                    val isSelected = path == activeFilePath
                                    val gitStatus = if (row.node.isDirectory) {
                                        gitFolderStatuses[path]
                                    } else {
                                        gitFileStatuses[path]
                                    }
                                    val entryCount = folderEntryCounts[path]
                                    val changedCount = folderChangedCounts[path]

                                    PhoneTreeRowItem(
                                        row = row,
                                        expanded = isExpanded,
                                        loading = isLoading,
                                        selected = isSelected,
                                        gitStatus = gitStatus,
                                        folderChangedCount = changedCount,
                                        folderEntryCount = entryCount,
                                        enabled = !screenState.isOffline,
                                        onClick = { onRowClick(row) },
                                        onLongPress = { onRowLongPress(row) },
                                    )

                                    // F6 · Lazy folder loading placeholder rows (Note 9)
                                    if (row.node.isDirectory && isLoading) {
                                        PhoneTreePlaceholderRow(depth = row.depth + 1, widthDp = 96)
                                        PhoneTreePlaceholderRow(depth = row.depth + 1, widthDp = 132)
                                        PhoneTreePlaceholderRow(depth = row.depth + 1, widthDp = 80)
                                    }
                                }
                            }

                            // Fast-scroll thumb on the right edge for long lists (F6, Note 9)
                            if (rows.size > 20) {
                                val fraction = if (rows.isEmpty()) {
                                    0f
                                } else {
                                    (listState.firstVisibleItemIndex.toFloat() / rows.size.coerceAtLeast(1))
                                        .coerceIn(0f, 0.85f)
                                }
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .fillMaxHeight()
                                        .padding(end = 4.dp, top = 24.dp, bottom = 24.dp)
                                        .width(6.dp)
                                        .testTag("phone-files-fast-scroll"),
                                ) {
                                    Spacer(Modifier.fillMaxHeight(fraction))
                                    Box(
                                        modifier = Modifier
                                            .width(6.dp)
                                            .height(56.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(tokens.text.opacity(0.30).color),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom card: Go to file… and Recent only (Note 65, t-files-card-p)
        if (screenState !is PhoneFilesScreenState.NoProject && onGoToFile != null) {
            BottomActionCard(
                modifier = Modifier.testTag("phone-files-bottom-card"),
            ) {
                val fieldShape = RoundedCornerShape(14.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(KitSpec.MinTouchTarget)
                        .clip(fieldShape)
                        .background(tokens.panel.color, fieldShape)
                        .cssBorder(tokens.borderSoft.color, fieldShape, width = 1.dp)
                        .ppmClickable(
                            shape = fieldShape,
                            onClickLabel = "Go to file…",
                            onClick = onGoToFile,
                        )
                        .padding(horizontal = 14.dp)
                        .testTag("phone-files-go-to-file"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PpmIcon(ProductIcon.Search, 18.dp, tokens.text3.color)
                    BasicText(
                        text = "Go to file…",
                        style = ppmText(14.5f, lineHeight = 20f).copy(color = tokens.text3.color),
                    )
                }
                if (onRecentFiles != null || onSearch != null) Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (onRecentFiles != null) Chip(
                        label = "Recent",
                        icon = ProductIcon.History,
                        onClick = onRecentFiles,
                        modifier = Modifier.testTag("phone-files-recent-chip"),
                    )
                    // Content search, the web sidebar's Search panel (`search-panel.tsx`).
                    if (onSearch != null) Chip(
                        label = "Search in files",
                        icon = ProductIcon.Search,
                        onClick = onSearch,
                        modifier = Modifier.testTag("phone-files-search-chip"),
                    )
                }
            }
        }
    }
}

/**
 * Sticky breadcrumb row at the top of the phone project tree (`crumb2`, Note 1).
 */
@Composable
fun PhoneTreeBreadcrumbBar(
    crumbs: List<PhonePathCrumb>,
    onCrumbClick: (PhonePathCrumb) -> Unit,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
    moreLabel: String = "More",
) {
    val tokens = LocalPpmTokens.current
    val scroll = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(tokens.panel.color)
            .cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom),
        verticalAlignment = Alignment.CenterVertically,
    ) {
    Row(
        modifier = Modifier
            .weight(1f)
            .horizontalScroll(scroll)
            .padding(horizontal = 14.dp)
            .testTag("phone-tree-breadcrumb"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                PpmIcon(ProductIcon.ChevronRight, 12.dp, tokens.text3.color)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .ppmClickable(
                        onClickLabel = crumb.label,
                        onClick = { onCrumbClick(crumb) },
                    )
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                BasicText(
                    text = crumb.label,
                    style = ppmText(
                        size = 12.5f,
                        weight = if (crumb.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        lineHeight = 16f,
                    ).copy(color = if (crumb.isCurrent) tokens.text.color else tokens.text2.color),
                    maxLines = 1,
                )
            }
        }
    }
    if (onMore != null) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .semantics { contentDescription = moreLabel }
                .ppmClickable(
                    shape = RoundedCornerShape(8.dp),
                    pressedColor = tokens.surfaceHover.color,
                    hoverColor = tokens.surfaceHover.color,
                    onClickLabel = moreLabel,
                    onClick = onMore,
                )
                .testTag("phone-breadcrumb-more"),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.MoreVertical, 18.dp, tokens.text2.color)
        }
    }
    }
}

/**
 * Single 48 dp tree row on a phone (`trow2`, `Phone 3 · F1 & F6`, Notes 1, 2, 9 & Section 8):
 * - 48 dp height, 16 dp indent per depth level, 20 dp file/folder icon
 * - Git status colour + 12 dp mono letter (`M, A, D, R, C, U`) on files; folder colour + count on folders
 * - Dimmed text when gitignored (`node.isIgnored`), keeping the icon undimmed.
 */
@Composable
fun PhoneTreeRowItem(
    row: FileTreeRow,
    expanded: Boolean,
    loading: Boolean,
    selected: Boolean,
    gitStatus: String?,
    folderChangedCount: Int? = null,
    folderEntryCount: Int? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val node = row.node
    val badgeLetter = remember(gitStatus) { normalizePhoneGitBadgeLetter(gitStatus) }
    val statusColor = remember(badgeLetter, tokens) { phoneGitColor(badgeLetter, tokens) }
    val rowBg = if (selected) tokens.accentWash.color else Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(KitSpec.MinTouchTarget)
            .background(rowBg)
            .semantics {
                this.selected = selected
                contentDescription = node.name
                onLongClick("File actions") {
                    onLongPress()
                    true
                }
            }
            .pointerInput(enabled, row.node.path) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongPress() },
                )
            }
            .padding(start = (16 + row.depth * 16).dp, end = 16.dp)
            .testTag("phone-tree-row-${node.path}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (node.isDirectory) {
            PpmIcon(
                icon = when {
                    loading -> ProductIcon.Loader2
                    expanded -> ProductIcon.ChevronDown
                    else -> ProductIcon.ChevronRight
                },
                size = 16.dp,
                tint = if (loading) tokens.accent.color else tokens.text3.color,
                spinning = loading,
            )
        } else {
            Spacer(Modifier.size(16.dp))
        }

        PhoneTreeFileGlyph(
            name = node.name,
            isDirectory = node.isDirectory,
            expanded = expanded,
        )

        val nameColor = when {
            statusColor != null -> statusColor
            node.isIgnored -> tokens.text3.color
            selected -> tokens.text.color
            node.isDirectory && expanded -> tokens.text.color
            else -> tokens.text2.color
        }

        TruncatedText(
            text = AnnotatedString(node.name),
            style = ppmText(
                size = 14f,
                weight = if (selected || (node.isDirectory && expanded)) FontWeight.Medium else FontWeight.Normal,
                lineHeight = 20f,
            ).copy(
                color = nameColor,
                textDecoration = if (!node.isDirectory && badgeLetter == "D") {
                    TextDecoration.LineThrough
                } else {
                    TextDecoration.None
                },
            ),
            modifier = Modifier.weight(1f),
        )

        if (node.isDirectory) {
            if (folderChangedCount != null && folderChangedCount > 0 && statusColor != null) {
                val pillShape = RoundedCornerShape(999.dp)
                Box(
                    modifier = Modifier
                        .background(statusColor.copy(alpha = 0.14f), pillShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("phone-tree-folder-changed-${node.path}"),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = folderChangedCount.toString(),
                        style = ppmText(11.5f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 14f)
                            .copy(color = statusColor),
                    )
                }
            }
            if (folderEntryCount != null && folderEntryCount > 0) {
                BasicText(
                    text = formatFolderEntryCount(folderEntryCount),
                    style = ppmText(12f, FontWeight.Normal, FontKind.MONO, lineHeight = 16f)
                        .copy(color = tokens.text3.color),
                    modifier = Modifier.testTag("phone-tree-folder-count-${node.path}"),
                )
            }
        } else if (badgeLetter != null && statusColor != null) {
            BasicText(
                text = badgeLetter,
                style = ppmText(12f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 16f)
                    .copy(color = statusColor),
                modifier = Modifier.testTag("phone-tree-git-letter-${node.path}"),
            )
        }
    }
}

internal fun phoneGitColor(badgeLetter: String?, tokens: ThemeTokens): Color? = when (badgeLetter) {
    "M" -> tokens.warning.color
    "A" -> tokens.success.color
    "D" -> tokens.error.color
    "R", "C" -> tokens.accent.color
    "U" -> tokens.text3.color
    else -> gitStatusRgba(badgeLetter, tokens)
}

@Composable
internal fun PhoneTreeFileGlyph(
    name: String,
    isDirectory: Boolean,
    expanded: Boolean,
) {
    val tokens = LocalPpmTokens.current
    if (isDirectory) {
        PpmIcon(
            icon = if (expanded) ProductIcon.FolderOpen else ProductIcon.Folder,
            size = 20.dp,
            tint = Color(0xFFE5A64E),
        )
        return
    }
    val ext = name.substringAfterLast('.', "").lowercase()
    val (icon, tint) = when {
        ext in setOf("ts", "tsx", "js", "jsx", "kt", "py", "go", "rs", "css", "html", "json", "yml", "yaml", "sh") ->
            ProductIcon.FileCode to tokens.accent.color
        ext in setOf("md", "markdown", "mdx", "txt") ->
            ProductIcon.FileText to Color(0xFF9AA7C7)
        ext in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico") ->
            ProductIcon.Image to Color(0xFFA78BFA)
        else ->
            ProductIcon.File to tokens.text2.color
    }
    PpmIcon(icon = icon, size = 20.dp, tint = tint)
}

/**
 * Placeholder shimmer row for lazy-loading folders and initial tree loading (`sk3 shimmer`, Note 9 & 10).
 */
@Composable
internal fun PhoneTreePlaceholderRow(
    depth: Int,
    widthDp: Int,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val barColor = tokens.text.opacity(0.08).color
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(KitSpec.MinTouchTarget)
            .padding(start = (16 + depth * 20).dp, end = 16.dp)
            .testTag("phone-tree-placeholder-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(barColor),
        )
        Box(
            modifier = Modifier
                .width(widthDp.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(barColor),
        )
    }
}

/**
 * Phone Files `⋮` overflow sheet (`Phone 3 · F1`, Note 3):
 * `Reveal Active File`, `Collapse All`, `Refresh`.
 */
@Composable
fun PhoneFilesOverflowSheet(
    onDismiss: () -> Unit,
    onAction: (PhoneFilesOverflowAction) -> Unit,
    modifier: Modifier = Modifier,
    extraActions: List<PhoneTreeLongPressAction> = emptyList(),
    onExtraAction: (PhoneTreeLongPressAction) -> Unit = {},
) {
    Sheet(
        title = "Files",
        onDismiss = onDismiss,
        forceBottomSheet = true,
        modifier = modifier.testTag("phone-files-overflow-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        ) {
            for (action in PhoneFilesOverflowAction.phoneMenu()) {
                val icon = when (action) {
                    PhoneFilesOverflowAction.REVEAL_ACTIVE_FILE -> ProductIcon.Crosshair
                    PhoneFilesOverflowAction.COLLAPSE_ALL -> ProductIcon.ChevronsDownUp
                    PhoneFilesOverflowAction.REFRESH -> ProductIcon.RefreshCw
                }
                PhoneSheetActionRow(
                    icon = icon,
                    label = action.label,
                    onClick = { onAction(action) },
                    testTag = "phone-files-overflow-${action.id}",
                )
            }
            for (action in extraActions) {
                PhoneSheetActionRow(
                    icon = phoneTreeActionIcon(action),
                    label = action.label,
                    onClick = { onExtraAction(action) },
                    testTag = "phone-files-overflow-${action.id}",
                )
            }
        }
    }
}

/**
 * Short long-press bottom sheet for a file or folder on a phone (`Phone 3 · Section 1`, Note 66):
 * - File: `Open`, `Copy Path`, `Copy Full Path`, `Download`
 * - Folder: `Open in Terminal`, `Copy Path`, `Copy Full Path`
 * - Footer line: `"Rename, delete, move and compare: open on a tablet."`
 */
@Composable
fun PhoneTreeLongPressSheet(
    node: FileTreeNode,
    gitBadge: String? = null,
    onDismiss: () -> Unit,
    onAction: (PhoneTreeLongPressAction) -> Unit,
    modifier: Modifier = Modifier,
    mutationActions: List<PhoneTreeLongPressAction> = emptyList(),
) {
    val tokens = LocalPpmTokens.current
    val subtitle = buildString {
        append(node.path.ifEmpty { node.name })
        if (!gitBadge.isNullOrBlank()) {
            append(" · ")
            append(gitBadge)
        }
    }
    val actions = remember(node.isDirectory, mutationActions) {
        PhoneTreeLongPressAction.actionsFor(isDirectory = node.isDirectory) + mutationActions
    }

    Sheet(
        title = node.name,
        subtitle = subtitle,
        onDismiss = onDismiss,
        forceBottomSheet = true,
        modifier = modifier.testTag("phone-tree-long-press-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        ) {
            for (action in actions) {
                PhoneSheetActionRow(
                    icon = phoneTreeActionIcon(action),
                    label = action.label,
                    onClick = { onAction(action) },
                    testTag = "phone-tree-action-${action.id}",
                )
            }
            BasicText(
                text = if (mutationActions.isEmpty()) {
                    PhoneTreeLongPressAction.TABLET_ONLY_FOOTER
                } else {
                    PhoneTreeLongPressAction.COMPARE_TABLET_ONLY_FOOTER
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .testTag("phone-tree-tablet-only-note"),
                style = ppmText(12.5f, lineHeight = 17f).copy(color = tokens.text3.color),
            )
        }
    }
}

internal fun phoneTreeActionIcon(action: PhoneTreeLongPressAction): ProductIcon = when (action) {
    PhoneTreeLongPressAction.OPEN -> ProductIcon.ArrowUpRight
    PhoneTreeLongPressAction.OPEN_IN_TERMINAL -> ProductIcon.TerminalSquare
    PhoneTreeLongPressAction.COPY_PATH -> ProductIcon.Link
    PhoneTreeLongPressAction.COPY_FULL_PATH -> ProductIcon.Link2
    PhoneTreeLongPressAction.DOWNLOAD -> ProductIcon.Download
    PhoneTreeLongPressAction.NEW_FILE -> ProductIcon.FilePlus
    PhoneTreeLongPressAction.NEW_FOLDER -> ProductIcon.FolderPlus
    PhoneTreeLongPressAction.RENAME -> ProductIcon.Pencil
    PhoneTreeLongPressAction.CUT -> ProductIcon.Scissors
    PhoneTreeLongPressAction.PASTE -> ProductIcon.ClipboardPaste
    PhoneTreeLongPressAction.DELETE -> ProductIcon.Trash2
}

@Composable
internal fun PhoneSheetActionRow(
    icon: ProductIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
    testTag: String? = null,
) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(KitSpec.RowPhoneOneLineHeight)
            .ppmClickable(
                pressedColor = tokens.surfaceHover.color,
                hoverColor = tokens.surfaceHover.color,
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PpmIcon(icon, 20.dp, tokens.text2.color)
        BasicText(
            text = label,
            modifier = Modifier.weight(1f),
            style = ppmText(15f, FontWeight.Normal, lineHeight = 20f).copy(color = tokens.text.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailingContent != null) {
            trailingContent()
        }
    }
}

/**
 * Centered empty/error state block (`empty3` in `wave3.css`, Note 10 & 65).
 */
@Composable
internal fun PhoneFilesEmptyBlock(
    icon: ProductIcon,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    monoDetail: String? = null,
    isError: Boolean = false,
    isWarning: Boolean = false,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    testTag: String? = null,
) {
    val tokens = LocalPpmTokens.current
    val iconShape = RoundedCornerShape(16.dp)
    val iconBg = when {
        isError -> tokens.error.opacity(0.10).color
        isWarning -> tokens.warning.opacity(0.14).color
        else -> tokens.surfaceHover.color
    }
    val iconTint = when {
        isError -> tokens.error.color
        isWarning -> tokens.warning.color
        else -> tokens.text3.color
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(iconShape)
                .background(iconBg, iconShape),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(icon, 26.dp, iconTint)
        }
        BasicText(
            text = title,
            style = ppmText(16f, FontWeight.SemiBold, lineHeight = 22f)
                .copy(color = tokens.text.color, textAlign = TextAlign.Center),
        )
        if (!monoDetail.isNullOrBlank()) {
            BasicText(
                text = monoDetail,
                style = ppmText(12f, FontWeight.Normal, FontKind.MONO, lineHeight = 18f)
                    .copy(color = tokens.text2.color, textAlign = TextAlign.Center),
            )
        }
        if (!subtitle.isNullOrBlank()) {
            BasicText(
                text = subtitle,
                style = ppmText(13.5f, FontWeight.Normal, lineHeight = 20f)
                    .copy(color = tokens.text2.color, textAlign = TextAlign.Center),
            )
        }
        if ((retryLabel != null && onRetry != null) || (secondaryLabel != null && onSecondary != null)) {
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (secondaryLabel != null && onSecondary != null) {
                    val btnShape = RoundedCornerShape(12.dp)
                    Row(
                        modifier = Modifier
                            .height(40.dp)
                            .clip(btnShape)
                            .background(tokens.panel2.color, btnShape)
                            .cssBorder(tokens.border.color, btnShape, width = 1.dp)
                            .ppmClickable(shape = btnShape, onClickLabel = secondaryLabel, onClick = onSecondary)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PpmIcon(ProductIcon.X, 16.dp, tokens.text.color)
                        BasicText(
                            text = secondaryLabel,
                            style = ppmText(13.5f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text.color),
                        )
                    }
                }
                if (retryLabel != null && onRetry != null) {
                    val btnShape = RoundedCornerShape(12.dp)
                    Row(
                        modifier = Modifier
                            .height(40.dp)
                            .clip(btnShape)
                            .background(tokens.accent.color, btnShape)
                            .ppmClickable(shape = btnShape, onClickLabel = retryLabel, onClick = onRetry)
                            .padding(horizontal = 14.dp)
                            .testTag("phone-files-retry-button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PpmIcon(ProductIcon.RefreshCw, 16.dp, tokens.accentFg.color)
                        BasicText(
                            text = retryLabel,
                            style = ppmText(13.5f, FontWeight.SemiBold, lineHeight = 18f)
                                .copy(color = tokens.accentFg.color),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Warning or error banner strip (`banner3` in `wave3.css`, Notes 10, 23, 27, 70).
 */
@Composable
internal fun PhoneFilesBanner(
    icon: ProductIcon,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    isError: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(14.dp)
    val bgColor = if (isError) {
        tokens.error.opacity(0.09).color
    } else {
        tokens.warning.opacity(0.11).color
    }
    val borderColor = if (isError) {
        tokens.error.opacity(0.25).color
    } else {
        tokens.warning.opacity(0.32).color
    }
    val accentColor = if (isError) tokens.error.color else tokens.warning.color

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(shape)
            .background(bgColor, shape)
            .cssBorder(borderColor, shape, width = 1.dp)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PpmIcon(icon, 18.dp, accentColor)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            BasicText(
                text = title,
                style = ppmText(13f, FontWeight.SemiBold, lineHeight = 17f).copy(color = accentColor),
            )
            if (!subtitle.isNullOrBlank()) {
                BasicText(
                    text = subtitle,
                    style = ppmText(12.5f, FontWeight.Normal, lineHeight = 16f).copy(color = tokens.text2.color),
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            val btnShape = RoundedCornerShape(10.dp)
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .clip(btnShape)
                    .background(tokens.panel2.color, btnShape)
                    .cssBorder(tokens.border.color, btnShape, width = 1.dp)
                    .ppmClickable(shape = btnShape, onClickLabel = actionLabel, onClick = onAction)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = actionLabel,
                    style = ppmText(13f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                )
            }
        }
    }
}

internal fun copyTextToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
