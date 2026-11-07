package com.hienlh.ppm.state.extensions

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.SocketConnection
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ContributedCommand(
    val command: String,
    val title: String,
    val icon: String? = null,
    val category: String? = null,
) {
    companion object {
        fun from(json: JsonElement?): ContributedCommand? {
            val obj = json.obj ?: return null
            val command = obj["command"].string ?: return null
            val title = obj["title"].string ?: return null
            return ContributedCommand(
                command = command,
                title = title,
                icon = obj["icon"].string,
                category = obj["category"].string,
            )
        }
    }
}

data class ContributedView(
    val id: String,
    val name: String,
    val type: String? = null, // "tree" | "webview"
    val icon: String? = null,
) {
    companion object {
        fun from(json: JsonElement?): ContributedView? {
            val obj = json.obj ?: return null
            val id = obj["id"].string ?: return null
            val name = obj["name"].string ?: return null
            return ContributedView(
                id = id,
                name = name,
                type = obj["type"].string,
                icon = obj["icon"].string,
            )
        }
    }
}

data class ContributedMenu(
    val command: String,
    val whenClause: String? = null,
    val group: String? = null,
) {
    companion object {
        fun from(json: JsonElement?): ContributedMenu? {
            val obj = json.obj ?: return null
            val command = obj["command"].string ?: return null
            return ContributedMenu(
                command = command,
                whenClause = obj["when"].string,
                group = obj["group"].string,
            )
        }
    }
}

data class ContributedKeybinding(
    val command: String,
    val key: String,
    val mac: String? = null,
    val whenClause: String? = null,
) {
    companion object {
        fun from(json: JsonElement?): ContributedKeybinding? {
            val obj = json.obj ?: return null
            val command = obj["command"].string ?: return null
            val key = obj["key"].string ?: return null
            return ContributedKeybinding(
                command = command,
                key = key,
                mac = obj["mac"].string,
                whenClause = obj["when"].string,
            )
        }
    }
}

data class ExtensionContributes(
    val commands: List<ContributedCommand> = emptyList(),
    val views: Map<String, List<ContributedView>> = emptyMap(),
    val menus: Map<String, List<ContributedMenu>> = emptyMap(),
    val keybindings: List<ContributedKeybinding> = emptyList(),
) {
    companion object {
        fun from(json: JsonElement?): ExtensionContributes {
            val obj = json.obj ?: return ExtensionContributes()
            val commands = obj["commands"].array.orEmpty().mapNotNull(ContributedCommand::from)
            val views = obj["views"].obj?.mapValues { (_, arr) ->
                arr.array.orEmpty().mapNotNull(ContributedView::from)
            }.orEmpty()
            val menus = obj["menus"].obj?.mapValues { (_, arr) ->
                arr.array.orEmpty().mapNotNull(ContributedMenu::from)
            }.orEmpty()
            val keybindings = obj["keybindings"].array.orEmpty().mapNotNull(ContributedKeybinding::from)
            return ExtensionContributes(
                commands = commands,
                views = views,
                menus = menus,
                keybindings = keybindings,
            )
        }
    }
}

data class StatusBarItemUI(
    val id: String,
    val text: String,
    val tooltip: String? = null,
    val command: String? = null,
    val alignment: String = "left", // "left" | "right"
    val priority: Int = 0,
    val extensionId: String? = null,
) {
    companion object {
        fun from(json: JsonElement?): StatusBarItemUI? {
            val obj = json.obj ?: return null
            val id = obj["id"].string ?: return null
            val text = obj["text"].string ?: return null
            return StatusBarItemUI(
                id = id,
                text = text,
                tooltip = obj["tooltip"].string,
                command = obj["command"].string,
                alignment = if (obj["alignment"].string == "right") "right" else "left",
                priority = obj["priority"].int ?: 0,
                extensionId = obj["extensionId"].string,
            )
        }
    }
}

