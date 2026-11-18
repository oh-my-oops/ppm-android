package com.hienlh.ppm.app

import com.hienlh.ppm.app.ui.editor.DeviceLspPreference
import com.hienlh.ppm.app.ui.files.explorerParents
import com.hienlh.ppm.app.ui.files.revealExplorerFile
import com.hienlh.ppm.protocol.AssistantUiRequest
import com.hienlh.ppm.protocol.AssistantUiResult
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.isAssistantProject
import com.hienlh.ppm.state.assistant.AssistantCommand
import com.hienlh.ppm.state.assistant.assistantTabDetails
import com.hienlh.ppm.state.assistant.assistantTabProject
import com.hienlh.ppm.state.assistant.buildUiStateSnapshot
import com.hienlh.ppm.state.assistant.listAssistantCommands
import com.hienlh.ppm.state.assistant.runAssistantCommand
import com.hienlh.ppm.state.assistant.unsavedWorkReason
import com.hienlh.ppm.state.database.losesDbWorkOnClose
import com.hienlh.ppm.state.database.nextQueryNumber
import com.hienlh.ppm.state.database.queryTabMetadata
import com.hienlh.ppm.state.formatShortcut
import com.hienlh.ppm.state.workspace.AiTabTarget
import com.hienlh.ppm.state.workspace.BulkClose
import com.hienlh.ppm.state.workspace.DOCK_PANEL_ID
import com.hienlh.ppm.state.workspace.NewTab
import com.hienlh.ppm.state.workspace.OPEN_FILE_TOOL
import com.hienlh.ppm.state.workspace.WorkspaceStore
import com.hienlh.ppm.state.workspace.isWindowPanelId
import com.hienlh.ppm.state.workspace.openAiTab
import com.hienlh.ppm.state.workspace.windowIdFromPanelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val SHOW_PROJECT_MILLIS = 15_000L

/**
 * The device half of the PPM Assistant's screen tools (`answer-assistant-ui.ts`): what each
 * `assistant_ui` operation does on this device. The controller has already refused every chat that
 * is not the Assistant's. An operation with nothing to do here (`describe_tab`, `move_tab`) is
 * answered as unsupported, so the agent is told rather than left waiting.
 */
