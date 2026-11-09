package com.hienlh.ppm.state.settings

import com.hienlh.ppm.protocol.WireJson
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

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
// 4. Imported Custom Themes & .vsix Extraction (Phase 12a.2)
// ===========================================================================

data class ImportedThemeItem(
    val id: String,
    val name: String,
    val style: String = "custom",
    val mode: String = "dark", // "dark" | "light"
    val tokens: Map<String, String> = emptyMap(),
    val swatch: List<String> = emptyList(),
    val mobileOverrides: Map<String, String> = emptyMap(),
) {
    val base: String get() = mode
    val previewColors: List<String> get() = swatch
    val variables: Map<String, String> get() = tokens
}

fun parseImportedThemes(element: JsonElement?): List<ImportedThemeItem> {
    val arr = element.array ?: return emptyList()
    return arr.mapNotNull(::parseImportedThemeItem)
}

fun parseThemeImportResponse(element: JsonElement?): List<ImportedThemeItem> {
    val obj = element.obj ?: return emptyList()
    return obj["themes"].array?.mapNotNull(::parseImportedThemeItem) ?: emptyList()
}

private fun parseStringMap(obj: Map<String, JsonElement>?): Map<String, String> = buildMap {
    if (obj != null) {
        for ((k, v) in obj) {
            v.string?.let { put(k, it) }
        }
    }
}

private fun parseImportedThemeItem(element: JsonElement?): ImportedThemeItem? {
    val obj = element.obj ?: return null
    val id = obj["id"].string ?: return null
    val name = obj["name"].string ?: id
    val style = obj["style"].string ?: "custom"
    val mode = obj["mode"].string ?: "dark"
    val tokens = parseStringMap(obj["tokens"].obj)
    val swatch = obj["swatch"].array?.mapNotNull { it.string } ?: emptyList()
    val mobileOverrides = parseStringMap(obj["mobileOverrides"].obj)
    return ImportedThemeItem(
        id = id,
        name = name,
        style = style,
        mode = mode,
        tokens = tokens,
        swatch = swatch,
        mobileOverrides = mobileOverrides,
    )
}

enum class ThemeUploadKind(val wireSource: String) {
    VSIX("upload"),
    JSON("upload"),
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

private const val MAX_VSIX_TOTAL_UNCOMPRESSED = 50L * 1024L * 1024L
private const val MAX_VSIX_ENTRIES = 500
private const val MAX_VSIX_ENTRY_BYTES = 5 * 1024 * 1024

data class ExtractedVsixTheme(
    val label: String,
    val uiTheme: String? = null,
    val jsonText: String,
)

data class ThemeImportUploadRequest(
    val source: String,
    val value: String,
    val name: String? = null,
)

fun isUnsafeVsixEntryName(name: String): Boolean {
    if (name.contains("..")) return true
    if (name.startsWith("/") || name.startsWith("\\")) return true
    if (Regex("^[a-zA-Z]:[/\\\\]").containsMatchIn(name)) return true
    return false
}

private fun resolveVsixThemeEntryName(themePath: String): String? {
    val p = themePath.replace("\\", "/").removePrefix("./")
    if (isUnsafeVsixEntryName(p)) return null
    val full = if (p.startsWith("extension/")) p else "extension/$p"
    if (full.split("/").any { it == ".." }) return null
    return full
}

/**
 * Port of `src/services/theme-import/unzip-vsix.ts`: extracts theme JSON documents from a `.vsix`
 * archive so each theme can be sent to `POST /api/settings/themes` with `source = "upload"` (which
 * expects a JSON theme string on the server).
 */
fun extractVsixThemes(bytes: ByteArray): List<ExtractedVsixTheme> {
    val entries = LinkedHashMap<String, ByteArray>()
    var entryCount = 0
    var totalUncompressed = 0L

    ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
        while (true) {
            val entry = zis.nextEntry ?: break
            entryCount++
            if (entryCount > MAX_VSIX_ENTRIES) {
                throw IllegalArgumentException("Archive has too many entries")
            }
            val name = entry.name
            if (isUnsafeVsixEntryName(name)) {
                throw IllegalArgumentException("Unsafe entry path: $name")
            }
            if (!entry.isDirectory) {
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var entryBytes = 0
                while (true) {
                    val read = zis.read(buf)
                    if (read < 0) break
                    entryBytes += read
                    totalUncompressed += read
                    if (totalUncompressed > MAX_VSIX_TOTAL_UNCOMPRESSED) {
                        throw IllegalArgumentException("Archive too large when decompressed")
                    }
                    if (entryBytes <= MAX_VSIX_ENTRY_BYTES) {
                        out.write(buf, 0, read)
                    }
                }
                if (entryBytes <= MAX_VSIX_ENTRY_BYTES) {
                    entries[name] = out.toByteArray()
                }
            }
            zis.closeEntry()
        }
    }

