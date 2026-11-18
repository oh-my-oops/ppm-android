package com.hienlh.ppm.chat

import com.hienlh.ppm.protocol.VaultKinds
import com.hienlh.ppm.protocol.VaultTiers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The AI's `vault_*` tool calls (`vault-tool-call.ts`), so the transcript can draw them without
 * ever showing a locked value. Claude names a tool `mcp__ppm-sessions__vault_read`, Codex
 * `ppm_sessions:vault_read` with the arguments wrapped as `{ server, tool, arguments }`, and
 * Antigravity `mcp_ppm-sessions_vault_read`.
 *
 * A secure or sealed read answers with the values themselves ("Vault items (secure tier)…"), and
 * that text is also what history hands back after a reload. The card draws a count instead.
 */

const val VAULT_LIST_TOOL = "vault_list"
const val VAULT_READ_TOOL = "vault_read"
const val VAULT_PROPOSE_TOOL = "vault_propose"

/** Any server's `vault_*` tool, under any provider's separator. */
private val VAULT_TOOL_NAME = Regex("""(?:__|:|_)(vault_list|vault_read|vault_propose)$""")

/** The start of a read that carries secure or sealed values (`valuesText` on the server). */
internal const val LOCKED_VALUES_PREFIX = "Vault items ("

sealed interface VaultToolCall {
    data object List : VaultToolCall

    data class Read(val items: kotlin.collections.List<String>, val purpose: String) : VaultToolCall

    /** Never the proposed value: only whether the AI sent one. */
    data class Propose(val name: String, val kind: String?, val tier: String?, val hasValue: Boolean) : VaultToolCall
}

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The vault tool a tool name is, or null. */
fun vaultToolOf(name: String): String? = VAULT_TOOL_NAME.find(name)?.groupValues?.get(1)

/** The vault call an event is, or null (`vaultToolCall`). */
fun vaultToolCall(name: String, input: JsonElement?): VaultToolCall? {
    val tool = vaultToolOf(name) ?: return null
    val raw = input as? JsonObject ?: JsonObject(emptyMap())
    val args = raw["arguments"] as? JsonObject ?: raw
    return when (tool) {
        VAULT_LIST_TOOL -> VaultToolCall.List
        VAULT_READ_TOOL -> VaultToolCall.Read(
            items = (args["items"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.text() }.orEmpty(),
            purpose = args["purpose"].text().orEmpty(),
        )
        else -> VaultToolCall.Propose(
            name = args["name"].text()?.takeIf { it.isNotBlank() }.orEmpty(),
            kind = args["kind"].text()?.takeIf(VaultKinds::isKind),
            tier = args["tier"].text()?.takeIf(VaultTiers::isTier),
            hasValue = !args["value"].text().isNullOrEmpty(),
        )
    }
}

/**
 * A read result that must not be drawn: how many items it carried. Null for anything else (a
 * public read, an error, "shown to the user only"), which the card may show as it is. Looked for
 * anywhere in the raw output as well as at the start of its text, so a wrapping this file does not
 * know about still hides the values rather than printing them (`lockedReadSummary`).
 */
fun vaultLockedReadCount(output: String): Int? {
    val text = tabToolResultText(output)
    if (text.startsWith(LOCKED_VALUES_PREFIX)) return text.split("\n").count { it.startsWith("- ") }
    if (!output.contains(LOCKED_VALUES_PREFIX)) return null
    return Regex("""(?:\n|\\n)- """).findAll(output).count()
}

private fun plural(n: Int, one: String) = "$n $one${if (n == 1) "" else "s"}"

/** The card's one line (`summaryOf`). */
internal fun vaultToolSummary(call: VaultToolCall, hiddenCount: Int?): String = when (call) {
    VaultToolCall.List -> "List vault items"
    is VaultToolCall.Read -> if (hiddenCount != null) "Read ${plural(hiddenCount, "vault item")}" else "Read vault: ${plural(call.items.size, "item")}"
    is VaultToolCall.Propose -> "Propose saving “${call.name.ifEmpty { "an item" }}”" + (call.kind?.let { " · ${VaultKinds.label(it)}" } ?: "")
}
