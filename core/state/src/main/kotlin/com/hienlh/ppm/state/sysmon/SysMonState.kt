package com.hienlh.ppm.state.sysmon

import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.long
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

const val METRICS_HISTORY_MAX = 900
const val GROUPS_RETENTION = 60

val SIGNAL_LABELS: Map<String, String> = linkedMapOf(
    "STOP" to "Suspend (SIGSTOP)",
    "CONT" to "Continue (SIGCONT)",
    "TERM" to "Terminate (SIGTERM)",
    "KILL" to "Kill (SIGKILL)",
    "HUP" to "Hang up (SIGHUP)",
    "INT" to "Interrupt (SIGINT)",
    "USR1" to "User signal 1",
    "USR2" to "User signal 2",
)

val DEFAULT_PROCESS_SIGNALS: List<String> = listOf("STOP", "CONT", "TERM", "KILL", "HUP", "INT", "USR1", "USR2")

fun normalizeProcessSignal(signal: String): String =
    signal.trim().uppercase().removePrefix("SIG")

data class CpuMetrics(
    val total: Double = 0.0,
    val cores: List<Double> = emptyList(),
    val model: String = "",
    val kernelPercent: Double? = null,
    val currentMHz: Double? = null,
    val tempC: Double? = null,
    val powerW: Double? = null,
    val threadCount: Int? = null,
    val handleCount: Int? = null,
    val uptimeSec: Long? = null,
) {
    companion object {
        fun from(json: JsonElement?): CpuMetrics {
            if (json == null) return CpuMetrics()
            return CpuMetrics(
                total = json["total"].double ?: 0.0,
                cores = json["cores"].array.orEmpty().mapNotNull { it.double },
                model = json["model"].string.orEmpty(),
                kernelPercent = json["kernelPercent"].double,
                currentMHz = json["currentMHz"].double,
                tempC = json["tempC"].double,
                powerW = json["powerW"].double,
                threadCount = json["threadCount"].int,
                handleCount = json["handleCount"].int,
                uptimeSec = json["uptimeSec"].long,
            )
        }
    }
}

data class MemoryMetrics(
    val totalMB: Double = 0.0,
    val usedMB: Double = 0.0,
    val availableMB: Double = 0.0,
    val percent: Double = 0.0,
    val cachedMB: Double? = null,
    val swapTotalMB: Double? = null,
    val swapUsedMB: Double? = null,
    val zramCompressedMB: Double? = null,
    val zramSavingsMB: Double? = null,
) {
    companion object {
        fun from(json: JsonElement?): MemoryMetrics {
            if (json == null) return MemoryMetrics()
            return MemoryMetrics(
                totalMB = json["totalMB"].double ?: 0.0,
                usedMB = json["usedMB"].double ?: 0.0,
                availableMB = json["availableMB"].double ?: 0.0,
                percent = json["percent"].double ?: 0.0,
                cachedMB = json["cachedMB"].double,
                swapTotalMB = json["swapTotalMB"].double,
                swapUsedMB = json["swapUsedMB"].double,
                zramCompressedMB = json["zramCompressedMB"].double,
                zramSavingsMB = json["zramSavingsMB"].double,
            )
        }
    }
}

data class RateMetrics(
    val inBps: Double = 0.0,
    val outBps: Double = 0.0,
    val available: Boolean = false,
) {
    companion object {
        fun from(json: JsonElement?): RateMetrics {
            if (json == null) return RateMetrics()
            return RateMetrics(
                inBps = json["inBps"].double ?: 0.0,
                outBps = json["outBps"].double ?: 0.0,
                available = json["available"].bool ?: false,
            )
        }
    }
}

data class GpuMetrics(
    val id: String? = null,
    val name: String = "",
    val utilPercent: Double = 0.0,
    val vramUsedMB: Double = 0.0,
    val vramTotalMB: Double = 0.0,
    val tempC: Double? = null,
    val powerW: Double? = null,
    val clockMHz: Double? = null,
) {
    companion object {
        fun from(json: JsonElement?): GpuMetrics? {
            val name = json["name"].string ?: return null
            return GpuMetrics(
                id = json["id"].string,
                name = name,
                utilPercent = json["utilPercent"].double ?: 0.0,
                vramUsedMB = json["vramUsedMB"].double ?: 0.0,
                vramTotalMB = json["vramTotalMB"].double ?: 0.0,
                tempC = json["tempC"].double,
                powerW = json["powerW"].double,
                clockMHz = json["clockMHz"].double,
            )
        }
    }
}

data class DiskMetrics(
    val id: String,
    val available: Boolean,
    val busyPercent: Double,
    val responseMs: Double,
    val readBps: Double,
    val writeBps: Double,
    val readTotal: Long,
    val writeTotal: Long,
    val tempC: Double? = null,
) {
    companion object {
        fun from(json: JsonElement?): DiskMetrics? {
            val id = json["id"].string ?: return null
            return DiskMetrics(
                id = id,
                available = json["available"].bool ?: false,
                busyPercent = json["busyPercent"].double ?: 0.0,
                responseMs = json["responseMs"].double ?: 0.0,
                readBps = json["readBps"].double ?: 0.0,
                writeBps = json["writeBps"].double ?: 0.0,
                readTotal = json["readTotal"].long ?: 0L,
                writeTotal = json["writeTotal"].long ?: 0L,
                tempC = json["tempC"].double,
            )
        }
    }
}

data class NicMetrics(
    val id: String,
    val available: Boolean,
    val rxBps: Double,
    val txBps: Double,
    val rxTotal: Long,
    val txTotal: Long,
    val state: String,
    val linkMbps: Double? = null,
    val ssid: String? = null,
    val signalPercent: Double? = null,
) {
    companion object {
        fun from(json: JsonElement?): NicMetrics? {
            val id = json["id"].string ?: return null
            return NicMetrics(
                id = id,
                available = json["available"].bool ?: false,
                rxBps = json["rxBps"].double ?: 0.0,
                txBps = json["txBps"].double ?: 0.0,
                rxTotal = json["rxTotal"].long ?: 0L,
                txTotal = json["txTotal"].long ?: 0L,
                state = json["state"].string ?: "unknown",
                linkMbps = json["linkMbps"].double,
                ssid = json["ssid"].string,
                signalPercent = json["signalPercent"].double,
            )
        }
    }
}

