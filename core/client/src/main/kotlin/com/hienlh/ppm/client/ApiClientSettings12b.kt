package com.hienlh.ppm.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private val JSON_MEDIA = "application/json".toMediaType()

private suspend fun ApiClient.callRawText(
    method: String,
    path: String,
    body: JsonElement? = null,
): String {
    val req = request(method, path, emptyList(), body?.toString()?.toRequestBody(JSON_MEDIA))
    val response = try {
        PpmHttp.client.newCall(req).await()
    } catch (error: IOException) {
        throw PpmError.Unreachable(error.message ?: "Unreachable")
    }
    return withContext(Dispatchers.IO) {
        response.use {
            val text = try {
                it.body.string()
            } catch (error: IOException) {
                throw PpmError.Unreachable(error.message ?: "Unreachable")
            }
            if (it.code in 200..299) {
                text
            } else {
                ApiClient.unwrap(it.code, it.header("Content-Type"), text)
                text
            }
        }
    }
}

// ===========================================================================
// 1. Claude Accounts Management (/api/accounts/*)
// ===========================================================================

suspend fun ApiClient.accountsGetSettings(): JsonElement =
    call("GET", "/api/accounts/settings")

suspend fun ApiClient.accountsPutSettings(
    strategy: String? = null,
    maxRetry: Int? = null,
    cooldownEnabled: Boolean? = null,
): JsonElement {
    val body = buildJsonObject {
        if (strategy != null) put("strategy", strategy)
        if (maxRetry != null) put("maxRetry", maxRetry)
        if (cooldownEnabled != null) put("cooldownEnabled", cooldownEnabled)
    }
    return call("PUT", "/api/accounts/settings", body = body)
}

suspend fun ApiClient.accountsAddManual(apiKey: String, label: String? = null): JsonElement {
    val body = buildJsonObject {
        put("apiKey", apiKey)
        if (!label.isNullOrBlank()) put("label", label)
    }
    return call("POST", "/api/accounts", body = body)
}

suspend fun ApiClient.accountsOAuthUrl(): JsonElement =
    call("GET", "/api/accounts/oauth/url")

suspend fun ApiClient.accountsOAuthExchange(code: String, state: String): JsonElement {
    val body = buildJsonObject {
        put("code", code)
        put("state", state)
    }
    return call("POST", "/api/accounts/oauth/exchange", body = body)
}

suspend fun ApiClient.accountsDelete(id: String): JsonElement =
    call("DELETE", "/api/accounts/${ApiClient.escape(id)}")

suspend fun ApiClient.accountsRename(id: String, label: String): JsonElement =
    call("PATCH", "/api/accounts/${ApiClient.escape(id)}", body = buildJsonObject { put("label", label) })

suspend fun ApiClient.accountsUsageHistory(id: String, hours: Int = 168): JsonElement =
    call("GET", "/api/accounts/${ApiClient.escape(id)}/usage-history", query = listOf("hours" to hours.toString()))

suspend fun ApiClient.accountsVerify(id: String): JsonElement =
    call("POST", "/api/accounts/${ApiClient.escape(id)}/verify")

suspend fun ApiClient.accountsTestToken(id: String, testRefresh: Boolean = false): JsonElement =
    call(
        "POST",
        "/api/accounts/${ApiClient.escape(id)}/test-token",
        body = buildJsonObject { put("testRefresh", testRefresh) },
    )

suspend fun ApiClient.accountsTestRawToken(token: String): JsonElement =
    call("POST", "/api/accounts/test-raw-token", body = buildJsonObject { put("token", token) })

suspend fun ApiClient.accountsTestExport(
    accountIds: List<String>,
    includeRefreshToken: Boolean = false,
    ppmPassword: String? = null,
): JsonElement {
    val body = buildJsonObject {
        put("accountIds", buildJsonArray { accountIds.forEach { add(JsonPrimitive(it)) } })
        put("includeRefreshToken", includeRefreshToken)
        if (!ppmPassword.isNullOrEmpty()) put("ppmPassword", ppmPassword)
    }
    return call("POST", "/api/accounts/test-export", body = body)
}

