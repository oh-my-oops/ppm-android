package com.hienlh.ppm.state.design.share

import com.hienlh.ppm.client.DesignShareChangedFiles
import com.hienlh.ppm.client.DesignShareLink
import com.hienlh.ppm.client.DesignShareVersion
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * How one share link reads in the owner's list (`design-share-link-status.ts`): its status chip,
 * its name, its two fact lines, and which actions still make sense for it. Pure; the server
 * decides the status, this only words it.
 */

enum class ShareChipTone { OK, WARN, MUTED, DANGER }

data class ShareStatusChip(val label: String, val tone: ShareChipTone)

fun shareStatusChip(link: DesignShareLink, now: Long): ShareStatusChip = when (link.status) {
    "revoked" -> ShareStatusChip("Revoked", ShareChipTone.MUTED)
    "expired" -> ShareStatusChip("Expired", ShareChipTone.MUTED)
    "needs-new-code" -> ShareStatusChip("Needs new code", ShareChipTone.WARN)
    "design-missing" -> ShareStatusChip("Design missing", ShareChipTone.DANGER)
    "signin-off" -> ShareStatusChip("Paused: sign-in is off", ShareChipTone.WARN)
    else -> {
        val left = expiresInLabel(link.expiresAt, now)
        ShareStatusChip(if (left != null && left != "Expired") left else "Active", ShareChipTone.OK)
    }
}

private val MonthDay = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val MonthDayTime = DateTimeFormatter.ofPattern("MMM d, hh:mm a", Locale.US)
private val MonthDayYearTime = DateTimeFormatter.ofPattern("MMM d, yyyy, hh:mm a", Locale.US)

private fun format(ms: Long, pattern: DateTimeFormatter, zone: ZoneId) = pattern.format(Instant.ofEpochMilli(ms).atZone(zone))

private fun isoMillis(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()

/** The name a link is listed under; an unlabelled one is named after when it was made. */
fun shareLinkTitle(link: DesignShareLink, zone: ZoneId = ZoneId.systemDefault()): String =
    link.label.ifEmpty { "Link from ${format(link.createdAt, MonthDay, zone)}" }

/** "Version from Nov 18, 08:00 AM", or that the pinned version is gone. */
fun shareVersionLine(link: DesignShareLink, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = link.snapshotCreatedAt?.let(::isoMillis) ?: return "The pinned version is no longer on disk"
    return "Version from ${format(at, MonthDayTime, zone)}"
}

/** "Never expires", or "Open until Nov 25, 2026, 08:00 AM". */
fun shareExpiryLine(link: DesignShareLink, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = link.expiresAt ?: return "Never expires"
    return "Open until ${format(at, MonthDayYearTime, zone)}"
}

enum class ShareLinkAction(val label: String, val destructive: Boolean = false) {
    EXPIRY("Change expiry"),
    RENAME("Rename"),
    REPIN("Update to latest version"),
    NEW_CODE("New code"),
    DELETE_COMMENTS("Delete this link's comments", destructive = true),
    REVOKE("Revoke", destructive = true),
    DELETE("Delete", destructive = true),
}

/**
 * Actions offered for a link, in menu order. A revoked link is final, so nothing that would
 * reopen it is offered; re-pin and new code need sign-in, as the server refuses them without.
 */
fun shareLinkActions(link: DesignShareLink, authEnabled: Boolean): List<ShareLinkAction> = buildList {
    val revoked = link.status == "revoked"
    if (!revoked) add(ShareLinkAction.EXPIRY)
    add(ShareLinkAction.RENAME)
    if (!revoked && authEnabled) {
        add(ShareLinkAction.REPIN)
        add(ShareLinkAction.NEW_CODE)
    }
    add(ShareLinkAction.DELETE_COMMENTS)
    if (!revoked) add(ShareLinkAction.REVOKE)
    add(ShareLinkAction.DELETE)
}

/** The value of the version picker that pins a snapshot taken now. */
const val CURRENT_SHARE_VERSION = "current"

private val SNAPSHOT_REASON = mapOf(
    "turn" to "after an AI turn",
    "pre-restore" to "before a restore",
    "before-edit" to "before a canvas edit",
    "manual" to "saved",
)

/** One history entry as the version picker lists it: "Nov 18, 08:00 AM, after an AI turn". */
fun shareVersionLabel(version: DesignShareVersion, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = isoMillis(version.createdAt)
    val whenText = if (at != null) format(at, MonthDayTime, zone) else version.createdAt
    return "$whenText, ${SNAPSHOT_REASON[version.reason] ?: version.reason}"
}

/** The changed-files groups a re-pin lists, the empty ones left out. */
fun shareChangedGroups(files: DesignShareChangedFiles): List<Pair<String, List<String>>> =
    listOf("Added" to files.added, "Changed" to files.changed, "Removed" to files.removed).filter { it.second.isNotEmpty() }

/** Newest first, the order a link created in the dialog takes. */
fun List<DesignShareLink>.newestFirst(): List<DesignShareLink> = sortedByDescending { it.createdAt }