    val pkgBytes = entries["extension/package.json"]
        ?: throw IllegalArgumentException("Not a valid extension: missing extension/package.json")
    val pkgObj = WireJson.parseOrNull(pkgBytes.toString(Charsets.UTF_8)).obj
        ?: throw IllegalArgumentException("Invalid extension/package.json")
    val contributed = pkgObj["contributes"].obj?.get("themes").array
    if (contributed.isNullOrEmpty()) {
        throw IllegalArgumentException("Extension contributes no themes")
    }

    val out = mutableListOf<ExtractedVsixTheme>()
    for (item in contributed) {
        val t = item.obj ?: continue
        val path = t["path"].string ?: continue
        val entryName = resolveVsixThemeEntryName(path) ?: continue
        val themeBytes = entries[entryName] ?: continue
        val text = themeBytes.toString(Charsets.UTF_8)
        if (WireJson.parseOrNull(text).obj != null) {
            out.add(
                ExtractedVsixTheme(
                    label = t["label"].string ?: "Imported Theme",
                    uiTheme = t["uiTheme"].string,
                    jsonText = text,
                ),
            )
        }
    }
    if (out.isEmpty()) {
        throw IllegalArgumentException("No readable themes found in extension")
    }
    return out
}

fun buildThemeFileUploadRequests(
    fileName: String,
    bytes: ByteArray,
    nameHint: String? = null,
): List<ThemeImportUploadRequest> {
    val cleanHint = nameHint?.trim()?.takeIf { it.isNotEmpty() }
    return when (detectThemeUploadKind(fileName)) {
        ThemeUploadKind.VSIX -> {
            extractVsixThemes(bytes).map { item ->
                ThemeImportUploadRequest(
                    source = "upload",
                    value = item.jsonText,
                    name = if (cleanHint != null) "$cleanHint ${item.label}" else item.label,
                )
            }
        }
        ThemeUploadKind.JSON -> {
            val defaultName = fileName.replace(Regex("\\.jsonc?$", RegexOption.IGNORE_CASE), "")
            listOf(
                ThemeImportUploadRequest(
                    source = "upload",
                    value = bytes.toString(Charsets.UTF_8),
                    name = cleanHint ?: defaultName,
                ),
            )
        }
        ThemeUploadKind.UNSUPPORTED -> {
            throw IllegalArgumentException("Unsupported file type. Choose a .json, .jsonc, or .vsix file.")
        }
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
    val installable: Boolean = false,
    val installWith: String? = null,
    val removable: Boolean = false,
    val alsoRemoves: List<String> = emptyList(),
) {
    val canInstall: Boolean get() = installable
}

data class LspRunningRow(
    val serverId: String,
    val rootPath: String,
    val state: String = "ready",
    val subscribers: Int = 0,
    val key: String = "",
) {
    val id: String get() = serverId
    val ready: Boolean get() = state == "ready"
}

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
            installable = s["installable"].bool ?: false,
            installWith = s["installWith"].string,
            removable = s["removable"].bool ?: false,
            alsoRemoves = s["alsoRemoves"].array?.mapNotNull { it.string } ?: emptyList(),
        )
    }
    val running = obj["running"].array.orEmpty().mapNotNull { item ->
        val r = item.obj ?: return@mapNotNull null
        val serverId = r["serverId"].string ?: return@mapNotNull null
        LspRunningRow(
            serverId = serverId,
            rootPath = r["rootPath"].string ?: "",
            state = r["state"].string ?: "ready",
            subscribers = r["subscribers"].int ?: 0,
            key = r["key"].string ?: "",
        )
    }
    return LspServersOverview(
        servers = servers,
        installDir = obj["installDir"].string ?: "",
        running = running,
    )
}