data class FanMetrics(
    val id: String,
    val label: String,
    val rpm: Int,
    val pwmPercent: Double? = null,
    val tempC: Double? = null,
) {
    companion object {
        fun from(json: JsonElement?): FanMetrics? {
            val id = json["id"].string ?: return null
            return FanMetrics(
                id = id,
                label = json["label"].string ?: id,
                rpm = json["rpm"].int ?: 0,
                pwmPercent = json["pwmPercent"].double,
                tempC = json["tempC"].double,
            )
        }
    }
}

data class SystemMetrics(
    val cpu: CpuMetrics = CpuMetrics(),
    val mem: MemoryMetrics = MemoryMetrics(),
    val disk: RateMetrics = RateMetrics(),
    val net: RateMetrics = RateMetrics(),
    val gpus: List<GpuMetrics> = emptyList(),
    val processCount: Int = 0,
    val disks: List<DiskMetrics> = emptyList(),
    val nics: List<NicMetrics> = emptyList(),
    val fans: List<FanMetrics> = emptyList(),
) {
    companion object {
        fun from(json: JsonElement?): SystemMetrics {
            if (json == null) return SystemMetrics()
            return SystemMetrics(
                cpu = CpuMetrics.from(json["cpu"]),
                mem = MemoryMetrics.from(json["mem"]),
                disk = RateMetrics.from(json["disk"]),
                net = RateMetrics.from(json["net"]),
                gpus = json["gpus"].array.orEmpty().mapNotNull(GpuMetrics::from),
                processCount = json["processCount"].int ?: 0,
                disks = json["disks"].array.orEmpty().mapNotNull(DiskMetrics::from),
                nics = json["nics"].array.orEmpty().mapNotNull(NicMetrics::from),
                fans = json["fans"].array.orEmpty().mapNotNull(FanMetrics::from),
            )
        }
    }
}

data class ProcessInfo(
    val pid: Int,
    val ppid: Int = -1,
    val name: String,
    val command: String = name,
    val cpu: Double = 0.0,
    val ramMB: Double = 0.0,
    val swapMB: Double? = null,
    val unitKey: String? = null,
    val startedAt: Long = 0L,
    val ppm: Boolean = false,
    val protected: Boolean = false,
    val diskReadBps: Double? = null,
    val diskWriteBps: Double? = null,
    val gpuPct: Double? = null,
    val gpuMemMB: Double? = null,
    val netInBps: Double? = null,
    val netOutBps: Double? = null,
) {
    companion object {
        fun from(json: JsonElement?): ProcessInfo? {
            val pid = json["pid"].int ?: return null
            val name = json["name"].string ?: return null
            return ProcessInfo(
                pid = pid,
                ppid = json["ppid"].int ?: -1,
                name = name,
                command = json["command"].string ?: name,
                cpu = json["cpu"].double ?: 0.0,
                ramMB = json["ramMB"].double ?: 0.0,
                swapMB = json["swapMB"].double,
                unitKey = json["unitKey"].string,
                startedAt = json["startedAt"].long ?: 0L,
                ppm = json["ppm"].bool ?: false,
                protected = json["protected"].bool ?: false,
                diskReadBps = json["diskReadBps"].double,
                diskWriteBps = json["diskWriteBps"].double,
                gpuPct = json["gpuPct"].double,
                gpuMemMB = json["gpuMemMB"].double,
                netInBps = json["netInBps"].double,
                netOutBps = json["netOutBps"].double,
            )
        }
    }
}

data class ProcessGroup(
    val key: String,
    val label: String,
    val rootPid: Int? = null,
    val cpu: Double = 0.0,
    val ramMB: Double = 0.0,
    val count: Int = 1,
    val ppm: Boolean = false,
    val pids: List<Int> = emptyList(),
    val swapMB: Double? = null,
    val diskReadBps: Double? = null,
    val diskWriteBps: Double? = null,
    val gpuPct: Double? = null,
    val gpuMemMB: Double? = null,
    val netInBps: Double? = null,
    val netOutBps: Double? = null,
) {
    companion object {
        fun from(json: JsonElement?): ProcessGroup? {
            val key = json["key"].string ?: return null
            val label = json["label"].string ?: key
            return ProcessGroup(
                key = key,
                label = label,
                rootPid = json["rootPid"].int,
                cpu = json["cpu"].double ?: 0.0,
                ramMB = json["ramMB"].double ?: 0.0,
                count = json["count"].int ?: 1,
                ppm = json["ppm"].bool ?: false,
                pids = json["pids"].array.orEmpty().mapNotNull { it.int },
                swapMB = json["swapMB"].double,
                diskReadBps = json["diskReadBps"].double,
                diskWriteBps = json["diskWriteBps"].double,
                gpuPct = json["gpuPct"].double,
                gpuMemMB = json["gpuMemMB"].double,
                netInBps = json["netInBps"].double,
                netOutBps = json["netOutBps"].double,
            )
        }
    }
}

data class ProcessColumnAvailability(
    val disk: Boolean = false,
    val gpu: Boolean = false,
    val gpuMemory: Boolean = true,
    val net: Boolean = false,
    val swap: Boolean = false,
) {
    companion object {
        fun from(json: JsonElement?): ProcessColumnAvailability {
            if (json == null) return ProcessColumnAvailability()
            return ProcessColumnAvailability(
                disk = json["disk"].bool ?: false,
                gpu = json["gpu"].bool ?: false,
                gpuMemory = json["gpuMemory"].bool ?: true,
                net = json["net"].bool ?: false,
                swap = json["swap"].bool ?: false,
            )
        }
    }
}

