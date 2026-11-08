package com.hienlh.ppm.app.ui.sysmon

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.logs.LogsAppBody
import com.hienlh.ppm.app.ui.tunnels.PortForwardingPanel
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.ServerInfo
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.logs.*
import com.hienlh.ppm.state.sysmon.*
import com.hienlh.ppm.state.tunnels.*
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class Phase11aUiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun `SystemMonitorBody renders overview cards navigates subtabs and opens kill confirm dialog`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = AppModel(context)

        val snapshot = MetricsSnapshot(
            ts = 10_000L,
            system = SystemMetrics(
                cpu = CpuMetrics(total = 42.0, cores = listOf(40.0, 44.0), model = "AMD Ryzen 9"),
                mem = MemoryMetrics(totalMB = 16384.0, usedMB = 8192.0, availableMB = 8192.0, percent = 50.0),
                disk = RateMetrics(inBps = 1024.0, outBps = 2048.0, available = true),
                net = RateMetrics(inBps = 4096.0, outBps = 1024.0, available = true),
                processCount = 2,
                disks = listOf(DiskMetrics("nvme0n1", available = true, busyPercent = 12.0, responseMs = 0.8, readBps = 1024.0, writeBps = 2048.0, readTotal = 1000L, writeTotal = 2000L)),
                nics = listOf(NicMetrics("eth0", available = true, rxBps = 4096.0, txBps = 1024.0, rxTotal = 10000L, txTotal = 5000L, state = "up")),
            ),
            groups = listOf(
                ProcessGroup(key = "node-grp", label = "Node Server", rootPid = 201, cpu = 25.0, ramMB = 256.0, count = 1, ppm = true, pids = listOf(201)),
            ),
            processes = listOf(
                ProcessInfo(pid = 201, ppid = 1, name = "node", command = "node server.js", cpu = 25.0, ramMB = 256.0, ppm = true, protected = false, unitKey = "system:nginx.service"),
            ),
            apps = listOf(
                AppInfo(id = "code.desktop", name = "VS Code", pids = listOf(201)),
            ),
        )
        val services = ServicesSnapshot(
            supported = true,
            manager = "systemd",
            services = listOf(
                ServiceInfo(unit = "nginx.service", scope = "system", description = "Nginx HTTP Server", activeState = "active", subState = "running", running = true, enabled = true),
                ServiceInfo(unit = "backup.service", scope = "system", description = "Nightly Backup", activeState = "failed", subState = "failed", failed = true),
            ),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                SystemMonitorBody(
                    model = model,
                    initialTab = "overview",
                    initialSnapshot = snapshot,
                    initialServices = services,
                )
            }
        }

        // Overview cards
        composeRule.onNodeWithText("AMD Ryzen 9").assertIsDisplayed()
        composeRule.onNodeWithText("42%").assertIsDisplayed()
        composeRule.onNodeWithText("50%").assertIsDisplayed()

        // Click CPU card -> switches to Performance tab
        composeRule.onNodeWithTag("overview-card-cpu").performClick()
        composeRule.onNodeWithTag("sysmon-performance-panel").assertIsDisplayed()

        // Switch to Apps tab
        composeRule.onNodeWithTag("sysmon-tab-apps").performClick()
        composeRule.onNodeWithTag("sysmon-apps-panel").assertIsDisplayed()
        composeRule.onNodeWithText("VS Code").assertIsDisplayed()

        // Switch to Processes tab, open KillConfirmDialog
        composeRule.onNodeWithTag("sysmon-tab-processes").performClick()
        composeRule.onNodeWithTag("sysmon-processes-panel").assertIsDisplayed()
        composeRule.onNodeWithText("Node Server").assertIsDisplayed()
        composeRule.onNodeWithTag("proc-group-kill-node-grp").performClick()
        composeRule.onNodeWithTag("kill-confirm-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()

        // Switch to Services tab
        composeRule.onNodeWithTag("sysmon-tab-services").performClick()
        composeRule.onNodeWithTag("sysmon-services-panel").assertIsDisplayed()
        composeRule.onNodeWithText("nginx.service").assertIsDisplayed()
        composeRule.onNodeWithTag("services-filter-failed").performClick()
        composeRule.onNodeWithText("backup.service").assertIsDisplayed()
    }

    @Test
    fun `LogsAppBody renders folded rows restart dividers issues and bug report preview`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = AppModel(context)

        val entries = listOf(
            LogEntry(id = "l1", ts = 1_000L, lv = "info", src = "server", tag = "http", msg = "GET /api/health 200"),
            LogEntry(id = "l2", ts = 2_000L, lv = "info", src = "server", tag = "http", msg = "GET /api/health 200"),
            LogEntry(id = "l3", ts = 3_000L, lv = "error", src = "ai", tag = "claude", msg = "Unhandled token sk-ant-api03-abcdefghijklmnopqrstuvwx", more = listOf("at runTurn (chat.ts:42)")),
        )
        val queryResult = LogQueryResult(
            entries = entries,
            matched = 3,
            inRange = 3,
            restarts = listOf(2_500L),
            fromTs = 500L,
        )
        val issuesResult = LogIssuesResult(
            issues = listOf(
                LogIssue(
                    id = "iss-1",
                    cls = "bug",
                    title = "Claude stream crash on token parse",
                    area = "ai/claude",
                    src = "ai",
                    why = "Stream closed unexpectedly while parsing chunk.",
                    fix = "Guard null delta in stream parser.",
                    count = 4,
                    errors = 4,
                    lines = listOf(entries[2]),
                ),
            ),
            analyzedAt = 3_500L,
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                LogsAppBody(
                    model = model,
                    initialView = "logs",
                    initialResult = queryResult,
                    initialIssues = issuesResult,
                )
            }
        }

        // Folded repeat badge ×2 and restart divider
        composeRule.onNodeWithTag("log-repeat-l1", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("logs-restart-divider-l3").assertIsDisplayed()

        // Expand continuation lines on l3
        composeRule.onNodeWithTag("log-more-toggle-l3", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("at runTurn (chat.ts:42)", useUnmergedTree = true).assertIsDisplayed()

        // Switch to Issues tab
        composeRule.onNodeWithTag("logs-tab-issues").performClick()
        composeRule.onNodeWithTag("logs-issues-pane").assertIsDisplayed()
        composeRule.onNodeWithText("Claude stream crash on token parse").assertIsDisplayed()

        // Click Draft report on issue -> switches to Report tab with snippet attached
        composeRule.onNodeWithTag("issue-draft-report-iss-1").performClick()
        composeRule.onNodeWithTag("logs-report-pane").assertIsDisplayed()

        // Switch to Preview mode and verify secret redaction
        composeRule.onNodeWithTag("report-mode-preview").performClick()
        composeRule.onNodeWithTag("report-preview-box").assertIsDisplayed()
    }

    @Test
    fun `PortForwardingPanel renders setup rows active tunnels and external stop confirmation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = AppModel(context)

        val tunnels = listOf(
            TunnelEntry(pid = 401, port = 3000, url = "https://box.ts.net:3000", source = "ppm", via = "tailscale", status = "running"),
            TunnelEntry(pid = 402, port = 8080, url = "https://demo.trycloudflare.com", source = "external", via = "cloudflare", status = "running"),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                PortForwardingPanel(
                    model = model,
                    initialTunnels = tunnels,
                    initialTailscale = TailscaleAvailability(available = true, dnsName = "box.ts.net"),
                    initialCloudflare = NamedTunnelStatus(mode = "quick"),
                )
            }
        }

        composeRule.onNodeWithText("PORT FORWARDING").assertIsDisplayed()
        composeRule.onNodeWithText(":3000").assertIsDisplayed()
        composeRule.onNodeWithText("Tailnet").assertIsDisplayed()
        composeRule.onNodeWithText(":8080").assertIsDisplayed()
        composeRule.onNodeWithText("Started outside PPM").assertIsDisplayed()

        // Clicking stop on external tunnel opens ConfirmDialog
        composeRule.onNodeWithTag("tunnel-stop-402").performClick()
        composeRule.onNodeWithText("Stop tunnel?").assertIsDisplayed()
    }

    @Test
    fun `KillConfirmDialog sends bare signal name TERM and LogsAppBody sends snippets and environment array to report draft`() {
        val signalBodies = CopyOnWriteArrayList<String>()
        val draftBodies = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                val body = request.body?.utf8().orEmpty()
                return when {
                    target.endsWith("/api/system/resources/signal") -> {
                        signalBodies += body
                        MockResponse.Builder()
                            .addHeader("Content-Type", "application/json")
                            .body("""{"ok":true,"data":{"pid":201,"signal":"TERM","signaled":[201]}}""")
                            .build()
                    }
                    target.endsWith("/api/logs/environment") -> {
                        MockResponse.Builder()
                            .addHeader("Content-Type", "application/json")
                            .body("""{"ok":true,"data":[["PPM","0.9.90"],["Runtime","Bun 1.2.0"],["OS","linux x64 6.8.0"]]}""")
                            .build()
                    }
                    target.endsWith("/api/logs/report/draft") -> {
                        draftBodies += body
                        MockResponse.Builder()
                            .addHeader("Content-Type", "application/json")
                            .body(
                                """{"ok":true,"data":{"title":"Claude stream crash on token parse","summary":"Crash while parsing stream chunk.","expected":"Stream parses cleanly.","steps":["Start Claude chat"],"additional":"Needs null check."}}""",
                            )
                            .build()
                    }
                    target.contains("/api/logs/github/duplicates") -> {
                        MockResponse.Builder()
                            .addHeader("Content-Type", "application/json")
                            .body(
                                """{"ok":true,"data":{"query":"Claude stream crash on token parse","issues":[{"number":42,"title":"Claude stream crash on token parse","url":"https://github.com/hienlh/ppm/issues/42"}]}}""",
                            )
                            .build()
                    }
                    else -> {
                        MockResponse.Builder()
                            .addHeader("Content-Type", "application/json")
                            .body("""{"ok":true,"data":{}}""")
                            .build()
                    }
                }
            }
        }
        server.start()
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val model = AppModel(context)
            model.api = ApiClient(server.url("/"), "tok")

            val snapshot = MetricsSnapshot(
                ts = 10_000L,
                system = SystemMetrics(
                    cpu = CpuMetrics(total = 42.0, cores = listOf(40.0, 44.0), model = "AMD Ryzen 9"),
                    mem = MemoryMetrics(totalMB = 16384.0, usedMB = 8192.0, availableMB = 8192.0, percent = 50.0),
                    disk = RateMetrics(inBps = 1024.0, outBps = 2048.0, available = true),
                    net = RateMetrics(inBps = 4096.0, outBps = 1024.0, available = true),
                    processCount = 1,
                ),
                groups = listOf(
                    ProcessGroup(key = "node-grp", label = "Node Server", rootPid = 201, cpu = 25.0, ramMB = 256.0, count = 1, ppm = true, pids = listOf(201)),
                    ProcessGroup(key = "prot-grp", label = "PPM Core", rootPid = 100, cpu = 5.0, ramMB = 128.0, count = 1, ppm = true, pids = listOf(100)),
                ),
                processes = listOf(
                    ProcessInfo(pid = 201, ppid = 1, name = "node", command = "node server.js", cpu = 25.0, ramMB = 256.0, ppm = true, startedAt = 1234L),
                    ProcessInfo(pid = 100, ppid = 1, name = "ppm", command = "ppm start", cpu = 5.0, ramMB = 128.0, ppm = true, protected = true, startedAt = 1000L),
                ),
            )
            val entries = listOf(
                LogEntry(id = "l3", ts = 3_000L, lv = "error", src = "ai", tag = "claude", msg = "Stream closed unexpectedly"),
            )
            val queryResult = LogQueryResult(entries = entries, matched = 1, inRange = 1)
            val issuesResult = LogIssuesResult(
                issues = listOf(
                    LogIssue(
                        id = "iss-1",
                        cls = "bug",
                        title = "Claude stream crash on token parse",
                        area = "ai/claude",
                        src = "ai",
                        why = "Stream closed unexpectedly while parsing chunk.",
                        fix = "Guard null delta in stream parser.",
                        count = 1,
                        errors = 1,
                        lines = entries,
                    ),
                ),
                analyzedAt = 3_500L,
            )

            composeRule.setContent {
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            SystemMonitorBody(
                                model = model,
                                initialTab = "processes",
                                initialSnapshot = snapshot,
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            LogsAppBody(
                                model = model,
                                initialView = "issues",
                                initialResult = queryResult,
                                initialIssues = issuesResult,
                            )
                        }
                    }
                }
            }

            // B4 / 11a.3: Group menu "End application" opens KillConfirmDialog; no signal chips, no Signal button, no PPM workspace note
            composeRule.onNodeWithTag("proc-group-menu-node-grp").performClick()
            composeRule.onNodeWithText("End application").assertIsDisplayed()
            composeRule.onNodeWithTag("proc-group-menu-end-application").performClick()
            composeRule.onNodeWithTag("kill-confirm-dialog").assertIsDisplayed()
            composeRule.onNodeWithTag("signal-chip-TERM").assertDoesNotExist()
            composeRule.onNodeWithTag("kill-confirm-signal-btn").assertDoesNotExist()
            composeRule.onNodeWithText("This process belongs to a PPM workspace or terminal.").assertDoesNotExist()
            composeRule.onNodeWithText("Cancel").performClick()

            // Send signal from process row menu ("Send signal" -> "Terminate (SIGTERM)")
            composeRule.onNodeWithTag("proc-mode-flat").performClick()
            composeRule.onNodeWithTag("proc-menu-201").performClick()
            composeRule.onNodeWithTag("proc-menu-send-signal").performClick()
            composeRule.onNodeWithTag("proc-menu-signal-TERM").performClick()

            composeRule.waitUntil(5_000) {
                signalBodies.isNotEmpty() && model.toasts.any { it.message.title == "Sent SIGTERM to node (201)" }
            }
            val sigJson = WireJson.parse(signalBodies.first())
            assertEquals("TERM", sigJson["signal"].string)
            assertEquals(201, sigJson["pid"].int)

            // B5: Click Draft report on issue -> switches to Report tab and calls Draft with AI
            composeRule.onNodeWithTag("issue-draft-report-iss-1").performClick()

            composeRule.waitUntil(5_000) { draftBodies.isNotEmpty() }
            val draftJson = WireJson.parse(draftBodies.first())
            val snippets = draftJson["snippets"].array.orEmpty()
            assertEquals(1, snippets.size)
            assertEquals("AI & chat · 00:00:03–00:00:03 UTC", snippets[0]["label"].string)
            assertTrue(snippets[0]["lines"].array.orEmpty().isNotEmpty())
            assertEquals("Claude stream crash on token parse", draftJson["note"].string)
            val envArr = draftJson["environment"].array.orEmpty()
            assertEquals(4, envArr.size)
            assertEquals("PPM", envArr[0].array.orEmpty()[0].string)
            assertEquals("0.9.90", envArr[0].array.orEmpty()[1].string)
            assertEquals("Browser", envArr[3].array.orEmpty()[0].string)
            assertTrue(envArr[3].array.orEmpty()[1].string.orEmpty().contains("Android"))

            // Wait for 700ms duplicate-issue search debounce and assert web strings from server JSON shape
            composeRule.mainClock.advanceTimeBy(800L)
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("report-duplicates-box")).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("report-duplicates-box").performScrollTo()
            composeRule.onNodeWithText("Open issues that look like this one:").assertIsDisplayed()
            composeRule.onNodeWithText("#42 Claude stream crash on token parse").assertIsDisplayed()

            // Protected group row menu shows "End application" and protected note
            composeRule.onNodeWithTag("proc-mode-grouped").performClick()
            composeRule.onNodeWithTag("proc-group-menu-prot-grp").performClick()
            composeRule.onNodeWithText("End application").assertIsDisplayed()
            composeRule.onNodeWithText("One of this app's processes is protected, so PPM will not end it.").assertIsDisplayed()
        } finally {
            server.close()
        }
    }

    @Test
    fun `KillConfirmDialog defaults includeTree to false shows CPU RAM has no Force Stop and posts to kill endpoint`() {
        val killBodies = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                val body = request.body?.utf8().orEmpty()
                return when {
                    target.endsWith("/api/system/resources/kill") -> {
                        killBodies += body
                        MockResponse.Builder()
                            .addHeader("Content-Type", "application/json")
                            .body("""{"ok":true,"data":{"pid":201,"killed":[201]}}""")
                            .build()
                    }
                    else -> {
                        MockResponse.Builder()
                            .addHeader("Content-Type", "application/json")
                            .body("""{"ok":true,"data":{}}""")
                            .build()
                    }
                }
            }
        }
        server.start()
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val model = AppModel(context)
            model.api = ApiClient(server.url("/"), "tok")

            val snapshot = MetricsSnapshot(
                ts = 10_000L,
                processes = listOf(
                    ProcessInfo(pid = 201, ppid = 1, name = "node", command = "node server.js", cpu = 25.0, ramMB = 256.0, startedAt = 1234L),
                ),
                groups = listOf(
                    ProcessGroup(key = "node-grp", label = "Node Server", rootPid = 201, cpu = 25.0, ramMB = 256.0, count = 1, ppm = false, pids = listOf(201)),
                ),
            )

            composeRule.setContent {
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                    SystemMonitorBody(
                        model = model,
                        initialTab = "processes",
                        initialSnapshot = snapshot,
                    )
                }
            }

            // Expand group or switch to flat to click single process kill
            composeRule.onNodeWithTag("proc-mode-flat").performClick()
            composeRule.onNodeWithTag("proc-kill-201").performClick()
            composeRule.onNodeWithTag("kill-confirm-dialog").assertIsDisplayed()
            composeRule.onNodeWithText("CPU 25.0% · RAM 256 MB").assertIsDisplayed()
            composeRule.onNodeWithText("Force Stop").assertDoesNotExist()
            composeRule.onNodeWithTag("sysmon-kill-confirm-ok").performClick()

            composeRule.waitUntil(5_000) { killBodies.isNotEmpty() && model.toasts.any { it.message.title == "Ended node (201)" } }
            val killJson = WireJson.parse(killBodies.first())
            assertEquals(201, killJson["pid"].int)
            assertEquals(false, killJson["tree"]?.toString()?.toBooleanStrictOrNull())
            assertTrue(model.toasts.any { it.message.title == "Ended node (201)" })
        } finally {
            server.close()
        }
    }

    @Test
    fun `ServicesPanel confirms stop restart and disable before calling systemServiceAction`() {
        val actionPaths = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                if (request.method == "POST" && target.contains("/api/system/services/")) {
                    actionPaths += target
                    if (target.endsWith("/disable")) {
                        return MockResponse.Builder()
                            .code(500)
                            .addHeader("Content-Type", "application/json")
                            .body("""{"ok":false}""")
                            .build()
                    }
                    return MockResponse.Builder()
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"unit":"nginx.service","action":"stop"}}""")
                        .build()
                }
                return MockResponse.Builder()
                    .addHeader("Content-Type", "application/json")
                    .body("""{"ok":true,"data":{"supported":true,"manager":"systemd","services":[{"unit":"nginx.service","scope":"system","description":"Nginx web server","activeState":"active","subState":"running","running":true,"failed":false,"enabled":true,"mainPid":812}]}}""")
                    .build()
            }
        }
        server.start()
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val model = AppModel(context)
            model.api = ApiClient(server.url("/"), "tok")

            val services = ServicesSnapshot(
                supported = true,
                manager = "systemd",
                services = listOf(
                    ServiceInfo(
                        unit = "nginx.service",
                        scope = "system",
                        description = "Nginx web server",
                        activeState = "active",
                        subState = "running",
                        running = true,
                        enabled = true,
                        mainPid = 812,
                    ),
                ),
            )

            composeRule.setContent {
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                    SystemMonitorBody(
                        model = model,
                        initialTab = "services",
                        initialServices = services,
                    )
                }
            }

            // No disable icon on the service row
            composeRule.onNodeWithTag("service-disable-nginx.service").assertDoesNotExist()

            composeRule.onNodeWithTag("service-stop-nginx.service").performClick()
            // Must not call API before confirming!
            assertTrue(actionPaths.isEmpty())
            composeRule.onNodeWithTag("sysmon-service-confirm").assertIsDisplayed()
            composeRule.onNodeWithText("Stop nginx.service? Anything depending on it stops too.").assertIsDisplayed()
            composeRule.onNodeWithText("System service · pid 812").assertIsDisplayed()
            composeRule.onNodeWithTag("sysmon-service-confirm-ok").performClick()

            composeRule.waitUntil(5_000) {
                actionPaths.isNotEmpty() && model.toasts.any { it.message.title == "nginx.service: stop done" }
            }
            assertTrue(actionPaths.first().endsWith("/api/system/services/system/nginx.service/stop"))

            // Long-press row opens context menu with "Disable at boot"; failure shows toast "Could not disable nginx.service"
            composeRule.onNodeWithTag("service-row-nginx.service").performTouchInput { longClick() }
            composeRule.onNodeWithText("Disable at boot").assertIsDisplayed()
            composeRule.onNodeWithTag("service-menu-action-disable").performClick()
            composeRule.onNodeWithTag("sysmon-service-confirm").assertIsDisplayed()
            composeRule.onNodeWithTag("sysmon-service-confirm-ok").performClick()

            composeRule.waitUntil(5_000) {
                actionPaths.size >= 2 && model.toasts.any { it.message.title == "Could not disable nginx.service" }
            }
            composeRule.onNodeWithText("Service action 'disable' failed").assertDoesNotExist()
        } finally {
            server.close()
        }
    }

    @Test
    fun `LogsBadge gates minimalMode on info polls only when visible and switch calls updateLogsBadge`() {
        val summaryHits = java.util.concurrent.atomic.AtomicInteger(0)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                return when {
                    target.endsWith("/api/logs/issues/summary") -> {
                        val hit = summaryHits.incrementAndGet()
                        MockResponse.Builder()
                            .addHeader("Content-Type", "application/json")
                            .body("""{"ok":true,"data":{"likelyBugs":$hit,"running":false}}""")
                            .build()
                    }
                    target.endsWith("/api/logs/issues") -> {
                        MockResponse.Builder()
                            .addHeader("Content-Type", "application/json")
                            .body("""{"ok":true,"data":{"issues":[],"unsorted":0,"running":false,"auto":false}}""")
                            .build()
                    }
                    else -> {
                        MockResponse.Builder()
                            .addHeader("Content-Type", "application/json")
                            .body("""{"ok":true,"data":{}}""")
                            .build()
                    }
                }
            }
        }
        server.start()
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val model = AppModel(context)
            model.api = ApiClient(server.url("/"), "tok")

            // 1. Before info loads (info == null), refreshLogsIssuesSummary / reconcileLogsBadge must be gated off
            model.refreshLogsIssuesSummary()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(0, summaryHits.get())

            // 2. When minimalMode is true, still gated off
            model.info = ServerInfo(version = "1.0.0", deviceName = "dev", tunnelActive = false, minimalMode = true)
            model.reconcileLogsBadge(pollIntervalMs = 80L)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(0, summaryHits.get())

            // 3. When info loads with minimalMode = false, summary is fetched and poll runs while visible
            model.info = ServerInfo(version = "1.0.0", deviceName = "dev", tunnelActive = false, minimalMode = false)
            model.windowVisible = false
            model.reconcileLogsBadge(pollIntervalMs = 80L)
            composeRule.waitUntil(5_000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                summaryHits.get() >= 1 && model.showLogsBadgeDot
            }
            val hitsWhileHidden = summaryHits.get()
            Thread.sleep(150L)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(hitsWhileHidden, summaryHits.get())

            // 4. Mount LogsAppBody, verify header has no UTC/offset chip, 32dp prefs button on tablet,
            // open preferences, and toggle "Red dot on the Logs button for a likely bug"
            val phoneMode = androidx.compose.runtime.mutableStateOf(false)
            composeRule.setContent {
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = phoneMode.value) {
                    LogsAppBody(
                        model = model,
                        initialView = "logs",
                        initialResult = LogQueryResult(),
                        initialIssues = LogIssuesResult(),
                    )
                }
            }
            assertEquals(1, model.openLogsWindowCount)
            // No UTC or localOffsetLabel chip in header before opening preferences
            composeRule.onNodeWithText("UTC").assertDoesNotExist()
            composeRule.onNodeWithText(localOffsetLabel()).assertDoesNotExist()
            val prefsBtnBounds = composeRule.onNodeWithTag("logs-prefs-toggle").fetchSemanticsNode().boundsInRoot
            val expected32dp = with(composeRule.density) { 32.dp.toPx() }
            assertEquals(expected32dp, prefsBtnBounds.width, 1f)
            assertEquals(expected32dp, prefsBtnBounds.height, 1f)
            composeRule.onNodeWithTag("logs-prefs-toggle").performClick()
            composeRule.onNodeWithText("Red dot on the Logs button for a likely bug").assertIsDisplayed()
            composeRule.onNodeWithTag("logs-prefs-badge-switch").performClick()
            assertEquals(false, model.logsBadge)
            assertEquals(0, model.logsLikelyBugs)

            // 5. On phone layout, neither the preferences button nor the preferences row is shown
            phoneMode.value = true
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("logs-prefs-toggle").assertDoesNotExist()
            composeRule.onNodeWithTag("logs-prefs").assertDoesNotExist()
        } finally {
            server.close()
        }
    }

    @Test
    fun `SystemMonitorBody always requests processes StatusBar hides CPU MEM when disconnected and reconnect resets tickCount`() {
        val streamHits = java.util.concurrent.atomic.AtomicInteger(0)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                return when {
                    target.contains("/api/system/resources/stream") -> {
                        val hit = streamHits.incrementAndGet()
                        val sseBody = if (hit == 1) {
                            buildString {
                                append("event: snapshot\n")
                                append("""data: {"ts":1000,"system":{"cpu":{"total":42},"mem":{"totalMB":1024,"usedMB":512,"availableMB":512,"percent":50},"disk":{"inBps":0,"outBps":0,"available":false},"net":{"inBps":0,"outBps":0,"available":false},"processCount":1},"groups":[],"processes":[]}""" + "\n\n")
                                append("event: snapshot\n")
                                append("""data: {"ts":2000,"system":{"cpu":{"total":45},"mem":{"totalMB":1024,"usedMB":512,"availableMB":512,"percent":50},"disk":{"inBps":0,"outBps":0,"available":false},"net":{"inBps":0,"outBps":0,"available":false},"processCount":1},"groups":[],"processes":[]}""" + "\n\n")
                            }
                        } else {
                            buildString {
                                append("event: snapshot\n")
                                append("""data: {"ts":3000,"system":{"cpu":{"total":55},"mem":{"totalMB":1024,"usedMB":512,"availableMB":512,"percent":50},"disk":{"inBps":0,"outBps":0,"available":false},"net":{"inBps":0,"outBps":0,"available":false},"processCount":1},"groups":[],"processes":[]}""" + "\n\n")
                            }
                        }
                        MockResponse.Builder()
                            .addHeader("Content-Type", "text/event-stream")
                            .body(sseBody)
                            .build()
                    }
                    else -> {
                        MockResponse.Builder()
                            .addHeader("Content-Type", "application/json")
                            .body("""{"ok":true,"data":{}}""")
                            .build()
                    }
                }
            }
        }
        server.start()
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val model = AppModel(context)
            model.api = ApiClient(server.url("/"), "tok")

            val release = model.retainResourceMonitor(wantProcesses = true, reconnectDelayMs = 200L)
            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline && model.sharedMetricsSnapshot?.ts != 2000L) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(10L)
            }
            assertEquals(2, model.sharedMetricsTickCount)

            // Advance main looper past the 200ms reconnect delay so connection #2 runs
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(250))
            while (System.currentTimeMillis() < deadline && model.sharedMetricsSnapshot?.ts != 3000L) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(10L)
            }
            // Second connection emitted only 1 snapshot, so tickCount must have been reset to 0 on reconnect -> 1
            assertEquals(1, model.sharedMetricsTickCount)
            release()

            // Mount SystemMonitorBody on overview tab and verify it retains with wantProcesses = true
            composeRule.setContent {
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                    Column(Modifier.fillMaxSize()) {
                        com.hienlh.ppm.app.ui.tablet.StatusBar(model = model)
                        Box(Modifier.weight(1f)) {
                            SystemMonitorBody(
                                model = model,
                                initialTab = "overview",
                            )
                        }
                    }
                }
            }
            assertEquals(1, model.resourceTierTracker.processesRefCount)

            // Stop background stream job while keeping model.api non-null so StatusBar renders Resources deterministically
            composeRule.waitForIdle()
            model.applyResourceReconcile(com.hienlh.ppm.state.sysmon.StreamReconcileAction.Disconnect)
            model.sharedMetricsSnapshot = MetricsSnapshot(
                ts = 3000L,
                system = SystemMetrics(
                    cpu = CpuMetrics(total = 55.0),
                    mem = MemoryMetrics(totalMB = 1024.0, usedMB = 512.0, availableMB = 512.0, percent = 50.0),
                ),
            )
            model.sharedMetricsConnected = false
            composeRule.waitForIdle()
            composeRule.onNodeWithText("CPU 55%", useUnmergedTree = true).assertDoesNotExist()

            model.sharedMetricsConnected = true
            composeRule.waitForIdle()
            composeRule.onNodeWithText("CPU 55%", useUnmergedTree = true).assertIsDisplayed()
        } finally {
            server.close()
        }
    }

    @Test
    fun `retainLogsTail reference counts subscribe and unsubscribe across two LogsAppBody views`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = AppModel(context)
        val sentFrames = mutableListOf<String>()
        model.logsTailSender = { frame -> sentFrames += frame }

        val showFirst = androidx.compose.runtime.mutableStateOf(true)
        val showSecond = androidx.compose.runtime.mutableStateOf(true)
        val initialLogs = LogQueryResult(
            entries = listOf(LogEntry(id = "1", ts = 1000L, lv = "info", src = "server", tag = "ppm", msg = "line 1")),
            matched = 1,
            inRange = 1,
            fromTs = 0L,
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Column(Modifier.fillMaxSize()) {
                    if (showFirst.value) {
                        Box(Modifier.weight(1f)) {
                            LogsAppBody(model = model, initialResult = initialLogs)
                        }
                    }
                    if (showSecond.value) {
                        Box(Modifier.weight(1f)) {
                            LogsAppBody(model = model, initialResult = initialLogs)
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        assertEquals(2, model.openLogsWindowCount)
        assertEquals(listOf("""{"type":"logs:subscribe"}"""), sentFrames)

        // Closing the second Logs view must NOT send logs:unsubscribe while the first view remains open
        showSecond.value = false
        composeRule.waitForIdle()
        assertEquals(1, model.openLogsWindowCount)
        assertEquals(listOf("""{"type":"logs:subscribe"}"""), sentFrames)

        // Closing the last Logs view sends logs:unsubscribe
        showFirst.value = false
        composeRule.waitForIdle()
        assertEquals(0, model.openLogsWindowCount)
        assertEquals(listOf("""{"type":"logs:subscribe"}""", """{"type":"logs:unsubscribe"}"""), sentFrames)
    }

    @Test
    fun `LogsReportPane toggles Project names redaction shows Copy body and open GitHub and toasts on copy failure`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = AppModel(context)
        model.projects = listOf(com.hienlh.ppm.protocol.ProjectInfo(name = "secret-project", path = "/work/secret-project"))
        model.reportClipboardCopy = { false }

        val longMsg = "Crash in secret-project " + "x".repeat(8500)
        val entries = listOf(
            LogEntry(id = "l1", ts = 1_000L, lv = "error", src = "server", tag = "ppm", msg = longMsg),
        )
        val issuesResult = LogIssuesResult(
            issues = listOf(
                LogIssue(
                    id = "iss-long",
                    cls = "bug",
                    title = "Failure in secret-project",
                    area = "server",
                    src = "server",
                    why = "Long error in secret-project",
                    count = 1,
                    errors = 1,
                    lines = entries,
                ),
            ),
            analyzedAt = 2_000L,
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                LogsAppBody(
                    model = model,
                    initialView = "issues",
                    initialResult = LogQueryResult(entries = entries, matched = 1, inRange = 1),
                    initialIssues = issuesResult,
                )
            }
        }
        composeRule.waitForIdle()

        // Click Draft report on issue -> opens Report tab with >8KB body
        composeRule.onNodeWithTag("issue-draft-report-iss-long").performClick()
        composeRule.waitForIdle()

        // Button text is "Copy body and open GitHub", NOT "Open issue (copies body)"
        composeRule.onNodeWithTag("report-open-github-btn").performScrollTo()
        composeRule.onNodeWithText("Copy body and open GitHub", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Open issue (copies body)", useUnmergedTree = true).assertDoesNotExist()

        // Clicking when clipboard copy fails shows exact web error toast
        composeRule.onNodeWithTag("report-open-github-btn").performClick()
        composeRule.waitForIdle()
        assertTrue(
            model.toasts.any {
                it.message.title == "Could not copy the body. Use Copy as Markdown, then paste it into the issue." &&
                    it.message.kind == com.hienlh.ppm.app.ErrorToastMessage.Kind.ERROR
            },
        )

        // Toggle "Project names" redaction chip on and verify preview redacts "secret-project" -> "<project>"
        composeRule.onNodeWithTag("report-privacy-projects").performScrollTo()
        composeRule.onNodeWithText("Project names", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("report-privacy-projects").performClick()
        composeRule.onNodeWithTag("report-mode-preview").performScrollTo()
        composeRule.onNodeWithTag("report-mode-preview").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("# Failure in <project>").assertIsDisplayed()
    }
}