data class TreeItemAction(
    val icon: String, // "refresh" | "edit" | "trash" | "plus" | "search"
    val tooltip: String,
    val command: String,
    val commandArgs: List<JsonElement> = emptyList(),
) {
    companion object {
        fun from(json: JsonElement?): TreeItemAction? {
            val obj = json.obj ?: return null
            val icon = obj["icon"].string ?: "refresh"
            val command = obj["command"].string ?: return null
            return TreeItemAction(
                icon = icon,
                tooltip = obj["tooltip"].string ?: "",
                command = command,
                commandArgs = obj["commandArgs"].array.orEmpty(),
            )
        }
    }
}

data class TreeItemUI(
    val id: String,
    val label: String,
    val description: String? = null,
    val tooltip: String? = null,
    val icon: String? = null,
    val color: String? = null,
    val badge: String? = null,
    val wrap: Boolean = false,
    val actions: List<TreeItemAction> = emptyList(),
    val collapsibleState: String = "none", // "none" | "collapsed" | "expanded"
    val command: String? = null,
    val commandArgs: List<JsonElement> = emptyList(),
    val children: List<TreeItemUI>? = null,
    val contextValue: String? = null,
) {
    companion object {
        fun from(json: JsonElement?): TreeItemUI? {
            val obj = json.obj ?: return null
            val id = obj["id"].string ?: return null
            val label = obj["label"].string ?: ""
            return TreeItemUI(
                id = id,
                label = label,
                description = obj["description"].string,
                tooltip = obj["tooltip"].string,
                icon = obj["icon"].string,
                color = obj["color"].string,
                badge = obj["badge"].string,
                wrap = obj["wrap"].bool ?: false,
                actions = obj["actions"].array.orEmpty().mapNotNull(TreeItemAction::from),
                collapsibleState = obj["collapsibleState"].string ?: "none",
                command = obj["command"].string,
                commandArgs = obj["commandArgs"].array.orEmpty(),
                children = obj["children"]?.array?.mapNotNull(::from),
                contextValue = obj["contextValue"].string,
            )
        }
    }
}

data class QuickPickItemUI(
    val label: String,
    val description: String? = null,
    val detail: String? = null,
    val picked: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("label", label)
        if (description != null) put("description", description)
        if (detail != null) put("detail", detail)
        if (picked) put("picked", true)
    }

    companion object {
        fun from(json: JsonElement?): QuickPickItemUI? {
            val obj = json.obj ?: return null
            val label = obj["label"].string ?: return null
            return QuickPickItemUI(
                label = label,
                description = obj["description"].string,
                detail = obj["detail"].string,
                picked = obj["picked"].bool ?: false,
            )
        }
    }
}

data class QuickPickOptions(
    val placeholder: String? = null,
    val canPickMany: Boolean = false,
) {
    companion object {
        fun from(json: JsonElement?): QuickPickOptions {
            val obj = json.obj ?: return QuickPickOptions()
            return QuickPickOptions(
                placeholder = obj["placeholder"].string,
                canPickMany = obj["canPickMany"].bool ?: false,
            )
        }
    }
}

data class QuickPickRequest(
    val requestId: String,
    val items: List<QuickPickItemUI>,
    val options: QuickPickOptions,
)

data class InputBoxOptions(
    val prompt: String? = null,
    val value: String? = null,
    val placeholder: String? = null,
    val password: Boolean = false,
) {
    companion object {
        fun from(json: JsonElement?): InputBoxOptions {
            val obj = json.obj ?: return InputBoxOptions()
            return InputBoxOptions(
                prompt = obj["prompt"].string,
                value = obj["value"].string,
                placeholder = obj["placeholder"].string,
                password = obj["password"].bool ?: false,
            )
        }
    }
}

data class InputBoxRequest(
    val requestId: String,
    val options: InputBoxOptions,
)

data class WebviewPanelUI(
    val id: String,
    val extensionId: String,
    val viewType: String,
    val title: String,
    val html: String = "",
    val projectName: String? = null,
)

