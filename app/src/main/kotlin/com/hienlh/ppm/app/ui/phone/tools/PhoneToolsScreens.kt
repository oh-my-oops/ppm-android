package com.hienlh.ppm.app.ui.phone.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.phone.OpenOnTabletNotice
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.ActionPrimaryButton
import com.hienlh.ppm.design.kit.BottomActionCard
import com.hienlh.ppm.design.kit.Chip
import com.hienlh.ppm.design.kit.ConfirmDialogCard
import com.hienlh.ppm.design.kit.ListRow
import com.hienlh.ppm.design.kit.SectionLabel
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.logs.LogChatInfo
import com.hienlh.ppm.state.logs.LogEntry
import com.hienlh.ppm.state.logs.LogRow
import com.hienlh.ppm.state.logs.clockTime
import com.hienlh.ppm.state.logs.foldRepeats
import com.hienlh.ppm.state.logs.restartDividers
import com.hienlh.ppm.state.settings.ScheduleLogLine
import com.hienlh.ppm.state.settings.ScheduleRowState
import com.hienlh.ppm.state.settings.ScheduleRunRow
import com.hienlh.ppm.state.sysmon.MetricsSnapshot
import com.hienlh.ppm.state.sysmon.PerformanceDevices
import com.hienlh.ppm.state.sysmon.ServiceDetails
import com.hienlh.ppm.state.sysmon.ServicesSnapshot
import com.hienlh.ppm.state.sysmon.TableRow
import com.hienlh.ppm.state.tunnels.SetupState
import com.hienlh.ppm.state.tunnels.TunnelEntry
import java.util.Locale

/**
 * Look-only System Monitor for phone (`designs/ppm-mobile-tablet-redesign/phone/7-tools/index.html` Section 1,
 * Requirement 14.1).
 *
 * - 5 sub-tabs (`Overview`, `Performance`, `Apps`, `Processes`, `Services`).
 * - Threshold coloring (`> 50%` amber, `> 80%` red).
 * - Performance tab drills into a device's chart & stats with a `‹ Devices` back row and system Back handler.
 * - No process kill/signal buttons, no app End task buttons, and no service start/stop/restart/enable controls.
 * - Search and filter controls live in a `BottomActionCard` when viewing `Apps`, `Processes`, or `Services`.
 */
