package com.hienlh.ppm.app.ui.phone.tools

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.logs.LogsAppBody
import com.hienlh.ppm.app.ui.remotedesktop.RemoteDesktopTabletToolbar
import com.hienlh.ppm.app.ui.sysmon.SystemMonitorBody
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.logs.LogEntry
import com.hienlh.ppm.state.logs.LogIssuesResult
import com.hienlh.ppm.state.logs.LogQueryResult
import com.hienlh.ppm.state.remotedesktop.RemoteDesktopCapabilities
import com.hienlh.ppm.state.settings.CloudStatusState
import com.hienlh.ppm.state.settings.PublicTunnelState
import com.hienlh.ppm.state.settings.ScheduleLogLine
import com.hienlh.ppm.state.settings.ScheduleRowState
import com.hienlh.ppm.state.settings.ScheduleRunRow
import com.hienlh.ppm.state.settings.ScheduleStatusState
import com.hienlh.ppm.state.sysmon.AppInfo
import com.hienlh.ppm.state.sysmon.CpuMetrics
import com.hienlh.ppm.state.sysmon.DiskMetrics
import com.hienlh.ppm.state.sysmon.MemoryMetrics
import com.hienlh.ppm.state.sysmon.MetricsSnapshot
import com.hienlh.ppm.state.sysmon.ProcessGroup
import com.hienlh.ppm.state.sysmon.ProcessInfo
import com.hienlh.ppm.state.sysmon.ServiceInfo
import com.hienlh.ppm.state.sysmon.ServicesSnapshot
import com.hienlh.ppm.state.sysmon.SystemMetrics
import com.hienlh.ppm.state.tunnels.SetupState
import com.hienlh.ppm.state.tunnels.TunnelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h1200dp-xxhdpi")
class PhoneToolsTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private fun sampleSnapshot(): MetricsSnapshot = MetricsSnapshot(
        ts = 1_700_000_000_000L,
        system = SystemMetrics(
            cpu = CpuMetrics(
                total = 64.0,
                cores = listOf(55.0, 73.0),
                model = "Apple M3 Pro",
                currentMHz = 3200.0,
                tempC = 58.0,
            ),
            mem = MemoryMetrics(
                totalMB = 16384.0,
                usedMB = 13500.0,
                availableMB = 2884.0,
                percent = 82.4,
            ),
            disks = listOf(
                DiskMetrics(
                    id = "nvme0n1",
                    available = true,
                    busyPercent = 39.0,
                    responseMs = 1.2,
                    readBps = 1024.0,
                    writeBps = 2048.0,
                    readTotal = 100_000L,
                    writeTotal = 200_000L,
                ),
            ),
        ),
        processes = listOf(
            ProcessInfo(
                pid = 101,
                ppid = 1,
                name = "bun",
                command = "bun run dev",
                cpu = 24.5,
                ramMB = 420.0,
                ppm = true,
                protected = true,
            ),
            ProcessInfo(
                pid = 202,
                ppid = 1,
                name = "node",
                command = "node worker.js",
                cpu = 14.2,
                ramMB = 280.0,
                ppm = false,
                protected = false,
            ),
        ),
        groups = listOf(
            ProcessGroup(
                key = "node-grp",
                label = "Node Worker",
                count = 1,
                cpu = 14.2,
                ramMB = 280.0,
                pids = listOf(202),
                ppm = false,
            ),
        ),
        apps = listOf(
            AppInfo(
                id = "app-node",
                name = "Node Worker",
                pids = listOf(202),
            ),
        ),
    )

    private fun sampleServices(): ServicesSnapshot = ServicesSnapshot(
        supported = true,
        manager = "systemd",
        services = listOf(
            ServiceInfo(
                unit = "ppm.service",
                scope = "user",
                description = "PPM Server",
                activeState = "active",
                subState = "running",
                enabled = true,
                running = true,
                failed = false,
            ),
            ServiceInfo(
                unit = "nginx.service",
                scope = "system",
                description = "Nginx HTTP Server",
                activeState = "active",
                subState = "running",
                enabled = true,
                running = true,
                failed = false,
            ),
        ),
    )

    @Test
    fun phoneMonitorState_enforcesLookOnlyAndDrillInAndThresholds() {
        assertEquals(MonitorMetricLevel.NORMAL, MonitorMetricLevel.fromPercent(42.0))
        assertEquals(MonitorMetricLevel.WARNING, MonitorMetricLevel.fromPercent(58.0))
        assertEquals(MonitorMetricLevel.CRITICAL, MonitorMetricLevel.fromPercent(85.0))

        val state = PhoneMonitorState()
        assertFalse(state.canKillProcess)
        assertFalse(state.canSendSignal)
        assertFalse(state.canEndAppTask)
        assertFalse(state.canControlService)

        state.selectTab(PhoneMonitorTab.PERFORMANCE)
        assertNull(state.selectedDeviceKey)
        state.openDeviceDetail("cpu")
        assertTrue(state.isDrilledIntoDevice)
        assertEquals("cpu", state.selectedDeviceKey)
        assertTrue(state.backFromDeviceDetail())
        assertNull(state.selectedDeviceKey)
    }

    @Test
    fun phoneSystemMonitor_hidesKillAndServiceControls_andDrillsInPerformance() {
        val state = PhoneMonitorState(initialTab = PhoneMonitorTab.PERFORMANCE)
        val snap = sampleSnapshot()

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneSystemMonitorScreen(
                    state = state,
                    snapshot = snap,
                    servicesSnapshot = sampleServices(),
                )
            }
        }

        // Drill in on CPU device and back via `‹ Devices`
        composeRule.onNodeWithTag("phone-sysmon-device-cpu").performClick()
        composeRule.onNodeWithTag("phone-sysmon-back-devices").assertIsDisplayed()
        composeRule.onNodeWithTag("phone-sysmon-back-devices").performClick()
        composeRule.onNodeWithTag("phone-sysmon-device-cpu").assertIsDisplayed()

        // Switch to Processes: bottom bar is displayed and no kill button exists
        composeRule.onNodeWithTag("phone-sysmon-tab-processes").performClick()
        composeRule.onNodeWithTag("phone-sysmon-bottom-bar").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTag("proc-group-kill-node-grp").fetchSemanticsNodes().size)
    }

    @Test
    fun systemMonitorBody_phoneReadOnlyHidesKillButtons_tabletShowsPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = AppModel(context)
        val snap = sampleSnapshot()

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                SystemMonitorBody(
                    model = model,
                    initialTab = "processes",
                    initialSnapshot = snap,
                    initialServices = sampleServices(),
                )
            }
        }

        // On phone (`mobile = true`), group kill and process kill buttons are hidden
        composeRule.onNodeWithTag("proc-group-node-grp").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTag("proc-group-kill-node-grp").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithTag("sysmon-preferences-trigger").fetchSemanticsNodes().size)
    }

    @Test
    fun phoneLogsScreen_isLookOnlyWithFiltersSheetAndNoIssuesOrReportTabs() {
        val state = PhoneLogsState()
        assertFalse(state.hasIssuesTab)
        assertFalse(state.hasReportTab)
        assertFalse(state.canSelectLines)
        assertFalse(state.canAskAi)

        val entries = listOf(
            LogEntry(id = "l1", ts = 1_700_000_000_000L, lv = "error", src = "server", tag = "http", msg = "Port 3210 busy"),
            LogEntry(id = "l2", ts = 1_700_000_001_000L, lv = "error", src = "server", tag = "http", msg = "Port 3210 busy"),
            LogEntry(id = "l3", ts = 1_700_000_060_000L, lv = "info", src = "ai", tag = "chat", msg = "Turn completed"),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneLogsScreen(
                    state = state,
                    entries = entries,
                )
            }
        }

        // Repeat badge x2 on folded l1/l2
        composeRule.onNodeWithText("×2").assertIsDisplayed()

        // Toggle Pause / Follow
        composeRule.onNodeWithTag("phone-logs-follow-toggle").performClick()
        assertFalse(state.follow)

        // Open Filters sheet, toggle UTC, and apply
        composeRule.onNodeWithTag("phone-logs-filters-btn").performClick()
        composeRule.onNodeWithTag("phone-logs-filters-sheet", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("phone-logs-utc-toggle", useUnmergedTree = true).performClick()
        assertTrue(state.showUtc)
        composeRule.onNodeWithTag("phone-logs-filters-apply", useUnmergedTree = true).performClick()
        assertFalse(state.filtersSheetOpen)
    }

    @Test
    fun logsAppBody_phoneHidesIssuesAndReportTabs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = AppModel(context)
        val result = LogQueryResult(
            entries = listOf(
                LogEntry(id = "e1", ts = 1_700_000_000_000L, lv = "info", src = "server", tag = "srv", msg = "Ready"),
            ),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                LogsAppBody(
                    model = model,
                    initialResult = result,
                    initialIssues = LogIssuesResult(),
                )
            }
        }

        composeRule.onNodeWithTag("logs-tab-logs").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTag("logs-tab-issues").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithTag("logs-tab-report").fetchSemanticsNodes().size)
    }

    @Test
    fun phonePortForwarding_showsTabletSetupNotice_forwardsPort_andConfirmsExternalStop() {
        val state = PhonePortForwardingState(initialTransport = "tailscale", initialPortInput = "5173")
        var forwardedPort: Int? = null
        var stoppedPid: Int? = null
        val externalTunnel = TunnelEntry(
            pid = 909,
            port = 8080,
            url = "https://demo.trycloudflare.com",
            via = "cloudflare",
            source = "external",
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhonePortForwardingScreen(
                    state = state,
                    tunnels = listOf(externalTunnel),
                    tailscaleSetup = SetupState("devbox.tail1234.ts.net", done = true),
                    cloudflareSetup = SetupState("No domain set up", done = false),
                    onStartForward = { port, _ -> forwardedPort = port },
                    onStopTunnel = { entry -> stoppedPid = entry.pid },
                    onOpenInTab = {},
                    onOpenInBrowser = {},
                    onCopyLink = {},
                )
            }
        }

        // Shows "Set up Tailscale or Cloudflare on a tablet." when Cloudflare is not configured
        composeRule.onNodeWithTag("phone-tunnels-tablet-setup-notice").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Set up Tailscale or Cloudflare on a tablet.").assertIsDisplayed()

        // Forward port from bottom action card
        composeRule.onNodeWithTag("phone-tunnels-forward-btn").performClick()
        assertEquals(5173, forwardedPort)

        // Stopping an external tunnel opens confirmation first
        composeRule.onNodeWithTag("phone-tunnel-stop-8080").performScrollTo().performClick()
        composeRule.onNodeWithTag("phone-tunnel-external-confirm-sheet", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Stop a tunnel PPM did not start?", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Stop tunnel", useUnmergedTree = true).performClick()
        assertEquals(909, stoppedPid)
    }

    @Test
    fun phoneSchedulesScreen_togglesRunsAndReadsLogs_withoutScriptEditing() {
        val schedules = listOf(
            ScheduleRowState(
                id = 1,
                name = "Nightly backup",
                kind = "script",
                cronExpr = "0 2 * * *",
                prompt = "./scripts/backup.sh",
                script = "./scripts/backup.sh",
                enabled = true,
                status = ScheduleStatusState(
                    state = "error",
                    lastError = "Exit code 2: disk full",
                ),
            ),
        )
        val state = PhoneSchedulesState(initialSchedules = schedules)
        assertFalse(state.canEditScript)
        assertFalse(state.canCreateOrDeleteSchedule)

        var ranId: Int? = null
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneSchedulesScreen(
                    state = state,
                    runsByScheduleId = mapOf(
                        1 to listOf(
                            ScheduleRunRow(
                                id = 42,
                                scheduleId = 1,
                                status = "error",
                                startedAt = "02:00",
                            ),
                        ),
                    ),
                    logsByScheduleId = mapOf(
                        1 to listOf(
                            ScheduleLogLine(seq = 1, stream = "stderr", ts = 1_700_000_000_000L, text = "No space left on device"),
                        ),
                    ),
                    onRunOrRestart = { row -> ranId = row.id },
                    onToggleEnabled = { row, enabled -> state.toggleScheduleEnabled(row.id, enabled) },
                )
            }
        }

        // Shows tablet notice for script editing and no script editor input
        composeRule.onNodeWithTag("phone-schedules-tablet-notice").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Edit a script or add a schedule on a tablet.").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTag("tablet-schedule-script-editor").fetchSemanticsNodes().size)

        // Toggle enable switch
        composeRule.onNodeWithTag("phone-schedule-switch-1", useUnmergedTree = true).performClick()
        assertFalse(state.schedules.first().enabled)

        // Primary action button in bottom card triggers Run now
        composeRule.onNodeWithTag("phone-schedules-primary-btn").performClick()
        assertEquals(1, ranId)

        // Open Full log page, verify Last error banner and log line, then back
        composeRule.onNodeWithTag("phone-schedules-full-log-btn").performClick()
        composeRule.onNodeWithTag("phone-schedule-log-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("phone-schedule-log-error-banner").assertIsDisplayed()
        composeRule.onNodeWithTag("phone-schedule-log-line-0").assertIsDisplayed()
        composeRule.onNodeWithTag("phone-schedule-log-back").performClick()
        composeRule.onNodeWithTag("phone-schedules-screen").assertIsDisplayed()
    }

    @Test
    fun phoneCloudShareSheet_rendersQrCode_validatesSlug_andTogglesSharing() {
        val state = PhoneCloudShareState().apply {
            loading = false
            cloud = CloudStatusState(loggedIn = true, email = "dev@host.io", cloudUrl = "https://ppm.bot", linked = true, deviceName = "Workbench")
            tunnel = PublicTunnelState(active = true, url = "https://abc.trycloudflare.com", localUrl = "http://192.168.1.5:8080", enabled = true)
            slug = "workstation-01"
        }
        var copiedUrl: String? = null
        var savedSlug: String? = null

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneCloudShareSheet(
                    state = state,
                    onDismiss = {},
                    onSignIn = {},
                    onSignOut = {},
                    onLink = {},
                    onUnlink = {},
                    onSaveSlug = { savedSlug = state.slugDraft },
                    onStartSharing = {},
                    onStopSharing = {},
                    onStartTempLink = {},
                    onStopTempLink = {},
                    onCopyUrl = { copiedUrl = it },
                    onOpenUrl = {},
                )
            }
        }

        // 168dp QR box and Copy public URL: the address the server reported, not a made-up one
        composeRule.onNodeWithTag("phone-cloud-qr-box", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("phone-cloud-copy-public", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals("https://abc.trycloudflare.com", copiedUrl)
        composeRule.onNodeWithText("ppm.bot/workstation-01", substring = true, useUnmergedTree = true).assertExists()

        // Edit permanent link slug with validation; Save hands the draft to the caller (which PUTs /api/cloud/alias)
        composeRule.onNodeWithTag("phone-cloud-slug-edit-btn", useUnmergedTree = true).performScrollTo().performClick()
        composeRule.onNodeWithTag("phone-cloud-slug-input", useUnmergedTree = true).performTextReplacement("INVALID SLUG!")
        assertFalse(state.isSlugDraftValid)
        composeRule.onNodeWithTag("phone-cloud-slug-input", useUnmergedTree = true).performTextReplacement("dev-box-2")
        assertTrue(state.isSlugDraftValid)
        composeRule.onNodeWithTag("phone-cloud-slug-save", useUnmergedTree = true).performClick()
        assertEquals("dev-box-2", savedSlug)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1280dp-h1000dp-xxhdpi")
    fun tabletTools_opensRemoteDesktopAndLogsAsTabs_andSupportsCloudflareSchedulesDesignsAndToolbar() {
        val openedTabs = mutableListOf<String>()
        val cfState = CloudflareFourStepState(
            initialDomain = "ppm.dev",
            initialSubdomainPrefix = "ide",
            initialPrereqChecked = true,
            initialSignedIn = true,
            initialZoneConfirmed = true,
        )
        val schedulesState = TabletSchedulesWorkspaceState(
            initialSchedules = listOf(
                ScheduleRowState(
                    id = 7,
                    name = "Deploy check",
                    kind = "script",
                    cronExpr = "*/15 * * * *",
                    prompt = "bun run check",
                    script = "bun run check",
                    enabled = true,
                ),
            ),
        )
        val designState = TabletDesignWorkspaceState()
        var savedHost: String? = null
        var stage by mutableStateOf("menu")

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                when (stage) {
                    "menu" -> TabletToolsMenuPopover(
                        onOpenToolTab = { entry ->
                            if (entry.opensAsTab) openedTabs += entry.tabType
                        },
                    )
                    "cloudflare" -> CloudflareNamedTunnelFourStepDialog(
                        state = cfState,
                        onSave = { savedHost = it },
                        onDismiss = {},
                    )
                    "schedules" -> TabletSchedulesWorkspace(
                        state = schedulesState,
                    )
                    "designs" -> TabletDesignsToolView(
                        state = designState,
                    )
                    "toolbar" -> RemoteDesktopTabletToolbar(
                        caps = RemoteDesktopCapabilities(),
                        currentDisplayId = null,
                        onSelectDisplay = {},
                        qualityChoice = "auto",
                        serverPreset = "high",
                        qualityHeld = 1.0,
                        onSetQuality = {},
                        onOpenCustomQuality = {},
                        codec = "h264",
                        onSetCodec = {},
                        hostModeId = null,
                        hostOriginalModeId = null,
                        resolutionError = null,
                        onSetHostMode = {},
                        viewStyle = "fit",
                        customScale = 1.0,
                        onSetViewStyle = {},
                        onSetCustomScale = {},
                        showCursor = true,
                        onSetShowCursor = {},
                        audioOn = false,
                        onSetAudioOn = {},
                        privacyOn = false,
                        privacyError = null,
                        onSetPrivacyOn = {},
                        clipboardEnabled = true,
                        onSetClipboardEnabled = {},
                        statsVisible = false,
                        onToggleStats = {},
                        recording = false,
                        recordingElapsedSec = 0,
                        onToggleRecording = {},
                        onScreenshot = {},
                        keyboardCaptured = true,
                    )
                }
            }
        }

        // Clicking Logs and Remote Desktop opens them as workspace tabs (Requirement 14.4)
        composeRule.onNodeWithTag("tablet-tool-item-logs").performClick()
        composeRule.onNodeWithTag("tablet-tool-item-remote-desktop").performClick()
        assertEquals(listOf("logs", "remote-desktop"), openedTabs)

        // Cloudflare 4-step dialog saves full hostname
        stage = "cloudflare"
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("cloudflare-4step-save").performClick()
        assertEquals("ide.ppm.dev", savedHost)

        // Tablet Schedules allows editing and saving script
        stage = "schedules"
        composeRule.waitForIdle()
        assertTrue(schedulesState.canEditScript)
        composeRule.onNodeWithTag("tablet-schedule-script-editor").performTextReplacement("bun test")
        composeRule.onNodeWithTag("tablet-schedule-save-btn").performClick()
        assertEquals("bun test", schedulesState.schedules.first().prompt)

        // Tablet Designs shows canvas and comment pins
        stage = "designs"
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tablet-design-canvas").assertIsDisplayed()
        composeRule.onNodeWithTag("tablet-design-comments-pane").assertIsDisplayed()

        // Tablet Remote Desktop toolbar shows input mode toggle and keyboard release pill
        stage = "toolbar"
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("remote-desktop-input-mode-toggle").assertIsDisplayed()
        composeRule.onNodeWithTag("remote-desktop-release-keyboard").assertIsDisplayed()
    }
}
