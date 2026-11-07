package com.hienlh.ppm.state.settings

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
    fun `currentTailscaleSetupStep walks 7 steps in exact order`() {
        val s0 = TailscaleState(cliInstalled = false)
        assertEquals(TailscaleStepId.Install, currentTailscaleSetupStep(s0))
        assertEquals(TailscaleStepId.Install, blockingTailscaleSetupStep(s0))

        val s1 = s0.copy(cliInstalled = true, backendState = "Stopped")
        assertEquals(TailscaleStepId.Start, currentTailscaleSetupStep(s1))

        val s2 = s1.copy(backendState = "NeedsLogin", platform = "linux", operatorReady = false)
        assertEquals(TailscaleStepId.Operator, currentTailscaleSetupStep(s2))

        val s3 = s2.copy(operatorReady = true)
        assertEquals(TailscaleStepId.SignIn, currentTailscaleSetupStep(s3))

        val s4 = s3.copy(backendState = "Running", httpsAvailable = false)
        assertEquals(TailscaleStepId.Dns, currentTailscaleSetupStep(s4))

        val s5 = s4.copy(httpsAvailable = true, magicDnsSuffix = "tail123.ts.net", tags = emptyList())
        assertEquals(TailscaleStepId.Tag, currentTailscaleSetupStep(s5))

        val s6 = s5.copy(tags = listOf("tag:server"), service = TailscaleServiceInfo(status = "disabled", name = "ppm"))
        assertEquals(TailscaleStepId.Service, currentTailscaleSetupStep(s6))
        assertNull(blockingTailscaleSetupStep(s6))
        assertEquals("https://ppm.tail123.ts.net", tailscaleServiceUrl(s6))

        val s7 = s6.copy(service = TailscaleServiceInfo(status = "ready", name = "ppm", url = "https://ppm.tail123.ts.net"))
        assertNull(currentTailscaleSetupStep(s7))
        assertTrue(isConnectedViaAddress("https://ppm.tail123.ts.net:3210", "ppm.tail123.ts.net."))
        assertFalse(isConnectedViaAddress("http://192.168.1.10:3210", "100.64.0.1"))
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
            projectName = "",
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