fun lspOriginLabel(origin: String?, installDir: String = ""): String = when (origin) {
    "project" -> "In project"
    "path" -> "On PATH"
    "bundled" -> "Bundled"
    else -> "Installed"
}

// ===========================================================================
// 9. Database Drivers (Phase 12a.3)
// ===========================================================================

data class DbDriverInstalledInfo(
    val version: String,
    val installedAt: String,
    val bytes: Long,
    val sha256: String = "",
)

data class DbDriverRow(
    val id: String,
    val displayName: String,
    val engines: List<String>,
    val usedFor: String = "",
    val pkg: String,
    val version: String,
    val license: String = "",
    val homepage: String = "",
    val state: String, // "missing" | "installed" | "outdated"
    val installed: DbDriverInstalledInfo? = null,
    val installing: Boolean = false,
    val removing: Boolean = false,
) {
    val dbTypes: List<String> get() = engines
    val npmPackage: String get() = pkg
    val sizeBytes: Long? get() = installed?.bytes
}

fun parseDbDrivers(element: JsonElement?): List<DbDriverRow> {
    val arr = element.array ?: return emptyList()
    return arr.mapNotNull(::parseDbDriverRow)
}

fun parseDbDriverRow(element: JsonElement?): DbDriverRow? {
    val obj = element.obj ?: return null
    val id = obj["id"].string ?: return null
    val installedObj = obj["installed"].obj
    val installedInfo = if (installedObj != null) {
        DbDriverInstalledInfo(
            version = installedObj["version"].string ?: "",
            installedAt = installedObj["installedAt"].string ?: "",
            bytes = installedObj["bytes"].long ?: 0L,
            sha256 = installedObj["sha256"].string ?: "",
        )
    } else {
        null
    }
    return DbDriverRow(
        id = id,
        displayName = obj["displayName"].string ?: id,
        engines = obj["engines"].array?.mapNotNull { it.string } ?: emptyList(),
        usedFor = obj["usedFor"].string ?: "",
        pkg = obj["package"].string ?: "",
        version = obj["version"].string ?: "",
        license = obj["license"].string ?: "",
        homepage = obj["homepage"].string ?: "",
        state = obj["state"].string ?: "missing",
        installed = installedInfo,
        installing = obj["installing"].bool ?: false,
        removing = obj["removing"].bool ?: false,
    )
}

fun hasBusyDbDriver(drivers: List<DbDriverRow>): Boolean =
    drivers.any { it.installing || it.removing }

fun formatDriverBytes(bytes: Long): String {
    if (bytes >= 1024L * 1024L) {
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
    return "${maxOf(1L, Math.round(bytes / 1024.0))} KB"
}

// ===========================================================================
// 10. Remote Desktop WebRTC Relay (Phase 12a.3)
// ===========================================================================

data class RelayInstallJobState(
    val receivedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val done: Boolean = false,
    val error: String? = null,
)

data class RelayStatusState(
    val installed: Boolean = false,
    val path: String? = null,
    val source: String? = null, // "bundled" | "system" | null
    val pinnedVersion: String = "",
    val available: Boolean = true,
    val job: RelayInstallJobState? = null,
) {
    val isReady: Boolean get() = installed
    val isDownloading: Boolean get() = job != null && !job.done
    val canUninstall: Boolean get() = installed && source == "bundled"
    val progressPercent: Int?
        get() = if (job != null && job.totalBytes > 0L) {
            Math.round((job.receivedBytes.toDouble() / job.totalBytes.toDouble()) * 100.0).toInt()
        } else {
            null
        }
    val error: String? get() = job?.error
}

fun parseRelayStatus(element: JsonElement?): RelayStatusState {
    val obj = element.obj ?: return RelayStatusState()
    val jobObj = obj["job"].obj
    val job = if (jobObj != null) {
        RelayInstallJobState(
            receivedBytes = jobObj["receivedBytes"].long ?: 0L,
            totalBytes = jobObj["totalBytes"].long ?: 0L,
            done = jobObj["done"].bool ?: false,
            error = jobObj["error"].string,
        )
    } else {
        null
    }
    return RelayStatusState(
        installed = obj["installed"].bool ?: false,
        path = obj["path"].string,
        source = obj["source"].string,
        pinnedVersion = obj["pinnedVersion"].string ?: "",
        available = obj["available"].bool ?: true,
        job = job,
    )
}
