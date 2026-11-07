package com.hienlh.ppm.state.settings

import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.long
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

// ===========================================================================
// 1. Settings Categories & Deep-Link Resolution (Phase 12a.1)
// ===========================================================================

enum class SettingsGroupId(val label: String) {
    CORE("Core"),
    AI("AI"),
    INTEGRATIONS("Integrations"),
    ADVANCED("Advanced"),
}

enum class SettingsCategoryId(
    val id: String,
    val label: String,
    val group: SettingsGroupId,
    val minimalSafe: Boolean = false,
) {
    // Core
    GENERAL("general", "General", SettingsGroupId.CORE, minimalSafe = true),
    APPEARANCE("appearance", "Appearance", SettingsGroupId.CORE, minimalSafe = true),
    SERVERS("servers", "Servers", SettingsGroupId.CORE, minimalSafe = true),
    VOICE("voice", "Voice Input", SettingsGroupId.CORE, minimalSafe = true),
    SHORTCUTS("shortcuts", "Keyboard Shortcuts", SettingsGroupId.CORE, minimalSafe = true),
    FILES("files", "File Filters", SettingsGroupId.CORE, minimalSafe = false),

    // AI (12b)
    AI("ai", "AI Providers", SettingsGroupId.AI, minimalSafe = false),
    ACCOUNTS("accounts", "Accounts", SettingsGroupId.AI, minimalSafe = false),
    MCP("mcp", "MCP Servers", SettingsGroupId.AI, minimalSafe = false),
    AI_RESOURCES("ai-resources", "AI Resources", SettingsGroupId.AI, minimalSafe = false),
    CLAWBOT("clawbot", "PPMBot", SettingsGroupId.AI, minimalSafe = false),
    DESIGN("design", "Design Canvas", SettingsGroupId.AI, minimalSafe = false),

    // Integrations (12c)
    NOTIFICATIONS("notifications", "Notifications", SettingsGroupId.INTEGRATIONS, minimalSafe = false),
    TUNNEL("tunnel", "Remote Access", SettingsGroupId.INTEGRATIONS, minimalSafe = true),
    CLOUD("cloud", "PPM Cloud", SettingsGroupId.INTEGRATIONS, minimalSafe = true),
    PROXY("proxy", "API Proxy", SettingsGroupId.INTEGRATIONS, minimalSafe = false),
    EXTENSIONS("extensions", "Extensions", SettingsGroupId.INTEGRATIONS, minimalSafe = false),
    JIRA("jira", "Jira", SettingsGroupId.INTEGRATIONS, minimalSafe = false),
    SCHEDULES("schedules", "Scheduled Agents", SettingsGroupId.INTEGRATIONS, minimalSafe = false),

    // Advanced (12a.3)
    QUERY_AUDIT("query-audit", "Query Audit Log", SettingsGroupId.ADVANCED, minimalSafe = false),
    LANGUAGE_SERVERS("language-servers", "Language Servers", SettingsGroupId.ADVANCED, minimalSafe = false),
    DATABASE_DRIVERS("database-drivers", "Database Drivers", SettingsGroupId.ADVANCED, minimalSafe = false),
    REMOTE_DESKTOP("remote-desktop", "Remote Desktop", SettingsGroupId.ADVANCED, minimalSafe = false),
    ;

    companion object {
        /** Resolve a category id or deep-link string; unknown ids fall back to [GENERAL]. */
        fun resolve(raw: String?): SettingsCategoryId {
            if (raw.isNullOrBlank()) return GENERAL
            val normalized = raw.trim().lowercase(Locale.ROOT)
            return entries.firstOrNull { it.id == normalized || it.name.lowercase(Locale.ROOT) == normalized }
                ?: GENERAL
        }
    }
}

// ===========================================================================
// 2. Shared UI Preferences vs Device-Local Preferences (Phase 12a.1)
// ===========================================================================

enum class EditorTabStyle(val wire: String, val label: String, val description: String) {
    DEFAULT("default", "Default", "Bottom accent bar with subtle dividers"),
    BOXED("boxed", "Boxed", "Full border around active tab"),
    PILL("pill", "Pill", "Rounded highlight on active tab"),
    ;