data class AppInfo(
    val id: String,
    val name: String,
    val icon: String? = null,
    val pids: List<Int> = emptyList(),
) {
    companion object {
        fun from(json: JsonElement?): AppInfo? {
            val id = json["id"].string ?: return null
            val name = json["name"].string ?: id
            return AppInfo(
                id = id,
                name = name,
                icon = json["icon"].string,
                pids = json["pids"].array.orEmpty().mapNotNull { it.int },
            )
        }
    }
}

data class GroupHistorySample(val cpu: Double, val ramMB: Double)

data class MetricsHistoryPoint(
    val ts: Long,
    val system: SystemMetrics,
    val groups: Map<String, GroupHistorySample> = emptyMap(),
)

data class MetricsSnapshot(
    val ts: Long,
    val platform: String = "linux",
    val tier: String = "full",
    val intervalMs: Int = 2000,
    val system: SystemMetrics = SystemMetrics(),
    val groups: List<ProcessGroup> = emptyList(),
    val processes: List<ProcessInfo> = emptyList(),
    val processColumns: ProcessColumnAvailability = ProcessColumnAvailability(),
    val warnings: List<String> = emptyList(),
    val apps: List<AppInfo>? = null,
    val signals: List<String> = emptyList(),
) {
    companion object {
        fun from(json: JsonElement?): MetricsSnapshot? {
            val ts = json["ts"].long ?: return null
            val appsArr = json["apps"].array
            return MetricsSnapshot(
                ts = ts,
                platform = json["platform"].string ?: "linux",
                tier = json["tier"].string ?: "full",
                intervalMs = json["intervalMs"].int ?: 2000,
                system = SystemMetrics.from(json["system"]),
                groups = json["groups"].array.orEmpty().mapNotNull(ProcessGroup::from),
                processes = json["processes"].array.orEmpty().mapNotNull(ProcessInfo::from),
                processColumns = ProcessColumnAvailability.from(json["processColumns"]),
                warnings = json["warnings"].array.orEmpty().mapNotNull { it.string },
                apps = appsArr?.mapNotNull(AppInfo::from),
                signals = json["signals"].array.orEmpty().mapNotNull { it.string },
            )
        }
    }
}

data class ProcessDetails(
    val pid: Int,
    val ppid: Int,
    val name: String,
    val startedAt: Long,
    val command: String? = null,
    val exe: String? = null,
    val cwd: String? = null,
    val user: String? = null,
    val state: String? = null,
    val threads: Int? = null,
    val nice: Int? = null,
    val cgroup: String? = null,
) {
    companion object {
        fun from(json: JsonElement?): ProcessDetails? {
            val pid = json["pid"].int ?: return null
            return ProcessDetails(
                pid = pid,
                ppid = json["ppid"].int ?: -1,
                name = json["name"].string.orEmpty(),
                startedAt = json["startedAt"].long ?: 0L,
                command = json["command"].string,
                exe = json["exe"].string,
                cwd = json["cwd"].string,
                user = json["user"].string,
                state = json["state"].string,
                threads = json["threads"].int,
                nice = json["nice"].int,
                cgroup = json["cgroup"].string,
            )
        }
    }
}

data class DiskInfo(
    val id: String,
    val model: String? = null,
    val kind: String = "unknown",
    val capacityBytes: Long = 0L,
    val systemDisk: Boolean = false,
    val removable: Boolean = false,
) {
    companion object {
        fun from(json: JsonElement?): DiskInfo? {
            val id = json["id"].string ?: return null
            return DiskInfo(
                id = id,
                model = json["model"].string,
                kind = json["kind"].string ?: "unknown",
                capacityBytes = json["capacityBytes"].long ?: 0L,
                systemDisk = json["systemDisk"].bool ?: false,
                removable = json["removable"].bool ?: false,
            )
        }
    }
}

data class NicInfo(
    val id: String,
    val kind: String = "other",
    val deviceName: String? = null,
    val driver: String? = null,
    val mac: String? = null,
    val ipv4: List<String> = emptyList(),
    val ipv6: List<String> = emptyList(),
) {
    companion object {
        fun from(json: JsonElement?): NicInfo? {
            val id = json["id"].string ?: return null
            return NicInfo(
                id = id,
                kind = json["kind"].string ?: "other",
                deviceName = json["deviceName"].string,
                driver = json["driver"].string,
                mac = json["mac"].string,
                ipv4 = json["ipv4"].array.orEmpty().mapNotNull { it.string },
                ipv6 = json["ipv6"].array.orEmpty().mapNotNull { it.string },
            )
        }
    }
}

data class GpuInfo(
    val id: String,
    val name: String,
    val vendor: String? = null,
    val driver: String? = null,
    val driverVersion: String? = null,
    val openglVersion: String? = null,
    val vulkanVersion: String? = null,
) {
    companion object {
        fun from(json: JsonElement?): GpuInfo? {
            val id = json["id"].string ?: return null
            return GpuInfo(
                id = id,
                name = json["name"].string ?: id,
                vendor = json["vendor"].string,
                driver = json["driver"].string,
                driverVersion = json["driverVersion"].string,
                openglVersion = json["openglVersion"].string,
                vulkanVersion = json["vulkanVersion"].string,
            )
        }
    }
}

data class CpuInfo(
    val name: String = "",
    val sockets: Int = 1,
    val physicalCores: Int = 1,
    val logicalCores: Int = 1,
    val baseMHz: Double? = null,
    val maxMHz: Double? = null,
    val virtualization: String? = null,
    val isVirtualMachine: Boolean = false,
    val freqDriver: String? = null,
    val freqGovernor: String? = null,
) {
    companion object {
        fun from(json: JsonElement?): CpuInfo? {
            val name = json["name"].string ?: return null
            return CpuInfo(
                name = name,
                sockets = json["sockets"].int ?: 1,
                physicalCores = json["physicalCores"].int ?: 1,
                logicalCores = json["logicalCores"].int ?: 1,
                baseMHz = json["baseMHz"].double,
                maxMHz = json["maxMHz"].double,
                virtualization = json["virtualization"].string,
                isVirtualMachine = json["isVirtualMachine"].bool ?: false,
                freqDriver = json["freqDriver"].string,
                freqGovernor = json["freqGovernor"].string,
            )
        }
    }
}