suspend fun ApiClient.accountsExportEncrypted(
    password: String,
    ppmPassword: String? = null,
    accountIds: List<String>? = null,
    includeRefreshToken: Boolean = false,
    refreshBeforeExport: Boolean = false,
): String {
    val body = buildJsonObject {
        put("password", password)
        if (!ppmPassword.isNullOrEmpty()) put("ppmPassword", ppmPassword)
        if (accountIds != null) {
            put("accountIds", buildJsonArray { accountIds.forEach { add(JsonPrimitive(it)) } })
        }
        put("includeRefreshToken", includeRefreshToken)
        put("refreshBeforeExport", refreshBeforeExport)
    }
    return callRawText("POST", "/api/accounts/export", body = body)
}

suspend fun ApiClient.accountsImportEncrypted(data: String, password: String): JsonElement =
    call(
        "POST",
        "/api/accounts/import",
        body = buildJsonObject {
            put("data", data)
            put("password", password)
        },
    )

// ===========================================================================
// 2. Codex Accounts Management (/api/codex-accounts/*)
// ===========================================================================

suspend fun ApiClient.codexAccountsOverview(): JsonElement =
    call("GET", "/api/codex-accounts")

suspend fun ApiClient.codexSetStrategy(strategy: String): JsonElement =
    call("PUT", "/api/codex-accounts/strategy", body = buildJsonObject { put("strategy", strategy) })

suspend fun ApiClient.codexAddApiKey(apiKey: String, label: String? = null): JsonElement {
    val body = buildJsonObject {
        put("apiKey", apiKey)
        if (!label.isNullOrBlank()) put("label", label)
    }
    return call("POST", "/api/codex-accounts/api-key", body = body)
}

suspend fun ApiClient.codexDeleteAccount(id: String): JsonElement =
    call("DELETE", "/api/codex-accounts/${ApiClient.escape(id)}")

suspend fun ApiClient.codexStartDeviceLogin(label: String? = null, accountId: String? = null): JsonElement {
    val body = buildJsonObject {
        if (!label.isNullOrBlank()) put("label", label)
        if (!accountId.isNullOrBlank()) put("accountId", accountId)
    }
    return call("POST", "/api/codex-accounts/device-login", body = body)
}

suspend fun ApiClient.codexDeviceLoginStatus(id: String): JsonElement =
    call("GET", "/api/codex-accounts/device-login/${ApiClient.escape(id)}/status")

suspend fun ApiClient.codexCancelDeviceLogin(id: String): JsonElement =
    call("DELETE", "/api/codex-accounts/device-login/${ApiClient.escape(id)}")

suspend fun ApiClient.codexStartBrowserLogin(label: String? = null, accountId: String? = null): JsonElement {
    val body = buildJsonObject {
        if (!label.isNullOrBlank()) put("label", label)
        if (!accountId.isNullOrBlank()) put("accountId", accountId)
    }
    return call("POST", "/api/codex-accounts/browser-login", body = body)
}

suspend fun ApiClient.codexBrowserLoginStatus(id: String): JsonElement =
    call("GET", "/api/codex-accounts/browser-login/${ApiClient.escape(id)}/status")

suspend fun ApiClient.codexSubmitBrowserCallback(id: String, callbackUrl: String): JsonElement =
    call(
        "POST",
        "/api/codex-accounts/browser-login/${ApiClient.escape(id)}/callback",
        body = buildJsonObject { put("callbackUrl", callbackUrl) },
    )

suspend fun ApiClient.codexCancelBrowserLogin(id: String): JsonElement =
    call("DELETE", "/api/codex-accounts/browser-login/${ApiClient.escape(id)}")

suspend fun ApiClient.codexExportEncrypted(password: String, accountIds: List<String>? = null): String {
    val body = buildJsonObject {
        put("password", password)
        if (accountIds != null) {
            put("accountIds", buildJsonArray { accountIds.forEach { add(JsonPrimitive(it)) } })
        }
    }
    return callRawText("POST", "/api/codex-accounts/export", body = body)
}

suspend fun ApiClient.codexImportEncrypted(data: String, password: String): JsonElement =
    call(
        "POST",
        "/api/codex-accounts/import",
        body = buildJsonObject {
            put("data", data)
            put("password", password)
        },
    )

