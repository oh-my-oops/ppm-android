package com.hienlh.ppm.app.ui.phone.tools

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hienlh.ppm.state.logs.LogEntry
import com.hienlh.ppm.state.logs.LogFilter
import com.hienlh.ppm.state.logs.compileLogSearch
import com.hienlh.ppm.state.logs.matchesLogFilter
import com.hienlh.ppm.state.settings.ScheduleRowState
import com.hienlh.ppm.state.sysmon.AppRowData
import com.hienlh.ppm.state.sysmon.AppRowsModel
import com.hienlh.ppm.state.sysmon.DeviceEntry
import com.hienlh.ppm.state.sysmon.HardwareInventory
import com.hienlh.ppm.state.sysmon.MetricsSnapshot
import com.hienlh.ppm.state.sysmon.PerformanceDevices
import com.hienlh.ppm.state.sysmon.ProcessGroup
import com.hienlh.ppm.state.sysmon.ProcessInfo
import com.hienlh.ppm.state.sysmon.ProcessTableModel
import com.hienlh.ppm.state.sysmon.ServiceInfo
import com.hienlh.ppm.state.sysmon.ServicesModel
import com.hienlh.ppm.state.sysmon.ServicesSnapshot
import com.hienlh.ppm.state.sysmon.TableRow
import com.hienlh.ppm.state.tunnels.TunnelEntry

/**
 * Threshold severity for System Monitor gauges and progress bars
 * (`designs/ppm-mobile-tablet-redesign/phone/7-tools/index.html` Section 1:
 * `> 50%` turns amber/warning, `> 80%` turns red/critical).
 */
enum class MonitorMetricLevel {
    NORMAL,
    WARNING,
    CRITICAL,
    ;

    companion object {
        fun fromPercent(percent: Double): MonitorMetricLevel = when {
            percent > 80.0 -> CRITICAL
            percent > 50.0 -> WARNING
            else -> NORMAL
        }
    }
}

/**
 * The 5 sub-tabs of System Monitor on phone and tablet.
 */
enum class PhoneMonitorTab(val id: String, val label: String) {
    OVERVIEW("overview", "Overview"),
    PERFORMANCE("performance", "Performance"),
    APPS("apps", "Apps"),
    PROCESSES("processes", "Processes"),
    SERVICES("services", "Services"),
}

enum class PhoneAppSort(val id: String, val label: String) {
    CPU("cpu", "CPU"),
    RAM("ram", "RAM"),
    NAME("name", "Name"),
}

enum class PhoneProcessMode(val id: String, val label: String) {
    GROUPED("grouped", "Grouped"),
    FLAT("flat", "Flat"),
    PPM_ONLY("ppm", "PPM only"),
}

enum class PhoneServiceScope(val id: String, val label: String) {
    SYSTEM("system", "System"),
    USER("user", "User"),
}

enum class PhoneServiceFilter(val id: String, val label: String) {
    ALL("all", "All"),
    RUNNING("running", "Running"),
    FAILED("failed", "Failed"),
    ENABLED("enabled", "Enabled"),
}

/**
 * State holder for the Phone look-only System Monitor (Requirement 14.1).
 *
 * Enforces read-only invariants on phone:
 * - No process kill or signal actions (`canKillProcess == false`, `canSendSignal == false`)
 * - No app End task actions (`canEndAppTask == false`)
 * - No service start/stop/restart/enable actions (`canControlService == false`)
 * - Performance tab drills in from the device list to a single device chart + stats with `‹ Devices` back navigation.
 */