data class HardwareInventory(
    val platform: String = "linux",
    val ts: Long = 0L,
    val disks: List<DiskInfo> = emptyList(),
    val nics: List<NicInfo> = emptyList(),
    val gpus: List<GpuInfo> = emptyList(),
    val cpu: CpuInfo? = null,
) {
    companion object {
        fun from(json: JsonElement?): HardwareInventory? {
            val ts = json["ts"].long ?: return null
            return HardwareInventory(
                platform = json["platform"].string ?: "linux",
                ts = ts,
                disks = json["disks"].array.orEmpty().mapNotNull(DiskInfo::from),
                nics = json["nics"].array.orEmpty().mapNotNull(NicInfo::from),
                gpus = json["gpus"].array.orEmpty().mapNotNull(GpuInfo::from),
                cpu = CpuInfo.from(json["cpu"]),
            )
        }
    }
}

data class ServiceInfo(
    val unit: String,
    val scope: String,
    val description: String = "",
    val activeState: String = "",
    val subState: String = "",
    val unitFileState: String? = null,
    val running: Boolean = false,
    val failed: Boolean = false,
    val enabled: Boolean = false,
    val mainPid: Int? = null,
    val refused: Map<String, String> = emptyMap(),
) {
    companion object {
        fun from(json: JsonElement?): ServiceInfo? {
            val unit = json["unit"].string ?: return null
            val scope = json["scope"].string ?: return null
            val refusedMap = json["refused"].obj?.mapNotNull { (k, v) ->
                v.string?.let { k to it }
            }?.toMap().orEmpty()
            return ServiceInfo(
                unit = unit,
                scope = scope,
                description = json["description"].string.orEmpty(),
                activeState = json["activeState"].string.orEmpty(),
                subState = json["subState"].string.orEmpty(),
                unitFileState = json["unitFileState"].string,
                running = json["running"].bool ?: false,
                failed = json["failed"].bool ?: false,
                enabled = json["enabled"].bool ?: false,
                mainPid = json["mainPid"].int,
                refused = refusedMap,
            )
        }
    }
}

data class ServicesSnapshot(
    val supported: Boolean = true,
    val manager: String = "systemd",
    val services: List<ServiceInfo> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    companion object {
        fun from(json: JsonElement?): ServicesSnapshot? {
            if (json == null) return null
            return ServicesSnapshot(
                supported = json["supported"].bool ?: false,
                manager = json["manager"].string ?: "systemd",
                services = json["services"].array.orEmpty().mapNotNull(ServiceInfo::from),
                warnings = json["warnings"].array.orEmpty().mapNotNull { it.string },
            )
        }
    }
}

data class ServiceLogLine(val ts: Long?, val message: String)

data class ServiceDetails(
    val service: ServiceInfo,
    val user: String? = null,
    val group: String? = null,
    val fragmentPath: String? = null,
    val logs: List<ServiceLogLine> = emptyList(),
) {
    companion object {
        fun from(json: JsonElement?): ServiceDetails? {
            val service = ServiceInfo.from(json) ?: return null
            val logs = json["logs"].array.orEmpty().mapNotNull { line ->
                val msg = line["message"].string ?: return@mapNotNull null
                ServiceLogLine(line["ts"].long, msg)
            }
            return ServiceDetails(
                service = service,
                user = json["user"].string,
                group = json["group"].string,
                fragmentPath = json["fragmentPath"].string,
                logs = logs,
            )
        }
    }
}

// ── Ring buffer & first-tick baseline guard ────────────────────────────

object SysMonRingBuffer {
    /**
     * Appends [snapshot] to [history], capping at [METRICS_HISTORY_MAX] (900) and trimming
     * per-group sparkline entries on points older than the last [GROUPS_RETENTION] (60).
     */
    fun appendSnapshot(
        history: List<MetricsHistoryPoint>,
        snapshot: MetricsSnapshot,
        maxPoints: Int = METRICS_HISTORY_MAX,
        groupsRetention: Int = GROUPS_RETENTION,
    ): List<MetricsHistoryPoint> {
        val point = MetricsHistoryPoint(
            ts = snapshot.ts,
            system = snapshot.system,
            groups = snapshot.groups.associate { it.key to GroupHistorySample(it.cpu, it.ramMB) },
        )
        val combined = (history + point).let {
            if (it.size > maxPoints) it.takeLast(maxPoints) else it
        }
        val cutoff = combined.size - groupsRetention
        if (cutoff <= 0) return combined
        return combined.mapIndexed { idx, p ->
            if (idx < cutoff && p.groups.isNotEmpty()) p.copy(groups = emptyMap()) else p
        }
    }

    /** First snapshot frame after connecting has no delta baseline yet (CPU 0%, rates n/a). */
    fun isMeasuring(tickCount: Int): Boolean = tickCount <= 1
}

// ── Process table pure logic ───────────────────────────────────────────

sealed interface TableRow {
    data class GroupRow(val group: ProcessGroup, val expanded: Boolean) : TableRow
    data class ProcRow(val proc: ProcessInfo, val indent: Boolean) : TableRow
}

data class Totals(
    val cpu: Double = 0.0,
    val ramMB: Double = 0.0,
    val count: Int = 0,
    val swapMB: Double? = null,
    val diskReadBps: Double? = null,
    val diskWriteBps: Double? = null,
    val gpuMemMB: Double? = null,
    val netInBps: Double? = null,
    val netOutBps: Double? = null,
)

data class BuildRowsResult(
    val rows: List<TableRow>,
    val totals: Totals,
    val autoExpanded: Set<String>,
)

