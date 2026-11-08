package com.hienlh.ppm.state.settings

import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.long
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ===========================================================================
// 1. Tools (ai.ppm_tools — Phase 12b.1)
// ===========================================================================

data class PpmToolDef(
    val id: String,
    val label: String,
    val note: String,
)

data class PpmToolGroup(
    val title: String,
    val intro: String,
    val tools: List<PpmToolDef>,
)

val PPM_TOOL_GROUPS: List<PpmToolGroup> = listOf(
    PpmToolGroup(
        title = "Tabs",
        intro = "On the device you are chatting from.",
        tools = listOf(
            PpmToolDef(
                id = "open_file",
                label = "Open files",
                note = "Opens a file in a PPM tab, at a line when it points you at one.",
            ),
            PpmToolDef(
                id = "open_preview",
                label = "Show pages",
                note = "Opens a page, chart or report it made in a PPM tab, and reads back its errors and a screenshot. While on, Claude's claude.ai Artifact tool is off.",
            ),
            PpmToolDef(
                id = "open_url",
                label = "Show running apps",
                note = "Opens a web server running on this machine, such as a dev server, in a PPM tab. On another device it goes through a private Tailscale forward; it never makes a public link.",
            ),
        ),
    ),
    PpmToolGroup(
        title = "Terminal",
        intro = "PPM's own terminals, on the machine PPM runs on.",
        tools = listOf(
            PpmToolDef(
                id = "read_terminal",
                label = "Read terminals",
                note = "Reads what the terminals of the chat's project printed, so you need not paste an error into the chat.",
            ),
            PpmToolDef(
                id = "run_in_terminal",
                label = "Type commands",
                note = "Opens a terminal with a command typed in, for one that needs your password or your eyes. Nothing runs until you press Enter.",
            ),
        ),
    ),
    PpmToolGroup(
        title = "Database",
        intro = "On the connections with “Available to the AI chat” on. The AI never sees their passwords.",
        tools = listOf(
            PpmToolDef(
                id = "db_query",
                label = "Read databases",
                note = "Runs read-only SQL. It cannot change anything.",
            ),
            PpmToolDef(
                id = "open_query",
                label = "Open Query tabs",
                note = "Opens a Query tab holding a script it wrote. You press Run.",
            ),
            PpmToolDef(
                id = "db_execute",
                label = "Change databases",
                note = "Runs a change only after you approve it with PPM's password, once, in one transaction.",
            ),
        ),
    ),
)

/** Matches `ppmToolOn` in `src/shared/ppm-tools.ts`: every tool is on by default unless explicitly false. */
fun isPpmToolEnabled(ppmTools: Map<String, Boolean>, toolId: String): Boolean =
    ppmTools[toolId] != false

// ===========================================================================
// 2. Claude & Codex Accounts (Phase 12b.2 & 12b.3)
// ===========================================================================

data class AccountRotationSettings(
    val strategy: String = "round-robin",
    val maxRetry: Int = 3,
    val cooldownEnabled: Boolean = true,
    val activeCount: Int = 0,
)

fun parseAccountRotationSettings(json: JsonElement?): AccountRotationSettings {
    if (json == null) return AccountRotationSettings()
    return AccountRotationSettings(
        strategy = json["strategy"].string ?: "round-robin",
        maxRetry = json["maxRetry"].int ?: 3,
        cooldownEnabled = json["cooldownEnabled"].bool ?: true,
        activeCount = json["activeCount"].int ?: 0,
    )
}

data class OAuthCodeFlowStart(
    val url: String,
    val state: String,
)

fun parseOAuthCodeFlowStart(json: JsonElement?): OAuthCodeFlowStart? {
    val url = json?.get("url")?.string?.takeIf { it.isNotBlank() } ?: return null
    val state = json["state"].string?.takeIf { it.isNotBlank() } ?: return null
    return OAuthCodeFlowStart(url = url, state = state)
}

private val ANTHROPIC_CODE_REGEX = Regex("""^[A-Za-z0-9_-]{16,}(#[A-Za-z0-9_-]{8,})?$""")

/**
 * Returns true if [clipboardText] looks like an Anthropic platform OAuth code (`<code>` or `<code>#<state>`)
 * so the dialog can offer a one-tap "Paste from clipboard" button when the user returns from the browser.
 */
