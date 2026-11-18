package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Settings → PPM Assistant as the server sends it (`src/shared/assistant-settings.ts`,
 * `assistant-persona.ts`, `assistant-limits.ts`, `assistant-notes.ts`): the Assistant's own provider,
 * model, effort, persona, instructions and MCP servers, the digest and quiet hours, the manager
 * defaults and the permission switches. MCP env and header values arrive blanked: they are secrets
 * the server never sends back.
 */

const val ASSISTANT_INSTRUCTIONS_MAX_CHARS = 8000
const val ASSISTANT_MCP_MAX_SERVERS = 20
const val PERSONA_NAME_MAX_CHARS = 32
const val PERSONA_SOUL_MAX_CHARS = 2000
const val PERSONA_MAX_ADDRESS_FORMS = 12
const val PERSONA_ADDRESS_FIELD_MAX_CHARS = 40
const val PERSONA_ADDRESS_WHEN_MAX_CHARS = 200

/** `.ppm/manager.md` and the template for it, counted in UTF-8 bytes as the server counts them. */
const val RULES_FILE_MAX_BYTES = 16 * 1024
const val NOTEBOOK_CAP_CHARS = 4000
const val NOTE_MAX_CHARS = 300

/** `ASSISTANT_EFFORTS`, with the names the picker shows. */
val ASSISTANT_EFFORTS: List<Pair<String, String>> = listOf(
    "low" to "Low", "medium" to "Medium", "high" to "High", "xhigh" to "Extra", "max" to "Max",
)

/**
 * Keys each saved by a pane of their own (`KEYS_SAVED_ELSEWHERE`): the General pane's Save leaves
 * them out, so a draft it loaded earlier never puts an old value back.
 */
val ASSISTANT_KEYS_SAVED_ELSEWHERE: List<String> = listOf(
    "digest", "quiet_hours", "timezone", "manager_default_mode", "manager_template", "restart_news",
    "auto_readonly_commands", "act_on_own",
)

data class AssistantProviderDefaults(val model: String? = null, val effort: String? = null) {
    val isEmpty: Boolean get() = model.isNullOrEmpty() && effort.isNullOrEmpty()

    fun toJson(): JsonObject = buildJsonObject {
        model?.takeIf { it.isNotEmpty() }?.let { put("model", it) }
        effort?.takeIf { it.isNotEmpty() }?.let { put("effort", it) }
    }

    companion object {
        fun from(json: JsonElement?) = AssistantProviderDefaults(
            model = json["model"].string?.takeIf { it.isNotEmpty() },
            effort = json["effort"].string?.takeIf { it.isNotEmpty() },
        )
    }
}

/** One way of addressing the user, and when to use it. */
data class AssistantAddressForm(val user: String = "", val self: String = "", val whenToUse: String = "") {
    fun toJson(): JsonObject = buildJsonObject {
        put("user", user)
        put("self", self)
        put("when", whenToUse)
    }

    companion object {
        fun from(json: JsonElement?) = AssistantAddressForm(json["user"].string.orEmpty(), json["self"].string.orEmpty(), json["when"].string.orEmpty())
    }
}

/** The Assistant's name, the ways it may address the user, and its character ("soul"). */
data class AssistantPersona(val name: String = "", val addressForms: List<AssistantAddressForm> = emptyList(), val soul: String = "") {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("address_forms", JsonArray(addressForms.map { it.toJson() }))
        put("soul", soul)
    }

    companion object {
        fun from(json: JsonElement?) = AssistantPersona(
            name = json["name"].string.orEmpty(),
            addressForms = json["address_forms"].array.orEmpty().map(AssistantAddressForm::from),
            soul = json["soul"].string.orEmpty(),
        )
    }
}

/**
 * One of the Assistant's MCP servers: a command ([STDIO]) or a URL ([HTTP]). [pairs] are its env
 * variables or headers; a saved value arrives blank, and a blank value sent back keeps it.
 */