data class KillProcessRequest(val pid: Int, val startedAt: Long, val tree: Boolean)

sealed interface KillTarget {
    data class SingleProcess(val proc: ProcessInfo) : KillTarget
    data class GroupTarget(val group: ProcessGroup, val members: List<ProcessInfo>) : KillTarget
}

object ProcessTableModel {
    private fun addOptional(sum: Double?, value: Double?): Double? {
        if (value == null) return sum
        return (sum ?: 0.0) + value
    }

    private fun sumPair(a: Double?, b: Double?): Double? {
        if (a == null && b == null) return null
        return (a ?: 0.0) + (b ?: 0.0)
    }

    private data class SortFields(
        val cpu: Double,
        val ramMB: Double,
        val name: String,
        val swapMB: Double?,
        val diskReadBps: Double?,
        val diskWriteBps: Double?,
        val gpuPct: Double?,
        val gpuMemMB: Double?,
        val netInBps: Double?,
        val netOutBps: Double?,
    )

    private fun <T> sortByKey(
        items: List<T>,
        key: String?,
        dir: String,
        fieldsOf: (T) -> SortFields,
    ): List<T> {
        if (key == null) return items
        val asc = dir == "asc"
        return items.sortedWith { a, b ->
            val av = fieldsOf(a)
            val bv = fieldsOf(b)
            when (key) {
                "name" -> {
                    val cmp = av.name.compareTo(bv.name, ignoreCase = true)
                    if (asc) cmp else -cmp
                }
                "cpu" -> if (asc) av.cpu.compareTo(bv.cpu) else bv.cpu.compareTo(av.cpu)
                "ram" -> if (asc) av.ramMB.compareTo(bv.ramMB) else bv.ramMB.compareTo(av.ramMB)
                "swap", "disk", "gpu", "gpuMem", "net" -> {
                    val aVal = when (key) {
                        "swap" -> av.swapMB
                        "disk" -> sumPair(av.diskReadBps, av.diskWriteBps)
                        "net" -> sumPair(av.netInBps, av.netOutBps)
                        "gpu" -> av.gpuPct
                        else -> av.gpuMemMB
                    }
                    val bVal = when (key) {
                        "swap" -> bv.swapMB
                        "disk" -> sumPair(bv.diskReadBps, bv.diskWriteBps)
                        "net" -> sumPair(bv.netInBps, bv.netOutBps)
                        "gpu" -> bv.gpuPct
                        else -> bv.gpuMemMB
                    }
                    when {
                        aVal == null && bVal == null -> 0
                        aVal == null -> 1
                        bVal == null -> -1
                        asc -> aVal.compareTo(bVal)
                        else -> bVal.compareTo(aVal)
                    }
                }
                else -> 0
            }
        }
    }

    fun buildRows(
        processes: List<ProcessInfo>,
        groups: List<ProcessGroup>,
        mode: String = "grouped",
        ppmOnly: Boolean = false,
        query: String = "",
        sortKey: String? = null,
        sortDir: String = "desc",
        expanded: Set<String> = emptySet(),
    ): BuildRowsResult {
        val q = query.trim().lowercase()
        val byPid = processes.associateBy { it.pid }

        fun processMatches(p: ProcessInfo): Boolean =
            p.name.lowercase().contains(q) || p.command.lowercase().contains(q)

        if (mode == "flat") {
            var flat = if (ppmOnly) processes.filter { it.ppm } else processes
            if (q.isNotEmpty()) flat = flat.filter(::processMatches)
            flat = sortByKey(flat, sortKey, sortDir) { p ->
                SortFields(p.cpu, p.ramMB, p.name, p.swapMB, p.diskReadBps, p.diskWriteBps, p.gpuPct, p.gpuMemMB, p.netInBps, p.netOutBps)
            }
            val totals = flat.fold(Totals()) { acc, p ->
                Totals(
                    cpu = acc.cpu + p.cpu,
                    ramMB = acc.ramMB + p.ramMB,
                    count = acc.count + 1,
                    swapMB = addOptional(acc.swapMB, p.swapMB),
                    diskReadBps = addOptional(acc.diskReadBps, p.diskReadBps),
                    diskWriteBps = addOptional(acc.diskWriteBps, p.diskWriteBps),
                    gpuMemMB = addOptional(acc.gpuMemMB, p.gpuMemMB),
                    netInBps = addOptional(acc.netInBps, p.netInBps),
                    netOutBps = addOptional(acc.netOutBps, p.netOutBps),
                )
            }
            return BuildRowsResult(
                rows = flat.map { TableRow.ProcRow(it, indent = false) },
                totals = totals,
                autoExpanded = emptySet(),
            )
        }

        var filteredGroups = if (ppmOnly) groups.filter { it.ppm } else groups
        val autoExpanded = mutableSetOf<String>()
        if (q.isNotEmpty()) {
            filteredGroups = filteredGroups.filter { g ->
                val labelMatch = g.label.lowercase().contains(q)
                val memberMatch = g.pids.any { pid -> byPid[pid]?.let(::processMatches) == true }
                val isMatch = labelMatch || memberMatch
                if (isMatch && memberMatch) autoExpanded += g.key
                isMatch
            }
        }
        filteredGroups = sortByKey(filteredGroups, sortKey, sortDir) { g ->
            SortFields(g.cpu, g.ramMB, g.label, g.swapMB, g.diskReadBps, g.diskWriteBps, g.gpuPct, g.gpuMemMB, g.netInBps, g.netOutBps)
        }
        val rows = mutableListOf<TableRow>()
        for (group in filteredGroups) {
            val isExpanded = if (q.isNotEmpty()) group.key in autoExpanded || group.key in expanded else group.key in expanded
            rows += TableRow.GroupRow(group, isExpanded)
            if (!isExpanded) continue
            var children = group.pids.mapNotNull { byPid[it] }
            if (q.isNotEmpty()) children = children.filter(::processMatches)
            children = sortByKey(children, sortKey, sortDir) { p ->
                SortFields(p.cpu, p.ramMB, p.name, p.swapMB, p.diskReadBps, p.diskWriteBps, p.gpuPct, p.gpuMemMB, p.netInBps, p.netOutBps)
            }
            for (proc in children) {
                rows += TableRow.ProcRow(proc, indent = true)
            }
        }
        val totals = filteredGroups.fold(Totals()) { acc, g ->
            Totals(
                cpu = acc.cpu + g.cpu,
                ramMB = acc.ramMB + g.ramMB,
                count = acc.count + g.count,
                swapMB = addOptional(acc.swapMB, g.swapMB),
                diskReadBps = addOptional(acc.diskReadBps, g.diskReadBps),
                diskWriteBps = addOptional(acc.diskWriteBps, g.diskWriteBps),
                gpuMemMB = addOptional(acc.gpuMemMB, g.gpuMemMB),
                netInBps = addOptional(acc.netInBps, g.netInBps),
                netOutBps = addOptional(acc.netOutBps, g.netOutBps),
            )
        }
        return BuildRowsResult(rows, totals, autoExpanded)
    }