@Composable
fun PhoneSystemMonitorScreen(
    state: PhoneMonitorState,
    snapshot: MetricsSnapshot?,
    servicesSnapshot: ServicesSnapshot? = null,
    serviceDetails: ServiceDetails? = null,
    servicesError: String? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current

    BackHandler(enabled = state.isDrilledIntoDevice) {
        state.backFromDeviceDetail()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("phone-sysmon-screen"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 44dp sub-tab strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(tokens.panel.color)
                    .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Bottom)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (tab in PhoneMonitorTab.entries) {
                    val active = state.activeTab == tab
                    val pillShape = RoundedCornerShape(8.dp)
                    Box(
                        modifier = Modifier
                            .testTag("phone-sysmon-tab-${tab.id}")
                            .height(32.dp)
                            .clip(pillShape)
                            .background(if (active) tokens.accentWash.color else Color.Transparent, pillShape)
                            .ppmClickable(
                                shape = pillShape,
                                onClickLabel = tab.label,
                                onClick = { state.selectTab(tab) },
                            )
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            text = tab.label,
                            style = ppmText(
                                size = 13f,
                                weight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            ).copy(color = if (active) tokens.accent.color else tokens.text2.color),
                        )
                    }
                }
            }

            // Tab content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (state.activeTab) {
                    PhoneMonitorTab.OVERVIEW -> {
                        val sys = snapshot?.system
                        val cpuPct = sys?.cpu?.total ?: 0.0
                        val ramPct = sys?.mem?.percent ?: 0.0
                        val diskPct = sys?.disks?.firstOrNull()?.busyPercent ?: 0.0

                        // 2-column metric cards
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PhoneMetricGaugeCard(
                                title = "CPU",
                                valueText = "${cpuPct.toInt()}%",
                                subtitle = sys?.cpu?.model?.ifBlank { "Processor" } ?: "Processor",
                                percent = cpuPct,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("phone-sysmon-card-cpu")
                                    .ppmClickable(onClick = {
                                        state.selectTab(PhoneMonitorTab.PERFORMANCE)
                                        state.openDeviceDetail("cpu")
                                    }),
                            )
                            PhoneMetricGaugeCard(
                                title = "RAM",
                                valueText = "${ramPct.toInt()}%",
                                subtitle = sys?.mem?.let {
                                    "${PerformanceDevices.formatRam(it.usedMB)} / ${PerformanceDevices.formatRam(it.totalMB)}"
                                } ?: "Memory",
                                percent = ramPct,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("phone-sysmon-card-ram")
                                    .ppmClickable(onClick = {
                                        state.selectTab(PhoneMonitorTab.PERFORMANCE)
                                        state.openDeviceDetail("memory")
                                    }),
                            )
                        }

                        PhoneMetricGaugeCard(
                            title = "DISK",
                            valueText = "${diskPct.toInt()}%",
                            subtitle = sys?.disks?.firstOrNull()?.id ?: "/",
                            percent = diskPct,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("phone-sysmon-card-disk"),
                        )

                        SectionLabel(text = "Top Processes (Look-Only)")
                        val topProcs = snapshot?.processes.orEmpty().sortedByDescending { it.cpu }.take(5)
                        for (proc in topProcs) {
                            ListRow(
                                title = proc.name,
                                subtitle = "PID ${proc.pid} · ${PerformanceDevices.formatRam(proc.ramMB)}",
                                subtitleMono = true,
                                meta = String.format(Locale.US, "%.1f%%", proc.cpu),
                                dividerBottom = true,
                                modifier = Modifier.testTag("phone-sysmon-top-proc-${proc.pid}"),
                            )
                        }
                    }

                    PhoneMonitorTab.PERFORMANCE -> {
                        val devices = state.performanceDevices(snapshot)
                        val selectedKey = state.selectedDeviceKey
                        val selectedDevice = devices.firstOrNull { it.key == selectedKey }

                        if (selectedDevice == null) {
                            SectionLabel(text = "Devices")
                            for (dev in devices) {
                                ListRow(
                                    title = dev.label,
                                    subtitle = dev.sublabel.ifBlank { dev.kind },
                                    meta = dev.detail,
                                    showChevron = true,
                                    dividerBottom = true,
                                    onClick = { state.openDeviceDetail(dev.key) },
                                    modifier = Modifier.testTag("phone-sysmon-device-${dev.key}"),
                                )
                            }
                        } else {
                            // Drill-in back row: `‹ Devices`
                            val backShape = RoundedCornerShape(10.dp)
                            Row(
                                modifier = Modifier
                                    .testTag("phone-sysmon-back-devices")
                                    .heightIn(min = 44.dp)
                                    .clip(backShape)
                                    .ppmClickable(
                                        shape = backShape,
                                        onClickLabel = "Devices",
                                        onClick = { state.backFromDeviceDetail() },
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                PpmIcon(
                                    icon = ProductIcon.ChevronLeft,
                                    size = 18.dp,
                                    tint = tokens.accent.color,
                                )
                                BasicText(
                                    text = "‹ Devices",
                                    style = ppmText(14f, FontWeight.SemiBold).copy(color = tokens.accent.color),
                                )
                            }

                            val detailShape = RoundedCornerShape(14.dp)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(detailShape)
                                    .background(tokens.panel.color, detailShape)
                                    .cssBorder(tokens.borderSoft.color, detailShape, width = 1.dp)
                                    .padding(16.dp)
                                    .testTag("phone-sysmon-device-detail-${selectedDevice.key}"),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                BasicText(
                                    text = selectedDevice.label,
                                    style = ppmText(16f, FontWeight.SemiBold).copy(color = tokens.text.color),
                                )
                                BasicText(
                                    text = selectedDevice.sublabel,
                                    style = ppmText(12.5f, FontWeight.Normal).copy(color = tokens.text2.color),
                                )
                                BasicText(
                                    text = selectedDevice.detail,
                                    style = ppmText(22f, FontWeight.Bold, FontKind.MONO).copy(color = tokens.accent.color),
                                )
                            }
                        }
                    }

                    PhoneMonitorTab.APPS -> {
                        val apps = state.filteredApps(snapshot)
                        for (app in apps) {
                            // Look-only: no End task button or destructive menu
                            ListRow(
                                title = app.name,
                                subtitle = "${app.processCount} processes · ${PerformanceDevices.formatRam(app.ramMB)}",
                                meta = String.format(Locale.US, "%.1f%%", app.cpu),
                                dividerBottom = true,
                                modifier = Modifier.testTag("phone-sysmon-app-${app.id}"),
                            )
                        }
                    }

                    PhoneMonitorTab.PROCESSES -> {
                        val rows = state.buildProcessRows(snapshot)
                        for (row in rows) {
                            when (row) {
                                is TableRow.GroupRow -> {
                                    val grp = row.group
                                    // Look-only: no kill button on group or child rows
                                    ListRow(
                                        title = "${grp.label} (${grp.count})",
                                        subtitle = PerformanceDevices.formatRam(grp.ramMB),
                                        meta = String.format(Locale.US, "%.1f%%", grp.cpu),
                                        dividerBottom = true,
                                        onClick = { state.toggleProcessGroup(grp.key) },
                                        modifier = Modifier.testTag("phone-sysmon-proc-group-${grp.key}"),
                                    )
                                }
                                is TableRow.ProcRow -> {
                                    val proc = row.proc
                                    ListRow(
                                        title = proc.name,
                                        subtitle = "PID ${proc.pid} · ${PerformanceDevices.formatRam(proc.ramMB)}",
                                        subtitleMono = true,
                                        meta = String.format(Locale.US, "%.1f%%", proc.cpu),
                                        compact = row.indent,
                                        dividerBottom = true,
                                        modifier = Modifier
                                            .then(if (row.indent) Modifier.padding(start = 16.dp) else Modifier)
                                            .testTag("phone-sysmon-proc-row-${proc.pid}"),
                                    )
                                }
                            }
                        }
                    }

                    PhoneMonitorTab.SERVICES -> {
                        val services = state.filteredServices(servicesSnapshot)
                        val emptyText = when {
                            servicesError != null && servicesSnapshot == null -> servicesError
                            servicesSnapshot == null -> "Loading services…"
                            !servicesSnapshot.supported -> "Services are not available on this host."
                            services.isEmpty() -> "No services match."
                            else -> null
                        }
                        if (emptyText != null) {
                            BasicText(
                                text = emptyText,
                                style = ppmText(13f, FontWeight.Normal).copy(
                                    color = if (servicesError != null && servicesSnapshot == null) tokens.error.color else tokens.text2.color,
                                ),
                                modifier = Modifier
                                    .padding(vertical = 16.dp)
                                    .testTag("phone-sysmon-services-empty"),
                            )
                        }
                        for (svc in services) {
                            // Look-only: tap opens read-only detail sheet; no Start/Stop/Restart buttons
                            ListRow(
                                title = svc.unit,
                                subtitle = svc.description.ifBlank { svc.subState },
                                meta = svc.activeState,
                                dividerBottom = true,
                                onClick = { state.selectedServiceForDetail = svc },
                                modifier = Modifier.testTag("phone-sysmon-service-${svc.unit}"),
                            )
                        }
                    }
                }
            }

            // Bottom filter/search bar for Apps, Processes, and Services
            if (state.activeTab in setOf(PhoneMonitorTab.APPS, PhoneMonitorTab.PROCESSES, PhoneMonitorTab.SERVICES)) {
                BottomActionCard(
                    modifier = Modifier.testTag("phone-sysmon-bottom-bar"),
                    applyWindowInsets = false,
                    chipsContent = {
                        when (state.activeTab) {
                            PhoneMonitorTab.APPS -> {
                                for (sort in PhoneAppSort.entries) {
                                    Chip(
                                        label = "Sort: ${sort.label}",
                                        selected = state.appSort == sort,
                                        onClick = { state.appSort = sort },
                                        modifier = Modifier.testTag("phone-sysmon-app-sort-${sort.id}"),
                                    )
                                }
                            }
                            PhoneMonitorTab.PROCESSES -> {
                                for (mode in PhoneProcessMode.entries) {
                                    Chip(
                                        label = mode.label,
                                        selected = state.processMode == mode,
                                        onClick = { state.processMode = mode },
                                        modifier = Modifier.testTag("phone-sysmon-proc-mode-${mode.id}"),
                                    )
                                }
                            }
                            PhoneMonitorTab.SERVICES -> {
                                for (scope in PhoneServiceScope.entries) {
                                    Chip(
                                        label = scope.label,
                                        selected = state.serviceScope == scope,
                                        onClick = { state.serviceScope = scope },
                                        modifier = Modifier.testTag("phone-sysmon-svc-scope-${scope.id}"),
                                    )
                                }
                                for (flt in PhoneServiceFilter.entries) {
                                    Chip(
                                        label = flt.label,
                                        selected = state.serviceFilter == flt,
                                        onClick = { state.serviceFilter = flt },
                                        modifier = Modifier.testTag("phone-sysmon-svc-filter-${flt.id}"),
                                    )
                                }
                            }
                            else -> {}
                        }
                    },
                ) {
                    val searchVal = when (state.activeTab) {
                        PhoneMonitorTab.APPS -> state.appSearch
                        PhoneMonitorTab.PROCESSES -> state.processSearch
                        PhoneMonitorTab.SERVICES -> state.serviceSearch
                        else -> ""
                    }
                    PhoneBottomSearchField(
                        value = searchVal,
                        placeholder = "Filter ${state.activeTab.label.lowercase()}…",
                        onValueChange = { next ->
                            when (state.activeTab) {
                                PhoneMonitorTab.APPS -> state.appSearch = next
                                PhoneMonitorTab.PROCESSES -> state.processSearch = next
                                PhoneMonitorTab.SERVICES -> state.serviceSearch = next
                                else -> {}
                            }
                        },
                        modifier = Modifier.testTag("phone-sysmon-search-input"),
                    )
                }
            }
        }

        // Read-only service detail sheet
        val detailSvc = state.selectedServiceForDetail
        if (detailSvc != null) {
            Sheet(
                title = detailSvc.unit,
                subtitle = "${detailSvc.activeState} (${detailSvc.subState}) · Read-only on phone",
                onDismiss = { state.selectedServiceForDetail = null },
                forceBottomSheet = true,
                applyWindowInsets = false,
                modifier = Modifier.testTag("phone-sysmon-service-detail-sheet"),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BasicText(
                        text = detailSvc.description.ifBlank { "System service" },
                        style = ppmText(13.5f, FontWeight.Normal).copy(color = tokens.text2.color),
                    )
                    if (serviceDetails != null && serviceDetails.service.unit == detailSvc.unit && serviceDetails.logs.isNotEmpty()) {
                        SectionLabel(text = "Recent Logs")
                        for (line in serviceDetails.logs.takeLast(10)) {
                            BasicText(
                                text = line.message,
                                style = ppmText(11.5f, FontWeight.Normal, FontKind.MONO).copy(color = tokens.text.color),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneMetricGaugeCard(
    title: String,
    valueText: String,
    subtitle: String,
    percent: Double,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val level = MonitorMetricLevel.fromPercent(percent)
    val barColor = when (level) {
        MonitorMetricLevel.CRITICAL -> tokens.error.color
        MonitorMetricLevel.WARNING -> tokens.warning.color
        MonitorMetricLevel.NORMAL -> tokens.accent.color
    }
    val cardShape = RoundedCornerShape(14.dp)

    Column(
        modifier = modifier
            .clip(cardShape)
            .background(tokens.panel.color, cardShape)
            .cssBorder(tokens.borderSoft.color, cardShape, width = 1.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = title,
                style = ppmText(11.5f, FontWeight.SemiBold).copy(color = tokens.text3.color),
            )
            BasicText(
                text = valueText,
                style = ppmText(18f, FontWeight.Bold, FontKind.MONO).copy(color = barColor),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(tokens.panel2.color),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((percent.coerceIn(0.0, 100.0) / 100.0).toFloat())
                    .fillMaxHeight()
                    .background(barColor, RoundedCornerShape(999.dp)),
            )
        }
        BasicText(
            text = subtitle,
            style = ppmText(11.5f, FontWeight.Normal).copy(color = tokens.text2.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Look-only Logs screen for phone (`designs/ppm-mobile-tablet-redesign/phone/7-tools/index.html` Section 2,
 * Requirement 14.1).
 *
 * - Opens directly on the live log tail (no `Issues` or `Report` tabs, no line selection or `Ask AI`).
 * - Source filter chips (`All`, `AI & chat`, `Shell & tools`, `Server`, `Tunnel`, `Browser`) + `Pause`/`Follow`.
 * - Bottom action card with search input, `Aa` case toggle, `.*` regex toggle, and `Filters` chip.
 * - `Filters` bottom sheet with Levels, Chat filter, Time ranges (`15m`, `1h`, `restart`, `today`, `all`),
 *   `Show times in UTC`, `Reset`, `Download as .log`, and `Show N lines`.
 */
@Composable
fun PhoneLogsScreen(
    state: PhoneLogsState,
    entries: List<LogEntry>,
    restarts: List<Long> = emptyList(),
    titles: Map<String, String> = emptyMap(),
    chats: List<LogChatInfo> = emptyList(),
    hasMore: Boolean = false,
    loading: Boolean = false,
    error: String? = null,
    onLoadOlder: (() -> Unit)? = null,
    onDownloadLog: ((List<LogRow>) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val filtered = remember(
        entries,
        titles,
        state.source,
        state.search,
        state.caseSensitive,
        state.regex,
        state.levels,
        state.chat,
    ) {
        state.filterEntries(entries, titles)
    }
    val folded = remember(filtered) { foldRepeats(filtered) }
    val dividers = remember(folded, restarts) { restartDividers(folded, restarts) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("phone-logs-screen"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top source strip + Pause/Follow button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(tokens.panel.color)
                    .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Bottom)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (chip in PhoneLogSourceChip.entries) {
                        val selected = state.source.equals(chip.id, ignoreCase = true)
                        Chip(
                            label = chip.label,
                            selected = selected,
                            onClick = { state.source = chip.id },
                            modifier = Modifier.testTag("phone-logs-source-${chip.id}"),
                        )
                    }
                }

                Chip(
                    label = if (state.follow) "Pause" else "Follow",
                    selected = state.follow,
                    onClick = { state.toggleFollow() },
                    modifier = Modifier.testTag("phone-logs-follow-toggle"),
                )
            }

            // Live tail log stream
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val chatSid = state.chat
                if (chatSid != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("phone-logs-chat-filter"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BasicText(
                            text = "Only lines naming ${logChatName(chatSid, titles)}",
                            style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text2.color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Chip(
                            label = "All chats",
                            onClick = { state.chat = null },
                            modifier = Modifier.testTag("phone-logs-chat-clear"),
                        )
                    }
                }
                if (error != null) {
                    BasicText(
                        text = error,
                        style = ppmText(12f, FontWeight.Normal).copy(color = tokens.error.color),
                        modifier = Modifier.testTag("phone-logs-error"),
                    )
                }
                if (hasMore && onLoadOlder != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .ppmClickable(onClickLabel = "Load earlier lines", onClick = onLoadOlder)
                            .testTag("phone-logs-load-older"),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            text = if (loading) "Loading…" else "Load earlier lines…",
                            style = ppmText(12f, FontWeight.Medium).copy(color = tokens.accent.color),
                        )
                    }
                }
                if (folded.isEmpty()) {
                    BasicText(
                        text = if (loading) "Loading…" else "No log lines match the current filter.",
                        style = ppmText(12.5f, FontWeight.Normal).copy(color = tokens.text2.color),
                        modifier = Modifier
                            .padding(vertical = 16.dp)
                            .testTag("phone-logs-empty"),
                    )
                }
                for (row in folded) {
                    val entry = row.entry
                    if (row.key in dividers) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .testTag("phone-logs-restart-${row.key}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            BasicText(
                                text = "PPM restarted",
                                style = ppmText(11.5f, FontWeight.SemiBold).copy(color = tokens.warning.color),
                            )
                        }
                    }

                    val rowShape = RoundedCornerShape(10.dp)
                    val levelColor = when (entry.lv.lowercase()) {
                        "error", "fatal" -> tokens.error.color
                        "warn" -> tokens.warning.color
                        "debug" -> tokens.text3.color
                        else -> tokens.accent.color
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(rowShape)
                            .background(tokens.panel.color, rowShape)
                            .cssBorder(tokens.borderSoft.color, rowShape, width = 1.dp)
                            .padding(10.dp)
                            .testTag("phone-log-row-${entry.id}"),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BasicText(
                                    text = clockTime(entry.ts, state.showUtc),
                                    style = ppmText(10.5f, FontWeight.Normal, FontKind.MONO).copy(color = tokens.text3.color),
                                    modifier = Modifier.testTag("phone-log-time-${entry.id}"),
                                )
                                BasicText(
                                    text = entry.lv.uppercase(),
                                    style = ppmText(10.5f, FontWeight.Bold, FontKind.MONO).copy(color = levelColor),
                                )
                                BasicText(
                                    text = entry.src,
                                    style = ppmText(11f, FontWeight.Medium, FontKind.MONO).copy(color = tokens.text2.color),
                                )
                            }
                            if (row.count > 1) {
                                BasicText(
                                    text = "×${row.count}",
                                    style = ppmText(11f, FontWeight.SemiBold, FontKind.MONO).copy(color = tokens.warning.color),
                                    modifier = Modifier.testTag("phone-log-repeat-${entry.id}"),
                                )
                            }
                        }

                        BasicText(
                            text = entry.msg,
                            style = ppmText(12.5f, FontWeight.Normal, FontKind.MONO, lineHeight = 18f)
                                .copy(color = tokens.text.color),
                        )

                        if (entry.more.isNotEmpty()) {
                            val expanded = entry.id in state.expandedMoreIds
                            BasicText(
                                text = if (expanded) "Hide details" else "more",
                                style = ppmText(11.5f, FontWeight.SemiBold).copy(color = tokens.accent.color),
                                modifier = Modifier
                                    .testTag("phone-log-more-${entry.id}")
                                    .ppmClickable(onClick = { state.toggleExpandedMore(entry.id) }),
                            )
                            if (expanded) {
                                BasicText(
                                    text = entry.more.joinToString("\n"),
                                    style = ppmText(11f, FontWeight.Normal, FontKind.MONO).copy(color = tokens.text2.color),
                                )
                            }
                        }
                    }
                }
            }

            // Bottom search & filter card
            BottomActionCard(
                modifier = Modifier.testTag("phone-logs-bottom-bar"),
                applyWindowInsets = false,
                chipsContent = {
                    Chip(
                        label = "Aa",
                        selected = state.caseSensitive,
                        monoLabel = true,
                        onClick = { state.caseSensitive = !state.caseSensitive },
                        modifier = Modifier.testTag("phone-logs-case-toggle"),
                    )
                    Chip(
                        label = ".*",
                        selected = state.regex,
                        monoLabel = true,
                        onClick = { state.regex = !state.regex },
                        modifier = Modifier.testTag("phone-logs-regex-toggle"),
                    )
                    val badgeCount = state.activeFilterBadgeCount
                    Chip(
                        label = if (badgeCount > 0) "Filters · $badgeCount" else "Filters",
                        selected = state.filtersSheetOpen || badgeCount > 0,
                        onClick = { state.filtersSheetOpen = true },
                        modifier = Modifier.testTag("phone-logs-filters-btn"),
                    )
                },
            ) {
                PhoneBottomSearchField(
                    value = state.search,
                    placeholder = "Search logs…",
                    onValueChange = { state.search = it },
                    modifier = Modifier.testTag("phone-logs-search-input"),
                )
            }
        }

        // Filters bottom sheet
        if (state.filtersSheetOpen) {
            Sheet(
                title = "Filters",
                onDismiss = { state.filtersSheetOpen = false },
                forceBottomSheet = true,
                applyWindowInsets = false,
                modifier = Modifier.testTag("phone-logs-filters-sheet"),
                headerActions = {
                    Chip(
                        label = "Reset",
                        onClick = { state.resetFilters() },
                        modifier = Modifier.testTag("phone-logs-filters-reset"),
                    )
                },
                footer = {
                    if (onDownloadLog != null) {
                        Chip(
                            label = "Download as .log",
                            enabled = folded.isNotEmpty(),
                            onClick = { onDownloadLog(folded) },
                            modifier = Modifier.testTag("phone-logs-download-btn"),
                        )
                    }
                    ActionPrimaryButton(
                        label = "Show ${filtered.size} lines",
                        onClick = { state.filtersSheetOpen = false },
                        modifier = Modifier.testTag("phone-logs-filters-apply"),
                    )
                },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionLabel(text = "Levels")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (lvl in listOf("error", "warn", "info", "debug")) {
                            Chip(
                                label = lvl.replaceFirstChar { it.uppercase() },
                                selected = lvl in state.levels,
                                onClick = { state.toggleLevel(lvl) },
                                modifier = Modifier.testTag("phone-logs-level-$lvl"),
                            )
                        }
                    }

                    SectionLabel(text = "Chat")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Chip(
                            label = "All chats",
                            selected = state.chat == null,
                            onClick = { state.chat = null },
                            modifier = Modifier.testTag("phone-logs-chat-all"),
                        )
                        val selectedSid = state.chat
                        val chatChoices = chats.take(40).let { list ->
                            if (selectedSid != null && list.none { it.sid == selectedSid }) {
                                listOf(LogChatInfo(sid = selectedSid, title = titles[selectedSid], count = 0, last = 0L)) + list
                            } else {
                                list
                            }
                        }
                        for (c in chatChoices) {
                            Chip(
                                label = c.title?.takeIf { it.isNotBlank() } ?: logChatName(c.sid, titles),
                                selected = state.chat == c.sid,
                                onClick = { state.chat = c.sid },
                                modifier = Modifier.testTag("phone-logs-chat-${c.sid}"),
                            )
                        }
                    }
                    if (chats.isEmpty()) {
                        BasicText(
                            text = "No chat named in this range",
                            style = ppmText(11.5f, FontWeight.Normal).copy(color = tokens.text3.color),
                        )
                    }

                    SectionLabel(text = "Time Range")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (rng in listOf("15m", "1h", "restart", "today", "all")) {
                            Chip(
                                label = rng,
                                selected = state.timeRange == rng,
                                onClick = { state.timeRange = rng },
                                modifier = Modifier.testTag("phone-logs-range-$rng"),
                            )
                        }
                    }

                    ListRow(
                        title = "Show times in UTC",
                        subtitle = if (state.showUtc) "UTC timestamps enabled" else "Local timezone",
                        onClick = { state.showUtc = !state.showUtc },
                        modifier = Modifier.testTag("phone-logs-utc-toggle"),
                    )
                }
            }
        }
    }
}