fun looksLikeAnthropicOAuthCode(clipboardText: String?): Boolean {
    val trimmed = clipboardText?.trim().orEmpty()
    if (trimmed.length < 16 || trimmed.length > 512) return false
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return false
    return ANTHROPIC_CODE_REGEX.matches(trimmed)
}

data class TokenTestReport(
    val accessTokenStatus: String,
    val accessTokenCode: Int? = null,
    val accessTokenError: String? = null,
    val refreshTokenStatus: String? = null,
    val refreshTokenError: String? = null,
)

fun parseTokenTestReport(json: JsonElement?): TokenTestReport {
    val access = json?.get("accessToken")
    val refresh = json?.get("refreshToken")
    return TokenTestReport(
        accessTokenStatus = access?.get("status")?.string ?: json?.get("status")?.string ?: "unknown",
        accessTokenCode = access?.get("code")?.int ?: json?.get("code")?.int,
        accessTokenError = access?.get("error")?.string ?: json?.get("error")?.string,
        refreshTokenStatus = refresh?.get("status")?.string,
        refreshTokenError = refresh?.get("error")?.string,
    )
}

/** Matches `DEFAULT_PASSWORD` in `src/web/components/settings/accounts/account-backup-format.ts`. */
const val CLAUDE_BACKUP_DEFAULT_PASSWORD = "ppm-hienlh"

fun effectiveClaudeBackupPassword(password: String): String =
    password.trim().ifEmpty { CLAUDE_BACKUP_DEFAULT_PASSWORD }

data class UsageSnapshotPoint(
    val recordedAt: String,
    val timestamp: Long = 0L,
    val fiveHour: Double,
    val sevenDay: Double,
)

private fun parseRecordedAtEpochMs(raw: String): Long? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    trimmed.toLongOrNull()?.let { return it }
    val iso = if (trimmed.contains('T')) {
        if (trimmed.endsWith("Z") || trimmed.contains('+') || trimmed.drop(10).contains('-')) trimmed else "${trimmed}Z"
    } else {
        trimmed.replace(' ', 'T') + "Z"
    }
    return runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull()
}

/**
 * Parses `LimitSnapshotRow[]` from `GET /api/accounts/:id/usage-history`
 * (`src/services/db.service.ts`: `{ recorded_at, five_hour_util, weekly_util, ... }`).
 */
fun parseUsageHistory(json: JsonElement?): List<UsageSnapshotPoint> =
    json?.array.orEmpty().mapNotNull { row ->
        val recordedAt = row["recorded_at"].string
            ?: row["timestamp"].long?.toString()
            ?: row["created_at"].long?.toString()
            ?: return@mapNotNull null
        val ts = parseRecordedAtEpochMs(recordedAt) ?: row["timestamp"].long ?: row["created_at"].long ?: 0L
        val fh = row["five_hour_util"].double ?: row["five_hour"].double ?: row["fiveHour"].double ?: 0.0
        val sd = row["weekly_util"].double ?: row["seven_day"].double ?: row["sevenDay"].double ?: 0.0
        UsageSnapshotPoint(recordedAt = recordedAt, timestamp = ts, fiveHour = fh, sevenDay = sd)
    }

/**
 * Server `DeviceLoginStatus` returned by both
 * `GET /api/codex-accounts/device-login/:id/status` and
 * `GET /api/codex-accounts/browser-login/:id/status` (`src/services/codex-account-login.ts`):
 * `{ state: "pending" } | { state: "done", account } | { state: "error", error }`.
 */
data class CodexLoginPollStatus(
    val state: String,
    val accountId: String? = null,
    val error: String? = null,
) {
    val isDone: Boolean get() = state == "done"
    val isError: Boolean get() = state == "error"
    val isPending: Boolean get() = state == "pending"
}

fun parseCodexLoginPollStatus(json: JsonElement?): CodexLoginPollStatus? {
    val state = json?.get("state")?.string?.takeIf { it.isNotBlank() } ?: return null
    return CodexLoginPollStatus(
        state = state,
        accountId = json["account"]["id"].string,
        error = json["error"].string,
    )
}

data class CodexDeviceLoginFlow(
    val id: String,
    val userCode: String,
    val verificationUrl: String,
    val status: String = "pending",
    val error: String? = null,
)

