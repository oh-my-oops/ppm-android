package com.hienlh.ppm.app.ui.sysmon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import kotlinx.coroutines.awaitCancellation
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.app.ui.SecondaryButton
import com.hienlh.ppm.app.ui.pressable
import com.hienlh.ppm.app.ui.tablet.Edge
import com.hienlh.ppm.app.ui.tablet.edge
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.fullResourceSamples
import com.hienlh.ppm.client.hardwareInventory
import com.hienlh.ppm.client.killProcess
import com.hienlh.ppm.client.processDetails
import com.hienlh.ppm.client.signalProcess
import com.hienlh.ppm.client.systemServiceAction
import com.hienlh.ppm.client.systemServiceDetails
import com.hienlh.ppm.client.systemServices
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.sysmon.*
import com.hienlh.ppm.state.windows.WindowRuntimeState
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private data class SysMonTabDef(val id: String, val label: String, val icon: ProductIcon)

private val SYSMON_TABS = listOf(
    SysMonTabDef("overview", "Overview", ProductIcon.Gauge),
    SysMonTabDef("performance", "Performance", ProductIcon.Activity),
    SysMonTabDef("apps", "Apps", ProductIcon.AppWindow),
    SysMonTabDef("processes", "Processes", ProductIcon.List),
    SysMonTabDef("services", "Services", ProductIcon.Layers),
)

@Composable
fun SystemMonitorWindowContent(model: AppModel, win: WindowRuntimeState) {
    val initialTab = win.payload["sysmonTab"].string ?: "overview"
    SystemMonitorBody(
        model = model,
        initialTab = initialTab,
        onTabChange = { next ->
            model.windows?.setPayload(win.id, buildJsonObject { put("sysmonTab", next) })
        },
    )
}

@Composable
fun SystemMonitorTabBody(model: AppModel, tab: WorkspaceTab) {
    val initialTab = tab.metaString("sysmonTab") ?: "overview"
    SystemMonitorBody(
        model = model,
        initialTab = initialTab,
        onTabChange = { next ->
            model.patchTabMetadata(tab.id, "sysmonTab" to JsonPrimitive(next))
        },
    )
}

@Composable
fun SystemMonitorBody(
    model: AppModel,
    initialTab: String = "overview",
    onTabChange: (String) -> Unit = {},
    initialSnapshot: MetricsSnapshot? = null,
    initialServices: ServicesSnapshot? = null,
    initialInventory: HardwareInventory? = null,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var activeTab by remember(initialTab) { mutableStateOf(initialTab) }
    var selectedPerfDevice by remember { mutableStateOf("cpu") }
    var preferencesOpen by remember { mutableStateOf(false) }
    var tempUnitFahrenheit by remember { mutableStateOf(false) }
    var showKernelTimes by remember { mutableStateOf(true) }

    val initialHistory = remember(initialSnapshot) {
        if (initialSnapshot != null) SysMonRingBuffer.appendSnapshot(emptyList(), initialSnapshot)
        else emptyList()
    }
    val initialTickCount = if (initialSnapshot != null) 2 else 0
    val snapshot = model.sharedMetricsSnapshot ?: initialSnapshot
    val history = model.sharedMetricsHistory.ifEmpty { initialHistory }
    val tickCount = if (model.sharedMetricsSnapshot != null) model.sharedMetricsTickCount else initialTickCount
    var inventory by remember { mutableStateOf(initialInventory) }
    var actionBanner by remember { mutableStateOf<String?>(null) }

    fun selectTab(tab: String) {
        activeTab = tab
        onTabChange(tab)
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model.api) {
        val api = model.api ?: return@LaunchedEffect
        try {
            val json = api.hardwareInventory()
            HardwareInventory.from(json)?.let { inventory = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    LaunchedEffect(model.api, lifecycle) {
        if (model.api == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val release = model.retainResourceMonitor(wantProcesses = true)
            try {
                awaitCancellation()
            } finally {
                release()
            }
        }
    }

    val measuring = SysMonRingBuffer.isMeasuring(tickCount)

    Column(
        Modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("system-monitor-body"),
    ) {
        // Sub-tab bar
        Row(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(tokens.panel2.opacity(0.6).color)
                .edge(Edge.Bottom, tokens.border.color)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (tab in SYSMON_TABS) {
                val active = activeTab == tab.id
                val tabShape = RoundedCornerShape(6.dp)
                Row(
                    Modifier
                        .height(28.dp)
                        .clip(tabShape)
                        .background(if (active) tokens.accent.opacity(0.15).color else Color.Transparent)
                        .ppmClickable(shape = tabShape) { selectTab(tab.id) }
                        .padding(horizontal = 10.dp)
                        .testTag("sysmon-tab-${tab.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PpmIcon(tab.icon, 14.dp, if (active) tokens.accent.color else tokens.text2.color)
                    BasicText(
                        tab.label,
                        style = ppmText(12f, if (active) FontWeight.SemiBold else FontWeight.Normal, lineHeight = 16f)
                            .copy(color = if (active) tokens.accent.color else tokens.text2.color),
                    )
                }
            }
            if (!mobile) {
                Spacer(Modifier.weight(1f))
                val prefShape = RoundedCornerShape(6.dp)
                Row(
                    Modifier
                        .height(28.dp)
                        .clip(prefShape)
                        .background(if (preferencesOpen) tokens.accent.opacity(0.15).color else Color.Transparent)
                        .ppmClickable(shape = prefShape) { preferencesOpen = !preferencesOpen }
                        .padding(horizontal = 8.dp)
                        .testTag("sysmon-preferences-trigger"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PpmIcon(ProductIcon.Settings, 13.dp, tokens.text2.color)
                    BasicText(
                        "Preferences",
                        style = ppmText(11f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.text2.color),
                    )
                }
            }
        }

        if (preferencesOpen && !mobile) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(tokens.panel2.color)
                    .edge(Edge.Bottom, tokens.border.color)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("sysmon-preferences-card"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BasicText(
                    "Temperature unit: ${if (tempUnitFahrenheit) "°F" else "°C"}",
                    Modifier
                        .ppmClickable { tempUnitFahrenheit = !tempUnitFahrenheit }
                        .testTag("sysmon-pref-temp-unit"),
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                )
                BasicText(
                    if (showKernelTimes) "Kernel times: On" else "Kernel times: Off",
                    Modifier
                        .ppmClickable { showKernelTimes = !showKernelTimes }
                        .testTag("sysmon-pref-kernel-times"),
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color),
                )
            }
        }

        // Warnings / Action banner
        if (actionBanner != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(tokens.error.opacity(0.12).color)
                    .edge(Edge.Bottom, tokens.error.opacity(0.25).color)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BasicText(
                    actionBanner!!,
                    Modifier.weight(1f),
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color),
                )
                Box(
                    Modifier.size(20.dp).ppmClickable { actionBanner = null },
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.X, 12.dp, tokens.error.color)
                }
            }
        }

        val cur = snapshot
        if (cur == null && activeTab != "services") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PpmIcon(ProductIcon.Loader2, 18.dp, tokens.text2.color, spinning = true)
                    BasicText("Connecting to resource monitor…", style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text2.color))
                }
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (activeTab) {
                    "overview" -> if (cur != null) {
                        OverviewPanel(
                            snapshot = cur,
                            history = history,
                            measuring = measuring,
                            onSelectDevice = { deviceKey ->
                                selectedPerfDevice = deviceKey
                                selectTab("performance")
                            },
                            onOpenProcesses = { selectTab("processes") },
                        )
                    }
                    "performance" -> if (cur != null) {
                        PerformancePanel(
                            snapshot = cur,
                            history = history,
                            inventory = inventory,
                            measuring = measuring,
                            selectedDevice = selectedPerfDevice,
                            onSelectDevice = { selectedPerfDevice = it },
                        )
                    }
                    "apps" -> if (cur != null) {
                        AppsPanel(
                            model = model,
                            snapshot = cur,
                            onError = { actionBanner = it },
                        )
                    }
                    "processes" -> if (cur != null) {
                        ProcessesPanel(
                            model = model,
                            snapshot = cur,
                            measuring = measuring,
                            onError = { actionBanner = it },
                        )
                    }
                    "services" -> {
                        ServicesPanel(
                            model = model,
                            processes = cur?.processes.orEmpty(),
                            initialServices = initialServices,
                            onError = { actionBanner = it },
                        )
                    }
                }
            }
        }
    }
}

// ── 1. Overview Panel ──────────────────────────────────────────────────