@Stable
class PhoneMonitorState(
    initialTab: PhoneMonitorTab = PhoneMonitorTab.OVERVIEW,
    initialSelectedDeviceKey: String? = null,
) {
    var activeTab: PhoneMonitorTab by mutableStateOf(initialTab)
    var selectedDeviceKey: String? by mutableStateOf(initialSelectedDeviceKey)

    var appSearch: String by mutableStateOf("")
    var appSort: PhoneAppSort by mutableStateOf(PhoneAppSort.CPU)

    var processSearch: String by mutableStateOf("")
    var processMode: PhoneProcessMode by mutableStateOf(PhoneProcessMode.GROUPED)
    var expandedGroups: Set<String> by mutableStateOf(emptySet())

    var serviceScope: PhoneServiceScope by mutableStateOf(PhoneServiceScope.SYSTEM)
    var serviceFilter: PhoneServiceFilter by mutableStateOf(PhoneServiceFilter.ALL)
    var serviceSearch: String by mutableStateOf("")
    var selectedServiceForDetail: ServiceInfo? by mutableStateOf(null)

    val canKillProcess: Boolean = false
    val canSendSignal: Boolean = false
    val canEndAppTask: Boolean = false
    val canControlService: Boolean = false

    val isDrilledIntoDevice: Boolean
        get() = activeTab == PhoneMonitorTab.PERFORMANCE && selectedDeviceKey != null

    fun selectTab(tab: PhoneMonitorTab) {
        activeTab = tab
    }

    fun openDeviceDetail(deviceKey: String) {
        selectedDeviceKey = deviceKey
    }

    /** Returns `true` if a device detail drill-in was popped back to the device list. */
    fun backFromDeviceDetail(): Boolean {
        if (selectedDeviceKey != null) {
            selectedDeviceKey = null
            return true
        }
        return false
    }

    fun toggleProcessGroup(groupKey: String) {
        expandedGroups = if (groupKey in expandedGroups) {
            expandedGroups - groupKey
        } else {
            expandedGroups + groupKey
        }
    }

    fun filteredApps(snapshot: MetricsSnapshot?): List<AppRowData> {
        if (snapshot == null) return emptyList()
        val raw = AppRowsModel.buildAppRows(snapshot.apps.orEmpty(), snapshot.processes)
        val filtered = AppRowsModel.filterAppRows(raw, appSearch)
        return AppRowsModel.sortAppRows(
            rows = filtered,
            key = appSort.id,
            dir = if (appSort == PhoneAppSort.NAME) "asc" else "desc",
        )
    }

    fun buildProcessRows(snapshot: MetricsSnapshot?): List<TableRow> {
        if (snapshot == null) return emptyList()
        val res = ProcessTableModel.buildRows(
            processes = snapshot.processes,
            groups = snapshot.groups,
            mode = if (processMode == PhoneProcessMode.FLAT) "flat" else "grouped",
            ppmOnly = processMode == PhoneProcessMode.PPM_ONLY,
            query = processSearch,
            sortKey = "cpu",
            sortDir = "desc",
            expanded = expandedGroups,
        )
        return res.rows
    }

    fun filteredServices(snapshot: ServicesSnapshot?): List<ServiceInfo> {
        val list = snapshot?.services.orEmpty()
        return ServicesModel.shapeServices(
            services = list,
            scope = serviceScope.id,
            filter = serviceFilter.id,
            query = serviceSearch,
        )
    }

    fun performanceDevices(
        snapshot: MetricsSnapshot?,
        inventory: HardwareInventory? = null,
    ): List<DeviceEntry> {
        val system = snapshot?.system ?: return emptyList()
        return PerformanceDevices.buildDeviceList(system, inventory)
    }
}

/**
 * Source filter chip definition for Phone Logs (Phone 7 Section 2).
 */
enum class PhoneLogSourceChip(val id: String, val label: String) {
    ALL("all", "All"),
    AI("ai", "AI & chat"),
    SHELL("shell", "Shell & tools"),
    SERVER("server", "Server"),
    TUNNEL("tunnel", "Tunnel"),
    /** This app's own lines, kept on the device, in place of the web's "Browser". */
    DEVICE(com.hienlh.ppm.state.logs.DEVICE_LOG_SOURCE, com.hienlh.ppm.state.logs.DEVICE_LOG_LABEL),
}

/**
 * State holder for the Phone look-only Logs screen (Requirement 14.1).
 *
 * Phone Logs is look-only:
 * - Opens directly on the live tail (`hasIssuesTab == false`, `hasReportTab == false`)
 * - No line selection or `Ask AI` (`canSelectLines == false`, `canAskAi == false`)
 * - Supports source chips, `Pause`/`Follow`, search with `Aa` and `.*`, and the `Filters` bottom sheet.
 */