sealed interface ExtServerMessage {
    data class ContributionsUpdate(
        val contributions: ExtensionContributes,
        val activationErrors: Map<String, String>,
    ) : ExtServerMessage

    data class StatusBarUpdate(val item: StatusBarItemUI) : ExtServerMessage
    data class StatusBarRemove(val itemId: String) : ExtServerMessage
    data class TreeUpdate(val viewId: String, val items: List<TreeItemUI>, val parentId: String?) : ExtServerMessage
    data class TreeRefresh(val viewId: String) : ExtServerMessage

    data class Notification(
        val id: String,
        val level: String, // "info" | "warn" | "error"
        val message: String,
        val actions: List<String>,
    ) : ExtServerMessage

    data class QuickPickShow(
        val requestId: String,
        val items: List<QuickPickItemUI>,
        val options: QuickPickOptions,
    ) : ExtServerMessage

    data class InputBoxShow(
        val requestId: String,
        val options: InputBoxOptions,
    ) : ExtServerMessage

    data class WebviewCreate(
        val panelId: String,
        val extensionId: String,
        val viewType: String,
        val title: String,
        val projectPath: String?,
        val projectName: String?,
    ) : ExtServerMessage

    data class WebviewHtml(val panelId: String, val html: String) : ExtServerMessage
    data class WebviewDispose(val panelId: String) : ExtServerMessage
    data class WebviewPostMessage(val panelId: String, val message: JsonElement) : ExtServerMessage

    data class TabOpen(
        val tabType: String,
        val title: String,
        val projectId: String?,
        val closable: Boolean,
        val metadata: Map<String, JsonElement>,
    ) : ExtServerMessage

    data class ProjectSwitch(val projectName: String) : ExtServerMessage
    data class Unknown(val type: String, val raw: Map<String, JsonElement>) : ExtServerMessage

    companion object {
        fun parse(text: String): ExtServerMessage? {
            val root = try {
                Json.parseToJsonElement(text).obj
            } catch (_: Exception) {
                null
            } ?: return null
            val type = root["type"].string ?: return null
            return when (type) {
                "contributions:update" -> ContributionsUpdate(
                    contributions = ExtensionContributes.from(root["contributions"]),
                    activationErrors = root["activationErrors"].obj?.mapNotNull { (k, v) ->
                        v.string?.let { k to it }
                    }?.toMap().orEmpty(),
                )
                "statusbar:update" -> {
                    val item = StatusBarItemUI.from(root["item"]) ?: return Unknown(type, root)
                    StatusBarUpdate(item)
                }
                "statusbar:remove" -> StatusBarRemove(root["itemId"].string ?: "")
                "tree:update" -> TreeUpdate(
                    viewId = root["viewId"].string ?: "",
                    items = root["items"].array.orEmpty().mapNotNull(TreeItemUI::from),
                    parentId = root["parentId"].string,
                )
                "tree:refresh" -> TreeRefresh(root["viewId"].string ?: "")
                "notification" -> Notification(
                    id = root["id"].string ?: "",
                    level = root["level"].string ?: "info",
                    message = root["message"].string ?: "",
                    actions = root["actions"].array.orEmpty().mapNotNull { it.string },
                )
                "quickpick:show" -> QuickPickShow(
                    requestId = root["requestId"].string ?: "",
                    items = root["items"].array.orEmpty().mapNotNull(QuickPickItemUI::from),
                    options = QuickPickOptions.from(root["options"]),
                )
                "inputbox:show" -> InputBoxShow(
                    requestId = root["requestId"].string ?: "",
                    options = InputBoxOptions.from(root["options"]),
                )
                "webview:create" -> WebviewCreate(
                    panelId = root["panelId"].string ?: "",
                    extensionId = root["extensionId"].string ?: "",
                    viewType = root["viewType"].string ?: "",
                    title = root["title"].string ?: "",
                    projectPath = root["projectPath"].string,
                    projectName = root["projectName"].string,
                )
                "webview:html" -> WebviewHtml(
                    panelId = root["panelId"].string ?: "",
                    html = root["html"].string ?: "",
                )
                "webview:dispose" -> WebviewDispose(root["panelId"].string ?: "")
                "webview:postMessage" -> WebviewPostMessage(
                    panelId = root["panelId"].string ?: "",
                    message = root["message"] ?: JsonNull,
                )
                "tab:open" -> TabOpen(
                    tabType = root["tabType"].string ?: "",
                    title = root["title"].string ?: "",
                    projectId = root["projectId"].string,
                    closable = root["closable"].bool ?: true,
                    metadata = root["metadata"].obj.orEmpty(),
                )
                "project:switch" -> ProjectSwitch(root["projectName"].string ?: "")
                else -> Unknown(type, root)
            }
        }
    }
}