fun parseCodexDeviceLogin(json: JsonElement?): CodexDeviceLoginFlow? {
    val id = json?.get("id")?.string?.takeIf { it.isNotBlank() } ?: return null
    return CodexDeviceLoginFlow(
        id = id,
        userCode = json["userCode"].string.orEmpty(),
        verificationUrl = json["verificationUrl"].string.orEmpty(),
        status = json["state"].string ?: json["status"].string ?: "pending",
        error = json["error"].string,
    )
}

fun applyCodexDeviceLoginPoll(
    current: CodexDeviceLoginFlow,
    pollJson: JsonElement?,
): CodexDeviceLoginFlow {
    val polled = parseCodexLoginPollStatus(pollJson) ?: return current
    return current.copy(status = polled.state, error = polled.error)
}

data class CodexBrowserLoginFlow(
    val id: String,
    val authUrl: String,
    val status: String = "pending",
    val error: String? = null,
)

fun parseCodexBrowserLogin(json: JsonElement?): CodexBrowserLoginFlow? {
    val id = json?.get("id")?.string?.takeIf { it.isNotBlank() } ?: return null
    return CodexBrowserLoginFlow(
        id = id,
        authUrl = json["authUrl"].string.orEmpty(),
        status = json["state"].string ?: json["status"].string ?: "pending",
        error = json["error"].string,
    )
}

fun applyCodexBrowserLoginPoll(
    current: CodexBrowserLoginFlow,
    pollJson: JsonElement?,
): CodexBrowserLoginFlow {
    val polled = parseCodexLoginPollStatus(pollJson) ?: return current
    return current.copy(status = polled.state, error = polled.error)
}

/** Detects a localhost callback URL (`http://localhost:1455/auth/callback?code=...`) copied from the browser. */
fun looksLikeCodexLoopbackUrl(text: String?): Boolean {
    val trimmed = text?.trim().orEmpty()
    return (trimmed.startsWith("http://localhost:") || trimmed.startsWith("http://127.0.0.1:")) &&
        trimmed.contains("code=")
}

// ===========================================================================
// 3. MCP Servers (Phase 12b.4 — port of src/types/mcp.ts)
// ===========================================================================

enum class McpTransport(val wire: String) {
    STDIO("stdio"),
    HTTP("http"),
    SSE("sse"),
}

data class McpKeyValuePair(
    val key: String = "",
    val value: String = "",
)

data class McpServerRow(
    val name: String,
    val transport: String = "stdio",
    val command: String = "",
    val args: List<String> = emptyList(),
    val url: String = "",
    val env: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
)

private val MCP_NAME_REGEX = Regex("""^[a-zA-Z0-9][a-zA-Z0-9_-]*$""")
private val HTTP_URL_REGEX = Regex("""^https?://.+""")

/** Exact port of `validateMcpName` in `src/types/mcp.ts`. */
fun validateMcpName(name: String): String? {
    if (name.isEmpty() || !MCP_NAME_REGEX.matches(name)) {
        return "Name must start with a letter/digit, then alphanumeric, hyphens, or underscores"
    }
    if (name.length > 50) return "Name max 50 chars"
    return null
}

/** Exact port of `validateMcpConfig` in `src/types/mcp.ts`. */
fun validateMcpConfig(transport: String, command: String, url: String): List<String> {
    val errors = mutableListOf<String>()
    when (transport) {
        "stdio" -> {
            if (command.isBlank()) errors.add("command is required for stdio")
        }
        "http", "sse" -> {
            if (url.isBlank()) {
                errors.add("url is required for $transport")
            } else if (!HTTP_URL_REGEX.matches(url.trim())) {
                errors.add("url must be HTTP(S)")
            }
        }
        else -> errors.add("type must be stdio, http, or sse")
    }
    return errors
}

fun buildMcpConfigJson(
    transport: String,
    command: String,
    argsText: String,
    url: String,
    kvPairs: List<McpKeyValuePair>,
): JsonObject {
    val kvMap = kvPairs.mapNotNull { (k, v) ->
        val trimmedKey = k.trim()
        if (trimmedKey.isNotEmpty()) trimmedKey to v else null
    }.toMap()
    return buildJsonObject {
        put("type", transport)
        if (transport == "stdio") {
            put("command", command.trim())
            val splitArgs = argsText.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            if (splitArgs.isNotEmpty()) {
                put("args", buildJsonArray { splitArgs.forEach { add(JsonPrimitive(it)) } })
            }
            if (kvMap.isNotEmpty()) {
                put("env", buildJsonObject { kvMap.forEach { (k, v) -> put(k, v) } })
            }
        } else {
            put("url", url.trim())
            if (kvMap.isNotEmpty()) {
                put("headers", buildJsonObject { kvMap.forEach { (k, v) -> put(k, v) } })
            }
        }
    }
}

