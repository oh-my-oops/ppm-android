package com.hienlh.ppm.state.settings

import com.hienlh.ppm.protocol.AntigravityAccount
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
            PpmToolDef(
                id = "open_branch_review",
                label = "Open Branch Review",
                note = "Opens Branch Review on a branch it finished work on, so you review every changed file there. Changes nothing.",
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
        title = "Session history",
        intro = "Earlier chats of the chat's own project, read-only. Another project is reachable only through turn links you sent.",
        tools = listOf(
            PpmToolDef(
                id = "search_sessions",
                label = "Search chats",
                note = "Searches the titles and the prompts and answers of this project's chats, never what tools printed, and says which turn each match is in.",
            ),
            PpmToolDef(
                id = "list_session_turns",
                label = "List turns",
                note = "Lists the turns of one of this project's chats: number, time and the first line of each prompt.",
            ),
            PpmToolDef(
                id = "read_session_turns",
                label = "Read turns",
                note = "Reads chosen turns of this project's chats: prompts, answers and one line per tool call. In another project, only the turns whose links you sent it.",
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
    PpmToolGroup(
        title = "Session collaboration",
        intro = "Sessions of one project opening and messaging each other. Every session an AI creates is an ordinary one you can see, open and stop.",
        tools = listOf(
            PpmToolDef(
                id = "create_session",
                label = "Create sessions",
                note = "Opens a new session of this project to hand work to. It shows in your session list, and you can open, chat into or stop it.",
            ),
            PpmToolDef(
                id = "send_to_session",
                label = "Message sessions",
                note = "Sends a message to another session of this project, and can wait for its reply. Only an explicit message reaches another session.",
            ),
            PpmToolDef(
                id = "session_status",
                label = "Check sessions",
                note = "Reads what the linked sessions are doing: running or idle, waiting on you, and the opening of their last answer.",
            ),
            PpmToolDef(
                id = "design_screenshot",
                label = "Screenshot designs",
                note = "Renders a design's pages to pictures on this machine, with a Chrome or Edge it finds here, so the AI can look at them and a manager chat can send them to you with a card.",
            ),
        ),
    ),
    PpmToolGroup(
        title = "Schedules",
        intro = "Prompts a chat schedules into itself, and script schedules it proposes for its project behind a card. Each one is listed in Settings → Schedules, where you can turn it off or delete it.",
        tools = listOf(
            PpmToolDef(
                id = "session_schedule_create",
                label = "Schedule prompts",
                note = "Schedules a prompt into its own chat on a cron, repeating or once. It survives restarts and is delivered when the chat is idle.",
            ),
            PpmToolDef(
                id = "session_schedule_list",
                label = "List schedules",
                note = "Lists the prompts scheduled into its own chat, with their next fire.",
            ),
            PpmToolDef(
                id = "session_schedule_delete",
                label = "Delete schedules",
                note = "Deletes a prompt scheduled into its own chat. Another chat's schedules are out of its reach.",
            ),
            PpmToolDef(
                id = "session_schedule_set_enabled",
                label = "Pause and resume schedules",
                note = "Pauses any schedule of its project at once, and says which. Turns a prompt scheduled into its own chat back on at once, and any other schedule of its project only after you allow a card.",
            ),
            PpmToolDef(
                id = "session_schedule_propose_script",
                label = "Propose script schedules",
                note = "Proposes a script schedule for its project. Nothing is created until you allow a card in PPM showing the whole script, where it runs, when, and which chats it messages.",
            ),
        ),
    ),
    PpmToolGroup(
        title = "Tasks",
        intro = "Every chat, including manager chats and the PPM Assistant, in projects with Tasks on. A chat started before a change gets it at its next start.",
        tools = listOf(
            PpmToolDef("ppm_task_list", "List tasks", "Lists a project's tasks in “what to do first” order, with filters."),
            PpmToolDef("ppm_task_get", "Read tasks", "Reads one task: its fields, links, children, recent history and the check of the chat it was handed to."),
            PpmToolDef("ppm_task_project", "Read task settings", "Reads a project's statuses, members and custom fields."),
            PpmToolDef("ppm_task_create", "Create tasks", "Adds a task. It is recorded in the task's history and you can undo it."),
            PpmToolDef("ppm_task_update", "Change tasks", "Changes a task's fields, links or result, with a reason shown in its history. You can undo it."),
            PpmToolDef("ppm_task_close", "Close tasks", "Closes a task as done or dropped. You can undo it."),
            PpmToolDef("ppm_task_reorder", "Reorder tasks", "Moves a task up or down among tasks of the same urgency and importance."),
            PpmToolDef("ppm_task_undo", "Undo task changes", "Undoes a change an AI made, within 90 days, while nobody changed the same fields since."),
            PpmToolDef("ppm_task_project_update", "Change task settings", "Adds or edits a project's members, statuses and custom fields, and how often handed-off work is checked."),
            PpmToolDef("ppm_task_notify_manager", "Tell the manager now", "The PPM Assistant only: wakes a project's manager chat about task changes, after you said yes."),
        ),
    ),
    PpmToolGroup(
        title = "Vault",
        intro = "Your secret vault (Settings → Vault), for ordinary chats. Off until you turn them on here; the PPM Assistant always has them.",
        tools = listOf(
            PpmToolDef("vault_list", "List vault items", "Lists the names, kinds and tiers of your vault's items, never a value."),
            PpmToolDef("vault_read", "Read vault items", "Reads public items at once. Secure items need your password on a card; a sealed item is shown to you unless you give it to the AI."),
            PpmToolDef("vault_propose", "Propose vault items", "Asks you to save something to the vault. You pick the tier and confirm with its password."),
        ),
    ),
    PpmToolGroup(
        title = "Spec mode",
        intro = "Requirements, design and tasks you approve step by step. Thorough checks run the tests the design names, in the project folder.",
        tools = listOf(
            PpmToolDef(
                id = "spec_status",
                label = "Read the spec",
                note = "Reads where the chat's spec stands: each document, the tasks and their waves, the last test runs.",
            ),
            PpmToolDef(
                id = "spec_create",
                label = "Start specs",
                note = "Starts a spec when you ask for one in an ordinary chat, which then works in Spec mode.",
            ),
            PpmToolDef(
                id = "spec_submit",
                label = "Submit documents",
                note = "Says requirements, design or tasks are ready. PPM checks them and asks you to approve; the AI never approves.",
            ),
            PpmToolDef(
                id = "spec_open_design",
                label = "Draw screens",
                note = "For a spec with a UI: creates its PPM Design and opens it, for the AI to draw the screens you approve before the design.",
            ),
            PpmToolDef(
                id = "task_start",
                label = "Start tasks",
                note = "Starts a task of the approved plan, refused while its dependencies are open or a running task shares its files.",
            ),
            PpmToolDef(
                id = "task_done",
                label = "Finish tasks",
                note = "Finishes a task (Standard checks). PPM ticks its box in tasks.md.",
            ),
            PpmToolDef(
                id = "task_red",
                label = "Run failing tests",
                note = "Thorough checks: PPM runs the task's new tests and must see them fail before the code exists, then locks them.",
            ),
            PpmToolDef(
                id = "task_green",
                label = "Run passing tests",
                note = "Thorough checks: PPM runs the locked tests, finishes the task when they pass, then compares the whole suite with the failures it had before.",
            ),
            PpmToolDef(
                id = "suite_check",
                label = "Check the suite",
                note = "Thorough checks: runs the whole test suite again after fixing failures it reported.",
            ),
        ),
    ),
    PpmToolGroup(
        title = "Memory",
        intro = "PPM's memory store, shared by Claude, Codex and Antigravity.",
        tools = listOf(
            PpmToolDef(
                id = "memory_search",
                label = "Search memory",
                note = "Finds memories by words and meaning; answers names and one-line descriptions only.",
            ),
            PpmToolDef(
                id = "memory_read",
                label = "Read memory",
                note = "Reads one memory in full.",
            ),
            PpmToolDef(
                id = "memory_write",
                label = "Save memory",
                note = "Creates or updates a PPM memory. A memory copied from a provider cannot be changed here.",
            ),
            PpmToolDef(
                id = "memory_forget",
                label = "Forget memory",
                note = "Archives a PPM memory. The file is kept.",
            ),
        ),
    ),
)

/**
 * A feature the onboarding tour introduces (`FEATURE_INTROS` of `src/shared/feature-intros.ts`):
 * its tools stay off until the install answers Turn on or Turn off.
 */
data class PpmFeatureIntro(val id: String, val tools: List<String>)

val PPM_FEATURE_INTROS: List<PpmFeatureIntro> = listOf(
    PpmFeatureIntro(
        id = "session-tools-2026-11",
        tools = listOf("search_sessions", "list_session_turns", "read_session_turns", "create_session", "send_to_session", "session_status", "design_screenshot"),
    ),
    PpmFeatureIntro(
        id = "session-schedules-2026-11",
        tools = listOf("session_schedule_create", "session_schedule_list", "session_schedule_delete", "session_schedule_set_enabled"),
    ),
    // A question of its own: turning prompts on never agreed to code that runs on this machine.
    PpmFeatureIntro(
        id = "script-schedule-proposals-2026-11",
        tools = listOf("session_schedule_propose_script"),
    ),
    PpmFeatureIntro(
        id = "ppm-memory-2026-11",
        tools = listOf("memory_search", "memory_read", "memory_write", "memory_forget"),
    ),
)

/** `featureIntroOfTool`: the introduced feature [toolId] belongs to, if any. */
fun ppmFeatureIntroOfTool(toolId: String): PpmFeatureIntro? = PPM_FEATURE_INTROS.firstOrNull { toolId in it.tools }

/** `answerFromSwitches`: on if any of the feature's set switches is on, off if all set ones are off, null while none is set. */
fun ppmFeatureAnswerFromSwitches(ppmTools: Map<String, Boolean>, intro: PpmFeatureIntro): Boolean? {
    val set = intro.tools.mapNotNull { ppmTools[it] }
    return if (set.isEmpty()) null else set.any { it }
}

/**
 * `ppmToolOn` of `src/shared/ppm-tools.ts`: a switch the user set is that switch; a tool of an
 * introduced feature follows the feature's other switches and is off while none is set; every
 * other tool is on. (`open_file`/`open_preview` follow the older `tab_tools` switch on the
 * server, which these settings do not carry, so they read as on unless set.)
 */
fun isPpmToolEnabled(ppmTools: Map<String, Boolean>, toolId: String): Boolean {
    ppmTools[toolId]?.let { return it }
    val intro = ppmFeatureIntroOfTool(toolId)
    if (intro != null) return ppmFeatureAnswerFromSwitches(ppmTools, intro) == true
    // The vault's tools reach the user's secrets (through a card): off until the user turns them on.
    return toolId !in VAULT_TOOLS
}

/** `VAULT_TOOLS`. */
val VAULT_TOOLS = listOf("vault_list", "vault_read", "vault_propose")

/** `ai.session_collab` (`src/shared/session-collab-settings.ts`), every field resolved and clamped. */
data class SessionCollabSettings(
    val askBeforeCreate: Boolean = true,
    val maxDepth: Int = 3,
    val maxAutoMessagesPerPair: Int = 20,
    val maxRunningSpawned: Int = 5,
    val waitTimeoutMinutes: Int = 10,
) {
    fun number(key: String): Int = when (key) {
        "maxDepth" -> maxDepth
        "maxAutoMessagesPerPair" -> maxAutoMessagesPerPair
        "maxRunningSpawned" -> maxRunningSpawned
        else -> waitTimeoutMinutes
    }

    companion object {
        /** `resolveSessionCollabSettings`: a missing or mistyped field takes its default; numbers are clamped. Never throws. */
        fun resolve(stored: kotlinx.serialization.json.JsonElement?): SessionCollabSettings {
            val raw = stored as? kotlinx.serialization.json.JsonObject ?: return SessionCollabSettings()
            fun num(key: String, default: Int): Int {
                val p = raw[key] as? kotlinx.serialization.json.JsonPrimitive ?: return default
                if (p.isString) return default
                val v = p.content.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return default
                return clampSessionCollabNumber(key, v)
            }
            val ask = (raw["askBeforeCreate"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
            return SessionCollabSettings(
                askBeforeCreate = ask ?: true,
                maxDepth = num("maxDepth", 3),
                maxAutoMessagesPerPair = num("maxAutoMessagesPerPair", 20),
                maxRunningSpawned = num("maxRunningSpawned", 5),
                waitTimeoutMinutes = num("waitTimeoutMinutes", 10),
            )
        }
    }
}

/** One of the four limits as Settings → Tools shows it (`NUMBERS`). */
data class SessionCollabNumber(val key: String, val label: String, val note: String, val min: Int, val max: Int, val unit: String? = null)

val SESSION_COLLAB_NUMBERS = listOf(
    SessionCollabNumber("maxDepth", "How deep", "Sessions created by created sessions, counted from one you started.", 1, 5),
    SessionCollabNumber("maxAutoMessagesPerPair", "Messages per pair", "Sent between two sessions before you are asked. Writing in either starts the count again.", 1, 200),
    SessionCollabNumber("maxRunningSpawned", "Running at once", "AI-created sessions of one project working at the same time.", 1, 20),
    SessionCollabNumber("waitTimeoutMinutes", "Longest wait", "How long a session waits for a reply before going on.", 1, 60, "min"),
)

/** `clampSessionCollabNumber`: rounded (half up, as `Math.round`), then clamped to the setting's range. */
fun clampSessionCollabNumber(key: String, value: Double): Int {
    val limits = SESSION_COLLAB_NUMBERS.first { it.key == key }
    return kotlin.math.floor(value + 0.5).toInt().coerceIn(limits.min, limits.max)
}

/** What a typed number commits to: the clamped value, or [current] for anything that is not a number. */
fun commitSessionCollabNumber(key: String, draft: String, current: Int): Int =
    draft.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { clampSessionCollabNumber(key, it) } ?: current

/**
 * `patchPpmToolSwitches`: [current] with [toolId] set to [on], as the server stores one switch.
 * A tool of an introduced feature first writes the feature's other tools at the state they show
 * now, so flipping one never flips its siblings.
 */
fun patchPpmToolSwitches(current: Map<String, Boolean>, toolId: String, on: Boolean): Map<String, Boolean> {
    val next = current.toMutableMap()
    ppmFeatureIntroOfTool(toolId)?.let { intro ->
        for (sibling in intro.tools) next[sibling] = isPpmToolEnabled(current, sibling)
    }
    next[toolId] = on
    return next
}

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

/**
 * Server `AntigravityLoginStatus` returned by `GET /api/antigravity-accounts/login/:id/status`
 * and `POST /api/antigravity-accounts/login/:id/callback` (`src/services/antigravity-account-login.ts`):
 * `{ state: "pending" } | { state: "done", account } | { state: "error", error }`.
 */
data class AntigravityLoginPollStatus(
    val state: String,
    val account: AntigravityAccount? = null,
    val error: String? = null,
) {
    val isDone: Boolean get() = state == "done"
    val isError: Boolean get() = state == "error"
    val isPending: Boolean get() = state == "pending"
}

fun parseAntigravityLoginStatus(json: JsonElement?): AntigravityLoginPollStatus? {
    val state = json?.get("state")?.string?.takeIf { it.isNotBlank() } ?: return null
    return AntigravityLoginPollStatus(
        state = state,
        account = AntigravityAccount.from(json["account"]),
        error = json["error"].string,
    )
}

data class AntigravityLoginFlow(
    val id: String,
    val authUrl: String,
    val status: String = "pending",
    val error: String? = null,
)

fun parseAntigravityLoginStart(json: JsonElement?): AntigravityLoginFlow? {
    val id = json?.get("id")?.string?.takeIf { it.isNotBlank() } ?: return null
    val authUrl = json["authUrl"].string?.takeIf { it.isNotBlank() } ?: return null
    return AntigravityLoginFlow(
        id = id,
        authUrl = authUrl,
        status = json["state"].string ?: json["status"].string ?: "pending",
        error = json["error"].string,
    )
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

/**
 * Mirrors `src/server/routes/ai-resources.ts:18,77` (`VALID_SCOPES = new Set(["project", "user"])`):
 * when duplicating a `"bundled"` resource, target `"project"` if a project is open, else `"user"`.
 */
fun duplicateAiResourceTargetScope(itemScope: String, activeProjectPath: String): String =
    if (itemScope == "project" || itemScope == "user") {
        itemScope
    } else if (activeProjectPath.isNotBlank()) {
        "project"
    } else {
        "user"
    }

// ===========================================================================
// 5. PPMBot / Clawbot (Phase 12b.5 — /api/settings/clawbot)
// ===========================================================================

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