/** The right-hand word of a transport row, from what the host answered (`TunnelsModel.*Setup`). */
internal fun transportMeta(setup: SetupState?, readyLabel: String): String = when {
    setup == null -> "Checking…"
    setup.done && !setup.warn -> readyLabel
    setup.warn -> "Needs attention"
    else -> "Not set up"
}

/** A chat as the web's logs pane names it: its title, else the first 8 characters of its id. */
internal fun logChatName(sid: String, titles: Map<String, String>): String =
    titles[sid]?.takeIf { it.isNotBlank() } ?: sid.take(8)

/**
 * Port Forwarding screen for phone (`designs/ppm-mobile-tablet-redesign/phone/7-tools/index.html` Section 3,
 * Requirement 14.2).
 *
 * - Shows Tailscale and Cloudflare status rows without `Set up` / `Manage` buttons.
 * - Shows `"Set up Tailscale or Cloudflare on a tablet."` when a transport is not configured.
 * - Lists active forwards with `:port`, `Tailnet`/`Public` badge, `Started outside PPM` badge, and
 *   44dp URL action strip (`Open in a tab`, `Open in the browser`, `Copy link`).
 * - Places `localhost:` port input, `Tailscale`/`Cloudflare` toggle, and `+ Forward` in `BottomActionCard`.
 * - Confirms stopping external tunnels with `"Stop a tunnel PPM did not start?"`.
 */
