package com.hienlh.ppm.state.sysmon

import com.hienlh.ppm.state.logs.*
import com.hienlh.ppm.state.tunnels.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SysMonLogsTunnelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `SysMonRingBuffer caps samples at 900 and trims groups older than 60`() {
        var history = emptyList<MetricsHistoryPoint>()
        for (i in 1..905) {
            val snapJson = json.parseToJsonElement(
                """
                {
                  "ts": ${i * 1000L},
                  "tier": "full",
                  "system": {
                    "cpu": { "total": 25.5, "cores": [20.0, 30.0] },
                    "mem": { "totalMB": 4000, "usedMB": 1000, "availableMB": 3000, "percent": 25.0 },
                    "disk": { "inBps": 10.0, "outBps": 20.0, "available": true },
                    "net": { "inBps": 100.0, "outBps": 200.0, "available": true },
                    "gpus": [],
                    "disks": [{ "id": "sda", "available": true, "busyPercent": 5.0, "responseMs": 1.0, "readBps": 10.0, "writeBps": 20.0, "readTotal": 100, "writeTotal": 200 }],
                    "nics": [{ "id": "eth0", "available": true, "rxBps": 100.0, "txBps": 200.0, "rxTotal": 1000, "txTotal": 2000, "state": "up" }],
                    "fans": []
                  },
                  "groups": [{ "key": "grp-$i", "label": "Group $i", "cpu": 12.5, "ramMB": 128.0, "count": 1, "pids": [100] }],
                  "processes": [{ "pid": 100, "ppid": 1, "name": "bun", "cpu": 12.5, "ramMB": 128.0 }]
                }
                """.trimIndent(),
            )
            val snap = MetricsSnapshot.from(snapJson)!!
            history = SysMonRingBuffer.appendSnapshot(history, snap)
        }
        assertEquals(METRICS_HISTORY_MAX, history.size)
        assertEquals(6L * 1000L, history.first().ts)
        assertEquals(905L * 1000L, history.last().ts)
        val nonEmptyGroups = history.count { it.groups.isNotEmpty() }
        assertEquals(GROUPS_RETENTION, nonEmptyGroups)
        assertTrue(SysMonRingBuffer.isMeasuring(1))
        assertFalse(SysMonRingBuffer.isMeasuring(2))
    }

    @Test
    fun `ProcessTableModel groups by ProcessGroup, auto-expands on search, sorts null last, and builds kill requests`() {
        val processes = listOf(
            ProcessInfo(pid = 10, ppid = 1, name = "ppm-server", command = "bun run index.ts", cpu = 10.0, ramMB = 100.0, diskReadBps = 500.0, diskWriteBps = 100.0, ppm = true, protected = true, startedAt = 1000L),
            ProcessInfo(pid = 11, ppid = 10, name = "worker", command = "bun worker.ts", cpu = 20.0, ramMB = 50.0, diskReadBps = null, diskWriteBps = null, ppm = true, startedAt = 1001L),
            ProcessInfo(pid = 20, ppid = 1, name = "postgres", command = "postgres -D /var", cpu = 5.0, ramMB = 200.0, diskReadBps = 1000.0, diskWriteBps = 0.0, startedAt = 1002L),
        )
        val groups = listOf(
            ProcessGroup(key = "ppm-core", label = "PPM Core", rootPid = 10, cpu = 30.0, ramMB = 150.0, count = 2, ppm = true, pids = listOf(10, 11), diskReadBps = 500.0, diskWriteBps = 100.0),
            ProcessGroup(key = "db", label = "PostgreSQL", rootPid = 20, cpu = 5.0, ramMB = 200.0, count = 1, ppm = false, pids = listOf(20), diskReadBps = 1000.0, diskWriteBps = 0.0),
        )

        val grouped = ProcessTableModel.buildRows(
            processes = processes,
            groups = groups,
            mode = "grouped",
            ppmOnly = false,
            query = "",
            sortKey = "cpu",
            sortDir = "desc",
            expanded = emptySet(),
        )
        assertEquals(2, grouped.rows.size)
        val firstGroup = (grouped.rows.first() as TableRow.GroupRow).group
        assertEquals("ppm-core", firstGroup.key)
        assertEquals(30.0, firstGroup.cpu, 0.01)
        assertTrue(ProcessTableModel.isGroupProtected(listOf(processes[0], processes[1])))

        // Search auto-expands matching group
        val searched = ProcessTableModel.buildRows(
            processes = processes,
            groups = groups,
            mode = "grouped",
            ppmOnly = false,
            query = "worker",
            sortKey = "cpu",
            sortDir = "desc",
            expanded = emptySet(),
        )
        assertEquals(2, searched.rows.size)
        assertTrue(searched.rows[0] is TableRow.GroupRow)
        assertTrue(searched.rows[1] is TableRow.ProcRow)

        // Flat sort by disk puts null last even in asc
        val flatDiskAsc = ProcessTableModel.buildRows(
            processes = processes,
            groups = groups,
            mode = "flat",
            ppmOnly = false,
            query = "",
            sortKey = "disk",
            sortDir = "asc",
            expanded = emptySet(),
        )
        val pids = flatDiskAsc.rows.map { (it as TableRow.ProcRow).proc.pid }
        assertEquals(listOf(10, 20, 11), pids)

        val killReqs = ProcessTableModel.buildGroupKillRequests(groups[0], listOf(processes[0], processes[1]))
        assertEquals(1, killReqs.size)
        assertEquals(10, killReqs.first().pid)
        assertTrue(killReqs.first().tree)
    }

    @Test
    fun `AppRowsModel aggregates process subtrees with cycle guard and stops at other app roots`() {
        val apps = listOf(
            AppInfo(id = "code.desktop", name = "VS Code", pids = listOf(100)),
            AppInfo(id = "term.desktop", name = "Terminal", pids = listOf(102)),
        )
        val processes = listOf(
            ProcessInfo(pid = 100, ppid = 1, name = "code", cpu = 10.0, ramMB = 100.0),
            ProcessInfo(pid = 101, ppid = 100, name = "code-helper", cpu = 5.0, ramMB = 50.0),
            ProcessInfo(pid = 102, ppid = 100, name = "gnome-terminal", cpu = 8.0, ramMB = 80.0),
            ProcessInfo(pid = 103, ppid = 102, name = "zsh", cpu = 2.0, ramMB = 20.0),
        )
        val rows = AppRowsModel.buildAppRows(apps, processes)
        assertEquals(2, rows.size)
        val codeRow = rows.first { it.id == "code.desktop" }
        val termRow = rows.first { it.id == "term.desktop" }
        assertEquals(setOf(100, 101), codeRow.memberPids.toSet())
        assertEquals(15.0, codeRow.cpu, 0.01)
        assertEquals(150.0, codeRow.ramMB, 0.01)
        assertEquals(setOf(102, 103), termRow.memberPids.toSet())
        assertEquals(10.0, termRow.cpu, 0.01)
    }

    @Test
    fun `ServicesModel rolls up unit resources and returns IDLE_UNIT for stopped services`() {
        val processes = listOf(
            ProcessInfo(pid = 500, ppid = 1, name = "nginx", cpu = 4.0, ramMB = 40.0, unitKey = "system:nginx.service"),
            ProcessInfo(pid = 501, ppid = 500, name = "nginx-worker", cpu = 6.0, ramMB = 80.0, unitKey = "system:nginx.service"),
        )
        val byUnit = ServicesModel.rollUpByUnit(processes)

        val nginxSvc = ServiceInfo(unit = "nginx.service", scope = "system", activeState = "active", subState = "running", running = true)
        val stoppedSvc = ServiceInfo(unit = "cron.service", scope = "system", activeState = "inactive", subState = "dead", running = false)

        val nginxRes = ServicesModel.resourcesFor(nginxSvc, byUnit)
        assertNotNull(nginxRes)
        assertEquals(10.0, nginxRes!!.cpu, 0.01)
        assertEquals(120.0, nginxRes.ramMB, 0.01)
        assertEquals(2, nginxRes.count)

        val stoppedRes = ServicesModel.resourcesFor(stoppedSvc, byUnit)
        assertEquals(ServicesModel.IDLE_UNIT, stoppedRes)
    }

    @Test
    fun `Logs folding, restart dividers, redaction, live dedupe, and report URL work`() {
        val entries = listOf(
            LogEntry(id = "1", ts = 1000L, lv = "info", src = "server", tag = "http", msg = "GET /api/health 200"),
            LogEntry(id = "2", ts = 2000L, lv = "info", src = "server", tag = "http", msg = "GET /api/health 200"),
            LogEntry(id = "3", ts = 3000L, lv = "error", src = "ai", tag = "claude", msg = "Crash on boot 2"),
        )
        val folded = foldRepeats(entries)
        assertEquals(2, folded.size)
        assertEquals(2, folded[0].count)
        assertEquals(listOf("1", "2"), folded[0].ids)
        assertEquals(2000L, folded[0].lastTs)

        val dividers = restartDividers(folded, listOf(2500L))
        assertEquals(1, dividers.size)
        assertEquals(2500L, dividers["3"])

        // Redaction
        val sample = "Token sk-ant-api03-abcdefghijklmnopqrstuvwx in /home/thawngho/Projects/ppm-secret for dev@example.com session 123e4567-e89b-12d3-a456-426614174000"
        val redacted = redactLogText(
            text = sample,
            opts = RedactOptions(home = true, email = true, chats = true, projects = listOf("ppm-secret")),
            mark = true,
        )
        assertFalse(redacted.contains("sk-ant-api03"))
        assertTrue(redacted.contains("\u0001[REDACTED]\u0002"))
        assertTrue(redacted.contains("\u0001~\u0002"))
        assertTrue(redacted.contains("\u0001<project>\u0002"))
        assertTrue(redacted.contains("\u0001<email>\u0002"))
        assertTrue(redacted.contains("\u0001123e4567\u0002"))

        // Live dedupe
        val initialResult = LogQueryResult(entries = entries, matched = 3, inRange = 3, fromTs = 500L)
        val liveMerged = applyLiveEntries(
            current = initialResult,
            entries = listOf(
                entries[1], // duplicate id "2"
                LogEntry(id = "4", ts = 4000L, lv = "warn", src = "shell", tag = "pty", msg = "resize"),
            ),
            titles = emptyMap(),
            filter = DEFAULT_LOG_FILTER,
        )
        assertEquals(4, liveMerged.entries.size)
        assertEquals(4, liveMerged.matched)

        // Report markdown and GitHub issue URL
        val body = reportBody(
            fields = ReportFields(
                title = "Crash on boot",
                labels = listOf("bug", "logs"),
                what = "Server crashed with token sk-1234567890abcdefghijklmnop",
                steps = "1. Start server\n2. Open logs",
                expected = "No crash",
            ),
            snippets = listOf(ReportSnippet(id = "s1", src = "server", rows = folded)),
            environment = listOf("PPM" to "0.10.22", "OS" to "Linux x64"),
            redact = RedactOptions(),
        )
        assertFalse(body.contains("sk-1234567890abcdefghijklmnop"))
        assertTrue(body.contains("[REDACTED]"))
        val url = issueUrl("Crash on boot", listOf("bug", "logs"), body)
        assertTrue(url.startsWith("https://github.com/hienlh/ppm/issues/new?"))
    }

    @Test
    fun `TunnelsModel computes Tailscale and Cloudflare setup states accurately`() {
        assertEquals(
            SetupState(status = "Tailscale is not installed", done = false, warn = false),
            TunnelsModel.tailscaleSetup(TailscaleAvailability(available = false, reason = "Tailscale is not installed")),
        )
        assertEquals(
            SetupState(status = "box.ts.net", done = true, warn = false),
            TunnelsModel.tailscaleSetup(TailscaleAvailability(available = true, dnsName = "box.ts.net")),
        )
        assertEquals(
            SetupState(status = "No domain set up", done = false, warn = false),
            TunnelsModel.cloudflareSetup(NamedTunnelStatus(mode = "quick")),
        )
        assertEquals(
            SetupState(status = "dev.example.com", done = true, warn = false),
            TunnelsModel.cloudflareSetup(NamedTunnelStatus(mode = "named", hostname = "dev.example.com")),
        )
    }

    @Test
    fun `groupForApp sets rootPid null so buildGroupKillRequests kills every member individually`() {
        val processes = listOf(
            ProcessInfo(pid = 100, ppid = 1, name = "chrome", cpu = 10.0, ramMB = 200.0, startedAt = 10L),
            ProcessInfo(pid = 101, ppid = 100, name = "chrome-gpu", cpu = 5.0, ramMB = 150.0, startedAt = 11L),
            ProcessInfo(pid = 102, ppid = 1, name = "chrome-broker", cpu = 2.0, ramMB = 50.0, startedAt = 12L),
        )
        val app = AppInfo(id = "chromium.desktop", name = "Chromium", pids = listOf(100, 102))
        val row = AppRowsModel.buildAppRows(listOf(app), processes).single()
        val group = AppRowsModel.groupForApp(row)
        assertNull(group.rootPid)
        assertEquals("app:chromium.desktop", group.key)
        assertEquals(setOf(100, 101, 102), group.pids.toSet())

        val members = group.pids.mapNotNull { pid -> processes.firstOrNull { it.pid == pid } }
        val reqs = ProcessTableModel.buildGroupKillRequests(group, members)
        assertEquals(3, reqs.size)
        assertTrue(reqs.all { !it.tree })
        assertEquals(setOf(100, 101, 102), reqs.map { it.pid }.toSet())
    }

    @Test
    fun `ServicesModel confirmWordingFor matches systemd launchd and scm wording and ResourceStreamTierTracker switches tiers`() {
        val sysSvc = ServiceInfo(unit = "nginx.service", scope = "system", running = true, enabled = true)
        val userSvc = ServiceInfo(unit = "com.example.agent", scope = "user", running = true, enabled = true)

        assertFalse(ServicesModel.needsConfirm("start"))
        assertFalse(ServicesModel.needsConfirm("enable"))
        assertTrue(ServicesModel.needsConfirm("stop"))
        assertTrue(ServicesModel.needsConfirm("restart"))
        assertTrue(ServicesModel.needsConfirm("disable"))

        val systemdStop = ServicesModel.confirmWordingFor("systemd", sysSvc, "stop")!!
        assertEquals("Stop service", systemdStop.title)
        assertEquals("Stop nginx.service? Anything depending on it stops too.", systemdStop.body)

        val launchdUserDisable = ServicesModel.confirmWordingFor("launchd", userSvc, "disable")!!
        assertEquals("Disable at login", launchdUserDisable.title)
        assertEquals("com.example.agent will no longer load at login. It keeps running now.", launchdUserDisable.body)

        val launchdSysDisable = ServicesModel.confirmWordingFor("launchd", sysSvc, "disable")!!
        assertEquals("Disable at boot", launchdSysDisable.title)

        val scmStop = ServicesModel.confirmWordingFor("scm", sysSvc, "stop")!!
        assertEquals("Stop nginx.service? Windows refuses if other running services depend on it.", scmStop.body)

        val tracker = ResourceStreamTierTracker()
        assertEquals(StreamReconcileAction.Connect(false), tracker.retain(wantProcesses = false))
        assertEquals(StreamReconcileAction.NoOp, tracker.retain(wantProcesses = false))
        assertEquals(StreamReconcileAction.Connect(true), tracker.retain(wantProcesses = true))
        assertEquals(StreamReconcileAction.Connect(false), tracker.release(wantProcesses = true))
        assertEquals(StreamReconcileAction.NoOp, tracker.release(wantProcesses = false))
        assertEquals(StreamReconcileAction.Disconnect, tracker.release(wantProcesses = false))
    }

    @Test
    fun `parseLogsEnvironment prepareIssueReport and LogsLiveFeedBuffer match web logs behaviour`() {
        // 1. parseLogsEnvironment parses Array<[string,string]> only
        val envArr = json.parseToJsonElement("""[["PPM","0.9.90"],["Runtime","Bun 1.2"]]""")
        assertEquals(listOf("PPM" to "0.9.90", "Runtime" to "Bun 1.2"), parseLogsEnvironment(envArr))
        val envObj = json.parseToJsonElement("""{"ppm":"0.9.90","os":"linux"}""")
        assertTrue(parseLogsEnvironment(envObj).isEmpty())

        // 2. prepareIssueReport redacts title + duplicateQuery and omits body from openUrl when over 8192 chars
        val redact = RedactOptions(home = true, email = true, chats = true, projects = listOf("secret-proj"))
        val longLine = "x".repeat(9000)
        val snip = ReportSnippet(
            id = "s1",
            src = "server",
            rows = foldRepeats(listOf(LogEntry(id = "e1", ts = 1000L, lv = "error", src = "server", tag = "ppm", msg = longLine))),
        )
        val prep = prepareIssueReport(
            fields = ReportFields(
                title = "Crash in /home/alice/secret-proj for alice@example.com",
                labels = listOf("bug"),
                what = "Failed at /home/alice",
            ),
            snippets = listOf(snip),
            environment = listOf("PPM" to "0.9.90"),
            redact = redact,
        )
        assertFalse(prep.redactedTitle.contains("alice"))
        assertFalse(prep.redactedTitle.contains("secret-proj"))
        assertEquals(prep.redactedTitle, prep.duplicateQuery)
        assertTrue(prep.bodyOverLimit)
        assertFalse(prep.openUrl.contains("body="))
        assertTrue(prep.body.contains(longLine))

        // 3. LogsLiveFeedBuffer holds in-flight lines during page read and merges by id without duplicates
        val buf = LogsLiveFeedBuffer()
        buf.beginPageRead()
        val liveDuringFetch = listOf(
            LogEntry(id = "2", ts = 2000L, lv = "info", src = "server", tag = "ppm", msg = "second"),
            LogEntry(id = "3", ts = 3000L, lv = "error", src = "server", tag = "ppm", msg = "third"),
        )
        val interim = buf.onLiveBatch(LogQueryResult(), liveDuringFetch, mapOf("s1" to "Chat 1"), DEFAULT_LOG_FILTER, follow = true)
        assertTrue(interim.entries.isEmpty())

        val page = LogQueryResult(
            entries = listOf(
                LogEntry(id = "1", ts = 1000L, lv = "info", src = "server", tag = "ppm", msg = "first"),
                LogEntry(id = "2", ts = 2000L, lv = "info", src = "server", tag = "ppm", msg = "second"),
            ),
            matched = 2,
            inRange = 2,
            fromTs = 500L,
        )
        val merged = buf.finishPageRead(page, DEFAULT_LOG_FILTER)
        assertEquals(listOf("1", "2", "3"), merged.entries.map { it.id })
        assertEquals("Chat 1", merged.titles["s1"])

        // 4. Paused entries and titles are cleared when beginPageRead() starts a new filter/range/reload fetch
        buf.onLiveBatch(
            merged,
            listOf(LogEntry(id = "4", ts = 4000L, lv = "warn", src = "server", tag = "ppm", msg = "held while paused")),
            mapOf("s2" to "Chat 2"),
            DEFAULT_LOG_FILTER,
            follow = false,
        )
        assertEquals(1, buf.pausedEntryCount)
        assertEquals(1, buf.pausedTitleCount)
        assertEquals(1, buf.pausedMatchingCount(merged, DEFAULT_LOG_FILTER))

        buf.beginPageRead()
        assertEquals(0, buf.pausedEntryCount)
        assertEquals(0, buf.pausedTitleCount)
        assertEquals(0, buf.pausedMatchingCount(merged, DEFAULT_LOG_FILTER))
    }
}
