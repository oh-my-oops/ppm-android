package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

// ===========================================================================
// 1. Notifications (/api/notifications/*, /api/settings/telegram*)
// ===========================================================================

suspend fun ApiClient.notificationsGetSettings(): JsonElement =
    call("GET", "/api/notifications/settings")

suspend fun ApiClient.notificationsPutSettings(patch: JsonObject): JsonElement =
    call("PUT", "/api/notifications/settings", body = patch)

suspend fun ApiClient.notificationsGetPush(): JsonElement =
    call("GET", "/api/notifications/push")

suspend fun ApiClient.notificationsUnsubscribePush(endpoint: String? = null, id: String? = null): JsonElement =
    call(
        "POST",
        "/api/notifications/push/unsubscribe",
        body = buildJsonObject {
            if (!endpoint.isNullOrBlank()) put("endpoint", endpoint)
            if (!id.isNullOrBlank()) put("id", id)
        },
    )

suspend fun ApiClient.notificationsTestPush(id: String? = null): JsonElement =
    call(
        "POST",
        "/api/notifications/push/test",
        body = buildJsonObject {
            if (!id.isNullOrBlank()) put("id", id)
        },
    )

suspend fun ApiClient.notificationsGetTelegram(): JsonElement =
    call("GET", "/api/notifications/telegram")

suspend fun ApiClient.settingsPutTelegramToken(botToken: String): JsonElement =
    call("PUT", "/api/settings/telegram", body = buildJsonObject { put("bot_token", botToken) })

suspend fun ApiClient.settingsTestTelegram(): JsonElement =
    call("POST", "/api/settings/telegram/test")

suspend fun ApiClient.notificationsStartTelegramConnect(): JsonElement =
    call("POST", "/api/notifications/telegram/connect")

suspend fun ApiClient.notificationsCancelTelegramConnect(): JsonElement =
    call("DELETE", "/api/notifications/telegram/connect")

suspend fun ApiClient.notificationsRemoveTelegramChat(chatId: String): JsonElement =
    call("DELETE", "/api/notifications/telegram/chats/${ApiClient.escape(chatId)}")

suspend fun ApiClient.notificationsGetNtfy(): JsonElement =
    call("GET", "/api/notifications/ntfy")

suspend fun ApiClient.notificationsPutNtfy(
    server: String,
    topic: String,
    token: String? = null,
): JsonElement =
    call(
        "PUT",
        "/api/notifications/ntfy",
        body = buildJsonObject {
            put("server", server)
            put("topic", topic)
            if (token != null) put("token", token)
        },
    )

suspend fun ApiClient.notificationsDeleteNtfy(): JsonElement =
    call("DELETE", "/api/notifications/ntfy")

suspend fun ApiClient.notificationsTestNtfy(): JsonElement =
    call("POST", "/api/notifications/ntfy/test")

// ===========================================================================
// 2. Remote Access: Tailscale, Public Link, Named Tunnel
// ===========================================================================

suspend fun ApiClient.tailscaleGetState(): JsonElement =
    call("GET", "/api/tailscale/state")

suspend fun ApiClient.tailscaleStartLogin(): JsonElement =
    call("POST", "/api/tailscale/login")

suspend fun ApiClient.tailscaleCancelLogin(): JsonElement =
    call("POST", "/api/tailscale/login/cancel")

suspend fun ApiClient.tailscaleConfigureService(
    enabled: Boolean? = null,
    name: String? = null,
    replace: Boolean? = null,
): JsonElement =
    call(
        "POST",
        "/api/tailscale/service",
        body = buildJsonObject {
            if (enabled != null) put("enabled", enabled)
            if (name != null) put("name", name)
            if (replace != null) put("replace", replace)
        },
    )

suspend fun ApiClient.tunnelGetStatus(): JsonElement =
    call("GET", "/api/tunnel")

suspend fun ApiClient.tunnelSetEnabled(enabled: Boolean): JsonElement =
    call("POST", "/api/tunnel/enabled", body = buildJsonObject { put("enabled", enabled) })

suspend fun ApiClient.tunnelStart(): JsonElement =
    call("POST", "/api/tunnel/start")

suspend fun ApiClient.tunnelStop(): JsonElement =
    call("POST", "/api/tunnel/stop")