internal class AssistantScreenTools(private val model: AppModel) {
    suspend fun answer(request: AssistantUiRequest, chat: ChatTab): AssistantUiResult {
        val args = request.args
        val handler: (suspend () -> JsonElement)? = when (request.op) {
            "get_state" -> { -> state() }
            "open_tab" -> { -> openTab(args, chat) }
            "focus_tab" -> { -> focusTab(args) }
            "switch_project" -> { -> switchProject(args) }
            "close_tab" -> { -> closeTab(args) }
            "reveal_path" -> { -> revealPath(args) }
            "list_commands" -> { -> listAssistantCommands(assistantCommands(model), args, model.selectedProject) }
            "run_command" -> { -> runAssistantCommand(assistantCommands(model), args, model.selectedProject) }
            else -> null
        }
        handler ?: return AssistantUiResult(request.requestId, null, "This PPM build does not support \"${request.op.take(40)}\" on the device.")
        return try {
            AssistantUiResult(request.requestId, handler(), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AssistantUiResult(request.requestId, null, e.message ?: e.toString())
        }
    }

    private fun state(): JsonElement {
        val layout = model.layout ?: throw IllegalStateException("This device shows no project yet.")
        return buildUiStateSnapshot(layout, model.windows?.state?.value?.windows.orEmpty(), phone = !model.wide)
    }

    private fun requireProject(name: JsonElement?): ProjectInfo {
        val text = (name as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text.isNullOrBlank() || isAssistantProject(text)) throw IllegalArgumentException("`project` must name one of the user's registered projects.")
        return model.projects.firstOrNull { it.name == text } ?: throw IllegalArgumentException("This device has no project named \"${text.take(100)}\".")
    }

    /**
     * [block] on [project]'s tabs, brought up first the way the project switcher does. On a phone
     * the Assistant lives in a project's grid, so it comes along onto the new one.
     */
    private suspend fun <T> inProject(project: ProjectInfo, block: (WorkspaceStore) -> T): T {
        val done = CompletableDeferred<Result<T>>()
        val run: (WorkspaceStore) -> Unit = { store ->
            if (!model.wide && store.state.value.gridPanelIds.none { pid -> store.state.value.panels[pid]?.tabs?.any { it.type == "assistant" } == true }) {
                model.assistant.openIn(store)
            }
            done.complete(runCatching { block(store) })
        }
        if (model.selectedProject == project.name) model.onWorkspace(run) else model.openProject(project.name, run)
        val result = withTimeoutOrNull(SHOW_PROJECT_MILLIS) { done.await() }
            ?: throw IllegalStateException("PPM could not show the project \"${project.name}\" in time.")
        return result.getOrThrow()
    }

    private fun nav(tabId: String?, previous: String?, window: String? = null) = buildJsonObject {
        put("tabId", tabId?.let(::JsonPrimitive) ?: JsonNull)
        put("project", model.selectedProject?.let(::JsonPrimitive) ?: JsonNull)
        put("previousProject", previous?.let(::JsonPrimitive) ?: JsonNull)
        window?.let { put("window", it) }
    }

    private fun str(obj: JsonObject, key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content

    private suspend fun openTab(args: JsonObject, chat: ChatTab): JsonElement {
        val project = requireProject(args["project"])
        val target = args["target"] as? JsonObject ?: throw IllegalArgumentException("Unknown tab kind.")
        val kind = str(target, "kind")
        if (kind !in setOf("chat", "terminal", "database", "file", "git", "settings")) throw IllegalArgumentException("Unknown tab kind.")
        val previous = model.selectedProject
        if (kind == "settings") {
            inProject(project) { }
            val section = str(target, "section")
            model.openSettings(section)
            val inWindow = model.wide && model.windows?.state?.value?.windows?.values?.any { it.kind == com.hienlh.ppm.state.windows.WindowKind.Settings } == true
            if (inWindow) return nav(null, previous, window = "settings")
            return nav(model.layout?.panelOf("settings")?.let { "settings" }, previous)
        }
        val sessionId = chat.controller?.sessionId ?: chat.session?.id ?: ""
        val tabId = inProject(project) { store ->
            when (kind) {
                "file" -> {
                    val filePath = str(target, "filePath") ?: throw IllegalArgumentException("No file was named.")
                    val line = (target["line"] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 }
                    val id = store.openAiTab(AiTabTarget(OPEN_FILE_TOOL, filePath, str(target, "projectName"), line), sessionId)
                    // Shown as code, not as its preview: a preview acts on its own (badges, scripts).
                    val tab = store.state.value.panelOf(id)?.tab(id)
                    if (tab != null && str(tab.metadata, "aiView") != "code" && line == null) {
                        store.updateTab(id) { it.withMetadata("aiView" to JsonPrimitive("code")) }
                    }
                    id
                }
                "chat" -> {
                    val sid = str(target, "sessionId")
                    store.openTab(
                        NewTab(
                            type = "chat",
                            title = str(target, "title") ?: if (sid != null) "Chat" else "AI Chat",
                            projectId = project.name,
                            metadata = buildJsonObject {
                                put("projectName", project.name)
                                sid?.let { put("sessionId", it) }
                                str(target, "providerId")?.let { put("providerId", it) }
                            },
                        ),
                    )
                }
                "terminal" -> store.openTab(
                    NewTab("terminal", "Terminal", project.name, buildJsonObject { put("projectName", project.name) }),
                )
                "git" -> {
                    if (str(target, "view") == "log") {
                        store.openTab(NewTab("git-log", "Commits", project.name, buildJsonObject { put("projectName", project.name) }))
                    } else {
                        store.openTab(NewTab("git-review", "Review changes", project.name, buildJsonObject { put("projectName", project.name) }))
                    }
                }
                else -> openDatabase(store, target)
            }
        }
        if (tabId.isEmpty()) throw IllegalStateException("PPM could not open the tab.")
        return nav(tabId, previous)
    }

    /** A saved connection's table, or a Query tab on it (`buildAssistantTabDef`'s database). */
    private fun openDatabase(store: WorkspaceStore, target: JsonObject): String {
        val connectionId = (target["connectionId"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
            ?: throw IllegalArgumentException("No connection was named.")
        val connectionName = str(target, "connectionName")
        val database = str(target, "database")
        val dbType = str(target, "dbType")
        val table = str(target, "table")
        if (table != null) {
            val where = (connectionName ?: "Database") + (database?.let { "/$it" } ?: "")
            return store.openTab(
                NewTab(
                    type = "database",
                    title = "$where · $table",
                    projectId = null,
                    metadata = buildJsonObject {
                        put("connectionId", connectionId)
                        database?.let { put("database", it) }
                        connectionName?.let { put("connectionName", it) }
                        dbType?.let { put("dbType", it) }
                        str(target, "color")?.let { put("connectionColor", it) }
                        put("schemaName", str(target, "schema").orEmpty())
                        put("tableName", table)
                    },
                ),
            )
        }
        val number = nextQueryNumber(store.state.value.panels.values.flatMap { it.tabs })
        val metadata = queryTabMetadata(
            sql = "", queryNumber = number, connectionId = connectionId, connectionName = connectionName,
            dbType = dbType, database = database, connectionColor = str(target, "color"),
        )
        return store.openTab(NewTab("db-query", "Query $number", null, metadata))
    }

    /** The project a tab is shown under, or null when it shows in every project (windows, project-less tabs). */
    private fun owningProject(panelId: String, projectId: String?): String? {
        val state = model.layout ?: return null
        if (panelId == DOCK_PANEL_ID) return projectId
        if (isWindowPanelId(panelId)) return null
        if (panelId in state.gridPanelIds) return state.currentProject
        return state.projectGrids.entries.firstOrNull { (_, grid) -> grid.any { panelId in it } }?.key
    }

    private fun findTab(args: JsonObject): Pair<com.hienlh.ppm.state.workspace.WorkspaceTab, String> {
        val tabId = str(args, "tabId") ?: throw IllegalArgumentException("`tabId` is required: an id from ui_get_state.")
        val panel = model.layout?.panelOf(tabId)
        val tab = panel?.tab(tabId)
        if (panel == null || tab == null) throw IllegalArgumentException("No tab \"${tabId.take(200)}\" is open on this device. Call ui_get_state for the current ids.")
        return tab to panel.id
    }

    private suspend fun focusTab(args: JsonObject): JsonElement {
        val (tab, panelId) = findTab(args)
        val previous = model.selectedProject
        val owner = owningProject(panelId, tab.projectId)
        if (owner != null && owner != previous && owner != "__global__") inProject(requireProject(JsonPrimitive(owner))) { }
        if (isWindowPanelId(panelId)) {
            if (!model.wide) throw IllegalArgumentException("That tab is in a floating window, which this device does not show.")
            model.activate(tab.id, panelId)
            windowIdFromPanelId(panelId)?.let { model.windows?.focus(it) }
        } else {
            model.activate(tab.id, panelId)
            if (panelId == DOCK_PANEL_ID) model.onWorkspace { it.setDockVisible(true) }
        }
        return nav(tab.id, previous)
    }

    private suspend fun switchProject(args: JsonObject): JsonElement {
        val project = requireProject(args["project"])
        val previous = model.selectedProject
        inProject(project) { }
        return nav(null, previous)
    }

    private fun closeTab(args: JsonObject): JsonElement {
        val (tab, panelId) = findTab(args)
        if (tab.type == "assistant") throw IllegalArgumentException("That is the Assistant's own tab; only the user closes it.")
        if (!tab.closable) throw IllegalArgumentException("That tab cannot be closed.")
        val project = assistantTabProject(tab)
        // `discardUnsaved` is the server's alone to add, once the user approved losing this work.
        val discard = (args["discardUnsaved"] as? JsonPrimitive)?.content == "true"
        val reason = if (discard) null else unsavedReason(tab)
        val shown = buildJsonObject {
            put("type", tab.type)
            put("title", tab.title)
            put("project", project?.let(::JsonPrimitive) ?: JsonNull)
        }
        if (reason != null) {
            return buildJsonObject {
                put("closed", false)
                put("tabId", tab.id)
                put("needsApproval", buildJsonObject { put("reason", reason); put("tab", shown) })
            }
        }
        val closed = assistantTabDetails(tab)?.let { JsonObject(shown + ("details" to it)) } ?: shown
        model.closeTab(tab.id, panelId, force = true)
        return buildJsonObject {
            put("closed", true)
            put("tabId", tab.id)
            put("closedTab", closed)
            put("project", model.selectedProject?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    private fun unsavedReason(tab: com.hienlh.ppm.state.workspace.WorkspaceTab): String? {
        if (losesDbWorkOnClose(tab)) {
            return "It holds database work that is not saved: SQL typed in a Query tab, a table changed on its Structure tab, or edited rows."
        }
        val path = str(tab.metadata, "filePath")
        val project = assistantTabProject(tab)
        val dirty = tab.type == "editor" && path != null && project != null && model.hasUnsavedEdits(project, path)
        return unsavedWorkReason(tab, dirty)
    }

    /** `reveal_path`: the project up, its Explorer on screen, the folders on the way opened and the row picked. */
    private suspend fun revealPath(args: JsonObject): JsonElement {
        val project = requireProject(args["project"])
        val path = str(args, "path")
        val kind = str(args, "kind")?.takeIf { it == "file" || it == "folder" }
        if (path == null || kind == null || path.startsWith("/") || path.split('/').contains("..")) {
            throw IllegalArgumentException("`path` must be a path inside the project, as the server sends it.")
        }
        val previous = model.selectedProject
        inProject(project) { }
        model.showSidebarSection("explorer")
        val tree = model.fileTree ?: throw IllegalStateException("The Explorer is not open on this device.")
        val shown = if (kind == "file") revealExplorerFile(tree, path) else revealFolder(tree, path)
        if (!shown) throw IllegalStateException("The Explorer does not list \"${path.take(200)}\" in ${project.name}.")
        return buildJsonObject {
            put("path", path)
            put("kind", kind)
            put("project", model.selectedProject?.let(::JsonPrimitive) ?: JsonNull)
            put("previousProject", previous?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    /** [revealExplorerFile] for a folder: its parents opened and the folder itself picked. */
    private suspend fun revealFolder(tree: com.hienlh.ppm.state.files.ProjectFileTree, path: String): Boolean {
        val parents = explorerParents(path) ?: return false
        val project = tree.state.value.project ?: return false
        tree.loadChildren("")?.join()
        for (parent in parents) {
            if (tree.state.value.node(parent)?.isDirectory != true) return false
            tree.setExpanded(parent, true)
            tree.loadChildren(parent)?.join()
            if (tree.state.value.project != project) return false
        }
        if (tree.state.value.node(path)?.isDirectory != true) return false
        tree.setFocusedPath(path)
        return true
    }
}

/**
 * This device's command registry as the Assistant sees it (`built-in-commands.ts`): the web's ids
 * and labels, for the commands this app has. Each declares whether it changes data.
 */
internal fun assistantCommands(model: AppModel): List<AssistantCommand> = buildList {
    val bindings = model.keyBindings
    fun keys(action: String): String? = formatShortcut(bindings.binding(action)).takeIf { it.isNotEmpty() }
    add(AssistantCommand("chat", "New AI Chat", "ai assistant claude", shortcut = keys("open-chat")) { model.newChat() })
    add(AssistantCommand("ppm-assistant", "PPM Assistant", "assistant agent helper control ppm ai claude codex") { model.openAssistant() })
    add(AssistantCommand("new-file", "New File", "create untitled blank empty", shortcut = keys("new-file")) { model.openNewFile() })
    add(AssistantCommand("new-db-query", "New DB Query", "sql database query scratchpad new") {
        val number = nextQueryNumber(model.layout?.panels?.values?.flatMap { it.tabs }.orEmpty())
        model.openWorkspaceTab(type = "db-query", title = "Query $number", metadata = queryTabMetadata(sql = "", queryNumber = number))
    })
    add(AssistantCommand("terminal", "New Terminal", "bash shell console", shortcut = keys("open-terminal")) { model.openTerminal(inDock = false) })
    add(AssistantCommand("remote-access", "Remote Access", "remote access tunnel cloudflare tailscale public link share url phone domain") { model.openSettings("remote-access") })
    add(AssistantCommand("forward-port", "Open Web Preview / Forward a Port", "forward port forwarding localhost web preview browser tab open website tunnel cloudflare tailscale dev server url") { model.openPortForwarding() })
    add(AssistantCommand("cloud-share", "PPM Cloud & Share", "cloud permanent link alias share phone remote device qr sign in login") { model.cloudShareOpen = true })
    add(AssistantCommand("new-db-connection", "New connection…", "database connection postgres pg mysql mariadb sqlite add") {
        model.openWorkspaceTab(type = "db-connection", title = "New Connection", metadata = buildJsonObject {})
    })
    // It turns the microphone on, which nobody but the user should do unasked.
    add(AssistantCommand("voice-input", "Voice Input", "speech microphone dictate voice", shortcut = keys("voice-input"), changesData = true) { model.toggleVoiceInput() })
    add(AssistantCommand("git-status", "Git Status", "changes diff staged", shortcut = keys("open-git-status")) { model.showSidebarSection("git") })
    add(AssistantCommand("toggle-pin-tab", "Pin / Unpin Tab", "pin unpin sticky keep tab", shortcut = keys("toggle-pin-tab"), changesData = true) { model.togglePinActiveTab() })
    add(AssistantCommand("close-saved-tabs", "Close Saved Tabs", "close saved tabs editors", shortcut = keys("close-saved-tabs"), changesData = true) { model.bulkCloseInFocusedPanel(BulkClose.SAVED) })
    add(AssistantCommand("close-all-tabs", "Close All Tabs", "close all tabs editors", shortcut = keys("close-all-tabs"), changesData = true) { model.bulkCloseInFocusedPanel(BulkClose.ALL) })
    add(AssistantCommand("problems", "Problems", "errors warnings diagnostics lint typescript", shortcut = keys("open-problems")) { model.openProblems() })
    add(AssistantCommand("word-wrap", "Toggle Word Wrap", "wrap unwrap word lines editor soft", shortcut = if (model.wide) "Alt+Z" else null, changesData = true) {
        model.setWordWrapPref(!(if (model.wide) model.wordWrap else model.mobileWordWrap))
    })
    add(AssistantCommand("compare-files", "Compare Files...", "diff compare two files select", shortcut = keys("compare-files")) { model.comparePickerOpen = true })
    // A device setting that starts or stops a language server; a phone has none to switch.
    if (model.wide) {
        val on = DeviceLspPreference.enabled.value
        add(AssistantCommand(
            "language-server", if (on) "Turn Off Language Server" else "Turn On Language Server",
            "lsp language server completions intellisense hover definition typescript pyright gopls", hint = "This device", changesData = true,
        ) { DeviceLspPreference.setEnabled(!DeviceLspPreference.enabled.value) })
    }
    add(AssistantCommand("settings", "Settings", "config preferences theme", shortcut = keys("open-settings")) { model.openSettings() })
    add(AssistantCommand("open-file-explorer", "Open File Explorer", "open file explorer finder browse files folders disk drive window") { model.openExplorer() })
    add(AssistantCommand("system-monitor", "System Monitor", "task manager activity monitor cpu memory ram disk network gpu processes services apps performance resources") { model.openSystemMonitor() })
    add(AssistantCommand("logs", "Logs", "logs server errors warnings debug tail ppm.log browser console cloudflared") { model.openLogs() })
    add(AssistantCommand("report-bug", "Report a Bug", "report bug issue github feedback problem crash") { model.openLogs("report") })
}

