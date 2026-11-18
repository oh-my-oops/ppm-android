package com.hienlh.ppm.state.settings

/** `SESSION_TRACE_MIN_RETENTION_DAYS` / `SESSION_TRACE_MIN_SIZE_MB`. */
const val SESSION_TRACE_MIN_RETENTION_DAYS = 1L
const val SESSION_TRACE_MIN_SIZE_MB = 50L

/** The two limits as typed, read (`checkSessionTraceLimits`): the values, or what is wrong with them. */
sealed interface SessionTraceLimitsCheck {
    data class Ok(val retentionDays: Long, val maxSizeMb: Long) : SessionTraceLimitsCheck

    data class Problem(val error: String) : SessionTraceLimitsCheck
}

/** A whole number written in the field, or null for anything else ("", "1.5", "1e3", "abc"). */
private fun wholeNumber(raw: String): Long? = raw.trim().takeIf { Regex("""^\d+$""").matches(it) }?.toLongOrNull()

/** The same floors the route enforces, so an obvious mistake is caught before a round trip. */
fun checkSessionTraceLimits(days: String, sizeMb: String): SessionTraceLimitsCheck {
    val retention = wholeNumber(days)
    if (retention == null || retention < SESSION_TRACE_MIN_RETENTION_DAYS) {
        return SessionTraceLimitsCheck.Problem("Keep for must be a whole number of at least $SESSION_TRACE_MIN_RETENTION_DAYS day.")
    }
    val size = wholeNumber(sizeMb)
    if (size == null || size < SESSION_TRACE_MIN_SIZE_MB) {
        return SessionTraceLimitsCheck.Problem("Max size must be a whole number of at least $SESSION_TRACE_MIN_SIZE_MB MB.")
    }
    return SessionTraceLimitsCheck.Ok(retention, size)
}
