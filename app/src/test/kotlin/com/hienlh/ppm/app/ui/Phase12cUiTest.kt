package com.hienlh.ppm.app.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.settings.CloudDeviceCodeFlowState
import com.hienlh.ppm.state.settings.CloudStatusState
import com.hienlh.ppm.state.settings.ExtensionItemRow
import com.hienlh.ppm.state.settings.NamedTunnelState
import com.hienlh.ppm.state.settings.NotificationSettingsState
import com.hienlh.ppm.state.settings.NtfyStatusState
import com.hienlh.ppm.state.settings.ProxySettingsState
import com.hienlh.ppm.state.settings.PublicTunnelState
import com.hienlh.ppm.state.settings.PushStatusState
import com.hienlh.ppm.state.settings.ScheduleProjectOption
import com.hienlh.ppm.state.settings.ScheduleRowState
import com.hienlh.ppm.state.settings.TailscaleDeviceInfo
import com.hienlh.ppm.state.settings.TailscaleLoginState
import com.hienlh.ppm.state.settings.TailscaleState
import com.hienlh.ppm.state.settings.TelegramNotifyState
import kotlinx.serialization.json.JsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class Phase12cUiTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun settingsGroupIntegrations_resolvesAllCategoriesAndFiltersMinimalMode() {
        val full = SettingsGroup.Integrations.categories(minimalMode = false)
        assertEquals(7, full.size)
        val minimal = SettingsGroup.Integrations.categories(minimalMode = true)
        assertEquals(listOf(SettingsCategory.Notifications, SettingsCategory.RemoteAccess), minimal)
        assertEquals(SettingsCategory.Schedules, SettingsCategory.resolveOrDefault("schedules"))
        assertEquals(SettingsCategory.RemoteAccess, SettingsCategory.resolveOrDefault("remote-access"))
    }

    @Test
    fun notificationsSettingsContent_togglesEventsAndValidatesNtfyBeforeSaving() {
        var updatedSettings: NotificationSettingsState? = null
        var savedNtfy: Triple<String, String, String?>? = null

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                NotificationsSettingsContent(
                    settings = NotificationSettingsState(),
                    pushStatus = PushStatusState(),
                    telegram = TelegramNotifyState(),
                    ntfy = NtfyStatusState(),
                    onUpdateSettings = { updatedSettings = it },
                    onTestPushDevice = {},
                    onRemovePushDevice = { _, _ -> },
                    onSaveTelegramToken = {},
                    onTestTelegram = {},
                    onStartTelegramConnect = {},
                    onCancelTelegramConnect = {},
                    onRemoveTelegramChat = {},
                    onSaveNtfy = { s, t, tok -> savedNtfy = Triple(s, t, tok) },
                    onTestNtfy = {},
                    onRemoveNtfy = {},
                )
            }
        }

        composeRule.onNodeWithTag("notify-event-done").performClick()
        assertEquals(false, updatedSettings?.events?.get("done"))

        // Invalid ntfy topic -> shows error and does not call onSaveNtfy
        composeRule.onNodeWithTag("notify-ntfy-server").performTextReplacement("https://ntfy.sh/")
        composeRule.onNodeWithTag("notify-ntfy-topic").performTextReplacement("bad/topic")
        composeRule.onNodeWithTag("notify-ntfy-save").performClick()
        composeRule.onNodeWithTag("notify-ntfy-error").assertIsDisplayed()
        assertNull(savedNtfy)

        // Valid topic -> normalizes trailing slash and saves
        composeRule.onNodeWithTag("notify-ntfy-topic").performTextReplacement("ppm_alerts")
        composeRule.onNodeWithTag("notify-ntfy-save").performClick()
        assertEquals(Triple("https://ntfy.sh", "ppm_alerts", null), savedNtfy)
    }

    @Test
    fun remoteAccessSettingsContent_runsOperatorCommandInTerminalAndTogglesPublicTunnel() {
        var terminalCmd: String? = null
        var publicEnabled: Boolean? = null

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                RemoteAccessSettingsContent(
                    currentServerUrl = "https://box.tail123.ts.net",
                    tailscale = TailscaleState(
                        platform = "linux",
                        installed = true,
                        backendState = "Running",
                        canManage = false,
                        osUser = "thawngho",
                        device = TailscaleDeviceInfo(
                            name = "box",
                            dnsName = "box.tail123.ts.net",
                            ips = listOf("100.64.0.1"),
                            tags = emptyList(),
                        ),
                    ),
                    publicTunnel = PublicTunnelState(enabled = false),
                    namedTunnel = NamedTunnelState(),
                    onRunInTerminal = { terminalCmd = it },
                    onStartTailscaleLogin = {},
                    onCancelTailscaleLogin = {},
                    onConfigureTailscaleService = { _, _, _ -> },
                    onTogglePublicTunnel = { publicEnabled = it },
                    onStartNamedTunnelLogin = {},
                    onCancelNamedTunnelLogin = {},
                    onSetupNamedTunnel = {},
                    onDisableNamedTunnel = {},
                )
            }
        }

        composeRule.onNodeWithTag("tailscale-run-operator-cmd").performClick()
        assertEquals("sudo tailscale set --operator=thawngho", terminalCmd)

        composeRule.onNodeWithTag("public-tunnel-toggle").performClick()
        assertEquals(true, publicEnabled)
    }

    @Test
    fun proxyAndExtensionsSettingsContent_switchProviderAndInstallExtension() {
        var generatedKey = false
        var installedPkg: String? = null

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                androidx.compose.foundation.layout.Column {
                    ProxySettingsContent(
                        state = ProxySettingsState(
                            enabled = true,
                            authKey = "ppm-key-123",
                            localEndpoint = "http://localhost:3210/proxy",
                            localOpenAiEndpoint = "http://localhost:3210/proxy/openai/v1",
                        ),
                        onToggleEnabled = {},
                        onGenerateKey = { generatedKey = true },
                    )
                    ExtensionsSettingsContent(
                        extensions = listOf(
                            ExtensionItemRow(
                                id = "@ppm/ext-git-graph",
                                version = "1.0.0",
                                displayName = "Git Graph",
                                description = "Commit graph",
                                enabled = true,
                                activated = true,
                            ),
                        ),
                        onInstall = { installedPkg = it },
                        onDevLink = {},
                        onToggleEnabled = { _, _ -> },
                        onRemove = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("proxy-generate-key").performClick()
        assertTrue(generatedKey)

        composeRule.onNodeWithTag("ext-install-input").performTextReplacement("@ppm/ext-database")
        composeRule.onNodeWithTag("ext-install-btn").performClick()
        assertEquals("@ppm/ext-database", installedPkg)
    }

    @Test
    fun schedulesSettingsContent_triggersRunNowAndCreatesScheduleWithProjectPathAndCronExpr() {
        var ranScheduleId: Int? = null
        var createdPayload: JsonObject? = null
        var startedCloudDeviceLogin = false

        val projectOptions = listOf(
            ScheduleProjectOption(name = "ppm", path = "/home/thawngho/Projects/ppm"),
            ScheduleProjectOption(name = "web", path = "/home/thawngho/Projects/web"),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                androidx.compose.foundation.layout.Column {
                    CloudSettingsContent(
                        status = CloudStatusState(loggedIn = false),
                        deviceCodeFlow = CloudDeviceCodeFlowState(
                            deviceCode = "dev_123",
                            userCode = "WXYZ-9876",
                            verificationUri = "https://ppm.bot/activate",
                            status = "waiting",
                        ),
                        aliasSlug = null,
                        onStartDeviceLogin = { startedCloudDeviceLogin = true },
                        onLogout = {},
                        onLinkDevice = {},
                        onUnlinkDevice = {},
                        onSaveAlias = {},
                    )
                    SchedulesSettingsContent(
                        schedules = listOf(
                            ScheduleRowState(
                                id = 7,
                                name = "Hourly Sync",
                                kind = "script",
                                mode = "tick",
                                cronExpr = "0 * * * *",
                                projectPath = "/home/thawngho/Projects/ppm",
                                enabled = true,
                            ),
                        ),
                        projects = projectOptions,
                        onToggleEnabledOnly = {},
                        onRunNow = { ranScheduleId = it },
                        onToggleScheduleEnabled = { _, _ -> },
                        onDeleteSchedule = {},
                        onCreateSchedule = {},
                        onExportTemplate = {},
                        onImportTemplate = { _, _ -> },
                        onOpenLogs = {},
                        onCloseLogs = {},
                    )
                    ScheduleCreateFormContent(
                        projects = projectOptions,
                        onDismiss = {},
                        onSubmit = { obj ->
                            createdPayload = obj
                        },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("cloud-device-user-code").assertIsDisplayed()

        composeRule.onNodeWithTag("schedule-run-7").performClick()
        assertEquals(7, ranScheduleId)

        composeRule.onNodeWithTag("schedule-project-web").performClick()
        composeRule.onNodeWithTag("schedule-form-name").performTextReplacement("Daily Summary")
        composeRule.onNodeWithTag("schedule-form-prompt").performTextReplacement("Summarize commits")
        composeRule.onNodeWithTag("schedule-form-submit").performClick()
        assertEquals("Daily Summary", createdPayload?.get("name")?.toString()?.trim('"'))
        assertEquals("/home/thawngho/Projects/web", createdPayload?.get("project_path")?.toString()?.trim('"'))
        assertEquals("0 * * * *", createdPayload?.get("cron_expr")?.toString()?.trim('"'))
    }

    @Test
    fun scheduleImportAndJiraAndRemoteAccess_enforceConfirmationsTrustAndStatusGates() {
        var importedTrust: Boolean? = null
        var importedProject: String? = null
        var savedJiraProjectId: Int? = null
        var deletedJiraProjectId: Int? = null
        var createdWatcherConfigId: Int? = null
        var publicToggledTo: Boolean? = null

        val validTemplateJson = """
            {
              "ppmScheduleTemplate": 1,
              "name": "Nightly Check",
              "description": "Audit repo",
              "kind": "prompt",
              "mode": "tick",
              "cron": "0 3 * * *",
              "prompt": "Check status",
              "maxTurns": 10,
              "permissionMode": "default"
            }
        """.trimIndent()

        var activeSection by androidx.compose.runtime.mutableStateOf(0)

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                when (activeSection) {
                    0 -> ScheduleImportFormContent(
                        projects = listOf(ScheduleProjectOption(name = "ppm", path = "/home/thawngho/Projects/ppm")),
                        onDismiss = {},
                        onImport = { _, proj, trust ->
                            importedProject = proj
                            importedTrust = trust
                        },
                    )
                    1 -> androidx.compose.foundation.layout.Column(
                        modifier = androidx.compose.ui.Modifier.verticalScroll(
                            androidx.compose.foundation.rememberScrollState(),
                        ),
                    ) {
                        JiraSettingsContent(
                            jiraEnabled = true,
                            projects = listOf(
                                com.hienlh.ppm.state.settings.JiraProjectOption(id = 1, name = "Alpha", path = "/projects/alpha", hasConfig = true),
                                com.hienlh.ppm.state.settings.JiraProjectOption(id = 2, name = "Beta", path = "/projects/beta", hasConfig = true),
                            ),
                            configs = listOf(
                                com.hienlh.ppm.state.settings.JiraConfigRow(id = 10, projectId = 1, projectName = "Alpha", baseUrl = "https://alpha.atlassian.net", email = "a@ex.com", hasToken = true),
                                com.hienlh.ppm.state.settings.JiraConfigRow(id = 20, projectId = 2, projectName = "Beta", baseUrl = "https://beta.atlassian.net", email = "b@ex.com", hasToken = true),
                            ),
                            watchers = emptyList(),
                            results = listOf(
                                com.hienlh.ppm.state.settings.JiraResultRow(id = 101, issueKey = "BETA-1", status = "done", sessionId = "sess-done"),
                                com.hienlh.ppm.state.settings.JiraResultRow(id = 102, issueKey = "BETA-2", status = "failed", sessionId = "sess-fail"),
                            ),
                            onToggleJiraEnabled = {},
                            onSaveConfig = { pid, _, _, _ -> savedJiraProjectId = pid },
                            onTestConfig = {},
                            onDeleteConfig = { pid -> deletedJiraProjectId = pid },
                            onCreateWatcher = { cid, _, _, _, _, _ -> createdWatcherConfigId = cid },
                            onToggleWatcher = { _, _ -> },
                            onPullWatcher = {},
                            onDeleteWatcher = {},
                            onTestJql = { _, _ -> },
                            onDebugResult = {},
                            onResumeResult = {},
                            onCancelResult = {},
                            onMarkResultRead = {},
                            onDeleteResult = {},
                        )
                    }
                    else -> RemoteAccessSettingsContent(
                        currentServerUrl = "https://random-quick.trycloudflare.com",
                        tailscale = TailscaleState(installed = true, backendState = "Running"),
                        publicTunnel = PublicTunnelState(enabled = true, active = true, url = "https://random-quick.trycloudflare.com"),
                        namedTunnel = NamedTunnelState(),
                        onRunInTerminal = {},
                        onStartTailscaleLogin = {},
                        onCancelTailscaleLogin = {},
                        onConfigureTailscaleService = { _, _, _ -> },
                        onTogglePublicTunnel = { publicToggledTo = it },
                        onStartNamedTunnelLogin = {},
                        onCancelNamedTunnelLogin = {},
                        onSetupNamedTunnel = {},
                        onDisableNamedTunnel = {},
                    )
                }
            }
        }

        // 1. Schedule import requires explicit trust checkbox before Import fires
        composeRule.onNodeWithTag("schedule-import-json").performTextReplacement(validTemplateJson)
        composeRule.onNodeWithTag("schedule-import-confirm").performClick()
        assertNull(importedTrust)
        composeRule.onNodeWithTag("schedule-import-trust").performClick()
        composeRule.onNodeWithTag("schedule-import-confirm").performClick()
        assertEquals(true, importedTrust)
        assertEquals("ppm", importedProject)

        // 2. Jira project & watcher config selector + delete confirmation + Resume only on failed result
        activeSection = 1
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("jira-project-2").performClick()
        composeRule.onNodeWithTag("jira-save-config").performClick()
        assertEquals(2, savedJiraProjectId)

        composeRule.onNodeWithTag("jira-watcher-config-20").performClick()
        composeRule.onNodeWithTag("jira-watcher-name").performTextReplacement("Bug Watcher")
        composeRule.onNodeWithTag("jira-watcher-jql").performTextReplacement("project = BETA")
        composeRule.onNodeWithTag("jira-watcher-add").performClick()
        assertEquals(20, createdWatcherConfigId)

        // Result 101 ("done" with sessionId) must NOT show Resume; Result 102 ("failed" with sessionId) DOES show Resume
        composeRule.onNodeWithTag("jira-result-resume-101").assertDoesNotExist()
        composeRule.onNodeWithTag("jira-result-resume-102").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("jira-delete-config-1").performScrollTo().performClick()
        assertNull(deletedJiraProjectId)
        composeRule.onNodeWithTag("jira-delete-config-confirm").performClick()
        assertEquals(1, deletedJiraProjectId)

        // 3. Turning off Public Tunnel while connected via its URL requires confirmation
        activeSection = 2
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("public-tunnel-toggle").performClick()
        assertNull(publicToggledTo)
        composeRule.onNodeWithTag("public-tunnel-turn-off-confirm").performClick()
        assertEquals(false, publicToggledTo)
    }

    @Test
    fun remoteAccessSettingsSection_surfacesServer409OnNamedTunnelSetupAndTailscaleServiceConflict() {
        var tailscaleServiceMode = "conflict"
        val toasts = mutableListOf<ErrorToastMessage>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    request.method == "GET" && path == "/api/tailscale/state" -> MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body(
                            """
                            {
                              "ok": true,
                              "data": {
                                "installed": true,
                                "backendState": "Running",
                                "canManage": true,
                                "magicDns": true,
                                "httpsCertificates": true,
                                "dnsSuffix": "tail123.ts.net",
                                "device": { "name": "box", "dnsName": "box.tail123.ts.net", "ips": ["100.64.0.1"], "tags": ["tag:ppm"] },
                                "service": { "name": "ppm", "defined": false, "approved": false, "advertised": false, "target": "http://127.0.0.1:9000", "pointsAtPpm": false },
                                "enabled": false,
                                "ppmPort": 3210,
                                "login": { "state": "idle", "url": null, "message": null },
                                "authEnabled": true
                              }
                            }
                            """.trimIndent(),
                        ).build()
                    request.method == "GET" && path == "/api/tunnel" -> MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"active":false,"url":null,"localUrl":"http://192.168.1.10:3210","enabled":false}}""")
                        .build()
                    request.method == "GET" && path == "/api/tunnel/named/status" -> MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body(
                            """
                            {
                              "ok": true,
                              "data": {
                                "mode": "quick",
                                "liveMode": "quick",
                                "hostname": "ppm.example.com",
                                "tunnelName": null,
                                "tokenMasked": null,
                                "certState": "valid",
                                "dismissed": false,
                                "login": { "state": "idle", "url": null, "message": null },
                                "tunnelWarning": null,
                                "authEnabled": true
                              }
                            }
                            """.trimIndent(),
                        ).build()
                    request.method == "POST" && path == "/api/tunnel/named/setup" -> MockResponse.Builder()
                        .code(409)
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":false,"error":"Hostname ppm.example.com is already in use"}""")
                        .build()
                    request.method == "POST" && path == "/api/tailscale/service" -> when (tailscaleServiceMode) {
                        "conflict" -> MockResponse.Builder()
                            .code(409)
                            .addHeader("Content-Type", "application/json")
                            .body("""{"ok":false,"error":"Service ppm already points to another port","code":"conflict"}""")
                            .build()
                        "error500" -> MockResponse.Builder()
                            .code(500)
                            .addHeader("Content-Type", "application/json")
                            .body("""{"ok":false,"error":"tailscaled socket closed"}""")
                            .build()
                        else -> MockResponse.Builder()
                            .code(500)
                            .addHeader("Content-Type", "application/json")
                            .body("""{"ok":false,"error":"invalid service config"}""")
                            .build()
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        try {
            val client = ApiClient(server.url("/"), "test-token")
            composeRule.setContent {
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                    androidx.compose.foundation.layout.Column(
                        modifier = androidx.compose.ui.Modifier.verticalScroll(
                            androidx.compose.foundation.rememberScrollState(),
                        ),
                    ) {
                        RemoteAccessSettingsSection(
                            api = client,
                            currentServerUrl = "http://192.168.1.10:3210",
                            onToast = { toasts += it },
                        )
                    }
                }
            }

            // 1) Named tunnel setup 409 -> inline error under hostname field, no toast
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("named-tunnel-save").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("named-tunnel-save").performScrollTo().performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("named-tunnel-error").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("named-tunnel-error")
                .assertIsDisplayed()
                .assertTextContains("Hostname ppm.example.com is already in use")
            assertTrue(toasts.isEmpty())

            // 2) Tailscale 409 with code:"conflict" -> shows conflict dialog ("Replace svc:ppm?") and NO toast / NO inline error
            composeRule.onNodeWithTag("tailscale-service-enable").performScrollTo().performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("tailscale-service-replace").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Replace svc:ppm?").assertIsDisplayed()
            composeRule.onNodeWithTag("tailscale-service-replace").assertIsDisplayed()
            composeRule.onNodeWithTag("tailscale-error").assertDoesNotExist()
            assertTrue(toasts.isEmpty())

            // Dismiss conflict dialog
            composeRule.onNodeWithText("Cancel").performClick()
            composeRule.waitForIdle()

            // 3) Tailscale 500 on turning on -> shows toast("Could not turn the address on", description = "tailscaled socket closed")
            tailscaleServiceMode = "error500"
            composeRule.onNodeWithTag("tailscale-service-enable").performScrollTo().performClick()
            composeRule.waitUntil(5_000) { toasts.isNotEmpty() }
            assertEquals("Could not turn the address on", toasts.last().title)
            assertEquals("tailscaled socket closed", toasts.last().description)
            composeRule.onNodeWithTag("tailscale-service-replace").assertDoesNotExist()

            // 4) Failed rename -> shows toast("Could not rename the address", ...) and resets the name field to "ppm"
            toasts.clear()
            tailscaleServiceMode = "renameFail"
            composeRule.onNodeWithTag("tailscale-service-name").performScrollTo().performTextReplacement("ppm-staging")
            composeRule.onNodeWithTag("tailscale-service-rename").performScrollTo().performClick()
            composeRule.waitUntil(5_000) { toasts.isNotEmpty() }
            assertEquals("Could not rename the address", toasts.last().title)
            assertEquals("invalid service config", toasts.last().description)
            composeRule.onNodeWithTag("tailscale-service-name").assertTextContains("ppm")
        } finally {
            server.close()
        }
    }

    @Test
    fun remoteAccessSettingsContent_showsLoginUrlsAndOpensBrowserOnlyOnButtonClick() {
        val openedUrls = mutableListOf<String>()
        val terminalCmds = mutableListOf<String>()
        var cancelledTailscale = false
        var retriedTailscale = false
        var closedTailscale = false
        var cancelledNamed = false

        var tailscaleLoginState by mutableStateOf(
            TailscaleLoginState(
                state = "starting",
                url = null,
            ),
        )
        var cfUrl by mutableStateOf<String?>(null)
        var cfSlow by mutableStateOf(false)

        // Verify QR code encoder round-trips through zxing QRCodeReader across short and long URLs
        val qrSamples = listOf(
            "https://login.tailscale.com/a/abc123",
            "x",
            "https://login.tailscale.com/a/" + "k".repeat(110),
            "https://dash.cloudflare.com/argotunnel?aud=" + "m".repeat(105),
            "https://dash.cloudflare.com/argotunnel?aud=" + "q".repeat(157),
        )
        for (sample in qrSamples) {
            val qrMatrix = encodeQrBitMatrix(sample)
            assertEquals(sample, decodeQrMatrixWithZxing(qrMatrix))
        }

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                androidx.compose.foundation.layout.Column(
                    modifier = androidx.compose.ui.Modifier.verticalScroll(
                        androidx.compose.foundation.rememberScrollState(),
                    ),
                ) {
                    TailscaleSignInDialog(
                        open = true,
                        login = tailscaleLoginState,
                        osUser = "thawngho",
                        onRetry = { retriedTailscale = true },
                        onCancel = { cancelledTailscale = true },
                        onClose = { closedTailscale = true },
                        onOpenUrl = { openedUrls += it },
                        onRunInTerminal = { terminalCmds += it },
                    )
                    NamedTunnelLoginStep(
                        url = cfUrl,
                        slow = cfSlow,
                        onCancel = { cancelledNamed = true },
                        onOpenUrl = { openedUrls += it },
                    )
                }
            }
        }

        // 1) Initial state: Tailscale "starting" spinner & Cloudflare "url == null" waiting spinner; no URL opened automatically
        composeRule.waitForIdle()
        assertTrue(openedUrls.isEmpty())
        composeRule.onNodeWithText("Asking Tailscale for a sign-in link…").assertIsDisplayed()
        composeRule.onNodeWithText("Waiting for sign-in…").assertIsDisplayed()
        composeRule.onNodeWithText("You can finish this step on your phone if that is easier.").assertIsDisplayed()

        // 2) Tailscale "waiting" with URL -> shows QR code, "Or scan it with your phone", "Cancel sign-in", "Open sign-in page"
        tailscaleLoginState = TailscaleLoginState(
            state = "waiting",
            url = "https://login.tailscale.com/a/abc123",
        )
        composeRule.waitForIdle()
        assertTrue(openedUrls.isEmpty())
        composeRule.onNodeWithText("Sign in with the account that owns your tailnet. This machine joins as soon as you finish.").assertIsDisplayed()
        composeRule.onNodeWithTag("tailscale-qr-code").assertIsDisplayed()
        composeRule.onNodeWithText("Or scan it with your phone").assertIsDisplayed()
        composeRule.onNodeWithTag("tailscale-open-login-url").performClick()
        assertEquals(listOf("https://login.tailscale.com/a/abc123"), openedUrls)

        // 3) Tailscale "needs-approval" -> shows approval text + "Open machines"
        tailscaleLoginState = TailscaleLoginState(state = "needs-approval")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Signed in. An admin has to approve this machine before it joins the tailnet.").assertIsDisplayed()
        composeRule.onNodeWithTag("tailscale-open-machines").performClick()
        assertEquals(
            listOf(
                "https://login.tailscale.com/a/abc123",
                "https://login.tailscale.com/admin/machines",
            ),
            openedUrls,
        )

        // 4) Tailscale "needs-operator" -> shows operator command + "Run in terminal" (which runs command and closes dialog)
        tailscaleLoginState = TailscaleLoginState(state = "needs-operator")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tailscale-signin-operator-code")
            .assertIsDisplayed()
            .assertTextContains("sudo tailscale set --operator=thawngho")
        composeRule.onNodeWithTag("tailscale-signin-run-terminal").performClick()
        assertEquals(listOf("sudo tailscale set --operator=thawngho"), terminalCmds)
        assertTrue(closedTailscale)

        // 5) Tailscale "error" -> shows error message + "Try again"
        tailscaleLoginState = TailscaleLoginState(state = "error", message = "tailscale up timed out")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("tailscale up timed out").assertIsDisplayed()
        composeRule.onNodeWithTag("tailscale-signin-retry").performClick()
        assertTrue(retriedTailscale)

        // 6) Tailscale "success" -> shows "Signed in."
        tailscaleLoginState = TailscaleLoginState(state = "success")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Signed in.").assertIsDisplayed()

        // 7) Cloudflare NamedTunnelLoginStep with URL and slow = true -> Copy icon turns to Copied check, Open link opens URL, slow banner dismisses on "Keep waiting"
        cfUrl = "https://dash.cloudflare.com/argotunnel?aud=xyz"
        cfSlow = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("named-tunnel-login-url")
            .assertIsDisplayed()
            .assertTextContains("https://dash.cloudflare.com/argotunnel?aud=xyz")
        composeRule.onNodeWithTag("named-tunnel-copy-login-url")
            .assertContentDescriptionEquals("Copy")
            .performClick()
        composeRule.onNodeWithTag("named-tunnel-copy-login-url")
            .assertContentDescriptionEquals("Copied")
        composeRule.onNodeWithTag("named-tunnel-open-login-url")
            .assertContentDescriptionEquals("Open link")
            .performClick()
        assertTrue(openedUrls.contains("https://dash.cloudflare.com/argotunnel?aud=xyz"))

        composeRule.onNodeWithTag("named-tunnel-slow-banner").assertIsDisplayed()
        composeRule.onNodeWithText("Still signing in?").assertIsDisplayed()
        composeRule.onNodeWithTag("named-tunnel-keep-waiting").performClick()
        composeRule.onNodeWithTag("named-tunnel-slow-banner").assertDoesNotExist()
        composeRule.onNodeWithTag("named-tunnel-cancel-login").performClick()
        assertTrue(cancelledNamed)
    }

    @Test
    fun remoteAccessSettingsSection_pollsTailscaleOnlyWhileLifecycleResumedAndReloadsOnResume() {
        var tailscaleReads = 0
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    request.method == "GET" && path == "/api/tailscale/state" -> {
                        tailscaleReads++
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "application/json")
                            .body(
                                """
                                {
                                  "ok": true,
                                  "data": {
                                    "installed": true,
                                    "backendState": "NeedsLogin",
                                    "canManage": true,
                                    "login": { "state": "waiting", "url": "https://login.tailscale.com/a/poll", "message": null }
                                  }
                                }
                                """.trimIndent(),
                            ).build()
                    }
                    request.method == "GET" && path == "/api/tunnel" -> MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"active":false,"url":null,"localUrl":null,"enabled":false}}""")
                        .build()
                    request.method == "GET" && path == "/api/tunnel/named/status" -> MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"mode":"quick","liveMode":"quick","certState":"missing","login":{"state":"idle"}}}""")
                        .build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        try {
            val client = ApiClient(server.url("/"), "test-token")
            val testOwner = TestLifecycleOwner12c(Lifecycle.State.RESUMED)

            composeRule.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides testOwner) {
                    PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                        RemoteAccessSettingsSection(
                            api = client,
                            tailscalePollMs = 80L,
                        )
                    }
                }
            }

            // 1) Initial load + active polling while RESUMED
            composeRule.waitUntil(5_000) { tailscaleReads >= 2 }

            // 2) Move lifecycle to STARTED (background): polling stops
            composeRule.runOnUiThread {
                testOwner.registry.currentState = Lifecycle.State.STARTED
            }
            composeRule.waitForIdle()
            val readsAtBackground = tailscaleReads
            Thread.sleep(250L)
            composeRule.waitForIdle()
            assertEquals(readsAtBackground, tailscaleReads)

            // 3) Return lifecycle to RESUMED (foreground): reloads immediately on resume
            composeRule.runOnUiThread {
                testOwner.registry.currentState = Lifecycle.State.RESUMED
            }
            composeRule.waitUntil(5_000) { tailscaleReads > readsAtBackground }
        } finally {
            server.close()
        }
    }
}

private fun decodeQrMatrixWithZxing(matrix: BitMatrix): String {
    val quietZone = 4
    val scale = 4
    val imgW = (matrix.width + quietZone * 2) * scale
    val imgH = (matrix.height + quietZone * 2) * scale
    val pixels = IntArray(imgW * imgH) { 0xFFFFFFFF.toInt() }
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            if (matrix.get(x, y)) {
                val startX = (x + quietZone) * scale
                val startY = (y + quietZone) * scale
                for (dy in 0 until scale) {
                    for (dx in 0 until scale) {
                        pixels[(startY + dy) * imgW + (startX + dx)] = 0xFF000000.toInt()
                    }
                }
            }
        }
    }
    val source = RGBLuminanceSource(imgW, imgH, pixels)
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    return QRCodeReader().decode(bitmap).text
}

private class TestLifecycleOwner12c(initialState: Lifecycle.State) : LifecycleOwner {
    val registry = LifecycleRegistry.createUnsafe(this).apply {
        currentState = initialState
    }
    override val lifecycle: Lifecycle get() = registry
}