suspend fun ApiClient.namedTunnelGetStatus(): JsonElement =
    call("GET", "/api/tunnel/named/status")

suspend fun ApiClient.namedTunnelDismiss(): JsonElement =
    call("POST", "/api/tunnel/named/dismiss")

suspend fun ApiClient.namedTunnelGetZone(): JsonElement =
    call("GET", "/api/tunnel/named/zone")

suspend fun ApiClient.namedTunnelStartLogin(relogin: Boolean = false): JsonElement =
    call(
        "POST",
        "/api/tunnel/named/login",
        query = if (relogin) listOf("relogin" to "1") else emptyList(),
    )

suspend fun ApiClient.namedTunnelCancelLogin(): JsonElement =
    call("POST", "/api/tunnel/named/login/cancel")

suspend fun ApiClient.namedTunnelSetup(hostname: String): JsonElement =
    call("POST", "/api/tunnel/named/setup", body = buildJsonObject { put("hostname", hostname) })

suspend fun ApiClient.namedTunnelDisable(): JsonElement =
    call("POST", "/api/tunnel/named/disable")

// ===========================================================================
// 3. Cloud & Share, Proxy, Extensions
// ===========================================================================

suspend fun ApiClient.cloudGetStatus(): JsonElement =
    call("GET", "/api/cloud/status")

suspend fun ApiClient.cloudGetLoginUrl(): JsonElement =
    call("GET", "/api/cloud/login-url")

private val CLOUD_JSON_MEDIA_TYPE = "application/json".toMediaType()

private suspend fun callCloudJson(url: String, body: JsonObject): JsonElement {
    val request = Request.Builder()
        .url(url)
        .post(body.toString().toRequestBody(CLOUD_JSON_MEDIA_TYPE))
        .header("Accept", "application/json")
        .build()
    val response = try {
        PpmHttp.client.newCall(request).await()
    } catch (e: IOException) {
        throw PpmError.Unreachable(describe(e))
    }
    return withContext(Dispatchers.IO) {
        response.use { res ->
            val text = try {
                res.body.string()
            } catch (e: IOException) {
                throw PpmError.Unreachable(describe(e))
            }
            val parsed = WireJson.parseOrNull(text)
                ?: throw PpmError.BadResponse("Cloud server answered HTTP ${res.code}.")
            if (!res.isSuccessful || parsed["ok"].bool == false) {
                throw PpmError.Server(
                    status = res.code,
                    serverMessage = parsed["error"].string ?: "Cloud server answered HTTP ${res.code}.",
                )
            }
            parsed["data"]?.takeIf { it !is JsonNull } ?: parsed
        }
    }
}

suspend fun ApiClient.cloudRequestDeviceCode(cloudUrl: String): JsonElement =
    callCloudJson("${cloudUrl.trimEnd('/')}/auth/device-code", buildJsonObject {})

suspend fun ApiClient.cloudPollDeviceCode(cloudUrl: String, deviceCode: String): JsonElement =
    callCloudJson(
        "${cloudUrl.trimEnd('/')}/auth/device-code/poll",
        buildJsonObject { put("device_code", deviceCode) },
    )

suspend fun ApiClient.cloudLogin(
    accessToken: String,
    email: String,
    cloudUrl: String? = null,
): JsonElement =
    call(
        "POST",
        "/api/cloud/login",
        body = buildJsonObject {
            put("access_token", accessToken)
            put("email", email)
            if (!cloudUrl.isNullOrBlank()) put("cloud_url", cloudUrl)
        },
    )

suspend fun ApiClient.cloudLogout(): JsonElement =
    call("POST", "/api/cloud/logout")

suspend fun ApiClient.cloudLink(name: String? = null): JsonElement =
    call(
        "POST",
        "/api/cloud/link",
        body = buildJsonObject {
            if (!name.isNullOrBlank()) put("name", name)
        },
    )

suspend fun ApiClient.cloudUnlink(): JsonElement =
    call("POST", "/api/cloud/unlink")

suspend fun ApiClient.cloudGetAlias(): JsonElement =
    call("GET", "/api/cloud/alias")

/** Pass `null` to remove the alias; empty string is rejected by server. */
suspend fun ApiClient.cloudSetAlias(slug: String?): JsonElement =
    call(
        "PATCH",
        "/api/cloud/alias",
        body = buildJsonObject {
            if (slug == null) put("slug", JsonNull) else put("slug", slug)
        },
    )