    companion object {
        fun fromWire(raw: String?): EditorTabStyle =
            entries.firstOrNull { it.wire == raw } ?: DEFAULT
    }
}

enum class ExplorerSkinMode(val wire: String, val label: String, val description: String) {
    AUTO("auto", "Auto", "Follow the host machine's OS"),
    WINDOWS("windows", "Windows 11", "Command bar, address breadcrumbs, status bar"),
    MACOS("macos", "macOS Finder", "Traffic lights, path bar, sidebar"),
    ;

    companion object {
        fun fromWire(raw: String?): ExplorerSkinMode =
            entries.firstOrNull { it.wire == raw } ?: AUTO
    }
}

data class UiPrefsState(
    val wordWrap: Boolean = true,
    val tabWrap: Boolean = false,
    val keepScreenAwake: Boolean = false,
    val editorTabStyle: EditorTabStyle = EditorTabStyle.DEFAULT,
    val explorerSkin: ExplorerSkinMode = ExplorerSkinMode.AUTO,
    val inlineBlame: Boolean = true,
    val designWindows: Boolean = false,
    val remoteDesktopStatsVisible: Boolean = false,
    val remoteDesktopWarningDismissed: Boolean = false,
    val jiraEnabled: Boolean = false,
)

fun parseUiPrefs(element: JsonElement?): UiPrefsState {
    val obj = element.obj ?: return UiPrefsState()
    return UiPrefsState(
        wordWrap = obj["wordWrap"].bool ?: true,
        tabWrap = obj["tabWrap"].bool ?: false,
        keepScreenAwake = obj["keepScreenAwake"].bool ?: false,
        editorTabStyle = EditorTabStyle.fromWire(obj["editorTabStyle"].string),
        explorerSkin = ExplorerSkinMode.fromWire(obj["explorerSkin"].string),
        inlineBlame = obj["inlineBlame"].bool ?: true,
        designWindows = obj["designWindows"].bool ?: false,
        remoteDesktopStatsVisible = obj["remoteDesktopStatsVisible"].bool ?: false,
        remoteDesktopWarningDismissed = obj["remoteDesktopWarningDismissed"].bool ?: false,
        jiraEnabled = obj["jiraEnabled"].bool ?: false,
    )
}

fun buildUiPrefsPatch(pairs: Map<String, Any>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) {
        when (v) {
            is Boolean -> put(k, v)
            is Int -> put(k, v)
            is String -> put(k, v)
            else -> put(k, JsonPrimitive(v.toString()))
        }
    }
}

/** Device-local preferences that do NOT sync to `/api/settings/ui-prefs`. */
data class LocalDeviceSettings(
    val mobileWordWrap: Boolean = true,
    val lspEnabled: Boolean = true,
)

// ===========================================================================
// 3. General Section Validators & Server Upgrade Status (Phase 12a.2)
// ===========================================================================

fun validateDeviceName(name: String): String? {
    val trimmed = name.trim()
    return if (trimmed.length > 100) "Device name must be 100 characters or less" else null
}

fun validatePasswordChange(password: String, confirm: String): String? {
    val trimmed = password.trim()
    if (trimmed.isEmpty()) return "Password is required"
    if (trimmed.length < 4) return "Password must be at least 4 characters"
    if (password != confirm) return "Passwords do not match"
    return null
}

data class UpgradeStatusInfo(
    val currentVersion: String,
    val availableVersion: String? = null,
    val installMethod: String = "bun",
) {
    val hasUpdate: Boolean get() = !availableVersion.isNullOrBlank() && availableVersion != currentVersion
}

fun parseUpgradeStatus(element: JsonElement?): UpgradeStatusInfo? {
    val obj = element.obj ?: return null
    val current = obj["currentVersion"].string ?: return null
    return UpgradeStatusInfo(
        currentVersion = current,
        availableVersion = obj["availableVersion"].string?.takeIf { it.isNotBlank() },
        installMethod = obj["installMethod"].string ?: "bun",
    )
}