@Composable
fun PhonePortForwardingScreen(
    state: PhonePortForwardingState,
    tunnels: List<TunnelEntry>,
    /** `null` while the host is still being asked (`/api/tunnels/transports`). */
    tailscaleSetup: SetupState? = null,
    /** `null` while the host is still being asked (`/api/tunnel/named/status`). */
    cloudflareSetup: SetupState? = null,
    starting: Boolean = false,
    error: String? = null,
    stoppingPid: Int? = null,
    onStartForward: (port: Int, transport: String) -> Unit,
    onStopTunnel: (TunnelEntry) -> Unit,
    onOpenInTab: (TunnelEntry) -> Unit,
    onOpenInBrowser: (TunnelEntry) -> Unit,
    onCopyLink: (TunnelEntry) -> Unit,
    onSelectTransport: (String) -> Unit = { state.transport = it },
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val tailscaleReady = tailscaleSetup?.done == true
    val cloudflareReady = cloudflareSetup?.done == true

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("phone-tunnels-screen"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionLabel(text = "Transports")
                ListRow(
                    title = "Tailscale",
                    subtitle = tailscaleSetup?.status ?: "Checking…",
                    meta = transportMeta(tailscaleSetup, readyLabel = "Connected"),
                    dividerBottom = true,
                    modifier = Modifier.testTag("phone-tunnels-row-tailscale"),
                )
                ListRow(
                    title = "Cloudflare",
                    subtitle = cloudflareSetup?.status ?: "Checking…",
                    meta = transportMeta(cloudflareSetup, readyLabel = "Ready"),
                    dividerBottom = true,
                    modifier = Modifier.testTag("phone-tunnels-row-cloudflare"),
                )

                val bothKnown = tailscaleSetup != null && cloudflareSetup != null
                if (bothKnown && state.shouldShowTabletSetupNotice(tailscaleReady = tailscaleReady, cloudflareReady = cloudflareReady)) {
                    OpenOnTabletNotice(
                        text = state.tabletSetupNoticeText,
                        modifier = Modifier.testTag("phone-tunnels-tablet-setup-notice"),
                    )
                }

                SectionLabel(text = "Active Forwards (${tunnels.size})")
                if (tunnels.isEmpty()) {
                    BasicText(
                        text = "No ports forwarded yet. Enter a localhost port below.",
                        style = ppmText(13f, FontWeight.Normal).copy(color = tokens.text2.color),
                    )
                } else {
                    for (entry in tunnels) {
                        val portKey = entry.port ?: entry.pid
                        val cardShape = RoundedCornerShape(14.dp)
                        val isExternal = entry.source == "external"
                        val isTailscale = entry.via == "tailscale"
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(cardShape)
                                .background(tokens.panel.color, cardShape)
                                .cssBorder(tokens.borderSoft.color, cardShape, width = 1.dp)
                                .padding(12.dp)
                                .testTag("phone-tunnel-card-$portKey"),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    BasicText(
                                        text = if (entry.port != null) ":${entry.port}" else "pid ${entry.pid}",
                                        style = ppmText(15f, FontWeight.Bold, FontKind.MONO).copy(color = tokens.text.color),
                                    )
                                    BasicText(
                                        text = if (isTailscale) "Tailnet" else "Public",
                                        style = ppmText(11.5f, FontWeight.SemiBold).copy(
                                            color = if (isTailscale) tokens.accent.color else tokens.success.color,
                                        ),
                                    )
                                    if (isExternal) {
                                        BasicText(
                                            text = "Started outside PPM",
                                            style = ppmText(11f, FontWeight.Medium).copy(color = tokens.warning.color),
                                            modifier = Modifier.testTag("phone-tunnel-external-badge-$portKey"),
                                        )
                                    }
                                }

                                Chip(
                                    label = if (stoppingPid == entry.pid) "Stopping…" else "Stop",
                                    alert = isExternal,
                                    enabled = stoppingPid != entry.pid,
                                    onClick = { state.requestStopTunnel(entry, onStopTunnel) },
                                    modifier = Modifier.testTag("phone-tunnel-stop-$portKey"),
                                )
                            }

                            // 44dp URL action strip: only an address the host reported can be opened or copied.
                            val urlText = entry.url?.takeIf { it.isNotBlank() }
                            if (urlText == null) {
                                BasicText(
                                    text = "Address not known",
                                    style = ppmText(12f, FontWeight.Normal).copy(color = tokens.text2.color),
                                    modifier = Modifier
                                        .padding(vertical = 8.dp)
                                        .testTag("phone-tunnel-no-url-$portKey"),
                                )
                            } else Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(tokens.panel2.color, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                BasicText(
                                    text = urlText,
                                    style = ppmText(12f, FontWeight.Normal, FontKind.MONO).copy(color = tokens.accent.color),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .ppmClickable(
                                            onClickLabel = "Open in a tab",
                                            onClick = { onOpenInTab(entry) },
                                        )
                                        .testTag("phone-tunnel-open-tab-$portKey"),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    BasicText(
                                        text = "Browser",
                                        style = ppmText(11.5f, FontWeight.SemiBold).copy(color = tokens.text2.color),
                                        modifier = Modifier
                                            .ppmClickable(
                                                onClickLabel = "Open in the browser",
                                                onClick = { onOpenInBrowser(entry) },
                                            )
                                            .testTag("phone-tunnel-open-browser-$portKey"),
                                    )
                                    BasicText(
                                        text = "Copy",
                                        style = ppmText(11.5f, FontWeight.SemiBold).copy(color = tokens.text2.color),
                                        modifier = Modifier
                                            .ppmClickable(
                                                onClickLabel = "Copy link",
                                                onClick = { onCopyLink(entry) },
                                            )
                                            .testTag("phone-tunnel-copy-$portKey"),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom port forward card
            val canForward = state.canSubmitForward(
                tailscaleReady = tailscaleReady,
                cloudflareReady = cloudflareReady,
                starting = starting,
            )
            BottomActionCard(
                modifier = Modifier.testTag("phone-tunnels-bottom-bar"),
                applyWindowInsets = false,
                topContent = {
                    BasicText(
                        text = error ?: state.helperText,
                        style = ppmText(11.5f, FontWeight.Normal).copy(
                            color = if (error != null) tokens.error.color else tokens.text2.color,
                        ),
                        modifier = Modifier.testTag(if (error != null) "phone-tunnels-error" else "phone-tunnels-helper"),
                    )
                },
                chipsContent = {
                    Chip(
                        label = "Tailscale",
                        selected = state.transport == "tailscale",
                        onClick = { onSelectTransport("tailscale") },
                        modifier = Modifier.testTag("phone-tunnels-transport-tailscale"),
                    )
                    Chip(
                        label = "Cloudflare",
                        selected = state.transport == "cloudflare",
                        onClick = { onSelectTransport("cloudflare") },
                        modifier = Modifier.testTag("phone-tunnels-transport-cloudflare"),
                    )
                },
                primaryAction = {
                    ActionPrimaryButton(
                        label = if (starting) "Forwarding…" else "+ Forward",
                        enabled = canForward,
                        onClick = {
                            val port = state.parsedPort ?: return@ActionPrimaryButton
                            onStartForward(port, state.transport)
                        },
                        modifier = Modifier.testTag("phone-tunnels-forward-btn"),
                    )
                },
            ) {
                PhoneBottomSearchField(
                    value = state.portInput,
                    placeholder = "localhost: 3000",
                    onValueChange = { state.portInput = it },
                    modifier = Modifier.testTag("phone-tunnels-port-input"),
                )
            }
        }

        // External tunnel stop confirmation
        val pendingExternal = state.confirmStopExternalTunnel
        if (pendingExternal != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0f, 0f, 0f, 0.52f))
                    .padding(16.dp)
                    .testTag("phone-tunnel-external-confirm-sheet"),
                contentAlignment = Alignment.Center,
            ) {
                ConfirmDialogCard(
                    title = state.externalStopConfirmTitle,
                    message = "Port :${pendingExternal.port ?: "?"} (pid ${pendingExternal.pid}) was started outside PPM.",
                    confirmLabel = "Stop tunnel",
                    destructive = true,
                    onConfirm = { state.confirmExternalStop(onStopTunnel) },
                    onDismiss = { state.dismissExternalStop() },
                )
            }
        }
    }
}