suspend fun ApiClient.settingsGetProxy(): JsonElement =
    call("GET", "/api/settings/proxy")

suspend fun ApiClient.settingsPutProxy(
    enabled: Boolean? = null,
    authKey: String? = null,
    generateKey: Boolean? = null,
): JsonElement =
    call(
        "PUT",
        "/api/settings/proxy",
        body = buildJsonObject {
            if (enabled != null) put("enabled", enabled)
            if (authKey != null) put("authKey", authKey)
            if (generateKey != null) put("generateKey", generateKey)
        },
    )

suspend fun ApiClient.extensionsList(): JsonElement =
    call("GET", "/api/extensions")

suspend fun ApiClient.extensionsInstall(name: String): JsonElement =
    call("POST", "/api/extensions/install", body = buildJsonObject { put("name", name) })

suspend fun ApiClient.extensionsDevLink(path: String): JsonElement =
    call("POST", "/api/extensions/dev-link", body = buildJsonObject { put("path", path) })

suspend fun ApiClient.extensionsSetEnabled(id: String, enabled: Boolean): JsonElement =
    call("PATCH", "/api/extensions/${ApiClient.escape(id)}", body = buildJsonObject { put("enabled", enabled) })

suspend fun ApiClient.extensionsRemove(id: String): JsonElement =
    call("DELETE", "/api/extensions/${ApiClient.escape(id)}")

// ===========================================================================
// 4. Jira (/api/jira/*)
// ===========================================================================

suspend fun ApiClient.jiraListConfigProjects(): JsonElement =
    call("GET", "/api/jira/config/projects")

suspend fun ApiClient.jiraListConfigs(): JsonElement =
    call("GET", "/api/jira/config")

suspend fun ApiClient.jiraPutConfig(
    projectId: Int,
    baseUrl: String,
    email: String,
    token: String? = null,
): JsonElement =
    call(
        "PUT",
        "/api/jira/config/$projectId",
        body = buildJsonObject {
            put("baseUrl", baseUrl)
            put("email", email)
            if (!token.isNullOrBlank()) put("token", token)
        },
    )

suspend fun ApiClient.jiraDeleteConfig(projectId: Int): JsonElement =
    call("DELETE", "/api/jira/config/$projectId")

suspend fun ApiClient.jiraTestConfig(projectId: Int): JsonElement =
    call("POST", "/api/jira/config/$projectId/test")

suspend fun ApiClient.jiraListWatchers(configId: Int): JsonElement =
    call("GET", "/api/jira/watchers", query = listOf("configId" to configId.toString()))

suspend fun ApiClient.jiraCreateWatcher(
    configId: Int,
    name: String,
    jql: String,
    promptTemplate: String? = null,
    intervalMs: Int = 120_000,
    mode: String = "notify",
): JsonElement =
    call(
        "POST",
        "/api/jira/watchers",
        body = buildJsonObject {
            put("configId", configId)
            put("name", name)
            put("jql", jql)
            if (!promptTemplate.isNullOrBlank()) put("promptTemplate", promptTemplate)
            put("intervalMs", intervalMs)
            put("mode", mode)
        },
    )

suspend fun ApiClient.jiraUpdateWatcher(
    id: Int,
    name: String? = null,
    jql: String? = null,
    promptTemplate: String? = null,
    intervalMs: Int? = null,
    enabled: Boolean? = null,
    mode: String? = null,
): JsonElement =
    call(
        "PUT",
        "/api/jira/watchers/$id",
        body = buildJsonObject {
            if (name != null) put("name", name)
            if (jql != null) put("jql", jql)
            if (promptTemplate != null) put("promptTemplate", promptTemplate)
            if (intervalMs != null) put("intervalMs", intervalMs)
            if (enabled != null) put("enabled", enabled)
            if (mode != null) put("mode", mode)
        },
    )

suspend fun ApiClient.jiraDeleteWatcher(id: Int): JsonElement =
    call("DELETE", "/api/jira/watchers/$id")

suspend fun ApiClient.jiraPullWatcher(id: Int): JsonElement =
    call("POST", "/api/jira/watchers/$id/pull")

