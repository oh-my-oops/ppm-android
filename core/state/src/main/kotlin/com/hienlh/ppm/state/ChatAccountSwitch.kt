package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.AccountInfo
import com.hienlh.ppm.protocol.AccountUsageEntry
import com.hienlh.ppm.protocol.CodexAccount
import com.hienlh.ppm.protocol.LimitBucket
import com.hienlh.ppm.protocol.UsageInfo
import kotlin.math.floor
import kotlin.math.max

/** Threshold from which `SwitchAccountView` shows search and folds unusable accounts (`MANY_ACCOUNTS`). */
const val MANY_SWITCH_ACCOUNTS = 5

/** Colour band of a footer usage figure (`usageTone` in `chat-account-switch.ts`). */
enum class FooterUsageTone(val wire: String) {
    OK("ok"),
    WARN("warn"),
    ERROR("error"),
}

/** Whole percent (`Math.round(util * 100)`), or null when there is no figure (`percent`). */
fun usagePercentOrNull(util: Double?): Int? =
    util?.let { floor(it * 100.0 + 0.5).toInt() }

/** Quiet below 70%, warning 70–89%, error at 90% and above (`usageTone`). */
fun footerUsageTone(pct: Int?): FooterUsageTone = when {
    pct == null || pct < 70 -> FooterUsageTone.OK
    pct >= 90 -> FooterUsageTone.ERROR
    else -> FooterUsageTone.WARN
}

/** One account in the chat's `Switch account` list (`SwitchAccount` in `chat-account-switch.ts`). */
data class SwitchAccount(
    val id: String,
    val label: String,
    /** `"Max"`, `"Pro"`, `"Plus"`… when known. */
    val plan: String? = null,
    val fiveHourPct: Int? = null,
    val weekPct: Int? = null,
    /** When each window resets (ISO), shown under its bar; null when the provider did not say. */
    val fiveHourResetsAt: String? = null,
    val weekResetsAt: String? = null,
    /** Why this account cannot take a turn now; non-null means unselectable. */
    val refusal: String? = null,
    /** Login was refused or expired without refresh: row offers signing in again. */
    val signedOut: Boolean = false,
    /** Free limit resets the account holds and when the nearest expires; count 0 when it holds none. */
    val resets: ResetsSummary? = null,
)

/** Result of [orderSwitchAccounts]. */
data class OrderedSwitchAccounts(
    val usable: List<SwitchAccount>,
    val outOfUsage: List<SwitchAccount>,
)

/** Plan badge for a Claude account (`claudePlanLabel`). */
fun claudePlanLabel(info: AccountInfo?): String? = when {
    info?.hasClaudeMax == true -> "Max"
    info?.hasClaudePro == true -> "Pro"
    else -> null
}

/** `"ChatGPT Plus"` shortened to `"Plus"` inside a Codex-specific list (`shortCodexPlan`). */
fun shortCodexPlan(planType: String?): String? =
    codexPlanLabel(planType)?.replace(Regex("^ChatGPT\\s+", RegexOption.IGNORE_CASE), "")

private fun limitRefusal(session: LimitBucket?, weekly: LimitBucket?): String? {
    if ((usagePercentOrNull(session?.utilization) ?: 0) >= 100) return "5-hour limit reached"
    if ((usagePercentOrNull(weekly?.utilization) ?: 0) >= 100) return "Weekly limit reached"
    return null
}

/** Builds a [SwitchAccount] for Claude (`claudeSwitchAccount`). */
fun claudeSwitchAccount(
    entry: AccountUsageEntry,
    info: AccountInfo? = null,
    now: Long = System.currentTimeMillis(),
): SwitchAccount {
    var refusal: String? = null
    var signedOut = false
    val status = info?.status ?: entry.accountStatus
    if (status == "disabled") {
        refusal = "Disabled"
    } else if (info?.reauthRequired == true) {
        refusal = "Signed out · sign in again"
        signedOut = true
    } else if (info != null && !info.hasRefreshToken && (info.expiresAt ?: 0L) != 0L && info.expiresAt!! < now / 1000L) {
        refusal = "Token expired · sign in again"
        signedOut = true
    }
    if (refusal == null) {
        refusal = limitRefusal(entry.usage.session, entry.usage.weekly)
    }
    return SwitchAccount(
        id = entry.accountId,
        label = entry.accountLabel ?: info?.label ?: info?.email ?: entry.accountId.take(8),
        plan = claudePlanLabel(info),
        fiveHourPct = usagePercentOrNull(entry.usage.session?.utilization),
        weekPct = usagePercentOrNull(entry.usage.weekly?.utilization),
        fiveHourResetsAt = bucketResetsAt(entry.usage.session, now),
        weekResetsAt = bucketResetsAt(entry.usage.weekly, now),
        refusal = refusal,
        signedOut = signedOut,
        resets = claudeResetsSummary(entry.usage.limitResets),
    )
}

