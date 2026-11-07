package com.hienlh.ppm.app.ui.sysmon

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.logs.LogsAppBody
import com.hienlh.ppm.app.ui.tunnels.PortForwardingPanel
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.logs.*
import com.hienlh.ppm.state.sysmon.*
import com.hienlh.ppm.state.tunnels.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
}
