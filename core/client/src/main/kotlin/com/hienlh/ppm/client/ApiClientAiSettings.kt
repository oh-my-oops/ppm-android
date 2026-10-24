package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.AiSettings
import com.hienlh.ppm.protocol.McpServerState
import com.hienlh.ppm.protocol.ModelOption
import com.hienlh.ppm.protocol.ProviderProbeStatus
import com.hienlh.ppm.protocol.TeamActivityFeed
import com.hienlh.ppm.protocol.TeamInfo
import com.hienlh.ppm.protocol.TeamMessage
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonObject

/** `GET /api/settings/ai`: the AI settings, API keys masked. */
suspend fun ApiClient.aiSettings(): AiSettings = AiSettings.from(call("GET", "/api/settings/ai")) ?: throw PpmError.BadResponse("Unexpected AI settings.")

/** `PUT /api/settings/ai` with one change ([com.hienlh.ppm.protocol.AiSettingsPatch]); answers the settings as saved. */
suspend fun ApiClient.updateAiSettings(patch: JsonObject): AiSettings =
    AiSettings.from(call("PUT", "/api/settings/ai", body = patch)) ?: throw PpmError.BadResponse("Unexpected AI settings.")

/** `GET /api/settings/ai/providers/status`: which CLI providers chat can reach, and why one cannot. */
suspend fun ApiClient.providerStatuses(): List<ProviderProbeStatus> =
    call("GET", "/api/settings/ai/providers/status").array.orEmpty().mapNotNull(ProviderProbeStatus::from)

/** `POST /api/settings/ai/providers/:id/probe`: looks for the provider again now, ahead of the server's own schedule. */
suspend fun ApiClient.probeProvider(providerId: String): ProviderProbeStatus =
    ProviderProbeStatus.from(call("POST", "/api/settings/ai/providers/${ApiClient.escape(providerId)}/probe"))
        ?: throw PpmError.BadResponse("Unexpected provider status.")

/** `GET /api/settings/ai/providers/:id/models`: the models Settings offers for a provider, no project needed. */
suspend fun ApiClient.settingsModels(providerId: String): List<ModelOption> =
    call("GET", "/api/settings/ai/providers/${ApiClient.escape(providerId)}/models").array.orEmpty().mapNotNull { entry ->
        val value = entry["value"].string ?: return@mapNotNull null
        ModelOption(value, entry["label"].string ?: value)
    }

/** `GET /api/teams`: the agent teams on the server's machine. */
suspend fun ApiClient.teams(): List<TeamInfo> = call("GET", "/api/teams").array.orEmpty().mapNotNull(TeamInfo::from)

/** `GET /api/teams/:name`: what the team's inboxes hold, oldest first. */
suspend fun ApiClient.teamMessages(name: String): List<TeamMessage> =
    call("GET", "/api/teams/${ApiClient.escape(name)}")["messages"].array.orEmpty().mapNotNull(TeamMessage::from)

/** `GET /api/teams/:name/activity`: what each member is doing, read from its transcript, and the replies sent back. */
suspend fun ApiClient.teamActivity(name: String): TeamActivityFeed =
    TeamActivityFeed.from(call("GET", "/api/teams/${ApiClient.escape(name)}/activity"))

/** `DELETE /api/teams/:name`: removes a team and everything it kept. */
suspend fun ApiClient.deleteTeam(name: String) {
    call("DELETE", "/api/teams/${ApiClient.escape(name)}")
}

/**
 * `GET /api/mcp-auth/status`: every MCP server's state as a chat in [project] would see it (the
 * home directory's with none). [fresh] asks again rather than taking the server's five-minute answer.
 */
suspend fun ApiClient.mcpAuthStatus(project: String?, fresh: Boolean): List<McpServerState> {
    val query = mutableListOf<Pair<String, String>>()
    if (project != null) query += "project" to project
    if (fresh) query += "fresh" to "1"
    return call("GET", "/api/mcp-auth/status", query).array.orEmpty().mapNotNull(McpServerState::from)
}
