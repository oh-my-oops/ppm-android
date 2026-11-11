package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.AccountInfo
import com.hienlh.ppm.protocol.AccountUsageEntry
import com.hienlh.ppm.protocol.AntigravityAccount
import com.hienlh.ppm.protocol.CodexAccount
import com.hienlh.ppm.protocol.UsageInfo
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `GET /chat/usage` (use-usage.ts): the quota of the account [sessionId] is bound to, or, for a tab
 * with no session, of the [accountId] it claimed. [refresh] makes the server read the provider
 * again first. Null when the server has no reading at all.
 */
suspend fun ApiClient.chatUsage(project: String, providerId: String, sessionId: String?, accountId: String?, refresh: Boolean): UsageInfo? {
    val query = mutableListOf("providerId" to providerId)
    if (sessionId != null) query += "session" to sessionId
    if (accountId != null) query += "accountId" to accountId
    if (refresh) query += "refresh" to "1"
    return UsageInfo.from(call("GET", "${chatPath(project)}/usage", query))
}

/**
 * `PUT /chat/sessions/:id/account {accountId}`: binds [sessionId] to another account from its next
 * message. Refused with the server's reason when that account cannot serve it.
 */
suspend fun ApiClient.setSessionAccount(project: String, sessionId: String, accountId: String) {
    call("PUT", "${chatPath(project)}/sessions/${ApiClient.escape(sessionId)}/account", body = buildJsonObject { put("accountId", accountId) })
}

/** `GET /api/accounts`: every Claude account PPM holds. */
suspend fun ApiClient.accounts(): List<AccountInfo> = call("GET", "/api/accounts").array.orEmpty().mapNotNull(AccountInfo::from)

/** `GET /api/accounts/active`: the account the rotation would pick next, if any. */
suspend fun ApiClient.activeAccount(): AccountInfo? = AccountInfo.from(call("GET", "/api/accounts/active"))

/** `GET /api/accounts/usage`: every Claude account's windows. */
suspend fun ApiClient.accountUsages(): List<AccountUsageEntry> =
    call("GET", "/api/accounts/usage").array.orEmpty().mapNotNull(AccountUsageEntry::from)

/**
 * `PATCH /api/accounts/:id {status}`: switches a Claude account on or off. Switching one back on
 * makes the server prove its token first, which can take most of a minute and fail with a
 * message worth showing.
 */
suspend fun ApiClient.setAccountStatus(id: String, status: String) {
    call("PATCH", "/api/accounts/${ApiClient.escape(id)}", body = buildJsonObject { put("status", status) })
}

/**
 * `PATCH /api/accounts/:id {label}`: gives a Claude account a name of its own (`account-rename-dialog.tsx`).
 * The server trims and caps it (80 characters) and refuses with a message worth showing.
 */
suspend fun ApiClient.renameAccount(id: String, label: String) {
    call("PATCH", "/api/accounts/${ApiClient.escape(id)}", body = buildJsonObject { put("label", label) })
}

/** `GET /api/codex-accounts`: the Codex accounts PPM manages; none means chats run on `~/.codex`. */
suspend fun ApiClient.codexAccounts(): List<CodexAccount> =
    call("GET", "/api/codex-accounts")["accounts"].array.orEmpty().mapNotNull(CodexAccount::from)

/** `GET /api/codex-accounts/usage`: each Codex account's quota, by account id. */
suspend fun ApiClient.codexUsages(): Map<String, UsageInfo> =
    call("GET", "/api/codex-accounts/usage").obj.orEmpty().mapNotNull { (id, usage) -> UsageInfo.from(usage)?.let { id to it } }.toMap()

/** `PATCH /api/codex-accounts/:id {status}`, which answers at once: there is no token to prove. */
suspend fun ApiClient.setCodexAccountStatus(id: String, status: String) {
    call("PATCH", "/api/codex-accounts/${ApiClient.escape(id)}", body = buildJsonObject { put("status", status) })
}

/** `PATCH /api/codex-accounts/:id {dailyGuardEnabled}`. */
suspend fun ApiClient.setCodexDailyGuard(id: String, enabled: Boolean) {
    call("PATCH", "/api/codex-accounts/${ApiClient.escape(id)}", body = buildJsonObject { put("dailyGuardEnabled", enabled) })
}

/**
 * `POST /api/codex-accounts/:id/reset-credit`: spends one free rate-limit reset. Answers how it
 * went — `reset`, `nothingToReset`, `noCredit` or `alreadyRedeemed` — and is refused (409) unless
 * a limit is reached right now.
 */
suspend fun ApiClient.spendCodexResetCredit(id: String): String =
    call("POST", "/api/codex-accounts/${ApiClient.escape(id)}/reset-credit")["outcome"].string.orEmpty()

/** `GET /api/antigravity-accounts`: the Antigravity accounts PPM manages (ambient `~/.gemini` + isolated logins). */
suspend fun ApiClient.antigravityAccounts(): List<AntigravityAccount> =
    call("GET", "/api/antigravity-accounts")["accounts"].array.orEmpty().mapNotNull(AntigravityAccount::from)

/** `GET /api/antigravity-accounts/usage`: each Antigravity account's quota, by account id. */
suspend fun ApiClient.antigravityUsages(): Map<String, UsageInfo> =
    call("GET", "/api/antigravity-accounts/usage").obj.orEmpty().mapNotNull { (id, usage) -> UsageInfo.from(usage)?.let { id to it } }.toMap()

/** `PATCH /api/antigravity-accounts/:id {status}`: switches an Antigravity account on or off and returns the updated account. */
suspend fun ApiClient.setAntigravityAccountStatus(id: String, status: String): AntigravityAccount? =
    AntigravityAccount.from(call("PATCH", "/api/antigravity-accounts/${ApiClient.escape(id)}", body = buildJsonObject { put("status", status) }))
