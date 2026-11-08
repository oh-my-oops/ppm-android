package com.hienlh.ppm.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.hienlh.ppm.app.withComposeActivity
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
}
