package com.hienlh.ppm.app.ui.phone

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.phone.settings.PhoneSettingsScreen
import com.hienlh.ppm.app.ui.phone.settings.phoneAccountsSummary
import com.hienlh.ppm.app.ui.phone.settings.phoneAiProviderSummary
import com.hienlh.ppm.app.ui.phone.settings.phoneNotificationsSummary
import com.hienlh.ppm.app.ui.phone.tools.scheduleRunSummary
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.AiSettings
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.ModelNames
import com.hienlh.ppm.state.logs.LogEntry
import com.hienlh.ppm.state.logs.LogRow
import com.hienlh.ppm.state.settings.NtfyStatusState
import com.hienlh.ppm.state.settings.PushDeviceRow
import com.hienlh.ppm.state.settings.PushStatusState
import com.hienlh.ppm.state.settings.ScheduleRunRow
import com.hienlh.ppm.state.settings.TelegramNotifyState
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone's Tools, Terminal, Settings and Cloud & Share screens read from and write to a real (mock) server: every value
 * they show is one the server answered, every action is a request the server receives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
class PhoneToolsWiringTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<String>()

    /** Path (no query) → JSON `data`, or null for a 500. Anything unlisted is a 404. */
    private val routes = mutableMapOf<String, (RecordedRequest) -> String?>()

    private lateinit var model: AppModel

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                requests += "${request.method} $target"
                val path = target.substringBefore('?')
                val route = routes[path]
                    ?: return MockResponse.Builder().code(404).addHeader("Content-Type", "application/json")
                        .body("""{"ok":false,"error":"Not found"}""").build()
                val data = route(request)
                    ?: return MockResponse.Builder().code(500).addHeader("Content-Type", "application/json")
                        .body("""{"ok":false,"error":"Host probe failed"}""").build()
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                    .body("""{"ok":true,"data":$data}""").build()
            }
        }
        server.start()
        model = AppModel(ApplicationProvider.getApplicationContext<Application>()).apply {
            selectedProject = "ppm"
        }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun connect() {
        model.api = ApiClient(server.url("/"), "token")
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) { content() }
        }
    }

    private fun SemanticsNodeInteractionsProvider.waitForTag(tag: String) {
        composeRule.waitUntil(10_000) { onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForRequest(prefix: String) {
        composeRule.waitUntil(10_000) { requests.any { it.startsWith(prefix) } }
    }

    @Test
    fun `Cloud and Share shows the server's account, device and link, and Sign out unlinks then logs out`() {
        routes["/api/cloud/status"] = {
            """{"logged_in":true,"email":"dev@host.io","cloud_url":"https://ppm.bot","linked":true,"device_name":"Workbench","device_id":"d1"}"""
        }
        routes["/api/tunnel"] = { """{"active":true,"url":"https://abc.trycloudflare.com","localUrl":"http://192.168.1.5:8080","enabled":true}""" }
        routes["/api/cloud/alias"] = { """{"slug":"workbench"}""" }
        routes["/api/tunnel/named/status"] = { """{"mode":"quick"}""" }
        routes["/api/cloud/unlink"] = { "{}" }
        routes["/api/cloud/logout"] = { "{}" }
        connect()

        show { PhoneCloudShareAreaHost(model = model, onDismiss = {}) }

        composeRule.waitForTag("phone-cloud-account-row")
        waitForText("dev@host.io")
        waitForText("Workbench")
        waitForText("https://abc.trycloudflare.com")
        waitForText("ppm.bot/workbench", substring = true)
        // Nothing the server did not say: no sample slug or sample host.
        assertEquals(0, composeRule.onAllNodesWithText("ppm.cloud", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)

        composeRule.onNodeWithTag("phone-cloud-signout-btn", useUnmergedTree = true).performScrollTo().performClick()
        composeRule.waitForTag("phone-cloud-signout-confirm")
        composeRule.onNode(
            hasText("Sign out") and hasAnyAncestor(hasTestTag("phone-cloud-signout-confirm")),
            useUnmergedTree = true,
        ).performClick()

        waitForRequest("POST /api/cloud/logout")
        val unlink = requests.indexOfFirst { it.startsWith("POST /api/cloud/unlink") }
        val logout = requests.indexOfFirst { it.startsWith("POST /api/cloud/logout") }
        assertTrue("a linked machine is unlinked before signing out: $requests", unlink in 0 until logout)
        composeRule.waitForTag("phone-cloud-signin-btn")
    }

    @Test
    fun `Port Forwarding says a failed probe failed, invents no host, and lists the server's forwards`() {
        routes["/api/tunnels/transports"] = { null }
        routes["/api/tunnel/named/status"] = { null }
        routes["/api/tunnels"] = {
            """[{"pid":5,"port":3000,"source":"ppm","via":"cloudflare"},
                {"pid":6,"port":4000,"url":"https://demo.trycloudflare.com","source":"ppm","via":"cloudflare"}]"""
        }
        connect()

        show { PhonePortForwardingAreaHost(model = model, nav = PhoneNav()) }

        waitForText("Could not check Tailscale on the host")
        waitForText("Could not check Cloudflare")
        composeRule.waitForTag("phone-tunnel-no-url-3000")
        composeRule.waitForTag("phone-tunnel-card-4000")
        for (invented in listOf("ts.net", "tailnet-", "example.com")) {
            assertEquals(invented, 0, composeRule.onAllNodesWithText(invented, substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
        }
        // A forward with no address offers no link actions.
        assertEquals(0, composeRule.onAllNodesWithTag("phone-tunnel-open-tab-3000", useUnmergedTree = true).fetchSemanticsNodes().size)

        composeRule.onNodeWithTag("phone-tunnel-copy-4000", useUnmergedTree = true).performScrollTo().performClick()
        composeRule.waitForIdle()
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("https://demo.trycloudflare.com", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue(model.toasts.any { it.message.title == "Link copied" })
    }

    @Test
    fun `a web-preview tab in the Port Forwarding area shows the forwarded page, not the forwards list`() {
        connect()
        val preview = WorkspaceTab(
            id = "web-preview:1",
            type = "web-preview",
            title = "localhost:4000",
            projectId = "ppm",
            metadata = buildJsonObject {
                put("url", "https://demo.trycloudflare.com")
                put("currentUrl", "https://demo.trycloudflare.com")
                put("via", "cloudflare")
                put("port", 4000)
            },
        )

        show { PhoneWorkspaceAreaBody(model = model, area = PhoneArea.PORT_FORWARDING, tab = preview, nav = PhoneNav()) }

        waitForText("demo.trycloudflare.com", substring = true)
        assertEquals(0, composeRule.onAllNodesWithTag("phone-tunnels-screen").fetchSemanticsNodes().size)
    }

    @Test
    fun `Monitor's Services tab lists the units the server reports`() {
        routes["/api/system/services"] = {
            """{"supported":true,"manager":"systemd","services":[
                {"unit":"nginx.service","scope":"system","description":"Web server","activeState":"active","subState":"running","running":true,"enabled":true},
                {"unit":"backup.service","scope":"user","description":"Nightly backup","activeState":"failed","subState":"failed","failed":true}
            ]}"""
        }
        connect()
        val tab = WorkspaceTab(
            id = "system-monitor:1",
            type = "system-monitor",
            title = "System Monitor",
            projectId = "ppm",
            metadata = buildJsonObject { put("sysmonTab", "services") },
        )

        show { PhoneMonitorAreaHost(model = model, tab = tab) }

        composeRule.waitForTag("phone-sysmon-service-nginx.service")
        waitForText("Web server", substring = true)
        assertTrue(requests.any { it.startsWith("GET /api/system/services") })
    }

    @Test
    fun `Logs reads the tab's chat filter from the server and loads older pages with before`() {
        val now = System.currentTimeMillis()
        routes["/api/logs"] = { req ->
            if ("before=e3" in req.target) {
                """{"entries":[
                    {"id":"e1","ts":${now - 4_000},"lv":"info","src":"server","tag":"chat","msg":"oldest line","sid":"s1"},
                    {"id":"e2","ts":${now - 3_000},"lv":"info","src":"server","tag":"chat","msg":"older line","sid":"s1"}
                ],"hasMore":false}"""
            } else {
                """{"entries":[
                    {"id":"e3","ts":${now - 2_000},"lv":"info","src":"server","tag":"chat","msg":"recent line","sid":"s1"},
                    {"id":"e4","ts":${now - 1_000},"lv":"warn","src":"server","tag":"chat","msg":"newest line","sid":"s1"}
                ],"hasMore":true,"chats":[{"sid":"s1","title":"Fix the login bug","count":4,"last":$now}],
                "titles":{"s1":"Fix the login bug"}}"""
            }
        }
        connect()
        val tab = WorkspaceTab(
            id = "logs:1",
            type = "logs",
            title = "Logs",
            projectId = "ppm",
            metadata = buildJsonObject { put("logsChat", "s1") },
        )

        show { PhoneLogsAreaHost(model = model, tab = tab) }

        composeRule.waitForTag("phone-log-time-e4")
        assertTrue("the first page is asked for the tab's chat: $requests", requests.first { it.contains("/api/logs") }.contains("chat=s1"))
        composeRule.waitForTag("phone-logs-chat-filter")
        waitForText("Fix the login bug", substring = true)

        composeRule.onNodeWithTag("phone-logs-load-older", useUnmergedTree = true).performScrollTo().performClick()
        waitForRequest("GET /api/logs?")
        composeRule.waitUntil(10_000) { requests.any { it.contains("before=e3") } }
        composeRule.waitForTag("phone-log-time-e1")
        assertTrue(requests.first { it.contains("before=e3") }.contains("chat=s1"))
    }

    @Test
    fun `Settings home summarises the server's settings and Schedules shows a schedule's run history`() {
        routes["/api/settings/ai"] = { """{"default_provider":"claude","providers":{"claude":{"model":"claude-opus-4-7","effort":"high"}}}""" }
        routes["/api/notifications/push"] = {
            """{"publicKey":"k","devices":[{"id":"d1","label":"Pixel","endpoint":"e1"},{"id":"d2","label":"Mac","endpoint":"e2"}]}"""
        }
        routes["/api/notifications/telegram"] = { """{"configured":false}""" }
        routes["/api/notifications/ntfy"] = { """{"configured":true,"server":"https://ntfy.sh","topic":"ppm"}""" }
        routes["/api/schedules"] = {
            """{"schedules":[{"id":3,"name":"Nightly report","kind":"prompt","cron_expr":"0 2 * * *","prompt":"Summarize the day","enabled":true}]}"""
        }
        routes["/api/schedules/3/runs"] = {
            """{"runs":[{"id":42,"schedule_id":3,"status":"success","started_at":"2026-11-10T02:00:00Z","ended_at":"2026-11-10T02:00:12Z","cost_usd":0.0123,"output_truncated":"All good"}]}"""
        }
        connect()

        show { PhoneSettingsScreen(model = model) }

        waitForText("Claude · ${ModelNames.displayName("claude-opus-4-7")} · High")
        waitForText("Push on 2 devices · ntfy")

        composeRule.onNodeWithTag("phone-settings-row-schedules", useUnmergedTree = true).performScrollTo().performClick()
        composeRule.waitForTag("phone-settings-schedules-page")
        composeRule.waitForTag("phone-schedule-row-3")
        composeRule.onNodeWithTag("phone-schedule-row-3", useUnmergedTree = true).performClick()
        composeRule.waitForTag("phone-schedule-run-3-42")
        assertTrue(requests.any { it.startsWith("GET /api/schedules/3/runs") })
    }

    @Test
    fun `Terminal with no terminal tab offers to open one only when a server is connected`() {
        connect()
        show { PhoneTerminalAreaHost(model = model, tab = null, nav = PhoneNav()) }

        composeRule.onNodeWithTag("phone-terminal-empty").assertExists()
        composeRule.onNodeWithTag("phone-terminal-open", useUnmergedTree = true).assertExists()
        assertEquals(0, composeRule.onAllNodesWithTag("terminal-key-row").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("terminal:1", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `summaries, run rows and the log download are built from real values`() {
        assertNull(phoneAccountsSummary(emptyList()))
        assertEquals("Claude", phoneAccountsSummary(listOf("Claude")))
        assertEquals("Claude, Codex & Antigravity", phoneAccountsSummary(listOf("Claude", "Codex", "Antigravity")))

        val ai = AiSettings.from(WireJson.parse("""{"default_provider":"codex","providers":{"codex":{"effort":"max"}}}"""))!!
        assertEquals("Codex · Max", phoneAiProviderSummary(ai))

        assertEquals("No channels set up", phoneNotificationsSummary(PushStatusState(), TelegramNotifyState(), NtfyStatusState()))
        assertEquals(
            "Push on 1 device · ntfy",
            phoneNotificationsSummary(PushStatusState(devices = listOf(PushDeviceRow("d", "Pixel", "e"))), null, NtfyStatusState(configured = true)),
        )

        val run = ScheduleRunRow(
            id = 42,
            scheduleId = 3,
            status = "success",
            costUsd = 0.0123,
            startedAt = "2026-11-10T02:00:00Z",
            endedAt = "2026-11-10T02:00:12Z",
        )
        assertEquals("#42 · 11/10 02:00 · 12s · \$0.0123", scheduleRunSummary(run, ZoneOffset.UTC))

        assertEquals("ppm-logs-20261111-0905.log", phoneLogsDownloadName(LocalDateTime.of(2026, 11, 11, 9, 5)))
        val entry = LogEntry(id = "e1", ts = 0L, lv = "warn", src = "server", tag = "chat", msg = "hello")
        assertEquals(
            "[1970-01-01T00:00:00Z] [WARN] [chat] hello [x3]\n",
            phoneLogsDownloadText(listOf(LogRow(key = "e1", entry = entry, count = 3, ids = listOf("e1"), lastTs = 0L))),
        )

        assertTrue(connectedThroughShareUrl("https://abc.trycloudflare.com/", "https://ABC.trycloudflare.com"))
        assertTrue(!connectedThroughShareUrl("http://192.168.1.5:8080/", "https://abc.trycloudflare.com"))
    }
}