object ExtClientMessages {
    fun ready(): String = buildJsonObject { put("type", "ready") }.toString()

    fun commandExecute(command: String, args: List<JsonElement> = emptyList()): String = buildJsonObject {
        put("type", "command:execute")
        put("command", command)
        if (args.isNotEmpty()) put("args", JsonArray(args))
    }.toString()

    fun treeExpand(viewId: String, itemId: String): String = buildJsonObject {
        put("type", "tree:expand")
        put("viewId", viewId)
        put("itemId", itemId)
    }.toString()

    fun webviewMessage(panelId: String, message: JsonElement): String = buildJsonObject {
        put("type", "webview:message")
        put("panelId", panelId)
        put("message", message)
    }.toString()

    fun webviewClose(panelId: String): String = buildJsonObject {
        put("type", "webview:close")
        put("panelId", panelId)
    }.toString()

    fun quickPickResolve(requestId: String, selected: List<QuickPickItemUI>?): String = buildJsonObject {
        put("type", "quickpick:resolve")
        put("requestId", requestId)
        if (selected == null) {
            put("selected", JsonNull)
        } else {
            put("selected", buildJsonArray { selected.forEach { add(it.toJson()) } })
        }
    }.toString()

    fun inputBoxResolve(requestId: String, value: String?): String = buildJsonObject {
        put("type", "inputbox:resolve")
        put("requestId", requestId)
        if (value == null) put("value", JsonNull) else put("value", value)
    }.toString()

    fun notificationAction(id: String, action: String?): String = buildJsonObject {
        put("type", "notification:action")
        put("id", id)
        if (action == null) put("action", JsonNull) else put("action", action)
    }.toString()
}

/**
 * Pure state machine and helpers for extension tabs, tree views, status bar, and webviews
 * (`use-extension-ws.ts`, `extension-store.ts`, `webview-theme.ts`, `webview-external-link.ts`,
 * `webview-file-icons.ts`).
 */
class ExtensionCoordinator(
    private val dispatchTtlMs: Long = 15_000L,
    private val closedTtlMs: Long = 5_000L,
) {
    private val locallyDispatchedViews = LinkedHashMap<String, Long>()
    private val recentlyClosedViews = LinkedHashMap<String, Long>()
    private val recoveryViews = HashSet<String>()

    fun viewSlug(viewTypeOrCommand: String): String =
        viewTypeOrCommand.removeSuffix(".view")

    fun closedViewKey(slug: String, projectName: String?): String =
        "$slug:${projectName.orEmpty()}"

    fun recordCommandDispatch(
        command: String,
        projectPath: String? = null,
        recovery: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val slug = viewSlug(command)
        val iter = recentlyClosedViews.keys.iterator()
        while (iter.hasNext()) {
            if (iter.next().startsWith("$slug:")) iter.remove()
        }
        locallyDispatchedViews["$slug:${projectPath.orEmpty()}"] = nowMs
        if (recovery) recoveryViews.add(slug) else recoveryViews.remove(slug)
    }

    fun recordWebviewClosed(
        viewType: String?,
        projectName: String?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (viewType.isNullOrEmpty()) return
        val slug = viewSlug(viewType)
        recentlyClosedViews[closedViewKey(slug, projectName)] = nowMs
    }

    fun isRecentlyClosed(
        slug: String,
        projectName: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val expIter = recentlyClosedViews.entries.iterator()
        while (expIter.hasNext()) {
            val entry = expIter.next()
            if (nowMs - entry.value > closedTtlMs) expIter.remove()
        }
        return closedViewKey(slug, projectName) in recentlyClosedViews
    }

    fun takeLocalDispatch(
        slug: String,
        projectPath: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val expIter = locallyDispatchedViews.entries.iterator()
        while (expIter.hasNext()) {
            val entry = expIter.next()
            if (nowMs - entry.value > dispatchTtlMs) expIter.remove()
        }
        if (locallyDispatchedViews.remove("$slug:${projectPath.orEmpty()}") != null) return true
        if (!projectPath.isNullOrEmpty() && locallyDispatchedViews.remove("$slug:") != null) return true
        if (projectPath.isNullOrEmpty()) {
            val match = locallyDispatchedViews.keys.firstOrNull { it.startsWith("$slug:") }
            if (match != null) {
                locallyDispatchedViews.remove(match)
                return true
            }
        }
        return false
    }

    fun consumeRecovery(slug: String): Boolean = recoveryViews.remove(slug)
}

