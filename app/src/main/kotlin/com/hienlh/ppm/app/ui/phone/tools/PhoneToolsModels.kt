package com.hienlh.ppm.app.ui.phone.tools

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hienlh.ppm.state.logs.LogEntry
import com.hienlh.ppm.state.logs.LogFilter
import com.hienlh.ppm.state.logs.compileLogSearch
import com.hienlh.ppm.state.logs.matchesLogFilter
import com.hienlh.ppm.state.settings.CloudDeviceCodeFlowState
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
    BROWSER("browser", "Browser"),
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
) {
    var source: String by mutableStateOf(initialSource)
    var follow: Boolean by mutableStateOf(initialFollow)
    var search: String by mutableStateOf("")
    var caseSensitive: Boolean by mutableStateOf(false)
    var regex: Boolean by mutableStateOf(false)

    var filtersSheetOpen: Boolean by mutableStateOf(false)
    var levels: Set<String> by mutableStateOf(setOf("error", "warn", "info", "debug"))
    var chatFilter: String by mutableStateOf("All chats")
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
            if (chatFilter != "All chats") count++
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
        chatFilter = "All chats"
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

    fun filterEntries(entries: List<LogEntry>): List<LogEntry> {
        val filter = LogFilter(
            src = source,
            levels = listOf("error", "warn", "info", "debug").associateWith { it in levels },
            q = search,
            regex = regex,
            caseSensitive = caseSensitive,
        )
        val compiled = compileLogSearch(filter)
        return entries.filter { entry ->
            matchesLogFilter(entry, filter, compiled)
        }
    }
}

/**
 * State holder for the Phone Port Forwarding screen (Requirement 14.2, Phone 7 Section 3).
 *
 * - Forwards a port over Tailscale or Cloudflare from the bottom action card.
 * - Shows `"Set up Tailscale or Cloudflare on a tablet."` when a transport is not configured
 *   instead of opening any setup wizard on phone (`canConfigureTransportOnPhone == false`).
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

    val canConfigureTransportOnPhone: Boolean = false
    val tabletSetupNoticeText: String = "Set up Tailscale or Cloudflare on a tablet."
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

    fun shouldShowTabletSetupNotice(
        tailscaleReady: Boolean,
        cloudflareReady: Boolean,
    ): Boolean = !tailscaleReady || !cloudflareReady

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
 * - Forbids script editing and schedule creation/deletion on phone
 *   (`canEditScript == false`, `canCreateOrDeleteSchedule == false`), showing
 *   `"Edit a script or add a schedule on a tablet."`.
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
    val canEditScript: Boolean = false
    val canCreateOrDeleteSchedule: Boolean = false
    val tabletNoticeText: String = "Edit a script or add a schedule on a tablet."

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
 * State holder for the Phone Cloud & Share drawer tile and bottom sheet
 * (Requirement 14.2, Phone 7 Section 5).
 */
@Stable
class PhoneCloudShareState(
    initialLoggedIn: Boolean = true,
    initialEmail: String? = "hienlh@ppm.local",
    initialLinked: Boolean = true,
    initialDeviceName: String? = "workstation-01",
    initialDeviceStatus: String = "online",
    initialSlug: String = "workstation-01",
    initialLocalUrl: String = "http://192.168.1.42:3210",
    initialTunnelActive: Boolean = true,
    initialPublicUrl: String? = "https://ppm.cloud/workstation-01",
    initialTempUrl: String? = "https://bold-meadow-4291.trycloudflare.com",
) {
    var loggedIn: Boolean by mutableStateOf(initialLoggedIn)
    var email: String? by mutableStateOf(initialEmail)
    var deviceCodeFlow: CloudDeviceCodeFlowState? by mutableStateOf(null)
    var confirmSignOut: Boolean by mutableStateOf(false)

    var linked: Boolean by mutableStateOf(initialLinked)
    var deviceName: String? by mutableStateOf(initialDeviceName)
    var deviceStatus: String by mutableStateOf(initialDeviceStatus)
    var confirmUnlink: Boolean by mutableStateOf(false)

    var slug: String by mutableStateOf(initialSlug)
    var editingSlug: Boolean by mutableStateOf(false)
    var slugDraft: String by mutableStateOf(initialSlug)

    var localUrl: String by mutableStateOf(initialLocalUrl)
    var tunnelActive: Boolean by mutableStateOf(initialTunnelActive)
    var publicUrl: String? by mutableStateOf(initialPublicUrl)
    var tempUrl: String? by mutableStateOf(initialTempUrl)

    val slugValidationMessage: String = "2–32 characters: lowercase letters, numbers, hyphens."

    val isSlugDraftValid: Boolean
        get() = isValidSlug(slugDraft)

    val drawerSubtitle: String
        get() = when {
            tunnelActive && !publicUrl.isNullOrBlank() ->
                publicUrl!!.removePrefix("https://").removePrefix("http://")
            tunnelActive -> "Public link active"
            else -> "Not sharing"
        }

    fun startEditSlug() {
        slugDraft = slug
        editingSlug = true
    }

    fun cancelEditSlug() {
        slugDraft = slug
        editingSlug = false
    }

    fun saveSlug(): Boolean {
        val cleaned = slugDraft.trim()
        if (!isValidSlug(cleaned)) return false
        slug = cleaned
        publicUrl = "https://ppm.cloud/$cleaned"
        editingSlug = false
        return true
    }

    fun startSharing() {
        tunnelActive = true
        if (publicUrl.isNullOrBlank()) {
            publicUrl = "https://ppm.cloud/$slug"
        }
    }

    fun stopSharing() {
        tunnelActive = false
    }

    companion object {
        private val SLUG_REGEX = Regex("^[a-z0-9-]{2,32}$")

        fun isValidSlug(candidate: String): Boolean = SLUG_REGEX.matches(candidate.trim())
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
