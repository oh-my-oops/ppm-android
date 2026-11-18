package com.hienlh.ppm.state.design.share

import com.hienlh.ppm.client.DesignShareLimits
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

/*
 * The expiry a share link is given (`design-share-expiry.ts`): a preset, never, or the end of a
 * chosen day in local time. The server checks the same bound against its own clock (after now, at
 * most a year ahead), so a custom day is capped to the last one whose end still falls inside it.
 */

enum class ShareExpiryPreset(val label: String) {
    ONE_DAY("1 day"),
    SEVEN_DAYS("7 days"),
    THIRTY_DAYS("30 days"),
    NEVER("Never"),
    CUSTOM("Custom"),
}

val DEFAULT_SHARE_EXPIRY = ShareExpiryPreset.SEVEN_DAYS

private const val DAY_MS = 24L * 60 * 60 * 1000

private fun ShareExpiryPreset.days(): Long? = when (this) {
    ShareExpiryPreset.ONE_DAY -> 1
    ShareExpiryPreset.SEVEN_DAYS -> 7
    ShareExpiryPreset.THIRTY_DAYS -> 30
    else -> null
}

/** A choice; [customDate] is read only for [ShareExpiryPreset.CUSTOM]. */
data class ShareExpiryChoice(val preset: ShareExpiryPreset, val customDate: LocalDate)

/** `{ ok, expiresAt }` or `{ ok: false, error }`. */
sealed interface ShareExpiryResult {
    data class Ok(val expiresAt: Long?) : ShareExpiryResult
    data class Invalid(val error: String) : ShareExpiryResult
}

fun localDateOf(ms: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

/** The last millisecond of [date] in local time. */
fun endOfLocalDay(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
    date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

/** The choice a new link starts at: 7 days, with two weeks ahead ready for Custom. */
fun defaultShareExpiry(now: Long, zone: ZoneId = ZoneId.systemDefault()): ShareExpiryChoice =
    ShareExpiryChoice(DEFAULT_SHARE_EXPIRY, localDateOf(now + 14 * DAY_MS, zone))

/** Earliest day the date picker offers: today, whose end is still ahead. */
fun minCustomDate(now: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = localDateOf(now, zone)

/** Latest day whose end is still at most a year ahead. */
fun maxCustomDate(now: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate {
    val limit = now + DesignShareLimits.MAX_EXPIRY_MS
    val sameDay = localDateOf(limit, zone)
    return if (endOfLocalDay(sameDay, zone) <= limit) sameDay else localDateOf(limit - DAY_MS, zone)
}

/** The `expiresAt` to send for a choice, or why it cannot be sent. */
fun expiryFromChoice(choice: ShareExpiryChoice, now: Long, zone: ZoneId = ZoneId.systemDefault()): ShareExpiryResult {
    if (choice.preset == ShareExpiryPreset.NEVER) return ShareExpiryResult.Ok(null)
    choice.preset.days()?.let { return ShareExpiryResult.Ok(now + it * DAY_MS) }
    val end = endOfLocalDay(choice.customDate, zone)
    if (end <= now) return ShareExpiryResult.Invalid("Pick a date that is still ahead")
    if (end > now + DesignShareLimits.MAX_EXPIRY_MS) return ShareExpiryResult.Invalid("A link can stay open for at most a year")
    return ShareExpiryResult.Ok(end)
}

/** What Change expiry starts at for a link: never, or its own day. */
fun choiceForExpiry(expiresAt: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()): ShareExpiryChoice =
    if (expiresAt == null) ShareExpiryChoice(ShareExpiryPreset.NEVER, localDateOf(now + 7 * DAY_MS, zone))
    else ShareExpiryChoice(ShareExpiryPreset.CUSTOM, localDateOf(maxOf(expiresAt, now), zone))

/** "Expires in 3 days", "Expires in 5 hours"…, or null for a link that never expires. */
fun expiresInLabel(expiresAt: Long?, now: Long): String? {
    if (expiresAt == null) return null
    val left = expiresAt - now
    if (left <= 0) return "Expired"
    // Rounded, not floored: a 7-day link read a moment after it was made still says 7 days.
    if (left < 60 * 60 * 1000) return "Expires in under an hour"
    val hours = (left / (60.0 * 60 * 1000)).roundHalfUp()
    if (hours < 24) return "Expires in $hours hour${if (hours == 1L) "" else "s"}"
    val days = (left / DAY_MS.toDouble()).roundHalfUp()
    return "Expires in $days day${if (days == 1L) "" else "s"}"
}

/** JavaScript's `Math.round`: halves go up. */
private fun Double.roundHalfUp(): Long = kotlin.math.floor(this + 0.5).roundToLong()