    fun toggleSort(current: String?, dir: String, clicked: String): Pair<String?, String> {
        if (current != clicked) return clicked to "desc"
        if (dir == "desc") return clicked to "asc"
        return null to "desc"
    }

    fun buildKillRequest(proc: ProcessInfo, tree: Boolean): KillProcessRequest =
        KillProcessRequest(proc.pid, proc.startedAt, tree)

    fun buildGroupKillRequests(group: ProcessGroup, members: List<ProcessInfo>): List<KillProcessRequest> {
        val root = group.rootPid?.let { rpid -> members.firstOrNull { it.pid == rpid } }
        if (root != null) return listOf(buildKillRequest(root, tree = true))
        return members.map { buildKillRequest(it, tree = false) }
    }

    fun isGroupProtected(members: List<ProcessInfo>): Boolean = members.any { it.protected }
}

// ── Apps tab pure logic ────────────────────────────────────────────────

data class AppRowData(
    val id: String,
    val name: String,
    val icon: String?,
    val pids: List<Int>,
    val memberPids: List<Int>,
    val processCount: Int,
    val cpu: Double,
    val ramMB: Double,
    val swapMB: Double? = null,
    val diskReadBps: Double? = null,
    val diskWriteBps: Double? = null,
    val gpuPct: Double? = null,
    val gpuMemMB: Double? = null,
)

object AppRowsModel {
    private fun round1(n: Double): Double = (n * 10.0).roundToInt() / 10.0

    private fun sumOptional(values: List<Double?>): Double? {
        val nonNull = values.filterNotNull()
        return if (nonNull.isEmpty()) null else nonNull.sum()
    }

    fun subtreePids(
        roots: List<Int>,
        children: Map<Int, List<Int>>,
        stopAt: (Int) -> Boolean = { false },
    ): List<Int> {
        val seen = LinkedHashSet<Int>()
        val stack = ArrayDeque(roots)
        while (stack.isNotEmpty()) {
            val pid = stack.removeLast()
            if (!seen.add(pid)) continue
            for (child in children[pid].orEmpty()) {
                if (!stopAt(child)) stack.addLast(child)
            }
        }
        return seen.toList()
    }

    fun buildAppRows(apps: List<AppInfo>, processes: List<ProcessInfo>): List<AppRowData> {
        val byPid = processes.associateBy { it.pid }
        val children = mutableMapOf<Int, MutableList<Int>>()
        for (p in processes) {
            children.getOrPut(p.ppid) { mutableListOf() }.add(p.pid)
        }
        val rootOwner = mutableMapOf<Int, String>()
        for (app in apps) for (pid in app.pids) rootOwner[pid] = app.id

        val out = mutableListOf<AppRowData>()
        for (app in apps) {
            val members = subtreePids(app.pids, children) { pid ->
                val owner = rootOwner[pid]
                owner != null && owner != app.id
            }.mapNotNull { byPid[it] }
            if (members.isEmpty()) continue
            out += AppRowData(
                id = app.id,
                name = app.name,
                icon = app.icon,
                pids = app.pids,
                memberPids = members.map { it.pid },
                processCount = members.size,
                cpu = round1(members.sumOf { it.cpu }),
                ramMB = round1(members.sumOf { it.ramMB }),
                swapMB = sumOptional(members.map { it.swapMB }),
                diskReadBps = sumOptional(members.map { it.diskReadBps }),
                diskWriteBps = sumOptional(members.map { it.diskWriteBps }),
                gpuPct = sumOptional(members.map { it.gpuPct })?.let { round1(it.coerceIn(0.0, 100.0)) },
                gpuMemMB = sumOptional(members.map { it.gpuMemMB }),
            )
        }
        return out
    }

    fun filterAppRows(rows: List<AppRowData>, query: String): List<AppRowData> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return rows
        return rows.filter { it.name.lowercase().contains(needle) }
    }

    fun sortAppRows(rows: List<AppRowData>, key: String, dir: String = "desc"): List<AppRowData> {
        val sign = if (dir == "asc") 1 else -1
        return rows.sortedWith { a, b ->
            if (key == "name") {
                sign * a.name.compareTo(b.name, ignoreCase = true)
            } else {
                val diff = if (key == "cpu") a.cpu.compareTo(b.cpu) else a.ramMB.compareTo(b.ramMB)
                if (diff != 0) sign * diff else a.name.compareTo(b.name, ignoreCase = true)
            }
        }
    }

    /**
     * Port of `groupForApp` (`apps-panel.tsx:30-41`): `rootPid` is null on purpose because an app
     * can have multiple roots, so `buildGroupKillRequests` kills each member individually.
     */
    fun groupForApp(app: AppRowData): ProcessGroup = ProcessGroup(
        key = "app:${app.id}",
        label = app.name,
        rootPid = null,
        cpu = app.cpu,
        ramMB = app.ramMB,
        count = app.processCount,
        ppm = false,
        pids = app.memberPids,
    )
}