object ExtensionWebviewBridge {
    const val HOST_THEME_MESSAGE = "ppm:theme"
    const val OPEN_EXTERNAL_COMMAND = "__ppm.openExternal"
    const val FILE_ICONS_COMMAND = "__ppm.fileIcons"
    private const val MAX_ICON_NAMES = 500

    /**
     * Inject `acquireVsCodeApi()` shim right after `<head>`.
     * Uses in-memory fallback when `sessionStorage` throws inside a sandboxed iframe
     * without `allow-same-origin`.
     */
    private const val VSCODE_API_SHIM = """<script>
(function(){
  var memState = null;
  window.acquireVsCodeApi = function() {
    return {
      postMessage: function(m) { window.parent.postMessage(m, "*"); },
      getState: function() {
        try { return JSON.parse(sessionStorage.getItem("vscode-state") || "null"); }
        catch (e) { return memState; }
      },
      setState: function(s) {
        memState = s;
        try { sessionStorage.setItem("vscode-state", JSON.stringify(s)); }
        catch (e) {}
        return s;
      }
    };
  };
})();
</script>"""

    private const val HOST_THEME_SHIM = """<script>
window.addEventListener("message",function(e){
  if(e.source!==window.parent)return;
  var m=e.data;
  if(!m||m.command!=="ppm:theme")return;
  document.documentElement.setAttribute("data-ppm-theme",m.mode==="dark"?"dark":"light");
  var el=document.getElementById("ppm-theme-vars");
  if(el)el.textContent=m.css||"";
});
</script>"""

    private val HTML_TAG_REGEX = Regex("""<html(\s[^>]*)?>""", RegexOption.IGNORE_CASE)

    val VAR_MAP: List<Pair<String, String>> = listOf(
        "--bg" to "--bg-solid",
        "--surface" to "--panel",
        "--selected" to "--accent-wash",
        "--text" to "--text",
        "--subtext" to "--text-2",
        "--subtle" to "--text-3",
        "--border" to "--border-soft",
        "--border2" to "--border",
        "--blue" to "--accent",
        "--green" to "--success",
        "--yellow" to "--warning",
        "--red" to "--error",
        "--accent" to "--accent",
        "--accent-fg" to "--accent-fg",
        "--accent-wash" to "--accent-wash",
        "--accent-wash-border" to "--accent-wash-border",
        "--panel" to "--panel",
        "--panel-2" to "--panel-2",
        "--shadow-float" to "--shadow-float",
        "--shadow-panel" to "--shadow-panel",
    )

    fun injectVscodeApiShim(html: String): String {
        if (html.isEmpty()) return html
        val headIdx = html.indexOf("<head>", ignoreCase = true)
        return if (headIdx != -1) {
            html.substring(0, headIdx + 6) + VSCODE_API_SHIM + html.substring(headIdx + 6)
        } else {
            VSCODE_API_SHIM + html
        }
    }