@Stable
class PhoneLogsState(
    initialSource: String = "all",
    initialFollow: Boolean = true,
    initialChat: String? = null,
) {
    var source: String by mutableStateOf(initialSource)
    var follow: Boolean by mutableStateOf(initialFollow)
    var search: String by mutableStateOf("")
    var caseSensitive: Boolean by mutableStateOf(false)
    var regex: Boolean by mutableStateOf(false)

    var filtersSheetOpen: Boolean by mutableStateOf(false)
    var levels: Set<String> by mutableStateOf(setOf("error", "warn", "info", "debug"))
    /** Session id whose lines alone are shown (`logsChat`, the web's chat menu); null for every chat. */
    var chat: String? by mutableStateOf(initialChat)
    var timeRange: String by mutableStateOf("1h")
    var showUtc: Boolean by mutableStateOf(false)
    var expandedMoreIds: Set<String> by mutableStateOf(emptySet())

    val hasIssuesTab: Boolean = false
    val hasReportTab: Boolean = false
    val canSelectLines: Boolean = false
    val canAskAi: Boolean = false

    val activeFilterBadgeCount: Int
        get() {
            var count = 0
            if (levels.size < 4) count++
            if (chat != null) count++
            if (timeRange != "1h") count++
            if (showUtc) count++
            return count
        }

    fun toggleFollow() {
        follow = !follow
    }

    fun toggleLevel(level: String) {
        val norm = level.lowercase()
        levels = if (norm in levels) {
            if (levels.size > 1) levels - norm else levels
        } else {
            levels + norm
        }
    }

    fun resetFilters() {
        levels = setOf("error", "warn", "info", "debug")
        chat = null
        timeRange = "1h"
        showUtc = false
    }

    fun toggleExpandedMore(entryId: String) {
        expandedMoreIds = if (entryId in expandedMoreIds) {
            expandedMoreIds - entryId
        } else {
            expandedMoreIds + entryId
        }
    }

    /** The filter the server is asked with (`/api/logs?…`), the same one the web's logs pane sends. */
    fun toLogFilter(): LogFilter = LogFilter(
        src = source,
        levels = listOf("error", "warn", "info", "debug").associateWith { it in levels },
        q = search,
        regex = regex,
        caseSensitive = caseSensitive,
        chat = chat,
    )

    fun filterEntries(entries: List<LogEntry>, titles: Map<String, String> = emptyMap()): List<LogEntry> {
        val filter = toLogFilter()
        val compiled = compileLogSearch(filter)
        return entries.filter { entry ->
            matchesLogFilter(entry, filter, compiled, titles)
        }
    }
}

/**
 * State holder for the Phone Port Forwarding screen (Requirement 14.2, Phone 7 Section 3).
 *
 * - Forwards a port over Tailscale or Cloudflare from the bottom action card.
 * - Each transport's row opens its setup in Settings → Remote Access (Set up, or Manage once done).
 * - Confirms before stopping a tunnel started outside PPM (`"Stop a tunnel PPM did not start?"`).
 */
@Stable
class PhonePortForwardingState(
    initialTransport: String = "tailscale",
    initialPortInput: String = "",
) {
    var transport: String by mutableStateOf(initialTransport)
    var portInput: String by mutableStateOf(initialPortInput)
    var confirmStopExternalTunnel: TunnelEntry? by mutableStateOf(null)

    val externalStopConfirmTitle: String = "Stop a tunnel PPM did not start?"

    val parsedPort: Int?
        get() = portInput.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    val isPortValid: Boolean
        get() = parsedPort != null

    val helperText: String
        get() = if (transport == "tailscale") {
            "Private: only devices in your tailnet can open it."
        } else {
            "Public: anyone with the link can open it."
        }

    fun canSubmitForward(
        tailscaleReady: Boolean,
        cloudflareReady: Boolean,
        starting: Boolean = false,
    ): Boolean {
        if (starting || parsedPort == null) return false
        return if (transport == "tailscale") tailscaleReady else true
    }

    fun requestStopTunnel(entry: TunnelEntry, onStopConfirmed: (TunnelEntry) -> Unit) {
        if (entry.source == "external") {
            confirmStopExternalTunnel = entry
        } else {
            onStopConfirmed(entry)
        }
    }

    fun confirmExternalStop(onStopConfirmed: (TunnelEntry) -> Unit) {
        val target = confirmStopExternalTunnel ?: return
        confirmStopExternalTunnel = null
        onStopConfirmed(target)
    }

    fun dismissExternalStop() {
        confirmStopExternalTunnel = null
    }
}

