package com.hienlh.ppm.state.git

import com.hienlh.ppm.client.GitBranch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

data class GitRefBadgeModel(
    val name: String,
    val syncState: String, // "synced" | "local-only" | "remote-only" | "ahead" | "behind"
    val isHead: Boolean = false,
    val isTag: Boolean = false,
) {
    val colorHex: Long
        get() = if (isTag) 0xFFD97706L else GitLogRefBadges.hashColor(name)

    val title: String
        get() = when {
            isTag -> "Tag: $name"
            syncState == "synced" -> "$name — synced with remote"
            syncState == "ahead" -> "$name — ahead of remote"
            syncState == "behind" -> "$name — behind remote"
            syncState == "remote-only" -> "$name — remote only"
            else -> "$name — local only"
        }
}

object GitLogRefBadges {
    private val BRANCH_COLORS = longArrayOf(
        0xFF2563EBL, // blue
        0xFF16A34AL, // green
        0xFFD97706L, // amber
        0xFF9333EAL, // purple
        0xFFDC2626L, // red
        0xFF0891B2L, // cyan
        0xFFC026D3L, // fuchsia
        0xFFEA580CL, // orange
    )

    private val IMAGE_EXTS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico", "avif",
    )

    private val FULL_HASH_REGEX = Regex("^[0-9a-f]{40}$", RegexOption.IGNORE_CASE)
    private val SHORT_DATE_FMT = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)

    fun hashColor(name: String): Long {
        var hash = 0
        for (i in name.indices) {
            hash = (hash * 31 + name[i].code)
        }
        val idx = abs(hash.toLong()) % BRANCH_COLORS.size
        return BRANCH_COLORS[idx.toInt()]
    }

    /**
     * Port of `buildRefBadges` from `src/web/components/git/git-ref-badge.tsx`.
     */
    fun buildRefBadges(
        commitHash: String,
        refs: List<String>,
        branches: List<GitBranch>,
        head: String,
    ): List<GitRefBadgeModel> {
        val badges = mutableListOf<GitRefBadgeModel>()
        val isHead = commitHash == head

        for (ref in refs) {
            if (ref.startsWith("tag: ")) {
                badges += GitRefBadgeModel(
                    name = ref.substring(5),
                    syncState = "synced",
                    isTag = true,
                )
            }
        }

        val branchesOnCommit = branches.filter { it.commit == commitHash }
        val localBranches = branchesOnCommit.filter { !it.remote }
        val remoteBranches = branchesOnCommit.filter { it.remote }

        val mergedRemoteNames = mutableSetOf<String>()

        for (local in localBranches) {
            val ahead = local.ahead ?: 0
            val behind = local.behind ?: 0
            val syncState = if (local.remotes.isNotEmpty() || !local.tracking.isNullOrBlank()) {
                when {
                    ahead > 0 && behind == 0 -> "ahead"
                    behind > 0 && ahead == 0 -> "behind"
                    else -> "synced"
                }
            } else {
                "local-only"
            }
            badges += GitRefBadgeModel(
                name = local.name,
                syncState = syncState,
                isHead = isHead && local.current,
            )

            for (remote in remoteBranches) {
                val stripped = remote.name.removePrefix("remotes/")
                val slashIdx = stripped.indexOf('/')
                if (slashIdx < 0) continue
                val remoteBranchName = stripped.substring(slashIdx + 1)
                if (remoteBranchName == local.name) {
                    mergedRemoteNames += remote.name
                }
            }
        }

        for (remote in remoteBranches) {
            if (remote.name in mergedRemoteNames) continue
            val displayName = remote.name.removePrefix("remotes/")
            badges += GitRefBadgeModel(
                name = displayName,
                syncState = "remote-only",
            )
        }

        return badges
    }

    /**
     * Port of `formatRelativeDate` from `src/web/components/git/git-log-panel.tsx`.
     */
    fun formatRelativeDate(dateStr: String, nowMs: Long = System.currentTimeMillis()): String {
        val epochMs = try {
            Instant.parse(dateStr).toEpochMilli()
        } catch (_: Exception) {
            return dateStr
        }
        val diff = nowMs - epochMs
        val mins = diff / 60_000L
        if (mins < 1) return "now"
        if (mins < 60) return "${mins}m"
        val hours = mins / 60
        if (hours < 24) return "${hours}h"
        val days = hours / 24
        if (days < 30) return "${days}d"
        return Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .format(SHORT_DATE_FMT)
    }

    /**
     * Port of `parseDiff` from `src/web/components/editor/diff-viewer.tsx`.
     */
    fun parseDiff(diff: String): Pair<String, String> {
        val lines = diff.split("\n")
        val originalLines = mutableListOf<String>()
        val modifiedLines = mutableListOf<String>()
        var inHunk = false

        for (line in lines) {
            if (
                line.startsWith("diff --git") ||
                line.startsWith("diff --no-index") ||
                line.startsWith("index ") ||
                line.startsWith("new file") ||
                line.startsWith("deleted file") ||
                line.startsWith("old mode") ||
                line.startsWith("new mode") ||
                line.startsWith("---") ||
                line.startsWith("+++") ||
                line.startsWith("Binary files") ||
                line.startsWith("\\ No newline")
            ) {
                continue
            }

            if (line.startsWith("@@")) {
                inHunk = true
                continue
            }
            if (!inHunk) continue

            when {
                line.startsWith("-") -> originalLines += line.substring(1)
                line.startsWith("+") -> modifiedLines += line.substring(1)
                else -> {
                    val content = if (line.startsWith(" ")) line.substring(1) else line
                    originalLines += content
                    modifiedLines += content
                }
            }
        }

        return originalLines.joinToString("\n") to modifiedLines.joinToString("\n")
    }

    fun shortRef(ref: String): String =
        if (FULL_HASH_REGEX.matches(ref)) ref.take(7) else ref

    fun canPreviewBinary(filePath: String): Boolean {
        val dot = filePath.lastIndexOf('.')
        if (dot < 0 || dot == filePath.lastIndex) return false
        val ext = filePath.substring(dot + 1).lowercase(Locale.ROOT)
        return ext in IMAGE_EXTS
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