/**
 * Schedules settings page for phone (`designs/ppm-mobile-tablet-redesign/phone/7-tools/index.html` Section 4,
 * Requirement 14.3).
 *
 * - Reached from Settings → Schedules.
 * - Shows 64dp rows with enable/disable switch, status badge, expandable prompt/details and recent runs `#id`.
 * - Shows `"Edit a script or add a schedule on a tablet."` (`OpenOnTabletNotice`) — no script editor,
 *   no `+ New` or delete controls on phone.
 * - Primary actions (`Full log` and `Run now` / `Restart`) sit in the `BottomActionCard` at the bottom.
 * - Tapping `Full log` opens `PhoneScheduleLogScreen` with `Last error` banner, monospace log lines,
 *   and bottom `Restart` / `Run now` card.
 */
@Composable
fun PhoneSchedulesScreen(
    state: PhoneSchedulesState,
    /** A schedule's run history (`/api/schedules/:id/runs`); a missing key means it is still being read. */
    runsByScheduleId: Map<Int, List<ScheduleRunRow>> = emptyMap(),
    /** A script schedule's live log (`/api/schedules/:id/logs`). */
    logsByScheduleId: Map<Int, List<ScheduleLogLine>> = emptyMap(),
    loading: Boolean = false,
    error: String? = null,
    onRunOrRestart: (ScheduleRowState) -> Unit,
    onToggleEnabled: (ScheduleRowState, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current

    BackHandler(enabled = state.isViewingFullLog) {
        state.closeFullLog()
    }

    val fullLogTarget = state.fullLogSchedule
    if (fullLogTarget != null) {
        PhoneScheduleLogScreen(
            schedule = fullLogTarget,
            primaryActionLabel = state.primaryActionLabelFor(fullLogTarget),
            logLines = logsByScheduleId[fullLogTarget.id].orEmpty(),
            runs = runsByScheduleId[fullLogTarget.id],
            onBack = { state.closeFullLog() },
            onRunOrRestart = { onRunOrRestart(fullLogTarget) },
            modifier = modifier,
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("phone-schedules-screen"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionLabel(text = state.settingsBreadcrumb)

                if (error != null) {
                    BasicText(
                        text = error,
                        style = ppmText(12.5f, FontWeight.Normal).copy(color = tokens.error.color),
                        modifier = Modifier.testTag("phone-schedules-error"),
                    )
                }
                if (state.schedules.isEmpty()) {
                    BasicText(
                        text = if (loading) "Loading schedules…" else "No schedules yet.",
                        style = ppmText(13f, FontWeight.Normal).copy(color = tokens.text2.color),
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .testTag("phone-schedules-empty"),
                    )
                }

                for (schedule in state.schedules) {
                    val selected = schedule.id == state.selectedSchedule?.id
                    val expanded = schedule.id == state.expandedScheduleId
                    val cardShape = RoundedCornerShape(14.dp)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(cardShape)
                            .background(tokens.panel.color, cardShape)
                            .cssBorder(
                                color = if (selected) tokens.accentWashBorder.color else tokens.borderSoft.color,
                                shape = cardShape,
                                width = 1.dp,
                            )
                            .testTag("phone-schedule-row-${schedule.id}"),
                    ) {
                        ListRow(
                            title = schedule.name,
                            subtitle = "${schedule.cronExpr} · ${schedule.kind}",
                            subtitleMono = true,
                            selected = selected,
                            onClick = { state.selectAndToggleExpand(schedule.id) },
                            trailingContent = {
                                Chip(
                                    label = if (schedule.enabled) "On" else "Off",
                                    selected = schedule.enabled,
                                    onClick = { onToggleEnabled(schedule, !schedule.enabled) },
                                    modifier = Modifier.testTag("phone-schedule-switch-${schedule.id}"),
                                )
                            },
                        )

                        if (expanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Top)
                                    .padding(12.dp)
                                    .testTag("phone-schedule-expanded-${schedule.id}"),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                // Read-only prompt/script preview (no editor on phone)
                                val previewText = (schedule.script ?: schedule.prompt).ifBlank { "No prompt text" }
                                BasicText(
                                    text = previewText,
                                    style = ppmText(12f, FontWeight.Normal, FontKind.MONO).copy(color = tokens.text2.color),
                                    modifier = Modifier.testTag("phone-schedule-readonly-preview-${schedule.id}"),
                                )
                                val runs = runsByScheduleId[schedule.id]
                                if (runs.isNullOrEmpty()) {
                                    BasicText(
                                        text = if (runs == null) "Loading runs…" else "No runs yet.",
                                        style = ppmText(11.5f, FontWeight.Normal).copy(color = tokens.text3.color),
                                        modifier = Modifier.testTag("phone-schedule-runs-empty-${schedule.id}"),
                                    )
                                }
                                for (run in runs.orEmpty().take(3)) {
                                    PhoneScheduleRunRow(run = run, scheduleId = schedule.id)
                                }
                            }
                        }
                    }
                }

                // Subtle tablet notice — no script editing or schedule creation on phone
                OpenOnTabletNotice(
                    text = state.tabletNoticeText,
                    modifier = Modifier.testTag("phone-schedules-tablet-notice"),
                )
            }

            // Bottom action card acting on the selected schedule
            val selected = state.selectedSchedule
            val primaryLabel = state.primaryActionLabelFor(selected)
            BottomActionCard(
                modifier = Modifier.testTag("phone-schedules-bottom-bar"),
                applyWindowInsets = false,
                chipsContent = {
                    Chip(
                        label = "Full log",
                        enabled = selected != null,
                        onClick = {
                            if (selected != null) state.openFullLog(selected.id)
                        },
                        modifier = Modifier.testTag("phone-schedules-full-log-btn"),
                    )
                },
                primaryAction = {
                    ActionPrimaryButton(
                        label = primaryLabel,
                        enabled = selected != null,
                        onClick = {
                            if (selected != null) onRunOrRestart(selected)
                        },
                        modifier = Modifier.testTag("phone-schedules-primary-btn"),
                    )
                },
            ) {
                BasicText(
                    text = selected?.let { "${it.name} (${it.status.state})" } ?: "Select a schedule",
                    style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color),
                )
            }
        }
    }
}