@Composable
private fun OverviewPanel(
    snapshot: MetricsSnapshot,
    history: List<MetricsHistoryPoint>,
    measuring: Boolean,
    onSelectDevice: (String) -> Unit,
    onOpenProcesses: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val sys = snapshot.system

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .testTag("sysmon-overview-panel"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Metric cards
        OverviewMetricCard(
            title = "CPU",
            subtitle = sys.cpu.model.ifEmpty { "${sys.cpu.cores.size} logical cores" },
            headline = if (measuring) "Measuring…" else "${sys.cpu.total.roundToInt()}%",
            detail = buildString {
                sys.cpu.currentMHz?.let { append(String.format(Locale.US, "%.2f GHz", it / 1000.0)) }
                sys.cpu.tempC?.let {
                    if (isNotEmpty()) append(" · ")
                    append("${it.roundToInt()}°C")
                }
            },
            series = history.map { it.system.cpu.total.toFloat() },
            maxVal = 100f,
            color = Color(0xFF3B82F6),
            tag = "overview-card-cpu",
            onClick = { onSelectDevice("cpu") },
        )

        OverviewMetricCard(
            title = "Memory",
            subtitle = "${PerformanceDevices.formatRam(sys.mem.usedMB)} / ${PerformanceDevices.formatRam(sys.mem.totalMB)}",
            headline = "${sys.mem.percent.roundToInt()}%",
            detail = "Available: ${PerformanceDevices.formatRam(sys.mem.availableMB)}" +
                (sys.mem.swapUsedMB?.takeIf { (sys.mem.swapTotalMB ?: 0.0) > 0.0 }?.let { " · Swap: ${PerformanceDevices.formatRam(it)}" } ?: ""),
            series = history.map { it.system.mem.percent.toFloat() },
            maxVal = 100f,
            color = Color(0xFF8B5CF6),
            tag = "overview-card-memory",
            onClick = { onSelectDevice("memory") },
        )

        val firstDisk = sys.disks.firstOrNull()
        OverviewMetricCard(
            title = "Disk",
            subtitle = firstDisk?.id ?: "I/O Throughput",
            headline = if (measuring) "Measuring…" else PerformanceDevices.formatBps(sys.disk.inBps + sys.disk.outBps),
            detail = "Read ${PerformanceDevices.formatBps(sys.disk.inBps)} · Write ${PerformanceDevices.formatBps(sys.disk.outBps)}",
            series = history.map { (it.system.disk.inBps + it.system.disk.outBps).toFloat() },
            maxVal = null,
            color = Color(0xFF10B981),
            tag = "overview-card-disk",
            onClick = { onSelectDevice(firstDisk?.let { "disk:${it.id}" } ?: "cpu") },
        )

        val firstNic = sys.nics.firstOrNull()
        OverviewMetricCard(
            title = "Network",
            subtitle = firstNic?.id ?: "All interfaces",
            headline = if (measuring) "Measuring…" else PerformanceDevices.formatBps(sys.net.inBps + sys.net.outBps),
            detail = "Down ${PerformanceDevices.formatBps(sys.net.inBps)} · Up ${PerformanceDevices.formatBps(sys.net.outBps)}",
            series = history.map { (it.system.net.inBps + it.system.net.outBps).toFloat() },
            maxVal = null,
            color = Color(0xFFF59E0B),
            tag = "overview-card-network",
            onClick = { onSelectDevice(firstNic?.let { "nic:${it.id}" } ?: "cpu") },
        )

        sys.gpus.forEachIndexed { idx, gpu ->
            OverviewMetricCard(
                title = if (sys.gpus.size > 1) "GPU $idx" else "GPU",
                subtitle = gpu.name,
                headline = "${gpu.utilPercent.roundToInt()}%",
                detail = "${PerformanceDevices.formatRam(gpu.vramUsedMB)} / ${PerformanceDevices.formatRam(gpu.vramTotalMB)} VRAM" +
                    (gpu.tempC?.let { " · ${it.roundToInt()}°C" } ?: ""),
                series = history.map { (it.system.gpus.getOrNull(idx)?.utilPercent ?: 0.0).toFloat() },
                maxVal = 100f,
                color = Color(0xFFEC4899),
                tag = "overview-card-gpu-$idx",
                onClick = { onSelectDevice("gpu:${gpu.id ?: idx}") },
            )
        }

        // Top processes quick view
        val cardShape = RoundedCornerShape(8.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(tokens.panel2.opacity(0.45).color)
                .cssBorder(tokens.border.color, cardShape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BasicText(
                    "Top Processes (${snapshot.system.processCount} total)",
                    style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                )
                BasicText(
                    "View all →",
                    Modifier.ppmClickable(onClick = onOpenProcesses).testTag("overview-view-all-processes"),
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.accent.color),
                )
            }
            val topProcs = snapshot.processes.sortedByDescending { it.cpu }.take(5)
            for (p in topProcs) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BasicText(
                            p.name,
                            style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        BasicText(
                            "#${p.pid}",
                            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BasicText(
                            String.format(Locale.US, "%.1f%%", p.cpu),
                            style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color),
                        )
                        BasicText(
                            PerformanceDevices.formatRam(p.ramMB),
                            style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text2.color),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewMetricCard(
    title: String,
    subtitle: String,
    headline: String,
    detail: String,
    series: List<Float>,
    maxVal: Float?,
    color: Color,
    tag: String,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.panel2.opacity(0.45).color)
            .cssBorder(tokens.border.color, shape)
            .pressable(shape = shape, onClick = onClick)
            .padding(12.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(title, style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color))
                BasicText(
                    subtitle,
                    style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                BasicText(
                    headline,
                    style = ppmText(18f, FontWeight.Bold, kind = FontKind.MONO, lineHeight = 22f).copy(color = tokens.text.color),
                )
                if (detail.isNotEmpty()) {
                    BasicText(
                        detail,
                        style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.color),
                    )
                }
            }
        }
        SparklineCanvas(
            values = series.takeLast(60),
            maxOverride = maxVal,
            lineColor = color,
            gridColor = tokens.border.opacity(0.35).color,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            showGrid = false,
        )
    }
}

// ── 2. Performance Panel ───────────────────────────────────────────────

