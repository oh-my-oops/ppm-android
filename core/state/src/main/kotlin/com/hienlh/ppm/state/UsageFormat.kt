package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.AccountInfo
import com.hienlh.ppm.protocol.AccountUsageEntry
import com.hienlh.ppm.protocol.AntigravityAccount
import com.hienlh.ppm.protocol.CodexAccount
import com.hienlh.ppm.protocol.LimitBucket
import com.hienlh.ppm.protocol.UsageInfo
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The formatting and colour rules account usage is drawn with (account-usage-format.ts), shared
 * by the chat's usage chip and every account card so a figure reads the same in both.
 */
enum class UsageTone {
    Success,
    Warning,
    Error,

    /** No figure to judge (`text-text-subtle`). */
    Subtle,
    ;

    companion object {
        /** `pctColor` / `barColor`: ≥ 90 red, ≥ 70 amber, else green. */
        fun of(pct: Int): UsageTone = when {
            pct >= 90 -> Error
            pct >= 70 -> Warning
            else -> Success
        }
    }
}

/** `Math.round(fraction * 100)`: JavaScript rounds a half up, toward +∞. */
fun usagePercent(fraction: Double): Int = floor(fraction * 100 + 0.5).toInt()

/** A window at a rounded 100 % or more (`atCap`): what refuses a turn, as the bars show it. */
fun atCap(utilization: Double?): Boolean = usagePercent(utilization ?: 0.0) >= 100

/** An ISO time as `new Date(iso).getTime()` reads one the server sends; null where it reads NaN. */
fun isoMillis(iso: String?): Long? {
    if (iso.isNullOrEmpty()) return null
    return runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
}

/**
 * "Resets in" for a bucket (`formatResetTime`), from whichever of the three shapes the server
 * sent; null when it carries none. A time that does not parse reads as now, as NaN does there.
 */
fun formatResetTime(bucket: LimitBucket?, now: Long): String? {
    if (bucket == null) return null
    val totalMins: Long = when {
        bucket.resetsInMinutes != null -> bucket.resetsInMinutes!!
        bucket.resetsInHours != null -> floor(bucket.resetsInHours!! * 60 + 0.5).toLong()
        bucket.resetsAt.isNotEmpty() -> {
            val diff = isoMillis(bucket.resetsAt)?.let { it - now }
            if (diff != null && diff > 0) ceil(diff / 60_000.0).toLong() else 0
        }
        else -> return null
    }
    if (totalMins <= 0) return "now"
    val d = totalMins / 1440
    val h = (totalMins % 1440) / 60
    val m = totalMins % 60
    if (d > 0) return if (m > 0) "${d}d ${h}h ${m}m" else if (h > 0) "${d}d ${h}h" else "${d}d"
    if (h > 0) return if (m > 0) "${h}h ${m}m" else "${h}h"
    return "${m}m"
}

/** Time until a sign-in or token ends (`formatExpiry`), from milliseconds. */
fun formatExpiry(expiresAtMs: Long, now: Long): String {
    val diff = expiresAtMs - now
    if (diff <= 0) return "expired"
    val mins = ceil(diff / 60_000.0).toLong()
    val h = mins / 60
    val d = h / 24
    if (d > 0) return "${d}d ${h % 24}h"
    if (h > 0) return "${h}h ${mins % 60}m"
    return "${mins}m"
}

/** The relative age of a reading (`formatLastUpdated`): "just now", "42s ago", "1h 5m ago", "3d ago". */
fun formatLastUpdated(ts: Long?, now: Long): String? {
    if (ts == null || ts == 0L) return null
    val secs = floor((now - ts) / 1000.0 + 0.5).toLong()
    if (secs < 5) return "just now"
    if (secs < 60) return "${secs}s ago"
    val mins = secs / 60
    if (mins < 60) return "${mins}m ago"
    val hrs = mins / 60
    val remainMins = mins % 60
    if (hrs < 24) return if (remainMins > 0) "${hrs}h ${remainMins}m ago" else "${hrs}h ago"
    return "${hrs / 24}d ago"
}

/** A token's state as a card footer names it (`tokenStatus`). */
data class TokenStatus(val label: String, val tip: String, val tone: UsageTone)

/**
 * `tokenStatus`: an expired token with a refresh token is only a warning, the server renews it;
 * without one it is dead. A token the server already refused to renew outranks both.
 */
