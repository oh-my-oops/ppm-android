package com.hienlh.ppm.app.ui.phone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hienlh.ppm.state.workspace.WorkspaceTab

/**
 * Closed set of workspace and tool areas available on a phone layout
 * (`designs/ppm-mobile-tablet-redesign/foundation/index.html`, Section 4 & Requirement 3.1).
 *
 * Anything outside this enum is a tablet-only feature and opens [OpenOnTablet] when reached on a
 * phone while keeping its tab intact in `WorkspaceStore` (Requirements 3.2, 3.3).
 */
enum class PhoneArea(
    val id: String,
    val title: String,
    val primaryTabType: String,
) {
    CHAT("chat", "Chat", "chat"),
    PROJECTS("projects", "Projects", "projects"),
    FILES("files", "Files", "editor"),
    SOURCE_CONTROL("source-control", "Source Control", "git-diff"),
    TERMINAL("terminal", "Terminal", "terminal"),
    DATABASE("database", "Database", "database"),
    MONITOR("monitor", "Monitor", "system-monitor"),
    LOGS("logs", "Logs", "logs"),
    SCHEDULES("schedules", "Schedules", "schedules"),
    PORT_FORWARDING("ports", "Port forwarding", "ports"),
    CLOUD_AND_SHARE("cloud-share", "Cloud & Share", "cloud-share"),
    SETTINGS("settings", "Settings", "settings"),

    /** Spec mode's specs (the web's sidebar Specs panel), and the one opened from them or from a chat. */
    SPECS("specs", "Specs", "specs"),

    /** PPM Tasks (the web's sidebar Tasks panel); a task picked from it or opened by link shows its page here. */
    TASKS("tasks", "Tasks", "tasks"),
    /** The PPM Assistant's tab, full screen. */
    ASSISTANT("assistant", "PPM Assistant", "assistant"),
    ;

    companion object {
        /** Settings categories supported on a phone (Requirement 3.1). */
        val PHONE_SETTINGS_SECTIONS: Set<String> = setOf(
            "connection",
            "appearance",
            "notifications",
            "about",
        )

        /** Settings categories that require a tablet (Requirement 3.2). */
        val TABLET_ONLY_SETTINGS_SECTIONS: Set<String> = setOf(
            "ai",
            "ai-providers",
            "providers",
            "accounts",
            "ssh",
            "ssh-keys",
            "tunnel",
            "tunnels",
            "backup",
            "db-backup",
        )

        /**
         * Maps a `WorkspaceTab.type` (or synthetic phone area id) to the [PhoneArea] that renders
         * it on a phone, or `null` when the tab type is tablet-only (Requirement 3.1, 3.2).
         */
        fun fromTabType(type: String): PhoneArea? = when (type.lowercase().trim()) {
            "chat" -> CHAT
            "projects" -> PROJECTS
            "editor", "file", "files" -> FILES
            "git-diff", "git-status", "source-control" -> SOURCE_CONTROL
            "terminal" -> TERMINAL
            "database", "sqlite", "db-query", "db-sql" -> DATABASE
            "system-monitor", "monitor" -> MONITOR
            "logs" -> LOGS
            "schedules" -> SCHEDULES
            "ports", "port-forwarding", "web-preview" -> PORT_FORWARDING
            "tunnels", "cloud-share" -> CLOUD_AND_SHARE
            "settings" -> SETTINGS
            "specs" -> SPECS
            "task", "tasks" -> TASKS
            "assistant" -> ASSISTANT
            else -> null
        }

        /**
         * The areas a server in minimal mode offers (`--minimal`): the chat, its files and Settings,
         * as the web keeps only the History, Explorer and Search sections and the chat, editor and
         * settings tabs. Everything else stays hidden rather than open onto a refusal.
         */
        val MINIMAL_AREAS: Set<PhoneArea> = setOf(CHAT, PROJECTS, FILES, SETTINGS)

        /** True when [area] is offered by a server in minimal mode ([minimal]), or always when not. */
        fun isAvailable(area: PhoneArea, minimal: Boolean): Boolean = !minimal || area in MINIMAL_AREAS

        /** A tab minimal mode shows (`isMinimalTab`): chat, settings and editor tabs. */
        fun isMinimalTabType(type: String): Boolean = type == "chat" || type == "settings" || type == "editor"

        /** Maps a [WorkspaceTab] to its [PhoneArea], or `null` if it is a tablet-only tab. */
        fun fromTab(tab: WorkspaceTab): PhoneArea? = fromTabType(tab.type)

        /** Resolves a [PhoneArea] by its drawer/route [id], or `null` if unknown. */
        fun fromId(id: String): PhoneArea? = entries.firstOrNull { it.id == id } ?: fromTabType(id)

        /** True when [type] is supported on a phone layout (Requirement 3.1). */
        fun isPhoneSupportedTabType(type: String): Boolean = fromTabType(type) != null

        /** True when [type] belongs to a tablet-only feature (Requirement 3.2). */
        fun isTabletOnlyTabType(type: String): Boolean = fromTabType(type) == null

        /** True when [tab] is a tablet-only tab that should render [OpenOnTablet] on phone. */
        fun isTabletOnly(tab: WorkspaceTab): Boolean = isTabletOnlyTabType(tab.type)

        /** True when a settings [section] is tablet-only (AI Providers, Accounts, SSH, Tunnel, Backup). */
        fun isTabletOnlySettingsSection(section: String): Boolean {
            val normalized = section.lowercase().trim()
            if (normalized in PHONE_SETTINGS_SECTIONS) return false
            return normalized in TABLET_ONLY_SETTINGS_SECTIONS || normalized.isNotEmpty()
        }
    }
}

