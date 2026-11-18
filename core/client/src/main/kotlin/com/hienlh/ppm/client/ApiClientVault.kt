package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.VaultCardResult
import com.hienlh.ppm.protocol.VaultItemMeta
import com.hienlh.ppm.protocol.VaultStatus
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom

/*
 * `/api/vault` (`vault-client.ts`): one function per route. Nothing here caches a value or a
 * password, and every password sent and value received is handed to [TraceRedactor.Global], so
 * neither can reach a trace entry even through an error message that happens to quote it.
 */

private const val VAULT = "/api/vault"

private fun itemPath(id: String) = "$VAULT/items/${ApiClient.escape(id)}"

/**
 * This process's handle for the Secure tier's five-minute Settings unlock: random and kept in
 * memory only, so the unlock answers to this app run and not to anything else holding PPM's
 * token. A new process starts locked.
 */
internal val VAULT_SETTINGS_HANDLE: String = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

private fun secret(value: String?) {
    TraceRedactor.Global.remember(value)
}

/** Only a non-empty password goes on the wire: an empty one would read as wrong and cost an attempt. */
private fun JsonObjectBuilder.password(password: String?) {
    put("handle", VAULT_SETTINGS_HANDLE)
    if (!password.isNullOrEmpty()) {
        secret(password)
        put("password", password)
    }
}

suspend fun ApiClient.vaultStatus(): VaultStatus = VaultStatus.from(call("GET", "$VAULT/status"))

suspend fun ApiClient.vaultItems(): List<VaultItemMeta> = VaultItemMeta.list(call("GET", "$VAULT/items"))

suspend fun ApiClient.vaultEnableTier(tier: String, password: String) {
    secret(password)
    call("POST", "$VAULT/tiers/$tier/enable", body = buildJsonObject { put("password", password) })
}

suspend fun ApiClient.vaultChangePassword(tier: String, oldPassword: String, newPassword: String) {
    secret(oldPassword)
    secret(newPassword)
    call(
        "POST",
        "$VAULT/tiers/$tier/password",
        body = buildJsonObject {
            put("oldPassword", oldPassword)
            put("newPassword", newPassword)
        },
    )
}

suspend fun ApiClient.vaultDeleteTier(tier: String, password: String) {
    secret(password)
    call("POST", "$VAULT/tiers/$tier/delete", body = buildJsonObject { put("password", password) })
}

/** Opens the Secure tier for this app's Settings for five minutes; answers when it closes again. */
suspend fun ApiClient.vaultUnlockSecure(password: String): Long {
    secret(password)
    return call(
        "POST",
        "$VAULT/tiers/secure/unlock",
        body = buildJsonObject {
            put("password", password)
            put("handle", VAULT_SETTINGS_HANDLE)
        },
    )["openUntil"].double?.toLong() ?: 0
}

/** Ends every open unlock of [tier] (Settings' and every chat's window), or of both tiers. */
suspend fun ApiClient.vaultLock(tier: String? = null) {
    call("POST", "$VAULT/lock", body = buildJsonObject { if (tier != null) put("tier", tier) })
}

suspend fun ApiClient.vaultAddItem(tier: String, name: String, kind: String, value: String, note: String?, password: String?): VaultItemMeta? {
    secret(value)
    return VaultItemMeta.from(
        call(
            "POST",
            "$VAULT/items",
            body = buildJsonObject {
                put("tier", tier)
                put("name", name)
                put("kind", kind)
                put("value", value)
                if (!note.isNullOrEmpty()) put("note", note)
                password(password)
            },
        ),
    )
}

/** [value] null keeps the current one; so does a null [note]. */
suspend fun ApiClient.vaultUpdateItem(id: String, name: String, kind: String, value: String?, note: String?, password: String?) {
    secret(value)
    call(
        "PATCH",
        itemPath(id),
        body = buildJsonObject {
            put("name", name)
            put("kind", kind)
            if (!value.isNullOrEmpty()) put("value", value)
            if (!note.isNullOrEmpty()) put("note", note)
            password(password)
        },
    )
}