fun parseMcpServers(json: JsonElement?): List<McpServerRow> =
    json?.array.orEmpty().mapNotNull { row ->
        val name = row["name"].string?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val config = row["config"].obj ?: row.obj
        val transport = row["transport"].string ?: config?.get("type")?.string ?: "stdio"
        val command = config?.get("command")?.string.orEmpty()
        val args = config?.get("args")?.array.orEmpty().mapNotNull { it.string }
        val url = config?.get("url")?.string.orEmpty()
        val env = buildMap {
            config?.get("env")?.obj?.forEach { (k, v) -> v.string?.let { put(k, it) } }
        }
        val headers = buildMap {
            config?.get("headers")?.obj?.forEach { (k, v) -> v.string?.let { put(k, it) } }
        }
        McpServerRow(
            name = name,
            transport = transport,
            command = command,
            args = args,
            url = url,
            env = env,
            headers = headers,
        )
    }

// ===========================================================================
// 4. AI Resources (Phase 12b.5 — /api/ai-resources)
// ===========================================================================

enum class AiResourceFilter(val id: String, val label: String) {
    ALL("all", "All"),
    SKILL("skill", "Skills"),
    AGENT("agent", "Agents"),
    COMMAND("command", "Commands"),
    MCP("mcp", "MCP"),
}

data class AiResourceItem(
    val type: String, // "skill" | "agent" | "command"
    val name: String,
    val description: String = "",
    val scope: String = "project",
    val source: String = "",
    val filePath: String,
    val rootPath: String = "",
    val readOnly: Boolean = false,
    val shadowed: Boolean = false,
    val shadowedBySource: String? = null,
    val overrides: Int = 0,
)

data class AiResourcesStats(
    val active: Int = 0,
    val project: Int = 0,
    val user: Int = 0,
    val bundled: Int = 0,
    val shadowed: Int = 0,
)

data class AiResourcesOverview(
    val items: List<AiResourceItem> = emptyList(),
    val stats: AiResourcesStats = AiResourcesStats(),
)

private val AI_RESOURCE_NAME_REGEX = Regex("""^[a-zA-Z0-9][a-zA-Z0-9._-]*$""")

fun validateAiResourceName(name: String): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "Name is required"
    if (!AI_RESOURCE_NAME_REGEX.matches(trimmed)) {
        return "Name must start with a letter/digit and contain only letters, digits, dots, hyphens, or underscores"
    }
    return null
}

fun parseAiResources(json: JsonElement?): AiResourcesOverview {
    if (json == null) return AiResourcesOverview()
    val items = mutableListOf<AiResourceItem>()
    for (grp in json["groups"].array.orEmpty()) {
        val groupType = grp["type"].string ?: "skill"
        for (raw in grp["items"].array.orEmpty()) {
            val name = raw["name"].string ?: continue
            val filePath = raw["filePath"].string ?: continue
            items.add(
                AiResourceItem(
                    type = raw["type"].string ?: groupType,
                    name = name,
                    description = raw["description"].string.orEmpty(),
                    scope = raw["scope"].string ?: "project",
                    source = raw["source"].string.orEmpty(),
                    filePath = filePath,
                    rootPath = raw["rootPath"].string.orEmpty(),
                    readOnly = raw["readOnly"].bool == true,
                    shadowed = raw["shadowed"].bool == true,
                    shadowedBySource = raw["shadowedBy"]["source"].string,
                    overrides = raw["overrides"].int ?: 0,
                ),
            )
        }
    }
    val st = json["stats"]
    val stats = AiResourcesStats(
        active = st["active"].int ?: 0,
        project = st["project"].int ?: 0,
        user = st["user"].int ?: 0,
        bundled = st["bundled"].int ?: 0,
        shadowed = st["shadowed"].int ?: 0,
    )
    return AiResourcesOverview(items = items, stats = stats)
}