suspend fun ApiClient.jiraTestJql(configId: Int, jql: String): JsonElement =
    call(
        "POST",
        "/api/jira/watchers/test-jql",
        body = buildJsonObject {
            put("configId", configId)
            put("jql", jql)
        },
    )

suspend fun ApiClient.jiraListResults(
    watcherId: Int? = null,
    status: String? = null,
    limit: Int = 50,
    offset: Int = 0,
): JsonElement {
    val q = mutableListOf(
        "limit" to limit.toString(),
        "offset" to offset.toString(),
    )
    if (watcherId != null) q.add("watcherId" to watcherId.toString())
    if (!status.isNullOrBlank()) q.add("status" to status)
    return call("GET", "/api/jira/results", query = q)
}

suspend fun ApiClient.jiraDeleteResult(id: Int): JsonElement =
    call("DELETE", "/api/jira/results/$id")

suspend fun ApiClient.jiraDebugResult(id: Int, prompt: String? = null): JsonElement =
    call(
        "POST",
        "/api/jira/results/$id/debug",
        body = buildJsonObject {
            if (!prompt.isNullOrBlank()) put("prompt", prompt)
        },
    )

suspend fun ApiClient.jiraResumeResult(id: Int, prompt: String? = null): JsonElement =
    call(
        "POST",
        "/api/jira/results/$id/resume",
        body = buildJsonObject {
            if (!prompt.isNullOrBlank()) put("prompt", prompt)
        },
    )

suspend fun ApiClient.jiraCancelResult(id: Int): JsonElement =
    call("POST", "/api/jira/results/$id/cancel")

suspend fun ApiClient.jiraMarkResultRead(id: Int): JsonElement =
    call("PATCH", "/api/jira/results/$id/read")

suspend fun ApiClient.jiraGetUnreadCount(): JsonElement =
    call("GET", "/api/jira/results/unread-count")

// ===========================================================================
// 5. Schedules (/api/schedules/*)
// ===========================================================================

/** Every schedule, or with [session] the session schedules aimed at that chat under any id it had. */
suspend fun ApiClient.schedulesList(enabledOnly: Boolean = false, session: String? = null): JsonElement =
    call(
        "GET",
        "/api/schedules",
        query = listOfNotNull(session?.let { "session" to it }, ("enabledOnly" to "true").takeIf { enabledOnly }),
    )

suspend fun ApiClient.schedulesGet(id: Int): JsonElement =
    call("GET", "/api/schedules/$id")

suspend fun ApiClient.schedulesCreate(body: JsonObject): JsonElement =
    call("POST", "/api/schedules", body = body)

suspend fun ApiClient.schedulesUpdate(id: Int, body: JsonObject): JsonElement =
    call("PATCH", "/api/schedules/$id", body = body)

suspend fun ApiClient.schedulesDelete(id: Int): JsonElement =
    call("DELETE", "/api/schedules/$id")

suspend fun ApiClient.schedulesRunNow(id: Int): JsonElement =
    call("POST", "/api/schedules/$id/run-now")

suspend fun ApiClient.schedulesListRuns(id: Int, limit: Int = 20): JsonElement =
    call("GET", "/api/schedules/$id/runs", query = listOf("limit" to limit.toString()))

suspend fun ApiClient.schedulesGetLogs(id: Int, after: Int = 0): JsonElement =
    call("GET", "/api/schedules/$id/logs", query = listOf("after" to after.toString()))

suspend fun ApiClient.schedulesGetState(id: Int): JsonElement =
    call("GET", "/api/schedules/$id/state")

suspend fun ApiClient.schedulesPutState(id: Int, state: JsonElement?): JsonElement =
    call(
        "PUT",
        "/api/schedules/$id/state",
        body = buildJsonObject {
            put("state", state ?: JsonNull)
        },
    )

suspend fun ApiClient.schedulesExport(id: Int): JsonElement =
    call("GET", "/api/schedules/$id/export")

suspend fun ApiClient.schedulesImport(
    template: JsonElement,
    projectName: String,
    inputValues: JsonObject = buildJsonObject {},
    secrets: JsonObject = buildJsonObject {},
    trust: Boolean = true,
): JsonElement =
    call(
        "POST",
        "/api/schedules/import",
        body = buildJsonObject {
            put("template", template)
            put("projectName", projectName)
            put("inputValues", inputValues)
            put("secrets", secrets)
            put("trust", trust)
        },
    )
