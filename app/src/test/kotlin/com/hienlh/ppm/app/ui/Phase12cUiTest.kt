package com.hienlh.ppm.app.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
                                "service": { "name": "ppm", "defined": false, "approved": false, "advertised": false, "pointsAtPpm": false },
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
                    request.method == "POST" && path == "/api/tailscale/service" -> MockResponse.Builder()
                        .code(409)
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":false,"error":"Service ppm already points to another port","code":"conflict"}""")
                        .build()
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
                        )
                    }
                }
            }

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

            composeRule.onNodeWithTag("tailscale-service-enable").performScrollTo().performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("tailscale-error").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("tailscale-error")
                .assertIsDisplayed()
                .assertTextContains("Service ppm already points to another port")
            composeRule.onNodeWithTag("tailscale-service-replace").performScrollTo().assertIsDisplayed()
        } finally {
            server.close()
        }
    }
}