/**
 * Identifier for a sheet or modal dialog stacked in [PhoneNav].
 */
sealed interface SheetKey {
    data object TabSwitcher : SheetKey
    data object ProjectSwitcher : SheetKey
    data object Notifications : SheetKey
    data object CloudShare : SheetKey
    data object BranchPicker : SheetKey
    data object FileActions : SheetKey
    data object SessionChanges : SheetKey
    data object AccountAndChat : SheetKey
    data object SwitchAccount : SheetKey
    data object Activity : SheetKey
    data class Custom(val id: String) : SheetKey
}

/**
 * Result of a single [PhoneNav.back] step, following the strict priority order in Requirement 2.5:
 * 1. [ClosedSheet] — closed the topmost open sheet or dialog;
 * 2. [ClosedDrawer] — closed the left navigation drawer;
 * 3. [PoppedTab] — returned to the previously viewed tab;
 * 4. [Exit] — nothing left to close or pop; the host activity may exit.
 */
sealed interface BackResult {
    data class ClosedSheet(val sheet: SheetKey) : BackResult
    data object ClosedDrawer : BackResult
    data class PoppedTab(val tabId: String) : BackResult
    data object Exit : BackResult
}

/**
 * Immutable snapshot of [PhoneNav] state.
 */
data class PhoneNavState(
    val drawerOpen: Boolean = false,
    val sheets: List<SheetKey> = emptyList(),
    val history: List<String> = emptyList(),
    val currentTabId: String? = null,
)

/**
 * State holder for the phone shell's navigation: left drawer state, sheet stack, and viewed tab
 * history (`design.md` — `PhoneNav`).
 *
 * [back] implements the exact 4-step priority order required by Requirement 2.5:
 * 1. Topmost sheet (`sheets.last()`) -> [BackResult.ClosedSheet]
 * 2. Open drawer (`drawerOpen == true`) -> [BackResult.ClosedDrawer]
 * 3. Previous tab in [history] -> [BackResult.PoppedTab]
 * 4. Otherwise -> [BackResult.Exit]
 */