// ── Services tab pure logic ────────────────────────────────────────────

data class UnitResources(
    val count: Int = 0,
    val cpu: Double = 0.0,
    val ramMB: Double = 0.0,
    val swapMB: Double? = null,
    val diskReadBps: Double? = null,
    val diskWriteBps: Double? = null,
    val gpuPct: Double? = null,
    val gpuMemMB: Double? = null,
)

data class ServiceCounts(val total: Int, val running: Int, val failed: Int)

object ServicesModel {
    val IDLE_UNIT = UnitResources(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    private val PROCESS_OWNING_SUFFIXES = listOf(".service", ".scope")

    private fun round1(n: Double): Double = (n * 10.0).roundToInt() / 10.0

    private fun addOptional(sum: Double?, value: Double?): Double? {
        if (value == null) return sum
        return (sum ?: 0.0) + value
    }

    fun rollUpByUnit(processes: List<ProcessInfo>): Map<String, UnitResources> {
        val byUnit = mutableMapOf<String, UnitResources>()
        for (p in processes) {
            val key = p.unitKey ?: continue
            val cur = byUnit[key] ?: UnitResources()
            byUnit[key] = cur.copy(
                count = cur.count + 1,
                cpu = cur.cpu + p.cpu,
                ramMB = cur.ramMB + p.ramMB,
                swapMB = addOptional(cur.swapMB, p.swapMB),
                diskReadBps = addOptional(cur.diskReadBps, p.diskReadBps),
                diskWriteBps = addOptional(cur.diskWriteBps, p.diskWriteBps),
                gpuPct = addOptional(cur.gpuPct, p.gpuPct),
                gpuMemMB = addOptional(cur.gpuMemMB, p.gpuMemMB),
            )
        }
        return byUnit.mapValues { (_, r) ->
            r.copy(
                cpu = round1(r.cpu),
                ramMB = round1(r.ramMB),
                gpuPct = r.gpuPct?.let { min(100.0, round1(it)) },
            )
        }
    }

    fun resourcesFor(
        service: ServiceInfo,
        byUnit: Map<String, UnitResources>?,
        manager: String = "systemd",
    ): UnitResources? {
        if (byUnit == null) return null
        val key = "${service.scope}:${service.unit}"
        byUnit[key]?.let { return it }
        val couldHaveProcesses = if (manager == "launchd") {
            service.running
        } else {
            service.subState == "running" && PROCESS_OWNING_SUFFIXES.any { service.unit.endsWith(it) }
        }
        return if (couldHaveProcesses) null else IDLE_UNIT
    }

    fun shapeServices(
        services: List<ServiceInfo>,
        scope: String,
        filter: String,
        query: String,
    ): List<ServiceInfo> {
        val needle = query.trim().lowercase()
        return services
            .filter { s ->
                s.scope == scope &&
                    when (filter) {
                        "running" -> s.running
                        "failed" -> s.failed
                        "enabled" -> s.enabled
                        else -> true
                    } &&
                    (needle.isEmpty() || s.unit.lowercase().contains(needle) || s.description.lowercase().contains(needle))
            }
            .sortedWith { a, b ->
                val bandA = if (a.failed) 0 else if (a.running) 1 else 2
                val bandB = if (b.failed) 0 else if (b.running) 1 else 2
                if (bandA != bandB) bandA - bandB else a.unit.compareTo(b.unit)
            }
    }

    fun serviceCounts(services: List<ServiceInfo>, scope: String? = null): ServiceCounts {
        val scoped = if (scope != null) services.filter { it.scope == scope } else services
        return ServiceCounts(
            total = scoped.size,
            running = scoped.count { it.running },
            failed = scoped.count { it.failed },
        )
    }

    data class ServiceConfirmWording(val title: String, val body: String, val cta: String)

    val CONFIRMED_ACTIONS: Set<String> = setOf("stop", "restart", "disable")

    fun needsConfirm(action: String): Boolean = action in CONFIRMED_ACTIONS

    fun confirmWordingFor(manager: String, service: ServiceInfo, action: String): ServiceConfirmWording? {
        val unit = service.unit
        return when (manager) {
            "scm" -> when (action) {
                "stop" -> ServiceConfirmWording("Stop service", "Stop $unit? Windows refuses if other running services depend on it.", "Stop")
                "restart" -> ServiceConfirmWording("Restart service", "Restart $unit? It will be briefly unavailable.", "Restart")
                "disable" -> ServiceConfirmWording("Disable at boot", "$unit will no longer start automatically (startup type Manual). It keeps running now.", "Disable")
                else -> null
            }
            "launchd" -> when (action) {
                "stop" -> ServiceConfirmWording("Stop job", "Stop $unit? launchd starts it again when something needs it, or by itself if it keeps it alive.", "Stop")
                "restart" -> ServiceConfirmWording("Restart job", "Restart $unit? It will be briefly unavailable.", "Restart")
                "disable" -> if (service.scope == "user") {
                    ServiceConfirmWording("Disable at login", "$unit will no longer load at login. It keeps running now.", "Disable")
                } else {
                    ServiceConfirmWording("Disable at boot", "$unit will no longer start at boot. It keeps running now.", "Disable")
                }
                else -> null
            }
            else -> when (action) {
                "stop" -> ServiceConfirmWording("Stop service", "Stop $unit? Anything depending on it stops too.", "Stop")
                "restart" -> ServiceConfirmWording("Restart service", "Restart $unit? It will be briefly unavailable.", "Restart")
                "disable" -> ServiceConfirmWording("Disable at boot", "$unit will no longer start at boot. It keeps running now.", "Disable")
                else -> null
            }
        }
    }
}

fun tabNeedsProcesses(tab: String): Boolean =
    tab == "processes" || tab == "apps" || tab == "services"

sealed interface StreamReconcileAction {
    data object NoOp : StreamReconcileAction
    data object Disconnect : StreamReconcileAction
    data class Connect(val wantProcesses: Boolean) : StreamReconcileAction
}

class ResourceStreamTierTracker {
    var refCount: Int = 0
        private set
    var processesRefCount: Int = 0
        private set
    var connected: Boolean = false
        private set
    var connectedProcesses: Boolean = false
        private set