// ===========================================================================
// 4. Imported Custom Themes (Phase 12a.2)
// ===========================================================================

data class ImportedThemeItem(
    val id: String,
    val name: String,
    val base: String,
    val previewColors: List<String> = emptyList(),
    val variables: Map<String, String> = emptyMap(),
)

fun parseImportedThemes(element: JsonElement?): List<ImportedThemeItem> {
    val arr = element.array ?: return emptyList()
    return arr.mapNotNull(::parseImportedThemeItem)
}

fun parseThemeImportResponse(element: JsonElement?): List<ImportedThemeItem> {
    val obj = element.obj ?: return emptyList()
    return obj["themes"].array?.mapNotNull(::parseImportedThemeItem) ?: emptyList()
}

private fun parseImportedThemeItem(element: JsonElement?): ImportedThemeItem? {
    val obj = element.obj ?: return null
    val id = obj["id"].string ?: return null
    val name = obj["name"].string ?: id
    val base = obj["base"].string ?: "dark"
    val preview = obj["previewColors"].array?.mapNotNull { it.string } ?: emptyList()
    val varsObj = obj["variables"].obj
    val vars = buildMap {
        if (varsObj != null) {
            for ((k, v) in varsObj) {
                v.string?.let { put(k, it) }
            }
        }
    }
    return ImportedThemeItem(
        id = id,
        name = name,
        base = base,
        previewColors = preview,
        variables = vars,
    )
}

enum class ThemeUploadKind(val wireSource: String) {
    VSIX("upload"),
    JSON("json"),
    UNSUPPORTED(""),
}

fun detectThemeUploadKind(fileName: String): ThemeUploadKind {
    val lower = fileName.trim().lowercase(Locale.ROOT)
    return when {
        lower.endsWith(".vsix") -> ThemeUploadKind.VSIX
        lower.endsWith(".json") || lower.endsWith(".jsonc") -> ThemeUploadKind.JSON
        else -> ThemeUploadKind.UNSUPPORTED
    }
}

// ===========================================================================
// 5. Keyboard Shortcuts Catalog & Combo Helpers (Phase 12a.2)
// ===========================================================================

enum class KeyCategory(val id: String, val label: String) {
    GENERAL("general", "General"),
    TABS("tabs", "Tabs"),
    PROJECTS("projects", "Projects"),
}

data class KeyActionDef(
    val id: String,
    val label: String,
    val category: KeyCategory,
    val defaultKey: String,
    val locked: Boolean = false,
    val note: String? = null,
    val minimalSafe: Boolean = false,
)

