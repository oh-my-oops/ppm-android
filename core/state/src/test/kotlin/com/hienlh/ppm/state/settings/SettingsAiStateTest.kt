package com.hienlh.ppm.state.settings

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAiStateTest {

    @Test
    fun `isPpmToolEnabled defaults to true and respects explicit false`() {
        val map = mapOf("open_file" to true, "db_execute" to false)
        assertTrue(isPpmToolEnabled(map, "open_file"))
        assertFalse(isPpmToolEnabled(map, "db_execute"))
        assertTrue(isPpmToolEnabled(map, "read_terminal"))
    }

    @Test
    fun `looksLikeAnthropicOAuthCode and looksLikeCodexLoopbackUrl detect clipboard formats`() {
        assertTrue(looksLikeAnthropicOAuthCode("abcDEF1234567890_xyz#state987654321"))
        assertTrue(looksLikeAnthropicOAuthCode("abcDEF1234567890_xyz"))
        assertFalse(looksLikeAnthropicOAuthCode("https://claude.ai/oauth"))
        assertFalse(looksLikeAnthropicOAuthCode("short"))

        assertTrue(looksLikeCodexLoopbackUrl("http://localhost:1455/auth/callback?code=abc123&state=xyz"))
        assertTrue(looksLikeCodexLoopbackUrl("http://127.0.0.1:1455/callback?code=abc123"))
        assertFalse(looksLikeCodexLoopbackUrl("https://chatgpt.com/auth"))
    }

    @Test
    fun `validateMcpName and validateMcpConfig match server validation rules`() {
        assertNull(validateMcpName("playwright-mcp_1"))
        assertNotNull(validateMcpName(""))
        assertNotNull(validateMcpName("-invalid"))
        assertNotNull(validateMcpName("a".repeat(51)))

        assertTrue(validateMcpConfig("stdio", "npx", "").isEmpty())
        assertEquals(listOf("command is required for stdio"), validateMcpConfig("stdio", "   ", ""))
        assertTrue(validateMcpConfig("http", "", "https://mcp.example.com").isEmpty())
        assertEquals(listOf("url must be HTTP(S)"), validateMcpConfig("sse", "", "ftp://bad"))

        val built = buildMcpConfigJson(
            transport = "stdio",
            command = "npx",
            argsText = "-y @playwright/mcp@latest",
            url = "",
            kvPairs = listOf(McpKeyValuePair("API_KEY", "secret"), McpKeyValuePair("  ", "ignored")),
        )
        assertEquals("stdio", built["type"].string)
        assertEquals("npx", built["command"].string)
        assertEquals(2, built["args"].array?.size)
        assertEquals("secret", built["env"].obj?.get("API_KEY").string)
    }

    @Test
    fun `parseAiResources and filterAiResources filter by chip and search query`() {
        val raw = WireJson.parseOrNull(
            """
            {
              "groups": [
                {
                  "type": "skill",
                  "items": [
                    {
                      "type": "skill",
                      "name": "ui-ux-pro-max",
                      "description": "Design system skill",
                      "scope": "user",
                      "source": "claude",
                      "filePath": "/home/user/.claude/skills/ui-ux-pro-max/SKILL.md",
                      "readOnly": false,
                      "shadowed": false
                    }
                  ]
                },
                {
                  "type": "command",
                  "items": [
                    {
                      "type": "command",
                      "name": "review-pr",
                      "description": "Review pull request",
                      "scope": "project",
                      "source": "ppm",
                      "filePath": "/repo/.claude/commands/review-pr.md",
                      "readOnly": true,
                      "shadowed": true
                    }
                  ]
                }
              ],
              "stats": { "active": 1, "project": 1, "user": 1, "bundled": 0, "shadowed": 1 }
            }
            """.trimIndent(),
        )
        val parsed = parseAiResources(raw)
        assertEquals(2, parsed.items.size)
        assertEquals(1, parsed.stats.active)
        assertEquals(1, parsed.stats.shadowed)

        val skillsOnly = filterAiResources(parsed.items, AiResourceFilter.SKILL, "")
        assertEquals(1, skillsOnly.size)
        assertEquals("ui-ux-pro-max", skillsOnly[0].name)

        val queried = filterAiResources(parsed.items, AiResourceFilter.ALL, "pull request")
        assertEquals(1, queried.size)
        assertEquals("review-pr", queried[0].name)

        assertNull(validateAiResourceName("my-skill_1.0"))
        assertNotNull(validateAiResourceName("../escape"))
    }

    @Test
    fun `utf8ByteCount and extractDesignSkillMentions handle multibyte text and deduplicate skills`() {
        assertEquals(3, utf8ByteCount("ăa"))
        val text = "Use /ui-ux-pro-max and /frontend-design then /ui-ux-pro-max again"
        assertEquals(listOf("ui-ux-pro-max", "frontend-design"), extractDesignSkillMentions(text))
    }

    @Test
    fun `12b blocker - parseCodexLoginPollStatus parses server DeviceLoginStatus and applies to device and browser flows`() {
        val startedDevice = parseCodexDeviceLogin(
            WireJson.parseOrNull(
                """
                {
                  "id": "dev-login-1",
                  "userCode": "ABCD-1234",
                  "verificationUrl": "https://auth.openai.com/codex/device",
                  "authUrl": "https://auth.openai.com/codex/device?user_code=ABCD-1234"
                }
                """.trimIndent(),
            ),
        )
        assertNotNull(startedDevice)
        assertEquals("pending", startedDevice!!.status)

        val pendingPoll = WireJson.parseOrNull("""{ "state": "pending" }""")
        val afterPending = applyCodexDeviceLoginPoll(startedDevice, pendingPoll)
        assertEquals("pending", afterPending.status)
        assertEquals("dev-login-1", afterPending.id)

        val donePoll = WireJson.parseOrNull(
            """
            {
              "state": "done",
              "account": {
                "id": "codex-acc-1",
                "label": "user@example.com",
                "type": "chatgpt",
                "status": "active",
                "planType": "plus"
              }
            }
            """.trimIndent(),
        )
        val parsedDone = parseCodexLoginPollStatus(donePoll)
        assertNotNull(parsedDone)
        assertTrue(parsedDone!!.isDone)
        assertEquals("codex-acc-1", parsedDone.accountId)

        val afterDone = applyCodexDeviceLoginPoll(afterPending, donePoll)
        assertEquals("done", afterDone.status)

        val startedBrowser = parseCodexBrowserLogin(
            WireJson.parseOrNull(
                """
                {
                  "id": "browser-login-1",
                  "authUrl": "https://auth.openai.com/oauth/authorize?client_id=test"
                }
                """.trimIndent(),
            ),
        )
        assertNotNull(startedBrowser)
        val errorPoll = WireJson.parseOrNull("""{ "state": "error", "error": "OAuth state mismatch" }""")
        val afterBrowserErr = applyCodexBrowserLoginPoll(startedBrowser!!, errorPoll)
        assertEquals("error", afterBrowserErr.status)
        assertEquals("OAuth state mismatch", afterBrowserErr.error)
    }

    @Test
    fun `12b blocker - effectiveClaudeBackupPassword falls back to DEFAULT_PASSWORD when blank`() {
        assertEquals("ppm-hienlh", effectiveClaudeBackupPassword(""))
        assertEquals("ppm-hienlh", effectiveClaudeBackupPassword("   "))
        assertEquals("my-secret", effectiveClaudeBackupPassword("  my-secret  "))
    }

    @Test
    fun `12b blocker - parseUsageHistory parses server LimitSnapshotRow fields recorded_at five_hour_util weekly_util`() {
        val raw = WireJson.parseOrNull(
            """
            [
              {
                "id": 42,
                "account_id": "acc-1",
                "provider": "claude",
                "five_hour_util": 0.45,
                "five_hour_resets_at": "2026-11-08T09:00:00Z",
                "weekly_util": 0.72,
                "weekly_resets_at": "2026-11-12T00:00:00Z",
                "weekly_opus_util": null,
                "weekly_opus_resets_at": null,
                "weekly_sonnet_util": 0.72,
                "weekly_sonnet_resets_at": "2026-11-12T00:00:00Z",
                "recorded_at": "2026-11-08 04:15:30"
              }
            ]
            """.trimIndent(),
        )
        val points = parseUsageHistory(raw)
        assertEquals(1, points.size)
        assertEquals("2026-11-08 04:15:30", points[0].recordedAt)
        assertEquals(0.45, points[0].fiveHour, 0.0001)
        assertEquals(0.72, points[0].sevenDay, 0.0001)
        assertTrue(points[0].timestamp > 0L)
    }

    @Test
    fun `12b blocker - parseClawbotTelegram and parseTelegramConnectLink handle server connect status and link`() {
        val connectReply = WireJson.parseOrNull(
            """
            {
              "url": "https://t.me/my_ppm_bot?start=tok_1234567890abcdef",
              "expiresAt": 1762575000000
            }
            """.trimIndent(),
        )
        val link = parseTelegramConnectLink(connectReply)
        assertNotNull(link)
        assertEquals("https://t.me/my_ppm_bot?start=tok_1234567890abcdef", link!!.url)
        assertEquals(1762575000000L, link.expiresAt)

        val statusWhileWaiting = WireJson.parseOrNull(
            """
            {
              "configured": true,
              "botUsername": "my_ppm_bot",
              "sharedWithNotifications": false,
              "enabled": true,
              "running": true,
              "chats": [
                { "chatId": "1001", "name": "Alice (@alice)" }
              ],
              "connect": {
                "active": true,
                "expiresAt": 1762575000000,
                "error": null
              }
            }
            """.trimIndent(),
        )
        val parsedWaiting = parseClawbotTelegram(
            statusWhileWaiting,
            previousConnectUrl = link.url,
            previousChatIds = setOf("1001"),
        )
        assertTrue(parsedWaiting.configured)
        assertTrue(parsedWaiting.connectActive)
        assertEquals(link.url, parsedWaiting.connectUrl)
        assertEquals(1762575000000L, parsedWaiting.connectExpiresAt)

        // Once a new chat connects and active becomes false, connectUrl clears
        val statusAfterConnected = WireJson.parseOrNull(
            """
            {
              "configured": true,
              "botUsername": "my_ppm_bot",
              "sharedWithNotifications": false,
              "enabled": true,
              "running": true,
              "chats": [
                { "chatId": "1001", "name": "Alice (@alice)" },
                { "chatId": "1002", "name": "Bob (@bob)" }
              ],
              "connect": {
                "active": false,
                "expiresAt": null,
                "error": null
              }
            }
            """.trimIndent(),
        )
        val parsedAfter = parseClawbotTelegram(
            statusAfterConnected,
            previousConnectUrl = link.url,
            previousChatIds = setOf("1001"),
        )
        assertFalse(parsedAfter.connectActive)
        assertNull(parsedAfter.connectUrl)
        assertEquals(2, parsedAfter.chats.size)
    }

    @Test
    fun `12b duplicateAiResourceTargetScope maps bundled scope to project or user`() {
        assertEquals("project", duplicateAiResourceTargetScope("bundled", "/home/thawngho/Projects/ppm"))
        assertEquals("user", duplicateAiResourceTargetScope("bundled", ""))
        assertEquals("project", duplicateAiResourceTargetScope("project", ""))
        assertEquals("user", duplicateAiResourceTargetScope("user", "/home/thawngho/Projects/ppm"))
    }
}
