package com.hienlh.ppm.state.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsIntegrationsStateTest {

    @Test
    fun `normalizeNtfyServer and validateNtfyTopic match server rules`() {
        assertEquals("https://ntfy.sh", normalizeNtfyServer("  https://ntfy.sh/// "))
        assertEquals("http://10.0.0.5:8080/sub", normalizeNtfyServer("http://10.0.0.5:8080/sub/"))
        assertNull(normalizeNtfyServer("ftp://ntfy.sh"))
        assertNull(normalizeNtfyServer("https://user:pass@ntfy.sh"))
        assertNull(normalizeNtfyServer("https://ntfy.sh?foo=bar"))

        assertNull(validateNtfyTopic("ppm_alerts-01"))
        assertNotNull(validateNtfyTopic(""))
        assertNotNull(validateNtfyTopic("bad/topic"))
        assertNotNull(validateNtfyTopic("a".repeat(65)))
    }

    @Test
    fun `parseTailscaleState and currentTailscaleSetupStep match shared tailscale-setup contract`() {
        val rawServerJson = Json.parseToJsonElement(
            """
            {
              "installed": true,
              "backendState": "Running",
              "canManage": false,
              "osUser": "thawngho",
              "platform": "linux",
              "tailnet": "example.com",
              "dnsSuffix": "tail123.ts.net",
              "magicDns": true,
              "httpsCertificates": true,
              "device": {
                "name": "workstation",
                "dnsName": "workstation.tail123.ts.net.",
                "ips": ["100.64.0.10"],
                "tags": ["tag:server"]
              },
              "service": {
                "name": "ppm",
                "url": "https://ppm.tail123.ts.net",
                "defined": true,
                "approved": false,
                "advertised": true,
                "target": "http://127.0.0.1:3210",
                "pointsAtPpm": true
              },
              "enabled": true,
              "ppmPort": 3210,
              "login": {
                "state": "idle",
                "url": null,
                "message": null
              },
              "authEnabled": true
            }
            """.trimIndent(),
        )
        val parsed = parseTailscaleState(rawServerJson)
        assertTrue(parsed.installed)
        assertEquals("Running", parsed.backendState)
        assertEquals(false, parsed.canManage)
        assertEquals("sudo tailscale set --operator=thawngho", parsed.operatorCommand)
        assertEquals("workstation.tail123.ts.net", parsed.device?.dnsName)
        assertEquals(listOf("tag:server"), parsed.device?.tags)
        assertEquals(TailscaleStepId.Operator, currentTailscaleSetupStep(parsed))

        // Patching login snapshot from POST /api/tailscale/login does not wipe device/service state
        val loginSnap = Json.parseToJsonElement("""{"state":"waiting","url":"https://login.tailscale.com/a/abc123","message":null}""")
        val withLogin = applyTailscaleLoginSnapshot(parsed, loginSnap)
        assertTrue(withLogin.installed)
        assertEquals("waiting", withLogin.login.state)
        assertEquals("https://login.tailscale.com/a/abc123", withLogin.login.url)

        // Walk all 7 setup steps in order
        val s0 = TailscaleState(installed = false)
        assertEquals(TailscaleStepId.Install, currentTailscaleSetupStep(s0))
        assertEquals(TailscaleStepId.Install, blockingTailscaleSetupStep(s0))

        val s1 = s0.copy(installed = true, backendState = "Stopped")
        assertEquals(TailscaleStepId.Start, currentTailscaleSetupStep(s1))

        val s2 = s1.copy(backendState = "NeedsLogin", platform = "linux", canManage = false, osUser = "thawngho")
        assertEquals(TailscaleStepId.Operator, currentTailscaleSetupStep(s2))

        val s3 = s2.copy(canManage = true)
        assertEquals(TailscaleStepId.SignIn, currentTailscaleSetupStep(s3))

        val s4 = s3.copy(backendState = "Running", magicDns = true, httpsCertificates = false)
        assertEquals(TailscaleStepId.Dns, currentTailscaleSetupStep(s4))

        val s5 = s4.copy(
            httpsCertificates = true,
            dnsSuffix = "tail123.ts.net",
            device = TailscaleDeviceInfo(name = "box", dnsName = "box.tail123.ts.net", ips = listOf("100.64.0.1"), tags = emptyList()),
        )
        assertEquals(TailscaleStepId.Tag, currentTailscaleSetupStep(s5))

        val s6 = s5.copy(
            device = s5.device!!.copy(tags = listOf("tag:server")),
            enabled = false,
            service = TailscaleServiceInfo(name = "ppm", pointsAtPpm = false, approved = false),
        )
        assertEquals(TailscaleStepId.Service, currentTailscaleSetupStep(s6))
        assertNull(blockingTailscaleSetupStep(s6))
        assertEquals("https://ppm.tail123.ts.net", tailscaleServiceUrl(s6))

        val s7 = s6.copy(
            enabled = true,
            service = TailscaleServiceInfo(name = "ppm", url = "https://ppm.tail123.ts.net", defined = true, approved = true, advertised = true, pointsAtPpm = true),
        )
        assertNull(currentTailscaleSetupStep(s7))
        assertTrue(isConnectedViaAddress("https://ppm.tail123.ts.net:3210", "ppm.tail123.ts.net."))
        assertFalse(isConnectedViaAddress("http://192.168.1.10:3210", "100.64.0.1"))
    }

    @Test
    fun `named tunnel and public tunnel partial mutations preserve existing state`() {
        val initialNamed = parseNamedTunnelState(
            Json.parseToJsonElement(
                """
                {
                  "mode": "named",
                  "liveMode": "named",
                  "hostname": "ppm.example.com",
                  "tunnelName": "ppm-main",
                  "tokenMasked": "eyJ***",
                  "certState": "valid",
                  "dismissed": false,
                  "login": {"state": "idle", "url": null, "message": null},
                  "tunnelWarning": null,
                  "authEnabled": true
                }
                """.trimIndent(),
            ),
        )
        val afterLoginStart = applyNamedTunnelLoginSnapshot(
            initialNamed,
            Json.parseToJsonElement("""{"state":"waiting","url":"https://dash.cloudflare.com/argotunnel?aud=123","message":null}"""),
        )
        assertEquals("named", afterLoginStart.mode)
        assertEquals("valid", afterLoginStart.certState)
        assertEquals("ppm.example.com", afterLoginStart.hostname)
        assertEquals("waiting", afterLoginStart.loginState)
        assertEquals("https://dash.cloudflare.com/argotunnel?aud=123", afterLoginStart.loginUrl)

        val afterCancel = applyNamedTunnelLoginSnapshot(
            afterLoginStart,
            Json.parseToJsonElement("""{"state":"cancelled"}"""),
        )
        assertEquals("cancelled", afterCancel.loginState)
        assertNull(afterCancel.loginUrl)
        assertEquals("ppm.example.com", afterCancel.hostname)

        val afterSetup = applyNamedTunnelSetupResult(
            afterCancel,
            Json.parseToJsonElement("""{"hostname":"dev.example.com","tunnelName":"ppm-dev","pending":false}"""),
        )
        assertEquals("named", afterSetup.mode)
        assertEquals("dev.example.com", afterSetup.hostname)
        assertEquals("ppm-dev", afterSetup.tunnelName)

        val afterDisable = applyNamedTunnelDisableResult(
            afterSetup,
            Json.parseToJsonElement("""{"mode":"quick"}"""),
        )
        assertEquals("quick", afterDisable.mode)
        assertEquals("valid", afterDisable.certState)

        val initialPublic = PublicTunnelState(active = false, url = null, localUrl = "http://192.168.1.5:3210", enabled = false)
        val enabledPublic = applyPublicTunnelEnabledResult(
            initialPublic,
            Json.parseToJsonElement("""{"enabled":true,"reload":false}"""),
        )
        assertTrue(enabledPublic.enabled)
        assertEquals("http://192.168.1.5:3210", enabledPublic.localUrl)
    }

    @Test
    fun `parseCloudDeviceCodeStart and parseCloudDeviceCodePoll parse cloud device-code flow`() {
        val loginUrlJson = Json.parseToJsonElement("""{"url":"https://ppm.bot/auth/login","cloud_url":"https://ppm.bot"}""")
        val cloudUrl = parseCloudLoginUrl(loginUrlJson)
        assertEquals("https://ppm.bot", cloudUrl)

        val startJson = Json.parseToJsonElement(
            """
            {
              "device_code": "dev_abc123",
              "user_code": "ABCD-1234",
              "verification_uri": "https://ppm.bot/activate",
              "expires_in": 600,
              "interval": 5
            }
            """.trimIndent(),
        )
        val flow = parseCloudDeviceCodeStart(startJson, cloudUrl)
        assertEquals("waiting", flow.status)
        assertEquals("dev_abc123", flow.deviceCode)
        assertEquals("ABCD-1234", flow.userCode)
        assertEquals("https://ppm.bot/activate", flow.verificationUri)

        val pollApproved = parseCloudDeviceCodePoll(
            Json.parseToJsonElement("""{"status":"approved","access_token":"tok_live_999","email":"dev@example.com"}"""),
        )
        assertEquals("approved", pollApproved.status)
        assertEquals("tok_live_999", pollApproved.accessToken)
        assertEquals("dev@example.com", pollApproved.email)
    }

    @Test
    fun `parseSchedulesList and parseScheduleRuns read exact ScheduleView and ScheduleRun server fields`() {
        val serverSchedulesJson = Json.parseToJsonElement(
            """
            [
              {
                "id": 7,
                "name": "Hourly Sync",
                "description": "Syncs repo state",
                "kind": "script",
                "mode": "tick",
                "cron_expr": "0 * * * *",
                "provider_id": "claude",
                "project_path": "/home/thawngho/Projects/ppm",
                "prompt": "",
                "script": "echo sync",
                "permission_mode": "bypassPermissions",
                "max_turns": 20,
                "timeout_ms": 60000,
                "enabled": 1,
                "session_id": "sess-1",
                "last_run_at": "2026-11-08T10:00:00Z",
                "next_fire_at": "2026-11-08T11:00:00Z",
                "created_at": "2026-11-01T00:00:00Z",
                "updated_at": "2026-11-08T10:00:00Z",
                "inputs": [{"key": "API_KEY", "label": "API Key", "type": "secret", "required": true}],
                "inputValues": {},
                "secretsSet": ["API_KEY"],
                "status": {
                  "state": "running",
                  "pid": 4321,
                  "since": 1762596000000,
                  "restartAt": null,
                  "restarts": 2,
                  "lastError": null
                }
              }
            ]
            """.trimIndent(),
        )
        val rows = parseSchedulesList(serverSchedulesJson)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(7, row.id)
        assertEquals("0 * * * *", row.cronExpr)
        assertEquals("/home/thawngho/Projects/ppm", row.projectPath)
        assertEquals(60000, row.timeoutMs)
        assertTrue(row.enabled)
        assertEquals("2026-11-08T10:00:00Z", row.lastRunAt)
        assertEquals("2026-11-08T11:00:00Z", row.nextFireAt)
        assertEquals(listOf("API_KEY"), row.secretsSet)
        assertEquals("running", row.status.state)
        assertEquals(4321, row.status.pid)
        assertEquals(1762596000000L, row.status.since)
        assertEquals(2, row.status.restarts)

        val runsJson = Json.parseToJsonElement(
            """
            [
              {
                "id": 99,
                "schedule_id": 7,
                "session_id": "sess-1",
                "rotated_to_session_id": null,
                "status": "success",
                "output_truncated": "done",
                "context_window_pct": 12.5,
                "cost_usd": 0.02,
                "error": null,
                "started_at": "2026-11-08T10:00:00Z",
                "ended_at": "2026-11-08T10:00:05Z"
              }
            ]
            """.trimIndent(),
        )
        val runs = parseScheduleRuns(runsJson)
        assertEquals(1, runs.size)
        assertEquals(7, runs[0].scheduleId)
        assertEquals("success", runs[0].status)
        assertEquals("2026-11-08T10:00:00Z", runs[0].startedAt)
        assertEquals("2026-11-08T10:00:05Z", runs[0].endedAt)
    }

    @Test
    fun `validateTailscaleServiceName checks DNS label rules`() {
        assertNull(validateTailscaleServiceName("ppm"))
        assertNull(validateTailscaleServiceName("ppm-dev-2"))
        assertNotNull(validateTailscaleServiceName(""))
        assertNotNull(validateTailscaleServiceName("-ppm"))
        assertNotNull(validateTailscaleServiceName("ppm-"))
        assertNotNull(validateTailscaleServiceName("PPM_UPPER"))
    }

    @Test
    fun `cronProblem and scheduleFormProblems enforce prompt vs script rules`() {
        assertEquals(5, cronFieldCount("*/5 * * * *"))
        assertEquals(6, cronFieldCount("*/10 * * * * *"))

        assertNull(cronProblem("*/5 * * * *", "prompt"))
        assertNotNull(cronProblem("*/10 * * * * *", "prompt"))
        assertNull(cronProblem("*/5 * * * * *", "script"))
        assertNotNull(cronProblem("*/2 * * * * *", "script"))
        assertNotNull(cronProblem("* * * * * *", "script"))

        val problems = scheduleFormProblems(
            name = "",
            projectPath = "",
            kind = "prompt",
            mode = "always-on",
            cron = "*/5 * * * *",
            prompt = "",
            script = "",
            timeoutMs = 5_000,
        )
        assertTrue(problems.any { it.contains("Name is required") })
        assertTrue(problems.any { it.contains("Project is required") })
        assertTrue(problems.any { it.contains("Always-on") })
        assertTrue(problems.any { it.contains("Prompt is required") })
    }

    @Test
    fun `parseScheduleTemplateJson validates schema and secret defaults`() {
        val validJson = """
            {
              "ppmScheduleTemplate": 1,
              "name": "Nightly Audit",
              "kind": "script",
              "mode": "tick",
              "cron": "*/10 * * * * *",
              "script": "echo hello",
              "timeoutMs": 30000,
              "inputs": [
                {"key": "API_TOKEN", "label": "Token", "type": "secret", "required": true}
              ]
            }
        """.trimIndent()
        val parsed = parseScheduleTemplateJson(validJson).getOrThrow()
        assertEquals("Nightly Audit", parsed.name)
        assertEquals("script", parsed.kind)
        assertEquals(1, parsed.inputs.size)
        assertEquals("API_TOKEN", parsed.inputs[0].key)

        val secretWithDefault = """
            {
              "ppmScheduleTemplate": 1,
              "name": "Bad Secret",
              "kind": "script",
              "mode": "tick",
              "cron": "* * * * *",
              "script": "echo hi",
              "inputs": [
                {"key": "API_TOKEN", "type": "secret", "default": "leaked"}
              ]
            }
        """.trimIndent()
        assertTrue(parseScheduleTemplateJson(secretWithDefault).isFailure)
    }

    @Test
    fun `mergeScheduleLogs resets buffer when server restarts and last shrinks`() {
        val initial = ScheduleLogBufferState(
            lines = listOf(
                ScheduleLogLine(seq = 10, stream = "stdout", ts = 1000L, text = "line 10"),
                ScheduleLogLine(seq = 11, stream = "stdout", ts = 1001L, text = "line 11"),
            ),
            afterSeq = 11,
            status = ScheduleStatusState(state = "running", pid = 123),
        )

        // Normal append
        val nextPoll = ScheduleLogPollResult(
            status = ScheduleStatusState(state = "running", pid = 123),
            lines = listOf(ScheduleLogLine(seq = 12, stream = "stderr", ts = 1002L, text = "line 12")),
            last = 12,
        )
        val appended = mergeScheduleLogs(initial, nextPoll)
        assertEquals(3, appended.lines.size)
        assertEquals(12, appended.afterSeq)
        assertFalse(appended.resetDetected)

        // Server restart: last = 0 < afterSeq = 12
        val restartPoll = ScheduleLogPollResult(
            status = ScheduleStatusState(state = "running", pid = 456),
            lines = emptyList(),
            last = 0,
        )
        val reset = mergeScheduleLogs(appended, restartPoll)
        assertTrue(reset.resetDetected)
        assertEquals(0, reset.afterSeq)
        assertTrue(reset.lines.isEmpty())
        assertEquals(456, reset.status.pid)
    }
}