// ===========================================================================
// 2b. Antigravity Accounts Management (/api/antigravity-accounts/*)
// ===========================================================================

suspend fun ApiClient.antigravityAccountsOverview(): JsonElement =
    call("GET", "/api/antigravity-accounts")

suspend fun ApiClient.antigravitySetStrategy(strategy: String): JsonElement =
    call("PUT", "/api/antigravity-accounts/strategy", body = buildJsonObject { put("strategy", strategy) })

suspend fun ApiClient.antigravityDeleteAccount(id: String): JsonElement =
    call("DELETE", "/api/antigravity-accounts/${ApiClient.escape(id)}")

suspend fun ApiClient.antigravityStartLogin(label: String? = null, accountId: String? = null): JsonElement {
    val body = buildJsonObject {
        if (!label.isNullOrBlank()) put("label", label)
        if (!accountId.isNullOrBlank()) put("accountId", accountId)
    }
    return call("POST", "/api/antigravity-accounts/login", body = body)
}

suspend fun ApiClient.antigravitySubmitLoginCode(id: String, code: String): JsonElement =
    call(
        "POST",
        "/api/antigravity-accounts/login/${ApiClient.escape(id)}/callback",
        body = buildJsonObject { put("code", code) },
    )

suspend fun ApiClient.antigravityLoginStatus(id: String): JsonElement =
    call("GET", "/api/antigravity-accounts/login/${ApiClient.escape(id)}/status")

suspend fun ApiClient.antigravityCancelLogin(id: String): JsonElement =
    call("DELETE", "/api/antigravity-accounts/login/${ApiClient.escape(id)}")

// ===========================================================================
// 3. MCP Servers (/api/settings/mcp/*)
// ===========================================================================

suspend fun ApiClient.mcpListServers(): JsonElement =
    call("GET", "/api/settings/mcp")

suspend fun ApiClient.mcpGetServer(name: String): JsonElement =
    call("GET", "/api/settings/mcp/${ApiClient.escape(name)}")

suspend fun ApiClient.mcpAddServer(name: String, config: JsonObject): JsonElement =
    call(
        "POST",
        "/api/settings/mcp",
        body = buildJsonObject {
            put("name", name)
            put("config", config)
        },
    )

suspend fun ApiClient.mcpUpdateServer(name: String, config: JsonObject): JsonElement =
    call("PUT", "/api/settings/mcp/${ApiClient.escape(name)}", body = config)

suspend fun ApiClient.mcpDeleteServer(name: String): JsonElement =
    call("DELETE", "/api/settings/mcp/${ApiClient.escape(name)}")

suspend fun ApiClient.mcpImportPreview(): JsonElement =
    call("GET", "/api/settings/mcp/import/preview")

suspend fun ApiClient.mcpImportFromClaude(): JsonElement =
    call("POST", "/api/settings/mcp/import")

// ===========================================================================
// 4. AI Resources (/api/ai-resources/*)
// ===========================================================================

suspend fun ApiClient.aiResourcesList(projectPath: String = ""): JsonElement =
    call(
        "GET",
        "/api/ai-resources",
        query = if (projectPath.isNotBlank()) listOf("project" to projectPath) else emptyList(),
    )

suspend fun ApiClient.aiResourcesReadContent(path: String, projectPath: String = ""): JsonElement {
    val q = mutableListOf("path" to path)
    if (projectPath.isNotBlank()) q.add("project" to projectPath)
    return call("GET", "/api/ai-resources/content", query = q)
}

suspend fun ApiClient.aiResourcesWriteContent(path: String, content: String, projectPath: String = ""): JsonElement =
    call(
        "PUT",
        "/api/ai-resources/content",
        body = buildJsonObject {
            put("path", path)
            put("content", content)
            put("project", projectPath)
        },
    )

suspend fun ApiClient.aiResourcesCreate(
    type: String,
    scope: String,
    name: String,
    projectPath: String = "",
): JsonElement =
    call(
        "POST",
        "/api/ai-resources",
        body = buildJsonObject {
            put("type", type)
            put("scope", scope)
            put("name", name)
            put("project", projectPath)
        },
    )

