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
}
