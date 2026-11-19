package com.hienlh.ppm.state.workspace

import com.hienlh.ppm.client.FsStat
import com.hienlh.ppm.protocol.TabOpenRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The tab the AI's `open_file` / `open_preview` asked for, and where it goes: a port of
 * src/web/lib/ai-tab-placement.ts and the tab half of open-ai-tab.ts. A tool card's Open button
 * goes through the same [WorkspaceStore.openAiTab], so a tab reopened from the chat's history
 * lands where the AI's own call put it.
 */

const val OPEN_FILE_TOOL = "open_file"
const val OPEN_PREVIEW_TOOL = "open_preview"

/** A file as a PPM tab names it (`AiTabTarget`): relative to [projectName]'s folder, or absolute outside it. */
data class AiTabTarget(
    val tool: String,
    val filePath: String,
    val projectName: String?,
    /** 1-based; the file opens as code at this line. */
    val line: Int? = null,
)

sealed interface AiTabPlacement {
    /** The file is open where it can be seen: bring its tab to the front. */
    data class Focus(val tabId: String, val panelId: String) : AiTabPlacement

    /** The file is open behind the chat: move its tab to [toPanelId], or split it out to the right when null. */
    data class Move(val tabId: String, val fromPanelId: String, val toPanelId: String?) : AiTabPlacement

    /** Open a new tab in [panelId], then split it out to the right when [split]. */
    data class Open(val panelId: String, val split: Boolean) : AiTabPlacement
}

private val ABSOLUTE_PATH = Regex("""^(/|[A-Za-z]:[/\\])""")

/** Whether [tab] is an editor tab showing this file, as `openAiTab` names it. */
fun isTabForFile(tab: WorkspaceTab, filePath: String, projectName: String?): Boolean {
    if (tab.type != "editor") return false
    val meta = tab.metadata
    val inline = meta["inlineContent"]
    if (jsTruthy(meta["viewerKey"]) || jsTruthy(meta["isUntitled"]) || (inline != null && inline !is JsonNull)) return false
    val path = meta["filePath"] as? JsonPrimitive
    if (path == null || !path.isString || path.content != filePath) return false
    // A relative path means nothing without its project; an absolute one names the file alone.
    if (ABSOLUTE_PATH.containsMatchIn(filePath)) return true
    val project = meta["projectName"]
    return if (projectName == null) project is JsonNull else (project as? JsonPrimitive)?.let { it.isString && it.content == projectName } == true
}

/**
 * On a desktop the chat stays on screen. The file opens in another panel, and when the chat's
 * panel is the only one it is split so the file sits to the chat's right. A tab already showing
 * the file is reused, unless it is hidden behind the chat in the chat's own panel: then it is
 * moved out beside the chat. A phone shows one panel at a time, so there the tab opens in the
 * first panel and takes the screen; the tab bar leads back to the chat.
 */
fun chooseAiTabPlacement(
    grid: List<List<String>>,
    panels: Map<String, WorkspacePanel>,
    focusedPanelId: String,
    mobile: Boolean,
    /** The chat that asked; the tab goes beside it. */
    sessionId: String,
    isTarget: (WorkspaceTab) -> Boolean,
): AiTabPlacement? {
    val onGrid = grid.flatten().filter { it in panels }
    if (onGrid.isEmpty()) return null
    val chatPanelId = onGrid.firstOrNull { id -> panels.getValue(id).tabs.any { tabSessionId(it) == sessionId } }
    val existing = findExisting(onGrid, chatPanelId, panels, isTarget)

    if (mobile) return existing ?: AiTabPlacement.Open(onGrid.first(), split = false)

    // The chat is in a floating window or not in this workspace at all: every grid panel is beside it.
    if (chatPanelId == null) {
        if (existing != null) return existing
        return AiTabPlacement.Open(if (focusedPanelId in onGrid) focusedPanelId else onGrid.first(), split = false)
    }

    val chatPanel = panels.getValue(chatPanelId)
    val chatShowing = chatPanel.tabs.any { it.id == chatPanel.activeTabId && tabSessionId(it) == sessionId }
    val beside = besidePanel(grid, chatPanelId, focusedPanelId, onGrid)
    if (existing != null) {
        if (existing.panelId != chatPanelId || !chatShowing) return existing
        return AiTabPlacement.Move(existing.tabId, chatPanelId, beside)
    }
    return if (beside != null) AiTabPlacement.Open(beside, split = false) else AiTabPlacement.Open(chatPanelId, split = true)
}

/** A tab already showing the file, looking outside the chat's panel first. */
private fun findExisting(
    onGrid: List<String>,
    chatPanelId: String?,
    panels: Map<String, WorkspacePanel>,
    isTarget: (WorkspaceTab) -> Boolean,
): AiTabPlacement.Focus? {
    val order = onGrid.filter { it != chatPanelId } + listOfNotNull(chatPanelId)
    for (panelId in order) {
        val tab = panels.getValue(panelId).tabs.firstOrNull(isTarget) ?: continue
        return AiTabPlacement.Focus(tab.id, panelId)
    }
    return null
}