val KEY_ACTIONS: List<KeyActionDef> = buildList {
    add(
        KeyActionDef(
            id = "command-palette",
            label = "Command Palette",
            category = KeyCategory.GENERAL,
            defaultKey = "F1",
            note = "Shift+Shift also opens (not customizable)",
            minimalSafe = true,
        ),
    )
    add(
        KeyActionDef(
            id = "toggle-sidebar",
            label = "Toggle Sidebar",
            category = KeyCategory.GENERAL,
            defaultKey = "Mod+B",
            minimalSafe = true,
        ),
    )
    add(
        KeyActionDef(
            id = "toggle-dock",
            label = "Toggle Terminal Panel",
            category = KeyCategory.GENERAL,
            defaultKey = "Mod+'",
            note = "Opens/closes the terminal panel; auto-opens a terminal when empty. Ctrl+` also toggles it on desktop (fixed).",
            minimalSafe = true,
        ),
    )
    add(
        KeyActionDef(
            id = "save-prevent",
            label = "Prevent Save Dialog",
            category = KeyCategory.GENERAL,
            defaultKey = "Mod+S",
            locked = true,
            note = "Always active — prevents browser save",
            minimalSafe = true,
        ),
    )
    add(
        KeyActionDef(
            id = "voice-input",
            label = "Voice Input",
            category = KeyCategory.GENERAL,
            defaultKey = "Mod+Shift+V",
            note = "Toggle speech-to-text in chat",
        ),
    )
    add(
        KeyActionDef(
            id = "chat-nav-prev",
            label = "Previous Chat Message",
            category = KeyCategory.GENERAL,
            defaultKey = "Alt+ArrowUp",
            note = "Jump to your previous message in the visible chat transcript",
        ),
    )
    add(
        KeyActionDef(
            id = "chat-nav-next",
            label = "Next Chat Message",
            category = KeyCategory.GENERAL,
            defaultKey = "Alt+ArrowDown",
            note = "Jump to your next message in the visible chat transcript",
        ),
    )
    add(
        KeyActionDef(
            id = "compare-files",
            label = "Compare Files...",
            category = KeyCategory.GENERAL,
            defaultKey = "Mod+Alt+D",
            note = "Open file-compare picker (seeds active file as A)",
        ),
    )
    // Tabs
    add(KeyActionDef("next-tab", "Next Tab", KeyCategory.TABS, "Alt+]", minimalSafe = true))
    add(KeyActionDef("prev-tab", "Previous Tab", KeyCategory.TABS, "Alt+[", minimalSafe = true))
    add(KeyActionDef("new-file", "New File", KeyCategory.TABS, "Mod+N"))
    add(KeyActionDef("open-chat", "Open Chat", KeyCategory.TABS, "Mod+L"))
    add(
        KeyActionDef(
            id = "open-terminal",
            label = "Open Terminal Tab",
            category = KeyCategory.TABS,
            defaultKey = "",
            note = "No default shortcut — Mod+' now toggles the terminal panel; still available via the command palette",
            minimalSafe = true,
        ),
    )
    add(KeyActionDef("open-settings", "Open Settings", KeyCategory.TABS, "Mod+,", minimalSafe = true))
    add(KeyActionDef("open-git-status", "Git Status (sidebar)", KeyCategory.TABS, "Mod+Shift+E"))
    add(KeyActionDef("open-search", "Search Files (sidebar)", KeyCategory.TABS, "Mod+Shift+F"))
    add(
        KeyActionDef(
            id = "open-problems",
            label = "Problems",
            category = KeyCategory.TABS,
            defaultKey = "Mod+Shift+M",
            note = "Errors and warnings across the open files, in the panel — VS Code's shortcut",
        ),
    )
    // Projects 1..9
    for (i in 1..9) {
        add(
            KeyActionDef(
                id = "switch-project-$i",
                label = "Switch to Project $i",
                category = KeyCategory.PROJECTS,
                defaultKey = "Mod+$i",
                minimalSafe = true,
            ),
        )
    }
}

val BROWSER_RESERVED_COMBOS: Set<String> = setOf(
    "Ctrl+T",
    "Ctrl+W",
    "Ctrl+N",
    "Ctrl+Tab",
    "Ctrl+L",
    "Ctrl+H",
    "Ctrl+J",
    "F5",
    "Ctrl+R",
    "Ctrl+Shift+I",
    "Ctrl+Shift+J",
)

fun formatKeyCombo(combo: String): String =
    combo.replace("Mod", "Ctrl")

fun isSystemReservedCombo(combo: String): Boolean =
    formatKeyCombo(combo) in BROWSER_RESERVED_COMBOS

fun parseKeybindingOverrides(element: JsonElement?): Map<String, String> {
    val obj = element.obj ?: return emptyMap()
    return buildMap {
        for ((k, v) in obj) {
            v.string?.let { put(k, it) }
        }
    }
}

fun effectiveKeyCombo(action: KeyActionDef, overrides: Map<String, String>): String =
    overrides[action.id] ?: action.defaultKey

// ===========================================================================
// 6. File Filters (Phase 12a.3)
// ===========================================================================

val DEFAULT_FILES_EXCLUDE: List<String> = listOf(
    ".git",
    ".DS_Store",
    "Thumbs.db",
)

val DEFAULT_SEARCH_EXCLUDE: List<String> = listOf(
    "node_modules",
    ".git",
    "dist",
    "build",
    ".next",
    "coverage",
    "*.lock",
    "bun.lockb",
)

