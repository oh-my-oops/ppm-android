package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * A chat that runs in a git worktree of its own (Claude Code's `--worktree`,
 * `src/shared/session-worktree.ts`): the CLI creates `<project>/.claude/worktrees/<name>` on a new
 * branch `worktree-<name>`, works there, and resumes there every time after, so parallel chats
 * never edit the same checkout. The server says so in `session_worktree`.
 */
data class SessionWorktree(
    val name: String,
    /** The worktree's absolute path. */
    val path: String,
    val branch: String,
) {
    companion object {
        /** What may be a worktree name (`WORKTREE_NAME_RE`): one path segment that is also a valid branch-name part. */
        val NAME_RE = Regex("^[a-z0-9][a-z0-9-]{0,39}$")

        /** Read field by field; null without a path, as `use-chat.ts` drops one (`typeof wt.path === "string"`). */
        fun from(json: JsonElement?): SessionWorktree? {
            val path = json["path"].string ?: return null
            return SessionWorktree(json["name"].string ?: "", path, json["branch"].string ?: "")
        }

        /**
         * A fresh name for a chat's worktree (`newWorktreeName`): short, sortable by when it was
         * made, unlikely to repeat — `chat-YYMMDD-HHMM-xxxx`.
         */
        fun newName(now: LocalDateTime = LocalDateTime.now(), random: Random = Random.Default): String {
            fun pad(n: Int) = n.toString().padStart(2, '0')
            val stamp = pad(now.year % 100) + pad(now.monthValue) + pad(now.dayOfMonth) + "-" + pad(now.hour) + pad(now.minute)
            return "chat-" + stamp + "-" + random.nextInt(0x10000).toString(16).padStart(4, '0')
        }
    }
}