@Composable
private fun PerformancePanel(
    snapshot: MetricsSnapshot,
    history: List<MetricsHistoryPoint>,
    inventory: HardwareInventory?,
    measuring: Boolean,
    selectedDevice: String,
    onSelectDevice: (String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val devices = remember(snapshot.system, inventory) {
        PerformanceDevices.buildDeviceList(snapshot.system, inventory)
    }
    val activeKey = PerformanceDevices.resolveSelected(devices, selectedDevice)
    val activeDevice = devices.firstOrNull { it.key == activeKey } ?: devices.first()
    var cpuMode by remember { mutableStateOf("overall") } // "overall" | "cores"

    BoxWithConstraints(Modifier.fillMaxSize().testTag("sysmon-performance-panel")) {
        val wide = maxWidth >= 600.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                // Left device list
                Column(
                    Modifier
                        .width(210.dp)
                        .fillMaxHeight()
                        .edge(Edge.Right, tokens.border.color)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (dev in devices) {
                        val selected = dev.key == activeKey
                        val shape = RoundedCornerShape(6.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(if (selected) tokens.accent.opacity(0.15).color else Color.Transparent)
                                .ppmClickable(shape = shape) { onSelectDevice(dev.key) }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("perf-device-${dev.key}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                BasicText(
                                    dev.label,
                                    style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f)
                                        .copy(color = if (selected) tokens.accent.color else tokens.text.color),
                                )
                                BasicText(
                                    dev.sublabel.ifEmpty { dev.kind },
                                    style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.color),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            BasicText(
                                dev.detail,
                                style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color),
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    PerformanceDeviceDetail(
                        device = activeDevice,
                        snapshot = snapshot,
                        history = history,
                        inventory = inventory,
                        measuring = measuring,
                        cpuMode = cpuMode,
                        onCpuModeChange = { cpuMode = it },
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .edge(Edge.Bottom, tokens.border.color)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (dev in devices) {
                        val selected = dev.key == activeKey
                        val shape = RoundedCornerShape(6.dp)
                        Row(
                            Modifier
                                .clip(shape)
                                .background(if (selected) tokens.accent.opacity(0.15).color else tokens.panel2.opacity(0.5).color)
                                .cssBorder((if (selected) tokens.accent else tokens.border).color, shape)
                                .ppmClickable(shape = shape) { onSelectDevice(dev.key) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag("perf-device-${dev.key}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            BasicText(
                                dev.label,
                                style = ppmText(12f, FontWeight.Medium, lineHeight = 16f)
                                    .copy(color = if (selected) tokens.accent.color else tokens.text.color),
                            )
                            BasicText(
                                dev.detail,
                                style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color),
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    PerformanceDeviceDetail(
                        device = activeDevice,
                        snapshot = snapshot,
                        history = history,
                        inventory = inventory,
                        measuring = measuring,
                        cpuMode = cpuMode,
                        onCpuModeChange = { cpuMode = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun PerformanceDeviceDetail(
    device: DeviceEntry,
    snapshot: MetricsSnapshot,
    history: List<MetricsHistoryPoint>,
    inventory: HardwareInventory?,
    measuring: Boolean,
    cpuMode: String,
    onCpuModeChange: (String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val sys = snapshot.system

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                BasicText(device.label, style = ppmText(16f, FontWeight.Bold, lineHeight = 22f).copy(color = tokens.text.color))
                if (device.sublabel.isNotEmpty()) {
                    BasicText(device.sublabel, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
                }
            }
            if (device.kind == "cpu" && sys.cpu.cores.size > 1) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.panel2.color)
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for ((modeId, label) in listOf("overall" to "Overall", "cores" to "Cores (${sys.cpu.cores.size})")) {
                        val active = cpuMode == modeId
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (active) tokens.accent.color else Color.Transparent)
                                .ppmClickable { onCpuModeChange(modeId) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            BasicText(
                                label,
                                style = ppmText(11f, FontWeight.Medium, lineHeight = 15f)
                                    .copy(color = if (active) tokens.accentFg.color else tokens.text2.color),
                            )
                        }
                    }
                }
            } else {
                BasicText(
                    if (measuring && device.kind != "memory") "Measuring…" else device.detail,
                    style = ppmText(20f, FontWeight.Bold, kind = FontKind.MONO, lineHeight = 24f).copy(color = tokens.text.color),
                )
            }
        }

        // Main Chart
        val chartShape = RoundedCornerShape(8.dp)
        if (device.kind == "cpu" && cpuMode == "cores" && sys.cpu.cores.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val chunks = sys.cpu.cores.indices.chunked(2)
                for (pair in chunks) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (coreIdx in pair) {
                            val corePct = sys.cpu.cores.getOrElse(coreIdx) { 0.0 }
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(chartShape)
                                    .background(tokens.panel2.opacity(0.4).color)
                                    .cssBorder(tokens.border.color, chartShape)
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    BasicText("CPU $coreIdx", style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.color))
                                    BasicText(
                                        "${corePct.roundToInt()}%",
                                        style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text.color),
                                    )
                                }
                                SparklineCanvas(
                                    values = history.takeLast(60).map { (it.system.cpu.cores.getOrNull(coreIdx) ?: 0.0).toFloat() },
                                    maxOverride = 100f,
                                    lineColor = Color(0xFF3B82F6),
                                    gridColor = tokens.border.opacity(0.3).color,
                                    modifier = Modifier.fillMaxWidth().height(42.dp),
                                    showGrid = true,
                                )
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            val (series, maxVal, color) = when (device.kind) {
                "cpu" -> Triple(history.takeLast(90).map { it.system.cpu.total.toFloat() }, 100f, Color(0xFF3B82F6))
                "memory" -> Triple(history.takeLast(90).map { it.system.mem.percent.toFloat() }, 100f, Color(0xFF8B5CF6))
                "disk" -> {
                    val diskId = device.id
                    Triple(
                        history.takeLast(90).map { pt ->
                            val d = pt.system.disks.firstOrNull { it.id == diskId }
                            (d?.busyPercent ?: 0.0).toFloat()
                        },
                        100f,
                        Color(0xFF10B981),
                    )
                }
                "nic" -> {
                    val nicId = device.id
                    Triple(
                        history.takeLast(90).map { pt ->
                            val n = pt.system.nics.firstOrNull { it.id == nicId }
                            ((n?.rxBps ?: 0.0) + (n?.txBps ?: 0.0)).toFloat()
                        },
                        null,
                        Color(0xFFF59E0B),
                    )
                }
                "gpu" -> {
                    val gpuId = device.id
                    Triple(
                        history.takeLast(90).map { pt ->
                            val g = pt.system.gpus.firstOrNull { (it.id ?: "") == (gpuId ?: "") } ?: pt.system.gpus.firstOrNull()
                            (g?.utilPercent ?: 0.0).toFloat()
                        },
                        100f,
                        Color(0xFFEC4899),
                    )
                }
                else -> Triple(
                    history.takeLast(90).map { pt -> (pt.system.fans.maxOfOrNull { it.rpm } ?: 0).toFloat() },
                    null,
                    Color(0xFF06B6D4),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clip(chartShape)
                    .background(tokens.panel2.opacity(0.4).color)
                    .cssBorder(tokens.border.color, chartShape)
                    .padding(10.dp),
            ) {
                SparklineCanvas(
                    values = series,
                    maxOverride = maxVal,
                    lineColor = color,
                    gridColor = tokens.border.opacity(0.45).color,
                    modifier = Modifier.fillMaxSize(),
                    showGrid = true,
                )
            }
        }

        // Stats grid
        val stats = buildList<Pair<String, String>> {
            when (device.kind) {
                "cpu" -> {
                    add("Utilization" to "${sys.cpu.total.roundToInt()}%")
                    sys.cpu.currentMHz?.let { add("Speed" to String.format(Locale.US, "%.2f GHz", it / 1000.0)) }
                    sys.cpu.tempC?.let { add("Temperature" to "${it.roundToInt()} °C") }
                    sys.cpu.powerW?.let { add("Power" to String.format(Locale.US, "%.1f W", it)) }
                    add("Processes" to "${sys.processCount}")
                    sys.cpu.threadCount?.let { add("Threads" to "$it") }
                    sys.cpu.handleCount?.let { add("Handles" to "$it") }
                    inventory?.cpu?.let { c ->
                        add("Cores" to "${c.physicalCores} physical / ${c.logicalCores} logical")
                        add("Sockets" to "${c.sockets}")
                        c.virtualization?.let { add("Virtualization" to it) }
                        c.freqGovernor?.let { add("Governor" to it) }
                    }
                }
                "memory" -> {
                    add("In use" to PerformanceDevices.formatRam(sys.mem.usedMB))
                    add("Available" to PerformanceDevices.formatRam(sys.mem.availableMB))
                    add("Total" to PerformanceDevices.formatRam(sys.mem.totalMB))
                    sys.mem.cachedMB?.let { add("Cached" to PerformanceDevices.formatRam(it)) }
                    sys.mem.swapTotalMB?.takeIf { it > 0.0 }?.let { total ->
                        add("Swap" to "${PerformanceDevices.formatRam(sys.mem.swapUsedMB ?: 0.0)} / ${PerformanceDevices.formatRam(total)}")
                    }
                    sys.mem.zramCompressedMB?.let { add("ZRAM compressed" to PerformanceDevices.formatRam(it)) }
                }
                "disk" -> {
                    val d = sys.disks.firstOrNull { it.id == device.id }
                    val info = inventory?.disks?.firstOrNull { it.id == device.id }
                    if (d != null) {
                        add("Active time" to "${d.busyPercent.roundToInt()}%")
                        add("Response time" to String.format(Locale.US, "%.1f ms", d.responseMs))
                        add("Read speed" to PerformanceDevices.formatBps(d.readBps))
                        add("Write speed" to PerformanceDevices.formatBps(d.writeBps))
                        d.tempC?.let { add("Temperature" to "${it.roundToInt()} °C") }
                    }
                    if (info != null) {
                        add("Type" to info.kind.uppercase())
                        if (info.capacityBytes > 0) {
                            add("Capacity" to String.format(Locale.US, "%.1f GB", info.capacityBytes / (1024.0 * 1024.0 * 1024.0)))
                        }
                        add("System disk" to if (info.systemDisk) "Yes" else "No")
                    }
                }
                "nic" -> {
                    val n = sys.nics.firstOrNull { it.id == device.id }
                    val info = inventory?.nics?.firstOrNull { it.id == device.id }
                    if (n != null) {
                        add("Receive" to PerformanceDevices.formatBps(n.rxBps))
                        add("Send" to PerformanceDevices.formatBps(n.txBps))
                        add("State" to n.state)
                        n.linkMbps?.let { add("Link speed" to "${it.roundToInt()} Mbps") }
                        n.ssid?.let { add("SSID" to it) }
                    }
                    if (info != null) {
                        info.driver?.let { add("Driver" to it) }
                        info.mac?.let { add("MAC" to it) }
                        if (info.ipv4.isNotEmpty()) add("IPv4" to info.ipv4.joinToString(", "))
                        if (info.ipv6.isNotEmpty()) add("IPv6" to info.ipv6.first())
                    }
                }
                "gpu" -> {
                    val g = sys.gpus.firstOrNull { (it.id ?: "") == (device.id ?: "") } ?: sys.gpus.firstOrNull()
                    val info = inventory?.gpus?.firstOrNull { it.id == device.id } ?: inventory?.gpus?.firstOrNull()
                    if (g != null) {
                        add("Utilization" to "${g.utilPercent.roundToInt()}%")
                        add("VRAM" to "${PerformanceDevices.formatRam(g.vramUsedMB)} / ${PerformanceDevices.formatRam(g.vramTotalMB)}")
                        g.tempC?.let { add("Temperature" to "${it.roundToInt()} °C") }
                        g.powerW?.let { add("Power" to String.format(Locale.US, "%.1f W", it)) }
                        g.clockMHz?.let { add("Clock" to "${it.roundToInt()} MHz") }
                    }
                    if (info != null) {
                        info.driver?.let { add("Driver" to it) }
                        info.driverVersion?.let { add("Driver version" to it) }
                        info.vulkanVersion?.let { add("Vulkan" to it) }
                    }
                }
                "fans" -> {
                    for (fan in sys.fans) {
                        add(fan.label to "${fan.rpm} RPM" + (fan.tempC?.let { " (${it.roundToInt()}°C)" } ?: ""))
                    }
                }
            }
        }

        val gridShape = RoundedCornerShape(8.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(gridShape)
                .background(tokens.panel2.opacity(0.35).color)
                .cssBorder(tokens.border.color, gridShape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (row in stats.chunked(2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for ((k, v) in row) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            BasicText(k, style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.color))
                            BasicText(
                                v,
                                style = ppmText(13f, FontWeight.Medium, kind = FontKind.MONO, lineHeight = 18f).copy(color = tokens.text.color),
                            )
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SparklineCanvas(
    values: List<Float>,
    maxOverride: Float?,
    lineColor: Color,
    gridColor: Color,
    modifier: Modifier = Modifier,
    showGrid: Boolean = true,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        if (showGrid) {
            // 10 columns x 5 rows grid matching web Performance chart
            for (i in 1..4) {
                val y = h * (i / 5f)
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            for (i in 1..9) {
                val x = w * (i / 10f)
                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
            }
        }

        if (values.isEmpty()) return@Canvas
        val peak = maxOverride ?: max(1f, values.maxOrNull() ?: 1f)
        val count = max(2, values.size)
        val linePath = Path()
        val fillPath = Path()

        values.forEachIndexed { idx, v ->
            val x = if (values.size == 1) w else (idx.toFloat() / (count - 1).toFloat()) * w
            val norm = (v / peak).coerceIn(0f, 1f)
            val y = h - norm * (h - 4f) - 2f
            if (idx == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            if (idx == values.lastIndex) {
                fillPath.lineTo(x, h)
                fillPath.close()
            }
        }

        drawPath(fillPath, lineColor.copy(alpha = 0.16f))
        drawPath(linePath, lineColor, style = Stroke(width = 2.dp.toPx()))
    }
}

// ── 3. Apps Panel ──────────────────────────────────────────────────────

@Composable
private fun AppsPanel(
    model: AppModel,
    snapshot: MetricsSnapshot,
    onError: (String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    val searchState = remember { TextFieldState("") }
    var sortKey by remember { mutableStateOf("cpu") }
    var sortDir by remember { mutableStateOf("desc") }
    var killTarget by remember { mutableStateOf<KillTarget?>(null) }
    var menuApp by remember { mutableStateOf<AppRowData?>(null) }

    val apps = snapshot.apps
    if (apps == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            BasicText(
                "Desktop application tracking is not available on this host.",
                style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text2.color),
            )
        }
        return
    }

    val allRows = remember(apps, snapshot.processes) {
        AppRowsModel.buildAppRows(apps, snapshot.processes)
    }
    val filtered = remember(allRows, searchState.text.toString(), sortKey, sortDir) {
        val f = AppRowsModel.filterAppRows(allRows, searchState.text.toString())
        AppRowsModel.sortAppRows(f, sortKey, sortDir)
    }

    Column(Modifier.fillMaxSize().testTag("sysmon-apps-panel")) {
        // Toolbar
        Row(
            Modifier
                .fillMaxWidth()
                .edge(Edge.Bottom, tokens.border.color)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val boxShape = RoundedCornerShape(6.dp)
            Row(
                Modifier
                    .weight(1f)
                    .height(30.dp)
                    .clip(boxShape)
                    .background(tokens.panel2.opacity(0.6).color)
                    .cssBorder(tokens.border.color, boxShape)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PpmIcon(ProductIcon.Search, 13.dp, tokens.text2.color)
                PpmTextField(
                    state = searchState,
                    placeholder = "Filter applications…",
                    modifier = Modifier.weight(1f).testTag("apps-search-input"),
                    style = ppmText(12f, lineHeight = 16f),
                )
            }
            for ((k, label) in listOf("cpu" to "CPU", "ram" to "Memory", "name" to "Name")) {
                val active = sortKey == k
                val chipShape = RoundedCornerShape(6.dp)
                Row(
                    Modifier
                        .height(28.dp)
                        .clip(chipShape)
                        .background(if (active) tokens.accent.opacity(0.15).color else tokens.panel2.opacity(0.5).color)
                        .ppmClickable(shape = chipShape) {
                            if (sortKey == k) sortDir = if (sortDir == "desc") "asc" else "desc"
                            else {
                                sortKey = k
                                sortDir = if (k == "name") "asc" else "desc"
                            }
                        }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        label,
                        style = ppmText(11f, FontWeight.Medium, lineHeight = 15f)
                            .copy(color = if (active) tokens.accent.color else tokens.text2.color),
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                BasicText("No running applications found.", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(filtered, key = { it.id }) { app ->
                    val byPid = snapshot.processes.associateBy { it.pid }
                    val members = app.memberPids.mapNotNull { byPid[it] }
                    val isProtected = ProcessTableModel.isGroupProtected(members)
                    val canEnd = !isProtected && members.isNotEmpty()
                    val isMobileReadOnly = LocalPpmMobile.current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .edge(Edge.Bottom, tokens.border.opacity(0.4).color)
                            .ppmClickable(enabled = !isMobileReadOnly) { if (!isMobileReadOnly) menuApp = app }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("app-row-${app.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(tokens.panel2.color),
                            contentAlignment = Alignment.Center,
                        ) {
                            PpmIcon(ProductIcon.AppWindow, 16.dp, tokens.accent.color)
                        }
                        Column(Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                BasicText(
                                    app.name,
                                    style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text.color),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (app.processCount > 1) {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(tokens.panel2.color)
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                    ) {
                                        BasicText(
                                            "${app.processCount}",
                                            style = ppmText(10f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text2.color),
                                        )
                                    }
                                }
                            }
                            BasicText(
                                String.format(Locale.US, "%.1f%% CPU · %s", app.cpu, PerformanceDevices.formatRam(app.ramMB)),
                                style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color),
                            )
                        }
                        if (!isMobileReadOnly) {
                            val btnShape = RoundedCornerShape(6.dp)
                            Box(
                                Modifier
                                    .height(26.dp)
                                    .alpha(if (canEnd) 1f else 0.4f)
                                    .clip(btnShape)
                                    .background(tokens.error.opacity(0.12).color)
                                    .ppmClickable(enabled = canEnd, shape = btnShape) {
                                        killTarget = KillTarget.GroupTarget(
                                            group = AppRowsModel.groupForApp(app),
                                            members = members,
                                        )
                                    }
                                    .padding(horizontal = 10.dp)
                                    .testTag("app-end-task-${app.id}"),
                                contentAlignment = Alignment.Center,
                            ) {
                                BasicText("End task", style = ppmText(11f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.error.color))
                            }
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .ppmClickable { menuApp = app }
                                    .testTag("app-menu-${app.id}"),
                                contentAlignment = Alignment.Center,
                            ) {
                                PpmIcon(ProductIcon.MoreVertical, 14.dp, tokens.text2.color)
                            }
                        }
                    }
                }
            }
        }
    }

    val targetApp = menuApp
    if (targetApp != null) {
        val byPid = snapshot.processes.associateBy { it.pid }
        val members = targetApp.memberPids.mapNotNull { byPid[it] }
        val endProtected = ProcessTableModel.isGroupProtected(members)
        Dialog(onDismissRequest = { menuApp = null }) {
            val shape = RoundedCornerShape(tokens.rad.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .ppmShadow(tokens.shadowPanel, shape)
                    .clip(shape)
                    .background(tokens.panel2.over(tokens.bgSolid).color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(12.dp)
                    .testTag("app-row-menu"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .alpha(if (endProtected) 0.5f else 1f)
                        .clip(RoundedCornerShape(6.dp))
                        .ppmClickable(enabled = !endProtected && members.isNotEmpty()) {
                            menuApp = null
                            killTarget = KillTarget.GroupTarget(
                                group = AppRowsModel.groupForApp(targetApp),
                                members = members,
                            )
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("app-menu-end-application"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        "End application",
                        style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.error.color),
                    )
                }
                if (endProtected) {
                    BasicText(
                        "One of this app's processes is protected, so PPM will not end it.",
                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.color),
                    )
                }
            }
        }
    }

    val pendingKill = killTarget
    if (pendingKill != null) {
        KillConfirmDialog(
            model = model,
            parentScope = scope,
            target = pendingKill,
            onDismiss = { killTarget = null },
        )
    }
}

// ── 4. Processes Panel ─────────────────────────────────────────────────

@Composable
private fun ProcessesPanel(
    model: AppModel,
    snapshot: MetricsSnapshot,
    measuring: Boolean,
    onError: (String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("grouped") }
    var ppmOnly by remember { mutableStateOf(false) }
    val searchState = remember { TextFieldState("") }
    var sortKey by remember { mutableStateOf<String?>("cpu") }
    var sortDir by remember { mutableStateOf("desc") }
    var expandedGroups by remember { mutableStateOf(emptySet<String>()) }
    var killTarget by remember { mutableStateOf<KillTarget?>(null) }
    var detailsPid by remember { mutableStateOf<Int?>(null) }
    var menuProc by remember { mutableStateOf<ProcessInfo?>(null) }
    var signalSubmenuOpen by remember { mutableStateOf(false) }

    val built = remember(
        snapshot.processes,
        snapshot.groups,
        mode,
        ppmOnly,
        searchState.text.toString(),
        sortKey,
        sortDir,
        expandedGroups,
    ) {
        ProcessTableModel.buildRows(
            processes = snapshot.processes,
            groups = snapshot.groups,
            mode = mode,
            ppmOnly = ppmOnly,
            query = searchState.text.toString(),
            sortKey = sortKey,
            sortDir = sortDir,
            expanded = expandedGroups,
        )
    }

    val byPid = remember(snapshot.processes) { snapshot.processes.associateBy { it.pid } }

    Column(Modifier.fillMaxSize().testTag("sysmon-processes-panel")) {
        // Controls bar
        Column(
            Modifier
                .fillMaxWidth()
                .edge(Edge.Bottom, tokens.border.color)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Grouped / Flat toggle
                Row(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.panel2.color)
                        .padding(2.dp),
                ) {
                    for ((mId, label) in listOf("grouped" to "Grouped", "flat" to "Flat")) {
                        val active = mode == mId
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (active) tokens.accent.color else Color.Transparent)
                                .ppmClickable { mode = mId }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("proc-mode-$mId"),
                        ) {
                            BasicText(
                                label,
                                style = ppmText(11f, FontWeight.Medium, lineHeight = 15f)
                                    .copy(color = if (active) tokens.accentFg.color else tokens.text2.color),
                            )
                        }
                    }
                }

                // PPM only chip
                val ppmShape = RoundedCornerShape(6.dp)
                Box(
                    Modifier
                        .clip(ppmShape)
                        .background(if (ppmOnly) tokens.accent.opacity(0.16).color else tokens.panel2.opacity(0.6).color)
                        .cssBorder((if (ppmOnly) tokens.accent else tokens.border).color, ppmShape)
                        .ppmClickable(shape = ppmShape) { ppmOnly = !ppmOnly }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                        .testTag("proc-ppm-only"),
                ) {
                    BasicText(
                        "PPM only",
                        style = ppmText(11f, FontWeight.Medium, lineHeight = 15f)
                            .copy(color = if (ppmOnly) tokens.accent.color else tokens.text2.color),
                    )
                }

                // Search input
                val searchShape = RoundedCornerShape(6.dp)
                Row(
                    Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(searchShape)
                        .background(tokens.panel2.opacity(0.6).color)
                        .cssBorder(tokens.border.color, searchShape)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PpmIcon(ProductIcon.Search, 12.dp, tokens.text2.color)
                    PpmTextField(
                        state = searchState,
                        placeholder = "Filter processes…",
                        modifier = Modifier.weight(1f).testTag("proc-search-input"),
                        style = ppmText(12f, lineHeight = 16f),
                    )
                }
            }

            // Sort header + Totals
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((colKey, label) in listOf("name" to "Name", "cpu" to "CPU", "ram" to "Memory", "disk" to "Disk")) {
                        val active = sortKey == colKey
                        val arrow = if (!active) "" else if (sortDir == "asc") " ↑" else " ↓"
                        BasicText(
                            "$label$arrow",
                            Modifier
                                .ppmClickable {
                                    val next = ProcessTableModel.toggleSort(sortKey, sortDir, colKey)
                                    sortKey = next.first
                                    sortDir = next.second
                                }
                                .testTag("proc-sort-$colKey"),
                            style = ppmText(11f, if (active) FontWeight.SemiBold else FontWeight.Normal, lineHeight = 15f)
                                .copy(color = if (active) tokens.accent.color else tokens.text2.color),
                        )
                    }
                }
                BasicText(
                    "${built.totals.count} procs · ${if (measuring) "—" else String.format(Locale.US, "%.1f%%", built.totals.cpu)} · ${PerformanceDevices.formatRam(built.totals.ramMB)}",
                    style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color),
                )
            }
        }

        // Rows
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(
                items = built.rows,
                key = { row ->
                    when (row) {
                        is TableRow.GroupRow -> "g:${row.group.key}"
                        is TableRow.ProcRow -> "p:${row.proc.pid}"
                    }
                },
            ) { row ->
                when (row) {
                    is TableRow.GroupRow -> {
                        val g = row.group
                        val members = g.pids.mapNotNull { byPid[it] }
                        val isProtected = ProcessTableModel.isGroupProtected(members)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(tokens.panel2.opacity(0.3).color)
                                .edge(Edge.Bottom, tokens.border.opacity(0.5).color)
                                .ppmClickable {
                                    expandedGroups = if (g.key in expandedGroups) expandedGroups - g.key else expandedGroups + g.key
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("proc-group-${g.key}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PpmIcon(
                                if (row.expanded) ProductIcon.ChevronDown else ProductIcon.ChevronRight,
                                14.dp,
                                tokens.text2.color,
                            )
                            Column(Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    BasicText(
                                        g.label,
                                        style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(tokens.panel2.color)
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                    ) {
                                        BasicText(
                                            "${g.count}",
                                            style = ppmText(10f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text2.color),
                                        )
                                    }
                                    if (g.ppm) {
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(tokens.accent.opacity(0.15).color)
                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                        ) {
                                            BasicText(
                                                "PPM",
                                                style = ppmText(9f, FontWeight.SemiBold, lineHeight = 13f).copy(color = tokens.accent.color),
                                            )
                                        }
                                    }
                                }
                            }
                            BasicText(
                                if (measuring) "—" else String.format(Locale.US, "%.1f%%", g.cpu),
                                style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text.color),
                            )
                            BasicText(
                                PerformanceDevices.formatRam(g.ramMB),
                                style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color),
                            )
                            if (!LocalPpmMobile.current) {
                                val groupKillTooltip = if (isProtected) {
                                    "Protected: this app contains a PPM or OS-critical process — cannot be ended as a whole."
                                } else {
                                    "End ${g.label} (${g.count} processes)"
                                }
                                Box(
                                    Modifier
                                        .size(22.dp)
                                        .alpha(if (isProtected || members.isEmpty()) 0.4f else 1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .webTitle(groupKillTooltip)
                                        .ppmClickable(enabled = !isProtected && members.isNotEmpty()) {
                                            killTarget = KillTarget.GroupTarget(g, members)
                                        }
                                        .semantics {
                                            contentDescription = "End ${g.label} and its ${g.count} processes"
                                        }
                                        .testTag("proc-group-kill-${g.key}"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    PpmIcon(
                                        ProductIcon.X,
                                        13.dp,
                                        if (isProtected || members.isEmpty()) tokens.text2.color else tokens.error.color,
                                    )
                                }
                            }
                        }
                    }
                    is TableRow.ProcRow -> {
                        val p = row.proc
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .edge(Edge.Bottom, tokens.border.opacity(0.35).color)
                                .ppmClickable { detailsPid = p.pid }
                                .padding(start = if (row.indent) 28.dp else 12.dp, end = 12.dp, top = 7.dp, bottom = 7.dp)
                                .testTag("proc-row-${p.pid}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    BasicText(
                                        p.name,
                                        style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    BasicText(
                                        "#${p.pid}",
                                        style = ppmText(10f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text2.color),
                                    )
                                    if (p.ppm) {
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(tokens.accent.opacity(0.15).color)
                                                .padding(horizontal = 4.dp, vertical = 1.dp),
                                        ) {
                                            BasicText(
                                                "PPM",
                                                style = ppmText(9f, FontWeight.SemiBold, lineHeight = 12f).copy(color = tokens.accent.color),
                                            )
                                        }
                                    }
                                }
                                if (p.command != p.name && p.command.isNotEmpty()) {
                                    BasicText(
                                        p.command,
                                        style = ppmText(10f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text2.opacity(0.8).color),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            BasicText(
                                if (measuring) "—" else String.format(Locale.US, "%.1f%%", p.cpu),
                                style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text.color),
                            )
                            BasicText(
                                PerformanceDevices.formatRam(p.ramMB),
                                style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color),
                            )
                            if (!LocalPpmMobile.current) {
                                if (!p.protected) {
                                    Box(
                                        Modifier
                                            .size(22.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .ppmClickable { killTarget = KillTarget.SingleProcess(p) }
                                            .testTag("proc-kill-${p.pid}"),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        PpmIcon(ProductIcon.X, 13.dp, tokens.error.color)
                                    }
                                } else {
                                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                                        PpmIcon(ProductIcon.Lock, 11.dp, tokens.text2.opacity(0.6).color)
                                    }
                                }
                                Box(
                                    Modifier
                                        .size(22.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .ppmClickable {
                                            menuProc = p
                                            signalSubmenuOpen = false
                                        }
                                        .testTag("proc-menu-${p.pid}"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    PpmIcon(ProductIcon.MoreVertical, 13.dp, tokens.text2.color)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val targetProc = menuProc
    if (targetProc != null) {
        val availableSignals = snapshot.signals.map(::normalizeProcessSignal).ifEmpty { DEFAULT_PROCESS_SIGNALS }
        Dialog(onDismissRequest = { menuProc = null }) {
            val shape = RoundedCornerShape(tokens.rad.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .ppmShadow(tokens.shadowPanel, shape)
                    .clip(shape)
                    .background(tokens.panel2.over(tokens.bgSolid).color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(12.dp)
                    .testTag("proc-row-menu"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .ppmClickable {
                            menuProc = null
                            detailsPid = targetProc.pid
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("proc-menu-details"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        "Details…",
                        style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text.color),
                    )
                }
                if (availableSignals.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .ppmClickable { signalSubmenuOpen = !signalSubmenuOpen }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag("proc-menu-send-signal"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        BasicText(
                            "Send signal",
                            style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text.color),
                        )
                        PpmIcon(
                            if (signalSubmenuOpen) ProductIcon.ChevronDown else ProductIcon.ChevronRight,
                            14.dp,
                            tokens.text2.color,
                        )
                    }
                    if (signalSubmenuOpen) {
                        Column(
                            Modifier.fillMaxWidth().padding(start = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            for (sig in availableSignals) {
                                val destructive = sig == "KILL" || sig == "STOP"
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .alpha(if (targetProc.protected) 0.5f else 1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .ppmClickable(enabled = !targetProc.protected) {
                                            val api = model.api
                                            menuProc = null
                                            if (api != null) {
                                                scope.launch {
                                                    try {
                                                        val res = api.signalProcess(targetProc.pid, targetProc.startedAt, sig, false)
                                                        val count = (res["signalled"].array ?: res["signaled"].array)?.size ?: 1
                                                        val msg = if (count > 1) {
                                                            "Sent SIG$sig to ${targetProc.name} and ${count - 1} child processes"
                                                        } else {
                                                            "Sent SIG$sig to ${targetProc.name} (${targetProc.pid})"
                                                        }
                                                        model.toast(ErrorToastMessage(msg, kind = ErrorToastMessage.Kind.SUCCESS))
                                                    } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (e: Exception) {
                                                        val msg = e.message?.takeIf { it.isNotBlank() } ?: "Could not signal ${targetProc.name}"
                                                        model.toast(ErrorToastMessage(msg, kind = ErrorToastMessage.Kind.ERROR))
                                                    }
                                                }
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                        .testTag("proc-menu-signal-$sig"),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    BasicText(
                                        SIGNAL_LABELS[sig] ?: sig,
                                        style = ppmText(12f, lineHeight = 16f)
                                            .copy(color = if (destructive) tokens.error.color else tokens.text.color),
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .alpha(if (targetProc.protected) 0.5f else 1f)
                        .clip(RoundedCornerShape(6.dp))
                        .ppmClickable(enabled = !targetProc.protected) {
                            menuProc = null
                            killTarget = KillTarget.SingleProcess(targetProc)
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("proc-menu-end"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        "End process",
                        style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.error.color),
                    )
                }
                if (targetProc.protected) {
                    BasicText(
                        "PPM or OS-critical process — it cannot be ended or signalled.",
                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.color),
                    )
                }
            }
        }
    }

    val pendingKill = killTarget
    if (pendingKill != null) {
        KillConfirmDialog(
            model = model,
            parentScope = scope,
            target = pendingKill,
            onDismiss = { killTarget = null },
        )
    }

    val inspectPid = detailsPid
    if (inspectPid != null) {
        ProcessDetailsDialog(
            model = model,
            pid = inspectPid,
            onDismiss = { detailsPid = null },
        )
    }
}

@Composable
private fun KillConfirmDialog(
    model: AppModel,
    parentScope: CoroutineScope,
    target: KillTarget,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var includeTree by remember(target) { mutableStateOf(false) }

    val isGroup = target is KillTarget.GroupTarget
    val title = if (isGroup) "End app" else "End process"
    val prompt = when (target) {
        is KillTarget.SingleProcess -> "End ${target.proc.name} (pid ${target.proc.pid})?"
        is KillTarget.GroupTarget -> "End ${target.group.label} and all its ${target.members.size} processes?"
    }
    val cpu = when (target) {
        is KillTarget.SingleProcess -> target.proc.cpu
        is KillTarget.GroupTarget -> target.group.cpu
    }
    val ramMB = when (target) {
        is KillTarget.SingleProcess -> target.proc.ramMB
        is KillTarget.GroupTarget -> target.group.ramMB
    }

    fun executeKill() {
        val tree = includeTree
        onDismiss()
        val api = model.api ?: return
        parentScope.launch {
            when (target) {
                is KillTarget.SingleProcess -> {
                    val req = ProcessTableModel.buildKillRequest(target.proc, tree)
                    try {
                        api.killProcess(req.pid, req.startedAt, req.tree)
                        model.toast(ErrorToastMessage("Ended ${target.proc.name} (${target.proc.pid})", kind = ErrorToastMessage.Kind.SUCCESS))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val msg = e.message ?: "Failed to end ${target.proc.name}"
                        model.toast(ErrorToastMessage(msg, kind = ErrorToastMessage.Kind.ERROR))
                    }
                }
                is KillTarget.GroupTarget -> {
                    val failures = mutableListOf<String>()
                    for (req in ProcessTableModel.buildGroupKillRequests(target.group, target.members)) {
                        try {
                            api.killProcess(req.pid, req.startedAt, req.tree)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failures += e.message ?: "pid ${req.pid}"
                        }
                    }
                    if (failures.isEmpty()) {
                        model.toast(ErrorToastMessage("Ended ${target.group.label} (${target.members.size} processes)", kind = ErrorToastMessage.Kind.SUCCESS))
                    } else {
                        val msg = "${target.group.label}: ${failures.size} of ${target.members.size} could not be ended — ${failures.first()}"
                        model.toast(ErrorToastMessage(msg, kind = ErrorToastMessage.Kind.ERROR))
                    }
                }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(tokens.rad.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .ppmShadow(tokens.shadowPanel, shape)
                .clip(shape)
                .background(tokens.panel2.over(tokens.bgSolid).color)
                .cssBorder(tokens.border.color, shape)
                .padding(20.dp)
                .testTag("kill-confirm-dialog"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText(title, style = ppmText(16f, FontWeight.SemiBold, lineHeight = 22f).copy(color = tokens.text.color))
            BasicText(prompt, style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text.color))
            BasicText(
                String.format(Locale.US, "CPU %.1f%% · RAM %s", cpu, PerformanceDevices.formatRam(ramMB)),
                Modifier.testTag("sysmon-kill-stats"),
                style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text2.color),
            )

            if (target is KillTarget.SingleProcess) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .ppmClickable { includeTree = !includeTree }
                        .padding(vertical = 4.dp)
                        .testTag("sysmon-kill-tree"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (includeTree) tokens.accent.color else Color.Transparent)
                            .cssBorder((if (includeTree) tokens.accent else tokens.border).color, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (includeTree) PpmIcon(ProductIcon.Check, 11.dp, tokens.accentFg.color)
                    }
                    BasicText(
                        "Also end child processes",
                        style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text.color),
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SecondaryButton("Cancel", Modifier.weight(1f), onClick = onDismiss)
                val btnShape = RoundedCornerShape(12.dp)
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(btnShape)
                        .background(tokens.error.color)
                        .ppmClickable(shape = btnShape) { executeKill() }
                        .testTag("sysmon-kill-confirm-ok"),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        if (isGroup) "End ${(target as KillTarget.GroupTarget).members.size} processes" else "End process",
                        style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = Color.White),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcessDetailsDialog(
    model: AppModel,
    pid: Int,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var details by remember { mutableStateOf<ProcessDetails?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(pid) {
        val api = model.api ?: run {
            loading = false
            return@LaunchedEffect
        }
        details = runCatching { ProcessDetails.from(api.processDetails(pid)) }.getOrNull()
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(tokens.rad.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .ppmShadow(tokens.shadowPanel, shape)
                .clip(shape)
                .background(tokens.panel2.over(tokens.bgSolid).color)
                .cssBorder(tokens.border.color, shape)
                .padding(20.dp)
                .testTag("process-details-dialog"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(
                "Process #$pid ${details?.name.orEmpty()}",
                style = ppmText(16f, FontWeight.SemiBold, lineHeight = 22f).copy(color = tokens.text.color),
            )
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                    PpmIcon(ProductIcon.Loader2, 18.dp, tokens.text2.color, spinning = true)
                }
            } else if (details == null) {
                BasicText("Process exited or could not be inspected.", style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text2.color))
            } else {
                val d = details!!
                val rows = listOfNotNull(
                    "PID" to "${d.pid}",
                    "Parent PID" to "${d.ppid}",
                    d.user?.let { "User" to it },
                    d.state?.let { "State" to it },
                    d.threads?.let { "Threads" to "$it" },
                    d.nice?.let { "Nice" to "$it" },
                    d.exe?.let { "Executable" to it },
                    d.cwd?.let { "Working dir" to it },
                    d.cgroup?.let { "Cgroup" to it },
                    d.command?.let { "Command" to it },
                )
                Column(
                    Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for ((k, v) in rows) {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            BasicText(k, style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.color))
                            BasicText(
                                v,
                                style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color),
                            )
                        }
                    }
                }
            }
            SecondaryButton("Close", Modifier.fillMaxWidth().padding(top = 6.dp), onClick = onDismiss)
        }
    }
}

// ── 5. Services Panel ──────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServicesPanel(
    model: AppModel,
    processes: List<ProcessInfo>,
    initialServices: ServicesSnapshot? = null,
    onError: (String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf(initialServices) }
    var loading by remember { mutableStateOf(initialServices == null) }
    var scopeTab by remember { mutableStateOf("system") } // "system" | "user"
    var filterChip by remember { mutableStateOf("all") } // "all" | "running" | "failed" | "enabled"
    val searchState = remember { TextFieldState("") }
    var busyUnit by remember { mutableStateOf<String?>(null) }
    var detailsTarget by remember { mutableStateOf<ServiceInfo?>(null) }
    var menuService by remember { mutableStateOf<ServiceInfo?>(null) }
    var pendingConfirm by remember { mutableStateOf<Pair<ServiceInfo, String>?>(null) }
    var inFlight by remember { mutableStateOf(false) }

    suspend fun fetchServices() {
        if (inFlight) return
        val api = model.api ?: run {
            loading = false
            return
        }
        inFlight = true
        try {
            val res = api.systemServices()
            snapshot = ServicesSnapshot.from(res)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e.message ?: "Could not list services")
        } finally {
            inFlight = false
            loading = false
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model.api, lifecycle) {
        if (model.api == null) {
            loading = false
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (initialServices == null) fetchServices()
            while (isActive) {
                delay(3_000L)
                if (model.api != null) fetchServices()
            }
        }
    }

    fun executeAction(service: ServiceInfo, action: String) {
        val api = model.api ?: return
        busyUnit = "${service.scope}:${service.unit}"
        scope.launch {
            try {
                val res = api.systemServiceAction(service.scope, service.unit, action)
                val note = res["note"].string?.takeIf { it.isNotBlank() }
                model.toast(ErrorToastMessage("${service.unit}: $action done", description = note, kind = ErrorToastMessage.Kind.SUCCESS))
                fetchServices()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message?.takeIf { it.isNotBlank() && !it.startsWith("The server answered HTTP ") }
                    ?: "Could not $action ${service.unit}"
                model.toast(ErrorToastMessage(msg, kind = ErrorToastMessage.Kind.ERROR))
            } finally {
                busyUnit = null
            }
        }
    }

    fun triggerAction(service: ServiceInfo, action: String) {
        if (ServicesModel.needsConfirm(action)) {
            pendingConfirm = service to action
        } else {
            executeAction(service, action)
        }
    }

    val byUnit = remember(processes) { ServicesModel.rollUpByUnit(processes) }
    val allServices = snapshot?.services.orEmpty()
    val sysCounts = remember(allServices) { ServicesModel.serviceCounts(allServices, "system") }
    val userCounts = remember(allServices) { ServicesModel.serviceCounts(allServices, "user") }
    val shaped = remember(allServices, scopeTab, filterChip, searchState.text.toString()) {
        ServicesModel.shapeServices(allServices, scopeTab, filterChip, searchState.text.toString())
    }

    Column(Modifier.fillMaxSize().testTag("sysmon-services-panel")) {
        // Scope & Filter Toolbar
        Column(
            Modifier
                .fillMaxWidth()
                .edge(Edge.Bottom, tokens.border.color)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.panel2.color)
                        .padding(2.dp),
                ) {
                    for ((sc, label) in listOf("system" to "System (${sysCounts.running}/${sysCounts.total})", "user" to "User (${userCounts.running}/${userCounts.total})")) {
                        val active = scopeTab == sc
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (active) tokens.accent.color else Color.Transparent)
                                .ppmClickable { scopeTab = sc }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("services-scope-$sc"),
                        ) {
                            BasicText(
                                label,
                                style = ppmText(11f, FontWeight.Medium, lineHeight = 15f)
                                    .copy(color = if (active) tokens.accentFg.color else tokens.text2.color),
                            )
                        }
                    }
                }

                val searchShape = RoundedCornerShape(6.dp)
                Row(
                    Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(searchShape)
                        .background(tokens.panel2.opacity(0.6).color)
                        .cssBorder(tokens.border.color, searchShape)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PpmIcon(ProductIcon.Search, 12.dp, tokens.text2.color)
                    PpmTextField(
                        state = searchState,
                        placeholder = "Filter services…",
                        modifier = Modifier.weight(1f).testTag("services-search-input"),
                        style = ppmText(12f, lineHeight = 16f),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((fId, label) in listOf("all" to "All", "running" to "Running", "failed" to "Failed", "enabled" to "Enabled")) {
                    val active = filterChip == fId
                    val chipShape = RoundedCornerShape(6.dp)
                    Box(
                        Modifier
                            .clip(chipShape)
                            .background(if (active) tokens.accent.opacity(0.15).color else tokens.panel2.opacity(0.5).color)
                            .ppmClickable(shape = chipShape) { filterChip = fId }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("services-filter-$fId"),
                    ) {
                        BasicText(
                            label,
                            style = ppmText(11f, FontWeight.Medium, lineHeight = 15f)
                                .copy(color = if (active) tokens.accent.color else tokens.text2.color),
                        )
                    }
                }
            }
        }

        if (loading && snapshot == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PpmIcon(ProductIcon.Loader2, 18.dp, tokens.text2.color, spinning = true)
            }
        } else if (snapshot?.supported == false) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                BasicText(
                    "Service management is not supported on this host.",
                    style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text2.color),
                )
            }
        } else if (shaped.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                BasicText("No matching services.", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(shaped, key = { "${it.scope}:${it.unit}" }) { svc ->
                    val res = ServicesModel.resourcesFor(svc, byUnit, snapshot?.manager ?: "systemd")
                    val isBusy = busyUnit == "${svc.scope}:${svc.unit}"
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .edge(Edge.Bottom, tokens.border.opacity(0.4).color)
                            .combinedClickable(
                                onClick = { detailsTarget = svc },
                                onLongClick = { menuService = svc },
                            )
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                            .testTag("service-row-${svc.unit}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val dotColor = when {
                            svc.failed -> Color(0xFFEF4444)
                            svc.running -> Color(0xFF22C55E)
                            else -> tokens.text2.opacity(0.4).color
                        }
                        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                        Column(Modifier.weight(1f)) {
                            BasicText(
                                svc.unit,
                                style = ppmText(12f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (svc.description.isNotEmpty()) {
                                BasicText(
                                    svc.description,
                                    style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.color),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (res != null && res.count > 0) {
                                BasicText(
                                    String.format(Locale.US, "%.1f%% CPU · %s", res.cpu, PerformanceDevices.formatRam(res.ramMB)),
                                    style = ppmText(10f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text2.opacity(0.8).color),
                                )
                            }
                        }

                        // Action buttons
                        if (isBusy) {
                            PpmIcon(ProductIcon.Loader2, 14.dp, tokens.text2.color, spinning = true)
                        } else if (!LocalPpmMobile.current) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (svc.running) {
                                    val stopRefused = svc.refused.containsKey("stop")
                                    Box(
                                        Modifier
                                            .size(24.dp)
                                            .alpha(if (stopRefused) 0.35f else 1f)
                                            .clip(RoundedCornerShape(4.dp))
                                            .ppmClickable(enabled = !stopRefused) { triggerAction(svc, "stop") }
                                            .testTag("service-stop-${svc.unit}"),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        PpmIcon(ProductIcon.Square, 12.dp, tokens.error.color)
                                    }
                                    val restartRefused = svc.refused.containsKey("restart")
                                    Box(
                                        Modifier
                                            .size(24.dp)
                                            .alpha(if (restartRefused) 0.35f else 1f)
                                            .clip(RoundedCornerShape(4.dp))
                                            .ppmClickable(enabled = !restartRefused) { triggerAction(svc, "restart") }
                                            .testTag("service-restart-${svc.unit}"),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        PpmIcon(ProductIcon.RotateCw, 12.dp, tokens.text2.color)
                                    }
                                } else {
                                    val startRefused = svc.refused.containsKey("start")
                                    Box(
                                        Modifier
                                            .size(24.dp)
                                            .alpha(if (startRefused) 0.35f else 1f)
                                            .clip(RoundedCornerShape(4.dp))
                                            .ppmClickable(enabled = !startRefused) { triggerAction(svc, "start") }
                                            .testTag("service-start-${svc.unit}"),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        PpmIcon(ProductIcon.Play, 12.dp, Color(0xFF22C55E))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val ctxService = menuService
    if (ctxService != null) {
        val manager = snapshot?.manager ?: "systemd"
        val allActions = listOf("start", "stop", "restart", "enable", "disable")
        val allowed = allActions.filter { !ctxService.refused.containsKey(it) }
        val reason = allActions.firstNotNullOfOrNull { ctxService.refused[it]?.takeIf { r -> r.isNotBlank() } }
        fun actionLabel(action: String): String {
            val base = when (action) {
                "start" -> "Start"
                "stop" -> "Stop"
                "restart" -> "Restart"
                "enable" -> "Enable at boot"
                "disable" -> "Disable at boot"
                else -> action
            }
            return if (manager == "launchd" && ctxService.scope == "user") {
                base.replace(" at boot", " at login")
            } else {
                base
            }
        }
        Dialog(onDismissRequest = { menuService = null }) {
            val shape = RoundedCornerShape(tokens.rad.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .ppmShadow(tokens.shadowPanel, shape)
                    .clip(shape)
                    .background(tokens.panel2.over(tokens.bgSolid).color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(16.dp)
                    .testTag("service-menu-dialog"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (action in allowed) {
                    val destructive = action == "stop" || action == "disable"
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (destructive) tokens.error.opacity(0.12).color else tokens.panel.color)
                            .ppmClickable(shape = RoundedCornerShape(8.dp)) {
                                menuService = null
                                triggerAction(ctxService, action)
                            }
                            .padding(horizontal = 12.dp)
                            .testTag("service-menu-action-$action"),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicText(
                            actionLabel(action),
                            style = ppmText(13f, FontWeight.Medium, lineHeight = 18f)
                                .copy(color = if (destructive) tokens.error.color else tokens.text.color),
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.panel.color)
                        .ppmClickable(shape = RoundedCornerShape(8.dp)) {
                            menuService = null
                            detailsTarget = ctxService
                        }
                        .padding(horizontal = 12.dp)
                        .testTag("service-menu-details"),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicText(
                        "Details and log",
                        style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text.color),
                    )
                }
                if (reason != null) {
                    BasicText(
                        reason,
                        style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.color),
                    )
                }
            }
        }
    }

    val confirmPair = pendingConfirm
    if (confirmPair != null) {
        val (svc, act) = confirmPair
        val manager = snapshot?.manager ?: "systemd"
        val wording = ServicesModel.confirmWordingFor(manager, svc, act)
        if (wording != null) {
            val scopeLabel = when {
                manager == "scm" -> "Windows"
                svc.scope == "system" -> "System"
                else -> "User"
            }
            val kindLabel = if (manager == "launchd") "job" else "service"
            val pidSuffix = if (svc.mainPid != null) " · pid ${svc.mainPid}" else ""
            Dialog(onDismissRequest = { pendingConfirm = null }) {
                val shape = RoundedCornerShape(tokens.rad.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .ppmShadow(tokens.shadowPanel, shape)
                        .clip(shape)
                        .background(tokens.panel2.over(tokens.bgSolid).color)
                        .cssBorder(tokens.border.color, shape)
                        .padding(20.dp)
                        .testTag("sysmon-service-confirm"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BasicText(wording.title, style = ppmText(16f, FontWeight.SemiBold, lineHeight = 22f).copy(color = tokens.text.color))
                    BasicText(wording.body, style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text.color))
                    BasicText(
                        "$scopeLabel $kindLabel$pidSuffix",
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SecondaryButton("Cancel", Modifier.weight(1f), onClick = { pendingConfirm = null })
                        val btnShape = RoundedCornerShape(12.dp)
                        Box(
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(btnShape)
                                .background(tokens.error.color)
                                .ppmClickable(shape = btnShape) {
                                    pendingConfirm = null
                                    executeAction(svc, act)
                                }
                                .testTag("sysmon-service-confirm-ok"),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                wording.cta,
                                style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = Color.White),
                            )
                        }
                    }
                }
            }
        }
    }

    val inspectSvc = detailsTarget
    if (inspectSvc != null) {
        ServiceDetailsDialog(
            model = model,
            service = inspectSvc,
            onDismiss = { detailsTarget = null },
        )
    }
}

@Composable
private fun ServiceDetailsDialog(
    model: AppModel,
    service: ServiceInfo,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var details by remember { mutableStateOf<ServiceDetails?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(service.scope, service.unit) {
        val api = model.api ?: run {
            loading = false
            return@LaunchedEffect
        }
        details = runCatching {
            ServiceDetails.from(api.systemServiceDetails(service.scope, service.unit))
        }.getOrNull()
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(tokens.rad.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .ppmShadow(tokens.shadowPanel, shape)
                .clip(shape)
                .background(tokens.panel2.over(tokens.bgSolid).color)
                .cssBorder(tokens.border.color, shape)
                .padding(20.dp)
                .testTag("service-details-dialog"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(
                service.unit,
                style = ppmText(15f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 20f).copy(color = tokens.text.color),
            )
            if (service.description.isNotEmpty()) {
                BasicText(service.description, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
            }
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                    PpmIcon(ProductIcon.Loader2, 18.dp, tokens.text2.color, spinning = true)
                }
            } else {
                val logs = details?.logs.orEmpty()
                if (logs.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(tokens.bgSolid.color)
                            .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        for (line in logs.takeLast(80)) {
                            BasicText(
                                line.message,
                                style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text.color),
                            )
                        }
                    }
                } else {
                    BasicText("No recent journal lines.", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
                }
            }
            SecondaryButton("Close", Modifier.fillMaxWidth().padding(top = 6.dp), onClick = onDismiss)
        }
    }
}