data class AssistantMcpServer(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val transport: String,
    val command: String = "",
    val args: List<String> = emptyList(),
    val url: String = "",
    val pairs: Map<String, String> = emptyMap(),
) {
    val stdio: Boolean get() = transport == STDIO

    /** What the list shows under the name: the command line, or the URL. */
    val summary: String get() = if (stdio) (listOf(command) + args).joinToString(" ") else url

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("transport", transport)
        val values = JsonObject(pairs.mapValues { JsonPrimitive(it.value) })
        if (stdio) {
            put("command", command)
            put("args", JsonArray(args.map(::JsonPrimitive)))
            put("env", values)
        } else {
            put("url", url)
            put("headers", values)
        }
    }

    companion object {
        const val STDIO = "stdio"
        const val HTTP = "http"

        fun from(json: JsonElement?): AssistantMcpServer? {
            val name = json["name"].string ?: return null
            val transport = json["transport"].string?.takeIf { it == STDIO || it == HTTP } ?: return null
            val pairs = (if (transport == STDIO) json["env"] else json["headers"]).obj.orEmpty().mapValues { it.value.string.orEmpty() }
            return AssistantMcpServer(
                id = json["id"].string.orEmpty(),
                name = name,
                enabled = json["enabled"].bool ?: true,
                transport = transport,
                command = json["command"].string.orEmpty(),
                args = json["args"].array.orEmpty().mapNotNull { it.string },
                url = json["url"].string.orEmpty(),
                pairs = pairs,
            )
        }
    }
}

/** The morning digest: on or off, and its "HH:MM" in the saved zone. */
data class AssistantDigest(val enabled: Boolean = true, val time: String = "08:00") {
    fun toJson(): JsonObject = buildJsonObject {
        put("enabled", enabled)
        put("time", time)
    }
}

data class AssistantQuietHours(val enabled: Boolean = false, val start: String = "22:00", val end: String = "07:00") {
    fun toJson(): JsonObject = buildJsonObject {
        put("enabled", enabled)
        put("start", start)
        put("end", end)
    }
}

/** What the General pane edits and saves with its one Save. */
data class AssistantGeneralDraft(
    val defaultProvider: String?,
    val providers: Map<String, AssistantProviderDefaults>,
    val instructions: String,
    val persona: AssistantPersona,
    val mcpServers: List<AssistantMcpServer>,
)

/** The settings row; [raw] keeps every key, read or not, so a save sends back what it did not edit. */
data class AssistantSettingsView(
    val raw: JsonObject,
    val general: AssistantGeneralDraft,
    val digest: AssistantDigest,
    val quietHours: AssistantQuietHours,
    val timezone: String?,
    val managerDefaultMode: String,
    val managerTemplate: String,
    val restartNews: Boolean,
    val autoReadonlyCommands: Boolean,
    val actOnOwn: Boolean,
) {
    companion object {
        fun from(json: JsonElement?): AssistantSettingsView {
            val raw = json as? JsonObject ?: JsonObject(emptyMap())
            val digest = raw["digest"]
            val quiet = raw["quiet_hours"]
            return AssistantSettingsView(
                raw = raw,
                general = AssistantGeneralDraft(
                    defaultProvider = raw["default_provider"].string?.takeIf { it.isNotEmpty() },
                    providers = raw["providers"].obj.orEmpty().mapValues { AssistantProviderDefaults.from(it.value) }.filterValues { !it.isEmpty },
                    instructions = raw["instructions"].string.orEmpty(),
                    persona = AssistantPersona.from(raw["persona"]),
                    mcpServers = raw["mcp_servers"].array.orEmpty().mapNotNull(AssistantMcpServer::from),
                ),
                digest = AssistantDigest(digest["enabled"].bool ?: true, digest["time"].string ?: "08:00"),
                quietHours = AssistantQuietHours(quiet["enabled"].bool ?: false, quiet["start"].string ?: "22:00", quiet["end"].string ?: "07:00"),
                timezone = raw["timezone"].string?.takeIf { it.isNotEmpty() },
                managerDefaultMode = raw["manager_default_mode"].string ?: "default",
                managerTemplate = raw["manager_template"].string.orEmpty(),
                // A server older than these switches sends none: the news and read-only commands on, acting alone off.
                restartNews = raw["restart_news"].bool != false,
                autoReadonlyCommands = raw["auto_readonly_commands"].bool != false,
                actOnOwn = raw["act_on_own"].bool == true,
            )
        }
    }
}