suspend fun ApiClient.vaultDeleteItem(id: String, password: String?) {
    call("POST", "${itemPath(id)}/delete", body = buildJsonObject { password(password) })
}

/** An item's value, for Settings to show; it is never kept by this client. */
suspend fun ApiClient.vaultReveal(id: String, password: String?): String {
    val value = call("POST", "${itemPath(id)}/reveal", body = buildJsonObject { password(password) })["value"].string.orEmpty()
    secret(value)
    return value
}

/** Moves an item; [passwords] holds the password of each locked tier that has to open, by tier. */
suspend fun ApiClient.vaultMove(id: String, to: String, passwords: Map<String, String>) {
    call(
        "POST",
        "${itemPath(id)}/move",
        body = buildJsonObject {
            put("to", to)
            put(
                "passwords",
                buildJsonObject {
                    for ((tier, value) in passwords) {
                        if (value.isEmpty()) continue
                        secret(value)
                        put(tier, value)
                    }
                },
            )
            put("handle", VAULT_SETTINGS_HANDLE)
        },
    )
}

/** A vault card's answer (`VaultCardAnswers`), over HTTP: never through the chat socket. */
suspend fun ApiClient.answerVaultCard(requestId: String, answer: JsonObject): VaultCardResult {
    answer["password"].string?.let(::secret)
    answer["value"].string?.let(::secret)
    val result = VaultCardResult.from(call("POST", "$VAULT/cards/${ApiClient.escape(requestId)}", body = answer))
    secret(result.shownValue)
    return result
}

/** The value an AI put into a proposal, for its card to show masked; null when it sent none. */
suspend fun ApiClient.vaultCardPrefill(requestId: String): String? {
    val value = call("GET", "$VAULT/cards/${ApiClient.escape(requestId)}/prefill")["value"].string
    secret(value)
    return value
}

/** A refused vault request, read for what the UI has to say about it (`VaultFailure`). */
data class VaultFailure(
    val message: String,
    val status: Int?,
    /** Wrong passwords left before the next lock-out (a 403). */
    val attemptsLeft: Int? = null,
    /** Epoch ms until the tier takes a password again. */
    val lockedUntil: Long? = null,
    /** A 423 with no lock-out: the action needs the tier's password (the Settings unlock ended). */
    val needsPassword: Boolean = false,
) {
    /** "1 attempt left." / "Try again in 42 s." beside the server's own message (`vaultFailureDetail`). */
    fun detail(now: Long): String? {
        if (lockedUntil != null && lockedUntil > now) return "Try again in ${ceilSeconds(lockedUntil - now)} s."
        if (attemptsLeft != null) return "$attemptsLeft attempt${if (attemptsLeft == 1) "" else "s"} left."
        return null
    }

    fun text(now: Long): String = detail(now)?.let { "$message $it" } ?: message

    fun lockedOut(now: Long): Boolean = (lockedUntil ?: 0) > now

    companion object {
        fun of(error: Throwable, now: Long = System.currentTimeMillis()): VaultFailure {
            if (error !is PpmError.Server) return VaultFailure(error.message ?: error.toString(), null)
            val retryAfter = error.body["retryAfterMs"].double?.toLong()?.takeIf { it > 0 }
            return VaultFailure(
                message = error.serverMessage,
                status = error.status,
                attemptsLeft = error.body["attemptsLeft"].int,
                lockedUntil = retryAfter?.let { now + it },
                needsPassword = error.status == 423 && retryAfter == null,
            )
        }

        /** The card's own lock-out, carried on its input, before any answer was tried. */
        fun lockedOutAt(lockedUntil: Long) = VaultFailure("Too many wrong passwords.", 423, lockedUntil = lockedUntil)

        private fun ceilSeconds(ms: Long) = (ms + 999) / 1000
    }
}

/** "4:12" for a countdown (`formatCountdown`). */
fun vaultCountdown(ms: Long): String {
    val s = maxOf(0L, (ms + 999) / 1000)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