/**
 * State holder for the Phone Schedules page under Settings → Schedules
 * (Requirement 14.3, Phone 7 Section 4).
 *
 * - Allows enabling/disabling schedules, running/restarting immediately, and reading the full log.
 * - Keeps primary actions (`Full log`, `Run now` / `Restart`) in the bottom action card.
 * - New, Import, Edit (a script or a prompt) and Delete open the web's schedule form in a sheet.
 */
@Stable
class PhoneSchedulesState(
    initialSchedules: List<ScheduleRowState> = emptyList(),
    initialSelectedId: Int? = initialSchedules.firstOrNull()?.id,
) {
    var schedules: List<ScheduleRowState> by mutableStateOf(initialSchedules)
    var selectedScheduleId: Int? by mutableStateOf(initialSelectedId)
    var expandedScheduleId: Int? by mutableStateOf(initialSelectedId)
    var viewingFullLogScheduleId: Int? by mutableStateOf(null)

    val settingsBreadcrumb: String = "Settings > Schedules"

    val selectedSchedule: ScheduleRowState?
        get() = schedules.firstOrNull { it.id == selectedScheduleId } ?: schedules.firstOrNull()

    val fullLogSchedule: ScheduleRowState?
        get() = schedules.firstOrNull { it.id == viewingFullLogScheduleId }

    val isViewingFullLog: Boolean
        get() = viewingFullLogScheduleId != null

    fun primaryActionLabelFor(schedule: ScheduleRowState?): String {
        if (schedule == null) return "Run now"
        val isRunningOrAlways = schedule.status.state.equals("running", ignoreCase = true) ||
            schedule.mode.equals("always-on", ignoreCase = true) ||
            schedule.cronExpr.equals("always", ignoreCase = true)
        return if (isRunningOrAlways) "Restart" else "Run now"
    }

    fun selectAndToggleExpand(scheduleId: Int) {
        selectedScheduleId = scheduleId
        expandedScheduleId = if (expandedScheduleId == scheduleId) null else scheduleId
    }

    fun toggleScheduleEnabled(scheduleId: Int, enabled: Boolean) {
        schedules = schedules.map { row ->
            if (row.id == scheduleId) row.copy(enabled = enabled) else row
        }
    }

    fun openFullLog(scheduleId: Int? = selectedSchedule?.id) {
        val targetId = scheduleId ?: return
        selectedScheduleId = targetId
        viewingFullLogScheduleId = targetId
    }

    /** Returns `true` if the full log sub-page was open and has been closed back to the list. */
    fun closeFullLog(): Boolean {
        if (viewingFullLogScheduleId != null) {
            viewingFullLogScheduleId = null
            return true
        }
        return false
    }
}

/**
 * Tool entry in the Tablet 7 Tools rail popover (`designs/ppm-mobile-tablet-redesign/tablet/7-tools/index.html` Section 1).
 *
 * Per Requirement 14.4, `Remote Desktop` and `Logs` open as workspace tabs (`opensAsTab == true`).
 */
data class TabletToolEntry(
    val id: String,
    val label: String,
    val auxBadge: String = "",
    val tabType: String,
    val opensAsTab: Boolean = true,
)

/**
 * State holder for the Cloudflare 4-step `Fixed address with Cloudflare` setup dialog on Tablet
 * (Tablet 7 Section 4).
 */