/** SQLite's `YYYY-MM-DD HH:MM:SS` (UTC) or ISO-8601, as `schedule-runs.tsx` reads a run's times. */
internal fun parseScheduleRunTime(ts: String?): java.time.Instant? {
    if (ts.isNullOrBlank()) return null
    val iso = if (ts.contains('T')) ts else ts.replace(' ', 'T') + "Z"
    return runCatching { java.time.Instant.parse(iso) }
        .recoverCatching { java.time.OffsetDateTime.parse(iso).toInstant() }
        .getOrNull()
}

/** `#42 · 11/11 02:00 · 12s · $0.0123`: what the web's run row says, in the phone's own zone. */
internal fun scheduleRunSummary(run: ScheduleRunRow, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
    val started = parseScheduleRunTime(run.startedAt)
    val ended = parseScheduleRunTime(run.endedAt)
    val startedText = started?.let {
        java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.US).withZone(zone).format(it)
    } ?: run.startedAt
    val duration = if (started != null && ended != null) "${java.time.Duration.between(started, ended).seconds}s" else "…"
    return buildString {
        append("#").append(run.id).append(" · ").append(startedText).append(" · ").append(duration)
        run.costUsd?.let { append(" · $").append(String.format(Locale.US, "%.4f", it)) }
    }
}