    fun retain(wantProcesses: Boolean): StreamReconcileAction {
        refCount += 1
        if (wantProcesses) processesRefCount += 1
        return reconcile()
    }

    fun release(wantProcesses: Boolean): StreamReconcileAction {
        refCount = (refCount - 1).coerceAtLeast(0)
        if (wantProcesses) processesRefCount = (processesRefCount - 1).coerceAtLeast(0)
        return reconcile()
    }

    fun reset() {
        refCount = 0
        processesRefCount = 0
        connected = false
        connectedProcesses = false
    }

    private fun reconcile(): StreamReconcileAction {
        val wanted = processesRefCount > 0
        if (refCount == 0) {
            if (!connected) return StreamReconcileAction.NoOp
            connected = false
            return StreamReconcileAction.Disconnect
        }
        if (!connected || connectedProcesses != wanted) {
            connected = true
            connectedProcesses = wanted
            return StreamReconcileAction.Connect(wanted)
        }
        return StreamReconcileAction.NoOp
    }
}

// ── Performance devices & formatting ───────────────────────────────────

data class DeviceEntry(
    val key: String,
    val kind: String,
    val label: String,
    val sublabel: String,
    val id: String?,
    val detail: String,
    val percent: Double?,
)

object PerformanceDevices {
    private val NIC_KIND_LABELS = mapOf(
        "wired" to "Ethernet",
        "wireless" to "Wi-Fi",
        "bluetooth" to "Bluetooth",
        "bridge" to "Bridge",
        "docker" to "Docker",
        "infiniband" to "InfiniBand",
        "multipass" to "Multipass",
        "virtual" to "Virtual",
        "vpn" to "VPN",
        "other" to "Other",
    )

    fun formatRam(mb: Double): String =
        if (mb >= 1024.0) String.format(Locale.US, "%.1f GB", mb / 1024.0)
        else String.format(Locale.US, "%.0f MB", mb)

    fun formatBps(bps: Double): String = when {
        bps >= 1024.0 * 1024.0 * 1024.0 -> String.format(Locale.US, "%.1f GB/s", bps / (1024.0 * 1024.0 * 1024.0))
        bps >= 1024.0 * 1024.0 -> String.format(Locale.US, "%.1f MB/s", bps / (1024.0 * 1024.0))
        bps >= 1024.0 -> String.format(Locale.US, "%.1f KB/s", bps / 1024.0)
        else -> String.format(Locale.US, "%.0f B/s", max(0.0, bps))
    }

    fun buildDeviceList(system: SystemMetrics, inventory: HardwareInventory?): List<DeviceEntry> {
        val diskInfo = inventory?.disks.orEmpty().associateBy { it.id }
        val nicInfo = inventory?.nics.orEmpty().associateBy { it.id }
        val entries = mutableListOf(
            DeviceEntry(
                key = "cpu",
                kind = "cpu",
                label = "CPU",
                sublabel = system.cpu.model,
                id = null,
                detail = "${system.cpu.total.roundToInt()}%",
                percent = system.cpu.total,
            ),
            DeviceEntry(
                key = "memory",
                kind = "memory",
                label = "Memory",
                sublabel = String.format(Locale.US, "%.1f/%.1f GB", system.mem.usedMB / 1024.0, system.mem.totalMB / 1024.0),
                id = null,
                detail = "${system.mem.percent.roundToInt()}%",
                percent = system.mem.percent,
            ),
        )
        system.disks.forEachIndexed { index, disk ->
            entries += DeviceEntry(
                key = "disk:${disk.id}",
                kind = "disk",
                label = "Disk $index",
                sublabel = diskInfo[disk.id]?.model ?: disk.id,
                id = disk.id,
                detail = if (disk.available) "${disk.busyPercent.roundToInt()}%" else "—",
                percent = if (disk.available) disk.busyPercent else null,
            )
        }
        for (nic in system.nics) {
            val info = nicInfo[nic.id]
            val link = nic.linkMbps
            val pct = if (nic.available && link != null && link > 0.0) {
                (((nic.rxBps + nic.txBps) * 8.0 / 1_000_000.0) / link * 100.0).coerceIn(0.0, 100.0)
            } else {
                null
            }
            entries += DeviceEntry(
                key = "nic:${nic.id}",
                kind = "nic",
                label = NIC_KIND_LABELS[info?.kind ?: "other"] ?: "Other",
                sublabel = nic.id,
                id = nic.id,
                detail = if (nic.available) formatBps(nic.rxBps + nic.txBps) else "—",
                percent = pct,
            )
        }
        system.gpus.forEachIndexed { index, gpu ->
            entries += DeviceEntry(
                key = "gpu:${gpu.id ?: index}",
                kind = "gpu",
                label = "GPU $index",
                sublabel = gpu.name,
                id = gpu.id,
                detail = "${gpu.utilPercent.roundToInt()}%",
                percent = gpu.utilPercent,
            )
        }
        if (system.fans.isNotEmpty()) {
            val fastest = system.fans.maxOf { it.rpm }
            entries += DeviceEntry(
                key = "fans",
                kind = "fans",
                label = "Fans",
                sublabel = "${system.fans.size} sensor${if (system.fans.size == 1) "" else "s"}",
                id = null,
                detail = "$fastest RPM",
                percent = null,
            )
        }
        return entries
    }

    fun resolveSelected(entries: List<DeviceEntry>, wanted: String?): String {
        if (wanted != null && entries.any { it.key == wanted }) return wanted
        return entries.firstOrNull()?.key ?: "cpu"
    }
}