fun tokenStatus(info: AccountInfo?, now: Long): TokenStatus {
    if (info == null) return TokenStatus("unknown", "No account info available", UsageTone.Subtle)
    // `!info.expiresAt`: zero counts as none.
    val expiresAt = info.expiresAt?.takeIf { it != 0L } ?: return TokenStatus("key", "API key (no expiry)", UsageTone.Subtle)
    if (info.reauthRequired) {
        return TokenStatus("sign in again", "Anthropic rejected this account's refresh token. Sign in again to restore it.", UsageTone.Error)
    }
    val expired = expiresAt * 1000 < now
    if (expired && info.hasRefreshToken) return TokenStatus("expired", "Token expired but has refresh token — will auto-renew", UsageTone.Warning)
    if (expired) return TokenStatus("expired", "Token expired, no refresh token", UsageTone.Error)
    if (info.hasRefreshToken) return TokenStatus("long-lived", "OAuth token with refresh — long-lived", UsageTone.Success)
    return TokenStatus("temp", "Temporary token without refresh — will expire", UsageTone.Warning)
}

/** A token past its expiry with nothing to renew it (the card's `isExpired`). */
fun AccountInfo.isExpired(now: Long): Boolean = !hasRefreshToken && (expiresAt ?: 0L) != 0L && expiresAt!! < now / 1000

/**
 * Why a Claude account cannot take this chat, in the card's words (usage-badge.tsx's
 * `unselectableReason`): only what the server itself refuses. Near a cap is allowed; reached is not.
 */
fun claudeUnselectableReason(info: AccountInfo?, entry: AccountUsageEntry?, now: Long): String? {
    if (info != null) {
        if (info.status == "disabled") return "Disabled"
        if (info.reauthRequired) return "Sign in again to use this account"
        if (info.isExpired(now)) return "Token expired — sign in again"
    }
    val session = entry?.usage?.session
    if (atCap(session?.utilization)) return "5-hour limit reached" + resetsSuffix(session, now)
    val weekly = entry?.usage?.weekly
    if (atCap(weekly?.utilization)) return "Weekly limit reached" + resetsSuffix(weekly, now)
    return null
}

/**
 * Why a Codex account cannot take this chat (codex-usage-panel.tsx): both windows are checked,
 * as a plan may have only one and whichever it has refuses the turn.
 */
fun codexUnselectableReason(account: CodexAccount, usage: UsageInfo?, now: Long): String? {
    if (account.status == "disabled") return "Disabled"
    if (account.signedOut) return "Signed out — sign in again first"
    if (atCap(usage?.fiveHour)) return "5-hour limit reached" + resetsSuffix(usage?.session, now)
    if (atCap(usage?.sevenDay)) return "Weekly limit reached" + resetsSuffix(usage?.weekly, now)
    return null
}

private fun resetsSuffix(bucket: LimitBucket?, now: Long): String =
    if (bucket != null && bucket.resetsAt.isNotEmpty()) " — resets ${formatResetTime(bucket, now)}" else ""

/** The refusal the panel shows when a card's Use is pressed anyway. */
fun switchRefusal(reason: String): String = "Cannot switch to this account — ${reason.lowercase()}."

/** `codexPlanLabel`: Codex's subscription enum as the plan name PPM shows. */
fun codexPlanLabel(planType: String?): String? {
    val key = planType?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return when (key) {
        "free" -> "ChatGPT Free"
        "go" -> "ChatGPT Go"
        "plus" -> "ChatGPT Plus"
        "pro" -> "ChatGPT Pro"
        "team" -> "ChatGPT Team"
        "business", "self_serve_business_prolite" -> "ChatGPT Business"
        "enterprise" -> "ChatGPT Enterprise"
        "edu" -> "ChatGPT Edu"
        else -> planType
    }
}

/** Where a weekly-only Codex account stands against its weekday allowance (`DailyGuardState`). */
data class DailyGuardState(val day: Int, val cap: Double, val used: Double, val blocked: Boolean)

/**
 * `dailyGuardState`: five weekday envelopes over a 168-hour window, in 24-hour slots counted from
 * the reset and named by their UTC weekday; null for any other window, or one already reset.
 */