@Composable
private fun PhoneScheduleRunRow(run: ScheduleRunRow, scheduleId: Int, showOutput: Boolean = false) {
    val tokens = LocalPpmTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("phone-schedule-run-$scheduleId-${run.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText(
                text = scheduleRunSummary(run),
                style = ppmText(11.5f, FontWeight.Medium, FontKind.MONO).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            BasicText(
                text = run.status,
                style = ppmText(11.5f, FontWeight.SemiBold, FontKind.MONO).copy(
                    color = when (run.status) {
                        "error" -> tokens.error.color
                        "running" -> tokens.warning.color
                        "done" -> tokens.success.color
                        else -> tokens.text2.color
                    },
                ),
            )
        }
        if (showOutput) {
            run.error?.takeIf { it.isNotBlank() }?.let {
                BasicText(
                    text = it,
                    style = ppmText(11.5f, FontWeight.Normal, FontKind.MONO).copy(color = tokens.error.color),
                )
            }
            run.outputTruncated?.takeIf { it.isNotBlank() }?.let {
                BasicText(
                    text = it,
                    style = ppmText(11f, FontWeight.Normal, FontKind.MONO, lineHeight = 16f).copy(color = tokens.text2.color),
                    modifier = Modifier.testTag("phone-schedule-run-output-${run.id}"),
                )
            }
        }
    }
}

/**
 * Full log sub-page for a phone schedule (`designs/ppm-mobile-tablet-redesign/phone/7-tools/index.html` Section 4.1).
 */
@Composable
fun PhoneScheduleLogScreen(
    schedule: ScheduleRowState,
    primaryActionLabel: String,
    logLines: List<ScheduleLogLine>,
    onBack: () -> Unit,
    onRunOrRestart: () -> Unit,
    modifier: Modifier = Modifier,
    runs: List<ScheduleRunRow>? = null,
) {
    val tokens = LocalPpmTokens.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("phone-schedule-log-screen"),
    ) {
        // Header with Back button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(tokens.panel.color)
                .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Bottom)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Chip(
                label = "‹ Back",
                onClick = onBack,
                modifier = Modifier.testTag("phone-schedule-log-back"),
            )
            BasicText(
                text = schedule.name,
                style = ppmText(15f, FontWeight.SemiBold).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!schedule.status.lastError.isNullOrBlank()) {
                val errShape = RoundedCornerShape(12.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(errShape)
                        .background(tokens.error.opacity(0.12).color, errShape)
                        .cssBorder(tokens.error.opacity(0.35).color, errShape, width = 1.dp)
                        .padding(12.dp)
                        .testTag("phone-schedule-log-error-banner"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    BasicText(
                        text = "Last error",
                        style = ppmText(11.5f, FontWeight.Bold).copy(color = tokens.error.color),
                    )
                    BasicText(
                        text = schedule.status.lastError!!,
                        style = ppmText(12f, FontWeight.Normal, FontKind.MONO).copy(color = tokens.text.color),
                    )
                }
            }

            if (schedule.kind.equals("script", ignoreCase = true)) {
                if (logLines.isEmpty()) {
                    BasicText(
                        text = "No output yet.",
                        style = ppmText(12f, FontWeight.Normal).copy(color = tokens.text3.color),
                        modifier = Modifier.testTag("phone-schedule-log-empty"),
                    )
                }
                for ((idx, line) in logLines.withIndex()) {
                    BasicText(
                        text = "${line.stream}: ${line.text}",
                        style = ppmText(12f, FontWeight.Normal, FontKind.MONO, lineHeight = 18f).copy(
                            color = if (line.stream == "stderr") tokens.error.color else tokens.text.color,
                        ),
                        modifier = Modifier.testTag("phone-schedule-log-line-$idx"),
                    )
                }
            } else {
                // A prompt schedule has no process log: its history is its runs, each with its output.
                if (runs.isNullOrEmpty()) {
                    BasicText(
                        text = if (runs == null) "Loading runs…" else "No runs yet.",
                        style = ppmText(12f, FontWeight.Normal).copy(color = tokens.text3.color),
                        modifier = Modifier.testTag("phone-schedule-log-empty"),
                    )
                }
                for (run in runs.orEmpty()) {
                    PhoneScheduleRunRow(run = run, scheduleId = schedule.id, showOutput = true)
                }
            }
        }

        BottomActionCard(
            modifier = Modifier.testTag("phone-schedule-log-bottom-bar"),
            applyWindowInsets = false,
            primaryAction = {
                ActionPrimaryButton(
                    label = primaryActionLabel,
                    onClick = onRunOrRestart,
                    modifier = Modifier.testTag("phone-schedule-log-run-btn"),
                )
            },
        ) {
            BasicText(
                text = "Status: ${schedule.status.state}",
                style = ppmText(12.5f, FontWeight.Medium, FontKind.MONO).copy(color = tokens.text2.color),
            )
        }
    }
}

/**
 * Tablet 7 Tools rail popover (`designs/ppm-mobile-tablet-redesign/tablet/7-tools/index.html` Section 1,
 * Requirement 14.4).
 *
 * Lists `System Monitor`, `Logs`, `Remote Desktop`, `Android`, `File Explorer`, `Extensions`, `Schedules`
 * and opens `Remote Desktop` and `Logs` (and other workspace tools) as tabs via [onOpenToolTab].
 */