/**
 * The panel a file opens in beside the chat: the one the user last focused, when that is not
 * the chat's own; otherwise the chat's neighbour to the right, then to the left, then any other.
 */
private fun besidePanel(grid: List<List<String>>, chatPanelId: String, focusedPanelId: String, onGrid: List<String>): String? {
    if (focusedPanelId != chatPanelId && focusedPanelId in onGrid) return focusedPanelId
    val row = grid.firstOrNull { chatPanelId in it } ?: emptyList()
    val col = row.indexOf(chatPanelId)
    val candidates = row.drop(col + 1) + row.take(maxOf(col, 0)).reversed() + grid.filter { it !== row }.flatten()
    return candidates.firstOrNull { it != chatPanelId && it in onGrid }
}

/**
 * Opens (or brings back) the file's tab beside the chat showing [sessionId]. The editor reads
 * `aiView` and `aiOpenAt` from the tab's metadata: every call switches the view the tool asked
 * for, also in a tab that was already open. Throws, with what to tell the user, when there is
 * nowhere to open it.
 */
fun WorkspaceStore.openAiTab(target: AiTabTarget, sessionId: String): String {
    val st = state.value
    val placement = chooseAiTabPlacement(st.grid, st.panels, st.focusedPanelId, mobile, sessionId) {
        isTabForFile(it, target.filePath, target.projectName)
    } ?: throw IllegalStateException("PPM has no panel to open the tab in")

    val now = clock()
    val aiView = if (target.line != null) "code" else if (target.tool == OPEN_PREVIEW_TOOL) "preview" else null
    val view = buildMap<String, JsonElement?> {
        if (target.line != null) {
            put("lineNumber", JsonPrimitive(target.line))
            // `endLine: undefined`: a range an earlier call selected is dropped.
            put("endLine", null)
            put("revealAt", JsonPrimitive(now))
        }
        if (aiView != null) {
            put("aiView", JsonPrimitive(aiView))
            put("aiOpenAt", JsonPrimitive(now))
        }
    }

    when (placement) {
        is AiTabPlacement.Open -> {
            val metadata = buildJsonObject {
                put("filePath", target.filePath)
                target.projectName?.let { put("projectName", it) }
            }.merged(view)
            val title = target.filePath.split('/', '\\').last()
            val tabId = openTab(NewTab("editor", title, target.projectName, metadata), placement.panelId)
            if (tabId.isEmpty()) throw IllegalStateException("PPM could not open the tab")
            if (placement.split) splitPanel(SplitDirection.Right, tabId, placement.panelId)
            return tabId
        }
        is AiTabPlacement.Focus -> {
            updateTab(placement.tabId) { it.copy(metadata = it.metadata.merged(view)) }
            showExistingTab(placement)
            return placement.tabId
        }
        is AiTabPlacement.Move -> {
            updateTab(placement.tabId) { it.copy(metadata = it.metadata.merged(view)) }
            showExistingTab(placement)
            return placement.tabId
        }
    }
}

/**
 * Opens a new `db-query` tab beside the chat showing [sessionId] (`openAiQueryTab` in `open-ai-tab.ts`).
 * Never reuses an existing Query tab and never sets `runOnOpen`, so the user always inspects the
 * AI-generated SQL before pressing Run.
 */
