package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * PPM's secret vault (`src/shared/vault.ts`): three tiers kept apart on purpose. `public` is plain
 * text the AI reads freely; `secure` and `sealed` are encrypted under passwords of their own,
 * which are never stored. Nothing here ever holds a value longer than the call that carries it.
 */

object VaultTiers {
    const val PUBLIC = "public"
    const val SECURE = "secure"
    const val SEALED = "sealed"

    val ALL = listOf(PUBLIC, SECURE, SEALED)
    val LOCKED = listOf(SECURE, SEALED)

    fun isTier(value: String?) = value in ALL

    fun isLocked(value: String?) = value == SECURE || value == SEALED

    /** `VAULT_TIER_LABELS`. */
    fun label(tier: String): String = when (tier) {
        PUBLIC -> "Public"
        SECURE -> "Secure"
        SEALED -> "Sealed"
        else -> tier
    }

    /** `VAULT_MIN_PASSWORD_LENGTH`. */
    fun minPasswordLength(tier: String): Int = if (tier == SEALED) 16 else 12
}

object VaultKinds {
    val ALL = listOf("ni_number", "address", "passport", "other")

    fun isKind(value: String?) = value in ALL

    /** `VAULT_KIND_LABELS`. */
    fun label(kind: String): String = when (kind) {
        "ni_number" -> "NI number"
        "address" -> "Address"
        "passport" -> "Passport"
        "other" -> "Other"
        else -> kind
    }
}

const val VAULT_MAX_NAME_LENGTH = 120
const val VAULT_MAX_VALUE_LENGTH = 8_000

/** How long a secure-tier window may stay open for one chat, in minutes (`VAULT_WINDOW_MINUTES`). */
val VAULT_WINDOW_MINUTES = listOf(5, 10, 15)

/** How long a sealed value shown on its card stays visible (`VAULT_SHOW_MS`). */
const val VAULT_SHOW_MS = 60_000L

/** How long Settings shows a revealed locked value (`VAULT_REVEAL_MS`). */
const val VAULT_REVEAL_MS = 30_000L

/** The `tool` of every vault card (`VAULT_CARD_APPROVAL`). */
const val VAULT_CARD_APPROVAL = "ppm:vault"

/** `VAULT_HISTORY_LIMIT`: what happens to a value once the AI was given it. */
const val VAULT_HISTORY_LIMIT =
    "After the turn PPM removes the value from the chat's history files on this machine; the open chat keeps it in memory until it is reopened, and what was sent to Anthropic or OpenAI cannot be removed."

/** An item as anyone may list it: never its value (`VaultItemMeta`). */
data class VaultItemMeta(val id: String, val tier: String, val name: String, val kind: String, val createdAt: Long, val updatedAt: Long) {
    companion object {
        fun from(json: JsonElement?): VaultItemMeta? {
            val id = json["id"].string ?: return null
            val tier = json["tier"].string?.takeIf(VaultTiers::isTier) ?: return null
            return VaultItemMeta(
                id = id,
                tier = tier,
                name = json["name"].string.orEmpty(),
                kind = json["kind"].string?.takeIf(VaultKinds::isKind) ?: "other",
                createdAt = json["createdAt"].double?.toLong() ?: 0,
                updatedAt = json["updatedAt"].double?.toLong() ?: 0,
            )
        }

        fun list(json: JsonElement?): List<VaultItemMeta> = json.array.orEmpty().mapNotNull(::from)
    }
}

/** One locked tier as `GET /api/vault/status` reports it (`VaultTierStatus`). */
data class VaultTierStatus(
    val enabled: Boolean,
    val itemCount: Int,
    val lockedUntil: Long,
    val attemptsLeft: Int,
    val settingsOpenUntil: Long,
) {
    companion object {
        val OFF = VaultTierStatus(false, 0, 0, 0, 0)

        fun from(json: JsonElement?) = VaultTierStatus(
            enabled = json["enabled"].bool == true,
            itemCount = json["itemCount"].int ?: 0,
            lockedUntil = json["lockedUntil"].double?.toLong() ?: 0,
            attemptsLeft = json["attemptsLeft"].int ?: 0,
            settingsOpenUntil = json["settingsOpenUntil"].double?.toLong() ?: 0,
        )
    }
}

data class VaultStatus(val publicCount: Int, val secure: VaultTierStatus, val sealed: VaultTierStatus) {
    fun tier(tier: String): VaultTierStatus = if (tier == VaultTiers.SEALED) sealed else secure

    companion object {
        fun from(json: JsonElement?) = VaultStatus(
            publicCount = json["publicCount"].int ?: 0,
            secure = VaultTierStatus.from(json["tiers"]["secure"]),
            sealed = VaultTierStatus.from(json["tiers"]["sealed"]),
        )
    }
}

/** Who asks on a vault card (`VaultCardRequester`). */
data class VaultCardRequester(val sessionTitle: String?, val assistant: Boolean, val fromTelegram: Boolean) {
    /** `requesterLabel`. */
    val label: String get() = if (assistant) "PPM Assistant" else sessionTitle ?: "This chat"

    companion object {
        fun from(json: JsonElement?) = VaultCardRequester(
            sessionTitle = json["sessionTitle"].string?.takeIf { it.isNotBlank() },
            assistant = json["assistant"].bool == true,
            fromTelegram = json["fromTelegram"].bool == true,
        )
    }
}