@Composable
fun TabletToolsMenuPopover(
    tools: List<TabletToolEntry> = defaultTabletToolEntries(),
    onOpenToolTab: (TabletToolEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val cardShape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier
            .width(240.dp)
            .clip(cardShape)
            .background(tokens.panel2.color, cardShape)
            .cssBorder(tokens.border.color, cardShape, width = 1.dp)
            .padding(6.dp)
            .testTag("tablet-tools-popover"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SectionLabel(text = "Tools")
        for (tool in tools) {
            ListRow(
                title = tool.label,
                meta = tool.auxBadge,
                compact = true,
                onClick = { onOpenToolTab(tool) },
                modifier = Modifier.testTag("tablet-tool-item-${tool.id}"),
            )
        }
    }
}

fun defaultTabletToolEntries(): List<TabletToolEntry> = listOf(
    TabletToolEntry(id = "system-monitor", label = "System Monitor", auxBadge = "22%", tabType = "system-monitor", opensAsTab = true),
    TabletToolEntry(id = "logs", label = "Logs", auxBadge = "2 issues", tabType = "logs", opensAsTab = true),
    TabletToolEntry(id = "remote-desktop", label = "Remote Desktop", auxBadge = ":1", tabType = "remote-desktop", opensAsTab = true),
    TabletToolEntry(id = "android", label = "Android", auxBadge = "Pixel 8", tabType = "android", opensAsTab = true),
    TabletToolEntry(id = "os-explorer", label = "File Explorer", auxBadge = "~", tabType = "os-explorer", opensAsTab = true),
    TabletToolEntry(id = "extensions", label = "Extensions", auxBadge = "2 active", tabType = "extensions", opensAsTab = false),
    TabletToolEntry(id = "schedules", label = "Schedules", auxBadge = "4 active", tabType = "schedules", opensAsTab = true),
)

/**
 * 4-step `Fixed address with Cloudflare` setup dialog for Tablet (`designs/ppm-mobile-tablet-redesign/tablet/7-tools/index.html` Section 4).
 *
 * All 4 steps (`1. Domain on Cloudflare`, `2. Sign in`, `3. Confirm your zone`, `4. Choose the address`)
 * are visible at once inside a `620dp` modal card.
 */
@Composable
fun CloudflareNamedTunnelFourStepDialog(
    state: CloudflareFourStepState,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val cardShape = RoundedCornerShape(18.dp)

    Column(
        modifier = modifier
            .widthIn(max = 620.dp)
            .fillMaxWidth()
            .clip(cardShape)
            .background(tokens.panel2.color, cardShape)
            .cssBorder(tokens.border.color, cardShape, width = 1.dp)
            .padding(20.dp)
            .testTag("cloudflare-4step-dialog"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(
            text = "Fixed address with Cloudflare",
            style = ppmText(17f, FontWeight.SemiBold).copy(color = tokens.text.color),
        )

        // Step 1
        ListRow(
            title = "1. Domain on Cloudflare",
            subtitle = "Your domain uses Cloudflare nameservers",
            compact = true,
            selected = state.prereqChecked,
            onClick = { state.prereqChecked = !state.prereqChecked },
            modifier = Modifier.testTag("cloudflare-step-1"),
        )

        // Step 2
        ListRow(
            title = "2. Sign in",
            subtitle = if (state.signedIn) "Authenticated with Cloudflare" else "Sign in with Cloudflare in your browser",
            compact = true,
            selected = state.signedIn,
            onClick = { state.signedIn = true },
            modifier = Modifier.testTag("cloudflare-step-2"),
        )

        // Step 3
        ListRow(
            title = "3. Confirm your zone",
            subtitle = "Zone: ${state.domain}",
            compact = true,
            selected = state.zoneConfirmed,
            onClick = { state.zoneConfirmed = true },
            modifier = Modifier.testTag("cloudflare-step-3"),
        )

        // Step 4
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cloudflare-step-4"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText(
                text = "4. Choose the address",
                style = ppmText(13.5f, FontWeight.SemiBold).copy(color = tokens.text.color),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PhoneBottomSearchField(
                    value = state.subdomainPrefix,
                    placeholder = "ppm",
                    onValueChange = { state.subdomainPrefix = it },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cloudflare-step-4-prefix"),
                )
                BasicText(
                    text = ".${state.domain}",
                    style = ppmText(13f, FontWeight.Medium, FontKind.MONO).copy(color = tokens.text2.color),
                    modifier = Modifier.testTag("cloudflare-step-4-domain-suffix"),
                )
            }
            BasicText(
                text = "https://${state.fullHostname}",
                style = ppmText(12f, FontWeight.Normal, FontKind.MONO).copy(color = tokens.accent.color),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip(
                label = "Cancel",
                onClick = onDismiss,
                modifier = Modifier.testTag("cloudflare-4step-cancel"),
            )
            Spacer(Modifier.width(8.dp))
            ActionPrimaryButton(
                label = "Save address",
                enabled = state.canSave,
                onClick = { onSave(state.fullHostname) },
                modifier = Modifier.testTag("cloudflare-4step-save"),
            )
        }
    }
}

/**
 * Tablet Schedules split workspace (`designs/ppm-mobile-tablet-redesign/tablet/7-tools/index.html` Section 10,
 * Requirement 14.4).
 *
 * Provides left schedule list (`All | Prompt | Script | Always-on`) and right detail pane with
 * editable script/prompt editor, cron input, recent runs list, and live log stream.
 */
@Composable
fun TabletSchedulesWorkspace(
    state: TabletSchedulesWorkspaceState,
    runsByScheduleId: Map<Int, List<ScheduleRunRow>> = emptyMap(),
    logsByScheduleId: Map<Int, List<ScheduleLogLine>> = emptyMap(),
    onRunNow: (ScheduleRowState) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("tablet-schedules-workspace"),
    ) {
        // Left list pane (280dp)
        Column(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .background(tokens.panel.color)
                .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.End)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for ((id, label) in listOf("all" to "All", "prompt" to "Prompt", "script" to "Script", "always" to "Always-on")) {
                    Chip(
                        label = label,
                        selected = state.kindFilter == id,
                        onClick = { state.kindFilter = id },
                        modifier = Modifier.testTag("tablet-schedules-filter-$id"),
                    )
                }
            }

            for (row in state.filteredSchedules) {
                ListRow(
                    title = row.name,
                    subtitle = "${row.cronExpr} · ${row.kind}",
                    subtitleMono = true,
                    compact = true,
                    selected = row.id == state.selectedSchedule?.id,
                    onClick = { state.selectSchedule(row.id) },
                    modifier = Modifier.testTag("tablet-schedule-item-${row.id}"),
                )
            }
        }

        // Right detail + script editor + runs + log pane
        val selected = state.selectedSchedule
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selected != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        text = selected.name,
                        style = ppmText(17f, FontWeight.SemiBold).copy(color = tokens.text.color),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(
                            label = "Save script",
                            onClick = { state.saveSelectedScheduleEdits() },
                            modifier = Modifier.testTag("tablet-schedule-save-btn"),
                        )
                        val isAlways = selected.mode.equals("always-on", ignoreCase = true) ||
                            selected.cronExpr.equals("always", ignoreCase = true)
                        ActionPrimaryButton(
                            label = if (isAlways) "Restart" else "Run now",
                            onClick = { onRunNow(selected) },
                            modifier = Modifier.testTag("tablet-schedule-run-btn"),
                        )
                    }
                }

                SectionLabel(text = "Script / Prompt Editor")
                PhoneBottomSearchField(
                    value = state.scriptDraft,
                    placeholder = "#!/usr/bin/env bash…",
                    onValueChange = { state.scriptDraft = it },
                    modifier = Modifier.testTag("tablet-schedule-script-editor"),
                )

                SectionLabel(text = "Recent Runs")
                for (run in runsByScheduleId[selected.id].orEmpty()) {
                    ListRow(
                        title = "Run #${run.id}",
                        subtitle = "Started ${run.startedAt}",
                        meta = run.status,
                        compact = true,
                        modifier = Modifier.testTag("tablet-schedule-run-${run.id}"),
                    )
                }

                SectionLabel(text = "Live Output Log")
                for ((idx, line) in logsByScheduleId[selected.id].orEmpty().withIndex()) {
                    BasicText(
                        text = line.text,
                        style = ppmText(12f, FontWeight.Normal, FontKind.MONO).copy(color = tokens.text.color),
                        modifier = Modifier.testTag("tablet-schedule-log-$idx"),
                    )
                }
            }
        }
    }
}

@Composable
internal fun PhoneBottomSearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(10.dp)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = ppmText(13.5f, FontWeight.Normal).copy(color = tokens.text.color),
        cursorBrush = SolidColor(tokens.accent.color),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 40.dp)
            .clip(shape)
            .background(tokens.panel2.color, shape)
            .cssBorder(tokens.border.color, shape, width = 1.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    BasicText(
                        text = placeholder,
                        style = ppmText(13.5f, FontWeight.Normal).copy(color = tokens.text3.color),
                    )
                }
                innerTextField()
            }
        },
    )
}