data class FileFiltersState(
    val filesExclude: List<String> = DEFAULT_FILES_EXCLUDE,
    val searchExclude: List<String> = DEFAULT_SEARCH_EXCLUDE,
    val useIgnoreFiles: Boolean = true,
)

fun parseGlobalFileFilters(element: JsonElement?): FileFiltersState {
    val obj = element.obj ?: return FileFiltersState()
    return FileFiltersState(
        filesExclude = obj["filesExclude"].array?.mapNotNull { it.string } ?: DEFAULT_FILES_EXCLUDE,
        searchExclude = obj["searchExclude"].array?.mapNotNull { it.string } ?: DEFAULT_SEARCH_EXCLUDE,
        useIgnoreFiles = obj["useIgnoreFiles"].bool ?: true,
    )
}

/**
 * Per-project settings (`GET /api/projects/:name/settings`) nest file filters under `files`.
 * Missing fields inherit from [globalFallback].
 */
fun parseProjectFileFilters(
    element: JsonElement?,
    globalFallback: FileFiltersState = FileFiltersState(),
): FileFiltersState {
    val filesObj = element.obj?.get("files").obj ?: return globalFallback
    return FileFiltersState(
        filesExclude = filesObj["filesExclude"].array?.mapNotNull { it.string } ?: globalFallback.filesExclude,
        searchExclude = filesObj["searchExclude"].array?.mapNotNull { it.string } ?: globalFallback.searchExclude,
        useIgnoreFiles = filesObj["useIgnoreFiles"].bool ?: globalFallback.useIgnoreFiles,
    )
}

fun addExcludePattern(existing: List<String>, raw: String): List<String>? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed in existing) return null
    return existing + trimmed
}

fun removeExcludePattern(existing: List<String>, pattern: String): List<String> =
    existing.filterNot { it == pattern }

// ===========================================================================
// 7. Query Audit Log (Phase 12a.3)
// ===========================================================================

data class QueryAuditState(
    val retentionDays: Int = 60,
    val maxSizeMb: Int = 500,
    val sizeBytes: Long = 0L,
    val entryCount: Long = 0L,
)

fun parseQueryAuditState(element: JsonElement?): QueryAuditState {
    val obj = element.obj ?: return QueryAuditState()
    return QueryAuditState(
        retentionDays = obj["retention_days"].int ?: 60,
        maxSizeMb = obj["max_size_mb"].int ?: 500,
        sizeBytes = obj["size_bytes"].long ?: 0L,
        entryCount = obj["entry_count"].long ?: 0L,
    )
}

fun validateQueryAuditInputs(retentionRaw: String, maxSizeRaw: String): Pair<Int, Int>? {
    val days = retentionRaw.trim().toIntOrNull() ?: return null
    val sizeMb = maxSizeRaw.trim().toIntOrNull() ?: return null
    if (days < 1 || sizeMb < 10) return null
    return days to sizeMb
}

fun formatAuditByteSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    if (bytes < 1024L) return "$bytes B"
    if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

// ===========================================================================
// 8. Language Servers (Phase 12a.3)
// ===========================================================================

data class LspServerRow(
    val id: String,
    val displayName: String,
    val languages: List<String>,
    val installed: Boolean,
    val origin: String? = null,
    val installHint: String = "",
    val canInstall: Boolean = false,
    val removable: Boolean = false,
    val alsoRemoves: List<String> = emptyList(),
)

data class LspRunningRow(
    val id: String,
    val rootPath: String,
    val ready: Boolean,
)

data class LspServersOverview(
    val servers: List<LspServerRow> = emptyList(),
    val installDir: String = "",
    val running: List<LspRunningRow> = emptyList(),
)