@Stable
class PhoneNav(
    initialTabId: String? = null,
    initialHistory: List<String> = emptyList(),
    initialDrawerOpen: Boolean = false,
    initialSheets: List<SheetKey> = emptyList(),
) {
    var drawerOpen: Boolean by mutableStateOf(initialDrawerOpen)
        private set

    var sheets: List<SheetKey> by mutableStateOf(initialSheets.distinct())
        private set

    var history: List<String> by mutableStateOf(dedupAdjacent(initialHistory, initialTabId))
        private set

    var currentTabId: String? by mutableStateOf(initialTabId)
        private set

    /** The sheet currently on top of the stack, or `null` when no sheet is open. */
    val topSheet: SheetKey?
        get() = sheets.lastOrNull()

    /** Whether [back] will consume the system Back press rather than exiting (`! Exit`). */
    val canHandleBack: Boolean
        get() = sheets.isNotEmpty() || drawerOpen || history.isNotEmpty()

    /** Snapshot of the current navigation state. */
    val state: PhoneNavState
        get() = PhoneNavState(
            drawerOpen = drawerOpen,
            sheets = sheets,
            history = history,
            currentTabId = currentTabId,
        )

    fun openDrawer() {
        drawerOpen = true
    }

    fun closeDrawer() {
        drawerOpen = false
    }

    fun toggleDrawer() {
        drawerOpen = !drawerOpen
    }

    /**
     * Pushes [sheet] onto the top of the sheet stack. If [sheet] was already in the stack, it is
     * moved to the top without duplicating it.
     */
    fun pushSheet(sheet: SheetKey) {
        sheets = sheets.filterNot { it == sheet } + sheet
    }

    /** Alias for [pushSheet]. */
    fun openSheet(sheet: SheetKey) = pushSheet(sheet)

    /** Closes [sheet] if it is currently open in the stack. */
    fun closeSheet(sheet: SheetKey) {
        if (sheet in sheets) {
            sheets = sheets.filterNot { it == sheet }
        }
    }

    /** Pops and returns the topmost sheet, or `null` if no sheet is open. */
    fun popSheet(): SheetKey? {
        val top = sheets.lastOrNull() ?: return null
        sheets = sheets.dropLast(1)
        return top
    }

    /** Closes all open sheets. */
    fun clearSheets() {
        if (sheets.isNotEmpty()) {
            sheets = emptyList()
        }
    }

    /**
     * Records a transition to [tabId]. The previous [currentTabId] (when non-null and different
     * from [tabId]) is pushed onto [history] so [back] returns to it.
     */
    fun visitTab(tabId: String?) {
        if (tabId == null) {
            currentTabId = null
            return
        }
        val previous = currentTabId
        if (previous == tabId) return
        if (previous != null) {
            val nextHistory = if (history.lastOrNull() == previous) history else history + previous
            history = nextHistory
        }
        currentTabId = tabId
    }

    /** Alias for [visitTab]. */
    fun selectTab(tabId: String) = visitTab(tabId)

    /**
     * Removes [tabId] from [history] when the tab is closed, and updates [currentTabId] to
     * [nextActiveTabId] (or the most recent remaining tab in [history]).
     */
    fun onTabClosed(tabId: String, nextActiveTabId: String? = null) {
        val filtered = collapseAdjacentDuplicates(history.filterNot { it == tabId })
        if (currentTabId == tabId) {
            val replacement = nextActiveTabId?.takeIf { it != tabId } ?: filtered.lastOrNull()
            history = if (replacement != null && filtered.lastOrNull() == replacement) {
                filtered.dropLast(1)
            } else {
                filtered
            }
            currentTabId = replacement
        } else {
            history = if (currentTabId != null && filtered.lastOrNull() == currentTabId) {
                filtered.dropLast(1)
            } else {
                filtered
            }
        }
    }

    /**
     * Synchronizes [history] and [currentTabId] with the set of currently open workspace tab IDs.
     * Synthetic phone area IDs (`phone:*`) are preserved while closed workspace tabs are pruned.
     */
    fun syncOpenTabs(openTabIds: Set<String>, activeTabId: String?) {
        val pruned = collapseAdjacentDuplicates(
            history.filter { it.startsWith(SYNTHETIC_AREA_PREFIX) || it in openTabIds },
        )
        val onSyntheticArea = currentTabId?.startsWith(SYNTHETIC_AREA_PREFIX) == true
        if (onSyntheticArea) {
            history = if (pruned.lastOrNull() == currentTabId) pruned.dropLast(1) else pruned
            return
        }
        history = if (activeTabId != null && pruned.lastOrNull() == activeTabId) {
            pruned.dropLast(1)
        } else {
            pruned
        }
        if (activeTabId != null && currentTabId != activeTabId) {
            visitTab(activeTabId)
        } else if (activeTabId == null && currentTabId != null && currentTabId !in openTabIds) {
            currentTabId = history.lastOrNull()
            if (currentTabId != null && history.isNotEmpty()) {
                history = history.dropLast(1)
            }
        }
    }

    /**
     * Handles the system Back button according to Requirement 2.5:
     * 1. Close the topmost sheet if any is open;
     * 2. Close the left drawer if it is open;
     * 3. Return to the previously viewed tab in [history] if any;
     * 4. Otherwise return [BackResult.Exit].
     */
    fun back(): BackResult {
        if (sheets.isNotEmpty()) {
            val top = sheets.last()
            sheets = sheets.dropLast(1)
            return BackResult.ClosedSheet(top)
        }
        if (drawerOpen) {
            drawerOpen = false
            return BackResult.ClosedDrawer
        }
        if (history.isNotEmpty()) {
            val previous = history.last()
            history = history.dropLast(1)
            currentTabId = previous
            return BackResult.PoppedTab(previous)
        }
        return BackResult.Exit
    }

    companion object {
        const val SYNTHETIC_AREA_PREFIX: String = "phone:"

        fun areaTabId(area: PhoneArea): String = "$SYNTHETIC_AREA_PREFIX${area.id}"

        fun parseAreaTabId(tabId: String?): PhoneArea? {
            if (tabId == null || !tabId.startsWith(SYNTHETIC_AREA_PREFIX)) return null
            return PhoneArea.fromId(tabId.removePrefix(SYNTHETIC_AREA_PREFIX))
        }

        private fun dedupAdjacent(items: List<String>, currentTabId: String?): List<String> {
            val collapsed = collapseAdjacentDuplicates(items)
            return if (currentTabId != null && collapsed.lastOrNull() == currentTabId) {
                collapsed.dropLast(1)
            } else {
                collapsed
            }
        }

        private fun collapseAdjacentDuplicates(items: List<String>): List<String> {
            if (items.size <= 1) return items
            val result = ArrayList<String>(items.size)
            for (item in items) {
                if (result.lastOrNull() != item) {
                    result.add(item)
                }
            }
            return result
        }
    }
}

/**
 * Remembers a [PhoneNav] instance across recompositions.
 */
@Composable
fun rememberPhoneNav(initialTabId: String? = null): PhoneNav =
    remember { PhoneNav(initialTabId = initialTabId) }