/** Builds a [SwitchAccount] for Codex (`codexSwitchAccount`). */
fun codexSwitchAccount(
    account: CodexAccount,
    usage: UsageInfo?,
    plan: String? = shortCodexPlan(account.planType),
    now: Long = System.currentTimeMillis(),
): SwitchAccount {
    var refusal: String? = null
    var signedOut = false
    if (account.status == "disabled") {
        refusal = "Disabled"
    } else if (account.signedOut) {
        refusal = "Signed out · sign in again"
        signedOut = account.type == "chatgpt"
    }
    if (refusal == null) {
        val sessionBucket = usage?.session ?: usage?.fiveHour?.let {
            LimitBucket(utilization = it, resetsAt = usage.fiveHourResetsAt.orEmpty(), resetsInMinutes = null, resetsInHours = null, windowHours = 5.0)
        }
        val weeklyBucket = usage?.weekly ?: usage?.sevenDay?.let {
            LimitBucket(utilization = it, resetsAt = usage.sevenDayResetsAt.orEmpty(), resetsInMinutes = null, resetsInHours = null, windowHours = 168.0)
        }
        refusal = limitRefusal(sessionBucket, weeklyBucket)
    }
    return SwitchAccount(
        id = account.id,
        label = account.label,
        plan = plan,
        fiveHourPct = usagePercentOrNull(usage?.session?.utilization ?: usage?.fiveHour),
        weekPct = usagePercentOrNull(usage?.weekly?.utilization ?: usage?.sevenDay),
        fiveHourResetsAt = bucketResetsAt(usage?.session, now),
        weekResetsAt = bucketResetsAt(usage?.weekly, now),
        refusal = refusal,
        signedOut = signedOut,
        resets = codexResetsSummary(usage?.resetCredits),
    )
}

/** Highest utilization window on an account (`highestPct`). */
fun highestPct(fiveHourPct: Int?, weekPct: Int?): Int? {
    if (fiveHourPct == null && weekPct == null) return null
    return max(fiveHourPct ?: 0, weekPct ?: 0)
}

fun highestPct(account: SwitchAccount): Int? = highestPct(account.fiveHourPct, account.weekPct)

/**
 * Current first (even if out of usage), then usable accounts ascending by [highestPct] (most
 * headroom first) with label tie-breaker, and refused accounts in `outOfUsage` (`orderSwitchAccounts`).
 */
fun orderSwitchAccounts(accounts: List<SwitchAccount>, currentId: String?): OrderedSwitchAccounts {
    val current = accounts.firstOrNull { it.id == currentId }
    val rest = accounts.filter { it !== current }
    val usableRest = rest
        .filter { it.refusal == null }
        .sortedWith(compareBy<SwitchAccount> { highestPct(it) ?: 0 }.thenBy { it.label })
    return OrderedSwitchAccounts(
        usable = if (current != null) listOf(current) + usableRest else usableRest,
        outOfUsage = rest.filter { it.refusal != null },
    )
}

/** Case-insensitive match by account label or plan (`matchesAccountQuery`). */
fun matchesAccountQuery(account: SwitchAccount, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return account.label.lowercase().contains(q) || account.plan.orEmpty().lowercase().contains(q)
}

/**
 * Resolved figures for `ChatFooterTools` (`chat-footer-tools.tsx` lines 130–149): never shows
 * another account's numbers or name when [pickedAccountId] disagrees with [usageInfo.activeAccountId].
 */
data class FooterUsageSummary(
    val currentId: String?,
    val usageMatches: Boolean,
    val fiveHourPct: Int?,
    val weekPct: Int?,
    val fiveHourResetsAt: String?,
    val weekResetsAt: String?,
    val hasUsage: Boolean,
    val accountLabel: String?,
    val plan: String?,
    val who: String,
    val ringTitle: String,
)

fun resolveFooterUsage(
    providerId: String?,
    usageInfo: UsageInfo?,
    pickedAccountId: String? = null,
    pickedAccountLabel: String? = null,
    switcherAccounts: List<SwitchAccount> = emptyList(),
    now: Long = System.currentTimeMillis(),
): FooterUsageSummary {
    val providerName = if (providerId == "codex") "Codex" else "Claude"
    val currentId = pickedAccountId ?: usageInfo?.activeAccountId
    val usageMatches = pickedAccountId == null || pickedAccountId == usageInfo?.activeAccountId
    val listed = switcherAccounts.firstOrNull { it.id == currentId }
    val fiveHourPct = if (usageMatches) usagePercentOrNull(usageInfo?.fiveHour) else listed?.fiveHourPct
    val weekPct = if (usageMatches) usagePercentOrNull(usageInfo?.sevenDay) else listed?.weekPct
    val fiveHourResetsAt = if (usageMatches) {
        bucketResetsAt(usageInfo?.session, now) ?: usageInfo?.fiveHourResetsAt?.takeIf { isoMillis(it) != null }
    } else {
        listed?.fiveHourResetsAt
    }
    val weekResetsAt = if (usageMatches) {
        bucketResetsAt(usageInfo?.weekly, now) ?: usageInfo?.sevenDayResetsAt?.takeIf { isoMillis(it) != null }
    } else {
        listed?.weekResetsAt
    }
    val hasUsage = fiveHourPct != null || weekPct != null
    val accountLabel = pickedAccountLabel
        ?: (if (usageMatches) usageInfo?.activeAccountLabel else null)
        ?: listed?.label
    val plan = listed?.plan
    val who = "${accountLabel ?: "Default account"} · $providerName${if (!plan.isNullOrEmpty()) " $plan" else ""}"
    fun windowTitle(label: String, pct: Int?, resetsAt: String?): String? {
        if (pct == null) return null
        val reset = formatResetCountdown(resetsAt, now)
        return "$label $pct%${if (reset != null) " (resets $reset)" else ""}"
    }
    val ringTitle = listOfNotNull(
        accountLabel?.takeIf(String::isNotEmpty),
        windowTitle("5h", fiveHourPct, fiveHourResetsAt),
        windowTitle("Week", weekPct, weekResetsAt),
    ).joinToString(" · ")
    return FooterUsageSummary(
        currentId = currentId,
        usageMatches = usageMatches,
        fiveHourPct = fiveHourPct,
        weekPct = weekPct,
        fiveHourResetsAt = fiveHourResetsAt,
        weekResetsAt = weekResetsAt,
        hasUsage = hasUsage,
        accountLabel = accountLabel,
        plan = plan,
        who = who,
        ringTitle = ringTitle,
    )
}