fun parseLspServersOverview(element: JsonElement?): LspServersOverview {
    val obj = element.obj ?: return LspServersOverview()
    val servers = obj["servers"].array.orEmpty().mapNotNull { item ->
        val s = item.obj ?: return@mapNotNull null
        val id = s["id"].string ?: return@mapNotNull null
        LspServerRow(
            id = id,
            displayName = s["displayName"].string ?: id,
            languages = s["languages"].array?.mapNotNull { it.string } ?: emptyList(),
            installed = s["installed"].bool ?: false,
            origin = s["origin"].string,
            installHint = s["installHint"].string ?: "",
            canInstall = s["canInstall"].bool ?: false,
            removable = s["removable"].bool ?: false,
            alsoRemoves = s["alsoRemoves"].array?.mapNotNull { it.string } ?: emptyList(),
        )
    }
    val running = obj["running"].array.orEmpty().mapNotNull { item ->
        val r = item.obj ?: return@mapNotNull null
        val id = r["id"].string ?: return@mapNotNull null
        LspRunningRow(
            id = id,
            rootPath = r["rootPath"].string ?: "",
            ready = r["ready"].bool ?: false,
        )
    }
    return LspServersOverview(
        servers = servers,
        installDir = obj["installDir"].string ?: "",
        running = running,
    )
}

fun lspOriginLabel(origin: String?, installDir: String): String = when (origin) {
    "ppm" -> if (installDir.isNotBlank()) "Installed by PPM in $installDir" else "Installed by PPM"
    "path" -> "Found on PATH"
    "bundled" -> "Bundled with PPM"
    "project" -> "In project node_modules"
    else -> ""
}

// ===========================================================================
// 9. Database Drivers (Phase 12a.3)
// ===========================================================================

data class DbDriverRow(
    val id: String,
    val displayName: String,
    val dbTypes: List<String>,
    val npmPackage: String,
    val version: String,
    val approxBytes: Long,
    val status: String, // "not_installed" | "installing" | "installed" | "failed"
    val installedAt: String? = null,
    val sizeBytes: Long? = null,
    val error: String? = null,
)

fun parseDbDrivers(element: JsonElement?): List<DbDriverRow> {
    val arr = element.array ?: return emptyList()
    return arr.mapNotNull(::parseDbDriverRow)
}

fun parseDbDriverRow(element: JsonElement?): DbDriverRow? {
    val obj = element.obj ?: return null
    val id = obj["id"].string ?: return null
    return DbDriverRow(
        id = id,
        displayName = obj["displayName"].string ?: id,
        dbTypes = obj["dbTypes"].array?.mapNotNull { it.string } ?: emptyList(),
        npmPackage = obj["npmPackage"].string ?: "",
        version = obj["version"].string ?: "",
        approxBytes = obj["approxBytes"].long ?: 0L,
        status = obj["status"].string ?: "not_installed",
        installedAt = obj["installedAt"].string,
        sizeBytes = obj["sizeBytes"].long,
        error = obj["error"].string,
    )
}

fun hasBusyDbDriver(drivers: List<DbDriverRow>): Boolean =
    drivers.any { it.status == "installing" }

fun formatDriverBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    if (bytes < 1024L * 1024L) return "${Math.round(bytes / 1024.0)} KB"
    return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

// ===========================================================================
// 10. Remote Desktop WebRTC Relay (Phase 12a.3)
// ===========================================================================

data class RelayStatusState(
    val status: String = "missing", // "ready" | "downloading" | "error" | "missing"
    val source: String? = null, // "bundled" | "system" | null
    val progress: Double? = null, // 0.0 .. 1.0
    val error: String? = null,
    val path: String? = null,
) {
    val isReady: Boolean get() = status == "ready"
    val isDownloading: Boolean get() = status == "downloading"
    val canUninstall: Boolean get() = status == "ready" && source == "bundled"
}

fun parseRelayStatus(element: JsonElement?): RelayStatusState {
    val obj = element.obj ?: return RelayStatusState()
    val rawProgress = obj["progress"]?.toString()?.toDoubleOrNull()
    return RelayStatusState(
        status = obj["status"].string ?: "missing",
        source = obj["source"].string,
        progress = rawProgress,
        error = obj["error"].string,
        path = obj["path"].string,
    )
}
