package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ClaudeLimitResetAnswer
import com.hienlh.ppm.protocol.ClaudeLimitResetGrant
import com.hienlh.ppm.protocol.ClaudeLimitResets
import com.hienlh.ppm.protocol.ResetCredits
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/*
 * Free limit resets on an account card, for Claude (`src/shared/claude-limit-resets.ts` and
 * `claude-limit-resets-button.tsx`) and Codex (`codex-reset-credit-button.tsx`), with the parts
 * both share (`account-resets.tsx`) and the chat's summary of them (`chat-account-switch.ts`).
 * Dates are written as Chrome writes them in English, which is what the web shows.
 */

// ---------------------------------------------------------------------------------------------
// Dates (`account-resets.tsx`)
// ---------------------------------------------------------------------------------------------

private val MonthDay = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val MonthDayYear = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
private val MonthDayTime = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)
private val MonthDayYearTime = DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", Locale.US)
private val WeekdayMonthDayTime = DateTimeFormatter.ofPattern("EEE, MMM d, h:mm a", Locale.US)

private fun sameYear(at: Instant, now: Long, zone: ZoneId) = at.atZone(zone).year == Instant.ofEpochMilli(now).atZone(zone).year

/** `resetWhen`: "Dec 11, 5:00 AM", with the year when it is not this one. Null for no date. */
fun resetWhen(iso: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String? {
    val at = isoMillis(iso)?.let(Instant::ofEpochMilli) ?: return null
    return (if (sameYear(at, now, zone)) MonthDayTime else MonthDayYearTime).withZone(zone).format(at)
}

/** `resetDay`: "Dec 11", with the year when it is not this one. Null for no date. */
fun resetDay(iso: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String? {
    val at = isoMillis(iso)?.let(Instant::ofEpochMilli) ?: return null
    return (if (sameYear(at, now, zone)) MonthDay else MonthDayYear).withZone(zone).format(at)
}

/** `daysLeft`: whole days until [iso], at least 1 while it has not passed. */
fun resetDaysLeft(iso: String, now: Long = System.currentTimeMillis()): Int? {
    val at = isoMillis(iso) ?: return null
    return max(1, ceil((at - now) / 86_400_000.0).toInt())
}

/** Within this many days an expiry is drawn as a warning (`EXPIRES_SOON_DAYS`). */
const val RESET_EXPIRES_SOON_DAYS = 7

/** One line of a card's "Free resets" section (`ResetLine`): the reset, ×count when more than one, and when it lapses. */
data class ResetLine(val key: String, val title: String, val count: Int = 1, val expiresAt: String? = null)

/** The right-hand side of a [ResetLine]: "Dec 11 · 28 days" (warning within a week), or "No expiry". */
data class ResetLineExpiry(val text: String, val soon: Boolean)

fun resetLineExpiry(expiresAt: String?, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): ResetLineExpiry {
    val day = expiresAt?.let { resetDay(it, now, zone) }
    val days = expiresAt?.let { resetDaysLeft(it, now) }
    if (day == null || days == null) return ResetLineExpiry("No expiry", false)
    return ResetLineExpiry("$day · $days day${if (days == 1) "" else "s"}", days <= RESET_EXPIRES_SOON_DAYS)
}

/** `ResetRow`'s line under the title: "Expires Dec 11, 5:00 AM", or "No expiry". */
fun resetRowExpiry(expiresAt: String?, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String =
    expiresAt?.let { resetWhen(it, now, zone) }?.let { "Expires $it" } ?: "No expiry"

// ---------------------------------------------------------------------------------------------
// Claude (`claude-limit-resets.ts`)
// ---------------------------------------------------------------------------------------------

/** `claudeResetsLeft`: how many resets the account holds in all. */
fun claudeResetsLeft(r: ClaudeLimitResets?): Int = r?.grants.orEmpty().sumOf { it.resetsLeft }

private val KindName = mapOf("session" to "5-hour reset", "fable" to "Fable reset", "full" to "full reset", "weekly" to "weekly reset")

/** `claudeResetKindName`: "full reset", or "Full reset" with [capital]. */
fun claudeResetKindName(kind: String, capital: Boolean = false): String {
    val name = KindName[kind] ?: "reset"
    return if (capital) name.replaceFirstChar { it.uppercaseChar() } else name
}

private val LimitName = mapOf(
    "five_hour" to "5-hour limit",
    "seven_day" to "weekly limit",
    "seven_day_opus" to "Opus weekly limit",
    "seven_day_sonnet" to "Sonnet weekly limit",
)

/** Whether a grant would refill the limit named — Fable's give-back counts for the week. */
private fun refills(g: ClaudeLimitResetGrant, limit: String) = limit in g.clears || (limit == "seven_day" && g.givesBackWeekly)

/** `claudeResetBlockedReason`: why a grant cannot be used now, in Claude Code's words; null when it can. */
fun claudeResetBlockedReason(g: ClaudeLimitResetGrant, r: ClaudeLimitResets): String? {
    if (g.usableNow && !g.paused && g.resetsLeft > 0 && r.cooldownUntil == null) return null
    if (g.paused) return "it is paused"
    if (g.resetsLeft == 0) return "none left"
    val blockedBy = mapOf(
        "five_hour" to "blocked_five_hour",
        "seven_day" to "blocked_seven_day",
        "seven_day_opus" to "blocked_opus",
        "seven_day_sonnet" to "blocked_sonnet",
    )
    val reason = g.unavailableReason
        ?: (if (r.cooldownUntil != null) "cooldown" else null)
        ?: g.blocking.firstNotNullOfOrNull { blockedBy[it] }
        ?: (if (g.blocking.isNotEmpty()) "blocked_other" else null)
        ?: (if (r.atLimit && r.exhausted.none { refills(g, it) }) "cannot_clear" else null)
        ?: (if (g.useRequiresLimit) "needs_limit" else "unavailable")
    return when (reason) {
        "cooldown" -> "a reset was just used, try again in a minute"
        "blocked_five_hour" -> "can't be used while you're at your ${LimitName["five_hour"]}"
        "blocked_seven_day" -> "can't be used while you're at your ${LimitName["seven_day"]}"
        "blocked_opus" -> "can't be used while you're at your ${LimitName["seven_day_opus"]}"
        "blocked_sonnet" -> "can't be used while you're at your ${LimitName["seven_day_sonnet"]}"
        "blocked_other" -> "can't be used while you're at another limit it doesn't refill"
        "cannot_clear" -> "doesn't refill the limit you've reached"
        "needs_limit" -> "usable once you reach a limit it refills"
        else -> "can't be used right now"
    }
}

/** `claudeResetRefills`: "5-hour and weekly limits", "Fable and 5-hour limits" — what using the grant refills. */
fun claudeResetRefills(g: ClaudeLimitResetGrant): String {
    val names = LinkedHashSet<String>()
    if (g.givesBackWeekly) names += "Fable"
    for (c in g.clears) {
        when (c) {
            "five_hour" -> names += "5-hour"
            "seven_day" -> names += "weekly"
            "seven_day_overage_included" -> names += if (g.givesBackWeekly) "Fable" else "weekly"
            "seven_day_opus" -> names += "Opus weekly"
            "seven_day_sonnet" -> names += "Sonnet weekly"
        }
    }
    val list = names.toMutableList()
    val last = list.removeLastOrNull() ?: return "limits"
    return if (list.isNotEmpty()) "${list.joinToString(", ")} and $last limits" else "$last limit"
}

/**
 * `claudeResetEarlyWarning`: used before any limit it refills is reached, a reset throws away what
 * was left of them; null when a limit it refills is reached.
 */
fun claudeResetEarlyWarning(g: ClaudeLimitResetGrant, r: ClaudeLimitResets): String? {
    if (r.exhausted.any { refills(g, it) }) return null
    val kind = claudeResetKindName(g.kind)
    var mostLimit: String? = null
    var mostUsed = -1
    for (limit in g.clears) {
        val used = g.percentUsed[limit] ?: continue
        if (mostLimit == null || used > mostUsed) {
            mostLimit = limit
            mostUsed = used
        }
    }
    val name = mostLimit?.let { LimitName[it] }
    if (name != null && mostUsed < 100) return "You still have ${100 - mostUsed}% of your $name left. Use your $kind anyway?"
    return "You haven't reached a limit yet. Use your $kind anyway?"
}

/** `grantDescription`: what using it refills, in a sentence. */
fun claudeGrantDescription(g: ClaudeLimitResetGrant): String {
    val back = if (g.givesBackWeekly) " and gives back what Fable used of your weekly limit. It doesn't reset your whole week" else ""
    return "Refills your ${claudeResetRefills(g)}$back. Your weekly reset day stays the same."
}

/** The grants a card lists and the sheet offers: those with a reset left. */
fun claudeResetGrantsLeft(r: ClaudeLimitResets?): List<ClaudeLimitResetGrant> = r?.grants.orEmpty().filter { it.resetsLeft > 0 }

/** `cardLines`: one card line per kind and expiry; two Fable grants ending the same day read "Fable reset ×2". */
fun claudeResetCardLines(grants: List<ClaudeLimitResetGrant>): List<ResetLine> {
    val groups = LinkedHashMap<String, ResetLine>()
    for (g in grants) {
        val key = "${g.kind}|${g.endsAt.orEmpty()}"
        val line = groups[key]
        groups[key] = line?.copy(count = line.count + g.resetsLeft)
            ?: ResetLine(key, claudeResetKindName(g.kind, capital = true), g.resetsLeft, g.endsAt)
    }
    return groups.values.toList()
}

/** The confirmation's last point: how many of this kind are left and until when. */
fun claudeResetConfirmLeft(g: ClaudeLimitResetGrant, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val left = if (g.resetsLeft > 1) "${g.resetsLeft} of this kind left" else "The only one of this kind"
    val by = g.endsAt?.let { resetWhen(it, now, zone) }?.let { ", use by $it" }.orEmpty()
    return "$left$by."
}

/** `claudeResetResultMessage`: the line shown after a claim. */
fun claudeResetResultMessage(
    account: String,
    answer: ClaudeLimitResetAnswer,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val kind = answer.kind?.let { claudeResetKindName(it) } ?: "reset"
    return when (answer.result) {
        "reset" -> {
            val done = when (answer.kind) {
                "fable" -> "Fable reset used. What Fable used is back in your weekly limit"
                "session" -> "5-hour limit reset"
                "weekly" -> "Weekly limits reset"
                else -> "Limits reset"
            }
            val week = answer.weeklyResetsAt?.let(::isoMillis)
                ?.let { " Your weekly reset day stays ${WeekdayMonthDayTime.withZone(zone).format(Instant.ofEpochMilli(it))}." }
                .orEmpty()
            val left = answer.resetsLeft?.let { n -> " ${if (n == 0) "No" else "$n"} $kind${if (n == 1) "" else "s"} left." }.orEmpty()
            "$account: $done.$week$left"
        }
        "already_used" -> "$account: that $kind had already been used."
        "not_limited" -> "$account: nothing needed resetting, so the $kind was kept."
        "cooldown" -> "$account: a reset was used moments ago. Nothing was used; try again in a minute."
        "ineligible" -> "$account: Anthropic no longer offers this account limit resets. Nothing was used."
        "rate_limited" -> "$account: Anthropic is turning requests away for now. Nothing was used; try again in a minute."
        "auth_error" -> "$account: Anthropic refused the account's sign-in. Nothing was used; sign in again and retry."
        "unavailable" ->
            if (answer.reason == "reset_unconfirmed" || answer.reason == "stamp_indeterminate") {
                "$account: couldn't confirm the reset went through. If the limits are not refilled in a moment, try again."
            } else {
                "$account: couldn't reset the limits${answer.reason?.let { " (${it.replace('_', ' ')})" }.orEmpty()}. Nothing was used; try again in a moment."
            }
        else -> "$account: couldn't tell whether the reset went through. Check the limits in a moment before trying again."
    }
}

// ---------------------------------------------------------------------------------------------
// Codex (`codex-reset-credit-button.tsx`)
// ---------------------------------------------------------------------------------------------

/** One Codex credit as the card and the sheet show it; [id] is missing for an older stored reading. */
data class CodexCredit(val id: String?, val title: String?, val expiresAt: String?)

/** `creditList`: each credit, soonest first; an older stored reading has only the count and the soonest. */
fun codexCreditList(c: ResetCredits): List<CodexCredit> {
    if (c.credits.isNotEmpty()) return c.credits.map { CodexCredit(it.id, it.title, it.expiresAt) }
    return List(c.available) { i -> if (i == 0) CodexCredit(c.nextCreditId, c.title, c.nextExpiresAt) else CodexCredit(null, c.title, null) }
}

fun codexResetCardLines(credits: List<CodexCredit>): List<ResetLine> =
    credits.mapIndexed { i, c -> ResetLine(c.id ?: "$i", c.title ?: "Full reset", 1, c.expiresAt) }

/** `NOT_AT_LIMIT`: why no Codex reset is offered before a limit is reached. */
const val CODEX_RESET_NOT_AT_LIMIT =
    "Usable once a limit is reached: a reset moves the weekly reset date, so spending one earlier wastes what is left of the window."

/** What a Codex reset does, under each one in the sheet. */
const val CODEX_RESET_DETAIL =
    "Resets the 5-hour and weekly usage to 0%. The weekly reset date then counts from your next use."

// ---------------------------------------------------------------------------------------------
// The chat's Switch account (`chat-account-switch.ts`)
// ---------------------------------------------------------------------------------------------

/** Free limit resets an account holds and when the nearest expires; 0 when it holds none. */
data class ResetsSummary(val count: Int, val nextExpiresAt: String?)

private fun earliest(times: List<String?>): String? =
    times.filterNotNull().filter { isoMillis(it) != null }.minByOrNull { isoMillis(it)!! }

/** `claudeResetsSummary`. */
fun claudeResetsSummary(r: ClaudeLimitResets?): ResetsSummary {
    val grants = claudeResetGrantsLeft(r)
    return ResetsSummary(grants.sumOf { it.resetsLeft }, earliest(grants.map { it.endsAt }))
}

/** `codexResetsSummary`. */
fun codexResetsSummary(c: ResetCredits?): ResetsSummary {
    if (c == null || c.available == 0) return ResetsSummary(0, null)
    return ResetsSummary(c.available, earliest(c.credits.map { it.expiresAt } + c.nextExpiresAt))
}