fun filterAiResources(
    items: List<AiResourceItem>,
    filter: AiResourceFilter,
    query: String,
): List<AiResourceItem> {
    val q = query.trim().lowercase()
    return items.filter { item ->
        val typeMatches = filter == AiResourceFilter.ALL || item.type == filter.id
        val queryMatches = q.isEmpty() ||
            item.name.lowercase().contains(q) ||
            item.description.lowercase().contains(q) ||
            item.scope.lowercase().contains(q)
        typeMatches && queryMatches
    }
}

// ===========================================================================
// 5. PPMBot / Clawbot (Phase 12b.5 — /api/settings/clawbot)
// ===========================================================================

data class ClawbotConfigState(
    val enabled: Boolean = false,
    val defaultProvider: String = "claude",
    val systemPrompt: String = "",
    val showToolCalls: Boolean = true,
    val showThinking: Boolean = false,
    val permissionMode: String = "bypassPermissions",
    val debounceMs: Int = 2000,
)

fun parseClawbotConfig(json: JsonElement?): ClawbotConfigState {
    if (json == null) return ClawbotConfigState()
    return ClawbotConfigState(
        enabled = json["enabled"].bool == true,
        defaultProvider = json["default_provider"].string ?: "claude",
        systemPrompt = json["system_prompt"].string.orEmpty(),
        showToolCalls = json["show_tool_calls"].bool ?: true,
        showThinking = json["show_thinking"].bool == true,
        permissionMode = json["permission_mode"].string ?: "bypassPermissions",
        debounceMs = json["debounce_ms"].int ?: 2000,
    )
}

fun clampDebounceMs(raw: Int): Int = raw.coerceIn(0, 30_000)

data class TelegramPairedChat(
    val chatId: String,
    val name: String,
    val username: String? = null,
)

/**
 * Response of `POST /api/settings/clawbot/telegram/connect` (`src/services/telegram-connect.service.ts:110-116`).
 */
data class TelegramConnectLink(
    val url: String,
    val expiresAt: Long,
)

fun parseTelegramConnectLink(json: JsonElement?): TelegramConnectLink? {
    val url = json?.get("url")?.string?.takeIf { it.isNotBlank() } ?: return null
    val expiresAt = json["expiresAt"].long ?: 0L
    return TelegramConnectLink(url = url, expiresAt = expiresAt)
}

/**
 * Matches `PPMBotTelegramStatus` in `src/shared/ppmbot-telegram.ts`:
 * `connect` on GET is `{ active: boolean, expiresAt: number | null, error: string | null }`.
 * `connectUrl` comes from `POST /api/settings/clawbot/telegram/connect` (`{ url, expiresAt }`)
 * and stays while `connectActive` is true and no new chat has consumed the link.
 */
data class ClawbotTelegramState(
    val configured: Boolean = false,
    val enabled: Boolean = false,
    val running: Boolean = false,
    val botUsername: String? = null,
    val sharedWithNotifications: Boolean = false,
    val chats: List<TelegramPairedChat> = emptyList(),
    val connectActive: Boolean = false,
    val connectExpiresAt: Long? = null,
    val connectUrl: String? = null,
    val connectError: String? = null,
)

fun parseClawbotTelegram(
    json: JsonElement?,
    previousConnectUrl: String? = null,
    previousChatIds: Set<String>? = null,
): ClawbotTelegramState {
    if (json == null) return ClawbotTelegramState()
    val chats = json["chats"].array.orEmpty().mapNotNull { row ->
        val chatId = row["chatId"].string ?: row["chat_id"].string ?: return@mapNotNull null
        TelegramPairedChat(
            chatId = chatId,
            name = row["name"].string ?: chatId,
            username = row["username"].string,
        )
    }
    val connectObj = json["connect"]
    val connectActive = connectObj["active"].bool == true
    val connectExpiresAt = connectObj["expiresAt"].long
    val connectError = connectObj["error"].string
    val newChatConnected = previousChatIds != null && chats.any { it.chatId !in previousChatIds }
    val preservedUrl = connectObj["url"].string ?: if (connectActive && !newChatConnected) previousConnectUrl else null
    return ClawbotTelegramState(
        configured = json["configured"].bool == true,
        enabled = json["enabled"].bool == true,
        running = json["running"].bool == true,
        botUsername = json["botUsername"].string,
        sharedWithNotifications = json["sharedWithNotifications"].bool == true,
        chats = chats,
        connectActive = connectActive,
        connectExpiresAt = connectExpiresAt,
        connectUrl = preservedUrl,
        connectError = connectError,
    )
}