suspend fun ApiClient.aiResourcesDuplicate(
    path: String,
    type: String,
    scope: String,
    name: String,
    projectPath: String = "",
): JsonElement =
    call(
        "POST",
        "/api/ai-resources/duplicate",
        body = buildJsonObject {
            put("path", path)
            put("type", type)
            put("scope", scope)
            put("name", name)
            put("project", projectPath)
        },
    )

suspend fun ApiClient.aiResourcesDelete(path: String, type: String, projectPath: String = ""): JsonElement =
    call(
        "DELETE",
        "/api/ai-resources",
        body = buildJsonObject {
            put("path", path)
            put("type", type)
            put("project", projectPath)
        },
    )

// ===========================================================================
// 5. PPMBot (/api/settings/clawbot/*)
// ===========================================================================

suspend fun ApiClient.clawbotGetConfig(): JsonElement =
    call("GET", "/api/settings/clawbot")

suspend fun ApiClient.clawbotPutConfig(patch: JsonObject): JsonElement =
    call("PUT", "/api/settings/clawbot", body = patch)

suspend fun ApiClient.clawbotGetTelegram(): JsonElement =
    call("GET", "/api/settings/clawbot/telegram")

suspend fun ApiClient.clawbotPutTelegramToken(botToken: String): JsonElement =
    call("PUT", "/api/settings/clawbot/telegram", body = buildJsonObject { put("bot_token", botToken) })

suspend fun ApiClient.clawbotStartTelegramConnect(): JsonElement =
    call("POST", "/api/settings/clawbot/telegram/connect")

suspend fun ApiClient.clawbotCancelTelegramConnect(): JsonElement =
    call("DELETE", "/api/settings/clawbot/telegram/connect")

suspend fun ApiClient.clawbotDisconnectPairedChat(chatId: String): JsonElement =
    call("DELETE", "/api/settings/clawbot/paired/${ApiClient.escape(chatId)}")

// ===========================================================================
// 6. Design Settings & Design Systems (/api/settings/design, /designs/systems)
// ===========================================================================

suspend fun ApiClient.designGetSettings(projectName: String? = null, fresh: Boolean = true): JsonElement {
    val q = mutableListOf<Pair<String, String>>()
    if (!projectName.isNullOrBlank()) q.add("project" to projectName)
    if (fresh) q.add("fresh" to "1")
    return call("GET", "/api/settings/design", query = q)
}

suspend fun ApiClient.designPutInstructions(instructions: String): JsonElement =
    call("PUT", "/api/settings/design", body = buildJsonObject { put("instructions", instructions) })

suspend fun ApiClient.designInstallSkill(): JsonElement =
    call("POST", "/api/settings/design/skill")

suspend fun ApiClient.designListSystems(projectName: String): JsonElement =
    call("GET", "/api/project/${ApiClient.escape(projectName)}/designs/systems")

suspend fun ApiClient.designCreateSystem(
    projectName: String,
    label: String,
    platform: String,
    root: String,
): JsonElement =
    call(
        "POST",
        "/api/project/${ApiClient.escape(projectName)}/designs/systems",
        body = buildJsonObject {
            put("label", label)
            put("platform", platform)
            put("root", root)
        },
    )

suspend fun ApiClient.designUpdateSystem(
    projectName: String,
    id: String,
    label: String,
    platform: String,
    root: String,
): JsonElement =
    call(
        "PATCH",
        "/api/project/${ApiClient.escape(projectName)}/designs/systems/${ApiClient.escape(id)}",
        body = buildJsonObject {
            put("label", label)
            put("platform", platform)
            put("root", root)
        },
    )

suspend fun ApiClient.designDeleteSystem(
    projectName: String,
    id: String,
    deleteFiles: Boolean = false,
): JsonElement {
    val q = mutableListOf("confirm" to id)
    if (deleteFiles) q.add("deleteFiles" to "1")
    return call(
        "DELETE",
        "/api/project/${ApiClient.escape(projectName)}/designs/systems/${ApiClient.escape(id)}",
        query = q,
    )
}
