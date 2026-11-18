package com.hienlh.ppm.chat

import com.hienlh.ppm.protocol.WireJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** vault-tool-call.test.ts: the vault tools under every provider's name, and a locked read never drawn. */
class VaultToolCallsTest {
    private fun json(text: String) = WireJson.parse(text)

    @Test
    fun `the vault tools under Claude's, Codex's and Antigravity's names`() {
        assertEquals(VaultToolCall.List, vaultToolCall("mcp__ppm-sessions__vault_list", json("{}")))
        assertEquals(
            VaultToolCall.Read(listOf("NI"), "Fill the form"),
            vaultToolCall("ppm_sessions:vault_read", json("""{"server":"ppm_sessions","tool":"vault_read","arguments":{"items":["NI",3],"purpose":"Fill the form"}}""")),
        )
        assertEquals(
            VaultToolCall.Propose("Home", "address", "secure", hasValue = true),
            vaultToolCall("mcp_ppm-sessions_vault_propose", json("""{"name":"Home","kind":"address","tier":"secure","value":"1 Road"}""")),
        )
        assertEquals(VaultToolCall.Propose("", null, null, hasValue = false), vaultToolCall("mcp__ppm-sessions__vault_propose", json("""{"kind":"x","tier":"y"}""")))
        assertNull(vaultToolCall("Read", json("{}")))
        assertNull(vaultToolCall("mcp__ppm-sessions__vault_reader", json("{}")))
    }

    @Test
    fun `a secure or sealed read is a count, live or rebuilt from history`() {
        val live = "Vault items (secure tier):\n- NI: AB123456C\n- Passport: 123456789"
        assertEquals(2, vaultLockedReadCount(live))
        // Inside the content blocks history hands back.
        assertEquals(1, vaultLockedReadCount("""[{"type":"text","text":"Vault items (sealed tier):\n- Passport: 99"}]"""))
        // A wrapping this side does not know still hides the values.
        assertEquals(2, vaultLockedReadCount("""{"result":"Vault items (secure tier):\n- a: 1\n- b: 2"}"""))
        assertNull(vaultLockedReadCount("Public items:\n- Home: 1 Road"))
        assertEquals("Read 2 vault items", vaultToolSummary(VaultToolCall.Read(listOf("a"), ""), 2))
        assertEquals("Read vault: 1 item", vaultToolSummary(VaultToolCall.Read(listOf("a"), ""), null))
        assertEquals("Propose saving “Home” · Address", vaultToolSummary(VaultToolCall.Propose("Home", "address", null, true), null))
        assertEquals("Propose saving “an item”", vaultToolSummary(VaultToolCall.Propose("", null, null, false), null))
    }
}