/** `GET /api/assistant/settings`. */
data class AssistantSettingsResponse(
    val settings: AssistantSettingsView,
    /** Providers that can run an Assistant session. */
    val providers: List<Pair<String, String>>,
    val instructionsMaxChars: Int,
    val maxServers: Int,
    /** PPM's own template for new manager rules, used while `manager_template` is empty. */
    val managerTemplateBuiltIn: String,
) {
    companion object {
        fun from(json: JsonElement?) = AssistantSettingsResponse(
            settings = AssistantSettingsView.from(json["settings"]),
            providers = json["providers"].array.orEmpty().mapNotNull { p ->
                val id = p["id"].string ?: return@mapNotNull null
                id to (p["name"].string ?: id)
            },
            instructionsMaxChars = json["limits"]["instructionsMaxChars"].int ?: ASSISTANT_INSTRUCTIONS_MAX_CHARS,
            maxServers = json["limits"]["maxServers"].int ?: ASSISTANT_MCP_MAX_SERVERS,
            managerTemplateBuiltIn = json["managerTemplateBuiltIn"].string.orEmpty(),
        )
    }
}

/** What `GET /managers/:project/rules` answers. */
data class ManagerRulesView(
    val approved: String?,
    /** The file's text in force: the approved rules plus additions PPM accepted. */
    val accepted: String?,
    val proposed: String?,
    val pendingHash: String?,
    val approvedAt: Long?,
    val addedSince: List<String>,
    /** The heading each added line sits under; null above the first heading, or from an older server. */
    val addedSinceUnder: List<String?>,
    val proposedAdded: List<String>?,
    val proposedRemoved: List<String>?,
) {
    /** What the editor starts from. */
    val inForce: String get() = accepted ?: approved.orEmpty()

    companion object {
        fun from(json: JsonElement?): ManagerRulesView {
            val diff = json["proposedDiff"]?.takeIf { it !is JsonNull }
            return ManagerRulesView(
                approved = json["approved"].string,
                accepted = json["accepted"].string,
                proposed = json["proposed"].string,
                pendingHash = json["pendingHash"].string?.takeIf { it.isNotEmpty() },
                approvedAt = json["approvedAt"].long,
                addedSince = json["addedSince"].array.orEmpty().mapNotNull { it.string },
                addedSinceUnder = json["addedSinceUnder"].array.orEmpty().map { it.string },
                proposedAdded = diff?.let { d -> d["added"].array.orEmpty().mapNotNull { it.string } },
                proposedRemoved = diff?.let { d -> d["removed"].array.orEmpty().mapNotNull { it.string } },
            )
        }
    }
}

/** One notebook line (`AssistantNote`); [source] is `user` or `assistant`. */
data class AssistantNote(val id: Long, val text: String, val source: String, val unverified: Boolean, val createdAt: Long, val updatedAt: Long) {
    companion object {
        fun from(json: JsonElement?): AssistantNote? {
            val id = json["id"].long ?: return null
            return AssistantNote(
                id = id,
                text = json["text"].string.orEmpty(),
                source = json["source"].string ?: "assistant",
                unverified = json["unverified"].bool ?: false,
                createdAt = json["createdAt"].long ?: 0,
                updatedAt = json["updatedAt"].long ?: 0,
            )
        }
    }
}

/** `GET /api/assistant/notes`: the lines, and the notebook's characters used against its cap. */
data class AssistantNotesView(val notes: List<AssistantNote>, val used: Int, val cap: Int, val noteMax: Int) {
    companion object {
        fun from(json: JsonElement?) = AssistantNotesView(
            notes = json["notes"].array.orEmpty().mapNotNull(AssistantNote::from),
            used = json["used"].int ?: 0,
            cap = json["cap"].int ?: NOTEBOOK_CAP_CHARS,
            noteMax = json["noteMax"].int ?: NOTE_MAX_CHARS,
        )
    }
}

/** Something the old PPMBot remembered (`GET /telegram/legacy-memories`); [project] `_global` when none. */
data class LegacyMemory(val id: Long, val project: String, val category: String, val content: String, val createdAt: Long) {
    companion object {
        /** A malformed row is dropped rather than shown (`parseLegacyMemories`). */
        fun list(json: JsonElement?): List<LegacyMemory> = json["memories"].array.orEmpty().mapNotNull { m ->
            val id = m["id"].long ?: return@mapNotNull null
            val content = m["content"].string?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            LegacyMemory(id, m["project"].string ?: "_global", m["category"].string.orEmpty(), content, m["createdAt"].long ?: 0)
        }
    }
}
