package com.hienlh.ppm.client

import kotlinx.serialization.json.JsonElement
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What "Always in this project" on a Claude approval card saved (`approval-rules.ts`): its rule
 * [key], what the card called it ([label]) and the project folder it holds in.
 */
data class SavedApprovalRule(val projectPath: String, val key: String, val label: String, val savedAt: String?) {
    /** What Settings shows: the command itself for a rule only that exact command matches. */
    val title: String get() = if (key.startsWith(EXACT)) key.removePrefix(EXACT) else label

    companion object {
        private const val EXACT = "bash-exact:"

        fun from(json: JsonElement?): SavedApprovalRule? {
            val projectPath = json["projectPath"].string ?: return null
            val key = json["key"].string ?: return null
            return SavedApprovalRule(projectPath, key, json["label"].string ?: key, json["savedAt"].string)
        }
    }
}

/** `GET /api/settings/approval-rules`: every project's saved rules; a server without them is a 404. */
suspend fun ApiClient.approvalRules(): List<SavedApprovalRule> =
    call("GET", "/api/settings/approval-rules").array.orEmpty().mapNotNull(SavedApprovalRule::from)

/** `DELETE /api/settings/approval-rules` with `{ projectPath, key }`: a rule that is gone already is a 404. */
suspend fun ApiClient.removeApprovalRule(projectPath: String, key: String) {
    call("DELETE", "/api/settings/approval-rules", body = buildJsonObject {
        put("projectPath", projectPath)
        put("key", key)
    })
}