@Stable
class CloudflareFourStepState(
    initialDomain: String = "example.com",
    initialSubdomainPrefix: String = "ppm",
    initialPrereqChecked: Boolean = true,
    initialSignedIn: Boolean = false,
    initialZoneConfirmed: Boolean = false,
) {
    var prereqChecked: Boolean by mutableStateOf(initialPrereqChecked)
    var signedIn: Boolean by mutableStateOf(initialSignedIn)
    var authUrl: String? by mutableStateOf(null)
    var zoneConfirmed: Boolean by mutableStateOf(initialZoneConfirmed)
    var domain: String by mutableStateOf(initialDomain)
    var subdomainPrefix: String by mutableStateOf(initialSubdomainPrefix)

    val fullHostname: String
        get() {
            val prefix = subdomainPrefix.trim().trimEnd('.')
            val cleanDomain = domain.trim().trimStart('.')
            return if (prefix.isEmpty()) cleanDomain else "$prefix.$cleanDomain"
        }

    val canSave: Boolean
        get() = prereqChecked && signedIn && zoneConfirmed &&
            subdomainPrefix.trim().isNotEmpty() && domain.trim().isNotEmpty()
}

/**
 * State holder for the Tablet Schedules split workspace (Requirement 14.4, Tablet 7 Section 10).
 *
 * Unlike Phone Schedules, Tablet Schedules provides a full script/prompt editor, schedule creation,
 * import/export, recent runs list, and live log stream.
 */
@Stable
class TabletSchedulesWorkspaceState(
    initialSchedules: List<ScheduleRowState> = emptyList(),
    initialSelectedId: Int? = initialSchedules.firstOrNull()?.id,
) {
    var schedules: List<ScheduleRowState> by mutableStateOf(initialSchedules)
    var selectedScheduleId: Int? by mutableStateOf(initialSelectedId)
    var kindFilter: String by mutableStateOf("all")
    var searchQuery: String by mutableStateOf("")
    var scriptDraft: String by mutableStateOf(
        initialSchedules.firstOrNull { it.id == initialSelectedId }?.let { it.script ?: it.prompt }.orEmpty(),
    )
    var cronDraft: String by mutableStateOf(
        initialSchedules.firstOrNull { it.id == initialSelectedId }?.cronExpr.orEmpty(),
    )

    val canEditScript: Boolean = true

    val filteredSchedules: List<ScheduleRowState>
        get() = schedules.filter { row ->
            val isAlways = row.mode.equals("always-on", ignoreCase = true) ||
                row.cronExpr.equals("always", ignoreCase = true)
            val kindMatch = when (kindFilter) {
                "prompt" -> row.kind.equals("prompt", ignoreCase = true) && !isAlways
                "script" -> row.kind.equals("script", ignoreCase = true) && !isAlways
                "always" -> isAlways
                else -> true
            }
            val query = searchQuery.trim()
            val textMatch = query.isEmpty() ||
                row.name.contains(query, ignoreCase = true) ||
                row.prompt.contains(query, ignoreCase = true) ||
                (row.script?.contains(query, ignoreCase = true) == true)
            kindMatch && textMatch
        }

    val selectedSchedule: ScheduleRowState?
        get() = schedules.firstOrNull { it.id == selectedScheduleId } ?: filteredSchedules.firstOrNull()

    fun selectSchedule(id: Int) {
        selectedScheduleId = id
        val target = schedules.firstOrNull { it.id == id }
        if (target != null) {
            scriptDraft = target.script ?: target.prompt
            cronDraft = target.cronExpr
        }
    }

    fun saveSelectedScheduleEdits() {
        val id = selectedScheduleId ?: return
        schedules = schedules.map { row ->
            if (row.id == id) {
                if (row.kind.equals("script", ignoreCase = true)) {
                    row.copy(script = scriptDraft, prompt = scriptDraft, cronExpr = cronDraft)
                } else {
                    row.copy(prompt = scriptDraft, cronExpr = cronDraft)
                }
            } else {
                row
            }
        }
    }
}