data class VaultCardItem(val id: String, val name: String, val kind: String) {
    companion object {
        fun from(json: JsonElement?): VaultCardItem? {
            val id = json["id"].string ?: return null
            val name = json["name"].string ?: return null
            val kind = json["kind"].string?.takeIf(VaultKinds::isKind) ?: return null
            return VaultCardItem(id, name, kind)
        }
    }
}

/** A vault card's input (`VaultCardInput`), checked field by field (`vaultCardInput`). */
sealed interface VaultCardInput {
    val requester: VaultCardRequester

    data class ReadSecure(
        override val requester: VaultCardRequester,
        val purpose: String,
        val items: List<VaultCardItem>,
        val requested: List<String>,
        val attemptsLeft: Int,
        val lockedUntil: Long,
    ) : VaultCardInput

    data class ReadSealed(
        override val requester: VaultCardRequester,
        val purpose: String,
        val item: VaultCardItem,
        val allowGiveToAi: Boolean,
        val attemptsLeft: Int,
        val lockedUntil: Long,
    ) : VaultCardInput

    data class Propose(
        override val requester: VaultCardRequester,
        val name: String,
        val itemKind: String,
        val suggestedTier: String,
        val hasPrefill: Boolean,
        val secureEnabled: Boolean,
        val sealedEnabled: Boolean,
    ) : VaultCardInput {
        fun tierOn(tier: String) = tier == VaultTiers.PUBLIC || (tier == VaultTiers.SECURE && secureEnabled) || (tier == VaultTiers.SEALED && sealedEnabled)
    }

    companion object {
        /** The card's input, or null for any other approval or a malformed one. */
        fun from(tool: String?, input: JsonElement?): VaultCardInput? {
            if (tool != VAULT_CARD_APPROVAL || input !is JsonObject) return null
            val who = VaultCardRequester.from(input["requester"])
            val num = { key: String -> input[key].double?.takeIf { it.isFinite() }?.toLong() ?: 0L }
            return when (input["kind"].string) {
                "read-secure" -> ReadSecure(
                    requester = who,
                    purpose = input["purpose"].string.orEmpty(),
                    items = input["items"].array.orEmpty().mapNotNull(VaultCardItem::from),
                    requested = input["requested"].array.orEmpty().mapNotNull { it.string },
                    attemptsLeft = num("attemptsLeft").toInt(),
                    lockedUntil = num("lockedUntil"),
                )
                "read-sealed" -> {
                    val item = VaultCardItem.from(input["item"]) ?: return null
                    ReadSealed(who, input["purpose"].string.orEmpty(), item, input["allowGiveToAi"].bool == true, num("attemptsLeft").toInt(), num("lockedUntil"))
                }
                "propose" -> Propose(
                    requester = who,
                    name = input["name"].string.orEmpty(),
                    itemKind = input["itemKind"].string?.takeIf(VaultKinds::isKind) ?: "other",
                    suggestedTier = input["suggestedTier"].string?.takeIf(VaultTiers::isTier) ?: VaultTiers.SECURE,
                    hasPrefill = input["hasPrefill"].bool == true,
                    secureEnabled = input["enabled"]["secure"].bool == true,
                    sealedEnabled = input["enabled"]["sealed"].bool == true,
                )
                else -> null
            }
        }
    }
}

/** The answers a vault card sends (`VaultCardAnswer`, `readSecureAnswer`, `readSealedAnswer`, `proposeAnswer`). */
object VaultCardAnswers {
    fun decline(): JsonObject = buildJsonObject { put("approved", false) }

    /** [minutes] null reads once; otherwise opens a window for this chat. Items in the card's order. */
    fun readSecure(password: String, itemIds: List<String>, minutes: Int?): JsonObject = buildJsonObject {
        put("approved", true)
        put("password", password)
        put("itemIds", kotlinx.serialization.json.JsonArray(itemIds.map(::JsonPrimitive)))
        if (minutes == null) {
            put("mode", "once")
        } else {
            put("mode", "window")
            put("minutes", minutes)
        }
    }

    /** The value goes to the AI only when the card allows it and the user ticked it. */
    fun readSealed(password: String, giveToAi: Boolean, allowGiveToAi: Boolean): JsonObject = buildJsonObject {
        put("approved", true)
        put("password", password)
        put("giveToAi", allowGiveToAi && giveToAi)
    }

    /** [value] null while the AI's prefilled value stands untouched: the server saves its own copy. */
    fun propose(tier: String, name: String, itemKind: String, value: String?, password: String): JsonObject = buildJsonObject {
        put("approved", true)
        put("tier", tier)
        put("name", name)
        put("itemKind", itemKind)
        if (value != null) put("value", value)
        if (tier != VaultTiers.PUBLIC) put("password", password)
    }
}

/** What a card's HTTP answer returns; a sealed value shown to the user travels only here. */
data class VaultCardResult(val approved: Boolean, val shownName: String?, val shownValue: String?) {
    companion object {
        fun from(json: JsonElement?) = VaultCardResult(json["approved"].bool == true, json["shown"]["name"].string, json["shown"]["value"].string)
    }
}

/** `looksSecretName`: a name that suggests a secret was put in the public tier. */
fun vaultLooksSecretName(name: String): Boolean =
    Regex(
        """\b(ni|nino|national ?insurance|passport|password|passwd|pass|pin|ssn|tax|bank|iban|sort ?code|card|cvv|secret|token|key|otp|seed|mnemonic|dob|birth)\b""",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(name) ||
        Regex("""mật ?khẩu|hộ chiếu|số tài khoản|cmnd|cccd""", RegexOption.IGNORE_CASE).containsMatchIn(name)