fun dailyGuardState(bucket: LimitBucket?, now: Long): DailyGuardState? {
    if (bucket == null || bucket.windowHours != 168.0) return null
    val resetsAt = isoMillis(bucket.resetsAt) ?: return null
    if (resetsAt <= now) return null
    val startedAt = resetsAt - 7 * DAY_MILLIS
    if (now < startedAt) return null
    val elapsedSlot = minOf(6L, (now - startedAt) / DAY_MILLIS)
    var day = 0
    for (slot in 0..elapsedSlot) {
        val weekday = Instant.ofEpochMilli(startedAt + slot * DAY_MILLIS).atZone(java.time.ZoneOffset.UTC).dayOfWeek
        if (weekday != java.time.DayOfWeek.SATURDAY && weekday != java.time.DayOfWeek.SUNDAY) day++
    }
    val cap = day / 5.0
    return DailyGuardState(day, cap, bucket.utilization, bucket.utilization >= cap)
}

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/** A limit is reached right now (`usageLimitReached`). */
fun usageLimitReached(usage: UsageInfo): Boolean = atCap(usage.session?.utilization) || atCap(usage.weekly?.utilization)

/** "Use reset" may be offered: a limit is reached and a free reset is left (`canUseResetCredit`). */
fun canUseResetCredit(usage: UsageInfo): Boolean = usageLimitReached(usage) && (usage.resetCredits?.available ?: 0) > 0

/** What a spent reset came to, as the panel reports it (`OUTCOME_MESSAGE`). */
fun resetCreditOutcome(outcome: String, label: String): String = when (outcome) {
    "reset" -> "$label: limits reset to 0%. The weekly reset date now counts from your next use."
    "nothingToReset" -> "$label: nothing needed resetting, so the reset was kept."
    "noCredit" -> "$label has no free reset left."
    "alreadyRedeemed" -> "$label: that reset had already been used."
    else -> "$label: $outcome"
}

/**
 * PPM reads Antigravity's quota every 5 minutes; a reading older than two missed sweeps
 * (`antigravity-usage-view.ts`) means neither the language server nor `agy` answered.
 */
const val STALE_ANTIGRAVITY_USAGE_MS = 11 * 60_000L

fun isAntigravityUsageStale(lastFetchedAt: String?, now: Long): Boolean {
    val at = isoMillis(lastFetchedAt) ?: return false
    return now - at > STALE_ANTIGRAVITY_USAGE_MS
}

private fun currentAntigravityBucket(bucket: LimitBucket, now: Long): LimitBucket {
    val resetAt = isoMillis(bucket.resetsAt)
    val restarted = resetAt != null && resetAt <= now
    if (!restarted && bucket.utilization > 0.0) return bucket
    return bucket.copy(
        utilization = if (restarted) 0.0 else bucket.utilization,
        resetsAt = "",
        resetsInMinutes = null,
        resetsInHours = null,
    )
}

/** `usage` with every Antigravity bucket brought up to [now], flat mirror fields included. */
fun currentAntigravityUsage(usage: UsageInfo, now: Long): UsageInfo {
    val session = usage.session?.let { currentAntigravityBucket(it, now) }
    val weekly = usage.weekly?.let { currentAntigravityBucket(it, now) }
    val scoped = usage.weeklyScoped.map { currentAntigravityBucket(it, now) }
    return usage.copy(
        fiveHour = session?.utilization ?: usage.fiveHour,
        sevenDay = weekly?.utilization ?: usage.sevenDay,
        session = session,
        weekly = weekly,
        weeklyScoped = scoped,
    )
}

/** Why an Antigravity account cannot take this chat (`antigravity-usage-panel.tsx`). */
fun antigravityUnselectableReason(account: AntigravityAccount, usage: UsageInfo?, now: Long): String? {
    if (account.status == "disabled") return "Disabled"
    val u = usage?.let { currentAntigravityUsage(it, now) }
    if (atCap(u?.fiveHour)) return "5-hour limit reached" + resetsSuffix(u?.session, now)
    if (atCap(u?.sevenDay)) return "Weekly limit reached" + resetsSuffix(u?.weekly, now)
    return null
}

private val ScopedWindowWord = Regex("\\b(weekly|hour|session)\\b", RegexOption.IGNORE_CASE)

/** `ScopedBucketRows`: keeps a label that already names its window or `·` separator, else `Weekly (label)`. */
fun scopedBucketLabel(label: String?): String {
    val text = label.orEmpty()
    return if (text.contains("·") || ScopedWindowWord.containsMatchIn(text)) text else "Weekly ($text)"
}