    fun hostThemeCss(readVar: (String) -> String): String {
        val decls = mutableListOf<String>()
        for ((webviewVar, appVar) in VAR_MAP) {
            val value = readVar(appVar).replace("<", "").replace(">", "").trim()
            if (value.isNotEmpty()) {
                decls += "$webviewVar: $value;"
            }
        }
        if (decls.isEmpty()) return ""
        return ":root[data-ppm-theme] { ${decls.joinToString(" ")} }"
    }

    fun injectHostTheme(html: String, mode: String, css: String): String {
        if (html.isEmpty()) return html
        val safeMode = if (mode == "dark") "dark" else "light"
        val match = HTML_TAG_REGEX.find(html)
        val withMode = if (match != null) {
            val attrs = match.groupValues.getOrNull(1).orEmpty()
            html.replaceRange(match.range, "<html$attrs data-ppm-theme=\"$safeMode\">")
        } else {
            html
        }
        val block = "<style id=\"ppm-theme-vars\">$css</style>$HOST_THEME_SHIM"
        val headEnd = withMode.lowercase().indexOf("</head>")
        return if (headEnd != -1) {
            withMode.substring(0, headEnd) + block + withMode.substring(headEnd)
        } else {
            withMode + block
        }
    }

    /**
     * Returns:
     * - `null` if [data] is NOT an `__ppm.openExternal` message (caller should forward to server)
     * - `Result.success(url)` if it is a valid `http(s)` URL to open externally
     * - `Result.failure(...)` if it IS an `__ppm.openExternal` message with an invalid/non-http URL (swallow it)
     */
    fun externalLinkRequest(data: JsonElement?): Result<String>? {
        val obj = data?.obj ?: return null
        if (obj["command"].string != OPEN_EXTERNAL_COMMAND) return null
        val raw = obj["url"].string ?: return Result.failure(IllegalArgumentException("Missing url"))
        return try {
            val uri = java.net.URI(raw)
            val scheme = uri.scheme?.lowercase()
            if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()) {
                Result.success(uri.toString())
            } else {
                Result.failure(IllegalArgumentException("Unsupported scheme"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Returns `null` when [data] is not a `__ppm.fileIcons` message; otherwise returns up to 500 file names.
     */
    fun fileIconsRequest(data: JsonElement?): List<String>? {
        val obj = data?.obj ?: return null
        if (obj["command"].string != FILE_ICONS_COMMAND) return null
        val names = obj["names"]?.array ?: return emptyList()
        return names.mapNotNull { it.string }.take(MAX_ICON_NAMES)
    }

    fun mergeTreeChildren(
        nodes: List<TreeItemUI>,
        parentId: String,
        children: List<TreeItemUI>,
    ): List<TreeItemUI> = nodes.map { node ->
        when {
            node.id == parentId -> node.copy(children = children, collapsibleState = "expanded")
            node.children != null -> node.copy(children = mergeTreeChildren(node.children, parentId, children))
            else -> node
        }
    }
}

/**
 * WebSocket client and state store for `/ws/extensions` (`use-extension-ws.ts` + `extension-store.ts`).
 */
class ExtensionClient(
    private val api: ApiClient,
    parent: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    val coordinator = ExtensionCoordinator()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _contributions = MutableStateFlow<ExtensionContributes?>(null)
    val contributions: StateFlow<ExtensionContributes?> = _contributions.asStateFlow()

    private val _activationErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val activationErrors: StateFlow<Map<String, String>> = _activationErrors.asStateFlow()

    private val _statusBarItems = MutableStateFlow<Map<String, StatusBarItemUI>>(emptyMap())
    val statusBarItems: StateFlow<Map<String, StatusBarItemUI>> = _statusBarItems.asStateFlow()

    private val _treeViews = MutableStateFlow<Map<String, List<TreeItemUI>>>(emptyMap())
    val treeViews: StateFlow<Map<String, List<TreeItemUI>>> = _treeViews.asStateFlow()

    private val _webviewPanels = MutableStateFlow<Map<String, WebviewPanelUI>>(emptyMap())
    val webviewPanels: StateFlow<Map<String, WebviewPanelUI>> = _webviewPanels.asStateFlow()

    private val _quickPick = MutableStateFlow<QuickPickRequest?>(null)
    val quickPick: StateFlow<QuickPickRequest?> = _quickPick.asStateFlow()

    private val _inputBox = MutableStateFlow<InputBoxRequest?>(null)
    val inputBox: StateFlow<InputBoxRequest?> = _inputBox.asStateFlow()

    private val _webviewMessages = MutableSharedFlow<Pair<String, JsonElement>>(extraBufferCapacity = 64)
    val webviewMessages: SharedFlow<Pair<String, JsonElement>> = _webviewMessages.asSharedFlow()

    var onWebviewCreated: ((ExtServerMessage.WebviewCreate) -> Unit)? = null
    var onWebviewDisposed: ((panelId: String) -> Unit)? = null
    var onTabOpen: ((ExtServerMessage.TabOpen) -> Unit)? = null
    var onProjectSwitch: ((projectName: String) -> Unit)? = null
    var onNotification: ((ExtServerMessage.Notification) -> Unit)? = null

    private var socket: SocketConnection? = null
    private var socketJob: Job? = null
    private var quickPickTimeoutJob: Job? = null
    private var inputBoxTimeoutJob: Job? = null
    private var lastFrameAt = clock()
    private var failures = 0
    private var stopped = false

    fun start() {
        connect()
    }

    fun stop() {
        stopped = true
        scope.cancel()
        socket?.close()
        socket = null
        _isConnected.value = false
    }

    fun wake() {
        if (stopped || (socket != null && clock() - lastFrameAt < 45_000L)) return
        failures = 0
        socketJob?.cancel()
        val dead = socket
        socket = null
        dead?.close()
        connect()
    }

    fun sendCommand(command: String, args: List<JsonElement> = emptyList()) {
        socket?.send(ExtClientMessages.commandExecute(command, args))
    }

    fun expandTree(viewId: String, itemId: String) {
        socket?.send(ExtClientMessages.treeExpand(viewId, itemId))
    }

    fun sendWebviewMessage(panelId: String, message: JsonElement) {
        socket?.send(ExtClientMessages.webviewMessage(panelId, message))
    }

    fun closeWebview(panelId: String) {
        _webviewPanels.value = _webviewPanels.value - panelId
        socket?.send(ExtClientMessages.webviewClose(panelId))
    }

    fun resolveQuickPick(requestId: String, selected: List<QuickPickItemUI>?) {
        quickPickTimeoutJob?.cancel()
        if (_quickPick.value?.requestId == requestId) {
            _quickPick.value = null
        }
        socket?.send(ExtClientMessages.quickPickResolve(requestId, selected))
    }

    fun resolveInputBox(requestId: String, value: String?) {
        inputBoxTimeoutJob?.cancel()
        if (_inputBox.value?.requestId == requestId) {
            _inputBox.value = null
        }
        socket?.send(ExtClientMessages.inputBoxResolve(requestId, value))
    }

    fun sendNotificationAction(id: String, action: String?) {
        socket?.send(ExtClientMessages.notificationAction(id, action))
    }

    internal fun handle(msg: ExtServerMessage) {
        when (msg) {
            is ExtServerMessage.ContributionsUpdate -> {
                _contributions.value = msg.contributions
                _activationErrors.value = msg.activationErrors
            }
            is ExtServerMessage.StatusBarUpdate -> {
                _statusBarItems.value = _statusBarItems.value + (msg.item.id to msg.item)
            }
            is ExtServerMessage.StatusBarRemove -> {
                _statusBarItems.value = _statusBarItems.value - msg.itemId
            }
            is ExtServerMessage.TreeUpdate -> {
                val updated = if (msg.parentId != null) {
                    val existing = _treeViews.value[msg.viewId].orEmpty()
                    ExtensionWebviewBridge.mergeTreeChildren(existing, msg.parentId, msg.items)
                } else {
                    msg.items
                }
                _treeViews.value = _treeViews.value + (msg.viewId to updated)
            }
            is ExtServerMessage.TreeRefresh -> Unit
            is ExtServerMessage.WebviewCreate -> {
                val existingHtml = _webviewPanels.value[msg.panelId]?.html.orEmpty()
                val panel = WebviewPanelUI(
                    id = msg.panelId,
                    extensionId = msg.extensionId,
                    viewType = msg.viewType,
                    title = msg.title,
                    html = existingHtml,
                    projectName = msg.projectName,
                )
                _webviewPanels.value = _webviewPanels.value + (msg.panelId to panel)
                onWebviewCreated?.invoke(msg)
            }
            is ExtServerMessage.WebviewHtml -> {
                val existing = _webviewPanels.value[msg.panelId] ?: return
                _webviewPanels.value = _webviewPanels.value + (msg.panelId to existing.copy(html = msg.html))
            }
            is ExtServerMessage.WebviewDispose -> {
                _webviewPanels.value = _webviewPanels.value - msg.panelId
                onWebviewDisposed?.invoke(msg.panelId)
            }
            is ExtServerMessage.WebviewPostMessage -> {
                _webviewMessages.tryEmit(msg.panelId to msg.message)
            }
            is ExtServerMessage.QuickPickShow -> {
                quickPickTimeoutJob?.cancel()
                _quickPick.value = QuickPickRequest(msg.requestId, msg.items, msg.options)
                quickPickTimeoutJob = scope.launch {
                    delay(30_000L)
                    if (_quickPick.value?.requestId == msg.requestId) {
                        resolveQuickPick(msg.requestId, null)
                    }
                }
            }
            is ExtServerMessage.InputBoxShow -> {
                inputBoxTimeoutJob?.cancel()
                _inputBox.value = InputBoxRequest(msg.requestId, msg.options)
                inputBoxTimeoutJob = scope.launch {
                    delay(30_000L)
                    if (_inputBox.value?.requestId == msg.requestId) {
                        resolveInputBox(msg.requestId, null)
                    }
                }
            }
            is ExtServerMessage.TabOpen -> onTabOpen?.invoke(msg)
            is ExtServerMessage.ProjectSwitch -> onProjectSwitch?.invoke(msg.projectName)
            is ExtServerMessage.Notification -> onNotification?.invoke(msg)
            is ExtServerMessage.Unknown -> Unit
        }
    }

    private fun connect() {
        if (stopped) return
        val socket = SocketConnection(api.extensionSocketUrl())
        this.socket = socket
        lastFrameAt = clock()
        socketJob = scope.launch {
            for (event in socket.events) receive(event, socket)
            socketEnded(socket)
        }
    }

    private fun receive(event: SocketConnection.Event, source: SocketConnection) {
        if (source !== socket) return
        when (event) {
            SocketConnection.Event.Opened -> {
                failures = 0
                lastFrameAt = clock()
                _isConnected.value = true
                source.send(ExtClientMessages.ready())
            }
            is SocketConnection.Event.Text -> {
                lastFrameAt = clock()
                ExtServerMessage.parse(event.text)?.let(::handle)
            }
            is SocketConnection.Event.Binary -> {
                lastFrameAt = clock()
                ExtServerMessage.parse(event.bytes.utf8())?.let(::handle)
            }
            is SocketConnection.Event.Closed -> Unit
        }
    }

    private fun socketEnded(ended: SocketConnection) {
        if (ended !== socket || stopped) return
        socket = null
        _isConnected.value = false
        val wait = minOf(1_000L shl minOf(failures, 5), 30_000L)
        failures++
        socketJob = scope.launch {
            delay(wait)
            connect()
        }
    }
}