fun WorkspaceStore.openAiQueryTab(query: kotlinx.serialization.json.JsonObject, sessionId: String): String {
    val st = state.value
    val placement = chooseAiTabPlacement(st.grid, st.panels, st.focusedPanelId, mobile, sessionId) { false }
    if (placement !is AiTabPlacement.Open) {
        throw IllegalStateException("PPM has no panel to open the tab in")
    }
    val allTabs = st.panels.values.flatMap { it.tabs }
    var maxQueryNum = 0
    for (t in allTabs) {
        if (t.type == "db-query") {
            val n = (t.metadata["queryNumber"] as? JsonPrimitive)?.content?.toIntOrNull()
            if (n != null && n > maxQueryNum) maxQueryNum = n
        }
    }
    val queryNumber = maxQueryNum + 1
    val sql = (query["sql"] as? JsonPrimitive)?.content.orEmpty()
    val queryId = webRandomId()
    val metadata = buildJsonObject {
        query["connectionId"]?.let { put("connectionId", it) }
        query["connectionName"]?.let { put("connectionName", it) }
        query["dbType"]?.let { put("dbType", it) }
        (query["database"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.let { put("database", it) }
        (query["connectionColor"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.let { put("connectionColor", it) }
        put("queryId", queryId)
        put("queryNumber", queryNumber)
        put("currentSql", sql)
        put("openedSql", sql)
    }
    val tabId = openTab(NewTab("db-query", "Query $queryNumber", st.currentProject, metadata), placement.panelId)
    if (tabId.isEmpty()) throw IllegalStateException("PPM could not open the tab")
    if (placement.split) splitPanel(SplitDirection.Right, tabId, placement.panelId)
    return tabId
}

/**
 * Opens Branch Review beside the chat showing [sessionId] on the refs `open_branch_review` checked
 * (`openAiBranchReviewTab`), or points the one already open there: a project has one Branch Review
 * tab, and the tab takes `base`/`head` from its metadata whenever `aiOpenAt` moves. In a project
 * folder holding several repositories the server named one; it goes in the tab's metadata as
 * `repo`, which scopes that tab alone and leaves the repository the user chose for Source Control
 * as it was. Throws, with what to tell the AI, when there is nowhere to open it.
 */
fun WorkspaceStore.openAiBranchReviewTab(request: TabOpenRequest.BranchReview, sessionId: String): String {
    val st = state.value
    val placement = chooseAiTabPlacement(st.grid, st.panels, st.focusedPanelId, mobile, sessionId) { tab ->
        tab.type == "branch-review" && (tab.metadata["projectName"] as? JsonPrimitive)?.let { it.isString && it.content == request.projectName } == true
    } ?: throw IllegalStateException("PPM has no panel to open the tab in")
    val view = mapOf<String, JsonElement?>(
        "base" to JsonPrimitive(request.base),
        "head" to JsonPrimitive(request.head),
        // None named: a pin an earlier call left goes, as the web's `repo: undefined` drops it.
        "repo" to request.repo?.let(::JsonPrimitive),
        "aiOpenAt" to JsonPrimitive(clock()),
    )
    return when (placement) {
        is AiTabPlacement.Open -> {
            val metadata = buildJsonObject { put("projectName", request.projectName) }.merged(view)
            val tabId = openTab(NewTab("branch-review", "Branch Review", request.projectName, metadata), placement.panelId)
            if (tabId.isEmpty()) throw IllegalStateException("PPM could not open the tab")
            if (placement.split) splitPanel(SplitDirection.Right, tabId, placement.panelId)
            tabId
        }
        is AiTabPlacement.Focus -> placement.tabId.also { reviewAgain(it, placement, view) }
        is AiTabPlacement.Move -> placement.tabId.also { reviewAgain(it, placement, view) }
    }
}

private fun WorkspaceStore.reviewAgain(tabId: String, placement: AiTabPlacement, view: Map<String, JsonElement?>) {
    updateTab(tabId) { it.copy(metadata = it.metadata.merged(view)) }
    showExistingTab(placement)
}

/** Brings a tab that is already open to where the placement wants it: in front, moved or split out. */
internal fun WorkspaceStore.showExistingTab(placement: AiTabPlacement) {
    when (placement) {
        is AiTabPlacement.Focus -> setActiveTab(placement.tabId, placement.panelId)
        is AiTabPlacement.Move -> if (placement.toPanelId != null) {
            moveTab(placement.tabId, placement.fromPanelId, placement.toPanelId)
        } else if (!splitPanel(SplitDirection.Right, placement.tabId, placement.fromPanelId)) {
            setActiveTab(placement.tabId, placement.fromPanelId)
        }
        is AiTabPlacement.Open -> {}
    }
}

/** Absolute, or `~`-relative: a path the host resolves on its own. */
internal val HOST_PATH = Regex("""^(/|[a-z]:[/\\]|~(?:[/\\]|$))""", RegexOption.IGNORE_CASE)

/**
 * The tab a tool card's Open button opens, from the call's own [path] (`resolveToolCallTarget`):
 * resolved on the host by [stat] (it may start with `~`), and named relative to the chat's
 * project when it lies inside [projectRoot], so it is the same tab the AI's call opened. Throws,
 * with what to tell the user, when it cannot be opened.
 */
suspend fun resolveToolCallTarget(
    tool: String,
    path: String,
    line: Int?,
    projectName: String?,
    projectRoot: String?,
    stat: suspend (String) -> FsStat,
): AiTabTarget {
    val root = projectRoot?.takeIf { it.isNotEmpty() }
    val raw = path.trim()
    val absolute = HOST_PATH.containsMatchIn(raw)
    if (!absolute && root == null) throw IllegalStateException("this chat has no project folder")
    val entry = stat(if (absolute) raw else absoluteProjectPath(root!!, raw))
    if (entry.isDirectory) throw IllegalStateException("$raw is a folder")
    val relative = root?.let { relativeProjectPath(it, entry.path) }
    return if (!relative.isNullOrEmpty() && projectName != null) {
        AiTabTarget(tool, relative, projectName, line)
    } else {
        AiTabTarget(tool, entry.path, null, line)
    }
}

/** `absoluteProjectPath` (file-store.ts): [relative] under [root], in the root's own separators. */
fun absoluteProjectPath(root: String, relative: String): String {
    if (relative.isEmpty()) return root
    val sep = if ('\\' in root) "\\" else "/"
    val native = if (sep == "\\") relative.replace('/', '\\') else relative
    return if (root.endsWith(sep)) root + native else root + sep + native
}

/** `relativeProjectPath`: [absolute] relative to [root], `""` for the root itself, null outside it. */
fun relativeProjectPath(root: String, absolute: String): String? {
    fun normalise(p: String) = p.replace('\\', '/').trimEnd('/')
    val base = normalise(root)
    val target = normalise(absolute)
    if (target == base) return ""
    return if (target.startsWith("$base/")) target.substring(base.length + 1) else null
}