data class ClawbotTaskRow(
    val id: String,
    val chatId: String,
    val projectName: String,
    val prompt: String,
    val status: String,
    val createdAt: Long,
    val completedAt: Long? = null,
)

fun parseClawbotTasks(json: JsonElement?): List<ClawbotTaskRow> =
    json?.array.orEmpty().mapNotNull { row ->
        val id = row["id"].string ?: return@mapNotNull null
        ClawbotTaskRow(
            id = id,
            chatId = row["chat_id"].string.orEmpty(),
            projectName = row["project_name"].string.orEmpty(),
            prompt = row["prompt"].string.orEmpty(),
            status = row["status"].string ?: "pending",
            createdAt = row["created_at"].long ?: 0L,
            completedAt = row["completed_at"].long,
        )
    }

data class ClawbotMemoryRow(
    val id: Long,
    val project: String,
    val content: String,
    val category: String,
    val importance: Int = 1,
)

fun parseClawbotMemories(json: JsonElement?): List<ClawbotMemoryRow> =
    json?.array.orEmpty().mapNotNull { row ->
        val id = row["id"].long ?: return@mapNotNull null
        ClawbotMemoryRow(
            id = id,
            project = row["project"].string ?: "_global",
            content = row["content"].string.orEmpty(),
            category = row["category"].string ?: "general",
            importance = row["importance"].int ?: 1,
        )
    }

// ===========================================================================
// 6. Design Settings & Design Systems (Phase 12b.5)
// ===========================================================================

const val DESIGN_INSTRUCTIONS_MAX_BYTES = 8_192

data class DesignSkillItem(
    val name: String,
    val description: String = "",
)

data class DesignProviderSkillGroup(
    val id: String,
    val name: String,
    val runtime: String,
    val available: Boolean,
    val items: List<DesignSkillItem>,
)

data class DesignSettingsState(
    val instructions: String = "",
    val maxBytes: Int = DESIGN_INSTRUCTIONS_MAX_BYTES,
    val providers: List<DesignProviderSkillGroup> = emptyList(),
)

fun parseDesignSettings(json: JsonElement?): DesignSettingsState {
    if (json == null) return DesignSettingsState()
    val providers = json["providers"].array.orEmpty().mapNotNull { p ->
        val id = p["id"].string ?: return@mapNotNull null
        val skills = p["items"].array.orEmpty().mapNotNull { s ->
            val name = s["name"].string ?: return@mapNotNull null
            DesignSkillItem(name = name, description = s["description"].string.orEmpty())
        }
        DesignProviderSkillGroup(
            id = id,
            name = p["name"].string ?: id,
            runtime = p["runtime"].string ?: "claude",
            available = p["available"].bool ?: true,
            items = skills,
        )
    }
    return DesignSettingsState(
        instructions = json["instructions"].string.orEmpty(),
        maxBytes = json["maxBytes"].int ?: DESIGN_INSTRUCTIONS_MAX_BYTES,
        providers = providers,
    )
}

data class DesignSystemRow(
    val id: String,
    val label: String,
    val platform: String = "web", // "web" | "mobile"
    val root: String = ".",
    val hasDesignSystem: Boolean = false,
)

fun parseDesignSystems(json: JsonElement?): List<DesignSystemRow> =
    json?.array.orEmpty().mapNotNull { row ->
        val id = row["id"].string ?: return@mapNotNull null
        DesignSystemRow(
            id = id,
            label = row["label"].string ?: id,
            platform = row["platform"].string ?: "web",
            root = row["root"].string ?: ".",
            hasDesignSystem = row["builtFrom"] != null || row["hasDesignSystem"].bool == true,
        )
    }

/** Counts UTF-8 bytes of [text], matching `utf8ByteLength` in `src/shared/design-skill-mentions.ts`. */
fun utf8ByteCount(text: String): Int = text.toByteArray(Charsets.UTF_8).size

private val SKILL_MENTION_REGEX = Regex("""(?:^|\s)/([a-zA-Z0-9][a-zA-Z0-9._-]*)""")

/** Extracts `/skill` mentions in appearance order without duplicates. */
fun extractDesignSkillMentions(text: String): List<String> =
    SKILL_MENTION_REGEX.findAll(text).map { it.groupValues[1] }.distinct().toList()
